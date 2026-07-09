package com.example.mealprep.grocery.api.mapper;

import com.example.mealprep.grocery.domain.entity.ShoppingListLine;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Derives the purchase <b>leftover</b> and <b>buffer %</b> for a shopping-list line — how much of the
 * bought pack(s) goes unused this week. Only meaningful for weight-coherent lines: the demand must be
 * in grams (so {@code IngredientUnitConverter} resolved it) and a gram pack must be suggested.
 * Count-bought lines (eggs, produce) and unconverted lines return {@code null}.
 *
 * <p>{@code bought = suggestedPackSizeG × suggestedPackCount} (suggestedPackCount is the number of
 * packs to buy); {@code leftover = max(0, bought − requestedQuantity)}; {@code buffer% =
 * leftover / bought × 100}. A high buffer on a staple isn't waste — it's pantry carryover; the buffer
 * matters most for perishables.
 */
public final class ShoppingListBuffer {

  private ShoppingListBuffer() {}

  static BigDecimal leftover(ShoppingListLine line) {
    BigDecimal bought = boughtGrams(line);
    if (bought == null || line.getRequestedQuantity() == null) {
      return null;
    }
    return bought.subtract(line.getRequestedQuantity()).max(BigDecimal.ZERO).setScale(1, RoundingMode.HALF_UP);
  }

  static BigDecimal bufferPercent(ShoppingListLine line) {
    BigDecimal bought = boughtGrams(line);
    BigDecimal leftover = leftover(line);
    if (bought == null || leftover == null || bought.signum() == 0) {
      return null;
    }
    return leftover.multiply(BigDecimal.valueOf(100)).divide(bought, 1, RoundingMode.HALF_UP);
  }

  /** Total grams purchased, or null when the line isn't gram-coherent with a gram pack. */
  private static BigDecimal boughtGrams(ShoppingListLine line) {
    if (!"g".equals(line.getRequestedUnit())
        || line.getSuggestedPackSizeG() == null
        || line.getSuggestedPackCount() == null) {
      return null;
    }
    return BigDecimal.valueOf((long) line.getSuggestedPackSizeG() * line.getSuggestedPackCount());
  }
}
