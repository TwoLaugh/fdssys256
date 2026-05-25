package com.example.mealprep.planner.domain.service.internal.composer;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the {@link NoOpRecipePoolSource} fallback ONLY when no other {@link RecipePoolSource}
 * bean exists. In a normal context the unconditional {@link CatalogueRecipePoolSource}
 * {@code @Component} is present, so this {@code @Bean} steps aside; the fallback materialises only
 * when something explicitly excludes the catalogue source (e.g. a slice test).
 *
 * <p>Uses the {@code @Configuration + @Bean + @ConditionalOnMissingBean} form rather than
 * {@code @Component @ConditionalOnMissingBean} on the Noop class itself — the latter gates itself
 * off during component scan (documented round-5 retro; see {@code
 * NoopFeedbackRevertersConfiguration} and friends). The {@code @Bean} method name differs from the
 * returned class name to avoid the {@code BeanDefinitionOverrideException} the retro also flagged.
 */
@Configuration(proxyBeanMethods = false)
class NoOpRecipePoolSourceConfiguration {

  @Bean
  @ConditionalOnMissingBean(RecipePoolSource.class)
  RecipePoolSource defaultRecipePoolSource() {
    return new NoOpRecipePoolSource();
  }
}
