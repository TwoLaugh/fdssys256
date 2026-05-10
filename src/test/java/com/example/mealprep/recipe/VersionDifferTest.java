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

/** Unit test for {@link VersionDiffer}. Pure-logic; no Spring context. */
class VersionDifferTest {

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final VersionDiffer differ = new VersionDiffer(objectMapper);

  // ---------------- Builders ----------------

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

  private RecipeMetadata metadata(int servings, int prep, int cook, int total) {
    return RecipeMetadata.builder()
        .id(UUID.randomUUID())
        .servings(servings)
        .prepTimeMins(prep)
        .cookTimeMins(cook)
        .totalTimeMins(total)
        .equipmentRequired(new ArrayList<>(List.of("pan")))
        .packable(false)
        .mealTypes(new ArrayList<>(List.of("DINNER")))
        .build();
  }

  private CreateRecipeMetadataRequest metadataRequest(int servings, int prep, int cook, int total) {
    return new CreateRecipeMetadataRequest(
        servings, prep, cook, total, List.of("pan"), null, null, false, null, List.of("DINNER"));
  }

  private RecipeTags tags(String protein, List<String> flavours) {
    return RecipeTags.builder()
        .id(UUID.randomUUID())
        .protein(protein)
        .complexity(Complexity.MODERATE)
        .flavourProfile(new ArrayList<>(flavours))
        .dietaryFlags(new ArrayList<>())
        .build();
  }

  private CreateRecipeTagsRequest tagsRequest(String protein, List<String> flavours) {
    return new CreateRecipeTagsRequest(protein, null, Complexity.MODERATE, flavours, List.of());
  }

  // ---------------- Tests ----------------

  @Test
  void identical_bodies_produce_empty_diff() {
    RecipeVersion parent =
        versionWith(
            List.of(ingredient("a.b", null, "A", new BigDecimal("1.000"), "g", 0)),
            List.of(step(1, "do thing", 5)),
            metadata(2, 5, 10, 15),
            tags("beef", List.of("savoury")));
    NewVersionInput requested =
        new NewVersionInput(
            List.of(ingredientRequest("a.b", null, "A", new BigDecimal("1.000"), "g", 0)),
            List.of(stepRequest(1, "do thing", 5)),
            metadataRequest(2, 5, 10, 15),
            tagsRequest("beef", List.of("savoury")));
    ObjectNode diff = differ.diff(parent, requested);
    assertThat(differ.isEmpty(diff)).isTrue();
  }

  @Test
  void ingredient_added_emits_ADDED() {
    RecipeVersion parent =
        versionWith(
            List.of(ingredient("a.b", null, "A", new BigDecimal("1.000"), "g", 0)),
            List.of(step(1, "do thing", 5)),
            metadata(2, 5, 10, 15),
            tags("beef", List.of("savoury")));
    NewVersionInput requested =
        new NewVersionInput(
            List.of(
                ingredientRequest("a.b", null, "A", new BigDecimal("1.000"), "g", 0),
                ingredientRequest("c.d", null, "C", new BigDecimal("2.000"), "ml", 1)),
            List.of(stepRequest(1, "do thing", 5)),
            metadataRequest(2, 5, 10, 15),
            tagsRequest("beef", List.of("savoury")));
    ObjectNode diff = differ.diff(parent, requested);
    assertThat(diff.get("ingredientChanges").size()).isEqualTo(1);
    JsonNode entry = diff.get("ingredientChanges").get(0);
    assertThat(entry.get("action").asText()).isEqualTo("ADDED");
    assertThat(entry.get("from").isNull()).isTrue();
    assertThat(entry.get("to").get("ingredientMappingKey").asText()).isEqualTo("c.d");
  }

  @Test
  void ingredient_removed_emits_REMOVED() {
    RecipeVersion parent =
        versionWith(
            List.of(
                ingredient("a.b", null, "A", new BigDecimal("1.000"), "g", 0),
                ingredient("c.d", null, "C", new BigDecimal("2.000"), "ml", 1)),
            List.of(step(1, "do thing", 5)),
            metadata(2, 5, 10, 15),
            tags("beef", List.of("savoury")));
    NewVersionInput requested =
        new NewVersionInput(
            List.of(ingredientRequest("a.b", null, "A", new BigDecimal("1.000"), "g", 0)),
            List.of(stepRequest(1, "do thing", 5)),
            metadataRequest(2, 5, 10, 15),
            tagsRequest("beef", List.of("savoury")));
    ObjectNode diff = differ.diff(parent, requested);
    assertThat(diff.get("ingredientChanges").size()).isEqualTo(1);
    JsonNode entry = diff.get("ingredientChanges").get(0);
    assertThat(entry.get("action").asText()).isEqualTo("REMOVED");
    assertThat(entry.get("to").isNull()).isTrue();
    assertThat(entry.get("from").get("ingredientMappingKey").asText()).isEqualTo("c.d");
  }

