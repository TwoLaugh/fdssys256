package com.example.mealprep.feedback.spi;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * Cross-module SPI for the PROVISIONS destination. The real adapter ships in {@code
 * tickets/feedback/01g} ({@code feedback.bridge.ProvisionsFeedbackBridgeImpl}); until then the Noop
 * default throws and the router classifies as {@code AI_UNAVAILABLE}.
 *
 * <p>The {@code Input} carries {@code feedbackId} + {@code confidence} (01g) so the bridge can
 * enforce the confidence floor and book an idempotency row.
 */
public interface ProvisionsFeedbackBridge {

  Result applyFeedback(Input input);

  record Input(
      UUID feedbackId,
      UUID userId,
      BigDecimal confidence,
      String extractedFeedback,
      UUID traceId,
      JsonNode structuredPayload) {}

  record Result(String summary, Map<String, Object> payload) {}
}
