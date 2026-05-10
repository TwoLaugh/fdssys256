package com.example.mealprep.recipe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.mealprep.recipe.domain.service.internal.HtmlImportParser;
import com.example.mealprep.recipe.domain.service.internal.HtmlImportParser.ParsedRecipe;
import com.example.mealprep.recipe.exception.RecipeImportFailureException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Unit test for {@link HtmlImportParser} — exercises all three extraction strategies. */
class HtmlImportParserTest {

  private final HtmlImportParser parser = new HtmlImportParser(new ObjectMapper());

  @Test
  void parse_jsonLdFixture_extractsAllFields() throws Exception {
    String html = readFixture("recipe/fixtures/jsonld-recipe.html");

    ParsedRecipe parsed = parser.parse(html, "https://example.com/jsonld");

    assertThat(parsed.extractionMethod()).isEqualTo("json_ld");
    assertThat(parsed.name()).isEqualTo("Roast Chicken");
    assertThat(parsed.description()).contains("weeknight roast");
    assertThat(parsed.ingredientLines()).hasSize(4);
    assertThat(parsed.methodSteps()).hasSize(3);
    assertThat(parsed.prepMinutes()).isEqualTo(15);
    assertThat(parsed.cookMinutes()).isEqualTo(75);
    assertThat(parsed.totalMinutes()).isEqualTo(90);
    assertThat(parsed.servings()).isEqualTo(4);
    assertThat(parsed.cuisine()).isEqualTo("American");
  }

  @Test
  void parse_microdataFixture_extractsBasicFields() throws Exception {
    String html = readFixture("recipe/fixtures/microdata-recipe.html");

    ParsedRecipe parsed = parser.parse(html, "https://example.com/microdata");

    assertThat(parsed.extractionMethod()).isEqualTo("microdata");
    assertThat(parsed.name()).isEqualTo("Tomato Soup");
    assertThat(parsed.ingredientLines()).hasSize(3);
    assertThat(parsed.methodSteps()).hasSize(3);
    assertThat(parsed.cuisine()).isEqualTo("Italian");
  }

  @Test
  void parse_noRecipeFixture_throwsImportFailure() throws Exception {
    String html = readFixture("recipe/fixtures/no-recipe.html");

    assertThatThrownBy(() -> parser.parse(html, "https://example.com/none"))
        .isInstanceOf(RecipeImportFailureException.class)
        .hasMessageContaining("no_extractor_matched");
  }

  private static String readFixture(String classpathName) throws Exception {
    var stream = HtmlImportParserTest.class.getClassLoader().getResourceAsStream(classpathName);
    if (stream == null) {
      throw new IllegalStateException("Fixture not found: " + classpathName);
    }
    try (stream) {
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
