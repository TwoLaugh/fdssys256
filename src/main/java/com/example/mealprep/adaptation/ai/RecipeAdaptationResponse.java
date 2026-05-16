package com.example.mealprep.adaptation.ai;

import com.example.mealprep.adaptation.api.dto.PlannerHintDto;
import com.example.mealprep.adaptation.domain.enums.AdaptationClassification;
import com.example.mealprep.recipe.api.dto.RecipeDiffDto;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.Nullable;
import java.math.BigDecimal;
import java.util.List;

/**
 * Structured-output payload returned by the Stage-C AI dispatch. Verbatim from {@code
 * lld/adaptation-pipeline.md} §RecipeAdaptationTask lines 857-865.
 *
 * <p>{@code chosenCandidateIndex == -1} signals {@code NO_CHANGE} — either infeasibility or the LLM
 * declined to recommend any candidate. {@code refinedDiff} is optional: when populated, the
 * orchestrator uses it as the final diff instead of the chosen candidate's pre-vetted diff
 * (allowing the LLM to refine an ingredient quantity, etc.). The trace persists either the
 * refinedDiff or — on auto-skip — the chosen candidate's diff.
 *
 * <p>{@code RecipeDiffDto} lives in the recipe module (recipe-01c manual-edit-and-diff).
 *
 * <p>01c ships the record here so the validation gates can be unit-tested in isolation. 01e will
 * ship the {@code AiTask<RecipeAdaptationResponse>} (i.e. {@code RecipeAdaptationTask}) that
 * actually constructs values of this shape from the model output.
 */
public record RecipeAdaptationResponse(
    int chosenCandidateIndex,
    AdaptationClassification classification,
    String reasoning,
    String nutritionalNotes,
    BigDecimal confidence,
    BigDecimal characterPreservationScore,
    @Nullable RecipeDiffDto refinedDiff,
    @Nullable JsonNode finalDiffJson,
    List<PlannerHintDto> plannerHints) {}
