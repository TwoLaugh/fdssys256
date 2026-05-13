package com.example.mealprep.feedback.event;

import com.example.mealprep.feedback.spi.Destination;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Catalogued in 01b but <strong>not published</strong> here — submission stops at {@code RECEIVED}.
 * Per LLD §Events (lld/feedback.md lines 664-670) feedback-01c publishes from the zero-route /
 * clarification path, and feedback-01d publishes from the post-routing path. Shipping the record in
 * 01b keeps subsequent tickets free of new event-record additions on a clean dependency edge.
 */
public record FeedbackProcessedEvent(
    UUID feedbackId,
    UUID userId,
    Set<Destination> destinationsTouched,
    boolean partialFailure,
    boolean clarificationPending,
    UUID traceId,
    Instant occurredAt) {}
