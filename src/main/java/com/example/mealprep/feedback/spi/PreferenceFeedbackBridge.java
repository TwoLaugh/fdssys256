package com.example.mealprep.feedback.spi;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.UUID;

/**
 * Cross-module SPI for the PREFERENCE destination. Wave-2 preference module does not (yet) expose
 * an {@code applyFeedback} write-API; per ticket 01d §12 we ship a bridge SPI with a Noop default
 * and flag for the preference team to land the real impl in a follow-up.
 *
 * <p>Feedback owns the SPI; the preference module supplies a {@code @Component} adapter when it
 * lands its real {@code applyFeedback} surface.
 */
public interface PreferenceFeedbackBridge {

  Result applyFeedback(Input input);

  record Input(UUID userId, String extractedFeedback, UUID traceId, JsonNode structuredPayload) {}

  record Result(String summary, Map<String, Object> payload) {}
}
