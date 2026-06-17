package com.example.mealprep.planner.domain.service.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.core.types.SlotKind;
import com.example.mealprep.nutrition.api.dto.CalorieTargetDto;
import com.example.mealprep.nutrition.api.dto.MacroTargetDto;
import com.example.mealprep.nutrition.api.dto.PerMealDistributionDto;
import com.example.mealprep.nutrition.api.dto.TargetsDto;
import com.example.mealprep.nutrition.domain.entity.EnforcementDirection;
import com.example.mealprep.nutrition.domain.entity.Goal;
import com.example.mealprep.planner.api.dto.MealSlotSkeleton;
import com.example.mealprep.planner.api.dto.PlanCompositionContext;
import com.example.mealprep.planner.api.dto.SlotAssignment;
import com.example.mealprep.planner.testdata.PlanTestData;
import com.example.mealprep.recipe.api.dto.NutritionPerServingDto;
import com.example.mealprep.recipe.api.dto.RecipeDto;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit test for {@link PortionOptimizer} — the finalise-time per-day portioning optimisation that
 * replaces the beam's calorie-only {@code PortionScaler} proxy on the chosen plan. The optimiser
 * sizes the day's slots jointly to minimise total weighted deviation from ALL the primary eater's
 * daily macro targets at once; these tests pin (a) it beats calorie-only scaling on a
 * protein-dense + carb-heavy day, (b) factors stay on the [0.5, 3.0] step grid, (c) no targets →
 * all 1.0, (d) a recipe with no nutrition → 1.0 for that slot, (e) a protein FLOOR does not get
 * maxed out (overshoot is mildly penalised).
 */
class PortionOptimizerTest {

  private static final LocalDate DAY = LocalDate.of(2026, 1, 5);
  private final PortionOptimizer optimizer = new PortionOptimizer();

  // ---- (a) beats calorie-only scaling on a protein-dense + carb-heavy day ----------------------

  @Test
  void optimised_day_is_closer_to_all_targets_than_calorie_only_scaling() {
    UUID userId = UUID.randomUUID();
    // Protein-dense main: 500 kcal, 70 g protein, 10 g carbs, 18 g fat, 3 g fibre per serving.
    NutritionPerServingDto proteinDense =
        nutrition(500, "70", "10", "18", "3");
    // Carb-heavy main: 400 kcal, 8 g protein, 90 g carbs, 6 g fat, 10 g fibre per serving.
    NutritionPerServingDto carbHeavy =
        nutrition(400, "8", "90", "6", "10");

    UUID r1 = UUID.randomUUID();
    UUID r2 = UUID.randomUUID();
    RecipeDto recipe1 = recipeWithNutrition(r1, proteinDense);
    RecipeDto recipe2 = recipeWithNutrition(r2, carbHeavy);

    UUID slot1 = UUID.randomUUID();
    UUID slot2 = UUID.randomUUID();
    SlotAssignment a1 = dinnerAssignment(slot1, r1);
    SlotAssignment a2 = dinnerAssignment(slot2, r2);

    // Daily targets: 2000 kcal bounded, 150 g protein floor, 200 g carbs bounded, 60 g fat limit,
    // 30 g fibre floor. Per-meal DINNER target 1000 kcal so the calorie-only PortionScaler factors
    // are r1=1000/500=2.0, r2=1000/400=2.5.
    TargetsDto targets =
        targets(
            userId,
            2000,
            EnforcementDirection.BOTH_BOUNDED,
            macro("150", EnforcementDirection.LOWER_FLOOR), // protein floor
            macro("200", EnforcementDirection.BOTH_BOUNDED), // carbs bounded
            macro("60", EnforcementDirection.UPPER_LIMIT), // fat limit
            macro("30", EnforcementDirection.LOWER_FLOOR), // fibre floor
            1000); // per-meal DINNER kcal target

    PlanCompositionContext ctx =
        contextFor(userId, List.of(slot1, slot2), List.of(recipe1, recipe2), targets);

    List<SlotAssignment> result = optimizer.optimise(List.of(a1, a2), ctx);

    // Calorie-only baseline factors the optimiser is meant to beat.
    double calOnly1 = PortionScaler.factor(500, 1000, new BigDecimal("70"), null);
    double calOnly2 = PortionScaler.factor(400, 1000, new BigDecimal("8"), null);
    assertThat(calOnly1).isEqualTo(2.0);
    assertThat(calOnly2).isEqualTo(2.5);

    List<double[]> perServing =
        List.of(macroVec(proteinDense), macroVec(carbHeavy));
    List<PortionOptimizer.MacroTarget> macros =
        PortionOptimizer.configuredMacros(targets);

    double calOnlyObjective =
        PortionOptimizer.objective(perServing, new double[] {calOnly1, calOnly2}, macros);
    double optimisedObjective =
        PortionOptimizer.objective(
            perServing,
            new double[] {
              result.get(0).portionFactor().doubleValue(),
              result.get(1).portionFactor().doubleValue()
            },
            macros);

    // The joint optimiser must reach STRICTLY lower total weighted deviation than calorie-only.
    assertThat(optimisedObjective).isLessThan(calOnlyObjective);
  }

