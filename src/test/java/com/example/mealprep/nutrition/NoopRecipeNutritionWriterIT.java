package com.example.mealprep.nutrition;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.nutrition.api.dto.RecipeNutritionResultDto;
import com.example.mealprep.nutrition.spi.RecipeNutritionWriter;
import com.example.mealprep.testsupport.TestContainersConfig;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

/**
 * Spring-context tests for the {@link RecipeNutritionWriter} SPI binding. Verifies round-5 SPI
 * pattern invariants:
 *
 * <ul>
 *   <li>With no other binding present, the {@code NoopRecipeNutritionWriterConfiguration} fallback
 *       wins via {@code @ConditionalOnMissingBean} (it must NOT be {@code @Primary} or marked as
 *       primary indirectly — see round-5 bug 1).
 *   <li>A {@code @TestConfiguration @Primary} fake wins over the Noop (round-5 bug 3 fix).
 * </ul>
 */
class NoopRecipeNutritionWriterIT {

  /** Sub-test: Noop is the wired bean when nothing else is registered. */
  @Nested
  @SpringBootTest
  @Import(TestContainersConfig.class)
  @ActiveProfiles("test")
  class WhenNoOverride {

    @Autowired private RecipeNutritionWriter writer;

    @Test
    void noopBean_isWired() {
      assertThat(writer.getClass().getName())
          .contains("NoopRecipeNutritionWriterConfiguration")
          .contains("NoopRecipeNutritionWriterImpl");
    }

    @Test
    void noopBean_doesNotThrow_onWrite() {
      writer.writeNutritionPerServing(
          UUID.randomUUID(),
          new RecipeNutritionResultDto(
              UUID.randomUUID(),
              0,
              new BigDecimal("0.00"),
              new BigDecimal("0.00"),
              new BigDecimal("0.00"),
              new BigDecimal("0.00"),
              Map.of(),
              "pending",
              List.of()));
    }
  }

  /** Sub-test: a {@code @Primary} test fake displaces the Noop. */
  @Nested
  @SpringBootTest
  @Import({TestContainersConfig.class, WhenPrimaryFakeOverrides.PrimaryFakeConfig.class})
  @ActiveProfiles("test")
  class WhenPrimaryFakeOverrides {

    @Autowired private RecipeNutritionWriter writer;

    @Test
    void primaryFake_winsOverNoop() {
      assertThat(writer.getClass().getName()).contains("PrimaryFakeWriter");
    }

    @TestConfiguration
    static class PrimaryFakeConfig {
      @Bean
      @Primary
      RecipeNutritionWriter primaryFakeWriter() {
        return new PrimaryFakeWriter();
      }
    }

    static class PrimaryFakeWriter implements RecipeNutritionWriter {
      @Override
      public void writeNutritionPerServing(UUID versionId, RecipeNutritionResultDto result) {
        // no-op fake — presence is what's asserted.
      }
    }
  }
}
