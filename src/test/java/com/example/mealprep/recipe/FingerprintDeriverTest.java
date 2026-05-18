package com.example.mealprep.recipe;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.recipe.api.dto.CharacterFingerprintDto;
import com.example.mealprep.recipe.api.dto.CreateIngredientRequest;
import com.example.mealprep.recipe.api.dto.CreateRecipeMetadataRequest;
import com.example.mealprep.recipe.api.dto.CreateRecipeTagsRequest;
import com.example.mealprep.recipe.domain.entity.Complexity;
import com.example.mealprep.recipe.domain.entity.RecipeIngredient;
import com.example.mealprep.recipe.domain.entity.RecipeMetadata;
import com.example.mealprep.recipe.domain.entity.RecipeTags;
import com.example.mealprep.recipe.domain.entity.RecipeVersion;
import com.example.mealprep.recipe.domain.service.internal.FingerprintDeriver;
import com.example.mealprep.recipe.domain.service.internal.NewVersionInput;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pure-unit coverage of {@link FingerprintDeriver}: top-N truncation, line-order sorting, the
 * null/empty fall-throughs on tags + metadata, and the {@code MODERATE} complexity defaulting. Real
 * instance, no mocking (pure logic).
 */
class FingerprintDeriverTest {

  private final FingerprintDeriver deriver = new FingerprintDeriver();

  // ---------------- deriveFromBody ----------------

  @Test
  void deriveFromBody_picksTopThreeIngredients_sortedByLineOrder() {
    NewVersionInput body =
        new NewVersionInput(
            List.of(
                ingredientReq(3, "salt"),
                ingredientReq(1, "carrot"),
                ingredientReq(0, "beef"),
                ingredientReq(2, "potato")),
            List.of(),
            metadataReq("Thai"),
            tagsReq(Complexity.INVOLVED, List.of("spicy", "sour")));

    CharacterFingerprintDto fp = deriver.deriveFromBody(body);

    // Sorted by lineOrder ascending then capped at 3 → beef, carrot, potato (salt dropped).
    assertThat(fp.definingIngredients()).containsExactly("beef", "carrot", "potato");
    assertThat(fp.definingTechniques()).isEmpty();
    assertThat(fp.textureEssentials()).isEmpty();
    assertThat(fp.flavourAnchors()).containsExactly("spicy", "sour");
    assertThat(fp.complexityTier()).isEqualTo(Complexity.INVOLVED);
    assertThat(fp.cuisineAnchor()).isEqualTo("Thai");
  }

  @Test
  void deriveFromBody_nullIngredients_yieldsEmptyDefiningList() {
    NewVersionInput body =
        new NewVersionInput(
            null,
            List.of(),
            metadataReq("Italian"),
            tagsReq(Complexity.MINIMAL, List.of("savoury")));

    CharacterFingerprintDto fp = deriver.deriveFromBody(body);

    assertThat(fp.definingIngredients()).isEmpty();
    assertThat(fp.complexityTier()).isEqualTo(Complexity.MINIMAL);
  }

  @Test
  void deriveFromBody_emptyIngredients_yieldsEmptyDefiningList() {
    NewVersionInput body =
        new NewVersionInput(
            List.of(), List.of(), metadataReq("Italian"), tagsReq(Complexity.MODERATE, List.of()));

    assertThat(deriver.deriveFromBody(body).definingIngredients()).isEmpty();
  }

  @Test
  void deriveFromBody_flavourAnchorsCappedAtFive() {
    NewVersionInput body =
        new NewVersionInput(
            List.of(ingredientReq(0, "egg")),
            List.of(),
            metadataReq("French"),
            tagsReq(Complexity.MODERATE, List.of("a", "b", "c", "d", "e", "f", "g")));

    CharacterFingerprintDto fp = deriver.deriveFromBody(body);

    assertThat(fp.flavourAnchors()).containsExactly("a", "b", "c", "d", "e");
  }

