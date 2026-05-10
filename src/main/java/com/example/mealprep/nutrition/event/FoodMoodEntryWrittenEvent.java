package com.example.mealprep.nutrition.event;

import com.example.mealprep.core.events.ScopeChangedEvent;
import com.example.mealprep.nutrition.api.dto.JournalAction;
import com.example.mealprep.nutrition.domain.entity.MealSlot;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Published {@code AFTER_COMMIT} on each food/mood journal write (CREATE / UPDATE / DELETE). No
 * listeners in 01c; the (future) Feedback System subscribes to refresh its classifier context.
 *
 * <p>{@code mealSlot} is null when the entry is untied (or for deletions of an untied entry).
 *
 * <p>{@code scopeKind = "nutrition-food-mood-journal"}, {@code scopeId = entryId}.
 */
public record FoodMoodEntryWrittenEvent(
    UUID entryId,
    UUID userId,
    LocalDate onDate,
    MealSlot mealSlot,
    JournalAction action,
    UUID traceId,
    Instant occurredAt)
    implements ScopeChangedEvent {

  @Override
  public String scopeKind() {
    return "nutrition-food-mood-journal";
  }

  @Override
  public UUID scopeId() {
    return entryId;
  }
}
