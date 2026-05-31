package com.example.mealprep.planner.domain.service;

import com.example.mealprep.planner.api.dto.PlanReoptSuggestionDto;
import com.example.mealprep.planner.domain.entity.SlotState;
import java.util.UUID;

/**
 * Write surface for the plan aggregate lifecycle (planner-01j). Accept / reject / abandon, per-slot
 * state transitions, and re-opt-suggestion accept/reject. The {@code generate} entry lives on
 * {@code PlanComposer} (it has the Stage A&rarr;D wiring); revert-to-a-historical-plan lives on
 * {@code RevertToPlanCoordinator} (it needs the same AI-outside-tx structure as the composer); this
 * interface is the rest of the post-generation lifecycle.
 *
 * <p>Cross-package surface so the controller (in {@code api.controller}) injects the interface; the
 * single impl is module-internal per the style guide.
 */
public interface PlanWriteService {

  /** {@code GENERATED -> ACTIVE}; publishes {@code PlanAcceptedEvent}. Returns the plan id. */
  UUID acceptPlan(UUID planId);

  /**
   * {@code GENERATED -> REJECTED}; idempotent (re-rejecting a {@code REJECTED} plan is a 200
   * no-op). Sets {@code rejectedReason}; publishes {@code PlanRejectedEvent}.
   */
  UUID rejectPlan(UUID planId, String reason);

  /**
   * {@code ACTIVE -> ABANDONED}; sets {@code abandonedReason}; publishes {@code
   * PlanAbandonedEvent}.
   */
  UUID abandonPlan(UUID planId, String reason);

  /** Transition a single slot's state via the state machine. Returns the parent plan id. */
  UUID changeSlotState(UUID planId, UUID slotId, SlotState newState);

  /**
   * Accept a {@code MealPrepPlanReoptSuggestion}: mutate the live plan in place from the
   * suggestion's proposed assignments, mark the suggestion {@code ACCEPTED}, publish {@code
   * PlanSupersededEvent} (old) + {@code PlanGeneratedEvent} (new generation).
   */
  PlanReoptSuggestionDto acceptReoptSuggestion(UUID planId, UUID suggestionId);

  /** Reject a {@code MealPrepPlanReoptSuggestion}: mark {@code REJECTED}; no plan change. */
  PlanReoptSuggestionDto rejectReoptSuggestion(UUID planId, UUID suggestionId);

  /**
   * Daily expiry sweep (planner-10): flip every still-{@code PENDING} re-opt suggestion whose
   * {@code expiresAt} ({@code weekStartDate + 7 days}) is in the past to {@code EXPIRED} and mark
   * it swept, so a week-old plan's suggestion can no longer be accepted. Driven by {@code
   * PlannerReoptExpirySweepScheduler}; returns the number of rows transitioned. Per LLD
   * §Idempotency / §Flow 6.
   */
  int sweepExpiredReoptSuggestions();

  /**
   * Weekly PlanCompleted sweep (planner-3): load prior-week ({@code weekStartDate} before the
   * current week's start day) {@code ACTIVE} plans and, for each whose slots are <em>all</em> in a
   * terminal state ({@code EATEN} / {@code SKIPPED}), transition it to {@code COMPLETED} and
   * publish {@code PlanCompletedEvent}. A plan with any non-terminal slot is left {@code ACTIVE}
   * (the sweep never auto-abandons). Driven by {@code PlannerPlanCompletedSweepScheduler} every
   * Monday; returns the number of plans completed. Per LLD §Events line ~1152.
   */
  int sweepCompletedPlans();
}
