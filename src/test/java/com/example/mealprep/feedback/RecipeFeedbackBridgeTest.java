package com.example.mealprep.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.mealprep.adaptation.api.dto.AdaptationResultDto;
import com.example.mealprep.adaptation.api.dto.FeedbackJobRequest;
import com.example.mealprep.adaptation.domain.enums.AdaptationClassification;
import com.example.mealprep.adaptation.domain.service.AdaptationService;
import com.example.mealprep.feedback.bridge.RecipeFeedbackBridge;
import com.example.mealprep.feedback.domain.entity.BridgeDispatchStatus;
import com.example.mealprep.feedback.domain.repository.FeedbackBridgeIdempotencyRepository;
import com.example.mealprep.feedback.exception.FeedbackBridgeDispatchFailedException;
import com.example.mealprep.feedback.spi.Destination;
import com.example.mealprep.feedback.spi.RecipeFeedbackHandler;
import com.example.mealprep.feedback.testdata.InlineTransactionTemplate;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * Unit tests for the real RECIPE bridge: real end-to-end dispatch to {@code
 * AdaptationService.enqueueFeedbackJob}, requiresApproval mapping, confidence floor, and
 * failure-throws-dispatch-failed.
 */
class RecipeFeedbackBridgeTest {

  private static final Instant NOW = Instant.parse("2026-05-22T10:00:00Z");

  private AdaptationService adaptationService;
  private FeedbackBridgeIdempotencyRepository idempotencyRepository;
  private RecipeFeedbackBridge bridge;

  @BeforeEach
  void setUp() {
    adaptationService = Mockito.mock(AdaptationService.class);
    idempotencyRepository = Mockito.mock(FeedbackBridgeIdempotencyRepository.class);
    when(idempotencyRepository.findByFeedbackIdAndDestination(any(), any()))
        .thenReturn(Optional.empty());
    when(idempotencyRepository.insertIfAbsent(any(), any(), anyString(), anyString(), any()))
        .thenReturn(1);
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    bridge =
        new RecipeFeedbackBridge(
            adaptationService, idempotencyRepository, new InlineTransactionTemplate(), clock);
  }

  @Test
  void happyPath_callsEnqueueFeedbackJob_booksDispatched() {
    UUID recipeId = UUID.randomUUID();
    when(adaptationService.enqueueFeedbackJob(any(FeedbackJobRequest.class)))
        .thenReturn(result(recipeId, false));

    RecipeFeedbackHandler.Input input = input(recipeId, new BigDecimal("0.92"));
    RecipeFeedbackHandler.Result result = bridge.handleRecipeFeedback(input);

    assertThat(result.requiresApproval()).isFalse();
    ArgumentCaptor<FeedbackJobRequest> reqCaptor =
        ArgumentCaptor.forClass(FeedbackJobRequest.class);
    verify(adaptationService).enqueueFeedbackJob(reqCaptor.capture());
    assertThat(reqCaptor.getValue().recipeId()).isEqualTo(recipeId);
    assertThat(reqCaptor.getValue().feedbackId()).isEqualTo(input.feedbackId());
    assertThat(reqCaptor.getValue().feedbackText()).isEqualTo("needed more salt");
    verify(idempotencyRepository)
        .insertIfAbsent(
            any(),
            eq(input.feedbackId()),
            eq("RECIPE"),
            eq(BridgeDispatchStatus.DISPATCHED.name()),
            eq(NOW));
  }

  @Test
  void requiresApprovalResult_isPropagated() {
    UUID recipeId = UUID.randomUUID();
    when(adaptationService.enqueueFeedbackJob(any(FeedbackJobRequest.class)))
        .thenReturn(result(recipeId, true));

    RecipeFeedbackHandler.Result result =
        bridge.handleRecipeFeedback(input(recipeId, new BigDecimal("0.92")));

    assertThat(result.requiresApproval()).isTrue();
  }

  @Test
  void adaptationFailure_booksFailed_andThrowsDispatchFailed() {
    when(adaptationService.enqueueFeedbackJob(any(FeedbackJobRequest.class)))
        .thenThrow(new IllegalStateException("pipeline down"));

    RecipeFeedbackHandler.Input input = input(UUID.randomUUID(), new BigDecimal("0.92"));

    assertThatThrownBy(() -> bridge.handleRecipeFeedback(input))
        .isInstanceOf(FeedbackBridgeDispatchFailedException.class);
    verify(idempotencyRepository)
        .insertIfAbsent(
            any(),
            eq(input.feedbackId()),
            eq("RECIPE"),
            eq(BridgeDispatchStatus.FAILED.name()),
            eq(NOW));
  }

  @Test
  void belowConfidenceFloor_booksRejected_andSkipsDispatch() {
    RecipeFeedbackHandler.Input input = input(UUID.randomUUID(), new BigDecimal("0.4"));

    RecipeFeedbackHandler.Result result = bridge.handleRecipeFeedback(input);

    assertThat(result.payload()).containsEntry("status", "REJECTED_LOW_CONFIDENCE");
    verifyNoInteractions(adaptationService);
    verify(idempotencyRepository)
        .insertIfAbsent(
            any(),
            eq(input.feedbackId()),
            eq(Destination.RECIPE.name()),
            eq(BridgeDispatchStatus.REJECTED_LOW_CONFIDENCE.name()),
            eq(NOW));
  }

  private RecipeFeedbackHandler.Input input(UUID recipeId, BigDecimal confidence) {
    ObjectNode payload = JsonNodeFactory.instance.objectNode();
    payload.put("recipeId", recipeId.toString());
    payload.put("feedbackType", "FLAVOUR");
    payload.put("affectsPlan", true);
    return new RecipeFeedbackHandler.Input(
        UUID.randomUUID(),
        recipeId,
        UUID.randomUUID(),
        confidence,
        "needed more salt",
        UUID.randomUUID(),
        payload);
  }

  private AdaptationResultDto result(UUID recipeId, boolean requiresApproval) {
    return new AdaptationResultDto(
        UUID.randomUUID(),
        recipeId,
        AdaptationClassification.VERSION,
        Optional.of(UUID.randomUUID()),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        JsonNodeFactory.instance.objectNode(),
        "reasoning",
        "nutrition notes",
        requiresApproval,
        List.of(),
        UUID.randomUUID(),
        new BigDecimal("0.92"));
  }
}
