package com.example.mealprep.nutrition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.example.mealprep.ai.domain.repository.AiCallLogRepository;
import com.example.mealprep.ai.spi.TaskType;
import com.example.mealprep.ai.testing.TestAiService;
import com.example.mealprep.nutrition.domain.service.internal.IngredientMatchResult;
import com.example.mealprep.nutrition.domain.service.internal.IngredientMatchTask;
import com.example.mealprep.nutrition.domain.service.internal.IngredientParseResult;
import com.example.mealprep.nutrition.domain.service.internal.IngredientParseTask;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

/**
 * nutrition-01k: {@link TestAiService} must ship deterministic built-in responses for BOTH new
 * ingredient task types so CI / ITs that resolve a novel ingredient make ZERO real API calls. This
 * lives in the nutrition test package so it can reference the real {@link IngredientParseTask} /
 * {@link IngredientMatchTask} (and their result types) without inverting the module-dependency
 * direction the production code preserves.
 *
 * <p>The defaults are tuned to degrade to the pipeline's deterministic fallback (parse omits a
 * search term; match declines with {@code chosenIndex = -1}) so they exercise the dispatch wiring
 * while keeping the USDA/OFF-mocked ITs' deterministic outcomes intact.
 */
class TestAiServiceNutritionDefaultsTest {

  private TestAiService stub() {
    return new TestAiService(
        mock(AiCallLogRepository.class),
        mock(ApplicationEventPublisher.class),
        Clock.fixed(Instant.parse("2026-06-09T00:00:00Z"), ZoneOffset.UTC),
        new ObjectMapper());
  }

  @Test
  void parseDefault_omitsSearchTerm_soPipelineUsesNormalisedTerm() {
    IngredientParseResult result =
        stub().execute(new IngredientParseTask("2 chicken breasts", "chicken breasts", null, null));

    assertThat(result).isNotNull();
    // No usable search term -> the pipeline falls back to its own normalised term.
    assertThat(result.searchTermOrNull()).isNull();
  }

  @Test
  void matchDefault_declines_soPipelineTakesFirstHit() {
    IngredientMatchResult result =
        stub()
            .execute(
                new IngredientMatchTask(
                    "chicken breast",
                    "chicken breast",
                    List.of(
                        new IngredientMatchTask.Candidate("USDA", "111", "Chicken breast, raw")),
                    null,
                    null));

    assertThat(result).isNotNull();
    assertThat(result.isNoMatch()).isTrue();
    assertThat(result.chosenIndex()).isEqualTo(-1);
  }

  @Test
  void bothDefaults_surviveClear() {
    TestAiService s = stub();
    s.clear();
    // After a reset (e.g. an IT's @AfterEach) the pipeline-driving defaults must still resolve.
    assertThat(s.execute(new IngredientParseTask("oats", "oats", null, null)).searchTermOrNull())
        .isNull();
    assertThat(
            s.execute(
                    new IngredientMatchTask(
                        "oats",
                        "oats",
                        List.of(new IngredientMatchTask.Candidate("USDA", "9", "Oats")),
                        null,
                        null))
                .isNoMatch())
        .isTrue();
  }

  @Test
  void recordsZeroCostStubbedCalls_forBothTaskTypes() {
    TestAiService s = stub();
    s.execute(new IngredientParseTask("oats", "oats", null, null));
    s.execute(
        new IngredientMatchTask(
            "oats",
            "oats",
            List.of(new IngredientMatchTask.Candidate("USDA", "9", "Oats")),
            null,
            null));

    List<TestAiService.RecordedCall> calls = s.recordedCalls();
    assertThat(calls).hasSize(2);
    assertThat(calls)
        .extracting(TestAiService.RecordedCall::taskType)
        .containsExactly(TaskType.NUTRITION_INGREDIENT_PARSE, TaskType.NUTRITION_INGREDIENT_MATCH);
  }
}
