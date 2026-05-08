package com.example.mealprep.ai.domain.service.internal;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Registry that dispatches Anthropic {@code tool_use} blocks to handlers keyed by tool name.
 *
 * <p>For 01d the registry ships with a single NO-OP handler that logs the invocation; downstream
 * modules (planner, recipe extraction) register real handlers in their own initialisation. The
 * plumbing is here so dispatcher / structured-output flows have a stable seam to call into.
 */
@Component
public class ToolUseInvoker {

  private static final Logger log = LoggerFactory.getLogger(ToolUseInvoker.class);

  /**
   * Sentinel name resolved when no handler is registered for a tool name. Real callers should
   * register a handler before relying on the invoker for non-no-op work.
   */
  public static final String NO_OP_NAME = "__no_op__";

  private final Map<String, BiFunction<JsonNode, Map<String, Object>, JsonNode>> handlers =
      new ConcurrentHashMap<>();

  public ToolUseInvoker() {
    handlers.put(
        NO_OP_NAME,
        (input, ctx) -> {
          log.info(
              "tool_use no-op handler invoked input={} context_keys={}",
              input,
              ctx == null ? "[]" : ctx.keySet());
          return null;
        });
  }

  /**
   * Register a handler for a named tool. Replaces any prior registration. Returns the previous
   * handler (or empty when none).
   */
  public Optional<BiFunction<JsonNode, Map<String, Object>, JsonNode>> register(
      String toolName, BiFunction<JsonNode, Map<String, Object>, JsonNode> handler) {
    if (toolName == null || toolName.isBlank()) {
      throw new IllegalArgumentException("toolName must not be blank");
    }
    if (handler == null) {
      throw new IllegalArgumentException("handler must not be null");
    }
    return Optional.ofNullable(handlers.put(toolName, handler));
  }

  public boolean isRegistered(String toolName) {
    return toolName != null && handlers.containsKey(toolName);
  }

  /**
   * Invoke the registered handler. Falls back to the no-op handler when no specific binding exists;
   * the caller logs / decides.
   */
  public JsonNode invoke(String toolName, JsonNode input, Map<String, Object> context) {
    BiFunction<JsonNode, Map<String, Object>, JsonNode> handler =
        handlers.getOrDefault(toolName, handlers.get(NO_OP_NAME));
    if (!handlers.containsKey(toolName)) {
      log.info("tool_use unregistered tool={} falling back to no-op", toolName);
    }
    return handler.apply(input, context);
  }
}
