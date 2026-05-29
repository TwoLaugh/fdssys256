package com.example.mealprep.preference.api.dto;

/**
 * The safety-critical Tier-1 hard-constraint categories whose <em>removal</em> requires a
 * confirmation interstitial (GAP-04). These are the constraints the deterministic {@code
 * HardConstraintFilterService} enforces as the system's only allergy/safety guardrail, so silently
 * dropping one is a real safety hole.
 *
 * <p>Age restrictions are intentionally NOT here: they are auto-populated/managed for child
 * profiles rather than user-removed as a deliberate safety decision, so they are not gated by this
 * interstitial (see {@code design/preference-model.md} §Tier-1 removal confirmation).
 */
public enum Tier1Category {
  /** An entry in {@code allergies} was removed. */
  ALLERGY,
  /** An entry in {@code medicalDiets} was removed. */
  MEDICAL_DIET,
  /** A severe/hard intolerance substance was removed from {@code intolerances}. */
  SEVERE_INTOLERANCE,
  /** The dietary-identity {@code base} was narrowed/changed away from its stored value. */
  DIETARY_IDENTITY_BASE
}
