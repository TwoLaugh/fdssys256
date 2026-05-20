package com.example.mealprep.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.mealprep.feedback.api.dto.ClassificationOutput;
import com.example.mealprep.feedback.api.dto.Screen;
import com.example.mealprep.feedback.domain.document.UiContextDocument;
import com.example.mealprep.feedback.domain.entity.FeedbackEntry;
import com.example.mealprep.feedback.domain.entity.RoutingDecision;
import com.example.mealprep.feedback.domain.entity.RoutingFailureKind;
import com.example.mealprep.feedback.domain.entity.RoutingLogEntry;
import com.example.mealprep.feedback.domain.entity.RoutingStatus;
import com.example.mealprep.feedback.domain.entity.SubmissionStatus;
import com.example.mealprep.feedback.domain.repository.FeedbackEntryRepository;
import com.example.mealprep.feedback.domain.repository.RoutingLogRepository;
import com.example.mealprep.feedback.domain.service.internal.ConfidenceGate;
import com.example.mealprep.feedback.domain.service.internal.DestinationDispatcher;
import com.example.mealprep.feedback.domain.service.internal.DestinationDispatcherRegistry;
import com.example.mealprep.feedback.domain.service.internal.DispatchContext;
import com.example.mealprep.feedback.domain.service.internal.DispatchResult;
import com.example.mealprep.feedback.domain.service.internal.FeedbackRouterImpl;
import com.example.mealprep.feedback.event.FeedbackProcessedEvent;
import com.example.mealprep.feedback.exception.RecipeFeedbackHandlerUnavailableException;
import com.example.mealprep.feedback.spi.Destination;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Unit tests for the per-destination fan-out, exception classification, status reconciliation, and
 * {@code FeedbackProcessedEvent} publication of {@link FeedbackRouterImpl}.
 */
class FeedbackRouterImplTest {

  private FeedbackEntryRepository entryRepository;
  private RoutingLogRepository routingLogRepository;
  private DestinationDispatcherRegistry registry;
  private ApplicationEventPublisher events;
  private TransactionTemplate txTemplate;
  private Clock clock;

  private FeedbackRouterImpl router;

  private UUID feedbackId;
  private UUID userId;
  private UUID traceId;
  private FeedbackEntry entry;

  @BeforeEach
  void setUp() {
    entryRepository = Mockito.mock(FeedbackEntryRepository.class);
    routingLogRepository = Mockito.mock(RoutingLogRepository.class);
    registry = Mockito.mock(DestinationDispatcherRegistry.class);
    events = Mockito.mock(ApplicationEventPublisher.class);
    txTemplate = inlineRequiresNewTemplate();
    clock = Clock.fixed(Instant.parse("2026-05-10T00:00:00Z"), ZoneOffset.UTC);

    router =
        new FeedbackRouterImpl(
            entryRepository, routingLogRepository, registry, events, txTemplate, clock);

    feedbackId = UUID.randomUUID();
    userId = UUID.randomUUID();
    traceId = UUID.randomUUID();
    entry =
        FeedbackEntry.builder()
            .id(feedbackId)
            .userId(userId)
            .traceId(traceId)
            .text("the salt was too much")
            .uiContext(
                new UiContextDocument(Screen.RECIPE_DETAIL, UUID.randomUUID(), 1, null, null, null))
            .submissionStatus(SubmissionStatus.CLASSIFIED)
            .classificationAttempts(1)
            .routingLog(new ArrayList<>())
            .build();

    when(entryRepository.findById(feedbackId)).thenReturn(Optional.of(entry));
    when(routingLogRepository.save(any(RoutingLogEntry.class)))
        .thenAnswer(inv -> inv.getArgument(0, RoutingLogEntry.class));
    when(entryRepository.updateSubmissionStatusAndLastClassifiedAt(
            org.mockito.ArgumentMatchers.eq(feedbackId),
            any(SubmissionStatus.class),
            any(Instant.class)))
        .thenAnswer(
            inv -> {
              entry.setSubmissionStatus(inv.getArgument(1, SubmissionStatus.class));
              entry.setLastClassifiedAt(inv.getArgument(2, Instant.class));
              return 1;
            });
  }

  @Test
  void singleAppliedDispatch_marksRouted_publishesEventWithDestinationTouched() {
    ConfidenceGate.ScoredClassification scored =
        scored(Destination.PREFERENCE, "0.92", RoutingDecision.AUTO_ROUTED);
    DestinationDispatcher dispatcher = stubDispatcher(Destination.PREFERENCE, applied());
    when(registry.resolve(Destination.PREFERENCE)).thenReturn(dispatcher);

    router.routeAll(feedbackId, List.of(scored));

    assertThat(entry.getSubmissionStatus()).isEqualTo(SubmissionStatus.ROUTED);
    ArgumentCaptor<FeedbackProcessedEvent> ec =
        ArgumentCaptor.forClass(FeedbackProcessedEvent.class);
    verify(events).publishEvent(ec.capture());
    FeedbackProcessedEvent ev = ec.getValue();
    assertThat(ev.destinationsTouched()).containsExactly(Destination.PREFERENCE);
    assertThat(ev.partialFailure()).isFalse();
    // Two saves on the log row: PENDING insert + APPLIED update.
    verify(routingLogRepository, times(2)).save(any(RoutingLogEntry.class));
  }

