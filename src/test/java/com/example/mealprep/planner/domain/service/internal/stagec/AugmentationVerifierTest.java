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
