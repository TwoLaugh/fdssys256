package com.example.mealprep.recipe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.mealprep.recipe.domain.service.internal.HtmlImportParser;
import com.example.mealprep.recipe.domain.service.internal.HtmlImportParser.ParsedRecipe;
import com.example.mealprep.recipe.exception.RecipeImportFailureException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * Mutation-killing unit tests for {@link HtmlImportParser}. Inline HTML/JSON-LD fixtures drive the
 * JSON-LD {@code @graph}/array recursion, the {@code @type} array form, the textual/array/object
 * {@code recipeInstructions} shapes, the {@code recipeYield} int/text/array forms, ISO-8601
 * duration parsing, and the common-selectors fallbacks — paths the fixture-based {@link
 * HtmlImportParserTest} did not reach.
 */
class HtmlImportParserMutationTest {

  private final HtmlImportParser parser = new HtmlImportParser(new ObjectMapper());

  private static String jsonLdPage(String json) {
    return "<html><head><script type=\"application/ld+json\">"
        + json
        + "</script></head><body></body></html>";
  }

  // ---------------- findRecipeNode: @graph + array recursion ----------------

  @Test
  void jsonLd_graphWrappedRecipe_isFound() {
    String json =
        "{\"@context\":\"https://schema.org\",\"@graph\":["
            + "{\"@type\":\"WebPage\",\"name\":\"Page\"},"
            + "{\"@type\":\"Recipe\",\"name\":\"Graph Stew\","
            + "\"recipeIngredient\":[\"1 onion\",\"2 carrots\"],"
            + "\"recipeInstructions\":\"Chop.\\nSimmer.\"}]}";
    ParsedRecipe parsed = parser.parse(jsonLdPage(json), "https://example.com/g");
    assertThat(parsed.extractionMethod()).isEqualTo("json_ld");
    assertThat(parsed.name()).isEqualTo("Graph Stew");
    assertThat(parsed.ingredientLines()).containsExactly("1 onion", "2 carrots");
    assertThat(parsed.methodSteps()).containsExactly("Chop.", "Simmer.");
  }

  @Test
  void jsonLd_topLevelArray_recursesIntoElements() {
    String json =
        "[{\"@type\":\"Organization\",\"name\":\"Acme\"},"
            + "{\"@type\":\"Recipe\",\"name\":\"Array Soup\","
            + "\"recipeIngredient\":[\"water\"],"
            + "\"recipeInstructions\":[\"Boil water.\"]}]";
    ParsedRecipe parsed = parser.parse(jsonLdPage(json), "https://example.com/a");
    assertThat(parsed.name()).isEqualTo("Array Soup");
    assertThat(parsed.ingredientLines()).containsExactly("water");
  }

  @Test
  void jsonLd_typeArrayContainingRecipe_isRecognised() {
    // isRecipeType array branch (L119-122): @type is ["Thing","Recipe"].
    String json =
        "{\"@type\":[\"Thing\",\"Recipe\"],\"name\":\"Multi Type\","
            + "\"recipeIngredient\":[\"salt\"],"
            + "\"recipeInstructions\":[\"Season.\"]}";
    ParsedRecipe parsed = parser.parse(jsonLdPage(json), "https://example.com/t");
    assertThat(parsed.name()).isEqualTo("Multi Type");
  }

  @Test
  void jsonLd_typeArrayWithoutRecipe_fallsThroughToNoExtractor() {
    // isRecipeType must return false for an array with no "Recipe" entry (kills the L122 false→
    // and L126 default-true mutants — a true here would wrongly accept a non-recipe node).
    String json = "{\"@type\":[\"Thing\",\"Article\"],\"name\":\"Not a recipe\"}";
    assertThatThrownBy(() -> parser.parse(jsonLdPage(json), "https://example.com/n"))
        .isInstanceOf(RecipeImportFailureException.class)
        .hasMessageContaining("no_extractor_matched");
  }

