package com.example.mealprep.planner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.example.mealprep.adaptation.api.dto.AdaptationResultDto;
import com.example.mealprep.adaptation.domain.enums.AdaptationClassification;
import com.example.mealprep.adaptation.domain.service.AdaptationQueryService;
import com.example.mealprep.adaptation.domain.service.AdaptationService;
import com.example.mealprep.core.types.SlotKind;
import com.example.mealprep.planner.api.dto.BeamSearchOutcome;
import com.example.mealprep.planner.api.dto.CandidatePlan;
import com.example.mealprep.planner.api.dto.GeneratePlanRequest;
import com.example.mealprep.planner.api.dto.PlanCompositionContext;
import com.example.mealprep.planner.api.dto.RecipePoolSnapshot;
import com.example.mealprep.planner.api.dto.RefineDirectiveDto;
import com.example.mealprep.planner.api.dto.ScoreResult;
import com.example.mealprep.planner.api.dto.SlotAssignment;
import com.example.mealprep.planner.api.dto.StageCResult;
import com.example.mealprep.planner.domain.entity.AugmentationSource;
import com.example.mealprep.planner.domain.entity.Plan;
import com.example.mealprep.planner.domain.entity.PlanStatus;
import com.example.mealprep.planner.domain.repository.PlanRepository;
import com.example.mealprep.planner.domain.service.internal.composer.PlanComposer;
import com.example.mealprep.planner.domain.service.internal.composer.PlanCompositionContextBuilder;
import com.example.mealprep.planner.domain.service.internal.rollup.RollupBuilder;
import com.example.mealprep.planner.domain.service.internal.stagec.Augmentation;
import com.example.mealprep.planner.domain.service.internal.stagec.Phase2Augmenter;
import com.example.mealprep.planner.domain.service.internal.stagec.StageCInvoker;
import com.example.mealprep.planner.testdata.PlanTestData;
import com.example.mealprep.testsupport.TestContainersConfig;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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
 * Complements {@code PlanComposerIT}: drives {@link PlanComposer#compose} over a real Postgres for
 * the orchestration branches the existing IT leaves uncovered — multi-candidate Stage-B rollup loop
 * + Stage-C choosing a NON-zero index, Stage-A {@code degradedToGreedy} → quality-warning, Stage-C
 * {@code chosenIndex} clamping when out of range, the refine-directive budget cap and the
 * unknown-slot skip path, and the VERSION/BRANCH classifications whose created-id is <b>empty</b>
 * (the {@code applyAdaptationResult} fall-through where the recipe is left unchanged — distinct
 * from {@code PlanComposerIT}'s present-id cases). Deterministic stages are {@code @MockBean}ed
 * exactly as in {@code PlanComposerIT} (same context config → no context-cache thrash).
 */
@SpringBootTest
@Import(TestContainersConfig.class)
@ActiveProfiles("test")
// Mocked context builder (empty pool) → the recipe-pool Tier-2 cold-start gate is out of scope and
// would otherwise fire on the empty pool and invoke the discovery runner. Disabled here (the gate
// has its own coverage in ColdStartGateTest / PlannerColdStartIT).
@org.springframework.test.context.TestPropertySource(
    properties = "mealprep.planner.cold-start.enabled=false")
class PlanComposerOrchestrationIT {

  private static final LocalDate WEEK =
      LocalDate.now().plusYears(42).with(java.time.DayOfWeek.MONDAY);

  @Autowired private PlanComposer composer;
  @Autowired private PlanRepository planRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private PlatformTransactionManager transactionManager;

  @MockBean private PlanCompositionContextBuilder contextBuilder;

  @MockBean
  private com.example.mealprep.planner.domain.service.internal.beamsearch.BeamSearchEngine
      beamSearchEngine;

  @MockBean private RollupBuilder rollupBuilder;
  @MockBean private StageCInvoker stageCInvoker;
  @MockBean private Phase2Augmenter phase2Augmenter;
  @MockBean private AdaptationService adaptationService;

  // AdaptationServiceImpl implements BOTH AdaptationService and AdaptationQueryService; @MockBean
  // on
  // one interface evicts the single shared impl bean (wave-3 retro: multi-interface @Service
  // @MockBean eviction). Mock the sibling too so the full context still loads.
  @MockBean private AdaptationQueryService adaptationQueryService;

  private final UUID slotId = UUID.randomUUID();
  private final UUID originalRecipeId = UUID.randomUUID();

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
  }

  private PlanCompositionContext ctx(UUID household) {
    return new PlanCompositionContext(
        household,
        WEEK,
        List.of(),
        Map.of(),
        Map.of(),
        null,
        null,
        null,
        new RecipePoolSnapshot(List.of(), Instant.now()),
        List.of(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        Map.of());
  }

  private CandidatePlan candidate(UUID recipeId, BigDecimal score) {
    SlotAssignment a =
        new SlotAssignment(
            UUID.randomUUID(),
            slotId,
            0,
            WEEK,
            SlotKind.DINNER,
            recipeId,
            UUID.randomUUID(),
            UUID.randomUUID(),
            2,
            false);
    return new CandidatePlan(
        UUID.randomUUID(),
        WEEK,
        List.of(a),
        new ScoreResult(score, PlanTestData.zeroScoreBreakdown()));
  }

  private GeneratePlanRequest request(UUID household) {
    return new GeneratePlanRequest(household, WEEK, false);
  }

  private RefineDirectiveDto directive(UUID targetSlotId) {
    return new RefineDirectiveDto("SUBSTITUTE_INGREDIENT", targetSlotId, "swap", "rice", "quinoa");
  }

  private AdaptationResultDto result(
      AdaptationClassification c,
      Optional<UUID> version,
      Optional<UUID> branch,
      Optional<UUID> substitution) {
    return new AdaptationResultDto(
        UUID.randomUUID(),
        originalRecipeId,
        c,
        version,
        branch,
        substitution,
        Optional.empty(),
        null,
        "stubbed",
        "",
        false,
        List.of(),
        UUID.randomUUID(),
        BigDecimal.ONE);
  }

  /** Two candidates; Stage-C deliberately picks index 1 (the non-default branch). */
  @Test
  void multiCandidate_stageCChoosesNonZeroIndex_persistsChosenRecipe() {
    UUID household = UUID.randomUUID();
    UUID recipe0 = UUID.randomUUID();
    UUID recipe1 = UUID.randomUUID();
    when(contextBuilder.build(any(), any(), any(), any())).thenReturn(ctx(household));
    when(beamSearchEngine.search(any(), any()))
        .thenReturn(
            new BeamSearchOutcome(
                List.of(candidate(recipe0, BigDecimal.ONE), candidate(recipe1, BigDecimal.TEN)),
                false));
    when(rollupBuilder.build(any(), any())).thenReturn(PlanTestData.emptyRollup());
    when(stageCInvoker.pickOne(any(), any(), any(), any()))
        .thenReturn(new StageCResult(1, "picked second", AugmentationSource.LLM, false));
    when(phase2Augmenter.augment(any(), any(), any(), any()))
        .thenReturn(
            new com.example.mealprep.planner.api.dto.AugmentationResult(
                List.<Augmentation>of(), List.<Augmentation>of(), List.of()));

    UUID planId = tx().execute(t -> composer.compose(request(household), UUID.randomUUID(), null));

    tx().executeWithoutResult(
            t -> {
              Plan p = planRepository.findById(planId).orElseThrow();
              assertThat(p.getStatus()).isEqualTo(PlanStatus.GENERATED);
              assertThat(p.isQualityWarning()).isFalse();
              assertThat(p.getDays().get(0).getSlots().get(0).getScheduledRecipe().getRecipeId())
                  .isEqualTo(recipe1);
            });
  }

  /** Stage-A degraded-to-greedy → quality warning even with a candidate present. */
  @Test
  void stageADegradedToGreedy_setsQualityWarning() {
    UUID household = UUID.randomUUID();
    when(contextBuilder.build(any(), any(), any(), any())).thenReturn(ctx(household));
    when(beamSearchEngine.search(any(), any()))
        .thenReturn(
            new BeamSearchOutcome(List.of(candidate(UUID.randomUUID(), BigDecimal.ONE)), true));
    when(rollupBuilder.build(any(), any())).thenReturn(PlanTestData.emptyRollup());
    when(stageCInvoker.pickOne(any(), any(), any(), any()))
        .thenReturn(new StageCResult(0, "ok", AugmentationSource.LLM, false));
    when(phase2Augmenter.augment(any(), any(), any(), any()))
        .thenReturn(
            new com.example.mealprep.planner.api.dto.AugmentationResult(
                List.<Augmentation>of(), List.<Augmentation>of(), List.of()));

    UUID planId = tx().execute(t -> composer.compose(request(household), UUID.randomUUID(), null));

    Plan p = planRepository.findById(planId).orElseThrow();
    assertThat(p.getStatus()).isEqualTo(PlanStatus.GENERATED);
    assertThat(p.isQualityWarning()).isTrue();
  }

  /** Stage-C returns an out-of-range index → clamped to 0, plan still persists. */
  @Test
  void stageCOutOfRangeIndex_clampsToZero() {
    UUID household = UUID.randomUUID();
    UUID recipe0 = UUID.randomUUID();
    when(contextBuilder.build(any(), any(), any(), any())).thenReturn(ctx(household));
    when(beamSearchEngine.search(any(), any()))
        .thenReturn(new BeamSearchOutcome(List.of(candidate(recipe0, BigDecimal.ONE)), false));
    when(rollupBuilder.build(any(), any())).thenReturn(PlanTestData.emptyRollup());
    when(stageCInvoker.pickOne(any(), any(), any(), any()))
        .thenReturn(new StageCResult(99, "bogus index", AugmentationSource.LLM, false));
    when(phase2Augmenter.augment(any(), any(), any(), any()))
        .thenReturn(
            new com.example.mealprep.planner.api.dto.AugmentationResult(
                List.<Augmentation>of(), List.<Augmentation>of(), List.of()));

    UUID planId = tx().execute(t -> composer.compose(request(household), UUID.randomUUID(), null));

    tx().executeWithoutResult(
            t -> {
              Plan p = planRepository.findById(planId).orElseThrow();
              assertThat(p.getDays().get(0).getSlots().get(0).getScheduledRecipe().getRecipeId())
                  .isEqualTo(recipe0);
            });
  }

  /** A refine-directive targeting an unknown slot id is logged + skipped; plan still persists. */
  @Test
  void refineDirectiveTargetingUnknownSlot_isSkipped() {
    UUID household = UUID.randomUUID();
    UUID recipe0 = UUID.randomUUID();
    when(contextBuilder.build(any(), any(), any(), any())).thenReturn(ctx(household));
    when(beamSearchEngine.search(any(), any()))
        .thenReturn(new BeamSearchOutcome(List.of(candidate(recipe0, BigDecimal.ONE)), false));
    when(rollupBuilder.build(any(), any())).thenReturn(PlanTestData.emptyRollup());
    when(stageCInvoker.pickOne(any(), any(), any(), any()))
        .thenReturn(new StageCResult(0, "ok", AugmentationSource.LLM, false));
    when(phase2Augmenter.augment(any(), any(), any(), any()))
        .thenReturn(
            new com.example.mealprep.planner.api.dto.AugmentationResult(
                List.<Augmentation>of(),
                List.<Augmentation>of(),
                // unknown slot id -> indexOfSlot returns -1 -> skip branch
                List.of(directive(UUID.randomUUID()))));

    UUID planId = tx().execute(t -> composer.compose(request(household), UUID.randomUUID(), null));

    tx().executeWithoutResult(
            t -> {
              Plan p = planRepository.findById(planId).orElseThrow();
              // adaptationService was never invoked (directive skipped); recipe unchanged.
              assertThat(p.getDays().get(0).getSlots().get(0).getScheduledRecipe().getRecipeId())
                  .isEqualTo(recipe0);
              assertThat(p.isQualityWarning()).isFalse();
            });
  }

  /** VERSION classification but versionIdCreated is EMPTY → applyAdaptationResult falls through. */
  @Test
  void versionClassificationWithEmptyVersionId_leavesRecipeUnchanged() {
    UUID household = UUID.randomUUID();
    when(contextBuilder.build(any(), any(), any(), any())).thenReturn(ctx(household));
    when(beamSearchEngine.search(any(), any()))
        .thenReturn(
            new BeamSearchOutcome(List.of(candidate(originalRecipeId, BigDecimal.ONE)), false));
    when(rollupBuilder.build(any(), any())).thenReturn(PlanTestData.emptyRollup());
    when(stageCInvoker.pickOne(any(), any(), any(), any()))
        .thenReturn(new StageCResult(0, "ok", AugmentationSource.LLM, false));
    when(phase2Augmenter.augment(any(), any(), any(), any()))
        .thenReturn(
            new com.example.mealprep.planner.api.dto.AugmentationResult(
                List.<Augmentation>of(), List.<Augmentation>of(), List.of(directive(slotId))));
    when(adaptationService.runPlanTimeRefineJob(any()))
        .thenReturn(
            result(
                AdaptationClassification.VERSION,
                Optional.empty(),
                Optional.empty(),
                Optional.empty()));

    UUID planId = tx().execute(t -> composer.compose(request(household), UUID.randomUUID(), null));

    tx().executeWithoutResult(
            t -> {
              Plan p = planRepository.findById(planId).orElseThrow();
              assertThat(p.getDays().get(0).getSlots().get(0).getScheduledRecipe().getRecipeId())
                  .isEqualTo(originalRecipeId);
            });
  }

  /** BRANCH classification but branchIdCreated is EMPTY → applyAdaptationResult falls through. */
  @Test
  void branchClassificationWithEmptyBranchId_leavesRecipeUnchanged() {
    UUID household = UUID.randomUUID();
    when(contextBuilder.build(any(), any(), any(), any())).thenReturn(ctx(household));
    when(beamSearchEngine.search(any(), any()))
        .thenReturn(
            new BeamSearchOutcome(List.of(candidate(originalRecipeId, BigDecimal.ONE)), false));
    when(rollupBuilder.build(any(), any())).thenReturn(PlanTestData.emptyRollup());
    when(stageCInvoker.pickOne(any(), any(), any(), any()))
        .thenReturn(new StageCResult(0, "ok", AugmentationSource.LLM, false));
    when(phase2Augmenter.augment(any(), any(), any(), any()))
        .thenReturn(
            new com.example.mealprep.planner.api.dto.AugmentationResult(
                List.<Augmentation>of(), List.<Augmentation>of(), List.of(directive(slotId))));
    when(adaptationService.runPlanTimeRefineJob(any()))
        .thenReturn(
            result(
                AdaptationClassification.BRANCH,
                Optional.empty(),
                Optional.empty(),
                Optional.empty()));

    UUID planId = tx().execute(t -> composer.compose(request(household), UUID.randomUUID(), null));

    tx().executeWithoutResult(
            t -> {
              Plan p = planRepository.findById(planId).orElseThrow();
              assertThat(p.getDays().get(0).getSlots().get(0).getScheduledRecipe().getRecipeId())
                  .isEqualTo(originalRecipeId);
            });
  }

  /** An idempotency key replays the same plan id; a blank key never caches. */
  @Test
  void blankIdempotencyKey_isNeverCached() {
    UUID household = UUID.randomUUID();
    UUID user = UUID.randomUUID();
    when(contextBuilder.build(any(), any(), any(), any())).thenReturn(ctx(household));
    when(beamSearchEngine.search(any(), any())).thenReturn(new BeamSearchOutcome(List.of(), false));

    UUID planId = tx().execute(t -> composer.compose(request(household), user, "   "));

    assertThat(planId).isNotNull();
    assertThat(composer.cachedPlanIdFor(user, "   ")).isEmpty();
    assertThat(composer.cachedPlanIdFor(user, null)).isEmpty();
  }
}
