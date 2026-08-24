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
 * AI dispatcher task that estimates the per-serving micronutrients a data source could NOT supply
 * (no USDA-from-ingredient value, none on the recipe source), from the recipe's name + ingredient
 * lines. The provenance seam's {@code estimated} tier — its output is always written {@code
 * source="estimated"} with the returned {@code confidence}, never silently merged with
 * measured/derived numbers.
 *
 * <p>MID tier: estimation needs some food-composition reasoning but is not frontier-grade. Mirrors
 * {@link IngredientParseTask} — structured output via a hand-built tool schema; the dispatcher
 * deserialises into {@link NutrientEstimationResult}. In the e2e profile {@code TestAiService}
 * intercepts the call with a canned response; with the OpenAI/Anthropic provider active and a key
 * present it is a real completion.
 */
public final class NutrientEstimationTask implements AiTask<NutrientEstimationResult> {

  /** Resolves to {@code prompts/nutrition/nutrient-estimation.txt}. */
  public static final String PROMPT_NAME = "nutrition/nutrient-estimation";

  public static final int PROMPT_VERSION = 1;

  static final String TOOL_NAME = "estimate_micros";

  private static final ObjectMapper SCHEMA_MAPPER = new ObjectMapper();
  private static final ToolDefinition TOOL_DEFINITION = buildToolDefinition();

  private final String recipeName;
  private final List<String> ingredients;
  private final int servings;
  private final List<String> missingNutrientKeys;
  private final UUID userId;
  private final UUID traceId;

  public NutrientEstimationTask(
      String recipeName,
      List<String> ingredients,
      int servings,
      List<String> missingNutrientKeys,
      UUID userId,
      UUID traceId) {
    this.recipeName = recipeName == null ? "" : recipeName;
    this.ingredients = ingredients == null ? List.of() : ingredients;
    this.servings = servings <= 0 ? 1 : servings;
    this.missingNutrientKeys = missingNutrientKeys == null ? List.of() : missingNutrientKeys;
    this.userId = userId;
    this.traceId = traceId;
  }

  @Override
  public TaskType type() {
    return TaskType.NUTRIENT_ESTIMATION;
  }

  @Override
  public ModelTier tier() {
    return ModelTier.MID;
  }

  @Override
  public PromptRef prompt() {
    return new PromptRef(PROMPT_NAME, PROMPT_VERSION);
  }

  @Override
  public Class<NutrientEstimationResult> outputType() {
    return NutrientEstimationResult.class;
  }

  @Override
  public Map<String, Object> variables() {
    return Map.of(
        "recipe.name",
        recipeName,
        "recipe.servings",
        servings,
        "recipe.ingredients",
        String.join("\n", ingredients),
        "missing.nutrientKeys",
        String.join(", ", missingNutrientKeys));
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

    ObjectNode estimates = props.putObject("estimates");
    estimates.put("type", "array");
    ObjectNode item = estimates.putObject("items");
    item.put("type", "object");
    ObjectNode itemProps = item.putObject("properties");
    // nutrientKey must echo one of the requested canonical keys (e.g. iodine_mcg) so the unit is
    // unambiguous (the suffix declares mg vs mcg); the value is per single serving.
    itemProps.putObject("nutrientKey").put("type", "string");
    itemProps.putObject("perServingValue").put("type", "number");
    ObjectNode conf = itemProps.putObject("confidence");
    conf.put("type", "number");
    conf.put("minimum", 0.0);
    conf.put("maximum", 1.0);
    item.putArray("required").add("nutrientKey").add("perServingValue").add("confidence");

    schema.putArray("required").add("estimates");
    return new ToolDefinition(
        TOOL_NAME,
        "Estimate per-serving values for the requested micronutrients the data source lacked, each"
            + " with a 0-1 confidence. Estimates only — never echo measured values.",
        schema);
  }
}
