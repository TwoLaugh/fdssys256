package com.example.mealprep.provisions.exception;

import java.util.UUID;

/**
 * Thrown when an inventory item is missing or owned by another user. Mapped to HTTP 404 — we
 * intentionally do NOT distinguish "missing" from "owned by another user", to avoid leaking the
 * existence of items the caller cannot see.
 */
public class InventoryItemNotFoundException extends ProvisionsException {

  private final UUID itemId;

  public InventoryItemNotFoundException(UUID itemId) {
    super("Inventory item " + itemId + " not found");
    this.itemId = itemId;
  }

  public UUID itemId() {
    return itemId;
  }
}
