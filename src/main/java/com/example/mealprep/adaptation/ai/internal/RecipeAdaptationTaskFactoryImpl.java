package com.example.mealprep.adaptation.ai.internal;

import com.example.mealprep.adaptation.ai.AdaptationContext;
import com.example.mealprep.adaptation.ai.RecipeAdaptationResponse;
import com.example.mealprep.adaptation.ai.RecipeAdaptationTask;
import com.example.mealprep.adaptation.ai.RecipeAdaptationTaskFactory;
import com.example.mealprep.adaptation.config.AdaptationConfig;
import com.example.mealprep.adaptation.domain.entity.AdaptationJob;
import com.example.mealprep.ai.spi.AiTask;
import com.example.mealprep.ai.spi.PromptRef;
import org.springframework.stereotype.Component;

/**
 * Real {@link RecipeAdaptationTaskFactory} — supersedes 01c's {@code
 * NoopRecipeAdaptationTaskFactory}. As a plain {@code @Component} it registers in the context, so
 * the {@code @ConditionalOnMissingBean} on {@code NoopRecipeAdaptationTaskFactoryConfiguration} no
 * longer fires and the Noop is skipped (the Noop file stays in place as the parallel-development
 * safety net — no deletion).
 *
 * <p>The {@link PromptRef} resolves the {@code RecipeAdaptationTask} v1 row that {@code
 * PromptTemplateLoader} INSERTs from {@code lld/prompts/05-recipe-adaptation.md} (its wiring table
 * declares {@code AiTask name = RecipeAdaptationTask}). Version 1 is the first loaded version; the
 * prompt engineer's content refinement bumps it append-only.
 */
@Component
public class RecipeAdaptationTaskFactoryImpl implements RecipeAdaptationTaskFactory {

  /** Matches the {@code AiTask name} row in {@code lld/prompts/05-recipe-adaptation.md}. */
  private static final PromptRef PROMPT_REF = new PromptRef("RecipeAdaptationTask", 1);

  private final AdaptationConfig config;

  public RecipeAdaptationTaskFactoryImpl(AdaptationConfig config) {
    this.config = config;
  }

  @Override
  public AiTask<RecipeAdaptationResponse> build(AdaptationJob job, AdaptationContext context) {
    return new RecipeAdaptationTask(job, context, PROMPT_REF, config);
  }
}
