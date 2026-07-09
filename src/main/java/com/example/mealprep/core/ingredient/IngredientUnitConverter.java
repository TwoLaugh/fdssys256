package com.example.mealprep.core.ingredient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Converts a recipe ingredient amount ({@code quantity} + {@code unit} for a canonical ingredient
 * key) into <b>grams</b>, so the shopping list can aggregate heterogeneous units coherently and the
 * pack-size optimiser can compute a real purchase + leftover.
 *
 * <p>Without this, {@code ShoppingListCalculator} sums raw quantities across units (2 tbsp + 1 cup →
 * "3"), which is meaningless once canonical keys merge ingredients that appear in different units.
 *
 * <p>Three conversion families:
 *
 * <ul>
 *   <li><b>Weight</b> ({@code g, kg, oz, lb}) — exact.
 *   <li><b>Volume</b> ({@code tsp, tbsp, cup, ml, l, …}) → ml → grams via a per-ingredient {@link
 *       #density(String) density} (default {@code 1.0} g/ml, water-like; overrides for oil, flour,
 *       sugar, …).
 * </ul>
 *
 * <p><b>Count units are intentionally NOT converted</b> ({@code clove, egg, slice, no-unit, …}):
 * count-bought ingredients (eggs, onions) match <em>count</em> packs, so converting them to grams
 * would mis-match the pack optimiser. They return {@link Optional#empty()} and the caller keeps the
 * raw count. Likewise an unrecognised unit returns empty (raw, no pack match).
 */
public final class IngredientUnitConverter {

  private IngredientUnitConverter() {}

  private static final Map<String, Double> WEIGHT_G =
      Map.of("g", 1.0, "kg", 1000.0, "oz", 28.3495, "lb", 453.592);

  private static final Map<String, Double> VOLUME_ML =
      Map.ofEntries(
          Map.entry("tsp", 4.93), Map.entry("tbsp", 14.79), Map.entry("cup", 236.6),
          Map.entry("ml", 1.0), Map.entry("l", 1000.0), Map.entry("pint", 473.18),
          Map.entry("quart", 946.35), Map.entry("gallon", 3785.41), Map.entry("fl oz", 29.57));

  /** Density g/ml for volume→weight; default 1.0. Keyed by canonical ingredient name (substring). */
  private static final Map<String, Double> DENSITY =
      Map.ofEntries(
          Map.entry("olive oil", 0.92), Map.entry("vegetable oil", 0.92),
          Map.entry("oil", 0.92), Map.entry("butter", 0.96), Map.entry("honey", 1.42),
          Map.entry("syrup", 1.33), Map.entry("milk", 1.03), Map.entry("cream", 1.01),
          Map.entry("yogurt", 1.03), Map.entry("flour", 0.53), Map.entry("sugar", 0.85),
          Map.entry("cornstarch", 0.54), Map.entry("cocoa", 0.41), Map.entry("oats", 0.41),
          Map.entry("rice", 0.85), Map.entry("salt", 1.2), Map.entry("soy sauce", 1.1),
          Map.entry("vinegar", 1.01), Map.entry("wine", 0.99), Map.entry("juice", 1.04),
          Map.entry("broth", 1.0), Map.entry("stock", 1.0), Map.entry("water", 1.0));

  /** Convert {@code quantity}+{@code unit} of {@code ingredientKey} to grams, or empty if unknown. */
  public static Optional<BigDecimal> toGrams(BigDecimal quantity, String unit, String ingredientKey) {
    if (quantity == null || quantity.signum() <= 0) {
      return Optional.empty();
    }
    String u = unit == null ? "" : unit.trim().toLowerCase(Locale.ROOT);
    String key = ingredientKey == null ? "" : ingredientKey.toLowerCase(Locale.ROOT);
    Double w = WEIGHT_G.get(u);
    if (w != null) {
      return Optional.of(scale(quantity, w));
    }
    Double ml = VOLUME_ML.get(u);
    if (ml != null) {
      return Optional.of(scale(quantity, ml * density(key)));
    }
    return Optional.empty(); // count / no-unit / unrecognised → keep raw, match a count pack instead
  }

  private static BigDecimal scale(BigDecimal qty, double factor) {
    return qty.multiply(BigDecimal.valueOf(factor)).setScale(3, RoundingMode.HALF_UP);
  }

  private static double density(String key) {
    Double exact = DENSITY.get(key);
    if (exact != null) {
      return exact;
    }
    for (Map.Entry<String, Double> e : DENSITY.entrySet()) {
      if (key.contains(e.getKey())) {
        return e.getValue();
      }
    }
    return 1.0; // water-like default
  }
}
