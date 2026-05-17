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
 * tickets (01e weight scheme, 01f rollup tunables, 01g LLM Stage-C timeout + iteration budget, 01h
 * Phase-2 augmentation bounds) append further fields.
 *
 * <p>{@code stageCTimeout} (default {@code PT20S}) and {@code iterationBudget} (default {@code 3})
 * were appended by planner-01g; {@code iterationBudget} is consumed by Stage D (01h/01j) but the
 * key lives at the planner level. planner-01h appends {@code maxAugmentations} (default 5) and
 * {@code maxRefineDirectives} (default 2), bounding the Phase-2 LLM output. planner-01i appends the
 * nested {@code midWeek} group (mid-week re-optimisation tunables).
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
    @NotNull ScoringTuning scoring,
    @NotNull Duration stageCTimeout,
    @Min(1) int iterationBudget,
    @Min(1) int maxAugmentations,
    @Min(0) int maxRefineDirectives,
    @NotNull MidWeek midWeek) {

  /**
   * Mid-week re-optimisation tunables (planner-01i). Bound under {@code
   * mealprep.planner.mid-week.*}. Both keys MUST exist in the main AND test {@code
   * application.properties} — the test file shadows the main one, and {@code @Validated} fails
   * context load on a missing {@code @NotNull} otherwise.
   *
   * <ul>
   *   <li>{@code lockHoursBeforeSlot} — a still-{@code PLANNED} slot becomes pinned once {@code
   *       now} is within this many hours of the slot's date (default 24).
   *   <li>{@code maxSuggestionsPerPlan} — once a plan accumulates this many {@code PENDING +
   *       REJECTED} re-opt suggestions, further {@code requestReopt} calls are rejected-by-budget
   *       (default 3) to prevent trigger thrashing.
   * </ul>
   */
  public record MidWeek(@Min(0) int lockHoursBeforeSlot, @Min(1) int maxSuggestionsPerPlan) {}

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
