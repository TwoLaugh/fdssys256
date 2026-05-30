package com.example.mealprep.core.audit.trace;

import com.example.mealprep.core.audit.domain.service.internal.TraceContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Seeds the per-request trace id into SLF4J MDC so every log line for the request carries {@code
 * %X{traceId}}, and echoes it back on the response for client-side correlation. Per lld/core.md
 * §Trace ID Propagation / Flow 4.
 *
 * <p>Registered at {@link Ordered#HIGHEST_PRECEDENCE} so it runs before the Spring Security chain
 * (and {@code OriginFilter}) — the trace id is therefore present for every log line those filters
 * emit too.
 *
 * <p>Lifecycle per request:
 *
 * <ol>
 *   <li>Read the inbound {@value #TRACE_HEADER} header; if present and a well-formed UUID, reuse
 *       it, otherwise generate a fresh one.
 *   <li>Push it onto MDC via {@link TraceContext#setTraceId(UUID)}.
 *   <li>Echo it on the response {@value #TRACE_HEADER} header (set before the chain runs so it is
 *       present even on an error/committed response).
 *   <li>Run the chain.
 *   <li><b>Always</b> clear MDC in a {@code finally} so a thread returned to the pool starts clean.
 * </ol>
 *
 * <p>Lives in {@code core.audit.trace} (a sanctioned {@code springWebStaysInApi} ArchUnit
 * carve-out, same pattern as {@code core.origin}) because a servlet filter legitimately depends on
 * the Servlet API. The trace-reading helper ({@link TraceContext}) carries no Spring Web dependency
 * and lives in {@code core.audit.domain.service.internal}.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

  /** Inbound/outbound correlation header. */
  public static final String TRACE_HEADER = "X-Trace-Id";

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    UUID traceId = resolveTraceId(request.getHeader(TRACE_HEADER));
    TraceContext.setTraceId(traceId);
    response.setHeader(TRACE_HEADER, traceId.toString());
    try {
      chain.doFilter(request, response);
    } finally {
      TraceContext.clear();
    }
  }

  /** Reuse a well-formed inbound UUID; otherwise generate a fresh one. */
  private static UUID resolveTraceId(String inbound) {
    if (inbound != null && !inbound.isBlank()) {
      try {
        return UUID.fromString(inbound.trim());
      } catch (IllegalArgumentException ignored) {
        // Malformed inbound header — fall through to a generated id rather than rejecting.
      }
    }
    return UUID.randomUUID();
  }
}
