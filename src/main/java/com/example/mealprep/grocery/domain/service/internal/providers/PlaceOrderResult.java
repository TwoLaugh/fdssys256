package com.example.mealprep.grocery.domain.service.internal.providers;

import com.example.mealprep.grocery.domain.entity.AutomationFailureRecord;
import com.example.mealprep.grocery.domain.entity.OrderLineStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Result of {@link GroceryProvider#placeOrder}. Per lld/grocery.md line 664. {@code lineStatuses}
 * is keyed by {@code groceryOrderLineId}; {@code partial} is {@code true} when only a subset was
 * added (a successful-but-flagged outcome). {@code confirmLink} is the URL the user clicks to
 * confirm in the provider's own UI — automation never auto-confirms. Reuses the persisted {@link
 * AutomationFailureRecord} entity shape for the failure log so the service can copy it straight
 * onto the order.
 */
public record PlaceOrderResult(
    String providerOrderId,
    String confirmLink,
    Map<UUID, OrderLineStatus> lineStatuses,
    boolean partial,
    List<AutomationFailureRecord> failureLog,
    Instant placedAt) {}
