package com.example.mealprep.notification.domain.entity;

/**
 * Structured reason a delivery was skipped, recorded alongside a {@code SKIPPED} {@link
 * DeliveryOutcome} so the delivery-log endpoint can surface why an expected notification did not
 * fire.
 */
public enum DeliverySkipReason {
  DISABLED_BY_PREF,
  QUIET_HOURS,
  DEDUPED_INTO_BUNDLE,
  CHANNEL_UNAVAILABLE
}