  @Test
  void twoAppliedDispatches_marksRouted_oneEventOnly() {
    ConfidenceGate.ScoredClassification s1 =
        scored(Destination.PREFERENCE, "0.9", RoutingDecision.AUTO_ROUTED);
    ConfidenceGate.ScoredClassification s2 =
        scored(Destination.PROVISIONS, "0.85", RoutingDecision.AUTO_ROUTED);
    DestinationDispatcher pref = stubDispatcher(Destination.PREFERENCE, applied());
    DestinationDispatcher prov = stubDispatcher(Destination.PROVISIONS, applied());
    when(registry.resolve(Destination.PREFERENCE)).thenReturn(pref);
    when(registry.resolve(Destination.PROVISIONS)).thenReturn(prov);

    router.routeAll(feedbackId, List.of(s1, s2));

    assertThat(entry.getSubmissionStatus()).isEqualTo(SubmissionStatus.ROUTED);
    verify(events, times(1)).publishEvent(any(FeedbackProcessedEvent.class));
    ArgumentCaptor<FeedbackProcessedEvent> ec =
        ArgumentCaptor.forClass(FeedbackProcessedEvent.class);
    verify(events).publishEvent(ec.capture());
    assertThat(ec.getValue().destinationsTouched())
        .containsExactlyInAnyOrder(Destination.PREFERENCE, Destination.PROVISIONS);
  }

  @Test
  void oneFailedTwoApplied_marksPartiallyFailed_partialFailureTrue() {
    ConfidenceGate.ScoredClassification sRecipe =
        scored(Destination.RECIPE, "0.9", RoutingDecision.AUTO_ROUTED);
    ConfidenceGate.ScoredClassification sPref =
        scored(Destination.PREFERENCE, "0.88", RoutingDecision.AUTO_ROUTED);
    ConfidenceGate.ScoredClassification sNut =
        scored(Destination.NUTRITION, "0.85", RoutingDecision.AUTO_ROUTED);
    DestinationDispatcher dRecipe = stubDispatcher(Destination.RECIPE, applied());
    DestinationDispatcher dPref = stubDispatcher(Destination.PREFERENCE, applied());
    when(registry.resolve(Destination.RECIPE)).thenReturn(dRecipe);
    when(registry.resolve(Destination.PREFERENCE)).thenReturn(dPref);
    DestinationDispatcher failingNutrition = Mockito.mock(DestinationDispatcher.class);
    when(failingNutrition.destination()).thenReturn(Destination.NUTRITION);
    when(failingNutrition.dispatch(any(DispatchContext.class)))
        .thenThrow(
            new com.example.mealprep.nutrition.exception.NutritionTargetsNotFoundException(userId));
    when(registry.resolve(Destination.NUTRITION)).thenReturn(failingNutrition);

    router.routeAll(feedbackId, List.of(sRecipe, sPref, sNut));

    assertThat(entry.getSubmissionStatus()).isEqualTo(SubmissionStatus.PARTIALLY_FAILED);
    ArgumentCaptor<FeedbackProcessedEvent> ec =
        ArgumentCaptor.forClass(FeedbackProcessedEvent.class);
    verify(events).publishEvent(ec.capture());
    assertThat(ec.getValue().partialFailure()).isTrue();
    assertThat(ec.getValue().destinationsTouched())
        .containsExactlyInAnyOrder(
            Destination.RECIPE, Destination.PREFERENCE, Destination.NUTRITION);
  }

  @Test
  void allDispatchersFail_marksFailed() {
    ConfidenceGate.ScoredClassification sPref =
        scored(Destination.PREFERENCE, "0.9", RoutingDecision.AUTO_ROUTED);
    DestinationDispatcher d = Mockito.mock(DestinationDispatcher.class);
    when(d.destination()).thenReturn(Destination.PREFERENCE);
    when(d.dispatch(any(DispatchContext.class)))
        .thenThrow(new DataAccessResourceFailureException("db down"));
    when(registry.resolve(Destination.PREFERENCE)).thenReturn(d);

    router.routeAll(feedbackId, List.of(sPref));

    assertThat(entry.getSubmissionStatus()).isEqualTo(SubmissionStatus.FAILED);
    ArgumentCaptor<FeedbackProcessedEvent> ec =
        ArgumentCaptor.forClass(FeedbackProcessedEvent.class);
    verify(events).publishEvent(ec.capture());
    assertThat(ec.getValue().partialFailure()).isTrue();
  }

  @Test
  void recipeFeedbackHandlerUnavailable_classifiedAsAiUnavailable() {
    ConfidenceGate.ScoredClassification sRecipe =
        scored(Destination.RECIPE, "0.95", RoutingDecision.AUTO_ROUTED);
    DestinationDispatcher d = Mockito.mock(DestinationDispatcher.class);
    when(d.destination()).thenReturn(Destination.RECIPE);
    when(d.dispatch(any(DispatchContext.class)))
        .thenThrow(new RecipeFeedbackHandlerUnavailableException("not on classpath"));
    when(registry.resolve(Destination.RECIPE)).thenReturn(d);

    router.routeAll(feedbackId, List.of(sRecipe));

    ArgumentCaptor<RoutingLogEntry> logCaptor = ArgumentCaptor.forClass(RoutingLogEntry.class);
    verify(routingLogRepository, times(2)).save(logCaptor.capture());
    RoutingLogEntry terminal = logCaptor.getAllValues().get(1);
    assertThat(terminal.getStatus()).isEqualTo(RoutingStatus.FAILED);
    assertThat(terminal.getFailureKind()).isEqualTo(RoutingFailureKind.AI_UNAVAILABLE);
  }

