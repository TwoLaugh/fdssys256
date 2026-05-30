package com.example.mealprep.recipe;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.discovery.api.dto.ParsedRecipe.ParsedIngredient;
import com.example.mealprep.discovery.source.internal.JsonLdRecipeExtractor;
import com.example.mealprep.recipe.domain.service.internal.HtmlImportParser;
import com.example.mealprep.recipe.extraction.ExtractionInput;
import com.example.mealprep.recipe.extraction.ExtractionLayer;
import com.example.mealprep.recipe.extraction.ParsedRecipe;
import com.example.mealprep.recipe.extraction.RecipeExtractionService;
import com.example.mealprep.recipe.extraction.RecipeExtractionServices;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Captured-real-page regression lock — IN the CI gate, deterministic.
 *
 * <p><b>Capture approach (per the ticket's "document which approach you used"):</b> this test feeds
 * a LIVE-CAPTURED real BBC Good Food page's markup. The fixture {@code
 * recipe/fixtures/bbc-good-food-real-capture.html} embeds the page's ACTUAL {@code <script
 * type="application/ld+json">} {@code schema.org/Recipe} block — fetched live from {@code
 * https://www.bbcgoodfood.com/recipes/classic-lasagne} (HTTP 200) and reproduced verbatim
 * (HowToStep[] instructions, ImageObject[], NutritionInformation, author[], keywords CSV, integer
 * recipeYield, ISO-8601 PT durations, UTF-8 accents in "purée" / "crème fraîche" / the "25–30" en
 * dash). Only the surrounding page chrome was trimmed; the Recipe JSON-LD is unmodified real-world
 * bytes — see the capture-provenance comment at the top of the fixture. It is stored (not fetched
 * at test time) so CI is deterministic; the live fetch is exercised separately by the opt-in
 * {@code @Tag("live")} {@code RealRecipeSiteLiveSmokeTest}.
 *
 * <p>The assertions prove the shared 5-layer {@link RecipeExtractionService} pulls the FULL recipe
 * from real-world markup — title, every ingredient line, and every method step — and that the
 * discovery adapter derives a non-null normalised {@code ingredientMappingKey} for each ingredient.
 */
class RealRecipeSiteCaptureTest {

  private static final String FIXTURE = "recipe/fixtures/bbc-good-food-real-capture.html";
  private static final String SOURCE_URL = "https://www.bbcgoodfood.com/recipes/classic-lasagne";

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final RecipeExtractionService service =
      RecipeExtractionServices.defaultService(objectMapper);

  @Test
  void capturedBbcGoodFoodPage_extractsFullRecipeViaSharedService() throws Exception {
    String html = readFixture();

    Optional<ParsedRecipe> result = service.extract(new ExtractionInput.FromHtml(SOURCE_URL, html));

    assertThat(result).as("real BBC Good Food JSON-LD must extract").isPresent();
    ParsedRecipe recipe = result.get();

    // Title.
    assertThat(recipe.name()).isEqualTo("Easy classic lasagne");
    assertThat(recipe.cuisine()).isEqualTo("Italian");
    assertThat(recipe.servings()).isEqualTo(6);
    assertThat(recipe.prepTimeMinutes()).isEqualTo(15);
    assertThat(recipe.cookTimeMinutes()).isEqualTo(60);
    assertThat(recipe.totalTimeMinutes()).isEqualTo(75);

    // The winning layer is Layer 1 (JSON-LD) — the dominant real-site format.
    assertThat(recipe.provenance().winningLayer()).isEqualTo(ExtractionLayer.JSON_LD);

    // Every ingredient line — the full set (15), in order, verbatim from the real markup.
    assertThat(recipe.ingredients())
        .hasSize(15)
        .extracting(ParsedRecipe.ParsedIngredient::rawLine)
        .containsExactly(
            "1 tbsp olive oil",
            "2 rashers smoked streaky bacon",
            "1 onion finely chopped",
            "1 celery stick, finely chopped",
            "1 medium carrot grated",
            "2 garlic cloves finely chopped",
            "500g beef mince",
            "1 tbsp tomato purée",
            "2 x 400g cans chopped tomatoes",
            "1 tbsp clear honey",
            "500g pack fresh egg lasagne sheets",
            "400ml crème fraîche",
            "125g ball mozzarella roughly torn",
            "50g freshly grated parmesan",
            "large handful basil leaves torn (optional)");

    // Method steps — all 5 HowToStep[] entries mapped, numbered 1..5.
    assertThat(recipe.methodSteps()).hasSize(5);
    assertThat(recipe.methodSteps().get(0).stepNumber()).isEqualTo(1);
    assertThat(recipe.methodSteps().get(0).instruction()).startsWith("Heat the oil in a large");
    assertThat(recipe.methodSteps().get(4).stepNumber()).isEqualTo(5);
    assertThat(recipe.methodSteps().get(4).instruction()).contains("crème fraîche");
  }

  @Test
  void capturedBbcGoodFoodPage_eachIngredientHasNonNullNormalisedMappingKey() throws Exception {
    // The discovery adapter is the consumer that derives ingredient_mapping_key. Run the captured
    // real page through it and assert every line gets a non-null, canonically-normalised key.
    JsonLdRecipeExtractor discoveryExtractor = new JsonLdRecipeExtractor(objectMapper);

    Optional<com.example.mealprep.discovery.api.dto.ParsedRecipe> result =
        discoveryExtractor.extract(readFixture(), SOURCE_URL);

    assertThat(result).isPresent();
    var ingredients = result.get().ingredients();
    assertThat(ingredients).hasSize(15);
    assertThat(ingredients)
        .allSatisfy(
            ingredient -> {
              String key = ingredient.ingredientMappingKey();
              assertThat(key).as("ingredientMappingKey must be non-null").isNotNull();
              assertThat(key).as("ingredientMappingKey must be non-blank").isNotBlank();
              // Canonical normalised form: lowercase, trimmed, internal whitespace collapsed.
              assertThat(key).isEqualTo(key.trim().toLowerCase().replaceAll("\\s+", " "));
            });
    // Spot-check the normalisation on the accented line.
    ParsedIngredient puree =
        ingredients.stream()
            .filter(i -> i.displayName().contains("tomato"))
            .findFirst()
            .orElseThrow();
    assertThat(puree.ingredientMappingKey()).isEqualTo("1 tbsp tomato purée");
  }

  @Test
  void capturedBbcGoodFoodPage_extractsViaRecipeImportAdapter() throws Exception {
    // The recipe URL-import adapter view of the same shared service — proves the import path also
    // pulls the full recipe from the captured real page and stamps json_ld provenance.
    HtmlImportParser importParser = new HtmlImportParser(objectMapper);

    HtmlImportParser.ParsedRecipe parsed = importParser.parse(readFixture(), SOURCE_URL);

    assertThat(parsed.extractionMethod()).isEqualTo("json_ld");
    assertThat(parsed.name()).isEqualTo("Easy classic lasagne");
    assertThat(parsed.ingredientLines()).hasSize(15);
    assertThat(parsed.methodSteps()).hasSize(5);
    assertThat(parsed.servings()).isEqualTo(6);
    assertThat(parsed.cuisine()).isEqualTo("Italian");
  }

  private static String readFixture() throws IOException {
    try (InputStream in =
        RealRecipeSiteCaptureTest.class.getClassLoader().getResourceAsStream(FIXTURE)) {
      if (in == null) {
        throw new IllegalStateException("fixture not found: " + FIXTURE);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
