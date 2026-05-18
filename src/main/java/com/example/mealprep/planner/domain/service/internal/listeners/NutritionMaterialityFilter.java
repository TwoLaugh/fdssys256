package com.example.mealprep.planner.domain.service.internal.listeners;

import com.example.mealprep.nutrition.event.NutritionIntakeDivergedEvent;
import com.example.mealprep.planner.config.PlannerProperties;
import com.example.mealprep.planner.domain.entity.Day;
import com.example.mealprep.planner.domain.entity.MealSlot;
import com.example.mealprep.planner.domain.entity.Plan;
import com.example.mealprep.planner.domain.entity.SlotState;
import java.math.BigDecimal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Decides whether a {@link NutritionIntakeDivergedEvent} materially affects a plan (planner-01k §4
 * + §9). Pure arithmetic on the event payload + the plan's slot states — no DB calls.
 *
 * <p>Material iff BOTH:
 *
 * <ol>
 *   <li>The largest absolute per-macro percent-variance in {@link
 *       NutritionIntakeDivergedEvent#summary()} meets or exceeds {@link
 *       PlannerProperties.Materiality#nutritionVariancePct()} (default 0.15 == 15%, per HLD), AND
 *   <li>The plan still has at least {@link
 *       PlannerProperties.Materiality#nutritionMinRedistributableMeals()} (default 3)
 *       unplanned/unpinned meals left in the week to redistribute the macro correction over — below
 *       that there are not enough degrees of freedom for a re-opt to meaningfully rebalance.
 * </ol>
 *
 * <p>An event with an empty {@code divergedMacros} set means a previously-diverged macro has
 * resolved back into range — that is the "divergence cleared" signal, never a re-opt trigger
 * (immaterial).
 *
 * <p>{@code percentVariance} is in fractional units ({@code 0.20} == +20%); we compare on absolute
 * value so a {@code -0.20} undershoot is treated identically to a {@code +0.20} overshoot.
 */
@Component
class NutritionMaterialityFilter {

  private static final Logger log = LoggerFactory.getLogger(NutritionMaterialityFilter.class);

  private final PlannerProperties properties;

  NutritionMaterialityFilter(PlannerProperties properties) {
    this.properties = properties;
  }

  boolean isMaterial(NutritionIntakeDivergedEvent event, Plan plan) {
    // Empty diverged set == a prior divergence resolved. Not a re-opt trigger.
    if (event.divergedMacros() == null || event.divergedMacros().isEmpty()) {
      return false;
    }
    if (event.summary() == null || event.summary().percentVariance() == null) {
      return false;
    }

    BigDecimal threshold = properties.materiality().nutritionVariancePct();
    BigDecimal maxAbsVariance = BigDecimal.ZERO;
    for (BigDecimal v : event.summary().percentVariance().values()) {
      if (v == null) {
        continue;
      }
      BigDecimal abs = v.abs();
      if (abs.compareTo(maxAbsVariance) > 0) {
        maxAbsVariance = abs;
      }
    }
    if (maxAbsVariance.compareTo(threshold) < 0) {
      log.debug(
          "Nutrition divergence max |variance|={} below threshold {} for plan {}; immaterial",
          maxAbsVariance,
          threshold,
          plan.getId());
      return false;
    }

    int redistributable = countRedistributableMeals(plan);
    int minMeals = properties.materiality().nutritionMinRedistributableMeals();
    if (redistributable < minMeals) {
      log.debug(
          "Nutrition divergence material by magnitude but only {} redistributable meal(s) (<{})"
              + " for plan {}; immaterial",
          redistributable,
          minMeals,
          plan.getId());
      return false;
    }
    return true;
  }

  /** Meals still {@link SlotState#PLANNED} — the only slots a re-opt can re-assign. */
  private int countRedistributableMeals(Plan plan) {
    int count = 0;
    for (Day day : plan.getDays()) {
      for (MealSlot slot : day.getSlots()) {
        if (slot.getState() == SlotState.PLANNED) {
          count++;
        }
      }
    }
    return count;
  }
}
