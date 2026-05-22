package com.example.mealprep.feedback.domain.service.internal.dispatcher;

import com.example.mealprep.feedback.api.dto.UiContextDto;
import com.example.mealprep.feedback.domain.entity.RoutingFailureKind;
import com.example.mealprep.feedback.domain.service.internal.DestinationDispatcher;
import com.example.mealprep.feedback.domain.service.internal.DispatchContext;
import com.example.mealprep.feedback.domain.service.internal.DispatchResult;
import com.example.mealprep.feedback.spi.Destination;
import com.example.mealprep.feedback.spi.RecipeFeedbackHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * RECIPE destination dispatcher. Resolves the recipe id from {@code
 * classification.structuredPayload.recipeId} first, falling back to {@code uiContext.recipeId}.
 * When both are absent the dispatcher fails with {@code DESTINATION_VALIDATION} per ticket 01d §11.
 *
 * <p>Calls the cross-module {@link RecipeFeedbackHandler} SPI (Noop default in 01d; real impl
 * supplied by the adaptation-pipeline module via the naming-reconciliation adapter to {@code
 * AdaptationService.enqueueFeedbackJob}).
 */
@Component
class RecipeDestinationDispatcher implements DestinationDispatcher {

  private final RecipeFeedbackHandler recipeHandler;
  private final ObjectMapper objectMapper;

  RecipeDestinationDispatcher(RecipeFeedbackHandler recipeHandler, ObjectMapper objectMapper) {
    this.recipeHandler = recipeHandler;
    this.objectMapper = objectMapper;
  }

  @Override
  public Destination destination() {
    return Destination.RECIPE;
  }

  @Override
  public DispatchResult dispatch(DispatchContext ctx) {
    UUID recipeId = resolveRecipeId(ctx);
    if (recipeId == null) {
      return DispatchResult.failed(
          RoutingFailureKind.DESTINATION_VALIDATION, "no recipe attached to this feedback");
    }
    RecipeFeedbackHandler.Input input =
        new RecipeFeedbackHandler.Input(
            ctx.feedbackId(),
            recipeId,
            ctx.userId(),
            ctx.classification().confidence(),
            ctx.classification().extractedFeedback(),
            ctx.traceId(),
            ctx.classification().structuredPayload());
    RecipeFeedbackHandler.Result result = recipeHandler.handleRecipeFeedback(input);
    JsonNode payload = objectMapper.valueToTree(result.payload());
    return result.requiresApproval()
        ? DispatchResult.awaitingApproval(result.summary(), payload)
        : DispatchResult.applied(result.summary(), payload);
  }

  private UUID resolveRecipeId(DispatchContext ctx) {
    JsonNode payload = ctx.classification().structuredPayload();
    if (payload != null) {
      JsonNode fromPayload = payload.path("recipeId");
      if (!fromPayload.isMissingNode() && !fromPayload.isNull()) {
        try {
          return UUID.fromString(fromPayload.asText());
        } catch (IllegalArgumentException ignored) {
          // fall through to UI-context lookup
        }
      }
    }
    UiContextDto ui = ctx.uiContext();
    return ui == null ? null : ui.recipeId();
  }
}
