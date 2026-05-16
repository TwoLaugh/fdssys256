package com.example.mealprep.feedback;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.feedback.api.dto.Screen;
import com.example.mealprep.feedback.api.dto.UiContextDto;
import com.example.mealprep.feedback.domain.service.internal.FeedbackClassificationContext;
import com.example.mealprep.feedback.spi.Destination;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link FeedbackClassificationContext} — screen-to-prompt-token mapping and {@code
 * recent_classifications = []} behaviour.
 */
class FeedbackClassificationContextTest {

  @Test
  void toPromptToken_mapsEveryScreen() {
    assertThat(FeedbackClassificationContext.toPromptToken(Screen.RECIPE_DETAIL))
        .isEqualTo("recipe_page");
    assertThat(FeedbackClassificationContext.toPromptToken(Screen.PLAN_MEAL_DETAIL))
        .isEqualTo("plan_view");
    assertThat(FeedbackClassificationContext.toPromptToken(Screen.PLAN_VIEW))
        .isEqualTo("plan_view");
    assertThat(FeedbackClassificationContext.toPromptToken(Screen.GROCERY))
        .isEqualTo("shopping_list");
    assertThat(FeedbackClassificationContext.toPromptToken(Screen.NUTRITION_DASHBOARD))
        .isEqualTo("nutrition_dashboard");
    assertThat(FeedbackClassificationContext.toPromptToken(Screen.SETTINGS)).isEqualTo("general");
    assertThat(FeedbackClassificationContext.toPromptToken(Screen.GENERAL)).isEqualTo("general");
  }

  @Test
  void toRendererMap_putsFeedbackTextAndScreenToken() {
    FeedbackClassificationContext ctx =
        new FeedbackClassificationContext(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "salt was too much",
            new UiContextDto(Screen.RECIPE_DETAIL, UUID.randomUUID(), 1, null, null, null),
            Optional.empty(),
            Optional.empty(),
            1);

    Map<String, Object> map = ctx.toRendererMap();
    assertThat(map.get("feedback_text")).isEqualTo("salt was too much");
    assertThat(map.get("screen_context")).isEqualTo("recipe_page");
    assertThat(map.get("recent_classifications")).isEqualTo(List.of());
    assertThat(map.get("attempt_number")).isEqualTo(1);
    assertThat(map.get("current_meal_context")).isNotNull();
  }

  @Test
  void toRendererMap_currentMealContextNull_whenNoIds() {
    FeedbackClassificationContext ctx =
        new FeedbackClassificationContext(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "x",
            new UiContextDto(Screen.GENERAL, null, null, null, null, null),
            Optional.empty(),
            Optional.empty(),
            1);
    assertThat(ctx.toRendererMap().get("current_meal_context")).isNull();
  }

  @Test
  void toRendererMap_appendsClarificationAndHintWhenPresent() {
    FeedbackClassificationContext ctx =
        new FeedbackClassificationContext(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "x",
            new UiContextDto(Screen.GENERAL, null, null, null, null, null),
            Optional.of("the user meant the recipe"),
            Optional.of(Destination.RECIPE),
            2);
    Map<String, Object> map = ctx.toRendererMap();
    assertThat(map.get("user_clarification_text")).isEqualTo("the user meant the recipe");
    assertThat(map.get("user_selected_hint")).isEqualTo("RECIPE");
    assertThat(map.get("attempt_number")).isEqualTo(2);
  }

  @Test
  void toRendererMap_recentClassificationsAlwaysEmptyIn01c() {
    FeedbackClassificationContext ctx =
        new FeedbackClassificationContext(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "x",
            new UiContextDto(Screen.SETTINGS, null, null, null, null, null),
            Optional.empty(),
            Optional.empty(),
            1);
    Object recent = ctx.toRendererMap().get("recent_classifications");
    assertThat(recent).isEqualTo(List.of());
  }
}
