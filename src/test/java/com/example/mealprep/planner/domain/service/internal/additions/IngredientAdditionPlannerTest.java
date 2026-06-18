package com.example.mealprep.planner.domain.service.internal.additions;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.planner.api.dto.Addition;
import com.example.mealprep.planner.api.dto.AdditionKind;
import com.example.mealprep.planner.api.dto.NutritionCoverageDocument;
import com.example.mealprep.planner.api.dto.NutritionTargetCoverageDocument;
import com.example.mealprep.planner.api.dto.PlanCompositionContext;
import com.example.mealprep.planner.api.dto.RollupSummaryDocument;
import com.example.mealprep.planner.api.dto.SlotAssignment;
import com.example.mealprep.planner.testdata.PlanTestData;
import com.example.mealprep.recipe.api.dto.NutritionPerServingDto;
import com.example.mealprep.recipe.api.dto.RecipeDto;
import com.example.mealprep.recipe.api.dto.RecipeMetadataDto;
import com.example.mealprep.recipe.api.dto.RecipeVersionDto;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit test for {@link IngredientAdditionPlanner}'s deterministic gap-fill — DB-free. Uses an empty
 * slot-skeleton context so the allergy filter is short-circuited (no household eaters to check),
 * exercising the residual/short-micro reading + greedy pick + per-day attach against the real
 * catalogue + USDA-fallback resolver.
 */
class IngredientAdditionPlannerTest {

  private static final LocalDate WEEK = LocalDate.of(2026, 1, 5);

  // Real resolver (null query service → catalogue USDA fallback); null filter is never called
  // because the context has no eaters; null AI service → deterministic carrier-slot placement.
  private final IngredientAdditionPlanner planner =
      new IngredientAdditionPlanner(new AdditionNutritionResolver(null), null, null);

  private static NutritionTargetCoverageDocument shortTarget(
      String key, String unit, String target, String projected) {
    return new NutritionTargetCoverageDocument(
        key, unit, new BigDecimal(target), new BigDecimal(projected), "LOWER_FLOOR", false, "SHORT",
        "measured");
  }

  private static RollupSummaryDocument rollupWithGap() {
    NutritionCoverageDocument coverage =
        new NutritionCoverageDocument(
            List.of(shortTarget("calories", "kcal", "3600", "3196")),
            List.of(
                shortTarget("vitamin_e_mg", "mg", "15", "5"),
                shortTarget("magnesium_mg", "mg", "420", "300")),
            0, 5, 16, 28, 0);
    return new RollupSummaryDocument(List.of(), null, coverage);
  }

  @Test
  void attaches_additions_spread_across_meals_and_varied_across_days() {
    // Two days, three meals each (breakfast/lunch/dinner at slotIndex 0/1/2).
    List<SlotAssignment> day0 =
        List.of(
            PlanTestData.assignment(UUID.randomUUID(), UUID.randomUUID(), WEEK, 0, 2),
            PlanTestData.assignment(UUID.randomUUID(), UUID.randomUUID(), WEEK, 1, 2),
            PlanTestData.assignment(UUID.randomUUID(), UUID.randomUUID(), WEEK, 2, 2));
    List<SlotAssignment> day1 =
        List.of(
            PlanTestData.assignment(UUID.randomUUID(), UUID.randomUUID(), WEEK.plusDays(1), 0, 2),
            PlanTestData.assignment(UUID.randomUUID(), UUID.randomUUID(), WEEK.plusDays(1), 1, 2),
            PlanTestData.assignment(UUID.randomUUID(), UUID.randomUUID(), WEEK.plusDays(1), 2, 2));
    List<SlotAssignment> assignments = new java.util.ArrayList<>();
    assignments.addAll(day0);
    assignments.addAll(day1);
    PlanCompositionContext ctx = PlanTestData.minimalContext(List.of(), List.of());

    List<SlotAssignment> result = planner.attach(assignments, rollupWithGap(), ctx);

    List<SlotAssignment> withAdditions =
        result.stream().filter(a -> !a.additions().isEmpty()).toList();
    assertThat(withAdditions).isNotEmpty();

    // SPREAD: additions do NOT all pile on the dinner carrier — at least one lands on a non-dinner
    // slot (slotIndex < 2), which the old single-carrier behaviour never did.
    assertThat(withAdditions)
        .anySatisfy(a -> assertThat(a.slotIndex()).isLessThan(2));
    // No slot carries more than MAX_ADDITIONS.
    assertThat(withAdditions).allSatisfy(a -> assertThat(a.additions()).hasSizeLessThanOrEqualTo(3));

    // The gap is still meaningfully closed: each day's additions total real calories.
    java.util.function.Function<LocalDate, Integer> dayKcal =
        d ->
            result.stream()
                .filter(a -> d.equals(a.onDate()))
                .flatMap(a -> a.additions().stream())
                .mapToInt(p -> p.nutrition().calories())
                .sum();
    assertThat(dayKcal.apply(WEEK)).isGreaterThan(200);
    assertThat(dayKcal.apply(WEEK.plusDays(1))).isGreaterThan(200);

    // VARIETY: day 0 and day 1 are not served the identical set of sides.
    java.util.function.Function<LocalDate, java.util.Set<String>> dayPicks =
        d ->
            result.stream()
                .filter(a -> d.equals(a.onDate()))
                .flatMap(a -> a.additions().stream())
                .map(Addition::name)
                .collect(java.util.stream.Collectors.toSet());
    assertThat(dayPicks.apply(WEEK)).isNotEqualTo(dayPicks.apply(WEEK.plusDays(1)));
  }

