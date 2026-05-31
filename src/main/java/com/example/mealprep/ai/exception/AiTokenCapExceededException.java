package com.example.mealprep.ai.exception;

import com.example.mealprep.ai.spi.TaskType;

/**
 * Thrown <em>before</em> dispatch when a task's rendered prompt exceeds the per-task input-token
 * cap (lld/ai.md Flow 1 step 4 + Flow 6). This is the AI-module enforcement of the Stage-C
 * context-shape rule — "the LLM sees N candidates + rollups, never the underlying pool" — made to
 * fail loudly: a caller that accidentally shoves the whole pool into the prompt trips this cap
 * rather than silently sending a giant (and expensive) request.
 *
 * <p>This is a <strong>caller bug</strong>, not a transient/graceful-degrade condition — mapped to
 * HTTP 422 Unprocessable Entity. The estimate is a coarse character-length proxy (~4 chars/token);
 * exactness is unnecessary because the cap exists to catch order-of-magnitude mistakes, not to
 * model the true tokenizer.
 */
public class AiTokenCapExceededException extends AiException {

  private final TaskType taskType;
  private final int estimatedTokens;
  private final int capTokens;

  public AiTokenCapExceededException(TaskType taskType, int estimatedTokens, int capTokens) {
    super(
        "AI input token cap exceeded for task "
            + taskType
            + " (estimated="
            + estimatedTokens
            + " tokens, cap="
            + capTokens
            + " tokens) — the rendered prompt is too large; pass candidates + rollups, not the"
            + " underlying pool");
    this.taskType = taskType;
    this.estimatedTokens = estimatedTokens;
    this.capTokens = capTokens;
  }

  public TaskType taskType() {
    return taskType;
  }

  public int estimatedTokens() {
    return estimatedTokens;
  }

  public int capTokens() {
    return capTokens;
  }
}
