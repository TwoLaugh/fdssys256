package com.example.mealprep.planner.domain.service.internal.composer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.mealprep.adaptation.api.dto.AdaptationResultDto;
import com.example.mealprep.adaptation.api.dto.PlanTimeRefineDirectiveRequest;
import com.example.mealprep.adaptation.domain.enums.AdaptationClassification;
import com.example.mealprep.adaptation.domain.service.AdaptationService;
import com.example.mealprep.adaptation.exception.AdaptationAiUnavailableException;
import com.example.mealprep.core.lock.LeaseHandle;
import com.example.mealprep.core.lock.LockKey;
import com.example.mealprep.core.lock.LockService;
import com.example.mealprep.core.types.SlotKind;
import com.example.mealprep.nutrition.api.dto.CandidatePlanRollupDto;
import com.example.mealprep.planner.api.dto.AugmentationResult;
import com.example.mealprep.planner.api.dto.BeamSearchOutcome;
import com.example.mealprep.planner.api.dto.CandidatePlan;
import com.example.mealprep.planner.api.dto.DailyRollupDocument;
import com.example.mealprep.planner.api.dto.GeneratePlanRequest;
import com.example.mealprep.planner.api.dto.PlanCompositionContext;
import com.example.mealprep.planner.api.dto.RollupSummaryDocument;
import com.example.mealprep.planner.api.dto.ScoreResult;
import com.example.mealprep.planner.api.dto.SlotAssignment;
import com.example.mealprep.planner.api.dto.StageCResult;
import com.example.mealprep.planner.config.PlannerProperties;
import com.example.mealprep.planner.domain.entity.AugmentationSource;
import com.example.mealprep.planner.domain.entity.Plan;
import com.example.mealprep.planner.domain.entity.PlanStatus;
import com.example.mealprep.planner.domain.entity.TriggerKind;
import com.example.mealprep.planner.domain.service.internal.PortionOptimizer;
import com.example.mealprep.planner.domain.service.internal.additions.IngredientAdditionPlanner;
import com.example.mealprep.planner.domain.service.internal.beamsearch.BeamSearchEngine;
import com.example.mealprep.planner.domain.service.internal.decisionlog.DecisionLogEntry;
import com.example.mealprep.planner.domain.service.internal.decisionlog.DecisionLogWriter;
import com.example.mealprep.planner.domain.service.internal.decisionlog.PlannerDecisionKind;
import com.example.mealprep.planner.domain.service.internal.rollup.RollupBuilder;
import com.example.mealprep.planner.domain.service.internal.stagec.Phase2Augmenter;
import com.example.mealprep.planner.domain.service.internal.stagec.RepairAugmentation;
import com.example.mealprep.planner.domain.service.internal.stagec.StageCInvoker;
import com.example.mealprep.planner.event.PlanGeneratedEvent;
import com.example.mealprep.planner.exception.ConcurrentGenerationInProgressException;
import com.example.mealprep.planner.testdata.PlanTestData;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Unit coverage for {@link PlanComposer}: single-flight lease, cold-start gating, the Stage A to D
 * orchestration, the persist/publish boundary and the idempotency cache. Collaborators are mocked;
 * the {@code self} proxy delegates {@code persistAndPublish} back to the real instance so the
 * write-boundary seam runs in-test. Declared in the composer package for the package-private
 * constructor and collaborator types.
 */
class PlanComposerTest {

  private static final LocalDate WEEK = LocalDate.of(2026, 3, 2);
  private static final Instant NOW = Instant.parse("2026-03-02T08:00:00Z");

  private final UUID userId = UUID.randomUUID();
  private final UUID householdId = UUID.randomUUID();
  private final GeneratePlanRequest request = new GeneratePlanRequest(householdId, WEEK, false);
  private final PlanCompositionContext context =
      PlanTestData.minimalContext(
          List.of(PlanTestData.skeletonFor(WEEK, 0, SlotKind.DINNER, 30)), List.of());

  private final PlanCompositionContextBuilder contextBuilder =
      mock(PlanCompositionContextBuilder.class);
  private final ColdStartGate coldStartGate = mock(ColdStartGate.class);
  private final BeamSearchEngine beamSearchEngine = mock(BeamSearchEngine.class);
  private final RollupBuilder rollupBuilder = mock(RollupBuilder.class);
  private final StageCInvoker stageCInvoker = mock(StageCInvoker.class);
  private final Phase2Augmenter phase2Augmenter = mock(Phase2Augmenter.class);
  private final IngredientAdditionPlanner additionPlanner = mock(IngredientAdditionPlanner.class);
  private final PortionOptimizer portionOptimizer = mock(PortionOptimizer.class);
  private final PlanPersister planPersister = mock(PlanPersister.class);
  private final AdaptationService adaptationService = mock(AdaptationService.class);
  private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
  private final DecisionLogWriter decisionLogWriter = mock(DecisionLogWriter.class);
  private final LockService lockService = mock(LockService.class);
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

