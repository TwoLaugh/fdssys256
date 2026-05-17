package com.example.mealprep.planner.config;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Externalised configuration for the planner module — bound to {@code mealprep.planner.*}. 01d
 * ships the first 7 keys consumed by the Stage-A beam search and hard-filter runner; subsequent
 * tickets (01e weight scheme, 01f rollup tunables, 01g/01h LLM tier targets) append further fields.
 *
 * <p>Spring Boot 3.x record-shaped {@code @ConfigurationProperties} are auto-{@code
 * ConstructorBinding} so defaults are wired via {@code application.properties} (not record
 * defaults). {@code @Validated} runs the Jakarta constraints at context-load time — a bad override
 * crashes startup with a clear bind-validation message.
 *
 * <p>{@code weekStartDayOfWeek} accepts Spring's relaxed binding ({@code MONDAY}, {@code monday},
 * {@code Monday}); the project locks to Monday-start weeks per the LLD.
 */
@ConfigurationProperties(prefix = "mealprep.planner")
@Validated
public record PlannerProperties(
    @NotNull DayOfWeek weekStartDayOfWeek,
    @Min(1) int beamWidth,
    @Min(1) int topN,
    @Min(1) int minPoolPerSlot,
    @Min(1) int maxPoolPerSlot,
    @NotNull @DecimalMin("1.0") @DecimalMax("3.0") BigDecimal maxTimeOvershootRatio,
    @NotNull Duration stageATimeout,
    @NotNull ScoringWeights weights,
    @NotNull ScoringTuning scoring) {

  /**
   * Composite-score weight scheme (planner-01e). v1 is uniform {@code 1/7 ≈ 0.143} per HLD §Initial
   * Weights v1. The sum is intentionally NOT enforced to be {@code 1.0} — the composite is {@code Σ
   * weight × sub_score}, meaningful for any non-negative vector, and per-sub-score boosting (e.g.
   * 2× preference) is a deliberate calibration lever. TODO(user): if calibration shows v1 uniform
   * weights are mis-tuned, override is a properties change (no redeploy), not a code change.
   */
  public record ScoringWeights(
      @NotNull @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal preference,
      @NotNull @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal nutrition,
      @NotNull @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal cost,
      @NotNull @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal variety,
      @NotNull @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal time,
      @NotNull @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal batch,
      @NotNull @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal provisions) {}

  /** Tunable numeric constants for variety / provisions / cost sub-scores (planner-01e). */
  public record ScoringTuning(
      @NotNull VarietyTargets variety,
      @NotNull ProvisionsTuning provisions,
      @NotNull CostTuning cost) {

    public record VarietyTargets(
        @Min(1) int cuisine, // default 5
        @Min(1) int protein, // default 4
        @Min(1) int cookingMethod, // default 3
        @Min(1) int maxRepeat) {} // default 2 (used by VarietyGate)

    public record ProvisionsTuning(@NotNull WasteValueTiers wasteValueTiers) {

      public record WasteValueTiers(
          @NotNull @DecimalMin("0.0") BigDecimal aboveSevenDays, // 1.0
          @NotNull @DecimalMin("0.0") BigDecimal threeDaysOrLess, // 2.0
          @NotNull @DecimalMin("0.0") BigDecimal oneDayOrLess) {} // 3.0
    }

    public record CostTuning(
        @NotNull @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal confidenceThreshold) {} // 0.1
  }
}
