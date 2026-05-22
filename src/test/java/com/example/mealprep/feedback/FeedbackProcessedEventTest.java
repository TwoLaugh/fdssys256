package com.example.mealprep.feedback;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.core.events.OriginAwareEvent;
import com.example.mealprep.core.origin.Origin;
import com.example.mealprep.feedback.event.FeedbackProcessedEvent;
import com.example.mealprep.feedback.spi.Destination;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * feedback-01g §194: {@link FeedbackProcessedEvent} implements {@link OriginAwareEvent} (core-02b),
 * and — because the originating feedback is user-driven — reports {@code origin() == USER} with a
 * null {@code originTrace()}. The AI attribution is set by the bridges on their downstream calls,
 * not on this event.
 */
class FeedbackProcessedEventTest {

  @Test
  void isOriginAware_andReportsUserOrigin() {
    FeedbackProcessedEvent event =
        new FeedbackProcessedEvent(
            UUID.randomUUID(),
            UUID.randomUUID(),
            Set.of(Destination.PREFERENCE),
            false,
            false,
            UUID.randomUUID(),
            Instant.parse("2026-05-22T10:00:00Z"));

    assertThat(event).isInstanceOf(OriginAwareEvent.class);
    assertThat(event.origin()).isEqualTo(Origin.USER);
    assertThat(event.originTrace()).isNull();
    // MealPrepEvent contract still satisfied via the record accessors.
    assertThat(event.traceId()).isNotNull();
    assertThat(event.occurredAt()).isNotNull();
  }
}
