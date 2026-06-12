package com.example.mealprep.recipe.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.hibernate.validator.constraints.URL;

/**
 * Request body for {@code POST /api/v1/recipes/imports/confirm} (recipe-3 / LLD §Flow 2). The user
 * has reviewed and possibly edited the {@link RecipeImportPreview}; this carries the authoritative
 * (edited) recipe plus the source provenance so the confirm path can persist it with {@code
 * dataQuality = IMPORTED}, write the {@code recipe_imports} row, and run dedup.
 *
 * @param previewToken the token echoed from the preview response (correlation/telemetry; see {@link
 *     RecipeImportPreview#previewToken})
 * @param sourceUrl the URL the recipe was imported from — stored on the provenance row
 * @param extractionMethod the extraction-layer label from the preview — stored on the provenance
 *     row
 * @param recipe the (possibly user-edited) recipe body to persist; re-validated server-side
 * @param ignoreDuplicateOfRecipeId dedup override ("import anyway"): persist despite an
 *     ingredient-overlap collision with exactly this recipe (the {@code candidateRecipeId} from the
 *     422). A collision with any other recipe still 422s. The honoured override is recorded as
 *     {@code duplicateOfRecipeId} on the import-provenance row.
 */
public record ConfirmImportRequest(
    @Size(max = 200) String previewToken,
    @NotBlank @URL @Size(max = 2048) String sourceUrl,
    @Size(max = 64) String extractionMethod,
    @NotNull @Valid CreateRecipeRequest recipe,
    UUID ignoreDuplicateOfRecipeId) {

  /**
   * Pre-override 4-arg constructor — defaults {@code ignoreDuplicateOfRecipeId} to {@code null}
   * (additive-DTO convention). Existing call sites compile unchanged.
   */
  public ConfirmImportRequest(
      String previewToken, String sourceUrl, String extractionMethod, CreateRecipeRequest recipe) {
    this(previewToken, sourceUrl, extractionMethod, recipe, null);
  }
}
