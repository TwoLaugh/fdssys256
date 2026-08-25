package com.example.mealprep.planner.domain.service.internal.additions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.mealprep.ai.domain.service.AiService;
import com.example.mealprep.core.types.SlotKind;
import com.example.mealprep.planner.api.dto.Addition;
import com.example.mealprep.planner.api.dto.MealSlotSkeleton;
import com.example.mealprep.planner.api.dto.NutritionCoverageDocument;
import com.example.mealprep.planner.api.dto.NutritionTargetCoverageDocument;
import com.example.mealprep.planner.api.dto.PlanCompositionContext;
import com.example.mealprep.planner.api.dto.RollupSummaryDocument;
import com.example.mealprep.planner.api.dto.SlotAssignment;
import com.example.mealprep.planner.domain.service.internal.additions.AdditionPairingResult.AdditionPlacement;
import com.example.mealprep.planner.testdata.PlanTestData;
import com.example.mealprep.preference.api.dto.FilterResult;
import com.example.mealprep.preference.domain.service.HardConstraintFilterService;
import com.example.mealprep.recipe.api.dto.NutritionPerServingDto;
import com.example.mealprep.recipe.api.dto.RecipeDto;
import com.example.mealprep.recipe.api.dto.RecipeMetadataDto;
import com.example.mealprep.recipe.api.dto.RecipeVersionDto;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Pins the exact numeric behaviour of the Phase-2 addition planner: which candidate wins, in what
 * order picks land on slots, and where the residual/short-micro thresholds sit. Uses side-recipe
 * candidates with hand-picked nutrition (household eaters plus a rejecting allergy filter keep the
 * built-in catalogue out) so every expected score is computable by hand.
 */
class IngredientAdditionPlannerMutationTest {

  private static final LocalDate WEEK = LocalDate.of(2026, 1, 5);
  private static final FilterResult PASS = new FilterResult(true, List.of());
  private static final FilterResult FAIL = new FilterResult(false, List.of());

  private static IngredientAdditionPlanner planner(
      HardConstraintFilterService filter, AiService ai) {
    return new IngredientAdditionPlanner(new AdditionNutritionResolver(null), filter, ai);
  }

  private static HardConstraintFilterService rejectAll() {
    HardConstraintFilterService filter = mock(HardConstraintFilterService.class);
    when(filter.checkForHousehold(anyList(), anyList(), any())).thenReturn(FAIL);
    return filter;
  }

  /** Context with a real eater (so the allergy filter is consulted) and a null-eaters skeleton. */
  private static PlanCompositionContext eaterContext(List<RecipeDto> pool) {
    MealSlotSkeleton withEaters =
        new MealSlotSkeleton(
            UUID.randomUUID(),
            UUID.randomUUID(),
            0,
            WEEK,
            SlotKind.DINNER,
            "dinner",
            30,
            true,
            List.of(UUID.randomUUID()));
    MealSlotSkeleton nullEaters =
        new MealSlotSkeleton(
            UUID.randomUUID(),
            UUID.randomUUID(),
            1,
            WEEK,
            SlotKind.DINNER,
            "dinner",
            30,
            true,
            null);
    return PlanTestData.minimalContext(List.of(withEaters, nullEaters), pool);
  }

  private static NutritionTargetCoverageDocument target(
      String key, String targetValue, String projected, String status) {
    return new NutritionTargetCoverageDocument(
        key,
        "u",
        new BigDecimal(targetValue),
        new BigDecimal(projected),
        "LOWER_FLOOR",
        false,
        status,
        "measured");
  }

  private static RollupSummaryDocument rollup(
      List<NutritionTargetCoverageDocument> macros, List<NutritionTargetCoverageDocument> micros) {
    return new RollupSummaryDocument(
        List.of(), null, new NutritionCoverageDocument(macros, micros, 0, 1, 0, 1, 0));
  }

  private static List<NutritionTargetCoverageDocument> caloriesShort(
      String targetValue, String projected) {
    return List.of(target("calories", targetValue, projected, "SHORT"));
  }

