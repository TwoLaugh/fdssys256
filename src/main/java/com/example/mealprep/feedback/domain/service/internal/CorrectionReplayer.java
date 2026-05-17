package com.example.mealprep.feedback.domain.service.internal;

import com.example.mealprep.feedback.api.dto.ClassificationOutput;
import com.example.mealprep.feedback.api.dto.CorrectionRequest;
import com.example.mealprep.feedback.domain.document.UiContextDocument;
import com.example.mealprep.feedback.domain.entity.CorrectionReplayStatus;
import com.example.mealprep.feedback.domain.entity.FeedbackEntry;
import com.example.mealprep.feedback.domain.entity.RoutingDecision;
import com.example.mealprep.feedback.domain.entity.RoutingFailureKind;
import com.example.mealprep.feedback.domain.entity.RoutingStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

/**
 * Builds the synthetic {@link ClassificationOutput} for a misclassification correction and re-fires
 * it through {@link FeedbackRouter#routeOneForReplay} (lld/feedback.md §Flow 4 steps 6-7, ticket
 * 01f §9-11). Also maps the dispatch outcome to the correction's {@link CorrectionReplayStatus}.
 *
 * <p>Synthetic shape: {@code destination = newDestination}, {@code confidence = 1.0} (user-attested
 * ground truth), {@code extractedFeedback = entry.text} (full text — the original split is no
 * longer authoritative), {@code structuredPayload} derived from the entry's UI context (non-null
 * fields only). Decision is {@code AUTO_ROUTED} since confidence is 1.0.
 */
@Component
public class CorrectionReplayer {

  private final FeedbackRouter router;
  private final ObjectMapper objectMapper;

  public CorrectionReplayer(FeedbackRouter router, ObjectMapper objectMapper) {
    this.router = router;
    this.objectMapper = objectMapper;
  }

  /** Build the synthetic scored classification for the corrected destination. */
  public ConfidenceGate.ScoredClassification buildSynthetic(
      FeedbackEntry entry, CorrectionRequest request) {
    ObjectNode payload = objectMapper.createObjectNode();
    UiContextDocument doc = entry.getUiContext();
    if (doc != null) {
      if (doc.recipeId() != null) {
        payload.put("recipeId", doc.recipeId().toString());
      }
      if (doc.mealSlotId() != null) {
        payload.put("mealSlotId", doc.mealSlotId().toString());
      }
      if (doc.planId() != null) {
        payload.put("planId", doc.planId().toString());
      }
    }
    ClassificationOutput synthetic =
        new ClassificationOutput(
            request.newDestination(), BigDecimal.ONE, entry.getText(), payload);
    return new ConfidenceGate.ScoredClassification(synthetic, RoutingDecision.AUTO_ROUTED);
  }

  /** Re-fire the synthetic classification through the router's replay path. */
  public FeedbackRouter.RouteReplayResult replay(
      FeedbackEntry entry, ConfidenceGate.ScoredClassification scored) {
    return router.routeOneForReplay(entry.getId(), scored);
  }

  /**
   * Map the replay dispatch outcome to the correction's {@link CorrectionReplayStatus}
   * (lld/feedback.md §Flow 4 step 7, ticket 01f §13):
   *
   * <ul>
   *   <li>{@code APPLIED} / {@code AWAITING_USER_APPROVAL} → {@code APPLIED}
   *   <li>{@code FAILED} + {@code DESTINATION_VALIDATION}/{@code DESTINATION_BUSINESS} → {@code
   *       DESTINATION_REJECTED}
   *   <li>{@code FAILED} + {@code TRANSIENT}/{@code AI_UNAVAILABLE}/{@code UNKNOWN} → {@code
   *       FAILED}
   * </ul>
   */
  public CorrectionReplayStatus mapReplayStatus(
      RoutingStatus status, RoutingFailureKind failureKind) {
    if (status == RoutingStatus.APPLIED || status == RoutingStatus.AWAITING_USER_APPROVAL) {
      return CorrectionReplayStatus.APPLIED;
    }
    if (failureKind == RoutingFailureKind.DESTINATION_VALIDATION
        || failureKind == RoutingFailureKind.DESTINATION_BUSINESS) {
      return CorrectionReplayStatus.DESTINATION_REJECTED;
    }
    return CorrectionReplayStatus.FAILED;
  }
}
