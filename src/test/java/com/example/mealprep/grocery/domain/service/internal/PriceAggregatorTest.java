package com.example.mealprep.grocery.domain.service.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.example.mealprep.grocery.api.dto.PriceAggregateDto;
import com.example.mealprep.grocery.config.GroceryConfig;
import com.example.mealprep.grocery.domain.entity.PriceObservation;
import com.example.mealprep.grocery.domain.entity.PriceSource;
import com.example.mealprep.grocery.domain.service.ReferencePriceSource;
import com.example.mealprep.grocery.domain.service.ReferencePriceSource.ReferencePrice;
import com.example.mealprep.grocery.testdata.GroceryTestData;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * V1-SIMPLE aggregator maths. Pure unit test — verifies confidence rises with sample count, the
 * reference fallback, the stale damping, range/recency, and (by absence) that NO decay / Bayesian /
 * inflation machinery runs. Per the 01c edge-case checklist + LLD §Flow 5 (v1 cut).
 */
@ExtendWith(MockitoExtension.class)
class PriceAggregatorTest {

  private static final Instant NOW = Instant.parse("2026-05-27T12:00:00Z");
  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

  // LLD defaults: staleThresholdDays = 90; halfLife/priorStrength present but v1 must NOT read
  // them.
  private final GroceryConfig config =
      new GroceryConfig(
          new GroceryConfig.AggregatorConfig(90, 2.0, 90),
          new GroceryConfig.ConfidenceWeightsConfig(1.0, 0.85, 0.7, 0.4, 0.15),
          new GroceryConfig.InflationConfig(0.005),
          new GroceryConfig.FreshnessConfig(8, 50),
          new GroceryConfig.SchedulerConfig("0 0 4 * * SUN", "0 0 * * * *", "0 0 5 * * *"),
          new GroceryConfig.OrderConfig(300, 24));

  @Mock private ReferencePriceSource referencePriceSource;

  private PriceAggregator aggregator() {
    return new PriceAggregator(config, referencePriceSource, clock);
  }

  private PriceObservation obs(
      int unitPence, PriceSource source, double weight, Instant observedAt) {
    return GroceryTestData.priceObservation()
        .ingredientMappingKey("chicken breast")
        .store("tesco_online")
        .paidUnitPence(unitPence)
        .source(source)
        .confidenceWeight(BigDecimal.valueOf(weight))
        .observedAt(observedAt)
        .build();
  }

  @Test
  void aggregate_singleObservation_lowConfidence_sampleCountOne() {
    PriceAggregateDto dto =
        aggregator()
            .aggregate(
                "chicken breast", "tesco_online", List.of(obs(100, PriceSource.PAID, 1.0, NOW)))
            .orElseThrow();

    assertThat(dto.sampleCount()).isEqualTo(1);
    assertThat(dto.pointEstimatePence()).isEqualTo(100);
    assertThat(dto.isStale()).isFalse();
    // confidence = 1.0 / (1.0 + 2.0) = 0.333
    assertThat(dto.confidence()).isEqualByComparingTo(new BigDecimal("0.333"));
  }

  @Test
  void aggregate_manyObservations_confidenceRisesAndTightens() {
    BigDecimal single =
        aggregator()
            .aggregate(
                "chicken breast", "tesco_online", List.of(obs(100, PriceSource.PAID, 1.0, NOW)))
            .orElseThrow()
            .confidence();

    List<PriceObservation> many =
        List.of(
            obs(100, PriceSource.PAID, 1.0, NOW),
            obs(110, PriceSource.PAID, 1.0, NOW.minus(2, ChronoUnit.DAYS)),
            obs(105, PriceSource.PAID, 1.0, NOW.minus(4, ChronoUnit.DAYS)),
            obs(108, PriceSource.PAID, 1.0, NOW.minus(6, ChronoUnit.DAYS)));
    PriceAggregateDto dto =
        aggregator().aggregate("chicken breast", "tesco_online", many).orElseThrow();

    assertThat(dto.sampleCount()).isEqualTo(4);
    // confidence = 4.0 / (4.0 + 2.0) = 0.667 — strictly higher than the single-observation case.
    assertThat(dto.confidence()).isEqualByComparingTo(new BigDecimal("0.667"));
    assertThat(dto.confidence().compareTo(single)).isPositive();
  }

  @Test
  void aggregate_weightedMean_isSourceWeightWeighted() {
    // PAID 200 @ 1.0 and MANUAL 100 @ 0.7 → (200*1.0 + 100*0.7) / (1.0 + 0.7) = 270/1.7 ≈ 159.
    PriceAggregateDto dto =
        aggregator()
            .aggregate(
                "chicken breast",
                "tesco_online",
                List.of(
                    obs(200, PriceSource.PAID, 1.0, NOW),
                    obs(100, PriceSource.MANUAL, 0.7, NOW.minus(1, ChronoUnit.DAYS))))
            .orElseThrow();
    assertThat(dto.pointEstimatePence()).isEqualTo(159);
    assertThat(dto.minPence()).isEqualTo(100);
    assertThat(dto.maxPence()).isEqualTo(200);
  }

  @Test
  void aggregate_allStale_marksStale_andDampsConfidence() {
    // Observed 200 days ago — older than staleThresholdDays (90).
    Instant old = NOW.minus(200, ChronoUnit.DAYS);
    PriceAggregateDto dto =
        aggregator()
            .aggregate(
                "chicken breast", "tesco_online", List.of(obs(100, PriceSource.PAID, 1.0, old)))
            .orElseThrow();
    assertThat(dto.isStale()).isTrue();
    assertThat(dto.lastSeenAt()).isEqualTo(old);
    // Undamped would be 0.333; stale halves it → 0.167.
    assertThat(dto.confidence()).isEqualByComparingTo(new BigDecimal("0.167"));
  }

  @Test
  void aggregate_noObservations_keyMappedInReference_returnsReferenceEstimate() {
    when(referencePriceSource.referencePrice("chicken breast"))
        .thenReturn(
            Optional.of(
                new ReferencePrice(
                    "chicken breast",
                    110,
                    "per_100g",
                    new BigDecimal("0.200"),
                    NOW.minus(120, ChronoUnit.DAYS),
                    "Open Food Facts Open Prices, ODbL v1.0")));

    PriceAggregateDto dto = aggregator().aggregate("chicken breast", null, List.of()).orElseThrow();

    assertThat(dto.sampleCount()).isZero();
    assertThat(dto.pointEstimatePence()).isEqualTo(110);
    assertThat(dto.isStale()).isTrue();
    assertThat(dto.confidence()).isEqualByComparingTo(new BigDecimal("0.200"));
  }

  @Test
  void aggregate_noObservations_keyUnmappedInReference_returnsEmpty() {
    when(referencePriceSource.referencePrice("novel ingredient")).thenReturn(Optional.empty());
    assertThat(aggregator().aggregate("novel ingredient", null, List.of())).isEmpty();
  }

  @Test
  void windowStart_usesStaleThresholdDays_notSixTimesHalfLife() {
    // V1-SIMPLE: a flat staleThresholdDays (90) window — NOT the v2 6×halfLife (540) window.
    Instant since = aggregator().windowStart(NOW);
    assertThat(since).isEqualTo(NOW.minus(90, ChronoUnit.DAYS));
    assertThat(since).isNotEqualTo(NOW.minus(540, ChronoUnit.DAYS));
  }
}