  /** Snack-tagged pool recipe with hand-picked per-serving nutrition and ingredient keys. */
  private static RecipeDto side(
      String name, int kcal, Map<String, String> micros, List<String> ingredientKeys) {
    RecipeDto base =
        PlanTestData.scoredRecipe(
            UUID.randomUUID(), 15, "Generic", "tofu", "roast", ingredientKeys);
    RecipeVersionDto v = base.currentVersionBody();
    RecipeMetadataDto m = v.metadata();
    RecipeMetadataDto snackMeta =
        new RecipeMetadataDto(
            m.servings(),
            m.prepTimeMins(),
            m.cookTimeMins(),
            m.totalTimeMins(),
            m.equipmentRequired(),
            m.fridgeDays(),
            m.freezerWeeks(),
            m.packable(),
            m.cuisine(),
            List.of("snack"));
    Map<String, BigDecimal> mic = new LinkedHashMap<>();
    micros.forEach((k, val) -> mic.put(k, new BigDecimal(val)));
    NutritionPerServingDto nut =
        new NutritionPerServingDto(
            kcal,
            new BigDecimal("5"),
            new BigDecimal("10"),
            new BigDecimal("4"),
            new BigDecimal("3"),
            mic);
    RecipeVersionDto sv =
        new RecipeVersionDto(
            v.id(),
            v.branchId(),
            v.versionNumber(),
            v.parentVersionId(),
            v.trigger(),
            v.changeReason(),
            v.embeddingStatus(),
            v.createdAt(),
            v.createdByActor(),
            v.adapterTraceId(),
            v.ingredients(),
            v.methodSteps(),
            snackMeta,
            v.tags(),
            v.appliedSubstitutionIds(),
            v.embedding(),
            nut);
    return new RecipeDto(
        base.id(),
        base.userId(),
        base.catalogue(),
        name,
        base.description(),
        base.currentVersion(),
        base.currentBranchId(),
        base.dataQuality(),
        base.nutritionStatus(),
        base.forkedFromRecipeId(),
        base.lastUsedInPlanAt(),
        base.archivedAt(),
        base.deletedAt(),
        base.imageUrl(),
        base.optimisticVersion(),
        base.createdAt(),
        base.updatedAt(),
        sv,
        base.branches());
  }

  private static RecipeDto side(String name, int kcal, Map<String, String> micros) {
    return side(name, kcal, micros, List.of());
  }

  private static List<SlotAssignment> week(int days, int slotsPerDay) {
    List<SlotAssignment> out = new ArrayList<>();
    for (int d = 0; d < days; d++) {
      for (int s = 0; s < slotsPerDay; s++) {
        out.add(
            PlanTestData.assignment(UUID.randomUUID(), UUID.randomUUID(), WEEK.plusDays(d), s, 2));
      }
    }
    return out;
  }

  private static List<Addition> additionsAt(
      List<SlotAssignment> result, LocalDate day, int slotIndex) {
    return result.stream()
        .filter(a -> day.equals(a.onDate()) && a.slotIndex() == slotIndex)
        .flatMap(a -> a.additions().stream())
        .toList();
  }

  private static List<String> namesAt(List<SlotAssignment> result, LocalDate day, int slotIndex) {
    return additionsAt(result, day, slotIndex).stream().map(Addition::name).toList();
  }

  @Test
  void residual_is_read_from_the_short_calories_row_only() {
    // Decoy rows: null entry, a SHORT non-calorie macro with a huge gap, a MET calories row.
    // Only the SHORT calories row counts: residual 150.
    List<NutritionTargetCoverageDocument> macros =
        Arrays.asList(
            null,
            target("protein_g", "1000", "100", "SHORT"),
            target("calories", "2000", "2000", "MET"),
            target("calories", "3000", "2850", "SHORT"));
    RecipeDto herbRice = side("Herb Rice", 140, Map.of());
    RecipeDto beanBake = side("Bean Bake", 300, Map.of());
    IngredientAdditionPlanner planner = planner(rejectAll(), null);

    List<SlotAssignment> result =
        planner.attach(
            week(2, 2), rollup(macros, List.of()), eaterContext(List.of(herbRice, beanBake)));

    // 150 kcal short: Bean Bake scores 2.0 (capped fill), Herb Rice 1.87. One pick closes it.
    // Day 1 rotates the bench window onto Herb Rice, one slot along.
    assertThat(namesAt(result, WEEK, 0)).containsExactly("Bean Bake");
    assertThat(namesAt(result, WEEK, 1)).isEmpty();
    assertThat(namesAt(result, WEEK.plusDays(1), 0)).isEmpty();
    assertThat(namesAt(result, WEEK.plusDays(1), 1)).containsExactly("Herb Rice");
  }

