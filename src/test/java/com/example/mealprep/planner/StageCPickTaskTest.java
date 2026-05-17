package com.example.mealprep.planner;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.ai.spi.ModelTier;
import com.example.mealprep.ai.spi.TaskType;
import com.example.mealprep.ai.spi.ToolDefinition;
import com.example.mealprep.planner.api.dto.IndexedCandidateRollup;
import com.example.mealprep.planner.domain.entity.TriggerKind;
import com.example.mealprep.planner.domain.service.internal.stagec.StageCPickResponse;
import com.example.mealprep.planner.domain.service.internal.stagec.StageCPickTask;
import com.example.mealprep.planner.testdata.PlanTestData;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Unit tests for the Stage-C {@link StageCPickTask} wiring (request shape). */
class StageCPickTaskTest {

  private static final LocalDate WEEK = LocalDate.of(2026, 1, 5);

  private static StageCPickTask task(UUID userId, UUID traceId) {
    List<IndexedCandidateRollup> indexed =
        List.of(
            new IndexedCandidateRollup(
                0, UUID.randomUUID(), PlanTestData.candidateRollup(WEEK, 2100)),
            new IndexedCandidateRollup(
                1, UUID.randomUUID(), PlanTestData.candidateRollup(WEEK, 1900)));
    return new StageCPickTask(
        indexed,
        "Household of 2 people; 2 member hard-constraint profile(s); week starting " + WEEK + ".",
        2,
        WEEK,
        TriggerKind.USER_INITIATED,
        userId,
        traceId);
  }

  @Test
  void task_type_is_planner_stage_c_and_tier_is_mid() {
    StageCPickTask t = task(UUID.randomUUID(), UUID.randomUUID());
    assertThat(t.type()).isEqualTo(TaskType.PLANNER_STAGE_C);
    assertThat(t.tier()).isEqualTo(ModelTier.MID);
    assertThat(t.outputType()).isEqualTo(StageCPickResponse.class);
  }

  @Test
  void prompt_ref_points_at_stage_c_pick_template_v1() {
    StageCPickTask t = task(UUID.randomUUID(), UUID.randomUUID());
    assertThat(t.prompt().name()).isEqualTo("planner/stage-c-pick");
    assertThat(t.prompt().version()).isEqualTo(1);
  }

  @Test
  void variables_carry_all_context_keys() {
    StageCPickTask t = task(UUID.randomUUID(), UUID.randomUUID());
    Map<String, Object> vars = t.variables();
    assertThat(vars)
        .containsKeys(
            "candidates", "constraints_summary", "household_size", "week_start", "trigger");
    assertThat(vars.get("week_start")).isEqualTo(WEEK.toString());
    assertThat(vars.get("trigger")).isEqualTo("USER_INITIATED");
    assertThat(vars.get("household_size")).isEqualTo(2);
    @SuppressWarnings("unchecked")
    List<IndexedCandidateRollup> candidates = (List<IndexedCandidateRollup>) vars.get("candidates");
    assertThat(candidates).hasSize(2);
    assertThat(candidates.get(0).index()).isZero();
  }

  @Test
  void tool_schema_matches_response_record_shape() {
    StageCPickTask t = task(UUID.randomUUID(), UUID.randomUUID());
    assertThat(t.tools()).isPresent();
    List<ToolDefinition> tools = t.tools().orElseThrow();
    assertThat(tools).hasSize(1);
    ToolDefinition tool = tools.get(0);
    assertThat(tool.name()).isEqualTo("stage_c_pick_response");
    assertThat(tool.inputSchema().path("properties").fieldNames())
        .toIterable()
        .containsExactlyInAnyOrder("chosenIndex", "reasoning");
    assertThat(tool.inputSchema().path("required"))
        .anySatisfy(n -> assertThat(n.asText()).isEqualTo("chosenIndex"))
        .anySatisfy(n -> assertThat(n.asText()).isEqualTo("reasoning"));
  }

  @Test
  void user_and_trace_ids_propagate_and_are_optional() {
    UUID userId = UUID.randomUUID();
    UUID traceId = UUID.randomUUID();
    StageCPickTask t = task(userId, traceId);
    assertThat(t.userId()).contains(userId);
    assertThat(t.traceId()).contains(traceId);

    StageCPickTask nullIds = task(null, null);
    assertThat(nullIds.userId()).isEmpty();
    assertThat(nullIds.traceId()).isEmpty();
  }

  @Test
  void system_prompt_is_a_non_blank_pilot_constant() {
    assertThat(task(UUID.randomUUID(), UUID.randomUUID()).systemPrompt())
        .isNotBlank()
        .contains("pick the best");
  }

  @Test
  void same_inputs_produce_identical_variables_payload() {
    UUID candId = UUID.randomUUID();
    List<IndexedCandidateRollup> indexed =
        List.of(new IndexedCandidateRollup(0, candId, PlanTestData.candidateRollup(WEEK, 2100)));
    StageCPickTask a =
        new StageCPickTask(indexed, "summary", 1, WEEK, TriggerKind.USER_INITIATED, candId, candId);
    StageCPickTask b =
        new StageCPickTask(indexed, "summary", 1, WEEK, TriggerKind.USER_INITIATED, candId, candId);
    assertThat(a.variables()).isEqualTo(b.variables());
  }
}
