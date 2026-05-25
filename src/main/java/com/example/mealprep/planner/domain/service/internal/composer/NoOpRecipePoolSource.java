package com.example.mealprep.planner.domain.service.internal.composer;

import com.example.mealprep.planner.api.dto.MealSlotSkeleton;
import com.example.mealprep.recipe.api.dto.RecipeDto;
import java.util.List;
import java.util.UUID;

/**
 * Fallback {@link RecipePoolSource} — returns an empty pool. NOT a {@code @Component}; it is
 * registered conditionally by {@link NoOpRecipePoolSourceConfiguration} via {@code @Bean
 * + @ConditionalOnMissingBean(RecipePoolSource.class)}, so it only materialises when no other
 * {@link RecipePoolSource} exists. In a normal context the unconditional {@link
 * CatalogueRecipePoolSource} {@code @Component} wins and this bean is never created.
 *
 * <p>Why the conditional {@code @Bean} (not {@code @Component @ConditionalOnMissingBean} on the
 * class, and not {@code @Primary} on {@link CatalogueRecipePoolSource}):
 *
 * <ul>
 *   <li><b>Not {@code @Primary}</b>: planner ITs supply a {@code @Primary} test stand-in for the
 *       pool source; a second prod-side {@code @Primary} would trip "more than one primary bean"
 *       (see {@link PlanCompositionContextBuilder} javadoc — the documented multi-{@code @Primary}
 *       collision gotcha).
 *   <li><b>Not {@code @Component @ConditionalOnMissingBean} on this class</b>: that shape
 *       order-of-evaluation-gates itself off during component scan (documented round-5 retro across
 *       this codebase, e.g. {@code NoopFeedbackRevertersConfiguration}). The {@code @Configuration
 *       + @Bean} form is the established, reliable pattern.
 * </ul>
 *
 * <p>When the pool is empty Stage A produces no candidates; the composer logs WARN and persists a
 * minimal {@code qualityWarning} plan rather than 500-ing.
 */
class NoOpRecipePoolSource implements RecipePoolSource {

  @Override
  public List<RecipeDto> fetchPool(
      UUID householdId, List<MealSlotSkeleton> skeletons, UUID traceId) {
    return List.of();
  }
}
