package com.example.mealprep.feedback.config;

import com.example.mealprep.feedback.exception.NutritionFeedbackBridgeUnavailableException;
import com.example.mealprep.feedback.exception.PreferenceFeedbackBridgeUnavailableException;
import com.example.mealprep.feedback.exception.ProvisionsFeedbackBridgeUnavailableException;
import com.example.mealprep.feedback.spi.NutritionFeedbackBridge;
import com.example.mealprep.feedback.spi.PreferenceFeedbackBridge;
import com.example.mealprep.feedback.spi.ProvisionsFeedbackBridge;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Default Noop wirings for the three wave-2 destination bridges. SPI-with-Noop pattern:
 * {@code @Configuration + @Bean + @ConditionalOnMissingBean}. Wave-2 modules supply real adapters
 * when their {@code applyFeedback} surfaces land; until then the destination dispatchers throw and
 * the router classifies as {@code AI_UNAVAILABLE} for the 01g sweep to retry.
 */
@Configuration
public class NoopFeedbackBridgesConfiguration {

  @Bean
  @ConditionalOnMissingBean(PreferenceFeedbackBridge.class)
  public PreferenceFeedbackBridge defaultPreferenceFeedbackBridge() {
    return new NoopPreferenceFeedbackBridge();
  }

  @Bean
  @ConditionalOnMissingBean(NutritionFeedbackBridge.class)
  public NutritionFeedbackBridge defaultNutritionFeedbackBridge() {
    return new NoopNutritionFeedbackBridge();
  }

  @Bean
  @ConditionalOnMissingBean(ProvisionsFeedbackBridge.class)
  public ProvisionsFeedbackBridge defaultProvisionsFeedbackBridge() {
    return new NoopProvisionsFeedbackBridge();
  }

  /** Throws on every call so the router books a FAILED + AI_UNAVAILABLE row. */
  public static final class NoopPreferenceFeedbackBridge implements PreferenceFeedbackBridge {
    @Override
    public Result applyFeedback(Input input) {
      throw new PreferenceFeedbackBridgeUnavailableException(
          "PreferenceFeedbackBridge is not implemented — preference module has not yet"
              + " shipped applyFeedback");
    }
  }

  /** Throws on every call so the router books a FAILED + AI_UNAVAILABLE row. */
  public static final class NoopNutritionFeedbackBridge implements NutritionFeedbackBridge {
    @Override
    public Result applyFeedback(Input input) {
      throw new NutritionFeedbackBridgeUnavailableException(
          "NutritionFeedbackBridge is not implemented — nutrition module has not yet shipped"
              + " applyFeedback");
    }
  }

  /** Throws on every call so the router books a FAILED + AI_UNAVAILABLE row. */
  public static final class NoopProvisionsFeedbackBridge implements ProvisionsFeedbackBridge {
    @Override
    public Result applyFeedback(Input input) {
      throw new ProvisionsFeedbackBridgeUnavailableException(
          "ProvisionsFeedbackBridge is not implemented — provisions module has not yet"
              + " shipped applyFeedback");
    }
  }
}
