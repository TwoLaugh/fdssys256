package com.example.mealprep.planner.domain.service.internal.scoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.mealprep.core.types.SlotKind;
import com.example.mealprep.nutrition.api.dto.CalorieTargetDto;
import com.example.mealprep.nutrition.api.dto.CandidatePlanRollupDto;
import com.example.mealprep.nutrition.api.dto.FloorGateResultDto;
import com.example.mealprep.nutrition.api.dto.MacroTargetDto;
import com.example.mealprep.nutrition.api.dto.TargetsDto;
import com.example.mealprep.nutrition.domain.entity.EnforcementDirection;
import com.example.mealprep.nutrition.domain.entity.Goal;
import com.example.mealprep.nutrition.domain.service.NutritionFloorGateService;
import com.example.mealprep.planner.api.dto.CandidatePlan;
import com.example.mealprep.planner.api.dto.MealSlotSkeleton;
import com.example.mealprep.planner.api.dto.PlanCompositionContext;
import com.example.mealprep.planner.api.dto.ScoreResult;
import com.example.mealprep.planner.api.dto.SlotAssignment;
import com.example.mealprep.planner.config.PlannerProperties;
import com.example.mealprep.planner.domain.service.internal.rollup.DailyCostAggregator;
import com.example.mealprep.planner.domain.service.internal.rollup.DailyMacroAggregator;
import com.example.mealprep.planner.domain.service.internal.rollup.WeeklyCostConfidence;
import com.example.mealprep.planner.testdata.PlanTestData;
import com.example.mealprep.preference.PreferenceModule;
import com.example.mealprep.preference.domain.service.TasteSimilarityQueryService;
import com.example.mealprep.recipe.api.dto.NutritionPerServingDto;
import com.example.mealprep.recipe.api.dto.RecipeDto;
import com.example.mealprep.provisions.api.dto.ProvisionForPlannerBundleDto;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Correctness gate for the incremental Stage-A scorer. Builds a range of representative composition
 * contexts + partial plans of varying length and asserts that the incremental composite (computed by
 * appending slots one-by-one, exactly as {@code BeamSearchEngineImpl} does) equals the exact {@link
 * ScoringEngine#score}{@code .composite()} for the same plan, byte-for-byte ({@code compareTo == 0}).
 *
 * <p>Both engines are built from the SAME sub-score beans / gates / properties, so any divergence is
 * a bug in the incremental accumulators, not differing inputs. The {@link NutritionFloorGateService}
 * is a single shared mock answering deterministically off the rollup it is handed — so the real
 * gate and the incremental gate (which build byte-identical rollups) always agree.
 */
class IncrementalScoringOracleTest {

  private static final LocalDate WEEK = LocalDate.of(2026, 1, 5);

  // ---- shared beans (one set drives BOTH engines) ---------------------------------------------

  private final PlannerProperties props = PlanTestData.scoringProperties();

  private final PreferenceModule preferenceModule = mock(PreferenceModule.class);
  private final TasteSimilarityQueryService tasteSimilarity = mock(TasteSimilarityQueryService.class);

  private final DailyMacroAggregator macroAggregator = new DailyMacroAggregator();
  private final NutritionFloorGateService floorGateService = mock(NutritionFloorGateService.class);

  private final PreferenceSubScore preferenceSubScore = new PreferenceSubScore(preferenceModule);
  private final NutritionSubScore nutritionSubScore = new NutritionSubScore(macroAggregator);
  private final CostSubScore costSubScore =
      new CostSubScore(props, new DailyCostAggregator(), new WeeklyCostConfidence(props));
  private final VarietySubScore varietySubScore = new VarietySubScore(props);
  private final TimeSubScore timeSubScore = new TimeSubScore();
  private final BatchSubScore batchSubScore = new BatchSubScore();
  private final ProvisionsSubScore provisionsSubScore = new ProvisionsSubScore(props);
  private final NutritionFloorGate floorGate =
      new NutritionFloorGate(floorGateService, macroAggregator);
  private final VarietyGate varietyGate = new VarietyGate(props);

  private final ScoringEngine exact =
      new ScoringEngineImpl(
          List.of(
              preferenceSubScore,
              nutritionSubScore,
              costSubScore,
              varietySubScore,
              timeSubScore,
              batchSubScore,
              provisionsSubScore),
          floorGate,
          varietyGate,
          props);

  private final IncrementalScoringEngine incremental =
      new IncrementalScoringEngine(
          preferenceSubScore,
          nutritionSubScore,
          varietySubScore,
          timeSubScore,
          batchSubScore,
          costSubScore,
          provisionsSubScore,
          floorGate,
          varietyGate,
          macroAggregator,
          props);

  IncrementalScoringOracleTest() {
    when(preferenceModule.tasteSimilarity()).thenReturn(tasteSimilarity);
    // Deterministic taste vector for any eater — exercises the preference cosine (not the neutral
    // fallback). A different eater id would still resolve to a vector, so multi-eater slots merge.
    lenient()
        .when(tasteSimilarity.getTasteVector(any(UUID.class)))
        .thenReturn(Optional.of(new float[] {0.2f, -0.4f, 0.7f, 0.1f}));
    // Floor gate: deterministic off the rollup so the real + incremental gates always agree. Fail
    // the gate when any day's calories are absurdly low (< 50) — lets a sparse plan trip it.
    lenient()
        .when(floorGateService.evaluate(any(UUID.class), any(CandidatePlanRollupDto.class)))
        .thenAnswer(
            inv -> {
              CandidatePlanRollupDto rollup = inv.getArgument(1);
              boolean ok =
                  rollup.perDay().stream().allMatch(d -> d.calories() >= 50);
              return new FloorGateResultDto(ok, List.of(), ok ? "ok" : "low");
            });
  }

  // ---- the oracle assertion -------------------------------------------------------------------

  /**
   * Append the assignments one-by-one through the incremental engine (mirroring the beam) and assert
   * the running composite at EVERY prefix equals the exact engine scoring that same prefix plan.
   */
  private void assertIncrementalMatchesExactForEveryPrefix(
      List<SlotAssignment> assignments, PlanCompositionContext ctx) {
    Object state = incremental.emptyState(ctx);
    List<SlotAssignment> prefix = new ArrayList<>();

    // empty prefix
    CandidatePlan emptyPlan = PlanTestData.candidatePlan(WEEK, List.copyOf(prefix));
    assertExact(incremental.composite(state, emptyPlan, ctx), emptyPlan, ctx, 0);

    for (int i = 0; i < assignments.size(); i++) {
      SlotAssignment a = assignments.get(i);
      state = incremental.append(state, a, ctx);
      prefix.add(a);
      CandidatePlan plan = PlanTestData.candidatePlan(WEEK, List.copyOf(prefix));
      assertExact(incremental.composite(state, plan, ctx), plan, ctx, i + 1);
    }
  }

  private void assertExact(
      BigDecimal incrementalComposite, CandidatePlan plan, PlanCompositionContext ctx, int len) {
    ScoreResult exactResult = exact.score(plan, ctx);
    assertThat(incrementalComposite.compareTo(exactResult.composite()))
        .as(
            "incremental composite (%s) must equal exact composite (%s) at prefix length %d",
            incrementalComposite, exactResult.composite(), len)
        .isZero();
  }

  // ---- scenarios ------------------------------------------------------------------------------

  @Test
  void empty_plan_no_targets_no_budget() {
    PlanCompositionContext ctx = PlanTestData.minimalContext(List.of(), List.of());
    assertIncrementalMatchesExactForEveryPrefix(List.of(), ctx);
  }

  @Test
  void single_slot_no_targets() {
    UUID r = UUID.randomUUID();
    MealSlotSkeleton skel = PlanTestData.skeletonFor(WEEK, 0, SlotKind.DINNER, 30);
    RecipeDto recipe = fullRecipe(r, 25, "Italian", "chicken", "bake");
    PlanCompositionContext ctx = scoringCtx(List.of(skel), List.of(recipe), null, Map.of());
    assertIncrementalMatchesExactForEveryPrefix(
        List.of(PlanTestData.assignment(skel.slotId(), r, WEEK, 0, 2)), ctx);
  }

  @Test
  void multi_day_full_plan_with_targets() {
    UUID user = UUID.randomUUID();
    List<MealSlotSkeleton> skels = new ArrayList<>();
    List<RecipeDto> pool = new ArrayList<>();
    List<SlotAssignment> assignments = new ArrayList<>();
    String[] cuisines = {"Italian", "Thai", "Indian", "Mexican", "French", "Japanese"};
    String[] proteins = {"chicken", "tofu", "beef", "fish"};
    String[] methods = {"bake", "fry", "grill"};
    for (int d = 0; d < 3; d++) {
      for (int s = 0; s < 3; s++) {
        int idx = d * 3 + s;
        SlotKind kind = s == 0 ? SlotKind.BREAKFAST : s == 1 ? SlotKind.LUNCH : SlotKind.DINNER;
        MealSlotSkeleton skel =
            PlanTestData.skeletonFor(WEEK.plusDays(d), s, kind, 20 + idx * 5);
        UUID r = UUID.randomUUID();
        RecipeDto recipe =
            fullRecipe(
                r,
                15 + idx * 4,
                cuisines[idx % cuisines.length],
                proteins[idx % proteins.length],
                methods[idx % methods.length]);
        skels.add(skel);
        pool.add(recipe);
        assignments.add(PlanTestData.assignment(skel.slotId(), r, WEEK.plusDays(d), s, 2));
      }
    }
    TargetsDto targets = targetsFor(user);
    PlanCompositionContext ctx = scoringCtx(skels, pool, user, Map.of(user, targets));
    assertIncrementalMatchesExactForEveryPrefix(assignments, ctx);
  }

  @Test
  void plan_with_null_recipe_ids_and_missing_recipes() {
    UUID user = UUID.randomUUID();
    MealSlotSkeleton s0 = PlanTestData.skeletonFor(WEEK, 0, SlotKind.LUNCH, 30);
    MealSlotSkeleton s1 = PlanTestData.skeletonFor(WEEK, 1, SlotKind.DINNER, 45);
    UUID present = UUID.randomUUID();
    RecipeDto recipe = fullRecipe(present, 30, "Thai", "tofu", "fry");
    PlanCompositionContext ctx =
        scoringCtx(List.of(s0, s1), List.of(recipe), user, Map.of(user, targetsFor(user)));

    List<SlotAssignment> assignments = new ArrayList<>();
    // null recipeId assignment
    assignments.add(
        new SlotAssignment(
            UUID.randomUUID(),
            s0.slotId(),
            0,
            WEEK,
            SlotKind.LUNCH,
            null,
            null,
            null,
            2,
            false));
    // recipeId not in the pool (missing recipe)
    assignments.add(PlanTestData.assignment(s1.slotId(), UUID.randomUUID(), WEEK, 1, 2));
    // present recipe
    assignments.add(PlanTestData.assignment(s0.slotId(), present, WEEK, 0, 2));
    assertIncrementalMatchesExactForEveryPrefix(assignments, ctx);
  }

  @Test
  void multi_eater_slots() {
    UUID user = UUID.randomUUID();
    UUID e1 = UUID.randomUUID();
    UUID e2 = UUID.randomUUID();
    MealSlotSkeleton skel =
        PlanTestData.skeletonWithEaters(UUID.randomUUID(), WEEK, 0, List.of(e1, e2));
    UUID r = UUID.randomUUID();
    RecipeDto recipe = fullRecipe(r, 40, "French", "beef", "grill");
    PlanCompositionContext ctx =
        scoringCtx(List.of(skel), List.of(recipe), user, Map.of(user, targetsFor(user)));
    assertIncrementalMatchesExactForEveryPrefix(
        List.of(PlanTestData.assignment(skel.slotId(), r, WEEK, 0, 3)), ctx);
  }

  @Test
  void variety_gate_failure_collapses_to_zero_identically() {
    // maxRepeat is 2 (defaultTuning) → a 3rd appearance of the same recipe trips the variety gate
    // and the composite must collapse to 0 in BOTH engines.
    UUID user = UUID.randomUUID();
    UUID r = UUID.randomUUID();
    RecipeDto recipe = fullRecipe(r, 30, "Indian", "chicken", "bake");
    List<MealSlotSkeleton> skels = new ArrayList<>();
    List<SlotAssignment> assignments = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      MealSlotSkeleton skel = PlanTestData.skeletonFor(WEEK.plusDays(i), 0, SlotKind.DINNER, 30);
      skels.add(skel);
      assignments.add(PlanTestData.assignment(skel.slotId(), r, WEEK.plusDays(i), 0, 2));
    }
    PlanCompositionContext ctx = scoringCtx(skels, List.of(recipe), user, Map.of(user, targetsFor(user)));
    assertIncrementalMatchesExactForEveryPrefix(assignments, ctx);
  }

  @Test
  void floor_gate_failure_path_collapses_identically() {
    // A recipe with very low calories (< 50/day) trips the deterministic floor-gate mock → 0 in
    // both engines; a richer one passes. Exercises both gate outcomes within one append sequence.
    UUID user = UUID.randomUUID();
    UUID lean = UUID.randomUUID();
    RecipeDto leanRecipe =
        PlanTestData.scoredRecipeFull(
            lean,
            30,
            "Italian",
            "tofu",
            "fry",
            new float[] {0.1f, 0.2f, 0.3f, 0.4f},
            new NutritionPerServingDto(
                10, new BigDecimal("1"), new BigDecimal("2"), new BigDecimal("1"),
                new BigDecimal("0.5"), Map.of()));
    MealSlotSkeleton skel = PlanTestData.skeletonFor(WEEK, 0, SlotKind.DINNER, 30);
    PlanCompositionContext ctx =
        scoringCtx(List.of(skel), List.of(leanRecipe), user, Map.of(user, targetsFor(user)));
    assertIncrementalMatchesExactForEveryPrefix(
        List.of(PlanTestData.assignment(skel.slotId(), lean, WEEK, 0, 2)), ctx);
  }

  @Test
  void with_budget_and_inventory_cost_and_provisions_engaged() {
    UUID user = UUID.randomUUID();
    UUID r = UUID.randomUUID();
    RecipeDto recipe = fullRecipe(r, 30, "Mexican", "fish", "grill");
    ProvisionForPlannerBundleDto provisions =
        PlanTestData.provisionsBundle(
            PlanTestData.budget(new BigDecimal("50.00")),
            Map.of(
                "rice", PlanTestData.supplierProduct("rice", new BigDecimal("0.50")),
                "oil", PlanTestData.supplierProduct("oil", new BigDecimal("1.20"))),
            List.of(PlanTestData.inventoryItem("rice", new BigDecimal("100"), 2)));
    MealSlotSkeleton skel = PlanTestData.skeletonFor(WEEK, 0, SlotKind.DINNER, 30);
    PlanCompositionContext ctx =
        PlanTestData.scoringContext(
            List.of(skel),
            List.of(recipe),
            provisions,
            Map.of(
                user,
                new com.example.mealprep.household.api.dto.SoftPreferenceBundleDto(
                    user, null, null)),
            Map.of(user, targetsFor(user)));
    assertIncrementalMatchesExactForEveryPrefix(
        List.of(PlanTestData.assignment(skel.slotId(), r, WEEK, 0, 2)), ctx);
  }

  // ---- builders -------------------------------------------------------------------------------

  private RecipeDto fullRecipe(
      UUID id, int totalTimeMins, String cuisine, String protein, String method) {
    float[] embedding = {
      (float) (id.getLeastSignificantBits() % 7) * 0.1f,
      (float) (id.getMostSignificantBits() % 5) * 0.2f,
      0.3f,
      -0.15f
    };
    NutritionPerServingDto nutrition =
        new NutritionPerServingDto(
            450,
            new BigDecimal("32.5"),
            new BigDecimal("48.0"),
            new BigDecimal("14.2"),
            new BigDecimal("6.0"),
            Map.of("iron_mg", new BigDecimal("3.2"), "calcium_mg", new BigDecimal("120")));
    return PlanTestData.scoredRecipeFull(
        id, totalTimeMins, cuisine, protein, method, embedding, nutrition);
  }

  private TargetsDto targetsFor(UUID user) {
    return new TargetsDto(
        UUID.randomUUID(),
        user,
        Goal.MAINTAIN,
        new CalorieTargetDto(2000, 0, 0, "daily", EnforcementDirection.BOTH_BOUNDED),
        new MacroTargetDto(new BigDecimal("120"), null, "daily", EnforcementDirection.LOWER_FLOOR, true),
        new MacroTargetDto(new BigDecimal("200"), null, "daily", EnforcementDirection.UPPER_LIMIT, true),
        new MacroTargetDto(new BigDecimal("60"), null, "daily", EnforcementDirection.UPPER_LIMIT, true),
        new MacroTargetDto(new BigDecimal("30"), null, "daily", EnforcementDirection.LOWER_FLOOR, true),
        null,
        null,
        List.of(),
        List.of(),
        List.of(),
        null,
        List.of(),
        Instant.parse("2026-01-01T00:00:00Z"),
        0L);
  }

  /** Context with the given primary user wired into soft prefs so {@code primaryUserId} resolves. */
  private PlanCompositionContext scoringCtx(
      List<MealSlotSkeleton> skeletons,
      List<RecipeDto> pool,
      UUID primaryUser,
      Map<UUID, TargetsDto> nutritionByUserId) {
    Map<UUID, com.example.mealprep.household.api.dto.SoftPreferenceBundleDto> softPrefs =
        primaryUser == null
            ? Map.of()
            : Map.of(
                primaryUser,
                new com.example.mealprep.household.api.dto.SoftPreferenceBundleDto(
                    primaryUser, null, null));
    return PlanTestData.scoringContext(skeletons, pool, null, softPrefs, nutritionByUserId);
  }
}
