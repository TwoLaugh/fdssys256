package com.example.mealprep.grocery.domain.entity;

/**
 * Why a shopping-list line exists. Per lld/grocery.md line 369.
 *
 * <ul>
 *   <li>{@code PLANNED_DEMAND} — derived from the plan's scheduled recipes.
 *   <li>{@code STAPLE_REPLENISHMENT} — a staple at {@code LOW}/{@code OUT} per provisions.
 * </ul>
 */
public enum ShoppingListLineType {
  PLANNED_DEMAND,
  STAPLE_REPLENISHMENT
}
