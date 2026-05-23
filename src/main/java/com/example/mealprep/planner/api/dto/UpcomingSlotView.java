package com.example.mealprep.planner.api.dto;

import com.example.mealprep.core.types.SlotKind;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/**
 * Flat read projection of an upcoming planned meal slot, returned by {@link
 * com.example.mealprep.planner.domain.service.PlanQueryService#getUpcomingSlots}. Built for the
 * notification {@code PrepReminderScanner}, which needs the slot's identity, day date, kind, time
 * budget and wall-clock meal time to derive a deterministic prep moment — without hydrating the
 * whole {@link PlanDto} graph.
 *
 * <p>{@code mealTime} is the slot's resolved wall-clock meal time (planner-01m) and is <b>never
 * null</b>. The planner resolves it via a three-level coalesce:
 *
 * <ol>
 *   <li>the slot's stored {@code meal_time} override, if set;
 *   <li>else the household owner's lifestyle-config {@code meal_timing.preferred_schedule} entry
 *       for the slot {@code kind} (the start of its time range, e.g. {@code "18:30-19:30"} -&gt;
 *       {@code 18:30});
 *   <li>else the slot-kind default (last-resort floor: BREAKFAST 08:00 / LUNCH 12:30 / DINNER 18:00
 *       / SNACK 15:00 / CUSTOM 12:00), preserving the pre-01m behaviour so households with no
 *       lifestyle config see no regression.
 * </ol>
 *
 * <p>{@code prepStepAtTime} is the slot's stored {@code prep_step_at_time} (reserved for the future
 * "pre-cook actions" feature); it is <b>nullable</b> and always null as of 01m.
 *
 * <p>{@code recipeId} is null for an empty slot (eating out / fasting).
 */
public record UpcomingSlotView(
    UUID slotId,
    UUID planId,
    UUID householdId,
    LocalDate dayDate,
    SlotKind kind,
    int slotIndex,
    int timeBudgetMin,
    UUID recipeId,
    LocalTime mealTime,
    LocalTime prepStepAtTime) {}
