package com.example.mealprep.discovery.graphimport;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.discovery.domain.service.internal.graphimport.GraphBatchValidator;
import com.example.mealprep.recipe.spi.ImportedRecipeData;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * G06 fail-closed validation matrix (unit; carries the Pitest load for the ticket). Boundary
 * conditions per the acceptance criteria: mealTypes casing + vocabulary, equipment membership incl.
 * case, unit != "g", quantity <= 0, servings != 1.
 */
class GraphBatchValidatorTest {

  private static final Set<String> CATALOGUE =
      Set.of(
          "oven",
          "hob",
          "microwave",
          "air_fryer",
          "slow_cooker",
          "blender",
          "food_processor",
          "grill",
          "bbq",
          "rice_cooker",
          "stand_mixer",
          "pressure_cooker",
          "kettle",
          "toaster",
          "dishwasher");

  // ===== mealTypes (hard requirement #1 — the engine will NOT catch it) =====

  @Test
  void mealTypesEmptyOrNullRejected() {
    assertThat(GraphBatchValidator.mealTypesViolations(null)).containsExactly("mealTypes empty");
    assertThat(GraphBatchValidator.mealTypesViolations(List.of()))
        .containsExactly("mealTypes empty");
  }

  @Test
  void mealTypesVocabularyIsTheEngineSlotKindSet() {
    assertThat(
            GraphBatchValidator.mealTypesViolations(
                List.of("breakfast", "lunch", "dinner", "snack")))
        .isEmpty();
    for (String bad : List.of("dessert", "side", "brunch", "beverage", "appetizer", "condiment")) {
      assertThat(GraphBatchValidator.mealTypesViolations(List.of(bad)))
          .containsExactly("unknown mealType: " + bad);
    }
  }

  @Test
  void mealTypesCasingRejected() {
    assertThat(GraphBatchValidator.mealTypesViolations(List.of("Lunch")))
        .containsExactly("mealType not lowercase: Lunch");
    assertThat(GraphBatchValidator.mealTypesViolations(List.of("DINNER")))
        .containsExactly("mealType not lowercase: DINNER");
    // one bad value does not mask a good one
    assertThat(GraphBatchValidator.mealTypesViolations(List.of("lunch", "Dinner"))).hasSize(1);
  }

  // ===== equipment (backstop of the spike-side frozen map) =====

  @Test
  void equipmentMembership() {
    assertThat(GraphBatchValidator.equipmentViolations(null, CATALOGUE)).isEmpty();
    assertThat(GraphBatchValidator.equipmentViolations(List.of(), CATALOGUE)).isEmpty();
    assertThat(GraphBatchValidator.equipmentViolations(List.of("oven", "hob"), CATALOGUE))
        .isEmpty();
    assertThat(GraphBatchValidator.equipmentViolations(List.of("wok"), CATALOGUE))
        .containsExactly("unknown equipment: wok");
  }

  @Test
  void equipmentIsCaseSensitiveAgainstTheCatalogue() {
    // "Oven" would silently make the dish unfillable — exactly the trap; fail-closed skip.
    assertThat(GraphBatchValidator.equipmentViolations(List.of("Oven"), CATALOGUE))
        .containsExactly("unknown equipment: Oven");
    assertThat(GraphBatchValidator.equipmentViolations(List.of("AIR_FRYER"), CATALOGUE))
        .containsExactly("unknown equipment: AIR_FRYER");
  }

  // ===== ingredients (consumed-basis grams contract) =====

  @Test
  void ingredientUnitMustBeGrams() {
    assertThat(GraphBatchValidator.ingredientViolations(List.of(ingredient("rice", "10", "g"))))
        .isEmpty();
    assertThat(GraphBatchValidator.ingredientViolations(List.of(ingredient("rice", "10", "ml"))))
        .containsExactly("ingredient line 1: unit must be \"g\", got ml");
    assertThat(GraphBatchValidator.ingredientViolations(List.of(ingredient("rice", "10", "G"))))
        .containsExactly("ingredient line 1: unit must be \"g\", got G");
  }

