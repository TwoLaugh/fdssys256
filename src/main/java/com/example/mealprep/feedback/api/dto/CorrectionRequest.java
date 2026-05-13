package com.example.mealprep.feedback.api.dto;

import com.example.mealprep.feedback.spi.Destination;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request body for the future {@code POST /api/v1/feedback/{feedbackId}/corrections/{routingId}}
 * endpoint. The shape is declared in 01b so {@link
 * com.example.mealprep.feedback.domain.service.FeedbackUpdateService#correctMisclassification}
 * compiles; the endpoint + full validation (including the {@code @ValidDestination} class-level
 * validator referenced by LLD §Validation) land in feedback-01f.
 *
 * <p>01b ships a plain {@code @NotNull} on {@code newDestination} — the full validator is deferred
 * per the ticket's "placeholder validator" note.
 */
public record CorrectionRequest(
    @NotNull Destination newDestination, @Size(max = 512) String note) {}
