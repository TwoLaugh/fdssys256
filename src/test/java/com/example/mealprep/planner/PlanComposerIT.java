package com.example.mealprep.planner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.example.mealprep.adaptation.api.dto.AdaptationResultDto;
import com.example.mealprep.adaptation.domain.enums.AdaptationClassification;
import com.example.mealprep.adaptation.domain.service.AdaptationQueryService;
import com.example.mealprep.adaptation.domain.service.AdaptationService;
import com.example.mealprep.adaptation.exception.AdaptationAiUnavailableException;
import com.example.mealprep.core.types.SlotKind;
import com.example.mealprep.planner.api.dto.BeamSearchOutcome;
import com.example.mealprep.planner.api.dto.CandidatePlan;
import com.example.mealprep.planner.api.dto.GeneratePlanRequest;
import com.example.mealprep.planner.api.dto.PlanCompositionContext;
import com.example.mealprep.planner.api.dto.RecipePoolSnapshot;
import com.example.mealprep.planner.api.dto.RefineDirectiveDto;
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
 * End-to-end {@link PlanComposer} Stage A&rarr;D wiring over a real Postgres (Testcontainers). The
 * deterministic stages (context build / beam search / rollup / Stage C / Phase 2) are
 * {@code @MockBean}ed so the test drives the composer's <b>orchestration + Stage-D routing +
 * persistence + idempotency</b> precisely — {@code @MockBean AdaptationService} stubs the full
 * classification matrix (VERSION / BRANCH / SUBSTITUTION / NO_CHANGE / AiUnavailable) per the
 * ticket DoD.
 */
@SpringBootTest
@Import(TestContainersConfig.class)
@ActiveProfiles("test")
class PlanComposerIT {

