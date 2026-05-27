package com.example.mealprep.grocery.testdata;

import com.example.mealprep.grocery.domain.entity.LineFulfilmentStatus;
import com.example.mealprep.grocery.domain.entity.ShoppingList;
import com.example.mealprep.grocery.domain.entity.ShoppingListLine;
import com.example.mealprep.grocery.domain.entity.ShoppingListLineType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Shared test-data builders for the grocery module. 01a ships the foundational shopping-list
 * builders; later tier tickets append order / proposal / price-observation builders here as their
 * behaviour lands. Pure factory methods — no Spring, no DB.
 */
public final class GroceryTestData {

  private GroceryTestData() {}

  /** A persisted-shape {@link ShoppingList} with sane defaults; override fields via the setters. */
  public static ShoppingList.ShoppingListBuilder shoppingList() {
    Instant now = Instant.now();
    return ShoppingList.builder()
        .id(UUID.randomUUID())
        .userId(UUID.randomUUID())
        .planId(UUID.randomUUID())
        .planGeneration(1)
        .generatedAt(now)
        .estimatedTotalCurrency("GBP")
        .staleIngredientCount(0)
        .pantryTrackingEnabled(true)
        .version(0L)
        .createdAt(now)
        .updatedAt(now);
  }

  /** A {@link ShoppingListLine} with sane defaults; not yet attached to a parent. */
  public static ShoppingListLine.ShoppingListLineBuilder shoppingListLine() {
    Instant now = Instant.now();
    return ShoppingListLine.builder()
        .id(UUID.randomUUID())
        .ingredientMappingKey("flour")
        .displayName("Flour")
        .requestedQuantity(new BigDecimal("1.000"))
        .requestedUnit("kg")
        .lineType(ShoppingListLineType.PLANNED_DEMAND)
        .staleEstimate(false)
        .fulfilmentStatus(LineFulfilmentStatus.UNFILLED)
        .createdAt(now)
        .updatedAt(now);
  }
}
