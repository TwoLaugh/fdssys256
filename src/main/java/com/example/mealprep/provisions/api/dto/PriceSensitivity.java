package com.example.mealprep.provisions.api.dto;

/**
 * Per-user shopping price-sensitivity dial. Lowercase Java constants so {@code @Enumerated(STRING)}
 * round-trips against the DB column's lowercase values without a converter — same pattern as 01a's
 * {@code HouseholdRole}.
 */
public enum PriceSensitivity {
  low,
  moderate,
  high
}
