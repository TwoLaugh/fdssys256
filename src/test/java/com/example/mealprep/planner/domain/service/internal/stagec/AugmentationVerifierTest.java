package com.example.mealprep.planner.domain.service.internal.stagec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.mealprep.core.types.SlotKind;
import com.example.mealprep.planner.api.dto.MealSlotSkeleton;
import com.example.mealprep.planner.api.dto.PlanCompositionContext;
import com.example.mealprep.planner.api.dto.RecipePoolSnapshot;
import com.example.mealprep.planner.config.PlannerProperties;
import com.example.mealprep.planner.testdata.PlanTestData;
import com.example.mealprep.preference.api.dto.FilterResult;
import com.example.mealprep.preference.domain.service.HardConstraintFilterService;
import com.example.mealprep.recipe.api.dto.RecipeDto;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AugmentationVerifier} — package-private, so this test lives in the {@code
 * stagec} package (mirrors the {@code beamsearch}/{@code scoring} internal-test convention). Covers
 * ticket planner-01h §"Verifier": hallucinated recipe, allergen clash, time-budget overshoot, swap
 * allergen safety, and the always-pass repair.
 */
class AugmentationVerifierTest {

  private static final LocalDate WEEK_START = LocalDate.of(2026, 1, 5);

  private final HardConstraintFilterService filter = mock(HardConstraintFilterService.class);
  private final PlannerProperties properties = PlanTestData.scoringProperties();
  private final AugmentationVerifier verifier = new AugmentationVerifier(filter, properties);

