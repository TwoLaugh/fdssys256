package com.example.mealprep.grocery.exception;

import java.util.UUID;

/**
 * Thrown when a transition to {@code RECONCILED} is attempted while substitution proposals are
 * still {@code PENDING_USER_REVIEW}. Maps to HTTP 422 — the user must resolve all proposals first.
 */
public class OrderHasOutstandingProposalsException extends GroceryException {

  private final UUID orderId;
  private final long outstandingCount;

  public OrderHasOutstandingProposalsException(UUID orderId, long outstandingCount) {
    super(
        "Grocery order "
            + orderId
            + " has "
            + outstandingCount
            + " outstanding substitution proposal(s); resolve all before reconciling");
    this.orderId = orderId;
    this.outstandingCount = outstandingCount;
  }

  public UUID orderId() {
    return orderId;
  }

  public long outstandingCount() {
    return outstandingCount;
  }
}
