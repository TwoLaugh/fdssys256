package com.example.mealprep.planner.testdata;

import com.example.mealprep.core.types.SlotKind;
import com.example.mealprep.planner.api.dto.DailyRollupDocument;
import com.example.mealprep.planner.api.dto.MealSlotSkeleton;
import com.example.mealprep.planner.api.dto.PlanCompositionContext;
import com.example.mealprep.planner.api.dto.RecipePoolSnapshot;
import com.example.mealprep.planner.api.dto.RollupSummaryDocument;
import com.example.mealprep.planner.api.dto.ScoreBreakdownDocument;
import com.example.mealprep.planner.api.dto.WeeklyRollupDocument;
import com.example.mealprep.planner.domain.entity.Day;
import com.example.mealprep.planner.domain.entity.MealSlot;
import com.example.mealprep.planner.domain.entity.Plan;
import com.example.mealprep.planner.domain.entity.PlanStatus;
import com.example.mealprep.planner.domain.entity.ScheduledRecipe;
import com.example.mealprep.planner.domain.entity.SlotState;
import com.example.mealprep.planner.domain.entity.TriggerKind;
import com.example.mealprep.recipe.api.dto.IngredientDto;
import com.example.mealprep.recipe.api.dto.RecipeDto;
import com.example.mealprep.recipe.api.dto.RecipeMetadataDto;
import com.example.mealprep.recipe.api.dto.RecipeTagsDto;
import com.example.mealprep.recipe.api.dto.RecipeVersionDto;
import com.example.mealprep.recipe.domain.entity.Catalogue;
import com.example.mealprep.recipe.domain.entity.DataQuality;
import com.example.mealprep.recipe.domain.entity.NutritionStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Fixture data for planner ITs and unit tests. {@link #newPlanGraph(LocalDate, int, int)} builds a
 * fully-linked aggregate (Plan → Day(s) → MealSlot(s) → ScheduledRecipe) suitable for persisting
 * with {@code cascade = ALL}.
 */
public final class PlanTestData {

  private PlanTestData() {}

  public static ScoreBreakdownDocument zeroScoreBreakdown() {
    return new ScoreBreakdownDocument(
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        true,
        true,
        "v1-uniform");
  }

  public static RollupSummaryDocument emptyRollup() {
    return new RollupSummaryDocument(
        Collections.<DailyRollupDocument>emptyList(),
        new WeeklyRollupDocument(
            0,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            0,
            BigDecimal.ZERO,
            0,
            Collections.<String>emptyList()));
  }

  /**
   * Build a fresh plan aggregate with the requested number of days and slots-per-day. Each slot
   * carries a {@link ScheduledRecipe}. The graph's parent/child pointers are wired explicitly so a
   * single {@code save(plan)} call (with cascade-all on the {@code days} association) persists the
   * full graph.
   */
  public static Plan newPlanGraph(LocalDate weekStartDate, int numDays, int slotsPerDay) {
    UUID householdId = UUID.randomUUID();
    return newPlanGraph(householdId, weekStartDate, 1, PlanStatus.GENERATED, numDays, slotsPerDay);
  }

  /**
   * Variant that lets the caller pin household, generation, and status — used by 01c read tests to
   * build multi-generation history fixtures and ACTIVE/SUPERSEDED state mixes.
   */
  public static Plan newPlanGraph(
      UUID householdId,
      LocalDate weekStartDate,
      int generation,
      PlanStatus status,
      int numDays,
      int slotsPerDay) {
    Plan plan =
        Plan.builder()
            .id(UUID.randomUUID())
            .householdId(householdId)
            .weekStartDate(weekStartDate)
            .generation(generation)
            .status(status)
            .triggerKind(TriggerKind.USER_INITIATED)
            .qualityWarning(false)
            .coldStart(false)
            .aiAugmented(false)
            .traceId(UUID.randomUUID())
            .decisionId(UUID.randomUUID())
            .scoreBreakdown(zeroScoreBreakdown())
            .rollupSummary(emptyRollup())
            .days(new ArrayList<>())
            .build();

    for (int d = 0; d < numDays; d++) {
      Day day =
          Day.builder()
              .id(UUID.randomUUID())
              .plan(plan)
              .onDate(weekStartDate.plusDays(d))
              .slots(new ArrayList<>())
              .build();

      for (int s = 0; s < slotsPerDay; s++) {
        MealSlot slot =
            MealSlot.builder()
                .id(UUID.randomUUID())
                .day(day)
                .plan(plan)
                .slotIndex(s)
                .kind(s == 0 ? SlotKind.BREAKFAST : s == 1 ? SlotKind.LUNCH : SlotKind.DINNER)
                .label(s == 0 ? "Breakfast" : s == 1 ? "Lunch" : "Dinner")
                .timeBudgetMin(30)
                .shared(true)
                .eaters(new ArrayList<>(List.of(UUID.randomUUID(), UUID.randomUUID())))
                .state(SlotState.PLANNED)
                .build();
        ScheduledRecipe scheduledRecipe =
            ScheduledRecipe.builder()
                .id(UUID.randomUUID())
                .slot(slot)
                .recipeId(UUID.randomUUID())
                .recipeVersionId(UUID.randomUUID())
                .recipeBranchId(UUID.randomUUID())
                .servings(2)
                .phase2Addition(false)
                .build();
        slot.setScheduledRecipe(scheduledRecipe);
        day.getSlots().add(slot);
      }

      plan.getDays().add(day);
    }
    return plan;
  }

  /**
   * Minimal {@link Plan} in {@code GENERATED} state for the given {@code (household, week,
   * generation)} tuple. No days/slots — used by {@link
   * com.example.mealprep.planner.PlanGenerationCounterIT} and other 01b+ tests that don't care
   * about the child graph.
   */
  public static Plan testGeneratedPlan(UUID householdId, LocalDate weekStartDate, int generation) {
    return basePlanBuilder(householdId, weekStartDate, generation, PlanStatus.GENERATED).build();
  }

  /**
   * Minimal {@link Plan} in {@code ACTIVE} state. No days/slots — used by the counter IT to verify
   * {@code currentActivePlanIdFor} returns the right id.
   */
  public static Plan testActivePlan(UUID householdId, LocalDate weekStartDate, int generation) {
    return basePlanBuilder(householdId, weekStartDate, generation, PlanStatus.ACTIVE).build();
  }

  // ---- planner-01d Stage-A fixture builders ---------------------------------------------------

  /**
   * Build a kind-tagged {@link RecipeDto} with the supplied {@code totalTimeMins}, equipment, and
   * ingredients. Used by the hard-filter and beam-search unit tests.
   */
  public static RecipeDto recipeFor(
      UUID id,
      SlotKind kind,
      int totalTimeMins,
      List<String> equipmentRequired,
      List<String> ingredientMappingKeys) {
    UUID branchId = UUID.randomUUID();
    UUID versionId = UUID.randomUUID();
    List<IngredientDto> ingredients = new ArrayList<>();
    int order = 0;
    for (String key : ingredientMappingKeys) {
      ingredients.add(
          new IngredientDto(
              UUID.randomUUID(),
              order++,
              key,
              key,
              BigDecimal.ONE,
              "g",
              null,
              false,
              false,
              BigDecimal.ONE));
    }
    RecipeMetadataDto metadata =
        new RecipeMetadataDto(
            2,
            Math.max(5, totalTimeMins / 3),
            Math.max(5, totalTimeMins * 2 / 3),
            totalTimeMins,
            equipmentRequired,
            null,
            null,
            false,
            "Generic",
            List.of(kind.name().toLowerCase(java.util.Locale.ROOT)));
    RecipeTagsDto tags = new RecipeTagsDto(null, null, null, List.<String>of(), List.<String>of());
    RecipeVersionDto version =
        new RecipeVersionDto(
            versionId,
            branchId,
            1,
            null,
            null,
            null,
            "PENDING",
            Instant.parse("2024-01-01T00:00:00Z"),
            "test",
            null,
            ingredients,
            List.of(),
            metadata,
            tags,
            null);
    return new RecipeDto(
        id,
        UUID.randomUUID(),
        Catalogue.USER,
        "recipe-" + id,
        "test recipe",
        1,
        branchId,
        DataQuality.USER_VERIFIED,
        NutritionStatus.PENDING,
        null,
        null,
        null,
        null,
        0L,
        Instant.parse("2024-01-01T00:00:00Z"),
        Instant.parse("2024-01-01T00:00:00Z"),
        version,
        List.of());
  }

  /** A breakfast recipe with no equipment and no ingredient mapping keys. */
  public static RecipeDto trivialRecipe(UUID id, SlotKind kind) {
    return recipeFor(id, kind, 20, List.<String>of(), List.<String>of());
  }

  /**
   * A {@link MealSlotSkeleton} with a single eater (so single-eater shared/per-person filters
   * pass).
   */
  public static MealSlotSkeleton skeletonFor(
      LocalDate onDate, int slotIndex, SlotKind kind, int timeBudgetMin) {
    return new MealSlotSkeleton(
        UUID.randomUUID(),
        UUID.randomUUID(),
        slotIndex,
        onDate,
        kind,
        kind.name().toLowerCase(java.util.Locale.ROOT),
        timeBudgetMin,
        true,
        new ArrayList<>(List.<UUID>of(UUID.randomUUID())));
  }

  /** A minimal {@link PlanCompositionContext} suitable for unit tests that bypass the filter. */
  public static PlanCompositionContext minimalContext(
      List<MealSlotSkeleton> skeletons, List<RecipeDto> recipePool) {
    return new PlanCompositionContext(
        UUID.randomUUID(),
        LocalDate.of(2026, 1, 5),
        skeletons,
        Map.<UUID, com.example.mealprep.preference.api.dto.HardConstraintsDto>of(),
        Map.<UUID, com.example.mealprep.household.api.dto.SoftPreferenceBundleDto>of(),
        null,
        null,
        null,
        new RecipePoolSnapshot(recipePool, Instant.parse("2026-01-01T00:00:00Z")),
        List.of(),
        UUID.randomUUID(),
        UUID.randomUUID());
  }

  private static Plan.PlanBuilder basePlanBuilder(
      UUID householdId, LocalDate weekStartDate, int generation, PlanStatus status) {
    return Plan.builder()
        .id(UUID.randomUUID())
        .householdId(householdId)
        .weekStartDate(weekStartDate)
        .generation(generation)
        .status(status)
        .triggerKind(TriggerKind.USER_INITIATED)
        .qualityWarning(false)
        .coldStart(false)
        .aiAugmented(false)
        .traceId(UUID.randomUUID())
        .decisionId(UUID.randomUUID())
        .scoreBreakdown(zeroScoreBreakdown())
        .rollupSummary(emptyRollup())
        .days(new ArrayList<>());
  }
}
