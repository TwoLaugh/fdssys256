package com.example.mealprep.recipe.spi;

import java.util.UUID;

/**
 * Inbound SPI from the planner module — recipe-01e ships the interface + the default impl on {@code
 * RecipeServiceImpl}. The planner module (recipe-side caller is planner-01a) will inject this bean
 * to record that a meal-plan slot applied a particular substitution.
 *
 * <p>Implementations must:
 *
 * <ul>
 *   <li>Throw {@code RecipeSubstitutionNotFoundException} (404) if the substitution doesn't exist.
 *   <li>Throw {@code SubstitutionRecordPreconditionException} (422) if the substitution is not in
 *       state {@code ACCEPTED}.
 *   <li>Append {@code planId} to {@code appliedInPlanIds} (idempotent — duplicates are dropped),
 *       bump {@code applicationCount}, refresh {@code lastAppliedAt = Instant.now()}.
 * </ul>
 */
public interface RecipeSubstitutionRecorder {

  /**
   * Record that a plan applied this substitution. Idempotent on {@code planId}; no event is emitted
   * (high-frequency planner-internal bookkeeping).
   */
  void recordSubstitution(UUID substitutionId, UUID planId);
}
