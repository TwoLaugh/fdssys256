package com.example.mealprep.grocery.domain.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * Tier 4 cold-start SPI (01c) — a low-but-nonzero reference cost estimate per normalised {@code
 * ingredientMappingKey}, used by {@code PriceAggregator} when a household has no observations for a
 * key. Public (re-exported via {@link com.example.mealprep.grocery.GroceryModule}); the snapshot
 * implementation ({@code ReferenceSnapshotSource}) is package-private in {@code
 * domain.service.internal} — mirrors discovery's SPI-with-impl-in-the-pocket pattern.
 *
 * <p>The reference data is a bundled Open Food Facts "Open Prices" starter snapshot (ODbL — every
 * estimate carries the {@code attribution} string). Per LLD §Flow 5 (lines 926-939) the v1 cut uses
 * the reference estimate as the cold-start fallback only — NO inflation-indexing synthesis in v1.
 */
public interface ReferencePriceSource {

  /**
   * A cold-start reference estimate for a mapping key (per normalised unit), or empty if unmapped.
   */
  Optional<ReferencePrice> referencePrice(String ingredientMappingKey);

  /**
   * Batch sibling of {@link #referencePrice(String)} — returns only the mapped keys. Issues ONE SQL
   * {@code WHERE ingredient_mapping_key IN (...)} (the ≤5-SQL target for {@code
   * getAggregatesByKeys}).
   */
  Map<String, ReferencePrice> referencePrices(Collection<String> keys);

  /**
   * A reference cost estimate. {@code unitPence} is per normalised {@code unit} (per_100g /
   * per_litre / per_item). {@code referenceConfidence} is a low fixed value (a reference is never
   * as good as a real observation). {@code attribution} carries the ODbL attribution string.
   */
  record ReferencePrice(
      String ingredientMappingKey,
      int unitPence,
      String unit,
      BigDecimal referenceConfidence,
      Instant sourceAsOf,
      String attribution) {}
}
