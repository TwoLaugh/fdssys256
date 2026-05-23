package com.example.mealprep.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.mealprep.feedback.domain.entity.BridgeDispatchStatus;
import com.example.mealprep.feedback.domain.repository.FeedbackBridgeIdempotencyRepository;
import com.example.mealprep.feedback.exception.FeedbackBridgeDispatchFailedException;
import com.example.mealprep.feedback.spi.Destination;
import com.example.mealprep.feedback.spi.PreferenceFeedbackBridge;
import com.example.mealprep.preference.api.dto.TasteProfileDto;
import com.example.mealprep.preference.domain.entity.TasteProfileChangeType;
import com.example.mealprep.preference.domain.repository.TasteProfileAuditLogRepository;
import com.example.mealprep.preference.domain.repository.TasteProfileRepository;
import com.example.mealprep.preference.domain.repository.TasteProfileVersionRepository;
import com.example.mealprep.preference.domain.service.TasteProfileQueryService;
import com.example.mealprep.preference.domain.service.TasteProfileUpdateService;
import com.example.mealprep.testsupport.TestContainersConfig;
import com.fasterxml.jackson.databind.node.ArrayNode;
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
 * AFTER_COMMIT atomicity IT (preference-01f §11 / decision-log 0010). Exercises the full {@code
 * PreferenceFeedbackBridgeImpl} → {@code applyDeltas} path through the real Spring context +
 * Postgres via the bridge's {@code REQUIRES_NEW} transaction template (the bridge fires from an
 * AFTER_COMMIT event listener in production). Asserts that on a valid batch the document mutation +
 * version snapshot + audit row + the bridge's {@code DISPATCHED} idempotency row all commit
 * together, and on a failure the document update rolls back while the bridge still books a {@code
 * FAILED} forensic row in its own REQUIRES_NEW transaction.
 */
@SpringBootTest
@Import(TestContainersConfig.class)
@ActiveProfiles("test")
class PreferenceFeedbackBridgeAppliesIT {

  @Autowired private PreferenceFeedbackBridge preferenceBridge;
  @Autowired private TasteProfileUpdateService updateService;
  @Autowired private TasteProfileQueryService queryService;
  @Autowired private FeedbackBridgeIdempotencyRepository idempotencyRepository;
  @Autowired private TasteProfileRepository tasteProfileRepository;
  @Autowired private TasteProfileVersionRepository versionRepository;
  @Autowired private TasteProfileAuditLogRepository auditLogRepository;

  @AfterEach
  void cleanup() {
    idempotencyRepository.deleteAll();
    auditLogRepository.deleteAll();
    versionRepository.deleteAll();
    tasteProfileRepository.deleteAll();
  }

  private PreferenceFeedbackBridge.Input input(UUID userId, UUID feedbackId, ObjectNode payload) {
    return new PreferenceFeedbackBridge.Input(
        feedbackId, userId, new BigDecimal("0.9"), "no more coriander", UUID.randomUUID(), payload);
  }

  private ObjectNode addCorianderPayload() {
    ObjectNode payload = JsonNodeFactory.instance.objectNode();
    payload.put("trigger", "BATCH");
    payload.put("modelTierUsed", "cheap");
    ArrayNode deltas = payload.putArray("deltas");
    ObjectNode add = deltas.addObject();
    add.put("op", "ADD");
    add.put("fieldPath", "ingredientPreferences.disliked");
    ObjectNode item = add.putObject("item");
    item.put("item", "coriander");
    item.put("evidenceCount", 3);
    item.put("lastSignal", "2026-05-01");
    item.put("source", "FEEDBACK");
    return payload;
  }

  @Test
  void validBatch_commitsDocumentVersionAuditAndDispatchedRow_together() {
    UUID userId = UUID.randomUUID();
    UUID feedbackId = UUID.randomUUID();
    updateService.initialise(userId);

    PreferenceFeedbackBridge.Result result =
        preferenceBridge.applyFeedback(input(userId, feedbackId, addCorianderPayload()));

    assertThat(result.payload()).containsEntry("status", "DISPATCHED");

    // Document mutated + version bumped, all visible after the bridge's tx committed.
    TasteProfileDto dto = queryService.getTasteProfile(userId).orElseThrow();
    assertThat(dto.documentVersion()).isEqualTo(2);
    assertThat(dto.document().ingredientPreferences().disliked())
        .extracting(
            com.example.mealprep.preference.domain.document.TasteProfileDocument
                    .IngredientPreference
                ::item)
        .contains("coriander");

    // Version snapshot + AI audit row committed.
    assertThat(versionRepository.count()).isEqualTo(2L); // init + delta
    boolean aiAudit =
        auditLogRepository.findAll().stream()
            .anyMatch(a -> a.getChangeType() == TasteProfileChangeType.AI_DELTA_APPLIED);
    assertThat(aiAudit).isTrue();

    // Bridge's DISPATCHED idempotency row committed in the same unit.
    assertThat(
            idempotencyRepository
                .findByFeedbackIdAndDestination(feedbackId, Destination.PREFERENCE)
                .orElseThrow()
                .getStatus())
        .isEqualTo(BridgeDispatchStatus.DISPATCHED);
  }

  @Test
  void invalidBatch_rollsBackDocument_butBooksFailedForensicRow() {
    UUID userId = UUID.randomUUID();
    UUID feedbackId = UUID.randomUUID();
    updateService.initialise(userId);

    // Remove of a non-existent item → InvalidTasteProfileDeltaException → dispatch failure.
    ObjectNode payload = JsonNodeFactory.instance.objectNode();
    payload.put("trigger", "BATCH");
    ArrayNode deltas = payload.putArray("deltas");
    ObjectNode remove = deltas.addObject();
    remove.put("op", "REMOVE");
    remove.put("fieldPath", "ingredientPreferences.disliked");
    remove.put("itemKey", "ghost");

    assertThatThrownBy(() -> preferenceBridge.applyFeedback(input(userId, feedbackId, payload)))
        .isInstanceOf(FeedbackBridgeDispatchFailedException.class);

    // Document untouched (version still 1) — the join rolled back the delta application.
    assertThat(queryService.getTasteProfile(userId).orElseThrow().documentVersion()).isEqualTo(1);
    assertThat(versionRepository.count()).isEqualTo(1L); // only the init snapshot

    // The FAILED idempotency row survives in its own REQUIRES_NEW transaction (forensic record).
    assertThat(
            idempotencyRepository
                .findByFeedbackIdAndDestination(feedbackId, Destination.PREFERENCE)
                .orElseThrow()
                .getStatus())
        .isEqualTo(BridgeDispatchStatus.FAILED);
  }
}
