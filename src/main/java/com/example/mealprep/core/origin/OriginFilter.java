package com.example.mealprep.core.origin;

import com.example.mealprep.auth.security.ServiceTokenAuthenticationProvider;
import com.example.mealprep.core.api.OriginHeaders;
import com.example.mealprep.core.origin.exception.ConfidenceBelowThresholdException;
import com.example.mealprep.core.origin.exception.OriginDepthExceededException;
import com.example.mealprep.core.origin.exception.OriginNotPermittedOnEndpointException;
import com.example.mealprep.core.origin.exception.OriginRateLimitExceededException;
import com.example.mealprep.core.origin.exception.OriginValidationException;
import com.example.mealprep.core.origin.internal.InMemoryTokenBucketRateLimiter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.ContentCachingRequestWrapper;

/**
 * Reads the {@code X-Origin*} headers, validates and applies origin policy (confidence floor,
 * recursion-guard, rate-limit, endpoint annotation check), authenticates Pattern-B bearer tokens,
 * and populates the request-scoped {@link OriginContext}. Per
 * tickets/core/02b-origin-tracking-foundation.md §OriginFilter and §Validation steps.
 *
 * <p><b>Ordering:</b> registered AFTER {@code SessionAuthenticationFilter} so a
 * cookie-authenticated Pattern-A request reaches the filter with {@code SecurityContextHolder}
 * already populated. The filter only mutates the security context under Pattern B (bearer token +
 * non-USER origin); under Pattern A it trusts whatever the cookie filter resolved.
 *
 * <p><b>Fail-closed:</b> any validation problem rethrows the corresponding exception so the global
 * exception handler emits a {@code ProblemDetail} response. Per the design doc's "Open Questions",
 * fail-closed was the chosen posture.
 *
 * <p><b>Body buffering:</b> AI-origin requests require the {@code confidence} field to be parsed
 * before the controller binds the body. The filter wraps the request in {@link
 * ContentCachingRequestWrapper} on AI-origin paths so the body remains re-readable downstream. The
 * wrapper is only applied when the origin requires it — plain user requests pay no extra cost.
 *
 * <p><b>Annotation check:</b> the filter resolves the controller handler via {@link
 * RequestMappingHandlerMapping} and, if {@code reject-origin-on-non-annotated-controller} is true,
 * rejects non-USER origin on a handler not annotated {@link OriginAware}. The lookup uses {@link
 * ObjectProvider} so the filter still wires when the mapping isn't yet a bean (e.g. some test
 * slices).
 */
public class OriginFilter extends OncePerRequestFilter {

  private static final Logger log = LoggerFactory.getLogger(OriginFilter.class);

  /**
   * Recursion-guard ceiling. Per design/origin-tracking-pattern.md §Recursion guard, "At depth ≥ 3,
   * reject with 422 and log loudly." Concretely: depths 0, 1, 2, 3 all pass; 4+ rejects. Kept as a
   * filter-local constant rather than a property to avoid contributors lowering the bar (3 is the
   * design-doc value).
   */
  static final int MAX_DEPTH = 3;

  private final OriginContext originContext;
  private final OriginProperties properties;
  private final InMemoryTokenBucketRateLimiter rateLimiter;
  private final ServiceTokenAuthenticationProvider serviceTokenAuth;
  private final ObjectProvider<RequestMappingHandlerMapping> handlerMappingProvider;
  private final ObjectMapper objectMapper;

