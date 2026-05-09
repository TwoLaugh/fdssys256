package com.example.mealprep.recipe;

import com.example.mealprep.recipe.domain.service.RecipeQueryService;
import com.example.mealprep.recipe.domain.service.RecipeUpdateService;
import org.springframework.stereotype.Component;

/**
 * Module facade re-exporting the recipe module's public service interfaces. Cross-module callers
 * inject this (or an individual service) rather than reaching into {@code domain.service.*}
 * directly.
 *
 * <p>Mirrors {@code AuthModule} / {@code PreferenceModule} / {@code HouseholdModule} / {@code
 * NutritionModule}; thin and carries no business logic. 01a lands {@code getById} + {@code
 * createRecipe}; later sub-tickets add manual-edit, branching, substitutions, adaptation, imports,
 * search, etc.
 */
@Component
public class RecipeModule {

  private final RecipeQueryService recipeQueryService;
  private final RecipeUpdateService recipeUpdateService;

  public RecipeModule(
      RecipeQueryService recipeQueryService, RecipeUpdateService recipeUpdateService) {
    this.recipeQueryService = recipeQueryService;
    this.recipeUpdateService = recipeUpdateService;
  }

  public RecipeQueryService query() {
    return recipeQueryService;
  }

  public RecipeUpdateService update() {
    return recipeUpdateService;
  }
}
