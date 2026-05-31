package com.example.mealprep.recipe.testing;

import com.example.mealprep.recipe.domain.entity.Catalogue;
import com.example.mealprep.recipe.domain.repository.RecipeRepository;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * E2E-only HTTP control plane for resetting the global SYSTEM recipe catalogue between scenarios.
 *
 * <p><b>Why this exists.</b> The SYSTEM catalogue is genuinely global and shared across every user
 * (SYSTEM rows carry the nil-UUID sentinel owner and are visible to all callers — see {@code
 * RecipeServiceImpl} / {@code RecipeRepository.findPlannableForUser}). The planner's Tier-2
 * cold-start gate ({@code ColdStartGate.fillIfCold}) latches {@code coldStart = (currentPoolSize <
 * threshold)} where {@code currentPoolSize} counts the caller's USER recipes <b>plus</b> that
 * shared SYSTEM catalogue. Any scenario that generates a plan with an empty USER catalogue and does
 * NOT prime a {@code DISCOVERY_FILTERING} AI response trips the gate; the per-candidate AI filter
 * then dispatches against the unprimed stub (which throws), and — correctly, per discovery-3
 * skip-and-flag — the candidates are KEPT and imported into the SYSTEM catalogue. Those imports
 * persist in the shared DB across scenarios, so a LATER cold-start scenario (XJ-06) sees a
 * non-empty SYSTEM pool, the gate does not fire, and its {@code coldStart = true} assertion fails.
 *
 * <p>The E2E suite's data-isolation contract (decision D5: self-contained data, self-scoped
 * assertions) is satisfied for per-USER state by every scenario minting a fresh random handle — but
 * the SYSTEM catalogue has no per-user scoping to lean on, so it needs an explicit cross-scenario
 * reset. This controller is the seam {@code Hooks} (clean-mode {@code @After}) calls to purge it,
 * so every scenario starts from a genuinely empty SYSTEM catalogue and the cold-start gate's
 * pool-size-at-entry reflects only what THAT scenario set up. In production the shared SYSTEM
 * catalogue is correct (only the first cold-start user bootstraps it); the reset is purely test
 * isolation and never weakens the assertion.
 *
 * <p><b>Strictly {@code e2e}-profile-gated</b> (mirrors {@link E2eRecipeFixtureController} / {@code
 * E2eAiStubController}): the bean and its {@code /test-support/recipe/catalogue/system} mappings do
 * not exist under {@code prod}/{@code dev}/{@code test} — the path is an unmapped 404 in production
 * and is never a live attack surface. Lives in {@code recipe.testing} (the sanctioned {@code
 * ..testing..} ArchUnit carve-out for e2e HTTP scaffolding); because that package is inside {@code
 * com.example.mealprep.recipe..} it may inject {@code RecipeRepository} directly per {@code
 * RecipeBoundaryTest} (the rule only forbids repository access from OUTSIDE the recipe module).
 *
 * <p><b>Reachability / security.</b> Same as the sibling test-support controllers: {@code
 * OriginFilter} fast-paths requests with no {@code X-Origin} header (the e2e {@code ApiClient}
 * sends none), and the call is made on the scenario's authenticated session, satisfying {@code
 * AuthSecurityConfig}'s deny-by-default chain. No security or origin change is needed.
 */
@RestController
@RequestMapping("/test-support/recipe/catalogue")
@Profile("e2e")
@Tag(name = "E2E Test Support")
public class E2eRecipeCatalogueController {

  private static final Logger log = LoggerFactory.getLogger(E2eRecipeCatalogueController.class);

  private final RecipeRepository recipeRepository;

  public E2eRecipeCatalogueController(RecipeRepository recipeRepository) {
    this.recipeRepository = recipeRepository;
  }

  /**
   * Hard-delete every SYSTEM-catalogue recipe (and, via {@code ON DELETE CASCADE}, its versions /
   * branches / ingredients / method / metadata / tags / imports / substitutions / ratings). Returns
   * the number of recipe roots purged so a caller can log/inspect it. Idempotent — a second call
   * returns 0.
   *
   * @return {@code {"purged": <count>}} — the number of SYSTEM recipe roots deleted
   */
  @DeleteMapping(path = "/system", produces = MediaType.APPLICATION_JSON_VALUE)
  @Transactional
  public PurgeResult purgeSystemCatalogue() {
    int purged = recipeRepository.deleteAllSystemCatalogue();
    log.info("E2E SYSTEM-catalogue reset: purged {} system recipe(s)", purged);
    return new PurgeResult(purged);
  }

  /**
   * Count of SYSTEM-catalogue recipe rows currently present. Lets a scenario / the cleanup hook
   * assert the global catalogue is empty at gate-entry (so XJ-06's cold-start gate fires for the
   * RIGHT reason — an empty SYSTEM, not a tolerant threshold).
   *
   * @return {@code {"count": <count>}} — the number of SYSTEM recipe rows
   */
  @GetMapping(path = "/system/count", produces = MediaType.APPLICATION_JSON_VALUE)
  public CountResult systemCatalogueCount() {
    return new CountResult(recipeRepository.countByCatalogue(Catalogue.SYSTEM));
  }

  /** Response body for {@link #purgeSystemCatalogue()}. */
  public record PurgeResult(int purged) {}

  /** Response body for {@link #systemCatalogueCount()}. */
  public record CountResult(long count) {}
}
