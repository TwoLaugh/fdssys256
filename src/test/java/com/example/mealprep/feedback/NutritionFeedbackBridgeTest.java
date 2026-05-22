package com.example.mealprep.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
import com.example.mealprep.nutrition.domain.service.NutritionUpdateService;
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
import org.mockito.Mockito;

/**
 * Unit tests for the real NUTRITION bridge: wired-but-deferred FAILED (no single-field adjustment
 * surface), confidence-floor rejection. The nutrition adjustment lands when the destination exposes
 * a feedback-adjustment method; until then the bridge books FAILED and throws so the router records
 * AI_UNAVAILABLE.
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
  void adjustment_isWiredButDeferred_booksFailed_andThrows() {
    NutritionFeedbackBridge.Input input = input(new BigDecimal("0.84"));

    assertThatThrownBy(() -> bridge.applyFeedback(input))
        .isInstanceOf(FeedbackBridgeDispatchFailedException.class);

    verify(idempotencyRepository)
        .insertIfAbsent(
            any(),
            eq(input.feedbackId()),
            eq("NUTRITION"),
            eq(BridgeDispatchStatus.FAILED.name()),
            eq(NOW));
    // Deferred: the full-replacement updateTargets is intentionally NOT called by the bridge.
    verifyNoInteractions(nutritionUpdateService);
  }

  @Test
  void belowConfidenceFloor_booksRejected() {
    NutritionFeedbackBridge.Input input = input(new BigDecimal("0.42"));

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

  private NutritionFeedbackBridge.Input input(BigDecimal confidence) {
    ObjectNode payload = JsonNodeFactory.instance.objectNode();
    payload.put("target", "sodium_mg_max");
    payload.put("direction", "decrease");
    payload.put("magnitude", "moderate");
    payload.put("absoluteValue", 2000);
    UUID feedbackId = UUID.randomUUID();
    return new NutritionFeedbackBridge.Input(
        feedbackId, UUID.randomUUID(), confidence, "cutting sodium", UUID.randomUUID(), payload);
  }
}
