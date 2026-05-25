package com.example.mealprep.planner.domain.service.internal.composer;

import com.example.mealprep.planner.api.dto.CandidatePlan;
import com.example.mealprep.planner.api.dto.GeneratePlanRequest;
import com.example.mealprep.planner.api.dto.PlanCompositionContext;
import com.example.mealprep.planner.api.dto.RollupSummaryDocument;
import com.example.mealprep.planner.api.dto.ScoreBreakdownDocument;
import com.example.mealprep.planner.api.dto.SlotAssignment;
import com.example.mealprep.planner.domain.entity.Day;
import com.example.mealprep.planner.domain.entity.MealSlot;
import com.example.mealprep.planner.domain.entity.Plan;
import com.example.mealprep.planner.domain.entity.PlanStatus;
import com.example.mealprep.planner.domain.entity.ScheduledRecipe;
import com.example.mealprep.planner.domain.entity.SlotState;
import com.example.mealprep.planner.domain.entity.TriggerKind;
import com.example.mealprep.planner.domain.repository.PlanRepository;
import com.example.mealprep.planner.domain.service.internal.lifecycle.PlanStateMachine;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Stage-D&rarr;persist step of the composer (planner-01j). Materialises the chosen {@link
 * CandidatePlan} into the durable {@code Plan} &rarr; {@code Day} &rarr; {@code MealSlot} &rarr;
 * {@code ScheduledRecipe} aggregate and writes it via {@code save(plan)} (cascade-all).
 *
 * <p>The new plan starts {@code DRAFT} and is moved to {@code GENERATED} through {@link
 * PlanStateMachine#assertPlanTransitionAllowed} so the lifecycle invariant is enforced at the one
 * write boundary (LLD §Flow 1 step 12). Called from inside {@code PlanComposer.compose}'s single
 * {@code @Transactional} — no own transaction annotation (it would be a same-bean self-invocation
 * no-op anyway).
 */
@Component
class PlanPersister {

  private final PlanRepository planRepository;
  private final PlanStateMachine stateMachine;

  PlanPersister(PlanRepository planRepository, PlanStateMachine stateMachine) {
    this.planRepository = planRepository;
    this.stateMachine = stateMachine;
  }

  /**
   * Build + persist the plan aggregate.
   *
   * @param chosen the Stage-C-selected (and Phase-2-mutated) candidate
   * @param request the originating generate request
   * @param context the frozen composition context (trace + decision ids)
   * @param planId the pre-allocated plan id (allocated by the composer so Stage-D requests can
   *     reference it)
   * @param aiAugmented whether Phase 2 applied any augmentation
   * @param qualityWarning whether a degradation occurred (empty pool, Stage-A greedy, Stage-D
   *     unavailable)
   * @param coldStart whether the cold-start gate fired (catalogue below the planning minimum, a
   *     discovery fill was attempted before Stage A) — surfaced to the UI per meal-planner.md
   * @return the persisted, flushed {@code Plan}
   */
  Plan persist(
      CandidatePlan chosen,
      GeneratePlanRequest request,
      PlanCompositionContext context,
      UUID planId,
      RollupSummaryDocument rollupSummary,
      boolean aiAugmented,
      boolean qualityWarning,
      boolean coldStart) {

    int generation =
        1
            + planRepository.countByHouseholdIdAndWeekStartDate(
                request.householdId(), request.weekStartDate());
    UUID replacesPlanId =
        planRepository
            .findFirstByHouseholdIdAndWeekStartDateAndStatus(
                request.householdId(), request.weekStartDate(), PlanStatus.ACTIVE)
            .map(Plan::getId)
            .orElse(null);

    ScoreBreakdownDocument scoreBreakdown =
        chosen.scoreResult() != null && chosen.scoreResult().breakdown() != null
            ? chosen.scoreResult().breakdown()
            : zeroBreakdown();

    Plan plan =
        Plan.builder()
            .id(planId)
            .householdId(request.householdId())
            .weekStartDate(request.weekStartDate())
            .generation(generation)
            .replacesPlanId(replacesPlanId)
            .status(PlanStatus.DRAFT)
            .triggerKind(TriggerKind.USER_INITIATED)
            .qualityWarning(qualityWarning)
            .coldStart(coldStart)
            .aiAugmented(aiAugmented)
            .traceId(context.traceId())
            .decisionId(context.decisionId() != null ? context.decisionId() : context.traceId())
            .scoreBreakdown(scoreBreakdown)
            .rollupSummary(rollupSummary)
            .days(new ArrayList<>())
            .build();

    // Group assignments by day, preserving week order.
    Map<UUID, Day> daysByDayId = new LinkedHashMap<>();
    List<SlotAssignment> assignments =
        chosen.assignments() == null ? List.of() : chosen.assignments();
    List<SlotAssignment> ordered = new ArrayList<>(assignments);
    ordered.sort(
        java.util.Comparator.comparing(SlotAssignment::onDate)
            .thenComparingInt(SlotAssignment::slotIndex));

    for (SlotAssignment a : ordered) {
      Day day =
          daysByDayId.computeIfAbsent(
              a.dayId(),
              dayId -> {
                Day d =
                    Day.builder()
                        .id(UUID.randomUUID())
                        .plan(plan)
                        .onDate(a.onDate())
                        .slots(new ArrayList<>())
                        .build();
                plan.getDays().add(d);
                return d;
              });

      MealSlot slot =
          MealSlot.builder()
              .id(UUID.randomUUID())
              .day(day)
              .plan(plan)
              .slotIndex(a.slotIndex())
              .kind(a.kind())
              .label(a.kind() != null ? a.kind().name() : "MEAL")
              .timeBudgetMin(0)
              .shared(true)
              .eaters(new ArrayList<>())
              .state(SlotState.PLANNED)
              .build();

      if (a.recipeId() != null) {
        ScheduledRecipe sr =
            ScheduledRecipe.builder()
                .id(UUID.randomUUID())
                .slot(slot)
                .recipeId(a.recipeId())
                .recipeVersionId(a.recipeVersionId() != null ? a.recipeVersionId() : a.recipeId())
                .recipeBranchId(a.recipeBranchId() != null ? a.recipeBranchId() : a.recipeId())
                .servings(a.servings() > 0 ? a.servings() : 1)
                .phase2Addition(false)
                .build();
        slot.setScheduledRecipe(sr);
      }
      day.getSlots().add(slot);
    }

    stateMachine.assertPlanTransitionAllowed(PlanStatus.DRAFT, PlanStatus.GENERATED);
    plan.setStatus(PlanStatus.GENERATED);

    return planRepository.save(plan);
  }

  private static ScoreBreakdownDocument zeroBreakdown() {
    return new ScoreBreakdownDocument(
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        true,
        true,
        "v1-uniform");
  }
}
