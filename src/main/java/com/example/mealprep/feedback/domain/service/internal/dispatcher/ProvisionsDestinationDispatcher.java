package com.example.mealprep.feedback.domain.service.internal.dispatcher;

import com.example.mealprep.feedback.domain.service.internal.DestinationDispatcher;
import com.example.mealprep.feedback.domain.service.internal.DispatchContext;
import com.example.mealprep.feedback.domain.service.internal.DispatchResult;
import com.example.mealprep.feedback.spi.Destination;
import com.example.mealprep.feedback.spi.ProvisionsFeedbackBridge;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * PROVISIONS destination dispatcher. Calls the {@link ProvisionsFeedbackBridge} SPI — the
 * provisions module supplies the real adapter once it lands {@code applyFeedback}; until then the
 * Noop default throws and the router classifies as {@code AI_UNAVAILABLE}.
 */
@Component
class ProvisionsDestinationDispatcher implements DestinationDispatcher {

  private final ProvisionsFeedbackBridge bridge;
  private final ObjectMapper objectMapper;

  ProvisionsDestinationDispatcher(ProvisionsFeedbackBridge bridge, ObjectMapper objectMapper) {
    this.bridge = bridge;
    this.objectMapper = objectMapper;
  }

  @Override
  public Destination destination() {
    return Destination.PROVISIONS;
  }

  @Override
  public DispatchResult dispatch(DispatchContext ctx) {
    ProvisionsFeedbackBridge.Input input =
        new ProvisionsFeedbackBridge.Input(
            ctx.userId(),
            ctx.classification().extractedFeedback(),
            ctx.traceId(),
            ctx.classification().structuredPayload());
    ProvisionsFeedbackBridge.Result result = bridge.applyFeedback(input);
    JsonNode payload = objectMapper.valueToTree(result.payload());
    return DispatchResult.applied(result.summary(), payload);
  }
}
