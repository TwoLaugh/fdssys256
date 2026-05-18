package com.example.mealprep.recipe;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.recipe.api.dto.CreateIngredientRequest;
import com.example.mealprep.recipe.api.dto.CreateMethodStepRequest;
import com.example.mealprep.recipe.api.dto.CreateRecipeMetadataRequest;
import com.example.mealprep.recipe.api.dto.CreateRecipeRequest;
import com.example.mealprep.recipe.api.mapper.ParsedRecipeToCreateRequestMapper;
import com.example.mealprep.recipe.domain.service.internal.HtmlImportParser.ParsedRecipe;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pure-unit coverage of {@link ParsedRecipeToCreateRequestMapper}: clamp boundaries, null/blank
 * skipping, mapping-key slug derivation, the {@code totalMinutes} ±1 reconciliation, and the
 * servings default. Real instance, no mocking.
 */
class ParsedRecipeToCreateRequestMapperTest {

  private final ParsedRecipeToCreateRequestMapper mapper = new ParsedRecipeToCreateRequestMapper();

  @Test
  void map_happyPath_copiesNameDescriptionAndOrders() {
    ParsedRecipe parsed =
        parsed(
            "Lemon Chicken",
            "A bright weeknight dish",
            List.of("2 lemons", "1 chicken breast"),
            List.of("Zest the lemons", "Sear the chicken"),
            10,
            20,
            30,
            4,
            "Mediterranean");

    CreateRecipeRequest req = mapper.map(parsed);

    assertThat(req.name()).isEqualTo("Lemon Chicken");
    assertThat(req.description()).isEqualTo("A bright weeknight dish");
    assertThat(req.ingredients()).hasSize(2);
    assertThat(req.ingredients().get(0).lineOrder()).isZero();
    assertThat(req.ingredients().get(1).lineOrder()).isEqualTo(1);
    assertThat(req.method()).hasSize(2);
    assertThat(req.method().get(0).stepNumber()).isEqualTo(1);
    assertThat(req.method().get(1).stepNumber()).isEqualTo(2);
    assertThat(req.tags()).isNull();
  }

  @Test
  void map_deriveMappingKey_slugifiesAndPrefixes() {
    ParsedRecipe parsed =
        parsed("n", "d", List.of("  Extra-Virgin Olive Oil!! "), List.of("step"), 0, 0, 0, 2, "x");

    CreateIngredientRequest ing = mapper.map(parsed).ingredients().get(0);

    assertThat(ing.displayName()).isEqualTo("Extra-Virgin Olive Oil!!");
    assertThat(ing.ingredientMappingKey()).isEqualTo("imported.extra.virgin.olive.oil");
    assertThat(ing.optional()).isFalse();
  }

  @Test
  void map_mappingKey_nonAlnumOnly_fallsBackToIngredientToken() {
    ParsedRecipe parsed = parsed("n", "d", List.of("!!!"), List.of("s"), 0, 0, 0, 1, "x");

    // displayName "!!!" -> slug strips to "" -> "ingredient" -> "imported.ingredient".
    assertThat(mapper.map(parsed).ingredients().get(0).ingredientMappingKey())
        .isEqualTo("imported.ingredient");
  }

  @Test
  void map_nullAndBlankIngredientLinesAreSkipped() {
    List<String> lines = new ArrayList<>();
    lines.add("flour");
    lines.add(null);
    lines.add("   ");
    lines.add("sugar");
    ParsedRecipe parsed = parsed("n", "d", lines, List.of("s"), 0, 0, 0, 1, "x");

    List<CreateIngredientRequest> out = mapper.map(parsed).ingredients();
    assertThat(out).hasSize(2);
    assertThat(out.get(0).displayName()).isEqualTo("flour");
    assertThat(out.get(0).lineOrder()).isZero();
    assertThat(out.get(1).displayName()).isEqualTo("sugar");
    assertThat(out.get(1).lineOrder()).isEqualTo(1);
  }

  @Test
  void map_nullIngredientLines_yieldsEmptyList() {
    ParsedRecipe parsed = parsed("n", "d", null, List.of("s"), 0, 0, 0, 1, "x");
    assertThat(mapper.map(parsed).ingredients()).isEmpty();
  }

  @Test
  void map_nullAndBlankMethodStepsSkipped_andRenumberedFromOne() {
    List<String> steps = new ArrayList<>();
    steps.add(null);
    steps.add("  Mix  ");
    steps.add("   ");
    steps.add("Bake");
    ParsedRecipe parsed = parsed("n", "d", List.of("flour"), steps, 0, 0, 0, 1, "x");

    List<CreateMethodStepRequest> out = mapper.map(parsed).method();
    assertThat(out).hasSize(2);
    assertThat(out.get(0).stepNumber()).isEqualTo(1);
    assertThat(out.get(0).instruction()).isEqualTo("Mix");
    assertThat(out.get(1).stepNumber()).isEqualTo(2);
    assertThat(out.get(1).instruction()).isEqualTo("Bake");
  }

  @Test
  void map_nullMethodSteps_yieldsEmptyList() {
    ParsedRecipe parsed = parsed("n", "d", List.of("flour"), null, 0, 0, 0, 1, "x");
    assertThat(mapper.map(parsed).method()).isEmpty();
  }

