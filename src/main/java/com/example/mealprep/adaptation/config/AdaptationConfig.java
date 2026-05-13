package com.example.mealprep.adaptation.config;

import com.example.mealprep.adaptation.domain.enums.JobSource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Typed configuration for the adaptation pipeline. Verbatim shape from {@code
 * lld/adaptation-pipeline.md} §Configuration (lines 705-724). Default values live in {@code
 * application.properties} under {@code mealprep.adaptation.*}; per-source budgets default to an
 * empty map per LLD line 727 ("Default values deferred").
 */
@ConfigurationProperties(prefix = "mealprep.adaptation")
@Validated
public record AdaptationConfig(
    @Min(1) @Max(20) int candidateTopN,
    @Positive int planTimeTimeoutMs,
    @Positive int feedbackTimeoutMs,
    @Positive int importTimeoutMs,
    @Min(0) @Max(20) int maxRebaseAttempts,
    @Min(0) @Max(7) int pendingChangeBudgetPerWeek,
    @Positive int pendingChangeExpiryDays,
    @NotNull @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal lowConfidenceFloor,
    @NotNull @DecimalMin("0.0") @DecimalMax("10.0") BigDecimal autoSkipTopRatio,
    Map<JobSource, @Valid BudgetConfig> sourceBudgets,
    @Positive int recipeAdvisoryLockSeconds,
    @NotBlank String pendingExpirySweepCron,
    @NotBlank String batchOrchestratorCron) {

  /**
   * Compact canonical constructor — coerces a missing / null {@code sourceBudgets} block to an
   * empty map per LLD line 715 ("Default values deferred"). Keeps callers from null-checking the
   * field.
   */
  public AdaptationConfig {
    sourceBudgets = sourceBudgets == null ? Collections.emptyMap() : Map.copyOf(sourceBudgets);
  }

  /** Per-trigger LLM cost budget — distinct buckets reflect HLD per-source cost shape. */
  public record BudgetConfig(
      @Positive Integer maxConcurrentJobs,
      @PositiveOrZero BigDecimal dailyCostBudgetGbp,
      @Positive Integer maxJobsPerHour) {}
}
