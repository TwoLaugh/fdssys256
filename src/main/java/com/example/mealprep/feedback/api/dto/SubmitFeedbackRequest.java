package com.example.mealprep.feedback.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/v1/feedback}. Shape per LLD lines 236-239 (lld/feedback.md).
 *
 * <p>{@code text} is bounded to 4000 chars to keep the AI classification prompt within budget;
 * users sending more verbose feedback can submit multiple entries.
 *
 * <p>{@code @ValidUiContext} on {@link UiContextDto} cross-validates the context fields against
 * {@code screen}; see {@code com.example.mealprep.feedback.validation.UiContextValidator}.
 */
public record SubmitFeedbackRequest(
    @NotBlank @Size(max = 4000) String text, @NotNull @Valid UiContextDto context) {}
