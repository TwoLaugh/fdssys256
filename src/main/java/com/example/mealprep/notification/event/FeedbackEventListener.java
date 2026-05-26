package com.example.mealprep.notification.event;

import com.example.mealprep.feedback.event.FeedbackProcessedEvent;
import com.example.mealprep.notification.domain.service.internal.NotificationDispatcherFacade;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Listens to the feedback module's {@link FeedbackProcessedEvent} and, for a positive outcome,
 * dispatches the NOTIF-16 feedback-confirmation notification {@code AFTER_COMMIT}. Never throws — a
 * dispatch failure is logged and metric-counted so the already-committed publisher transaction is
 * never affected (mirrors {@link ProvisionEventListener} / {@link NutritionEventListener}; the
 * sibling listeners are synchronous, so this one is too — no {@code @Async}).
 *
 * <p><b>Fire-condition gate (NOTIF-16, locked design).</b> Fire iff the feedback resulted in ≥1
 * destination actually applying a change. The gate reads the event's {@code appliedDestinations}
 * (the succeeded subset) and {@code clarificationPending} fields:
 *
 * <pre>{@code event.appliedDestinations() != null && !event.appliedDestinations().isEmpty()
 *     && !event.clarificationPending()}</pre>
 *
 * Reading {@code appliedDestinations} (NOT {@code destinationsTouched}) is the correctness point:
 * {@code destinationsTouched} is every destination the router <b>attempted</b> regardless of
 * outcome, so a routed-but-all-FAILED feedback has a non-empty {@code destinationsTouched} yet
 * applied nothing. Gating on the succeeded set fires the routed apply paths (all-success and
 * partial-success — something applied) and skips the negative outcomes, each of which carries an
 * EMPTY {@code appliedDestinations} (or sets {@code clarificationPending}):
 *
 * <ul>
 *   <li><b>non-actionable / empty</b> ({@code markRoutedEmpty}): {@code appliedDestinations=∅} →
 *       skip;
 *   <li><b>clarification-pending</b> ({@code queueClarification}): {@code
 *       clarificationPending=true} (and {@code appliedDestinations=∅}) → skip;
 *   <li><b>total failure</b> ({@code markFailed} / {@code StuckClassificationRetrier} pre-route
 *       terminal paths, AND a routed feedback whose every bridge FAILED): {@code
 *       appliedDestinations=∅} → skip.
 * </ul>
 *
 * (HLD-GAP G15 — per-destination failure detail on partial success — is deferred; v1 fires plainly
 * when anything applied and the payload lists exactly the succeeded destinations.) The gate lives
 * HERE in the listener, not the resolver, so the resolver maps unconditionally and the dispatcher
 * never even runs for a non-firing outcome.
 */
@Component("notificationFeedbackEventListener")
public class FeedbackEventListener {

  private static final Logger log = LoggerFactory.getLogger(FeedbackEventListener.class);

  private final NotificationDispatcherFacade dispatcher;
  private final MeterRegistry meterRegistry;

  public FeedbackEventListener(
      NotificationDispatcherFacade dispatcher, MeterRegistry meterRegistry) {
    this.dispatcher = dispatcher;
    this.meterRegistry = meterRegistry;
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onFeedbackProcessed(FeedbackProcessedEvent event) {
    if (!appliedAtLeastOneDestination(event)) {
      // Non-actionable / clarification-pending / total-failure — NOTIF-16 is positive-outcome only.
      return;
    }
    try {
      dispatcher.dispatchFeedbackProcessed(event);
    } catch (Exception e) {
      handleFailure(event, e);
    }
  }

  /** The NOTIF-16 fire condition — see the class javadoc. */
  private static boolean appliedAtLeastOneDestination(FeedbackProcessedEvent event) {
    return event.appliedDestinations() != null
        && !event.appliedDestinations().isEmpty()
        && !event.clarificationPending();
  }

  private void handleFailure(FeedbackProcessedEvent event, Exception e) {
    log.error("notification dispatch failed for event={}", event, e);
    meterRegistry
        .counter("notification.dispatch.failure", "kind", "FEEDBACK_CONFIRMATION")
        .increment();
  }
}
