package com.example.mealprep.nutrition.domain.service.internal;

import com.example.mealprep.ai.spi.AiTask;
import com.example.mealprep.ai.spi.ModelTier;
import com.example.mealprep.ai.spi.PromptRef;
import com.example.mealprep.ai.spi.TaskType;
import com.example.mealprep.ai.spi.ToolDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * AI dispatcher task that turns one raw / normalised ingredient line into a clean food search term
 * plus a structured parse (LLD nutrition Flow 6 step 3, line 980). Output feeds the USDA / OFF
 * search in {@link IngredientMappingPipeline}.
 *
 * <p>Cheap-tier (parse is a simple high-volume extraction). Structured output is described by a
 * hand-built JSON Schema in {@link #buildToolDefinition()} — same shape as the sibling planner /
 * feedback tasks; the AI module ships no schema auto-derivation. The dispatcher validates the model
 * response against this schema via {@code StructuredOutputParser} before deserialising into {@link
 * IngredientParseResult}.
 */
public final class IngredientParseTask implements AiTask<IngredientParseResult> {

  /**
   * Prompt name handed to the renderer; resolves to {@code prompts/nutrition/ingredient-parse.txt}.
   */
  public static final String PROMPT_NAME = "nutrition/ingredient-parse";

  /** Prompt version owned by the nutrition module. Bump when the prompt body ships a v2. */
  public static final int PROMPT_VERSION = 1;

  /** Tool name the structured-output schema is registered under. */
  static final String TOOL_NAME = "parse_ingredient";

  private static final ObjectMapper SCHEMA_MAPPER = new ObjectMapper();
  private static final ToolDefinition TOOL_DEFINITION = buildToolDefinition();

  private final String rawTerm;
  private final String normalisedTerm;
  private final UUID userId;
  private final UUID traceId;

  public IngredientParseTask(String rawTerm, String normalisedTerm, UUID userId, UUID traceId) {
    if (normalisedTerm == null || normalisedTerm.isBlank()) {
      throw new IllegalArgumentException("normalisedTerm must not be blank");
    }
    this.rawTerm = rawTerm == null ? normalisedTerm : rawTerm;
    this.normalisedTerm = normalisedTerm;
    this.userId = userId;
    this.traceId = traceId;
  }

  @Override
  public TaskType type() {
    return TaskType.NUTRITION_INGREDIENT_PARSE;
  }

  @Override
  public ModelTier tier() {
    // Parse is a simple, high-volume extraction — the cheap tier (gpt-5.4-mini) per ai config.
    return ModelTier.CHEAP;
  }

  @Override
  public PromptRef prompt() {
    return new PromptRef(PROMPT_NAME, PROMPT_VERSION);
  }

  @Override
  public Class<IngredientParseResult> outputType() {
    return IngredientParseResult.class;
  }

  @Override
  public Map<String, Object> variables() {
    return Map.of(
        "ingredient.rawTerm", rawTerm,
        "ingredient.normalisedTerm", normalisedTerm);
  }

  @Override
  public Optional<List<ToolDefinition>> tools() {
    return Optional.of(List.of(TOOL_DEFINITION));
  }

  @Override
  public Optional<UUID> userId() {
    return Optional.ofNullable(userId);
  }

  @Override
  public Optional<UUID> traceId() {
    return Optional.ofNullable(traceId);
  }

  private static ToolDefinition buildToolDefinition() {
    ObjectNode schema = SCHEMA_MAPPER.createObjectNode();
    schema.put("type", "object");
    ObjectNode props = schema.putObject("properties");

    props.putObject("ingredient").put("type", "string");
    ObjectNode searchTerm = props.putObject("usdaSearchTerm");
    searchTerm.put("type", "string");
    searchTerm.put("minLength", 1);

    // quantity / unit / gramsEstimate are TRUE optionals (a line may state none) — the model OMITS
    // them when absent. With strict:false the dispatcher accepts the omission; they are not in
    // `required`. Numbers are kept loosely typed (number) so a model emitting an integer or decimal
    // both validate.
    props.putObject("quantity").put("type", "number");
    props.putObject("unit").put("type", "string");
    props.putObject("gramsEstimate").put("type", "number");
    props.putObject("isCooked").put("type", "boolean");

    ObjectNode confidence = props.putObject("confidence");
    confidence.put("type", "number");
    confidence.put("minimum", 0.0);
    confidence.put("maximum", 1.0);

    // Only the search term + confidence are required — the rest of the structured parse is best
    // -effort and the pipeline tolerates their absence.
    schema.putArray("required").add("usdaSearchTerm").add("confidence");
    return new ToolDefinition(
        TOOL_NAME,
        "Parse one ingredient line into a clean USDA search term and structured fields; no nutrition"
            + " values.",
        schema);
  }
}