  private PlanComposer selfProxy;
  private PlanComposer composer;

  @BeforeEach
  void setUp() {
    newComposer(PlanTestData.scoringProperties());
    when(lockService.acquireLease(any(), any()))
        .thenReturn(
            Optional.of(
                new LeaseHandle(
                    LockKey.forPlanWeek(householdId, WEEK),
                    UUID.randomUUID(),
                    NOW,
                    NOW.plusSeconds(600))));
    when(lockService.releaseLease(any())).thenReturn(true);
    when(contextBuilder.build(eq(request), eq(userId), any(), any())).thenReturn(context);
    when(decisionLogWriter.write(any())).thenAnswer(inv -> UUID.randomUUID());
    when(rollupBuilder.build(any(), any())).thenReturn(twoDayRollup());
    when(phase2Augmenter.augment(any(), any(), any(), any()))
        .thenReturn(new AugmentationResult(List.of(), List.of(), List.of()));
    when(portionOptimizer.optimise(any(), any())).thenAnswer(inv -> inv.getArgument(0));
    when(additionPlanner.attach(any(), any(), any())).thenAnswer(inv -> inv.getArgument(0));
    when(planPersister.persist(
            any(), any(), any(), any(), any(), anyBoolean(), anyBoolean(), anyBoolean()))
        .thenAnswer(
            inv ->
                plan(
                    inv.getArgument(3),
                    inv.getArgument(5),
                    inv.getArgument(6),
                    inv.getArgument(7)));
  }

  // ---- compose entry: lease + cold start -----------------------------------------------------

  @Test
  void contendedLeaseRejectsBeforeAnyAiWork() {
    when(lockService.acquireLease(any(), any())).thenReturn(Optional.empty());

    assertThatThrownBy(() -> composer.compose(request, userId, null))
        .isInstanceOf(ConcurrentGenerationInProgressException.class);

    verify(lockService)
        .acquireLease(eq(LockKey.forPlanWeek(householdId, WEEK)), eq(Duration.ofMinutes(10)));
    verifyNoInteractions(beamSearchEngine, contextBuilder, planPersister);
  }

  @Test
  void coldStartGateResultLandsOnThePersistedPlan() {
    stubOneCandidate();
    when(coldStartGate.fillIfCold(eq(userId), eq(context.slotSkeletons()), eq(0), any()))
        .thenReturn(true);

    composer.compose(request, userId, null);

    verify(coldStartGate).fillIfCold(eq(userId), eq(context.slotSkeletons()), eq(0), any());
    // Pre-context for the gate plus the fresh post-gate context.
    verify(contextBuilder, times(2)).build(eq(request), eq(userId), any(), any());
    assertThat(capturedPersistInputs().coldStart()).isTrue();
  }

  @Test
  void coldStartGateSkippedWhenDisabled() {
    newComposer(
        withColdStart(
            PlanTestData.scoringProperties(),
            new PlannerProperties.ColdStart(false, 3, 50, Duration.ofSeconds(20), List.of())));
    stubOneCandidate();

    composer.compose(request, userId, null);

    verifyNoInteractions(coldStartGate);
    verify(contextBuilder, times(1)).build(eq(request), eq(userId), any(), any());
    assertThat(capturedPersistInputs().coldStart()).isFalse();
  }

  @Test
  void composeReturnsThePersistedPlanIdAndReleasesTheLease() {
    stubOneCandidate();

    UUID result = composer.compose(request, userId, null);

    PlanComposer.PersistInputs in = capturedPersistInputs();
    assertThat(result).isNotNull().isEqualTo(in.planId());
    assertThat(in.outcome()).isEqualTo("ok");
    verify(lockService).releaseLease(any());
  }

  @Test
  void cleanRunCarriesNoQualityWarning() {
    stubOneCandidate();

    composer.compose(request, userId, null);

    PlanComposer.PersistInputs in = capturedPersistInputs();
    assertThat(in.qualityWarning()).isFalse();
    assertThat(in.aiAugmented()).isFalse();
    JsonNode out = entryOfKind(PlannerDecisionKind.STAGE_C_DONE).outputs();
    assertThat(out).isNotNull();
    assertThat(out.get("chosenIndex").intValue()).isZero();
    assertThat(out.get("reasoning").asText()).isEqualTo("picked");
    assertThat(out.get("qualityWarnings")).isEmpty();
  }

  // ---- empty pool ----------------------------------------------------------------------------

