package com.example.mealprep.adaptation.ai.internal;

import com.example.mealprep.adaptation.ai.RecipeAdaptationTaskFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link NoopRecipeAdaptationTaskFactory} as the default {@link
 * RecipeAdaptationTaskFactory} bean when no other implementation is present in the context.
 *
 * <p><b>Critical naming rule</b>: the {@code @Bean} factory method name {@link
 * #defaultRecipeAdaptationTaskFactory()} MUST differ from the class name {@code
 * NoopRecipeAdaptationTaskFactory}. Per decisions/0010 round-5 bug-2: if the factory method is
 * named the same as the class, Spring's bean-definition-name collision triggers the
 * {@code @ConditionalOnMissingBean} mis-fire from round 5.
 *
 * <p>Same shape as {@link
 * com.example.mealprep.adaptation.domain.service.internal.NoopNutritionalKnowledgeConfiguration};
 * the 01e {@code RecipeAdaptationTaskFactoryImpl} bean registers first, the conditional sees it,
 * and the Noop is skipped.
 */
@Configuration(proxyBeanMethods = false)
public class NoopRecipeAdaptationTaskFactoryConfiguration {

  /** Factory method — bean name distinct from class name to avoid round-5 bug-2 collision. */
  @Bean
  @ConditionalOnMissingBean(RecipeAdaptationTaskFactory.class)
  RecipeAdaptationTaskFactory defaultRecipeAdaptationTaskFactory() {
    return new NoopRecipeAdaptationTaskFactory();
  }
}
