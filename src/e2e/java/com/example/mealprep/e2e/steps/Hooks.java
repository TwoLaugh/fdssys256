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
 * <p>Teardown scope today is session invalidation (logout). Per-domain data teardown (delete the
 * recipes / plans / etc. a scenario created) is added as each domain's delete endpoints land;
 * account deletion itself is an HLD-GAP (AUTH-18), so a registered user is intentionally NOT
 * hard-deleted here — every scenario uses a fresh RANDOM handle (D5 self-contained data), so
 * leftover users never collide.
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

    // Clean mode: invalidate the session. Logout is idempotent server-side (anonymous-accessible),
    // so this is safe even when the scenario never logged in.
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
