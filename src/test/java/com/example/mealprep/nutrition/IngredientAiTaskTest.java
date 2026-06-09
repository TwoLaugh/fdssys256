package com.example.mealprep.nutrition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.mealprep.ai.spi.ModelTier;
import com.example.mealprep.ai.spi.TaskType;
import com.example.mealprep.ai.spi.ToolDefinition;
import com.example.mealprep.nutrition.domain.service.internal.IngredientMatchResult;
import com.example.mealprep.nutrition.domain.service.internal.IngredientMatchTask;
import com.example.mealprep.nutrition.domain.service.internal.IngredientParseResult;
import com.example.mealprep.nutrition.domain.service.internal.IngredientParseTask;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Shape + schema tests for the two nutrition AI tasks (nutrition-01k). */
class IngredientAiTaskTest {

  // ---------------- parse task ----------------

  @Test
  void parseTask_isCheapTier_withParseSchema() {
    IngredientParseTask task = new IngredientParseTask("2 eggs", "2 eggs", null, null);

    assertThat(task.type()).isEqualTo(TaskType.NUTRITION_INGREDIENT_PARSE);
    assertThat(task.tier()).isEqualTo(ModelTier.CHEAP);
    assertThat(task.prompt().name()).isEqualTo("nutrition/ingredient-parse");
    assertThat(task.outputType()).isEqualTo(IngredientParseResult.class);

    ToolDefinition tool = task.tools().orElseThrow().get(0);
    assertThat(tool.name()).isEqualTo("parse_ingredient");
    JsonNode schema = tool.inputSchema();
    assertThat(schema.get("type").asText()).isEqualTo("object");
    assertThat(schema.get("properties").has("usdaSearchTerm")).isTrue();
    // usdaSearchTerm + confidence are required; the rest of the parse is optional.
    assertThat(schema.get("required").toString()).contains("usdaSearchTerm").contains("confidence");
  }

  @Test
  void parseTask_rejectsBlankNormalisedTerm() {
    assertThatThrownBy(() -> new IngredientParseTask("raw", "  ", null, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void parseResult_searchTermOrNull_trimsAndNullsBlank() {
    assertThat(
            new IngredientParseResult("egg", "  egg  ", null, null, null, false, null)
                .searchTermOrNull())
        .isEqualTo("egg");
    assertThat(
            new IngredientParseResult("egg", "   ", null, null, null, false, null)
                .searchTermOrNull())
        .isNull();
    assertThat(
            new IngredientParseResult("egg", null, null, null, null, false, null)
                .searchTermOrNull())
        .isNull();
  }

  // ---------------- match task ----------------

  @Test
  void matchTask_isCheapTier_withMatchSchema_andNumberedCandidates() {
    IngredientMatchTask task =
        new IngredientMatchTask(
            "chicken breast",
            "chicken breast",
            List.of(
                new IngredientMatchTask.Candidate("USDA", "111", "Chicken breast, raw"),
                new IngredientMatchTask.Candidate("OFF", "222", "Smoked chicken breast")),
            null,
            null);

    assertThat(task.type()).isEqualTo(TaskType.NUTRITION_INGREDIENT_MATCH);
    assertThat(task.tier()).isEqualTo(ModelTier.CHEAP);
    assertThat(task.prompt().name()).isEqualTo("nutrition/ingredient-match");
    assertThat(task.candidateCount()).isEqualTo(2);

    ToolDefinition tool = task.tools().orElseThrow().get(0);
    assertThat(tool.name()).isEqualTo("match_ingredient");
    JsonNode schema = tool.inputSchema();
    assertThat(schema.get("properties").get("chosenIndex").get("minimum").asInt()).isEqualTo(-1);

    // Candidates are rendered as a numbered block for the prompt.
    String rendered = (String) task.variables().get("candidates");
    assertThat(rendered).contains("[0] (USDA) Chicken breast, raw");
    assertThat(rendered).contains("[1] (OFF) Smoked chicken breast");
  }

  @Test
  void matchTask_rejectsEmptyCandidates() {
    assertThatThrownBy(() -> new IngredientMatchTask("x", "x", List.of(), null, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void matchResult_noMatch_andConfidenceHelpers() {
    assertThat(new IngredientMatchResult(-1, BigDecimal.ZERO, "none").isNoMatch()).isTrue();
    assertThat(new IngredientMatchResult(0, new BigDecimal("0.9"), "ok").isNoMatch()).isFalse();
    assertThat(new IngredientMatchResult(0, null, "ok").confidenceOrZero()).isEqualTo(0.0);
    assertThat(new IngredientMatchResult(0, new BigDecimal("0.42"), "ok").confidenceOrZero())
        .isEqualTo(0.42);
  }
}