  @Test
  void ingredient_quantity_change_emits_one_MODIFIED_with_fieldChanged_quantity() {
    RecipeVersion parent =
        versionWith(
            List.of(ingredient("a.b", null, "A", new BigDecimal("1.000"), "g", 0)),
            List.of(step(1, "do thing", 5)),
            metadata(2, 5, 10, 15),
            tags("beef", List.of("savoury")));
    NewVersionInput requested =
        new NewVersionInput(
            List.of(ingredientRequest("a.b", null, "A", new BigDecimal("2.500"), "g", 0)),
            List.of(stepRequest(1, "do thing", 5)),
            metadataRequest(2, 5, 10, 15),
            tagsRequest("beef", List.of("savoury")));
    ObjectNode diff = differ.diff(parent, requested);
    assertThat(diff.get("ingredientChanges").size()).isEqualTo(1);
    JsonNode entry = diff.get("ingredientChanges").get(0);
    assertThat(entry.get("action").asText()).isEqualTo("MODIFIED");
    assertThat(entry.get("fieldChanged").asText()).isEqualTo("quantity");
  }

  @Test
  void method_renumbering_handled_via_step_number_match() {
    // Parent: 1,2,3,4 — request: 1,2,3 (old step 4 deleted, 3 renumbered conceptually).
    // Strategy: match by stepNumber; old step 4 → REMOVED, old step 3 unchanged if
    // instruction/duration unchanged.
    RecipeVersion parent =
        versionWith(
            List.of(ingredient("a.b", null, "A", new BigDecimal("1.000"), "g", 0)),
            List.of(
                step(1, "first", 1),
                step(2, "second", 2),
                step(3, "third", 3),
                step(4, "fourth", 4)),
            metadata(2, 5, 10, 15),
            tags("beef", List.of("savoury")));
    NewVersionInput requested =
        new NewVersionInput(
            List.of(ingredientRequest("a.b", null, "A", new BigDecimal("1.000"), "g", 0)),
            List.of(
                stepRequest(1, "first", 1),
                stepRequest(2, "second", 2),
                stepRequest(3, "third", 3)),
            metadataRequest(2, 5, 10, 15),
            tagsRequest("beef", List.of("savoury")));
    ObjectNode diff = differ.diff(parent, requested);
    assertThat(diff.get("methodChanges").size()).isEqualTo(1);
    JsonNode entry = diff.get("methodChanges").get(0);
    assertThat(entry.get("action").asText()).isEqualTo("REMOVED");
    assertThat(entry.get("step").asInt()).isEqualTo(4);
  }

  @Test
  void metadata_field_changes_emit_one_MODIFIED_per_field() {
    RecipeVersion parent =
        versionWith(
            List.of(ingredient("a.b", null, "A", new BigDecimal("1.000"), "g", 0)),
            List.of(step(1, "do thing", 5)),
            metadata(2, 5, 10, 15),
            tags("beef", List.of("savoury")));
    NewVersionInput requested =
        new NewVersionInput(
            List.of(ingredientRequest("a.b", null, "A", new BigDecimal("1.000"), "g", 0)),
            List.of(stepRequest(1, "do thing", 5)),
            metadataRequest(4, 5, 10, 15), // servings 2 → 4
            tagsRequest("beef", List.of("savoury")));
    ObjectNode diff = differ.diff(parent, requested);
    assertThat(diff.get("metadataChanges").size()).isEqualTo(1);
    JsonNode entry = diff.get("metadataChanges").get(0);
    assertThat(entry.get("field").asText()).isEqualTo("servings");
    assertThat(entry.get("from").asInt()).isEqualTo(2);
    assertThat(entry.get("to").asInt()).isEqualTo(4);
  }

  @Test
  void tag_array_change_emits_MODIFIED_with_dimension() {
    RecipeVersion parent =
        versionWith(
            List.of(ingredient("a.b", null, "A", new BigDecimal("1.000"), "g", 0)),
            List.of(step(1, "do thing", 5)),
            metadata(2, 5, 10, 15),
            tags("beef", List.of("savoury")));
    NewVersionInput requested =
        new NewVersionInput(
            List.of(ingredientRequest("a.b", null, "A", new BigDecimal("1.000"), "g", 0)),
            List.of(stepRequest(1, "do thing", 5)),
            metadataRequest(2, 5, 10, 15),
            tagsRequest("beef", List.of("savoury", "umami")));
    ObjectNode diff = differ.diff(parent, requested);
    assertThat(diff.get("tagChanges").size()).isEqualTo(1);
    JsonNode entry = diff.get("tagChanges").get(0);
    assertThat(entry.get("dimension").asText()).isEqualTo("flavourProfile");
  }
}
