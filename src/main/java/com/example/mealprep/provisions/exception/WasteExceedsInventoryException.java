package com.example.mealprep.provisions.exception;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Thrown by the service when {@code POST /waste} attempts to log a quantity greater than the linked
 * inventory item's remaining quantity (and tracking is active). Mapped to HTTP 422 by {@code
 * ProvisionsExceptionHandler}.
 *
 * <p>LLD line 530 names the exception; LLD line 550 places the rule on the service layer (the
 * class-level {@code @ValidWasteQuantity} validator is shape-only and never reaches the DB). No
 * Spring-Web imports here — HTTP-layer mapping lives in {@code api/ProvisionsExceptionHandler}.
 */
public class WasteExceedsInventoryException extends ProvisionsException {

  private final UUID inventoryItemId;
  private final BigDecimal requested;
  private final BigDecimal remaining;

  public WasteExceedsInventoryException(
      UUID inventoryItemId, BigDecimal requested, BigDecimal remaining) {
    super(
        "Waste quantity "
            + requested
            + " exceeds remaining inventory "
            + remaining
            + " for item "
            + inventoryItemId);
    this.inventoryItemId = inventoryItemId;
    this.requested = requested;
    this.remaining = remaining;
  }

  public UUID getInventoryItemId() {
    return inventoryItemId;
  }

  public BigDecimal getRequested() {
    return requested;
  }

  public BigDecimal getRemaining() {
    return remaining;
  }
}
