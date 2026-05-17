package com.example.mealprep.adaptation;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.adaptation.ai.AdaptationContext;
import com.example.mealprep.adaptation.ai.RecipeAdaptationResponse;
import com.example.mealprep.adaptation.ai.RecipeAdaptationTask;
import com.example.mealprep.adaptation.api.dto.NutritionalKnowledgeBundleDto;
import com.example.mealprep.adaptation.config.AdaptationConfig;
import com.example.mealprep.adaptation.domain.entity.AdaptationJob;
import com.example.mealprep.adaptation.domain.enums.ApprovalPolicy;
import com.example.mealprep.adaptation.domain.enums.JobPriority;
import com.example.mealprep.adaptation.domain.enums.JobSource;
import com.example.mealprep.adaptation.domain.enums.JobStatus;
import com.example.mealprep.ai.spi.ModelTier;
import com.example.mealprep.ai.spi.TaskType;
import com.example.mealprep.ai.spi.ToolDefinition;
import com.example.mealprep.recipe.domain.entity.Catalogue;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RecipeAdaptationTaskTest {

  private static final AdaptationConfig CONFIG =
      new AdaptationConfig(
          5,
          10_000,
          8_000,
          12_000,
          3,
          3,
          14,
          new BigDecimal("0.50"),
          new BigDecimal("2.00"),
          null,
          30,
          "0 0 4 * * *",
          "0 30 4 * * *");

  @Test
  void task_type_is_recipe_adaptation_and_tier_is_mid() {
    RecipeAdaptationTask task = task(JobSource.FEEDBACK, ctx("FEEDBACK", "hello", null));
    assertThat(task.type()).isEqualTo(TaskType.RECIPE_ADAPTATION);
    assertThat(task.tier()).isEqualTo(ModelTier.MID);
    assertThat(task.outputType()).isEqualTo(RecipeAdaptationResponse.class);
  }

  @Test
  void prompt_ref_points_at_recipe_adaptation_template_v1() {
    RecipeAdaptationTask task = task(JobSource.IMPORT, ctx("IMPORT", null, null));
    assertThat(task.prompt().name()).isEqualTo("RecipeAdaptationTask");
    assertThat(task.prompt().version()).isEqualTo(1);
  }

  @Test
  void variables_include_all_keys_and_never_npe_on_null_trigger_fields() {
    // IMPORT has no feedbackText/ratingDelta/directive/dataModelChange — all null.
    RecipeAdaptationTask task = task(JobSource.IMPORT, ctx("IMPORT", null, null));
    Map<String, Object> vars = task.variables();
    assertThat(vars)
        .containsKeys(
            "mode",
            "recipe",
            "candidates",
            "softPreferences",
            "hardConstraintsHash",
            "nutritionTargets",
            "knowledgeBundle",
            "feedbackText",
            "ratingDelta",
            "directive",
            "dataModelChange");
    assertThat(vars.get("feedbackText")).isEqualTo("");
    assertThat(vars.get("ratingDelta")).isEqualTo("");
    assertThat(vars.get("directive")).isEqualTo("");
    assertThat(vars.get("dataModelChange")).isEqualTo("");
    assertThat(vars.get("mode")).isEqualTo("IMPORT");
  }

  @Test
  void variables_pass_non_null_feedback_through() {
    RecipeAdaptationTask task = task(JobSource.FEEDBACK, ctx("FEEDBACK", "too salty", null));
    assertThat(task.variables().get("feedbackText")).isEqualTo("too salty");
  }

  @Test
  void tool_schema_matches_response_record_shape() {
    RecipeAdaptationTask task = task(JobSource.FEEDBACK, ctx("FEEDBACK", "x", null));
    assertThat(task.tools()).isPresent();
    List<ToolDefinition> tools = task.tools().orElseThrow();
    assertThat(tools).hasSize(1);
    ToolDefinition tool = tools.get(0);
    assertThat(tool.name()).isEqualTo("recipe_adaptation_response");
    assertThat(tool.inputSchema().path("properties").fieldNames())
        .toIterable()
        .contains(
            "chosenCandidateIndex",
            "classification",
            "reasoning",
            "nutritionalNotes",
            "confidence",
            "characterPreservationScore",
            "refinedDiff",
            "plannerHints");
  }

  @Test
  void user_and_trace_ids_come_from_job() {
    UUID userId = UUID.randomUUID();
    UUID traceId = UUID.randomUUID();
    AdaptationJob job = job(JobSource.FEEDBACK, userId, traceId);
    RecipeAdaptationTask task =
        new RecipeAdaptationTask(
            job,
            ctx("FEEDBACK", "x", null),
            task(JobSource.FEEDBACK, ctx("FEEDBACK", "x", null)).prompt(),
            CONFIG);
    assertThat(task.userId()).contains(userId);
    assertThat(task.traceId()).contains(traceId);
  }

  private static RecipeAdaptationTask task(JobSource source, AdaptationContext ctx) {
    return new RecipeAdaptationTask(
        job(source, UUID.randomUUID(), UUID.randomUUID()),
        ctx,
        new com.example.mealprep.ai.spi.PromptRef("RecipeAdaptationTask", 1),
        CONFIG);
  }

  private static AdaptationContext ctx(String mode, String feedbackText, Object unused) {
    return new AdaptationContext(
        mode,
        null,
        null,
        null,
        List.of(),
        null,
        "hc:none",
        null,
        new NutritionalKnowledgeBundleDto(List.of(), List.of(), List.of(), List.of()),
        feedbackText,
        null,
        null,
        null);
  }

  private static AdaptationJob job(JobSource source, UUID userId, UUID traceId) {
    return AdaptationJob.builder()
        .id(UUID.randomUUID())
        .recipeId(UUID.randomUUID())
        .userId(userId)
        .catalogue(Catalogue.USER)
        .source(source)
        .priority(JobPriority.SYNC)
        .approvalPolicy(ApprovalPolicy.PENDING_CHANGE)
        .status(JobStatus.RUNNING)
        .inputs(JsonNodeFactory.instance.objectNode())
        .traceId(traceId)
        .enqueuedAt(Instant.now())
        .build();
  }
}
