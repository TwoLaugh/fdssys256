package com.example.mealprep.planner.domain.service.internal.composer;

import com.example.mealprep.planner.api.dto.MealSlotSkeleton;
import com.example.mealprep.recipe.api.dto.RecipeDto;
import java.util.List;
import java.util.UUID;

/**
 * Planner-internal SPI for fetching the candidate recipe pool for a generation run (planner-01j).
 *
 * <p><b>Why a seam and not a direct {@code RecipeQueryService} call?</b> The recipe module's
 * cross-module read surface ({@code RecipeQueryService}) currently exposes only by-id / branch /
 * substitution reads — there is no catalogue-wide search-by-constraints method yet (that lands with
 * a later recipe ticket). Rather than block the whole planner HTTP face on an unmerged recipe
 * dependency, the composer depends on this seam: the default production bean ({@link
 * NoOpRecipePoolSource}) returns an empty pool (the composer then degrades to a {@code
 * qualityWarning} plan and logs WARN), and ITs/the recipe module can supply a real implementation
 * additively without touching the composer.
 */
public interface RecipePoolSource {

  /**
   * Resolve the frozen recipe pool for a generation run.
   *
   * @param householdId the planning household
   * @param skeletons the resolved slot skeletons (kinds + time budgets drive the candidate set)
   * @param traceId the run's trace id (for cross-module read correlation when a real impl lands)
   * @return the candidate recipes; never null (empty when no source is wired)
   */
  List<RecipeDto> fetchPool(UUID householdId, List<MealSlotSkeleton> skeletons, UUID traceId);
}
