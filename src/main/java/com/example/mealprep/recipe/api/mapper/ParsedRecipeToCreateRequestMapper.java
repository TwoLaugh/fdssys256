package com.example.mealprep.recipe.api.mapper;

import com.example.mealprep.recipe.api.dto.CreateIngredientRequest;
import com.example.mealprep.recipe.api.dto.CreateMethodStepRequest;
import com.example.mealprep.recipe.api.dto.CreateRecipeMetadataRequest;
import com.example.mealprep.recipe.api.dto.CreateRecipeRequest;
import com.example.mealprep.recipe.domain.service.internal.HtmlImportParser.ParsedRecipe;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Maps a deterministic {@link ParsedRecipe} to a {@link CreateRecipeRequest} that satisfies 01a's
 * Jakarta validators (notably {@code @ValidRecipeMetadata}, which asserts {@code totalTimeMins ≈
 * prepTimeMins + cookTimeMins ± 1}).
 *
 * <p>If the parser did not deliver explicit prep/cook/total minutes, all three default to 0 (which
 * trivially satisfies the equality validator). When at least one of prep/cook is present, total is
 * computed as their sum so the validator passes.
 */
@Component
public class ParsedRecipeToCreateRequestMapper {

  private static final int DEFAULT_SERVINGS = 1;
  private static final int MAX_NAME_LENGTH = 160;
  private static final int MAX_DESCRIPTION_LENGTH = 2000;
  private static final int MAX_DISPLAY_NAME_LENGTH = 160;
  private static final int MAX_MAPPING_KEY_LENGTH = 160;

  public CreateRecipeRequest map(ParsedRecipe parsed) {
    String name = clamp(parsed.name(), MAX_NAME_LENGTH);
    String description = clamp(parsed.description(), MAX_DESCRIPTION_LENGTH);
    List<CreateIngredientRequest> ingredients = mapIngredients(parsed.ingredientLines());
    List<CreateMethodStepRequest> method = mapMethodSteps(parsed.methodSteps());
    CreateRecipeMetadataRequest metadata = buildMetadata(parsed);
    return new CreateRecipeRequest(name, description, ingredients, method, metadata, null);
  }

  private static List<CreateIngredientRequest> mapIngredients(List<String> lines) {
    List<CreateIngredientRequest> out = new ArrayList<>();
    if (lines == null) {
      return out;
    }
    int order = 0;
    for (String raw : lines) {
      if (raw == null || raw.isBlank()) {
        continue;
      }
      String displayName = clamp(raw.trim(), MAX_DISPLAY_NAME_LENGTH);
      String mappingKey = clamp(deriveMappingKey(displayName), MAX_MAPPING_KEY_LENGTH);
      out.add(
          new CreateIngredientRequest(order++, mappingKey, displayName, null, null, null, false));
    }
    return out;
  }

  private static List<CreateMethodStepRequest> mapMethodSteps(List<String> steps) {
    List<CreateMethodStepRequest> out = new ArrayList<>();
    if (steps == null) {
      return out;
    }
    int idx = 1;
    for (String step : steps) {
      if (step == null || step.isBlank()) {
        continue;
      }
      out.add(new CreateMethodStepRequest(idx++, step.trim(), null));
    }
    return out;
  }

  private static CreateRecipeMetadataRequest buildMetadata(ParsedRecipe parsed) {
    int prep = parsed.prepMinutes() != null ? parsed.prepMinutes() : 0;
    int cook = parsed.cookMinutes() != null ? parsed.cookMinutes() : 0;
    int total;
    if (parsed.totalMinutes() != null) {
      // If explicit total disagrees with prep+cook, prefer prep+cook (validator allows ± 1).
      int sum = prep + cook;
      total = Math.abs(parsed.totalMinutes() - sum) <= 1 ? parsed.totalMinutes() : sum;
    } else {
      total = prep + cook;
    }
    int servings =
        parsed.servings() != null && parsed.servings() > 0 ? parsed.servings() : DEFAULT_SERVINGS;
    String cuisine = clamp(parsed.cuisine(), 64);
    return new CreateRecipeMetadataRequest(
        servings, prep, cook, total, List.of(), null, null, false, cuisine, List.of());
  }

  private static String deriveMappingKey(String displayName) {
    if (displayName == null || displayName.isBlank()) {
      return "imported.unknown";
    }
    String slug =
        displayName.toLowerCase().replaceAll("[^a-z0-9]+", ".").replaceAll("^\\.+|\\.+$", "");
    if (slug.isEmpty()) {
      slug = "ingredient";
    }
    return "imported." + slug;
  }

  private static String clamp(String value, int max) {
    if (value == null) {
      return null;
    }
    return value.length() > max ? value.substring(0, max) : value;
  }
}
