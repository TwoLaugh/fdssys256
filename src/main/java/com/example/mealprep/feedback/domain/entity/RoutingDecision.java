package com.example.mealprep.feedback.domain.entity;

/**
 * The three-way fork emitted by the confidence gate. Values verbatim from lld/feedback.md §Entities
 * line 219.
 *
 * <ul>
 *   <li>{@code AUTO_ROUTED} — confidence ≥ 0.8; route silently.
 *   <li>{@code ROUTED_WITH_FLAG} — confidence 0.5..0.8; route but flag for user review.
 *   <li>{@code CLARIFICATION_QUEUED} — confidence &lt; 0.5; queue a clarification question.
 * </ul>
 */
public enum RoutingDecision {
  AUTO_ROUTED,
  ROUTED_WITH_FLAG,
  CLARIFICATION_QUEUED
}
