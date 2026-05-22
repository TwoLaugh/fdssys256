package com.example.mealprep.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.mealprep.feedback.bridge.NutritionFeedbackBridgeImpl;
import com.example.mealprep.feedback.bridge.PreferenceFeedbackBridgeImpl;
import com.example.mealprep.feedback.bridge.ProvisionsFeedbackBridgeImpl;
import com.example.mealprep.feedback.bridge.RecipeFeedbackBridge;
import com.example.mealprep.feedback.domain.entity.BridgeDispatchStatus;
import com.example.mealprep.feedback.domain.repository.FeedbackBridgeIdempotencyRepository;
import com.example.mealprep.feedback.exception.FeedbackBridgeDispatchFailedException;
import com.example.mealprep.feedback.spi.Destination;
import com.example.mealprep.feedback.spi.NutritionFeedbackBridge;
import com.example.mealprep.feedback.spi.PreferenceFeedbackBridge;
import com.example.mealprep.feedback.spi.ProvisionsFeedbackBridge;
import com.example.mealprep.feedback.spi.RecipeFeedbackHandler;
import com.example.mealprep.testsupport.TestContainersConfig;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * End-to-end wiring verification for the four real destination bridges (feedback-01g): the Noop
 * configs are gone, the real {@code @Component} impls satisfy the SPI
 * {@code @ConditionalOnMissingBean} slots, and each bridge persists an idempotency row through the
 * real Spring context + Postgres.
 *
 * <p>Downstream-surface caveat: preference's {@code applyDeltas} is stubbed (deferred ticket),
 * nutrition has no single-field adjustment surface yet, and provisions {@code MARK_DEPLETED} has no
 * by-key lookup yet — so those bridges book {@code FAILED} (the wired-but-deferred v1 outcome).
 * Provisions {@code REMOVE_EQUIPMENT} and recipe adaptation are real end-to-end; they are exercised
 * here at the bridge edge (recipe against a non-existent recipe surfaces as a dispatch failure,
 * which still proves the call reaches the pipeline).
 */
@SpringBootTest
@Import(TestContainersConfig.class)
@ActiveProfiles("test")
class FeedbackBridgeEndToEndIT {

  @Autowired private PreferenceFeedbackBridge preferenceBridge;
  @Autowired private NutritionFeedbackBridge nutritionBridge;
  @Autowired private ProvisionsFeedbackBridge provisionsBridge;
  @Autowired private RecipeFeedbackHandler recipeHandler;
  @Autowired private FeedbackBridgeIdempotencyRepository idempotencyRepository;

  @AfterEach
  void cleanup() {
    idempotencyRepository.deleteAll();
  }

  @Test
  void realBridgeBeans_replaceNoops() {
    assertThat(preferenceBridge).isInstanceOf(PreferenceFeedbackBridgeImpl.class);
    assertThat(nutritionBridge).isInstanceOf(NutritionFeedbackBridgeImpl.class);
    assertThat(provisionsBridge).isInstanceOf(ProvisionsFeedbackBridgeImpl.class);
    assertThat(recipeHandler).isInstanceOf(RecipeFeedbackBridge.class);
  }

  @Test
  void preferenceBridge_stubbedApplyDeltas_booksFailed() {
    // applyDeltas is deferred end-to-end: it does a lookup-then-apply, so for a fresh user with no
    // lazily-initialised taste profile it throws TasteProfileNotFoundException before it ever
    // reaches the (separately stubbed) delta-applier. Either way the bridge owns the failure: it
    // books a FAILED idempotency row and rethrows FeedbackBridgeDispatchFailedException. We pass a
    // random userId (no profile row) to exercise that wired-but-deferred dispatch-failure path.
    UUID feedbackId = UUID.randomUUID();
    ObjectNode payload = JsonNodeFactory.instance.objectNode();
    payload.put("trigger", "BATCH");
    PreferenceFeedbackBridge.Input input =
        new PreferenceFeedbackBridge.Input(
            feedbackId,
            UUID.randomUUID(),
            new BigDecimal("0.7"),
            "no coriander",
            UUID.randomUUID(),
            payload);

    assertThatThrownBy(() -> preferenceBridge.applyFeedback(input))
        .isInstanceOf(FeedbackBridgeDispatchFailedException.class);

    assertThat(
            idempotencyRepository
                .findByFeedbackIdAndDestination(feedbackId, Destination.PREFERENCE)
                .orElseThrow()
                .getStatus())
        .isEqualTo(BridgeDispatchStatus.FAILED);
  }

  @Test
  void preferenceBridge_lowConfidence_booksRejected_andSkips() {
    UUID feedbackId = UUID.randomUUID();
    ObjectNode payload = JsonNodeFactory.instance.objectNode();
    payload.put("trigger", "BATCH");
    PreferenceFeedbackBridge.Input input =
        new PreferenceFeedbackBridge.Input(
            feedbackId,
            UUID.randomUUID(),
            new BigDecimal("0.4"),
            "maybe less salt?",
            UUID.randomUUID(),
            payload);

    PreferenceFeedbackBridge.Result result = preferenceBridge.applyFeedback(input);

    assertThat(result.payload()).containsEntry("status", "REJECTED_LOW_CONFIDENCE");
    assertThat(
            idempotencyRepository
                .findByFeedbackIdAndDestination(feedbackId, Destination.PREFERENCE)
                .orElseThrow()
                .getStatus())
        .isEqualTo(BridgeDispatchStatus.REJECTED_LOW_CONFIDENCE);
  }

  @Test
  void provisionsBridge_removeEquipment_absent_isIdempotentDispatched() {
    UUID feedbackId = UUID.randomUUID();
    ObjectNode payload = JsonNodeFactory.instance.objectNode();
    payload.put("provisionsAction", "REMOVE_EQUIPMENT");
    payload.put("equipmentName", "nonexistent gadget");
    ProvisionsFeedbackBridge.Input input =
        new ProvisionsFeedbackBridge.Input(
            feedbackId,
            UUID.randomUUID(),
            new BigDecimal("0.9"),
            "don't have that gadget",
            UUID.randomUUID(),
            payload);

    ProvisionsFeedbackBridge.Result result = provisionsBridge.applyFeedback(input);

    // Removing equipment that doesn't exist is an idempotent no-op → DISPATCHED, not FAILED.
    assertThat(result.payload()).containsEntry("status", "DISPATCHED");
    assertThat(
            idempotencyRepository
                .findByFeedbackIdAndDestination(feedbackId, Destination.PROVISIONS)
                .orElseThrow()
                .getStatus())
        .isEqualTo(BridgeDispatchStatus.DISPATCHED);
  }

  @Test
  void nutritionBridge_isWiredButDeferred_booksFailed() {
    UUID feedbackId = UUID.randomUUID();
    ObjectNode payload = JsonNodeFactory.instance.objectNode();
    payload.put("target", "sodium_mg_max");
    payload.put("direction", "decrease");
    NutritionFeedbackBridge.Input input =
        new NutritionFeedbackBridge.Input(
            feedbackId,
            UUID.randomUUID(),
            new BigDecimal("0.84"),
            "cutting sodium",
            UUID.randomUUID(),
            payload);

    assertThatThrownBy(() -> nutritionBridge.applyFeedback(input))
        .isInstanceOf(FeedbackBridgeDispatchFailedException.class);
    assertThat(
            idempotencyRepository
                .findByFeedbackIdAndDestination(feedbackId, Destination.NUTRITION)
                .orElseThrow()
                .getStatus())
        .isEqualTo(BridgeDispatchStatus.FAILED);
  }
}
