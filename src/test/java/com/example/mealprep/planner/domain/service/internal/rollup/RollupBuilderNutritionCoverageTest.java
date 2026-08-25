package com.example.mealprep.planner.domain.service.internal.rollup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.example.mealprep.core.types.SlotKind;
import com.example.mealprep.nutrition.api.dto.CalorieTargetDto;
import com.example.mealprep.nutrition.api.dto.MacroTargetDto;
import com.example.mealprep.nutrition.api.dto.MicroTargetDto;
import com.example.mealprep.nutrition.api.dto.TargetsDto;
import com.example.mealprep.nutrition.domain.entity.EnforcementDirection;
import com.example.mealprep.planner.api.dto.CandidatePlan;
import com.example.mealprep.planner.api.dto.MealSlotSkeleton;
import com.example.mealprep.planner.api.dto.NutritionCoverageDocument;
import com.example.mealprep.planner.api.dto.NutritionTargetCoverageDocument;
import com.example.mealprep.planner.api.dto.PlanCompositionContext;
import com.example.mealprep.planner.api.dto.RollupSummaryDocument;
import com.example.mealprep.planner.domain.service.internal.scoring.NutritionFloorGate;
import com.example.mealprep.planner.testdata.PlanTestData;
import com.example.mealprep.recipe.api.dto.NutritionPerServingDto;
import com.example.mealprep.recipe.api.dto.RecipeDto;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Unit test for the nutrition-coverage section of {@link RollupBuilderImpl}: primary-user
 * resolution, daily-average projection, per-macro and per-micro MET/SHORT/NO_DATA scoring,
 * provenance blending, and the fat breakdown.
 */
class RollupBuilderNutritionCoverageTest {

  private static final LocalDate WEEK = LocalDate.of(2026, 1, 5);

  private final NutritionFloorGate floorGate = Mockito.mock(NutritionFloorGate.class);

  private RollupBuilderImpl builder() {
    return new RollupBuilderImpl(
        new DailyMacroAggregator(),
        new DailyCostAggregator(),
        new WeeklyCostConfidence(PlanTestData.scoringProperties()),
        floorGate);
  }

  private static BigDecimal bd(String v) {
    return new BigDecimal(v);
  }

  private static MacroTargetDto macro(String targetG, EnforcementDirection dir) {
    return new MacroTargetDto(targetG == null ? null : bd(targetG), null, null, dir, true);
  }

  private static MicroTargetDto micro(String key, String floor, String cap) {
    return new MicroTargetDto(
        key, floor == null ? null : bd(floor), cap == null ? null : bd(cap), null, null, false);
  }

  private static TargetsDto targets(
      CalorieTargetDto calories,
      MacroTargetDto protein,
      MacroTargetDto carbs,
      MacroTargetDto fat,
      MacroTargetDto fibre,
      MacroTargetDto satFat,
      List<MicroTargetDto> micros) {
    return new TargetsDto(
        UUID.randomUUID(),
        UUID.randomUUID(),
        null,
        calories,
        protein,
        carbs,
        fat,
        fibre,
        satFat,
        null,
        null,
        null,
        micros,
        null,
        null,
        null,
        0L);
  }

  private static MealSlotSkeleton skeleton(List<UUID> eaters) {
    return new MealSlotSkeleton(
        UUID.randomUUID(), UUID.randomUUID(), 0, WEEK, SlotKind.DINNER, "dinner", 30, true, eaters);
  }

  private static NutritionTargetCoverageDocument row(
      List<NutritionTargetCoverageDocument> rows, String key) {
    return rows.stream().filter(r -> key.equals(r.key())).findFirst().orElseThrow();
  }

