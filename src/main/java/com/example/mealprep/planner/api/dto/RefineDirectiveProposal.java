package com.example.mealprep.planner.api.dto;

import java.util.UUID;

/**
 * Planner-local raw LLM output shape for a refine-directive. Per ticket planner-01h §"LLD
 * divergence — {@code RefineDirectiveDto} shape": this is the LLM's untyped output; {@link
 * RefineDirectiveDto} is the (planner-local placeholder for the) cross-module contract forwarded to
 * the adaptation pipeline by the composer (01j).
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
