package com.example.mealprep.adaptation.domain.enums;

/**
 * How the worker classified the chosen candidate — used on {@code AdaptationTrace} and as the
 * {@code proposed_classification} on a {@code PendingChange}. {@code NO_CHANGE} covers both
 * infeasibility and gate-rejected adaptations. Verbatim from {@code lld/adaptation-pipeline.md}
 * line 282.
 */
public enum AdaptationClassification {
  VERSION,
  BRANCH,
  SUBSTITUTION,
  NO_CHANGE
}
