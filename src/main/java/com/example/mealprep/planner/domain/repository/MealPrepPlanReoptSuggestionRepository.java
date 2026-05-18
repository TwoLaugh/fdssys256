package com.example.mealprep.planner.domain.repository;

import com.example.mealprep.planner.domain.entity.MealPrepPlanReoptSuggestion;
import com.example.mealprep.planner.domain.entity.ReoptSuggestionStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository for {@link MealPrepPlanReoptSuggestion} (planner-01i). {@code public} so the in-module
 * {@code domain.service.internal.reopt} package injects it; cross-module isolation is enforced by
 * {@code PlannerBoundaryTest} (ArchUnit), same pattern as {@link PlanRepository}.
 *
 * <p>01i ships exactly the three reads the coordinator + sibling tickets need so 01j/01k land
 * additively: the idempotency lookup (#4), the budget count (#14), and the expiry-sweep scan (01l /
 * follow-up). Signatures are locked by the 01i ticket — 01j/01k hard-block on them.
 */
public interface MealPrepPlanReoptSuggestionRepository
    extends JpaRepository<MealPrepPlanReoptSuggestion, UUID> {

  /**
   * Idempotency check (invariant #4) — backed by {@code idx_planner_plan_reopt_plan_trigger_event}.
   */
  Optional<MealPrepPlanReoptSuggestion> findByPlanIdAndTriggerEventId(
      UUID planId, UUID triggerEventId);

  /**
   * Budget guard (invariant #14) — counts PENDING + REJECTED against {@code maxSuggestionsPerPlan}.
   */
  long countByPlanIdAndStatusIn(UUID planId, Collection<ReoptSuggestionStatus> statuses);

  /** Expiry sweep (01l / follow-up) — stale PENDING rows to flip to EXPIRED. */
  List<MealPrepPlanReoptSuggestion> findAllByStatusAndSweptFalseAndExpiresAtBefore(
      ReoptSuggestionStatus status, Instant cutoff);
}
