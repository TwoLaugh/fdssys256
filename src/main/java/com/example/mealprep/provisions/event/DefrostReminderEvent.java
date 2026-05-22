package com.example.mealprep.provisions.event;

import com.example.mealprep.core.events.ScopeChangedEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * Published {@code AFTER_COMMIT} when a defrost-reminder trigger fires for a frozen inventory item
 * that backs an upcoming planned meal slot (the scanner ships in a sibling ticket; this record is
 * the minimal contract). Consumed by the notification module ({@code
 * ProvisionEventListener.onDefrostReminder}) to raise a {@code PROVISION_DEFROST_REMINDER}
 * notification, bundled per {@code mealSlotId}.
 *
 * <p>{@code scopeKind = "inventory-item"}, {@code scopeId = inventoryItemId}.
 */
public record DefrostReminderEvent(
    UUID userId,
    UUID householdId,
    UUID inventoryItemId,
    UUID mealSlotId,
    Instant defrostBy,
    UUID traceId,
    Instant occurredAt)
    implements ScopeChangedEvent {

  @Override
  public String scopeKind() {
    return "inventory-item";
  }

  @Override
  public UUID scopeId() {
    return inventoryItemId;
  }
}
