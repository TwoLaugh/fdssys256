package com.example.mealprep.planner.testdata;

import com.example.mealprep.core.types.SlotKind;
import com.example.mealprep.planner.api.dto.CandidatePlan;
import com.example.mealprep.planner.api.dto.DailyRollupDocument;
import com.example.mealprep.planner.api.dto.MealSlotSkeleton;
import com.example.mealprep.planner.api.dto.PlanCompositionContext;
import com.example.mealprep.planner.api.dto.RecipePoolSnapshot;
import com.example.mealprep.planner.api.dto.RollupSummaryDocument;
import com.example.mealprep.planner.api.dto.ScoreBreakdownDocument;
import com.example.mealprep.planner.api.dto.SlotAssignment;
import com.example.mealprep.planner.api.dto.WeeklyRollupDocument;
import com.example.mealprep.planner.config.PlannerProperties;
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
import com.example.mealprep.recipe.domain.entity.Complexity;
import com.example.mealprep.recipe.domain.entity.DataQuality;
import com.example.mealprep.recipe.domain.entity.NutritionStatus;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Duration;
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
        UUID.randomUUID(),
        Map.of());
  }

  // ---- planner-01e scoring fixture builders ---------------------------------------------------

  /**
   * v1-uniform weights (0.143 each) + default tuning constants, matching application.properties.
   */
  public static PlannerProperties.ScoringWeights uniformWeights() {
    BigDecimal w = new BigDecimal("0.143");
    return new PlannerProperties.ScoringWeights(w, w, w, w, w, w, w);
  }

  public static PlannerProperties.ScoringTuning defaultTuning() {
    return new PlannerProperties.ScoringTuning(
        new PlannerProperties.ScoringTuning.VarietyTargets(5, 4, 3, 2),
        new PlannerProperties.ScoringTuning.ProvisionsTuning(
            new PlannerProperties.ScoringTuning.ProvisionsTuning.WasteValueTiers(
                new BigDecimal("1.0"), new BigDecimal("2.0"), new BigDecimal("3.0"))),
        new PlannerProperties.ScoringTuning.CostTuning(new BigDecimal("0.1")));
  }

  /** Default mid-week sub-config (planner-01i): lock=24h, maxSuggestionsPerPlan=3. */
  public static PlannerProperties.MidWeek defaultMidWeek() {
    return new PlannerProperties.MidWeek(24, 3);
  }

  /**
   * Default materiality sub-config (planner-01k): nutrition variance 15%, &ge;3 redistributable
   * meals, soft-preference delta 10 points — matches application.properties.
   */
  public static PlannerProperties.Materiality defaultMateriality() {
    return new PlannerProperties.Materiality(new BigDecimal("0.15"), 3, 10);
  }

  /**
   * Default cold-start sub-config (recipe-pool Tier-2): enabled, 3× distinct-slot-kind threshold,
   * 50 recipe quota, PT20S timeout, empty source-keys (all enabled) — matches
   * application.properties.
   */
  public static PlannerProperties.ColdStart defaultColdStart() {
    return new PlannerProperties.ColdStart(true, 3, 50, Duration.ofSeconds(20), List.of());
  }

  /** Full {@link PlannerProperties} wired with the v1-uniform scoring block for unit tests. */
  public static PlannerProperties scoringProperties() {
    return new PlannerProperties(
        DayOfWeek.MONDAY,
        20,
        5,
        3,
        50,
        new BigDecimal("1.5"),
        Duration.ofSeconds(30),
        uniformWeights(),
        defaultTuning(),
        Duration.ofSeconds(20),
        3,
        5,
        2,
        defaultMidWeek(),
        defaultMateriality(),
        defaultColdStart());
  }

  /**
   * Build a recipe with explicit cuisine / protein / cooking-method / totalTime, suitable for the
   * variety + time sub-score tests. Ingredient mapping keys drive cost / provisions.
   */
  public static RecipeDto scoredRecipe(
      UUID id,
      int totalTimeMins,
      String cuisine,
      String protein,
      String cookingMethod,
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
            List.<String>of(),
            null,
            null,
            false,
            cuisine,
            List.of("dinner"));
    RecipeTagsDto tags =
        new RecipeTagsDto(
            protein, cookingMethod, Complexity.MODERATE, List.<String>of(), List.<String>of());
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
        null,
        0L,
        Instant.parse("2024-01-01T00:00:00Z"),
        Instant.parse("2024-01-01T00:00:00Z"),
        version,
        List.of());
  }

  /**
   * A {@link SlotAssignment} on {@code onDate} for {@code recipeId} with the given slot id and
   * servings. {@code slotId} must match the {@link MealSlotSkeleton#slotId()} used by the
   * time-budget lookup.
   */
  public static SlotAssignment assignment(
      UUID slotId, UUID recipeId, LocalDate onDate, int slotIndex, int servings) {
    return new SlotAssignment(
        UUID.randomUUID(),
        slotId,
        slotIndex,
        onDate,
        SlotKind.DINNER,
        recipeId,
        UUID.randomUUID(),
        UUID.randomUUID(),
        servings,
        false);
  }

  public static CandidatePlan candidatePlan(LocalDate weekStart, List<SlotAssignment> assignments) {
    return new CandidatePlan(UUID.randomUUID(), weekStart, assignments, null);
  }

  /**
   * Context carrying a recipe pool + slot skeletons + optional provisions + optional nutrition
   * targets, suitable for the sub-score / gate unit tests.
   */
  public static PlanCompositionContext scoringContext(
      List<MealSlotSkeleton> skeletons,
      List<RecipeDto> recipePool,
      com.example.mealprep.provisions.api.dto.ProvisionForPlannerBundleDto provisions,
      Map<UUID, com.example.mealprep.household.api.dto.SoftPreferenceBundleDto> softPrefs,
      Map<UUID, com.example.mealprep.nutrition.api.dto.TargetsDto> nutritionByUserId) {
    return new PlanCompositionContext(
        UUID.randomUUID(),
        LocalDate.of(2026, 1, 5),
        skeletons,
        Map.<UUID, com.example.mealprep.preference.api.dto.HardConstraintsDto>of(),
        softPrefs,
        null,
        provisions,
        null,
        new RecipePoolSnapshot(recipePool, Instant.parse("2026-01-01T00:00:00Z")),
        List.of(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        nutritionByUserId);
  }

  /** A budget DTO with the given weekly target in GBP (null target = no budget row). */
  public static com.example.mealprep.provisions.api.dto.BudgetDto budget(BigDecimal weeklyTarget) {
    return new com.example.mealprep.provisions.api.dto.BudgetDto(
        UUID.randomUUID(),
        UUID.randomUUID(),
        weeklyTarget,
        "GBP",
        BigDecimal.ZERO,
        com.example.mealprep.provisions.api.dto.PriceSensitivity.moderate,
        weeklyTarget != null,
        null,
        0L);
  }

  public static com.example.mealprep.provisions.api.dto.SupplierProductDto supplierProduct(
      String mappingKey, BigDecimal price) {
    return new com.example.mealprep.provisions.api.dto.SupplierProductDto(
        UUID.randomUUID(),
        "prod-" + mappingKey,
        "Tesco",
        mappingKey,
        price,
        price,
        "g",
        null,
        null,
        "grocery",
        null,
        LocalDate.of(2026, 1, 1),
        List.of(),
        mappingKey,
        0L);
  }

  /** Active inventory item with a relative expiry anchored to real wall-clock (no time-bomb). */
  public static com.example.mealprep.provisions.api.dto.InventoryItemDto inventoryItem(
      String mappingKey, BigDecimal quantity, int daysToExpiryFromToday) {
    return new com.example.mealprep.provisions.api.dto.InventoryItemDto(
        UUID.randomUUID(),
        UUID.randomUUID(),
        mappingKey,
        "grocery",
        com.example.mealprep.provisions.domain.entity.StorageLocation.CUPBOARD,
        com.example.mealprep.provisions.domain.entity.TrackingMode.QUANTITY,
        quantity,
        "g",
        BigDecimal.ZERO,
        com.example.mealprep.provisions.domain.entity.StapleStatus.STOCKED,
        false,
        LocalDate.now().plusDays(daysToExpiryFromToday),
        mappingKey,
        null,
        com.example.mealprep.provisions.domain.entity.ItemSource.MANUAL_ADD,
        null,
        com.example.mealprep.provisions.domain.entity.ItemLifecycleStatus.ACTIVE,
        null,
        Instant.parse("2026-01-01T00:00:00Z"),
        Instant.parse("2026-01-01T00:00:00Z"),
        0L);
  }

  public static com.example.mealprep.provisions.api.dto.ProvisionForPlannerBundleDto
      provisionsBundle(
          com.example.mealprep.provisions.api.dto.BudgetDto budget,
          Map<String, com.example.mealprep.provisions.api.dto.SupplierProductDto> prices,
          List<com.example.mealprep.provisions.api.dto.InventoryItemDto> activeInventory) {
    return new com.example.mealprep.provisions.api.dto.ProvisionForPlannerBundleDto(
        UUID.randomUUID(),
        activeInventory,
        List.of(),
        List.of(),
        budget,
        prices,
        new com.example.mealprep.provisions.api.dto.BundleStaleness(
            0, false, Instant.parse("2026-01-01T00:00:00Z")));
  }

  // ---- planner-01f rollup fixture builders ----------------------------------------------------

  /**
   * A full 7-day {@link CandidatePlan}: one DINNER slot per day, all pointing at {@code recipeId}.
   * Used by the rollup builder IT/tests to assert the 7-entry sorted daily list.
   */
  public static CandidatePlan weeklyPlanFixture(LocalDate weekStart, UUID recipeId) {
    List<SlotAssignment> assignments = new ArrayList<>();
    for (int d = 0; d < 7; d++) {
      assignments.add(assignment(UUID.randomUUID(), recipeId, weekStart.plusDays(d), 0, 2));
    }
    return candidatePlan(weekStart, assignments);
  }

  /**
   * A recipe whose {@code nutritionStatus} is {@code PENDING} (the codebase's "nutrition not yet
   * calculated" signal), so the rollup's {@code staleIngredientCount} counts it. Identical shape to
   * {@link #scoredRecipe} which already defaults to {@code PENDING} — named for intent at call
   * sites that specifically exercise the stale-count path.
   */
  public static RecipeDto planWithStaleNutrition(UUID id) {
    return scoredRecipe(id, 30, "Generic", "tofu", "fry", List.of("rice"));
  }

  // ---- planner-01g Stage-C fixture builders ---------------------------------------------------

  /** A successful (non-fallback) Stage-C result picking {@code chosenIndex}. */
  public static com.example.mealprep.planner.api.dto.StageCResult stageCResultLlm(
      int chosenIndex, String reasoning) {
    return new com.example.mealprep.planner.api.dto.StageCResult(
        chosenIndex,
        reasoning,
        com.example.mealprep.planner.domain.entity.AugmentationSource.LLM,
        false);
  }

  /** The deterministic-fallback Stage-C result (index 0, fixed reasoning, fallback=true). */
  public static com.example.mealprep.planner.api.dto.StageCResult stageCResultFallback() {
    return new com.example.mealprep.planner.api.dto.StageCResult(
        0,
        "AI ranking unavailable; deterministic top-scored candidate selected.",
        com.example.mealprep.planner.domain.entity.AugmentationSource.LLM,
        true);
  }

  /** A single-day {@link CandidateDailyRollupDto} with the given macros (micros empty). */
  public static com.example.mealprep.nutrition.api.dto.CandidateDailyRollupDto dailyRollup(
      LocalDate date, int kcal) {
    return new com.example.mealprep.nutrition.api.dto.CandidateDailyRollupDto(
        date,
        com.example.mealprep.nutrition.domain.entity.ActivityLevel.LIGHT_ACTIVITY,
        kcal,
        new BigDecimal("100"),
        new BigDecimal("200"),
        new BigDecimal("60"),
        new BigDecimal("30"),
        Map.of());
  }

  /** A one-day {@link CandidatePlanRollupDto} for {@code weekStart}. */
  public static com.example.mealprep.nutrition.api.dto.CandidatePlanRollupDto candidateRollup(
      LocalDate weekStart, int kcal) {
    return new com.example.mealprep.nutrition.api.dto.CandidatePlanRollupDto(
        weekStart, weekStart, List.of(dailyRollup(weekStart, kcal)));
  }

  /**
   * Two score-sorted candidates and their index-aligned rollups. Candidate 0 is the deterministic
   * top-scored one (higher kcal here is arbitrary fixture data, not a scoring signal).
   */
  public static List<CandidatePlan> twoCandidates(LocalDate weekStart) {
    return List.of(
        candidatePlan(
            weekStart, List.of(assignment(UUID.randomUUID(), UUID.randomUUID(), weekStart, 0, 2))),
        candidatePlan(
            weekStart, List.of(assignment(UUID.randomUUID(), UUID.randomUUID(), weekStart, 0, 2))));
  }

  /** Index-aligned rollups for {@link #twoCandidates(LocalDate)}. */
  public static List<com.example.mealprep.nutrition.api.dto.CandidatePlanRollupDto> twoRollups(
      LocalDate weekStart) {
    return List.of(candidateRollup(weekStart, 2100), candidateRollup(weekStart, 1900));
  }

  // ---- planner-01h Phase-2 augmentation fixture builders --------------------------------------

  /** Raw LLM {@code ADD_SNACK} proposal targeting {@code slotId} / {@code recipeId}. */
  public static com.example.mealprep.planner.api.dto.AugmentationProposal addSnackProposal(
      UUID slotId, UUID recipeId, int servings) {
    return new com.example.mealprep.planner.api.dto.AugmentationProposal(
        "ADD_SNACK", slotId, recipeId, servings, null, null, null, null, "fill a nutrition gap");
  }

  /** Raw LLM {@code SUBSTITUTE_INGREDIENT} refine-directive proposal. */
  public static com.example.mealprep.planner.api.dto.RefineDirectiveProposal
      refineDirectiveProposal(UUID slotId, String fromKey, String toKey) {
    return new com.example.mealprep.planner.api.dto.RefineDirectiveProposal(
        "SUBSTITUTE_INGREDIENT", slotId, fromKey, toKey, null, null, "swap for nutrition");
  }

  /** A {@code Phase2AugmentationResponse} with the supplied raw augmentation proposals. */
  public static com.example.mealprep.planner.domain.service.internal.stagec
          .Phase2AugmentationResponse
      phase2Response(
          List<com.example.mealprep.planner.api.dto.AugmentationProposal> augmentations,
          List<com.example.mealprep.planner.api.dto.RefineDirectiveProposal> refineDirectives) {
    return new com.example.mealprep.planner.domain.service.internal.stagec
        .Phase2AugmentationResponse(augmentations, refineDirectives);
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
