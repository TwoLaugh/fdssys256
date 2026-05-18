package com.example.mealprep.planner.domain.service.internal.composer;

import com.example.mealprep.planner.api.dto.MealSlotSkeleton;
import com.example.mealprep.recipe.api.dto.RecipeDto;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Default {@link RecipePoolSource} — returns an empty pool because no catalogue-wide recipe-search
 * cross-module surface exists yet (see {@link RecipePoolSource} javadoc). A later recipe ticket (or
 * an IT {@code @TestConfiguration}) supplies a {@code @Primary} {@link RecipePoolSource} to
 * override this fallback without touching the composer.
 *
 * <p>When the pool is empty Stage A produces no candidates; the composer logs WARN and persists a
 * minimal {@code qualityWarning} plan rather than 500-ing — the HTTP face stays alive ahead of the
 * recipe-search dependency.
 */
@Component
class NoOpRecipePoolSource implements RecipePoolSource {

  @Override
  public List<RecipeDto> fetchPool(
      UUID householdId, List<MealSlotSkeleton> skeletons, UUID traceId) {
    return List.of();
  }
}
