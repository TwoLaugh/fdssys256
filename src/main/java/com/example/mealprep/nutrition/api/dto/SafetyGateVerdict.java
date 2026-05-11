package com.example.mealprep.nutrition.api.dto;

/**
 * Outcome of {@link com.example.mealprep.nutrition.domain.service.internal.DirectiveSafetyGate}.
 * {@code BLOCKED} short-circuits the accept flow; the verdict + findings are persisted on the
 * directive row regardless so the user can review the gate's reasoning.
 */
public enum SafetyGateVerdict {
  PASSED,
  BLOCKED,
  PASSED_WITH_WARNINGS
}
