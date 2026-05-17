package com.example.mealprep.feedback.spi;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.UUID;

/**
 * Cross-module SPI for the PROVISIONS destination. Wave-2 provisions module does not (yet) expose
 * an {@code applyFeedback} write-API; per ticket 01d §14 we ship a bridge SPI with a Noop default
 * and flag for the provisions team to land the real impl in a follow-up.
 */
public interface ProvisionsFeedbackBridge {

  Result applyFeedback(Input input);

  record Input(UUID userId, String extractedFeedback, UUID traceId, JsonNode structuredPayload) {}

  record Result(String summary, Map<String, Object> payload) {}
}
