package com.example.mealprep.feedback.config;

import com.example.mealprep.feedback.exception.RecipeFeedbackHandlerUnavailableException;
import com.example.mealprep.feedback.spi.RecipeFeedbackHandler;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Default {@link RecipeFeedbackHandler} for the case where the adaptation-pipeline module hasn't
 * supplied a real impl. SPI-with-Noop pattern: {@code @Configuration + @Bean
 * + @ConditionalOnMissingBean} (per the round-5 retro: avoid {@code @Component
 * + @ConditionalOnMissingBean}).
 *
 * <p>The bean method name differs from the enclosing class to avoid the {@code
 * BeanDefinitionOverrideException} pitfall.
 */
@Configuration
public class NoopRecipeFeedbackHandlerConfiguration {

  @Bean
  @ConditionalOnMissingBean(RecipeFeedbackHandler.class)
  public RecipeFeedbackHandler defaultRecipeFeedbackHandler() {
    return new NoopRecipeFeedbackHandler();
  }

  /**
   * Throws {@link RecipeFeedbackHandlerUnavailableException} on every call — the router classifies
   * it as {@code AI_UNAVAILABLE} so a future 01g sweep replays once the real handler is wired.
   */
  public static final class NoopRecipeFeedbackHandler implements RecipeFeedbackHandler {
    @Override
    public Result handleRecipeFeedback(Input input) {
      throw new RecipeFeedbackHandlerUnavailableException(
          "RecipeFeedbackHandler is not implemented — adaptation-pipeline module not on"
              + " classpath yet");
    }
  }
}
