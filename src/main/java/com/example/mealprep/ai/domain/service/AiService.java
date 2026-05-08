package com.example.mealprep.ai.domain.service;

import com.example.mealprep.ai.exception.AiInvalidRequestException;
import com.example.mealprep.ai.exception.AiInvalidResponseException;
import com.example.mealprep.ai.exception.AiUnavailableException;
import com.example.mealprep.ai.spi.AiTask;
import com.example.mealprep.ai.spi.EmbeddingTask;

/**
 * Cross-module dispatcher. The single Java seam every module crosses to reach Anthropic. The
 * implementation:
 *
 * <ol>
 *   <li>records an {@code AiCallLog} row with status {@code PENDING};
 *   <li>calls Anthropic via {@code AnthropicClient};
 *   <li>updates the row to {@code SUCCEEDED} or {@code FAILED};
 *   <li>deserialises the response per {@link AiTask#outputType()};
 *   <li>publishes {@code AiCallSucceededEvent} or {@code AiCallFailedEvent} after commit.
 * </ol>
 *
 * <p>Calling modules implement {@link AiTask}; nobody implements {@code AiService} outside this
 * module except the test bean.
 *
 * @see com.example.mealprep.ai.testing.TestAiService
 */
public interface AiService {

  /**
   * Dispatch a task.
   *
   * @throws AiUnavailableException 5xx / network failures after retries are exhausted (graceful
   *     degrade signal).
   * @throws AiInvalidRequestException 4xx — caller bug; never retried.
   * @throws AiInvalidResponseException response could not be parsed into the task's output type.
   */
  <T> T execute(AiTask<T> task);

  /**
   * Compute an embedding vector for {@code task.inputText()}. Returns a {@code float[]} sized to
   * the configured embedding model (1536 for {@code text-embedding-3-small}). Identical input text
   * is cached for {@code mealprep.ai.embedding.cache-ttl-hours} so repeat embeds of the same text
   * incur no cost; cache hits return the SAME {@code float[]} instance.
   *
   * @throws IllegalArgumentException task is null or input text is empty / blank
   * @throws AiUnavailableException retries exhausted on transient OpenAI failures
   * @throws AiInvalidRequestException 4xx from OpenAI (bad input, no auth, etc.)
   * @throws AiInvalidResponseException OpenAI returned a malformed embedding payload
   */
  float[] embed(EmbeddingTask task);
}
