package com.example.mealprep.core.audit.domain.service.internal;

import java.util.UUID;
import java.util.concurrent.Callable;
import org.slf4j.MDC;
import org.springframework.lang.Nullable;

/**
 * Request-scoped accessor for the current trace id, backed by SLF4J {@link MDC} under the {@value
 * #MDC_KEY} key. The trace id is seeded per inbound HTTP request by {@code TraceIdFilter}; this
 * class is the sanctioned way for application code (especially {@code @Async} / {@code @Scheduled}
 * background work) to read it or carry it onto a worker thread.
 *
 * <p>Per lld/core.md §Trace ID Propagation: trace ids cross module boundaries via explicit method
 * arguments, never thread-locals — but within a single request/worker thread the MDC value is what
 * makes every log line carry {@code %X{traceId}}. Background dispatchers therefore wrap their work
 * in {@link #runWithTraceId}/{@link #callWithTraceId} so the spawned thread logs under the same
 * trace id and always cleans up afterwards (ad-hoc {@code MDC.put} on a pooled thread is a leak).
 *
 * <p>All-static; not instantiable.
 */
public final class TraceContext {

  /**
   * MDC key under which the current trace id is stored. Mirrored by the logback {@code %X} pattern.
   */
  public static final String MDC_KEY = "traceId";

  private TraceContext() {
    // utility — no instances.
  }

  /**
   * The current trace id, or {@code null} if none is set on this thread (e.g. a request that never
   * passed through {@code TraceIdFilter}, or a worker thread outside a {@link #runWithTraceId}
   * scope).
   */
  @Nullable
  public static UUID currentTraceId() {
    String raw = MDC.get(MDC_KEY);
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return UUID.fromString(raw);
    } catch (IllegalArgumentException ex) {
      // A non-UUID value in MDC is a contract violation by whoever set it; treat as unset.
      return null;
    }
  }

  /**
   * The current trace id, throwing {@link IllegalStateException} if none is set. Use when the
   * caller genuinely requires a trace id to proceed.
   */
  public static UUID requireTraceId() {
    UUID traceId = currentTraceId();
    if (traceId == null) {
      throw new IllegalStateException("No trace id is set on the current thread");
    }
    return traceId;
  }

  /** Set the trace id on the current thread's MDC, overwriting any existing value. */
  public static void setTraceId(UUID traceId) {
    if (traceId == null) {
      throw new IllegalArgumentException("traceId must not be null");
    }
    MDC.put(MDC_KEY, traceId.toString());
  }

  /** Remove the trace id from the current thread's MDC. Always safe to call (idempotent). */
  public static void clear() {
    MDC.remove(MDC_KEY);
  }

  /**
   * Run {@code body} with {@code traceId} pushed onto MDC for its duration, restoring the previous
   * MDC value (or clearing it) afterwards — even if {@code body} throws. Nested calls restore the
   * outer trace id on exit. This is the only sanctioned way for background ({@code @Async} /
   * {@code @Scheduled}) work to trace under a known id.
   */
  public static void runWithTraceId(UUID traceId, Runnable body) {
    if (traceId == null) {
      throw new IllegalArgumentException("traceId must not be null");
    }
    if (body == null) {
      throw new IllegalArgumentException("body must not be null");
    }
    String previous = MDC.get(MDC_KEY);
    MDC.put(MDC_KEY, traceId.toString());
    try {
      body.run();
    } finally {
      restore(previous);
    }
  }

  /**
   * {@link Callable} variant of {@link #runWithTraceId} — runs {@code body} under {@code traceId}
   * and returns its result, restoring the previous MDC value (or clearing it) afterwards.
   */
  public static <T> T callWithTraceId(UUID traceId, Callable<T> body) throws Exception {
    if (traceId == null) {
      throw new IllegalArgumentException("traceId must not be null");
    }
    if (body == null) {
      throw new IllegalArgumentException("body must not be null");
    }
    String previous = MDC.get(MDC_KEY);
    MDC.put(MDC_KEY, traceId.toString());
    try {
      return body.call();
    } finally {
      restore(previous);
    }
  }

  private static void restore(@Nullable String previous) {
    if (previous == null) {
      MDC.remove(MDC_KEY);
    } else {
      MDC.put(MDC_KEY, previous);
    }
  }
}
