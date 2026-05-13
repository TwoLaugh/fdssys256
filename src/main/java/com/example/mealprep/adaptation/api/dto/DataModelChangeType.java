package com.example.mealprep.adaptation.api.dto;

/**
 * Trigger-3 sub-classifier — names which user data-model surface mutated. Carried on {@link
 * DataModelJobRequest#changeType()} so the adaptation pipeline can route per-source budgets and
 * choose the right prompt template.
 *
 * <p>Per LLD §DTOs line 331; verbatim from {@code lld/adaptation-pipeline.md}.
 */
public enum DataModelChangeType {
  /** User preferences (likes, dislikes, taste profile, prep-style). */
  PREFERENCE,
  /** Nutrition targets (macros, micros, kcal). */
  NUTRITION_TARGETS,
  /** Provisions budget — weekly £ cap or per-meal cap. */
  PROVISIONS_BUDGET,
  /** Hard constraints (allergies, dislikes-as-blocks, equipment unavailability). */
  HARD_CONSTRAINTS
}
