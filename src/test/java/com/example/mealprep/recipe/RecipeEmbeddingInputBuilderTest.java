package com.example.mealprep.recipe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.example.mealprep.recipe.domain.entity.Catalogue;
import com.example.mealprep.recipe.domain.entity.DataQuality;
import com.example.mealprep.recipe.domain.entity.NutritionStatus;
import com.example.mealprep.recipe.domain.entity.Recipe;
import com.example.mealprep.recipe.domain.entity.RecipeBranch;
import com.example.mealprep.recipe.domain.entity.RecipeIngredient;
import com.example.mealprep.recipe.domain.entity.RecipeMetadata;
import com.example.mealprep.recipe.domain.entity.RecipeTags;
import com.example.mealprep.recipe.domain.entity.RecipeVersion;
import com.example.mealprep.recipe.domain.entity.VersionTrigger;
import com.example.mealprep.recipe.domain.repository.RecipeVersionRepository;
import com.example.mealprep.recipe.domain.service.internal.RecipeEmbeddingInputBuilder;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link RecipeEmbeddingInputBuilder}. Covers composition shape, null-field skip,
 * ingredient ordering by {@code line_order}, and the missing-version null return.
 */
@ExtendWith(MockitoExtension.class)
class RecipeEmbeddingInputBuilderTest {

  @Mock private RecipeVersionRepository versionRepository;

  private RecipeEmbeddingInputBuilder builder() {
    return new RecipeEmbeddingInputBuilder(versionRepository);
  }

  @Test
  void loadAndCompose_missingVersion_returnsNull() {
    UUID versionId = UUID.randomUUID();
    when(versionRepository.findById(versionId)).thenReturn(Optional.empty());
    assertThat(builder().loadAndCompose(versionId)).isNull();
  }

  @Test
  void loadAndCompose_composesNameDescriptionCuisineTagsAndIngredients() {
    UUID versionId = UUID.randomUUID();
    UUID recipeId = UUID.randomUUID();
    Recipe recipe =
        Recipe.builder()
            .id(recipeId)
            .userId(UUID.randomUUID())
            .catalogue(Catalogue.USER)
            .name("Lemon chicken")
            .description("Light weekday dinner")
            .currentVersion(1)
            .currentBranchId(UUID.randomUUID())
            .dataQuality(DataQuality.USER_VERIFIED)
            .nutritionStatus(NutritionStatus.PENDING)
            .build();
    RecipeVersion version = bareVersion(versionId, recipe);

    RecipeMetadata md =
        RecipeMetadata.builder()
            .id(UUID.randomUUID())
            .version(version)
            .servings(2)
            .prepTimeMins(10)
            .cookTimeMins(20)
            .totalTimeMins(30)
            .equipmentRequired(new ArrayList<>())
            .packable(false)
            .cuisine("Mediterranean")
            .mealTypes(new ArrayList<>())
            .build();
    version.setMetadata(md);

    RecipeTags tags =
        RecipeTags.builder()
            .id(UUID.randomUUID())
            .version(version)
            .protein("chicken")
            .cookingMethod("pan-fry")
            .flavourProfile(List.of("citrus", "savoury"))
            .dietaryFlags(new ArrayList<>())
            .build();
    version.setTags(tags);

    version.setIngredients(
        List.of(ingredient(version, 1, "chicken breast"), ingredient(version, 0, "lemon")));

    when(versionRepository.findById(versionId)).thenReturn(Optional.of(version));

    String composed = builder().loadAndCompose(versionId);
    assertThat(composed)
        .isEqualTo(
            "Lemon chicken Light weekday dinner Mediterranean chicken pan-fry citrus,savoury lemon,chicken breast");
  }

