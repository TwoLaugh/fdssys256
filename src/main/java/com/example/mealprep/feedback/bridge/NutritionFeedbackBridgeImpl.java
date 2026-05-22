package com.example.mealprep.feedback.bridge;

import com.example.mealprep.feedback.bridge.internal.FeedbackBridgeSupport;
import com.example.mealprep.feedback.config.FeedbackTxTemplateConfig;
import com.example.mealprep.feedback.domain.entity.BridgeDispatchStatus;
import com.example.mealprep.feedback.domain.repository.FeedbackBridgeIdempotencyRepository;
import com.example.mealprep.feedback.spi.Destination;
import com.example.mealprep.feedback.spi.NutritionFeedbackBridge;
import com.example.mealprep.nutrition.domain.service.NutritionUpdateService;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Real NUTRITION bridge (tickets/feedback/01g §8-11). Replaces {@code
 * NoopFeedbackBridgesConfiguration.NoopNutritionFeedbackBridge}: this {@code @Component} satisfies
 * {@code @ConditionalOnMissingBean(NutritionFeedbackBridge.class)} so the Noop steps aside.
 *
 * <p>The classifier emits a single-field target adjustment ({@code {target, direction, magnitude,
 * absoluteValue, reason}}). Ticket §10 anticipates that the nutrition write surface may not yet
 * expose {@code applyFeedbackAdjustment} / {@code updateMicroTarget}, and instructs the bridge to
 * call the next-closest method or flag a follow-up. Verified at agent start: {@link
 * NutritionUpdateService} exposes only {@code updateTargets(userId, UpdateTargetsRequest,
 * actorUserId)} — a <b>full-replacement</b> write requiring the complete targets document plus an
 * optimistic-{@code expectedVersion}. Applying one feedback-driven delta through a wholesale
 * replacement (read-modify-write the entire document, race the user's concurrent edits on a stale
 * version) is unsafe for a fire-and-forget AFTER-routing bridge.
 *
 * <p><b>Decision (worth user review)</b>: the bridge is fully wired (confidence floor, idempotency,
 * AI origin attribution, payload parse) but records the adjustment as a <b>deferred FAILED</b> with
 * a structured reason rather than forcing a full-document replacement. The single-field
 * feedback-adjustment surface ({@code applyFeedbackAdjustment}) lands in a nutrition follow-up;
 * when it does, only {@link #applyAdjustment} changes. This mirrors the sanctioned
 * preference→stubbed pattern: wire now, apply when the destination surface is ready.
 */
@Component
public class NutritionFeedbackBridgeImpl extends FeedbackBridgeSupport
    implements NutritionFeedbackBridge {

  private static final Destination DESTINATION = Destination.NUTRITION;
  private static final String DEFERRED_REASON = "nutrition-feedback-adjustment-not-exposed";

  // Held for the follow-up wiring; the deferred path doesn't call it yet but the bean is injected
  // so the wiring is complete and the follow-up is a one-method change.
  @SuppressWarnings("unused")
  private final NutritionUpdateService nutritionUpdateService;

  public NutritionFeedbackBridgeImpl(
      NutritionUpdateService nutritionUpdateService,
      FeedbackBridgeIdempotencyRepository idempotencyRepository,
      @Qualifier(FeedbackTxTemplateConfig.REQUIRES_NEW_TX_TEMPLATE)
          TransactionTemplate requiresNewTxTemplate,
      Clock clock) {
    super(idempotencyRepository, requiresNewTxTemplate, clock);
    this.nutritionUpdateService = nutritionUpdateService;
  }

  @Override
  public Result applyFeedback(Input input) {
    if (belowConfidenceFloor(input.confidence())) {
      recordOutcome(input.feedbackId(), DESTINATION, BridgeDispatchStatus.REJECTED_LOW_CONFIDENCE);
      log()
          .warn(
              "nutrition feedback below confidence floor — skipped. feedbackId={} destination={}"
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
              "nutrition feedback already dispatched within idempotency window — no-op."
                  + " feedbackId={}",
              input.feedbackId());
      return new Result("idempotent no-op", Map.of("status", "ALREADY_DISPATCHED"));
    }
    return applyAdjustment(input);
  }

  /**
   * Translate + dispatch the target adjustment. Deferred until nutrition exposes a single-field
   * feedback-adjustment surface (see class javadoc). The payload is parsed so the structured log
   * carries the intended adjustment for the quality-monitoring path.
   */
  private Result applyAdjustment(Input input) {
    JsonNode payload = input.structuredPayload();
    String target = textOrNull(payload, "target");
    String direction = textOrNull(payload, "direction");
    String magnitude = textOrNull(payload, "magnitude");

    log()
        .error(
            "nutrition feedback adjustment is wired-but-deferred: NutritionUpdateService exposes"
                + " only full-replacement updateTargets, no single-field applyFeedbackAdjustment"
                + " surface yet ({}). feedbackId={} target={} direction={} magnitude={}"
                + " actorType={} originTrace={}",
            DEFERRED_REASON,
            input.feedbackId(),
            target,
            direction,
            magnitude,
            actorType(),
            originTrace(input.feedbackId()));
    // Book FAILED + throw so the router records AI_UNAVAILABLE; the call lands when nutrition
    // exposes a single-field feedback-adjustment surface (then only this method changes).
    throw failed(
        input.feedbackId(), DESTINATION, new UnsupportedOperationException(DEFERRED_REASON));
  }

  private static String textOrNull(JsonNode payload, String field) {
    if (payload == null) {
      return null;
    }
    JsonNode node = payload.path(field);
    return node.isMissingNode() || node.isNull() ? null : node.asText();
  }
}
