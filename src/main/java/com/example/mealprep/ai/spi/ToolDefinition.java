package com.example.mealprep.ai.spi;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Anthropic tool-use definition. The dispatcher passes this through to the API verbatim; the input
 * schema is a JSON Schema object describing the structured-output shape.
 *
 * <p>01a does not act on {@code tool_use} blocks beyond deserialising the response payload. Multi
 * -turn tool-use orchestration lands in a later ticket if a calling module needs it.
 */
public record ToolDefinition(String name, String description, JsonNode inputSchema) {

  public ToolDefinition {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("ToolDefinition name must not be blank");
    }
    if (inputSchema == null) {
      throw new IllegalArgumentException("ToolDefinition inputSchema must not be null");
    }
  }
}
