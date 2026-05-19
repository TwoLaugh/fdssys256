package com.example.mealprep.planner.domain.service.internal.scoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.core.types.SlotKind;
import com.example.mealprep.planner.api.dto.MealSlotSkeleton;
import com.example.mealprep.planner.api.dto.PlanCompositionContext;
import com.example.mealprep.planner.api.dto.RecipePoolSnapshot;
import com.example.mealprep.planner.testdata.PlanTestData;
import com.example.mealprep.recipe.api.dto.RecipeDto;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit test for the package-private {@link ScoringSupport} static helpers. Exercises the
 * recipe-index build and the deterministic {@code primaryUserId} derivation directly so the
 * shared-helper mutants are killed once rather than relying on incidental coverage from the seven
 * sub-score tests.
 */
class ScoringSupportTest {

  private static final LocalDate WEEK = LocalDate.of(2026, 1, 5);

  private static PlanCompositionContext ctxWith(
      List<RecipeDto> pool,
      List<MealSlotSkeleton> skeletons,
      Map<UUID, com.example.mealprep.household.api.dto.SoftPreferenceBundleDto> softPrefs) {
    return new PlanCompositionContext(
        UUID.randomUUID(),
        WEEK,
        skeletons,
        Map.of(),
        softPrefs,
        null,
        null,
        null,
        new RecipePoolSnapshot(pool, Instant.parse("2026-01-01T00:00:00Z")),
        List.of(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        Map.of());
  }

  private static PlanCompositionContext ctxNullPool() {
    return new PlanCompositionContext(
        UUID.randomUUID(),
        WEEK,
        List.of(),
        Map.of(),
        Map.of(),
        null,
        null,
        null,
        new RecipePoolSnapshot(null, Instant.parse("2026-01-01T00:00:00Z")),
        List.of(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        Map.of());
  }

  /**
   * Index a real two-recipe pool and look the recipes up. Kills the L26 EmptyObjectReturnVals
   * mutant ({@code return index} replaced with an empty map) — an empty index would make every
   * findRecipe miss.
   */
  @Test
  void recipe_index_indexes_pool_by_id() {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    RecipeDto ra = PlanTestData.scoredRecipe(a, 20, "Thai", "tofu", "fry", List.of());
    RecipeDto rb = PlanTestData.scoredRecipe(b, 20, "Italian", "beef", "bake", List.of());

    Map<UUID, RecipeDto> index =
        ScoringSupport.recipeIndex(ctxWith(List.of(ra, rb), List.of(), Map.of()));

    assertThat(index).hasSize(2);
    assertThat(ScoringSupport.findRecipe(index, a)).contains(ra);
    assertThat(ScoringSupport.findRecipe(index, b)).contains(rb);
    assertThat(ScoringSupport.findRecipe(index, UUID.randomUUID())).isEmpty();
  }

  /** Null recipe pool → empty (but non-null) index; findRecipe misses cleanly. */
  @Test
  void recipe_index_null_pool_is_empty_not_null() {
    Map<UUID, RecipeDto> index = ScoringSupport.recipeIndex(ctxNullPool());
    assertThat(index).isNotNull().isEmpty();
    assertThat(ScoringSupport.findRecipe(index, UUID.randomUUID())).isEmpty();
  }

  /** Duplicate ids: first wins (putIfAbsent), so the index never throws and keeps the first. */
  @Test
  void recipe_index_dedups_keeping_first() {
    UUID dup = UUID.randomUUID();
    RecipeDto first = PlanTestData.scoredRecipe(dup, 20, "Thai", "tofu", "fry", List.of());
    RecipeDto second = PlanTestData.scoredRecipe(dup, 99, "Italian", "beef", "bake", List.of());

    Map<UUID, RecipeDto> index =
        ScoringSupport.recipeIndex(ctxWith(List.of(first, second), List.of(), Map.of()));

    assertThat(index).hasSize(1);
    assertThat(ScoringSupport.findRecipe(index, dup)).contains(first);
  }

  /**
   * softPrefs present → primary is the lowest-by-natural-order user id. Kills the L52
   * NegateConditionals (the {@code !isEmpty()} guard) and the L53 NullReturnVals: a non-null,
   * deterministic id is returned, and it is specifically the minimum of the key set.
   */
  @Test
  void primary_user_is_lowest_soft_pref_key() {
    UUID low = new UUID(0L, 1L);
    UUID high = new UUID(0L, 2L);
    var prefs =
        Map.of(
            high,
            new com.example.mealprep.household.api.dto.SoftPreferenceBundleDto(high, null, null),
            low,
            new com.example.mealprep.household.api.dto.SoftPreferenceBundleDto(low, null, null));

    UUID primary = ScoringSupport.primaryUserId(ctxWith(List.of(), List.of(), prefs));

    assertThat(primary).isEqualTo(low);
  }

  /**
   * No soft prefs but a slot skeleton with eaters → falls back to the first eater of the first
   * skeleton. Kills the L54/L55 lambda mutants (the {@code eaters != null && !eaters.isEmpty()}
   * filter and the {@code eaters.get(0)} map) — an empty-eaters skeleton is skipped and the first
   * populated one supplies the id.
   */
  @Test
  void primary_user_falls_back_to_first_eater_when_no_soft_prefs() {
    UUID eater = UUID.randomUUID();
    MealSlotSkeleton emptyEaters =
        new MealSlotSkeleton(
            UUID.randomUUID(),
            UUID.randomUUID(),
            0,
            WEEK,
            SlotKind.DINNER,
            "dinner",
            30,
            true,
            new ArrayList<>());
    MealSlotSkeleton withEater =
        new MealSlotSkeleton(
            UUID.randomUUID(),
            UUID.randomUUID(),
            1,
            WEEK,
            SlotKind.DINNER,
            "dinner",
            30,
            true,
            new ArrayList<>(List.of(eater)));

    UUID primary =
        ScoringSupport.primaryUserId(ctxWith(List.of(), List.of(emptyEaters, withEater), Map.of()));

    assertThat(primary).isEqualTo(eater);
  }

  /** Empty soft prefs is treated as "no prefs" (the {@code !isEmpty()} guard) → eater fallback. */
  @Test
  void empty_soft_prefs_map_falls_through_to_eater() {
    UUID eater = UUID.randomUUID();
    MealSlotSkeleton skel = PlanTestData.skeletonFor(WEEK, 0, SlotKind.DINNER, 30);
    skel.eaters().clear();
    skel.eaters().add(eater);

    UUID primary = ScoringSupport.primaryUserId(ctxWith(List.of(), List.of(skel), Map.of()));

    assertThat(primary).isEqualTo(eater);
  }

  /** No soft prefs and no skeletons → null (no user to aggregate against). */
  @Test
  void primary_user_is_null_when_nothing_resolvable() {
    assertThat(ScoringSupport.primaryUserId(ctxWith(List.of(), List.of(), Map.of()))).isNull();
  }
}
