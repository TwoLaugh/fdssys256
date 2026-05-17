package com.example.mealprep.feedback.domain.service.internal.dispatcher;

import com.example.mealprep.feedback.domain.service.internal.DestinationDispatcher;
import com.example.mealprep.feedback.domain.service.internal.DispatchContext;
import com.example.mealprep.feedback.domain.service.internal.DispatchResult;
import com.example.mealprep.feedback.spi.Destination;
import com.example.mealprep.feedback.spi.NutritionFeedbackBridge;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * NUTRITION destination dispatcher. Calls the {@link NutritionFeedbackBridge} SPI — the nutrition
 * module supplies the real adapter once it lands {@code applyFeedback}; until then the Noop default
 * throws and the router classifies as {@code AI_UNAVAILABLE}.
 */
@Component
class NutritionDestinationDispatcher implements DestinationDispatcher {

  private final NutritionFeedbackBridge bridge;
  private final ObjectMapper objectMapper;

  NutritionDestinationDispatcher(NutritionFeedbackBridge bridge, ObjectMapper objectMapper) {
    this.bridge = bridge;
    this.objectMapper = objectMapper;
  }

  @Override
  public Destination destination() {
    return Destination.NUTRITION;
  }

  @Override
  public DispatchResult dispatch(DispatchContext ctx) {
    NutritionFeedbackBridge.Input input =
        new NutritionFeedbackBridge.Input(
            ctx.userId(),
            ctx.classification().extractedFeedback(),
            ctx.traceId(),
            ctx.classification().structuredPayload());
    NutritionFeedbackBridge.Result result = bridge.applyFeedback(input);
    JsonNode payload = objectMapper.valueToTree(result.payload());
    return DispatchResult.applied(result.summary(), payload);
  }
}
