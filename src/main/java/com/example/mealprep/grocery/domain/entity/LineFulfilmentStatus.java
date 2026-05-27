package com.example.mealprep.grocery.domain.entity;

/**
 * Per-line fulfilment marker on a {@link ShoppingListLine} — NOT a soft-delete; the line stays for
 * history. Per lld/grocery.md line 370.
 */
public enum LineFulfilmentStatus {
  UNFILLED,
  PARTIAL,
  BOUGHT,
  SUBSTITUTED,
  DROPPED
}
