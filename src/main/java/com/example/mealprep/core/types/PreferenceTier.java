package com.example.mealprep.core.types;

/**
 * Tiered structure of the {@code preference} module per {@code design/preference-model.md}. Used in
 * cross-module references (events, audit logs) to identify which tier an update affected.
 */
public enum PreferenceTier {
  /** DB-locked allergy and dietary-identity table; user-only mutations. */
  HARD_CONSTRAINTS,
  /** AI-maintained JSONB document, ~2.5k tokens. */
  TASTE_PROFILE,
  /** User-set lifestyle settings (meal structure, batch cooking, eating contexts). */
  LIFESTYLE_CONFIG,
  /** Long-lived metadata (e.g. age range, household role). */
  PROFILE_METADATA
}