  @Test
  void short_micros_keep_only_short_rows_and_drive_picks_without_calories() {
    // No macros at all, so the residual is 0 and only the zinc gap of 20 drives the greedy.
    List<NutritionTargetCoverageDocument> micros =
        Arrays.asList(
            null, target("copper_mg", "50", "10", "MET"), target("zinc_mg", "20", "0", "SHORT"));
    RecipeDto seedMix = side("Seed Mix", 100, Map.of("zinc_mg", "12", "selenium_mcg", "5"));
    RecipeDto oatCup = side("Oat Cup", 100, Map.of("zinc_mg", "8"));
    IngredientAdditionPlanner planner = planner(rejectAll(), null);

    List<SlotAssignment> result =
        planner.attach(week(1, 2), rollup(null, micros), eaterContext(List.of(seedMix, oatCup)));

    // Seed Mix fills 12 of 20 (0.6) and wins; Oat Cup then exactly fills the remaining 8 (1.0).
    assertThat(namesAt(result, WEEK, 0)).containsExactly("Seed Mix");
    assertThat(namesAt(result, WEEK, 1)).containsExactly("Oat Cup");
  }

  @Test
  void exact_micro_fill_removes_the_gap_and_stops_the_greedy() {
    List<NutritionTargetCoverageDocument> micros = List.of(target("iron_mg", "10", "0", "SHORT"));
    RecipeDto lentilCup = side("Lentil Cup", 60, Map.of("iron_mg", "10", "selenium_mcg", "4"));
    RecipeDto cornCup = side("Corn Cup", 30, Map.of());
    IngredientAdditionPlanner planner = planner(rejectAll(), null);

    List<SlotAssignment> result =
        planner.attach(
            week(1, 2),
            rollup(caloriesShort("3000", "2900"), micros),
            eaterContext(List.of(lentilCup, cornCup)));

    // Lentil Cup (score 2.2) zeroes the iron gap; residual drops to 40, so nothing else is added.
    assertThat(namesAt(result, WEEK, 0)).containsExactly("Lentil Cup");
    assertThat(namesAt(result, WEEK, 1)).isEmpty();
  }

  @Test
  void micro_score_is_the_filled_fraction_of_each_gap() {
    List<NutritionTargetCoverageDocument> micros =
        List.of(
            target("potassium_mg", "100", "0", "SHORT"), target("vitamin_c_mg", "4", "0", "SHORT"));
    RecipeDto potatoCup = side("Potato Cup", 90, Map.of("potassium_mg", "50"));
    RecipeDto pepperCup = side("Pepper Cup", 80, Map.of("vitamin_c_mg", "4"));
    IngredientAdditionPlanner planner = planner(rejectAll(), null);

    List<SlotAssignment> result =
        planner.attach(
            week(1, 2), rollup(null, micros), eaterContext(List.of(potatoCup, pepperCup)));

    // Filling all of a 4mg gap (1.0) outranks filling half of a 100mg gap (0.5).
    assertThat(namesAt(result, WEEK, 0)).containsExactly("Pepper Cup");
    assertThat(namesAt(result, WEEK, 1)).containsExactly("Potato Cup");
  }

  @Test
  void calorie_fill_is_weighted_double() {
    List<NutritionTargetCoverageDocument> micros =
        List.of(target("folate_mcg", "10", "0", "SHORT"));
    RecipeDto riceBowl = side("Rice Bowl", 80, Map.of());
    RecipeDto greenCup = side("Green Cup", 10, Map.of("folate_mcg", "8"));
    IngredientAdditionPlanner planner = planner(rejectAll(), null);

    List<SlotAssignment> result =
        planner.attach(
            week(1, 2),
            rollup(caloriesShort("3000", "2900"), micros),
            eaterContext(List.of(riceBowl, greenCup)));

    // Rice Bowl 80/100 * 2 = 1.6 beats Green Cup 0.2 + 0.8 = 1.0. At half weight it would lose.
    assertThat(namesAt(result, WEEK, 0)).containsExactly("Rice Bowl");
    assertThat(namesAt(result, WEEK, 1)).containsExactly("Green Cup");
  }

