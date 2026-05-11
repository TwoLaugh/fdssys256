package com.example.mealprep.recipe.domain.service.internal;

import com.example.mealprep.recipe.api.dto.CharacterFingerprintDto;
import com.example.mealprep.recipe.api.dto.CreateIngredientRequest;
import com.example.mealprep.recipe.api.dto.CreateRecipeMetadataRequest;
import com.example.mealprep.recipe.api.dto.CreateRecipeTagsRequest;
import com.example.mealprep.recipe.domain.entity.Complexity;
import com.example.mealprep.recipe.domain.entity.RecipeIngredient;
import com.example.mealprep.recipe.domain.entity.RecipeMetadata;
import com.example.mealprep.recipe.domain.entity.RecipeTags;
import com.example.mealprep.recipe.domain.entity.RecipeVersion;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Derives a minimal {@link CharacterFingerprintDto} from a new-version body. Used by branch
 * creation when the caller doesn't supply {@code fingerprintOverride}.
 *
 * <p>Pipeline-driven AI inference will fill in {@code definingTechniques} and {@code
 * textureEssentials} later (recipe-01k). 01d's derivation is intentionally minimal — see ticket
 * "LLD divergence note: fingerprint derivation".
 */
@Component
public class FingerprintDeriver {

  private static final int MAX_DEFINING_INGREDIENTS = 3;
  private static final int MAX_FLAVOUR_ANCHORS = 5;

  /** Derive from a {@link NewVersionInput} carrier (write-time call site). */
  public CharacterFingerprintDto deriveFromBody(NewVersionInput body) {
    List<String> definingIngredients = topIngredientNames(body.ingredients());
    List<String> flavour = flavourAnchors(body.tags());
    Complexity complexity = complexity(body.tags());
    String cuisine = cuisine(body.metadata());
    return new CharacterFingerprintDto(
        definingIngredients, List.of(), List.of(), flavour, complexity, cuisine);
  }

  /**
   * Derive from a persisted {@link RecipeVersion} body (read-time call site used when the parent
   * branch-point version never had a fingerprint populated, e.g. main's v1 from 01a).
   */
  public CharacterFingerprintDto deriveFromVersion(RecipeVersion version) {
    List<RecipeIngredient> ingredients =
        version.getIngredients() != null ? version.getIngredients() : List.of();
    List<String> definingIngredients = new ArrayList<>();
    List<RecipeIngredient> sortedIngredients = new ArrayList<>(ingredients);
    sortedIngredients.sort(Comparator.comparingInt(RecipeIngredient::getLineOrder));
    for (int i = 0; i < sortedIngredients.size() && i < MAX_DEFINING_INGREDIENTS; i++) {
      definingIngredients.add(sortedIngredients.get(i).getDisplayName());
    }
    RecipeTags tags = version.getTags();
    List<String> flavour;
    Complexity complexity;
    if (tags == null) {
      flavour = List.of();
      complexity = Complexity.MODERATE;
    } else {
      flavour =
          tags.getFlavourProfile() == null
              ? List.of()
              : tags.getFlavourProfile().stream().limit(MAX_FLAVOUR_ANCHORS).toList();
      complexity = tags.getComplexity() == null ? Complexity.MODERATE : tags.getComplexity();
    }
    RecipeMetadata metadata = version.getMetadata();
    String cuisine = metadata == null ? null : metadata.getCuisine();
    return new CharacterFingerprintDto(
        definingIngredients, List.of(), List.of(), flavour, complexity, cuisine);
  }

  private static List<String> topIngredientNames(List<CreateIngredientRequest> ingredients) {
    if (ingredients == null || ingredients.isEmpty()) {
      return Collections.emptyList();
    }
    return ingredients.stream()
        .sorted(Comparator.comparingInt(CreateIngredientRequest::lineOrder))
        .limit(MAX_DEFINING_INGREDIENTS)
        .map(CreateIngredientRequest::displayName)
        .toList();
  }

  private static List<String> flavourAnchors(CreateRecipeTagsRequest tags) {
    if (tags == null || tags.flavourProfile() == null) {
      return Collections.emptyList();
    }
    return tags.flavourProfile().stream().limit(MAX_FLAVOUR_ANCHORS).toList();
  }

  private static Complexity complexity(CreateRecipeTagsRequest tags) {
    if (tags == null || tags.complexity() == null) {
      return Complexity.MODERATE;
    }
    return tags.complexity();
  }

  private static String cuisine(CreateRecipeMetadataRequest metadata) {
    return metadata == null ? null : metadata.cuisine();
  }
}
