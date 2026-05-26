package com.example.mealprep.recipe;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.recipe.domain.service.internal.HtmlImportParser;
import com.example.mealprep.recipe.domain.service.internal.HtmlImportParser.ParsedRecipe;
import com.example.mealprep.recipe.testing.E2eRecipeFixtureController;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * Guards the contract between {@link E2eRecipeFixtureController} (the hermetic recipe page served
 * to the e2e URL-import flow) and {@link HtmlImportParser} (the REAL JSON-LD extractor that flow
 * runs): the served page must parse into a complete recipe — name + ingredients + method steps — or
 * the un-pended RCP-03 / XJ-01 e2e scenarios would silently fail their import leg.
 */
class E2eRecipeFixtureParseTest {

  private final HtmlImportParser parser = new HtmlImportParser(new ObjectMapper());

  @Test
  void servedFixtureHtml_parsesIntoCompleteRecipeViaJsonLd() {
    String html = new E2eRecipeFixtureController().fixture("chicken-and-rice-bowl");

    ParsedRecipe parsed =
        parser.parse(
            html, "http://localhost:8080/test-support/recipe/fixtures/chicken-and-rice-bowl");

    assertThat(parsed.extractionMethod()).isEqualTo("json_ld");
    assertThat(parsed.name()).isEqualTo("Chicken and Rice Bowl");
    // Realistic, USDA-mappable whole-food ingredient lines (XJ-01 derives nutrition from these).
    assertThat(parsed.ingredientLines())
        .containsExactly(
            "200 g chicken breast",
            "150 g white rice",
            "100 g broccoli",
            "1 tbsp olive oil",
            "1 tsp salt");
    assertThat(parsed.methodSteps()).hasSize(3);
    assertThat(parsed.methodSteps().get(0)).contains("Cook the white rice");
    // Times/servings are present and self-consistent (totalTime == prep + cook).
    assertThat(parsed.prepMinutes()).isEqualTo(10);
    assertThat(parsed.cookMinutes()).isEqualTo(20);
    assertThat(parsed.totalMinutes()).isEqualTo(30);
    assertThat(parsed.servings()).isEqualTo(2);
    assertThat(parsed.cuisine()).isEqualTo("American");
  }
}
