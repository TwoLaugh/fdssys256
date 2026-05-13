package com.example.mealprep.feedback.event;

import com.example.mealprep.feedback.api.dto.Screen;
import java.time.Instant;
import java.util.UUID;

/**
 * Published AFTER_COMMIT by {@code FeedbackServiceImpl.submitFeedback} per LLD §Flow 1
 * (lld/feedback.md lines 658-662). The async classification listener in feedback-01c will subscribe
 * via {@code @TransactionalEventListener(phase = AFTER_COMMIT)}; the Notification module also reads
 * {@code screen} for telemetry per LLD line 661.
 *
 * <p>{@code traceId} threads through the AI call log and downstream events so an operator can trace
 * one submission end-to-end across modules.
 */
public record FeedbackSubmittedEvent(
    UUID feedbackId, UUID userId, Screen screen, UUID traceId, Instant occurredAt) {}