  @Test
  void awaitingApprovalResult_stampsLogStatusAwaitingApproval() {
    ConfidenceGate.ScoredClassification sRecipe =
        scored(Destination.RECIPE, "0.95", RoutingDecision.AUTO_ROUTED);
    DispatchResult res =
        DispatchResult.awaitingApproval("proposed", JsonNodeFactory.instance.objectNode());
    DestinationDispatcher approving = stubDispatcher(Destination.RECIPE, res);
    when(registry.resolve(Destination.RECIPE)).thenReturn(approving);

    router.routeAll(feedbackId, List.of(sRecipe));

    ArgumentCaptor<RoutingLogEntry> logCaptor = ArgumentCaptor.forClass(RoutingLogEntry.class);
    verify(routingLogRepository, times(2)).save(logCaptor.capture());
    RoutingLogEntry terminal = logCaptor.getAllValues().get(1);
    assertThat(terminal.getStatus()).isEqualTo(RoutingStatus.AWAITING_USER_APPROVAL);
    assertThat(terminal.getActionTaken()).isEqualTo("proposed");
    // AWAITING_USER_APPROVAL counts as non-failed → ROUTED.
    assertThat(entry.getSubmissionStatus()).isEqualTo(SubmissionStatus.ROUTED);
  }

  @Test
  void actionTakenTruncatedTo512Chars() {
    String longMessage = "x".repeat(600);
    ConfidenceGate.ScoredClassification s =
        scored(Destination.PREFERENCE, "0.9", RoutingDecision.AUTO_ROUTED);
    DestinationDispatcher truncDispatcher =
        stubDispatcher(
            Destination.PREFERENCE,
            DispatchResult.applied(longMessage, JsonNodeFactory.instance.objectNode()));
    when(registry.resolve(Destination.PREFERENCE)).thenReturn(truncDispatcher);

    router.routeAll(feedbackId, List.of(s));

    ArgumentCaptor<RoutingLogEntry> logCaptor = ArgumentCaptor.forClass(RoutingLogEntry.class);
    verify(routingLogRepository, times(2)).save(logCaptor.capture());
    assertThat(logCaptor.getAllValues().get(1).getActionTaken()).hasSize(512);
  }

  @Test
  void failureMessageTruncatedTo512Chars_andSecretsStripped() {
    String longSecret = "Authorization: Bearer abc " + "y".repeat(600);
    ConfidenceGate.ScoredClassification s =
        scored(Destination.PREFERENCE, "0.9", RoutingDecision.AUTO_ROUTED);
    DestinationDispatcher d = Mockito.mock(DestinationDispatcher.class);
    when(d.destination()).thenReturn(Destination.PREFERENCE);
    when(d.dispatch(any(DispatchContext.class))).thenThrow(new IllegalStateException(longSecret));
    when(registry.resolve(Destination.PREFERENCE)).thenReturn(d);

    router.routeAll(feedbackId, List.of(s));

    ArgumentCaptor<RoutingLogEntry> logCaptor = ArgumentCaptor.forClass(RoutingLogEntry.class);
    verify(routingLogRepository, times(2)).save(logCaptor.capture());
    String stamped = logCaptor.getAllValues().get(1).getFailureMessage();
    assertThat(stamped).hasSizeLessThanOrEqualTo(512);
    assertThat(stamped).doesNotContain("Authorization");
    assertThat(stamped).contains("[REDACTED]");
  }

  @Test
  void emptyClassifications_isNoop_noEvent_noLogWrites() {
    router.routeAll(feedbackId, List.of());
    verify(events, never()).publishEvent(any());
    verify(routingLogRepository, never()).save(any(RoutingLogEntry.class));
    verify(entryRepository, never()).updateSubmissionStatusAndLastClassifiedAt(any(), any(), any());
  }

  @Test
  void destinationBusinessException_classifiedAsBusiness() {
    ConfidenceGate.ScoredClassification s =
        scored(Destination.PROVISIONS, "0.9", RoutingDecision.AUTO_ROUTED);
    DestinationDispatcher d = Mockito.mock(DestinationDispatcher.class);
    when(d.destination()).thenReturn(Destination.PROVISIONS);
    UUID itemId = UUID.randomUUID();
    when(d.dispatch(any(DispatchContext.class)))
        .thenThrow(
            new com.example.mealprep.provisions.exception.InventoryItemNotFoundException(itemId));
    when(registry.resolve(Destination.PROVISIONS)).thenReturn(d);

    router.routeAll(feedbackId, List.of(s));

    ArgumentCaptor<RoutingLogEntry> logCaptor = ArgumentCaptor.forClass(RoutingLogEntry.class);
    verify(routingLogRepository, times(2)).save(logCaptor.capture());
    assertThat(logCaptor.getAllValues().get(1).getFailureKind())
        .isEqualTo(RoutingFailureKind.DESTINATION_BUSINESS);
  }

  // ---------------- routeOneForReplay ----------------

  @Test
  void routeOneForReplay_appliedDispatch_returnsAppliedNoEventNoReconcile() {
    ConfidenceGate.ScoredClassification s =
        scored(Destination.PREFERENCE, "0.9", RoutingDecision.AUTO_ROUTED);
    DestinationDispatcher d = stubDispatcher(Destination.PREFERENCE, applied());
    when(registry.resolve(Destination.PREFERENCE)).thenReturn(d);

    FeedbackRouterImpl.RouteReplayResult result = router.routeOneForReplay(feedbackId, s);

    assertThat(result.status()).isEqualTo(RoutingStatus.APPLIED);
    assertThat(result.failureKind()).isNull();
    assertThat(result.newRoutingLogId()).isNotNull();
    // Replay must NOT reconcile entry status nor publish the processed event.
    verify(events, never()).publishEvent(any());
    verify(entryRepository, never()).updateSubmissionStatusAndLastClassifiedAt(any(), any(), any());
    // PENDING insert + terminal update.
    verify(routingLogRepository, times(2)).save(any(RoutingLogEntry.class));
  }

