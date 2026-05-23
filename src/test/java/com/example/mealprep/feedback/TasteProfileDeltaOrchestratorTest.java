package com.example.mealprep.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.mealprep.ai.domain.service.AiService;
import com.example.mealprep.ai.exception.AiUnavailableException;
import com.example.mealprep.ai.spi.AiTask;
import com.example.mealprep.feedback.ai.config.PreferenceDeltaProperties;
import com.example.mealprep.feedback.ai.dto.AiTasteProfileDelta;
import com.example.mealprep.feedback.ai.dto.ClassifiedFeedbackEvent;
import com.example.mealprep.feedback.ai.dto.TasteProfileDeltaResponse;
import com.example.mealprep.feedback.ai.internal.AiToApplyDeltaMapper;
import com.example.mealprep.feedback.ai.internal.PreferenceFeedbackBatchGatherer;
import com.example.mealprep.feedback.ai.internal.TasteProfileDeltaOrchestrator;
import com.example.mealprep.feedback.ai.internal.TasteProfileDeltaOrchestrator.RunResult;
import com.example.mealprep.feedback.ai.task.PreferenceTasteProfileDeltaTask;
import com.example.mealprep.preference.PreferenceModule;
import com.example.mealprep.preference.api.dto.ApplyTasteProfileDeltasRequest;
import com.example.mealprep.preference.api.dto.TasteProfileDto;
import com.example.mealprep.preference.domain.document.TasteProfileDocument;
import com.example.mealprep.preference.domain.entity.TasteProfileTrigger;
import com.example.mealprep.preference.domain.entity.TasteVectorStatus;
import com.example.mealprep.preference.domain.service.PreferenceArchiveQueryService;
import com.example.mealprep.preference.domain.service.TasteProfileQueryService;
import com.example.mealprep.preference.domain.service.TasteProfileUpdateService;
import com.example.mealprep.preference.exception.InvalidTasteProfileDeltaException;
import com.example.mealprep.preference.exception.TasteProfileBudgetExceededException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Unit tests for {@link TasteProfileDeltaOrchestrator}: trigger flows, AiUnavailable defer, the
 * corrective-retry seam, empty-AI-response, and the missing-profile skip. Real {@link
 * AiToApplyDeltaMapper} (same-module collaborator); mocks only the cross-module boundaries
 * (AiService, PreferenceModule services) and the batch gatherer (a thin DB-reader).
 */
class TasteProfileDeltaOrchestratorTest {

  private final AiService aiService = Mockito.mock(AiService.class);
  private final PreferenceFeedbackBatchGatherer batchGatherer =
      Mockito.mock(PreferenceFeedbackBatchGatherer.class);
  private final PreferenceModule preferenceModule = Mockito.mock(PreferenceModule.class);
  private final TasteProfileQueryService tasteProfileQuery =
      Mockito.mock(TasteProfileQueryService.class);
  private final TasteProfileUpdateService tasteProfileUpdate =
      Mockito.mock(TasteProfileUpdateService.class);
  private final PreferenceArchiveQueryService archiveQuery =
      Mockito.mock(PreferenceArchiveQueryService.class);

  private final AiToApplyDeltaMapper mapper =
      new AiToApplyDeltaMapper(
          new com.fasterxml.jackson.databind.ObjectMapper(), java.time.Clock.systemUTC());

  private final PreferenceDeltaProperties properties = new PreferenceDeltaProperties(null, 5, true);

  private TasteProfileDeltaOrchestrator orchestrator;

  private final UUID userId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    when(preferenceModule.tasteProfileQuery()).thenReturn(tasteProfileQuery);
    when(preferenceModule.tasteProfileUpdate()).thenReturn(tasteProfileUpdate);
    when(preferenceModule.preferenceArchiveQuery()).thenReturn(archiveQuery);
    when(archiveQuery.getFullArchive(any())).thenReturn(List.of());

