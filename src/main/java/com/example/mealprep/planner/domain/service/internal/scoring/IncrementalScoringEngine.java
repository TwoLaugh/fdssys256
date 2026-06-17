package com.example.mealprep.planner.domain.service.internal.scoring;

import com.example.mealprep.nutrition.api.dto.TargetsDto;
import com.example.mealprep.planner.api.dto.CandidatePlan;
import com.example.mealprep.planner.api.dto.MealSlotSkeleton;
import com.example.mealprep.planner.api.dto.PlanCompositionContext;
import com.example.mealprep.planner.api.dto.SlotAssignment;
import com.example.mealprep.planner.config.PlannerProperties;
import com.example.mealprep.planner.domain.service.internal.rollup.DailyMacroAggregator;
import com.example.mealprep.planner.domain.service.internal.rollup.DailyMacroTotals;
import com.example.mealprep.planner.domain.service.internal.rollup.IncrementalNutritionState;
import com.example.mealprep.recipe.api.dto.RecipeDto;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Incremental composite scorer for the Stage-A beam search. Carries {@link IncrementalScoreState}
 * running accumulators on each partial plan; appending one slot derives the child state in
 * O(1)-ish, and {@link #composite} finalises by reproducing each LOCKED sub-score's exact arithmetic
 * from the raw accumulators — so the result is byte-identical (BigDecimal {@code compareTo == 0}) to
 * {@link ScoringEngine#score}{@code .composite()} for the same plan.
 *
 * <p>Used ONLY for pruning during the search; {@code BeamSearchEngineImpl.finalise()} re-scores the
 * returned top-N with the exact {@link ScoringEngine}, so the persisted breakdown is always the
 * exact engine's output and any incremental bug can only mis-prune, never mis-persist.
 *
 * <p>Six contributors are incremental (preference, time, variety, batch, nutrition, nutrition floor
 * gate, plus the variety gate); COST and PROVISIONS remain whole-plan {@code compute(plan, ctx)}
 * calls in {@link #composite} (they early-out cheaply with no budget / empty inventory and are
 * additive-but-fiddly — a later pass). The composite reproduces {@link ScoringEngineImpl}'s weighted
 * sum + the two multiplicative gates exactly, sourced from the state for the incremental sub-scores
 * and from whole-plan {@code compute()} for cost / provisions.
 */
@Component
public class IncrementalScoringEngine implements BeamCandidateScorer {

  private final PreferenceSubScore preferenceSubScore;
  private final NutritionSubScore nutritionSubScore;
  private final VarietySubScore varietySubScore;
  private final TimeSubScore timeSubScore;
  private final BatchSubScore batchSubScore;
  private final CostSubScore costSubScore;
  private final ProvisionsSubScore provisionsSubScore;
  private final NutritionFloorGate nutritionFloorGate;
  private final VarietyGate varietyGate;
  private final DailyMacroAggregator macroAggregator;
  private final PlannerProperties properties;

  public IncrementalScoringEngine(
      PreferenceSubScore preferenceSubScore,
      NutritionSubScore nutritionSubScore,
      VarietySubScore varietySubScore,
      TimeSubScore timeSubScore,
      BatchSubScore batchSubScore,
      CostSubScore costSubScore,
      ProvisionsSubScore provisionsSubScore,
      NutritionFloorGate nutritionFloorGate,
      VarietyGate varietyGate,
      DailyMacroAggregator macroAggregator,
      PlannerProperties properties) {
    this.preferenceSubScore = preferenceSubScore;
    this.nutritionSubScore = nutritionSubScore;
    this.varietySubScore = varietySubScore;
    this.timeSubScore = timeSubScore;
    this.batchSubScore = batchSubScore;
    this.costSubScore = costSubScore;
    this.provisionsSubScore = provisionsSubScore;
    this.nutritionFloorGate = nutritionFloorGate;
    this.varietyGate = varietyGate;
    this.macroAggregator = macroAggregator;
    this.properties = properties;
  }

  /** Seed an empty-plan state for {@code ctx} (fresh per-day nutrition accumulator). */
  public IncrementalScoreState empty(PlanCompositionContext ctx) {
    return IncrementalScoreState.empty(macroAggregator.seedIncremental(ctx));
  }

  /**
   * Derive the child state from appending {@code a} to {@code parent}. Each accumulator folds in the
   * one slot using the SAME formula helper the whole-plan sub-score uses, so the resulting raw
   * accumulators equal a whole-plan walk over the parent's assignments plus this one.
   */
  public IncrementalScoreState append(
      IncrementalScoreState parent, SlotAssignment a, PlanCompositionContext ctx) {
    Map<UUID, MealSlotSkeleton> bySlotId = PreferenceSubScore.slotIndex(ctx);
    Map<UUID, RecipeDto> recipes = ScoringSupport.recipeIndex(ctx);

    // preference: + the exact per-slot taste score (memoised per recipe|slot).
    BigDecimal preferenceSum =
        parent.preferenceSum.add(preferenceSubScore.perSlotScore(a, ctx, bySlotId));
    int preferenceCount = parent.preferenceCount + 1;

    // time: + the exact per-slot time-fit score.
    BigDecimal timeSum = parent.timeSum.add(TimeSubScore.perSlotScore(a, bySlotId, recipes));
    int timeCount = parent.timeCount + 1;

    // variety: fold this recipe's distinct cuisine/protein/method values into copied sets.
    Set<String> cuisines = new HashSet<>(parent.cuisines);
    Set<String> proteins = new HashSet<>(parent.proteins);
    Set<String> methods = new HashSet<>(parent.methods);
    RecipeDto recipe = ScoringSupport.findRecipe(recipes, a.recipeId()).orElse(null);
    VarietySubScore.addSlotDimensions(recipe, cuisines, proteins, methods);

    // batch: running slot count + sawNoBatch + distinct non-null sessions (always empty in 01e).
    int batchSlotCount = parent.batchSlotCount + 1;
    boolean sawNoBatch = parent.sawNoBatch;
    Set<UUID> distinctSessions = parent.distinctSessions;
    UUID sessionId = BatchSubScore.batchSessionId(a);
    if (sessionId == null) {
      sawNoBatch = true;
    } else {
      distinctSessions = new HashSet<>(parent.distinctSessions);
      distinctSessions.add(sessionId);
    }

    // nutrition + floor gate: fold the slot's per-serving nutrition into the per-day totals.
    IncrementalNutritionState nutrition = parent.nutrition.append(a);

    // variety gate: running per-recipe count; trip the fail flag once any recipe exceeds maxRepeat
    // (null recipeId is excluded, matching VarietyGate.passes).
    Map<UUID, Integer> recipeCounts = parent.recipeCounts;
    boolean varietyGateFailed = parent.varietyGateFailed;
    if (a.recipeId() != null) {
      recipeCounts = new HashMap<>(parent.recipeCounts);
      int next = recipeCounts.merge(a.recipeId(), 1, Integer::sum);
      if (next > varietyGate.maxRepeat()) {
        varietyGateFailed = true;
      }
    }

    return new IncrementalScoreState(
        preferenceSum,
        preferenceCount,
        timeSum,
        timeCount,
        cuisines,
        proteins,
        methods,
        batchSlotCount,
        sawNoBatch,
        distinctSessions,
        nutrition,
        recipeCounts,
        varietyGateFailed);
  }

  /**
   * Finalise the composite for {@code state} — byte-identical to {@link ScoringEngine#score}{@code
   * .composite()} for the same plan. Reproduces {@link ScoringEngineImpl}'s weighted sum and the two
   * multiplicative gates: the incremental sub-scores from the raw accumulators, cost / provisions
   * from whole-plan {@code compute(plan, ctx)}.
   */
  public BigDecimal composite(
      IncrementalScoreState state, CandidatePlan plan, PlanCompositionContext ctx) {
    BigDecimal preference = finalisePreference(state);
    BigDecimal time = finaliseTime(state);
    BigDecimal variety = finaliseVariety(state);
    BigDecimal batch = finaliseBatch(state);
    BigDecimal nutrition = finaliseNutrition(state, ctx);
    BigDecimal cost = costSubScore.compute(plan, ctx);
    BigDecimal provisions = provisionsSubScore.compute(plan, ctx);

    PlannerProperties.ScoringWeights w = properties.weights();
    BigDecimal unweighted =
        preference
            .multiply(w.preference())
            .add(nutrition.multiply(w.nutrition()))
            .add(cost.multiply(w.cost()))
            .add(variety.multiply(w.variety()))
            .add(time.multiply(w.time()))
            .add(batch.multiply(w.batch()))
            .add(provisions.multiply(w.provisions()));

    boolean floorPassed = finaliseFloorGate(state, plan, ctx);
    boolean varietyPassed = !state.varietyGateFailed;
    BigDecimal gateFactor = (floorPassed && varietyPassed) ? BigDecimal.ONE : BigDecimal.ZERO;
    return unweighted.multiply(gateFactor).setScale(6, RoundingMode.HALF_UP);
  }

  // ---- BeamCandidateScorer adapter (opaque Object state for the beam) --------------------------

  @Override
  public Object emptyState(PlanCompositionContext ctx) {
    return empty(ctx);
  }

  @Override
  public Object append(Object parentState, SlotAssignment a, PlanCompositionContext ctx) {
    return append((IncrementalScoreState) parentState, a, ctx);
  }

  @Override
  public BigDecimal composite(Object state, CandidatePlan planView, PlanCompositionContext ctx) {
    return composite((IncrementalScoreState) state, planView, ctx);
  }

  // ---- finalize helpers (reproduce each LOCKED sub-score's exact arithmetic) -------------------

  private BigDecimal finalisePreference(IncrementalScoreState state) {
    if (state.preferenceCount == 0) {
      return PreferenceSubScore.NEUTRAL; // mean over zero slots is neutral
    }
    return state.preferenceSum.divide(
        BigDecimal.valueOf(state.preferenceCount), 6, RoundingMode.HALF_UP);
  }

  private BigDecimal finaliseTime(IncrementalScoreState state) {
    if (state.timeCount == 0) {
      return BigDecimal.ONE;
    }
    return state.timeSum.divide(BigDecimal.valueOf(state.timeCount), 6, RoundingMode.HALF_UP);
  }

  private BigDecimal finaliseVariety(IncrementalScoreState state) {
    return varietySubScore.finalScore(
        state.cuisines.size(),
        state.proteins.size(),
        state.methods.size(),
        varietySubScore.varietyTargets());
  }

  private BigDecimal finaliseBatch(IncrementalScoreState state) {
    if (state.batchSlotCount == 0) {
      return BigDecimal.ONE; // empty plan → vacuous
    }
    return BatchSubScore.finalScore(
        state.batchSlotCount, state.distinctSessions.size(), state.sawNoBatch);
  }

  private BigDecimal finaliseNutrition(IncrementalScoreState state, PlanCompositionContext ctx) {
    TargetsDto targets = nutritionSubScore.targetsFor(ctx);
    if (targets == null) {
      return BigDecimal.ONE; // no targets configured → vacuous fit = 1.0
    }
    Map<LocalDate, DailyMacroTotals> byDate = state.nutrition.toByDate();
    return nutritionSubScore.scoreFromTotals(byDate, targets);
  }

  private boolean finaliseFloorGate(
      IncrementalScoreState state, CandidatePlan plan, PlanCompositionContext ctx) {
    // Mirror NutritionFloorGate.passes vacuous guards exactly.
    if (plan.assignments() == null || plan.assignments().isEmpty()) {
      return true;
    }
    UUID primary = ScoringSupport.primaryUserId(ctx);
    if (primary == null) {
      return true;
    }
    return nutritionFloorGate.passesForTotals(primary, state.nutrition.toByDate());
  }
}
