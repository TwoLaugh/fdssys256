package com.example.mealprep.feedback.ai.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One PREFERENCE-routed feedback event handed to {@code PreferenceTasteProfileDeltaTask} as part of
 * the batch ({@code lld/prompts/01-taste-profile-delta.md:43-49}). The orchestrator builds these
 * from the feedback module's {@code FeedbackEntry} + its PREFERENCE {@code RoutingLogEntry}.
 *
 * @param feedbackId the originating feedback entry id.
 * @param userText verbatim free-text the user submitted (or the classifier's extracted snippet).
 * @param contextSummary short UI-context description (e.g. "feedback on Wednesday's chicken stir
 *     -fry, eaten 2 days ago").
 * @param classifierConfidence the classifier's confidence that this feedback is preference
 *     -flavoured.
 * @param occurredAt when the feedback was submitted.
 */
public record ClassifiedFeedbackEvent(
    UUID feedbackId,
    String userText,
    String contextSummary,
    BigDecimal classifierConfidence,
    Instant occurredAt) {}
