package com.example.mealprep.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.example.mealprep.feedback.event.FeedbackProcessedEvent;
import com.example.mealprep.feedback.spi.Destination;
import com.example.mealprep.notification.domain.service.internal.NotificationDispatcherFacade;
import com.example.mealprep.notification.event.FeedbackEventListener;
import com.example.mealprep.notification.event.NutritionEventListener;
import com.example.mealprep.notification.event.PlannerEventListener;
import com.example.mealprep.notification.event.ProvisionEventListener;
import com.example.mealprep.nutrition.event.HealthDirectiveReceivedEvent;
import com.example.mealprep.planner.event.PlanGeneratedEvent;
import com.example.mealprep.provisions.event.ItemSpoiledEvent;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EventListenerExceptionIsolationTest {

  @Mock private NotificationDispatcherFacade dispatcher;
  private final MeterRegistry meterRegistry = new SimpleMeterRegistry();

  @Test
  void provisionListener_callsDispatcher() {
    var listener = new ProvisionEventListener(dispatcher, meterRegistry);
    var event =
        new ItemSpoiledEvent(
            UUID.randomUUID(), List.of(UUID.randomUUID()), "x", UUID.randomUUID(), Instant.now());

    listener.onItemSpoiled(event);

    verify(dispatcher).dispatchItemSpoiled(event);
  }

  @Test
  void provisionListener_swallowsDispatchException_andCountsMetric() {
    var listener = new ProvisionEventListener(dispatcher, meterRegistry);
    var event =
        new ItemSpoiledEvent(
            UUID.randomUUID(), List.of(UUID.randomUUID()), "x", UUID.randomUUID(), Instant.now());
    doThrow(new RuntimeException("boom")).when(dispatcher).dispatchItemSpoiled(any());

    assertThatCode(() -> listener.onItemSpoiled(event)).doesNotThrowAnyException();
    assertThat(
            meterRegistry
                .counter("notification.dispatch.failure", "kind", "PROVISION_ITEM_SPOILED")
                .count())
        .isEqualTo(1.0);
  }

  @Test
  void nutritionListener_swallowsDispatchException() {
    var listener = new NutritionEventListener(dispatcher, meterRegistry);
    var event =
        new HealthDirectiveReceivedEvent(
            UUID.randomUUID(),
            UUID.randomUUID(),
            com.example.mealprep.nutrition.api.dto.DirectiveType.values()[0],
            "p",
            Instant.now(),
            UUID.randomUUID(),
            Instant.now());
    doThrow(new RuntimeException("boom")).when(dispatcher).dispatchHealthDirectiveReceived(any());

    assertThatCode(() -> listener.onHealthDirectiveReceived(event)).doesNotThrowAnyException();
  }

  @Test
  void plannerListener_swallowsDispatchException() {
    var listener = new PlannerEventListener(dispatcher, meterRegistry);
    var event =
        new PlanGeneratedEvent(
            UUID.randomUUID(),
            UUID.randomUUID(),
            LocalDate.now(),
            1,
            null,
            UUID.randomUUID(),
            UUID.randomUUID(),
            false,
            false,
            false,
            UUID.randomUUID(),
            Instant.now());
    doThrow(new RuntimeException("boom")).when(dispatcher).dispatchPlanGenerated(any());

    assertThatCode(() -> listener.onPlanGenerated(event)).doesNotThrowAnyException();
  }

  // ---------------- NOTIF-16 feedback-confirmation: fire-gate + never-throw ----------------

  private static FeedbackProcessedEvent feedbackEvent(
      Set<Destination> touched,
      Set<Destination> applied,
      boolean partialFailure,
      boolean clarificationPending) {
    return new FeedbackProcessedEvent(
        UUID.randomUUID(),
        UUID.randomUUID(),
        touched,
        applied,
        partialFailure,
        clarificationPending,
        UUID.randomUUID(),
        Instant.now());
  }

  @Test
  void feedbackListener_appliedChange_dispatches() {
    var listener = new FeedbackEventListener(dispatcher, meterRegistry);
    var event =
        feedbackEvent(Set.of(Destination.PROVISIONS), Set.of(Destination.PROVISIONS), false, false);

    listener.onFeedbackProcessed(event);

    verify(dispatcher).dispatchFeedbackProcessed(event);
  }

  @Test
  void feedbackListener_partialSuccess_dispatches() {
    var listener = new FeedbackEventListener(dispatcher, meterRegistry);
    // Both attempted, only PROVISIONS applied → still fires.
    var event =
        feedbackEvent(
            Set.of(Destination.PROVISIONS, Destination.NUTRITION),
            Set.of(Destination.PROVISIONS),
            true,
            false);

    listener.onFeedbackProcessed(event);

    verify(dispatcher).dispatchFeedbackProcessed(event);
  }

  @Test
  void feedbackListener_nonActionable_doesNotDispatch() {
    var listener = new FeedbackEventListener(dispatcher, meterRegistry);

    listener.onFeedbackProcessed(feedbackEvent(Set.of(), Set.of(), false, false));

    verify(dispatcher, never()).dispatchFeedbackProcessed(any());
  }

  @Test
  void feedbackListener_clarificationPending_doesNotDispatch() {
    var listener = new FeedbackEventListener(dispatcher, meterRegistry);

    listener.onFeedbackProcessed(feedbackEvent(Set.of(), Set.of(), false, true));

    verify(dispatcher, never()).dispatchFeedbackProcessed(any());
  }

  @Test
  void feedbackListener_totalFailure_doesNotDispatch() {
    var listener = new FeedbackEventListener(dispatcher, meterRegistry);

    // Pre-route terminal failure publishes an empty touched set with partialFailure=true.
    listener.onFeedbackProcessed(feedbackEvent(Set.of(), Set.of(), true, false));

    verify(dispatcher, never()).dispatchFeedbackProcessed(any());
  }

  @Test
  void feedbackListener_allRoutesFailed_touchedNonEmptyButNothingApplied_doesNotDispatch() {
    var listener = new FeedbackEventListener(dispatcher, meterRegistry);

    // The bug this fix closes: destinations were ATTEMPTED (touched non-empty) but every route
    // FAILED, so applied is EMPTY → must NOT dispatch.
    listener.onFeedbackProcessed(
        feedbackEvent(
            Set.of(Destination.PROVISIONS, Destination.NUTRITION), Set.of(), true, false));

    verify(dispatcher, never()).dispatchFeedbackProcessed(any());
  }

  @Test
  void feedbackListener_swallowsDispatchException_andCountsMetric() {
    var listener = new FeedbackEventListener(dispatcher, meterRegistry);
    var event =
        feedbackEvent(Set.of(Destination.PROVISIONS), Set.of(Destination.PROVISIONS), false, false);
    doThrow(new RuntimeException("boom")).when(dispatcher).dispatchFeedbackProcessed(any());

    assertThatCode(() -> listener.onFeedbackProcessed(event)).doesNotThrowAnyException();
    assertThat(
            meterRegistry
                .counter("notification.dispatch.failure", "kind", "FEEDBACK_CONFIRMATION")
                .count())
        .isEqualTo(1.0);
  }
}
