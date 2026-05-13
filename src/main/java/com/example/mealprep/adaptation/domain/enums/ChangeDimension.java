package com.example.mealprep.adaptation.domain.enums;

/**
 * Logical "axis of change" a {@code PendingChange} adjusts. The partial unique index on {@code
 * (recipe_id, change_dimension) WHERE status = 'PENDING'} enforces one-active-per-pair so
 * supersession is atomic. Verbatim from {@code lld/adaptation-pipeline.md} line 126.
 *
 * <p>{@code GENERAL} is the documented fallback per LLD line 245 — "Unseeded values surface a WARN
 * log and fall back to general."
 */
public enum ChangeDimension {
  SALT_LEVEL,
  PROTEIN,
  METHOD_SIMPLIFICATION,
  PORTION_SIZE,
  FLAVOUR_BALANCE,
  ACID_BALANCE,
  TEXTURE,
  COOKING_TIME,
  SUBSTITUTION_PROMOTION,
  GENERAL
}
