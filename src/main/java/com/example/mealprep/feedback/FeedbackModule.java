package com.example.mealprep.feedback;

import com.example.mealprep.feedback.domain.service.FeedbackQueryService;
import com.example.mealprep.feedback.domain.service.FeedbackUpdateService;
import org.springframework.stereotype.Component;

/**
 * Module facade re-exporting the feedback module's public service interfaces. Mirrors the {@code
 * NutritionModule} / {@code HouseholdModule} shape — thin, carries no business logic. Cross- module
 * callers inject either this facade or one of the underlying service interfaces directly.
 *
 * <p>01b lands {@link FeedbackQueryService} (read surface — entry, list, routing-decision lookups)
 * and {@link FeedbackUpdateService} (submission write); subsequent tickets append impl bodies for
 * correction, clarification answer, and scheduled sweep methods on the same interfaces.
 */
@Component
public class FeedbackModule {

  private final FeedbackQueryService feedbackQueryService;
  private final FeedbackUpdateService feedbackUpdateService;

  public FeedbackModule(
      FeedbackQueryService feedbackQueryService, FeedbackUpdateService feedbackUpdateService) {
    this.feedbackQueryService = feedbackQueryService;
    this.feedbackUpdateService = feedbackUpdateService;
  }

  public FeedbackQueryService query() {
    return feedbackQueryService;
  }

  public FeedbackUpdateService update() {
    return feedbackUpdateService;
  }
}
