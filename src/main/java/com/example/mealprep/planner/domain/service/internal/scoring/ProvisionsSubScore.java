package com.example.mealprep.planner.domain.service.internal.scoring;

import com.example.mealprep.planner.api.dto.CandidatePlan;
import com.example.mealprep.planner.api.dto.PlanCompositionContext;
import com.example.mealprep.planner.api.dto.SlotAssignment;
import com.example.mealprep.planner.config.PlannerProperties;
import com.example.mealprep.provisions.api.dto.InventoryItemDto;
import com.example.mealprep.provisions.api.dto.ProvisionForPlannerBundleDto;
import com.example.mealprep.recipe.api.dto.IngredientDto;
import com.example.mealprep.recipe.api.dto.RecipeDto;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Provisions-utilisation sub-score (use-what-you-have, prioritising near-expiry stock). Algorithm
 * LOCKED per LLD §ProvisionsSubScore (2026-05-07):
 *
 * <pre>
 *   if pantry_tracking_enabled is false: 0.5                       // neutral when disabled
 *   covered_value = Σ min(demand[i], inv[i].qty) × waste_value(inv[i])   for i in plan ∩ inventory
 *   max_value     = Σ demand[i] × max_waste_value                        for i in plan
 *   return clamp(covered_value / max_value, 0, 1)
 * </pre>
 *
 * <p>{@code waste_value}: {@code >7d}→1.0, {@code ≤3d}→2.0, {@code ≤1d}→3.0; {@code
 * max_waste_value}=3.0. Tiers tunable via {@code mealprep.planner.scoring.provisions.*}. {@code
 * demand[i]} = Σ {@code ingredient.quantity × servings} across slots using ingredient {@code i}.
 *
 * <p><b>01e codebase divergence — no pantry-tracking flag</b>: ticket item 35 expected {@code
 * LifestyleConfigDocument.pantryTrackingEnabled()}; that field does not exist (the document carries
 * meal-timing / novelty / batch-cooking only). 01e's proxy: pantry tracking is considered DISABLED
 * (→ {@code 0.5} neutral, no inventory access — cold-start friendly) when the provisions bundle is
 * null OR carries no active inventory rows; ENABLED (compute coverage) when active inventory
 * exists. This preserves the LOCKED disabled-path semantics. TODO(user): swap to the real lifestyle
 * flag when preference-01a surfaces it.
 */
@Component
class ProvisionsSubScore implements SubScoreCalculator {

  private static final BigDecimal NEUTRAL = new BigDecimal("0.500000");

  private final PlannerProperties properties;

  ProvisionsSubScore(PlannerProperties properties) {
    this.properties = properties;
  }

  @Override
  public String name() {
    return "provisions";
  }

  @Override
  public BigDecimal compute(CandidatePlan plan, PlanCompositionContext ctx) {
    ProvisionForPlannerBundleDto provisions = ctx.provisions();
    if (provisions == null
        || provisions.activeInventory() == null
        || provisions.activeInventory().isEmpty()) {
      return NEUTRAL; // proxy for pantry_tracking_enabled == false
    }

    Map<UUID, RecipeDto> recipes = ScoringSupport.recipeIndex(ctx);

    // demand[i] = Σ (quantity × servings) per ingredient mapping key
    Map<String, BigDecimal> demand = new HashMap<>();
    if (plan.assignments() != null) {
      for (SlotAssignment a : plan.assignments()) {
        RecipeDto recipe = ScoringSupport.findRecipe(recipes, a.recipeId()).orElse(null);
        if (recipe == null
            || recipe.currentVersionBody() == null
            || recipe.currentVersionBody().ingredients() == null) {
          continue;
        }
        for (IngredientDto ing : recipe.currentVersionBody().ingredients()) {
          if (ing.ingredientMappingKey() == null || ing.quantity() == null) {
            continue;
          }
          BigDecimal qty = ing.quantity().multiply(BigDecimal.valueOf(a.servings()));
          demand.merge(ing.ingredientMappingKey(), qty, BigDecimal::add);
        }
      }
    }
    if (demand.isEmpty()) {
      return NEUTRAL; // nothing demanded → no signal
    }

    Map<String, InventoryItemDto> inventory = new HashMap<>();
    for (InventoryItemDto item : provisions.activeInventory()) {
      if (item.ingredientMappingKey() != null) {
        inventory.putIfAbsent(item.ingredientMappingKey(), item);
      }
    }

    PlannerProperties.ScoringTuning.ProvisionsTuning.WasteValueTiers tiers =
        properties.scoring().provisions().wasteValueTiers();
    BigDecimal maxWasteValue =
        tiers.aboveSevenDays().max(tiers.threeDaysOrLess()).max(tiers.oneDayOrLess());
    if (maxWasteValue.compareTo(BigDecimal.ZERO) <= 0) {
      maxWasteValue = BigDecimal.ONE;
    }

    BigDecimal coveredValue = BigDecimal.ZERO;
    BigDecimal maxValue = BigDecimal.ZERO;
    for (Map.Entry<String, BigDecimal> e : demand.entrySet()) {
      BigDecimal d = e.getValue();
      maxValue = maxValue.add(d.multiply(maxWasteValue));
      InventoryItemDto item = inventory.get(e.getKey());
      if (item == null || item.quantity() == null) {
        continue;
      }
      BigDecimal usable = d.min(item.quantity());
      coveredValue = coveredValue.add(usable.multiply(wasteValue(item, tiers)));
    }

    if (maxValue.compareTo(BigDecimal.ZERO) == 0) {
      return NEUTRAL;
    }
    return coveredValue
        .divide(maxValue, 6, RoundingMode.HALF_UP)
        .max(BigDecimal.ZERO)
        .min(BigDecimal.ONE);
  }

  private static BigDecimal wasteValue(
      InventoryItemDto item,
      PlannerProperties.ScoringTuning.ProvisionsTuning.WasteValueTiers tiers) {
    LocalDate expiry = item.expiryDate();
    if (expiry == null) {
      return tiers.aboveSevenDays(); // no expiry known → lowest urgency tier
    }
    long days = ChronoUnit.DAYS.between(LocalDate.now(), expiry);
    if (days <= 1) {
      return tiers.oneDayOrLess();
    }
    if (days <= 3) {
      return tiers.threeDaysOrLess();
    }
    return tiers.aboveSevenDays();
  }
}
