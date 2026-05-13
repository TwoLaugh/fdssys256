package com.example.mealprep.feedback.api.dto;

import com.example.mealprep.feedback.spi.Destination;
import jakarta.validation.constraints.Size;

/**
 * Request body for the future {@code POST /api/v1/feedback/clarifications/{queryId}/answer}
 * endpoint. Declared in 01b so {@link
 * com.example.mealprep.feedback.domain.service.FeedbackUpdateService#answerClarificationQuery}
 * compiles; the endpoint + full {@code @AssertTrue} cross-field rule (at least one of {@code
 * selectedDestination} / {@code clarificationText} must be present) lands in feedback-01e.
 */
public record AnswerClarificationRequest(
    Destination selectedDestination, @Size(max = 4000) String clarificationText) {}
