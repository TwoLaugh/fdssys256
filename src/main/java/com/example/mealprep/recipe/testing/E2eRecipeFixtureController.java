package com.example.mealprep.recipe.testing;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * E2E-only HTTP control plane that serves a HERMETIC recipe web page for the recipe URL-import
 * flow.
 *
 * <p><b>Why this exists.</b> The URL-import path (RCP-03, XJ-01) does a REAL HTTP fetch ({@code
 * UrlFetcher}) followed by REAL deterministic JSON-LD extraction ({@code HtmlImportParser}) — it is
 * NOT routed through the AI double. So a scenario that imports "from a reachable recipe URL" needs
 * an actually-fetchable web page that carries a complete schema.org Recipe. Pointing at a live
 * public recipe site would make the suite non-deterministic and network-dependent; instead this
 * controller serves that page FROM THE APP ITSELF. The e2e step targets {@code
 * http://localhost:8080/test-support/recipe/fixtures/<slug>}, and {@code UrlFetcher} (a plain
 * {@code RestClient} GET with no host allowlist) fetches the app's own endpoint over loopback. The
 * import then exercises the genuine fetch + JSON-LD-extraction + create-recipe wire-contract, end
 * to end, with zero external dependency.
 *
 * <p><b>Realistic, USDA-mappable ingredients.</b> The served Recipe uses plain, quantity-prefixed
 * whole-food ingredient lines ("200 g chicken breast", "150 g white rice", "1 tbsp olive oil", ...)
 * because a downstream cross-journey (XJ-01) derives nutrition from the imported ingredients via
 * the USDA mapper. The data is hardcoded and deterministic so every run imports the identical
 * recipe.
 *
 * <p><b>Strictly {@code e2e}-profile-gated</b> (mirrors {@link
 * com.example.mealprep.ai.testing.E2eAiStubController} and {@code E2eFeedbackSeedController}): the
 * bean and its {@code /test-support/recipe/fixtures/**} mapping do not exist under {@code
 * prod}/{@code dev}/{@code test} — the path is simply an unmapped 404 in production and is never a
 * live attack surface. It lives in {@code recipe.testing} (the sanctioned {@code ..testing..}
 * ArchUnit carve-out for e2e HTTP scaffolding) rather than {@code recipe.api}, and the {@code
 * /test-support} prefix signals it is test scaffolding, not product API surface.
 *
 * <p><b>Reachability.</b> {@code OriginFilter} fast-paths requests with no {@code X-Origin} header
 * (the e2e {@code ApiClient} sends none), so it never blocks {@code /test-support/**}. The app
 * fetching its own endpoint over loopback carries no session cookie; this GET is therefore safe to
 * keep anonymous — it only serves static fixture HTML, mutates nothing, and 404s in prod. To allow
 * the unauthenticated loopback fetch through the deny-by-default chain, {@code AuthSecurityConfig}
 * permits this read-only fixture path under the {@code e2e} profile only.
 */
@RestController
@RequestMapping("/test-support/recipe/fixtures")
@Profile("e2e")
@Tag(name = "E2E Test Support")
public class E2eRecipeFixtureController {

  /**
   * A complete, self-contained schema.org Recipe page. Realistic whole-food ingredient lines with
   * gram/tbsp quantities so the downstream USDA nutrition derive (XJ-01) has mappable inputs. The
   * JSON-LD block alone satisfies {@code HtmlImportParser}'s {@code isComplete} contract (name + ≥1
   * ingredient + ≥1 instruction); prep/cook/total/servings are present and self-consistent.
   */
  private static final String RECIPE_HTML =
      """
      <!doctype html>
      <html lang="en">
      <head>
        <meta charset="utf-8">
        <title>Hermetic E2E Recipe — Chicken &amp; Rice Bowl</title>
        <script type="application/ld+json">
        {
          "@context": "https://schema.org",
          "@type": "Recipe",
          "name": "Chicken and Rice Bowl",
          "description": "A simple high-protein chicken and rice bowl used as a deterministic e2e import fixture.",
          "recipeIngredient": [
            "200 g chicken breast",
            "150 g white rice",
            "100 g broccoli",
            "1 tbsp olive oil",
            "1 tsp salt"
          ],
          "recipeInstructions": [
            { "@type": "HowToStep", "text": "Cook the white rice in salted water until tender." },
            { "@type": "HowToStep", "text": "Pan-fry the chicken breast in olive oil until cooked through." },
            { "@type": "HowToStep", "text": "Steam the broccoli, then combine everything in a bowl and serve." }
          ],
          "prepTime": "PT10M",
          "cookTime": "PT20M",
          "totalTime": "PT30M",
          "recipeYield": 2,
          "recipeCuisine": "American"
        }
        </script>
      </head>
      <body>
        <h1 class="recipe-title">Chicken and Rice Bowl</h1>
        <ul class="ingredients">
          <li>200 g chicken breast</li>
          <li>150 g white rice</li>
          <li>100 g broccoli</li>
          <li>1 tbsp olive oil</li>
          <li>1 tsp salt</li>
        </ul>
        <ol class="method">
          <li>Cook the white rice in salted water until tender.</li>
          <li>Pan-fry the chicken breast in olive oil until cooked through.</li>
          <li>Steam the broccoli, then combine everything in a bowl and serve.</li>
        </ol>
      </body>
      </html>
      """;

  /**
   * Serve the hermetic recipe page. A single hardcoded fixture is served for every {@code slug};
   * the slug is accepted only so the URL reads as a normal recipe permalink and a later wave can
   * add slug-keyed variants without changing the route.
   *
   * @param slug ignored — the same deterministic recipe is always returned
   * @return the fixture recipe page as {@code text/html}
   */
  @GetMapping(path = "/{slug}", produces = MediaType.TEXT_HTML_VALUE)
  public String fixture(@PathVariable String slug) {
    return RECIPE_HTML;
  }
}
