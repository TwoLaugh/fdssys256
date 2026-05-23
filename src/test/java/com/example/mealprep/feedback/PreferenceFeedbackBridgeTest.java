package com.example.mealprep.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.mealprep.feedback.bridge.PreferenceFeedbackBridgeImpl;
import com.example.mealprep.feedback.domain.entity.BridgeDispatchStatus;
import com.example.mealprep.feedback.domain.entity.FeedbackBridgeIdempotency;
import com.example.mealprep.feedback.domain.repository.FeedbackBridgeIdempotencyRepository;
import com.example.mealprep.feedback.exception.FeedbackBridgeDispatchFailedException;
import com.example.mealprep.feedback.spi.Destination;
import com.example.mealprep.feedback.spi.PreferenceFeedbackBridge;
import com.example.mealprep.feedback.testdata.InlineTransactionTemplate;
import com.example.mealprep.preference.api.dto.ApplyTasteProfileDeltasRequest;
import com.example.mealprep.preference.domain.service.TasteProfileUpdateService;
import com.example.mealprep.preference.exception.InvalidTasteProfileDeltaException;
import com.example.mealprep.preference.exception.TasteProfileBudgetExceededException;
import com.example.mealprep.preference.exception.TasteProfileNotFoundException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * Unit tests for the real PREFERENCE bridge: stubbed-applyDeltas handling (expected v1 FAILED),
 * confidence-floor rejection, idempotency window, and request construction with the AI origin
 * trace.
 */
class PreferenceFeedbackBridgeTest {

  private static final Instant NOW = Instant.parse("2026-05-22T10:00:00Z");

  private TasteProfileUpdateService tasteProfileUpdateService;
  private FeedbackBridgeIdempotencyRepository idempotencyRepository;
  private PreferenceFeedbackBridgeImpl bridge;

  @BeforeEach
  void setUp() {
    tasteProfileUpdateService = Mockito.mock(TasteProfileUpdateService.class);
    idempotencyRepository = Mockito.mock(FeedbackBridgeIdempotencyRepository.class);
    when(idempotencyRepository.findByFeedbackIdAndDestination(any(), any()))
        .thenReturn(Optional.empty());
    when(idempotencyRepository.insertIfAbsent(any(), any(), anyString(), anyString(), any()))
        .thenReturn(1);
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    bridge =
        new PreferenceFeedbackBridgeImpl(
            tasteProfileUpdateService,
            new ObjectMapper(),
            idempotencyRepository,
            new InlineTransactionTemplate(),
            clock);
  }

  @Test
  void invalidDelta_booksFailed_andThrowsDispatchFailed() {
    // preference-01f: applyDeltas now runs the real applier; a delta-validation failure surfaces as
    // a preference domain exception (InvalidTasteProfileDeltaException), which the bridge catches
    // via
    // the PreferenceException base, books FAILED, and rethrows FeedbackBridgeDispatchFailed.
    when(tasteProfileUpdateService.applyDeltas(any(UUID.class), any()))
        .thenThrow(new InvalidTasteProfileDeltaException("unknown fieldPath: bogus"));

    PreferenceFeedbackBridge.Input input = input(new BigDecimal("0.7"));

    assertThatThrownBy(() -> bridge.applyFeedback(input))
        .isInstanceOf(FeedbackBridgeDispatchFailedException.class);

    // applyDeltas was attempted (bridge is wired) and the idempotency row is booked FAILED.
    verify(tasteProfileUpdateService).applyDeltas(eq(input.userId()), any());
    verify(idempotencyRepository)
        .insertIfAbsent(
            any(),
            eq(input.feedbackId()),
            eq("PREFERENCE"),
            eq(BridgeDispatchStatus.FAILED.name()),
            eq(NOW));
  }

  @Test
  void budgetExceeded_booksFailed_andThrowsDispatchFailed() {
    when(tasteProfileUpdateService.applyDeltas(any(UUID.class), any()))
        .thenThrow(new TasteProfileBudgetExceededException("over budget"));

    PreferenceFeedbackBridge.Input input = input(new BigDecimal("0.8"));

    assertThatThrownBy(() -> bridge.applyFeedback(input))
        .isInstanceOf(FeedbackBridgeDispatchFailedException.class);
    verify(idempotencyRepository)
        .insertIfAbsent(
            any(),
            eq(input.feedbackId()),
            eq("PREFERENCE"),
            eq(BridgeDispatchStatus.FAILED.name()),
            eq(NOW));
  }

