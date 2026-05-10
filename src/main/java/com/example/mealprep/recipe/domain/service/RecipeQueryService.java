package com.example.mealprep.recipe.domain.service;

import com.example.mealprep.recipe.api.dto.RecipeBranchDto;
import com.example.mealprep.recipe.api.dto.RecipeDto;
import com.example.mealprep.recipe.api.dto.RecipeImportDto;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-by-others contract for the recipe module.
 *
 * <p>01a landed {@link #getById}; recipe-01b appended {@link #getBranches} (powers {@code GET
 * /api/v1/recipes/{recipeId}/branches} and the {@code branches[]} field on {@link RecipeDto}) and
 * {@link #getImportProvenance} (powers {@code GET /api/v1/recipes/{recipeId}/import-provenance}).
 */
public interface RecipeQueryService {

  /**
   * Returns the recipe + current-version body + branches[], or empty if the recipe is missing or
   * soft-deleted.
   */
  Optional<RecipeDto> getById(UUID recipeId);

  /**
   * Returns the branches for a recipe sorted by {@code createdAt ASC} (so 'main' is first). Throws
   * {@code RecipeNotFoundException} if the recipe doesn't exist or is soft-deleted.
   */
  List<RecipeBranchDto> getBranches(UUID recipeId);

  /**
   * Returns the import provenance row for a recipe, or empty if the recipe was created manually (no
   * {@code recipe_imports} row was written). Callers are expected to map empty → 404 with {@code
   * recipe-import-not-found}, and "recipe missing" → 404 with {@code recipe-not-found}.
   */
  Optional<RecipeImportDto> getImportProvenance(UUID recipeId);
}
