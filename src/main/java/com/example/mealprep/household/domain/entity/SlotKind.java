package com.example.mealprep.household.domain.entity;

/**
 * Module-local enumeration of meal-slot kinds used inside {@code HouseholdSettingsDocument}.
 * Lower-case literal forms match the OpenAPI enum casing (the JSON document values are lower-case
 * strings); Jackson's default for an enum is {@code Enum.name()}, so the field-by-field audit-log
 * JSON is consistent with the OpenAPI shape.
 */
public enum SlotKind {
  breakfast,
  lunch,
  dinner,
  snack,
  custom
}