  @Test
  void missingProfile_booksFailed_andThrowsDispatchFailed() {
    when(tasteProfileUpdateService.applyDeltas(any(UUID.class), any()))
        .thenThrow(new TasteProfileNotFoundException(UUID.randomUUID()));

    PreferenceFeedbackBridge.Input input = input(new BigDecimal("0.9"));

    assertThatThrownBy(() -> bridge.applyFeedback(input))
        .isInstanceOf(FeedbackBridgeDispatchFailedException.class);
    verify(idempotencyRepository)
        .insertIfAbsent(
            any(),
            eq(input.feedbackId()),
            eq("PREFERENCE"),
            eq(BridgeDispatchStatus.FAILED.name()),
            eq(NOW));
  }

  @Test
  void happyPath_whenApplierSucceeds_booksDispatched() {
    // Simulate the post-deferred-ticket world where applyDeltas no longer throws.
    when(tasteProfileUpdateService.applyDeltas(any(UUID.class), any())).thenReturn(null);

    PreferenceFeedbackBridge.Input input = input(new BigDecimal("0.9"));
    PreferenceFeedbackBridge.Result result = bridge.applyFeedback(input);

    assertThat(result.payload()).containsEntry("status", "DISPATCHED");
    ArgumentCaptor<ApplyTasteProfileDeltasRequest> reqCaptor =
        ArgumentCaptor.forClass(ApplyTasteProfileDeltasRequest.class);
    verify(tasteProfileUpdateService).applyDeltas(eq(input.userId()), reqCaptor.capture());
    assertThat(reqCaptor.getValue().feedbackRangeStart())
        .isEqualTo("feedback-" + input.feedbackId());
    verify(idempotencyRepository)
        .insertIfAbsent(
            any(),
            eq(input.feedbackId()),
            eq("PREFERENCE"),
            eq(BridgeDispatchStatus.DISPATCHED.name()),
            eq(NOW));
  }

  @Test
  void belowConfidenceFloor_booksRejected_andSkipsDestinationCall() {
    PreferenceFeedbackBridge.Input input = input(new BigDecimal("0.4"));

    PreferenceFeedbackBridge.Result result = bridge.applyFeedback(input);

    assertThat(result.payload()).containsEntry("status", "REJECTED_LOW_CONFIDENCE");
    verify(tasteProfileUpdateService, never()).applyDeltas(any(), any());
    verify(idempotencyRepository)
        .insertIfAbsent(
            any(),
            eq(input.feedbackId()),
            eq("PREFERENCE"),
            eq(BridgeDispatchStatus.REJECTED_LOW_CONFIDENCE.name()),
            eq(NOW));
  }

  @Test
  void withinIdempotencyWindow_isNoOp() {
    UUID feedbackId = UUID.randomUUID();
    FeedbackBridgeIdempotency recent =
        FeedbackBridgeIdempotency.builder()
            .id(UUID.randomUUID())
            .feedbackId(feedbackId)
            .destination(Destination.PREFERENCE)
            .status(BridgeDispatchStatus.DISPATCHED)
            .dispatchedAt(NOW.minusSeconds(60)) // 1 minute ago — inside the 5-minute window
            .build();
    when(idempotencyRepository.findByFeedbackIdAndDestination(feedbackId, Destination.PREFERENCE))
        .thenReturn(Optional.of(recent));

    PreferenceFeedbackBridge.Result result =
        bridge.applyFeedback(input(feedbackId, new BigDecimal("0.9")));

    assertThat(result.payload()).containsEntry("status", "ALREADY_DISPATCHED");
    verify(tasteProfileUpdateService, never()).applyDeltas(any(), any());
  }

  @Test
  void afterIdempotencyWindowExpiry_reprocesses() {
    UUID feedbackId = UUID.randomUUID();
    FeedbackBridgeIdempotency stale =
        FeedbackBridgeIdempotency.builder()
            .id(UUID.randomUUID())
            .feedbackId(feedbackId)
            .destination(Destination.PREFERENCE)
            .status(BridgeDispatchStatus.FAILED)
            .dispatchedAt(NOW.minusSeconds(6 * 60)) // 6 minutes ago — outside the window
            .build();
    when(idempotencyRepository.findByFeedbackIdAndDestination(feedbackId, Destination.PREFERENCE))
        .thenReturn(Optional.of(stale));
    when(tasteProfileUpdateService.applyDeltas(any(UUID.class), any())).thenReturn(null);

    bridge.applyFeedback(input(feedbackId, new BigDecimal("0.9")));

    verify(tasteProfileUpdateService).applyDeltas(eq(feedbackId), any());
  }

  private PreferenceFeedbackBridge.Input input(BigDecimal confidence) {
    return input(UUID.randomUUID(), confidence);
  }

  private PreferenceFeedbackBridge.Input input(UUID feedbackId, BigDecimal confidence) {
    ObjectNode payload = JsonNodeFactory.instance.objectNode();
    payload.put("trigger", "BATCH");
    return new PreferenceFeedbackBridge.Input(
        feedbackId, feedbackId, confidence, "no more coriander", UUID.randomUUID(), payload);
  }
}