  /**
   * Two days, two recipes, deliberately asymmetric figures so the daily averages land exactly on
   * each target's edge: kcal (2200+1800)/2 = 2000, protein 100.0, carbs 250.0, fat 77.0 (exactly
   * +10% over 70), fibre 30.0, saturated fat 20.0. Micro averages: iron 10, vitamin D 5, zinc 12,
   * sodium 1500, magnesium 100. The primary eater is userA (second skeleton; the first has no
   * eaters); the targets map lists userB first so map order must not win.
   */
  private RollupSummaryDocument buildTwoDayCoverage() {
    when(floorGate.passes(any(), any())).thenReturn(true);
    UUID userA = UUID.randomUUID();
    UUID userB = UUID.randomUUID();
    UUID r1 = UUID.randomUUID();
    UUID r2 = UUID.randomUUID();

    NutritionPerServingDto n1 =
        new NutritionPerServingDto(
            2200,
            bd("90.0"),
            bd("240.0"),
            bd("80.0"),
            bd("28.0"),
            Map.of(
                "iron_mg", bd("8"),
                "vitamin_d_mcg", bd("4"),
                "zinc_mg", bd("14"),
                "sodium_mg", bd("1400"),
                "magnesium_mg", bd("100"),
                "saturated_fat_g", bd("18"),
                "monounsaturated_fat_g", bd("30"),
                "polyunsaturated_fat_g", bd("10")));
    NutritionPerServingDto n2 =
        new NutritionPerServingDto(
            1800,
            bd("110.0"),
            bd("260.0"),
            bd("74.0"),
            bd("32.0"),
            Map.of(
                "iron_mg", bd("12"),
                "vitamin_d_mcg", bd("6"),
                "zinc_mg", bd("10"),
                "sodium_mg", bd("1600"),
                "magnesium_mg", bd("100"),
                "saturated_fat_g", bd("22"),
                "monounsaturated_fat_g", bd("20"),
                "polyunsaturated_fat_g", bd("14")),
            Map.of(
                "iron_mg", "estimated",
                "vitamin_d_mcg", "derived",
                "magnesium_mg", "self_reported"),
            Map.of());
    RecipeDto recipe1 = PlanTestData.scoredRecipeFull(r1, 30, "Thai", "tofu", "fry", null, n1);
    RecipeDto recipe2 = PlanTestData.scoredRecipeFull(r2, 30, "Thai", "beef", "fry", null, n2);

    TargetsDto targetsA =
        targets(
            new CalorieTargetDto(2000, 0, 0, "daily", EnforcementDirection.LOWER_FLOOR),
            macro("100", EnforcementDirection.LOWER_FLOOR),
            macro("250", EnforcementDirection.UPPER_LIMIT),
            macro("70", EnforcementDirection.BOTH_BOUNDED),
            macro("30", null),
            macro("15", EnforcementDirection.UPPER_LIMIT),
            Arrays.asList(
                null,
                micro(null, "1", null),
                micro("useless_mg", null, null),
                micro("iron_mg", "8", null),
                micro("vitamin_d_mcg", "5", null),
                micro("calcium_mg", "1000", null),
                micro("zinc_mg", null, "10"),
                micro("sodium_mg", null, "1500"),
                micro("magnesium_mg", "90", null)));
    // userB's row is a decoy with a single 555 kcal target; picking it would be visible everywhere
    TargetsDto targetsB =
        targets(
            new CalorieTargetDto(555, 0, 0, "daily", EnforcementDirection.LOWER_FLOOR),
            null,
            null,
            null,
            null,
            null,
            null);
    Map<UUID, TargetsDto> nutrition = new LinkedHashMap<>();
    nutrition.put(userB, targetsB);
    nutrition.put(userA, targetsA);

    List<MealSlotSkeleton> skeletons = List.of(skeleton(List.of()), skeleton(List.of(userA)));
    PlanCompositionContext ctx =
        PlanTestData.scoringContext(
            skeletons, List.of(recipe1, recipe2), null, Map.of(), nutrition);
    CandidatePlan plan =
        PlanTestData.candidatePlan(
            WEEK,
            List.of(
                PlanTestData.assignment(UUID.randomUUID(), r1, WEEK, 0, 2),
                PlanTestData.assignment(UUID.randomUUID(), r2, WEEK.plusDays(1), 0, 2)));
    return builder().build(plan, ctx);
  }

