package com.example.mealprep.feedback.bridge;

import com.example.mealprep.feedback.bridge.internal.FeedbackBridgeSupport;
import com.example.mealprep.feedback.config.FeedbackTxTemplateConfig;
import com.example.mealprep.feedback.domain.entity.BridgeDispatchStatus;
import com.example.mealprep.feedback.domain.repository.FeedbackBridgeIdempotencyRepository;
import com.example.mealprep.feedback.spi.Destination;
import com.example.mealprep.feedback.spi.PreferenceFeedbackBridge;
import com.example.mealprep.preference.api.dto.ApplyTasteProfileDeltasRequest;
import com.example.mealprep.preference.api.dto.TasteProfileDelta;
import com.example.mealprep.preference.domain.entity.TasteProfileTrigger;
import com.example.mealprep.preference.domain.service.TasteProfileUpdateService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Real PREFERENCE bridge (tickets/feedback/01g §5-7). Replaces {@code
 * NoopFeedbackBridgesConfiguration.NoopPreferenceFeedbackBridge}: this {@code @Component} satisfies
 * the {@code @ConditionalOnMissingBean(PreferenceFeedbackBridge.class)} so the Noop steps aside.
 *
 * <p>Parses the classifier's {@code taste_profile_delta} payload into an {@link
 * ApplyTasteProfileDeltasRequest} and calls {@link TasteProfileUpdateService#applyDeltas} with AI
 * origin attribution (origin_trace = {@code feedback-<feedback_id>}).
 *
 * <p><b>Stubbed downstream</b>: per tickets/preference/01c the underlying {@code
 * TasteProfileDeltaApplier} is a stub that throws {@link UnsupportedOperationException}. This is
 * the <b>expected v1 behaviour</b> — the bridge is fully wired and ready; the applier ships in the
 * deferred {@code 01c-delta-applier} ticket. We catch the stub exception, book a {@code FAILED}
 * idempotency row, and log a structured line referencing the deferred ticket. We deliberately do
 * NOT implement the delta-applier here (out of scope, ticket §"What's NOT in scope").
 */
@Component
public class PreferenceFeedbackBridgeImpl extends FeedbackBridgeSupport
    implements PreferenceFeedbackBridge {

  private static final Destination DESTINATION = Destination.PREFERENCE;
  private static final String DEFERRED_APPLIER_TICKET = "tickets/preference/01c-delta-applier";

  private final TasteProfileUpdateService tasteProfileUpdateService;
  private final ObjectMapper objectMapper;

  public PreferenceFeedbackBridgeImpl(
      TasteProfileUpdateService tasteProfileUpdateService,
      ObjectMapper objectMapper,
      FeedbackBridgeIdempotencyRepository idempotencyRepository,
      @Qualifier(FeedbackTxTemplateConfig.REQUIRES_NEW_TX_TEMPLATE)
          TransactionTemplate requiresNewTxTemplate,
      Clock clock) {
    super(idempotencyRepository, requiresNewTxTemplate, clock);
    this.tasteProfileUpdateService = tasteProfileUpdateService;
    this.objectMapper = objectMapper;
  }

  @Override
  public Result applyFeedback(Input input) {
    if (belowConfidenceFloor(input.confidence())) {
      recordOutcome(input.feedbackId(), DESTINATION, BridgeDispatchStatus.REJECTED_LOW_CONFIDENCE);
      log()
          .warn(
              "preference feedback below confidence floor — skipped. feedbackId={} destination={}"
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
              "preference feedback already dispatched within idempotency window — no-op."
                  + " feedbackId={}",
              input.feedbackId());
      return new Result("idempotent no-op", Map.of("status", "ALREADY_DISPATCHED"));
    }

    ApplyTasteProfileDeltasRequest request = buildRequest(input);
    try {
      tasteProfileUpdateService.applyDeltas(input.userId(), request);
      recordOutcome(input.feedbackId(), DESTINATION, BridgeDispatchStatus.DISPATCHED);
      return new Result(
          "taste profile deltas applied",
          Map.of("status", "DISPATCHED", "originTrace", originTrace(input.feedbackId())));
    } catch (UnsupportedOperationException stubbed) {
      // Expected v1 outcome — the delta-applier is a stub. Book FAILED + structured log, then throw
      // FeedbackBridgeDispatchFailedException so the router records AI_UNAVAILABLE (the bridge is
      // wired and ready; the applier ships in the deferred ticket).
      log()
          .error(
              "preference taste-profile applyDeltas is not yet implemented (stubbed per {}); bridge"
                  + " is wired and ready, applier ships in the deferred ticket. feedbackId={}"
                  + " userId={} actorType={} originTrace={}",
              DEFERRED_APPLIER_TICKET,
              input.feedbackId(),
              input.userId(),
              actorType(),
              originTrace(input.feedbackId()),
              stubbed);
      throw failed(input.feedbackId(), DESTINATION, stubbed);
    }
  }

  /**
   * Parse the classifier's {@code {deltas:[...], trigger, feedbackRange*}} payload defensively. The
   * downstream applier is stubbed today, so we keep parsing tolerant: malformed / absent deltas
   * yield an empty list rather than a hard failure (the call throws {@code
   * UnsupportedOperationException} regardless). The {@code feedbackRange*} fields are stamped with
   * the origin trace so the future applier's audit row carries provenance.
   */
  private ApplyTasteProfileDeltasRequest buildRequest(Input input) {
    String trace = originTrace(input.feedbackId());
    List<TasteProfileDelta> deltas = parseDeltas(input.structuredPayload());
    TasteProfileTrigger trigger = parseTrigger(input.structuredPayload());
    String modelTier = textOrNull(input.structuredPayload(), "modelTierUsed");
    return new ApplyTasteProfileDeltasRequest(deltas, trigger, trace, trace, modelTier);
  }

  private List<TasteProfileDelta> parseDeltas(JsonNode payload) {
    List<TasteProfileDelta> result = new ArrayList<>();
    if (payload == null) {
      return result;
    }
    JsonNode deltasNode = payload.path("deltas");
    if (!deltasNode.isArray()) {
      return result;
    }
    for (JsonNode deltaNode : deltasNode) {
      try {
        result.add(objectMapper.treeToValue(deltaNode, TasteProfileDelta.class));
      } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException ignored) {
        // Tolerant parse: skip a delta the polymorphic mapper can't resolve. The downstream
        // applier is stubbed; once it lands, classifier-shape validation moves there.
        log().debug("skipping unparseable taste-profile delta for feedbackId={}", "(redacted)");
      }
    }
    return result;
  }

  private TasteProfileTrigger parseTrigger(JsonNode payload) {
    String raw = textOrNull(payload, "trigger");
    if (raw == null) {
      return TasteProfileTrigger.BATCH;
    }
    try {
      return TasteProfileTrigger.valueOf(raw);
    } catch (IllegalArgumentException unknown) {
      return TasteProfileTrigger.BATCH;
    }
  }

  private static String textOrNull(JsonNode payload, String field) {
    if (payload == null) {
      return null;
    }
    JsonNode node = payload.path(field);
    return node.isMissingNode() || node.isNull() ? null : node.asText();
  }
}
