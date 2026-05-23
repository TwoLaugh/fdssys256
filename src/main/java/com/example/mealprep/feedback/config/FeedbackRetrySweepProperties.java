package com.example.mealprep.feedback.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Externalised configuration for the {@code retryStuckClassifications} sweep (feedback-01i), bound
 * to the {@code mealprep.feedback.retry-sweep.*} prefix. Mirrors the magnitude-properties pattern
 * from {@code tickets/nutrition/01i} — every field falls back to its documented default when the
 * property is absent so the binder never NPEs on a partially-specified profile.
 *
 * <ul>
 *   <li>{@code stuckAfter} — entries in {@code RECEIVED}/{@code CLASSIFYING} whose retry clock is
 *       older than this are re-classified. LLD line 575 ("more than 5 minutes").
 *   <li>{@code escalateAfter} — entries created longer than this ago are escalated to {@code
 *       FAILED} instead of retried. LLD line 576 ("after 24h of failed retries").
 *   <li>{@code fixedDelayMs} — the {@code @Scheduled(fixedDelay)} cadence. LLD line 514 ("every 2
 *       minutes"). NOTE: the {@code @Scheduled} annotation reads the same key via a {@code ${...}}
 *       placeholder (annotation attributes must be compile-time constants); this field is the typed
 *       mirror for documentation + any future programmatic use.
 * </ul>
 *
 * <p>The retry clock measures time-since-last-attempt: {@code lastClassifiedAt} when present
 * (stamped on every classification transition, including the revert-to-RECEIVED path), falling back
 * to {@code createdAt} for an entry that has never been classified. This stops a {@code
 * CLASSIFYING} entry that was retried minutes ago from being re-dispatched on the very next sweep.
 */
@ConfigurationProperties(prefix = "mealprep.feedback.retry-sweep")
public record FeedbackRetrySweepProperties(
    Duration stuckAfter, Duration escalateAfter, Long fixedDelayMs) {

  public FeedbackRetrySweepProperties {
    if (stuckAfter == null) {
      stuckAfter = Duration.ofMinutes(5);
    }
    if (escalateAfter == null) {
      escalateAfter = Duration.ofHours(24);
    }
    if (fixedDelayMs == null) {
      fixedDelayMs = 120_000L;
    }
  }
}
