package com.example.mealprep.planner.domain.service.internal.composer;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.adaptation.api.dto.DirectiveKind;
import com.example.mealprep.adaptation.api.dto.PlanTimeRefineDirectiveRequest;
import com.example.mealprep.planner.api.dto.PlanCompositionContext;
import com.example.mealprep.planner.api.dto.RefineDirectiveProposal;
import com.example.mealprep.planner.testdata.PlanTestData;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pure-logic unit test for the package-private {@link RefineDirectiveMapper} (Stage-D mapping). The
 * raw-type→{@link DirectiveKind} translation, the kind-specific {@code targetDelta} payload, the
 * constraints snapshot and the full request assembly were previously exercised only by the wider
 * composer IT — this pins each branch directly.
 */
class RefineDirectiveMapperTest {

  private static final Instant NOW = Instant.parse("2026-05-18T09:00:00Z");
  private final RefineDirectiveMapper mapper =
      new RefineDirectiveMapper(new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC));

  private static RefineDirectiveProposal proposal(
      String type, String from, String to, Integer curMin, Integer tgtMin) {
    return new RefineDirectiveProposal(
        type, UUID.randomUUID(), from, to, curMin, tgtMin, "because reasons");
  }

  // ---- mapKind --------------------------------------------------------------------------------

  @Test
  void mapKind_null_defaults_to_ingredient_swap() {
    assertThat(mapper.mapKind(null)).isEqualTo(DirectiveKind.INGREDIENT_SWAP);
  }

  @Test
  void mapKind_unknown_defaults_to_ingredient_swap() {
    assertThat(mapper.mapKind("TOTALLY_UNKNOWN")).isEqualTo(DirectiveKind.INGREDIENT_SWAP);
  }

  @Test
  void mapKind_known_strings_map_case_and_whitespace_insensitively() {
    assertThat(mapper.mapKind("  substitute_ingredient ")).isEqualTo(DirectiveKind.INGREDIENT_SWAP);
    assertThat(mapper.mapKind("SWAP")).isEqualTo(DirectiveKind.INGREDIENT_SWAP);
    assertThat(mapper.mapKind("reduce_time")).isEqualTo(DirectiveKind.TIME_DELTA);
    assertThat(mapper.mapKind("SHORTEN_TIME")).isEqualTo(DirectiveKind.TIME_DELTA);
    assertThat(mapper.mapKind("reduce_cost")).isEqualTo(DirectiveKind.COST_DELTA);
    assertThat(mapper.mapKind("CHEAPER")).isEqualTo(DirectiveKind.COST_DELTA);
    assertThat(mapper.mapKind("raise_protein")).isEqualTo(DirectiveKind.NUTRITION_DELTA);
    assertThat(mapper.mapKind("ADJUST_NUTRITION")).isEqualTo(DirectiveKind.NUTRITION_DELTA);
    assertThat(mapper.mapKind("equipment")).isEqualTo(DirectiveKind.EQUIPMENT_OVERLAP);
    assertThat(mapper.mapKind("EQUIPMENT_OVERLAP")).isEqualTo(DirectiveKind.EQUIPMENT_OVERLAP);
  }

  // ---- targetDelta ----------------------------------------------------------------------------

  @Test
  void targetDelta_ingredient_swap_carries_from_and_to() {
    ObjectNode delta =
        mapper.targetDelta(
            proposal("SWAP", "butter", "olive-oil", null, null), DirectiveKind.INGREDIENT_SWAP);
    assertThat(delta.get("from").asText()).isEqualTo("butter");
    assertThat(delta.get("to").asText()).isEqualTo("olive-oil");
  }

  @Test
  void targetDelta_time_delta_includes_only_non_null_minutes() {
    ObjectNode both =
        mapper.targetDelta(proposal("REDUCE_TIME", null, null, 60, 30), DirectiveKind.TIME_DELTA);
    assertThat(both.get("currentMin").asInt()).isEqualTo(60);
    assertThat(both.get("targetMin").asInt()).isEqualTo(30);

    // Kills the L71/L74 NegateConditionals: null minutes must be OMITTED, not written.
    ObjectNode neither =
        mapper.targetDelta(
            proposal("REDUCE_TIME", null, null, null, null), DirectiveKind.TIME_DELTA);
    assertThat(neither.has("currentMin")).isFalse();
    assertThat(neither.has("targetMin")).isFalse();

    ObjectNode onlyTarget =
        mapper.targetDelta(proposal("REDUCE_TIME", null, null, null, 25), DirectiveKind.TIME_DELTA);
    assertThat(onlyTarget.has("currentMin")).isFalse();
    assertThat(onlyTarget.get("targetMin").asInt()).isEqualTo(25);
  }

  @Test
  void targetDelta_cost_delta_default_arm_is_empty_node() {
    ObjectNode delta =
        mapper.targetDelta(
            proposal("REDUCE_COST", null, null, null, null), DirectiveKind.COST_DELTA);
    assertThat(delta).isNotNull();
    assertThat(delta.isEmpty()).isTrue();
  }

  // ---- constraintsSnapshot --------------------------------------------------------------------

  @Test
  void constraintsSnapshot_reads_weekly_budget_when_present() {
    var bundle =
        PlanTestData.provisionsBundle(
            PlanTestData.budget(new BigDecimal("42.50")), Map.of(), List.of());
    PlanCompositionContext ctx =
        PlanTestData.scoringContext(List.of(), List.of(), bundle, Map.of(), Map.of());

    var snap = mapper.constraintsSnapshot(ctx);

    assertThat(snap.weeklyBudgetGbp()).isEqualByComparingTo(new BigDecimal("42.50"));
    assertThat(snap.pinnedAt()).isEqualTo(NOW);
    assertThat(snap.equipmentAvailable()).isEmpty();
    assertThat(snap.nutritionTargets()).isEmpty();
  }

  @Test
  void constraintsSnapshot_null_budget_yields_null_weekly_budget() {
    PlanCompositionContext ctx = PlanTestData.minimalContext(List.of(), List.of());
    assertThat(mapper.constraintsSnapshot(ctx).weeklyBudgetGbp()).isNull();
  }

  // ---- toRequest ------------------------------------------------------------------------------

  @Test
  void toRequest_assembles_full_request_with_explicit_parent_decision_id() {
    PlanCompositionContext ctx = PlanTestData.minimalContext(List.of(), List.of());
    UUID recipeId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    UUID planId = UUID.randomUUID();
    UUID slotId = UUID.randomUUID();
    UUID parent = UUID.randomUUID();

    PlanTimeRefineDirectiveRequest req =
        mapper.toRequest(
            proposal("SUBSTITUTE_INGREDIENT", "rice", "quinoa", null, null),
            recipeId,
            userId,
            planId,
            slotId,
            parent,
            ctx);

    assertThat(req.recipeId()).isEqualTo(recipeId);
    assertThat(req.userId()).isEqualTo(userId);
    assertThat(req.planId()).isEqualTo(planId);
    assertThat(req.slotId()).isEqualTo(slotId);
    assertThat(req.directive().kind()).isEqualTo(DirectiveKind.INGREDIENT_SWAP);
    assertThat(req.directive().description()).isEqualTo("because reasons");
    assertThat(req.directive().targetDelta().get("from").asText()).isEqualTo("rice");
    // Kills L135 NegateConditionals: with an explicit parentDecisionId it must be used as-is.
    assertThat(req.parentDecisionId()).isEqualTo(parent);
    assertThat(req.traceId()).isEqualTo(ctx.traceId());
  }

  @Test
  void toRequest_null_parent_decision_id_falls_back_to_trace_id() {
    PlanCompositionContext ctx = PlanTestData.minimalContext(List.of(), List.of());

    PlanTimeRefineDirectiveRequest req =
        mapper.toRequest(
            proposal("REDUCE_TIME", null, null, 45, 20),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            null,
            ctx);

    // Kills L135 (other arm): null parent → context.traceId() anchor.
    assertThat(req.parentDecisionId()).isEqualTo(ctx.traceId());
    assertThat(req.directive().kind()).isEqualTo(DirectiveKind.TIME_DELTA);
    assertThat(req.directive().targetDelta().get("currentMin").asInt()).isEqualTo(45);
  }
}