  @Test
  void emptyCandidatePoolPersistsMinimalQualityWarningPlan() {
    stubStageA(List.of(), false);

    UUID result = composer.compose(request, userId, null);

    PlanComposer.PersistInputs in = capturedPersistInputs();
    assertThat(result).isEqualTo(in.planId());
    assertThat(in.outcome()).isEqualTo("no-candidates");
    assertThat(in.qualityWarning()).isTrue();
    assertThat(in.aiAugmented()).isFalse();
    assertThat(in.chosen().assignments()).isEmpty();
    assertThat(in.chosen().weekStartDate()).isEqualTo(WEEK);
    assertThat(in.rollupSummary()).isNotNull();
    assertThat(in.rollupSummary().daily()).isEmpty();
    assertThat(in.rollupSummary().weekly().kcalTotal()).isZero();
    verifyNoInteractions(stageCInvoker, phase2Augmenter, adaptationService);
  }

  // ---- decision-log payloads -----------------------------------------------------------------

  @Test
  void startRowRecordsRequestInputsAsTraceRoot() {
    stubOneCandidate();

    composer.compose(request, userId, null);

    DecisionLogEntry start = entryOfKind(PlannerDecisionKind.PLAN_GENERATION_START);
    assertThat(start.parentDecisionId()).isNull();
    JsonNode in = start.inputs();
    assertThat(in).isNotNull();
    assertThat(in.get("householdId").asText()).isEqualTo(householdId.toString());
    assertThat(in.get("weekStartDate").asText()).isEqualTo(WEEK.toString());
    assertThat(in.get("traceId").asText()).isEqualTo(start.traceId().toString());
  }

  @Test
  void stageARowRecordsScoresAndPoolSizes() {
    SlotAssignment withSlot =
        PlanTestData.assignment(UUID.randomUUID(), UUID.randomUUID(), WEEK, 0, 2);
    CandidatePlan scored =
        new CandidatePlan(
            UUID.randomUUID(),
            WEEK,
            List.of(withSlot, assignmentWithoutSlotId()),
            new ScoreResult(new BigDecimal("1.25"), PlanTestData.zeroScoreBreakdown()));
    SlotAssignment second =
        PlanTestData.assignment(UUID.randomUUID(), UUID.randomUUID(), WEEK, 0, 2);
    CandidatePlan unscored = new CandidatePlan(UUID.randomUUID(), WEEK, List.of(second), null);
    stubStageA(List.of(scored, unscored), false);
    when(stageCInvoker.pickOne(any(), any(), any(), any()))
        .thenReturn(PlanTestData.stageCResultLlm(0, "picked"));

    composer.compose(request, userId, null);

    JsonNode out = entryOfKind(PlannerDecisionKind.STAGE_A_DONE).outputs();
    assertThat(out).isNotNull();
    assertThat(out.get("topNRecipeIds").get(0).asText()).isEqualTo(scored.candidateId().toString());
    assertThat(out.get("topNRecipeIds").get(1).asText())
        .isEqualTo(unscored.candidateId().toString());
    assertThat(out.get("topNScores").get(0).decimalValue()).isEqualByComparingTo("1.25");
    assertThat(out.get("topNScores").get(1).isNull()).isTrue();
    JsonNode poolSizes = out.get("poolSizes");
    assertThat(poolSizes.size()).isEqualTo(2);
    assertThat(poolSizes.get(withSlot.slotId().toString()).intValue()).isEqualTo(1);
    assertThat(poolSizes.get(second.slotId().toString()).intValue()).isEqualTo(1);
  }

  // ---- Stage B -> C rollup mapping -----------------------------------------------------------

  @Test
  void stageCReceivesPerDayRollupsMappedFromStageB() {
    stubOneCandidate();

    composer.compose(request, userId, null);

    CandidatePlanRollupDto dto = capturedStageCRollups().get(0);
    assertThat(dto).isNotNull();
    assertThat(dto.startDate()).isEqualTo(WEEK);
    assertThat(dto.endDate()).isEqualTo(WEEK.plusDays(1));
    assertThat(dto.perDay()).hasSize(2);
    assertThat(dto.perDay().get(0).calories()).isEqualTo(1800);
    assertThat(dto.perDay().get(0).proteinG()).isEqualByComparingTo("90");
    assertThat(dto.perDay().get(0).carbsG()).isEqualByComparingTo("200");
    assertThat(dto.perDay().get(0).fatG()).isEqualByComparingTo("60");
    assertThat(dto.perDay().get(0).fibreG()).isEqualByComparingTo("25");
    assertThat(dto.perDay().get(1).calories()).isEqualTo(2000);
  }

  @Test
  void stageCRollupFallsBackToOneZeroDayWhenRollupHasNoDailyEntries() {
    stubOneCandidate();
    when(rollupBuilder.build(any(), any())).thenReturn(PlanTestData.emptyRollup());

    composer.compose(request, userId, null);

    CandidatePlanRollupDto dto = capturedStageCRollups().get(0);
    assertThat(dto.perDay()).hasSize(1);
    assertThat(dto.perDay().get(0).calories()).isZero();
    assertThat(dto.startDate()).isEqualTo(dto.endDate());
  }

  // ---- Stage C choice ------------------------------------------------------------------------

