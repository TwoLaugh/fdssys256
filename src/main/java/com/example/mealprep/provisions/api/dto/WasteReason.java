package com.example.mealprep.provisions.api.dto;

/**
 * Why an inventory item was thrown away. Persisted as {@code varchar(32)} in {@code
 * provision_waste_log.reason}; JPA's {@code @Enumerated(STRING)} writes the upper-case enum name —
 * the LLD line 215 sample lowercase is the human-readable surface, not the DB encoding.
 */
public enum WasteReason {
  EXPIRED,
  LEFTOVER_NOT_EATEN,
  DIDNT_LIKE,
  SPOILED_EARLY,
  MADE_TOO_MUCH
}