  private static final LocalDate WEEK =
      LocalDate.now().plusYears(40).with(java.time.DayOfWeek.MONDAY);

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
  // one interface evicts the single shared impl bean, leaving AdaptationAdminController unable to
  // wire AdaptationQueryService → context-load failure (wave-3 retro: multi-interface @Service
  // @MockBean eviction). Mock the sibling interface too so the full context still loads.
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
  }

  private UUID slotId = UUID.randomUUID();
  private UUID originalRecipeId = UUID.randomUUID();

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

  private CandidatePlan oneSlotCandidate() {
    SlotAssignment a =
        new SlotAssignment(
            UUID.randomUUID(),
            slotId,
            0,
            WEEK,
            SlotKind.DINNER,
            originalRecipeId,
            UUID.randomUUID(),
            UUID.randomUUID(),
            2,
            false);
    return new CandidatePlan(
        UUID.randomUUID(),
        WEEK,
        List.of(a),
        new com.example.mealprep.planner.api.dto.ScoreResult(
            BigDecimal.ONE, PlanTestData.zeroScoreBreakdown()));
  }

  private void wireDeterministicStages(UUID household, List<RefineDirectiveDto> directives) {
    PlanCompositionContext context = ctx(household);
    CandidatePlan candidate = oneSlotCandidate();
    when(contextBuilder.build(any(), any(), any(), any())).thenReturn(context);
    when(beamSearchEngine.search(any(), any()))
        .thenReturn(new BeamSearchOutcome(List.of(candidate), false));
    when(rollupBuilder.build(any(), any())).thenReturn(PlanTestData.emptyRollup());
    when(stageCInvoker.pickOne(any(), any(), any(), any()))
        .thenReturn(new StageCResult(0, "picked", AugmentationSource.LLM, false));
    when(phase2Augmenter.augment(any(), any(), any(), any()))
        .thenReturn(
            new com.example.mealprep.planner.api.dto.AugmentationResult(
                List.<Augmentation>of(), List.<Augmentation>of(), directives));
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

  private GeneratePlanRequest request(UUID household) {
    return new GeneratePlanRequest(household, WEEK, false);
  }

  private RefineDirectiveDto directive() {
    return new RefineDirectiveDto("SUBSTITUTE_INGREDIENT", slotId, "swap", "rice", "quinoa");
  }

  @Test
  void noCandidates_persistsQualityWarningPlan() {
    UUID household = UUID.randomUUID();
    PlanCompositionContext context = ctx(household);
    when(contextBuilder.build(any(), any(), any(), any())).thenReturn(context);
    when(beamSearchEngine.search(any(), any())).thenReturn(new BeamSearchOutcome(List.of(), false));

    UUID planId = tx().execute(t -> composer.compose(request(household), UUID.randomUUID(), null));

    assertThat(planId).isNotNull();
    Plan reloaded = planRepository.findById(planId).orElseThrow();
    assertThat(reloaded.getStatus()).isEqualTo(PlanStatus.GENERATED);
    assertThat(reloaded.isQualityWarning()).isTrue();
  }

  @Test
  void stageD_versionClassification_replacesRecipeId() {
    UUID household = UUID.randomUUID();
    UUID newVersion = UUID.randomUUID();
    wireDeterministicStages(household, List.of(directive()));
    when(adaptationService.runPlanTimeRefineJob(any()))
        .thenReturn(
            result(
                AdaptationClassification.VERSION,
                Optional.of(newVersion),
                Optional.empty(),
                Optional.empty()));

    UUID planId = tx().execute(t -> composer.compose(request(household), UUID.randomUUID(), null));

    // Reload + lazy navigation MUST run inside a session: OSIV is off (prod parity, mirrored into
    // test props), so Plan.days/slots are uninitialised on a detached findById result (wave-3
    // retro: lazy access outside a tx → LazyInitializationException).
    tx().executeWithoutResult(
            t -> {
              Plan reloaded = planRepository.findById(planId).orElseThrow();
              UUID persistedRecipeId =
                  reloaded.getDays().get(0).getSlots().get(0).getScheduledRecipe().getRecipeId();
              assertThat(persistedRecipeId).isEqualTo(newVersion);
            });
  }

  @Test
  void stageD_branchClassification_replacesBranch() {
    UUID household = UUID.randomUUID();
    UUID newBranch = UUID.randomUUID();
    wireDeterministicStages(household, List.of(directive()));
    when(adaptationService.runPlanTimeRefineJob(any()))
        .thenReturn(
            result(
                AdaptationClassification.BRANCH,
                Optional.empty(),
                Optional.of(newBranch),
                Optional.empty()));

    UUID planId = tx().execute(t -> composer.compose(request(household), UUID.randomUUID(), null));

    tx().executeWithoutResult(
            t -> {
              Plan reloaded = planRepository.findById(planId).orElseThrow();
              UUID persistedRecipeId =
                  reloaded.getDays().get(0).getSlots().get(0).getScheduledRecipe().getRecipeId();
              assertThat(persistedRecipeId).isEqualTo(newBranch);
            });
  }

  @Test
  void stageD_substitution_keepsOriginalRecipe() {
    UUID household = UUID.randomUUID();
    wireDeterministicStages(household, List.of(directive()));
    when(adaptationService.runPlanTimeRefineJob(any()))
        .thenReturn(
            result(
                AdaptationClassification.SUBSTITUTION,
                Optional.empty(),
                Optional.empty(),
                Optional.of(UUID.randomUUID())));

    UUID planId = tx().execute(t -> composer.compose(request(household), UUID.randomUUID(), null));

    tx().executeWithoutResult(
            t -> {
              Plan reloaded = planRepository.findById(planId).orElseThrow();
              assertThat(
                      reloaded
                          .getDays()
                          .get(0)
                          .getSlots()
                          .get(0)
                          .getScheduledRecipe()
                          .getRecipeId())
                  .isEqualTo(originalRecipeId);
            });
  }

  @Test
  void stageD_noChange_keepsOriginalRecipe() {
    UUID household = UUID.randomUUID();
    wireDeterministicStages(household, List.of(directive()));
    when(adaptationService.runPlanTimeRefineJob(any()))
        .thenReturn(
            result(
                AdaptationClassification.NO_CHANGE,
                Optional.empty(),
                Optional.empty(),
                Optional.empty()));

    UUID planId = tx().execute(t -> composer.compose(request(household), UUID.randomUUID(), null));

    tx().executeWithoutResult(
            t -> {
              Plan reloaded = planRepository.findById(planId).orElseThrow();
              assertThat(
                      reloaded
                          .getDays()
                          .get(0)
                          .getSlots()
                          .get(0)
                          .getScheduledRecipe()
                          .getRecipeId())
                  .isEqualTo(originalRecipeId);
              assertThat(reloaded.isQualityWarning()).isFalse();
            });
  }

  @Test
  void stageD_aiUnavailable_swallowed_planPersistsWithQualityWarning() {
    UUID household = UUID.randomUUID();
    wireDeterministicStages(household, List.of(directive()));
    when(adaptationService.runPlanTimeRefineJob(any()))
        .thenThrow(
            new AdaptationAiUnavailableException(
                "model down",
                new com.example.mealprep.ai.exception.AiUnavailableException("down")));

    UUID planId = tx().execute(t -> composer.compose(request(household), UUID.randomUUID(), null));

    tx().executeWithoutResult(
            t -> {
              Plan reloaded = planRepository.findById(planId).orElseThrow();
              assertThat(reloaded.getStatus()).isEqualTo(PlanStatus.GENERATED);
              assertThat(reloaded.isQualityWarning()).isTrue();
              assertThat(
                      reloaded
                          .getDays()
                          .get(0)
                          .getSlots()
                          .get(0)
                          .getScheduledRecipe()
                          .getRecipeId())
                  .isEqualTo(originalRecipeId);
            });
  }

  @Test
  void idempotencyKey_replay_returnsSamePlanId() {
    UUID household = UUID.randomUUID();
    UUID user = UUID.randomUUID();
    wireDeterministicStages(household, List.of());
    String key = "idem-" + UUID.randomUUID();

    UUID firstId = tx().execute(t -> composer.compose(request(household), user, key));

    Optional<UUID> cached = composer.cachedPlanIdFor(user, key);
    assertThat(cached).contains(firstId);
    // A different user with the same key does not collide.
    assertThat(composer.cachedPlanIdFor(UUID.randomUUID(), key)).isEmpty();
  }
}
