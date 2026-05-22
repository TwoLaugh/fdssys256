package com.example.mealprep.feedback.bridge;

import com.example.mealprep.feedback.bridge.internal.FeedbackBridgeSupport;
import com.example.mealprep.feedback.config.FeedbackTxTemplateConfig;
import com.example.mealprep.feedback.domain.entity.BridgeDispatchStatus;
import com.example.mealprep.feedback.domain.repository.FeedbackBridgeIdempotencyRepository;
import com.example.mealprep.feedback.spi.Destination;
import com.example.mealprep.feedback.spi.ProvisionsFeedbackBridge;
import com.example.mealprep.provisions.domain.service.ProvisionUpdateService;
import com.example.mealprep.provisions.exception.EquipmentNotFoundException;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
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
 *   <li>{@code MARK_DEPLETED} → mark the matching inventory row exhausted. The provisions write
 *       surface ({@code markExhausted(itemId, actorUserId)}) is keyed by item id, and the public
 *       provisions <i>read</i> surface does not yet expose a lookup by {@code ingredientMappingKey}
 *       ({@code InventorySearchCriteria} carries only storage-location / staple filters). The
 *       bridge is fully wired; the depletion call is recorded as a <b>deferred FAILED</b> with a
 *       structured reason until provisions exposes a by-key lookup (a sibling provisions LLD note).
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

  public ProvisionsFeedbackBridgeImpl(
      ProvisionUpdateService provisionUpdateService,
      FeedbackBridgeIdempotencyRepository idempotencyRepository,
      @Qualifier(FeedbackTxTemplateConfig.REQUIRES_NEW_TX_TEMPLATE)
          TransactionTemplate requiresNewTxTemplate,
      Clock clock) {
    super(idempotencyRepository, requiresNewTxTemplate, clock);
    this.provisionUpdateService = provisionUpdateService;
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
   * Mark the {@code (userId, ingredientMappingKey)} inventory row exhausted. Wired-but-deferred:
   * the provisions write surface ({@code markExhausted(itemId, …)}) needs an item id, and no public
   * by-mapping-key lookup exists on {@code ProvisionQueryService} yet. Recorded as FAILED with a
   * structured reason so the feedback quality-monitoring path surfaces it; the call lands once
   * provisions exposes the lookup.
   */
  private Result markDepleted(Input input) {
    String ingredientKey = textOrNull(input.structuredPayload(), "ingredientMappingKey");
    log()
        .error(
            "provisions MARK_DEPLETED is wired-but-deferred: provisions exposes no by-mapping-key"
                + " inventory lookup on its public read surface yet ({}). feedbackId={}"
                + " ingredientKey={} actorType={} originTrace={}",
            "provisions-inventory-lookup-by-key-not-exposed",
            input.feedbackId(),
            ingredientKey,
            actorType(),
            originTrace(input.feedbackId()));
    // Book FAILED + throw so the router records AI_UNAVAILABLE; lands when provisions exposes the
    // by-mapping-key lookup that markExhausted(itemId,…) needs.
    throw failed(
        input.feedbackId(),
        DESTINATION,
        new UnsupportedOperationException("provisions-inventory-lookup-by-key-not-exposed"));
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