  @Test
  void jsonLd_caseInsensitiveRecipeType_isRecognised() {
    String json =
        "{\"@type\":\"recipe\",\"name\":\"Lowercase\","
            + "\"recipeIngredient\":[\"x\"],\"recipeInstructions\":[\"do x\"]}";
    ParsedRecipe parsed = parser.parse(jsonLdPage(json), "https://example.com/lc");
    assertThat(parsed.name()).isEqualTo("Lowercase");
  }

  @Test
  void jsonLd_completeNodeButNonRecipeArrayType_isRejected() {
    // A fully-formed node whose @type array contains NO "Recipe" must be rejected. The L121
    // negate and the L126 "return true" mutants would both wrongly accept this complete node and
    // succeed instead of throwing no_extractor_matched.
    String json =
        "{\"@type\":[\"Thing\",\"Article\"],\"name\":\"Looks Complete\","
            + "\"recipeIngredient\":[\"flour\",\"sugar\"],"
            + "\"recipeInstructions\":[\"Mix.\",\"Bake.\"]}";
    assertThatThrownBy(() -> parser.parse(jsonLdPage(json), "https://example.com/cnr"))
        .isInstanceOf(RecipeImportFailureException.class)
        .hasMessageContaining("no_extractor_matched");
  }

  @Test
  void jsonLd_absentCuisine_isNull_notEmptyString() {
    // textOrNull must return null (not "") for an absent/blank field — kills the L245
    // 'replaced return value with ""' mutant.
    String json =
        "{\"@type\":\"Recipe\",\"name\":\"No Cuisine\","
            + "\"recipeIngredient\":[\"a\"],\"recipeInstructions\":[\"x\"]}";
    ParsedRecipe parsed = parser.parse(jsonLdPage(json), "https://example.com/nc");
    assertThat(parsed.cuisine()).isNull();
    assertThat(parsed.description()).isNull();
  }

  // ---------------- parseInstructions shapes ----------------

  @Test
  void jsonLd_instructionsAsHowToStepObjects_extractsTextField() {
    String json =
        "{\"@type\":\"Recipe\",\"name\":\"Steps\","
            + "\"recipeIngredient\":[\"a\"],"
            + "\"recipeInstructions\":[{\"@type\":\"HowToStep\",\"text\":\"First step.\"},"
            + "{\"@type\":\"HowToStep\",\"text\":\"Second step.\"}]}";
    ParsedRecipe parsed = parser.parse(jsonLdPage(json), "https://example.com/s");
    assertThat(parsed.methodSteps()).containsExactly("First step.", "Second step.");
  }

  @Test
  void jsonLd_instructionsAsHowToSection_recursesItemListElement() {
    String json =
        "{\"@type\":\"Recipe\",\"name\":\"Sectioned\","
            + "\"recipeIngredient\":[\"a\"],"
            + "\"recipeInstructions\":[{\"@type\":\"HowToSection\","
            + "\"itemListElement\":[{\"@type\":\"HowToStep\",\"text\":\"Nested step.\"}]}]}";
    ParsedRecipe parsed = parser.parse(jsonLdPage(json), "https://example.com/sec");
    assertThat(parsed.methodSteps()).containsExactly("Nested step.");
  }

  @Test
  void jsonLd_instructionsAsNewlineDelimitedText_isSplitAndTrimmed() {
    String json =
        "{\"@type\":\"Recipe\",\"name\":\"Textual\","
            + "\"recipeIngredient\":[\"a\"],"
            + "\"recipeInstructions\":\"  Step one  \\n\\n  Step two  \"}";
    ParsedRecipe parsed = parser.parse(jsonLdPage(json), "https://example.com/txt");
    // Blank lines dropped, each line trimmed (kills the L164/L167 negate mutants).
    assertThat(parsed.methodSteps()).containsExactly("Step one", "Step two");
  }

  // ---------------- parseServings forms ----------------

