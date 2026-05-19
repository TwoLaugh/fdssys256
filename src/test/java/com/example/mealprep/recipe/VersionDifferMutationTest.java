package com.example.mealprep.recipe;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.recipe.api.dto.CreateIngredientRequest;
import com.example.mealprep.recipe.api.dto.CreateMethodStepRequest;
import com.example.mealprep.recipe.api.dto.CreateRecipeMetadataRequest;
import com.example.mealprep.recipe.api.dto.CreateRecipeTagsRequest;
import com.example.mealprep.recipe.domain.entity.Complexity;
import com.example.mealprep.recipe.domain.entity.RecipeIngredient;
import com.example.mealprep.recipe.domain.entity.RecipeMetadata;
import com.example.mealprep.recipe.domain.entity.RecipeMethodStep;
import com.example.mealprep.recipe.domain.entity.RecipeTags;
import com.example.mealprep.recipe.domain.entity.RecipeVersion;
import com.example.mealprep.recipe.domain.service.internal.NewVersionInput;
import com.example.mealprep.recipe.domain.service.internal.VersionDiffer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Mutation-killing unit tests for {@link VersionDiffer}. Each test asserts the precise per-field
 * diff entry so that the {@code addMetadataChange}/{@code addTagChange} void-call removal mutants,
 * the {@code parent != null ? ... : null} negate mutants, and the {@code equalBigDecimal}/{@code
 * equalNullable}/{@code isEmpty}/{@code sectionEmpty} return mutants are detectable. Complements
 * the happy-path coverage in {@link VersionDifferTest}.
 */
class VersionDifferMutationTest {

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final VersionDiffer differ = new VersionDiffer(objectMapper);

  // ---------------- builders ----------------

  private RecipeVersion versionWith(
      List<RecipeIngredient> ingredients,
      List<RecipeMethodStep> methodSteps,
      RecipeMetadata metadata,
      RecipeTags tags) {
    RecipeVersion v =
        RecipeVersion.builder()
            .id(UUID.randomUUID())
            .ingredients(new ArrayList<>(ingredients))
            .methodSteps(new ArrayList<>(methodSteps))
            .build();
    v.setMetadata(metadata);
    v.setTags(tags);
    return v;
  }

  private RecipeIngredient ingredient(
      String key, String prep, String displayName, BigDecimal qty, String unit, int lineOrder) {
    return RecipeIngredient.builder()
        .id(UUID.randomUUID())
        .ingredientMappingKey(key)
        .preparation(prep)
        .displayName(displayName)
        .quantity(qty)
        .unit(unit)
        .optional(false)
        .needsReview(false)
        .lineOrder(lineOrder)
        .build();
  }

  private CreateIngredientRequest ingredientRequest(
      String key, String prep, String displayName, BigDecimal qty, String unit, int lineOrder) {
    return new CreateIngredientRequest(lineOrder, key, displayName, qty, unit, prep, false);
  }

  private RecipeMethodStep step(int n, String instruction, Integer durationMinutes) {
    return RecipeMethodStep.builder()
        .id(UUID.randomUUID())
        .stepNumber(n)
        .instruction(instruction)
        .durationMinutes(durationMinutes)
        .build();
  }

  private CreateMethodStepRequest stepRequest(int n, String instruction, Integer durationMinutes) {
    return new CreateMethodStepRequest(n, instruction, durationMinutes);
  }

  private RecipeMetadata metadata(
      int servings,
      int prep,
      int cook,
      int total,
      Integer fridgeDays,
      Integer freezerWeeks,
      boolean packable,
      String cuisine,
      List<String> equipment,
      List<String> mealTypes) {
    return RecipeMetadata.builder()
        .id(UUID.randomUUID())
        .servings(servings)
        .prepTimeMins(prep)
        .cookTimeMins(cook)
        .totalTimeMins(total)
        .fridgeDays(fridgeDays)
        .freezerWeeks(freezerWeeks)
        .packable(packable)
        .cuisine(cuisine)
        .equipmentRequired(new ArrayList<>(equipment))
        .mealTypes(new ArrayList<>(mealTypes))
        .build();
  }

