package com.example.mealprep.adaptation.domain.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.mealprep.adaptation.ai.AdaptationContext;
import com.example.mealprep.adaptation.ai.RecipeAdaptationResponse;
import com.example.mealprep.adaptation.config.AdaptationConfig;
import com.example.mealprep.adaptation.domain.entity.AdaptationJob;
import com.example.mealprep.adaptation.domain.enums.AdaptationClassification;
import com.example.mealprep.adaptation.domain.enums.ApprovalPolicy;
import com.example.mealprep.adaptation.domain.enums.JobPriority;
import com.example.mealprep.adaptation.domain.enums.JobSource;
import com.example.mealprep.adaptation.domain.enums.JobStatus;
import com.example.mealprep.adaptation.domain.repository.AdaptationFingerprintRepository;
import com.example.mealprep.adaptation.domain.repository.AdaptationJobRepository;
import com.example.mealprep.adaptation.domain.repository.AdaptationTraceRepository;
import com.example.mealprep.adaptation.domain.repository.NutritionalKnowledgeRepository;
import com.example.mealprep.adaptation.domain.repository.PendingChangeRepository;
import com.example.mealprep.adaptation.domain.repository.PlannerHintRecordRepository;
import com.example.mealprep.adaptation.domain.service.internal.AdaptationLlmInvoker;
import com.example.mealprep.adaptation.domain.service.internal.AdaptationTraceWriter;
import com.example.mealprep.adaptation.domain.service.internal.CandidateGenerator;
import com.example.mealprep.adaptation.domain.service.internal.ChangeDimensionResolver;
import com.example.mealprep.adaptation.domain.service.internal.CharacterPreservationGate;
import com.example.mealprep.adaptation.domain.service.internal.ConfidenceFloorGate;
import com.example.mealprep.adaptation.domain.service.internal.IngredientRemoveStrategy;
import com.example.mealprep.adaptation.domain.service.internal.IngredientSwapStrategy;
import com.example.mealprep.adaptation.domain.service.internal.MethodSimplificationStrategy;
import com.example.mealprep.adaptation.domain.service.internal.PendingChangeStore;
import com.example.mealprep.adaptation.domain.service.internal.PortionAdjustStrategy;
import com.example.mealprep.adaptation.domain.service.internal.ScoringEngine;
import com.example.mealprep.adaptation.event.AdaptationCandidateProducedEvent;
import com.example.mealprep.adaptation.event.AdaptationJobCompletedEvent;
import com.example.mealprep.adaptation.event.AdaptationJobFailedEvent;
import com.example.mealprep.adaptation.exception.AdaptationAiUnavailableException;
import com.example.mealprep.adaptation.exception.LockTimeoutException;
import com.example.mealprep.core.audit.domain.service.DecisionLogService;
import com.example.mealprep.core.lock.LockKey;
import com.example.mealprep.core.lock.LockService;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Smoke-tests the orchestration on {@link AdaptationServiceImpl#processJob} via a wiring with mocks
 * for the LLM invoker, lock service, audit, etc.
 */
class AdaptationServiceImplProcessJobSmokeTest {

  @Test
  void plan_time_lock_failure_throws_lock_timeout() {
    Wiring w = new Wiring();
    AdaptationJob job = job(JobSource.PLAN_TIME);
    when(w.jobRepository.findById(job.getId())).thenReturn(Optional.of(job));
    when(w.lockService.tryAcquire(any(LockKey.class))).thenReturn(false);
    assertThatThrownBy(() -> w.service.processJob(job)).isInstanceOf(LockTimeoutException.class);
  }

  @Test
  void ai_unavailable_failure_publishes_failed_event_and_rethrows() {
    Wiring w = new Wiring();
    AdaptationJob job = job(JobSource.FEEDBACK);
    when(w.jobRepository.findById(job.getId())).thenReturn(Optional.of(job));
    when(w.lockService.tryAcquire(any(LockKey.class))).thenReturn(true);
    when(w.llmInvoker.invoke(any(), any()))
        .thenThrow(
            new AdaptationAiUnavailableException(
                "ai-unavailable: 503",
                new com.example.mealprep.ai.exception.AiUnavailableException("503")));
    assertThatThrownBy(() -> w.service.processJob(job))
        .isInstanceOf(AdaptationAiUnavailableException.class);
    verify(w.events).publishEvent(any(AdaptationCandidateProducedEvent.class));
    verify(w.events).publishEvent(any(AdaptationJobFailedEvent.class));
  }

  @Test
  void happy_path_auto_skip_writes_trace_and_publishes_completed() {
    Wiring w = new Wiring();
    AdaptationJob job = job(JobSource.IMPORT);
    when(w.jobRepository.findById(job.getId())).thenReturn(Optional.of(job));
    when(w.lockService.tryAcquire(any(LockKey.class))).thenReturn(true);
    when(w.scoringEngine.shouldAutoSkipStageC(any())).thenReturn(true);
    when(w.pendingChangeStore.create(any(), any(), any(), any(), any(), any()))
        .thenReturn(UUID.randomUUID());

    w.service.processJob(job);

    verify(w.events, times(1)).publishEvent(any(AdaptationCandidateProducedEvent.class));
    verify(w.events, times(1)).publishEvent(any(AdaptationJobCompletedEvent.class));
    verify(w.traceWriter, times(1)).write(any(AdaptationTraceWriter.TraceData.class));
  }

  private static AdaptationJob job(JobSource source) {
    return AdaptationJob.builder()
        .id(UUID.randomUUID())
        .recipeId(UUID.randomUUID())
        .userId(UUID.randomUUID())
        .catalogue(com.example.mealprep.recipe.domain.entity.Catalogue.USER)
        .source(source)
        .priority(JobPriority.SYNC)
        .approvalPolicy(ApprovalPolicy.PENDING_CHANGE)
        .status(JobStatus.RUNNING)
        .inputs(JsonNodeFactory.instance.objectNode())
        .traceId(UUID.randomUUID())
        .enqueuedAt(Instant.now())
        .build();
  }

  private static class Wiring {
    final AdaptationJobRepository jobRepository = mock(AdaptationJobRepository.class);
    final PendingChangeRepository pendingChangeRepository = mock(PendingChangeRepository.class);
    final AdaptationTraceRepository traceRepository = mock(AdaptationTraceRepository.class);
    final AdaptationFingerprintRepository fingerprintRepository =
        mock(AdaptationFingerprintRepository.class);
    final PlannerHintRecordRepository plannerHintRepository =
        mock(PlannerHintRecordRepository.class);
    final NutritionalKnowledgeRepository nutritionalKnowledgeRepository =
        mock(NutritionalKnowledgeRepository.class);
    final CandidateGenerator candidateGenerator =
        new CandidateGenerator(
            new IngredientSwapStrategy(),
            new PortionAdjustStrategy(),
            new MethodSimplificationStrategy(),
            new IngredientRemoveStrategy());
    final ScoringEngine scoringEngine = mock(ScoringEngine.class);
    final AdaptationLlmInvoker llmInvoker = mock(AdaptationLlmInvoker.class);
    final ConfidenceFloorGate confidenceFloorGate;
    final CharacterPreservationGate characterPreservationGate = new CharacterPreservationGate();
    final PendingChangeStore pendingChangeStore = mock(PendingChangeStore.class);
    final ChangeDimensionResolver dimensionResolver = new ChangeDimensionResolver();
    final AdaptationTraceWriter traceWriter = mock(AdaptationTraceWriter.class);
    final LockService lockService = mock(LockService.class);
    final DecisionLogService decisionLogService = mock(DecisionLogService.class);
    final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    final AdaptationServiceImpl service;

    Wiring() {
      AdaptationConfig config =
          new AdaptationConfig(
              5,
              10_000,
              8_000,
              12_000,
              3,
              3,
              14,
              new BigDecimal("0.50"),
              new BigDecimal("2.00"),
              null,
              30,
              "0 0 4 * * *",
              "0 30 4 * * *");
      this.confidenceFloorGate = new ConfidenceFloorGate(config);
      // ScoringEngine mock returns a single-candidate list so processJob doesn't bail out empty.
      RecipeAdaptationResponse response =
          new RecipeAdaptationResponse(
              0,
              AdaptationClassification.VERSION,
              "ok",
              "",
              BigDecimal.valueOf(0.8),
              BigDecimal.valueOf(0.8),
              null,
              JsonNodeFactory.instance.objectNode(),
              List.of());
      // make scoring engine pass through a fake top-N list of size 1 to drive the auto-skip path.
      when(scoringEngine.selectTopN(any()))
          .thenAnswer(
              inv -> {
                List<?> raw = inv.getArgument(0);
                return raw.isEmpty() ? List.of() : List.of(((List<?>) raw).get(0));
              });
      when(llmInvoker.invoke(any(AdaptationJob.class), any(AdaptationContext.class)))
          .thenReturn(response);
      this.service =
          new AdaptationServiceImpl(
              jobRepository,
              pendingChangeRepository,
              traceRepository,
              fingerprintRepository,
              plannerHintRepository,
              nutritionalKnowledgeRepository,
              candidateGenerator,
              scoringEngine,
              llmInvoker,
              confidenceFloorGate,
              characterPreservationGate,
              pendingChangeStore,
              dimensionResolver,
              traceWriter,
              lockService,
              decisionLogService,
              events);
    }
  }
}
