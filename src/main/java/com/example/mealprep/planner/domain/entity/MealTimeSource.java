package com.example.mealprep.planner.domain.entity;

/**
 * Which level of the three-level meal-time coalesce produced a slot's resolved {@code
 * effectiveMealTime} (planner — frontend-gaps: planner-effective-meal-time). Surfaced on {@link
 * com.example.mealprep.planner.api.dto.MealSlotDto} so the UI can caption a serve time as a user
 * override versus a default ("default time") without re-reading the preference module.
 *
 * <ul>
 *   <li>{@code SLOT_OVERRIDE} — the slot's stored {@code meal_time} override was set.
 *   <li>{@code LIFESTYLE_SCHEDULE} — no override; the household owner's lifestyle-config {@code
 *       meal_timing} schedule supplied the time for this slot kind.
 *   <li>{@code KIND_DEFAULT} — neither; the slot-kind default floor applied.
 * </ul>
 */
public enum MealTimeSource {
  SLOT_OVERRIDE,
  LIFESTYLE_SCHEDULE,
  KIND_DEFAULT
}
