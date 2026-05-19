package com.example.mealprep.planner.domain.service.internal.scoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.planner.api.dto.CandidatePlan;
import com.example.mealprep.planner.api.dto.PlanCompositionContext;
import com.example.mealprep.planner.config.PlannerProperties;
import com.example.mealprep.planner.testdata.PlanTestData;
import com.example.mealprep.recipe.api.dto.RecipeDto;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit test for {@link ProvisionsSubScore} — LOCKED covered/max waste-value ratio. Inventory expiry
 * is anchored to {@code LocalDate.now()}-relative (no hardcoded date → no time-bomb).
 */
class ProvisionsSubScoreTest {

  private static final LocalDate WEEK = LocalDate.of(2026, 1, 5);
  private final ProvisionsSubScore calc = new ProvisionsSubScore(PlanTestData.scoringProperties());

  @Test
  void name_is_provisions() {
    assertThat(calc.name()).isEqualTo("provisions");
  }

  @Test
  void no_inventory_returns_neutral_disabled_proxy() {
    var bundle =
        PlanTestData.provisionsBundle(
            PlanTestData.budget(new BigDecimal("50")), Map.of(), List.of());
    PlanCompositionContext ctx =
        PlanTestData.scoringContext(List.of(), List.of(), bundle, Map.of(), Map.of());
    assertThat(calc.compute(PlanTestData.candidatePlan(WEEK, List.of()), ctx))
        .isEqualByComparingTo(new BigDecimal("0.5"));
  }

  @Test
  void fully_covered_far_expiry_scores_one_third_of_max_tier() {
    UUID id = UUID.randomUUID();
    RecipeDto recipe = PlanTestData.scoredRecipe(id, 20, "Thai", "tofu", "fry", List.of("rice"));
    // demand = 1 * 2 servings = 2; inventory 10 covers it, expiry far (>7d) → waste_value 1.0
    var inv = PlanTestData.inventoryItem("rice", new BigDecimal("10"), 30);
    var bundle =
        PlanTestData.provisionsBundle(
            PlanTestData.budget(new BigDecimal("50")), Map.of(), List.of(inv));
    PlanCompositionContext ctx =
        PlanTestData.scoringContext(List.of(), List.of(recipe), bundle, Map.of(), Map.of());
    CandidatePlan plan =
        PlanTestData.candidatePlan(
            WEEK, List.of(PlanTestData.assignment(UUID.randomUUID(), id, WEEK, 0, 2)));
    // covered = 2 * 1.0 = 2; max = 2 * 3.0 = 6 → 2/6 ≈ 0.333333
    assertThat(calc.compute(plan, ctx)).isEqualByComparingTo(new BigDecimal("0.333333"));
  }

  @Test
  void near_expiry_item_weighted_three_times_scores_one() {
    UUID id = UUID.randomUUID();
    RecipeDto recipe = PlanTestData.scoredRecipe(id, 20, "Thai", "tofu", "fry", List.of("milk"));
    var inv = PlanTestData.inventoryItem("milk", new BigDecimal("10"), 1); // ≤1d → waste_value 3.0
    var bundle =
        PlanTestData.provisionsBundle(
            PlanTestData.budget(new BigDecimal("50")), Map.of(), List.of(inv));
    PlanCompositionContext ctx =
        PlanTestData.scoringContext(List.of(), List.of(recipe), bundle, Map.of(), Map.of());
    CandidatePlan plan =
        PlanTestData.candidatePlan(
            WEEK, List.of(PlanTestData.assignment(UUID.randomUUID(), id, WEEK, 0, 2)));
    // covered = 2 * 3.0 = 6; max = 2 * 3.0 = 6 → 1.0
    assertThat(calc.compute(plan, ctx)).isEqualByComparingTo(BigDecimal.ONE);
  }

  @Test
  void ingredient_not_in_inventory_contributes_to_max_only() {
    UUID id = UUID.randomUUID();
    RecipeDto recipe =
        PlanTestData.scoredRecipe(id, 20, "Thai", "tofu", "fry", List.of("rice", "saffron"));
    var inv = PlanTestData.inventoryItem("rice", new BigDecimal("10"), 30);
    var bundle =
        PlanTestData.provisionsBundle(
            PlanTestData.budget(new BigDecimal("50")), Map.of(), List.of(inv));
    PlanCompositionContext ctx =
        PlanTestData.scoringContext(List.of(), List.of(recipe), bundle, Map.of(), Map.of());
    CandidatePlan plan =
        PlanTestData.candidatePlan(
            WEEK, List.of(PlanTestData.assignment(UUID.randomUUID(), id, WEEK, 0, 2)));
    // rice covered 2*1=2; saffron only in max. max = (2+2)*3 = 12 → 2/12 ≈ 0.166667
    assertThat(calc.compute(plan, ctx)).isEqualByComparingTo(new BigDecimal("0.166667"));
  }

  // ---- mutation-killing additions -------------------------------------------------------------

