package com.example.mealprep.recipe.domain.service;

import com.example.mealprep.recipe.api.dto.CreateRecipeRequest;
import com.example.mealprep.recipe.api.dto.ImportRecipeFromUrlRequest;
import com.example.mealprep.recipe.api.dto.RecipeDto;
import java.util.UUID;

/**
 * Write-side contract for the recipe module.
 *
 * <p>01a only ships the {@code manual_create} trigger; recipe-01b appends the URL import flow
 * ({@link #importFromUrl}) which fetches + parses + persists in a single transaction with {@code
 * dataQuality = IMPORTED} and {@code trigger = IMPORT}.
 */
public interface RecipeUpdateService {

  /**
   * Creates a {@code Recipe} aggregate root with its main branch and v1 body in a single
   * transaction. Publishes {@code RecipeCreatedEvent} and {@code RecipeVersionCreatedEvent} {@code
   * AFTER_COMMIT}.
   */
  RecipeDto createRecipe(UUID userId, CreateRecipeRequest request);

  /**
   * Imports a recipe from a URL by fetching the page, running the deterministic {@code
   * HtmlImportParser}, and persisting the recipe + a {@code RecipeImport} provenance row
   * atomically. Throws {@code RecipeImportFailureException} on fetch or extraction failure.
   */
  RecipeDto importFromUrl(UUID userId, ImportRecipeFromUrlRequest request);
}
