package com.example.mealprep.planner.domain.service.internal.scoring;

import com.example.mealprep.nutrition.api.dto.MacroTargetDto;
import com.example.mealprep.nutrition.api.dto.MicroTargetDto;
import com.example.mealprep.nutrition.api.dto.TargetsDto;
import com.example.mealprep.nutrition.domain.entity.EnforcementDirection;
import com.example.mealprep.planner.api.dto.CandidatePlan;
import com.example.mealprep.planner.api.dto.PlanCompositionContext;
import com.example.mealprep.planner.domain.service.internal.rollup.DailyMacroAggregator;
import com.example.mealprep.planner.domain.service.internal.rollup.DailyMacroTotals;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Nutrition-fit sub-score. Direction-aware deviation penalty mapped to a {@code [0,1]} fit, scored
 * <b>per day against the daily targets</b> and averaged over the days the candidate fills.
 *
 * <pre>
 *   direction_score(actual, target, direction):
 *     if target == null OR target == 0: 1.0
 *     deviation = (actual - target) / target
 *     UPPER_LIMIT:  penalty = max(0, deviation)
 *     LOWER_FLOOR:  penalty = max(0, -deviation)
 *     BOTH_BOUNDED: penalty = abs(deviation)
 *     return max(0, 1 - penalty)
 *   day_score   = MACRO_WEIGHT·mean(macro fits) + MICRO_WEIGHT·mean(micro fits)   (whichever configured)
 *   sub_score   = mean(day_score over filled days)
 * </pre>
 *
 * <p><b>Per-serving, per-person.</b> Per-day totals come from the shared {@link
 * DailyMacroAggregator}, which sums one serving per slot for the household's primary user (targets
 * are that user's daily intake). Scoring weekly actuals against daily targets — the pre-wiring
 * behaviour — compared a week's intake to a day's target; scoring per day fixes that.
 *
 * <p><b>Macro vs micro blend.</b> The handful of macros (calories + protein carry the most weight
 * in practice) and the ~30 micro targets are blended {@link #MACRO_WEIGHT}/{@link #MICRO_WEIGHT} so
 * a long micro list cannot drown out calories/protein. A micro {@code targetValue} is a daily floor
 * (want ≥), an {@code upperLimit} a cap (want ≤); both set ⇒ the day must satisfy both.
 *
 * <p><b>Key-alignment dependency.</b> A micro absent from the day's totals scores as a shortfall
 * (actual 0). That is only correct once recipe micro keys (verbatim from the USDA/OFF source) are
 * normalised to the target {@code nutrientKey}s — otherwise a key mismatch reads as a false
 * shortfall. That normalisation is the Stage-4 data-path work.
 *
 * <p>Aggregation is against the household's primary user only ({@link
 * ScoringSupport#primaryUserId}) — the per-eater average is deferred to a calibration pass. A plan
 * with no targets row, or no configured macro/micro targets, scores a vacuous {@code 1.0}.
 */
@Component
class NutritionSubScore implements SubScoreCalculator {

  /**
   * Within-nutrition blend of the daily macro-fit and micro-coverage terms. Macros dominate; micros
   * are a meaningful secondary nudge. Tunable — promote to {@code PlannerProperties} during weight
   * calibration if the split needs per-environment control.
   */
  private static final BigDecimal MACRO_WEIGHT = new BigDecimal("0.60");

  private static final BigDecimal MICRO_WEIGHT = new BigDecimal("0.40");

  private final DailyMacroAggregator macroAggregator;

  NutritionSubScore(DailyMacroAggregator macroAggregator) {
    this.macroAggregator = macroAggregator;
  }

  @Override
  public String name() {
    return "nutrition";
  }

  @Override
  public BigDecimal compute(CandidatePlan plan, PlanCompositionContext ctx) {
    TargetsDto targets = targetsFor(ctx);
    if (targets == null) {
      return BigDecimal.ONE; // no targets configured → vacuous fit = 1.0
    }
    return scoreFromTotals(macroAggregator.aggregateByDate(plan, ctx), targets);
  }

  /** The primary user's targets row for {@code ctx}, or {@code null} when none is configured. */
  TargetsDto targetsFor(PlanCompositionContext ctx) {
    UUID primary = ScoringSupport.primaryUserId(ctx);
    return primary == null ? null : ctx.nutritionByUserId().get(primary);
  }

  /**
   * Score the nutrition sub-score from already-aggregated per-day totals against {@code targets} —
   * the exact day-scoring + blend + mean the whole-plan {@link #compute} runs once it has {@code
   * byDate}. The incremental Stage-A scorer carries the SAME per-day totals (built by the shared
   * {@link DailyMacroAggregator} per-slot arithmetic), so calling this finalises byte-identically.
   * {@code targets} must be non-null (caller short-circuits the no-targets case to {@code 1.0}).
   */
  BigDecimal scoreFromTotals(Map<LocalDate, DailyMacroTotals> byDate, TargetsDto targets) {
    if (byDate.isEmpty()) {
      return BigDecimal.ONE; // nothing to score
    }

    List<BigDecimal> dayScores = new ArrayList<>();
    for (DailyMacroTotals day : byDate.values()) {
      BigDecimal macroFit = macroFitScore(day, targets); // null if no macro target configured
      BigDecimal microFit = microFitScore(day, targets); // null if no micro target configured
      BigDecimal blended = blend(macroFit, microFit);
      if (blended != null) {
        dayScores.add(blended);
      }
    }
    if (dayScores.isEmpty()) {
      return BigDecimal.ONE; // neither macros nor micros configured → vacuous 1.0
    }
    return mean(dayScores);
  }

  /** Mean direction-fit over calories + each configured macro for one day; {@code null} if none. */
  private static BigDecimal macroFitScore(DailyMacroTotals day, TargetsDto targets) {
    List<BigDecimal> scores = new ArrayList<>();
    if (targets.calories() != null && targets.calories().dailyTarget() > 0) {
      scores.add(
          directionScore(
              BigDecimal.valueOf(day.kcal()),
              BigDecimal.valueOf(targets.calories().dailyTarget()),
              targets.calories().direction()));
    }
    addMacro(scores, day.proteinG(), targets.protein());
    addMacro(scores, day.carbsG(), targets.carbs());
    addMacro(scores, day.fatG(), targets.fat());
    addMacro(scores, day.fibreG(), targets.fibre());
    addMacro(scores, day.saturatedFatG(), targets.satFat());
    return scores.isEmpty() ? null : mean(scores);
  }

  /**
   * Mean coverage over each configured micro target for one day; {@code null} if none configured.
   */
  private static BigDecimal microFitScore(DailyMacroTotals day, TargetsDto targets) {
    List<MicroTargetDto> micros = targets.microTargets();
    if (micros == null || micros.isEmpty()) {
      return null;
    }
    Map<String, BigDecimal> actuals = day.micros() == null ? Map.of() : day.micros();
    List<BigDecimal> scores = new ArrayList<>();
    for (MicroTargetDto micro : micros) {
      if (micro == null || micro.nutrientKey() == null) {
        continue;
      }
      boolean hasFloor = micro.targetValue() != null;
      boolean hasCap = micro.upperLimit() != null;
      if (!hasFloor && !hasCap) {
        continue; // advisory entry with no bound → nothing to score
      }
      BigDecimal actual = actuals.getOrDefault(micro.nutrientKey(), BigDecimal.ZERO);
      BigDecimal score;
      if (hasFloor && hasCap) {
        // Must clear the floor AND stay under the cap → the worse of the two sub-scores.
        score =
            directionScore(actual, micro.targetValue(), EnforcementDirection.LOWER_FLOOR)
                .min(directionScore(actual, micro.upperLimit(), EnforcementDirection.UPPER_LIMIT));
      } else if (hasFloor) {
        score = directionScore(actual, micro.targetValue(), EnforcementDirection.LOWER_FLOOR);
      } else {
        score = directionScore(actual, micro.upperLimit(), EnforcementDirection.UPPER_LIMIT);
      }
      scores.add(score);
    }
    return scores.isEmpty() ? null : mean(scores);
  }

  private static BigDecimal blend(BigDecimal macroFit, BigDecimal microFit) {
    if (macroFit != null && microFit != null) {
      return macroFit.multiply(MACRO_WEIGHT).add(microFit.multiply(MICRO_WEIGHT));
    }
    if (macroFit != null) {
      return macroFit;
    }
    return microFit; // may be null → caller skips the day
  }

  private static void addMacro(List<BigDecimal> scores, BigDecimal actual, MacroTargetDto target) {
    if (target == null || target.targetG() == null) {
      return; // macro not configured → excluded from the mean
    }
    scores.add(directionScore(nz(actual), target.targetG(), target.direction()));
  }

  /** LOCKED direction-aware deviation penalty mapped to a {@code [0,1]} fit score. */
  static BigDecimal directionScore(
      BigDecimal actual, BigDecimal target, EnforcementDirection direction) {
    if (target == null || target.compareTo(BigDecimal.ZERO) == 0) {
      return BigDecimal.ONE;
    }
    BigDecimal deviation = nz(actual).subtract(target).divide(target, 6, RoundingMode.HALF_UP);
    BigDecimal penalty;
    switch (direction) {
      case UPPER_LIMIT -> penalty = deviation.max(BigDecimal.ZERO);
      case LOWER_FLOOR -> penalty = deviation.negate().max(BigDecimal.ZERO);
      case BOTH_BOUNDED -> penalty = deviation.abs();
      default -> penalty = deviation.abs();
    }
    return BigDecimal.ONE.subtract(penalty).max(BigDecimal.ZERO);
  }

  private static BigDecimal mean(List<BigDecimal> scores) {
    BigDecimal sum = BigDecimal.ZERO;
    for (BigDecimal s : scores) {
      sum = sum.add(s);
    }
    return sum.divide(BigDecimal.valueOf(scores.size()), 6, RoundingMode.HALF_UP);
  }

  private static BigDecimal nz(BigDecimal v) {
    return v == null ? BigDecimal.ZERO : v;
  }
}