  @Test
  void routeOneForReplay_dispatcherThrowsBusinessException_returnsFailedWithKind() {
    ConfidenceGate.ScoredClassification s =
        scored(Destination.PROVISIONS, "0.9", RoutingDecision.AUTO_ROUTED);
    DestinationDispatcher d = Mockito.mock(DestinationDispatcher.class);
    when(d.destination()).thenReturn(Destination.PROVISIONS);
    when(d.dispatch(any(DispatchContext.class)))
        .thenThrow(
            new com.example.mealprep.provisions.exception.InventoryItemNotFoundException(
                UUID.randomUUID()));
    when(registry.resolve(Destination.PROVISIONS)).thenReturn(d);

    FeedbackRouterImpl.RouteReplayResult result = router.routeOneForReplay(feedbackId, s);

    assertThat(result.status()).isEqualTo(RoutingStatus.FAILED);
    assertThat(result.failureKind()).isEqualTo(RoutingFailureKind.DESTINATION_BUSINESS);
  }

  @Test
  void routeOneForReplay_catastrophicTxFailure_persistsDefensiveFailureLog_returnsUnknown() {
    ConfidenceGate.ScoredClassification s =
        scored(Destination.PREFERENCE, "0.9", RoutingDecision.AUTO_ROUTED);
    // First save (PENDING insert inside the routeOne tx) explodes → catastrophic catch.
    when(routingLogRepository.save(any(RoutingLogEntry.class)))
        .thenThrow(new DataAccessResourceFailureException("ds down"))
        .thenAnswer(inv -> inv.getArgument(0, RoutingLogEntry.class));

    FeedbackRouterImpl.RouteReplayResult result = router.routeOneForReplay(feedbackId, s);

    assertThat(result.status()).isEqualTo(RoutingStatus.FAILED);
    assertThat(result.failureKind()).isEqualTo(RoutingFailureKind.UNKNOWN);
    // Defensive FAILED row written OUTSIDE the failed tx (second save).
    ArgumentCaptor<RoutingLogEntry> cap = ArgumentCaptor.forClass(RoutingLogEntry.class);
    verify(routingLogRepository, times(2)).save(cap.capture());
    RoutingLogEntry defensive = cap.getAllValues().get(1);
    assertThat(defensive.getStatus()).isEqualTo(RoutingStatus.FAILED);
    assertThat(defensive.getFailureKind()).isEqualTo(RoutingFailureKind.UNKNOWN);
  }

  @Test
  void routeOneForReplay_unknownFeedbackId_throwsNotFound() {
    UUID missing = UUID.randomUUID();
    when(entryRepository.findById(missing)).thenReturn(Optional.empty());
    ConfidenceGate.ScoredClassification s =
        scored(Destination.PREFERENCE, "0.9", RoutingDecision.AUTO_ROUTED);

    org.junit.jupiter.api.Assertions.assertThrows(
        com.example.mealprep.feedback.exception.FeedbackEntryNotFoundException.class,
        () -> router.routeOneForReplay(missing, s));
  }

  // ---------------- classifyException branch coverage ----------------

  @Test
  void constraintViolation_classifiedAsDestinationValidation() {
    ConfidenceGate.ScoredClassification s =
        scored(Destination.PREFERENCE, "0.9", RoutingDecision.AUTO_ROUTED);
    DestinationDispatcher d = Mockito.mock(DestinationDispatcher.class);
    when(d.destination()).thenReturn(Destination.PREFERENCE);
    when(d.dispatch(any(DispatchContext.class)))
        .thenThrow(new jakarta.validation.ConstraintViolationException("bad payload", Set.of()));
    when(registry.resolve(Destination.PREFERENCE)).thenReturn(d);

    router.routeAll(feedbackId, List.of(s));

    ArgumentCaptor<RoutingLogEntry> cap = ArgumentCaptor.forClass(RoutingLogEntry.class);
    verify(routingLogRepository, times(2)).save(cap.capture());
    assertThat(cap.getAllValues().get(1).getFailureKind())
        .isEqualTo(RoutingFailureKind.DESTINATION_VALIDATION);
  }

  @Test
  void cannotAcquireLock_classifiedAsTransient() {
    ConfidenceGate.ScoredClassification s =
        scored(Destination.PREFERENCE, "0.9", RoutingDecision.AUTO_ROUTED);
    DestinationDispatcher d = Mockito.mock(DestinationDispatcher.class);
    when(d.destination()).thenReturn(Destination.PREFERENCE);
    when(d.dispatch(any(DispatchContext.class)))
        .thenThrow(new org.springframework.dao.CannotAcquireLockException("row locked"));
    when(registry.resolve(Destination.PREFERENCE)).thenReturn(d);

    router.routeAll(feedbackId, List.of(s));

    ArgumentCaptor<RoutingLogEntry> cap = ArgumentCaptor.forClass(RoutingLogEntry.class);
    verify(routingLogRepository, times(2)).save(cap.capture());
    assertThat(cap.getAllValues().get(1).getFailureKind()).isEqualTo(RoutingFailureKind.TRANSIENT);
  }

