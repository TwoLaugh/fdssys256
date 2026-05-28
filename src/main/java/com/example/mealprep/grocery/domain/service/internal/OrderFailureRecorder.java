package com.example.mealprep.grocery.domain.service.internal;

import com.example.mealprep.grocery.domain.entity.GroceryOrder;
import com.example.mealprep.grocery.domain.entity.GroceryOrderStatus;
import com.example.mealprep.grocery.event.GroceryProviderUnavailableEvent;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists order-failure state in a SEPARATE committed transaction (grocery-01e). Lives on its own
 * bean so the {@code REQUIRES_NEW} propagation actually fires (Spring's proxy is crossed). The
 * lifecycle service calls these from inside its locked {@code quote}/{@code place} transaction and
 * THEN throws the HTTP-mapped 503 exception — the main transaction rolls back (it has nothing left
 * to persist) and the single-flight advisory lock releases cleanly.
 *
 * <p>The writes go through IMMEDIATE bulk updates (not managed-entity {@code save}) because, under
 * OSIV, a managed entity loaded in the {@code REQUIRES_NEW} tx shares the request-bound Hibernate
 * session with the outer tx, so its dirty state would be flushed/rolled-back on the OUTER tx's
 * rollback — losing the failure-forward state. A JPQL bulk update executes against the {@code
 * REQUIRES_NEW} connection immediately and is not subject to the outer rollback (LLD Flow 4: the
 * failure state must STICK). The diagnostic {@code automation_failure_log} JSONB append is omitted
 * on this path (it is best-effort diagnostics; the status + reason are the load-bearing contract).
 */
@Component
class OrderFailureRecorder {

  private final GroceryOrderDataGateway dataGateway;
  private final ApplicationEventPublisher eventPublisher;
  private final Clock clock;

  OrderFailureRecorder(
      GroceryOrderDataGateway dataGateway, ApplicationEventPublisher eventPublisher, Clock clock) {
    this.dataGateway = dataGateway;
    this.eventPublisher = eventPublisher;
    this.clock = clock;
  }

  /**
   * Move the order to {@code PROVIDER_UNAVAILABLE} (failure-forward STATE CAPTURE — GROC-27), bump
   * the provider-state failure counter, and publish {@link GroceryProviderUnavailableEvent}.
   * Committed in its own transaction.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  void recordProviderUnavailable(UUID orderId, String reason, String message) {
    GroceryOrder order = dataGateway.findOrderById(orderId).orElse(null);
    if (order == null) {
      return;
    }
    Instant now = clock.instant();
    // PROVIDER_UNAVAILABLE is a failure-forward capture for quote/place (the legal-edges table
    // lists
    // only the placed→unavailable happy edge; the quote-from-DRAFT failure also lands here per the
    // failure matrix, LLD lines 917-924). Don't clobber an already-progressed/terminal order.
    if (order.getStatus() != GroceryOrderStatus.CONFIRMED
        && order.getStatus() != GroceryOrderStatus.DELIVERED
        && order.getStatus() != GroceryOrderStatus.RECONCILED
        && order.getStatus() != GroceryOrderStatus.ARCHIVED
        && order.getStatus() != GroceryOrderStatus.CANCELLED) {
      dataGateway.updateOrderStatusAndReason(
          orderId, GroceryOrderStatus.PROVIDER_UNAVAILABLE, "Provider unavailable: " + reason, now);
    }
    dataGateway.bumpProviderFailure(order.getUserId(), order.getProviderKey(), reason, now);

    eventPublisher.publishEvent(
        new GroceryProviderUnavailableEvent(
            order.getUserId(),
            order.getId(),
            order.getProviderKey(),
            reason,
            order.getTraceId(),
            now));
  }

  /**
   * Revert the order to {@code DRAFT} with the AI cost-cap fallback reason. Committed in its own
   * transaction. (From {@code DRAFT}/{@code QUOTED} the target is always {@code DRAFT}; later
   * states never reach this path because the AI navigator only runs during quote/place.)
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  void recordAiUnavailableRevert(UUID orderId, String message) {
    GroceryOrder order = dataGateway.findOrderById(orderId).orElse(null);
    if (order == null) {
      return;
    }
    Instant now = clock.instant();
    dataGateway.updateOrderStatusAndReason(
        orderId, GroceryOrderStatus.DRAFT, "AI cost cap reached", now);
  }
}
