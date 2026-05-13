package com.example.mealprep.adaptation.domain.enums;

/**
 * Severity level on a {@code PlannerHintRecord}. {@code BLOCK} hints prevent the planner from
 * scheduling the recipe in the relevant slot. Verbatim from {@code lld/adaptation-pipeline.md} line
 * 229.
 */
public enum HintSeverity {
  INFO,
  WARN,
  BLOCK
}
