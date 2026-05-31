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
 *
 * <p><b>{@code deliverySlotSecured} (grocery-4).</b> Whether the provider locked in a delivery slot
 * during placement. When {@code true} the service auto-advances the placed order to {@code
 * AWAITING_USER_CONFIRMATION} (the user then confirms in the provider UI). When {@code false} the
 * order PAUSES at {@code PLACED} — the LLD's "delivery slot fails → order pauses at PLACED; user
 * picks slot manually in the provider UI" failure mode (lld/grocery.md line 922). The user later
 * advances it (e.g. via a refresh once a slot is chosen). A {@code PLACED_PARTIAL} outcome always
 * auto-advances regardless of this flag — its pause-for-manual-completion semantic is separate.
 */
public record PlaceOrderResult(
    String providerOrderId,
    String confirmLink,
    Map<UUID, OrderLineStatus> lineStatuses,
    boolean partial,
    boolean deliverySlotSecured,
    List<AutomationFailureRecord> failureLog,
    Instant placedAt) {}
