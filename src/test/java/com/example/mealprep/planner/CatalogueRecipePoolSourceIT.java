package com.example.mealprep.planner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.example.mealprep.adaptation.domain.service.AdaptationQueryService;
import com.example.mealprep.adaptation.domain.service.AdaptationService;
import com.example.mealprep.core.types.SlotKind;
import com.example.mealprep.household.api.dto.HouseholdDto;
import com.example.mealprep.household.api.dto.HouseholdMemberDto;
import com.example.mealprep.household.api.dto.PlannerSlotEntryDto;
import com.example.mealprep.household.api.dto.SlotConfigurationPlannerViewDto;
import com.example.mealprep.household.domain.entity.HouseholdRole;
import com.example.mealprep.household.domain.service.HouseholdMergeService;
import com.example.mealprep.household.domain.service.HouseholdQueryService;
import com.example.mealprep.household.domain.service.HouseholdUpdateService;
import com.example.mealprep.planner.api.dto.GeneratePlanRequest;
import com.example.mealprep.planner.api.dto.MealSlotSkeleton;
import com.example.mealprep.planner.api.dto.StageCResult;
import com.example.mealprep.planner.domain.entity.AugmentationSource;
import com.example.mealprep.planner.domain.entity.MealSlot;
import com.example.mealprep.planner.domain.entity.Plan;
import com.example.mealprep.planner.domain.entity.PlanStatus;
import com.example.mealprep.planner.domain.entity.ScheduledRecipe;
import com.example.mealprep.planner.domain.repository.PlanRepository;
import com.example.mealprep.planner.domain.service.internal.composer.PlanComposer;
import com.example.mealprep.planner.domain.service.internal.composer.RecipePoolSource;
import com.example.mealprep.planner.domain.service.internal.stagec.Augmentation;
import com.example.mealprep.planner.domain.service.internal.stagec.Phase2Augmenter;
import com.example.mealprep.planner.domain.service.internal.stagec.StageCInvoker;
import com.example.mealprep.recipe.api.dto.CreateRecipeMetadataRequest;
import com.example.mealprep.recipe.api.dto.CreateRecipeRequest;
import com.example.mealprep.recipe.api.dto.RecipeDto;
import com.example.mealprep.recipe.domain.service.RecipeQueryService;
import com.example.mealprep.recipe.domain.service.RecipeUpdateService;
import com.example.mealprep.recipe.testdata.RecipeTestData;
import com.example.mealprep.testsupport.TestContainersConfig;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Integration test for the Tier-1 catalogue-backed recipe pool (planner-01j Stage A). Seeds USER
 * recipes for each meal-kind over a real Postgres, then proves the two ends of the seam:
 *
 * <ol>
 *   <li>the real {@link RecipePoolSource} (bound to {@code CatalogueRecipePoolSource} — {@code
 *       NoOpRecipePoolSource} steps aside via {@code @ConditionalOnMissingBean}) returns the seeded
 *       candidates, hydrated enough to survive the hard filters; and
 *   <li>driving the real {@link PlanComposer} (real beam search, hard filter, scoring, rollup,
 *       persister) end-to-end yields a persisted plan whose slots are filled with the seeded
 *       recipes — the actual "generation produces non-empty slots" proof.
 * </ol>
 *
 * <p>Only the cross-module read surfaces are {@code @MockBean}ed: {@link HouseholdQueryService}
 * (roster for the pool source + slot-config for the context builder), the LLM Stage-C {@link
 * StageCInvoker}, and the preference/nutrition/provisions readers (so the run is deterministic and
 * isolated from other modules' data). The pool source, recipe query service, beam search, hard
 * filter, scoring and persistence are all the real beans.
 */
@SpringBootTest
@Import(TestContainersConfig.class)
@ActiveProfiles("test")
class CatalogueRecipePoolSourceIT {

  private static final LocalDate WEEK =
      LocalDate.now().plusYears(45).with(java.time.DayOfWeek.MONDAY);

  @Autowired private RecipePoolSource recipePoolSource;
  @Autowired private RecipeQueryService recipeQueryService;
  @Autowired private RecipeUpdateService recipeUpdateService;
  @Autowired private PlanComposer composer;
  @Autowired private PlanRepository planRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private PlatformTransactionManager transactionManager;

  @MockBean private HouseholdQueryService householdQueryService;
  // HouseholdServiceImpl implements all three interfaces; @MockBean on one evicts the shared impl,
  // so the siblings must be mocked too or HouseholdModule fails to wire (multi-interface eviction).
  @MockBean private HouseholdUpdateService householdUpdateService;
  @MockBean private HouseholdMergeService householdMergeService;

  // The two AI-backed stages are mocked deterministically (the test profile's TestAiService has no
  // canned Stage-C / Phase-2 response registered). Everything else in the composer pipeline — beam
  // search, hard filter, scoring, rollup, persistence — is the real bean.
  @MockBean private StageCInvoker stageCInvoker;
  @MockBean private Phase2Augmenter phase2Augmenter;

  // PreferenceQueryService / NutritionQueryService / ProvisionForPlannerService are deliberately
  // NOT mocked: each is a multi-interface @Service (e.g. PreferenceServiceImpl also implements
  // PreferenceUpdateService) so @MockBean would evict the shared impl and break the sibling's
  // wiring (documented multi-interface eviction). The real beans return empty/null for the
  // never-seeded test users — exactly the inputs we want — so we keep them real.

  // AdaptationServiceImpl implements both AdaptationService + AdaptationQueryService; mock both to
  // avoid the multi-interface @MockBean eviction (same retro as the other planner ITs). The mocked
  // Phase-2 emits no directives, so Stage D never actually calls adaptationService.
  @MockBean private AdaptationService adaptationService;
  @MockBean private AdaptationQueryService adaptationQueryService;

  private TransactionTemplate tx() {
    return new TransactionTemplate(transactionManager);
  }

  @AfterEach
  void cleanup() {
    jdbcTemplate.update("DELETE FROM planner_plan_reopt_suggestions");
    jdbcTemplate.update("DELETE FROM planner_reopt_suggestions");
    jdbcTemplate.update("DELETE FROM planner_scheduled_recipes");
    jdbcTemplate.update("DELETE FROM planner_meal_slots");
    jdbcTemplate.update("DELETE FROM planner_days");
    jdbcTemplate.update("DELETE FROM planner_plans");
    jdbcTemplate.update("DELETE FROM decision_log");
    jdbcTemplate.update("DELETE FROM recipe_imports");
    jdbcTemplate.update("DELETE FROM recipe_tags");
    jdbcTemplate.update("DELETE FROM recipe_metadata");
    jdbcTemplate.update("DELETE FROM recipe_method_steps");
    jdbcTemplate.update("DELETE FROM recipe_ingredients");
    jdbcTemplate.update("UPDATE recipe_recipes SET current_branch_id = NULL");
    jdbcTemplate.update("DELETE FROM recipe_versions");
    jdbcTemplate.update("DELETE FROM recipe_branches");
    jdbcTemplate.update("DELETE FROM recipe_recipes");
  }

  // ---------------- seeding ----------------

  /** Create a USER-catalogue recipe of the given kind + total time for {@code userId}. */
  private UUID seedRecipe(UUID userId, String name, SlotKind kind, int totalTimeMins) {
    CreateRecipeMetadataRequest metadata =
        new CreateRecipeMetadataRequest(
            2,
            Math.max(5, totalTimeMins / 3),
            Math.max(5, totalTimeMins * 2 / 3),
            totalTimeMins,
            // No equipment required so the hard filter's equipment gate always passes (provisions
            // are mocked empty).
            List.of(),
            3,
            2,
            true,
            "Generic",
            List.of(kind.name().toLowerCase(java.util.Locale.ROOT)));
    CreateRecipeRequest request =
        new CreateRecipeRequest(
            name,
            "seeded for planner pool IT",
            RecipeTestData.defaultIngredients(),
            RecipeTestData.defaultMethod(),
            metadata,
            RecipeTestData.defaultTags());
    return tx().execute(t -> recipeUpdateService.createRecipe(userId, request).id());
  }

  /** Seed {@code perKind} recipes for each of the supplied kinds; returns all seeded recipe ids. */
  private Set<UUID> seedCatalogue(UUID userId, int perKind, SlotKind... kinds) {
    Set<UUID> ids = new java.util.LinkedHashSet<>();
    for (SlotKind kind : kinds) {
      for (int i = 0; i < perKind; i++) {
        // vary the total time so beam search has distinct candidates, all within a 60-min budget.
        ids.add(seedRecipe(userId, kind + "-recipe-" + i, kind, 20 + i * 5));
      }
    }
    return ids;
  }

  // ---------------- household stubbing ----------------

  private void stubHousehold(UUID householdId, UUID userId) {
    HouseholdMemberDto member =
        new HouseholdMemberDto(
            UUID.randomUUID(),
            householdId,
            userId,
            HouseholdRole.primary,
            "owner",
            0,
            Instant.now(),
            0L);
    when(householdQueryService.getById(eq(householdId)))
        .thenReturn(
            Optional.of(
                new HouseholdDto(householdId, "h", userId, List.of(member), Instant.now(), 0L)));
  }

  private void stubSlotConfig(UUID householdId, UUID userId, SlotKind... kinds) {
    List<PlannerSlotEntryDto> slots = new ArrayList<>();
    for (SlotKind kind : kinds) {
      slots.add(
          new PlannerSlotEntryDto(
              kind.name().toLowerCase(java.util.Locale.ROOT),
              toHouseholdKind(kind),
              true,
              1,
              60,
              null,
              null));
    }
    when(householdQueryService.getSlotConfigurationPlannerView(householdId))
        .thenReturn(
            new SlotConfigurationPlannerViewDto(
                householdId, slots, List.of(userId), List.of(userId), null, null, Instant.now()));
    when(householdQueryService.getSettings(eq(householdId), any())).thenReturn(Optional.empty());
  }

  private com.example.mealprep.household.domain.entity.SlotKind toHouseholdKind(SlotKind kind) {
    return switch (kind) {
      case BREAKFAST -> com.example.mealprep.household.domain.entity.SlotKind.breakfast;
      case LUNCH -> com.example.mealprep.household.domain.entity.SlotKind.lunch;
      case DINNER -> com.example.mealprep.household.domain.entity.SlotKind.dinner;
      case SNACK -> com.example.mealprep.household.domain.entity.SlotKind.snack;
      case CUSTOM -> com.example.mealprep.household.domain.entity.SlotKind.custom;
    };
  }

  // ============================================================================================
  // 1. The pool source returns the seeded catalogue, fully hydrated.
  // ============================================================================================

  @Test
  void fetchPool_returnsSeededUserRecipes_hydratedForStageA() {
    UUID household = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    Set<UUID> seeded =
        seedCatalogue(userId, 3, SlotKind.BREAKFAST, SlotKind.LUNCH, SlotKind.DINNER);
    stubHousehold(household, userId);

    List<MealSlotSkeleton> skeletons =
        List.of(
            new MealSlotSkeleton(
                UUID.randomUUID(),
                UUID.randomUUID(),
                0,
                WEEK,
                SlotKind.DINNER,
                "dinner",
                60,
                true,
                List.of(userId)));

    List<RecipeDto> pool =
        tx().execute(t -> recipePoolSource.fetchPool(household, skeletons, UUID.randomUUID()));

    // All 9 seeded recipes are returned (kind filtering is the planner's job, not the pool's).
    assertThat(pool).extracting(RecipeDto::id).containsExactlyInAnyOrderElementsOf(seeded);
    // Every candidate is hydrated: current-version body + the fields Stage A's HardFilterRunner
    // reads (mealTypes, totalTimeMins) + the ids the beam search schedules.
    assertThat(pool)
        .allSatisfy(
            r -> {
              assertThat(r.currentVersionBody()).isNotNull();
              assertThat(r.currentVersionBody().id()).isNotNull();
              assertThat(r.currentBranchId()).isNotNull();
              assertThat(r.currentVersionBody().metadata()).isNotNull();
              assertThat(r.currentVersionBody().metadata().mealTypes()).isNotEmpty();
              assertThat(r.currentVersionBody().metadata().totalTimeMins()).isPositive();
              assertThat(r.currentVersionBody().ingredients()).isNotEmpty();
              assertThat(r.currentVersionBody().ingredients())
                  .allSatisfy(i -> assertThat(i.ingredientMappingKey()).isNotNull());
            });
  }

  @Test
  void fetchPool_isUserScoped_doesNotLeakAnotherUsersRecipes() {
    UUID household = UUID.randomUUID();
    UUID owner = UUID.randomUUID();
    UUID stranger = UUID.randomUUID();
    Set<UUID> ownerRecipes = seedCatalogue(owner, 2, SlotKind.DINNER);
    Set<UUID> strangerRecipes = seedCatalogue(stranger, 2, SlotKind.DINNER);
    stubHousehold(household, owner);

    List<RecipeDto> pool =
        tx().execute(t -> recipePoolSource.fetchPool(household, List.of(), UUID.randomUUID()));

    assertThat(pool).extracting(RecipeDto::id).containsExactlyInAnyOrderElementsOf(ownerRecipes);
    assertThat(pool).extracting(RecipeDto::id).doesNotContainAnyElementsOf(strangerRecipes);
  }

  // ============================================================================================
  // 2. The real composer fills slots from the seeded catalogue (the generation proof).
  // ============================================================================================

  @Test
  void generate_withSeededCatalogue_producesPlanWithNonEmptySlots() {
    UUID household = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    Set<UUID> seeded = seedCatalogue(userId, 3, SlotKind.DINNER);
    stubHousehold(household, userId);
    stubSlotConfig(household, userId, SlotKind.DINNER);
    // Deterministic LLM pick: always candidate 0; no augmentations (Phase-2 emits empty directives
    // in this codebase, so no Stage-D adaptation runs).
    when(stageCInvoker.pickOne(any(), any(), any(), any()))
        .thenReturn(new StageCResult(0, "picked top-scored", AugmentationSource.LLM, false));
    when(phase2Augmenter.augment(any(), any(), any(), any()))
        .thenReturn(
            new com.example.mealprep.planner.api.dto.AugmentationResult(
                List.<Augmentation>of(), List.<Augmentation>of(), List.of()));

    GeneratePlanRequest request = new GeneratePlanRequest(household, WEEK, false);
    UUID planId = tx().execute(t -> composer.compose(request, userId, null));

    assertThat(planId).isNotNull();
    tx().executeWithoutResult(
            t -> {
              Plan plan = planRepository.findById(planId).orElseThrow();
              assertThat(plan.getStatus()).isEqualTo(PlanStatus.GENERATED);
              // One DINNER slot per day across the 7-day week.
              List<MealSlot> slots =
                  plan.getDays().stream().flatMap(d -> d.getSlots().stream()).toList();
              assertThat(slots).hasSize(7);
              // Every slot is filled with a scheduled recipe drawn from the seeded catalogue —
              // this is the real proof that generation no longer yields an empty plan.
              assertThat(slots)
                  .allSatisfy(
                      slot -> {
                        ScheduledRecipe sr = slot.getScheduledRecipe();
                        assertThat(sr).isNotNull();
                        assertThat(sr.getRecipeId()).isNotNull();
                        assertThat(seeded).contains(sr.getRecipeId());
                      });
              List<UUID> scheduledRecipeIds =
                  slots.stream()
                      .map(s -> s.getScheduledRecipe().getRecipeId())
                      .collect(Collectors.toList());
              assertThat(seeded).containsAll(Set.copyOf(scheduledRecipeIds));
              // Not a quality-warning plan: the pool was non-empty so Stage A produced candidates.
              assertThat(plan.isQualityWarning()).isFalse();
            });
  }

  @Test
  void recipePoolSource_boundBeanIsCatalogueBacked_notNoOp() {
    // The conditional NoOp must have stepped aside; the wired bean is the catalogue source.
    assertThat(recipePoolSource.getClass().getSimpleName()).isEqualTo("CatalogueRecipePoolSource");
  }
}
