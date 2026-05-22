package com.example.mealprep.feedback.bridge;

import com.example.mealprep.adaptation.api.dto.AdaptationResultDto;
import com.example.mealprep.adaptation.api.dto.FeedbackJobRequest;
import com.example.mealprep.adaptation.api.dto.FeedbackJobRequest.RatingDeltaDto;
import com.example.mealprep.adaptation.domain.service.AdaptationService;
import com.example.mealprep.feedback.bridge.internal.FeedbackBridgeSupport;
import com.example.mealprep.feedback.config.FeedbackTxTemplateConfig;
import com.example.mealprep.feedback.domain.entity.BridgeDispatchStatus;
import com.example.mealprep.feedback.domain.repository.FeedbackBridgeIdempotencyRepository;
import com.example.mealprep.feedback.spi.Destination;
import com.example.mealprep.feedback.spi.RecipeFeedbackHandler;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Real RECIPE bridge (tickets/feedback/01g §15-18). Replaces {@code
 * NoopRecipeFeedbackHandlerConfiguration.NoopRecipeFeedbackHandler}: this {@code @Component}
 * satisfies {@code @ConditionalOnMissingBean(RecipeFeedbackHandler.class)} so the Noop steps aside.
 *
 * <p>Routes recipe-specific feedback to the existing adaptation pipeline. Per {@code
 * tickets/WAVE3-NAMING-RECONCILIATION.md}, the sibling LLDs' {@code
 * OptimiserService.handleRecipeFeedback(...)} forward-reference resolves to {@link
 * AdaptationService#enqueueFeedbackJob}. This is a <b>real, end-to-end</b> call — the adaptation
 * pipeline runs synchronously and returns an {@link AdaptationResultDto}; the bridge is just the
 * routing edge. Origin attribution (origin_trace = {@code feedback-<feedback_id>}, depth 1) extends
 * the pipeline's existing {@code traceId} propagation.
 */
@Component
public class RecipeFeedbackBridge extends FeedbackBridgeSupport implements RecipeFeedbackHandler {

  private static final Destination DESTINATION = Destination.RECIPE;

  private final AdaptationService adaptationService;

  public RecipeFeedbackBridge(
      AdaptationService adaptationService,
      FeedbackBridgeIdempotencyRepository idempotencyRepository,
      @Qualifier(FeedbackTxTemplateConfig.REQUIRES_NEW_TX_TEMPLATE)
          TransactionTemplate requiresNewTxTemplate,
      Clock clock) {
    super(idempotencyRepository, requiresNewTxTemplate, clock);
    this.adaptationService = adaptationService;
  }

  @Override
  public Result handleRecipeFeedback(Input input) {
    if (belowConfidenceFloor(input.confidence())) {
      recordOutcome(input.feedbackId(), DESTINATION, BridgeDispatchStatus.REJECTED_LOW_CONFIDENCE);
      log()
          .warn(
              "recipe feedback below confidence floor — skipped. feedbackId={} destination={}"
                  + " confidence={}",
              input.feedbackId(),
              DESTINATION,
              input.confidence());
      return new Result(
          false, "rejected: confidence below floor", Map.of("status", "REJECTED_LOW_CONFIDENCE"));
    }
    if (alreadyDispatched(input.feedbackId(), DESTINATION)) {
      log()
          .info(
              "recipe feedback already dispatched within idempotency window — no-op. feedbackId={}",
              input.feedbackId());
      return new Result(false, "idempotent no-op", Map.of("status", "ALREADY_DISPATCHED"));
    }

    FeedbackJobRequest request = buildRequest(input);
    try {
      AdaptationResultDto result = adaptationService.enqueueFeedbackJob(request);
      recordOutcome(input.feedbackId(), DESTINATION, BridgeDispatchStatus.DISPATCHED);
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("status", "DISPATCHED");
      payload.put("originTrace", originTrace(input.feedbackId()));
      payload.put("classification", String.valueOf(result.classification()));
      payload.put("jobId", String.valueOf(result.jobId()));
      return new Result(result.requiresApproval(), "recipe adaptation enqueued", payload);
    } catch (RuntimeException ex) {
      log()
          .error(
              "recipe adaptation dispatch failed. feedbackId={} recipeId={} actorType={}"
                  + " originTrace={}",
              input.feedbackId(),
              input.recipeId(),
              actorType(),
              originTrace(input.feedbackId()),
              ex);
      // Book FAILED + re-throw as the typed dispatch exception so the router classifies it
      // consistently (AI_UNAVAILABLE) without poisoning peers or the feedback transaction.
      throw failed(input.feedbackId(), DESTINATION, ex);
    }
  }

  private FeedbackJobRequest buildRequest(Input input) {
    return new FeedbackJobRequest(
        input.recipeId(),
        input.userId(),
        input.feedbackId(),
        input.extractedFeedback(),
        parseRatingDelta(input.structuredPayload()),
        input.traceId(),
        null);
  }

  /**
   * Pull the optional rating-dimension drops the classifier may have emitted. Absent fields map to
   * {@code null} ("no opinion on this dimension") per {@link RatingDeltaDto}.
   */
  private static RatingDeltaDto parseRatingDelta(JsonNode payload) {
    if (payload == null) {
      return new RatingDeltaDto(null, null, null, null);
    }
    JsonNode delta = payload.path("ratingDelta");
    JsonNode source = delta.isObject() ? delta : payload;
    return new RatingDeltaDto(
        decimalOrNull(source, "taste"),
        decimalOrNull(source, "effortWorthIt"),
        decimalOrNull(source, "portionFit"),
        decimalOrNull(source, "repeatValue"));
  }

  private static BigDecimal decimalOrNull(JsonNode node, String field) {
    JsonNode v = node.path(field);
    if (v.isMissingNode() || v.isNull() || !v.isNumber()) {
      return null;
    }
    return v.decimalValue();
  }
}