  @Test
  void loadAndCompose_nullFieldsAreSkipped_notWrittenAsLiteralNull() {
    UUID versionId = UUID.randomUUID();
    Recipe recipe =
        Recipe.builder()
            .id(UUID.randomUUID())
            .userId(UUID.randomUUID())
            .catalogue(Catalogue.USER)
            .name("Plain rice")
            .description(null)
            .currentVersion(1)
            .currentBranchId(UUID.randomUUID())
            .dataQuality(DataQuality.USER_VERIFIED)
            .nutritionStatus(NutritionStatus.PENDING)
            .build();
    RecipeVersion version = bareVersion(versionId, recipe);
    version.setIngredients(List.of(ingredient(version, 0, "rice")));
    when(versionRepository.findById(versionId)).thenReturn(Optional.of(version));

    assertThat(builder().loadAndCompose(versionId)).isEqualTo("Plain rice rice");
  }

  @Test
  void loadAndCompose_blankOrNullDisplayNames_areFilteredOut() {
    // The ingredient stream filter `s -> s != null && !s.isBlank()` must drop blank/null names so
    // the composed string has no stray commas — kills the L83 "replaced boolean return with true"
    // mutant which would include the blank entries.
    UUID versionId = UUID.randomUUID();
    Recipe recipe =
        Recipe.builder()
            .id(UUID.randomUUID())
            .userId(UUID.randomUUID())
            .catalogue(Catalogue.USER)
            .name("Soup")
            .description(null)
            .currentVersion(1)
            .currentBranchId(UUID.randomUUID())
            .dataQuality(DataQuality.USER_VERIFIED)
            .nutritionStatus(NutritionStatus.PENDING)
            .build();
    RecipeVersion version = bareVersion(versionId, recipe);
    version.setIngredients(
        List.of(
            ingredient(version, 0, "stock"),
            ingredient(version, 1, "   "),
            ingredient(version, 2, null),
            ingredient(version, 3, "carrot")));
    when(versionRepository.findById(versionId)).thenReturn(Optional.of(version));

    assertThat(builder().loadAndCompose(versionId)).isEqualTo("Soup stock,carrot");
  }

  @Test
  void loadAndCompose_deterministicOrdering_byLineOrder() {
    UUID versionId = UUID.randomUUID();
    Recipe recipe =
        Recipe.builder()
            .id(UUID.randomUUID())
            .userId(UUID.randomUUID())
            .catalogue(Catalogue.USER)
            .name("Stew")
            .description(null)
            .currentVersion(1)
            .currentBranchId(UUID.randomUUID())
            .dataQuality(DataQuality.USER_VERIFIED)
            .nutritionStatus(NutritionStatus.PENDING)
            .build();
    RecipeVersion version = bareVersion(versionId, recipe);
    version.setIngredients(
        List.of(
            ingredient(version, 3, "salt"),
            ingredient(version, 1, "carrot"),
            ingredient(version, 2, "potato"),
            ingredient(version, 0, "beef")));
    when(versionRepository.findById(versionId)).thenReturn(Optional.of(version));

    assertThat(builder().loadAndCompose(versionId)).isEqualTo("Stew beef,carrot,potato,salt");
  }

  // ---------------- helpers ----------------

  private static RecipeVersion bareVersion(UUID id, Recipe recipe) {
    return RecipeVersion.builder()
        .id(id)
        .recipe(recipe)
        .branch(
            RecipeBranch.builder()
                .id(UUID.randomUUID())
                .recipe(recipe)
                .name("main")
                .currentVersion(1)
                .divergenceScore(new BigDecimal("0.000"))
                .createdByActor("user:" + UUID.randomUUID())
                .build())
        .versionNumber(1)
        .changeDiff(JsonNodeFactory.instance.objectNode())
        .trigger(VersionTrigger.MANUAL_CREATE)
        .embeddingStatus("pending")
        .createdByActor("user:" + UUID.randomUUID())
        .ingredients(new ArrayList<>())
        .methodSteps(new ArrayList<>())
        .build();
  }

  private static RecipeIngredient ingredient(RecipeVersion version, int lineOrder, String name) {
    return RecipeIngredient.builder()
        .id(UUID.randomUUID())
        .version(version)
        .lineOrder(lineOrder)
        .ingredientMappingKey(name)
        .displayName(name)
        .optional(false)
        .needsReview(false)
        .build();
  }
}
