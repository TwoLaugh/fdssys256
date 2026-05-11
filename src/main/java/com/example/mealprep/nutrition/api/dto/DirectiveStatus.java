package com.example.mealprep.nutrition.api.dto;

/**
 * Lifecycle state of a {@link com.example.mealprep.nutrition.domain.entity.HealthDirective}. {@code
 * PENDING_REVIEW} on inbound; the accept / reject endpoints transition to {@code ACCEPTED} / {@code
 * REJECTED}. {@code SUPERSEDED} and {@code EXPIRED} are reserved for the auto-expiry sweep deferred
 * to nutrition-01f.
 */
public enum DirectiveStatus {
  PENDING_REVIEW,
  ACCEPTED,
  REJECTED,
  SUPERSEDED,
  EXPIRED
}
