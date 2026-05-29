package com.example.mealprep.planner.config;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Duration;
import java.util.List;
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
 * nested {@code midWeek} group (mid-week re-optimisation tunables). planner-01k appends the nested
 * {@code materiality} group (the event-listener materiality-filter thresholds — see {@code
 * Materiality}).
 *
 * <p>{@code leaseTtl} (default {@code PT10M}) is the validity window of the connection-free
 * start-of-generation single-flight lease ({@code core.LockService.acquireLease}) taken at the very
 * start of {@code compose()}, before the AI pipeline runs. It must be safely larger than the
 * maximum plan-generation time (~20s) so a still-running generation never has its lease reclaimed
 * out from under it; on a holder crash the lease becomes reclaimable after this window. Normal
 * completions release the lease immediately in a finally block, well before the TTL.
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
    @NotNull Duration leaseTtl,
    @NotNull MidWeek midWeek,
    @NotNull Materiality materiality,
    @NotNull ColdStart coldStart) {

  /**
   * Cold-start gate tunables (planner recipe-pool Tier-2). Bound under {@code
   * mealprep.planner.cold-start.*}. The gate runs in the composer BEFORE Stage A: when the
   * candidate catalogue is below {@code slotKindMultiplier × distinct-slot-kinds} it invokes {@code
   * DiscoveryService.runJobSync} (bounded) to fill the SYSTEM catalogue, then re-reads the pool and
   * flags the plan {@code coldStart = true}. Per meal-planner.md §Cold start + lld/planner.md
   * §Flow-1 step 5.
   *
   * <p>Every key MUST exist in the main AND test {@code application.properties} — the test file
   * shadows the main one, and {@code @Validated} fails context load on a missing {@code @NotNull}
   * otherwise.
   *
   * <ul>
   *   <li>{@code enabled} — master switch. {@code false} skips the gate entirely (plans behave
   *       exactly as Tier-1: an empty catalogue yields a quality-warning plan, never a discovery
   *       call). Default {@code true}.
   *   <li>{@code slotKindMultiplier} — the per-distinct-slot-kind candidate floor. The threshold is
   *       {@code slotKindMultiplier × distinctSlotKinds} (meal-planner.md heuristic "≥3× slot
   *       count"). Default {@code 3}.
   *   <li>{@code requestedCount} — the discovery job quota (recipes to import in one cold-start
   *       run). Default {@code 50} (meal-planner.md "adds up to 50 recipes per cold-start run").
   *   <li>{@code timeout} — the bounded {@code runJobSync} deadline. On timeout the gate degrades
   *       (keeps whatever the runner committed so far). Default {@code PT20S}.
   *   <li>{@code sourceKeys} — the discovery sources the gate requests. Empty (the prod default) ⇒
   *       "all currently-enabled sources". The {@code e2e} profile overrides this to the single
   *       deterministic {@code e2e_curated_seed} source so cold-start is fast + repeatable in the
   *       stack and the non-deterministic web/Google sources never run there.
   * </ul>
   */
  public record ColdStart(
      boolean enabled,
      @Min(1) int slotKindMultiplier,
      @Min(1) @jakarta.validation.constraints.Max(50) int requestedCount,
      @NotNull Duration timeout,
      @NotNull List<String> sourceKeys) {}

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
   * Event-listener materiality-filter thresholds (planner-01k). Bound under {@code
   * mealprep.planner.materiality.*}. Every key MUST exist in the main AND test {@code
   * application.properties} — the test file shadows the main one, and {@code @Validated} fails
   * context load on a missing {@code @NotNull} otherwise.
   *
   * <ul>
   *   <li>{@code nutritionVariancePct} — minimum absolute macro divergence (a fraction; {@code
   *       0.15} == 15%, per HLD) for a {@code NutritionIntakeDivergedEvent} to be material. Below
   *       this the divergence is noise; surfacing a re-opt would thrash. Default {@code 0.15}.
   *   <li>{@code nutritionMinRedistributableMeals} — a divergence is only actionable if the plan
   *       still has at least this many unplanned/unpinned meals left in the week to redistribute
   *       macros over. Default {@code 3}.
   *   <li>{@code preferenceSoftDeltaPoints} — minimum soft-preference delta-point swing for a
   *       pure-soft {@code HardConstraintsUpdatedEvent} field-change to be material.
   *       Hard-constraint changes (allergy/dietary-identity) are ALWAYS material regardless of
   *       this; this only gates small soft-preference nudges. Default {@code 10}.
   * </ul>
   */
  public record Materiality(
      @NotNull @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal nutritionVariancePct,
      @Min(0) int nutritionMinRedistributableMeals,
      @Min(0) int preferenceSoftDeltaPoints) {}

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
