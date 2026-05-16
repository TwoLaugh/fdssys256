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
