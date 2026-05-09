package com.example.mealprep.preference.domain.entity;

/**
 * Categorises a hard-constraint filter violation. The kind tells upstream callers (planner UI, plan
 * messaging) which message to render and which constraint type to surface.
 */
public enum ViolationKind {
  /** Direct match against {@code HardConstraints.allergies} or its expanded derivatives. */
  ALLERGY,
  /** Match against a {@code HardIntolerance.substance} entry. */
  INTOLERANCE,
  /** Excluded by the user's {@code dietaryIdentityBase} (e.g. vegan rejecting chicken). */
  DIETARY_BASE,
  /** Reserved for callers that need to flag dietary-base exception edge cases. */
  DIETARY_EXCEPTION_MISMATCH,
  /** Match against {@code HardConstraints.medicalDiets} or one of its derived restrictions. */
  MEDICAL_DIET,
  /** Match against an {@code AgeRestriction.ruleKey}. */
  AGE_RESTRICTION
}
