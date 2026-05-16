package com.example.mealprep.adaptation.ai.internal;

import com.example.mealprep.adaptation.ai.AdaptationContext;
import com.example.mealprep.adaptation.ai.RecipeAdaptationResponse;
import com.example.mealprep.adaptation.ai.RecipeAdaptationTaskFactory;
import com.example.mealprep.adaptation.domain.entity.AdaptationJob;
import com.example.mealprep.adaptation.exception.AdaptationAiUnavailableException;
import com.example.mealprep.ai.exception.AiUnavailableException;
import com.example.mealprep.ai.spi.AiTask;

/**
 * Loud-failure Noop {@link RecipeAdaptationTaskFactory}. Wired by {@link
 * NoopRecipeAdaptationTaskFactoryConfiguration} via {@code @ConditionalOnMissingBean} so the actual
 * 01e impl supersedes it once shipped.
 *
 * <p>{@link #build} throws {@link AdaptationAiUnavailableException} so the pipeline transitions the
 * job to {@code FAILED(AI_UNAVAILABLE)} and the test surface is unambiguous.
 */
public class NoopRecipeAdaptationTaskFactory implements RecipeAdaptationTaskFactory {

  @Override
  public AiTask<RecipeAdaptationResponse> build(AdaptationJob job, AdaptationContext context) {
    throw new AdaptationAiUnavailableException(
        "RecipeAdaptationTask factory not wired - ticket 01e",
        new AiUnavailableException("RecipeAdaptationTask factory Noop active"));
  }
}