  @Test
  void macro_rows_score_each_direction_against_exact_daily_averages() {
    NutritionCoverageDocument cov = buildTwoDayCoverage().nutritionCoverage();

    assertThat(cov).isNotNull();
    assertThat(cov.macros())
        .extracting(NutritionTargetCoverageDocument::key)
        .containsExactly("calories", "protein", "carbs", "fat", "fibre", "saturated_fat");

    NutritionTargetCoverageDocument calories = row(cov.macros(), "calories");
    assertThat(calories.unit()).isEqualTo("kcal");
    assertThat(calories.target()).isEqualByComparingTo("2000");
    assertThat(calories.projectedDailyAvg()).isEqualByComparingTo("2000");
    assertThat(calories.direction()).isEqualTo("LOWER_FLOOR");
    assertThat(calories.met()).isTrue();
    assertThat(calories.status()).isEqualTo("MET");
    assertThat(calories.source()).isEqualTo("measured");

    // floor met exactly at target
    NutritionTargetCoverageDocument protein = row(cov.macros(), "protein");
    assertThat(protein.unit()).isEqualTo("g");
    assertThat(protein.projectedDailyAvg()).isEqualByComparingTo("100");
    assertThat(protein.direction()).isEqualTo("LOWER_FLOOR");
    assertThat(protein.status()).isEqualTo("MET");

    // ceiling met exactly at target
    NutritionTargetCoverageDocument carbs = row(cov.macros(), "carbs");
    assertThat(carbs.projectedDailyAvg()).isEqualByComparingTo("250");
    assertThat(carbs.direction()).isEqualTo("UPPER_LIMIT");
    assertThat(carbs.status()).isEqualTo("MET");

    // 77.0 sits exactly on the +10% band edge of 70
    NutritionTargetCoverageDocument fat = row(cov.macros(), "fat");
    assertThat(fat.projectedDailyAvg()).isEqualByComparingTo("77.0");
    assertThat(fat.direction()).isEqualTo("BOTH_BOUNDED");
    assertThat(fat.status()).isEqualTo("MET");

    // null direction defaults to the symmetric band
    NutritionTargetCoverageDocument fibre = row(cov.macros(), "fibre");
    assertThat(fibre.direction()).isEqualTo("BOTH_BOUNDED");
    assertThat(fibre.projectedDailyAvg()).isEqualByComparingTo("30.0");
    assertThat(fibre.status()).isEqualTo("MET");

    NutritionTargetCoverageDocument satFat = row(cov.macros(), "saturated_fat");
    assertThat(satFat.target()).isEqualByComparingTo("15");
    assertThat(satFat.projectedDailyAvg()).isEqualByComparingTo("20.0");
    assertThat(satFat.met()).isFalse();
    assertThat(satFat.status()).isEqualTo("SHORT");

    assertThat(cov.macrosMet()).isEqualTo(5);
    assertThat(cov.macrosTotal()).isEqualTo(6);
  }

  @Test
  void micro_rows_score_floors_caps_missing_data_and_provenance() {
    NutritionCoverageDocument cov = buildTwoDayCoverage().nutritionCoverage();

    assertThat(cov).isNotNull();
    // null entries, null keys and bound-less targets are dropped
    assertThat(cov.micros())
        .extracting(NutritionTargetCoverageDocument::key)
        .containsExactly(
            "iron_mg", "vitamin_d_mcg", "calcium_mg", "zinc_mg", "sodium_mg", "magnesium_mg");

    NutritionTargetCoverageDocument iron = row(cov.micros(), "iron_mg");
    assertThat(iron.unit()).isEqualTo("mg");
    assertThat(iron.target()).isEqualByComparingTo("8");
    assertThat(iron.projectedDailyAvg()).isEqualByComparingTo("10");
    assertThat(iron.direction()).isEqualTo("LOWER_FLOOR");
    assertThat(iron.met()).isTrue();
    assertThat(iron.status()).isEqualTo("MET");
    // one estimated day drags the whole projection down to estimated
    assertThat(iron.source()).isEqualTo("estimated");

    // floor met exactly at target
    NutritionTargetCoverageDocument vitD = row(cov.micros(), "vitamin_d_mcg");
    assertThat(vitD.unit()).isEqualTo("mcg");
    assertThat(vitD.projectedDailyAvg()).isEqualByComparingTo("5");
    assertThat(vitD.status()).isEqualTo("MET");
    assertThat(vitD.source()).isEqualTo("derived");

    // no recipe carries calcium: unknown, not a measured zero
    NutritionTargetCoverageDocument calcium = row(cov.micros(), "calcium_mg");
    assertThat(calcium.projectedDailyAvg()).isNull();
    assertThat(calcium.met()).isFalse();
    assertThat(calcium.status()).isEqualTo("NO_DATA");
    assertThat(calcium.source()).isNull();

    NutritionTargetCoverageDocument zinc = row(cov.micros(), "zinc_mg");
    assertThat(zinc.target()).isEqualByComparingTo("10");
    assertThat(zinc.projectedDailyAvg()).isEqualByComparingTo("12");
    assertThat(zinc.direction()).isEqualTo("UPPER_LIMIT");
    assertThat(zinc.met()).isFalse();
    assertThat(zinc.status()).isEqualTo("SHORT");
    assertThat(zinc.source()).isEqualTo("measured");

    // ceiling met exactly at the cap
    NutritionTargetCoverageDocument sodium = row(cov.micros(), "sodium_mg");
    assertThat(sodium.projectedDailyAvg()).isEqualByComparingTo("1500");
    assertThat(sodium.status()).isEqualTo("MET");

    // equal trust keeps the earlier day's label
    NutritionTargetCoverageDocument magnesium = row(cov.micros(), "magnesium_mg");
    assertThat(magnesium.status()).isEqualTo("MET");
    assertThat(magnesium.source()).isEqualTo("measured");

    assertThat(cov.microsMet()).isEqualTo(4);
    assertThat(cov.microsTotal()).isEqualTo(6);
    assertThat(cov.microsNoData()).isEqualTo(1);
  }

