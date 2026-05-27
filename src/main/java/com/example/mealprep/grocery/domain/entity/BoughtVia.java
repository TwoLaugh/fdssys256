package com.example.mealprep.grocery.domain.entity;

/**
 * How a {@link ShoppingListLine} was marked bought. Per lld/grocery.md line 371.
 *
 * <ul>
 *   <li>{@code MANUAL} — Tier 2 single-line mark-bought.
 *   <li>{@code ORDER} — Tier 3 order reconciliation.
 *   <li>{@code BULK_TOTAL} — Tier 2 bulk mark-bought with total-spend distribution.
 * </ul>
 */
public enum BoughtVia {
  MANUAL,
  ORDER,
  BULK_TOTAL
}
