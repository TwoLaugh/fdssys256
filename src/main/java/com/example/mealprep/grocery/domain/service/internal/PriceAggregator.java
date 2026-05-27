package com.example.mealprep.grocery.domain.service.internal;

import com.example.mealprep.grocery.api.dto.PriceAggregateDto;
import com.example.mealprep.grocery.config.GroceryConfig;
import com.example.mealprep.grocery.domain.entity.PriceObservation;
import com.example.mealprep.grocery.domain.service.ReferencePriceSource;
import com.example.mealprep.grocery.domain.service.ReferencePriceSource.ReferencePrice;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Tier 4 V1-SIMPLE aggregator (01c). Pure, deterministic — collapses a set of price observations
 * for one (household, key[, store]) into a {@link PriceAggregateDto} of {@code (estimate,
 * confidence, range, recency, stale)}.
 *
 * <p><b>V1-SIMPLE — NO time-decay, NO Bayesian prior, NO inflation indexing.</b> This class
 * deliberately reads ONLY {@code GroceryConfig.confidenceWeights} (via each row's persisted {@code
 * confidence_weight}) and {@code aggregator.staleThresholdDays}. It NEVER reads {@code
 * halfLifeDays} / {@code priorStrength} / {@code inflation.monthlyFactor} (those are the
 * deferred-v2 fields). The v1 maths is:
 *
 * <ul>
 *   <li>point estimate = {@code sum(unitPence × sourceWeight) / sum(sourceWeight)} (flat
 *       source-weighted mean — no decay term);
 *   <li>confidence = {@code min(1.0, sumWeight / (sumWeight + PRIOR_CONST))} with a fixed {@link
 *       #PRIOR_CONST} = 2.0 (so 1 observation → low/wide, many → high/tight) — NOT the v2
 *       Bayesian-with-priorStrength form;
 *   <li>{@code isStale = lastSeenAt < now − staleThresholdDays}; when stale the confidence is
 *       dampened by {@link #STALE_DAMPING}.
 * </ul>
 *
 * <p>Cold start: when no in-window observations exist, falls back to the {@link
 * ReferencePriceSource} reference estimate (low {@code referenceConfidence}, {@code sampleCount =
 * 0}, {@code isStale = true}). {@link Optional#empty()} ONLY when the key is also unmapped in the
 * reference source (a true novel-ingredient cold start). Per LLD §Flow 5 (lines 926-939, v1 cut).
 */
@Component
class PriceAggregator {

  /**
   * Fixed v1 prior — replaces the v2 {@code priorStrength} config field (which v1 must NOT read).
   */
  static final double PRIOR_CONST = 2.0;

  /** Multiplier applied to confidence when all in-window observations are stale. */
  static final double STALE_DAMPING = 0.5;

  private final GroceryConfig config;
  private final ReferencePriceSource referencePriceSource;
  private final Clock clock;

  PriceAggregator(GroceryConfig config, ReferencePriceSource referencePriceSource, Clock clock) {
    this.config = config;
    this.referencePriceSource = referencePriceSource;
    this.clock = clock;
  }

  /** {@code now − staleThresholdDays} — the flat v1 fetch window (v2 uses {@code 6 × halfLife}). */
  Instant windowStart(Instant now) {
    return now.minus(config.aggregator().staleThresholdDays(), ChronoUnit.DAYS);
  }

  /**
   * Aggregate a pre-fetched, in-window observation list for one key+store. {@code store} carries
   * the store name (or null for cross-store) into the resulting DTO; the caller is responsible for
   * having filtered {@code observations} to that store. When the list is empty, falls back to the
   * reference source for {@code key}.
   */
  Optional<PriceAggregateDto> aggregate(
      String key, String store, List<PriceObservation> observations) {
    Instant now = clock.instant();
    if (observations.isEmpty()) {
      return referenceFallback(key, store, now);
    }

    double weightedSum = 0.0;
    double sumWeight = 0.0;
    int sampleCount = 0;
    Integer minPence = null;
    Integer maxPence = null;
    Instant minObservedAt = null;
    Instant maxObservedAt = null;
    Instant lastSeenAt = null;

    for (PriceObservation o : observations) {
      Integer unitPence = o.getPaidUnitPence();
      if (unitPence == null) {
        continue; // a row with no normalised unit price can't contribute to the estimate
      }
      double weight = o.getConfidenceWeight() == null ? 0.0 : o.getConfidenceWeight().doubleValue();
      weightedSum += (double) unitPence * weight;
      sumWeight += weight;
      sampleCount++;

      if (minPence == null || unitPence < minPence) {
        minPence = unitPence;
        minObservedAt = o.getObservedAt();
      }
      if (maxPence == null || unitPence > maxPence) {
        maxPence = unitPence;
        maxObservedAt = o.getObservedAt();
      }
      if (lastSeenAt == null || o.getObservedAt().isAfter(lastSeenAt)) {
        lastSeenAt = o.getObservedAt();
      }
    }

    if (sampleCount == 0 || sumWeight <= 0.0) {
      // every row lacked a usable unit price → treat as no observations.
      return referenceFallback(key, store, now);
    }

    int pointEstimate = (int) Math.round(weightedSum / sumWeight);
    boolean isStale = lastSeenAt.isBefore(windowStart(now));

    double rawConfidence = Math.min(1.0, sumWeight / (sumWeight + PRIOR_CONST));
    if (isStale) {
      rawConfidence *= STALE_DAMPING;
    }
    BigDecimal confidence = BigDecimal.valueOf(rawConfidence).setScale(3, RoundingMode.HALF_UP);

    return Optional.of(
        new PriceAggregateDto(
            key,
            store,
            pointEstimate,
            confidence,
            minPence,
            maxPence,
            minObservedAt,
            maxObservedAt,
            lastSeenAt,
            sampleCount,
            isStale));
  }

  /** Build the cold-start DTO from a reference estimate, or empty when the key is unmapped. */
  Optional<PriceAggregateDto> referenceFallback(String key, String store, Instant now) {
    return toAggregate(key, store, referencePriceSource.referencePrice(key).orElse(null), now);
  }

  /**
   * Shared reference→DTO conversion used by both the single fallback and the batch path. {@code
   * ref} null → empty.
   */
  Optional<PriceAggregateDto> toAggregate(
      String key, String store, ReferencePrice ref, Instant now) {
    if (ref == null) {
      return Optional.empty();
    }
    BigDecimal confidence = ref.referenceConfidence().setScale(3, RoundingMode.HALF_UP);
    return Optional.of(
        new PriceAggregateDto(
            key,
            store,
            ref.unitPence(),
            confidence,
            ref.unitPence(),
            ref.unitPence(),
            ref.sourceAsOf(),
            ref.sourceAsOf(),
            ref.sourceAsOf(),
            0,
            true));
  }
}
