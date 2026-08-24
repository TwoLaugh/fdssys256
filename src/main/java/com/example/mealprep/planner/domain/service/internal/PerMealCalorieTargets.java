package com.example.mealprep.planner.domain.service.internal;

import com.example.mealprep.nutrition.api.dto.PerMealDistributionDto;
import com.example.mealprep.nutrition.api.dto.TargetsDto;
import com.example.mealprep.planner.api.dto.MealSlotSkeleton;
import com.example.mealprep.planner.api.dto.PlanCompositionContext;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The primary eater's per-meal calorie targets, keyed by normalised slot kind ({@code
 * SNACKS}→{@code SNACK}). Single source for the {@code DailyMacroAggregator} (which scales
 * coverage) and {@code PlanPersister} (which persists the resulting {@code portionFactor}) so the
 * factor both compute via {@link PortionScaler} is identical. Empty when there are no targets / no
 * eaters → factor 1.0.
 */
public final class PerMealCalorieTargets {

  private PerMealCalorieTargets() {}

  public static Map<String, Integer> forContext(PlanCompositionContext ctx) {
    Map<String, Integer> out = new LinkedHashMap<>();
    if (ctx == null || ctx.nutritionByUserId() == null || ctx.slotSkeletons() == null) {
      return out;
    }
    UUID primary =
        ctx.slotSkeletons().stream()
            .map(MealSlotSkeleton::eaters)
            .filter(e -> e != null && !e.isEmpty())
            .map(e -> e.get(0))
            .findFirst()
            .orElse(null);
    TargetsDto t = primary == null ? null : ctx.nutritionByUserId().get(primary);
    if (t == null || t.perMealDistribution() == null) {
      return out;
    }
    for (PerMealDistributionDto m : t.perMealDistribution()) {
      if (m != null && m.mealSlot() != null && m.calorieTarget() > 0) {
        out.put(PortionScaler.normaliseKind(m.mealSlot().name()), m.calorieTarget());
      }
    }
    return out;
  }

  /**
   * The primary eater's per-meal PROTEIN targets (grams), keyed by normalised slot kind — the
   * companion to {@link #forContext} used by the macro-aware {@link PortionScaler#factor} to cap
   * scale-up on protein-dense recipes. Empty when there are no targets / no per-meal distribution →
   * the protein cap is disabled and scaling is calorie-only.
   */
  public static Map<String, BigDecimal> proteinForContext(PlanCompositionContext ctx) {
    Map<String, BigDecimal> out = new LinkedHashMap<>();
    if (ctx == null || ctx.nutritionByUserId() == null || ctx.slotSkeletons() == null) {
      return out;
    }
    UUID primary =
        ctx.slotSkeletons().stream()
            .map(MealSlotSkeleton::eaters)
            .filter(e -> e != null && !e.isEmpty())
            .map(e -> e.get(0))
            .findFirst()
            .orElse(null);
    TargetsDto t = primary == null ? null : ctx.nutritionByUserId().get(primary);
    if (t == null || t.perMealDistribution() == null) {
      return out;
    }
    for (PerMealDistributionDto m : t.perMealDistribution()) {
      if (m != null
          && m.mealSlot() != null
          && m.proteinTargetG() != null
          && m.proteinTargetG().signum() > 0) {
        out.put(PortionScaler.normaliseKind(m.mealSlot().name()), m.proteinTargetG());
      }
    }
    return out;
  }
}
