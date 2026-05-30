package com.example.mealprep.recipe;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.recipe.extraction.ExtractionInput;
import com.example.mealprep.recipe.extraction.ExtractionLayer;
import com.example.mealprep.recipe.extraction.ParsedRecipe;
import com.example.mealprep.recipe.extraction.RecipeExtractionService;
import com.example.mealprep.recipe.extraction.RecipeSiteExtractor;
import com.example.mealprep.recipe.extraction.internal.ExtractionValidator;
import com.example.mealprep.recipe.extraction.internal.JsonLdExtractionLayer;
import com.example.mealprep.recipe.extraction.internal.MicrodataExtractionLayer;
import com.example.mealprep.recipe.extraction.internal.PerSiteExtractorRegistry;
import com.example.mealprep.recipe.extraction.internal.RecipeExtractionServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Orchestration tests for the shared 5-layer {@link RecipeExtractionService}: layer ordering, the
 * Layer 5 validator gate, the per-site (Layer 3) pre-extract / post-process hooks, the JSON-LD-only
 * entry point, and the input-mode handling. These exercise the new code directly (the consumer
 * adapters are tested via {@code HtmlImportParserTest} / {@code JsonLdRecipeExtractorTest} / {@code
 * RealRecipeSiteCaptureTest}).
 */
class RecipeExtractionServiceTest {

  private final ObjectMapper mapper = new ObjectMapper();

  private RecipeExtractionService service(RecipeSiteExtractor... perSite) {
    return new RecipeExtractionServiceImpl(
        new JsonLdExtractionLayer(mapper),
        new MicrodataExtractionLayer(),
        new PerSiteExtractorRegistry(List.of(perSite)),
        new ExtractionValidator());
  }

  private static String jsonLdPage(String body) {
    return "<html><head><script type=\"application/ld+json\">"
        + body
        + "</script></head><body></body></html>";
  }

  // ---------------- Layer 1: JSON-LD ----------------

  @Test
  void extract_jsonLd_winsAtLayer1_withProvenance() {
    String html =
        jsonLdPage(
            "{\"@type\":\"Recipe\",\"name\":\"Soup\",\"recipeIngredient\":[\"water\"],"
                + "\"recipeInstructions\":[\"Boil.\"]}");

    Optional<ParsedRecipe> result = service().extract(new ExtractionInput.FromHtml("u", html));

    assertThat(result).isPresent();
    assertThat(result.get().name()).isEqualTo("Soup");
    assertThat(result.get().provenance().winningLayer()).isEqualTo(ExtractionLayer.JSON_LD);
    assertThat(result.get().provenance().layersTried()).containsExactly(ExtractionLayer.JSON_LD);
  }

  // ---------------- Layer 2: microdata / common selectors ----------------

  @Test
  void extract_microdata_winsAtLayer2_whenNoJsonLd() {
    String html =
        "<html><body><div itemscope itemtype=\"https://schema.org/Recipe\">"
            + "<h1 itemprop=\"name\">Tomato Soup</h1>"
            + "<span itemprop=\"recipeIngredient\">tomatoes</span>"
            + "<span itemprop=\"recipeInstructions\">Simmer.</span>"
            + "</div></body></html>";

    Optional<ParsedRecipe> result = service().extract(new ExtractionInput.FromHtml("u", html));

    assertThat(result).isPresent();
    assertThat(result.get().name()).isEqualTo("Tomato Soup");
    assertThat(result.get().provenance().winningLayer()).isEqualTo(ExtractionLayer.MICRODATA);
    assertThat(result.get().provenance().layerDetail()).isEqualTo("microdata");
    assertThat(result.get().provenance().layersTried())
        .containsExactly(ExtractionLayer.JSON_LD, ExtractionLayer.MICRODATA);
  }

  @Test
  void extract_commonSelectors_winAtLayer2_withDetail() {
    String html =
        "<html><body><h1>Pancakes</h1>"
            + "<ul class=\"ingredients\"><li>flour</li></ul>"
            + "<ul class=\"method\"><li>Mix.</li></ul></body></html>";

    Optional<ParsedRecipe> result = service().extract(new ExtractionInput.FromHtml("u", html));

    assertThat(result).isPresent();
    assertThat(result.get().provenance().winningLayer()).isEqualTo(ExtractionLayer.MICRODATA);
    assertThat(result.get().provenance().layerDetail()).isEqualTo("common_selectors");
  }

