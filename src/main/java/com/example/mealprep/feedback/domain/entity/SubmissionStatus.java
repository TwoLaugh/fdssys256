package com.example.mealprep.feedback.domain.entity;

/**
 * State of a {@code FeedbackEntry} as it travels through classification + routing. Values verbatim
 * from lld/feedback.md §Entities line 218.
 *
 * <ul>
 *   <li>{@code RECEIVED} — entry persisted, classification not yet started.
 *   <li>{@code CLASSIFYING} — AiTask in flight.
 *   <li>{@code CLASSIFIED} — classification returned but routing not yet attempted (transient).
 *   <li>{@code CLARIFICATION_PENDING} — classifier confidence &lt; 0.5; awaiting user answer.
 *   <li>{@code ROUTED} — every classification fanned out and at least applied.
 *   <li>{@code PARTIALLY_FAILED} — some destinations applied, others failed.
 *   <li>{@code FAILED} — terminal failure (e.g. AI unavailable + retries exhausted, or expired
 *       clarification).
 *   <li>{@code CORRECTED} — user corrected the routing after the original applied.
 * </ul>
 */
public enum SubmissionStatus {
  RECEIVED,
  CLASSIFYING,
  CLASSIFIED,
  CLARIFICATION_PENDING,
  ROUTED,
  PARTIALLY_FAILED,
  FAILED,
  CORRECTED
}
