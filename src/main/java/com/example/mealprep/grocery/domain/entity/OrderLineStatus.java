package com.example.mealprep.grocery.domain.entity;

/** Per-line status within a {@link GroceryOrder}. Per lld/grocery.md line 373. */
public enum OrderLineStatus {
  QUEUED,
  ADDED,
  ADDED_PARTIAL,
  UNAVAILABLE,
  SUBSTITUTED,
  DELIVERED,
  REJECTED
}
