package com.example.mealprep.feedback.bridge;

import com.example.mealprep.feedback.bridge.internal.FeedbackBridgeSupport;
import com.example.mealprep.feedback.config.FeedbackTxTemplateConfig;
import com.example.mealprep.feedback.domain.entity.BridgeDispatchStatus;
import com.example.mealprep.feedback.domain.repository.FeedbackBridgeIdempotencyRepository;
import com.example.mealprep.feedback.spi.Destination;
import com.example.mealprep.feedback.spi.ProvisionsFeedbackBridge;
import com.example.mealprep.provisions.api.dto.InventoryItemDto;
import com.example.mealprep.provisions.domain.service.ProvisionQueryService;
import com.example.mealprep.provisions.domain.service.ProvisionUpdateService;
import com.example.mealprep.provisions.exception.EquipmentNotFoundException;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Real PROVISIONS bridge (tickets/feedback/01g §12-14). Replaces {@code
 * NoopFeedbackBridgesConfiguration.NoopProvisionsFeedbackBridge}: this {@code @Component} satisfies
 * {@code @ConditionalOnMissingBean(ProvisionsFeedbackBridge.class)} so the Noop steps aside.
 *
 * <p>Disambiguates on the classifier's {@code provisionsAction} field:
 *
 * <ul>
 *   <li>{@code REMOVE_EQUIPMENT} → {@link ProvisionUpdateService#deleteEquipment(java.util.UUID,
 *       String)}. <b>Real, end-to-end</b>. Idempotent: a missing equipment row is a no-op (the
 *       service's {@code EquipmentNotFoundException} is swallowed).
 *   <li>{@code MARK_DEPLETED} ("I'm out of soy sauce") → look up the user's ACTIVE inventory rows
 *       for the {@code ingredientMappingKey} via {@link
 *       ProvisionQueryService#getActiveInventoryByMappingKey(java.util.UUID, String)} and mark each
 *       resolved row exhausted via {@link ProvisionUpdateService#markExhausted(java.util.UUID,
 *       java.util.UUID)}. <b>Real, end-to-end</b>. Idempotent: no active rows for the key is a
 *       no-op success (the user being "out" of something they don't track is not a failure), and
 *       {@code markExhausted} itself is idempotent on already-exhausted rows. All rows are
 *       exhausted (zero remaining), each emitting an {@code ItemRanOutEvent}; a missing/blank
 *       {@code ingredientMappingKey} books FAILED.
 *   <li>any other / reserved action ({@code ADJUST_BUDGET}, …) → {@code FAILED} with reason {@code
 *       unsupported-provisions-action} (ticket §14).
 * </ul>
 */
@Component
public class ProvisionsFeedbackBridgeImpl extends FeedbackBridgeSupport
    implements ProvisionsFeedbackBridge {

  private static final Destination DESTINATION = Destination.PROVISIONS;
  private static final String ACTION_REMOVE_EQUIPMENT = "REMOVE_EQUIPMENT";
  private static final String ACTION_MARK_DEPLETED = "MARK_DEPLETED";

  private final ProvisionUpdateService provisionUpdateService;
  private final ProvisionQueryService provisionQueryService;

  public ProvisionsFeedbackBridgeImpl(
      ProvisionUpdateService provisionUpdateService,
      ProvisionQueryService provisionQueryService,
      FeedbackBridgeIdempotencyRepository idempotencyRepository,
      @Qualifier(FeedbackTxTemplateConfig.REQUIRES_NEW_TX_TEMPLATE)
          TransactionTemplate requiresNewTxTemplate,
      Clock clock) {
    super(idempotencyRepository, requiresNewTxTemplate, clock);
    this.provisionUpdateService = provisionUpdateService;
    this.provisionQueryService = provisionQueryService;
  }

  @Override
  public Result applyFeedback(Input input) {
    if (belowConfidenceFloor(input.confidence())) {
      recordOutcome(input.feedbackId(), DESTINATION, BridgeDispatchStatus.REJECTED_LOW_CONFIDENCE);
      log()
          .warn(
              "provisions feedback below confidence floor — skipped. feedbackId={} destination={}"
                  + " confidence={}",
              input.feedbackId(),
              DESTINATION,
              input.confidence());
      return new Result(
          "rejected: confidence below floor", Map.of("status", "REJECTED_LOW_CONFIDENCE"));
    }
    if (alreadyDispatched(input.feedbackId(), DESTINATION)) {
      log()
          .info(
              "provisions feedback already dispatched within idempotency window — no-op."
                  + " feedbackId={}",
              input.feedbackId());
      return new Result("idempotent no-op", Map.of("status", "ALREADY_DISPATCHED"));
    }

    String action = textOrNull(input.structuredPayload(), "provisionsAction");
    if (ACTION_REMOVE_EQUIPMENT.equals(action)) {
      return removeEquipment(input);
    }
    if (ACTION_MARK_DEPLETED.equals(action)) {
      return markDepleted(input);
    }
    return unsupportedAction(input, action);
  }

  private Result removeEquipment(Input input) {
    String equipmentName = textOrNull(input.structuredPayload(), "equipmentName");
    if (equipmentName == null || equipmentName.isBlank()) {
      log()
          .error(
              "provisions REMOVE_EQUIPMENT missing equipmentName. feedbackId={} originTrace={}",
              input.feedbackId(),
              originTrace(input.feedbackId()));
      throw failed(
          input.feedbackId(), DESTINATION, new IllegalArgumentException("missing-equipment-name"));
    }
    try {
      provisionUpdateService.deleteEquipment(input.userId(), equipmentName);
      recordOutcome(input.feedbackId(), DESTINATION, BridgeDispatchStatus.DISPATCHED);
      log()
          .info(
              "provisions equipment removed via feedback. feedbackId={} equipment={} actorType={}"
                  + " originTrace={}",
              input.feedbackId(),
              equipmentName,
              actorType(),
              originTrace(input.feedbackId()));
      return new Result(
          "equipment removed: " + equipmentName,
          Map.of("status", "DISPATCHED", "originTrace", originTrace(input.feedbackId())));
    } catch (EquipmentNotFoundException absent) {
      // Idempotent removal: nothing to remove is success, not failure (ticket §13).
      recordOutcome(input.feedbackId(), DESTINATION, BridgeDispatchStatus.DISPATCHED);
      log()
          .info(
              "provisions equipment already absent — idempotent no-op. feedbackId={} equipment={}",
              input.feedbackId(),
              equipmentName);
      return new Result(
          "equipment already absent: " + equipmentName,
          Map.of("status", "DISPATCHED", "noop", "equipment-absent"));
    }
  }

  /**
   * Mark every ACTIVE inventory row for {@code (userId, ingredientMappingKey)} exhausted ("I'm out
   * of soy sauce" → zero remaining). Looks the rows up via {@link
   * ProvisionQueryService#getActiveInventoryByMappingKey(java.util.UUID, String)} then calls {@link
   * ProvisionUpdateService#markExhausted(java.util.UUID, java.util.UUID)} per row. No active rows
   * is an idempotent no-op success (mirrors the equipment-already-absent branch); a missing/blank
   * {@code ingredientMappingKey} books FAILED (mirrors the equipment-name guard).
   */
  private Result markDepleted(Input input) {
    String ingredientKey = textOrNull(input.structuredPayload(), "ingredientMappingKey");
    if (ingredientKey == null || ingredientKey.isBlank()) {
      log()
          .error(
              "provisions MARK_DEPLETED missing ingredientMappingKey. feedbackId={} originTrace={}",
              input.feedbackId(),
              originTrace(input.feedbackId()));
      throw failed(
          input.feedbackId(),
          DESTINATION,
          new IllegalArgumentException("missing-ingredient-mapping-key"));
    }
    List<InventoryItemDto> rows =
        provisionQueryService.getActiveInventoryByMappingKey(input.userId(), ingredientKey);
    if (rows.isEmpty()) {
      // Being "out" of something the user doesn't track is success, not failure (ticket §13).
      recordOutcome(input.feedbackId(), DESTINATION, BridgeDispatchStatus.DISPATCHED);
      log()
          .info(
              "provisions MARK_DEPLETED — no active rows to deplete, idempotent no-op."
                  + " feedbackId={} ingredientKey={}",
              input.feedbackId(),
              ingredientKey);
      return new Result(
          "nothing to deplete: " + ingredientKey,
          Map.of("status", "DISPATCHED", "noop", "nothing-to-deplete"));
    }
    for (InventoryItemDto row : rows) {
      provisionUpdateService.markExhausted(row.id(), input.userId());
    }
    recordOutcome(input.feedbackId(), DESTINATION, BridgeDispatchStatus.DISPATCHED);
    log()
        .info(
            "provisions inventory depleted via feedback. feedbackId={} ingredientKey={} rows={}"
                + " actorType={} originTrace={}",
            input.feedbackId(),
            ingredientKey,
            rows.size(),
            actorType(),
            originTrace(input.feedbackId()));
    return new Result(
        "inventory depleted: " + ingredientKey + " (" + rows.size() + " row(s))",
        Map.of("status", "DISPATCHED", "originTrace", originTrace(input.feedbackId())));
  }

  private Result unsupportedAction(Input input, String action) {
    log()
        .error(
            "provisions feedback with unsupported action ({}). feedbackId={} provisionsAction={}"
                + " originTrace={}",
            "unsupported-provisions-action",
            input.feedbackId(),
            action,
            originTrace(input.feedbackId()));
    throw failed(
        input.feedbackId(),
        DESTINATION,
        new UnsupportedOperationException("unsupported-provisions-action: " + action));
  }

  private static String textOrNull(JsonNode payload, String field) {
    if (payload == null) {
      return null;
    }
    JsonNode node = payload.path(field);
    return node.isMissingNode() || node.isNull() ? null : node.asText();
  }
}
