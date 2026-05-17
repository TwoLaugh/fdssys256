package com.example.mealprep.adaptation.ai;

import com.example.mealprep.adaptation.config.AdaptationConfig;
import com.example.mealprep.adaptation.domain.entity.AdaptationJob;
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
 * The single concrete {@link AiTask} the adaptation pipeline supplies to the AI dispatcher. Built
 * per Stage-C dispatch by {@link com.example.mealprep.adaptation.ai.internal
 * .RecipeAdaptationTaskFactoryImpl} from the loaded {@link AdaptationContext}.
 *
 * <p>Per LLD §The RecipeAdaptationTask (lines 816-868). The merged {@code AiTask} SPI shape (see
 * {@link AiTask}) is the lean {@code type/tier/prompt/outputType/variables/tools/userId/traceId}
 * surface — the per-mode timeout override and {@code getSystemPrompt} hooks in older ticket
 * pseudocode never landed on the SPI (timeout/tier resolution is owned by the AI dispatcher keyed
 * off {@link TaskType}). The per-source timeout values still live on {@link AdaptationConfig} and
 * are wired by the AI module's tier/timeout table; this task only carries the discriminator.
 *
 * <p><b>Nullable context values</b> ({@code feedbackText}, {@code ratingDelta}, {@code directive},
 * {@code dataModelChange}) are run through {@link #orEmpty} so the variables map never trips {@code
 * Map.of}'s null-hostile contract — the prompt template renders empty placeholders cleanly.
 */
public final class RecipeAdaptationTask implements AiTask<RecipeAdaptationResponse> {

  /**
   * v1 placeholder system prompt. Intentionally minimal — the prompt engineer ships the real
   * content via {@code lld/prompts/05-recipe-adaptation.md} (LLD line 945 "Prompt content
   * deferred"). The runtime template name resolves to {@code RecipeAdaptationTask} v1 in the {@code
   * ai_prompt_template} table.
   */
  static final String SYSTEM_PROMPT =
      "You are MealPrep's recipe-adaptation assistant. Given a recipe and a list of pre-validated"
          + " candidate adaptations, choose the best candidate that satisfies the trigger goal"
          + " while preserving the recipe's culinary character. Output a single structured"
          + " response matching the RecipeAdaptationResponse tool schema. Reasoning must be one or"
          + " two sentences. Confidence is a 0..1 scalar. characterPreservationScore is a 0..1"
          + " scalar measuring how well the adapted recipe preserves the original's identity. When"
          + " classification=NO_CHANGE, set chosenCandidateIndex=-1 and explain why no candidate"
          + " satisfies the goal.";

  /** Tool name the structured-output schema is registered under. */
  static final String TOOL_NAME = "recipe_adaptation_response";

  private static final ObjectMapper SCHEMA_MAPPER = new ObjectMapper();

  /**
   * Hand-written JSON-schema for {@link RecipeAdaptationResponse}. The AI module ships no {@code
   * ToolDefinitionFactory.from(Class)} auto-derivation (verified absent in 01e), so the 8-field
   * record shape is mirrored here. <b>Worth user review.</b>
   */
  private static final ToolDefinition TOOL_DEFINITION = buildToolDefinition();

  private final AdaptationJob job;
  private final AdaptationContext context;
  private final PromptRef userPromptRef;

  @SuppressWarnings("unused")
  private final AdaptationConfig config;

  public RecipeAdaptationTask(
      AdaptationJob job, AdaptationContext context, PromptRef ref, AdaptationConfig config) {
    this.job = job;
    this.context = context;
    this.userPromptRef = ref;
    this.config = config;
  }

  @Override
  public TaskType type() {
    return TaskType.RECIPE_ADAPTATION;
  }

  @Override
  public ModelTier tier() {
    // Per lld/prompts/05-recipe-adaptation.md wiring: Sonnet 4.6 (mid).
    return ModelTier.MID;
  }

  @Override
  public PromptRef prompt() {
    return userPromptRef;
  }

  @Override
  public Class<RecipeAdaptationResponse> outputType() {
    return RecipeAdaptationResponse.class;
  }

  @Override
  public Map<String, Object> variables() {
    // Map.ofEntries (not Map.of) — the LLD specifies 11 keys; Map.of caps at 10 pairs.
    return Map.ofEntries(
        Map.entry("mode", orEmpty(context.mode())),
        Map.entry("recipe", orEmpty(context.recipe())),
        Map.entry("candidates", orEmpty(context.candidates())),
        Map.entry("softPreferences", orEmpty(context.softPreferencesHash())),
        Map.entry("hardConstraintsHash", orEmpty(context.hardConstraintsHash())),
        Map.entry("nutritionTargets", orEmpty(context.nutritionTargetsSummary())),
        Map.entry("knowledgeBundle", orEmpty(context.knowledgeBundle())),
        Map.entry("feedbackText", orEmpty(context.feedbackText())),
        Map.entry("ratingDelta", orEmpty(context.ratingDelta())),
        Map.entry("directive", orEmpty(context.directive())),
        Map.entry("dataModelChange", orEmpty(context.dataModelChange())));
  }

  @Override
  public Optional<List<ToolDefinition>> tools() {
    return Optional.of(List.of(TOOL_DEFINITION));
  }

  @Override
  public Optional<UUID> userId() {
    return Optional.ofNullable(job.getUserId());
  }

  @Override
  public Optional<UUID> traceId() {
    return Optional.ofNullable(job.getTraceId());
  }

  /** Exposes the v1 placeholder system prompt for the factory / dispatcher fallback path. */
  public String systemPrompt() {
    return SYSTEM_PROMPT;
  }

  /**
   * Null-safe substitution for {@code Map.of}: a null value becomes an empty {@code String} so the
   * Mustache-style renderer emits an empty placeholder rather than the dispatcher NPEing on {@code
   * Map.of}. Non-null values pass through unchanged.
   */
  static Object orEmpty(Object value) {
    return value == null ? "" : value;
  }

  private static ToolDefinition buildToolDefinition() {
    ObjectNode schema = SCHEMA_MAPPER.createObjectNode();
    schema.put("type", "object");
    ObjectNode props = schema.putObject("properties");
    props.putObject("chosenCandidateIndex").put("type", "integer");
    props.putObject("classification").put("type", "string");
    props.putObject("reasoning").put("type", "string");
    props.putObject("nutritionalNotes").put("type", "string");
    props.putObject("confidence").put("type", "number");
    props.putObject("characterPreservationScore").put("type", "number");
    props.putObject("refinedDiff").put("type", "object");
    props.putObject("finalDiffJson").put("type", "object");
    props.putObject("plannerHints").put("type", "array");
    schema
        .putArray("required")
        .add("chosenCandidateIndex")
        .add("classification")
        .add("reasoning")
        .add("confidence")
        .add("characterPreservationScore");
    return new ToolDefinition(
        TOOL_NAME, "Structured recipe-adaptation decision and proposed change.", schema);
  }
}
