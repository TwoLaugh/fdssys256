package com.example.mealprep.feedback.domain.service.internal;

import com.example.mealprep.feedback.api.dto.Screen;
import com.example.mealprep.feedback.api.dto.UiContextDto;
import com.example.mealprep.feedback.spi.Destination;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Carrier record for everything the classifier task needs to render the user prompt. Per
 * lld/feedback.md §Classifier (AiTask) lines 549-560.
 *
 * <p>{@code userClarificationText} and {@code userSelectedHint} are populated on the
 * re-classification path after a user answers a clarification query (feedback-01e); empty on first
 * attempt.
 */
public record FeedbackClassificationContext(
    UUID userId,
    UUID traceId,
    String feedbackText,
    UiContextDto uiContext,
    Optional<String> userClarificationText,
    Optional<Destination> userSelectedHint,
    int attemptNumber) {

  /**
   * Maps each {@link Screen} to its prompt-template token. The mapping lives here (rather than on
   * {@code Screen}) to keep the enum free of prompt-engineering concerns. Tokens match the
   * placeholders in {@code lld/prompts/04-feedback-classification.md}.
   */
  public static String toPromptToken(Screen screen) {
    return switch (screen) {
      case RECIPE_DETAIL -> "recipe_page";
      case PLAN_MEAL_DETAIL, PLAN_VIEW -> "plan_view";
      case GROCERY -> "shopping_list";
      case NUTRITION_DASHBOARD -> "nutrition_dashboard";
      case SETTINGS, GENERAL -> "general";
    };
  }

  /**
   * Build the variable map handed to {@link com.example.mealprep.ai.spi.AiTask#variables()}. Keys
   * match the placeholders in {@code prompts/04-feedback-classification.md}:
   *
   * <ul>
   *   <li>{@code feedback_text} — the raw user submission text
   *   <li>{@code screen_context} — token derived from {@code uiContext.screen()}
   *   <li>{@code current_meal_context} — small map of {@code recipeId}/{@code mealSlotId} or null
   *   <li>{@code recent_classifications} — empty list in 01c; populated by future enhancement (see
   *       feedback-01g)
   *   <li>{@code user_clarification_text} / {@code user_selected_hint} — appended when
   *       re-classifying after a clarification answer
   * </ul>
   */
  public Map<String, Object> toRendererMap() {
    Map<String, Object> map = new HashMap<>();
    map.put("feedback_text", feedbackText);
    map.put("screen_context", uiContext == null ? "general" : toPromptToken(uiContext.screen()));
    map.put("current_meal_context", currentMealContext());
    // TODO(feedback-01g): populate last-5 recent classifications cross-module query.
    map.put("recent_classifications", List.of());
    userClarificationText.ifPresent(t -> map.put("user_clarification_text", t));
    userSelectedHint.ifPresent(h -> map.put("user_selected_hint", h.name()));
    map.put("attempt_number", attemptNumber);
    return map;
  }

  private Map<String, Object> currentMealContext() {
    if (uiContext == null) {
      return null;
    }
    if (uiContext.recipeId() == null && uiContext.mealSlotId() == null) {
      return null;
    }
    Map<String, Object> meal = new HashMap<>();
    meal.put("recipeId", uiContext.recipeId());
    meal.put("mealSlotId", uiContext.mealSlotId());
    meal.put("eatenAt", null);
    return meal;
  }
}