  @Test
  void map_clampsNameAndDescriptionToMaxLength() {
    String longName = "x".repeat(200);
    String longDesc = "y".repeat(3000);
    ParsedRecipe parsed = parsed(longName, longDesc, List.of("a"), List.of("s"), 0, 0, 0, 1, "c");

    CreateRecipeRequest req = mapper.map(parsed);
    assertThat(req.name()).hasSize(160);
    assertThat(req.description()).hasSize(2000);
  }

  @Test
  void map_nullNameAndDescription_passThroughAsNull() {
    ParsedRecipe parsed = parsed(null, null, List.of("a"), List.of("s"), 0, 0, 0, 1, "c");
    CreateRecipeRequest req = mapper.map(parsed);
    assertThat(req.name()).isNull();
    assertThat(req.description()).isNull();
  }

  @Test
  void map_exactlyMaxLength_isNotClamped() {
    String name160 = "n".repeat(160);
    ParsedRecipe parsed = parsed(name160, "d", List.of("a"), List.of("s"), 0, 0, 0, 1, "c");
    assertThat(mapper.map(parsed).name()).isEqualTo(name160);
  }

  // ---------------- metadata: prep/cook/total reconciliation ----------------

  @Test
  void metadata_nullPrepCookTotal_allZero() {
    ParsedRecipe parsed = parsed("n", "d", List.of("a"), List.of("s"), null, null, null, null, "c");
    CreateRecipeMetadataRequest md = mapper.map(parsed).metadata();
    assertThat(md.prepTimeMins()).isZero();
    assertThat(md.cookTimeMins()).isZero();
    assertThat(md.totalTimeMins()).isZero();
  }

  @Test
  void metadata_explicitTotalWithinOne_keptVerbatim() {
    // prep+cook = 30, explicit total 31 (|31-30| = 1 <= 1) → keep 31.
    ParsedRecipe parsed = parsed("n", "d", List.of("a"), List.of("s"), 10, 20, 31, 4, "c");
    assertThat(mapper.map(parsed).metadata().totalTimeMins()).isEqualTo(31);
  }

  @Test
  void metadata_explicitTotalOffByTwo_replacedBySum() {
    // prep+cook = 30, explicit total 33 (|33-30| = 3 > 1) → replaced with 30.
    ParsedRecipe parsed = parsed("n", "d", List.of("a"), List.of("s"), 10, 20, 33, 4, "c");
    assertThat(mapper.map(parsed).metadata().totalTimeMins()).isEqualTo(30);
  }

  @Test
  void metadata_nullTotal_computedAsSum() {
    ParsedRecipe parsed = parsed("n", "d", List.of("a"), List.of("s"), 7, 13, null, 4, "c");
    assertThat(mapper.map(parsed).metadata().totalTimeMins()).isEqualTo(20);
  }

  @Test
  void metadata_nullServings_defaultsToOne() {
    ParsedRecipe parsed = parsed("n", "d", List.of("a"), List.of("s"), 0, 0, 0, null, "c");
    assertThat(mapper.map(parsed).metadata().servings()).isEqualTo(1);
  }

  @Test
  void metadata_zeroServings_defaultsToOne() {
    ParsedRecipe parsed = parsed("n", "d", List.of("a"), List.of("s"), 0, 0, 0, 0, "c");
    assertThat(mapper.map(parsed).metadata().servings()).isEqualTo(1);
  }

  @Test
  void metadata_negativeServings_defaultsToOne() {
    ParsedRecipe parsed = parsed("n", "d", List.of("a"), List.of("s"), 0, 0, 0, -3, "c");
    assertThat(mapper.map(parsed).metadata().servings()).isEqualTo(1);
  }

  @Test
  void metadata_positiveServings_preserved() {
    ParsedRecipe parsed = parsed("n", "d", List.of("a"), List.of("s"), 0, 0, 0, 6, "c");
    assertThat(mapper.map(parsed).metadata().servings()).isEqualTo(6);
  }

  @Test
  void metadata_cuisineClampedToSixtyFour() {
    String longCuisine = "c".repeat(100);
    ParsedRecipe parsed = parsed("n", "d", List.of("a"), List.of("s"), 0, 0, 0, 1, longCuisine);
    assertThat(mapper.map(parsed).metadata().cuisine()).hasSize(64);
  }

  @Test
  void metadata_nullCuisine_passThroughAsNull() {
    ParsedRecipe parsed = parsed("n", "d", List.of("a"), List.of("s"), 0, 0, 0, 1, null);
    assertThat(mapper.map(parsed).metadata().cuisine()).isNull();
  }

  // ---------------- helpers ----------------

  private static ParsedRecipe parsed(
      String name,
      String description,
      List<String> ingredients,
      List<String> method,
      Integer prep,
      Integer cook,
      Integer total,
      Integer servings,
      String cuisine) {
    return new ParsedRecipe(
        name,
        description,
        ingredients,
        method,
        prep,
        cook,
        total,
        servings,
        cuisine,
        "structured-data",
        JsonNodeFactory.instance.objectNode());
  }
}