  @Test
  void stageCChoiceSelectsThatCandidate() {
    List<CandidatePlan> candidates = stubTwoCandidates();
    when(stageCInvoker.pickOne(any(), any(), any(), any()))
        .thenReturn(PlanTestData.stageCResultLlm(1, "prefers variety"));

    composer.compose(request, userId, null);

    assertThat(capturedPersistInputs().chosen().candidateId())
        .isEqualTo(candidates.get(1).candidateId());
    DecisionLogEntry stageC = entryOfKind(PlannerDecisionKind.STAGE_C_DONE);
    assertThat(stageC.inputs().get("rollupCount").intValue()).isEqualTo(2);
    assertThat(stageC.inputs().get("promptVersion").asText()).isEqualTo("planner-stage-c-v1");
    assertThat(stageC.outputs().get("chosenIndex").intValue()).isEqualTo(1);
    assertThat(stageC.outputs().get("reasoning").asText()).isEqualTo("prefers variety");
  }

  @Test
  void outOfRangeStageCIndexFallsBackToTopCandidate() {
    List<CandidatePlan> candidates = stubTwoCandidates();
    when(stageCInvoker.pickOne(any(), any(), any(), any()))
        .thenReturn(new StageCResult(2, "off the end", AugmentationSource.LLM, false));

    composer.compose(request, userId, null);

    assertThat(capturedPersistInputs().chosen().candidateId())
        .isEqualTo(candidates.get(0).candidateId());
  }

  @Test
  void negativeStageCIndexFallsBackToTopCandidate() {
    List<CandidatePlan> candidates = stubTwoCandidates();
    when(stageCInvoker.pickOne(any(), any(), any(), any()))
        .thenReturn(new StageCResult(-1, "bad index", AugmentationSource.LLM, false));

    composer.compose(request, userId, null);

    assertThat(capturedPersistInputs().chosen().candidateId())
        .isEqualTo(candidates.get(0).candidateId());
  }

  // ---- aiAugmented flag ----------------------------------------------------------------------

  @Test
  void aiAugmentedWhenLlmPickedAndAugmentationsApplied() {
    stubOneCandidate();
    when(phase2Augmenter.augment(any(), any(), any(), any()))
        .thenReturn(
            new AugmentationResult(
                List.of(new RepairAugmentation(null, "gap", "added")), List.of(), List.of()));

    composer.compose(request, userId, null);

    assertThat(capturedPersistInputs().aiAugmented()).isTrue();
    JsonNode out = entryOfKind(PlannerDecisionKind.PHASE_2_DONE).outputs();
    assertThat(out).isNotNull();
    assertThat(out.get("augmentationCount").intValue()).isEqualTo(1);
    assertThat(out.get("refineDirectiveCount").intValue()).isZero();
  }

  @Test
  void notAiAugmentedOnStageCFallback() {
    stubOneCandidate();
    when(stageCInvoker.pickOne(any(), any(), any(), any()))
        .thenReturn(PlanTestData.stageCResultFallback());
    when(phase2Augmenter.augment(any(), any(), any(), any()))
        .thenReturn(
            new AugmentationResult(
                List.of(new RepairAugmentation(null, "gap", "added")), List.of(), List.of()));

    composer.compose(request, userId, null);

    assertThat(capturedPersistInputs().aiAugmented()).isFalse();
  }

  @Test
  void notAiAugmentedWithoutAppliedAugmentations() {
    stubOneCandidate();

    composer.compose(request, userId, null);

    assertThat(capturedPersistInputs().aiAugmented()).isFalse();
  }

  // ---- quality warning from Stage A degradation ----------------------------------------------

  @Test
  void beamDegradationSetsQualityWarning() {
    SlotAssignment a = PlanTestData.assignment(UUID.randomUUID(), UUID.randomUUID(), WEEK, 0, 2);
    CandidatePlan cand =
        new CandidatePlan(
            UUID.randomUUID(),
            WEEK,
            List.of(a),
            new ScoreResult(new BigDecimal("1.25"), PlanTestData.zeroScoreBreakdown()));
    stubStageA(List.of(cand), true);
    when(stageCInvoker.pickOne(any(), any(), any(), any()))
        .thenReturn(PlanTestData.stageCResultLlm(0, "picked"));

    composer.compose(request, userId, null);

    assertThat(capturedPersistInputs().qualityWarning()).isTrue();
    JsonNode warnings =
        entryOfKind(PlannerDecisionKind.STAGE_C_DONE).outputs().get("qualityWarnings");
    assertThat(warnings.get(0).asText()).isEqualTo("stage-a-degraded-to-greedy");
  }

  // ---- Stage D routing -----------------------------------------------------------------------