  @Test
  void jsonLd_servingsAsInteger_isParsed() {
    String json =
        "{\"@type\":\"Recipe\",\"name\":\"N\",\"recipeIngredient\":[\"a\"],"
            + "\"recipeInstructions\":[\"x\"],\"recipeYield\":6}";
    assertThat(parser.parse(jsonLdPage(json), "https://example.com/i").servings()).isEqualTo(6);
  }

  @Test
  void jsonLd_servingsAsTextWithWords_takesLeadingNumber() {
    String json =
        "{\"@type\":\"Recipe\",\"name\":\"N\",\"recipeIngredient\":[\"a\"],"
            + "\"recipeInstructions\":[\"x\"],\"recipeYield\":\"8 servings\"}";
    assertThat(parser.parse(jsonLdPage(json), "https://example.com/tw").servings()).isEqualTo(8);
  }

  @Test
  void jsonLd_servingsAsArray_usesFirstElement() {
    String json =
        "{\"@type\":\"Recipe\",\"name\":\"N\",\"recipeIngredient\":[\"a\"],"
            + "\"recipeInstructions\":[\"x\"],\"recipeYield\":[\"4\",\"4 servings\"]}";
    assertThat(parser.parse(jsonLdPage(json), "https://example.com/ar").servings()).isEqualTo(4);
  }

  @Test
  void jsonLd_servingsUnparseableText_isNull() {
    String json =
        "{\"@type\":\"Recipe\",\"name\":\"N\",\"recipeIngredient\":[\"a\"],"
            + "\"recipeInstructions\":[\"x\"],\"recipeYield\":\"many\"}";
    assertThat(parser.parse(jsonLdPage(json), "https://example.com/u").servings()).isNull();
  }

  @Test
  void jsonLd_noServingsKey_isNull() {
    String json =
        "{\"@type\":\"Recipe\",\"name\":\"N\",\"recipeIngredient\":[\"a\"],"
            + "\"recipeInstructions\":[\"x\"]}";
    assertThat(parser.parse(jsonLdPage(json), "https://example.com/no").servings()).isNull();
  }

  // ---------------- isoDurationToMinutes ----------------

  @Test
  void jsonLd_isoDurations_areConvertedToMinutes() {
    String json =
        "{\"@type\":\"Recipe\",\"name\":\"N\",\"recipeIngredient\":[\"a\"],"
            + "\"recipeInstructions\":[\"x\"],"
            + "\"prepTime\":\"PT20M\",\"cookTime\":\"PT1H30M\",\"totalTime\":\"PT1H50M\"}";
    ParsedRecipe parsed = parser.parse(jsonLdPage(json), "https://example.com/d");
    assertThat(parsed.prepMinutes()).isEqualTo(20);
    assertThat(parsed.cookMinutes()).isEqualTo(90);
    assertThat(parsed.totalMinutes()).isEqualTo(110);
  }

  @Test
  void jsonLd_invalidDuration_isNull_notZero() {
    String json =
        "{\"@type\":\"Recipe\",\"name\":\"N\",\"recipeIngredient\":[\"a\"],"
            + "\"recipeInstructions\":[\"x\"],\"prepTime\":\"not-a-duration\"}";
    // Kills the L259 "replaced Integer return with 0" mutant — a bad duration must yield null.
    assertThat(parser.parse(jsonLdPage(json), "https://example.com/bad").prepMinutes()).isNull();
  }

  @Test
  void jsonLd_missingDuration_isNull() {
    String json =
        "{\"@type\":\"Recipe\",\"name\":\"N\",\"recipeIngredient\":[\"a\"],"
            + "\"recipeInstructions\":[\"x\"]}";
    assertThat(parser.parse(jsonLdPage(json), "https://example.com/md").cookMinutes()).isNull();
  }

  // ---------------- stringArray ----------------

  @Test
  void jsonLd_ingredientAsSingleString_becomesOneElementList() {
    String json =
        "{\"@type\":\"Recipe\",\"name\":\"N\",\"recipeIngredient\":\"just one\","
            + "\"recipeInstructions\":[\"x\"]}";
    assertThat(parser.parse(jsonLdPage(json), "https://example.com/si").ingredientLines())
        .containsExactly("just one");
  }