  @Test
  void queryTimeout_classifiedAsTransient() {
    ConfidenceGate.ScoredClassification s =
        scored(Destination.PREFERENCE, "0.9", RoutingDecision.AUTO_ROUTED);
    DestinationDispatcher d = Mockito.mock(DestinationDispatcher.class);
    when(d.destination()).thenReturn(Destination.PREFERENCE);
    when(d.dispatch(any(DispatchContext.class)))
        .thenThrow(new org.springframework.dao.QueryTimeoutException("statement timeout"));
    when(registry.resolve(Destination.PREFERENCE)).thenReturn(d);

    router.routeAll(feedbackId, List.of(s));

    ArgumentCaptor<RoutingLogEntry> cap = ArgumentCaptor.forClass(RoutingLogEntry.class);
    verify(routingLogRepository, times(2)).save(cap.capture());
    assertThat(cap.getAllValues().get(1).getFailureKind()).isEqualTo(RoutingFailureKind.TRANSIENT);
  }

  @Test
  void aiInvalidRequest_classifiedAsAiUnavailable() {
    ConfidenceGate.ScoredClassification s =
        scored(Destination.RECIPE, "0.9", RoutingDecision.AUTO_ROUTED);
    DestinationDispatcher d = Mockito.mock(DestinationDispatcher.class);
    when(d.destination()).thenReturn(Destination.RECIPE);
    when(d.dispatch(any(DispatchContext.class)))
        .thenThrow(new com.example.mealprep.ai.exception.AiInvalidRequestException("bad prompt"));
    when(registry.resolve(Destination.RECIPE)).thenReturn(d);

    router.routeAll(feedbackId, List.of(s));

    ArgumentCaptor<RoutingLogEntry> cap = ArgumentCaptor.forClass(RoutingLogEntry.class);
    verify(routingLogRepository, times(2)).save(cap.capture());
    assertThat(cap.getAllValues().get(1).getFailureKind())
        .isEqualTo(RoutingFailureKind.AI_UNAVAILABLE);
  }

  @Test
  void aiInvalidResponse_classifiedAsAiUnavailable() {
    ConfidenceGate.ScoredClassification s =
        scored(Destination.RECIPE, "0.9", RoutingDecision.AUTO_ROUTED);
    DestinationDispatcher d = Mockito.mock(DestinationDispatcher.class);
    when(d.destination()).thenReturn(Destination.RECIPE);
    when(d.dispatch(any(DispatchContext.class)))
        .thenThrow(
            new com.example.mealprep.ai.exception.AiInvalidResponseException("garbage json"));
    when(registry.resolve(Destination.RECIPE)).thenReturn(d);

    router.routeAll(feedbackId, List.of(s));

    ArgumentCaptor<RoutingLogEntry> cap = ArgumentCaptor.forClass(RoutingLogEntry.class);
    verify(routingLogRepository, times(2)).save(cap.capture());
    assertThat(cap.getAllValues().get(1).getFailureKind())
        .isEqualTo(RoutingFailureKind.AI_UNAVAILABLE);
  }

  @Test
  void arbitraryRuntimeException_classifiedAsUnknown() {
    ConfidenceGate.ScoredClassification s =
        scored(Destination.PREFERENCE, "0.9", RoutingDecision.AUTO_ROUTED);
    DestinationDispatcher d = Mockito.mock(DestinationDispatcher.class);
    when(d.destination()).thenReturn(Destination.PREFERENCE);
    when(d.dispatch(any(DispatchContext.class)))
        .thenThrow(new IllegalArgumentException("totally unexpected"));
    when(registry.resolve(Destination.PREFERENCE)).thenReturn(d);

    router.routeAll(feedbackId, List.of(s));

    ArgumentCaptor<RoutingLogEntry> cap = ArgumentCaptor.forClass(RoutingLogEntry.class);
    verify(routingLogRepository, times(2)).save(cap.capture());
    RoutingLogEntry terminal = cap.getAllValues().get(1);
    assertThat(terminal.getStatus()).isEqualTo(RoutingStatus.FAILED);
    assertThat(terminal.getFailureKind()).isEqualTo(RoutingFailureKind.UNKNOWN);
  }

  // ---------------- catastrophic routeAll + reconcile edges ----------------

  @Test
  void catastrophicRouteOneFailure_persistsDefensiveFailedLog_thenReconcilesFailed() {
    ConfidenceGate.ScoredClassification s =
        scored(Destination.PREFERENCE, "0.9", RoutingDecision.AUTO_ROUTED);
    // First save (PENDING insert) blows the routeOne tx; defensive log save (second) succeeds.
    when(routingLogRepository.save(any(RoutingLogEntry.class)))
        .thenThrow(new DataAccessResourceFailureException("ds down"))
        .thenAnswer(inv -> inv.getArgument(0, RoutingLogEntry.class));

    router.routeAll(feedbackId, List.of(s));

    assertThat(entry.getSubmissionStatus()).isEqualTo(SubmissionStatus.FAILED);
    ArgumentCaptor<RoutingLogEntry> cap = ArgumentCaptor.forClass(RoutingLogEntry.class);
    verify(routingLogRepository, times(2)).save(cap.capture());
    RoutingLogEntry defensive = cap.getAllValues().get(1);
    assertThat(defensive.getStatus()).isEqualTo(RoutingStatus.FAILED);
    assertThat(defensive.getFailureKind()).isEqualTo(RoutingFailureKind.UNKNOWN);
    assertThat(defensive.getRoutedAt()).isNotNull();
    assertThat(defensive.getCompletedAt()).isNotNull();
    // Event still published despite the catastrophic dispatch.
    verify(events).publishEvent(any(FeedbackProcessedEvent.class));
  }

