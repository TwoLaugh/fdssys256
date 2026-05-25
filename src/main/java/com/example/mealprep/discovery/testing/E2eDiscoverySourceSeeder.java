package com.example.mealprep.discovery.testing;

import com.example.mealprep.discovery.domain.entity.DiscoverySource;
import com.example.mealprep.discovery.domain.entity.DiscoverySourceKind;
import com.example.mealprep.discovery.domain.entity.DiscoverySourceType;
import com.example.mealprep.discovery.domain.repository.DiscoverySourceRepository;
import com.example.mealprep.discovery.source.E2eSeedDiscoverySource;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Upserts the {@code e2e_curated_seed} {@code discovery_sources} row at boot under the {@code e2e}
 * profile, so the planner's cold-start gate (pinned to that source key via {@code
 * mealprep.planner.cold-start.source-keys}) resolves an enabled DB row to run {@link
 * E2eSeedDiscoverySource}.
 *
 * <p>This is the DB-row half of the deterministic cold-start seam (the bean half is {@link
 * E2eSeedDiscoverySource}). It is profile-scoped to {@code e2e} and never loads in prod/dev/test —
 * the same convention as the other {@code <module>.testing} e2e seeders ({@code
 * E2ePreferenceSeedController}, etc.). The shared {@code R__} curated-source seed is left
 * untouched; the real web/Google rows stay enabled but the gate simply never asks for them under
 * e2e.
 *
 * <p>{@code ApplicationReadyEvent} (not {@code @PostConstruct}) ensures Flyway has finished and the
 * datasource is live before the write.
 */
@Component
@Profile("e2e")
public class E2eDiscoverySourceSeeder {

  private static final Logger log = LoggerFactory.getLogger(E2eDiscoverySourceSeeder.class);

  private final DiscoverySourceRepository sourceRepository;

  public E2eDiscoverySourceSeeder(DiscoverySourceRepository sourceRepository) {
    this.sourceRepository = sourceRepository;
  }

  @EventListener(ApplicationReadyEvent.class)
  @Transactional
  public void seed() {
    if (sourceRepository.findBySourceKey(E2eSeedDiscoverySource.KEY).isPresent()) {
      log.info(
          "e2e discovery seed source '{}' already present; skipping upsert",
          E2eSeedDiscoverySource.KEY);
      return;
    }
    DiscoverySource row =
        DiscoverySource.builder()
            .id(UUID.randomUUID())
            .sourceKey(E2eSeedDiscoverySource.KEY)
            .displayName("E2E Curated Seed")
            .sourceType(DiscoverySourceType.CURATED)
            .kind(DiscoverySourceKind.SITEMAP)
            .baseUrl("https://e2e.seed.local")
            .enabled(true)
            .userDisabled(false)
            .requestsPerMinute(600)
            .requestsPerDay(100_000)
            .respectRobotsTxt(false)
            .userAgent("MealPrepAI-E2E/1.0")
            .crawlConfig(null)
            .failureStreak(0)
            .build();
    sourceRepository.saveAndFlush(row);
    log.info(
        "seeded e2e discovery source '{}' (enabled) for cold-start determinism",
        E2eSeedDiscoverySource.KEY);
  }
}
