package com.example.mealprep.ai.domain.service;

import com.example.mealprep.ai.exception.AiInvalidRequestException;
import com.example.mealprep.ai.exception.AiInvalidResponseException;
import com.example.mealprep.ai.exception.AiUnavailableException;
import com.example.mealprep.ai.spi.AiTask;

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
}