  @Test
  void versionAdaptationSwapsRecipeOnTheTargetSlot() {
    SlotAssignment noSlot = assignmentWithoutSlotId();
    SlotAssignment other =
        PlanTestData.assignment(UUID.randomUUID(), UUID.randomUUID(), WEEK, 1, 2);
    UUID slotB = UUID.randomUUID();
    SlotAssignment target = PlanTestData.assignment(slotB, UUID.randomUUID(), WEEK, 2, 2);
    stubCandidateWithAssignments(List.of(noSlot, other, target));
    when(phase2Augmenter.augment(any(), any(), any(), any()))
        .thenReturn(
            new AugmentationResult(
                List.of(),
                List.of(),
                List.of(PlanTestData.refineDirectiveProposal(slotB, "beef", "lentils"))));
    UUID jobId = UUID.randomUUID();
    UUID newVersion = UUID.randomUUID();
    when(adaptationService.runPlanTimeRefineJob(any()))
        .thenReturn(
            adaptationResult(
                jobId,
                AdaptationClassification.VERSION,
                Optional.of(newVersion),
                Optional.empty()));

    composer.compose(request, userId, null);

    ArgumentCaptor<PlanTimeRefineDirectiveRequest> req =
        ArgumentCaptor.forClass(PlanTimeRefineDirectiveRequest.class);
    verify(adaptationService).runPlanTimeRefineJob(req.capture());
    assertThat(req.getValue().recipeId()).isEqualTo(target.recipeId());
    assertThat(req.getValue().slotId()).isEqualTo(slotB);

    List<SlotAssignment> persisted = capturedPersistInputs().chosen().assignments();
    assertThat(persisted.get(2).recipeId()).isEqualTo(newVersion);
    assertThat(persisted.get(2).recipeVersionId()).isEqualTo(newVersion);
    assertThat(persisted.get(2).recipeBranchId()).isEqualTo(target.recipeBranchId());
    assertThat(persisted.get(1)).isEqualTo(other);

    JsonNode out = entryOfKind(PlannerDecisionKind.STAGE_D_OUTCOME).outputs();
    assertThat(out).isNotNull();
    assertThat(out.get("adaptationJobId").asText()).isEqualTo(jobId.toString());
    assertThat(out.get("classification").asText()).isEqualTo("VERSION");
    assertThat(out.get("versionIdCreated").asText()).isEqualTo(newVersion.toString());
  }

  @Test
  void branchAdaptationAppliesToTheFirstSlot() {
    UUID slotA = UUID.randomUUID();
    SlotAssignment target = PlanTestData.assignment(slotA, UUID.randomUUID(), WEEK, 0, 2);
    SlotAssignment other =
        PlanTestData.assignment(UUID.randomUUID(), UUID.randomUUID(), WEEK, 1, 2);
    stubCandidateWithAssignments(List.of(target, other));
    when(phase2Augmenter.augment(any(), any(), any(), any()))
        .thenReturn(
            new AugmentationResult(
                List.of(),
                List.of(),
                List.of(PlanTestData.refineDirectiveProposal(slotA, "beef", "lentils"))));
    UUID newBranch = UUID.randomUUID();
    when(adaptationService.runPlanTimeRefineJob(any()))
        .thenReturn(
            adaptationResult(
                UUID.randomUUID(),
                AdaptationClassification.BRANCH,
                Optional.empty(),
                Optional.of(newBranch)));

    composer.compose(request, userId, null);

    verify(adaptationService).runPlanTimeRefineJob(any());
    List<SlotAssignment> persisted = capturedPersistInputs().chosen().assignments();
    assertThat(persisted.get(0).recipeId()).isEqualTo(newBranch);
    assertThat(persisted.get(0).recipeBranchId()).isEqualTo(newBranch);
    assertThat(persisted.get(0).recipeVersionId()).isEqualTo(target.recipeVersionId());
    assertThat(persisted.get(1)).isEqualTo(other);
  }

  @Test
  void directiveTargetingUnknownSlotIsSkipped() {
    SlotAssignment only = PlanTestData.assignment(UUID.randomUUID(), UUID.randomUUID(), WEEK, 0, 2);
    stubCandidateWithAssignments(List.of(only));
    when(phase2Augmenter.augment(any(), any(), any(), any()))
        .thenReturn(
            new AugmentationResult(
                List.of(),
                List.of(),
                List.of(
                    PlanTestData.refineDirectiveProposal(UUID.randomUUID(), "beef", "lentils"))));

    composer.compose(request, userId, null);

    verifyNoInteractions(adaptationService);
    assertThat(capturedPersistInputs().chosen().assignments()).containsExactly(only);
  }