  private CreateRecipeMetadataRequest metadataRequest(
      int servings,
      int prep,
      int cook,
      int total,
      Integer fridgeDays,
      Integer freezerWeeks,
      boolean packable,
      String cuisine,
      List<String> equipment,
      List<String> mealTypes) {
    return new CreateRecipeMetadataRequest(
        servings,
        prep,
        cook,
        total,
        equipment,
        fridgeDays,
        freezerWeeks,
        packable,
        cuisine,
        mealTypes);
  }

  private RecipeMetadata defaultMeta() {
    return metadata(2, 5, 10, 15, 3, 2, false, "Italian", List.of("pan"), List.of("DINNER"));
  }

  private CreateRecipeMetadataRequest defaultMetaReq() {
    return metadataRequest(2, 5, 10, 15, 3, 2, false, "Italian", List.of("pan"), List.of("DINNER"));
  }

  private RecipeTags tags(
      String protein, String cookingMethod, Complexity c, List<String> flav, List<String> diet) {
    return RecipeTags.builder()
        .id(UUID.randomUUID())
        .protein(protein)
        .cookingMethod(cookingMethod)
        .complexity(c)
        .flavourProfile(new ArrayList<>(flav))
        .dietaryFlags(new ArrayList<>(diet))
        .build();
  }

  private CreateRecipeTagsRequest tagsReq(
      String protein, String cookingMethod, Complexity c, List<String> flav, List<String> diet) {
    return new CreateRecipeTagsRequest(protein, cookingMethod, c, flav, diet);
  }

  private RecipeVersion parentVersion() {
    return versionWith(
        List.of(ingredient("a.b", null, "A", new BigDecimal("1.000"), "g", 0)),
        List.of(step(1, "do thing", 5)),
        defaultMeta(),
        tags("beef", "stovetop", Complexity.MODERATE, List.of("savoury"), List.of("VEGAN")));
  }

  private NewVersionInput input(CreateRecipeMetadataRequest md, CreateRecipeTagsRequest tg) {
    return new NewVersionInput(
        List.of(ingredientRequest("a.b", null, "A", new BigDecimal("1.000"), "g", 0)),
        List.of(stepRequest(1, "do thing", 5)),
        md,
        tg);
  }

  private CreateRecipeTagsRequest defaultTagsReq() {
    return tagsReq("beef", "stovetop", Complexity.MODERATE, List.of("savoury"), List.of("VEGAN"));
  }

  private JsonNode onlyMetadataChange(CreateRecipeMetadataRequest md) {
    return differ.diff(parentVersion(), input(md, defaultTagsReq())).get("metadataChanges");
  }

  private JsonNode onlyTagChange(CreateRecipeTagsRequest tg) {
    return differ.diff(parentVersion(), input(defaultMetaReq(), tg)).get("tagChanges");
  }

  // ---------------- diffMetadata per-field void-call kills ----------------

  @Test
  void metadata_prepTimeChange_emitsPrepTimeMinsField() {
    JsonNode changes =
        onlyMetadataChange(
            metadataRequest(
                2, 99, 10, 15, 3, 2, false, "Italian", List.of("pan"), List.of("DINNER")));
    assertThat(changes.size()).isEqualTo(1);
    assertThat(changes.get(0).get("field").asText()).isEqualTo("prepTimeMins");
    assertThat(changes.get(0).get("from").asInt()).isEqualTo(5);
    assertThat(changes.get(0).get("to").asInt()).isEqualTo(99);
  }

  @Test
  void metadata_cookTimeChange_emitsCookTimeMinsField() {
    JsonNode changes =
        onlyMetadataChange(
            metadataRequest(
                2, 5, 77, 15, 3, 2, false, "Italian", List.of("pan"), List.of("DINNER")));
    assertThat(changes.size()).isEqualTo(1);
    assertThat(changes.get(0).get("field").asText()).isEqualTo("cookTimeMins");
  }

  @Test
  void metadata_totalTimeChange_emitsTotalTimeMinsField() {
    JsonNode changes =
        onlyMetadataChange(
            metadataRequest(
                2, 5, 10, 88, 3, 2, false, "Italian", List.of("pan"), List.of("DINNER")));
    assertThat(changes.size()).isEqualTo(1);
    assertThat(changes.get(0).get("field").asText()).isEqualTo("totalTimeMins");
  }