  @Test
  void calorie_fill_is_a_capped_fraction_of_the_residual() {
    List<NutritionTargetCoverageDocument> micros =
        List.of(target("calcium_mg", "50", "0", "SHORT"));
    RecipeDto yogurtCup = side("Yogurt Cup", 20, Map.of("calcium_mg", "50"));
    RecipeDto nutBowl = side("Nut Bowl", 100, Map.of());
    IngredientAdditionPlanner planner = planner(rejectAll(), null);

    List<SlotAssignment> result =
        planner.attach(
            week(1, 2),
            rollup(caloriesShort("3000", "2800"), micros),
            eaterContext(List.of(yogurtCup, nutBowl)));

    // Yogurt Cup 0.2 + 1.0 = 1.2 beats Nut Bowl's 1.0. Raw kcal-times-residual would flip it.
    assertThat(namesAt(result, WEEK, 0)).containsExactly("Yogurt Cup");
    assertThat(namesAt(result, WEEK, 1)).containsExactly("Nut Bowl");
  }

  @Test
  void tied_scores_keep_the_first_candidate() {
    RecipeDto kaleCup = side("Kale Cup", 100, Map.of());
    RecipeDto slawCup = side("Slaw Cup", 100, Map.of());
    IngredientAdditionPlanner planner = planner(rejectAll(), null);

    List<SlotAssignment> result =
        planner.attach(
            week(1, 1),
            rollup(caloriesShort("3000", "2850"), List.of()),
            eaterContext(List.of(kaleCup, slawCup)));

    assertThat(namesAt(result, WEEK, 0)).containsExactly("Kale Cup");
  }

  @Test
  void candidate_that_helps_nothing_is_never_picked() {
    // Gap is vitamin D only; the sole safe candidate carries neither vitamin D nor useful kcal
    // (residual is 0), so its score is 0 and the input list comes back untouched.
    List<NutritionTargetCoverageDocument> micros =
        List.of(target("vitamin_d_mcg", "10", "0", "SHORT"));
    RecipeDto cornBowl = side("Corn Bowl", 100, Map.of("selenium_mcg", "5"));
    IngredientAdditionPlanner planner = planner(rejectAll(), null);
    List<SlotAssignment> assignments = week(1, 1);

    List<SlotAssignment> result =
        planner.attach(assignments, rollup(List.of(), micros), eaterContext(List.of(cornBowl)));

    assertThat(result).isSameAs(assignments);
  }

  @Test
  void residual_at_the_threshold_still_consults_candidates_but_adds_nothing() {
    HardConstraintFilterService filter = rejectAll();
    RecipeDto peaCup = side("Pea Cup", 50, Map.of());
    IngredientAdditionPlanner planner = planner(filter, null);
    List<SlotAssignment> assignments = week(1, 1);

    List<SlotAssignment> result =
        planner.attach(
            assignments,
            rollup(caloriesShort("3000", "2920"), List.of()),
            eaterContext(List.of(peaCup)));

    // Exactly 80 kcal short: not below the floor, so candidates are gathered (the allergy
    // filter runs), but the greedy needs strictly more than 80 to pick anything.
    assertThat(result).isSameAs(assignments);
    verify(filter, atLeastOnce()).checkForHousehold(anyList(), anyList(), any());
  }

  @Test
  void zero_gap_short_row_is_not_a_gap() {
    HardConstraintFilterService filter = rejectAll();
    List<NutritionTargetCoverageDocument> micros = List.of(target("copper_mg", "5", "5", "SHORT"));
    IngredientAdditionPlanner planner = planner(filter, null);
    List<SlotAssignment> assignments = week(1, 1);

    List<SlotAssignment> result =
        planner.attach(
            assignments, rollup(caloriesShort("3000", "2990"), micros), eaterContext(List.of()));

    // Residual 10 and a SHORT row whose gap is zero: nothing to close, so the planner bails
    // before it ever gathers candidates.
    assertThat(result).isSameAs(assignments);
    verifyNoInteractions(filter);
  }