  // ---------------- common selectors fallbacks ----------------

  @Test
  void commonSelectors_h1Fallback_andInstructionsClassFallback() {
    // No h1.recipe-title → falls back to plain h1 (L302/303). No .method li → falls back to
    // .instructions li (L307/308). Kills the L302/L307 negate + parse L54 return mutants.
    String html =
        "<html><body>"
            + "<h1>Pancakes</h1>"
            + "<ul class=\"ingredients\"><li>flour</li><li>milk</li></ul>"
            + "<ul class=\"instructions\"><li>Mix.</li><li>Fry.</li></ul>"
            + "</body></html>";
    ParsedRecipe parsed = parser.parse(html, "https://example.com/cs");
    assertThat(parsed.extractionMethod()).isEqualTo("common_selectors");
    assertThat(parsed.name()).isEqualTo("Pancakes");
    assertThat(parsed.ingredientLines()).containsExactly("flour", "milk");
    assertThat(parsed.methodSteps()).containsExactly("Mix.", "Fry.");
  }

  @Test
  void commonSelectors_recipeTitleClassTakesPrecedenceOverPlainH1() {
    String html =
        "<html><body>"
            + "<h1 class=\"recipe-title\">Real Title</h1>"
            + "<h1>Decoy</h1>"
            + "<ul class=\"ingredients\"><li>egg</li></ul>"
            + "<ul class=\"method\"><li>Whisk.</li></ul>"
            + "</body></html>";
    ParsedRecipe parsed = parser.parse(html, "https://example.com/rt");
    // textOf(h1.recipe-title) non-null → the plain-h1 fallback must NOT run (kills the L302
    // negate mutant which would overwrite with "Decoy").
    assertThat(parsed.name()).isEqualTo("Real Title");
  }

  @Test
  void commonSelectors_methodClassPreferredOverInstructionsFallback() {
    String html =
        "<html><body>"
            + "<h1 class=\"recipe-title\">Dish</h1>"
            + "<ul class=\"ingredients\"><li>rice</li></ul>"
            + "<ul class=\"method\"><li>Steam rice.</li></ul>"
            + "<ul class=\"instructions\"><li>Should be ignored.</li></ul>"
            + "</body></html>";
    ParsedRecipe parsed = parser.parse(html, "https://example.com/mp");
    // .method li non-empty → the .instructions fallback must NOT replace it (kills the L307
    // negate mutant).
    assertThat(parsed.methodSteps()).containsExactly("Steam rice.");
  }

  @Test
  void microdata_ingredientsItempropFallback_whenRecipeIngredientAbsent() {
    String html =
        "<html><body><div itemscope itemtype=\"https://schema.org/Recipe\">"
            + "<span itemprop=\"name\">Micro Dish</span>"
            + "<span itemprop=\"ingredients\">200g pasta</span>"
            + "<span itemprop=\"recipeInstructions\">Cook pasta.</span>"
            + "</div></body></html>";
    ParsedRecipe parsed = parser.parse(html, "https://example.com/mi");
    assertThat(parsed.extractionMethod()).isEqualTo("microdata");
    assertThat(parsed.ingredientLines()).containsExactly("200g pasta");
  }

  @Test
  void incompleteRecipe_missingMethod_throwsImportFailure() {
    // isComplete must reject a recipe with no method steps (exercises the completeness gate so
    // a recipe node that parses but is incomplete still falls through).
    String json = "{\"@type\":\"Recipe\",\"name\":\"No Method\",\"recipeIngredient\":[\"a\"]}";
    assertThatThrownBy(() -> parser.parse(jsonLdPage(json), "https://example.com/im"))
        .isInstanceOf(RecipeImportFailureException.class)
        .hasMessageContaining("no_extractor_matched");
  }
}
