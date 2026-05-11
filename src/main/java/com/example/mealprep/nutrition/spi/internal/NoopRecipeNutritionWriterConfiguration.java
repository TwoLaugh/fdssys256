package com.example.mealprep.nutrition.spi.internal;

import com.example.mealprep.nutrition.api.dto.RecipeNutritionResultDto;
import com.example.mealprep.nutrition.spi.RecipeNutritionWriter;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Default {@link RecipeNutritionWriter} bean — active only when no other module has registered one.
 * Logs a WARN and returns silently so the recalc flow (controller, listener) completes; the recipe
 * version simply does not receive the computed nutrition payload until {@code recipe-01f} wires its
 * real impl.
 *
 * <p>Implementation note (round-5 SPI pattern): the binding is declared via a
 * {@code @Configuration} class + {@code @Bean @ConditionalOnMissingBean} method (not
 * {@code @Component @ConditionalOnMissingBean} on the class). The {@code @Component} variant
 * doesn't reliably defer to other implementations during component-scan ordering. The {@code @Bean}
 * variant evaluates after bean definition gathering, so a real implementation always wins when
 * present.
 *
 * <p>The {@code @Bean} method name {@code defaultRecipeNutritionWriter} is intentionally distinct
 * from the enclosing class name {@code NoopRecipeNutritionWriterConfiguration} — otherwise Spring
 * would attempt to register two beans under the same name and throw {@code
 * BeanDefinitionOverrideException}.
 */
@Configuration
public class NoopRecipeNutritionWriterConfiguration {

  @Bean
  @ConditionalOnMissingBean(RecipeNutritionWriter.class)
  RecipeNutritionWriter defaultRecipeNutritionWriter() {
    return new NoopRecipeNutritionWriterImpl();
  }

  /**
   * The Noop impl. Package-private so tests inside {@code spi/internal} can reference it; external
   * callers route through the {@link RecipeNutritionWriter} interface.
   */
  static class NoopRecipeNutritionWriterImpl implements RecipeNutritionWriter {

    private static final Logger log = LoggerFactory.getLogger(NoopRecipeNutritionWriterImpl.class);

    @Override
    public void writeNutritionPerServing(UUID versionId, RecipeNutritionResultDto result) {
      log.warn(
          "NoopRecipeNutritionWriter: skipping write for version {} (status {}, calories {}/serving)"
              + " — recipe-01f impl not yet wired",
          versionId,
          result.nutritionStatus(),
          result.caloriesPerServing());
    }
  }
}
