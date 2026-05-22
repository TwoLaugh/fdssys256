package com.example.mealprep.feedback.domain.service.internal.dispatcher;

import com.example.mealprep.feedback.domain.service.internal.DestinationDispatcher;
import com.example.mealprep.feedback.domain.service.internal.DispatchContext;
import com.example.mealprep.feedback.domain.service.internal.DispatchResult;
import com.example.mealprep.feedback.spi.Destination;
import com.example.mealprep.feedback.spi.PreferenceFeedbackBridge;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * PREFERENCE destination dispatcher. Calls the {@link PreferenceFeedbackBridge} SPI — the
 * preference module supplies the real adapter once it lands its {@code applyFeedback} surface;
 * until then the Noop default throws and the router classifies as {@code AI_UNAVAILABLE} for the
 * 01g sweep to retry.
 *
 * <p>TODO (per LLD §Out of Scope line 933): hard-constraint refusal stays in the prompt's
 * responsibility for v1; this dispatcher does not enforce.
 */
@Component
class PreferenceDestinationDispatcher implements DestinationDispatcher {

  private final PreferenceFeedbackBridge bridge;
  private final ObjectMapper objectMapper;

  PreferenceDestinationDispatcher(PreferenceFeedbackBridge bridge, ObjectMapper objectMapper) {
    this.bridge = bridge;
    this.objectMapper = objectMapper;
  }

  @Override
  public Destination destination() {
    return Destination.PREFERENCE;
  }

  @Override
  public DispatchResult dispatch(DispatchContext ctx) {
    PreferenceFeedbackBridge.Input input =
        new PreferenceFeedbackBridge.Input(
            ctx.feedbackId(),
            ctx.userId(),
            ctx.classification().confidence(),
            ctx.classification().extractedFeedback(),
            ctx.traceId(),
            ctx.classification().structuredPayload());
    PreferenceFeedbackBridge.Result result = bridge.applyFeedback(input);
    JsonNode payload = objectMapper.valueToTree(result.payload());
    return DispatchResult.applied(result.summary(), payload);
  }
}
