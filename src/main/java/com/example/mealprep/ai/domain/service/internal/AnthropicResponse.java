package com.example.mealprep.ai.domain.service.internal;

/**
 * Minimal extracted view of an Anthropic Messages response — enough for {@code AiServiceImpl} to
 * deserialise the payload and audit the token counts. Internal to {@code domain.service.internal}.
 *
 * @param body raw response text (concatenation of {@code content[].text} or the JSON of the first
 *     {@code tool_use} input).
 * @param requestTokens prompt-side token count from {@code usage.input_tokens}, or {@code null}
 *     when the upstream omitted it.
 * @param responseTokens completion-side token count from {@code usage.output_tokens}, or {@code
 *     null} when the upstream omitted it.
 * @param modelId the model the upstream actually answered with — may differ from the one we asked
 *     for if Anthropic rolls a snapshot.
 */
public record AnthropicResponse(
    String body, Integer requestTokens, Integer responseTokens, String modelId) {}