  @Test
  void metadata_fridgeDaysChange_emitsFridgeDaysField() {
    JsonNode changes =
        onlyMetadataChange(
            metadataRequest(
                2, 5, 10, 15, 9, 2, false, "Italian", List.of("pan"), List.of("DINNER")));
    assertThat(changes.size()).isEqualTo(1);
    assertThat(changes.get(0).get("field").asText()).isEqualTo("fridgeDays");
    assertThat(changes.get(0).get("from").asInt()).isEqualTo(3);
    assertThat(changes.get(0).get("to").asInt()).isEqualTo(9);
  }

  @Test
  void metadata_freezerWeeksChange_emitsFreezerWeeksField() {
    JsonNode changes =
        onlyMetadataChange(
            metadataRequest(
                2, 5, 10, 15, 3, 8, false, "Italian", List.of("pan"), List.of("DINNER")));
    assertThat(changes.size()).isEqualTo(1);
    assertThat(changes.get(0).get("field").asText()).isEqualTo("freezerWeeks");
  }

  @Test
  void metadata_packableChange_emitsPackableField() {
    JsonNode changes =
        onlyMetadataChange(
            metadataRequest(
                2, 5, 10, 15, 3, 2, true, "Italian", List.of("pan"), List.of("DINNER")));
    assertThat(changes.size()).isEqualTo(1);
    assertThat(changes.get(0).get("field").asText()).isEqualTo("packable");
    assertThat(changes.get(0).get("from").asBoolean()).isFalse();
    assertThat(changes.get(0).get("to").asBoolean()).isTrue();
  }

  @Test
  void metadata_cuisineChange_emitsCuisineField() {
    JsonNode changes =
        onlyMetadataChange(
            metadataRequest(2, 5, 10, 15, 3, 2, false, "Thai", List.of("pan"), List.of("DINNER")));
    assertThat(changes.size()).isEqualTo(1);
    assertThat(changes.get(0).get("field").asText()).isEqualTo("cuisine");
    assertThat(changes.get(0).get("from").asText()).isEqualTo("Italian");
    assertThat(changes.get(0).get("to").asText()).isEqualTo("Thai");
  }

  @Test
  void metadata_equipmentListChange_emitsEquipmentRequiredField() {
    JsonNode changes =
        onlyMetadataChange(
            metadataRequest(
                2, 5, 10, 15, 3, 2, false, "Italian", List.of("pan", "whisk"), List.of("DINNER")));
    assertThat(changes.size()).isEqualTo(1);
    assertThat(changes.get(0).get("field").asText()).isEqualTo("equipmentRequired");
  }

  @Test
  void metadata_mealTypesListChange_emitsMealTypesField() {
    JsonNode changes =
        onlyMetadataChange(
            metadataRequest(
                2, 5, 10, 15, 3, 2, false, "Italian", List.of("pan"), List.of("DINNER", "LUNCH")));
    assertThat(changes.size()).isEqualTo(1);
    assertThat(changes.get(0).get("field").asText()).isEqualTo("mealTypes");
  }

  @Test
  void metadata_bothNull_producesEmptyMetadataChanges_andDoesNotReturnNull() {
    // parent metadata null + requested metadata null → early-return empty array (kills the L253
    // negate + the L254 null-return mutant).
    RecipeVersion parent =
        versionWith(
            List.of(ingredient("a.b", null, "A", new BigDecimal("1.000"), "g", 0)),
            List.of(step(1, "do thing", 5)),
            null,
            tags("beef", "stovetop", Complexity.MODERATE, List.of("savoury"), List.of("VEGAN")));
    NewVersionInput requested =
        new NewVersionInput(
            List.of(ingredientRequest("a.b", null, "A", new BigDecimal("1.000"), "g", 0)),
            List.of(stepRequest(1, "do thing", 5)),
            null,
            defaultTagsReq());
    ObjectNode diff = differ.diff(parent, requested);
    JsonNode mc = diff.get("metadataChanges");
    assertThat(mc).isNotNull();
    assertThat(mc.isArray()).isTrue();
    assertThat(mc.size()).isZero();
  }

