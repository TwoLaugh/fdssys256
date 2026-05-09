package com.example.mealprep.recipe.domain.service;

import com.example.mealprep.recipe.api.dto.RecipeDto;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-by-others contract for the recipe module. The full LLD service surface (LLD §Service
 * Interfaces) covers 25+ methods across query / update / write-api — only the {@code getById}
 * cross-module read lands in 01a; siblings (planner, nutrition, hard-constraint filter) need it to
 * fetch a recipe by id.
 */
public interface RecipeQueryService {

  /**
   * Returns the recipe + current-version body, or empty if the recipe is missing or soft-deleted.
   */
  Optional<RecipeDto> getById(UUID recipeId);
}
