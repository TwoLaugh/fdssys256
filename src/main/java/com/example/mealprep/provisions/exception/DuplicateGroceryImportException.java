package com.example.mealprep.provisions.exception;

import com.example.mealprep.provisions.domain.entity.ItemSource;
import java.util.UUID;

/**
 * Thrown when a grocery-import request is a duplicate of an already-applied {@code (userId, source,
 * sourceRef)} triple. Mapped to HTTP 409 — the idempotency log table is the source of truth
 * (race-safe via the composite-PK constraint, not in-memory).
 */
public class DuplicateGroceryImportException extends ProvisionsException {

  private final UUID userId;
  private final ItemSource source;
  private final String sourceRef;

  public DuplicateGroceryImportException(UUID userId, ItemSource source, String sourceRef) {
    super(
        "Grocery import already applied for userId="
            + userId
            + ", source="
            + source
            + ", sourceRef="
            + sourceRef);
    this.userId = userId;
    this.source = source;
    this.sourceRef = sourceRef;
  }

  public UUID getUserId() {
    return userId;
  }

  public ItemSource getSource() {
    return source;
  }

  public String getSourceRef() {
    return sourceRef;
  }
}
