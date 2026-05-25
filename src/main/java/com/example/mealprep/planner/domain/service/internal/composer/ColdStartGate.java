package com.example.mealprep.planner.domain.service.internal.composer;

import com.example.mealprep.core.types.SlotKind;
import com.example.mealprep.discovery.api.dto.DiscoveryConstraints;
import com.example.mealprep.discovery.api.dto.DiscoveryJobDto;
import com.example.mealprep.discovery.api.dto.StartDiscoveryJobRequest;
import com.example.mealprep.discovery.domain.entity.DiscoveryJobTrigger;
import com.example.mealprep.discovery.domain.service.DiscoveryService;
import com.example.mealprep.planner.api.dto.MealSlotSkeleton;
import com.example.mealprep.planner.config.PlannerProperties;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Cold-start gate (planner recipe-pool Tier-2). Runs in {@link PlanComposer#compose} BEFORE Stage
 * A: a brand-new household with no USER recipes and an empty SYSTEM catalogue would otherwise get a
 * candidate-less (quality-warning) plan. When the candidate pool is below the planning minimum the
 * gate invokes the discovery module — synchronously and bounded — to fill the SYSTEM catalogue, so
 * the household's first plan has scheduled recipes.
 *
 * <p>Per meal-planner.md §Cold start (threshold "≥3× slot count"; "discovery + AI generation
 * pre-step fills the system catalogue up to ~50 USDA-mapped recipes") and lld/planner.md §Flow-1
 * step 5 ("Run cold-start gate: catalogue-size check. If below threshold, trigger RecipeDiscovery …
 * Sets coldStart = true").
 *
 * <h2>Cross-module call (sanctioned)</h2>
 *
 * The gate injects {@link DiscoveryService} — the discovery module's public update-side facade,
 * whose javadoc states it is "Injected by {@code planner} (cold-start, via {@code runJobSync})".
 * The {@code DiscoveryBoundaryTest.discoveryServiceInjectedOnlyByPlannerAndRecipe} ArchUnit rule
 * explicitly whitelists {@code com.example.mealprep.planner..} as a permitted consumer, so this is
 * an architecturally-blessed seam (no rule change needed).
 *
 * <h2>Determinism</h2>
 *
 * The sources the gate asks discovery to run are configurable via {@code
 * mealprep.planner.cold-start.source-keys}: empty (prod default) ⇒ all currently-enabled sources;
 * the {@code e2e} profile pins it to the single deterministic {@code e2e_curated_seed} source so
 * the non-deterministic web/Google scrapers never run in the E2E stack.
 *
 * <h2>Graceful degradation</h2>
 *
 * Every failure mode keeps the request alive — the gate NEVER throws to the composer:
 *
 * <ul>
 *   <li>gate disabled / pool already above threshold ⇒ no discovery call, {@code coldStart =
 *       false}.
 *   <li>{@code runJobSync} times out / fails / imports nothing ⇒ {@code coldStart} is still {@code
 *       true} (a cold-start attempt was made — the LLD flags the run, not the outcome), but the
 *       composer simply re-reads whatever the catalogue now holds; an unchanged empty pool falls
 *       through to today's empty quality-warning plan. Never a 500.
 *   <li>any {@link RuntimeException} from discovery is caught + logged WARN; {@code coldStart}
 *       reflects that an attempt was made.
 * </ul>
 */
@Component
class ColdStartGate {

  private static final Logger log = LoggerFactory.getLogger(ColdStartGate.class);

  /** Discovery constraints schema version (mirrors {@code DiscoveryConstraints} v1). */
  private static final int CONSTRAINTS_SCHEMA_VERSION = 1;

  private final DiscoveryService discoveryService;
  private final PlannerProperties properties;

  ColdStartGate(DiscoveryService discoveryService, PlannerProperties properties) {
    this.discoveryService = discoveryService;
    this.properties = properties;
  }

  /**
   * Decide whether the catalogue is cold and, if so, fill it via a bounded synchronous discovery
   * run. Returns {@code true} when a cold-start fill was attempted (the composer flags the plan
   * {@code coldStart = true} and re-reads the pool); {@code false} when the gate did not fire (pool
   * already adequate or the gate is disabled).
   *
   * @param requestUserId the resolved caller (the discovery job is attributed to them)
   * @param skeletons the resolved slot skeletons (their distinct kinds drive the threshold)
   * @param currentPoolSize the candidate count Stage-A would otherwise see
   * @param traceId the run trace id (threaded onto the discovery request)
   */
  boolean fillIfCold(
      UUID requestUserId, List<MealSlotSkeleton> skeletons, int currentPoolSize, UUID traceId) {
    PlannerProperties.ColdStart cfg = properties.coldStart();
    if (!cfg.enabled()) {
      return false;
    }

    int threshold = threshold(skeletons, cfg.slotKindMultiplier());
    if (currentPoolSize >= threshold) {
      log.debug(
          "Cold-start gate: pool {} >= threshold {} (trace {}); not cold.",
          currentPoolSize,
          threshold,
          traceId);
      return false;
    }

    log.info(
        "Cold-start gate FIRED: pool {} < threshold {} (multiplier {} × {} distinct slot-kind(s));"
            + " filling SYSTEM catalogue via discovery (trace {}).",
        currentPoolSize,
        threshold,
        cfg.slotKindMultiplier(),
        distinctKindCount(skeletons),
        traceId);

    try {
      StartDiscoveryJobRequest request =
          new StartDiscoveryJobRequest(
              DiscoveryJobTrigger.COLD_START,
              cfg.requestedCount(),
              constraints(),
              cfg.sourceKeys().isEmpty() ? null : List.copyOf(cfg.sourceKeys()),
              traceId);
      DiscoveryJobDto result = discoveryService.runJobSync(requestUserId, request, cfg.timeout());
      log.info(
          "Cold-start discovery returned status={} ingested={} (trace {}).",
          result.status(),
          result.recipesIngested(),
          traceId);
    } catch (RuntimeException ex) {
      // Degrade gracefully — a cold-start attempt was made, but discovery failed. The composer
      // re-reads the (possibly still-empty) pool and falls back to a quality-warning plan. Never
      // surface a 500 from the gate (LLD §Failure Modes).
      log.warn(
          "Cold-start discovery failed ({}: {}) for trace {}; proceeding with current catalogue.",
          ex.getClass().getSimpleName(),
          ex.getMessage(),
          traceId);
    }
    return true;
  }

  /** Threshold = {@code slotKindMultiplier × distinctSlotKinds}, floor 1 (meal-planner.md). */
  private int threshold(List<MealSlotSkeleton> skeletons, int multiplier) {
    return Math.max(1, distinctKindCount(skeletons)) * multiplier;
  }

  private int distinctKindCount(List<MealSlotSkeleton> skeletons) {
    Set<SlotKind> kinds = new LinkedHashSet<>();
    if (skeletons != null) {
      for (MealSlotSkeleton skel : skeletons) {
        if (skel != null && skel.kind() != null) {
          kinds.add(skel.kind());
        }
      }
    }
    return kinds.size();
  }

  /**
   * Minimal discovery constraints for a cold-start fill: no cuisine/meal-type narrowing (the
   * planner's downstream hard-filter does per-slot kind + time-budget filtering), no hard
   * ingredient exclusions (a fresh user's hard constraints are applied per-slot at scheduling time,
   * and the runner's own hard-constraint filter is a safety net regardless). Keeps the cold-start
   * fill broad so the SYSTEM catalogue gains variety across meal kinds.
   */
  private static DiscoveryConstraints constraints() {
    return new DiscoveryConstraints(
        CONSTRAINTS_SCHEMA_VERSION, null, null, null, null, null, null, null);
  }

  /** Test seam: the effective bounded deadline. */
  Duration effectiveTimeout() {
    return properties.coldStart().timeout();
  }
}
