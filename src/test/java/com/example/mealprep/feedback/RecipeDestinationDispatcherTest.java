package com.example.mealprep.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.mealprep.feedback.api.dto.ClassificationOutput;
import com.example.mealprep.feedback.api.dto.Screen;
import com.example.mealprep.feedback.api.dto.UiContextDto;
import com.example.mealprep.feedback.domain.entity.RoutingFailureKind;
import com.example.mealprep.feedback.domain.entity.RoutingStatus;
import com.example.mealprep.feedback.domain.service.internal.DestinationDispatcher;
import com.example.mealprep.feedback.domain.service.internal.DestinationDispatcherRegistry;
import com.example.mealprep.feedback.domain.service.internal.DispatchContext;
import com.example.mealprep.feedback.domain.service.internal.DispatchResult;
import com.example.mealprep.feedback.exception.RecipeFeedbackHandlerUnavailableException;
import com.example.mealprep.feedback.spi.Destination;
import com.example.mealprep.feedback.spi.RecipeFeedbackHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/**
 * Unit tests for the RECIPE dispatcher: recipe-id resolution (payload, fallback, missing), happy +
 * approval + Noop-throws paths.
 */
class RecipeDestinationDispatcherTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void payloadRecipeIdWins_appliesResult() {
    UUID recipeId = UUID.randomUUID();
    RecipeFeedbackHandler handler = mock(RecipeFeedbackHandler.class);
    when(handler.handleRecipeFeedback(any(RecipeFeedbackHandler.Input.class)))
        .thenReturn(new RecipeFeedbackHandler.Result(false, "adapted", Map.of("k", "v")));

    DestinationDispatcher dispatcher = dispatcher(handler);
    DispatchResult result =
        dispatcher.dispatch(ctxWithPayloadRecipeId(recipeId, UUID.randomUUID()));

    assertThat(result.status()).isEqualTo(RoutingStatus.APPLIED);
    assertThat(result.actionTaken()).isEqualTo("adapted");
  }

  @Test
  void payloadMissing_fallsBackToUiContextRecipeId() {
    UUID uiRecipe = UUID.randomUUID();
    RecipeFeedbackHandler handler = mock(RecipeFeedbackHandler.class);
    when(handler.handleRecipeFeedback(any(RecipeFeedbackHandler.Input.class)))
        .thenReturn(new RecipeFeedbackHandler.Result(false, "adapted", Map.of()));

    DestinationDispatcher dispatcher = dispatcher(handler);
    DispatchContext ctx = ctxWithNoPayloadRecipeId(uiRecipe);
    DispatchResult result = dispatcher.dispatch(ctx);

    assertThat(result.status()).isEqualTo(RoutingStatus.APPLIED);
  }

  @Test
  void payloadAndUiContextMissing_failsValidation() {
    RecipeFeedbackHandler handler = mock(RecipeFeedbackHandler.class);
    DestinationDispatcher dispatcher = dispatcher(handler);

    DispatchResult result = dispatcher.dispatch(ctxWithNoPayloadRecipeId(null));

    assertThat(result.status()).isEqualTo(RoutingStatus.FAILED);
    assertThat(result.failureKind()).isEqualTo(RoutingFailureKind.DESTINATION_VALIDATION);
    assertThat(result.failureMessage()).isEqualTo("no recipe attached to this feedback");
  }

  @Test
  void requiresApproval_mapsToAwaitingApproval() {
    UUID recipeId = UUID.randomUUID();
    RecipeFeedbackHandler handler = mock(RecipeFeedbackHandler.class);
    when(handler.handleRecipeFeedback(any(RecipeFeedbackHandler.Input.class)))
        .thenReturn(new RecipeFeedbackHandler.Result(true, "proposed", Map.of("k", "v")));

    DestinationDispatcher dispatcher = dispatcher(handler);
    DispatchResult result = dispatcher.dispatch(ctxWithPayloadRecipeId(recipeId, null));

    assertThat(result.status()).isEqualTo(RoutingStatus.AWAITING_USER_APPROVAL);
  }

  @Test
  void noopHandler_propagatesUnavailableException() {
    UUID recipeId = UUID.randomUUID();
    // Wire the Noop config via Spring so we can call the real Noop bean.
    try (AnnotationConfigApplicationContext ctxApp = new AnnotationConfigApplicationContext()) {
      ctxApp.register(
          com.example.mealprep.feedback.config.NoopRecipeFeedbackHandlerConfiguration.class);
      ctxApp.refresh();
      ApplicationContext appCtx = ctxApp;
      RecipeFeedbackHandler noop = appCtx.getBean(RecipeFeedbackHandler.class);
      DestinationDispatcher dispatcher = dispatcher(noop);
      assertThatThrownBy(() -> dispatcher.dispatch(ctxWithPayloadRecipeId(recipeId, null)))
          .isInstanceOf(RecipeFeedbackHandlerUnavailableException.class);
    }
  }

  private DestinationDispatcher dispatcher(RecipeFeedbackHandler handler) {
    try {
      Class<?> implClass =
          Class.forName(
              "com.example.mealprep.feedback.domain.service.internal.dispatcher.RecipeDestinationDispatcher");
      java.lang.reflect.Constructor<?> ctor =
          implClass.getDeclaredConstructor(RecipeFeedbackHandler.class, ObjectMapper.class);
      ctor.setAccessible(true);
      return (DestinationDispatcher) ctor.newInstance(handler, objectMapper);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }

  private DispatchContext ctxWithPayloadRecipeId(UUID payloadRecipeId, UUID uiContextRecipeId) {
    ObjectNode payload = JsonNodeFactory.instance.objectNode();
    payload.put("recipeId", payloadRecipeId.toString());
    payload.put("affectsPlan", true);
    ClassificationOutput co =
        new ClassificationOutput(
            Destination.RECIPE, new BigDecimal("0.92"), "make it lighter", payload);
    UiContextDto ui =
        uiContextRecipeId == null
            ? new UiContextDto(Screen.GENERAL, null, null, null, null, null)
            : new UiContextDto(Screen.RECIPE_DETAIL, uiContextRecipeId, 1, null, null, null);
    return new DispatchContext(
        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), ui, co, 1);
  }

  private DispatchContext ctxWithNoPayloadRecipeId(UUID uiContextRecipeId) {
    ObjectNode payload = JsonNodeFactory.instance.objectNode();
    payload.put("affectsPlan", true);
    ClassificationOutput co =
        new ClassificationOutput(Destination.RECIPE, new BigDecimal("0.92"), "extracted", payload);
    UiContextDto ui =
        uiContextRecipeId == null
            ? new UiContextDto(Screen.GENERAL, null, null, null, null, null)
            : new UiContextDto(Screen.RECIPE_DETAIL, uiContextRecipeId, 1, null, null, null);
    return new DispatchContext(
        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), ui, co, 1);
  }

  // Helper to silence unused-import for assertions referencing the registry type.
  @SuppressWarnings("unused")
  private void unusedDependencyImport(DestinationDispatcherRegistry r) {}
}
