package com.example.mealprep.feedback.domain.entity;

/**
 * Coarse-grained classification of a routing-log row's failure. Values verbatim from
 * lld/feedback.md §Entities line 221.
 *
 * <ul>
 *   <li>{@code TRANSIENT} — retryable (network blip, AI rate-limit).
 *   <li>{@code DESTINATION_VALIDATION} — destination rejected the payload (4xx-ish).
 *   <li>{@code DESTINATION_BUSINESS} — destination accepted shape but business-rejected.
 *   <li>{@code AI_UNAVAILABLE} — classifier itself failed.
 *   <li>{@code UNKNOWN} — fallback for unrecognised failure.
 * </ul>
 */
public enum RoutingFailureKind {
  TRANSIENT,
  DESTINATION_VALIDATION,
  DESTINATION_BUSINESS,
  AI_UNAVAILABLE,
  UNKNOWN
}
