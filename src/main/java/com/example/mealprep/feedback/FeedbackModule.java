package com.example.mealprep.feedback;

import com.example.mealprep.feedback.ai.config.PreferenceDeltaProperties;
import com.example.mealprep.feedback.config.FeedbackRetrySweepProperties;
import com.example.mealprep.feedback.domain.service.FeedbackQueryService;
import com.example.mealprep.feedback.domain.service.FeedbackUpdateService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Module facade re-exporting the feedback module's public service interfaces. Mirrors the {@code
 * NutritionModule} / {@code HouseholdModule} shape — thin, carries no business logic. Cross- module
 * callers inject either this facade or one of the underlying service interfaces directly.
 *
 * <p>01b lands {@link FeedbackQueryService} (read surface — entry, list, routing-decision lookups)
 * and {@link FeedbackUpdateService} (submission write); subsequent tickets append impl bodies for
 * correction, clarification answer, and scheduled sweep methods on the same interfaces.
 *
 * <p>Registers {@link FeedbackRetrySweepProperties} (feedback-01i) and {@link
 * PreferenceDeltaProperties} (preference-01g) via {@code @EnableConfigurationProperties} — the
 * project has no {@code @ConfigurationPropertiesScan}, so config-property records are registered
 * explicitly at their module facade (mirrors {@code PlannerModule}).
 */
@Component
@EnableConfigurationProperties({
  FeedbackRetrySweepProperties.class,
  PreferenceDeltaProperties.class
})
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
