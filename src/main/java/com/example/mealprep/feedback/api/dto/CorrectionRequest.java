package com.example.mealprep.feedback.api.dto;

import com.example.mealprep.feedback.spi.Destination;
import com.example.mealprep.feedback.validation.ValidDestination;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/v1/feedback/{feedbackId}/routes/{routingId}/correct}
 * (lld/feedback.md §Flow 4, ticket 01f §1). {@code newDestination} must be a recognised {@link
 * Destination} ({@code @ValidDestination}); the structural validity of the correction (e.g. RECIPE
 * needs a recipeId) is checked in the service layer as a 422 {@code
 * InvalidCorrectionTargetException}.
 *
 * <p>Field renamed from 01b's {@code note} placeholder to {@code userCorrectionNote} to match the
 * entity / mapper / OpenAPI schema.
 */
public record CorrectionRequest(
    @NotNull @ValidDestination Destination newDestination,
    @Size(max = 512) String userCorrectionNote) {}
