package com.example.mealprep.feedback.api.dto;

import com.example.mealprep.feedback.domain.entity.SubmissionStatus;
import java.util.List;
import java.util.UUID;

/**
 * Response from {@code POST /api/v1/feedback} (HTTP 202 Accepted). Shape per LLD lines 307-313
 * (lld/feedback.md).
 *
 * <p>In 01b classification has not run yet — {@code submissionStatus} is always {@code RECEIVED},
 * {@code routes} is empty, {@code pendingClarificationQueryId} is null. The {@code Location} header
 * points to {@code GET /api/v1/feedback/{feedbackId}} which the client polls for routing
 * progression as 01c/01d land.
 */
public record SubmitFeedbackResponse(
    UUID feedbackId,
    UUID traceId,
    SubmissionStatus submissionStatus,
    List<RoutingDecisionDto> routes,
    UUID pendingClarificationQueryId) {}
