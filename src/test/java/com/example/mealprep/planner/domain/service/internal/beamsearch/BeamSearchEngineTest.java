package com.example.mealprep.planner.domain.service.internal.beamsearch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;

import com.example.mealprep.core.types.SlotKind;
import com.example.mealprep.planner.api.dto.BeamSearchOutcome;
import com.example.mealprep.planner.api.dto.CandidatePlan;
import com.example.mealprep.planner.api.dto.MealSlotSkeleton;
import com.example.mealprep.planner.api.dto.PlanCompositionContext;
import com.example.mealprep.planner.api.dto.RecipePoolSnapshot;
import com.example.mealprep.planner.api.dto.ScoreBreakdownDocument;
import com.example.mealprep.planner.api.dto.ScoreResult;
import com.example.mealprep.planner.api.dto.SlotAssignment;
import com.example.mealprep.planner.config.PlannerProperties;
import com.example.mealprep.planner.domain.service.internal.scoring.ScoringEngine;
import com.example.mealprep.planner.testdata.PlanTestData;
import com.example.mealprep.preference.api.dto.FilterResult;
import com.example.mealprep.preference.domain.service.HardConstraintFilterService;
import com.example.mealprep.provisions.api.dto.BundleStaleness;
import com.example.mealprep.provisions.api.dto.EquipmentDto;
import com.example.mealprep.provisions.api.dto.ProvisionForPlannerBundleDto;
import com.example.mealprep.recipe.api.dto.RecipeDto;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Pure-logic unit test for {@code BeamSearchEngineImpl}. Uses a deterministic stub {@link
 * ScoringEngine} (score = inverse of last recipe-id's least-significant bits — so higher id →
 * higher score) so canonical fixtures produce repeatable outputs.
 *
 * <p>Verifies: single-slot top-N, week-scale beam-cap loop invariant, empty pool no-throw,
 * fully-pinned no-expansion, and the timeout → greedy fallback path.
 */
class BeamSearchEngineTest {

  private static final LocalDate WEEK_START = LocalDate.of(2026, 1, 5);

  private HardConstraintFilterService filterService;
  private PlannerProperties properties;
  private HardFilterRunner hardFilterRunner;
  private BeamPruner pruner;
  private DeterministicScoringEngine scoring;
  private BeamSearchEngine engine;

  @BeforeEach
  void setup() {
    filterService = Mockito.mock(HardConstraintFilterService.class);
    lenient()
        .when(filterService.check(any(UUID.class), anyList()))
        .thenReturn(new FilterResult(true, List.of()));
    lenient()
        .when(filterService.checkForHousehold(anyList(), anyList()))
        .thenReturn(new FilterResult(true, List.of()));
    properties = newProps(Duration.ofSeconds(30));
    hardFilterRunner = new HardFilterRunner(filterService, properties);
    pruner = new BeamPruner();
    scoring = new DeterministicScoringEngine();
    engine = new BeamSearchEngineImpl(hardFilterRunner, pruner, scoring, properties);
  }

  @Test
  void single_slot_three_recipe_pool_width2_topn1() {
    MealSlotSkeleton slot = PlanTestData.skeletonFor(WEEK_START, 0, SlotKind.LUNCH, 60);
    UUID r1 = uuidOf(1);
    UUID r2 = uuidOf(2);
    UUID r3 = uuidOf(3); // highest id → highest deterministic score
    List<RecipeDto> pool =
        List.of(
            PlanTestData.trivialRecipe(r1, SlotKind.LUNCH),
            PlanTestData.trivialRecipe(r2, SlotKind.LUNCH),
            PlanTestData.trivialRecipe(r3, SlotKind.LUNCH));
    PlanCompositionContext ctx = ctxWith(List.of(slot), pool);

    BeamSearchOutcome outcome = engine.search(ctx, new BeamSearchConfig(2, 1, 50));

    assertThat(outcome.degradedToGreedy()).isFalse();
    assertThat(outcome.candidates()).hasSize(1);
    CandidatePlan top = outcome.candidates().get(0);
    assertThat(top.assignments()).hasSize(1);
    assertThat(top.assignments().get(0).recipeId()).isEqualTo(r3);
  }

  @Test
  void week_scale_beam_never_exceeds_width() {
    // 7 days × 3 slots = 21 slots; pool of 5 per slot; width=5 → beam stays at <=5 always.
    LocalDate week = WEEK_START;
    List<MealSlotSkeleton> slots = new ArrayList<>();
    for (int d = 0; d < 7; d++) {
      for (int s = 0; s < 3; s++) {
        SlotKind k = s == 0 ? SlotKind.BREAKFAST : s == 1 ? SlotKind.LUNCH : SlotKind.DINNER;
        slots.add(PlanTestData.skeletonFor(week.plusDays(d), s, k, 60));
      }
    }
    List<RecipeDto> pool = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      pool.add(PlanTestData.trivialRecipe(uuidOf(100 + i), SlotKind.BREAKFAST));
      pool.add(PlanTestData.trivialRecipe(uuidOf(200 + i), SlotKind.LUNCH));
      pool.add(PlanTestData.trivialRecipe(uuidOf(300 + i), SlotKind.DINNER));
    }

