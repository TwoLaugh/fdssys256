package com.example.mealprep.planner.domain.service.internal.stagec;

import com.example.mealprep.ai.spi.AiTask;
import com.example.mealprep.ai.spi.ModelTier;
import com.example.mealprep.ai.spi.PromptRef;
import com.example.mealprep.ai.spi.TaskType;
import com.example.mealprep.ai.spi.ToolDefinition;
import com.example.mealprep.planner.api.dto.IndexedCandidateRollup;
import com.example.mealprep.planner.domain.entity.TriggerKind;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The single concrete {@link AiTask} the planner supplies for Stage C (prompt #8). Built per
 * composition run by {@code StageCInvokerImpl}. Per {@code lld/planner.md} §StageCInvoker (lines
 * 821-867).
 *
 * <p><b>SPI shape note.</b> The merged {@link AiTask} SPI is the lean {@code
 * type/tier/prompt/outputType/variables/tools/userId/traceId} surface — the {@code getSystemPrompt}
 * / {@code getTimeoutOverride} / {@code getToolSchema} hooks in the older ticket pseudocode never
 * landed on the SPI (tier + timeout resolution is owned by the AI dispatcher, keyed off {@link
 * TaskType}). The system prompt is exposed via the non-SPI {@link #systemPrompt()} accessor
 * (mirroring {@code RecipeAdaptationTask}); the per-call timeout override the ticket mentions has
 * no SPI seam, so {@code StageCInvokerImpl} keeps {@code PlannerProperties.stageCTimeout} for
 * documentation / future-wiring but the dispatcher applies its {@link TaskType}-keyed timeout.
 *
 * <p>The AI module ships no {@code ToolDefinition.from(Class)} auto-derivation (verified absent —
 * {@code RecipeAdaptationTask} hand-writes its schema too), so the 2-field {@link
 * StageCPickResponse} shape is mirrored in {@link #buildToolDefinition()}.
 */
public final class StageCPickTask implements AiTask<StageCPickResponse> {

  /**
   * v1 pilot system prompt. Intentionally minimal — sized to validate the wiring. The real content
   * is a separate prompt-engineering exercise with its own eval set (LLD §Out of Scope). The user
   * prompt body lives at {@code prompts/planner/stage-c-pick.txt}.
   */
  static final String SYSTEM_PROMPT =
      "You are a meal planning assistant. Your task is to pick the best weekly meal plan from N"
          + " candidates given the household's constraints and weekly rollups. Use the tool to"
          + " return the index of the chosen plan and a brief one-paragraph reasoning.";

  /** Tool name the structured-output schema is registered under. */
  static final String TOOL_NAME = "stage_c_pick_response";

  /** Prompt-template ref; resolves to {@code prompts/planner/stage-c-pick.txt}. */
  static final PromptRef PROMPT_REF = new PromptRef("planner/stage-c-pick", 1);

  private static final ObjectMapper SCHEMA_MAPPER = new ObjectMapper();
  private static final ToolDefinition TOOL_DEFINITION = buildToolDefinition();

  private final List<IndexedCandidateRollup> indexed;
  private final String constraintsSummary;
  private final int householdSize;
  private final LocalDate weekStartDate;
  private final TriggerKind trigger;
  private final UUID primaryUserId;
  private final UUID traceId;

  public StageCPickTask(
      List<IndexedCandidateRollup> indexed,
      String constraintsSummary,
      int householdSize,
      LocalDate weekStartDate,
      TriggerKind trigger,
      UUID primaryUserId,
      UUID traceId) {
    this.indexed = List.copyOf(indexed);
    this.constraintsSummary = constraintsSummary;
    this.householdSize = householdSize;
    this.weekStartDate = weekStartDate;
    this.trigger = trigger;
    this.primaryUserId = primaryUserId;
    this.traceId = traceId;
  }

  @Override
  public TaskType type() {
    return TaskType.PLANNER_STAGE_C;
  }

  @Override
  public ModelTier tier() {
    // Per lld/planner.md line 835: mid tier (HLD §AI Model Tiers).
    return ModelTier.MID;
  }

  @Override
  public PromptRef prompt() {
    return PROMPT_REF;
  }

  @Override
  public Class<StageCPickResponse> outputType() {
    return StageCPickResponse.class;
  }

  @Override
  public Map<String, Object> variables() {
    return Map.of(
        "candidates", indexed,
        "constraints_summary", constraintsSummary,
        "household_size", householdSize,
        "week_start", weekStartDate.toString(),
        "trigger", trigger.name());
  }

  @Override
  public Optional<List<ToolDefinition>> tools() {
    return Optional.of(List.of(TOOL_DEFINITION));
  }

  @Override
  public Optional<UUID> userId() {
    return Optional.ofNullable(primaryUserId);
  }

  @Override
  public Optional<UUID> traceId() {
    return Optional.ofNullable(traceId);
  }

  /** Exposes the v1 pilot system prompt for the dispatcher's in-memory fallback path. */
  public String systemPrompt() {
    return SYSTEM_PROMPT;
  }

  private static ToolDefinition buildToolDefinition() {
    ObjectNode schema = SCHEMA_MAPPER.createObjectNode();
    schema.put("type", "object");
    ObjectNode props = schema.putObject("properties");
    props.putObject("chosenIndex").put("type", "integer");
    props.putObject("reasoning").put("type", "string");
    schema.putArray("required").add("chosenIndex").add("reasoning");
    return new ToolDefinition(
        TOOL_NAME, "The chosen candidate index and a brief reasoning.", schema);
  }
}
