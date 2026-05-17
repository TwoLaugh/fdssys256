package com.example.mealprep.feedback.event;

import com.example.mealprep.feedback.spi.Destination;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Published AFTER COMMIT when a user corrects a misclassified routing (lld/feedback.md §Events
 * lines 672-690, §Flow 4 step 9; ticket 01f §15). Lets the destinations / notification /
 * quality-monitoring modules react without polling the corrections table.
 *
 * <p>One event per correction. Published <em>inside</em> the correction transaction so
 * {@code @TransactionalEventListener(AFTER_COMMIT)} consumers actually receive it (events published
 * with no active tx are silently dropped — wave-3 retro).
 */
public record FeedbackMisclassificationCorrectedEvent(
    UUID feedbackId,
    UUID originalRoutingId,
    UUID replayRoutingId,
    Destination originalDestination,
    Destination correctedDestination,
    BigDecimal originalConfidence,
    UUID userId,
    UUID traceId,
    Instant occurredAt) {}