    PlanCompositionContext ctx = ctxWith(slots, pool);
    BeamSearchOutcome outcome = engine.search(ctx, new BeamSearchConfig(5, 3, 50));

    assertThat(outcome.candidates()).hasSize(3);
    for (CandidatePlan c : outcome.candidates()) {
      assertThat(c.assignments()).hasSize(21);
    }
  }

  @Test
  void empty_pool_at_slot_does_not_throw_and_leaves_slot_unfilled() {
    // Skeleton kind=BREAKFAST but only DINNER recipes in the pool → empty pool for that slot.
    MealSlotSkeleton breakfastSlot =
        PlanTestData.skeletonFor(WEEK_START, 0, SlotKind.BREAKFAST, 30);
    MealSlotSkeleton dinnerSlot = PlanTestData.skeletonFor(WEEK_START, 1, SlotKind.DINNER, 60);
    RecipeDto onlyDinner = PlanTestData.trivialRecipe(uuidOf(1), SlotKind.DINNER);
    PlanCompositionContext ctx = ctxWith(List.of(breakfastSlot, dinnerSlot), List.of(onlyDinner));

    BeamSearchOutcome outcome = engine.search(ctx, new BeamSearchConfig(2, 1, 50));

    assertThat(outcome.degradedToGreedy()).isFalse();
    assertThat(outcome.candidates()).hasSize(1);
    // Only the dinner slot got assigned; breakfast slot left unfilled (1 assignment total).
    assertThat(outcome.candidates().get(0).assignments()).hasSize(1);
    assertThat(outcome.candidates().get(0).assignments().get(0).kind()).isEqualTo(SlotKind.DINNER);
  }

  @Test
  void all_pinned_slots_produce_single_candidate_without_expansion() {
    MealSlotSkeleton slot = PlanTestData.skeletonFor(WEEK_START, 0, SlotKind.DINNER, 60);
    UUID recipeId = uuidOf(42);
    SlotAssignment pinned =
        new SlotAssignment(
            slot.dayId(),
            slot.slotId(),
            slot.slotIndex(),
            slot.onDate(),
            slot.kind(),
            recipeId,
            UUID.randomUUID(),
            UUID.randomUUID(),
            2,
            true);

    PlanCompositionContext ctx =
        new PlanCompositionContext(
            UUID.randomUUID(),
            WEEK_START,
            List.of(slot),
            Map.of(),
            Map.of(),
            null,
            simpleProvisions(),
            null,
            new RecipePoolSnapshot(List.<RecipeDto>of(), Instant.parse("2026-01-01T00:00:00Z")),
            List.of(pinned),
            UUID.randomUUID(),
            UUID.randomUUID(),
            Map.of());

    BeamSearchOutcome outcome = engine.search(ctx, new BeamSearchConfig(5, 1, 50));

    assertThat(outcome.candidates()).hasSize(1);
    assertThat(outcome.candidates().get(0).assignments()).hasSize(1);
    assertThat(outcome.candidates().get(0).assignments().get(0).recipeId()).isEqualTo(recipeId);
    assertThat(outcome.candidates().get(0).assignments().get(0).pinned()).isTrue();
  }

  @Test
  void timeout_triggers_greedy_fallback() {
    // 1ns timeout combined with a multi-slot, multi-recipe expansion → every attempt's slot loop
    // overruns after the first iteration, exercising both fallback levels and ending in greedy.
    PlannerProperties tinyTimeout = newProps(Duration.ofNanos(1));
    HardFilterRunner runner2 = new HardFilterRunner(filterService, tinyTimeout);
    BeamSearchEngine fastTimeoutEngine =
        new BeamSearchEngineImpl(runner2, pruner, scoring, tinyTimeout);

    List<MealSlotSkeleton> slots =
        List.of(
            PlanTestData.skeletonFor(WEEK_START, 0, SlotKind.BREAKFAST, 60),
            PlanTestData.skeletonFor(WEEK_START, 1, SlotKind.LUNCH, 60),
            PlanTestData.skeletonFor(WEEK_START, 2, SlotKind.DINNER, 60));
    List<RecipeDto> pool =
        List.of(
            PlanTestData.trivialRecipe(uuidOf(1), SlotKind.BREAKFAST),
            PlanTestData.trivialRecipe(uuidOf(2), SlotKind.LUNCH),
            PlanTestData.trivialRecipe(uuidOf(3), SlotKind.DINNER));
    PlanCompositionContext ctx = ctxWith(slots, pool);

    BeamSearchOutcome outcome = fastTimeoutEngine.search(ctx, new BeamSearchConfig(4, 2, 50));

    assertThat(outcome.degradedToGreedy()).isTrue();
  }

  @Test
  void determinism_same_fixture_same_output() {
    MealSlotSkeleton slot = PlanTestData.skeletonFor(WEEK_START, 0, SlotKind.LUNCH, 60);
    List<RecipeDto> pool =
        List.of(
            PlanTestData.trivialRecipe(uuidOf(1), SlotKind.LUNCH),
            PlanTestData.trivialRecipe(uuidOf(2), SlotKind.LUNCH),
            PlanTestData.trivialRecipe(uuidOf(3), SlotKind.LUNCH));
    PlanCompositionContext ctx = ctxWith(List.of(slot), pool);

    BeamSearchOutcome a = engine.search(ctx, new BeamSearchConfig(3, 3, 50));
    BeamSearchOutcome b = engine.search(ctx, new BeamSearchConfig(3, 3, 50));

    assertThat(a.candidates()).hasSameSizeAs(b.candidates());
    for (int i = 0; i < a.candidates().size(); i++) {
      assertThat(a.candidates().get(i).assignments().get(0).recipeId())
          .isEqualTo(b.candidates().get(i).assignments().get(0).recipeId());
    }
  }

  // ---- helpers --------------------------------------------------------------------------------

  private static PlannerProperties newProps(Duration timeout) {
    return new PlannerProperties(
        DayOfWeek.MONDAY,
        20,
        5,
        3,
        50,
        new BigDecimal("1.5"),
        timeout,
        null,
        null,
        Duration.ofSeconds(20),
        3,
        5,
        2,
        null);
  }

  private static PlanCompositionContext ctxWith(
      List<MealSlotSkeleton> skeletons, List<RecipeDto> recipes) {
    return new PlanCompositionContext(
        UUID.randomUUID(),
        WEEK_START,
        skeletons,
        Map.of(),
        Map.of(),
        null,
        simpleProvisions(),
        null,
        new RecipePoolSnapshot(recipes, Instant.parse("2026-01-01T00:00:00Z")),
        List.of(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        Map.of());
  }

  private static ProvisionForPlannerBundleDto simpleProvisions() {
    List<EquipmentDto> eq =
        List.of(
            new EquipmentDto(UUID.randomUUID(), UUID.randomUUID(), "pan", true, null, 0L),
            new EquipmentDto(UUID.randomUUID(), UUID.randomUUID(), "knife", true, null, 0L),
            new EquipmentDto(UUID.randomUUID(), UUID.randomUUID(), "oven", true, null, 0L),
            new EquipmentDto(UUID.randomUUID(), UUID.randomUUID(), "stand-mixer", true, null, 0L));
    return new ProvisionForPlannerBundleDto(
        UUID.randomUUID(),
        List.of(),
        List.of(),
        eq,
        null,
        Map.of(),
        new BundleStaleness(10_000, false, Instant.parse("2026-01-01T00:00:00Z")));
  }

  private static UUID uuidOf(int seed) {
    return new UUID(0L, seed);
  }

  /**
   * Deterministic scoring engine: composite = sum of {@code lsb / 1e9} across each assigned recipe.
   * Higher recipe id → higher score, monotonic and tie-broken by recipe-id stable order. Plan-level
   * (not per-slot incremental) — the search sums across the assignment list.
   */
  private static final class DeterministicScoringEngine implements ScoringEngine {
    private final Map<UUID, BigDecimal> scoresByRecipe = new HashMap<>();

    @Override
    public ScoreResult score(CandidatePlan plan, PlanCompositionContext context) {
      BigDecimal total = BigDecimal.ZERO;
      for (SlotAssignment a : plan.assignments()) {
        total =
            total.add(
                scoresByRecipe.computeIfAbsent(
                    a.recipeId(),
                    id -> BigDecimal.valueOf(id.getLeastSignificantBits()).movePointLeft(9)));
      }
      ScoreBreakdownDocument breakdown =
          new ScoreBreakdownDocument(
              BigDecimal.ZERO,
              BigDecimal.ZERO,
              BigDecimal.ZERO,
              BigDecimal.ZERO,
              BigDecimal.ZERO,
              BigDecimal.ZERO,
              BigDecimal.ZERO,
              total,
              true,
              true,
              "v1-uniform-test");
      return new ScoreResult(total, breakdown);
    }
  }
}
