package com.example.mealprep.provisions.domain.entity;

/**
 * Who triggered a write. {@code USER} for HTTP create/update (01a); the rest land with their flows
 * — {@code COOK_EVENT} (01g), {@code GROCERY_IMPORT} (01h), {@code SYSTEM} (01k retention sweep
 * etc.).
 */
public enum AuditActor {
  USER,
  COOK_EVENT,
  GROCERY_IMPORT,
  NUTRITION_LOGGER,
  SYSTEM
}