  @Test
  void deriveFromBody_nullTags_defaultsComplexityModerateAndEmptyFlavour() {
    NewVersionInput body =
        new NewVersionInput(
            List.of(ingredientReq(0, "egg")), List.of(), metadataReq("French"), null);

    CharacterFingerprintDto fp = deriver.deriveFromBody(body);

    assertThat(fp.complexityTier()).isEqualTo(Complexity.MODERATE);
    assertThat(fp.flavourAnchors()).isEmpty();
  }

  @Test
  void deriveFromBody_tagsPresentButNullComplexityAndNullFlavour_defaults() {
    CreateRecipeTagsRequest tags =
        new CreateRecipeTagsRequest("beef", "stovetop", null, null, List.of());
    NewVersionInput body =
        new NewVersionInput(
            List.of(ingredientReq(0, "egg")), List.of(), metadataReq("French"), tags);

    CharacterFingerprintDto fp = deriver.deriveFromBody(body);

    assertThat(fp.complexityTier()).isEqualTo(Complexity.MODERATE);
    assertThat(fp.flavourAnchors()).isEmpty();
  }

  @Test
  void deriveFromBody_nullMetadata_yieldsNullCuisine() {
    NewVersionInput body =
        new NewVersionInput(
            List.of(ingredientReq(0, "egg")),
            List.of(),
            null,
            tagsReq(Complexity.MODERATE, List.of("savoury")));

    assertThat(deriver.deriveFromBody(body).cuisineAnchor()).isNull();
  }

  @Test
  void deriveFromBody_metadataPresentNullCuisine_yieldsNullCuisine() {
    NewVersionInput body =
        new NewVersionInput(
            List.of(ingredientReq(0, "egg")),
            List.of(),
            metadataReq(null),
            tagsReq(Complexity.MODERATE, List.of("savoury")));

    assertThat(deriver.deriveFromBody(body).cuisineAnchor()).isNull();
  }

  @Test
  void deriveFromBody_exactlyThreeIngredients_keepsAll() {
    NewVersionInput body =
        new NewVersionInput(
            List.of(ingredientReq(0, "a"), ingredientReq(1, "b"), ingredientReq(2, "c")),
            List.of(),
            metadataReq("Italian"),
            tagsReq(Complexity.MODERATE, List.of()));

    assertThat(deriver.deriveFromBody(body).definingIngredients()).containsExactly("a", "b", "c");
  }

  // ---------------- deriveFromVersion ----------------

  @Test
  void deriveFromVersion_sortsIngredientsByLineOrder_capsAtThree() {
    RecipeVersion version = bareVersion();
    version.setIngredients(
        List.of(
            versionIngredient(2, "potato"),
            versionIngredient(0, "beef"),
            versionIngredient(3, "salt"),
            versionIngredient(1, "carrot")));
    version.setTags(tagsEntity(Complexity.INVOLVED, List.of("spicy")));
    version.setMetadata(metadataEntity("Thai"));

    CharacterFingerprintDto fp = deriver.deriveFromVersion(version);

    assertThat(fp.definingIngredients()).containsExactly("beef", "carrot", "potato");
    assertThat(fp.flavourAnchors()).containsExactly("spicy");
    assertThat(fp.complexityTier()).isEqualTo(Complexity.INVOLVED);
    assertThat(fp.cuisineAnchor()).isEqualTo("Thai");
  }

  @Test
  void deriveFromVersion_nullIngredients_yieldsEmptyDefiningList() {
    RecipeVersion version = bareVersion();
    version.setIngredients(null);
    version.setTags(tagsEntity(Complexity.MODERATE, List.of("savoury")));
    version.setMetadata(metadataEntity("Italian"));

    assertThat(deriver.deriveFromVersion(version).definingIngredients()).isEmpty();
  }

  @Test
  void deriveFromVersion_nullTags_defaultsModerateAndEmptyFlavour() {
    RecipeVersion version = bareVersion();
    version.setIngredients(List.of(versionIngredient(0, "rice")));
    version.setTags(null);
    version.setMetadata(metadataEntity("Japanese"));

    CharacterFingerprintDto fp = deriver.deriveFromVersion(version);

    assertThat(fp.complexityTier()).isEqualTo(Complexity.MODERATE);
    assertThat(fp.flavourAnchors()).isEmpty();
    assertThat(fp.definingIngredients()).containsExactly("rice");
  }

