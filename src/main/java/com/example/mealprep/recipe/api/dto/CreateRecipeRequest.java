package com.example.mealprep.recipe.api.dto;

import com.example.mealprep.recipe.validation.ValidIngredientList;
import com.example.mealprep.recipe.validation.ValidMethodSteps;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * Request body for {@code POST /api/v1/recipes} ({@code manual_create} trigger).
 *
 * <p>{@code ignoreDuplicateOfRecipeId} (recipe-import-dedup-consistency ticket) is the dedup
 * override: when the previous attempt 422'd with {@code recipe-import-duplicate}, re-submitting
 * with this field set to the 422's {@code candidateRecipeId} persists anyway. A collision with any
 * <b>other</b> recipe still 422s — the client must name the exact candidate it is overriding, so a
 * new collision appearing between attempts is never blind-forced. Ignored when no collision occurs.
 */
public record CreateRecipeRequest(
    @NotBlank @Size(max = 160) String name,
    @Size(max = 2000) String description,
    @NotEmpty @Valid @ValidIngredientList List<CreateIngredientRequest> ingredients,
    @NotEmpty @Valid @ValidMethodSteps List<CreateMethodStepRequest> method,
    @NotNull @Valid CreateRecipeMetadataRequest metadata,
    @Valid CreateRecipeTagsRequest tags,
    UUID ignoreDuplicateOfRecipeId) {

  /**
   * Pre-override 6-arg constructor — defaults {@code ignoreDuplicateOfRecipeId} to {@code null}
   * (additive-DTO convention). Existing call sites compile unchanged.
   */
  public CreateRecipeRequest(
      String name,
      String description,
      List<CreateIngredientRequest> ingredients,
      List<CreateMethodStepRequest> method,
      CreateRecipeMetadataRequest metadata,
      CreateRecipeTagsRequest tags) {
    this(name, description, ingredients, method, metadata, tags, null);
  }
}
