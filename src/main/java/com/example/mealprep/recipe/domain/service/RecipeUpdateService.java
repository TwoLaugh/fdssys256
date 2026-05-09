package com.example.mealprep.recipe.domain.service;

import com.example.mealprep.recipe.api.dto.CreateRecipeRequest;
import com.example.mealprep.recipe.api.dto.RecipeDto;
import java.util.UUID;

/**
 * Write-side contract for the recipe module. 01a only ships the {@code manual_create} trigger;
 * later sub-tickets layer on manual edit, branch creation, substitutions, adaptation, etc.
 */
public interface RecipeUpdateService {

  /**
   * Creates a {@code Recipe} aggregate root with its main branch and v1 body in a single
   * transaction. Publishes {@code RecipeCreatedEvent} and {@code RecipeVersionCreatedEvent} {@code
   * AFTER_COMMIT}.
   */
  RecipeDto createRecipe(UUID userId, CreateRecipeRequest request);
}