  @Test
  void doubleFault_defensiveLogAlsoFails_isSwallowed_reconcileStillRuns() {
    ConfidenceGate.ScoredClassification s =
        scored(Destination.PREFERENCE, "0.9", RoutingDecision.AUTO_ROUTED);
    // Every save throws → routeOne tx fails AND the defensive persistFailureLog double-faults.
    when(routingLogRepository.save(any(RoutingLogEntry.class)))
        .thenThrow(new DataAccessResourceFailureException("ds down"));

    router.routeAll(feedbackId, List.of(s));

    // Double-fault is logged and swallowed; reconcile still flips status to FAILED + publishes.
    assertThat(entry.getSubmissionStatus()).isEqualTo(SubmissionStatus.FAILED);
    verify(events).publishEvent(any(FeedbackProcessedEvent.class));
  }

  @Test
  void reconcile_rowsZero_throwsFeedbackEntryNotFound() {
    ConfidenceGate.ScoredClassification s =
        scored(Destination.PREFERENCE, "0.9", RoutingDecision.AUTO_ROUTED);
    DestinationDispatcher d = stubDispatcher(Destination.PREFERENCE, applied());
    when(registry.resolve(Destination.PREFERENCE)).thenReturn(d);
    // The reconcile UPDATE matches zero rows (entry vanished mid-flight).
    when(entryRepository.updateSubmissionStatusAndLastClassifiedAt(
            org.mockito.ArgumentMatchers.eq(feedbackId),
            any(SubmissionStatus.class),
            any(Instant.class)))
        .thenReturn(0);

    org.junit.jupiter.api.Assertions.assertThrows(
        com.example.mealprep.feedback.exception.FeedbackEntryNotFoundException.class,
        () -> router.routeAll(feedbackId, List.of(s)));
    verify(events, never()).publishEvent(any());
  }

  @Test
  void nullClassifications_isNoop() {
    router.routeAll(feedbackId, null);
    verify(events, never()).publishEvent(any());
    verify(routingLogRepository, never()).save(any(RoutingLogEntry.class));
  }

  @Test
  void nullUiContext_isToleratedAndDispatchStillRuns() {
    entry.setUiContext(null);
    ConfidenceGate.ScoredClassification s =
        scored(Destination.PREFERENCE, "0.9", RoutingDecision.AUTO_ROUTED);
    DestinationDispatcher d = stubDispatcher(Destination.PREFERENCE, applied());
    when(registry.resolve(Destination.PREFERENCE)).thenReturn(d);

    router.routeAll(feedbackId, List.of(s));

    assertThat(entry.getSubmissionStatus()).isEqualTo(SubmissionStatus.ROUTED);
    ArgumentCaptor<DispatchContext> ctx = ArgumentCaptor.forClass(DispatchContext.class);
    verify(d).dispatch(ctx.capture());
    assertThat(ctx.getValue().uiContext()).isNull();
  }

  @Test
  void unknownFeedbackId_routeAll_throwsNotFound() {
    UUID missing = UUID.randomUUID();
    when(entryRepository.findById(missing)).thenReturn(Optional.empty());
    ConfidenceGate.ScoredClassification s =
        scored(Destination.PREFERENCE, "0.9", RoutingDecision.AUTO_ROUTED);

    org.junit.jupiter.api.Assertions.assertThrows(
        com.example.mealprep.feedback.exception.FeedbackEntryNotFoundException.class,
        () -> router.routeAll(missing, List.of(s)));
  }

  // ---------------- mutation-kill: routeOneCapturing setter chain ----------------

  /**
   * The replay path (routeOneCapturing) had six VoidMethodCallMutator survivors on each of the
   * routing-log setters (status, actionTaken, destinationResultJson, failureKind, failureMessage,
   * completedAt). This test captures the terminal-save argument and pins every field — removing any
   * one setter must surface here.
   */
  @Test
  void routeOneForReplay_appliedDispatch_persistedLogRowCarriesEveryDispatcherField() {
    ConfidenceGate.ScoredClassification s =
        scored(Destination.PREFERENCE, "0.9", RoutingDecision.AUTO_ROUTED);
    ObjectNode resultPayload = JsonNodeFactory.instance.objectNode();
    resultPayload.put("ackId", "abc-123");
    DispatchResult applied = DispatchResult.applied("queued the update", resultPayload);
    DestinationDispatcher d = stubDispatcher(Destination.PREFERENCE, applied);
    when(registry.resolve(Destination.PREFERENCE)).thenReturn(d);

    router.routeOneForReplay(feedbackId, s);

    ArgumentCaptor<RoutingLogEntry> cap = ArgumentCaptor.forClass(RoutingLogEntry.class);
    verify(routingLogRepository, times(2)).save(cap.capture());
    RoutingLogEntry terminal = cap.getAllValues().get(1);
    // setStatus — kills VoidMethodCall removal of setStatus.
    assertThat(terminal.getStatus()).isEqualTo(RoutingStatus.APPLIED);
    // setActionTaken — kills VoidMethodCall removal of setActionTaken.
    assertThat(terminal.getActionTaken()).isEqualTo("queued the update");
    // setDestinationResultJson — kills its VoidMethodCall removal.
    assertThat(terminal.getDestinationResultJson()).isSameAs(resultPayload);
    // setFailureKind — null on success; kills its VoidMethodCall removal because the PENDING
    // insert would otherwise leak a stale value... we set up no stale state but the assertion
    // pins the absence.
    assertThat(terminal.getFailureKind()).isNull();
    // setFailureMessage — null on success.
    assertThat(terminal.getFailureMessage()).isNull();
    // setCompletedAt — must match the clock instant; kills its VoidMethodCall removal.
    assertThat(terminal.getCompletedAt()).isEqualTo(Instant.parse("2026-05-10T00:00:00Z"));
  }

