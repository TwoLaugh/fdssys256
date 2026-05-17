package com.example.mealprep.planner.domain.service.internal.scoring;

import com.example.mealprep.nutrition.api.dto.CandidateDailyRollupDto;
import com.example.mealprep.nutrition.api.dto.CandidatePlanRollupDto;
import com.example.mealprep.nutrition.api.dto.FloorGateResultDto;
import com.example.mealprep.nutrition.domain.entity.ActivityLevel;
import com.example.mealprep.nutrition.domain.service.NutritionFloorGateService;
import com.example.mealprep.planner.api.dto.CandidatePlan;
import com.example.mealprep.planner.api.dto.PlanCompositionContext;
import com.example.mealprep.planner.api.dto.SlotAssignment;
import com.example.mealprep.recipe.api.dto.RecipeDto;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Multiplicative nutrition hard-floor kill-switch. Delegates to {@link NutritionFloorGateService}
 * (shipped by nutrition-01g) — the gate builds an ad-hoc {@link CandidatePlanRollupDto} (per-day
 * macro totals) and reads {@link FloorGateResultDto#passed()}.
 *
 * <p>The service returns {@code passed=true} when the user has no targets row (nutrition-01g spec),
 * so 01e needs no special-case logic. An empty / null plan passes vacuously (no rollup days → the
 * gate cannot build a {@code @Size(min=1)} rollup, so it returns {@code true} without calling the
 * service). Aggregation is against the household's primary user only (household-default mode; see
 * {@link ScoringSupport#primaryUserId}).
 *
 * <p><b>01e codebase divergence — recipe nutrition not exposed</b>: {@code RecipeVersionDto} has no
 * {@code nutritionPerServing}, so every per-day macro total is {@code 0}. With zero macros a
 * configured LOWER_FLOOR would fail; this is the intended cold-start signal (parallels {@code
 * NutritionSubScore}). When recipe nutrition lands, wire it into {@link #dailyRollup}.
 */
@Component
class NutritionFloorGate {

  private final NutritionFloorGateService floorGateService;

  NutritionFloorGate(NutritionFloorGateService floorGateService) {
    this.floorGateService = floorGateService;
  }

  boolean passes(CandidatePlan plan, PlanCompositionContext ctx) {
    if (plan.assignments() == null || plan.assignments().isEmpty()) {
      return true; // vacuous — no days to evaluate
    }
    UUID primary = ScoringSupport.primaryUserId(ctx);
    if (primary == null) {
      return true; // no user to evaluate against
    }

    // group assignments by day → one CandidateDailyRollupDto per date (sorted for determinism)
    TreeMap<LocalDate, List<SlotAssignment>> byDate = new TreeMap<>();
    for (SlotAssignment a : plan.assignments()) {
      byDate.computeIfAbsent(a.onDate(), d -> new ArrayList<>()).add(a);
    }
    if (byDate.isEmpty()) {
      return true;
    }

    Map<UUID, RecipeDto> recipes = ScoringSupport.recipeIndex(ctx);
    List<CandidateDailyRollupDto> perDay = new ArrayList<>();
    for (Map.Entry<LocalDate, List<SlotAssignment>> e : byDate.entrySet()) {
      perDay.add(dailyRollup(e.getKey(), e.getValue(), recipes));
    }
    LocalDate start = byDate.firstKey();
    LocalDate end = byDate.lastKey();

    CandidatePlanRollupDto rollup = new CandidatePlanRollupDto(start, end, perDay);
    FloorGateResultDto result = floorGateService.evaluate(primary, rollup);
    return result.passed();
  }

  private CandidateDailyRollupDto dailyRollup(
      LocalDate date, List<SlotAssignment> slots, Map<UUID, RecipeDto> recipes) {
    // Recipe nutrition is not exposed on RecipeVersionDto in this codebase → all macro totals 0.
    // The seam to plug real per-serving macros in is here (multiply by a.servings()).
    return new CandidateDailyRollupDto(
        date,
        ActivityLevel.LIGHT_ACTIVITY,
        0,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        Map.of());
  }
}
