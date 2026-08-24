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

  // Single-entry memo of the recipe index, keyed by composition-context IDENTITY. The beam scores
  // tens of thousands of candidates per generation and EACH of the seven sub-scores called
  // recipeIndex(ctx), rebuilding the same pool-sized HashMap every time (the "deferred until
  // profiling" optimisation noted below). The ctx is one immutable instance for the whole
  // generation, so an identity-keyed last-value cache collapses those rebuilds to one per
  // generation. The {ctx, index} pair lives behind ONE volatile reference so a reader never sees a
  // ctx paired with another generation's index (the torn-read a two-field cache would allow under
  // concurrent household generations); a generation that misses simply rebuilds and replaces the
  // holder — always correct, never stale.
  private record IndexMemo(PlanCompositionContext ctx, Map<UUID, RecipeDto> index) {}

  private static volatile IndexMemo memo;

  /**
   * Build (or return the memoised) {@code recipeId -> RecipeDto} index over the frozen pool
   * snapshot. Memoised per composition-context identity so the seven sub-scores share one build per
   * generation instead of rebuilding it for every candidate scored.
   */
  static Map<UUID, RecipeDto> recipeIndex(PlanCompositionContext ctx) {
    IndexMemo current = memo;
    if (current != null && current.ctx() == ctx) {
      return current.index();
    }
    Map<UUID, RecipeDto> index = new HashMap<>();
    if (ctx.recipePool() == null || ctx.recipePool().recipes() == null) {
      return index;
    }
    for (RecipeDto r : ctx.recipePool().recipes()) {
      if (r != null && r.id() != null) {
        index.putIfAbsent(r.id(), r);
      }
    }
    memo = new IndexMemo(ctx, index);
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
