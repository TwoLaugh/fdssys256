package com.example.mealprep.planner.domain.service.internal.additions;

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
 * Culinary-appropriateness gate for in-meal additions (Phase 2, inc 3 — the "recipe-changer AI").
 * The deterministic {@link IngredientAdditionPlanner} decides WHICH whole foods close the day's
 * calorie + micro gap; this task decides which meal each pairs with best and writes the note. MID
 * tier (gpt-5.4-mini class) — sensible pairing needs some food reasoning but is not frontier-grade.
 * Mirrors {@code NutrientEstimationTask}: structured output via a hand-built tool schema, the
 * dispatcher deserialises into {@link AdditionPairingResult}. Skippable — the planner falls back to
 * deterministic placement on {@code AiUnavailableException}.
 */
public final class AdditionPairingTask implements AiTask<AdditionPairingResult> {

  /** Resolves to {@code prompts/planner/addition-pairing.txt}. */
  public static final String PROMPT_NAME = "planner/addition-pairing";

  public static final int PROMPT_VERSION = 1;

  static final String TOOL_NAME = "pair_additions";

  private static final ObjectMapper SCHEMA_MAPPER = new ObjectMapper();
  private static final ToolDefinition TOOL_DEFINITION = buildToolDefinition();

  private final String additions;
  private final String meals;
  private final String slotKinds;
  private final UUID userId;
  private final UUID traceId;

  public AdditionPairingTask(
      String additions, String meals, String slotKinds, UUID userId, UUID traceId) {
    this.additions = additions == null ? "" : additions;
    this.meals = meals == null ? "" : meals;
    this.slotKinds = slotKinds == null ? "" : slotKinds;
    this.userId = userId;
    this.traceId = traceId;
  }

  @Override
  public TaskType type() {
    return TaskType.PLANNER_ADDITION_PAIRING;
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
  public Class<AdditionPairingResult> outputType() {
    return AdditionPairingResult.class;
  }

  @Override
  public Map<String, Object> variables() {
    return Map.of(
        "additions", additions,
        "meals", meals,
        "slot.kinds", slotKinds);
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

    ObjectNode placements = props.putObject("placements");
    placements.put("type", "array");
    ObjectNode item = placements.putObject("items");
    item.put("type", "object");
    ObjectNode itemProps = item.putObject("properties");
    itemProps.putObject("additionName").put("type", "string");
    itemProps.putObject("slotKind").put("type", "string");
    itemProps.putObject("note").put("type", "string");
    item.putArray("required").add("additionName").add("slotKind").add("note");

    schema.putArray("required").add("placements");
    return new ToolDefinition(
        TOOL_NAME,
        "Assign each supplied addition to the meal slot it pairs with best and write a short, natural"
            + " note. additionName must echo a supplied addition; slotKind must be one of the"
            + " supplied kinds.",
        schema);
  }
}