  @Test
  void metadata_parentNullRequestedPresent_emitsChangesUsingNullFromValues() {
    // parent metadata null but requested present → NOT the early return; every non-null requested
    // field differs from null `from` (kills the L253 negate which would early-return empty, and
    // the L279/L280/L284/L285/L294/L295 `parent != null ? ... : null` negate mutants).
    RecipeVersion parent =
        versionWith(
            List.of(ingredient("a.b", null, "A", new BigDecimal("1.000"), "g", 0)),
            List.of(step(1, "do thing", 5)),
            null,
            tags("beef", "stovetop", Complexity.MODERATE, List.of("savoury"), List.of("VEGAN")));
    NewVersionInput requested = input(defaultMetaReq(), defaultTagsReq());
    JsonNode mc = differ.diff(parent, requested).get("metadataChanges");
    assertThat(mc.size()).isGreaterThan(0);
    boolean cuisineFromNull = false;
    for (JsonNode e : mc) {
      if ("cuisine".equals(e.get("field").asText())) {
        assertThat(e.get("from").isNull()).isTrue();
        assertThat(e.get("to").asText()).isEqualTo("Italian");
        cuisineFromNull = true;
      }
    }
    assertThat(cuisineFromNull).isTrue();
  }

  // ---------------- diffTags per-field void-call kills ----------------

  @Test
  void tags_proteinChange_emitsProteinDimension() {
    JsonNode changes =
        onlyTagChange(
            tagsReq(
                "chicken", "stovetop", Complexity.MODERATE, List.of("savoury"), List.of("VEGAN")));
    assertThat(changes.size()).isEqualTo(1);
    assertThat(changes.get(0).get("dimension").asText()).isEqualTo("protein");
    assertThat(changes.get(0).get("from").asText()).isEqualTo("beef");
    assertThat(changes.get(0).get("to").asText()).isEqualTo("chicken");
  }

  @Test
  void tags_cookingMethodChange_emitsCookingMethodDimension() {
    JsonNode changes =
        onlyTagChange(
            tagsReq("beef", "oven", Complexity.MODERATE, List.of("savoury"), List.of("VEGAN")));
    assertThat(changes.size()).isEqualTo(1);
    assertThat(changes.get(0).get("dimension").asText()).isEqualTo("cookingMethod");
    assertThat(changes.get(0).get("from").asText()).isEqualTo("stovetop");
    assertThat(changes.get(0).get("to").asText()).isEqualTo("oven");
  }

  @Test
  void tags_complexityChange_emitsComplexityDimension() {
    JsonNode changes =
        onlyTagChange(
            tagsReq("beef", "stovetop", Complexity.INVOLVED, List.of("savoury"), List.of("VEGAN")));
    assertThat(changes.size()).isEqualTo(1);
    assertThat(changes.get(0).get("dimension").asText()).isEqualTo("complexity");
    assertThat(changes.get(0).get("from").asText()).isEqualTo("MODERATE");
    assertThat(changes.get(0).get("to").asText()).isEqualTo("INVOLVED");
  }

  @Test
  void tags_dietaryFlagsChange_emitsDietaryFlagsDimension() {
    JsonNode changes =
        onlyTagChange(
            tagsReq(
                "beef",
                "stovetop",
                Complexity.MODERATE,
                List.of("savoury"),
                List.of("VEGAN", "NUT_FREE")));
    assertThat(changes.size()).isEqualTo(1);
    assertThat(changes.get(0).get("dimension").asText()).isEqualTo("dietaryFlags");
  }

  // ---------------- equalBigDecimal / equalNullable / ingredientSnapshot ----------------

  @Test
  void ingredient_quantityNullToValue_emitsQuantityModified() {
    // parent quantity null, requested quantity 2.500 → equalBigDecimal(null, 2.5) must be false
    // (kills the L402 negate + L405 one-null-return mutants). Snapshot of the null-quantity parent
    // must serialise quantity as a JSON null (kills the ingredientSnapshot L164 negate).
    RecipeVersion parent =
        versionWith(
            List.of(ingredient("a.b", null, "A", null, "g", 0)),
            List.of(step(1, "do thing", 5)),
            defaultMeta(),
            tags("beef", "stovetop", Complexity.MODERATE, List.of("savoury"), List.of("VEGAN")));
    NewVersionInput requested =
        new NewVersionInput(
            List.of(ingredientRequest("a.b", null, "A", new BigDecimal("2.500"), "g", 0)),
            List.of(stepRequest(1, "do thing", 5)),
            defaultMetaReq(),
            defaultTagsReq());
    ObjectNode diff = differ.diff(parent, requested);
    JsonNode ic = diff.get("ingredientChanges");
    assertThat(ic.size()).isEqualTo(1);
    JsonNode entry = ic.get(0);
    assertThat(entry.get("action").asText()).isEqualTo("MODIFIED");
    assertThat(entry.get("fieldChanged").asText()).isEqualTo("quantity");
    assertThat(entry.get("from").get("quantity").isNull()).isTrue();
    assertThat(entry.get("to").get("quantity").decimalValue()).isEqualByComparingTo("2.500");
  }