  // ---- (b) factors respect the [0.5, 3.0] step grid --------------------------------------------

  @Test
  void factors_stay_on_the_half_to_three_step_grid() {
    UUID userId = UUID.randomUUID();
    UUID r1 = UUID.randomUUID();
    UUID r2 = UUID.randomUUID();
    // One tiny recipe (would want to scale UP past 3.0) and one huge (would want to scale BELOW 0.5)
    // — the grid clamp must hold both.
    RecipeDto tiny = recipeWithNutrition(r1, nutrition(50, "5", "5", "1", "1"));
    RecipeDto huge = recipeWithNutrition(r2, nutrition(5000, "300", "300", "100", "50"));
    UUID s1 = UUID.randomUUID();
    UUID s2 = UUID.randomUUID();

    TargetsDto targets =
        targets(
            userId,
            2000,
            EnforcementDirection.BOTH_BOUNDED,
            macro("150", EnforcementDirection.LOWER_FLOOR),
            macro("200", EnforcementDirection.BOTH_BOUNDED),
            macro("60", EnforcementDirection.UPPER_LIMIT),
            macro("30", EnforcementDirection.LOWER_FLOOR),
            1000);
    PlanCompositionContext ctx =
        contextFor(userId, List.of(s1, s2), List.of(tiny, huge), targets);

    List<SlotAssignment> result =
        optimizer.optimise(List.of(dinnerAssignment(s1, r1), dinnerAssignment(s2, r2)), ctx);

    for (SlotAssignment a : result) {
      BigDecimal f = a.portionFactor();
      assertThat(f).isNotNull();
      double v = f.doubleValue();
      assertThat(v).isBetween(PortionScaler.MIN_FACTOR, PortionScaler.MAX_FACTOR);
      // On the 0.25 step grid: v / 0.25 is an integer.
      double steps = v / PortionScaler.STEP;
      assertThat(steps).isEqualTo(Math.rint(steps));
    }
  }

  // ---- (c) no targets → all 1.0 ----------------------------------------------------------------

  @Test
  void no_targets_leaves_every_factor_at_one() {
    UUID userId = UUID.randomUUID();
    UUID r1 = UUID.randomUUID();
    RecipeDto recipe = recipeWithNutrition(r1, nutrition(500, "30", "40", "15", "5"));
    UUID s1 = UUID.randomUUID();
    UUID s2 = UUID.randomUUID();
    // Context with a recipe pool but NO nutrition targets row.
    PlanCompositionContext ctx =
        contextFor(userId, List.of(s1, s2), List.of(recipe), null);

    List<SlotAssignment> result =
        optimizer.optimise(List.of(dinnerAssignment(s1, r1), dinnerAssignment(s2, r1)), ctx);

    assertThat(result.get(0).portionFactor()).isEqualByComparingTo(BigDecimal.ONE);
    assertThat(result.get(1).portionFactor()).isEqualByComparingTo(BigDecimal.ONE);
  }

  // ---- (d) a recipe with no nutrition → 1.0 for that slot --------------------------------------

  @Test
  void slot_with_no_recipe_nutrition_gets_factor_one() {
    UUID userId = UUID.randomUUID();
    UUID withN = UUID.randomUUID();
    UUID noN = UUID.randomUUID();
    RecipeDto withNutrition = recipeWithNutrition(withN, nutrition(500, "40", "40", "15", "5"));
    // A recipe present in the pool but with NO per-serving nutrition (PENDING status).
    RecipeDto noNutrition = PlanTestData.scoredRecipe(noN, 30, "Generic", "tofu", "fry", List.of());
    UUID s1 = UUID.randomUUID();
    UUID s2 = UUID.randomUUID();

    TargetsDto targets =
        targets(
            userId,
            2000,
            EnforcementDirection.BOTH_BOUNDED,
            macro("150", EnforcementDirection.LOWER_FLOOR),
            macro("200", EnforcementDirection.BOTH_BOUNDED),
            macro("60", EnforcementDirection.UPPER_LIMIT),
            macro("30", EnforcementDirection.LOWER_FLOOR),
            1000);
    PlanCompositionContext ctx =
        contextFor(userId, List.of(s1, s2), List.of(withNutrition, noNutrition), targets);

    List<SlotAssignment> result =
        optimizer.optimise(List.of(dinnerAssignment(s1, withN), dinnerAssignment(s2, noN)), ctx);

    // The no-nutrition slot must be exactly 1.0; the sizable slot may be optimised away from 1.0.
    assertThat(result.get(1).portionFactor()).isEqualByComparingTo(BigDecimal.ONE);
  }

  // ---- (e) overshooting a protein FLOOR is penalised → a protein-dense day doesn't max out -----