  @Test
  void ingredientQuantityMustBePositive() {
    assertThat(GraphBatchValidator.ingredientViolations(List.of(ingredient("rice", "0", "g"))))
        .containsExactly("ingredient line 1: quantity must be > 0, got 0");
    assertThat(GraphBatchValidator.ingredientViolations(List.of(ingredient("rice", "-1", "g"))))
        .containsExactly("ingredient line 1: quantity must be > 0, got -1");
    assertThat(
            GraphBatchValidator.ingredientViolations(
                List.of(
                    new ImportedRecipeData.ImportedIngredient(
                        1, "rice", "rice", null, "g", null, false))))
        .containsExactly("ingredient line 1: quantity must be > 0, got null");
    // 0.5 g is legal (positive)
    assertThat(GraphBatchValidator.ingredientViolations(List.of(ingredient("rice", "0.5", "g"))))
        .isEmpty();
  }

  @Test
  void ingredientKeyMustBeNormalForm() {
    assertThat(
            GraphBatchValidator.ingredientViolations(List.of(ingredientWithKey("Chicken Breast"))))
        .containsExactly("ingredient line 1: ingredientMappingKey not normal-form: Chicken Breast");
    assertThat(GraphBatchValidator.ingredientViolations(List.of(ingredientWithKey(" rice"))))
        .hasSize(1);
    assertThat(
            GraphBatchValidator.ingredientViolations(List.of(ingredientWithKey("double  space"))))
        .hasSize(1);
    assertThat(GraphBatchValidator.ingredientViolations(List.of(ingredientWithKey(null))))
        .hasSize(1);
  }

  @Test
  void emptyIngredientsRejected() {
    assertThat(GraphBatchValidator.ingredientViolations(null)).containsExactly("ingredients empty");
    assertThat(GraphBatchValidator.ingredientViolations(List.of()))
        .containsExactly("ingredients empty");
  }

  // ===== servings (D2) =====

  @Test
  void servingsMustBeExactlyOne() {
    assertThat(GraphBatchValidator.servingsViolation(1)).isEmpty();
    assertThat(GraphBatchValidator.servingsViolation(2)).contains("servings must be 1 (D2), got 2");
    assertThat(GraphBatchValidator.servingsViolation(0)).isPresent();
    assertThat(GraphBatchValidator.servingsViolation(null))
        .contains("servings must be 1 (D2), got null");
  }

  // ===== whole-dish aggregation =====

  @Test
  void dishViolationsAggregatesAllCategories() {
    ImportedRecipeData bad =
        dish(List.of("Lunch"), List.of("wok"), List.of(ingredient("rice", "0", "ml")), 3);
    List<String> violations = GraphBatchValidator.dishViolations(bad, CATALOGUE);
    assertThat(violations)
        .containsExactly(
            "mealType not lowercase: Lunch",
            "unknown equipment: wok",
            "ingredient line 1: unit must be \"g\", got ml",
            "ingredient line 1: quantity must be > 0, got 0",
            "servings must be 1 (D2), got 3");
  }

  @Test
  void cleanDishHasNoViolations() {
    ImportedRecipeData clean =
        dish(List.of("dinner"), List.of("hob"), List.of(ingredient("rice", "180", "g")), 1);
    assertThat(GraphBatchValidator.dishViolations(clean, CATALOGUE)).isEmpty();
  }

  // ===== helpers =====

  private static ImportedRecipeData.ImportedIngredient ingredient(
      String key, String quantity, String unit) {
    return new ImportedRecipeData.ImportedIngredient(
        1, key, key, new BigDecimal(quantity), unit, null, false);
  }

  private static ImportedRecipeData.ImportedIngredient ingredientWithKey(String key) {
    return new ImportedRecipeData.ImportedIngredient(
        1, "rice", key, BigDecimal.TEN, "g", null, false);
  }

  private static ImportedRecipeData dish(
      List<String> mealTypes,
      List<String> equipment,
      List<ImportedRecipeData.ImportedIngredient> ingredients,
      Integer servings) {
    return new ImportedRecipeData(
        "graph:camp-test",
        null,
        "f".repeat(64),
        "Test dish",
        "A test dish.",
        ingredients,
        List.of(new ImportedRecipeData.ImportedMethodStep(1, "Cook.", 10)),
        new ImportedRecipeData.ImportedRecipeMetadata(
            servings, 10, 20, 30, equipment, null, null, null, null, mealTypes),
        new ImportedRecipeData.ImportedRecipeTags("rice", "simmer", null, null, List.of()),
        "graph@1234abc+c@0123456789abcdef",
        null,
        UUID.randomUUID(),
        UUID.randomUUID());
  }
}
