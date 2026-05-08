package com.example.mealprep.ai.testdata;

import com.example.mealprep.ai.spi.AiTask;
import com.example.mealprep.ai.spi.ModelTier;
import com.example.mealprep.ai.spi.PromptRef;
import com.example.mealprep.ai.spi.TaskType;
import com.example.mealprep.ai.spi.ToolDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Test data builder for the AI module. Produces {@link AiTask} fixtures whose defaults are valid
 * across the dispatcher contract; callers tweak only the field under test.
 */
public final class AiTestData {

  private AiTestData() {}

  public static <T> StubTaskBuilder<T> task(Class<T> outputType) {
    return new StubTaskBuilder<>(outputType);
  }

  /** Minimal {@code tool_use}-style schema for tests that need a non-empty tool list. */
  public static ToolDefinition simpleTool(String name, ObjectMapper objectMapper) {
    ObjectNode schema = objectMapper.createObjectNode();
    schema.put("type", "object");
    schema.putObject("properties").putObject("answer").put("type", "string");
    return new ToolDefinition(name, "test tool", schema);
  }

  public static final class StubTaskBuilder<T> {
    private TaskType type = TaskType.FEEDBACK_CLASSIFICATION;
    private ModelTier tier = ModelTier.CHEAP;
    private PromptRef prompt = new PromptRef("test/echo", 1);
    private final Class<T> outputType;
    private final Map<String, Object> variables = new HashMap<>();
    private final List<ToolDefinition> tools = new ArrayList<>();
    private UUID userId;
    private UUID traceId;

    public StubTaskBuilder(Class<T> outputType) {
      this.outputType = outputType;
      this.variables.put("prompt", "Hello, Claude.");
    }

    public StubTaskBuilder<T> ofType(TaskType type) {
      this.type = type;
      return this;
    }

    public StubTaskBuilder<T> withTier(ModelTier tier) {
      this.tier = tier;
      return this;
    }

    public StubTaskBuilder<T> withPrompt(PromptRef prompt) {
      this.prompt = prompt;
      return this;
    }

    public StubTaskBuilder<T> withVariable(String key, Object value) {
      this.variables.put(key, value);
      return this;
    }

    public StubTaskBuilder<T> withUserId(UUID userId) {
      this.userId = userId;
      return this;
    }

    public StubTaskBuilder<T> withTraceId(UUID traceId) {
      this.traceId = traceId;
      return this;
    }

    public StubTaskBuilder<T> withTool(ToolDefinition tool) {
      this.tools.add(tool);
      return this;
    }

    public AiTask<T> build() {
      TaskType capturedType = type;
      ModelTier capturedTier = tier;
      PromptRef capturedPrompt = prompt;
      Class<T> capturedOutputType = outputType;
      Map<String, Object> capturedVars = Map.copyOf(variables);
      List<ToolDefinition> capturedTools = List.copyOf(tools);
      Optional<UUID> capturedUserId = Optional.ofNullable(userId);
      Optional<UUID> capturedTraceId = Optional.ofNullable(traceId);
      return new AiTask<>() {
        @Override
        public TaskType type() {
          return capturedType;
        }

        @Override
        public ModelTier tier() {
          return capturedTier;
        }

        @Override
        public PromptRef prompt() {
          return capturedPrompt;
        }

        @Override
        public Class<T> outputType() {
          return capturedOutputType;
        }

        @Override
        public Map<String, Object> variables() {
          return capturedVars;
        }

        @Override
        public Optional<List<ToolDefinition>> tools() {
          return capturedTools.isEmpty() ? Optional.empty() : Optional.of(capturedTools);
        }

        @Override
        public Optional<UUID> userId() {
          return capturedUserId;
        }

        @Override
        public Optional<UUID> traceId() {
          return capturedTraceId;
        }
      };
    }
  }
}
