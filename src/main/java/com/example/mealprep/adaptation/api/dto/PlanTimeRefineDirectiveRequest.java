package com.example.mealprep.adaptation.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Trigger-4 request — planner Stage D sync entry. The planner blocks on the call while the
 * adaptation pipeline runs the four-stage flow within {@link
 * com.example.mealprep.adaptation.config.AdaptationConfig#planTimeTimeoutMs()} ms; if AI is
 * unavailable the call throws {@code AdaptationAiUnavailableException}.
 *
 * <p>The outcome is always a substitution overlay (or {@code NO_CHANGE} on infeasibility) —
 * plan-time bypasses user approval per HLD §Job sources, so no {@link PendingChangeDto} is created.
 * Sibling planner-tickets read {@link AdaptationResultDto#classification()} {@code == NO_CHANGE} as
 * the infeasibility signal.
 *
 * <p>Per LLD §DTOs lines 344-354; verbatim from {@code lld/adaptation-pipeline.md}.
 */
public record PlanTimeRefineDirectiveRequest(
    @NotNull UUID recipeId,
    @NotNull UUID userId,
    @NotNull UUID planId,
    @NotNull UUID slotId,
    @NotNull @Valid RefineDirectiveDto directive,
    @NotNull PlanConstraintsSnapshotDto constraints,
    @NotNull UUID parentDecisionId,
    @NotNull UUID traceId) {

  /**
   * The refine-directive payload. {@code targetDelta}'s shape depends on {@code kind} — e.g. {@code
   * {amountGbp: -2.0}} for {@link DirectiveKind#COST_DELTA}, {@code {nutrient: "protein_g", amount:
   * 10}} for {@link DirectiveKind#NUTRITION_DELTA}.
   */
  public record RefineDirectiveDto(DirectiveKind kind, String description, JsonNode targetDelta) {}
}
