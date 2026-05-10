package com.example.mealprep.nutrition.domain.entity;

/**
 * Action discriminator for {@link IntakeAuditLog} rows. {@code SNACK_*} actions populate {@code
 * snackId} and leave {@code mealSlot} null; all other actions populate {@code mealSlot} and leave
 * {@code snackId} null.
 */
public enum IntakeAuditAction {
  PREFILL,
  CONFIRM,
  OVERRIDE,
  EDIT,
  SKIP,
  SNACK_ADD,
  SNACK_REMOVE
}
