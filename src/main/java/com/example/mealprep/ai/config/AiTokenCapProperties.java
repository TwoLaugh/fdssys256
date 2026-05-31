package com.example.mealprep.ai.config;

import com.example.mealprep.ai.spi.TaskType;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Per-task input-token caps (lld/ai.md Flow 1 step 4 + Flow 6) — bound to {@code
 * mealprep.ai.token-cap.*}.
 *
 * <p>The cap is the AI-module's enforcement of the Stage-C context-shape rule: a prompt larger than
 * the task's cap trips {@link com.example.mealprep.ai.exception.AiTokenCapExceededException} before
 * the wire call, so "shove the whole pool in" fails loudly rather than silently sending a giant
 * request. Token counts are estimated from the rendered prompt's character length via {@link
 * #charsPerToken} (~4 chars/token is the standard rough heuristic; exactness is unnecessary because
 * the cap catches order-of-magnitude mistakes, not the true tokenizer).
 *
 * <ul>
 *   <li>{@code enabled} — master switch (default true). {@code false} skips the check entirely.
 *   <li>{@code defaultTokens} — the cap applied to any task type with no explicit override (default
 *       200k tokens — generous, so ordinary tasks are unaffected; it only catches runaway prompts).
 *   <li>{@code charsPerToken} — chars-per-token divisor for the estimate (default 4).
 *   <li>{@code perTask} — per-{@link TaskType} overrides. {@code PLANNER_STAGE_C} defaults tight
 *       (32k) because that is the task the Stage-C "candidates + rollups, never the pool" rule
 *       guards; a caller passing the underlying pool blows past 32k and is rejected.
 * </ul>
 */
@ConfigurationProperties(prefix = "mealprep.ai.token-cap")
public record AiTokenCapProperties(
    Boolean enabled, Integer defaultTokens, Integer charsPerToken, Map<TaskType, Integer> perTask) {

  public AiTokenCapProperties {
    if (enabled == null) {
      enabled = true;
    }
    if (defaultTokens == null || defaultTokens <= 0) {
      defaultTokens = 200_000;
    }
    if (charsPerToken == null || charsPerToken <= 0) {
      charsPerToken = 4;
    }
    Map<TaskType, Integer> resolved = new EnumMap<>(TaskType.class);
    // Stage-C default tight cap — the context-shape guard's primary target.
    resolved.put(TaskType.PLANNER_STAGE_C, 32_000);
    if (perTask != null) {
      resolved.putAll(perTask);
    }
    perTask = Map.copyOf(resolved);
  }

  /** Resolve the token cap for a task type — its override, else the default. */
  public int capFor(TaskType taskType) {
    return perTask.getOrDefault(taskType, defaultTokens);
  }

  /** Estimate token count from a rendered-prompt character length (coarse, ~charsPerToken). */
  public int estimateTokens(int chars) {
    return Math.max(0, chars) / charsPerToken;
  }
}
