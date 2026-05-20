package com.example.mealprep.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.mealprep.feedback.api.dto.CorrectionRequest;
import com.example.mealprep.feedback.api.dto.Screen;
import com.example.mealprep.feedback.api.dto.SubmitFeedbackResponse;
import com.example.mealprep.feedback.api.mapper.ClarificationQueryMapper;
import com.example.mealprep.feedback.api.mapper.FeedbackEntryMapper;
import com.example.mealprep.feedback.api.mapper.MisclassificationCorrectionMapper;
import com.example.mealprep.feedback.api.mapper.RoutingLogMapper;
import com.example.mealprep.feedback.domain.document.UiContextDocument;
import com.example.mealprep.feedback.domain.entity.CorrectionReplayStatus;
import com.example.mealprep.feedback.domain.entity.FeedbackEntry;
import com.example.mealprep.feedback.domain.entity.MisclassificationCorrection;
import com.example.mealprep.feedback.domain.entity.RoutingFailureKind;
import com.example.mealprep.feedback.domain.entity.RoutingLogEntry;
import com.example.mealprep.feedback.domain.entity.RoutingStatus;
import com.example.mealprep.feedback.domain.entity.SubmissionStatus;
import com.example.mealprep.feedback.domain.repository.ClarificationQueryRepository;
import com.example.mealprep.feedback.domain.repository.FeedbackEntryRepository;
import com.example.mealprep.feedback.domain.repository.MisclassificationCorrectionRepository;
import com.example.mealprep.feedback.domain.repository.RoutingLogRepository;
import com.example.mealprep.feedback.domain.service.FeedbackUpdateService;
import com.example.mealprep.feedback.domain.service.internal.ClarificationExpirer;
import com.example.mealprep.feedback.domain.service.internal.ConfidenceGate;
import com.example.mealprep.feedback.domain.service.internal.CorrectionReplayer;
import com.example.mealprep.feedback.domain.service.internal.FeedbackRouter;
import com.example.mealprep.feedback.event.FeedbackMisclassificationCorrectedEvent;
import com.example.mealprep.feedback.exception.FeedbackEntryNotFoundException;
import com.example.mealprep.feedback.exception.InvalidCorrectionTargetException;
import com.example.mealprep.feedback.exception.RoutingDecisionNotFoundException;
import com.example.mealprep.feedback.spi.Destination;
import com.example.mealprep.feedback.spi.NutritionFeedbackReverter;
import com.example.mealprep.feedback.spi.PreferenceFeedbackReverter;
import com.example.mealprep.feedback.spi.ProvisionsFeedbackReverter;
import com.example.mealprep.feedback.spi.RecipeFeedbackReverter;
import com.example.mealprep.feedback.testdata.FeedbackTestData;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Unit tests for {@code FeedbackServiceImpl.correctMisclassification} (feedback-01f Flow 4). Pure
 * Mockito; the package-private impl is reflectively constructed.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CorrectMisclassificationServiceTest {

  @Mock private FeedbackEntryRepository feedbackEntryRepository;
  @Mock private RoutingLogRepository routingLogRepository;
  @Mock private ClarificationQueryRepository clarificationQueryRepository;
  @Mock private MisclassificationCorrectionRepository misclassificationCorrectionRepository;
  @Mock private FeedbackEntryMapper entryMapper;
  @Mock private RoutingLogMapper routingLogMapper;
  @Mock private ClarificationQueryMapper clarificationQueryMapper;
  @Mock private MisclassificationCorrectionMapper misclassificationCorrectionMapper;
  @Mock private ClarificationExpirer clarificationExpirer;
  @Mock private CorrectionReplayer correctionReplayer;
  @Mock private PreferenceFeedbackReverter preferenceReverter;
  @Mock private NutritionFeedbackReverter nutritionReverter;
  @Mock private ProvisionsFeedbackReverter provisionsReverter;
  @Mock private RecipeFeedbackReverter recipeReverter;
  @Mock private ApplicationEventPublisher eventPublisher;

  private final Clock clock = Clock.fixed(Instant.parse("2026-05-10T00:00:00Z"), ZoneOffset.UTC);

  private FeedbackUpdateService service() {
    try {
      Class<?> implClass =
          Class.forName("com.example.mealprep.feedback.domain.service.FeedbackServiceImpl");
      var ctor =
          implClass.getDeclaredConstructor(
              FeedbackEntryRepository.class,
              RoutingLogRepository.class,
              ClarificationQueryRepository.class,
              MisclassificationCorrectionRepository.class,
              FeedbackEntryMapper.class,
              RoutingLogMapper.class,
              ClarificationQueryMapper.class,
              MisclassificationCorrectionMapper.class,
              ClarificationExpirer.class,
              CorrectionReplayer.class,
              PreferenceFeedbackReverter.class,
              NutritionFeedbackReverter.class,
              ProvisionsFeedbackReverter.class,
              RecipeFeedbackReverter.class,
              ApplicationEventPublisher.class,
              Clock.class);
      ctor.setAccessible(true);
      return (FeedbackUpdateService)
          ctor.newInstance(
              feedbackEntryRepository,
              routingLogRepository,
              clarificationQueryRepository,
              misclassificationCorrectionRepository,
              entryMapper,
              routingLogMapper,
              clarificationQueryMapper,
              misclassificationCorrectionMapper,
              clarificationExpirer,
              correctionReplayer,
              preferenceReverter,
              nutritionReverter,
              provisionsReverter,
              recipeReverter,
              eventPublisher,
              clock);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }

  private FeedbackEntry entryWithRouting(UUID userId, UUID feedbackId, RoutingLogEntry... rows) {
    FeedbackEntry entry =
        FeedbackEntry.builder()
            .id(feedbackId)
            .userId(userId)
            .text("the salt was too much")
            .uiContext(
                new UiContextDocument(Screen.RECIPE_DETAIL, UUID.randomUUID(), 1, null, null, null))
            .submissionStatus(SubmissionStatus.ROUTED)
            .classificationAttempts(1)
            .traceId(UUID.randomUUID())
            .routingLog(new ArrayList<>(List.of(rows)))
            .build();
    for (RoutingLogEntry r : rows) {
      r.setFeedbackEntry(entry);
    }
    return entry;
  }

  private RoutingLogEntry originalRow(UUID id, Destination dest, RoutingStatus status) {
    RoutingLogEntry r = FeedbackTestData.routingLogEntry(null, dest, status);
    r.setId(id);
    r.setConfidence(new BigDecimal("0.620"));
    return r;
  }

  @Test
  void happyPath_marksCorrectedAway_writesGroundTruth_replays_publishesEvent() {
    UUID userId = UUID.randomUUID();
    UUID feedbackId = UUID.randomUUID();
    UUID routingId = UUID.randomUUID();
    UUID newRoutingId = UUID.randomUUID();

    RoutingLogEntry original =
        originalRow(routingId, Destination.PREFERENCE, RoutingStatus.APPLIED);
    FeedbackEntry entry = entryWithRouting(userId, feedbackId, original);

    // Refreshed view: original now CORRECTED_AWAY + a new APPLIED replay row.
    RoutingLogEntry replayRow =
        FeedbackTestData.routingLogEntry(null, Destination.PROVISIONS, RoutingStatus.APPLIED);
    replayRow.setId(newRoutingId);
    RoutingLogEntry originalAfter =
        originalRow(routingId, Destination.PREFERENCE, RoutingStatus.CORRECTED_AWAY);
    FeedbackEntry refreshed = entryWithRouting(userId, feedbackId, originalAfter, replayRow);

    when(feedbackEntryRepository.findWithRoutingByIdAndUserId(feedbackId, userId))
        .thenReturn(Optional.of(entry));
    // Post-replay routing log re-read: original (CORRECTED_AWAY) + new APPLIED replay row.
    when(routingLogRepository.findByFeedbackEntryIdOrderByRoutedAtAsc(feedbackId))
        .thenReturn(List.of(originalAfter, replayRow));

    CorrectionRequest req =
        FeedbackTestData.correctionRequest(Destination.PROVISIONS, "standing pref");
    ConfidenceGate.ScoredClassification scored =
        new ConfidenceGate.ScoredClassification(
            FeedbackTestData.classificationOutput(
                Destination.PROVISIONS, "1.0", "txt", FeedbackTestData.samplePayload()),
            com.example.mealprep.feedback.domain.entity.RoutingDecision.AUTO_ROUTED);
    when(correctionReplayer.buildSynthetic(any(), eq(req))).thenReturn(scored);
    when(correctionReplayer.replay(any(), eq(scored)))
        .thenReturn(
            new FeedbackRouter.RouteReplayResult(newRoutingId, RoutingStatus.APPLIED, null));
    when(correctionReplayer.mapReplayStatus(RoutingStatus.APPLIED, null))
        .thenReturn(CorrectionReplayStatus.APPLIED);
    when(feedbackEntryRepository.updateSubmissionStatus(eq(feedbackId), any())).thenReturn(1);
    when(routingLogMapper.toDtos(any())).thenReturn(List.of());

    SubmitFeedbackResponse resp =
        service().correctMisclassification(userId, feedbackId, routingId, req);

    assertThat(original.getStatus()).isEqualTo(RoutingStatus.CORRECTED_AWAY);
    assertThat(original.getSupersededById()).isEqualTo(newRoutingId);
    assertThat(resp.submissionStatus()).isEqualTo(SubmissionStatus.CORRECTED);

    // Preference reverter invoked (original dest), others not.
    verify(preferenceReverter).revert(any());
    verify(recipeReverter, never()).revert(any());

    ArgumentCaptor<MisclassificationCorrection> corrCap =
        ArgumentCaptor.forClass(MisclassificationCorrection.class);
    verify(misclassificationCorrectionRepository, org.mockito.Mockito.atLeastOnce())
        .save(corrCap.capture());
    MisclassificationCorrection saved = corrCap.getValue();
    assertThat(saved.getOriginalDestination()).isEqualTo(Destination.PREFERENCE);
    assertThat(saved.getOriginalConfidence()).isEqualByComparingTo(new BigDecimal("0.620"));
    assertThat(saved.getCorrectedDestination()).isEqualTo(Destination.PROVISIONS);
    assertThat(saved.getActorUserId()).isEqualTo(userId);
    assertThat(saved.getReplayRoutingId()).isEqualTo(newRoutingId);
    assertThat(saved.getReplayStatus()).isEqualTo(CorrectionReplayStatus.APPLIED);

    ArgumentCaptor<FeedbackMisclassificationCorrectedEvent> evCap =
        ArgumentCaptor.forClass(FeedbackMisclassificationCorrectedEvent.class);
    verify(eventPublisher).publishEvent(evCap.capture());
    FeedbackMisclassificationCorrectedEvent ev = evCap.getValue();
    assertThat(ev.feedbackId()).isEqualTo(feedbackId);
    assertThat(ev.originalRoutingId()).isEqualTo(routingId);
    assertThat(ev.replayRoutingId()).isEqualTo(newRoutingId);
    assertThat(ev.originalDestination()).isEqualTo(Destination.PREFERENCE);
    assertThat(ev.correctedDestination()).isEqualTo(Destination.PROVISIONS);
  }

  @Test
  void revertThrows_correctionStillProceeds() {
    UUID userId = UUID.randomUUID();
    UUID feedbackId = UUID.randomUUID();
    UUID routingId = UUID.randomUUID();
    UUID newRoutingId = UUID.randomUUID();
    RoutingLogEntry original =
        originalRow(routingId, Destination.PREFERENCE, RoutingStatus.APPLIED);
    FeedbackEntry entry = entryWithRouting(userId, feedbackId, original);
    when(feedbackEntryRepository.findWithRoutingByIdAndUserId(feedbackId, userId))
        .thenReturn(Optional.of(entry));
    doThrow(new RuntimeException("revert boom")).when(preferenceReverter).revert(any());

    CorrectionRequest req = FeedbackTestData.correctionRequest(Destination.NUTRITION, null);
    ConfidenceGate.ScoredClassification scored =
        new ConfidenceGate.ScoredClassification(
            FeedbackTestData.classificationOutput(
                Destination.NUTRITION, "1.0", "t", FeedbackTestData.samplePayload()),
            com.example.mealprep.feedback.domain.entity.RoutingDecision.AUTO_ROUTED);
    when(correctionReplayer.buildSynthetic(any(), any())).thenReturn(scored);
    when(correctionReplayer.replay(any(), any()))
        .thenReturn(
            new FeedbackRouter.RouteReplayResult(newRoutingId, RoutingStatus.APPLIED, null));
    when(correctionReplayer.mapReplayStatus(any(), any()))
        .thenReturn(CorrectionReplayStatus.APPLIED);
    when(feedbackEntryRepository.updateSubmissionStatus(eq(feedbackId), any())).thenReturn(1);
    when(routingLogMapper.toDtos(any())).thenReturn(List.of());

    // Does not throw despite reverter failure.
    service().correctMisclassification(userId, feedbackId, routingId, req);
    verify(misclassificationCorrectionRepository, org.mockito.Mockito.atLeastOnce()).save(any());
  }

  @Test
  void replayDestinationRejected_mapsToDestinationRejected() {
    UUID userId = UUID.randomUUID();
    UUID feedbackId = UUID.randomUUID();
    UUID routingId = UUID.randomUUID();
    RoutingLogEntry original =
        originalRow(routingId, Destination.PREFERENCE, RoutingStatus.APPLIED);
    FeedbackEntry entry = entryWithRouting(userId, feedbackId, original);
    when(feedbackEntryRepository.findWithRoutingByIdAndUserId(feedbackId, userId))
        .thenReturn(Optional.of(entry));
    CorrectionRequest req = FeedbackTestData.correctionRequest(Destination.NUTRITION, null);
    ConfidenceGate.ScoredClassification scored =
        new ConfidenceGate.ScoredClassification(
            FeedbackTestData.classificationOutput(
                Destination.NUTRITION, "1.0", "t", FeedbackTestData.samplePayload()),
            com.example.mealprep.feedback.domain.entity.RoutingDecision.AUTO_ROUTED);
    when(correctionReplayer.buildSynthetic(any(), any())).thenReturn(scored);
    when(correctionReplayer.replay(any(), any()))
        .thenReturn(
            new FeedbackRouter.RouteReplayResult(
                UUID.randomUUID(),
                RoutingStatus.FAILED,
                RoutingFailureKind.DESTINATION_VALIDATION));
    when(correctionReplayer.mapReplayStatus(
            RoutingStatus.FAILED, RoutingFailureKind.DESTINATION_VALIDATION))
        .thenReturn(CorrectionReplayStatus.DESTINATION_REJECTED);
    when(feedbackEntryRepository.updateSubmissionStatus(eq(feedbackId), any())).thenReturn(1);
    when(routingLogMapper.toDtos(any())).thenReturn(List.of());

    service().correctMisclassification(userId, feedbackId, routingId, req);

    ArgumentCaptor<MisclassificationCorrection> cap =
        ArgumentCaptor.forClass(MisclassificationCorrection.class);
    verify(misclassificationCorrectionRepository, org.mockito.Mockito.atLeastOnce())
        .save(cap.capture());
    assertThat(cap.getValue().getReplayStatus())
        .isEqualTo(CorrectionReplayStatus.DESTINATION_REJECTED);
  }

  @Test
  void feedbackNotFound_throws404() {
    UUID userId = UUID.randomUUID();
    UUID feedbackId = UUID.randomUUID();
    when(feedbackEntryRepository.findWithRoutingByIdAndUserId(feedbackId, userId))
        .thenReturn(Optional.empty());
    assertThatThrownBy(
            () ->
                service()
                    .correctMisclassification(
                        userId,
                        feedbackId,
                        UUID.randomUUID(),
                        FeedbackTestData.correctionRequest(Destination.RECIPE, null)))
        .isInstanceOf(FeedbackEntryNotFoundException.class);
  }

  @Test
  void routingNotInEntry_throws404() {
    UUID userId = UUID.randomUUID();
    UUID feedbackId = UUID.randomUUID();
    RoutingLogEntry original =
        originalRow(UUID.randomUUID(), Destination.PREFERENCE, RoutingStatus.APPLIED);
    FeedbackEntry entry = entryWithRouting(userId, feedbackId, original);
    when(feedbackEntryRepository.findWithRoutingByIdAndUserId(feedbackId, userId))
        .thenReturn(Optional.of(entry));
    assertThatThrownBy(
            () ->
                service()
                    .correctMisclassification(
                        userId,
                        feedbackId,
                        UUID.randomUUID(),
                        FeedbackTestData.correctionRequest(Destination.RECIPE, null)))
        .isInstanceOf(RoutingDecisionNotFoundException.class);
  }

  @Test
  void newDestinationEqualsOriginal_throws422() {
    UUID userId = UUID.randomUUID();
    UUID feedbackId = UUID.randomUUID();
    UUID routingId = UUID.randomUUID();
    RoutingLogEntry original =
        originalRow(routingId, Destination.PREFERENCE, RoutingStatus.APPLIED);
    FeedbackEntry entry = entryWithRouting(userId, feedbackId, original);
    when(feedbackEntryRepository.findWithRoutingByIdAndUserId(feedbackId, userId))
        .thenReturn(Optional.of(entry));
    assertThatThrownBy(
            () ->
                service()
                    .correctMisclassification(
                        userId,
                        feedbackId,
                        routingId,
                        FeedbackTestData.correctionRequest(Destination.PREFERENCE, null)))
        .isInstanceOf(InvalidCorrectionTargetException.class)
        .hasMessageContaining("no-op");
  }

  @Test
  void alreadyCorrectedAway_throws422_chainsUnsupported() {
    UUID userId = UUID.randomUUID();
    UUID feedbackId = UUID.randomUUID();
    UUID routingId = UUID.randomUUID();
    RoutingLogEntry original =
        originalRow(routingId, Destination.PREFERENCE, RoutingStatus.CORRECTED_AWAY);
    FeedbackEntry entry = entryWithRouting(userId, feedbackId, original);
    when(feedbackEntryRepository.findWithRoutingByIdAndUserId(feedbackId, userId))
        .thenReturn(Optional.of(entry));
    assertThatThrownBy(
            () ->
                service()
                    .correctMisclassification(
                        userId,
                        feedbackId,
                        routingId,
                        FeedbackTestData.correctionRequest(Destination.NUTRITION, null)))
        .isInstanceOf(InvalidCorrectionTargetException.class)
        .hasMessageContaining("chains");
  }

  @Test
  void correctToRecipe_withNoRecipeAnywhere_throws422() {
    UUID userId = UUID.randomUUID();
    UUID feedbackId = UUID.randomUUID();
    UUID routingId = UUID.randomUUID();
    RoutingLogEntry original =
        originalRow(routingId, Destination.PREFERENCE, RoutingStatus.APPLIED);
    // structured payload without recipeId
    com.fasterxml.jackson.databind.node.ObjectNode noRecipe =
        com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
    noRecipe.put("affectsPlan", true);
    original.setStructuredPayload(noRecipe);
    FeedbackEntry entry = entryWithRouting(userId, feedbackId, original);
    entry.setUiContext(new UiContextDocument(Screen.GENERAL, null, null, null, null, null));
    when(feedbackEntryRepository.findWithRoutingByIdAndUserId(feedbackId, userId))
        .thenReturn(Optional.of(entry));
    assertThatThrownBy(
            () ->
                service()
                    .correctMisclassification(
                        userId,
                        feedbackId,
                        routingId,
                        FeedbackTestData.correctionRequest(Destination.RECIPE, null)))
        .isInstanceOf(InvalidCorrectionTargetException.class)
        .hasMessageContaining("no recipe attached");
  }

  /**
   * Mutation-kill: NegateConditionals SURVIVED on the inner {@code recipeId() != null} short-
   * circuit of {@code hasRecipeIdInContext}. Configure entry.uiContext non-null but with a NULL
   * recipeId AND a payload missing recipeId — original code throws (both terms false). The mutation
   * flipping the inner null-check to {@code == null} would make hasRecipeIdInContext true
   * (uiContext != null && (null == null)) → no throw — observable here.
   */
  @Test
  void correctToRecipe_uiContextWithoutRecipeId_andPayloadEmpty_throws422() {
    UUID userId = UUID.randomUUID();
    UUID feedbackId = UUID.randomUUID();
    UUID routingId = UUID.randomUUID();
    RoutingLogEntry original =
        originalRow(routingId, Destination.PREFERENCE, RoutingStatus.APPLIED);
    com.fasterxml.jackson.databind.node.ObjectNode noRecipe =
        com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
    noRecipe.put("affectsPlan", true);
    original.setStructuredPayload(noRecipe);
    FeedbackEntry entry = entryWithRouting(userId, feedbackId, original);
    // uiContext IS set (non-null) but recipeId is null — distinguishes the two NegateConditionals
    // mutations on lines 266-267.
    entry.setUiContext(new UiContextDocument(Screen.GENERAL, null, null, null, null, null));
    when(feedbackEntryRepository.findWithRoutingByIdAndUserId(feedbackId, userId))
        .thenReturn(Optional.of(entry));
    assertThatThrownBy(
            () ->
                service()
                    .correctMisclassification(
                        userId,
                        feedbackId,
                        routingId,
                        FeedbackTestData.correctionRequest(Destination.RECIPE, null)))
        .isInstanceOf(InvalidCorrectionTargetException.class)
        .hasMessageContaining("no recipe attached");
  }

  /**
   * Mutation-kill: when uiContext.recipeId is null and the payload's "recipeId" field is the empty
   * string (path returns "" → isEmpty true → hasRecipeIdInPayload false), the throw fires. If the
   * {@code !...isEmpty()} mutation flips, hasRecipeIdInPayload becomes true → no throw.
   */
  @Test
  void correctToRecipe_payloadWithEmptyRecipeIdString_andUiContextEmpty_throws422() {
    UUID userId = UUID.randomUUID();
    UUID feedbackId = UUID.randomUUID();
    UUID routingId = UUID.randomUUID();
    RoutingLogEntry original =
        originalRow(routingId, Destination.PREFERENCE, RoutingStatus.APPLIED);
    com.fasterxml.jackson.databind.node.ObjectNode payload =
        com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
    payload.put("recipeId", ""); // present-but-empty: path.asText("") returns "" → isEmpty true.
    original.setStructuredPayload(payload);
    FeedbackEntry entry = entryWithRouting(userId, feedbackId, original);
    entry.setUiContext(new UiContextDocument(Screen.GENERAL, null, null, null, null, null));
    when(feedbackEntryRepository.findWithRoutingByIdAndUserId(feedbackId, userId))
        .thenReturn(Optional.of(entry));
    assertThatThrownBy(
            () ->
                service()
                    .correctMisclassification(
                        userId,
                        feedbackId,
                        routingId,
                        FeedbackTestData.correctionRequest(Destination.RECIPE, null)))
        .isInstanceOf(InvalidCorrectionTargetException.class)
        .hasMessageContaining("no recipe attached");
  }

  /**
   * Mutation-kill: when entry.uiContext is null and payload carries no recipeId, validation throws.
   * This pins the {@code entry.getUiContext() != null} outer short-circuit of L266 — if mutated to
   * {@code == null}, the AND short-circuits past the null-safe recipeId() lookup with a non-null
   * uiContext... but with uiContext literally null, the short-circuit prevents an NPE and the
   * second-term check kicks in. We exercise this null-uiContext branch explicitly.
   */
  @Test
  void correctToRecipe_uiContextNull_andPayloadMissingRecipeId_throws422() {
    UUID userId = UUID.randomUUID();
    UUID feedbackId = UUID.randomUUID();
    UUID routingId = UUID.randomUUID();
    RoutingLogEntry original =
        originalRow(routingId, Destination.PREFERENCE, RoutingStatus.APPLIED);
    com.fasterxml.jackson.databind.node.ObjectNode noRecipe =
        com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
    noRecipe.put("affectsPlan", true);
    original.setStructuredPayload(noRecipe);
    FeedbackEntry entry = entryWithRouting(userId, feedbackId, original);
    entry.setUiContext(null); // explicitly null — covers the L266 outer null check
    when(feedbackEntryRepository.findWithRoutingByIdAndUserId(feedbackId, userId))
        .thenReturn(Optional.of(entry));
    assertThatThrownBy(
            () ->
                service()
                    .correctMisclassification(
                        userId,
                        feedbackId,
                        routingId,
                        FeedbackTestData.correctionRequest(Destination.RECIPE, null)))
        .isInstanceOf(InvalidCorrectionTargetException.class)
        .hasMessageContaining("no recipe attached");
  }

  /**
   * Mutation-kill: VoidMethodCallMutator SURVIVED on {@code
   * original.setCompletedAt(clock.instant())} at L192. The happy-path test asserts status flipped
   * to CORRECTED_AWAY but never reads completedAt. We pin it explicitly.
   */
  @Test
  void happyPath_stampsCompletedAtOnTheCorrectedAwayRow() {
    UUID userId = UUID.randomUUID();
    UUID feedbackId = UUID.randomUUID();
    UUID routingId = UUID.randomUUID();
    UUID newRoutingId = UUID.randomUUID();
    RoutingLogEntry original =
        originalRow(routingId, Destination.PREFERENCE, RoutingStatus.APPLIED);
    FeedbackEntry entry = entryWithRouting(userId, feedbackId, original);
    when(feedbackEntryRepository.findWithRoutingByIdAndUserId(feedbackId, userId))
        .thenReturn(Optional.of(entry));
    when(routingLogRepository.findByFeedbackEntryIdOrderByRoutedAtAsc(feedbackId))
        .thenReturn(List.of(original));
    CorrectionRequest req =
        FeedbackTestData.correctionRequest(Destination.PROVISIONS, "standing pref");
    ConfidenceGate.ScoredClassification scored =
        new ConfidenceGate.ScoredClassification(
            FeedbackTestData.classificationOutput(
                Destination.PROVISIONS, "1.0", "txt", FeedbackTestData.samplePayload()),
            com.example.mealprep.feedback.domain.entity.RoutingDecision.AUTO_ROUTED);
    when(correctionReplayer.buildSynthetic(any(), eq(req))).thenReturn(scored);
    when(correctionReplayer.replay(any(), eq(scored)))
        .thenReturn(
            new FeedbackRouter.RouteReplayResult(newRoutingId, RoutingStatus.APPLIED, null));
    when(correctionReplayer.mapReplayStatus(RoutingStatus.APPLIED, null))
        .thenReturn(CorrectionReplayStatus.APPLIED);
    when(feedbackEntryRepository.updateSubmissionStatus(eq(feedbackId), any())).thenReturn(1);
    when(routingLogMapper.toDtos(any())).thenReturn(List.of());

    service().correctMisclassification(userId, feedbackId, routingId, req);

    // Kills VoidMethodCallMutator on setCompletedAt — the original row's completedAt must be
    // stamped with the test clock's instant.
    assertThat(original.getCompletedAt()).isEqualTo(clock.instant());
  }

  /**
   * Mutation-kill: bestEffortRevert switch-branches at L296/L297 (NUTRITION/PROVISIONS) are
   * NO_COVERAGE because existing tests cover only PREFERENCE. We dispatch one correction whose
   * original destination is NUTRITION and another whose original is PROVISIONS, asserting the
   * matching reverter is invoked.
   */
  @Test
  void bestEffortRevert_originalNutrition_invokesNutritionReverter() {
    runRevertCoverage(Destination.NUTRITION);
    verify(nutritionReverter).revert(any());
    verify(preferenceReverter, never()).revert(any());
    verify(provisionsReverter, never()).revert(any());
    verify(recipeReverter, never()).revert(any());
  }

  @Test
  void bestEffortRevert_originalProvisions_invokesProvisionsReverter() {
    runRevertCoverage(Destination.PROVISIONS);
    verify(provisionsReverter).revert(any());
    verify(preferenceReverter, never()).revert(any());
    verify(nutritionReverter, never()).revert(any());
    verify(recipeReverter, never()).revert(any());
  }

  /**
   * Mutation-kill: bestEffortRevert L298 (RECIPE switch arm) NO_COVERAGE. Existing tests cover
   * PREFERENCE + we just added NUTRITION + PROVISIONS — close the loop on RECIPE.
   */
  @Test
  void bestEffortRevert_originalRecipe_invokesRecipeReverter() {
    UUID userId = UUID.randomUUID();
    UUID feedbackId = UUID.randomUUID();
    UUID routingId = UUID.randomUUID();
    UUID newRoutingId = UUID.randomUUID();
    RoutingLogEntry original = originalRow(routingId, Destination.RECIPE, RoutingStatus.APPLIED);
    FeedbackEntry entry = entryWithRouting(userId, feedbackId, original);
    when(feedbackEntryRepository.findWithRoutingByIdAndUserId(feedbackId, userId))
        .thenReturn(Optional.of(entry));
    when(routingLogRepository.findByFeedbackEntryIdOrderByRoutedAtAsc(feedbackId))
        .thenReturn(List.of(original));
    CorrectionRequest req = FeedbackTestData.correctionRequest(Destination.PREFERENCE, null);
    ConfidenceGate.ScoredClassification scored =
        new ConfidenceGate.ScoredClassification(
            FeedbackTestData.classificationOutput(
                Destination.PREFERENCE, "1.0", "txt", FeedbackTestData.samplePayload()),
            com.example.mealprep.feedback.domain.entity.RoutingDecision.AUTO_ROUTED);
    when(correctionReplayer.buildSynthetic(any(), eq(req))).thenReturn(scored);
    when(correctionReplayer.replay(any(), eq(scored)))
        .thenReturn(
            new FeedbackRouter.RouteReplayResult(newRoutingId, RoutingStatus.APPLIED, null));
    when(correctionReplayer.mapReplayStatus(RoutingStatus.APPLIED, null))
        .thenReturn(CorrectionReplayStatus.APPLIED);
    when(feedbackEntryRepository.updateSubmissionStatus(eq(feedbackId), any())).thenReturn(1);
    when(routingLogMapper.toDtos(any())).thenReturn(List.of());

    service().correctMisclassification(userId, feedbackId, routingId, req);

    verify(recipeReverter).revert(any());
    verify(preferenceReverter, never()).revert(any());
    verify(nutritionReverter, never()).revert(any());
    verify(provisionsReverter, never()).revert(any());
  }

  private void runRevertCoverage(Destination originalDest) {
    UUID userId = UUID.randomUUID();
    UUID feedbackId = UUID.randomUUID();
    UUID routingId = UUID.randomUUID();
    UUID newRoutingId = UUID.randomUUID();
    RoutingLogEntry original = originalRow(routingId, originalDest, RoutingStatus.APPLIED);
    FeedbackEntry entry = entryWithRouting(userId, feedbackId, original);
    when(feedbackEntryRepository.findWithRoutingByIdAndUserId(feedbackId, userId))
        .thenReturn(Optional.of(entry));
    when(routingLogRepository.findByFeedbackEntryIdOrderByRoutedAtAsc(feedbackId))
        .thenReturn(List.of(original));
    // Correct to PREFERENCE (or RECIPE if original is preference) — different destination required.
    Destination newDest =
        originalDest == Destination.PREFERENCE ? Destination.PROVISIONS : Destination.PREFERENCE;
    CorrectionRequest req = FeedbackTestData.correctionRequest(newDest, null);
    ConfidenceGate.ScoredClassification scored =
        new ConfidenceGate.ScoredClassification(
            FeedbackTestData.classificationOutput(
                newDest, "1.0", "txt", FeedbackTestData.samplePayload()),
            com.example.mealprep.feedback.domain.entity.RoutingDecision.AUTO_ROUTED);
    when(correctionReplayer.buildSynthetic(any(), eq(req))).thenReturn(scored);
    when(correctionReplayer.replay(any(), eq(scored)))
        .thenReturn(
            new FeedbackRouter.RouteReplayResult(newRoutingId, RoutingStatus.APPLIED, null));
    when(correctionReplayer.mapReplayStatus(RoutingStatus.APPLIED, null))
        .thenReturn(CorrectionReplayStatus.APPLIED);
    when(feedbackEntryRepository.updateSubmissionStatus(eq(feedbackId), any())).thenReturn(1);
    when(routingLogMapper.toDtos(any())).thenReturn(List.of());

    service().correctMisclassification(userId, feedbackId, routingId, req);
  }

  /**
   * Mutation-kill: recomputeSubmissionStatus L316 NullReturnVals SURVIVED — the empty-active
   * branch. When every row is CORRECTED_AWAY (i.e. active list is empty after filtering) the method
   * returns SubmissionStatus.FAILED. Setup: post-replay routing log contains only one
   * CORRECTED_AWAY row (replay row was, e.g., also corrected away or simply missing because the
   * replay failed before the row was written... we approximate by returning only the original
   * CORRECTED_AWAY).
   */
  @Test
  void recomputeSubmissionStatus_onlyCorrectedAwayRows_yieldsFailed() {
    UUID userId = UUID.randomUUID();
    UUID feedbackId = UUID.randomUUID();
    UUID routingId = UUID.randomUUID();
    UUID newRoutingId = UUID.randomUUID();
    RoutingLogEntry original =
        originalRow(routingId, Destination.PREFERENCE, RoutingStatus.APPLIED);
    FeedbackEntry entry = entryWithRouting(userId, feedbackId, original);
    when(feedbackEntryRepository.findWithRoutingByIdAndUserId(feedbackId, userId))
        .thenReturn(Optional.of(entry));
    RoutingLogEntry originalAfter =
        originalRow(routingId, Destination.PREFERENCE, RoutingStatus.CORRECTED_AWAY);
    // No replay row in the post-replay routing log: simulates the rare race where only the
    // CORRECTED_AWAY row remains visible.
    when(routingLogRepository.findByFeedbackEntryIdOrderByRoutedAtAsc(feedbackId))
        .thenReturn(List.of(originalAfter));

    CorrectionRequest req = FeedbackTestData.correctionRequest(Destination.PROVISIONS, null);
    ConfidenceGate.ScoredClassification scored =
        new ConfidenceGate.ScoredClassification(
            FeedbackTestData.classificationOutput(
                Destination.PROVISIONS, "1.0", "txt", FeedbackTestData.samplePayload()),
            com.example.mealprep.feedback.domain.entity.RoutingDecision.AUTO_ROUTED);
    when(correctionReplayer.buildSynthetic(any(), eq(req))).thenReturn(scored);
    when(correctionReplayer.replay(any(), eq(scored)))
        .thenReturn(
            new FeedbackRouter.RouteReplayResult(newRoutingId, RoutingStatus.APPLIED, null));
    when(correctionReplayer.mapReplayStatus(RoutingStatus.APPLIED, null))
        .thenReturn(CorrectionReplayStatus.APPLIED);
    when(feedbackEntryRepository.updateSubmissionStatus(eq(feedbackId), any())).thenReturn(1);
    when(routingLogMapper.toDtos(any())).thenReturn(List.of());

    SubmitFeedbackResponse resp =
        service().correctMisclassification(userId, feedbackId, routingId, req);

    // Kills NullReturnVals on the empty-active SubmissionStatus.FAILED branch.
    assertThat(resp.submissionStatus()).isEqualTo(SubmissionStatus.FAILED);
  }

  /**
   * Mutation-kill: recomputeSubmissionStatus L321 NullReturnVals NO_COVERAGE — the PARTIALLY_FAILED
   * branch (anyFailed && anyNonFailed). Post-replay routing log holds one FAILED and one APPLIED
   * row → active size 2, mixed → PARTIALLY_FAILED.
   */
  @Test
  void recomputeSubmissionStatus_mixedFailedAndApplied_yieldsPartiallyFailed() {
    UUID userId = UUID.randomUUID();
    UUID feedbackId = UUID.randomUUID();
    UUID routingId = UUID.randomUUID();
    UUID newRoutingId = UUID.randomUUID();
    RoutingLogEntry original =
        originalRow(routingId, Destination.PREFERENCE, RoutingStatus.APPLIED);
    FeedbackEntry entry = entryWithRouting(userId, feedbackId, original);
    when(feedbackEntryRepository.findWithRoutingByIdAndUserId(feedbackId, userId))
        .thenReturn(Optional.of(entry));

    RoutingLogEntry appliedSibling =
        FeedbackTestData.routingLogEntry(null, Destination.NUTRITION, RoutingStatus.APPLIED);
    RoutingLogEntry replayFailed =
        FeedbackTestData.routingLogEntry(null, Destination.PROVISIONS, RoutingStatus.FAILED);
    replayFailed.setId(newRoutingId);
    when(routingLogRepository.findByFeedbackEntryIdOrderByRoutedAtAsc(feedbackId))
        .thenReturn(List.of(appliedSibling, replayFailed));

    CorrectionRequest req = FeedbackTestData.correctionRequest(Destination.PROVISIONS, null);
    ConfidenceGate.ScoredClassification scored =
        new ConfidenceGate.ScoredClassification(
            FeedbackTestData.classificationOutput(
                Destination.PROVISIONS, "1.0", "txt", FeedbackTestData.samplePayload()),
            com.example.mealprep.feedback.domain.entity.RoutingDecision.AUTO_ROUTED);
    when(correctionReplayer.buildSynthetic(any(), eq(req))).thenReturn(scored);
    when(correctionReplayer.replay(any(), eq(scored)))
        .thenReturn(
            new FeedbackRouter.RouteReplayResult(
                newRoutingId, RoutingStatus.FAILED, RoutingFailureKind.AI_UNAVAILABLE));
    when(correctionReplayer.mapReplayStatus(
            RoutingStatus.FAILED, RoutingFailureKind.AI_UNAVAILABLE))
        .thenReturn(CorrectionReplayStatus.FAILED);
    when(feedbackEntryRepository.updateSubmissionStatus(eq(feedbackId), any())).thenReturn(1);
    when(routingLogMapper.toDtos(any())).thenReturn(List.of());

    SubmitFeedbackResponse resp =
        service().correctMisclassification(userId, feedbackId, routingId, req);

    // Kills NullReturnVals on the PARTIALLY_FAILED return.
    assertThat(resp.submissionStatus()).isEqualTo(SubmissionStatus.PARTIALLY_FAILED);
  }

  /**
   * Mutation-kill: recomputeSubmissionStatus L321 (FAILED return) NO_COVERAGE — when every active
   * row is FAILED the method returns SubmissionStatus.FAILED. Existing happy-path covers the
   * CORRECTED return; this configures all-failed.
   */
  @Test
  void recomputeSubmissionStatus_allRowsFailed_yieldsFailed() {
    UUID userId = UUID.randomUUID();
    UUID feedbackId = UUID.randomUUID();
    UUID routingId = UUID.randomUUID();
    UUID newRoutingId = UUID.randomUUID();
    RoutingLogEntry original =
        originalRow(routingId, Destination.PREFERENCE, RoutingStatus.APPLIED);
    FeedbackEntry entry = entryWithRouting(userId, feedbackId, original);
    when(feedbackEntryRepository.findWithRoutingByIdAndUserId(feedbackId, userId))
        .thenReturn(Optional.of(entry));

    // Post-replay routing log: only a single FAILED row (original was flipped to CORRECTED_AWAY
    // and is excluded; replay row was FAILED → active.size==1, all FAILED → FAILED.).
    RoutingLogEntry replayFailed =
        FeedbackTestData.routingLogEntry(null, Destination.PROVISIONS, RoutingStatus.FAILED);
    replayFailed.setId(newRoutingId);
    RoutingLogEntry originalAfter =
        originalRow(routingId, Destination.PREFERENCE, RoutingStatus.CORRECTED_AWAY);
    when(routingLogRepository.findByFeedbackEntryIdOrderByRoutedAtAsc(feedbackId))
        .thenReturn(List.of(originalAfter, replayFailed));

    CorrectionRequest req = FeedbackTestData.correctionRequest(Destination.PROVISIONS, null);
    ConfidenceGate.ScoredClassification scored =
        new ConfidenceGate.ScoredClassification(
            FeedbackTestData.classificationOutput(
                Destination.PROVISIONS, "1.0", "txt", FeedbackTestData.samplePayload()),
            com.example.mealprep.feedback.domain.entity.RoutingDecision.AUTO_ROUTED);
    when(correctionReplayer.buildSynthetic(any(), eq(req))).thenReturn(scored);
    when(correctionReplayer.replay(any(), eq(scored)))
        .thenReturn(
            new FeedbackRouter.RouteReplayResult(
                newRoutingId, RoutingStatus.FAILED, RoutingFailureKind.AI_UNAVAILABLE));
    when(correctionReplayer.mapReplayStatus(
            RoutingStatus.FAILED, RoutingFailureKind.AI_UNAVAILABLE))
        .thenReturn(CorrectionReplayStatus.FAILED);
    when(feedbackEntryRepository.updateSubmissionStatus(eq(feedbackId), any())).thenReturn(1);
    when(routingLogMapper.toDtos(any())).thenReturn(List.of());

    SubmitFeedbackResponse resp =
        service().correctMisclassification(userId, feedbackId, routingId, req);

    // Kills the NullReturnVals on the FAILED return + pins the all-failed reconciliation.
    assertThat(resp.submissionStatus()).isEqualTo(SubmissionStatus.FAILED);
  }

  /**
   * Mutation-kill: success path of correcting TO RECIPE when only the payload carries recipeId
   * (uiContext is null / empty). Confirms the {@code hasRecipeIdInPayload} OR-branch lets the
   * correction proceed.
   */
  @Test
  void correctToRecipe_payloadCarriesRecipeId_uiContextEmpty_passesValidation() {
    UUID userId = UUID.randomUUID();
    UUID feedbackId = UUID.randomUUID();
    UUID routingId = UUID.randomUUID();
    UUID newRoutingId = UUID.randomUUID();
    UUID payloadRecipeId = UUID.randomUUID();
    RoutingLogEntry original =
        originalRow(routingId, Destination.PREFERENCE, RoutingStatus.APPLIED);
    com.fasterxml.jackson.databind.node.ObjectNode payload =
        com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
    payload.put("recipeId", payloadRecipeId.toString());
    original.setStructuredPayload(payload);
    FeedbackEntry entry = entryWithRouting(userId, feedbackId, original);
    entry.setUiContext(null);
    when(feedbackEntryRepository.findWithRoutingByIdAndUserId(feedbackId, userId))
        .thenReturn(Optional.of(entry));

    CorrectionRequest req = FeedbackTestData.correctionRequest(Destination.RECIPE, null);
    ConfidenceGate.ScoredClassification scored =
        new ConfidenceGate.ScoredClassification(
            FeedbackTestData.classificationOutput(
                Destination.RECIPE, "1.0", "txt", FeedbackTestData.samplePayload()),
            com.example.mealprep.feedback.domain.entity.RoutingDecision.AUTO_ROUTED);
    when(correctionReplayer.buildSynthetic(any(), eq(req))).thenReturn(scored);
    when(correctionReplayer.replay(any(), eq(scored)))
        .thenReturn(
            new FeedbackRouter.RouteReplayResult(newRoutingId, RoutingStatus.APPLIED, null));
    when(correctionReplayer.mapReplayStatus(RoutingStatus.APPLIED, null))
        .thenReturn(CorrectionReplayStatus.APPLIED);
    when(feedbackEntryRepository.updateSubmissionStatus(eq(feedbackId), any())).thenReturn(1);
    when(routingLogMapper.toDtos(any())).thenReturn(List.of());
    when(routingLogRepository.findByFeedbackEntryIdOrderByRoutedAtAsc(feedbackId))
        .thenReturn(List.of(original));

    // Must NOT throw — kills any mutation that would route this happy-path through the throw.
    SubmitFeedbackResponse resp =
        service().correctMisclassification(userId, feedbackId, routingId, req);
    assertThat(resp).isNotNull();
  }
}
