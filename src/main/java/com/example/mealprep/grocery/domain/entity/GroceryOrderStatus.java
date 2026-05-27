package com.example.mealprep.grocery.domain.entity;

/**
 * Top-down lifecycle state of a {@link GroceryOrder}. Per lld/grocery.md line 372. Legal
 * transitions are enforced service-side by {@code OrderStateMachine} (ships in a later tier
 * ticket); {@code PLACED_PARTIAL} is a distinct status so listeners can react to a partially-placed
 * order — the signal that the user must complete the basket manually.
 */
public enum GroceryOrderStatus {
  DRAFT,
  QUOTED,
  PLACED,
  PLACED_PARTIAL,
  AWAITING_USER_CONFIRMATION,
  CONFIRMED,
  DELIVERED,
  RECONCILED,
  CANCELLED,
  ARCHIVED,
  PROVIDER_UNAVAILABLE
}
