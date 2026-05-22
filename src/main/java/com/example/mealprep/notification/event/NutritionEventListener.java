package com.example.mealprep.notification.event;

import com.example.mealprep.notification.domain.service.internal.NotificationDispatcherFacade;
import com.example.mealprep.nutrition.event.HealthDirectiveReceivedEvent;
import com.example.mealprep.nutrition.event.NutritionIntakeDivergedEvent;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Listens to nutrition-module events. Never throws — see {@link ProvisionEventListener}. */
@Component
public class NutritionEventListener {

  private static final Logger log = LoggerFactory.getLogger(NutritionEventListener.class);

  private final NotificationDispatcherFacade dispatcher;
  private final MeterRegistry meterRegistry;

  public NutritionEventListener(
      NotificationDispatcherFacade dispatcher, MeterRegistry meterRegistry) {
    this.dispatcher = dispatcher;
    this.meterRegistry = meterRegistry;
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onNutritionIntakeDiverged(NutritionIntakeDivergedEvent event) {
    try {
      dispatcher.dispatchNutritionIntakeDiverged(event);
    } catch (Exception e) {
      handleFailure("NUTRITION_INTAKE_DIVERGED", event, e);
    }
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onHealthDirectiveReceived(HealthDirectiveReceivedEvent event) {
    try {
      dispatcher.dispatchHealthDirectiveReceived(event);
    } catch (Exception e) {
      handleFailure("HEALTH_DIRECTIVE_RECEIVED", event, e);
    }
  }

  private void handleFailure(String kind, Object event, Exception e) {
    log.error("notification dispatch failed for event={}", event, e);
    meterRegistry.counter("notification.dispatch.failure", "kind", kind).increment();
  }
}
