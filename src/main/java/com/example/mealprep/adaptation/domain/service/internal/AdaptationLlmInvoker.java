package com.example.mealprep.adaptation.domain.service.internal;

import com.example.mealprep.adaptation.ai.AdaptationContext;
import com.example.mealprep.adaptation.ai.RecipeAdaptationResponse;
import com.example.mealprep.adaptation.ai.RecipeAdaptationTaskFactory;
import com.example.mealprep.adaptation.domain.entity.AdaptationJob;
import com.example.mealprep.adaptation.exception.AdaptationAiUnavailableException;
import com.example.mealprep.ai.domain.service.AiService;
import com.example.mealprep.ai.exception.AiUnavailableException;
import com.example.mealprep.ai.spi.AiTask;
import org.springframework.stereotype.Component;

/**
 * Stage-C dispatcher: builds the {@code RecipeAdaptationTask} via the injected factory and
 * dispatches via {@link AiService#execute}. {@link AiUnavailableException} is the single exception
 * this component catches; everything else propagates to the orchestrator unchanged.
 *
 * <p>Per ticket 01c §Step 5; LLD §Stage C lines 746-747. The wrap into {@link
 * AdaptationAiUnavailableException} happens here so callers see only adaptation-module exceptions.
 */
@Component
public class AdaptationLlmInvoker {

  private final AiService aiService;
  private final RecipeAdaptationTaskFactory taskFactory;

  public AdaptationLlmInvoker(AiService aiService, RecipeAdaptationTaskFactory taskFactory) {
    this.aiService = aiService;
    this.taskFactory = taskFactory;
  }

  /** Build the task and dispatch. */
  public RecipeAdaptationResponse invoke(AdaptationJob job, AdaptationContext context) {
    AiTask<RecipeAdaptationResponse> task = taskFactory.build(job, context);
    try {
      return aiService.execute(task);
    } catch (AiUnavailableException e) {
      throw new AdaptationAiUnavailableException("ai-unavailable: " + e.getMessage(), e);
    }
  }
}
