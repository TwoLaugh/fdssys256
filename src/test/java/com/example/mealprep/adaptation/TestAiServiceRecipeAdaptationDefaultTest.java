package com.example.mealprep.adaptation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.example.mealprep.adaptation.ai.RecipeAdaptationResponse;
import com.example.mealprep.adaptation.domain.enums.AdaptationClassification;
import com.example.mealprep.ai.domain.repository.AiCallLogRepository;
import com.example.mealprep.ai.spi.AiTask;
import com.example.mealprep.ai.spi.ModelTier;
import com.example.mealprep.ai.spi.PromptRef;
import com.example.mealprep.ai.spi.TaskType;
import com.example.mealprep.ai.spi.ToolDefinition;
import com.example.mealprep.ai.testing.TestAiService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Fix #2: {@link TestAiService} must ship a built-in NO_CHANGE response for {@link
 * TaskType#RECIPE_ADAPTATION} so the always-on adaptation Trigger-1 dispatch does not hard-fail
 * with "No canned response registered" in e2e. This test lives in the adaptation test package (not
 * the ai package) so it can reference {@link RecipeAdaptationResponse} without inverting the module
 * dependency direction the production code preserves.
 */
class TestAiServiceRecipeAdaptationDefaultTest {

  private TestAiService stub() {
    return new TestAiService(
        mock(AiCallLogRepository.class),
        mock(ApplicationEventPublisher.class),
        Clock.fixed(java.time.Instant.parse("2026-05-26T00:00:00Z"), ZoneOffset.UTC),
        new ObjectMapper());
  }

  @Test
  void recipeAdaptation_default_isNoChange_andClearsTheGates() {
    RecipeAdaptationResponse response = stub().execute(recipeAdaptationTask());

    // chosenCandidateIndex == -1 is the NO_CHANGE signal.
    assertThat(response.chosenCandidateIndex()).isEqualTo(-1);
    assertThat(response.classification()).isEqualTo(AdaptationClassification.NO_CHANGE);
    // Confidence clears any sane low-confidence floor (default config floor is 0.50).
    assertThat(response.confidence()).isGreaterThanOrEqualTo(java.math.BigDecimal.valueOf(0.5));
    // Character-preservation clears the 0.6 gate so the worker reaches a clean terminal outcome.
    assertThat(response.characterPreservationScore())
        .isGreaterThanOrEqualTo(java.math.BigDecimal.valueOf(0.6));
  }

  @Test
  void recipeAdaptation_default_survivesClear() {
    TestAiService s = stub();
    s.clear();
    // After a reset the always-on Trigger-1 path must still resolve a default rather than
    // hard-fail.
    assertThat(s.execute(recipeAdaptationTask()).classification())
        .isEqualTo(AdaptationClassification.NO_CHANGE);
  }

  private static AiTask<RecipeAdaptationResponse> recipeAdaptationTask() {
    return new AiTask<>() {
      @Override
      public TaskType type() {
        return TaskType.RECIPE_ADAPTATION;
      }

      @Override
      public ModelTier tier() {
        return ModelTier.MID;
      }

      @Override
      public PromptRef prompt() {
        return new PromptRef("adaptation/recipe-adaptation", 1);
      }

      @Override
      public Class<RecipeAdaptationResponse> outputType() {
        return RecipeAdaptationResponse.class;
      }

      @Override
      public Map<String, Object> variables() {
        return Map.of();
      }

      @Override
      public Optional<List<ToolDefinition>> tools() {
        return Optional.empty();
      }

      @Override
      public Optional<UUID> userId() {
        return Optional.of(UUID.randomUUID());
      }

      @Override
      public Optional<UUID> traceId() {
        return Optional.of(UUID.randomUUID());
      }
    };
  }
}
