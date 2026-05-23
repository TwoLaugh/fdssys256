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
import com.example.mealprep.preference.exception.PreferenceException;
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
 * <p><b>Dispatch-failure handling</b>: {@code applyDeltas} does a lookup-then-apply — it loads the
 * user's taste-profile aggregate (404 if the lazily-initialised profile does not exist yet) and
 * runs the real {@code TasteProfileDeltaApplier} + {@code TasteProfileBudgetGuard}
 * (preference-01f). Any failure surfaces as a {@link PreferenceException} subtype (missing profile,
 * invalid delta, or budget exceeded). All are <b>legitimate dispatch failures</b> the bridge owns
 * gracefully: it catches the {@code PreferenceException} base, books a {@code FAILED} idempotency
 * row, logs a structured line, and rethrows {@code FeedbackBridgeDispatchFailedException} so the
 * router records {@code AI_UNAVAILABLE}. No raw preference exception is allowed to propagate past
 * the bridge edge.
 */
@Component
public class PreferenceFeedbackBridgeImpl extends FeedbackBridgeSupport
    implements PreferenceFeedbackBridge {

  private static final Destination DESTINATION = Destination.PREFERENCE;

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
    } catch (PreferenceException dispatchFailure) {
      // Every applyDeltas failure surfaces as a preference domain exception:
      //   - TasteProfileNotFoundException (404)  — the lazily-initialised profile doesn't exist
      // yet;
      //   - InvalidTasteProfileDeltaException (422) — a delta in the batch failed validation;
      //   - TasteProfileBudgetExceededException (422) — the post-apply doc blew the token budget.
      // All are legitimate dispatch failures the bridge owns gracefully: book a FAILED idempotency
      // row + structured log, then throw FeedbackBridgeDispatchFailedException so the router
      // records
      // AI_UNAVAILABLE. No raw preference exception is allowed past the bridge edge. (Pre-01f this
      // also caught the applier's UnsupportedOperationException stub; the stub is now gone.)
      log()
          .error(
              "preference taste-profile applyDeltas dispatch failed. feedbackId={} userId={}"
                  + " actorType={} originTrace={}",
              input.feedbackId(),
              input.userId(),
              actorType(),
              originTrace(input.feedbackId()),
              dispatchFailure);
      throw failed(input.feedbackId(), DESTINATION, dispatchFailure);
    }
  }

  /**
   * Parse the classifier's {@code {deltas:[...], trigger, feedbackRange*}} payload defensively.
   * Parsing stays tolerant: a delta the polymorphic mapper can't resolve is skipped rather than
   * failing the whole batch (a single malformed op should not poison a good batch). The {@code
   * feedbackRange*} fields are stamped with the {@code feedback-<id>} origin trace so the applier's
   * audit row + event carry provenance (the service parses the UUID back out for the traceId).
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
