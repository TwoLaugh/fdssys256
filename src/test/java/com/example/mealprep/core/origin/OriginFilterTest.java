package com.example.mealprep.core.origin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.mealprep.auth.security.ServiceTokenAuthenticationProvider;
import com.example.mealprep.core.api.OriginHeaders;
import com.example.mealprep.core.origin.OriginProperties.RateLimitConfig;
import com.example.mealprep.core.origin.OriginProperties.Scope;
import com.example.mealprep.core.origin.exception.ConfidenceBelowThresholdException;
import com.example.mealprep.core.origin.exception.OriginDepthExceededException;
import com.example.mealprep.core.origin.exception.OriginNotPermittedOnEndpointException;
import com.example.mealprep.core.origin.exception.OriginRateLimitExceededException;
import com.example.mealprep.core.origin.exception.OriginValidationException;
import com.example.mealprep.core.origin.internal.InMemoryTokenBucketRateLimiter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Unit test for {@link OriginFilter} — every validation branch (unknown origin, missing trace,
 * depth-exceeded, confidence-below-threshold, non-annotated controller, rate-limit-exceeded,
 * happy-path with USER and AI origins). The filter is constructed directly with mocked
 * collaborators; no Spring context.
 *
 * <p>{@link RequestMappingHandlerMapping} is mocked to return either an annotated or unannotated
 * handler depending on the test case — see the {@code SampleAnnotated} / {@code SampleUnannotated}
 * test fixtures at the bottom.
 */
@ExtendWith(MockitoExtension.class)
class OriginFilterTest {

  private static final Clock FIXED_CLOCK =
      Clock.fixed(Instant.parse("2026-05-21T10:00:00Z"), ZoneOffset.UTC);

  @Mock private ServiceTokenAuthenticationProvider serviceTokenAuth;
  @Mock private RequestMappingHandlerMapping handlerMapping;
  @Mock private ObjectProvider<RequestMappingHandlerMapping> handlerMappingProvider;
  @Mock private FilterChain chain;

  private OriginContext originContext;
  private InMemoryTokenBucketRateLimiter rateLimiter;
  private OriginFilter filter;
  private OriginProperties properties;

  @BeforeEach
  void setUp() throws Exception {
    originContext = new OriginContext();
    properties =
        new OriginProperties(
            new BigDecimal("0.5"),
            Duration.ofHours(1),
            Map.of(
                Origin.AI_FEEDBACK, new RateLimitConfig(2, Scope.PER_USER),
                Origin.AI_ADAPTATION, new RateLimitConfig(2, Scope.PER_USER)),
            true);
    rateLimiter = new InMemoryTokenBucketRateLimiter(properties, FIXED_CLOCK);
    lenient().when(handlerMappingProvider.getIfAvailable()).thenReturn(handlerMapping);
    // Default: an annotated handler is found, so the @OriginAware check passes.
    lenient()
        .when(handlerMapping.getHandler(any(HttpServletRequest.class)))
        .thenReturn(handlerForAnnotated());
    filter =
        new OriginFilter(
            originContext,
            properties,
            rateLimiter,
            serviceTokenAuth,
            handlerMappingProvider,
            new ObjectMapper());
    SecurityContextHolder.clearContext();
  }

  // ------------------------------------------------------------------------------------------
  // Happy paths

  @Test
  void no_X_Origin_header_leaves_context_at_USER_defaults_and_proceeds() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("PUT", "/api/v1/some-endpoint");
    MockHttpServletResponse res = new MockHttpServletResponse();

    filter.doFilter(req, res, chain);

