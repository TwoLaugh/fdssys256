package com.example.mealprep.grocery.testdata;

import com.example.mealprep.grocery.domain.entity.LineFulfilmentStatus;
import com.example.mealprep.grocery.domain.entity.PriceObservation;
import com.example.mealprep.grocery.domain.entity.PriceSource;
import com.example.mealprep.grocery.domain.entity.ReferencePriceRow;
import com.example.mealprep.grocery.domain.entity.ShoppingList;
import com.example.mealprep.grocery.domain.entity.ShoppingListLine;
import com.example.mealprep.grocery.domain.entity.ShoppingListLineType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
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

  /**
   * A persisted-shape {@link PriceObservation} (01c) with sane defaults: PAID source (weight 1.0),
   * cross-store key "chicken breast", observed now. Override fields via the setters/builder.
   */
  public static PriceObservation.PriceObservationBuilder priceObservation() {
    Instant now = Instant.now();
    return PriceObservation.builder()
        .id(UUID.randomUUID())
        .userId(UUID.randomUUID())
        .householdId(UUID.randomUUID())
        .ingredientMappingKey("chicken breast")
        .store("tesco_online")
        .currency("GBP")
        .paidUnitPence(110)
        .source(PriceSource.PAID)
        .confidenceWeight(new BigDecimal("1.000"))
        .observedAt(now)
        .createdAt(now);
  }

  /** A persisted-shape {@link ReferencePriceRow} (01c) with the e2e chicken-breast defaults. */
  public static ReferencePriceRow.ReferencePriceRowBuilder referencePriceRow() {
    return ReferencePriceRow.builder()
        .id(UUID.randomUUID())
        .ingredientMappingKey("chicken breast")
        .referenceUnitPence(110)
        .unit("per_100g")
        .referenceConfidence(new BigDecimal("0.200"))
        .sourceAsOf(LocalDate.parse("2026-01-01"))
        .attribution("Open Food Facts Open Prices, ODbL v1.0")
        .sampleProducts(12)
        .createdAt(Instant.now());
  }
}
