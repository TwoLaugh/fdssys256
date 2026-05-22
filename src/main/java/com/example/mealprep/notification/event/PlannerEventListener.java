package com.example.mealprep.notification.event;

import com.example.mealprep.notification.domain.service.internal.NotificationDispatcherFacade;
import com.example.mealprep.planner.event.PlanGeneratedEvent;
import com.example.mealprep.planner.event.PrepReminderEvent;
import com.example.mealprep.planner.event.ReoptSuggestedEvent;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Listens to planner-module events. Never throws — see {@link ProvisionEventListener}.
 *
 * <p>Explicit bean name avoids a collision with the planner module's own {@code
 * planner.domain.service.internal.listeners.PlannerEventListener}, which would otherwise share the
 * default {@code plannerEventListener} bean name.
 */
@Component("notificationPlannerEventListener")
public class PlannerEventListener {

  private static final Logger log = LoggerFactory.getLogger(PlannerEventListener.class);

  private final NotificationDispatcherFacade dispatcher;
  private final MeterRegistry meterRegistry;

  public PlannerEventListener(
      NotificationDispatcherFacade dispatcher, MeterRegistry meterRegistry) {
    this.dispatcher = dispatcher;
    this.meterRegistry = meterRegistry;
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onPrepReminder(PrepReminderEvent event) {
    try {
      dispatcher.dispatchPrepReminder(event);
    } catch (Exception e) {
      handleFailure("PLANNER_PREP_REMINDER", event, e);
    }
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onReoptSuggested(ReoptSuggestedEvent event) {
    try {
      dispatcher.dispatchReoptSuggested(event);
    } catch (Exception e) {
      handleFailure("PLANNER_REOPT_SUGGESTED", event, e);
    }
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onPlanGenerated(PlanGeneratedEvent event) {
    try {
      dispatcher.dispatchPlanGenerated(event);
    } catch (Exception e) {
      handleFailure("PLANNER_PLAN_GENERATED", event, e);
    }
  }

  private void handleFailure(String kind, Object event, Exception e) {
    log.error("notification dispatch failed for event={}", event, e);
    meterRegistry.counter("notification.dispatch.failure", "kind", kind).increment();
  }
}
