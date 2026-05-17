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
}
