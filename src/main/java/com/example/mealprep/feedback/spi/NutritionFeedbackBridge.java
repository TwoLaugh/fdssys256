package com.example.mealprep.feedback.spi;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.UUID;

/**
 * Cross-module SPI for the NUTRITION destination. Wave-2 nutrition module does not (yet) expose an
 * {@code applyFeedback} write-API; per ticket 01d §13 we ship a bridge SPI with a Noop default and
 * flag for the nutrition team to land the real impl in a follow-up.
 */
public interface NutritionFeedbackBridge {

  Result applyFeedback(Input input);

  record Input(UUID userId, String extractedFeedback, UUID traceId, JsonNode structuredPayload) {}

  record Result(String summary, Map<String, Object> payload) {}
}
