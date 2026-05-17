package com.example.mealprep.feedback.config;

import com.example.mealprep.feedback.spi.NutritionFeedbackReverter;
import com.example.mealprep.feedback.spi.PreferenceFeedbackReverter;
import com.example.mealprep.feedback.spi.ProvisionsFeedbackReverter;
import com.example.mealprep.feedback.spi.RecipeFeedbackReverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * SPI-with-Noop defaults for the four destination reverters (ticket 01f §24, lld/feedback.md §Flow
 * 4 step 3). The wave-2 destination modules' real {@code revertFeedback} surfaces are a forward
 * dependency; until they land, correction is "best-effort, log-only on undo" — the Noops log WARN
 * and return without throwing so {@code correctMisclassification} still records the ground-truth
 * row and fires the synthetic replay.
 *
 * <p>{@code @Configuration + @Bean + @ConditionalOnMissingBean} (NOT
 * {@code @Component @ConditionalOnMissingBean} — the latter gates itself off during component-scan,
 * round-5 retro). Each {@code @Bean} method name DIFFERS from the enclosing class name (round-5
 * {@code BeanDefinitionOverrideException} avoidance). When a destination module ships a
 * {@code @Component} impl of the SPI, the matching Noop steps aside via the conditional.
 */
@Configuration
public class NoopFeedbackRevertersConfiguration {

  private static final Logger log =
      LoggerFactory.getLogger(NoopFeedbackRevertersConfiguration.class);

  @Bean
  @ConditionalOnMissingBean(PreferenceFeedbackReverter.class)
  public PreferenceFeedbackReverter defaultPreferenceReverter() {
    return ctx ->
        log.warn(
            "revert not implemented in preference module yet; correction is log-only for"
                + " routing {}",
            ctx.originalRoutingId());
  }

  @Bean
  @ConditionalOnMissingBean(NutritionFeedbackReverter.class)
  public NutritionFeedbackReverter defaultNutritionReverter() {
    return ctx ->
        log.warn(
            "revert not implemented in nutrition module yet; correction is log-only for"
                + " routing {}",
            ctx.originalRoutingId());
  }

  @Bean
  @ConditionalOnMissingBean(ProvisionsFeedbackReverter.class)
  public ProvisionsFeedbackReverter defaultProvisionsReverter() {
    return ctx ->
        log.warn(
            "revert not implemented in provisions module yet; correction is log-only for"
                + " routing {}",
            ctx.originalRoutingId());
  }

  @Bean
  @ConditionalOnMissingBean(RecipeFeedbackReverter.class)
  public RecipeFeedbackReverter defaultRecipeReverter() {
    return ctx ->
        log.warn(
            "revert not implemented in adaptation-pipeline module yet; correction is log-only"
                + " for routing {}",
            ctx.originalRoutingId());
  }
}
