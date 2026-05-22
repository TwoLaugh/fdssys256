package com.example.mealprep.notification.domain.entity;

/**
 * Outcome of a single delivery attempt recorded in {@code notification_delivery_log}. {@code
 * DEFERRED} and {@code FAILED} are reserved for future channels (push / email); the v1 in-app
 * channel only ever records {@code DELIVERED} or, on suppression, {@code SKIPPED}.
 */
public enum DeliveryOutcome {
  DELIVERED,
  SKIPPED,
  DEFERRED,
  FAILED
}
