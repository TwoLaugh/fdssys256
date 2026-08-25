package com.example.mealprep.planner.domain.service.internal.composer;

import com.example.mealprep.planner.api.dto.CandidatePlan;
import com.example.mealprep.planner.api.dto.GeneratePlanRequest;
import com.example.mealprep.planner.api.dto.MealSlotSkeleton;
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
import com.example.mealprep.planner.domain.service.internal.PerMealCalorieTargets;
import com.example.mealprep.planner.domain.service.internal.PortionScaler;
import com.example.mealprep.planner.domain.service.internal.lifecycle.PlanStateMachine;
import com.example.mealprep.recipe.api.dto.RecipeDto;
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

    // Group assignments by calendar date. planner_days is UNIQUE on (plan_id, on_date), so exactly
    // ONE Day row may exist per date — a multi-kind day (breakfast/lunch/dinner/snack on the same
    // date) yields several SlotAssignments sharing that onDate and they MUST collapse into one Day.
    // (Keying by a.dayId() was a latent bug: the slot skeleton mints a distinct dayId per slot
    // kind,
    // so a real multi-kind plan produced duplicate (plan_id, on_date) rows -> 23505. It went
    // unnoticed while NoOpRecipePoolSource kept every plan empty — no slots, no day rows.)
    // Portion factor (Phase 1b): the per-person servings each main scales to, computed from the
    // SAME per-meal calorie targets the rollup's coverage uses (via PortionScaler) so the persisted
    // value never disagrees with the scaled coverage. Persisted so grocery + UI reflect it.
    Map<String, Integer> mealCalTargets = PerMealCalorieTargets.forContext(context);
    Map<String, BigDecimal> mealProteinTargets = PerMealCalorieTargets.proteinForContext(context);
    Map<UUID, Integer> recipeKcal = recipeKcalById(context);
    Map<UUID, BigDecimal> recipeProtein = recipeProteinById(context);

    // An assignment carries only the slot ids; eaters, shared flag, label and time budget live on
    // the context's skeletons. Persist them from there, keyed by slotId, so slot rows leave here
    // with the real composition. Empty eaters on a persisted slot broke every downstream reader at
    // once: the intake prefill listener saw nobody to prefill, the UI showed "0 eating", and the
    // re-opt context rebuild (which reads these rows back into skeletons) lost the household.
    Map<UUID, MealSlotSkeleton> skeletonsBySlotId = skeletonsBySlotId(context);
    List<UUID> allEaters = eaterUnion(context);

    Map<java.time.LocalDate, Day> daysByDate = new LinkedHashMap<>();
    List<SlotAssignment> assignments =
        chosen.assignments() == null ? List.of() : chosen.assignments();
    List<SlotAssignment> ordered = new ArrayList<>(assignments);
    ordered.sort(
        java.util.Comparator.comparing(SlotAssignment::onDate)
            .thenComparingInt(SlotAssignment::slotIndex));

    for (SlotAssignment a : ordered) {
      Day day =
          daysByDate.computeIfAbsent(
              a.onDate(),
              onDate -> {
                Day d =
                    Day.builder()
                        .id(UUID.randomUUID())
                        .plan(plan)
                        .onDate(onDate)
                        .slots(new ArrayList<>())
                        .build();
                plan.getDays().add(d);
                return d;
              });

      // No matching skeleton should not happen on the compose path; degrade to a shared slot
      // eaten by everyone the context knows rather than an empty row.
      MealSlotSkeleton skel = skeletonsBySlotId.get(a.slotId());
      MealSlot slot =
          MealSlot.builder()
              .id(UUID.randomUUID())
              .day(day)
              .plan(plan)
              .slotIndex(a.slotIndex())
              .kind(a.kind())
              .label(
                  skel != null && skel.label() != null
                      ? skel.label()
                      : (a.kind() != null ? a.kind().name() : "MEAL"))
              .timeBudgetMin(skel != null ? skel.timeBudgetMin() : 0)
              .shared(skel == null || skel.shared())
              .eaters(
                  skel != null && skel.eaters() != null
                      ? new ArrayList<>(skel.eaters())
                      : new ArrayList<>(allEaters))
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
                .additions(new ArrayList<>(a.additions()))
                // Persist the PortionOptimizer's finalise-time factor when the chosen plan carries
                // one (the optimiser sized the day jointly against all macro targets); otherwise
                // fall back to the calorie-only PortionScaler computation so the persisted value
                // matches the rollup's scaled coverage for any unoptimised assignment.
                .portionFactor(
                    a.portionFactor() != null
                        ? a.portionFactor()
                        : BigDecimal.valueOf(
                            a.kind() == null
                                ? 1.0
                                : PortionScaler.factor(
                                    recipeKcal.getOrDefault(a.recipeId(), 0),
                                    mealCalTargets.get(
                                        PortionScaler.normaliseKind(a.kind().name())),
                                    recipeProtein.get(a.recipeId()),
                                    mealProteinTargets.get(
                                        PortionScaler.normaliseKind(a.kind().name())))))
                .build();
        slot.setScheduledRecipe(sr);
      }
      day.getSlots().add(slot);
    }

    stateMachine.assertPlanTransitionAllowed(PlanStatus.DRAFT, PlanStatus.GENERATED);
    plan.setStatus(PlanStatus.GENERATED);

    return planRepository.save(plan);
  }

  /** slotId → skeleton, so each persisted slot can pick up its configured composition. */
  private static Map<UUID, MealSlotSkeleton> skeletonsBySlotId(PlanCompositionContext ctx) {
    Map<UUID, MealSlotSkeleton> out = new LinkedHashMap<>();
    if (ctx == null || ctx.slotSkeletons() == null) {
      return out;
    }
    for (MealSlotSkeleton sk : ctx.slotSkeletons()) {
      if (sk != null && sk.slotId() != null) {
        out.putIfAbsent(sk.slotId(), sk);
      }
    }
    return out;
  }

  /** Every eater seen across the context's skeletons, in first-seen order. */
  private static List<UUID> eaterUnion(PlanCompositionContext ctx) {
    java.util.LinkedHashSet<UUID> out = new java.util.LinkedHashSet<>();
    if (ctx == null || ctx.slotSkeletons() == null) {
      return List.of();
    }
    for (MealSlotSkeleton sk : ctx.slotSkeletons()) {
      if (sk != null && sk.eaters() != null) {
        out.addAll(sk.eaters());
      }
    }
    return new ArrayList<>(out);
  }

  /** recipeId → per-serving calories from the composition pool, for the portion-factor maths. */
  private static Map<UUID, Integer> recipeKcalById(PlanCompositionContext ctx) {
    Map<UUID, Integer> out = new LinkedHashMap<>();
    if (ctx == null || ctx.recipePool() == null || ctx.recipePool().recipes() == null) {
      return out;
    }
    for (RecipeDto r : ctx.recipePool().recipes()) {
      if (r != null
          && r.id() != null
          && r.currentVersionBody() != null
          && r.currentVersionBody().nutritionPerServing() != null) {
        out.put(r.id(), r.currentVersionBody().nutritionPerServing().calories());
      }
    }
    return out;
  }

  /** recipeId → per-serving protein (g) from the pool, for the macro-aware portion-factor cap. */
  private static Map<UUID, BigDecimal> recipeProteinById(PlanCompositionContext ctx) {
    Map<UUID, BigDecimal> out = new LinkedHashMap<>();
    if (ctx == null || ctx.recipePool() == null || ctx.recipePool().recipes() == null) {
      return out;
    }
    for (RecipeDto r : ctx.recipePool().recipes()) {
      if (r != null
          && r.id() != null
          && r.currentVersionBody() != null
          && r.currentVersionBody().nutritionPerServing() != null
          && r.currentVersionBody().nutritionPerServing().proteinG() != null) {
        out.put(r.id(), r.currentVersionBody().nutritionPerServing().proteinG());
      }
    }
    return out;
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