  @Test
  void protein_dense_day_does_not_max_out_servings_against_a_floor() {
    UUID userId = UUID.randomUUID();
    // Very protein-dense single dinner: 600 kcal, 60 g protein per serving. Calories floor only;
    // protein has a generous floor (100 g). With ONLY a "more is fine" mindset the optimiser could
    // pile servings up to 3.0 — but overshoot is mildly penalised on a floor, so it should stop near
    // the point that meets the floors, not the grid maximum.
    UUID r1 = UUID.randomUUID();
    RecipeDto recipe = recipeWithNutrition(r1, nutrition(600, "60", "30", "20", "5"));
    UUID s1 = UUID.randomUUID();

    // Calories floor 1200 (so 2 servings = 1200 meets it), protein floor 100 (2 servings = 120
    // meets it), carbs floor low, no fat/fibre pressure to push higher.
    TargetsDto targets =
        targets(
            userId,
            1200,
            EnforcementDirection.LOWER_FLOOR,
            macro("100", EnforcementDirection.LOWER_FLOOR), // protein floor
            macro("60", EnforcementDirection.LOWER_FLOOR), // carbs floor
            macro("80", EnforcementDirection.UPPER_LIMIT), // fat limit (caps over-scaling)
            macro("10", EnforcementDirection.LOWER_FLOOR), // fibre floor
            600);
    PlanCompositionContext ctx =
        contextFor(userId, List.of(s1), List.of(recipe), targets);

    List<SlotAssignment> result =
        optimizer.optimise(List.of(dinnerAssignment(s1, r1)), ctx);

    double factor = result.get(0).portionFactor().doubleValue();
    // It must NOT max out at the grid ceiling just because protein/calories are floors.
    assertThat(factor).isLessThan(PortionScaler.MAX_FACTOR);
  }

  // ---- helpers ---------------------------------------------------------------------------------

  private static double[] macroVec(NutritionPerServingDto n) {
    // Index-aligned with PortionOptimizer.Macro ordinal order:
    // CALORIES, PROTEIN, CARBS, FAT, FIBRE, SAT_FAT.
    return new double[] {
      n.calories(),
      n.proteinG().doubleValue(),
      n.carbsG().doubleValue(),
      n.fatG().doubleValue(),
      n.fibreG().doubleValue(),
      0.0
    };
  }

  private static NutritionPerServingDto nutrition(
      int kcal, String protein, String carbs, String fat, String fibre) {
    return new NutritionPerServingDto(
        kcal,
        new BigDecimal(protein),
        new BigDecimal(carbs),
        new BigDecimal(fat),
        new BigDecimal(fibre),
        Map.of());
  }

  private static RecipeDto recipeWithNutrition(UUID id, NutritionPerServingDto nutrition) {
    // A small non-null embedding keeps scoredRecipeFull's version body well-formed; only the
    // nutritionPerServing matters to the optimiser.
    return PlanTestData.scoredRecipeFull(
        id, 30, "Generic", "tofu", "fry", new float[] {0.1f, 0.2f}, nutrition);
  }

  private static SlotAssignment dinnerAssignment(UUID slotId, UUID recipeId) {
    return new SlotAssignment(
        UUID.randomUUID(),
        slotId,
        0,
        DAY,
        SlotKind.DINNER,
        recipeId,
        UUID.randomUUID(),
        UUID.randomUUID(),
        1,
        false);
  }

  private static MacroTargetDto macro(String targetG, EnforcementDirection direction) {
    return new MacroTargetDto(new BigDecimal(targetG), null, direction.name(), direction, true);
  }

  private static TargetsDto targets(
      UUID userId,
      int calories,
      EnforcementDirection calorieDirection,
      MacroTargetDto protein,
      MacroTargetDto carbs,
      MacroTargetDto fat,
      MacroTargetDto fibre,
      int perMealDinnerCalTarget) {
    return new TargetsDto(
        UUID.randomUUID(),
        userId,
        Goal.MAINTAIN,
        new CalorieTargetDto(calories, 100, 100, "daily_band", calorieDirection),
        protein,
        carbs,
        fat,
        fibre,
        null, // satFat
        null,
        List.of(),
        List.of(
            new PerMealDistributionDto(
                com.example.mealprep.nutrition.domain.entity.MealSlot.DINNER,
                perMealDinnerCalTarget,
                protein.targetG())),
        List.of(),
        null,
        List.of(),
        java.time.Instant.parse("2026-01-01T00:00:00Z"),
        0L);
  }

  private static PlanCompositionContext contextFor(
      UUID userId,
      List<UUID> slotIds,
      List<RecipeDto> recipePool,
      TargetsDto targets) {
    List<MealSlotSkeleton> skeletons = new ArrayList<>();
    int idx = 0;
    for (UUID slotId : slotIds) {
      skeletons.add(PlanTestData.skeletonWithEaters(slotId, DAY, idx++, List.of(userId)));
    }
    Map<UUID, TargetsDto> nutritionByUserId =
        targets == null ? Map.of() : Map.of(userId, targets);
    return PlanTestData.scoringContext(skeletons, recipePool, null, Map.of(), nutritionByUserId);
  }
}
