package com.example.mealprep.planner.domain.service.internal.scoring;

import com.example.mealprep.planner.api.dto.PlanCompositionContext;
import com.example.mealprep.recipe.api.dto.RecipeDto;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Internal scoring helpers shared by the seven {@code SubScoreCalculator}s. Package-private, no
 * Spring stereotype — pure static utilities.
 *
 * <p>Per planner-01e gotcha #8: each calculator builds a {@code recipeId -> RecipeDto} index once
 * per {@code compute} call (one O(N) walk) rather than O(N) per slot lookup. The cross-call /
 * shared-index optimisation (passing a prebuilt map from the composer) is deferred until profiling.
 */
final class ScoringSupport {

  private ScoringSupport() {}

  /** Build a {@code recipeId -> RecipeDto} index over the frozen pool snapshot. */
  static Map<UUID, RecipeDto> recipeIndex(PlanCompositionContext ctx) {
    Map<UUID, RecipeDto> index = new HashMap<>();
    if (ctx.recipePool() == null || ctx.recipePool().recipes() == null) {
      return index;
    }
    for (RecipeDto r : ctx.recipePool().recipes()) {
      if (r != null && r.id() != null) {
        index.putIfAbsent(r.id(), r);
      }
    }
    return index;
  }

  static Optional<RecipeDto> findRecipe(Map<UUID, RecipeDto> index, UUID recipeId) {
    return Optional.ofNullable(index.get(recipeId));
  }

  /**
   * Resolve the "primary" user the household-default scoring modes (nutrition, provisions, gate)
   * aggregate against. The codebase carries no explicit {@code primaryUserId} field on {@code
   * HouseholdSettingsDto} / its document, so 01e derives it deterministically: the
   * lowest-by-natural-order key of {@code softPrefsByUserId}, falling back to the first eater of
   * the first slot skeleton, else {@code null}. Worth user review — see ticket items 17 / 35 / 47;
   * 01j's composer can pin an explicit primary user once that field exists.
   */
  static UUID primaryUserId(PlanCompositionContext ctx) {
    if (ctx.softPrefsByUserId() != null && !ctx.softPrefsByUserId().isEmpty()) {
      return ctx.softPrefsByUserId().keySet().stream().sorted().findFirst().orElse(null);
    }
    if (ctx.slotSkeletons() != null) {
      return ctx.slotSkeletons().stream()
          .filter(s -> s.eaters() != null && !s.eaters().isEmpty())
          .map(s -> s.eaters().get(0))
          .findFirst()
          .orElse(null);
    }
    return null;
  }
}