  @Test
  void fat_breakdown_carries_saturated_mono_and_poly_averages() {
    NutritionCoverageDocument cov = buildTwoDayCoverage().nutritionCoverage();

    assertThat(cov).isNotNull();
    assertThat(cov.fatBreakdown()).isNotNull();
    assertThat(cov.fatBreakdown().saturatedG()).isEqualByComparingTo("20.0");
    assertThat(cov.fatBreakdown().monounsaturatedG()).isEqualByComparingTo("25");
    assertThat(cov.fatBreakdown().polyunsaturatedG()).isEqualByComparingTo("12");
  }

  @Test
  void coverage_is_null_when_no_user_has_targets() {
    when(floorGate.passes(any(), any())).thenReturn(true);
    UUID id = UUID.randomUUID();
    RecipeDto recipe = PlanTestData.scoredRecipe(id, 30, "Thai", "tofu", "fry", List.of("rice"));
    CandidatePlan plan =
        PlanTestData.candidatePlan(
            WEEK, List.of(PlanTestData.assignment(UUID.randomUUID(), id, WEEK, 0, 2)));
    PlanCompositionContext ctx = PlanTestData.minimalContext(List.of(), List.of(recipe));

    assertThat(builder().build(plan, ctx).nutritionCoverage()).isNull();
  }

  @Test
  void coverage_is_null_for_a_plan_with_no_days_even_with_targets() {
    when(floorGate.passes(any(), any())).thenReturn(true);
    UUID userA = UUID.randomUUID();
    TargetsDto t =
        targets(
            new CalorieTargetDto(2000, 0, 0, "daily", EnforcementDirection.LOWER_FLOOR),
            null,
            null,
            null,
            null,
            null,
            null);
    PlanCompositionContext ctx =
        PlanTestData.scoringContext(
            List.of(skeleton(List.of(userA))), List.of(), null, Map.of(), Map.of(userA, t));

    CandidatePlan emptyPlan = PlanTestData.candidatePlan(WEEK, List.of());
    assertThat(builder().build(emptyPlan, ctx).nutritionCoverage()).isNull();
  }

