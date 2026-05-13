package com.example.mealprep.feedback.domain.entity;

/**
 * Lifecycle of a single routing-log row. Values verbatim from lld/feedback.md §Entities line 220.
 *
 * <p>{@code AWAITING_USER_APPROVAL} covers the recipe path — the optimiser produces a
 * <i>proposed</i> adaptation, not an applied one (per system-overview.md §Recipe Optimiser).
 */
public enum RoutingStatus {
  PENDING,
  APPLIED,
  FAILED,
  CORRECTED_AWAY,
  REPLAYED,
  AWAITING_USER_APPROVAL
}
