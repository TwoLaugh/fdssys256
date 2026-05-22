package com.example.mealprep.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.mealprep.feedback.api.dto.ClassificationOutput;
import com.example.mealprep.feedback.api.dto.Screen;
import com.example.mealprep.feedback.api.dto.UiContextDto;
import com.example.mealprep.feedback.domain.entity.RoutingStatus;
import com.example.mealprep.feedback.domain.service.internal.DestinationDispatcher;
import com.example.mealprep.feedback.domain.service.internal.DispatchContext;
import com.example.mealprep.feedback.domain.service.internal.DispatchResult;
import com.example.mealprep.feedback.exception.FeedbackBridgeDispatchFailedException;
import com.example.mealprep.feedback.spi.Destination;
import com.example.mealprep.feedback.spi.ProvisionsFeedbackBridge;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProvisionsDestinationDispatcherTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void happyPath_returnsApplied() {
    ProvisionsFeedbackBridge bridge = mock(ProvisionsFeedbackBridge.class);
    when(bridge.applyFeedback(any(ProvisionsFeedbackBridge.Input.class)))
        .thenReturn(new ProvisionsFeedbackBridge.Result("provisions updated", Map.of()));

    DestinationDispatcher dispatcher = dispatcher(bridge);
    DispatchResult result = dispatcher.dispatch(ctx());

    assertThat(result.status()).isEqualTo(RoutingStatus.APPLIED);
    assertThat(dispatcher.destination()).isEqualTo(Destination.PROVISIONS);
  }

  @Test
  void bridgeDispatchFailure_propagates() {
    // The Noop config was removed in 01g; the real bridge throws FeedbackBridgeDispatchFailed on a
    // failed/deferred destination call (unsupported action, missing field, deferred surface), which
    // the dispatcher must propagate so the router classifies as AI_UNAVAILABLE.
    ProvisionsFeedbackBridge bridge = mock(ProvisionsFeedbackBridge.class);
    when(bridge.applyFeedback(any(ProvisionsFeedbackBridge.Input.class)))
        .thenThrow(
            new FeedbackBridgeDispatchFailedException(
                Destination.PROVISIONS,
                UUID.randomUUID(),
                new UnsupportedOperationException("unsupported-provisions-action")));
    DestinationDispatcher dispatcher = dispatcher(bridge);
    assertThatThrownBy(() -> dispatcher.dispatch(ctx()))
        .isInstanceOf(FeedbackBridgeDispatchFailedException.class);
  }

  private DestinationDispatcher dispatcher(ProvisionsFeedbackBridge bridge) {
    try {
      Class<?> implClass =
          Class.forName(
              "com.example.mealprep.feedback.domain.service.internal.dispatcher.ProvisionsDestinationDispatcher");
      java.lang.reflect.Constructor<?> ctor =
          implClass.getDeclaredConstructor(ProvisionsFeedbackBridge.class, ObjectMapper.class);
      ctor.setAccessible(true);
      return (DestinationDispatcher) ctor.newInstance(bridge, objectMapper);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }

  private DispatchContext ctx() {
    ObjectNode payload = JsonNodeFactory.instance.objectNode();
    payload.put("kept", "extra rice");
    ClassificationOutput co =
        new ClassificationOutput(
            Destination.PROVISIONS, new BigDecimal("0.95"), "save leftovers", payload);
    UiContextDto ui = new UiContextDto(Screen.GENERAL, null, null, null, null, null);
    return new DispatchContext(
        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), ui, co, 1);
  }
}
