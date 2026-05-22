package com.example.mealprep.feedback.event;

import com.example.mealprep.core.events.OriginAwareEvent;
import com.example.mealprep.core.origin.Origin;
import com.example.mealprep.feedback.spi.Destination;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Catalogued in 01b but <strong>not published</strong> here — submission stops at {@code RECEIVED}.
 * Per LLD §Events (lld/feedback.md lines 664-670) feedback-01c publishes from the zero-route /
 * clarification path, and feedback-01d publishes from the post-routing path. Shipping the record in
 * 01b keeps subsequent tickets free of new event-record additions on a clean dependency edge.
 *
 * <p>Implements {@link OriginAwareEvent} (core-02b / feedback-01g §23): the originating feedback is
 * <b>user-driven</b>, so {@link #origin()} returns {@link Origin#USER} and {@link #originTrace()}
 * is null. The feedback bridges, when they call downstream destination services, set {@code
 * AI_FEEDBACK} attribution on those calls (see {@code feedback.bridge..}); the event itself records
 * the source side, which is the user submitting feedback.
 */
public record FeedbackProcessedEvent(
    UUID feedbackId,
    UUID userId,
    Set<Destination> destinationsTouched,
    boolean partialFailure,
    boolean clarificationPending,
    UUID traceId,
    Instant occurredAt)
    implements OriginAwareEvent {

  @Override
  public Origin origin() {
    return Origin.USER;
  }

  @Override
  public String originTrace() {
    return null;
  }
}
