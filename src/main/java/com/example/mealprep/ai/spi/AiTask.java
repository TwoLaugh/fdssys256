package com.example.mealprep.ai.spi;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Cross-module SPI implemented by every calling module that needs an AI completion. The dispatcher
 * ({@code AiService.execute}) reads the methods on this interface to render a prompt, choose a
 * model, deserialise the response, and audit the call.
 *
 * <p>For 01a the SPI is deliberately small. Per-task token caps, prompt-template loading, and
 * structured-output JSR-303 validation are deferred to later tickets — a calling module today
 * supplies a system / user prompt via {@link PromptRef} (resolved by future 01d) and a target
 * {@link Class} for Jackson to deserialise into.
 *
 * @param <T> response payload type — Jackson reads the model's {@code text} content (or first
 *     {@code tool_use} input) into this type.
 */
public interface AiTask<T> {

  /** Discriminator for the dispatcher; drives tier resolution and metrics tags. */
  TaskType type();

  /** Override the tier mapping if the calling module needs a different model than the default. */
  ModelTier tier();

  /** Reference to the prompt template the dispatcher renders. */
  PromptRef prompt();

  /** Target type for Jackson deserialisation of the response body. */
  Class<T> outputType();

  /** Variable substitutions for the prompt template — values are Jackson-serialisable. */
  Map<String, Object> variables();

  /**
   * Tools (Anthropic's structured-output mechanism). Empty when the task expects free-text JSON.
   * 01a passes the definitions through to the API but does not orchestrate multi-turn tool use.
   */
  Optional<List<ToolDefinition>> tools();

  /** Owning user (for cost tracking); empty for system-initiated tasks. */
  Optional<UUID> userId();

  /** Trace identifier for decision-log correlation; empty when no upstream trace is in context. */
  Optional<UUID> traceId();
}
