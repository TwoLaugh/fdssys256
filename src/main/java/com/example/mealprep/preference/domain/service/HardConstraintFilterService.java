package com.example.mealprep.preference.domain.service;

import com.example.mealprep.preference.api.dto.FilterContext;
import com.example.mealprep.preference.api.dto.FilterResult;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Hot-path read every food-output module (recipe, planner, discovery, adaptation pipeline) calls to
 * validate that proposed ingredients satisfy a user's hard constraints. Loads the stored {@code
 * HardConstraints} aggregate, expands allergies via {@code preference_allergen_derivatives}, and
 * returns a structured {@link FilterResult} listing every violation.
 *
 * <p>Implementations run with {@code @Transactional(readOnly = true)} and never mutate state.
 *
 * <p>Per the LLD: {@code checkRecipe} accepts ingredient keys directly rather than loading the
 * recipe itself, to avoid a cross-module dependency on {@code RecipeQueryService} (the recipe
 * module also injects this filter — that would create a cycle).
 *
 * <p><b>Call context (preference-3 / LLD Flow 2 step 5).</b> Every method takes a {@link
 * FilterContext}. A context-conditional dietary-identity exception (e.g. "fish on the weekend")
 * widens the base diet <em>only when its stored {@code context} matches the call context</em>.
 * Callers that have no richer context pass {@link FilterContext#ANY}, under which only
 * universally-applicable ({@code context = "any"}) exceptions widen — the conservative, safe
 * default for a safety-critical filter.
 */
public interface HardConstraintFilterService {

  /**
   * Check a flat list of ingredient mapping keys against one user's hard constraints in the given
   * call {@code context}. Returns {@code passes=true, violations=[]} if the user has no aggregate
   * row at all (never throws on missing data).
   */
  FilterResult check(UUID userId, List<String> ingredientMappingKeys, FilterContext context);

  /**
   * As {@link #check}, but each {@link com.example.mealprep.preference.api.dto.Violation} carries
   * the supplied {@code recipeId} so upstream callers can attribute the violation back to the
   * triggering recipe.
   */
  FilterResult checkRecipe(
      UUID userId, UUID recipeId, List<String> recipeIngredientMappingKeys, FilterContext context);

  /**
   * Filter many recipes against one user's hard constraints in the given call {@code context}.
   * Returns the IDs of the recipes that pass. Loads the user's aggregate ONCE outside the
   * per-recipe loop — used by the planner's beam search at scale (1000s of recipes per planning
   * run); per-recipe aggregate loads would be a correctness bug (perf bug at minimum).
   */
  List<UUID> filterRecipes(
      UUID userId, Map<UUID, List<String>> recipesIngredientKeys, FilterContext context);

  /**
   * Union of every user's constraints in a household — a meal that violates any household member's
   * constraints fails. Each {@link com.example.mealprep.preference.api.dto.Violation} carries the
   * specific {@code userId} so the UI can attribute the violation to that household member. The
   * {@code context} applies uniformly to every member's exception evaluation.
   */
  FilterResult checkForHousehold(
      List<UUID> userIds, List<String> ingredientMappingKeys, FilterContext context);
}
