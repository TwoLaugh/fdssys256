package com.example.mealprep.notification.event;

import com.example.mealprep.notification.domain.service.internal.NotificationDispatcherFacade;
import com.example.mealprep.provisions.event.DefrostReminderEvent;
import com.example.mealprep.provisions.event.ItemNearingExpiryEvent;
import com.example.mealprep.provisions.event.ItemSpoiledEvent;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Listens to provisions-module events and dispatches the corresponding notifications {@code
 * AFTER_COMMIT}. Each handler never throws: a dispatch failure is logged and metric-counted so the
 * already-committed publisher transaction is never affected.
 */
@Component("notificationProvisionEventListener")
public class ProvisionEventListener {

  private static final Logger log = LoggerFactory.getLogger(ProvisionEventListener.class);

  private final NotificationDispatcherFacade dispatcher;
  private final MeterRegistry meterRegistry;

  public ProvisionEventListener(
      NotificationDispatcherFacade dispatcher, MeterRegistry meterRegistry) {
    this.dispatcher = dispatcher;
    this.meterRegistry = meterRegistry;
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onItemNearingExpiry(ItemNearingExpiryEvent event) {
    try {
      dispatcher.dispatchItemNearingExpiry(event);
    } catch (Exception e) {
      handleFailure("PROVISION_ITEM_NEAR_EXPIRY", event, e);
    }
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onItemSpoiled(ItemSpoiledEvent event) {
    try {
      dispatcher.dispatchItemSpoiled(event);
    } catch (Exception e) {
      handleFailure("PROVISION_ITEM_SPOILED", event, e);
    }
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onDefrostReminder(DefrostReminderEvent event) {
    try {
      dispatcher.dispatchDefrostReminder(event);
    } catch (Exception e) {
      handleFailure("PROVISION_DEFROST_REMINDER", event, e);
    }
  }

  private void handleFailure(String kind, Object event, Exception e) {
    log.error("notification dispatch failed for event={}", event, e);
    meterRegistry.counter("notification.dispatch.failure", "kind", kind).increment();
  }
}
