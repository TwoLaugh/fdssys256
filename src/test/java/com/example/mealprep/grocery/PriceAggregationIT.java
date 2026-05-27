package com.example.mealprep.grocery;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.grocery.api.dto.PriceAggregateDto;
import com.example.mealprep.grocery.api.dto.RecordManualPriceRequest;
import com.example.mealprep.grocery.domain.service.PriceHistoryService;
import com.example.mealprep.testsupport.TestContainersConfig;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Service-level aggregation behaviour against real Postgres (Testcontainers): weighted mean across
 * real rows, staleness from real timestamps, the {@code getAggregatesByKeys} batch, the bundled
 * reference-snapshot cold-start fallback, and the true-cold-start empty. Complements {@link
 * PriceHistoryControllerIT} (which covers the HTTP layer). Single-user mode → {@code householdId ==
 * userId} (matches the controller's scope resolution).
 */
@SpringBootTest
@Import(TestContainersConfig.class)
@ActiveProfiles("test")
class PriceAggregationIT {

  @Autowired private PriceHistoryService priceHistory;
  @Autowired private JdbcTemplate jdbcTemplate;

  @AfterEach
  void cleanup() {
    jdbcTemplate.update("DELETE FROM grocery_price_history");
  }

  private void record(UUID userId, String key, String store, int totalPence, Instant observedAt) {
    priceHistory.recordManualPrice(
        userId,
        new RecordManualPriceRequest(
            key, store, totalPence, BigDecimal.ONE, "per_100g", observedAt));
  }

  @Test
  void getAggregate_multipleObservations_weightedMeanAndSampleCount() {
    UUID userId = UUID.randomUUID();
    Instant now = Instant.now();
    record(userId, "chicken breast", "tesco_online", 200, now);
    record(userId, "chicken breast", "tesco_online", 220, now.minus(1, ChronoUnit.DAYS));

    PriceAggregateDto dto =
        priceHistory.getAggregate(userId, "chicken breast", "tesco_online").orElseThrow();

    assertThat(dto.sampleCount()).isEqualTo(2);
    assertThat(dto.pointEstimatePence()).isEqualTo(210); // both MANUAL (equal weight) → simple mean
    assertThat(dto.minPence()).isEqualTo(200);
    assertThat(dto.maxPence()).isEqualTo(220);
    assertThat(dto.isStale()).isFalse();
  }

  @Test
  void getAggregate_observationOlderThanWindow_fallsBackToReferenceMarkedStale() {
    UUID userId = UUID.randomUUID();
    // V1-SIMPLE: the observation window IS staleThresholdDays (90d, per the ticket), so an
    // observation older than that falls OUT of the window — the aggregate then surfaces via the
    // bundled reference ("white rice" = 18 p/100g), a stale low-confidence number, rather than a
    // damped observation-aggregate. (The damped-stale-observation path is only reachable if the
    // window is widened beyond the threshold — noted as a v2 refinement.)
    record(userId, "white rice", "aldi", 25, Instant.now().minus(200, ChronoUnit.DAYS));

    PriceAggregateDto dto = priceHistory.getAggregate(userId, "white rice", "aldi").orElseThrow();

    assertThat(dto.isStale()).isTrue();
    assertThat(dto.sampleCount()).isZero(); // out-of-window observation → reference fallback
    assertThat(dto.pointEstimatePence()).isEqualTo(18);
  }

  @Test
  void getAggregate_noObservations_referenceSeedKey_fallsBackToReference() {
    UUID userId = UUID.randomUUID();
    // No observations; "broccoli" is in the bundled reference snapshot (30 p/100g, conf 0.200).
    PriceAggregateDto dto = priceHistory.getAggregate(userId, "broccoli", null).orElseThrow();

    assertThat(dto.sampleCount()).isZero();
    assertThat(dto.pointEstimatePence()).isEqualTo(30);
    assertThat(dto.confidence()).isEqualByComparingTo(new BigDecimal("0.200"));
    assertThat(dto.isStale()).isTrue(); // a reference estimate is never "fresh"
  }

  @Test
  void getAggregate_unknownKey_notInReference_returnsEmpty() {
    UUID userId = UUID.randomUUID();
    assertThat(priceHistory.getAggregate(userId, "definitely-not-real-xyz", null)).isEmpty();
  }

  @Test
  void getAggregatesByKeys_batch_mixesObservedAndReferenceAndSkipsUnknown() {
    UUID userId = UUID.randomUUID();
    record(userId, "chicken breast", "tesco_online", 180, Instant.now());

    Map<String, PriceAggregateDto> byKey =
        priceHistory.getAggregatesByKeys(
            userId, List.of("chicken breast", "white rice", "definitely-not-real-xyz"));

    // chicken breast → from the observation; white rice → reference fallback; unknown → absent.
    assertThat(byKey).containsKeys("chicken breast", "white rice");
    assertThat(byKey).doesNotContainKey("definitely-not-real-xyz");
    assertThat(byKey.get("chicken breast").sampleCount()).isEqualTo(1);
    assertThat(byKey.get("chicken breast").pointEstimatePence()).isEqualTo(180);
    assertThat(byKey.get("white rice").sampleCount()).isZero();
  }

  @Test
  void getCrossStoreAggregates_returnsPerStoreEntries() {
    UUID userId = UUID.randomUUID();
    record(userId, "olive oil", "tesco_online", 70, Instant.now());
    record(userId, "olive oil", "aldi", 55, Instant.now());

    List<PriceAggregateDto> perStore =
        priceHistory.getCrossStoreAggregatesByKey(userId, "olive oil");

    assertThat(perStore).hasSize(2);
    assertThat(perStore)
        .extracting(PriceAggregateDto::store)
        .containsExactlyInAnyOrder("tesco_online", "aldi");
  }

  @Test
  void recordManualPrice_persistsObservationThatSurfacesInAggregate() {
    UUID userId = UUID.randomUUID();
    // "salt" has a reference (8 p/100g, conf 0.200) so a no-observation read is the reference
    // fallback (sampleCount 0); after recording a real observation the aggregate is observation-
    // backed (sampleCount 1).
    PriceAggregateDto before =
        priceHistory.getAggregate(userId, "salt", "tesco_online").orElseThrow();
    assertThat(before.sampleCount()).isZero();

    record(userId, "salt", "tesco_online", 9, Instant.now());

    PriceAggregateDto after =
        priceHistory.getAggregate(userId, "salt", "tesco_online").orElseThrow();
    assertThat(after.sampleCount()).isEqualTo(1);
    assertThat(after.pointEstimatePence()).isEqualTo(9);
  }
}
