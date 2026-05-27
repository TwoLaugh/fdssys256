package com.example.mealprep.grocery.domain.entity;

/**
 * Provenance of a {@link PriceObservation}, source-weighted at write time per {@code
 * GroceryConfig.confidenceWeights}. Per lld/grocery.md line 375.
 *
 * <p>Default weights: {@code PAID=1.0}, {@code QUOTE=0.85}, {@code MANUAL=0.7}, {@code
 * MANUAL_ESTIMATED=0.4}, {@code INFLATION_INDEXED=0.15}.
 */
public enum PriceSource {
  PAID,
  QUOTE,
  MANUAL,
  MANUAL_ESTIMATED,
  INFLATION_INDEXED
}