  @Test
  void noChangeAdaptationLeavesTheRecipeAlone() {
    UUID slotA = UUID.randomUUID();
    SlotAssignment target = PlanTestData.assignment(slotA, UUID.randomUUID(), WEEK, 0, 2);
    stubCandidateWithAssignments(List.of(target));
    when(phase2Augmenter.augment(any(), any(), any(), any()))
        .thenReturn(
            new AugmentationResult(
                List.of(),
                List.of(),
                List.of(PlanTestData.refineDirectiveProposal(slotA, "beef", "lentils"))));
    UUID jobId = UUID.randomUUID();
    when(adaptationService.runPlanTimeRefineJob(any()))
        .thenReturn(
            adaptationResult(
                jobId, AdaptationClassification.NO_CHANGE, Optional.empty(), Optional.empty()));

    composer.compose(request, userId, null);

    assertThat(capturedPersistInputs().chosen().assignments()).containsExactly(target);
    JsonNode out = entryOfKind(PlannerDecisionKind.STAGE_D_OUTCOME).outputs();
    assertThat(out.get("adaptationJobId").asText()).isEqualTo(jobId.toString());
    assertThat(out.get("classification").asText()).isEqualTo("NO_CHANGE");
    assertThat(out.get("versionIdCreated").isNull()).isTrue();
  }

  @Test
  void versionAdaptationWithoutCreatedVersionIsANoOp() {
    UUID slotA = UUID.randomUUID();
    SlotAssignment target = PlanTestData.assignment(slotA, UUID.randomUUID(), WEEK, 0, 2);
    stubCandidateWithAssignments(List.of(target));
    when(phase2Augmenter.augment(any(), any(), any(), any()))
        .thenReturn(
            new AugmentationResult(
                List.of(),
                List.of(),
                List.of(PlanTestData.refineDirectiveProposal(slotA, "beef", "lentils"))));
    when(adaptationService.runPlanTimeRefineJob(any()))
        .thenReturn(
            adaptationResult(
                UUID.randomUUID(),
                AdaptationClassification.VERSION,
                Optional.empty(),
                Optional.empty()));

    composer.compose(request, userId, null);

    assertThat(capturedPersistInputs().chosen().assignments()).containsExactly(target);
  }

  @Test
  void directiveRoutingStopsAtTheConfiguredBudget() {
    UUID slotA = UUID.randomUUID();
    UUID slotB = UUID.randomUUID();
    UUID slotC = UUID.randomUUID();
    stubCandidateWithAssignments(
        List.of(
            PlanTestData.assignment(slotA, UUID.randomUUID(), WEEK, 0, 2),
            PlanTestData.assignment(slotB, UUID.randomUUID(), WEEK, 1, 2),
            PlanTestData.assignment(slotC, UUID.randomUUID(), WEEK, 2, 2)));
    when(phase2Augmenter.augment(any(), any(), any(), any()))
        .thenReturn(
            new AugmentationResult(
                List.of(),
                List.of(),
                List.of(
                    PlanTestData.refineDirectiveProposal(slotA, "beef", "lentils"),
                    PlanTestData.refineDirectiveProposal(slotB, "cream", "yoghurt"),
                    PlanTestData.refineDirectiveProposal(slotC, "butter", "oil"))));
    when(adaptationService.runPlanTimeRefineJob(any()))
        .thenReturn(
            adaptationResult(
                UUID.randomUUID(),
                AdaptationClassification.NO_CHANGE,
                Optional.empty(),
                Optional.empty()));

    composer.compose(request, userId, null);

    // maxRefineDirectives is 2 in the fixture properties; the third proposal is dropped.
    ArgumentCaptor<PlanTimeRefineDirectiveRequest> req =
        ArgumentCaptor.forClass(PlanTimeRefineDirectiveRequest.class);
    verify(adaptationService, times(2)).runPlanTimeRefineJob(req.capture());
    assertThat(req.getAllValues())
        .extracting(PlanTimeRefineDirectiveRequest::slotId)
        .containsExactly(slotA, slotB);
  }

  @Test
  void stageDUnavailabilityKeepsOriginalRecipeAndFlagsQuality() {
    UUID slotA = UUID.randomUUID();
    SlotAssignment target = PlanTestData.assignment(slotA, UUID.randomUUID(), WEEK, 0, 2);
    stubCandidateWithAssignments(List.of(target));
    when(phase2Augmenter.augment(any(), any(), any(), any()))
        .thenReturn(
            new AugmentationResult(
                List.of(),
                List.of(),
                List.of(PlanTestData.refineDirectiveProposal(slotA, "beef", "lentils"))));
    when(adaptationService.runPlanTimeRefineJob(any()))
        .thenThrow(new AdaptationAiUnavailableException("model down", null));

    composer.compose(request, userId, null);

    PlanComposer.PersistInputs in = capturedPersistInputs();
    assertThat(in.qualityWarning()).isTrue();
    assertThat(in.chosen().assignments()).containsExactly(target);
    JsonNode out = entryOfKind(PlannerDecisionKind.STAGE_D_OUTCOME).outputs();
    assertThat(out.get("classification").asText()).isEqualTo("AI_UNAVAILABLE");
    assertThat(out.get("adaptationJobId").isNull()).isTrue();
  }

  // ---- persist boundary ----------------------------------------------------------------------

