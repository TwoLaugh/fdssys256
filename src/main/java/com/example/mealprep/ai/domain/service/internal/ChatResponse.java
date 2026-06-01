package com.example.mealprep.ai.domain.service.internal;

/**
 * Provider-agnostic view of a chat-completion response — the seam {@link ChatClient} returns and
 * {@link AiServiceImpl} consumes, regardless of whether Anthropic or OpenAI answered. Carries just
 * enough for the dispatcher to deserialise the payload and audit the token counts. Internal to
 * {@code domain.service.internal}.
 *
 * <p>Mirrors {@link AnthropicResponse} field-for-field; {@link AnthropicClient} adapts its existing
 * {@link AnthropicResponse} into this shape so the Anthropic wire-parsing tests stay untouched
 * while the dispatcher depends only on the provider-neutral type.
 *
 * @param body raw response text — the model's structured-output JSON (OpenAI: the assistant message
 *     {@code content}; Anthropic: the concatenated {@code text} blocks or the first {@code
 *     tool_use} input).
 * @param requestTokens prompt-side token count (OpenAI {@code usage.prompt_tokens} / Anthropic
 *     {@code usage.input_tokens}), or {@code null} when the upstream omitted it.
 * @param responseTokens completion-side token count (OpenAI {@code usage.completion_tokens} /
 *     Anthropic {@code usage.output_tokens}), or {@code null} when the upstream omitted it.
 * @param modelId the model the upstream actually answered with — may differ from the requested id
 *     if the provider rolls a snapshot.
 */
public record ChatResponse(
    String body, Integer requestTokens, Integer responseTokens, String modelId) {}
