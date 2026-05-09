package com.example.mealprep.provisions.api.dto;

import com.example.mealprep.provisions.domain.entity.StorageLocation;

/**
 * Optional filters for {@code GET /api/v1/provisions/inventory}. {@code null} fields are treated as
 * "no filter". The full LLD shape (lines 269-321) carries six filter fields; 01a ships the two the
 * endpoint exposes today and the rest land with later read endpoints (01b/01g/01k).
 */
public record InventorySearchCriteria(StorageLocation storageLocation, Boolean isStaple) {

  /** Convenience for callers passing no filters. */
  public static InventorySearchCriteria none() {
    return new InventorySearchCriteria(null, null);
  }
}
