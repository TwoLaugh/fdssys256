package com.example.mealprep.adaptation.ai;

import com.example.mealprep.adaptation.domain.entity.AdaptationJob;
import com.example.mealprep.ai.spi.AiTask;

/**
 * Cross-ticket bridge: 01c injects this interface so the {@code AdaptationLlmInvoker} compiles
 * without {@code RecipeAdaptationTask} (which lands in 01e). 01c also ships a Noop implementation
 * ({@code NoopRecipeAdaptationTaskFactory}) that throws {@code AdaptationAiUnavailableException} so
 * the pipeline reports cleanly until 01e merges.
 *
 * <p>Pattern same as the recipe-01f / nutrition-01f cross-SPI bridge — see {@code decisions/0010
 * §What worked}.
 *
 * <p>01e replaces the Noop bean by registering a real implementation that constructs a {@code
 * RecipeAdaptationTask}; Spring's {@code @ConditionalOnMissingBean} on the Noop config defers to
 * the real bean when present.
 */
public interface RecipeAdaptationTaskFactory {

  /**
   * Build a {@code AiTask} carrying the loaded context — invoked by {@code AdaptationLlmInvoker}.
   */
  AiTask<RecipeAdaptationResponse> build(AdaptationJob job, AdaptationContext context);
}
