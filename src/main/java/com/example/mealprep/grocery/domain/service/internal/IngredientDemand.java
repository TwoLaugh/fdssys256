package com.example.mealprep.grocery.domain.service.internal;

import java.math.BigDecimal;

/**
 * Mutable-by-rebuild value carrying the aggregated demand for one normalised ingredient mapping key
 * during the Tier-1 six-step calculation (grocery-01b). Per lld/grocery.md §Flow 1 step 1.
 *
 * @param key the NORMALISED ingredient mapping key (via {@code IngredientMappingKeys.normalise})
 * @param displayName a human label for the line (first-seen display name wins)
 * @param quantity summed demand quantity
 * @param unit the demand unit (first-seen unit; v1 does not convert units across recipes)
 * @param category optional ingredient category used for pack-size category fallback ({@code null}
 *     for plan-derived demand — recipes carry no per-ingredient category; populated for staples,
 *     which carry an inventory category)
 * @param qualityNotes optional informational quality hint applied in step 5
 */
record IngredientDemand(
    String key,
    String displayName,
    BigDecimal quantity,
    String unit,
    String category,
    String qualityNotes) {

  IngredientDemand add(BigDecimal more) {
    return new IngredientDemand(key, displayName, quantity.add(more), unit, category, qualityNotes);
  }

  /** Subtract {@code amount}, clamping at zero (never negative — step 2 underflow rule). */
  IngredientDemand subtractClampingAtZero(BigDecimal amount) {
    BigDecimal remaining = quantity.subtract(amount);
    if (remaining.signum() < 0) {
      remaining = BigDecimal.ZERO;
    }
    return new IngredientDemand(key, displayName, remaining, unit, category, qualityNotes);
  }

  IngredientDemand withQualityNotes(String notes) {
    return new IngredientDemand(key, displayName, quantity, unit, category, notes);
  }

  boolean isPositive() {
    return quantity != null && quantity.signum() > 0;
  }
}
