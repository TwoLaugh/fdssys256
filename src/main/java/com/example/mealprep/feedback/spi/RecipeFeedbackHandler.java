package com.example.mealprep.feedback.spi;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * Cross-module SPI for the RECIPE destination. The real implementation ships in {@code
 * tickets/feedback/01g} ({@code feedback.bridge.RecipeFeedbackBridge}) and adapts {@code
 * AdaptationService.enqueueFeedbackJob} (per {@code tickets/WAVE3-NAMING-RECONCILIATION.md}); until
 * then the Noop default throws and the router classifies as {@code AI_UNAVAILABLE}.
 *
 * <p>Feedback owns the SPI (lives in {@code feedback.spi.}); the cross-module direction is
 * adaptation-pipeline → feedback. This avoids feedback depending on adaptation-pipeline types at
 * compile time.
 */
public interface RecipeFeedbackHandler {

  Result handleRecipeFeedback(Input input);

  /**
   * Carrier for the recipe destination call. {@code structuredPayload} is the per-destination JSON
   * fragment the classifier emitted; the adapter teases out the rating-delta etc. when assembling
   * the {@code FeedbackJobRequest}. {@code feedbackId} + {@code confidence} (01g) drive the
   * confidence floor + idempotency row.
   */
  record Input(
      UUID feedbackId,
      UUID recipeId,
      UUID userId,
      BigDecimal confidence,
      String extractedFeedback,
      UUID traceId,
      JsonNode structuredPayload) {}

  /**
   * Carrier for the recipe destination response. {@code requiresApproval == true} maps to a {@code
   * AWAITING_USER_APPROVAL} routing-log status; {@code false} maps to {@code APPLIED}. The adapter
   * populates {@code payload} with whatever destination-result detail the dispatcher should persist
   * on the routing log row.
   */
  record Result(boolean requiresApproval, String summary, Map<String, Object> payload) {}
}