    assertThat(originContext.getOrigin()).isEqualTo(Origin.USER);
    assertThat(originContext.getOriginTrace()).isNull();
    assertThat(originContext.getOriginDepth()).isZero();
    assertThat(originContext.isNonUserOrigin()).isFalse();
    verify(chain).doFilter(any(), any());
  }

  @Test
  void AI_FEEDBACK_with_valid_confidence_and_trace_populates_context() throws Exception {
    MockHttpServletRequest req = aiFeedbackRequest("0.7", "feedback-abc", "0");
    MockHttpServletResponse res = new MockHttpServletResponse();

    filter.doFilter(req, res, chain);

    assertThat(originContext.getOrigin()).isEqualTo(Origin.AI_FEEDBACK);
    assertThat(originContext.getOriginTrace()).isEqualTo("feedback-abc");
    assertThat(originContext.getOriginDepth()).isZero();
    assertThat(originContext.getConfidence()).isEqualByComparingTo("0.7");
    verify(chain).doFilter(any(), any());
  }

  @Test
  void kebab_case_origin_value_is_normalised_to_enum() throws Exception {
    MockHttpServletRequest req = aiFeedbackRequest("0.7", "feedback-abc", "0");
    req.removeHeader(OriginHeaders.X_ORIGIN);
    // design-doc style — kebab-case lowercase
    req.addHeader(OriginHeaders.X_ORIGIN, "ai-feedback");
    MockHttpServletResponse res = new MockHttpServletResponse();

    filter.doFilter(req, res, chain);

    assertThat(originContext.getOrigin()).isEqualTo(Origin.AI_FEEDBACK);
  }

  // ------------------------------------------------------------------------------------------
  // Validation rejections

  @Test
  void unknown_origin_value_throws_OriginValidationException() {
    MockHttpServletRequest req = new MockHttpServletRequest("PUT", "/x");
    req.addHeader(OriginHeaders.X_ORIGIN, "TOTALLY_BOGUS");
    assertThatExceptionOfType(OriginValidationException.class)
        .isThrownBy(() -> filter.doFilter(req, new MockHttpServletResponse(), chain))
        .satisfies(ex -> assertThat(ex.getProblemSlug()).isEqualTo("unknown-origin"));
    verifyChainNotInvoked();
  }

  @Test
  void non_user_origin_missing_trace_throws() {
    MockHttpServletRequest req = new MockHttpServletRequest("PUT", "/x");
    req.addHeader(OriginHeaders.X_ORIGIN, "AI_FEEDBACK");
    // no X-Origin-Trace
    assertThatExceptionOfType(OriginValidationException.class)
        .isThrownBy(() -> filter.doFilter(req, new MockHttpServletResponse(), chain))
        .satisfies(
            ex ->
                assertThat(ex.getProblemSlug())
                    .isEqualTo("origin-trace-required-for-non-user-origin"));
    verifyChainNotInvoked();
  }

  @Test
  void depth_exceeding_max_throws_OriginDepthExceededException() {
    MockHttpServletRequest req = aiFeedbackRequest("0.7", "feedback-x", "4");
    assertThatExceptionOfType(OriginDepthExceededException.class)
        .isThrownBy(() -> filter.doFilter(req, new MockHttpServletResponse(), chain))
        .satisfies(ex -> assertThat(ex.getDepth()).isEqualTo(4));
    verifyChainNotInvoked();
  }

  @Test
  void depth_equal_to_max_is_permitted() throws Exception {
    MockHttpServletRequest req = aiFeedbackRequest("0.7", "feedback-x", "3");
    filter.doFilter(req, new MockHttpServletResponse(), chain);
    assertThat(originContext.getOriginDepth()).isEqualTo(3);
    verify(chain).doFilter(any(), any());
  }

  @Test
  void invalid_depth_string_throws_OriginValidationException() {
    MockHttpServletRequest req = aiFeedbackRequest("0.7", "feedback-x", "not-a-number");
    assertThatExceptionOfType(OriginValidationException.class)
        .isThrownBy(() -> filter.doFilter(req, new MockHttpServletResponse(), chain))
        .satisfies(ex -> assertThat(ex.getProblemSlug()).isEqualTo("invalid-origin-depth"));
  }

  @Test
  void confidence_below_threshold_throws_ConfidenceBelowThresholdException() {
    MockHttpServletRequest req = aiFeedbackRequest("0.3", "feedback-x", "0");
    assertThatExceptionOfType(ConfidenceBelowThresholdException.class)
        .isThrownBy(() -> filter.doFilter(req, new MockHttpServletResponse(), chain))
        .satisfies(
            ex -> {
              assertThat(ex.getThreshold()).isEqualByComparingTo("0.5");
              assertThat(ex.getActual()).isEqualByComparingTo("0.3");
            });
    verifyChainNotInvoked();
  }

  @Test
  void confidence_missing_from_body_throws_ConfidenceBelowThresholdException() {
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/x");
    req.addHeader(OriginHeaders.X_ORIGIN, "AI_FEEDBACK");
    req.addHeader(OriginHeaders.X_ORIGIN_TRACE, "feedback-x");
    req.setContent("{\"other\": \"value\"}".getBytes());
    req.setContentType("application/json");

    assertThatExceptionOfType(ConfidenceBelowThresholdException.class)
        .isThrownBy(() -> filter.doFilter(req, new MockHttpServletResponse(), chain))
        .satisfies(ex -> assertThat(ex.getActual()).isEqualByComparingTo("0"));
  }

  @Test
  void non_AI_origins_skip_confidence_check() throws Exception {
    // SYSTEM_SCHEDULED doesn't require a body confidence field.
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/x");
    req.addHeader(OriginHeaders.X_ORIGIN, "SYSTEM_SCHEDULED");
    req.addHeader(OriginHeaders.X_ORIGIN_TRACE, "scheduled-x");
    filter.doFilter(req, new MockHttpServletResponse(), chain);
    assertThat(originContext.getOrigin()).isEqualTo(Origin.SYSTEM_SCHEDULED);
    verify(chain).doFilter(any(), any());
  }

  @Test
  void unannotated_handler_rejects_non_user_origin_with_403() throws Exception {
    when(handlerMapping.getHandler(any(HttpServletRequest.class)))
        .thenReturn(handlerForUnannotated());
    MockHttpServletRequest req = aiFeedbackRequest("0.9", "feedback-x", "0");

    assertThatExceptionOfType(OriginNotPermittedOnEndpointException.class)
        .isThrownBy(() -> filter.doFilter(req, new MockHttpServletResponse(), chain));
    verifyChainNotInvoked();
  }

  @Test
  void rate_limit_exhaustion_throws_OriginRateLimitExceededException() throws Exception {
    UUID actingAs = UUID.randomUUID();
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                new com.example.mealprep.auth.domain.service.AuthenticatedPrincipal(actingAs, null),
                null,
                java.util.List.of()));

    // Bucket is 2 for AI_FEEDBACK per setup; first 2 pass.
    filter.doFilter(
        aiFeedbackRequest("0.7", "feedback-1", "0"), new MockHttpServletResponse(), chain);
    filter.doFilter(
        aiFeedbackRequest("0.7", "feedback-2", "0"), new MockHttpServletResponse(), chain);

    // Third call is rejected.
    MockHttpServletRequest req3 = aiFeedbackRequest("0.7", "feedback-3", "0");
    assertThatExceptionOfType(OriginRateLimitExceededException.class)
        .isThrownBy(() -> filter.doFilter(req3, new MockHttpServletResponse(), chain))
        .satisfies(
            ex -> {
              assertThat(ex.getOrigin()).isEqualTo(Origin.AI_FEEDBACK);
              assertThat(ex.getRetryAfter()).isGreaterThan(Duration.ZERO);
            });
  }

  // ------------------------------------------------------------------------------------------
  // Authentication dispatch

  @Test
  void pattern_B_with_bearer_and_acting_as_invokes_service_token_provider() throws Exception {
    UUID actingAs = UUID.randomUUID();
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/x");
    req.addHeader(OriginHeaders.X_ORIGIN, "SYSTEM_SCHEDULED");
    req.addHeader(OriginHeaders.X_ORIGIN_TRACE, "scheduled-x");
    req.addHeader(OriginHeaders.X_ACTING_AS, actingAs.toString());
    req.addHeader("Authorization", "Bearer my-secret-token");

    when(serviceTokenAuth.authenticate("my-secret-token", actingAs, Origin.SYSTEM_SCHEDULED))
        .thenReturn(
            new UsernamePasswordAuthenticationToken(
                new com.example.mealprep.auth.domain.service.AuthenticatedPrincipal(actingAs, null),
                null,
                java.util.List.of()));

    filter.doFilter(req, new MockHttpServletResponse(), chain);

    verify(serviceTokenAuth, times(1))
        .authenticate("my-secret-token", actingAs, Origin.SYSTEM_SCHEDULED);
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
  }

  @Test
  void pattern_B_missing_acting_as_throws_validation_exception() {
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/x");
    req.addHeader(OriginHeaders.X_ORIGIN, "SYSTEM_SCHEDULED");
    req.addHeader(OriginHeaders.X_ORIGIN_TRACE, "scheduled-x");
    req.addHeader("Authorization", "Bearer my-token");
    // no X-Acting-As

    assertThatExceptionOfType(OriginValidationException.class)
        .isThrownBy(() -> filter.doFilter(req, new MockHttpServletResponse(), chain))
        .satisfies(
            ex -> assertThat(ex.getProblemSlug()).isEqualTo("acting-as-required-under-pattern-b"));
    verify(serviceTokenAuth, never()).authenticate(any(), any(), any());
  }

  @Test
  void pattern_A_without_bearer_does_not_invoke_service_token_provider() throws Exception {
    UUID userId = UUID.randomUUID();
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                new com.example.mealprep.auth.domain.service.AuthenticatedPrincipal(
                    userId, UUID.randomUUID()),
                null,
                java.util.List.of()));

    MockHttpServletRequest req = aiFeedbackRequest("0.7", "feedback-pa", "0");
    filter.doFilter(req, new MockHttpServletResponse(), chain);

    verify(serviceTokenAuth, never()).authenticate(any(), any(), any());
    // The existing session principal is untouched.
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
  }

  // ------------------------------------------------------------------------------------------
  // Helpers

  private MockHttpServletRequest aiFeedbackRequest(String confidence, String trace, String depth) {
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/some-endpoint");
    req.addHeader(OriginHeaders.X_ORIGIN, "AI_FEEDBACK");
    req.addHeader(OriginHeaders.X_ORIGIN_TRACE, trace);
    req.addHeader(OriginHeaders.X_ORIGIN_DEPTH, depth);
    req.setContent(("{\"confidence\": " + confidence + "}").getBytes());
    req.setContentType("application/json");
    return req;
  }

  private void verifyChainNotInvoked() {
    try {
      verify(chain, never()).doFilter(any(), any());
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  /** A {@link HandlerExecutionChain} whose handler method is annotated {@link OriginAware}. */
  private HandlerExecutionChain handlerForAnnotated() throws NoSuchMethodException {
    Method m = SampleAnnotated.class.getMethod("annotatedHandler");
    HandlerMethod hm = new HandlerMethod(new SampleAnnotated(), m);
    return new HandlerExecutionChain(hm);
  }

  /** A {@link HandlerExecutionChain} whose handler method is NOT annotated. */
  private HandlerExecutionChain handlerForUnannotated() throws NoSuchMethodException {
    Method m = SampleUnannotated.class.getMethod("plainHandler");
    HandlerMethod hm = new HandlerMethod(new SampleUnannotated(), m);
    return new HandlerExecutionChain(hm);
  }

  @SuppressWarnings("unused")
  static class SampleAnnotated {
    @OriginAware
    public String annotatedHandler() {
      return "ok";
    }
  }

  @SuppressWarnings("unused")
  static class SampleUnannotated {
    public String plainHandler() {
      return "ok";
    }
  }

  /**
   * Within a single dispatch the {@link OncePerRequestFilter} guard skips a second pass — the
   * filter sets a request attribute on first entry and short-circuits if it re-enters before the
   * attribute is cleared. We simulate the re-entry by pre-setting the {@code FILTERED} attribute
   * and asserting the body is NOT re-processed (chain still called once, context untouched).
   */
  @Test
  void filter_skips_when_already_filtered_attribute_present() throws ServletException, IOException {
    MockHttpServletRequest req = aiFeedbackRequest("0.7", "feedback-x", "0");
    // Mark the request as already filtered (OncePerRequestFilter uses class-name + ".FILTERED").
    req.setAttribute(OriginFilter.class.getName() + ".FILTERED", Boolean.TRUE);

    filter.doFilter(req, new MockHttpServletResponse(), chain);

    // The body was never inspected — context stays at USER defaults — and the chain proceeds.
    assertThat(originContext.getOrigin()).isEqualTo(Origin.USER);
    verify(chain, times(1)).doFilter(any(), any());
  }
}
