package com.example.mealprep.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import com.example.mealprep.notification.domain.service.internal.NotificationDispatcherFacade;
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
}
