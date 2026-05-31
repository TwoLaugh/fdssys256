package com.example.mealprep.ai.domain.service.internal;

import com.example.mealprep.ai.config.AiTokenCapProperties;
import com.example.mealprep.ai.exception.AiTokenCapExceededException;
import com.example.mealprep.ai.spi.AiTask;
import com.example.mealprep.ai.spi.TaskType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Pre-dispatch per-task input-token cap (lld/ai.md Flow 1 step 4 + Flow 6 — Stage-C context-shape
 * safeguard). Measures the <em>rendered</em> user message (the exact text {@link AnthropicClient}
 * will send, via {@link AnthropicClient#renderUserMessage}) and rejects with {@link
 * AiTokenCapExceededException} (422) when the coarse char-length token estimate exceeds the task's
 * cap from {@link AiTokenCapProperties}.
 *
 * <p>This makes "shove the whole pool into the prompt" fail loudly at the AI-module seam rather
 * than relying purely on the calling module to keep its context small — closing finding {@code
 * ai-4}. The estimate is deliberately a character-length proxy (no tokenizer dependency); the cap
 * exists to catch order-of-magnitude mistakes, and precision lands in the post-call logged token
 * count.
 */
@Component
public class TokenCapGuard {

  private static final Logger log = LoggerFactory.getLogger(TokenCapGuard.class);

  private final AiTokenCapProperties properties;
  private final ObjectMapper objectMapper;

  public TokenCapGuard(AiTokenCapProperties properties, ObjectMapper objectMapper) {
    this.properties = properties;
    this.objectMapper = objectMapper;
  }

  /**
   * Reject the dispatch when the rendered prompt exceeds the task's input-token cap.
   *
   * @throws AiTokenCapExceededException when the estimate exceeds the cap (caller bug → 422)
   */
  public void checkOrThrow(AiTask<?> task) {
    if (!Boolean.TRUE.equals(properties.enabled())) {
      return;
    }
    TaskType taskType = task.type();
    String rendered = AnthropicClient.renderUserMessage(task, objectMapper);
    int chars = rendered == null ? 0 : rendered.length();
    int estimatedTokens = properties.estimateTokens(chars);
    int cap = properties.capFor(taskType);
    if (estimatedTokens > cap) {
      log.warn(
          "ai token cap exceeded taskType={} estimatedTokens={} cap={} chars={}",
          taskType,
          estimatedTokens,
          cap,
          chars);
      throw new AiTokenCapExceededException(taskType, estimatedTokens, cap);
    }
  }
}