  @Test
  void no_additions_when_coverage_is_already_met() {
    NutritionCoverageDocument met =
        new NutritionCoverageDocument(
            List.of(
                new NutritionTargetCoverageDocument(
                    "calories", "kcal", new BigDecimal("3600"), new BigDecimal("3590"),
                    "LOWER_FLOOR", true, "MET", "measured")),
            List.of(),
            5, 5, 28, 28, 0);
    List<SlotAssignment> assignments =
        List.of(PlanTestData.assignment(UUID.randomUUID(), UUID.randomUUID(), WEEK, 0, 2));
    PlanCompositionContext ctx = PlanTestData.minimalContext(List.of(), List.of());

    List<SlotAssignment> result =
        planner.attach(assignments, new RollupSummaryDocument(List.of(), null, met), ctx);

    assertThat(result).allSatisfy(a -> assertThat(a.additions()).isEmpty());
  }

  /** Build a snack-tagged (side-proxy) pool recipe carrying the given per-serving nutrition. */
  private static RecipeDto snackSide(UUID id, String name, int kcal, Map<String, String> micros) {
    RecipeDto base = PlanTestData.scoredRecipe(id, 15, "Generic", "tofu", "roast", List.of("chickpea"));
    RecipeVersionDto v = base.currentVersionBody();
    RecipeMetadataDto m = v.metadata();
    RecipeMetadataDto snackMeta =
        new RecipeMetadataDto(
            m.servings(), m.prepTimeMins(), m.cookTimeMins(), m.totalTimeMins(),
            m.equipmentRequired(), m.fridgeDays(), m.freezerWeeks(), m.packable(), m.cuisine(),
            List.of("snack"));
    Map<String, BigDecimal> mic = new java.util.LinkedHashMap<>();
    micros.forEach((k, val) -> mic.put(k, new BigDecimal(val)));
    NutritionPerServingDto nut =
        new NutritionPerServingDto(
            kcal, new BigDecimal("8"), new BigDecimal("20"), new BigDecimal("6"), new BigDecimal("5"), mic);
    RecipeVersionDto sv =
        new RecipeVersionDto(
            v.id(), v.branchId(), v.versionNumber(), v.parentVersionId(), v.trigger(),
            v.changeReason(), v.embeddingStatus(), v.createdAt(), v.createdByActor(),
            v.adapterTraceId(), v.ingredients(), v.methodSteps(), snackMeta, v.tags(),
            v.appliedSubstitutionIds(), v.embedding(), nut);
    return new RecipeDto(
        base.id(), base.userId(), base.catalogue(), name, base.description(), base.currentVersion(),
        base.currentBranchId(), base.dataQuality(), base.nutritionStatus(), base.forkedFromRecipeId(),
        base.lastUsedInPlanAt(), base.archivedAt(), base.deletedAt(), base.imageUrl(),
        base.optimisticVersion(), base.createdAt(), base.updatedAt(), sv, base.branches());
  }

  @Test
  void picks_a_side_recipe_when_it_uniquely_fills_a_short_micro() {
    // A snack-tagged side recipe carries vitamin_d — no INGREDIENT catalogue candidate has it, so the
    // greedy must reach for the SIDE_RECIPE to close that short micro.
    RecipeDto side = snackSide(UUID.randomUUID(), "Roasted Chickpea Cup", 220, Map.of("vitamin_d_mcg", "8.0"));
    NutritionCoverageDocument cov =
        new NutritionCoverageDocument(
            List.of(shortTarget("calories", "kcal", "3600", "3200")),
            List.of(shortTarget("vitamin_d_mcg", "mcg", "15", "5")),
            0, 5, 16, 28, 0);
    SlotAssignment a = PlanTestData.assignment(UUID.randomUUID(), UUID.randomUUID(), WEEK, 2, 2);
    PlanCompositionContext ctx = PlanTestData.minimalContext(List.of(), List.of(side));

    List<SlotAssignment> result =
        planner.attach(List.of(a), new RollupSummaryDocument(List.of(), null, cov), ctx);

    List<Addition> picks = result.stream().flatMap(s -> s.additions().stream()).toList();
    assertThat(picks)
        .anySatisfy(
            p -> {
              assertThat(p.kind()).isEqualTo(AdditionKind.SIDE_RECIPE);
              assertThat(p.recipeId()).isEqualTo(side.id());
              assertThat(p.name()).isEqualTo("Roasted Chickpea Cup");
              assertThat(p.nutrition().micros()).containsKey("vitamin_d_mcg");
            });
  }

  @Test
  void no_additions_when_no_coverage() {
    List<SlotAssignment> assignments =
        List.of(PlanTestData.assignment(UUID.randomUUID(), UUID.randomUUID(), WEEK, 0, 2));
    PlanCompositionContext ctx = PlanTestData.minimalContext(List.of(), List.of());

    List<SlotAssignment> result =
        planner.attach(assignments, new RollupSummaryDocument(List.of(), null, null), ctx);

    assertThat(result).isEqualTo(assignments);
  }
}
