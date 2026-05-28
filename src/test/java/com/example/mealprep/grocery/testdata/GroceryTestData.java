package com.example.mealprep.grocery.testdata;

import com.example.mealprep.grocery.domain.entity.GroceryProviderState;
import com.example.mealprep.grocery.domain.entity.GrocerySubstitutionProposal;
import com.example.mealprep.grocery.domain.entity.LineFulfilmentStatus;
import com.example.mealprep.grocery.domain.entity.PackSizeHeuristic;
import com.example.mealprep.grocery.domain.entity.PriceObservation;
import com.example.mealprep.grocery.domain.entity.PriceSource;
import com.example.mealprep.grocery.domain.entity.ReferencePriceRow;
import com.example.mealprep.grocery.domain.entity.ShoppingList;
import com.example.mealprep.grocery.domain.entity.ShoppingListLine;
import com.example.mealprep.grocery.domain.entity.ShoppingListLineType;
import com.example.mealprep.grocery.domain.entity.SubstitutionProposalStatus;
import com.example.mealprep.grocery.domain.service.internal.providers.SubstitutionProposal;
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

  /**
   * A weight/volume pack-size heuristic (01b) matched by mapping key: {@code packSizeG} grams/ml at
   * the given rank (1 = smallest). {@code packUnit} defaults to "g".
   */
  public static PackSizeHeuristic packByKeySize(String key, int packSizeG, int rank) {
    return PackSizeHeuristic.builder()
        .id(UUID.randomUUID())
        .ingredientMappingKey(key)
        .packSizeG(packSizeG)
        .packUnit("g")
        .rank(rank)
        .build();
  }

  /** A count-based pack-size heuristic (01b) matched by mapping key: {@code packCount} items. */
  public static PackSizeHeuristic packByKeyCount(String key, int packCount, int rank) {
    return PackSizeHeuristic.builder()
        .id(UUID.randomUUID())
        .ingredientMappingKey(key)
        .packCount(packCount)
        .packUnit("items")
        .rank(rank)
        .build();
  }

  /** A weight pack-size heuristic (01b) matched by category fallback. */
  public static PackSizeHeuristic packByCategorySize(String category, int packSizeG, int rank) {
    return PackSizeHeuristic.builder()
        .id(UUID.randomUUID())
        .category(category)
        .packSizeG(packSizeG)
        .packUnit("g")
        .rank(rank)
        .build();
  }

  /**
   * A persisted-shape {@link GrocerySubstitutionProposal} (01f) with sane defaults: a parseable,
   * {@code PENDING_USER_REVIEW} proposal (original "white rice" → substitute "brown rice").
   * Override fields via the builder; set {@code groceryOrderId} to attach it to an order.
   */
  public static GrocerySubstitutionProposal.GrocerySubstitutionProposalBuilder
      substitutionProposal() {
    Instant now = Instant.now();
    return GrocerySubstitutionProposal.builder()
        .id(UUID.randomUUID())
        .groceryOrderId(UUID.randomUUID())
        .originalProductId("fake-sku-white rice")
        .originalDisplayName("White rice")
        .originalIngredientMappingKey("white rice")
        .substituteProductId("fake-sku-brown rice")
        .substituteDisplayName("Brown rice")
        .substituteIngredientMappingKey(null)
        .substituteQuantity(new BigDecimal("1.000"))
        .substituteUnit("kg")
        .substituteUnitPence(22)
        .reason("out of stock")
        .proposalStatus(SubstitutionProposalStatus.PENDING_USER_REVIEW)
        .version(0L)
        .createdAt(now)
        .updatedAt(now);
  }

  /**
   * A provider-surfaced {@link SubstitutionProposal} (the SPI record the persister maps). {@code
   * originalProductId} should match the order line's {@code providerProductId} (the fake sets it to
   * {@code "fake-sku-" + key}) so the persister can attach the proposal to its line.
   */
  public static SubstitutionProposal providerSubstitution(
      String originalKey, String substituteName) {
    return new SubstitutionProposal(
        "fake-sku-" + originalKey,
        "Original " + originalKey,
        "fake-sku-sub-" + originalKey,
        substituteName,
        new BigDecimal("1.000"),
        "kg",
        22,
        "out of stock",
        null);
  }

  /** A provider-surfaced OPAQUE substitution (no substitute product id) → persisted UNPARSED. */
  public static SubstitutionProposal opaqueProviderSubstitution(String originalKey) {
    return new SubstitutionProposal(
        "fake-sku-" + originalKey,
        "Original " + originalKey,
        null,
        null,
        null,
        null,
        null,
        "dom differs",
        null);
  }

  /**
   * A {@link GroceryProviderState} builder (grocery-01g) wired against the {@code "fake"} provider
   * with {@code scheduled_refresh_enabled = true} and the default top-N (50). Override {@code
   * userId} / {@code scheduledRefreshEnabled} / {@code refreshTopNIngredients} via the builder
   * before {@code .build()}.
   */
  public static GroceryProviderState.GroceryProviderStateBuilder providerState() {
    Instant now = Instant.now();
    return GroceryProviderState.builder()
        .id(UUID.randomUUID())
        .userId(UUID.randomUUID())
        .providerKey("fake")
        .enabled(true)
        .consecutiveFailures(0)
        .scheduledRefreshEnabled(true)
        .refreshTopNIngredients(50)
        .version(0L)
        .createdAt(now)
        .updatedAt(now);
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
