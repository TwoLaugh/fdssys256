package com.example.mealprep.planner.domain.service.internal.beamsearch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.mealprep.core.types.SlotKind;
import com.example.mealprep.planner.api.dto.MealSlotSkeleton;
import com.example.mealprep.planner.api.dto.PlanCompositionContext;
import com.example.mealprep.planner.api.dto.RecipePoolSnapshot;
import com.example.mealprep.planner.config.PlannerProperties;
import com.example.mealprep.planner.testdata.PlanTestData;
import com.example.mealprep.preference.api.dto.FilterResult;
import com.example.mealprep.preference.api.dto.Violation;
import com.example.mealprep.preference.domain.entity.ViolationKind;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Pure-logic unit test for {@link HardFilterRunner}. Each filter axis (kind, time-budget,
 * equipment, hard-constraints) has its own targeted case; the pool-cap and empty-pool behaviours
 * are also covered.
 *
 * <p>{@link HardConstraintFilterService} is mocked — preference-01b's contract is exercised
 * indirectly via Mockito stubbing.
 */
class HardFilterRunnerTest {

  private static final LocalDate WEEK_START = LocalDate.of(2026, 1, 5);

  private HardConstraintFilterService filterService;
  private PlannerProperties properties;
  private HardFilterRunner runner;

  @BeforeEach
  void setup() {
    filterService = Mockito.mock(HardConstraintFilterService.class);
    properties =
        new PlannerProperties(
            DayOfWeek.MONDAY,
            20,
            5,
            3,
            50,
            new BigDecimal("1.5"),
            Duration.ofSeconds(30),
            null,
            null,
            Duration.ofSeconds(20),
            3,
            5,
            2,
            null);
    runner = new HardFilterRunner(filterService, properties);
    when(filterService.check(any(UUID.class), anyList()))
        .thenReturn(new FilterResult(true, List.of()));
    when(filterService.checkForHousehold(anyList(), anyList()))
        .thenReturn(new FilterResult(true, List.of()));
  }

  @Test
  void wrong_kind_recipes_filtered_out() {
    MealSlotSkeleton breakfastSlot =
        PlanTestData.skeletonFor(WEEK_START, 0, SlotKind.BREAKFAST, 30);
    RecipeDto breakfast = PlanTestData.trivialRecipe(uuidOf(1), SlotKind.BREAKFAST);
    RecipeDto dinner = PlanTestData.trivialRecipe(uuidOf(2), SlotKind.DINNER);

    Map<UUID, List<RecipeDto>> pool =
        runner.filterPool(ctxWith(List.of(breakfastSlot), List.of(breakfast, dinner)));

    assertThat(pool.get(breakfastSlot.slotId()))
        .extracting(RecipeDto::id)
        .containsExactly(breakfast.id());
  }

  @Test
  void over_time_budget_recipe_filtered() {
    // budget = 30, ratio = 1.5 → cap = 45. Recipe with totalTimeMins=90 must be dropped, 45 kept.
    MealSlotSkeleton slot = PlanTestData.skeletonFor(WEEK_START, 0, SlotKind.LUNCH, 30);
    RecipeDto fast = PlanTestData.recipeFor(uuidOf(1), SlotKind.LUNCH, 45, List.of(), List.of());
    RecipeDto slow = PlanTestData.recipeFor(uuidOf(2), SlotKind.LUNCH, 90, List.of(), List.of());

    Map<UUID, List<RecipeDto>> pool =
        runner.filterPool(ctxWith(List.of(slot), List.of(fast, slow)));

    assertThat(pool.get(slot.slotId())).extracting(RecipeDto::id).containsExactly(fast.id());
  }

  @Test
  void recipe_needing_unavailable_equipment_is_filtered() {
    MealSlotSkeleton slot = PlanTestData.skeletonFor(WEEK_START, 0, SlotKind.DINNER, 60);
    RecipeDto needsMixer =
        PlanTestData.recipeFor(uuidOf(1), SlotKind.DINNER, 30, List.of("stand-mixer"), List.of());
    RecipeDto needsPan =
        PlanTestData.recipeFor(uuidOf(2), SlotKind.DINNER, 30, List.of("pan"), List.of());

    PlanCompositionContext ctx =
        ctxBuilderWith(
            List.of(slot), List.of(needsMixer, needsPan), provisionsWith(List.of("pan", "knife")));

    Map<UUID, List<RecipeDto>> pool = runner.filterPool(ctx);

    assertThat(pool.get(slot.slotId())).extracting(RecipeDto::id).containsExactly(needsPan.id());
  }

  @Test
  void empty_equipment_required_means_always_available() {
    MealSlotSkeleton slot = PlanTestData.skeletonFor(WEEK_START, 0, SlotKind.DINNER, 60);
    RecipeDto noEq = PlanTestData.recipeFor(uuidOf(1), SlotKind.DINNER, 30, List.of(), List.of());

    PlanCompositionContext ctx =
        ctxBuilderWith(List.of(slot), List.of(noEq), provisionsWith(List.of()));

    Map<UUID, List<RecipeDto>> pool = runner.filterPool(ctx);

    assertThat(pool.get(slot.slotId())).extracting(RecipeDto::id).containsExactly(noEq.id());
  }

