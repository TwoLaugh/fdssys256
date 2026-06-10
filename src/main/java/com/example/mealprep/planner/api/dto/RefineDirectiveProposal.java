package com.example.mealprep.planner.api.dto;

import java.util.UUID;

/**
 * Planner-local raw LLM output shape for a refine-directive — the Phase-2 AI response carries these
 * verbatim. The composer (planner-01j) forwards each proposal to the adaptation pipeline by mapping
 * it onto the cross-module {@code PlanTimeRefineDirectiveRequest} (and its nested {@code
 * RefineDirectiveDto}) via {@code RefineDirectiveMapper}, then dispatching {@code
 * AdaptationService.runPlanTimeRefineJob(...)}. This proposal is the carrier element of {@code
 * AugmentationResult.emittedDirectives()}.
 *
 * <p>Field relevance depends on {@code type}:
 *
 * <ul>
 *   <li>{@code "SUBSTITUTE_INGREDIENT"} — {@code targetSlotId}, {@code fromIngredientKey}, {@code
 *       toIngredientKey}
 *   <li>{@code "REDUCE_TIME"} — {@code targetSlotId}, {@code currentTimeMin}, {@code targetTimeMin}
 * </ul>
 */
public record RefineDirectiveProposal(
    String type,
    UUID targetSlotId,
    String fromIngredientKey,
    String toIngredientKey,
    Integer currentTimeMin,
    Integer targetTimeMin,
    String reasoning) {}