  @Test
  void persistAndPublishWritesFinishRowPublishesEventAndCachesKey() {
    UUID planId = UUID.randomUUID();
    UUID parent = UUID.randomUUID();
    UUID traceId = UUID.randomUUID();
    CandidatePlan chosen =
        PlanTestData.candidatePlan(
            WEEK,
            List.of(PlanTestData.assignment(UUID.randomUUID(), UUID.randomUUID(), WEEK, 0, 2)));
    long startNanos = System.nanoTime() - Duration.ofSeconds(5).toNanos();

    UUID result =
        composer.persistAndPublish(
            new PlanComposer.PersistInputs(
                chosen,
                request,
                context,
                planId,
                PlanTestData.emptyRollup(),
                true,
                false,
                true,
                traceId,
                parent,
                startNanos,
                "ok",
                userId,
                "idem-42"));

    assertThat(result).isEqualTo(planId);

    ArgumentCaptor<PlanGeneratedEvent> event = ArgumentCaptor.forClass(PlanGeneratedEvent.class);
    verify(eventPublisher).publishEvent(event.capture());
    PlanGeneratedEvent e = event.getValue();
    assertThat(e.planId()).isEqualTo(planId);
    assertThat(e.householdId()).isEqualTo(householdId);
    assertThat(e.weekStartDate()).isEqualTo(WEEK);
    assertThat(e.generation()).isEqualTo(1);
    assertThat(e.trigger()).isEqualTo(TriggerKind.USER_INITIATED);
    assertThat(e.decisionId()).isEqualTo(context.decisionId());
    assertThat(e.coldStart()).isTrue();
    assertThat(e.aiAugmented()).isTrue();
    assertThat(e.qualityWarning()).isFalse();
    assertThat(e.traceId()).isEqualTo(traceId);
    assertThat(e.occurredAt()).isEqualTo(NOW);

    DecisionLogEntry finish = entryOfKind(PlannerDecisionKind.PLAN_GENERATION_COMPLETE);
    assertThat(finish.parentDecisionId()).isEqualTo(parent);
    assertThat(finish.actorUserId()).isEqualTo(userId);
    JsonNode out = finish.outputs();
    assertThat(out.get("planId").asText()).isEqualTo(planId.toString());
    assertThat(out.get("status").asText()).isEqualTo("GENERATED");
    assertThat(out.get("qualityWarning").booleanValue()).isFalse();
    assertThat(out.get("outcome").asText()).isEqualTo("ok");
    assertThat(out.get("durationMs").intValue()).isBetween(5000, 30000);

    assertThat(composer.cachedPlanIdFor(userId, "idem-42")).contains(planId);
  }

  // ---- idempotency cache ---------------------------------------------------------------------

  @Test
  void idempotencyCacheHitsOnlyForTheSameUserAndKey() {
    stubOneCandidate();

    UUID id = composer.compose(request, userId, "idem-1");

    assertThat(composer.cachedPlanIdFor(userId, "idem-1")).contains(id);
    assertThat(composer.cachedPlanIdFor(userId, "idem-2")).isEmpty();
    assertThat(composer.cachedPlanIdFor(UUID.randomUUID(), "idem-1")).isEmpty();
  }

  @Test
  void missingOrBlankIdempotencyKeyIsNeverCached() {
    stubOneCandidate();

    UUID id = composer.compose(request, userId, null);

    assertThat(id).isNotNull();
    assertThat(composer.cachedPlanIdFor(userId, null)).isEmpty();
    assertThat(composer.cachedPlanIdFor(userId, "  ")).isEmpty();
  }

  // ---- fixtures ------------------------------------------------------------------------------

  private void newComposer(PlannerProperties properties) {
    selfProxy = mock(PlanComposer.class);
    composer =
        new PlanComposer(
            contextBuilder,
            coldStartGate,
            selfProxy,
            beamSearchEngine,
            rollupBuilder,
            stageCInvoker,
            phase2Augmenter,
            additionPlanner,
            portionOptimizer,
            planPersister,
            new RefineDirectiveMapper(objectMapper, clock),
            adaptationService,
            eventPublisher,
            decisionLogWriter,
            lockService,
            properties,
            objectMapper,
            clock);
    // The production self-reference is the Spring proxy; here it hands the call back to the real
    // instance so the persist seam executes.
    when(selfProxy.persistAndPublish(any()))
        .thenAnswer(inv -> composer.persistAndPublish(inv.getArgument(0)));
  }

  private void stubStageA(List<CandidatePlan> candidates, boolean degraded) {
    when(beamSearchEngine.search(any(), any()))
        .thenReturn(new BeamSearchOutcome(candidates, degraded));
  }

  private CandidatePlan stubOneCandidate() {
    SlotAssignment a = PlanTestData.assignment(UUID.randomUUID(), UUID.randomUUID(), WEEK, 0, 2);
    return stubCandidateWithAssignments(List.of(a));
  }

