package com.example.mealprep.grocery.config;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Typed configuration for the grocery module — confidence formula + freshness + scheduler + order
 * defaults. Verbatim shape from lld/grocery.md §Configuration (lines 990-1001). Defaults live in
 * {@code application.properties} under {@code mealprep.grocery.*}; bound via
 * {@code @EnableConfigurationProperties(GroceryConfig.class)} on {@link
 * com.example.mealprep.grocery.GroceryModule} (the project has no
 * {@code @ConfigurationPropertiesScan}).
 *
 * <p><b>v1 note (ticket-01a):</b> the full config surface is shipped so it doesn't churn, but 01c's
 * V1-SIMPLE aggregate reads ONLY {@code confidenceWeights} + {@code aggregator.staleThresholdDays}.
 * {@code aggregator.halfLifeDays} / {@code priorStrength} and {@code inflation.monthlyFactor} are
 * wired through but UNUSED in v1 (the decay / Bayesian / inflation fields land with the deferred-v2
 * work per the README v1-scope line).
 */
@ConfigurationProperties(prefix = "mealprep.grocery")
@Validated
public record GroceryConfig(
    @NotNull AggregatorConfig aggregator,
    @NotNull ConfidenceWeightsConfig confidenceWeights,
    @NotNull InflationConfig inflation,
    @NotNull FreshnessConfig freshness,
    @NotNull SchedulerConfig scheduler,
    @NotNull OrderConfig order) {

  /** Aggregation tunables. {@code halfLifeDays}/{@code priorStrength} are v2 (unused in v1). */
  public record AggregatorConfig(
      @Min(1) int halfLifeDays,
      @DecimalMin("0.0") double priorStrength,
      @Min(1) int staleThresholdDays) {}

  /** Source-weights applied at write time. v1 reads these (the only price signal in V1-SIMPLE). */
  public record ConfidenceWeightsConfig(
      @DecimalMin("0.0") @DecimalMax("1.0") double paid,
      double quote,
      double manual,
      double manualEstimated,
      double inflationIndexed) {}

  /** Inflation-indexing factor. v2-only (unused in v1). */
  public record InflationConfig(@DecimalMin("0.0") double monthlyFactor) {}

  /** Freshness ramp-up + default curated-set size. */
  public record FreshnessConfig(@Min(0) int rampUpWeeks, @Min(1) int defaultRefreshTopN) {}

  /** Cron expressions for the (optional, 01g) scheduled jobs. */
  public record SchedulerConfig(
      @NotBlank String refreshCron,
      @NotBlank String orderStatusCron,
      @NotBlank String archiveCron) {}

  /** Order single-flight + provider-retry tunables. */
  public record OrderConfig(
      @Min(1) int singleFlightLockTtlSeconds, @Min(1) int providerUnavailableRetryHours) {}
}
