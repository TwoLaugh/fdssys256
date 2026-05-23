package com.example.mealprep.planner.domain.service.internal;

import com.example.mealprep.core.types.SlotKind;
import java.time.LocalTime;

/**
 * The one home for the slot-kind default wall-clock meal times (planner-01m). This is the
 * last-resort floor of the {@code getUpcomingSlots} three-level coalesce: a slot with no stored
 * override and no matching lifestyle-config {@code meal_timing} entry resolves to its kind default.
 *
 * <p>These values preserve the pre-01m behaviour: they are the same table that lived in {@code
 * notification.scanner.PrepReminderScanner.defaultMealTime} before this ticket. The notification
 * sibling ticket {@code notification-01c} deletes the scanner's private copy and consumes the
 * already-resolved {@code UpcomingSlotView.mealTime} instead, so this constant has a single home.
 */
final class SlotKindDefaultTimes {

  private SlotKindDefaultTimes() {}

  /** Default wall-clock meal time for a slot kind. Total over all kinds; never returns null. */
  static LocalTime forKind(SlotKind kind) {
    return switch (kind) {
      case BREAKFAST -> LocalTime.of(8, 0);
      case LUNCH -> LocalTime.of(12, 30);
      case DINNER -> LocalTime.of(18, 0);
      case SNACK -> LocalTime.of(15, 0);
      case CUSTOM -> LocalTime.of(12, 0);
    };
  }
}
