package com.example.mealprep.planner.api.dto;

/**
 * Classification of a constraint-feasibility conflict surfaced by {@code
 * ConstraintFeasibilityCheck} (planner-6, LLD §Constraint feasibility DTOs). Each conflict the
 * check detects is mapped to one of these kinds so the UI can render an appropriate resolution
 * dialog before Stage A runs.
 */
public enum ConflictType {
  /** A shared slot whose combined household hard constraints leave no viable recipe. */
  HOUSEHOLD_HARD_COLLISION,
  /** Nutrition floors and the weekly budget cannot both be satisfied by the available pool. */
  NUTRITION_VS_BUDGET,
  /** Provisions / equipment availability bottlenecks the pool below the planning minimum. */
  PROVISIONS_BOTTLENECK,
  /** Soft preferences are so narrow that the post-filter pool is under-determined. */
  OVER_SPECIFIED_PREFERENCES
}
