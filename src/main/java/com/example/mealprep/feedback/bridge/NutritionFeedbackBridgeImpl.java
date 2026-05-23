package com.example.mealprep.feedback.bridge;

import com.example.mealprep.feedback.bridge.internal.FeedbackBridgeSupport;
import com.example.mealprep.feedback.config.FeedbackTxTemplateConfig;
import com.example.mealprep.feedback.domain.entity.BridgeDispatchStatus;
import com.example.mealprep.feedback.domain.repository.FeedbackBridgeIdempotencyRepository;
import com.example.mealprep.feedback.spi.Destination;
import com.example.mealprep.feedback.spi.NutritionFeedbackBridge;
import com.example.mealprep.nutrition.api.dto.FeedbackTargetAdjustment;
import com.example.mealprep.nutrition.domain.entity.AdjustmentDirection;
import com.example.mealprep.nutrition.domain.entity.AdjustmentMagnitude;
import com.example.mealprep.nutrition.domain.service.NutritionUpdateService;
import com.example.mealprep.nutrition.exception.InvalidFeedbackAdjustmentException;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Real NUTRITION bridge (tickets/feedback/01g §8-11, made live by tickets/nutrition/01i). Replaces
 * {@code NoopFeedbackBridgesConfiguration.NoopNutritionFeedbackBridge}: this {@code @Component}
 * satisfies {@code @ConditionalOnMissingBean(NutritionFeedbackBridge.class)} so the Noop steps
 * aside.
 *
 * <p>The classifier emits a single-field target adjustment ({@code {target, direction, magnitude,
 * absoluteValue, reason}}). This bridge parses that payload into a {@link FeedbackTargetAdjustment}
 * and calls {@link NutritionUpdateService#applyFeedbackAdjustment} — a single-field, relative write
 * that is safe to call fire-and-forget (no client {@code expectedVersion}; the {@code @Version}
 * bump is internal). On success it books a {@code DISPATCHED} idempotency row and returns; an
 * unknown target ({@link InvalidFeedbackAdjustmentException}) books {@code FAILED} + a structured
 * log and rethrows {@code FeedbackBridgeDispatchFailedException} so the router records the failure
 * (reason {@code unsupported-adjustment-target}). This mirrors the preference / provisions bridges'
 * catch shape.
 *
 * <p><b>AFTER_COMMIT atomicity (decision-log 0010)</b>: this bridge runs under its {@code
 * REQUIRES_NEW} {@code TransactionTemplate}; {@code applyFeedbackAdjustment} keeps plain
 * {@code @Transactional} (REQUIRED) so it JOINS this template tx — the target update + audit row
 * commit together with the {@code DISPATCHED} idempotency row as one unit. The origin trace ({@code
 * feedback-<feedback_id>}, actor_type = AI) is stamped onto the adjustment so the audit row carries
 * the cross-cutting provenance.
 */
@Component
public class NutritionFeedbackBridgeImpl extends FeedbackBridgeSupport
    implements NutritionFeedbackBridge {

  private static final Destination DESTINATION = Destination.NUTRITION;

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
   * Parse the classifier payload into a {@link FeedbackTargetAdjustment} and dispatch it. The
   * adjustment's {@code reason} carries the {@code feedback-<feedback_id>} origin trace so the
   * nutrition audit row records the cross-cutting provenance ({@code actor_type = AI}).
   */
  private Result applyAdjustment(Input input) {
    String trace = originTrace(input.feedbackId());
    FeedbackTargetAdjustment adjustment;
    try {
      adjustment = parseAdjustment(input, trace);
    } catch (IllegalArgumentException malformed) {
      log()
          .error(
              "nutrition feedback adjustment payload malformed. feedbackId={} actorType={}"
                  + " originTrace={}",
              input.feedbackId(),
              actorType(),
              trace,
              malformed);
      throw failed(input.feedbackId(), DESTINATION, malformed);
    }

    try {
      nutritionUpdateService.applyFeedbackAdjustment(input.userId(), adjustment);
      recordOutcome(input.feedbackId(), DESTINATION, BridgeDispatchStatus.DISPATCHED);
      log()
          .info(
              "nutrition target adjusted via feedback. feedbackId={} target={} direction={}"
                  + " magnitude={} actorType={} originTrace={}",
              input.feedbackId(),
              adjustment.target(),
              adjustment.direction(),
              adjustment.magnitude(),
              actorType(),
              trace);
      return new Result(
          "nutrition target adjusted: " + adjustment.target(),
          Map.of("status", "DISPATCHED", "originTrace", trace));
    } catch (InvalidFeedbackAdjustmentException unknownTarget) {
      // The classifier emitted a target nutrition does not expose. Book FAILED + structured log,
      // then throw so the router records the failure — mirror the preference/provisions bridges.
      log()
          .error(
              "nutrition feedback adjustment target unsupported ({}). feedbackId={} target={}"
                  + " actorType={} originTrace={}",
              "unsupported-adjustment-target",
              input.feedbackId(),
              adjustment.target(),
              actorType(),
              trace,
              unknownTarget);
      throw failed(input.feedbackId(), DESTINATION, unknownTarget);
    }
  }

  /**
   * Build the {@link FeedbackTargetAdjustment} from the classifier payload. {@code direction} /
   * {@code magnitude} are parsed case-insensitively; a missing / unparseable enum throws {@code
   * IllegalArgumentException} (booked FAILED). {@code reason} carries the origin trace per the
   * origin-tracking convention.
   */
  private static FeedbackTargetAdjustment parseAdjustment(Input input, String trace) {
    JsonNode payload = input.structuredPayload();
    String target = textOrNull(payload, "target");
    AdjustmentDirection direction = parseDirection(textOrNull(payload, "direction"));
    AdjustmentMagnitude magnitude = parseMagnitude(textOrNull(payload, "magnitude"));
    BigDecimal absoluteValue = decimalOrNull(payload, "absoluteValue");
    return new FeedbackTargetAdjustment(target, direction, magnitude, absoluteValue, trace);
  }

  private static AdjustmentDirection parseDirection(String raw) {
    if (raw == null) {
      throw new IllegalArgumentException("missing direction");
    }
    return AdjustmentDirection.valueOf(raw.trim().toUpperCase(Locale.ROOT));
  }

  private static AdjustmentMagnitude parseMagnitude(String raw) {
    if (raw == null) {
      throw new IllegalArgumentException("missing magnitude");
    }
    return AdjustmentMagnitude.valueOf(raw.trim().toUpperCase(Locale.ROOT));
  }

  private static BigDecimal decimalOrNull(JsonNode payload, String field) {
    if (payload == null) {
      return null;
    }
    JsonNode node = payload.path(field);
    if (node.isMissingNode() || node.isNull()) {
      return null;
    }
    return node.decimalValue();
  }

  private static String textOrNull(JsonNode payload, String field) {
    if (payload == null) {
      return null;
    }
    JsonNode node = payload.path(field);
    return node.isMissingNode() || node.isNull() ? null : node.asText();
  }
}