  @Test
  void side_candidates_are_capped_at_six() {
    List<RecipeDto> pool = new ArrayList<>();
    pool.add(side("Side One", 100, Map.of()));
    pool.add(side("Side Two", 100, Map.of()));
    pool.add(side("Side Three", 100, Map.of()));
    pool.add(side("Side Four", 100, Map.of()));
    pool.add(side("Side Five", 100, Map.of()));
    pool.add(side("Side Six", 100, Map.of()));
    // Seventh in pool order; would win the first pick outright if the cap let it in.
    pool.add(side("Side Seven", 300, Map.of()));
    IngredientAdditionPlanner planner = planner(rejectAll(), null);

    List<SlotAssignment> result =
        planner.attach(
            week(1, 3), rollup(caloriesShort("3600", "3250"), List.of()), eaterContext(pool));

    assertThat(namesAt(result, WEEK, 0)).containsExactly("Side One");
    assertThat(namesAt(result, WEEK, 1)).containsExactly("Side Two");
    assertThat(namesAt(result, WEEK, 2)).containsExactly("Side Three");
  }

  @Test
  void side_candidates_must_have_positive_side_sized_calories() {
    List<NutritionTargetCoverageDocument> micros = List.of(target("zinc_mg", "10", "0", "SHORT"));
    // Zero kcal excluded even though it would zero the zinc gap; exactly 350 kcal included.
    RecipeDto zeroCup = side("Zero Cup", 0, Map.of("zinc_mg", "10"));
    RecipeDto grainPlate = side("Grain Plate", 350, Map.of());
    IngredientAdditionPlanner planner = planner(rejectAll(), null);

    List<SlotAssignment> result =
        planner.attach(
            week(1, 1),
            rollup(caloriesShort("3600", "3200"), micros),
            eaterContext(List.of(zeroCup, grainPlate)));

    assertThat(namesAt(result, WEEK, 0)).containsExactly("Grain Plate");
  }

  @Test
  void side_allergy_check_sends_only_clean_ingredient_keys() {
    HardConstraintFilterService filter = mock(HardConstraintFilterService.class);
    when(filter.checkForHousehold(anyList(), anyList(), any()))
        .thenAnswer(
            inv -> {
              List<String> keys = inv.getArgument(1);
              return keys.equals(List.of("peas")) ? PASS : FAIL;
            });
    RecipeDto prawnCup = side("Prawn Cup", 300, Map.of(), List.of("shellfish"));
    RecipeDto peaMedley = side("Pea Medley", 200, Map.of(), Arrays.asList(null, "  ", "peas"));
    IngredientAdditionPlanner planner = planner(filter, null);

    List<SlotAssignment> result =
        planner.attach(
            week(1, 1),
            rollup(caloriesShort("3600", "3350"), List.of()),
            eaterContext(List.of(prawnCup, peaMedley)));

    // Prawn Cup fails the household check and stays out despite the better score; Pea Medley's
    // null and blank keys are stripped before the check.
    assertThat(namesAt(result, WEEK, 0)).containsExactly("Pea Medley");
  }

  @Test
  void null_assignments_and_no_safe_candidates_are_no_ops() {
    IngredientAdditionPlanner planner = planner(rejectAll(), null);
    RollupSummaryDocument gap = rollup(caloriesShort("3600", "3200"), List.of());

    assertThat(planner.attach(null, gap, eaterContext(List.of()))).isNull();

    List<SlotAssignment> assignments = week(1, 1);
    assertThat(planner.attach(assignments, gap, eaterContext(List.of()))).isSameAs(assignments);
  }

  @Test
  void additions_land_on_the_lowest_slot_index_regardless_of_input_order() {
    RecipeDto beanCup = side("Bean Cup", 300, Map.of());
    IngredientAdditionPlanner planner = planner(rejectAll(), null);
    List<SlotAssignment> assignments =
        List.of(
            PlanTestData.assignment(UUID.randomUUID(), UUID.randomUUID(), WEEK, 2, 2),
            PlanTestData.assignment(UUID.randomUUID(), UUID.randomUUID(), WEEK, 0, 2),
            PlanTestData.assignment(UUID.randomUUID(), UUID.randomUUID(), WEEK, 1, 2));

    List<SlotAssignment> result =
        planner.attach(
            assignments,
            rollup(caloriesShort("3600", "3280"), List.of()),
            eaterContext(List.of(beanCup)));

    assertThat(namesAt(result, WEEK, 0)).containsExactly("Bean Cup");
    assertThat(namesAt(result, WEEK, 1)).isEmpty();
    assertThat(namesAt(result, WEEK, 2)).isEmpty();
  }