  @Test
  void ingredient_bothQuantitiesNull_isNotAQuantityModification() {
    // equalBigDecimal(null, null) must return true so a null→null quantity emits NO entry. The
    // L402 `if (a == null && b == null)` negate mutant would fall through to the one-null branch
    // and report a spurious quantity change. Only displayName actually differs here.
    RecipeVersion parent =
        versionWith(
            List.of(ingredient("a.b", null, "Old", null, "g", 0)),
            List.of(step(1, "do thing", 5)),
            defaultMeta(),
            tags("beef", "stovetop", Complexity.MODERATE, List.of("savoury"), List.of("VEGAN")));
    NewVersionInput requested =
        new NewVersionInput(
            List.of(ingredientRequest("a.b", null, "New", null, "g", 0)),
            List.of(stepRequest(1, "do thing", 5)),
            defaultMetaReq(),
            defaultTagsReq());
    JsonNode ic = differ.diff(parent, requested).get("ingredientChanges");
    assertThat(ic.size()).isEqualTo(1);
    assertThat(ic.get(0).get("fieldChanged").asText()).isEqualTo("displayName");
  }

  @Test
  void ingredient_removedSnapshot_carriesNonNullEntityQuantityAsNumber() {
    // A REMOVED entry snapshots the entity side; a non-null entity quantity must serialise as a
    // number (covers the entity-side ingredientSnapshot L164 quantity branch).
    RecipeVersion parent =
        versionWith(
            List.of(
                ingredient("a.b", null, "A", new BigDecimal("1.000"), "g", 0),
                ingredient("gone.key", null, "Gone", new BigDecimal("3.250"), "ml", 1)),
            List.of(step(1, "do thing", 5)),
            defaultMeta(),
            tags("beef", "stovetop", Complexity.MODERATE, List.of("savoury"), List.of("VEGAN")));
    NewVersionInput requested = input(defaultMetaReq(), defaultTagsReq());
    JsonNode ic = differ.diff(parent, requested).get("ingredientChanges");
    assertThat(ic.size()).isEqualTo(1);
    JsonNode entry = ic.get(0);
    assertThat(entry.get("action").asText()).isEqualTo("REMOVED");
    assertThat(entry.get("from").get("quantity").isNull()).isFalse();
    assertThat(entry.get("from").get("quantity").decimalValue()).isEqualByComparingTo("3.250");
  }

  @Test
  void ingredient_sameQuantityDifferentScale_isNotAModification() {
    // 1.0 vs 1.000 — compareTo == 0 so equalBigDecimal must return true (kills the L408
    // compareTo-based mutants by requiring NO quantity entry while a real change is present).
    RecipeVersion parent =
        versionWith(
            List.of(ingredient("a.b", null, "Old name", new BigDecimal("1.0"), "g", 0)),
            List.of(step(1, "do thing", 5)),
            defaultMeta(),
            tags("beef", "stovetop", Complexity.MODERATE, List.of("savoury"), List.of("VEGAN")));
    NewVersionInput requested =
        new NewVersionInput(
            List.of(ingredientRequest("a.b", null, "New name", new BigDecimal("1.000"), "g", 0)),
            List.of(stepRequest(1, "do thing", 5)),
            defaultMetaReq(),
            defaultTagsReq());
    JsonNode ic = differ.diff(parent, requested).get("ingredientChanges");
    // Only displayName changed; quantity (1.0 vs 1.000) must NOT show up.
    assertThat(ic.size()).isEqualTo(1);
    assertThat(ic.get(0).get("fieldChanged").asText()).isEqualTo("displayName");
  }

