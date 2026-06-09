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
 * AI dispatcher task that re-ranks the USDA / OFF search candidates for one ingredient and picks
 * the best match — or declines (LLD nutrition Flow 6 step 5, line 982). The chosen candidate's
 * nutrition is what {@link IngredientMappingPipeline} persists.
 *
 * <p>Cheap-tier (a bounded classification over a short candidate list). The candidate list is
 * rendered into the prompt as a numbered block; the model returns the chosen 0-based index (or
 * {@code -1} for "no good match") via the {@code match_ingredient} tool, validated against {@link
 * #buildToolDefinition()} and deserialised into {@link IngredientMatchResult}.
 */
public final class IngredientMatchTask implements AiTask<IngredientMatchResult> {

  /**
   * Prompt name handed to the renderer; resolves to {@code prompts/nutrition/ingredient-match.txt}.
   */
  public static final String PROMPT_NAME = "nutrition/ingredient-match";

  /** Prompt version owned by the nutrition module. Bump when the prompt body ships a v2. */
  public static final int PROMPT_VERSION = 1;

  /** Tool name the structured-output schema is registered under. */
  static final String TOOL_NAME = "match_ingredient";

  private static final ObjectMapper SCHEMA_MAPPER = new ObjectMapper();
  private static final ToolDefinition TOOL_DEFINITION = buildToolDefinition();

  private final String rawTerm;
  private final String searchTerm;
  private final List<Candidate> candidates;
  private final UUID userId;
  private final UUID traceId;

  public IngredientMatchTask(
      String rawTerm, String searchTerm, List<Candidate> candidates, UUID userId, UUID traceId) {
    if (candidates == null || candidates.isEmpty()) {
      throw new IllegalArgumentException("candidates must not be empty");
    }
    this.rawTerm = rawTerm == null ? searchTerm : rawTerm;
    this.searchTerm = searchTerm;
    this.candidates = List.copyOf(candidates);
    this.userId = userId;
    this.traceId = traceId;
  }

  @Override
  public TaskType type() {
    return TaskType.NUTRITION_INGREDIENT_MATCH;
  }

  @Override
  public ModelTier tier() {
    // Match is a bounded re-rank over a short candidate list — cheap tier (gpt-5.4-mini).
    return ModelTier.CHEAP;
  }

  @Override
  public PromptRef prompt() {
    return new PromptRef(PROMPT_NAME, PROMPT_VERSION);
  }

  @Override
  public Class<IngredientMatchResult> outputType() {
    return IngredientMatchResult.class;
  }

  @Override
  public Map<String, Object> variables() {
    return Map.of(
        "ingredient.rawTerm",
        rawTerm,
        "ingredient.searchTerm",
        searchTerm == null ? "" : searchTerm,
        "candidates",
        renderCandidates());
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

  /**
   * Number of candidates the model may choose from — the valid index range is {@code 0..size-1}.
   */
  public int candidateCount() {
    return candidates.size();
  }

  /** One numbered candidate line per row, e.g. {@code [0] (USDA) Chicken, breast, raw}. */
  private String renderCandidates() {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < candidates.size(); i++) {
      Candidate c = candidates.get(i);
      if (i > 0) {
        sb.append('\n');
      }
      sb.append('[')
          .append(i)
          .append("] (")
          .append(c.source())
          .append(") ")
          .append(c.description());
    }
    return sb.toString();
  }

  private static ToolDefinition buildToolDefinition() {
    ObjectNode schema = SCHEMA_MAPPER.createObjectNode();
    schema.put("type", "object");
    ObjectNode props = schema.putObject("properties");

    ObjectNode chosen = props.putObject("chosenIndex");
    chosen.put("type", "integer");
    // -1 == no good match; otherwise a 0-based index into the candidate list.
    chosen.put("minimum", -1);

    ObjectNode confidence = props.putObject("confidence");
    confidence.put("type", "number");
    confidence.put("minimum", 0.0);
    confidence.put("maximum", 1.0);

    props.putObject("reason").put("type", "string");

    schema.putArray("required").add("chosenIndex").add("confidence");
    return new ToolDefinition(
        TOOL_NAME,
        "Pick the best-matching candidate by 0-based index, or -1 for no good match; no nutrition"
            + " values.",
        schema);
  }

  /**
   * A single candidate the model re-ranks. {@code source} is a human label (USDA / OFF) shown to
   * the model as a mild tie-breaker; {@code externalId} is the database id the pipeline persists if
   * this candidate is chosen; {@code description} is the food-database title the model reads.
   */
  public record Candidate(String source, String externalId, String description) {}
}
