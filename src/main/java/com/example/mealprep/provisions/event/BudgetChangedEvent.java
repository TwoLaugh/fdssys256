package com.example.mealprep.provisions.event;

import com.example.mealprep.core.events.ScopeChangedEvent;
import com.example.mealprep.provisions.api.dto.PriceSensitivity;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Published {@code AFTER_COMMIT} when a budget row is upserted with a genuine field change. {@code
 * previousWeeklyTarget} is {@code null} on the insert path (no prior row) and the previous numeric
 * value on the update path. The LLD declared this field non-null; 01c relaxes to nullable to
 * accommodate the collapsed PUT-as-upsert flow.
 *
 * <p>{@code scopeKind = "budget"}, {@code scopeId} hashes the {@code userId} via deterministic UUID
 * — stable across publications for the same user.
 */
public record BudgetChangedEvent(
    UUID userId,
    BigDecimal previousWeeklyTarget,
    BigDecimal newWeeklyTarget,
    PriceSensitivity newPriceSensitivity,
    UUID traceId,
    Instant occurredAt)
    implements ScopeChangedEvent {

  @Override
  public String scopeKind() {
    return "budget";
  }

  @Override
  public UUID scopeId() {
    return UUID.nameUUIDFromBytes(("budget:" + userId).getBytes());
  }
}
