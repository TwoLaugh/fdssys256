package com.example.mealprep.adaptation.ai;

import com.example.mealprep.adaptation.api.dto.AdaptationCandidateDto;
import com.example.mealprep.adaptation.api.dto.FeedbackJobRequest;
import com.example.mealprep.adaptation.api.dto.NutritionalKnowledgeBundleDto;
import com.example.mealprep.adaptation.api.dto.PlanTimeRefineDirectiveRequest;
import com.example.mealprep.recipe.api.dto.CharacterFingerprintDto;
import com.example.mealprep.recipe.api.dto.RecipeDto;
import com.example.mealprep.recipe.api.dto.RecipeVersionDto;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.Nullable;
import java.util.List;

/**
 * Typed context bundle passed from the worker pipeline's Stage-2 loader into Stages A/B/C. Carries
 * the loaded recipe + version body, character fingerprint, preferences hash, nutrition targets,
 * food-science bundle, and source-specific payload (feedback text + rating delta, plan-time
 * directive, data-model-change summary).
 *
 * <p>Per ticket 01c §AdaptationContext shape (LLD §RecipeAdaptationTask lines 829-843). Records the
 * {@code mode} as a free string (matching the {@code AiTask.getContext()} key set used in 01e) so
 * the 4 trigger sources stay legible at the dispatch boundary.
 *
 * <p>{@code candidates} is appended after Stage A/B; the loader-built context starts with an empty
 * list, which the orchestrator replaces via {@link #withCandidates(List)}.
 *
 * <p>Cross-module DTOs reused here: {@link RecipeDto}, {@link RecipeVersionDto}, {@link
 * CharacterFingerprintDto} from recipe; {@link NutritionalKnowledgeBundleDto} from the adaptation
 * food-science seam. {@code softPreferencesHash} and {@code nutritionTargetsSummary} are
 * placeholder string keys — peer module DTOs ({@code SoftPreferenceSummary}, {@code
 * NutritionTargetsSummary}) are not yet available; 01e refines as needed when those land.
 */
public record AdaptationContext(
    String mode,
    RecipeDto recipe,
    RecipeVersionDto currentVersion,
    @Nullable CharacterFingerprintDto fingerprint,
    List<AdaptationCandidateDto> candidates,
    @Nullable String softPreferencesHash,
    String hardConstraintsHash,
    @Nullable String nutritionTargetsSummary,
    NutritionalKnowledgeBundleDto knowledgeBundle,
    @Nullable String feedbackText,
    @Nullable FeedbackJobRequest.RatingDeltaDto ratingDelta,
    @Nullable PlanTimeRefineDirectiveRequest.RefineDirectiveDto directive,
    @Nullable JsonNode dataModelChange) {

  /** Returns a copy of this context with the supplied candidates replacing the current list. */
  public AdaptationContext withCandidates(List<AdaptationCandidateDto> newCandidates) {
    return new AdaptationContext(
        mode,
        recipe,
        currentVersion,
        fingerprint,
        List.copyOf(newCandidates),
        softPreferencesHash,
        hardConstraintsHash,
        nutritionTargetsSummary,
        knowledgeBundle,
        feedbackText,
        ratingDelta,
        directive,
        dataModelChange);
  }
}