  public OriginFilter(
      OriginContext originContext,
      OriginProperties properties,
      InMemoryTokenBucketRateLimiter rateLimiter,
      ServiceTokenAuthenticationProvider serviceTokenAuth,
      ObjectProvider<RequestMappingHandlerMapping> handlerMappingProvider,
      ObjectMapper objectMapper) {
    this.originContext = originContext;
    this.properties = properties;
    this.rateLimiter = rateLimiter;
    this.serviceTokenAuth = serviceTokenAuth;
    this.handlerMappingProvider = handlerMappingProvider;
    this.objectMapper = objectMapper;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {

    String rawOrigin = request.getHeader(OriginHeaders.X_ORIGIN);

    // Fast path: no header → USER origin, nothing to do. OriginContext stays at its defaults.
    if (rawOrigin == null || rawOrigin.isBlank()) {
      chain.doFilter(request, response);
      return;
    }

    Origin origin = parseOrigin(rawOrigin);

    // Required trace for any non-USER origin.
    String trace = request.getHeader(OriginHeaders.X_ORIGIN_TRACE);
    if (origin != Origin.USER && (trace == null || trace.isBlank())) {
      throw new OriginValidationException(
          "origin-trace-required-for-non-user-origin",
          "X-Origin-Trace is required when X-Origin is non-USER.");
    }

    int depth = parseDepth(request.getHeader(OriginHeaders.X_ORIGIN_DEPTH));
    if (depth > MAX_DEPTH) {
      log.warn(
          "origin-depth-exceeded: depth={} origin={} trace={} (recursion guard fired)",
          depth,
          origin,
          trace);
      throw new OriginDepthExceededException(depth);
    }

    UUID actingAsUserId = parseUuid(request.getHeader(OriginHeaders.X_ACTING_AS));

    // Authentication dispatch: Pattern B if Bearer header is present AND origin is non-USER;
    // otherwise Pattern A (the existing SecurityContext set by SessionAuthenticationFilter is
    // trusted as-is, and we may still be anonymous — downstream authorize rules handle that).
    String authHeader = request.getHeader("Authorization");
    if (origin != Origin.USER && authHeader != null && authHeader.startsWith("Bearer ")) {
      if (actingAsUserId == null) {
        throw new OriginValidationException(
            "acting-as-required-under-pattern-b",
            "X-Acting-As is required when authenticating with a service token.");
      }
      String rawToken = authHeader.substring("Bearer ".length()).trim();
      Authentication serviceAuth = serviceTokenAuth.authenticate(rawToken, actingAsUserId, origin);
      SecurityContextHolder.getContext().setAuthentication(serviceAuth);
    }

    // The user identity for PER_USER rate-limit scope: actingAsUserId if set, else the current
    // session principal's userId, else null (which falls through to GLOBAL semantics).
    UUID rateLimitUserId = actingAsUserId != null ? actingAsUserId : currentSessionUserId();

    // Wrap request only when we'll need to read the body (AI origins with confidence-floor).
    HttpServletRequest effectiveRequest = request;
    BigDecimal confidence = null;
    if (origin == Origin.AI_FEEDBACK || origin == Origin.AI_ADAPTATION) {
      ContentCachingRequestWrapper wrapped = new ContentCachingRequestWrapper(request);
      // Trigger a body read so the wrapper caches it; the InputStream is now repeatable for the
      // controller's @RequestBody binding.
      confidence = readConfidenceFromBody(wrapped);
      BigDecimal floor = properties.aiConfidenceFloor();
      if (confidence == null || confidence.compareTo(floor) < 0) {
        // Treat absent confidence the same as below-floor: AI-origin calls MUST declare confidence.
        BigDecimal actual = confidence == null ? BigDecimal.ZERO : confidence;
        throw new ConfidenceBelowThresholdException(floor, actual);
      }
      effectiveRequest = wrapped;
    }

    // Defence-in-depth: non-USER origin on a controller method without @OriginAware → 403.
    if (origin != Origin.USER && properties.rejectOriginOnNonAnnotatedController()) {
      enforceOriginAwareAnnotation(request, origin);
    }

    // Rate limit (per the configured bucket; origins absent from the map are unlimited).
    Optional<Duration> retryAfter = rateLimiter.tryAcquire(origin, rateLimitUserId);
    if (retryAfter.isPresent()) {
      throw new OriginRateLimitExceededException(origin, retryAfter.get());
    }

    // Populate the request-scoped context for downstream services.
    originContext.setOrigin(origin);
    originContext.setOriginTrace(trace);
    originContext.setOriginDepth(depth);
    originContext.setConfidence(confidence);
    originContext.setActingAsUserId(actingAsUserId);

    chain.doFilter(effectiveRequest, response);
  }

  private Origin parseOrigin(String raw) {
    // Accept both UPPER_SNAKE (Java enum form) and kebab-case (design-doc/header style) for
    // ergonomics. "AI_FEEDBACK" and "ai-feedback" both resolve to Origin.AI_FEEDBACK.
    String normalised = raw.trim().toUpperCase().replace('-', '_');
    try {
      return Origin.valueOf(normalised);
    } catch (IllegalArgumentException ex) {
      throw new OriginValidationException(
          "unknown-origin",
          "Unknown X-Origin value: '"
              + raw
              + "'. Permitted: USER, AI_FEEDBACK, AI_ADAPTATION,"
              + " SYSTEM_SCHEDULED, SYSTEM_REOPT, SYSTEM_DISCOVERY.");
    }
  }

  private int parseDepth(String raw) {
    if (raw == null || raw.isBlank()) {
      return 0;
    }
    try {
      int d = Integer.parseInt(raw.trim());
      if (d < 0) {
        throw new OriginValidationException(
            "invalid-origin-depth", "X-Origin-Depth must be a non-negative integer.");
      }
      return d;
    } catch (NumberFormatException ex) {
      throw new OriginValidationException(
          "invalid-origin-depth", "X-Origin-Depth is not a valid integer: '" + raw + "'.");
    }
  }

  private UUID parseUuid(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return UUID.fromString(raw.trim());
    } catch (IllegalArgumentException ex) {
      throw new OriginValidationException(
          "invalid-acting-as", "X-Acting-As is not a valid UUID: '" + raw + "'.");
    }
  }