  private CandidatePlan stubCandidateWithAssignments(List<SlotAssignment> assignments) {
    CandidatePlan cand =
        new CandidatePlan(
            UUID.randomUUID(),
            WEEK,
            assignments,
            new ScoreResult(new BigDecimal("1.25"), PlanTestData.zeroScoreBreakdown()));
    stubStageA(List.of(cand), false);
    when(stageCInvoker.pickOne(any(), any(), any(), any()))
        .thenReturn(PlanTestData.stageCResultLlm(0, "picked"));
    return cand;
  }

  private List<CandidatePlan> stubTwoCandidates() {
    CandidatePlan c0 =
        PlanTestData.candidatePlan(
            WEEK,
            List.of(PlanTestData.assignment(UUID.randomUUID(), UUID.randomUUID(), WEEK, 0, 2)));
    CandidatePlan c1 =
        PlanTestData.candidatePlan(
            WEEK,
            List.of(PlanTestData.assignment(UUID.randomUUID(), UUID.randomUUID(), WEEK, 0, 2)));
    stubStageA(List.of(c0, c1), false);
    return List.of(c0, c1);
  }

  private Plan plan(UUID planId, boolean aiAugmented, boolean qualityWarning, boolean coldStart) {
    return Plan.builder()
        .id(planId)
        .householdId(householdId)
        .weekStartDate(WEEK)
        .generation(1)
        .status(PlanStatus.GENERATED)
        .triggerKind(TriggerKind.USER_INITIATED)
        .qualityWarning(qualityWarning)
        .coldStart(coldStart)
        .aiAugmented(aiAugmented)
        .traceId(context.traceId())
        .decisionId(context.decisionId())
        .build();
  }

  private static RollupSummaryDocument twoDayRollup() {
    return new RollupSummaryDocument(
        List.of(dailyDoc(WEEK, 1800, "90"), dailyDoc(WEEK.plusDays(1), 2000, "110")),
        PlanTestData.emptyRollup().weekly());
  }

  private static DailyRollupDocument dailyDoc(LocalDate date, int kcal, String proteinG) {
    return new DailyRollupDocument(
        date,
        kcal,
        new BigDecimal(proteinG),
        new BigDecimal("60"),
        new BigDecimal("200"),
        new BigDecimal("25"),
        new BigDecimal("12.50"),
        45,
        List.of());
  }

  private static SlotAssignment assignmentWithoutSlotId() {
    return new SlotAssignment(
        UUID.randomUUID(),
        null,
        0,
        WEEK,
        SlotKind.DINNER,
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        2,
        false);
  }

  private static AdaptationResultDto adaptationResult(
      UUID jobId,
      AdaptationClassification classification,
      Optional<UUID> versionIdCreated,
      Optional<UUID> branchIdCreated) {
    return new AdaptationResultDto(
        jobId,
        UUID.randomUUID(),
        classification,
        versionIdCreated,
        branchIdCreated,
        Optional.empty(),
        Optional.empty(),
        null,
        "adapted",
        null,
        false,
        List.of(),
        UUID.randomUUID(),
        null);
  }

  private static PlannerProperties withColdStart(
      PlannerProperties p, PlannerProperties.ColdStart coldStart) {
    return new PlannerProperties(
        p.weekStartDayOfWeek(),
        p.beamWidth(),
        p.topN(),
        p.minPoolPerSlot(),
        p.maxPoolPerSlot(),
        p.maxTimeOvershootRatio(),
        p.stageATimeout(),
        p.weights(),
        p.scoring(),
        p.stageCTimeout(),
        p.iterationBudget(),
        p.maxAugmentations(),
        p.maxRefineDirectives(),
        p.leaseTtl(),
        p.midWeek(),
        p.materiality(),
        coldStart);
  }

  private PlanComposer.PersistInputs capturedPersistInputs() {
    ArgumentCaptor<PlanComposer.PersistInputs> captor =
        ArgumentCaptor.forClass(PlanComposer.PersistInputs.class);
    verify(selfProxy).persistAndPublish(captor.capture());
    return captor.getValue();
  }

  @SuppressWarnings("unchecked")
  private List<CandidatePlanRollupDto> capturedStageCRollups() {
    ArgumentCaptor<List<CandidatePlanRollupDto>> captor = ArgumentCaptor.forClass(List.class);
    verify(stageCInvoker).pickOne(any(), captor.capture(), any(), any());
    return captor.getValue();
  }

  private DecisionLogEntry entryOfKind(PlannerDecisionKind kind) {
    ArgumentCaptor<DecisionLogEntry> captor = ArgumentCaptor.forClass(DecisionLogEntry.class);
    verify(decisionLogWriter, atLeastOnce()).write(captor.capture());
    return captor.getAllValues().stream().filter(e -> e.kind() == kind).findFirst().orElseThrow();
  }
}
