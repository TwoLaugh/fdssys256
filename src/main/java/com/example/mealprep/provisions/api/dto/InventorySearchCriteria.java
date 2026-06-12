package com.example.mealprep.provisions.api.dto;

import com.example.mealprep.provisions.domain.entity.ItemLifecycleStatus;
import com.example.mealprep.provisions.domain.entity.StorageLocation;

/**
 * Optional filters for {@code GET /api/v1/provisions/inventory}. {@code null} fields are treated as
 * "no filter", except {@code itemStatus} where {@code null} means the default {@code ACTIVE} view
 * (the pre-P2 behaviour — existing callers see byte-identical results).
 *
 * <p>{@code expiringWithinDays} (≥ 0) narrows to items whose non-null {@code expiryDate} is on or
 * before {@code today + N} ({@code today} from the service clock, consistent with the
 * notification-module expiry scanner); items without an expiry date never match. {@code 0} means
 * "expiring today". The full LLD shape (lines 269-321) carries six filter fields; the remaining
 * ones land with later read endpoints (01b/01g/01k).
 */
public record InventorySearchCriteria(
    StorageLocation storageLocation,
    Boolean isStaple,
    ItemLifecycleStatus itemStatus,
    Integer expiringWithinDays) {

  /** Pre-P2 two-filter shape — kept so existing callers compile unchanged. */
  public InventorySearchCriteria(StorageLocation storageLocation, Boolean isStaple) {
    this(storageLocation, isStaple, null, null);
  }

  /** Convenience for callers passing no filters. */
  public static InventorySearchCriteria none() {
    return new InventorySearchCriteria(null, null, null, null);
  }
}
