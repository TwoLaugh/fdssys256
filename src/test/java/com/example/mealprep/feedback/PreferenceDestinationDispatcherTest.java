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
import com.example.mealprep.feedback.exception.PreferenceFeedbackBridgeUnavailableException;
import com.example.mealprep.feedback.spi.Destination;
import com.example.mealprep.feedback.spi.PreferenceFeedbackBridge;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class PreferenceDestinationDispatcherTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void happyPath_returnsApplied() {
    PreferenceFeedbackBridge bridge = mock(PreferenceFeedbackBridge.class);
    when(bridge.applyFeedback(any(PreferenceFeedbackBridge.Input.class)))
        .thenReturn(new PreferenceFeedbackBridge.Result("updated", Map.of("k", "v")));

    DestinationDispatcher dispatcher = dispatcher(bridge);
    DispatchResult result = dispatcher.dispatch(ctx());

    assertThat(result.status()).isEqualTo(RoutingStatus.APPLIED);
    assertThat(result.actionTaken()).isEqualTo("updated");
    assertThat(dispatcher.destination()).isEqualTo(Destination.PREFERENCE);
  }

  @Test
  void noopBridge_propagatesUnavailableException() {
    try (AnnotationConfigApplicationContext ctxApp = new AnnotationConfigApplicationContext()) {
      ctxApp.register(com.example.mealprep.feedback.config.NoopFeedbackBridgesConfiguration.class);
      ctxApp.refresh();
      PreferenceFeedbackBridge noop = ctxApp.getBean(PreferenceFeedbackBridge.class);
      DestinationDispatcher dispatcher = dispatcher(noop);
      assertThatThrownBy(() -> dispatcher.dispatch(ctx()))
          .isInstanceOf(PreferenceFeedbackBridgeUnavailableException.class);
    }
  }

  private DestinationDispatcher dispatcher(PreferenceFeedbackBridge bridge) {
    try {
      Class<?> implClass =
          Class.forName(
              "com.example.mealprep.feedback.domain.service.internal.dispatcher.PreferenceDestinationDispatcher");
      java.lang.reflect.Constructor<?> ctor =
          implClass.getDeclaredConstructor(PreferenceFeedbackBridge.class, ObjectMapper.class);
      ctor.setAccessible(true);
      return (DestinationDispatcher) ctor.newInstance(bridge, objectMapper);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }

  private DispatchContext ctx() {
    ObjectNode payload = JsonNodeFactory.instance.objectNode();
    payload.put("constraint", "no cream");
    ClassificationOutput co =
        new ClassificationOutput(
            Destination.PREFERENCE, new BigDecimal("0.95"), "no more cream sauces", payload);
    UiContextDto ui = new UiContextDto(Screen.GENERAL, null, null, null, null, null);
    return new DispatchContext(
        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), ui, co, 1);
  }
}
