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
  AGE_RESTRICTION,
  /**
   * Under-determined case: the ingredient matches an allergy/intolerance constraint that a
   * conditional dietary-identity exception <em>might</em> relax, but the ingredient key carries no
   * tag that lets the exception apply decisively. Per {@code lld/preference.md} Flow 2 step 7 the
   * filter flags the ambiguity ({@code passes = false}) rather than silently passing — the safer of
   * the two choices for a safety-critical filter. Example: a dairy allergy with a {@code
   * lactose_free} exception and an ingredient key {@code yoghurt} that does not declare itself
   * lactose-free. The caller surfaces this to the user for resolution rather than treating it as a
   * hard rejection.
   */
  AMBIGUOUS
}