  @Test
  void shared_slot_routes_through_check_for_household() {
    MealSlotSkeleton slot = PlanTestData.skeletonFor(WEEK_START, 0, SlotKind.DINNER, 60);
    RecipeDto r = PlanTestData.trivialRecipe(uuidOf(1), SlotKind.DINNER);

    runner.filterPool(ctxWith(List.of(slot), List.of(r)));

    verify(filterService).checkForHousehold(anyList(), anyList());
    verify(filterService, never()).check(any(UUID.class), anyList());
  }

  @Test
  void per_person_slot_routes_through_per_eater_check() {
    UUID eater = UUID.randomUUID();
    MealSlotSkeleton perPerson =
        new MealSlotSkeleton(
            UUID.randomUUID(),
            UUID.randomUUID(),
            0,
            WEEK_START,
            SlotKind.LUNCH,
            "lunch",
            60,
            false,
            new ArrayList<>(List.of(eater)));
    RecipeDto r = PlanTestData.trivialRecipe(uuidOf(1), SlotKind.LUNCH);

    runner.filterPool(ctxWith(List.of(perPerson), List.of(r)));

    verify(filterService).check(any(UUID.class), anyList());
    verify(filterService, never()).checkForHousehold(anyList(), anyList());
  }

  @Test
  void hard_constraint_failure_drops_recipe() {
    MealSlotSkeleton slot = PlanTestData.skeletonFor(WEEK_START, 0, SlotKind.DINNER, 60);
    RecipeDto r1 = PlanTestData.trivialRecipe(uuidOf(1), SlotKind.DINNER);
    RecipeDto r2 = PlanTestData.trivialRecipe(uuidOf(2), SlotKind.DINNER);

    when(filterService.checkForHousehold(anyList(), anyList()))
        .thenReturn(new FilterResult(true, List.of()))
        .thenReturn(
            new FilterResult(
                false,
                List.of(
                    new Violation(
                        UUID.randomUUID(), r2.id(), "peanut", ViolationKind.ALLERGY, "peanut"))));

    Map<UUID, List<RecipeDto>> pool = runner.filterPool(ctxWith(List.of(slot), List.of(r1, r2)));

    assertThat(pool.get(slot.slotId())).hasSize(1);
  }

  @Test
  void pool_exceeding_max_is_capped_in_id_order() {
    properties =
        new PlannerProperties(
            DayOfWeek.MONDAY,
            20,
            5,
            3,
            3,
            new BigDecimal("1.5"),
            Duration.ofSeconds(30),
            null,
            null,
            Duration.ofSeconds(20),
            3,
            5,
            2,
            null);
    runner = new HardFilterRunner(filterService, properties);

    MealSlotSkeleton slot = PlanTestData.skeletonFor(WEEK_START, 0, SlotKind.DINNER, 60);
    List<RecipeDto> many = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      many.add(PlanTestData.trivialRecipe(uuidOf(i + 1), SlotKind.DINNER));
    }

    Map<UUID, List<RecipeDto>> pool = runner.filterPool(ctxWith(List.of(slot), many));

    assertThat(pool.get(slot.slotId())).hasSize(3);
    assertThat(pool.get(slot.slotId()).get(0).id()).isEqualTo(uuidOf(1));
    assertThat(pool.get(slot.slotId()).get(1).id()).isEqualTo(uuidOf(2));
    assertThat(pool.get(slot.slotId()).get(2).id()).isEqualTo(uuidOf(3));
  }

  @Test
  void no_matching_recipes_yields_empty_list_at_key() {
    MealSlotSkeleton slot = PlanTestData.skeletonFor(WEEK_START, 0, SlotKind.BREAKFAST, 30);
    RecipeDto onlyDinner = PlanTestData.trivialRecipe(uuidOf(1), SlotKind.DINNER);

    Map<UUID, List<RecipeDto>> pool =
        runner.filterPool(ctxWith(List.of(slot), List.of(onlyDinner)));

    assertThat(pool).containsKey(slot.slotId());
    assertThat(pool.get(slot.slotId())).isEmpty();
  }

  // ---- helpers --------------------------------------------------------------------------------

  private PlanCompositionContext ctxWith(
      List<MealSlotSkeleton> skeletons, List<RecipeDto> recipes) {
    return ctxBuilderWith(skeletons, recipes, provisionsWith(List.of("pan", "knife", "oven")));
  }

  private static PlanCompositionContext ctxBuilderWith(
      List<MealSlotSkeleton> skeletons,
      List<RecipeDto> recipes,
      ProvisionForPlannerBundleDto prov) {
    return new PlanCompositionContext(
        UUID.randomUUID(),
        WEEK_START,
        skeletons,
        Map.of(),
        Map.of(),
        null,
        prov,
        null,
        new RecipePoolSnapshot(recipes, Instant.parse("2026-01-01T00:00:00Z")),
        List.of(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        Map.of());
  }

  private static ProvisionForPlannerBundleDto provisionsWith(List<String> equipmentNames) {
    List<EquipmentDto> eq = new ArrayList<>();
    for (String name : equipmentNames) {
      eq.add(new EquipmentDto(UUID.randomUUID(), UUID.randomUUID(), name, true, null, 0L));
    }
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
}
