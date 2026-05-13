package com.example.mealprep.feedback;

/**
 * Module facade — re-exports the feedback module's public service interfaces. In 01a this is a stub
 * with a single private constructor; the services land in feedback-01b ({@code
 * FeedbackQueryService} / {@code FeedbackUpdateService}) and the constructor + getters are filled
 * in then, matching the {@code NutritionModule} shape.
 */
public final class FeedbackModule {

  private FeedbackModule() {
    // No instances — facade only.
  }
}