  @Test
  void ingredient_displayNameChange_isDetected_killingEqualNullableAlwaysTrue() {
    // equalNullable replaced-with-true would treat differing display names as equal → no entry.
    RecipeVersion parent =
        versionWith(
            List.of(ingredient("a.b", null, "Original", new BigDecimal("1.000"), "g", 0)),
            List.of(step(1, "do thing", 5)),
            defaultMeta(),
            tags("beef", "stovetop", Complexity.MODERATE, List.of("savoury"), List.of("VEGAN")));
    NewVersionInput requested =
        new NewVersionInput(
            List.of(ingredientRequest("a.b", null, "Renamed", new BigDecimal("1.000"), "g", 0)),
            List.of(stepRequest(1, "do thing", 5)),
            defaultMetaReq(),
            defaultTagsReq());
    JsonNode ic = differ.diff(parent, requested).get("ingredientChanges");
    assertThat(ic.size()).isEqualTo(1);
    assertThat(ic.get(0).get("fieldChanged").asText()).isEqualTo("displayName");
    assertThat(ic.get(0).get("from").get("displayName").asText()).isEqualTo("Original");
    assertThat(ic.get(0).get("to").get("displayName").asText()).isEqualTo("Renamed");
  }

  @Test
  void ingredientSnapshot_nonNullQuantity_isSerialisedAsNumber() {
    // requested side has a non-null quantity → snapshot must put the numeric value, not a JSON
    // null (kills the ingredientSnapshot L180 negate mutant).
    RecipeVersion parent =
        versionWith(
            List.of(ingredient("a.b", null, "A", new BigDecimal("1.000"), "g", 0)),
            List.of(step(1, "do thing", 5)),
            defaultMeta(),
            tags("beef", "stovetop", Complexity.MODERATE, List.of("savoury"), List.of("VEGAN")));
    NewVersionInput requested =
        new NewVersionInput(
            List.of(ingredientRequest("a.b", null, "A", new BigDecimal("4.250"), "g", 0)),
            List.of(stepRequest(1, "do thing", 5)),
            defaultMetaReq(),
            defaultTagsReq());
    JsonNode entry = differ.diff(parent, requested).get("ingredientChanges").get(0);
    assertThat(entry.get("to").get("quantity").isNull()).isFalse();
    assertThat(entry.get("to").get("quantity").decimalValue()).isEqualByComparingTo("4.250");
  }

  // ---------------- isEmpty / sectionEmpty ----------------

  @Test
  void isEmpty_nullDiff_isTrue() {
    assertThat(differ.isEmpty(null)).isTrue();
  }

  @Test
  void isEmpty_allSectionsEmpty_isTrue_butAnyNonEmptyIsFalse() {
    RecipeVersion parent = parentVersion();
    NewVersionInput identical = input(defaultMetaReq(), defaultTagsReq());
    ObjectNode emptyDiff = differ.diff(parent, identical);
    assertThat(differ.isEmpty(emptyDiff)).isTrue();

    // One real change → isEmpty must flip to false (kills the L63 negate + L66 boolean-true
    // mutants; a true-returning isEmpty would mask non-empty diffs).
    NewVersionInput changed =
        input(
            metadataRequest(
                9, 5, 10, 15, 3, 2, false, "Italian", List.of("pan"), List.of("DINNER")),
            defaultTagsReq());
    ObjectNode nonEmptyDiff = differ.diff(parent, changed);
    assertThat(differ.isEmpty(nonEmptyDiff)).isFalse();
  }

  @Test
  void isEmpty_missingSection_isTreatedAsEmpty() {
    // A diff object with no recognised sections must be considered empty (exercises sectionEmpty's
    // isMissingNode branch — kills the L74 negate/true mutants).
    ObjectNode bogus = objectMapper.createObjectNode();
    bogus.put("unrelated", "value");
    assertThat(differ.isEmpty(bogus)).isTrue();
  }

  @Test
  void isEmpty_sectionPresentButPopulated_isFalse() {
    ObjectNode diff = objectMapper.createObjectNode();
    diff.set("ingredientChanges", objectMapper.createArrayNode().add("x"));
    diff.set("methodChanges", objectMapper.createArrayNode());
    diff.set("metadataChanges", objectMapper.createArrayNode());
    diff.set("tagChanges", objectMapper.createArrayNode());
    assertThat(differ.isEmpty(diff)).isFalse();
  }
}
