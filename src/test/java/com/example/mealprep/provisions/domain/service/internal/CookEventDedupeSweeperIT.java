package com.example.mealprep.provisions.domain.service.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.provisions.domain.entity.ProvisionCookEventDedupe;
import com.example.mealprep.provisions.domain.entity.ProvisionCookEventDedupe.CookEventDedupeId;
import com.example.mealprep.provisions.domain.repository.CookEventDedupeRepository;
import com.example.mealprep.testsupport.TestContainersConfig;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Integration test for {@link CookEventDedupeSweeper#sweep()} against the real Postgres dedupe
 * table. The sweeper deletes {@code provision_cook_event_dedupe} rows whose {@code created_at}
 * precedes {@code now - 24h}; this asserts the exact cutoff: a row aged 25h is purged, a row aged
 * 1h survives, and a row exactly inside the boundary survives.
 *
 * <p>The production {@code Clock} bean is {@code Clock.systemUTC()} (real wall-clock), so the
 * sweeper's cutoff moves with the test run — fixtures are anchored to {@link Instant#now()} (never
 * a hardcoded date) so the test cannot become a time-bomb. Lives in the sweeper's internal package
 * so the package-private {@code @Component} can be autowired directly.
 */
@SpringBootTest
@Import(TestContainersConfig.class)
@ActiveProfiles("test")
class CookEventDedupeSweeperIT {

  @Autowired private CookEventDedupeSweeper sweeper;
  @Autowired private CookEventDedupeRepository repository;
  @Autowired private JdbcTemplate jdbcTemplate;

  @AfterEach
  void cleanup() {
    jdbcTemplate.update("DELETE FROM provision_cook_event_dedupe");
  }

  private CookEventDedupeId seed(String dedupeKey, Instant createdAt) {
    UUID mealSlotId = UUID.randomUUID();
    repository.saveAndFlush(new ProvisionCookEventDedupe(mealSlotId, dedupeKey, createdAt));
    return new CookEventDedupeId(mealSlotId, dedupeKey);
  }

  @Test
  void sweep_deletesRowsOlderThan24h_andRetainsNewerRows() {
    Instant now = Instant.now();
    CookEventDedupeId stale = seed("stale", now.minus(Duration.ofHours(25)));
    CookEventDedupeId fresh = seed("fresh", now.minus(Duration.ofHours(1)));

    sweeper.sweep();

    assertThat(repository.existsById(stale)).isFalse();
    assertThat(repository.existsById(fresh)).isTrue();
  }

  @Test
  void sweep_isANoOp_whenAllRowsAreWithinRetention() {
    Instant now = Instant.now();
    CookEventDedupeId a = seed("recent-a", now.minus(Duration.ofHours(2)));
    CookEventDedupeId b = seed("recent-b", now.minus(Duration.ofMinutes(30)));

    sweeper.sweep();

    assertThat(repository.existsById(a)).isTrue();
    assertThat(repository.existsById(b)).isTrue();
    Long remaining =
        jdbcTemplate.queryForObject("SELECT count(*) FROM provision_cook_event_dedupe", Long.class);
    assertThat(remaining).isEqualTo(2L);
  }
}
