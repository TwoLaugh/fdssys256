package com.example.mealprep.adaptation.api.dto;

/**
 * Plan-time refine-directive kind. Names what the planner asked the adaptation pipeline to push on
 * a slot — drop cost, raise protein, shorten time, ensure equipment compatibility, or swap a
 * specific ingredient.
 *
 * <p>Per LLD §DTOs lines 351-353; verbatim from {@code lld/adaptation-pipeline.md}.
 */
public enum DirectiveKind {
  /** Reduce cost by an amount specified in {@code targetDelta}. */
  COST_DELTA,
  /** Raise or lower nutrition (macro/micro) by an amount specified in {@code targetDelta}. */
  NUTRITION_DELTA,
  /** Reduce active or total minutes by an amount specified in {@code targetDelta}. */
  TIME_DELTA,
  /** Restrict to equipment already used elsewhere in the plan-week. */
  EQUIPMENT_OVERLAP,
  /** Swap a specific ingredient (key) for an alternative. */
  INGREDIENT_SWAP
}