  /**
   * The routeOne (routeAll) path had two SURVIVED VoidMethodCallMutator on setDestinationResultJson
   * (L291) + setCompletedAt (L294). The other four setters in the same method had been killed by
   * other tests; these two were specifically untested. Replicates the same end-state assertion as
   * above for routeAll.
   */
  @Test
  void routeAll_appliedDispatch_persistedLogRowCarriesDestinationResultAndCompletedAt() {
    ConfidenceGate.ScoredClassification s =
        scored(Destination.PREFERENCE, "0.9", RoutingDecision.AUTO_ROUTED);
    ObjectNode resultPayload = JsonNodeFactory.instance.objectNode();
    resultPayload.put("ackId", "abc-123");
    DispatchResult applied = DispatchResult.applied("queued the update", resultPayload);
    DestinationDispatcher d = stubDispatcher(Destination.PREFERENCE, applied);
    when(registry.resolve(Destination.PREFERENCE)).thenReturn(d);

    router.routeAll(feedbackId, List.of(s));

    ArgumentCaptor<RoutingLogEntry> cap = ArgumentCaptor.forClass(RoutingLogEntry.class);
    verify(routingLogRepository, times(2)).save(cap.capture());
    RoutingLogEntry terminal = cap.getAllValues().get(1);
    assertThat(terminal.getDestinationResultJson()).isSameAs(resultPayload);
    assertThat(terminal.getCompletedAt()).isEqualTo(Instant.parse("2026-05-10T00:00:00Z"));
  }

  // ---------------- mutation-kill: truncate / stripSecrets / toDto ----------------

  /**
   * truncate(s) at boundary length 512 must return the original string unchanged (the conditional
   * is {@code <= MESSAGE_MAX_LEN}). ConditionalsBoundaryMutator flips it to {@code <}, which would
   * unnecessarily substring a length-512 message. We use {@code isSameAs} to prove no substring
   * call was made.
   */
  @Test
  void truncate_exactly512Chars_returnsOriginalIdentity() {
    String exactly512 = "z".repeat(512);
    ConfidenceGate.ScoredClassification s =
        scored(Destination.PREFERENCE, "0.9", RoutingDecision.AUTO_ROUTED);
    DestinationDispatcher d =
        stubDispatcher(
            Destination.PREFERENCE,
            DispatchResult.applied(exactly512, JsonNodeFactory.instance.objectNode()));
    when(registry.resolve(Destination.PREFERENCE)).thenReturn(d);

    router.routeAll(feedbackId, List.of(s));

    ArgumentCaptor<RoutingLogEntry> cap = ArgumentCaptor.forClass(RoutingLogEntry.class);
    verify(routingLogRepository, times(2)).save(cap.capture());
    String stamped = cap.getAllValues().get(1).getActionTaken();
    // Identity assertion — if the boundary flipped to `<`, substring(0,512) would yield an equal
    // but distinct String instance. We assert both: same content AND same identity.
    assertThat(stamped).isEqualTo(exactly512);
    assertThat(stamped).isSameAs(exactly512);
  }

  /**
   * Mutation-kill for routeOneCapturing L234/L235 (setFailureKind / setFailureMessage). When the
   * replay dispatcher returns FAILED, the persisted log row must carry the dispatcher's failureKind
   * + failureMessage — removing either setter is invisible to RouteReplayResult-only assertions but
   * visible here.
   */
  @Test
  void routeOneForReplay_dispatcherFails_persistedRowCarriesFailureKindAndMessage() {
    ConfidenceGate.ScoredClassification s =
        scored(Destination.PROVISIONS, "0.9", RoutingDecision.AUTO_ROUTED);
    DestinationDispatcher d = Mockito.mock(DestinationDispatcher.class);
    when(d.destination()).thenReturn(Destination.PROVISIONS);
    when(d.dispatch(any(DispatchContext.class)))
        .thenThrow(new RuntimeException("boom-business-message"));
    when(registry.resolve(Destination.PROVISIONS)).thenReturn(d);

    router.routeOneForReplay(feedbackId, s);

    ArgumentCaptor<RoutingLogEntry> cap = ArgumentCaptor.forClass(RoutingLogEntry.class);
    verify(routingLogRepository, times(2)).save(cap.capture());
    RoutingLogEntry terminal = cap.getAllValues().get(1);
    assertThat(terminal.getStatus()).isEqualTo(RoutingStatus.FAILED);
    // Kill setFailureKind VoidMethodCall removal.
    assertThat(terminal.getFailureKind()).isEqualTo(RoutingFailureKind.UNKNOWN);
    // Kill setFailureMessage VoidMethodCall removal.
    assertThat(terminal.getFailureMessage()).contains("boom-business-message");
  }

