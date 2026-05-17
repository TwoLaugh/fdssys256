package com.example.mealprep.planner.domain.service.internal.rollup;

import com.example.mealprep.planner.api.dto.CandidatePlan;
import com.example.mealprep.planner.api.dto.DailyRollupDocument;
import com.example.mealprep.planner.api.dto.PlanCompositionContext;
import com.example.mealprep.planner.api.dto.RollupSummaryDocument;
import com.example.mealprep.planner.api.dto.ScoreResult;
import com.example.mealprep.planner.api.dto.SlotAssignment;
import com.example.mealprep.planner.api.dto.WeeklyRollupDocument;
import com.example.mealprep.planner.domain.service.internal.scoring.NutritionFloorGate;
import com.example.mealprep.recipe.api.dto.RecipeDto;
import com.example.mealprep.recipe.domain.entity.NutritionStatus;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Stage B rollup builder (planner-01f). Walks an already-loaded candidate plan against the loaded
 * composition context and emits the flat {@link RollupSummaryDocument}. Deterministic, no DB.
 *
 * <p>Aggregation is delegated to the shared {@link DailyMacroAggregator} / {@link
 * DailyCostAggregator} / {@link WeeklyCostConfidence} helpers (also used by the refactored 01e
 * {@code NutritionFloorGate} / {@code CostSubScore}) so the gate, the cost sub-score and the rollup
 * never drift.
 *
 * <p><b>Codebase divergences vs. the ticket's verbatim snippets</b> (the snippets assumed an
 * idealised LLD shape that does not match this codebase):
 *
 * <ul>
 *   <li>{@code RecipeVersionDto} has no {@code nutritionPerServing} → macros are 0 (01e behaviour
 *       preserved). The {@code staleIngredientCount} counts distinct recipes whose {@code
 *       RecipeDto.nutritionStatus != CALCULATED} (the codebase's nutrition-pending signal in lieu
 *       of a null JsonNode).
 *   <li>{@code SlotAssignment} has no {@code batchCookSessionId} field → {@code batchCookSessions}
 *       is 0 (no batch grouping is modelled on the assignment yet; 01j wires it).
 *   <li>{@code totalTimeMin} uses {@code recipe.currentVersionBody().metadata().totalTimeMins()}.
 *   <li>{@code varietyIndex} is read from {@code plan.scoreResult().breakdown().variety()} (never
 *       recomputed); {@code BigDecimal.ZERO} when {@link ScoreResult} is absent (fixtures that
 *       build {@code CandidatePlan} directly — ticket gotcha #8).
 *   <li>Violations v1: {@code "slot <kind>@<date> is unfilled"} per missing recipe, plus {@code
 *       "hard floor breach"} when the re-run {@link NutritionFloorGate} returns false.
 * </ul>
 */
@Component
class RollupBuilderImpl implements RollupBuilder {

  private final DailyMacroAggregator macroAggregator;
  private final DailyCostAggregator costAggregator;
  private final WeeklyCostConfidence costConfidence;
  private final NutritionFloorGate floorGate;

  RollupBuilderImpl(
      DailyMacroAggregator macroAggregator,
      DailyCostAggregator costAggregator,
      WeeklyCostConfidence costConfidence,
      NutritionFloorGate floorGate) {
    this.macroAggregator = macroAggregator;
    this.costAggregator = costAggregator;
    this.costConfidence = costConfidence;
    this.floorGate = floorGate;
  }

  @Override
  public RollupSummaryDocument build(CandidatePlan plan, PlanCompositionContext ctx) {
    Map<LocalDate, DailyMacroTotals> dailyMacros = macroAggregator.aggregateByDate(plan, ctx);
    Map<LocalDate, BigDecimal> dailyCosts = costAggregator.aggregateByDate(plan, ctx);
    Map<LocalDate, Integer> dailyTotalTimes = aggregateTotalTime(plan, ctx);
    Map<LocalDate, List<String>> dailyViolations = computeDailyViolations(plan, ctx);

    List<DailyRollupDocument> daily =
        dailyMacros.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(
                e ->
                    new DailyRollupDocument(
                        e.getKey(),
                        e.getValue().kcal(),
                        e.getValue().proteinG().setScale(1, RoundingMode.HALF_UP),
                        e.getValue().fatG().setScale(1, RoundingMode.HALF_UP),
                        e.getValue().carbsG().setScale(1, RoundingMode.HALF_UP),
                        e.getValue().fibreG().setScale(1, RoundingMode.HALF_UP),
                        dailyCosts.getOrDefault(e.getKey(), BigDecimal.ZERO),
                        dailyTotalTimes.getOrDefault(e.getKey(), 0),
                        dailyViolations.getOrDefault(e.getKey(), List.of())))
            .toList();

    WeeklyRollupDocument weekly = buildWeekly(plan, ctx, daily);
    return new RollupSummaryDocument(daily, weekly);
  }

  private WeeklyRollupDocument buildWeekly(
      CandidatePlan plan, PlanCompositionContext ctx, List<DailyRollupDocument> daily) {
    int kcalTotal = daily.stream().mapToInt(DailyRollupDocument::kcal).sum();
    int n = Math.max(1, daily.size());

    BigDecimal proteinAvg = average(daily, DailyRollupDocument::proteinG, n);
    BigDecimal fatAvg = average(daily, DailyRollupDocument::fatG, n);
    BigDecimal carbsAvg = average(daily, DailyRollupDocument::carbsG, n);

    BigDecimal costTotal =
        daily.stream().map(DailyRollupDocument::costGbp).reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal confidence = costConfidence.compute(plan, ctx);

    int staleCount = countStaleRecipes(plan, ctx);
    BigDecimal varietyIndex = resolveVarietyIndex(plan);
    int batchSessions =
        0; // no batchCookSessionId on SlotAssignment in this codebase (01j wires it)

    List<String> constraintViolations = aggregateConstraintViolations(plan, ctx, daily);

    return new WeeklyRollupDocument(
        kcalTotal,
        proteinAvg,
        fatAvg,
        carbsAvg,
        costTotal,
        confidence,
        staleCount,
        varietyIndex,
        batchSessions,
        constraintViolations);
  }

  private BigDecimal average(
      List<DailyRollupDocument> daily,
      java.util.function.Function<DailyRollupDocument, BigDecimal> field,
      int n) {
    return daily.stream()
        .map(field)
        .reduce(BigDecimal.ZERO, BigDecimal::add)
        .divide(BigDecimal.valueOf(n), 1, RoundingMode.HALF_UP);
  }

  private Map<LocalDate, Integer> aggregateTotalTime(
      CandidatePlan plan, PlanCompositionContext ctx) {
    Map<LocalDate, Integer> byDate = new TreeMap<>();
    if (plan == null || plan.assignments() == null) {
      return new LinkedHashMap<>();
    }
    Map<UUID, RecipeDto> byRecipeId = indexRecipes(ctx);
    for (SlotAssignment a : plan.assignments()) {
      LocalDate date = a.onDate();
      if (date == null) {
        continue;
      }
      byDate.putIfAbsent(date, 0);
      RecipeDto recipe = byRecipeId.get(a.recipeId());
      if (recipe == null
          || recipe.currentVersionBody() == null
          || recipe.currentVersionBody().metadata() == null) {
        continue;
      }
      byDate.merge(date, recipe.currentVersionBody().metadata().totalTimeMins(), Integer::sum);
    }
    return new LinkedHashMap<>(byDate);
  }

  private Map<LocalDate, List<String>> computeDailyViolations(
      CandidatePlan plan, PlanCompositionContext ctx) {
    Map<LocalDate, List<String>> byDate = new TreeMap<>();
    if (plan == null || plan.assignments() == null) {
      return new LinkedHashMap<>();
    }
    Map<UUID, RecipeDto> byRecipeId = indexRecipes(ctx);
    for (SlotAssignment a : plan.assignments()) {
      LocalDate date = a.onDate();
      if (date == null) {
        continue;
      }
      byDate.computeIfAbsent(date, d -> new ArrayList<>());
      if (byRecipeId.get(a.recipeId()) == null) {
        byDate.get(date).add("slot " + a.kind() + "@" + date + " is unfilled");
      }
    }
    Map<LocalDate, List<String>> out = new LinkedHashMap<>();
    for (Map.Entry<LocalDate, List<String>> e : byDate.entrySet()) {
      out.put(e.getKey(), List.copyOf(e.getValue()));
    }
    return out;
  }

  private List<String> aggregateConstraintViolations(
      CandidatePlan plan, PlanCompositionContext ctx, List<DailyRollupDocument> daily) {
    List<String> all = new ArrayList<>();
    for (DailyRollupDocument d : daily) {
      all.addAll(d.violations());
    }
    if (plan != null
        && plan.assignments() != null
        && !plan.assignments().isEmpty()
        && !floorGate.passes(plan, ctx)) {
      all.add("hard floor breach");
    }
    return List.copyOf(all);
  }

  private int countStaleRecipes(CandidatePlan plan, PlanCompositionContext ctx) {
    if (plan == null || plan.assignments() == null) {
      return 0;
    }
    Map<UUID, RecipeDto> byRecipeId = indexRecipes(ctx);
    java.util.Set<UUID> stale = new java.util.HashSet<>();
    for (SlotAssignment a : plan.assignments()) {
      RecipeDto recipe = byRecipeId.get(a.recipeId());
      if (recipe != null && recipe.nutritionStatus() != NutritionStatus.CALCULATED) {
        stale.add(recipe.id());
      }
    }
    return stale.size();
  }

  private BigDecimal resolveVarietyIndex(CandidatePlan plan) {
    if (plan == null || plan.scoreResult() == null || plan.scoreResult().breakdown() == null) {
      return BigDecimal.ZERO;
    }
    return Objects.requireNonNullElse(plan.scoreResult().breakdown().variety(), BigDecimal.ZERO);
  }

  private Map<UUID, RecipeDto> indexRecipes(PlanCompositionContext ctx) {
    Map<UUID, RecipeDto> index = new LinkedHashMap<>();
    if (ctx == null || ctx.recipePool() == null || ctx.recipePool().recipes() == null) {
      return index;
    }
    for (RecipeDto r : ctx.recipePool().recipes()) {
      if (r != null && r.id() != null) {
        index.putIfAbsent(r.id(), r);
      }
    }
    return index;
  }
}
