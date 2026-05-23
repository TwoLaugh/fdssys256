package com.example.mealprep.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.mealprep.feedback.bridge.NutritionFeedbackBridgeImpl;
import com.example.mealprep.feedback.domain.entity.BridgeDispatchStatus;
import com.example.mealprep.feedback.domain.repository.FeedbackBridgeIdempotencyRepository;
import com.example.mealprep.feedback.exception.FeedbackBridgeDispatchFailedException;
import com.example.mealprep.feedback.spi.Destination;
import com.example.mealprep.feedback.spi.NutritionFeedbackBridge;
import com.example.mealprep.feedback.testdata.InlineTransactionTemplate;
import com.example.mealprep.nutrition.api.dto.FeedbackTargetAdjustment;
import com.example.mealprep.nutrition.domain.entity.AdjustmentDirection;
import com.example.mealprep.nutrition.domain.entity.AdjustmentMagnitude;
import com.example.mealprep.nutrition.domain.service.NutritionUpdateService;
import com.example.mealprep.nutrition.exception.InvalidFeedbackAdjustmentException;
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
 * Unit tests for the now-live NUTRITION bridge (nutrition-01i flipped it off its deferred-FAILED
 * path): it parses the classifier payload into a {@link FeedbackTargetAdjustment} and dispatches it
 * to {@link NutritionUpdateService#applyFeedbackAdjustment}, booking DISPATCHED on success and
 * FAILED (rethrow) on an unsupported target. Confidence-floor + idempotency short-circuits are
 * unchanged.
 */
class NutritionFeedbackBridgeTest {

  private static final Instant NOW = Instant.parse("2026-05-22T10:00:00Z");

  private NutritionUpdateService nutritionUpdateService;
  private FeedbackBridgeIdempotencyRepository idempotencyRepository;
  private NutritionFeedbackBridgeImpl bridge;

  @BeforeEach
  void setUp() {
    nutritionUpdateService = Mockito.mock(NutritionUpdateService.class);
    idempotencyRepository = Mockito.mock(FeedbackBridgeIdempotencyRepository.class);
    when(idempotencyRepository.findByFeedbackIdAndDestination(any(), any()))
        .thenReturn(Optional.empty());
    when(idempotencyRepository.insertIfAbsent(any(), any(), anyString(), anyString(), any()))
        .thenReturn(1);
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    bridge =
        new NutritionFeedbackBridgeImpl(
            nutritionUpdateService, idempotencyRepository, new InlineTransactionTemplate(), clock);
  }

  @Test
  void applyFeedback_validAdjustment_dispatchesToServiceAndBooksDispatched() {
    NutritionFeedbackBridge.Input input =
        input(new BigDecimal("0.84"), "protein_target_g", "increase", "moderate", null);

    NutritionFeedbackBridge.Result result = bridge.applyFeedback(input);

    assertThat(result.payload()).containsEntry("status", "DISPATCHED");
    assertThat(result.payload()).containsEntry("originTrace", "feedback-" + input.feedbackId());

    ArgumentCaptor<FeedbackTargetAdjustment> captor =
        ArgumentCaptor.forClass(FeedbackTargetAdjustment.class);
    verify(nutritionUpdateService).applyFeedbackAdjustment(eq(input.userId()), captor.capture());
    FeedbackTargetAdjustment dispatched = captor.getValue();
    assertThat(dispatched.target()).isEqualTo("protein_target_g");
    assertThat(dispatched.direction()).isEqualTo(AdjustmentDirection.INCREASE);
    assertThat(dispatched.magnitude()).isEqualTo(AdjustmentMagnitude.MODERATE);
    assertThat(dispatched.reason()).isEqualTo("feedback-" + input.feedbackId());

    verify(idempotencyRepository)
        .insertIfAbsent(
            any(),
            eq(input.feedbackId()),
            eq(Destination.NUTRITION.name()),
            eq(BridgeDispatchStatus.DISPATCHED.name()),
            eq(NOW));
  }

  @Test
  void applyFeedback_carriesAbsoluteValue_whenPresentInPayload() {
    NutritionFeedbackBridge.Input input =
        input(new BigDecimal("0.9"), "calorie_target", "increase", "small", new BigDecimal("2200"));

    bridge.applyFeedback(input);

    ArgumentCaptor<FeedbackTargetAdjustment> captor =
        ArgumentCaptor.forClass(FeedbackTargetAdjustment.class);
    verify(nutritionUpdateService).applyFeedbackAdjustment(eq(input.userId()), captor.capture());
    assertThat(captor.getValue().absoluteValue()).isEqualByComparingTo("2200");
  }

  @Test
  void applyFeedback_unsupportedTarget_booksFailedAndThrows() {
    NutritionFeedbackBridge.Input input =
        input(new BigDecimal("0.84"), "vibes", "increase", "moderate", null);
    Mockito.doThrow(new InvalidFeedbackAdjustmentException("vibes"))
        .when(nutritionUpdateService)
        .applyFeedbackAdjustment(eq(input.userId()), any());

    assertThatThrownBy(() -> bridge.applyFeedback(input))
        .isInstanceOf(FeedbackBridgeDispatchFailedException.class);

    verify(idempotencyRepository)
        .insertIfAbsent(
            any(),
            eq(input.feedbackId()),
            eq(Destination.NUTRITION.name()),
            eq(BridgeDispatchStatus.FAILED.name()),
            eq(NOW));
  }

  @Test
  void applyFeedback_malformedMagnitude_booksFailedAndThrows_withoutCallingService() {
    NutritionFeedbackBridge.Input input =
        input(new BigDecimal("0.84"), "protein_target_g", "increase", "ENORMOUS", null);

    assertThatThrownBy(() -> bridge.applyFeedback(input))
        .isInstanceOf(FeedbackBridgeDispatchFailedException.class);

    verify(nutritionUpdateService, never()).applyFeedbackAdjustment(any(), any());
    verify(idempotencyRepository)
        .insertIfAbsent(
            any(),
            eq(input.feedbackId()),
            eq(Destination.NUTRITION.name()),
            eq(BridgeDispatchStatus.FAILED.name()),
            eq(NOW));
  }

  @Test
  void belowConfidenceFloor_booksRejected() {
    NutritionFeedbackBridge.Input input =
        input(new BigDecimal("0.42"), "protein_target_g", "increase", "moderate", null);

    NutritionFeedbackBridge.Result result = bridge.applyFeedback(input);

    assertThat(result.payload()).containsEntry("status", "REJECTED_LOW_CONFIDENCE");
    verify(idempotencyRepository)
        .insertIfAbsent(
            any(),
            eq(input.feedbackId()),
            eq(Destination.NUTRITION.name()),
            eq(BridgeDispatchStatus.REJECTED_LOW_CONFIDENCE.name()),
            eq(NOW));
    verifyNoInteractions(nutritionUpdateService);
  }

  private NutritionFeedbackBridge.Input input(
      BigDecimal confidence,
      String target,
      String direction,
      String magnitude,
      BigDecimal absoluteValue) {
    ObjectNode payload = JsonNodeFactory.instance.objectNode();
    payload.put("target", target);
    payload.put("direction", direction);
    payload.put("magnitude", magnitude);
    if (absoluteValue != null) {
      payload.put("absoluteValue", absoluteValue);
    }
    UUID feedbackId = UUID.randomUUID();
    return new NutritionFeedbackBridge.Input(
        feedbackId, UUID.randomUUID(), confidence, "adjust target", UUID.randomUUID(), payload);
  }
}
