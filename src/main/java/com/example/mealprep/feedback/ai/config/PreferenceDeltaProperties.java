package com.example.mealprep.feedback.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Externalised configuration for the preference AI delta-generation pipeline (preference-01g),
 * bound to the {@code mealprep.feedback.preference-delta.*} prefix. Mirrors the
 * magnitude-properties pattern from {@code FeedbackRetrySweepProperties} — every field falls back
 * to its documented default when the property is absent so the binder never NPEs on a
 * partially-specified profile.
 *
 * <ul>
 *   <li>{@code weeklyCron} — the {@code @Scheduled(cron)} expression for the WEEKLY sweep. NOTE:
 *       the {@code @Scheduled} annotation reads the same key via a {@code ${...}} placeholder
 *       (annotation attributes must be compile-time constants); this field is the typed mirror for
 *       documentation + any future programmatic use.
 *   <li>{@code batchThreshold} — the per-user PREFERENCE-routed feedback count that triggers a
 *       BATCH run (default 5, per {@code design/preference-model.md:67}). On the Nth feedback the
 *       cursor schedules a delta-update run and resets the counter.
 *   <li>{@code correctiveRetryEnabled} — when true the orchestrator re-prompts once on a rejected
 *       batch ({@code InvalidTasteProfileDeltaException} / {@code
 *       TasteProfileBudgetExceededException}); when false the batch is logged + skipped on first
 *       failure.
 * </ul>
 */
@ConfigurationProperties(prefix = "mealprep.feedback.preference-delta")
public record PreferenceDeltaProperties(
    String weeklyCron, Integer batchThreshold, Boolean correctiveRetryEnabled) {

  /** Sundays 03:00 by default, matching the ticket's {@code 0 0 3 * * SUN}. */
  public static final String DEFAULT_WEEKLY_CRON = "0 0 3 * * SUN";

  /** Default batch threshold — every 5th PREFERENCE-routed feedback. */
  public static final int DEFAULT_BATCH_THRESHOLD = 5;

  public PreferenceDeltaProperties {
    if (weeklyCron == null || weeklyCron.isBlank()) {
      weeklyCron = DEFAULT_WEEKLY_CRON;
    }
    if (batchThreshold == null || batchThreshold < 1) {
      batchThreshold = DEFAULT_BATCH_THRESHOLD;
    }
    if (correctiveRetryEnabled == null) {
      correctiveRetryEnabled = Boolean.TRUE;
    }
  }
}