  // ---------------- Layer 5: validator gate ----------------

  @Test
  void extract_jsonLdMissingIngredients_failsValidatorAndFallsThrough() {
    // A JSON-LD Recipe with no ingredients fails the Layer 5 gate; no microdata either → empty.
    String html =
        jsonLdPage("{\"@type\":\"Recipe\",\"name\":\"X\",\"recipeInstructions\":[\"Boil.\"]}");

    Optional<ParsedRecipe> result = service().extract(new ExtractionInput.FromHtml("u", html));

    assertThat(result).isEmpty();
  }

  @Test
  void extract_noRecipeAnywhere_returnsEmpty() {
    String html = "<html><body><h1>About</h1><p>no recipe</p></body></html>";
    assertThat(service().extract(new ExtractionInput.FromHtml("u", html))).isEmpty();
  }

  // ---------------- input-mode handling ----------------

  @Test
  void extract_nullInput_returnsEmpty() {
    assertThat(service().extract(null)).isEmpty();
  }

  @Test
  void extract_blankHtml_returnsEmpty() {
    assertThat(service().extract(new ExtractionInput.FromHtml("u", "   "))).isEmpty();
  }

  @Test
  void extract_fromUrl_hasNoBody_returnsEmpty() {
    // v1 routes live consumers through FromHtml; a bare FromUrl carries no body for the stack.
    assertThat(service().extract(new ExtractionInput.FromUrl("https://x/r"))).isEmpty();
  }

  // ---------------- Layer 3: per-site registry ----------------

  @Test
  void extract_perSitePreExtract_shortCircuitsLayers1And2() {
    RecipeSiteExtractor preExtractor =
        new RecipeSiteExtractor() {
          @Override
          public String domainPattern() {
            return "example.com";
          }

          @Override
          public Optional<ParsedRecipe> extractRaw(String url, String html) {
            return Optional.of(
                new ParsedRecipe(
                    url,
                    "Per-Site Override",
                    null,
                    List.of(ParsedRecipe.ParsedIngredient.ofLine("special")),
                    List.of(new ParsedRecipe.ParsedMethodStep(1, "do it")),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null));
          }
        };
    // JSON-LD present on the page, but the matching per-site extractor wins first.
    String html =
        jsonLdPage(
            "{\"@type\":\"Recipe\",\"name\":\"Ignored\",\"recipeIngredient\":[\"x\"],"
                + "\"recipeInstructions\":[\"y\"]}");

    Optional<ParsedRecipe> result =
        service(preExtractor).extract(new ExtractionInput.FromHtml("https://example.com/r", html));

    assertThat(result).isPresent();
    assertThat(result.get().name()).isEqualTo("Per-Site Override");
    assertThat(result.get().provenance().winningLayer()).isEqualTo(ExtractionLayer.PER_SITE);
  }

  @Test
  void extract_perSitePostProcess_cleansLayer1Result() {
    RecipeSiteExtractor postProcessor =
        new RecipeSiteExtractor() {
          @Override
          public String domainPattern() {
            return "example.com";
          }

          @Override
          public ParsedRecipe postProcess(ParsedRecipe extracted, String url, String html) {
            return new ParsedRecipe(
                extracted.sourceUrl(),
                extracted.name().replace("BBC Good Food - ", ""),
                extracted.description(),
                extracted.ingredients(),
                extracted.methodSteps(),
                extracted.prepTimeMinutes(),
                extracted.cookTimeMinutes(),
                extracted.totalTimeMinutes(),
                extracted.servings(),
                extracted.cuisine(),
                extracted.provenance());
          }
        };
    String html =
        jsonLdPage(
            "{\"@type\":\"Recipe\",\"name\":\"BBC Good Food - Stew\","
                + "\"recipeIngredient\":[\"beef\"],\"recipeInstructions\":[\"cook\"]}");

    Optional<ParsedRecipe> result =
        service(postProcessor).extract(new ExtractionInput.FromHtml("https://example.com/r", html));

    assertThat(result).isPresent();
    // post-processed name; still a JSON-LD win.
    assertThat(result.get().name()).isEqualTo("Stew");
    assertThat(result.get().provenance().winningLayer()).isEqualTo(ExtractionLayer.JSON_LD);
  }