  /**
   * Inventory exists (pantry tracking "enabled") but the plan's recipe is absent from the pool, so
   * demand is empty → the method returns neutral 0.5. Kills the L90 NullReturnVals mutant ({@code
   * return NEUTRAL} → {@code return null}); a null return would NPE on isEqualByComparingTo.
   */
  @Test
  void empty_demand_with_inventory_present_returns_neutral() {
    var inv = PlanTestData.inventoryItem("rice", new BigDecimal("10"), 30);
    var bundle =
        PlanTestData.provisionsBundle(
            PlanTestData.budget(new BigDecimal("50")), Map.of(), List.of(inv));
    PlanCompositionContext ctx =
        PlanTestData.scoringContext(List.of(), List.of(), bundle, Map.of(), Map.of());
    CandidatePlan plan =
        PlanTestData.candidatePlan(
            WEEK,
            List.of(PlanTestData.assignment(UUID.randomUUID(), UUID.randomUUID(), WEEK, 0, 2)));
    assertThat(calc.compute(plan, ctx)).isEqualByComparingTo(new BigDecimal("0.5"));
  }

  /**
   * Inventory item with a {@code null} expiry date → lowest-urgency tier (aboveSevenDays = 1.0).
   * Kills the L135 NullReturnVals mutant in {@code wasteValue} ({@code return
   * tiers.aboveSevenDays()} → {@code return null}); a null waste value would NPE inside the
   * covered-value multiply. covered = 2 × 1.0 = 2; max = 2 × 3.0 = 6 → 0.333333.
   */
  @Test
  void null_expiry_uses_lowest_urgency_tier() {
    UUID id = UUID.randomUUID();
    RecipeDto recipe = PlanTestData.scoredRecipe(id, 20, "Thai", "tofu", "fry", List.of("rice"));
    var inv = inventoryItemNullExpiry("rice", new BigDecimal("10"));
    var bundle =
        PlanTestData.provisionsBundle(
            PlanTestData.budget(new BigDecimal("50")), Map.of(), List.of(inv));
    PlanCompositionContext ctx =
        PlanTestData.scoringContext(List.of(), List.of(recipe), bundle, Map.of(), Map.of());
    CandidatePlan plan =
        PlanTestData.candidatePlan(
            WEEK, List.of(PlanTestData.assignment(UUID.randomUUID(), id, WEEK, 0, 2)));
    assertThat(calc.compute(plan, ctx)).isEqualByComparingTo(new BigDecimal("0.333333"));
  }

  /**
   * Item expiring in exactly 3 days → {@code threeDaysOrLess} tier (2.0), NOT aboveSevenDays. Kills
   * two mutants in {@code wasteValue}:
   *
   * <ul>
   *   <li>L141 ConditionalsBoundary {@code days <= 3} → {@code days < 3}: at days==3 the mutated
   *       guard is false → falls through to aboveSevenDays (1.0) → score 1/3 not 2/3.
   *   <li>L142 NullReturnVals {@code return tiers.threeDaysOrLess()} → {@code return null}: NPE.
   * </ul>
   *
   * covered = 2 × 2.0 = 4; max = 2 × 3.0 = 6 → 0.666667.
   */
  @Test
  void item_expiring_in_exactly_three_days_uses_three_day_tier() {
    UUID id = UUID.randomUUID();
    RecipeDto recipe = PlanTestData.scoredRecipe(id, 20, "Thai", "tofu", "fry", List.of("rice"));
    var inv = PlanTestData.inventoryItem("rice", new BigDecimal("10"), 3);
    var bundle =
        PlanTestData.provisionsBundle(
            PlanTestData.budget(new BigDecimal("50")), Map.of(), List.of(inv));
    PlanCompositionContext ctx =
        PlanTestData.scoringContext(List.of(), List.of(recipe), bundle, Map.of(), Map.of());
    CandidatePlan plan =
        PlanTestData.candidatePlan(
            WEEK, List.of(PlanTestData.assignment(UUID.randomUUID(), id, WEEK, 0, 2)));
    assertThat(calc.compute(plan, ctx)).isEqualByComparingTo(new BigDecimal("0.666667"));
  }

  /**
   * Item expiring in exactly 1 day → {@code oneDayOrLess} tier (3.0). Pins the {@code days <= 1}
   * boundary so the most-urgent tier is reached at the boundary, not just below it.
   */
  @Test
  void item_expiring_in_exactly_one_day_uses_most_urgent_tier() {
    UUID id = UUID.randomUUID();
    RecipeDto recipe = PlanTestData.scoredRecipe(id, 20, "Thai", "tofu", "fry", List.of("rice"));
    var inv = PlanTestData.inventoryItem("rice", new BigDecimal("10"), 1);
    var bundle =
        PlanTestData.provisionsBundle(
            PlanTestData.budget(new BigDecimal("50")), Map.of(), List.of(inv));
    PlanCompositionContext ctx =
        PlanTestData.scoringContext(List.of(), List.of(recipe), bundle, Map.of(), Map.of());
    CandidatePlan plan =
        PlanTestData.candidatePlan(
            WEEK, List.of(PlanTestData.assignment(UUID.randomUUID(), id, WEEK, 0, 2)));
    // covered = 2 × 3.0 = 6; max = 2 × 3.0 = 6 → 1.0
    assertThat(calc.compute(plan, ctx)).isEqualByComparingTo(BigDecimal.ONE);
  }

