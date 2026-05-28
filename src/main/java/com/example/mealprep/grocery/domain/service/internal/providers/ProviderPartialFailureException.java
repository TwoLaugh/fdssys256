package com.example.mealprep.grocery.domain.service.internal.providers;

import com.example.mealprep.grocery.domain.entity.AutomationFailureRecord;
import com.example.mealprep.grocery.domain.entity.OrderLineStatus;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * CHECKED exception raised by {@link GroceryProvider#placeOrder} when the basket was placed for
 * only a SUBSET of lines. Per lld/grocery.md line 672 this is checked so the compiler forces the
 * service to catch it; per lld/grocery.md lines 755 / 764 it is DELIBERATELY NOT an error — the
 * calling service catches it, drives the order to {@code PLACED_PARTIAL}, persists the added lines
 * + the {@code confirmLink}, and returns a 200 body ("fail forward").
 *
 * <p>Carries the partial outcome so the service can persist it without a second provider call:
 * {@code providerOrderId}, the {@code confirmLink}, the per-line statuses, and the failure log.
 */
public class ProviderPartialFailureException extends Exception {

  private final transient PlaceOrderResult partialResult;

  public ProviderPartialFailureException(String message, PlaceOrderResult partialResult) {
    super(message);
    this.partialResult = partialResult;
  }

  /** The partially-filled outcome (added lines, confirm link, failure log). */
  public PlaceOrderResult partialResult() {
    return partialResult;
  }

  /** Convenience: the per-line statuses from the partial result (empty when none). */
  public Map<UUID, OrderLineStatus> lineStatuses() {
    return partialResult == null ? Map.of() : partialResult.lineStatuses();
  }

  /** Convenience: the failure records from the partial result (empty when none). */
  public List<AutomationFailureRecord> failureLog() {
    return partialResult == null || partialResult.failureLog() == null
        ? List.of()
        : partialResult.failureLog();
  }
}
