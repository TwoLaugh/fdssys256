package com.example.mealprep.adaptation.domain.enums;

/**
 * What the worker actually did once gates passed: created a new version / branch / substitution,
 * staged a pending change, or recorded a no-op / failure. Verbatim from {@code
 * lld/adaptation-pipeline.md} line 179.
 */
public enum OutcomeKind {
  VERSION_CREATED,
  BRANCH_CREATED,
  SUBSTITUTION_CREATED,
  PENDING_CREATED,
  NO_OP,
  FAILED
}