  @Test
  void deriveFromVersion_tagsWithNullFlavourAndNullComplexity_defaults() {
    RecipeVersion version = bareVersion();
    version.setIngredients(List.of(versionIngredient(0, "rice")));
    RecipeTags tags = RecipeTags.builder().id(UUID.randomUUID()).build();
    tags.setFlavourProfile(null);
    tags.setComplexity(null);
    version.setTags(tags);
    version.setMetadata(metadataEntity("Japanese"));

    CharacterFingerprintDto fp = deriver.deriveFromVersion(version);

    assertThat(fp.complexityTier()).isEqualTo(Complexity.MODERATE);
    assertThat(fp.flavourAnchors()).isEmpty();
  }

  @Test
  void deriveFromVersion_flavourCappedAtFive() {
    RecipeVersion version = bareVersion();
    version.setIngredients(List.of(versionIngredient(0, "rice")));
    version.setTags(tagsEntity(Complexity.MODERATE, List.of("a", "b", "c", "d", "e", "f")));
    version.setMetadata(metadataEntity("Japanese"));

    assertThat(deriver.deriveFromVersion(version).flavourAnchors())
        .containsExactly("a", "b", "c", "d", "e");
  }

  @Test
  void deriveFromVersion_nullMetadata_yieldsNullCuisine() {
    RecipeVersion version = bareVersion();
    version.setIngredients(List.of(versionIngredient(0, "rice")));
    version.setTags(tagsEntity(Complexity.MODERATE, List.of("savoury")));
    version.setMetadata(null);

    assertThat(deriver.deriveFromVersion(version).cuisineAnchor()).isNull();
  }

  // ---------------- helpers ----------------

  private static CreateIngredientRequest ingredientReq(int lineOrder, String name) {
    return new CreateIngredientRequest(lineOrder, name + ".key", name, null, null, null, false);
  }

  private static CreateRecipeMetadataRequest metadataReq(String cuisine) {
    return new CreateRecipeMetadataRequest(
        1, 0, 0, 0, List.of(), null, null, false, cuisine, List.of());
  }

  private static CreateRecipeTagsRequest tagsReq(Complexity complexity, List<String> flavour) {
    return new CreateRecipeTagsRequest("beef", "stovetop", complexity, flavour, List.of());
  }

  private static RecipeVersion bareVersion() {
    return RecipeVersion.builder()
        .id(UUID.randomUUID())
        .versionNumber(1)
        .ingredients(new ArrayList<>())
        .methodSteps(new ArrayList<>())
        .build();
  }

  private static RecipeIngredient versionIngredient(int lineOrder, String name) {
    return RecipeIngredient.builder()
        .id(UUID.randomUUID())
        .lineOrder(lineOrder)
        .ingredientMappingKey(name + ".key")
        .displayName(name)
        .quantity(new BigDecimal("1.000"))
        .optional(false)
        .needsReview(false)
        .build();
  }

  private static RecipeTags tagsEntity(Complexity complexity, List<String> flavour) {
    return RecipeTags.builder()
        .id(UUID.randomUUID())
        .protein("beef")
        .cookingMethod("stovetop")
        .complexity(complexity)
        .flavourProfile(new ArrayList<>(flavour))
        .dietaryFlags(new ArrayList<>())
        .build();
  }

  private static RecipeMetadata metadataEntity(String cuisine) {
    return RecipeMetadata.builder()
        .id(UUID.randomUUID())
        .servings(2)
        .prepTimeMins(5)
        .cookTimeMins(10)
        .totalTimeMins(15)
        .equipmentRequired(new ArrayList<>())
        .packable(false)
        .cuisine(cuisine)
        .mealTypes(new ArrayList<>())
        .build();
  }
}
