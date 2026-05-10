package com.example.mealprep.nutrition.domain.entity;

/**
 * Lifecycle status of an {@link IntakeSlot}'s actual nutrition values. {@code PENDING} on first
 * pre-fill; flipped by confirm / override / edit / skip writes.
 */
public enum IntakeSlotStatus {
  PENDING,
  CONFIRMED,
  OVERRIDDEN,
  EDITED,
  SKIPPED
}
