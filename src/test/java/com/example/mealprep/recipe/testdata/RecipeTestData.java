package com.example.mealprep.recipe.testdata;

import com.example.mealprep.recipe.api.dto.CreateIngredientRequest;
import com.example.mealprep.recipe.api.dto.CreateMethodStepRequest;
import com.example.mealprep.recipe.api.dto.CreateRecipeMetadataRequest;
import com.example.mealprep.recipe.api.dto.CreateRecipeRequest;
import com.example.mealprep.recipe.api.dto.CreateRecipeTagsRequest;
import com.example.mealprep.recipe.domain.entity.Complexity;
import java.math.BigDecimal;
import java.util.List;

/**
 * Test Data Builder for the recipe module. Defaults pass all 01a validators so callers tweak only
 * the field under test.
 */
public final class RecipeTestData {

  private RecipeTestData() {}

  public static CreateRecipeRequest defaultCreateRequest() {
    return new CreateRecipeRequest(
        "Spaghetti Bolognese",
        "Hearty weeknight pasta.",
        defaultIngredients(),
        defaultMethod(),
        defaultMetadata(),
        defaultTags());
  }

  public static CreateRecipeRequest createRequestWithName(String name) {
    return new CreateRecipeRequest(
        name,
        "Hearty weeknight pasta.",
        defaultIngredients(),
        defaultMethod(),
        defaultMetadata(),
        defaultTags());
  }

  public static CreateRecipeRequest createRequestWithoutTags() {
    return new CreateRecipeRequest(
        "Spaghetti Bolognese",
        "Hearty weeknight pasta.",
        defaultIngredients(),
        defaultMethod(),
        defaultMetadata(),
        null);
  }

  public static List<CreateIngredientRequest> defaultIngredients() {
    return List.of(
        new CreateIngredientRequest(
            0, "spaghetti.dry", "Spaghetti", new BigDecimal("400.000"), "g", null, false),
        new CreateIngredientRequest(
            1, "beef.mince", "Lean beef mince", new BigDecimal("500.000"), "g", null, false),
        new CreateIngredientRequest(
            2, "tomato.passata", "Passata", new BigDecimal("700.000"), "g", null, false));
  }

  public static List<CreateMethodStepRequest> defaultMethod() {
    return List.of(
        new CreateMethodStepRequest(1, "Brown the mince in a wide pan.", 8),
        new CreateMethodStepRequest(2, "Add passata and simmer for 25 minutes.", 25),
        new CreateMethodStepRequest(3, "Cook spaghetti to al dente; drain.", 9));
  }

  public static CreateRecipeMetadataRequest defaultMetadata() {
    return new CreateRecipeMetadataRequest(
        4, 15, 30, 45, List.of("large pan", "colander"), 3, 2, true, "Italian", List.of("DINNER"));
  }

  public static CreateRecipeTagsRequest defaultTags() {
    return new CreateRecipeTagsRequest(
        "beef", "stovetop", Complexity.MODERATE, List.of("savoury", "umami"), List.of());
  }
}
