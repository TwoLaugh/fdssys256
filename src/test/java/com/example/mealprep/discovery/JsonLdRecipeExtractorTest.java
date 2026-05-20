package com.example.mealprep.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.discovery.api.dto.ParsedRecipe;
import com.example.mealprep.discovery.source.internal.JsonLdRecipeExtractor;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the shared JSON-LD extractor: schema.org mapping, {@code @type}-array and {@code
 * @graph} traversal, malformed-block tolerance (second valid block wins), {@code recipeYield} +
 * ISO-8601 duration parsing, and the fixed 0.85 confidence.
 */
class JsonLdRecipeExtractorTest {

  private final JsonLdRecipeExtractor extractor = new JsonLdRecipeExtractor(new ObjectMapper());

  private String fixture(String name) throws IOException {
    try (InputStream in =
        getClass().getClassLoader().getResourceAsStream("discovery/fixtures/" + name)) {
      if (in == null) {
        throw new IllegalStateException("fixture not found: " + name);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  @Test
  void extract_bbcGoodFoodSingleRecipe_mapsAllFields() throws Exception {
    Optional<ParsedRecipe> result =
        extractor.extract(
            fixture("bbc-good-food-recipe.html"), "https://www.bbcgoodfood.com/recipes/x");

    assertThat(result).isPresent();
    ParsedRecipe r = result.get();
    assertThat(r.name()).isEqualTo("Classic Spaghetti Bolognese");
    assertThat(r.description()).isEqualTo("A rich, slow-cooked Italian-style ragu.");
    assertThat(r.canonicalUrl()).isEqualTo("https://www.bbcgoodfood.com/recipes/x");
    assertThat(r.extractionMethod()).isEqualTo("json_ld");
    assertThat(r.extractionConfidence()).isEqualByComparingTo(BigDecimal.valueOf(0.85));
    assertThat(r.ingredients()).hasSize(4);
    assertThat(r.ingredients().get(0).displayName()).isEqualTo("2 tbsp olive oil");
    assertThat(r.ingredients().get(0).ingredientMappingKey()).isNull();
    assertThat(r.method()).hasSize(4);
    assertThat(r.method().get(0).stepNumber()).isEqualTo(1);
    assertThat(r.method().get(3).stepNumber()).isEqualTo(4);
    assertThat(r.metadata().servings()).isEqualTo(4);
    assertThat(r.metadata().prepTimeMins()).isEqualTo(15);
    assertThat(r.metadata().cookTimeMins()).isEqualTo(90);
    assertThat(r.metadata().totalTimeMins()).isEqualTo(105);
    assertThat(r.metadata().cuisine()).isEqualTo("Italian");
  }

  @Test
  void extract_typeArrayWithinGraph_recipeExtracted() throws Exception {
    Optional<ParsedRecipe> result =
        extractor.extract(fixture("serious-eats-recipe.html"), "https://seriouseats.test/c");

    assertThat(result).isPresent();
    ParsedRecipe r = result.get();
    assertThat(r.name()).isEqualTo("The Best Chocolate Chip Cookies");
    // recipeInstructions as HowToStep objects → mapped from .text
    assertThat(r.method()).hasSize(3);
    assertThat(r.method().get(0).instruction()).isEqualTo("Cream butter and sugar.");
    // recipeYield "Makes 12 cookies" → first integer
    assertThat(r.metadata().servings()).isEqualTo(12);
    assertThat(r.metadata().prepTimeMins()).isEqualTo(20);
  }

  @Test
  void extract_malformedFirstBlock_secondValidBlockWins() throws Exception {
    Optional<ParsedRecipe> result =
        extractor.extract(fixture("malformed-jsonld.html"), "https://test/pancakes");

    assertThat(result).isPresent();
    ParsedRecipe r = result.get();
    assertThat(r.name()).isEqualTo("Fluffy Pancakes");
    assertThat(r.metadata().servings()).isEqualTo(6);
    // recipeInstructions as a single string → split on newline (here one line)
    assertThat(r.method()).hasSize(1);
    assertThat(r.method().get(0).instruction()).isEqualTo("Whisk everything. Fry in a hot pan.");
  }

  @Test
  void extract_noJsonLd_returnsEmpty() throws Exception {
    assertThat(extractor.extract(fixture("no-jsonld.html"), "https://test/about")).isEmpty();
  }

  @Test
  void extract_nullOrBlankHtml_returnsEmpty() {
    assertThat(extractor.extract(null, "https://test/x")).isEmpty();
    assertThat(extractor.extract("   ", "https://test/x")).isEmpty();
  }

  @Test
  void extract_inlineTypeArray_recipeExtracted() {
    String html =
        "<html><head><script type=\"application/ld+json\">"
            + "{\"@type\":[\"Recipe\",\"NutritionInformation\"],\"name\":\"Soup\","
            + "\"recipeIngredient\":[\"water\"],\"recipeInstructions\":[\"Boil.\"],"
            + "\"recipeYield\":\"4-6\"}"
            + "</script></head><body></body></html>";

    Optional<ParsedRecipe> result = extractor.extract(html, "https://test/soup");

    assertThat(result).isPresent();
    assertThat(result.get().name()).isEqualTo("Soup");
    // "4-6" → first integer 4
    assertThat(result.get().metadata().servings()).isEqualTo(4);
  }

  @Test
  void extract_malformedDuration_toleratedAsNull() {
    String html =
        "<html><head><script type=\"application/ld+json\">"
            + "{\"@type\":\"Recipe\",\"name\":\"X\",\"prepTime\":\"15 minutes\","
            + "\"recipeIngredient\":[\"a\"],\"recipeInstructions\":[\"b\"]}"
            + "</script></head><body></body></html>";

    Optional<ParsedRecipe> result = extractor.extract(html, "https://test/x");

    assertThat(result).isPresent();
    assertThat(result.get().metadata().prepTimeMins()).isNull();
  }

  // ====== parseYield branch kills ======

  @Test
  void extract_recipeYieldFieldAbsent_yieldsNullServings() {
    // kills EmptyObjectReturnValsMutator at parseYield line 223 — the `node == null` early-return
    // must yield null, not 0. Absent recipeYield → r.get("recipeYield") returns Java null.
    String html =
        "<html><head><script type=\"application/ld+json\">"
            + "{\"@type\":\"Recipe\",\"name\":\"X\","
            + "\"recipeIngredient\":[\"a\"],\"recipeInstructions\":[\"b\"]}"
            + "</script></head><body></body></html>";

    Optional<ParsedRecipe> result = extractor.extract(html, "https://test/x");
    assertThat(result).isPresent();
    assertThat(result.get().metadata().servings()).isNull();
  }

  @Test
  void extract_recipeYieldNumberFormatExceptionFallback_yieldsNull() {
    // kills EmptyObjectReturnValsMutator at parseYield line 237 — a digit sequence too large for
    // Integer.parseInt triggers NumberFormatException and the catch returns null (not 0).
    // We must produce a textual yield whose regex group cannot fit in an int. Pattern is
    // \\d+ which matches greedily; a 30-digit number is too big for Integer.
    String hugeNumber = "1".repeat(30);
    String html =
        "<html><head><script type=\"application/ld+json\">"
            + "{\"@type\":\"Recipe\",\"name\":\"X\",\"recipeYield\":\""
            + hugeNumber
            + " servings\","
            + "\"recipeIngredient\":[\"a\"],\"recipeInstructions\":[\"b\"]}"
            + "</script></head><body></body></html>";

    Optional<ParsedRecipe> result = extractor.extract(html, "https://test/x");
    assertThat(result).isPresent();
    assertThat(result.get().metadata().servings()).isNull();
  }

  @Test
  void extract_recipeIngredientFieldAbsent_emptyIngredients() {
    // kills EmptyObjectReturnValsMutator at stringArray line 198 — absent recipeIngredient → null
    // node → return empty list (NOT Collections.emptyList which is the mutation).
    // Both code paths produce empty lists; the kill comes from line 161/198's `return out;` versus
    // `return Collections.emptyList()` — they're observably the same for callers using isEmpty,
    // BUT the caller stream-collects ingredients from this list. The mutated empty list is
    // immutable; the production list is a fresh ArrayList. We assert presence + emptiness.
    String html =
        "<html><head><script type=\"application/ld+json\">"
            + "{\"@type\":\"Recipe\",\"name\":\"X\","
            + "\"recipeInstructions\":[\"b\"]}"
            + "</script></head><body></body></html>";

    Optional<ParsedRecipe> result = extractor.extract(html, "https://test/x");
    assertThat(result).isPresent();
    assertThat(result.get().ingredients()).isEmpty();
  }

  @Test
  void extract_recipeInstructionsFieldAbsent_emptyMethod() {
    // kills EmptyObjectReturnValsMutator at parseInstructions line 161 — absent field → null
    // node → return empty list.
    String html =
        "<html><head><script type=\"application/ld+json\">"
            + "{\"@type\":\"Recipe\",\"name\":\"X\","
            + "\"recipeIngredient\":[\"salt\"]}"
            + "</script></head><body></body></html>";

    Optional<ParsedRecipe> result = extractor.extract(html, "https://test/x");
    assertThat(result).isPresent();
    assertThat(result.get().method()).isEmpty();
  }

  @Test
  void extract_recipeYieldAsInteger_returnsIntegerValue() {
    // kills NullReturnValsMutator + EmptyObjectReturnValsMutator on parseYield int-branch (line
    // 226).
    String html =
        "<html><head><script type=\"application/ld+json\">"
            + "{\"@type\":\"Recipe\",\"name\":\"X\",\"recipeYield\":8,"
            + "\"recipeIngredient\":[\"a\"],\"recipeInstructions\":[\"b\"]}"
            + "</script></head><body></body></html>";

    Optional<ParsedRecipe> result = extractor.extract(html, "https://test/x");
    assertThat(result).isPresent();
    assertThat(result.get().metadata().servings()).isEqualTo(8);
  }

  @Test
  void extract_recipeYieldEmptyArray_yieldsNullServings() {
    // kills NegateConditionalsMutator on the !node.isEmpty() check (parseYield line 228) — an
    // empty array short-circuits to null.
    String html =
        "<html><head><script type=\"application/ld+json\">"
            + "{\"@type\":\"Recipe\",\"name\":\"X\",\"recipeYield\":[],"
            + "\"recipeIngredient\":[\"a\"],\"recipeInstructions\":[\"b\"]}"
            + "</script></head><body></body></html>";

    Optional<ParsedRecipe> result = extractor.extract(html, "https://test/x");
    assertThat(result).isPresent();
    assertThat(result.get().metadata().servings()).isNull();
  }

  @Test
  void extract_recipeYieldArrayWithInteger_recursesAndReturnsValue() {
    // kills the EmptyObjectReturnValsMutator at line 229 — recursion must return the first
    // element's
    // value, not 0.
    String html =
        "<html><head><script type=\"application/ld+json\">"
            + "{\"@type\":\"Recipe\",\"name\":\"X\",\"recipeYield\":[12, 24],"
            + "\"recipeIngredient\":[\"a\"],\"recipeInstructions\":[\"b\"]}"
            + "</script></head><body></body></html>";

    Optional<ParsedRecipe> result = extractor.extract(html, "https://test/x");
    assertThat(result).isPresent();
    assertThat(result.get().metadata().servings()).isEqualTo(12);
  }

  @Test
  void extract_recipeYieldTextWithoutAnyDigit_yieldsNullServings() {
    // kills NegateConditionalsMutator at parseYield line 233 (m.find()) — no integer = no yield.
    String html =
        "<html><head><script type=\"application/ld+json\">"
            + "{\"@type\":\"Recipe\",\"name\":\"X\",\"recipeYield\":\"many\","
            + "\"recipeIngredient\":[\"a\"],\"recipeInstructions\":[\"b\"]}"
            + "</script></head><body></body></html>";

    Optional<ParsedRecipe> result = extractor.extract(html, "https://test/x");
    assertThat(result).isPresent();
    assertThat(result.get().metadata().servings()).isNull();
  }

  @Test
  void extract_recipeYieldUnsupportedNodeType_yieldsNullServings() {
    // Exercises the final-fall-through return null path (line 241).
    String html =
        "<html><head><script type=\"application/ld+json\">"
            + "{\"@type\":\"Recipe\",\"name\":\"X\",\"recipeYield\":{},"
            + "\"recipeIngredient\":[\"a\"],\"recipeInstructions\":[\"b\"]}"
            + "</script></head><body></body></html>";

    Optional<ParsedRecipe> result = extractor.extract(html, "https://test/x");
    assertThat(result).isPresent();
    assertThat(result.get().metadata().servings()).isNull();
  }

  // ====== stringArray branch kills (recipeIngredient as string-or-array) ======

  @Test
  void extract_recipeIngredientAsSingleString_singletonIngredient() {
    // kills NegateConditionalsMutator + EmptyObjectReturnValsMutator at stringArray text-branch
    // (lines 200-205). Schema.org permits a single string for recipeIngredient.
    String html =
        "<html><head><script type=\"application/ld+json\">"
            + "{\"@type\":\"Recipe\",\"name\":\"X\",\"recipeIngredient\":\"salt\","
            + "\"recipeInstructions\":[\"b\"]}"
            + "</script></head><body></body></html>";

    Optional<ParsedRecipe> result = extractor.extract(html, "https://test/x");
    assertThat(result).isPresent();
    assertThat(result.get().ingredients()).extracting("displayName").containsExactly("salt");
  }

  @Test
  void extract_recipeIngredientAsBlankString_yieldsEmptyIngredients() {
    // Exercises stringArray's "trim then check isEmpty" branch — a blank string yields empty.
    String html =
        "<html><head><script type=\"application/ld+json\">"
            + "{\"@type\":\"Recipe\",\"name\":\"X\",\"recipeIngredient\":\"   \","
            + "\"recipeInstructions\":[\"b\"]}"
            + "</script></head><body></body></html>";

    Optional<ParsedRecipe> result = extractor.extract(html, "https://test/x");
    assertThat(result).isPresent();
    assertThat(result.get().ingredients()).isEmpty();
  }

  @Test
  void extract_recipeIngredientArrayContainsBlankAndNonString_filtered() {
    // Exercises stringArray's array-branch isTextual + trim/isEmpty guards.
    String html =
        "<html><head><script type=\"application/ld+json\">"
            + "{\"@type\":\"Recipe\",\"name\":\"X\","
            + "\"recipeIngredient\":[\"\",\" salt \",42,\"pepper\"],"
            + "\"recipeInstructions\":[\"b\"]}"
            + "</script></head><body></body></html>";

    Optional<ParsedRecipe> result = extractor.extract(html, "https://test/x");
    assertThat(result).isPresent();
    assertThat(result.get().ingredients())
        .extracting("displayName")
        .containsExactly("salt", "pepper");
  }

  // ====== parseInstructions branch kills (HowToStep with itemListElement) ======

  @Test
  void extract_howToStepWithItemListElement_recursivelyFlattens() {
    // kills NegateConditionalsMutator at parseInstructions line 185 (itemList != null) and
    // EmptyObjectReturnValsMutator at line 161 (the early-return when node is null).
    String html =
        "<html><head><script type=\"application/ld+json\">"
            + "{\"@type\":\"Recipe\",\"name\":\"X\","
            + "\"recipeIngredient\":[\"a\"],"
            + "\"recipeInstructions\":[{\"@type\":\"HowToSection\","
            + "\"itemListElement\":[\"step1\",\"step2\"]}]"
            + "}</script></head><body></body></html>";

    Optional<ParsedRecipe> result = extractor.extract(html, "https://test/x");
    assertThat(result).isPresent();
    assertThat(result.get().method()).extracting("instruction").containsExactly("step1", "step2");
  }

  // ====== findRecipeNode + @type-array kills (line 120) ======

  @Test
  void extract_typeArrayWithoutRecipe_returnsEmpty() {
    // kills NegateConditionalsMutator at findRecipeNode line 120 (textual @type comparison inside
    // array). With @type=["Article","Thing"] there's no "Recipe" → no recipe.
    String html =
        "<html><head><script type=\"application/ld+json\">"
            + "{\"@type\":[\"Article\",\"Thing\"],\"name\":\"X\"}"
            + "</script></head><body></body></html>";

    assertThat(extractor.extract(html, "https://test/x")).isEmpty();
  }

  @Test
  void extract_topLevelJsonLdArrayContainsRecipe_extracted() {
    // kills NullReturnValsMutator at findRecipeNode line 98 (root.isArray() recursion) and
    // NegateConditionalsMutator at line 97 (the `r != null` guard).
    String html =
        "<html><head><script type=\"application/ld+json\">"
            + "[{\"@type\":\"WebPage\"},"
            + "{\"@type\":\"Recipe\",\"name\":\"Salad\","
            + "\"recipeIngredient\":[\"lettuce\"],"
            + "\"recipeInstructions\":[\"mix\"]}]"
            + "</script></head><body></body></html>";

    Optional<ParsedRecipe> result = extractor.extract(html, "https://test/salad");
    assertThat(result).isPresent();
    assertThat(result.get().name()).isEqualTo("Salad");
  }

  // ====== textOrNull empty-after-trim branch ======

  @Test
  void extract_descriptionAbsent_returnsNullDescription() {
    // kills EmptyObjectReturnValsMutator at textOrNull line 246 — absent field → null, NOT "".
    String html =
        "<html><head><script type=\"application/ld+json\">"
            + "{\"@type\":\"Recipe\",\"name\":\"X\","
            + "\"recipeIngredient\":[\"a\"],\"recipeInstructions\":[\"b\"]}"
            + "</script></head><body></body></html>";

    Optional<ParsedRecipe> result = extractor.extract(html, "https://test/x");
    assertThat(result).isPresent();
    assertThat(result.get().description()).isNull();
  }

  @Test
  void extract_descriptionNotTextual_returnsNullDescription() {
    // Exercises the `!isTextual()` branch of textOrNull line 245.
    String html =
        "<html><head><script type=\"application/ld+json\">"
            + "{\"@type\":\"Recipe\",\"name\":\"X\",\"description\":[1,2],"
            + "\"recipeIngredient\":[\"a\"],\"recipeInstructions\":[\"b\"]}"
            + "</script></head><body></body></html>";

    Optional<ParsedRecipe> result = extractor.extract(html, "https://test/x");
    assertThat(result).isPresent();
    assertThat(result.get().description()).isNull();
  }

  @Test
  void extract_descriptionAllWhitespace_returnsNullDescription() {
    // kills EmptyObjectReturnValsMutator at textOrNull line 246 — a blank value must yield null,
    // not "".
    String html =
        "<html><head><script type=\"application/ld+json\">"
            + "{\"@type\":\"Recipe\",\"name\":\"X\",\"description\":\"   \","
            + "\"recipeIngredient\":[\"a\"],\"recipeInstructions\":[\"b\"]}"
            + "</script></head><body></body></html>";

    Optional<ParsedRecipe> result = extractor.extract(html, "https://test/x");
    assertThat(result).isPresent();
    assertThat(result.get().description()).isNull();
  }

  // ====== isoToMinutes blank ======

  @Test
  void extract_prepTimeBlank_returnsNullDuration() {
    // kills EmptyObjectReturnValsMutator at isoToMinutes line 254 — blank yields null, not 0.
    String html =
        "<html><head><script type=\"application/ld+json\">"
            + "{\"@type\":\"Recipe\",\"name\":\"X\",\"prepTime\":\"   \","
            + "\"recipeIngredient\":[\"a\"],\"recipeInstructions\":[\"b\"]}"
            + "</script></head><body></body></html>";

    Optional<ParsedRecipe> result = extractor.extract(html, "https://test/x");
    assertThat(result).isPresent();
    assertThat(result.get().metadata().prepTimeMins()).isNull();
  }

  @Test
  void extract_prepTimeValidIso_returnsMinutes() {
    String html =
        "<html><head><script type=\"application/ld+json\">"
            + "{\"@type\":\"Recipe\",\"name\":\"X\",\"prepTime\":\"PT45M\","
            + "\"recipeIngredient\":[\"a\"],\"recipeInstructions\":[\"b\"]}"
            + "</script></head><body></body></html>";

    Optional<ParsedRecipe> result = extractor.extract(html, "https://test/x");
    assertThat(result).isPresent();
    assertThat(result.get().metadata().prepTimeMins()).isEqualTo(45);
  }
}
