package com.example.mealprep.ai;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.mealprep.ai.config.AiTokenCapProperties;
import com.example.mealprep.ai.domain.service.internal.TokenCapGuard;
import com.example.mealprep.ai.exception.AiTokenCapExceededException;
import com.example.mealprep.ai.spi.AiTask;
import com.example.mealprep.ai.spi.TaskType;
import com.example.mealprep.ai.testdata.AiTestData;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TokenCapGuard} — the per-task input-token cap / Stage-C context-shape
 * safeguard (finding {@code ai-4}). Token counts are a char-length proxy (4 chars/token default).
 */
class TokenCapGuardTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  private TokenCapGuard guard(AiTokenCapProperties props) {
    return new TokenCapGuard(props, objectMapper);
  }

  private AiTokenCapProperties props(
      boolean enabled, int defaultTokens, Map<TaskType, Integer> per) {
    return new AiTokenCapProperties(enabled, defaultTokens, 4, per);
  }

  private AiTask<String> taskWithPrompt(TaskType type, String prompt) {
    return AiTestData.task(String.class).ofType(type).withVariable("prompt", prompt).build();
  }

  @Test
  void underCap_passes() {
    AiTask<String> task = taskWithPrompt(TaskType.FEEDBACK_CLASSIFICATION, "short prompt");
    assertThatCode(() -> guard(props(true, 200_000, Map.of())).checkOrThrow(task))
        .doesNotThrowAnyException();
  }

  @Test
  void overDefaultCap_throws422Style_withEstimateAndCap() {
    // defaultTokens=10 → cap is 10 tokens = 40 chars at 4 chars/token. A 100-char prompt → 25
    // tokens > 10.
    AiTask<String> task = taskWithPrompt(TaskType.FEEDBACK_CLASSIFICATION, "x".repeat(100));
    assertThatThrownBy(() -> guard(props(true, 10, Map.of())).checkOrThrow(task))
        .isInstanceOf(AiTokenCapExceededException.class)
        .satisfies(
            ex -> {
              AiTokenCapExceededException t = (AiTokenCapExceededException) ex;
              org.assertj.core.api.Assertions.assertThat(t.taskType())
                  .isEqualTo(TaskType.FEEDBACK_CLASSIFICATION);
              org.assertj.core.api.Assertions.assertThat(t.estimatedTokens()).isEqualTo(25);
              org.assertj.core.api.Assertions.assertThat(t.capTokens()).isEqualTo(10);
            });
  }

  @Test
  void perTaskOverride_isApplied_aheadOfDefault() {
    // Default huge; PLANNER_STAGE_C overridden tight to 5 tokens (=20 chars). 40-char prompt → 10
    // tokens > 5 → reject for Stage-C, but a FEEDBACK task with the same prompt passes.
    Map<TaskType, Integer> per = Map.of(TaskType.PLANNER_STAGE_C, 5);
    String prompt = "y".repeat(40);
    assertThatThrownBy(
            () ->
                guard(props(true, 1_000_000, per))
                    .checkOrThrow(taskWithPrompt(TaskType.PLANNER_STAGE_C, prompt)))
        .isInstanceOf(AiTokenCapExceededException.class);
    assertThatCode(
            () ->
                guard(props(true, 1_000_000, per))
                    .checkOrThrow(taskWithPrompt(TaskType.FEEDBACK_CLASSIFICATION, prompt)))
        .doesNotThrowAnyException();
  }

  @Test
  void stageCDefault_isTight_evenWithNoExplicitOverride() {
    // The record seeds PLANNER_STAGE_C=32_000 by default. 32_001 tokens = 128_004 chars trips it.
    AiTokenCapProperties defaults = new AiTokenCapProperties(true, null, null, null);
    org.assertj.core.api.Assertions.assertThat(defaults.capFor(TaskType.PLANNER_STAGE_C))
        .isEqualTo(32_000);
    AiTask<String> tooBig = taskWithPrompt(TaskType.PLANNER_STAGE_C, "z".repeat(32_001 * 4));
    assertThatThrownBy(() -> guard(defaults).checkOrThrow(tooBig))
        .isInstanceOf(AiTokenCapExceededException.class);
  }

  @Test
  void disabled_skipsCheckEntirely() {
    AiTask<String> hugePrompt =
        taskWithPrompt(TaskType.FEEDBACK_CLASSIFICATION, "x".repeat(10_000));
    assertThatCode(() -> guard(props(false, 1, Map.of())).checkOrThrow(hugePrompt))
        .doesNotThrowAnyException();
  }
}