  @Test
  void bench_appends_gap_relevant_alternatives_by_descending_score() {
    List<NutritionTargetCoverageDocument> micros =
        List.of(target("magnesium_mg", "10", "0", "SHORT"));
    // Pool order deliberately not score order; Corn Chip is gap-irrelevant (score 0).
    RecipeDto almondCup = side("Almond Cup", 100, Map.of("magnesium_mg", "10"));
    RecipeDto oatSquare = side("Oat Square", 100, Map.of("magnesium_mg", "2"));
    RecipeDto seedBar = side("Seed Bar", 100, Map.of("magnesium_mg", "5"));
    RecipeDto cornChip = side("Corn Chip", 100, Map.of("selenium_mcg", "3"));
    IngredientAdditionPlanner planner = planner(rejectAll(), null);

    List<SlotAssignment> result =
        planner.attach(
            week(4, 1),
            rollup(null, micros),
            eaterContext(List.of(almondCup, oatSquare, seedBar, cornChip)));

    // Bench is [Almond Cup, Seed Bar, Oat Square]; the window slides one per day and wraps on
    // day 3. Corn Chip never appears.
    assertThat(namesAt(result, WEEK, 0)).containsExactly("Almond Cup");
    assertThat(namesAt(result, WEEK.plusDays(1), 0)).containsExactly("Seed Bar");
    assertThat(namesAt(result, WEEK.plusDays(2), 0)).containsExactly("Oat Square");
    assertThat(namesAt(result, WEEK.plusDays(3), 0)).containsExactly("Almond Cup");
  }

  @Test
  void bench_is_capped_at_six_and_rotates_across_the_week() {
    // Allow exactly seven catalogue ingredients through the allergy filter. Portion kcal:
    // walnuts 183, almonds 162, avocado 160, olive oil 119, banana 105, yogurt 100,
    // blueberries 84. The bench keeps the top six; blueberries miss the cut.
    Set<String> allowed =
        Set.of(
            "walnuts", "almonds", "avocado", "olive oil", "banana", "greek yogurt", "blueberries");
    HardConstraintFilterService filter = mock(HardConstraintFilterService.class);
    when(filter.checkForHousehold(anyList(), anyList(), any()))
        .thenAnswer(
            inv -> {
              List<String> keys = inv.getArgument(1);
              return keys.size() == 1 && allowed.contains(keys.get(0)) ? PASS : FAIL;
            });
    IngredientAdditionPlanner planner = planner(filter, null);

    List<SlotAssignment> result =
        planner.attach(
            week(5, 3), rollup(caloriesShort("6000", "1000"), List.of()), eaterContext(List.of()));

    // Day 0 is the greedy's own picks, best first.
    assertThat(additionsAt(result, WEEK, 0))
        .singleElement()
        .satisfies(a -> assertThat(a.nutrition().calories()).isEqualTo(183));
    assertThat(additionsAt(result, WEEK, 1))
        .singleElement()
        .satisfies(a -> assertThat(a.nutrition().calories()).isEqualTo(162));
    assertThat(additionsAt(result, WEEK, 2))
        .singleElement()
        .satisfies(a -> assertThat(a.nutrition().calories()).isEqualTo(160));
    // Day 4 wraps a six-strong bench: indices 4, 5, 6 are banana, yogurt, walnuts again.
    assertThat(additionsAt(result, WEEK.plusDays(4), 0))
        .singleElement()
        .satisfies(a -> assertThat(a.nutrition().calories()).isEqualTo(183));
    assertThat(additionsAt(result, WEEK.plusDays(4), 1))
        .singleElement()
        .satisfies(a -> assertThat(a.nutrition().calories()).isEqualTo(105));
    assertThat(additionsAt(result, WEEK.plusDays(4), 2))
        .singleElement()
        .satisfies(a -> assertThat(a.nutrition().calories()).isEqualTo(100));
  }