    // A REQUIRES_NEW template that runs the callback inline (no real tx in a unit test).
    TransactionTemplate inlineTemplate = Mockito.mock(TransactionTemplate.class);
    Mockito.lenient()
        .doAnswer(TasteProfileDeltaOrchestratorTest::runCallback)
        .when(inlineTemplate)
        .executeWithoutResult(any());
    Mockito.lenient()
        .doAnswer(TasteProfileDeltaOrchestratorTest::runCallbackWithResult)
        .when(inlineTemplate)
        .execute(any());

    orchestrator =
        new TasteProfileDeltaOrchestrator(
            aiService, mapper, batchGatherer, preferenceModule, properties, inlineTemplate);
  }

  @SuppressWarnings("unchecked")
  private static Object runCallback(InvocationOnMock inv) {
    java.util.function.Consumer<org.springframework.transaction.TransactionStatus> c =
        inv.getArgument(0);
    c.accept(new SimpleTransactionStatus());
    return null;
  }

  @SuppressWarnings("unchecked")
  private static Object runCallbackWithResult(InvocationOnMock inv) {
    TransactionCallback<Object> c = inv.getArgument(0);
    return c.doInTransaction(new SimpleTransactionStatus());
  }

  @Test
  void run_noProfile_skipsWithoutAiCall() {
    when(tasteProfileQuery.getTasteProfile(userId)).thenReturn(Optional.empty());

    RunResult result = orchestrator.run(userId, TasteProfileTrigger.BATCH, null, null, null);

    assertThat(result).isEqualTo(RunResult.SKIPPED_NO_PROFILE);
    verify(aiService, never()).execute(any());
    verify(tasteProfileUpdate, never()).applyDeltas(any(), any());
  }

  @Test
  void run_emptyBatch_skipsWithoutAiCall() {
    profileExists();
    when(batchGatherer.gather(eq(userId), any())).thenReturn(List.of());

    RunResult result = orchestrator.run(userId, TasteProfileTrigger.BATCH, null, null, null);

    assertThat(result).isEqualTo(RunResult.SKIPPED_EMPTY_BATCH);
    verify(aiService, never()).execute(any());
  }

  @Test
  @SuppressWarnings("unchecked")
  void run_happyPath_appliesMappedDeltasWithBatchTriggerAndFeedbackRange() {
    profileExists();
    UUID f1 = UUID.randomUUID();
    UUID f2 = UUID.randomUUID();
    when(batchGatherer.gather(eq(userId), any())).thenReturn(List.of(event(f1), event(f2)));
    when(aiService.execute(any(AiTask.class)))
        .thenReturn(
            new TasteProfileDeltaResponse(
                List.of(
                    new AiTasteProfileDelta.Add(
                        "likes.ingredients",
                        "prawns",
                        "in stir fry",
                        f1.toString(),
                        "explicit",
                        AiTasteProfileDelta.Confidence.MEDIUM)),
                "added prawns",
                List.of()));

    RunResult result = orchestrator.run(userId, TasteProfileTrigger.BATCH, null, null, null);

    assertThat(result).isEqualTo(RunResult.APPLIED);
    ArgumentCaptor<ApplyTasteProfileDeltasRequest> captor =
        ArgumentCaptor.forClass(ApplyTasteProfileDeltasRequest.class);
    verify(tasteProfileUpdate).applyDeltas(eq(userId), captor.capture());
    ApplyTasteProfileDeltasRequest req = captor.getValue();
    assertThat(req.trigger()).isEqualTo(TasteProfileTrigger.BATCH);
    assertThat(req.feedbackRangeStart()).isEqualTo("feedback-" + f1);
    assertThat(req.feedbackRangeEnd()).isEqualTo("feedback-" + f2);
    assertThat(req.modelTierUsed()).isEqualTo("mid");
    assertThat(req.deltas()).hasSize(1);
  }

  @Test
  @SuppressWarnings("unchecked")
  void run_emptyAiResponse_noApplyCallNoVersionBump() {
    profileExists();
    when(batchGatherer.gather(eq(userId), any())).thenReturn(List.of(event(UUID.randomUUID())));
    when(aiService.execute(any(AiTask.class)))
        .thenReturn(new TasteProfileDeltaResponse(List.of(), "no change warranted", List.of()));

    RunResult result = orchestrator.run(userId, TasteProfileTrigger.BATCH, null, null, null);

    assertThat(result).isEqualTo(RunResult.NO_DELTAS);
    verify(tasteProfileUpdate, never()).applyDeltas(any(), any());
  }

  @Test
  @SuppressWarnings("unchecked")
  void run_aiUnavailable_defersWithoutThrowingNoApply() {
    profileExists();
    when(batchGatherer.gather(eq(userId), any())).thenReturn(List.of(event(UUID.randomUUID())));
    when(aiService.execute(any(AiTask.class))).thenThrow(new AiUnavailableException("503"));

    RunResult result = orchestrator.run(userId, TasteProfileTrigger.BATCH, null, null, null);

    assertThat(result).isEqualTo(RunResult.AI_UNAVAILABLE);
    verify(tasteProfileUpdate, never()).applyDeltas(any(), any());
  }

  @Test
  @SuppressWarnings("unchecked")
  void run_invalidDelta_retriesOnceWithCorrectiveHintThenSucceeds() {
    profileExists();
    UUID f1 = UUID.randomUUID();
    when(batchGatherer.gather(eq(userId), any())).thenReturn(List.of(event(f1)));
    when(aiService.execute(any(AiTask.class)))
        .thenReturn(addResponse("first"), addResponse("corrected"));
    // First apply rejects; second succeeds.
    Mockito.doThrow(new InvalidTasteProfileDeltaException("bad path"))
        .doReturn(null)
        .when(tasteProfileUpdate)
        .applyDeltas(eq(userId), any());

    RunResult result = orchestrator.run(userId, TasteProfileTrigger.BATCH, null, null, null);

    assertThat(result).isEqualTo(RunResult.APPLIED);
    // Two AI calls (initial + corrective) and two apply attempts.
    verify(aiService, times(2)).execute(any(AiTask.class));
    verify(tasteProfileUpdate, times(2)).applyDeltas(eq(userId), any());

    // The corrective retry carried a hint.
    ArgumentCaptor<AiTask<TasteProfileDeltaResponse>> taskCaptor =
        ArgumentCaptor.forClass(AiTask.class);
    verify(aiService, times(2)).execute(taskCaptor.capture());
    PreferenceTasteProfileDeltaTask retryTask =
        (PreferenceTasteProfileDeltaTask) taskCaptor.getAllValues().get(1);
    assertThat(retryTask.variables()).containsKey("corrective_hint");
  }

  @Test
  @SuppressWarnings("unchecked")
  void run_budgetExceeded_retryStillBad_logsDeltaInvalidNoApply() {
    profileExists();
    when(batchGatherer.gather(eq(userId), any())).thenReturn(List.of(event(UUID.randomUUID())));
    when(aiService.execute(any(AiTask.class))).thenReturn(addResponse("a"), addResponse("b"));
    Mockito.doThrow(new TasteProfileBudgetExceededException("over budget"))
        .when(tasteProfileUpdate)
        .applyDeltas(eq(userId), any());

    RunResult result = orchestrator.run(userId, TasteProfileTrigger.BATCH, null, null, null);

    assertThat(result).isEqualTo(RunResult.DELTA_INVALID);
    verify(tasteProfileUpdate, times(2)).applyDeltas(eq(userId), any());
  }

  @Test
  @SuppressWarnings("unchecked")
  void run_correctiveRetryDisabled_doesNotRetry() {
    TasteProfileDeltaOrchestrator noRetry =
        new TasteProfileDeltaOrchestrator(
            aiService,
            mapper,
            batchGatherer,
            preferenceModule,
            new PreferenceDeltaProperties(null, 5, false),
            inlineTemplate());
    profileExists();
    when(batchGatherer.gather(eq(userId), any())).thenReturn(List.of(event(UUID.randomUUID())));
    when(aiService.execute(any(AiTask.class))).thenReturn(addResponse("a"));
    Mockito.doThrow(new InvalidTasteProfileDeltaException("bad"))
        .when(tasteProfileUpdate)
        .applyDeltas(eq(userId), any());

    RunResult result = noRetry.run(userId, TasteProfileTrigger.BATCH, null, null, null);

    assertThat(result).isEqualTo(RunResult.DELTA_INVALID);
    verify(aiService, times(1)).execute(any(AiTask.class));
    verify(tasteProfileUpdate, times(1)).applyDeltas(eq(userId), any());
  }

  @Test
  @SuppressWarnings("unchecked")
  void run_manualWithExplicitRange_honoursOverrideAndTrace() {
    profileExists();
    when(batchGatherer.gather(eq(userId), any())).thenReturn(List.of(event(UUID.randomUUID())));
    when(aiService.execute(any(AiTask.class))).thenReturn(addResponse("a"));
    when(tasteProfileUpdate.applyDeltas(eq(userId), any())).thenReturn(null);
    UUID trace = UUID.randomUUID();

    RunResult result =
        orchestrator.run(userId, TasteProfileTrigger.MANUAL, trace, "feedback-X", "feedback-Y");

    assertThat(result).isEqualTo(RunResult.APPLIED);
    ArgumentCaptor<ApplyTasteProfileDeltasRequest> captor =
        ArgumentCaptor.forClass(ApplyTasteProfileDeltasRequest.class);
    verify(tasteProfileUpdate).applyDeltas(eq(userId), captor.capture());
    assertThat(captor.getValue().trigger()).isEqualTo(TasteProfileTrigger.MANUAL);
    assertThat(captor.getValue().feedbackRangeStart()).isEqualTo("feedback-X");
    assertThat(captor.getValue().feedbackRangeEnd()).isEqualTo("feedback-Y");
  }

  // ---------------- helpers ----------------

  private TransactionTemplate inlineTemplate() {
    TransactionTemplate inlineTemplate = Mockito.mock(TransactionTemplate.class);
    Mockito.lenient()
        .doAnswer(TasteProfileDeltaOrchestratorTest::runCallback)
        .when(inlineTemplate)
        .executeWithoutResult(any());
    return inlineTemplate;
  }

  private void profileExists() {
    when(tasteProfileQuery.getTasteProfile(userId)).thenReturn(Optional.of(profileDto()));
  }

  private TasteProfileDto profileDto() {
    return new TasteProfileDto(
        UUID.randomUUID(),
        userId,
        TasteProfileDocument.empty(LocalDate.of(2026, 5, 23)),
        3,
        "feedback-cursor",
        12,
        Instant.parse("2026-05-20T00:00:00Z"),
        500,
        TasteVectorStatus.EMBEDDED,
        2L,
        Instant.parse("2026-05-01T00:00:00Z"),
        Instant.parse("2026-05-20T00:00:00Z"));
  }

  private ClassifiedFeedbackEvent event(UUID id) {
    return new ClassifiedFeedbackEvent(
        id, "I love prawns", "feedback on a recipe", new BigDecimal("0.92"), Instant.now());
  }

  private TasteProfileDeltaResponse addResponse(String reasoning) {
    return new TasteProfileDeltaResponse(
        List.of(
            new AiTasteProfileDelta.Add(
                "likes.ingredients",
                "prawns",
                "note",
                UUID.randomUUID().toString(),
                reasoning,
                AiTasteProfileDelta.Confidence.MEDIUM)),
        reasoning,
        List.of());
  }
}
