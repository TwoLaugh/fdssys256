package com.example.mealprep.feedback.domain.service.internal;

import com.example.mealprep.feedback.api.dto.ClassificationOutput;
import com.example.mealprep.feedback.api.dto.UiContextDto;
import java.util.UUID;

/**
 * Carrier passed from {@link FeedbackRouterImpl#routeOne} to a {@link DestinationDispatcher}. Per
 * ticket 01d §2.
 *
 * <p>{@code uiContext} is nullable: only the RECIPE dispatcher cares about it (for the recipe-id
 * fallback path). {@code routingLogId} is allocated by the router before the dispatcher runs so
 * downstream tracing can correlate.
 */
public record DispatchContext(
    UUID feedbackId,
    UUID userId,
    UUID traceId,
    UUID routingLogId,
    UiContextDto uiContext,
    ClassificationOutput classification,
    int attemptNumber) {}
