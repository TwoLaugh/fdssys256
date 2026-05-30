package com.example.mealprep.recipe;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.discovery.api.dto.ParsedRecipe;
import com.example.mealprep.discovery.source.internal.JsonLdRecipeExtractor;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Opt-in LIVE smoke test — EXCLUDED from the blocking gate.
 *
 * <p>This is the "go hit the real site" check for manual / nightly use. It actually fetches a live
 * BBC Good Food recipe URL over the network and runs the fetched markup through the same shared
 * extraction engine the production import path uses ({@link JsonLdRecipeExtractor} over {@code
 * RecipeExtractionService}), asserting a real recipe comes out.
 *
 * <p><b>Why it is gate-excluded.</b> It is tagged {@link Tag @Tag("live")}, and the Surefire /
 * Failsafe configuration in {@code pom.xml} sets {@code
 * <excludedGroups>${test.excludedGroups}</excludedGroups>} (default {@code live}), so a plain
 * {@code ./mvnw test} / {@code ./mvnw verify} never runs it — it would be flaky (the site changes
 * its markup, can be down, or can bot-block our user agent). The deterministic regression lock is
 * {@code RealRecipeSiteCaptureTest}, which uses a stored capture of this same page.
 *
 * <p><b>How to run it on demand:</b>
 *
 * <pre>{@code ./mvnw test -Dgroups=live -Dtest.excludedGroups=}</pre>
 *
 * ({@code -Dgroups=live} selects the group; {@code -Dtest.excludedGroups=} clears the default
 * exclude property the Surefire config binds to — JUnit excludes a tag that is in both the include
 * and exclude sets, so the exclude must be cleared). Network access to {@code www.bbcgoodfood.com}
 * is required; the test self-skips via an assumption if the live fetch fails so a manual run on a
 * blocked network does not hard-fail.
 */
@Tag("live")
class RealRecipeSiteLiveSmokeTest {

  private static final String LIVE_URL = "https://www.bbcgoodfood.com/recipes/classic-lasagne";
  private static final String USER_AGENT =
      "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko)"
          + " Chrome/124.0 Safari/537.36";

  private final JsonLdRecipeExtractor extractor = new JsonLdRecipeExtractor(new ObjectMapper());

  @Test
  void liveFetch_realBbcGoodFoodRecipe_extractsARecipe() throws Exception {
    String html = fetchLive(LIVE_URL);
    org.junit.jupiter.api.Assumptions.assumeTrue(
        html != null && !html.isBlank(),
        "live BBC Good Food fetch returned no body (network blocked / site down) — skipping smoke");

    Optional<ParsedRecipe> result = extractor.extract(html, LIVE_URL);

    assertThat(result).as("live BBC Good Food page must yield a JSON-LD recipe").isPresent();
    ParsedRecipe recipe = result.get();
    assertThat(recipe.name()).isNotBlank();
    assertThat(recipe.ingredients()).isNotEmpty();
    assertThat(recipe.ingredients())
        .allSatisfy(i -> assertThat(i.ingredientMappingKey()).isNotBlank());
    assertThat(recipe.method()).isNotEmpty();
    assertThat(recipe.extractionMethod()).isEqualTo("json_ld");
  }

  private static String fetchLive(String url) {
    try {
      HttpClient client =
          HttpClient.newBuilder()
              .followRedirects(HttpClient.Redirect.NORMAL)
              .connectTimeout(Duration.ofSeconds(15))
              .build();
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(url))
              .header("User-Agent", USER_AGENT)
              .timeout(Duration.ofSeconds(30))
              .GET()
              .build();
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        return null;
      }
      return response.body();
    } catch (Exception e) {
      // Network error / timeout / blocked — return null so the test's assumption self-skips.
      return null;
    }
  }
}
