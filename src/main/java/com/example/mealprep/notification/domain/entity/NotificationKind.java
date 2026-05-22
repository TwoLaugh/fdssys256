package com.example.mealprep.notification.domain.entity;

/**
 * Closed enum of notification kinds — one value per source event per {@code lld/notification.md}
 * §Notification Kinds. Persisted as the literal name via {@code @Enumerated(STRING)}.
 *
 * <p>{@code PLANNER_PLAN_GENERATED} ships but is default-OFF in the seeded preferences (see {@link
 * com.example.mealprep.notification.domain.service.internal.NotificationDefaults}); it doubles up
 * with the planner UI's natural "your plan is ready" state and is opt-in.
 */
public enum NotificationKind {
  /** {@code ItemNearingExpiryEvent} — inventory item(s) approaching expiry. */
  PROVISION_ITEM_NEAR_EXPIRY,
  /** {@code ItemSpoiledEvent} — inventory item(s) marked spoiled. */
  PROVISION_ITEM_SPOILED,
  /** {@code DefrostReminderEvent} — frozen item should be moved to defrost. */
  PROVISION_DEFROST_REMINDER,
  /** {@code NutritionIntakeDivergedEvent} — actual intake diverged from target. */
  NUTRITION_INTAKE_DIVERGED,
  /** {@code HealthDirectiveReceivedEvent} — an inbound health directive arrived. */
  HEALTH_DIRECTIVE_RECEIVED,
  /** {@code PrepReminderEvent} — an upcoming slot needs advance prep. */
  PLANNER_PREP_REMINDER,
  /** {@code ReoptSuggestedEvent} — a re-optimisation suggestion is ready to review. */
  PLANNER_REOPT_SUGGESTED,
  /** {@code PlanGeneratedEvent} — a new plan was generated (optional, default OFF). */
  PLANNER_PLAN_GENERATED
}
