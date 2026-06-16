package com.example.mealprep.planner.domain.service.internal.scoring;

import com.example.mealprep.nutrition.api.dto.CandidateDailyRollupDto;
import com.example.mealprep.nutrition.api.dto.CandidatePlanRollupDto;
import com.example.mealprep.nutrition.api.dto.FloorGateResultDto;
import com.example.mealprep.nutrition.domain.entity.ActivityLevel;
import com.example.mealprep.nutrition.domain.service.NutritionFloorGateService;
import com.example.mealprep.planner.api.dto.CandidatePlan;
import com.example.mealprep.planner.api.dto.PlanCompositionContext;
import com.example.mealprep.planner.domain.service.internal.rollup.DailyMacroAggregator;
import com.example.mealprep.planner.domain.service.internal.rollup.DailyMacroTotals;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Multiplicative nutrition hard-floor kill-switch. Delegates to {@link NutritionFloorGateService}
 * (shipped by nutrition-01g) — builds a {@link CandidatePlanRollupDto} (per-day macro totals) and
 * reads {@link FloorGateResultDto#passed()}.
 *
 * <p><b>planner-01f refactor</b>: the per-day macro aggregation is delegated to the shared {@link
 * DailyMacroAggregator} (also used by 01f's {@code RollupBuilder}) so the gate and the rollup never
 * drift. Now that {@code RecipeVersionDto.nutritionPerServing} is wired, the aggregator returns the
 * day's real macro + micro totals (per-serving, one serving per slot), so the gate enforces hard
 * floors against actual nutrition. A failed gate collapses the candidate's composite to 0 (it is a
 * 0/1 multiplier) rather than removing it — an unreachable hard floor degrades to a best-effort
 * plan, it does not zero out generation.
 *
 * <p>The service returns {@code passed=true} when the user has no targets row (nutrition-01g spec).
 * An empty / null plan passes vacuously (no rollup days → cannot build a {@code @Size(min=1)}
 * rollup). Aggregation is against the household's primary user only (see {@link
 * ScoringSupport#primaryUserId}).
 */
@Component
public class NutritionFloorGate {

  private final NutritionFloorGateService floorGateService;
  private final DailyMacroAggregator macroAggregator;

  NutritionFloorGate(
      NutritionFloorGateService floorGateService, DailyMacroAggregator macroAggregator) {
    this.floorGateService = floorGateService;
    this.macroAggregator = macroAggregator;
  }

  public boolean passes(CandidatePlan plan, PlanCompositionContext ctx) {
    if (plan == null || plan.assignments() == null || plan.assignments().isEmpty()) {
      return true; // vacuous — no days to evaluate
    }
    UUID primary = ScoringSupport.primaryUserId(ctx);
    if (primary == null) {
      return true; // no user to evaluate against
    }

    Map<LocalDate, DailyMacroTotals> byDate = macroAggregator.aggregateByDate(plan, ctx);
    if (byDate.isEmpty()) {
      return true;
    }

    List<CandidateDailyRollupDto> perDay = new ArrayList<>();
    LocalDate start = null;
    LocalDate end = null;
    for (Map.Entry<LocalDate, DailyMacroTotals> e : byDate.entrySet()) {
      LocalDate date = e.getKey();
      if (start == null) {
        start = date;
      }
      end = date;
      perDay.add(toDailyRollupDto(date, e.getValue()));
    }

    CandidatePlanRollupDto rollup = new CandidatePlanRollupDto(start, end, perDay);
    FloorGateResultDto result = floorGateService.evaluate(primary, rollup);
    return result.passed();
  }

  private CandidateDailyRollupDto toDailyRollupDto(LocalDate date, DailyMacroTotals totals) {
    // Maps the shared per-day totals (real per-serving macros + micros, summed in
    // DailyMacroAggregator) onto the gate service's DTO.
    Map<String, BigDecimal> micros = totals.micros() == null ? Map.of() : totals.micros();
    return new CandidateDailyRollupDto(
        date,
        ActivityLevel.LIGHT_ACTIVITY,
        totals.kcal(),
        nz(totals.proteinG()),
        nz(totals.carbsG()),
        nz(totals.fatG()),
        nz(totals.fibreG()),
        micros);
  }

  private static BigDecimal nz(BigDecimal v) {
    return v == null ? BigDecimal.ZERO : v;
  }
}
