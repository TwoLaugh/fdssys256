package com.example.mealprep.e2e.steps;

import com.example.mealprep.e2e.support.E2eConfig;
import com.example.mealprep.e2e.support.ScenarioContext;
import io.cucumber.java.After;
import io.cucumber.java.Scenario;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The toggleable cleanup hook — the clean-vs-soak switch (decision D5).
 *
 * <p><b>Clean mode</b> (default, {@code MEALPREP_E2E_CLEANUP} unset or {@code true}): this
 * {@code @After} tears down the scenario's session so each scenario starts from a clean auth state
 * — isolated regression gate.
 *
 * <p><b>Soak mode</b> ({@code MEALPREP_E2E_CLEANUP=false}): the teardown is skipped entirely, so
 * state accumulates across scenarios — emergent-bug discovery (uniqueness collisions, pagination,
 * ordering). Scenarios additionally tagged {@code @soak} ALWAYS skip teardown regardless of the
 * flag, so a soak-only scenario never self-cleans even in a clean run.
 *
 * <p>Cleanup is gated at TWO levels (flag + tag) so neither alone can accidentally leave a clean
 * regression run accumulating state, nor force a soak scenario to self-destruct.
 *
 * <p>Teardown scope today is (1) a reset of the global SYSTEM recipe catalogue and (2) session
 * invalidation (logout). Per-domain USER data teardown (delete the recipes / plans / etc. a
 * scenario created) is added as each domain's delete endpoints land; account deletion itself is an
 * HLD-GAP (AUTH-18), so a registered user is intentionally NOT hard-deleted here — every scenario
 * uses a fresh RANDOM handle (D5 self-contained data), so leftover USER state never collides.
 *
 * <p><b>Why reset the SYSTEM catalogue (the one piece of GLOBAL state).</b> Unlike USER recipes,
 * the SYSTEM catalogue is shared across every user and has no per-user scoping to keep scenarios
 * isolated. The planner's cold-start gate latches {@code coldStart = (poolSize < threshold)} where
 * {@code poolSize} counts the caller's USER recipes plus that shared SYSTEM catalogue. A scenario
 * that generates a plan with an empty USER catalogue and no primed {@code DISCOVERY_FILTERING}
 * response trips the gate; discovery's per-candidate AI filter then dispatches against the unprimed
 * stub (which throws) and — correctly, per discovery-3 skip-and-flag — KEEPS and imports those
 * candidates into SYSTEM. Left un-reset, that import pollutes a LATER cold-start scenario (XJ-06),
 * whose {@code coldStart = true} assertion needs an EMPTY SYSTEM at gate-entry. Purging SYSTEM here
 * (a {@code DELETE /test-support/recipe/catalogue/system} on the still-authenticated session,
 * BEFORE logout) gives every clean-mode scenario a genuinely empty shared catalogue. Best-effort: a
 * purge failure is logged, never masking the scenario's own result.
 */
public class Hooks {

  private static final Logger log = LoggerFactory.getLogger(Hooks.class);

  private final ScenarioContext context;

  public Hooks(ScenarioContext context) {
    this.context = context;
  }

  @After(order = 1000)
  public void teardown(Scenario scenario) {
    boolean soakScenario = scenario.getSourceTagNames().contains("@soak");
    if (!E2eConfig.cleanupEnabled() || soakScenario) {
      log.info(
          "E2E cleanup SKIPPED (mode=soak, cleanupEnabled={}, @soak={}) for scenario '{}'",
          E2eConfig.cleanupEnabled(),
          soakScenario,
          scenario.getName());
      return;
    }

    // Clean mode. Reset the cross-scenario GLOBAL state the per-user random handles can't isolate
    // (see class javadoc). Both resets ride the still-authenticated session (deny-by-default chain)
    // and run BEFORE logout. Best-effort: failures are logged, never propagated, so they can't mask
    // the scenario's own result.
    //
    // Two coupled pieces of global state must BOTH be reset for the next scenario's cold-start to
    // re-import the deterministic seeds cleanly:
    //   1. SYSTEM recipes — clears the recipe-side import-fingerprint dedup (recipe_imports
    // cascades
    //      off recipe_recipes), and empties the shared catalogue the cold-start gate counts.
    //   2. discovery jobs — cascades discovery_scrape_log, clearing the discovery-side
    //      content-fingerprint dedup window (else the seeds look like DUPLICATEs and nothing
    // ingests).
    try {
      context.api().request().when().delete("/test-support/recipe/catalogue/system");
      log.info("E2E cleanup: SYSTEM recipe catalogue reset for scenario '{}'", scenario.getName());
    } catch (RuntimeException ex) {
      log.warn(
          "E2E cleanup SYSTEM-catalogue reset failed for scenario '{}': {}",
          scenario.getName(),
          ex.toString());
    }
    try {
      context.api().request().when().delete("/test-support/discovery/jobs");
      log.info("E2E cleanup: discovery dedup memory reset for scenario '{}'", scenario.getName());
    } catch (RuntimeException ex) {
      log.warn(
          "E2E cleanup discovery reset failed for scenario '{}': {}",
          scenario.getName(),
          ex.toString());
    }

    // Invalidate the session. Logout is idempotent server-side (anonymous-accessible), so this is
    // safe even when the scenario never logged in.
    try {
      context.api().request().when().post("/api/v1/auth/logout");
      log.info("E2E cleanup DONE (session invalidated) for scenario '{}'", scenario.getName());
    } catch (RuntimeException ex) {
      // Never let teardown mask the scenario's own result — log and move on.
      log.warn(
          "E2E cleanup logout failed for scenario '{}': {}", scenario.getName(), ex.toString());
    }
  }
}