  @Test
  void llm_pairing_gets_first_day_meals_and_applies_non_blank_notes() {
    RecipeDto crispyKale = side("Crispy Kale", 200, Map.of());
    RecipeDto lemonPeas = side("Lemon Peas", 150, Map.of());
    // Over the side-size cap, so these are name lookups only, never candidates.
    RecipeDto oatBowl = side("Oat Bowl", 400, Map.of());
    RecipeDto unnamed = side(null, 400, Map.of());
    RecipeDto curry = side("Curry", 400, Map.of());

    AiService ai = mock(AiService.class);
    when(ai.execute(any()))
        .thenReturn(
            new AdditionPairingResult(
                Arrays.asList(
                    null,
                    new AdditionPlacement(null, "DINNER", "orphan"),
                    new AdditionPlacement("Crispy Kale", "BREAKFAST", "Great with the eggs"),
                    new AdditionPlacement("Lemon Peas", "DINNER", "   "))));
    IngredientAdditionPlanner planner = planner(rejectAll(), ai);

    SlotAssignment breakfast = slot(0, WEEK, SlotKind.BREAKFAST, oatBowl.id());
    SlotAssignment dinner = slot(2, WEEK, SlotKind.DINNER, unnamed.id());
    SlotAssignment snack = slot(3, WEEK, SlotKind.SNACK, null);
    SlotAssignment lunch = slot(1, WEEK.plusDays(1), SlotKind.LUNCH, curry.id());

    List<SlotAssignment> result =
        planner.attach(
            List.of(breakfast, dinner, snack, lunch),
            rollup(caloriesShort("3600", "3196"), List.of()),
            eaterContext(List.of(crispyKale, lemonPeas, oatBowl, unnamed, curry)));

    ArgumentCaptor<AdditionPairingTask> cap = ArgumentCaptor.forClass(AdditionPairingTask.class);
    verify(ai).execute(cap.capture());
    Map<String, Object> vars = cap.getValue().variables();
    assertThat((String) vars.get("additions")).contains("Crispy Kale").contains("side dish");
    assertThat(vars.get("meals")).isEqualTo("BREAKFAST: Oat Bowl\nDINNER: a meal");
    assertThat(vars.get("slot.kinds")).isEqualTo("BREAKFAST, DINNER, LUNCH");

    // Day 0: Crispy Kale (residual 404, score 0.99) then Lemon Peas. The real note replaces
    // the reasoning; the blank note does not. The recipe-less snack slot gets nothing.
    assertThat(additionsAt(result, WEEK, 0))
        .singleElement()
        .satisfies(
            a -> {
              assertThat(a.name()).isEqualTo("Crispy Kale");
              assertThat(a.reasoning()).isEqualTo("Great with the eggs");
            });
    assertThat(additionsAt(result, WEEK, 2))
        .singleElement()
        .satisfies(
            a -> {
              assertThat(a.name()).isEqualTo("Lemon Peas");
              assertThat(a.reasoning()).isEqualTo("side dish");
            });
    assertThat(additionsAt(result, WEEK, 3)).isEmpty();
    // Day 1 has one meal; the window puts Lemon Peas first, noted Crispy Kale second.
    assertThat(namesAt(result, WEEK.plusDays(1), 1)).containsExactly("Lemon Peas", "Crispy Kale");
  }

  @Test
  void broken_llm_payload_entries_are_skipped_and_placement_stays_deterministic() {
    RecipeDto crispyKale = side("Crispy Kale", 200, Map.of());
    AiService ai = mock(AiService.class);
    when(ai.execute(any())).thenReturn(new AdditionPairingResult(null));
    IngredientAdditionPlanner planner = planner(rejectAll(), ai);

    List<SlotAssignment> result =
        planner.attach(
            week(1, 1),
            rollup(caloriesShort("3600", "3300"), List.of()),
            eaterContext(List.of(crispyKale)));

    verify(ai).execute(any(AdditionPairingTask.class));
    assertThat(additionsAt(result, WEEK, 0))
        .singleElement()
        .satisfies(
            a -> {
              assertThat(a.name()).isEqualTo("Crispy Kale");
              assertThat(a.reasoning()).isEqualTo("side dish");
            });
  }

  private static SlotAssignment slot(
      int slotIndex, LocalDate onDate, SlotKind kind, UUID recipeId) {
    return new SlotAssignment(
        UUID.randomUUID(),
        UUID.randomUUID(),
        slotIndex,
        onDate,
        kind,
        recipeId,
        UUID.randomUUID(),
        UUID.randomUUID(),
        2,
        false);
  }
}