  private PlanCompositionContext ctxWith(List<MealSlotSkeleton> slots, List<RecipeDto> recipes) {
    return new PlanCompositionContext(
        UUID.randomUUID(),
        WEEK_START,
        slots,
        Map.of(),
        Map.of(),
        null,
        null,
        null,
        new RecipePoolSnapshot(recipes, Instant.parse("2026-01-01T00:00:00Z")),
        List.of(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        Map.of());
  }

  private void allowAll() {
    lenient()
        .when(filter.checkForHousehold(anyList(), anyList()))
        .thenReturn(new FilterResult(true, List.of()));
    lenient()
        .when(filter.check(org.mockito.ArgumentMatchers.any(UUID.class), anyList()))
        .thenReturn(new FilterResult(true, List.of()));
  }

  @Test
  void addSnack_recipeNotInPool_fails() {
    allowAll();
    MealSlotSkeleton slot = PlanTestData.skeletonFor(WEEK_START, 0, SlotKind.DINNER, 30);
    PlanCompositionContext ctx = ctxWith(List.of(slot), List.of());

    boolean passes =
        verifier.passes(
            new AddSnackAugmentation(slot.slotId(), UUID.randomUUID(), 2, "snack"), ctx);

    assertThat(passes).isFalse();
  }

  @Test
  void addSnack_recipeInPool_allergenClash_fails() {
    UUID recipeId = UUID.randomUUID();
    RecipeDto recipe =
        PlanTestData.recipeFor(recipeId, SlotKind.SNACK, 10, List.of(), List.of("peanut"));
    MealSlotSkeleton slot = PlanTestData.skeletonFor(WEEK_START, 0, SlotKind.SNACK, 30);
    PlanCompositionContext ctx = ctxWith(List.of(slot), List.of(recipe));
    when(filter.checkForHousehold(anyList(), eq(List.of("peanut"))))
        .thenReturn(new FilterResult(false, List.of()));

    boolean passes =
        verifier.passes(new AddSnackAugmentation(slot.slotId(), recipeId, 1, "snack"), ctx);

    assertThat(passes).isFalse();
  }

  @Test
  void addSnack_recipeInPool_safe_withinTimeBudget_passes() {
    UUID recipeId = UUID.randomUUID();
    // skeleton time budget 30 × overshoot 1.5 = 45; recipe total 20 → within budget.
    RecipeDto recipe =
        PlanTestData.recipeFor(recipeId, SlotKind.SNACK, 20, List.of(), List.of("oats"));
    MealSlotSkeleton slot = PlanTestData.skeletonFor(WEEK_START, 0, SlotKind.SNACK, 30);
    PlanCompositionContext ctx = ctxWith(List.of(slot), List.of(recipe));
    allowAll();

    boolean passes =
        verifier.passes(new AddSnackAugmentation(slot.slotId(), recipeId, 1, "snack"), ctx);

    assertThat(passes).isTrue();
  }

  @Test
  void addSnack_exceedsTimeBudgetTimes1point5_fails() {
    UUID recipeId = UUID.randomUUID();
    // budget 30 × 1.5 = 45; recipe total 60 → over budget.
    RecipeDto recipe =
        PlanTestData.recipeFor(recipeId, SlotKind.SNACK, 60, List.of(), List.of("oats"));
    MealSlotSkeleton slot = PlanTestData.skeletonFor(WEEK_START, 0, SlotKind.SNACK, 30);
    PlanCompositionContext ctx = ctxWith(List.of(slot), List.of(recipe));
    allowAll();

    boolean passes =
        verifier.passes(new AddSnackAugmentation(slot.slotId(), recipeId, 1, "snack"), ctx);

    assertThat(passes).isFalse();
  }

  @Test
  void ingredientSwap_targetKeyAllergenClash_fails() {
    MealSlotSkeleton slot = PlanTestData.skeletonFor(WEEK_START, 0, SlotKind.DINNER, 30);
    PlanCompositionContext ctx = ctxWith(List.of(slot), List.of());
    when(filter.checkForHousehold(anyList(), eq(List.of("shellfish"))))
        .thenReturn(new FilterResult(false, List.of()));

    boolean passes =
        verifier.passes(
            new IngredientSwapAugmentation(slot.slotId(), "tofu", "shellfish", "swap"), ctx);

    assertThat(passes).isFalse();
  }

  @Test
  void ingredientSwap_targetKeySafe_passes() {
    MealSlotSkeleton slot = PlanTestData.skeletonFor(WEEK_START, 0, SlotKind.DINNER, 30);
    PlanCompositionContext ctx = ctxWith(List.of(slot), List.of());
    allowAll();

    boolean passes =
        verifier.passes(
            new IngredientSwapAugmentation(slot.slotId(), "butter", "olive_oil", "swap"), ctx);

    assertThat(passes).isTrue();
  }

  @Test
  void repair_alwaysPasses_noConstraintToVerify() {
    PlanCompositionContext ctx = ctxWith(List.of(), List.of());

    boolean passes =
        verifier.passes(new RepairAugmentation(null, "protein low", "add a snack"), ctx);

    assertThat(passes).isTrue();
  }

  // ---- mutation-killing additions -------------------------------------------------------------

  /**
   * IngredientSwap whose targetSlotId is NOT among the skeletons → {@code slot.isEmpty()}
   * plan-level branch: the verifier unions every eater across all skeletons and runs ONE household
   * check. A clash there fails the augmentation. Kills the L120 NegateConditionals (the {@code
   * slotSkeletons() == null} guard), the L124 lambda (per-skeleton eater flat-map) and the L127
   * NegateConditionals / L130 BooleanFalse (the household result is honoured).
   */
  @Test
  void ingredientSwap_unknownSlot_planLevelHouseholdCheck_clashFails() {
    UUID e1 = UUID.randomUUID();
    UUID e2 = UUID.randomUUID();
    MealSlotSkeleton s1 =
        new MealSlotSkeleton(
            UUID.randomUUID(),
            UUID.randomUUID(),
            0,
            WEEK_START,
            SlotKind.DINNER,
            "dinner",
            30,
            true,
            List.of(e1));
    MealSlotSkeleton s2 =
        new MealSlotSkeleton(
            UUID.randomUUID(),
            UUID.randomUUID(),
            1,
            WEEK_START,
            SlotKind.LUNCH,
            "lunch",
            30,
            true,
            List.of(e2));
    PlanCompositionContext ctx = ctxWith(List.of(s1, s2), List.of());
    when(filter.checkForHousehold(eq(List.of(e1, e2)), eq(List.of("shellfish"))))
        .thenReturn(new FilterResult(false, List.of()));

    boolean passes =
        verifier.passes(
            new IngredientSwapAugmentation(UUID.randomUUID(), "tofu", "shellfish", "swap"), ctx);

    assertThat(passes).isFalse();
  }

  /**
   * Unknown slot AND no eaters anywhere → {@code allEaters.isEmpty()} → never blocks on missing
   * data, returns true. Kills the L128 BooleanFalseReturnVals mutant ({@code return true} → {@code
   * return false}).
   */
  @Test
  void ingredientSwap_unknownSlot_noEaters_passesVacuously() {
    allowAll();
    PlanCompositionContext ctx = ctxWith(List.of(), List.of());

    boolean passes =
        verifier.passes(new IngredientSwapAugmentation(UUID.randomUUID(), "a", "b", "swap"), ctx);

    assertThat(passes).isTrue();
  }

  /**
   * Per-person slot, single eater, the per-eater check PASSES → the loop completes and the method
   * returns true. Kills the L135 (empty-eaters short-circuit must NOT be taken here) and the L146
   * BooleanFalseReturnVals mutant ({@code return true} at the end of the per-eater loop).
   */
  @Test
  void ingredientSwap_perPersonSlot_allEatersPass_returnsTrue() {
    UUID eater = UUID.randomUUID();
    MealSlotSkeleton perPerson =
        new MealSlotSkeleton(
            UUID.randomUUID(),
            UUID.randomUUID(),
            0,
            WEEK_START,
            SlotKind.LUNCH,
            "lunch",
            30,
            false,
            List.of(eater));
    PlanCompositionContext ctx = ctxWith(List.of(perPerson), List.of());
    when(filter.check(eq(eater), eq(List.of("quinoa"))))
        .thenReturn(new FilterResult(true, List.of()));

    boolean passes =
        verifier.passes(
            new IngredientSwapAugmentation(perPerson.slotId(), "rice", "quinoa", "swap"), ctx);

    assertThat(passes).isTrue();
  }

  /**
   * Recipe whose total time equals exactly the floor(budget × overshoot) limit (30 × 1.5 = 45) is
   * NOT over budget (the check is {@code compareTo(limit) > 0}). Kills the L158
   * ConditionalsBoundary mutant {@code > 0} → {@code >= 0}: at equality the mutated guard would
   * wrongly reject.
   */
  @Test
  void addSnack_totalTimeExactlyAtLimit_passes() {
    UUID recipeId = UUID.randomUUID();
    RecipeDto recipe =
        PlanTestData.recipeFor(recipeId, SlotKind.SNACK, 45, List.of(), List.of("oats"));
    MealSlotSkeleton slot = PlanTestData.skeletonFor(WEEK_START, 0, SlotKind.SNACK, 30);
    PlanCompositionContext ctx = ctxWith(List.of(slot), List.of(recipe));
    allowAll();

    boolean passes =
        verifier.passes(new AddSnackAugmentation(slot.slotId(), recipeId, 1, "snack"), ctx);

    assertThat(passes).isTrue();
  }

  /**
   * Two skeletons; the swap targets the SECOND one (per-person, distinct eater). The verifier must
   * resolve THAT slot, not just the first. Kills the L175 BooleanTrueReturnVals mutant in the
   * findSlot filter lambda ({@code slotId.equals(s.slotId())} → constant true would match the first
   * skeleton and route through the wrong eater set).
   */
  @Test
  void findSlot_matchesTheRequestedSlotNotJustTheFirst() {
    UUID e1 = UUID.randomUUID();
    UUID e2 = UUID.randomUUID();
    MealSlotSkeleton first =
        new MealSlotSkeleton(
            UUID.randomUUID(),
            UUID.randomUUID(),
            0,
            WEEK_START,
            SlotKind.DINNER,
            "dinner",
            30,
            false,
            List.of(e1));
    MealSlotSkeleton second =
        new MealSlotSkeleton(
            UUID.randomUUID(),
            UUID.randomUUID(),
            1,
            WEEK_START,
            SlotKind.LUNCH,
            "lunch",
            30,
            false,
            List.of(e2));
    PlanCompositionContext ctx = ctxWith(List.of(first, second), List.of());
    // Only the SECOND slot's eater clashes; if findSlot wrongly returned the first, e1 would be
    // checked and the swap would pass.
    when(filter.check(eq(e1), anyList())).thenReturn(new FilterResult(true, List.of()));
    when(filter.check(eq(e2), eq(List.of("nuts")))).thenReturn(new FilterResult(false, List.of()));

    boolean passes =
        verifier.passes(
            new IngredientSwapAugmentation(second.slotId(), "seeds", "nuts", "swap"), ctx);

    assertThat(passes).isFalse();
  }

  /**
   * ADD_SNACK recipe with a blank-key ingredient alongside a real one: only the non-blank key must
   * reach the constraint filter. Kills the L184 BooleanTrueReturnVals mutant in the ingredientKeys
   * filter ({@code k != null && !k.isBlank()} → constant true would forward the blank key too).
   */
  @Test
  void ingredientKeys_excludesBlankMappingKeys() {
    UUID recipeId = UUID.randomUUID();
    RecipeDto recipe =
        PlanTestData.recipeFor(recipeId, SlotKind.SNACK, 10, List.of(), List.of("oats", "  "));
    MealSlotSkeleton slot = PlanTestData.skeletonFor(WEEK_START, 0, SlotKind.SNACK, 30);
    PlanCompositionContext ctx = ctxWith(List.of(slot), List.of(recipe));
    // The verifier must call the household check with EXACTLY ["oats"] (blank filtered out).
    when(filter.checkForHousehold(anyList(), eq(List.of("oats"))))
        .thenReturn(new FilterResult(true, List.of()));

    boolean passes =
        verifier.passes(new AddSnackAugmentation(slot.slotId(), recipeId, 1, "snack"), ctx);

    assertThat(passes).isTrue();
  }

  @Test
  void ingredientSwap_perPersonSlot_usesCheckNotHousehold() {
    UUID eater = UUID.randomUUID();
    MealSlotSkeleton perPerson =
        new MealSlotSkeleton(
            UUID.randomUUID(),
            UUID.randomUUID(),
            0,
            WEEK_START,
            SlotKind.LUNCH,
            "lunch",
            30,
            false,
            List.of(eater));
    PlanCompositionContext ctx = ctxWith(List.of(perPerson), List.of());
    when(filter.check(eq(eater), eq(List.of("nuts"))))
        .thenReturn(new FilterResult(false, List.of()));

    boolean passes =
        verifier.passes(
            new IngredientSwapAugmentation(perPerson.slotId(), "seeds", "nuts", "swap"), ctx);

    assertThat(passes).isFalse();
  }
}