  /**
   * All waste-value tiers configured to 0 forces the {@code maxWasteValue <= 0} guard (L104) to
   * reset maxWasteValue to 1.0 so the per-ingredient max is non-zero, while every covered
   * contribution stays 0 → score 0.0. The boundary mutant {@code <= 0} → {@code < 0} leaves
   * maxWasteValue at 0, collapsing maxValue to 0 and returning neutral 0.5 instead — observable.
   */
  @Test
  void zero_waste_tiers_floor_max_value_to_one_score_zero() {
    var props =
        new PlannerProperties(
            java.time.DayOfWeek.MONDAY,
            20,
            5,
            3,
            50,
            new BigDecimal("1.5"),
            java.time.Duration.ofSeconds(30),
            PlanTestData.uniformWeights(),
            new PlannerProperties.ScoringTuning(
                PlanTestData.defaultTuning().variety(),
                new PlannerProperties.ScoringTuning.ProvisionsTuning(
                    new PlannerProperties.ScoringTuning.ProvisionsTuning.WasteValueTiers(
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)),
                PlanTestData.defaultTuning().cost()),
            java.time.Duration.ofSeconds(20),
            3,
            5,
            2,
            PlanTestData.defaultMidWeek(),
            PlanTestData.defaultMateriality());
    ProvisionsSubScore zeroTierCalc = new ProvisionsSubScore(props);
    UUID id = UUID.randomUUID();
    RecipeDto recipe = PlanTestData.scoredRecipe(id, 20, "Thai", "tofu", "fry", List.of("rice"));
    var inv = PlanTestData.inventoryItem("rice", new BigDecimal("10"), 30);
    var bundle =
        PlanTestData.provisionsBundle(
            PlanTestData.budget(new BigDecimal("50")), Map.of(), List.of(inv));
    PlanCompositionContext ctx =
        PlanTestData.scoringContext(List.of(), List.of(recipe), bundle, Map.of(), Map.of());
    CandidatePlan plan =
        PlanTestData.candidatePlan(
            WEEK, List.of(PlanTestData.assignment(UUID.randomUUID(), id, WEEK, 0, 2)));
    assertThat(zeroTierCalc.compute(plan, ctx)).isEqualByComparingTo(BigDecimal.ZERO);
  }

  /**
   * Zero-serving assignment → demand for the ingredient key is 0 (non-empty map, zero value) →
   * maxValue sums to 0 → the {@code maxValue == 0} guard returns neutral 0.5. Kills the L122
   * NullReturnVals mutant ({@code return NEUTRAL} → {@code return null}); a null return would NPE
   * on isEqualByComparingTo.
   */
  @Test
  void zero_serving_demand_collapses_max_value_to_zero_returns_neutral() {
    UUID id = UUID.randomUUID();
    RecipeDto recipe = PlanTestData.scoredRecipe(id, 20, "Thai", "tofu", "fry", List.of("rice"));
    var inv = PlanTestData.inventoryItem("rice", new BigDecimal("10"), 30);
    var bundle =
        PlanTestData.provisionsBundle(
            PlanTestData.budget(new BigDecimal("50")), Map.of(), List.of(inv));
    PlanCompositionContext ctx =
        PlanTestData.scoringContext(List.of(), List.of(recipe), bundle, Map.of(), Map.of());
    CandidatePlan plan =
        PlanTestData.candidatePlan(
            WEEK, List.of(PlanTestData.assignment(UUID.randomUUID(), id, WEEK, 0, 0)));
    assertThat(calc.compute(plan, ctx)).isEqualByComparingTo(new BigDecimal("0.5"));
  }

  /**
   * An ACTIVE inventory item with a {@code null} expiry date (cannot use the time-relative helper).
   */
  private static com.example.mealprep.provisions.api.dto.InventoryItemDto inventoryItemNullExpiry(
      String mappingKey, BigDecimal quantity) {
    return new com.example.mealprep.provisions.api.dto.InventoryItemDto(
        UUID.randomUUID(),
        UUID.randomUUID(),
        mappingKey,
        "grocery",
        com.example.mealprep.provisions.domain.entity.StorageLocation.CUPBOARD,
        com.example.mealprep.provisions.domain.entity.TrackingMode.QUANTITY,
        quantity,
        "g",
        BigDecimal.ZERO,
        com.example.mealprep.provisions.domain.entity.StapleStatus.STOCKED,
        false,
        null,
        mappingKey,
        null,
        com.example.mealprep.provisions.domain.entity.ItemSource.MANUAL_ADD,
        null,
        com.example.mealprep.provisions.domain.entity.ItemLifecycleStatus.ACTIVE,
        null,
        java.time.Instant.parse("2026-01-01T00:00:00Z"),
        java.time.Instant.parse("2026-01-01T00:00:00Z"),
        0L);
  }
}
