package com.example.mealprep.planner.domain.service.internal;

import com.example.mealprep.core.types.SlotKind;
import com.example.mealprep.planner.domain.entity.MealSlot;
import com.example.mealprep.planner.domain.entity.MealTimeSource;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The single home for a meal slot's wall-clock-time resolution (planner-01m). Both the {@code
 * getUpcomingSlots} read projection (for the notification {@code PrepReminderScanner}) and the
 * {@code MealSlotDto} mapper (for the Plan grid / Today timeline — frontend-gaps:
 * planner-effective-meal-time) resolve through here, so the three-level coalesce has exactly one
 * implementation.
 *
 * <p>The coalesce, never null:
 *
 * <ol>
 *   <li>the slot's stored {@code meal_time} override, if set → {@link
 *       MealTimeSource#SLOT_OVERRIDE};
 *   <li>else the household owner's lifestyle-config {@code meal_timing} entry for the slot kind
 *       (the start of its time range, e.g. {@code "18:30-19:30"} → {@code 18:30}) → {@link
 *       MealTimeSource#LIFESTYLE_SCHEDULE};
 *   <li>else the slot-kind default floor (see {@link SlotKindDefaultTimes}) → {@link
 *       MealTimeSource#KIND_DEFAULT}, preserving the pre-01m behaviour so households with no
 *       lifestyle config (e.g. pre-onboarding) see a sensible time and never a 500.
 * </ol>
 *
 * <p>The caller supplies the lifestyle-config {@code meal_timing} map (kind key → range/time
 * string), loaded <b>once per call</b> so the cross-module preference read cost is independent of
 * the slot count — never per slot. An empty/{@code null} map skips level 2 (the pre-onboarding
 * case).
 */
public final class MealTimeResolver {

  private static final Logger log = LoggerFactory.getLogger(MealTimeResolver.class);

  private MealTimeResolver() {}

  /** Resolved wall-clock time plus the coalesce level that produced it. */
  public record Resolved(LocalTime time, MealTimeSource source) {}

  /**
   * Resolve a slot's effective wall-clock meal time and the source level, applying the three-level
   * coalesce. Never returns null.
   */
  public static Resolved resolve(MealSlot slot, Map<String, String> mealTimingMap) {
    if (slot.getMealTime() != null) {
      return new Resolved(slot.getMealTime(), MealTimeSource.SLOT_OVERRIDE);
    }
    SlotKind kind = slot.getKind();
    if (mealTimingMap != null) {
      LocalTime fromConfig = parseRangeStart(mealTimingMap.get(kindKey(kind)));
      if (fromConfig != null) {
        return new Resolved(fromConfig, MealTimeSource.LIFESTYLE_SCHEDULE);
      }
    }
    return new Resolved(SlotKindDefaultTimes.forKind(kind), MealTimeSource.KIND_DEFAULT);
  }

  /**
   * The resolved time only (the {@code getUpcomingSlots} path, which does not surface the source).
   */
  public static LocalTime resolveTime(MealSlot slot, Map<String, String> mealTimingMap) {
    return resolve(slot, mealTimingMap).time();
  }

  /**
   * The lifestyle-config map key for a slot kind — the lower-cased enum name ({@code BREAKFAST} →
   * {@code "breakfast"}). Keys are canonical-cased by convention per {@code
   * LifestyleConfigDocument}.
   */
  public static String kindKey(SlotKind kind) {
    return kind.name().toLowerCase(Locale.ROOT);
  }

  /**
   * Parse the start of a meal-timing value: a range like {@code "18:30-19:30"} yields {@code
   * 18:30}; a bare {@code "19:00"} yields {@code 19:00}. Returns null for a null/blank/malformed
   * value (the caller then falls through to the slot-kind default); a malformed value is logged at
   * DEBUG and never throws.
   */
  public static LocalTime parseRangeStart(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    String start = value.trim();
    int dash = start.indexOf('-');
    if (dash >= 0) {
      start = start.substring(0, dash).trim();
    }
    try {
      return LocalTime.parse(start);
    } catch (DateTimeParseException e) {
      log.debug("Unparseable meal-timing value '{}'; falling back to slot-kind default", value);
      return null;
    }
  }
}