  @Test
  void zero_targets_omit_the_calories_row_and_pass_the_macro_vacuously() {
    when(floorGate.passes(any(), any())).thenReturn(true);
    UUID userA = UUID.randomUUID();
    UUID r = UUID.randomUUID();
    NutritionPerServingDto n =
        new NutritionPerServingDto(500, bd("40.0"), bd("50.0"), bd("10.0"), bd("5.0"), Map.of());
    RecipeDto recipe = PlanTestData.scoredRecipeFull(r, 30, "Thai", "tofu", "fry", null, n);
    // calories target 0 drops the row; a 0g protein ceiling can never bind, so it reads MET
    TargetsDto t =
        targets(
            new CalorieTargetDto(0, 0, 0, "daily", EnforcementDirection.LOWER_FLOOR),
            macro("0", EnforcementDirection.UPPER_LIMIT),
            null,
            null,
            null,
            null,
            null);
    PlanCompositionContext ctx =
        PlanTestData.scoringContext(
            List.of(skeleton(List.of(userA))), List.of(recipe), null, Map.of(), Map.of(userA, t));
    CandidatePlan plan =
        PlanTestData.candidatePlan(
            WEEK, List.of(PlanTestData.assignment(UUID.randomUUID(), r, WEEK, 0, 2)));

    NutritionCoverageDocument cov = builder().build(plan, ctx).nutritionCoverage();

    assertThat(cov).isNotNull();
    assertThat(cov.macros())
        .extracting(NutritionTargetCoverageDocument::key)
        .containsExactly("protein");
    NutritionTargetCoverageDocument protein = row(cov.macros(), "protein");
    assertThat(protein.projectedDailyAvg()).isEqualByComparingTo("40.0");
    assertThat(protein.met()).isTrue();
    assertThat(protein.status()).isEqualTo("MET");
    assertThat(cov.macrosMet()).isEqualTo(1);
    assertThat(cov.micros()).isEmpty();
    assertThat(cov.microsTotal()).isZero();
    // no saturated/mono/poly data anywhere in the plan
    assertThat(cov.fatBreakdown()).isNull();
  }

  @Test
  void primary_user_falls_back_to_first_targets_row_when_first_eater_has_none() {
    when(floorGate.passes(any(), any())).thenReturn(true);
    UUID userA = UUID.randomUUID();
    UUID eaterWithoutTargets = UUID.randomUUID();
    UUID r = UUID.randomUUID();
    NutritionPerServingDto n =
        new NutritionPerServingDto(1800, bd("80.0"), bd("200.0"), bd("60.0"), bd("25.0"), Map.of());
    RecipeDto recipe = PlanTestData.scoredRecipeFull(r, 30, "Thai", "tofu", "fry", null, n);
    TargetsDto t =
        targets(
            new CalorieTargetDto(1800, 0, 0, "daily", EnforcementDirection.LOWER_FLOOR),
            null,
            null,
            null,
            null,
            null,
            null);
    PlanCompositionContext ctx =
        PlanTestData.scoringContext(
            List.of(skeleton(List.of(eaterWithoutTargets))),
            List.of(recipe),
            null,
            Map.of(),
            Map.of(userA, t));
    CandidatePlan plan =
        PlanTestData.candidatePlan(
            WEEK, List.of(PlanTestData.assignment(UUID.randomUUID(), r, WEEK, 0, 2)));

    NutritionCoverageDocument cov = builder().build(plan, ctx).nutritionCoverage();

    assertThat(cov).isNotNull();
    NutritionTargetCoverageDocument calories = row(cov.macros(), "calories");
    assertThat(calories.target()).isEqualByComparingTo("1800");
    assertThat(calories.status()).isEqualTo("MET");
  }

  @Test
  void null_assignments_yield_empty_daily_and_no_coverage() {
    CandidatePlan plan = new CandidatePlan(UUID.randomUUID(), WEEK, null, null);
    PlanCompositionContext ctx = PlanTestData.minimalContext(List.of(), List.of());

    RollupSummaryDocument doc = builder().build(plan, ctx);

    assertThat(doc.daily()).isEmpty();
    assertThat(doc.nutritionCoverage()).isNull();
    assertThat(doc.weekly().kcalTotal()).isZero();
  }

  @Test
  void missing_recipe_pool_yields_a_zeroed_day_with_an_unfilled_violation() {
    when(floorGate.passes(any(), any())).thenReturn(true);
    CandidatePlan plan =
        PlanTestData.candidatePlan(
            WEEK,
            List.of(PlanTestData.assignment(UUID.randomUUID(), UUID.randomUUID(), WEEK, 0, 2)));
    PlanCompositionContext ctx =
        new PlanCompositionContext(
            UUID.randomUUID(),
            WEEK,
            List.of(),
            Map.of(),
            Map.of(),
            null,
            null,
            null,
            null,
            List.of(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            Map.of());

    RollupSummaryDocument doc = builder().build(plan, ctx);

    assertThat(doc.daily()).hasSize(1);
    assertThat(doc.daily().get(0).totalTimeMin()).isZero();
    assertThat(doc.daily().get(0).violations()).anyMatch(v -> v.contains("unfilled"));
    assertThat(doc.nutritionCoverage()).isNull();
  }
}