  @Test
  void extract_perSiteNonMatchingDomain_isNotApplied() {
    RecipeSiteExtractor other =
        new RecipeSiteExtractor() {
          @Override
          public String domainPattern() {
            return "other-site.com";
          }

          @Override
          public Optional<ParsedRecipe> extractRaw(String url, String html) {
            throw new AssertionError("must not be called for a non-matching domain");
          }
        };
    String html =
        jsonLdPage(
            "{\"@type\":\"Recipe\",\"name\":\"Plain\",\"recipeIngredient\":[\"x\"],"
                + "\"recipeInstructions\":[\"y\"]}");

    Optional<ParsedRecipe> result =
        service(other).extract(new ExtractionInput.FromHtml("https://example.com/r", html));

    assertThat(result).isPresent();
    assertThat(result.get().name()).isEqualTo("Plain");
  }

  // ---------------- JSON-LD-only entry point (discovery consumer) ----------------

  @Test
  void extractJsonLdOnly_returnsRecipeEvenWithEmptyIngredients() {
    // The discovery contract: a Recipe missing ingredients is still returned (no validator gate).
    String html =
        jsonLdPage("{\"@type\":\"Recipe\",\"name\":\"Bare\",\"recipeInstructions\":[\"step\"]}");

    Optional<ParsedRecipe> result =
        service().extractJsonLdOnly(new ExtractionInput.FromHtml("u", html));

    assertThat(result).isPresent();
    assertThat(result.get().name()).isEqualTo("Bare");
    assertThat(result.get().ingredients()).isEmpty();
    assertThat(result.get().provenance().winningLayer()).isEqualTo(ExtractionLayer.JSON_LD);
  }

  @Test
  void extractJsonLdOnly_doesNotFallBackToMicrodata() {
    // Microdata present but no JSON-LD → JSON-LD-only entry point returns empty (no Layer-2
    // fallback).
    String html =
        "<html><body><div itemscope itemtype=\"https://schema.org/Recipe\">"
            + "<h1 itemprop=\"name\">Micro</h1>"
            + "<span itemprop=\"recipeIngredient\">x</span>"
            + "<span itemprop=\"recipeInstructions\">y</span></div></body></html>";

    assertThat(service().extractJsonLdOnly(new ExtractionInput.FromHtml("u", html))).isEmpty();
  }

  @Test
  void extractJsonLdOnly_nullAndBlank_returnEmpty() {
    assertThat(service().extractJsonLdOnly(null)).isEmpty();
    assertThat(service().extractJsonLdOnly(new ExtractionInput.FromHtml("u", "  "))).isEmpty();
    assertThat(service().extractJsonLdOnly(new ExtractionInput.FromUrl("u"))).isEmpty();
  }

  // ---------------- ExtractionValidator direct ----------------

  @Test
  void validator_rejectsNullBlankNameEmptyLists() {
    ExtractionValidator validator = new ExtractionValidator();
    assertThat(validator.isSufficient(null)).isFalse();
    ParsedRecipe noName =
        new ParsedRecipe(
            "u",
            "  ",
            null,
            List.of(ParsedRecipe.ParsedIngredient.ofLine("a")),
            List.of(new ParsedRecipe.ParsedMethodStep(1, "b")),
            null,
            null,
            null,
            null,
            null,
            null);
    assertThat(validator.isSufficient(noName)).isFalse();
    ParsedRecipe noIngredients =
        new ParsedRecipe(
            "u",
            "Name",
            null,
            List.of(),
            List.of(new ParsedRecipe.ParsedMethodStep(1, "b")),
            null,
            null,
            null,
            null,
            null,
            null);
    assertThat(validator.isSufficient(noIngredients)).isFalse();
    ParsedRecipe ok =
        new ParsedRecipe(
            "u",
            "Name",
            null,
            List.of(ParsedRecipe.ParsedIngredient.ofLine("a")),
            List.of(new ParsedRecipe.ParsedMethodStep(1, "b")),
            null,
            null,
            null,
            null,
            null,
            null);
    assertThat(validator.isSufficient(ok)).isTrue();
  }
}
