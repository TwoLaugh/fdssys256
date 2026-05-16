package com.example.mealprep.feedback.domain.service.internal;

import com.example.mealprep.ai.domain.service.AiService;
import com.example.mealprep.feedback.api.dto.ClassificationResult;
import org.springframework.stereotype.Component;

/**
 * Single-method wrapper around {@link AiService#execute} for the feedback classifier. Per
 * lld/feedback.md line 527 — "the classifier is invoked exclusively from {@code
 * FeedbackClassifier}"; the wrapper exists to (a) name the seam and (b) let unit tests mock the
 * classifier outcome without going through the AI module.
 *
 * <p>AI exceptions ({@code AiUnavailableException}, {@code AiInvalidResponseException}, {@code
 * AiInvalidRequestException}, {@code AiCostBudgetExceededException}) propagate as-is. The caller
 * maps them to defer / terminal-failure paths.
 */
@Component
public class FeedbackClassifier {

  private final AiService aiService;

  public FeedbackClassifier(AiService aiService) {
    this.aiService = aiService;
  }

  public ClassificationResult classify(FeedbackClassificationContext context) {
    if (context == null) {
      throw new IllegalArgumentException("context must not be null");
    }
    return aiService.execute(new FeedbackClassificationTask(context));
  }
}
