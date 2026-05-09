package com.example.mealprep.config;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/**
 * Shared constants + helpers for {@link ProblemDetail} construction across all module-specific
 * {@code @RestControllerAdvice} classes.
 *
 * <p>Avoids duplicating the {@code PROBLEM_BASE} URI prefix and the {@link Duration}-to-seconds
 * clamping logic in every advice class.
 */
public final class ProblemDetailSupport {

  public static final String PROBLEM_BASE = "https://mealprep.example.com/problems/";

  private ProblemDetailSupport() {}

  /**
   * Build a ProblemDetail with the standard {@code type}/{@code title}/{@code instance} fields
   * populated. Caller adds any {@code setProperty(...)} extensions afterwards.
   */
  public static ProblemDetail build(
      HttpStatus status, String detail, String typeSlug, String title, String requestUri) {
    ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
    pd.setType(URI.create(PROBLEM_BASE + typeSlug));
    pd.setTitle(title);
    pd.setInstance(URI.create(requestUri));
    return pd;
  }

  /**
   * Compute Retry-After in whole seconds from {@code now} until {@code target}, with a floor of 1
   * (we never advise zero). Returns 1 if {@code target} is null or in the past.
   */
  public static long retryAfterSeconds(Clock clock, Instant target) {
    if (target == null) {
      return 1L;
    }
    return clampToWholeSeconds(Duration.between(Instant.now(clock), target));
  }

  /** Clamp a duration to whole seconds, ceiling, with a floor of 1. */
  public static long clampToWholeSeconds(Duration duration) {
    if (duration == null || duration.isNegative() || duration.isZero()) {
      return 1L;
    }
    long seconds = duration.toSeconds();
    if (duration.minusSeconds(seconds).toNanos() > 0) {
      seconds += 1;
    }
    return Math.max(seconds, 1L);
  }
}
