package com.example.mealprep.discovery.source;

import com.example.mealprep.discovery.api.dto.DiscoveryCandidate;
import com.example.mealprep.discovery.api.dto.DiscoveryQuery;
import com.example.mealprep.discovery.api.dto.ParsedRecipe;
import com.example.mealprep.discovery.domain.entity.DiscoverySourceKind;
import com.example.mealprep.discovery.domain.service.DiscoverySource;
import com.example.mealprep.discovery.exception.ExtractionFailedException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Deterministic {@link DiscoverySource} for the {@code e2e} profile ONLY ({@code source_key =
 * e2e_curated_seed}). Yields a small, fixed, fully-parseable set of {@link ParsedRecipe}s across
 * the breakfast / lunch / dinner meal kinds — no network, no credentials, no quota — so the
 * planner's cold-start gate ({@code ColdStartGate} → {@code DiscoveryService.runJobSync}) fills the
 * SYSTEM catalogue fast and repeatably in the prod-parity docker stack.
 *
 * <h2>Why a profile-gated source, not a faked real source</h2>
 *
 * The real v1 sources ({@code ReferenceCuratedSource} = {@code bbc_good_food} web-scraper, {@code
 * GoogleCustomSearchAdapter} = {@code google_cse} key+quota API) are non-deterministic /
 * credential-dependent and would make every planner E2E run flaky + slow. This is the same
 * precedent as {@code TestAiService} (the {@code @Profile("e2e")} AI double): every external dep
 * stays real in the stack, only the genuinely non-deterministic ones get a deterministic stand-in.
 *
 * <p>The real sources' {@code discovery_sources} rows are enabled by the shared {@code R__} seed
 * under every profile, so they are NOT disabled here. Instead the cold-start gate is pinned (via
 * {@code mealprep.planner.cold-start.source-keys=e2e_curated_seed} in {@code
 * application-e2e.properties}) to request ONLY this source's key — {@code runJobSync}'s {@code
 * resolveEnabledByKey} then runs this source alone and never touches the web/Google sources. The
 * companion {@code E2eDiscoverySourceSeeder} upserts this source's enabled DB row at boot.
 *
 * <p>Lives in {@code com.example.mealprep.discovery.source..} to satisfy {@code
 * DiscoveryBoundaryTest.discoverySourceImplsLiveInSourcePackage}.
 */
@Component
@Profile("e2e")
public class E2eSeedDiscoverySource implements DiscoverySource {

  /** Stable key matching the {@code discovery_sources} row the seeder upserts. */
  public static final String KEY = "e2e_curated_seed";

  private static final Logger log = LoggerFactory.getLogger(E2eSeedDiscoverySource.class);

  // High extraction confidence so the runner's low-confidence guard (< 0.5) never trips.
  private static final BigDecimal CONFIDENCE = new BigDecimal("0.95");

  /**
   * The deterministic seed set. Six recipes per meal kind (breakfast / lunch / dinner) = 18, which
   * clears the cold-start threshold (3 × distinct-slot-kinds) for any realistic week (max 3 main
   * kinds ⇒ threshold 9; +snack ⇒ 12). Each has a stable canonical URL + a per-recipe-unique method
   * instruction so the runner's content-fingerprint dedup is idempotent across re-runs and never
   * collapses distinct seeds. Each carries a small ingredient list (see {@link
   * Seed#toParsedRecipe}) — discovery-1 fixed the ingest path so ingredient-bearing recipes now
   * persist with normalised {@code ingredient_mapping_key}s.
   */
  private static final List<Seed> SEEDS = buildSeeds();

  @Override
  public String key() {
    return KEY;
  }

  @Override
  public DiscoverySourceKind kind() {
    return DiscoverySourceKind.SITEMAP;
  }

  @Override
  public List<DiscoveryCandidate> search(DiscoveryQuery query) {
    int cap = Math.max(1, query.maxResults());
    List<DiscoveryCandidate> out = new ArrayList<>();
    for (Seed seed : SEEDS) {
      if (out.size() >= cap) {
        break;
      }
      out.add(
          new DiscoveryCandidate(
              KEY, seed.url(), seed.name(), "deterministic e2e seed recipe", Map.of()));
    }
    log.debug("e2e_curated_seed search returning {} candidate(s)", out.size());
    return out;
  }

  @Override
  public ParsedRecipe fetchRecipe(DiscoveryCandidate candidate) {
    Seed seed =
        SEEDS.stream()
            .filter(s -> s.url().equals(candidate.candidateUrl()))
            .findFirst()
            .orElseThrow(
                () -> new ExtractionFailedException(candidate.candidateUrl(), "unknown e2e seed"));
    return seed.toParsedRecipe();
  }

  private static List<Seed> buildSeeds() {
    List<Seed> seeds = new ArrayList<>();
    addKind(seeds, "breakfast", "Breakfast");
    addKind(seeds, "lunch", "Lunch");
    addKind(seeds, "dinner", "Dinner");
    return List.copyOf(seeds);
  }

  private static void addKind(List<Seed> seeds, String mealType, String label) {
    // Six per kind. Names + URLs are deterministic so re-runs dedup-by-fingerprint to the same
    // SYSTEM rows.
    for (int i = 0; i < 6; i++) {
      String name = "E2E " + label + " " + (i + 1);
      String url = "https://e2e.seed.local/" + mealType + "/" + (i + 1);
      seeds.add(new Seed(url, name, mealType));
    }
  }

  /** A single deterministic recipe definition. */
  private record Seed(String url, String name, String mealType) {

    ParsedRecipe toParsedRecipe() {
      // discovery-1 (fixed): the discovery→recipe import path now carries a normalised
      // ingredient_mapping_key through every hop (extractor → runner → RecipeServiceImpl), so an
      // imported recipe with ingredients persists cleanly against the NOT-NULL column. These seeds
      // are deliberately ingredient-bearing so the prod-parity e2e stack exercises that path.
      // Each ParsedIngredient supplies an explicit normalised key; the runner re-normalises and
      // falls back to normalise(displayName) defensively. The per-recipe-unique method instruction
      // (qualified by the recipe name) keeps the content fingerprint distinct so each seed imports
      // rather than dedup-collapsing.
      List<ParsedRecipe.ParsedIngredient> ingredients =
          List.of(
              new ParsedRecipe.ParsedIngredient(
                  "Olive oil", "olive oil", new BigDecimal("1"), "tbsp", null, false),
              new ParsedRecipe.ParsedIngredient(
                  "Sea salt", "sea salt", new BigDecimal("1"), "pinch", null, true));
      List<ParsedRecipe.ParsedMethodStep> method =
          List.of(new ParsedRecipe.ParsedMethodStep(1, "Prepare and serve " + name + ".", 25));
      ParsedRecipe.ParsedRecipeMetadata metadata =
          new ParsedRecipe.ParsedRecipeMetadata(
              2, 10, 15, 25, List.of(), "International", List.of(mealType));
      return new ParsedRecipe(
          url,
          name,
          "Deterministic e2e seed recipe.",
          ingredients,
          method,
          metadata,
          "e2e-seed",
          CONFIDENCE);
    }
  }
}
