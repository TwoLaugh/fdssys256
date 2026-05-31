package com.example.mealprep.discovery.testing;

import com.example.mealprep.discovery.domain.repository.DiscoveryJobRepository;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * E2E-only HTTP control plane for resetting the discovery module's cross-scenario dedup memory.
 *
 * <p><b>Why this exists.</b> The discovery runner short-circuits a candidate as a {@code DUPLICATE}
 * when its content fingerprint already appears in {@code discovery_scrape_log} within the dedup
 * lookback window (default 30 days — see {@code DiscoveryJobRunner} fetch phase / {@code
 * DiscoveryScrapeLogRepository.existsByContentFingerprintAndOccurredAtAfter}). The scrape-log table
 * is append-only and its rows survive the per-scenario recipe-catalogue reset (they reference
 * {@code discovery_jobs}, not recipes). So once ANY scenario's cold-start fill ingests the
 * deterministic {@code e2e_curated_seed} recipes, every LATER scenario's cold-start sees those
 * fingerprints as duplicates and discovery ingests NOTHING — the cold-start gate still fires (pool
 * below threshold) but the re-read pool stays empty, so a cold-start scenario (XJ-06) produces a
 * plan with no scheduled recipes.
 *
 * <p>Resetting the catalogue alone is therefore not enough; the discovery dedup memory must also be
 * cleared so the deterministic seeds re-import cleanly each scenario. {@code Hooks} (clean-mode
 * {@code @After}) calls this alongside the recipe-catalogue reset. In production the scrape-log
 * dedup is correct (a recipe genuinely already in the catalogue should not be re-imported); the
 * reset is purely E2E test isolation against the shared DB.
 *
 * <p><b>What it deletes.</b> Every {@code discovery_jobs} row. {@code discovery_scrape_log.job_id}
 * references {@code discovery_jobs(id)} {@code ON DELETE CASCADE}, so a single delete sweeps the
 * scrape-log audit (and its fingerprint dedup window) too. The {@code discovery_sources} rows — the
 * enabled {@code e2e_curated_seed} source the cold-start gate resolves — are NOT touched, so the
 * next cold-start still finds its source.
 *
 * <p><b>Strictly {@code e2e}-profile-gated</b> (mirrors {@link E2eDiscoverySourceSeeder} and the
 * other {@code <module>.testing} e2e scaffolding): the bean and its {@code
 * /test-support/discovery/jobs} mapping do not exist under {@code prod}/{@code dev}/{@code test} —
 * an unmapped 404 in prod, never a live attack surface. Lives in {@code discovery.testing}; because
 * that package is inside {@code com.example.mealprep.discovery..} it may inject {@code
 * DiscoveryJobRepository} directly per {@code DiscoveryBoundaryTest} (the rule only forbids
 * repository access from OUTSIDE the discovery module — exactly as {@link E2eDiscoverySourceSeeder}
 * injects {@code DiscoverySourceRepository}).
 *
 * <p><b>Reachability / security.</b> Same as the sibling test-support controllers: {@code
 * OriginFilter} fast-paths requests with no {@code X-Origin} header (the e2e client sends none),
 * and the call rides the scenario's authenticated session, satisfying the deny-by-default auth
 * chain.
 */
@RestController
@RequestMapping("/test-support/discovery")
@Profile("e2e")
@Tag(name = "E2E Test Support")
public class E2eDiscoveryResetController {

  private static final Logger log = LoggerFactory.getLogger(E2eDiscoveryResetController.class);

  private final DiscoveryJobRepository jobRepository;

  public E2eDiscoveryResetController(DiscoveryJobRepository jobRepository) {
    this.jobRepository = jobRepository;
  }

  /**
   * Hard-delete every discovery job (and, via {@code ON DELETE CASCADE}, its {@code
   * discovery_scrape_log} rows — clearing the content-fingerprint dedup window). Returns the number
   * of jobs purged. Idempotent — a second call returns 0.
   *
   * @return {@code {"purged": <count>}} — the number of discovery jobs deleted
   */
  @DeleteMapping(path = "/jobs", produces = MediaType.APPLICATION_JSON_VALUE)
  @Transactional
  public PurgeResult purgeJobs() {
    int purged = jobRepository.deleteAllJobs();
    log.info("E2E discovery reset: purged {} discovery job(s) + cascaded scrape log", purged);
    return new PurgeResult(purged);
  }

  /** Response body for {@link #purgeJobs()}. */
  public record PurgeResult(int purged) {}
}
