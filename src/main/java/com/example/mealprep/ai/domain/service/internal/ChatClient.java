package com.example.mealprep.ai.domain.service.internal;

import com.example.mealprep.ai.spi.AiTask;

/**
 * Chat-dispatch seam {@link AiServiceImpl} depends on. One implementation per provider — {@link
 * AnthropicClient} (Anthropic Messages API) and {@link OpenAiChatClient} (OpenAI chat-completions
 * with structured outputs) — selected at wiring time by {@code mealprep.ai.chat-provider} (see
 * {@code AiClientConfig}). The dispatcher is provider-agnostic: it hands a fully-described {@link
 * AiTask} plus the tier-resolved model id to whichever implementation is wired in, and reads back a
 * provider-neutral {@link ChatResponse} (raw model text / structured JSON + token usage).
 *
 * <p>Implementations own their own resilience: a per-task-type Resilience4j circuit breaker (keyed
 * {@code ai-${taskType}}) and a classified retry loop (transient / rate-limit retried, caller-bug
 * 4xx fatal) via {@link RetryPolicy}. The contract below is what {@link AiServiceImpl} relies on;
 * it mirrors the failure taxonomy {@link AnthropicClient} has always thrown so swapping providers
 * needs no change in the dispatcher's catch blocks.
 */
public interface ChatClient {

  /**
   * Make one chat-completion call through the task-type circuit breaker, with classified retry, and
   * return the provider-neutral response.
   *
   * @param task the fully-described task (rendered prompt variables, tools / output schema, ids).
   * @param modelId the concrete provider model id the dispatcher resolved from the task's tier.
   * @return the raw model text / structured JSON plus token usage.
   * @throws com.example.mealprep.ai.exception.AiCircuitOpenException the task-type breaker is open
   *     — short-circuited without a wire call.
   * @throws com.example.mealprep.ai.exception.AiInvalidRequestException a non-retryable 4xx
   *     caller-bug (incl. AUTH 401/403, invalid-request 400, context-length).
   * @throws com.example.mealprep.ai.exception.AiRateLimitException retries exhausted on HTTP 429 /
   *     {@code insufficient_quota}.
   * @throws com.example.mealprep.ai.exception.AiUnavailableException retries exhausted on transient
   *     (5xx / transport) failures, or no provider client configured.
   * @throws com.example.mealprep.ai.exception.AiInvalidResponseException the upstream returned a
   *     malformed / schema-invalid payload (e.g. an OpenAI refusal, or non-JSON body).
   */
  ChatResponse chat(AiTask<?> task, String modelId);
}