  /**
   * The "no-secret" path of stripSecrets — input contains nothing in SECRET_PATTERNS, so the method
   * returns the original string. EmptyObjectReturnValsMutator would replace with "" — this test
   * pins the message content + identity.
   */
  @Test
  void failureMessage_withNoSecrets_passesThroughUnmodified() {
    String benign = "destination rejected the payload";
    ConfidenceGate.ScoredClassification s =
        scored(Destination.PREFERENCE, "0.9", RoutingDecision.AUTO_ROUTED);
    DestinationDispatcher d = Mockito.mock(DestinationDispatcher.class);
    when(d.destination()).thenReturn(Destination.PREFERENCE);
    when(d.dispatch(any(DispatchContext.class))).thenThrow(new IllegalStateException(benign));
    when(registry.resolve(Destination.PREFERENCE)).thenReturn(d);

    router.routeAll(feedbackId, List.of(s));

    ArgumentCaptor<RoutingLogEntry> cap = ArgumentCaptor.forClass(RoutingLogEntry.class);
    verify(routingLogRepository, times(2)).save(cap.capture());
    String stamped = cap.getAllValues().get(1).getFailureMessage();
    // Kills EmptyObjectReturnVals on stripSecrets — empty would not contain the original message.
    assertThat(stamped).isEqualTo(benign);
    assertThat(stamped).contains("rejected");
  }

  /**
   * The "short-message" path of truncate — under 512 chars, must return the original (not "").
   * Kills EmptyObjectReturnValsMutator on truncate.
   */
  @Test
  void truncate_shortMessage_returnsOriginal() {
    String shortMsg = "short";
    ConfidenceGate.ScoredClassification s =
        scored(Destination.PREFERENCE, "0.9", RoutingDecision.AUTO_ROUTED);
    DestinationDispatcher d =
        stubDispatcher(
            Destination.PREFERENCE,
            DispatchResult.applied(shortMsg, JsonNodeFactory.instance.objectNode()));
    when(registry.resolve(Destination.PREFERENCE)).thenReturn(d);

    router.routeAll(feedbackId, List.of(s));

    ArgumentCaptor<RoutingLogEntry> cap = ArgumentCaptor.forClass(RoutingLogEntry.class);
    verify(routingLogRepository, times(2)).save(cap.capture());
    String stamped = cap.getAllValues().get(1).getActionTaken();
    assertThat(stamped).isEqualTo(shortMsg).isNotEmpty();
  }

  /**
   * toDto(UiContextDocument) on a non-null doc returns a fully-populated UiContextDto. The
   * NullReturnValsMutator survives if dispatch never reads from ctx.uiContext(); we pin every field
   * through the DispatchContext captured by the dispatcher.
   */
  @Test
  void routeAll_dispatchContextCarriesPopulatedUiContextDto() {
    UUID recipeId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    entry.setUiContext(
        new com.example.mealprep.feedback.domain.document.UiContextDocument(
            Screen.RECIPE_DETAIL,
            recipeId,
            5,
            UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"),
            UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc"),
            null));
    ConfidenceGate.ScoredClassification s =
        scored(Destination.PREFERENCE, "0.9", RoutingDecision.AUTO_ROUTED);
    DestinationDispatcher d = stubDispatcher(Destination.PREFERENCE, applied());
    when(registry.resolve(Destination.PREFERENCE)).thenReturn(d);

    router.routeAll(feedbackId, List.of(s));

    ArgumentCaptor<DispatchContext> cap = ArgumentCaptor.forClass(DispatchContext.class);
    verify(d).dispatch(cap.capture());
    com.example.mealprep.feedback.api.dto.UiContextDto ui = cap.getValue().uiContext();
    // Kills NullReturnValsMutator on toDto — must return non-null populated dto.
    assertThat(ui).isNotNull();
    assertThat(ui.screen()).isEqualTo(Screen.RECIPE_DETAIL);
    assertThat(ui.recipeId()).isEqualTo(recipeId);
    assertThat(ui.recipeVersion()).isEqualTo(5);
    assertThat(ui.mealSlotId()).isEqualTo(UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"));
    assertThat(ui.planId()).isEqualTo(UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc"));
  }

  // ---------------- helpers ----------------

  private static DispatchResult applied() {
    ObjectNode payload = JsonNodeFactory.instance.objectNode();
    payload.put("ok", true);
    return DispatchResult.applied("applied", payload);
  }

  private static DestinationDispatcher stubDispatcher(Destination dest, DispatchResult result) {
    DestinationDispatcher d = Mockito.mock(DestinationDispatcher.class);
    when(d.destination()).thenReturn(dest);
    when(d.dispatch(any(DispatchContext.class))).thenReturn(result);
    return d;
  }

  private static ConfidenceGate.ScoredClassification scored(
      Destination dest, String confidence, RoutingDecision decision) {
    ObjectNode payload = JsonNodeFactory.instance.objectNode();
    payload.put("note", dest.name());
    ClassificationOutput co =
        new ClassificationOutput(dest, new BigDecimal(confidence), "snippet " + dest, payload);
    return new ConfidenceGate.ScoredClassification(co, decision);
  }

  /** Inline TransactionTemplate that runs callbacks synchronously without a real PTM. */
  private static TransactionTemplate inlineRequiresNewTemplate() {
    PlatformTransactionManager noop =
        new PlatformTransactionManager() {
          @Override
          public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
          }

          @Override
          public void commit(TransactionStatus status) {}

          @Override
          public void rollback(TransactionStatus status) {}
        };
    TransactionTemplate t =
        new TransactionTemplate(noop) {
          @Override
          public <T> T execute(TransactionCallback<T> action) {
            return action.doInTransaction(new SimpleTransactionStatus());
          }
        };
    t.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    return t;
  }
}