  private UUID currentSessionUserId() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth == null || auth.getPrincipal() == null) {
      return null;
    }
    Object principal = auth.getPrincipal();
    if (principal
        instanceof com.example.mealprep.auth.domain.service.AuthenticatedPrincipal authed) {
      return authed.userId();
    }
    return null;
  }

  private BigDecimal readConfidenceFromBody(ContentCachingRequestWrapper wrapped)
      throws IOException {
    // Consume the input stream to populate the wrapper's internal buffer; subsequent
    // getContentAsByteArray() returns the same bytes the controller will bind.
    byte[] body = wrapped.getInputStream().readAllBytes();
    if (body.length == 0) {
      return null;
    }
    try {
      JsonNode root = objectMapper.readTree(body);
      JsonNode node = root.get("confidence");
      if (node == null || node.isNull()) {
        return null;
      }
      if (node.isNumber()) {
        return node.decimalValue();
      }
      if (node.isTextual()) {
        return new BigDecimal(node.asText());
      }
      return null;
    } catch (RuntimeException ex) {
      // Body parse failure on a non-JSON request: not the filter's problem to flag — the
      // controller's binding will fail naturally. Return null so the confidence check fails
      // closed (treated as below floor → 422).
      return null;
    }
  }

  private void enforceOriginAwareAnnotation(HttpServletRequest request, Origin origin) {
    RequestMappingHandlerMapping mapping = handlerMappingProvider.getIfAvailable();
    if (mapping == null) {
      // No mapping bean visible (e.g. some test slices). Fail-closed: a non-USER origin without
      // resolvable handler is rejected — we can't verify @OriginAware so we can't allow it.
      throw new OriginNotPermittedOnEndpointException(origin.name(), request.getRequestURI());
    }
    HandlerExecutionChain chain;
    try {
      chain = mapping.getHandler(request);
    } catch (Exception ex) {
      log.debug("handler lookup failed during origin annotation check", ex);
      throw new OriginNotPermittedOnEndpointException(origin.name(), request.getRequestURI());
    }
    if (chain == null || !(chain.getHandler() instanceof HandlerMethod hm)) {
      throw new OriginNotPermittedOnEndpointException(origin.name(), request.getRequestURI());
    }
    // Annotation can be on the method OR on the declaring controller class.
    boolean annotated =
        hm.hasMethodAnnotation(OriginAware.class)
            || hm.getBeanType().isAnnotationPresent(OriginAware.class);
    if (!annotated) {
      throw new OriginNotPermittedOnEndpointException(origin.name(), request.getRequestURI());
    }
  }
}
