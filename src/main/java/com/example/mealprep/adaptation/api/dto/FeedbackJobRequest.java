package com.example.mealprep.adaptation.api.dto;

import jakarta.annotation.Nullable;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Trigger-2 request — feedback module entry. Sync; the call enqueues and processes the job, then
 * returns the {@link AdaptationResultDto}. The feedback module's recipe-destination handler blocks
 * on this call so it can return the user a final state in one round trip.
 *
 * <p>Per LLD §DTOs lines 319-327; verbatim from {@code lld/adaptation-pipeline.md}.
 */
public record FeedbackJobRequest(
    @NotNull UUID recipeId,
    @NotNull UUID userId,
    @NotNull UUID feedbackId,
    @NotNull String feedbackText,
    @NotNull RatingDeltaDto ratingDelta,
    @NotNull UUID traceId,
    @Nullable UUID parentDecisionId) {

  /**
   * Rating dimension drops — the structured signal that biases {@code CandidateGenerator}. Each
   * field is the post-feedback delta on that dimension; null means "no opinion on this dimension".
   */
  public record RatingDeltaDto(
      BigDecimal taste, BigDecimal effortWorthIt, BigDecimal portionFit, BigDecimal repeatValue) {}
}
