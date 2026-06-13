package com.example.mealprep.planner.api.dto;

import com.example.mealprep.core.types.SlotKind;
import com.example.mealprep.planner.domain.entity.MealTimeSource;
import com.example.mealprep.planner.domain.entity.PinnedReason;
import com.example.mealprep.planner.domain.entity.SlotState;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * One eating slot within a {@link DayDto}. {@code scheduledRecipe} is nullable when the slot is
 * empty (e.g. eating out, fasting). {@code pinnedReason} is nullable when the slot is regenerable.
 *
 * <p>{@code mealTime} and {@code prepStepAtTime} (planner-01m) are the slot's <b>stored</b>
 * wall-clock times — both nullable. {@code mealTime} is the per-slot override (null = unset).
 * {@code prepStepAtTime} is reserved for the future pre-cook-actions feature and is always null as
 * of 01m.
 *
 * <p>{@code effectiveMealTime} (frontend-gaps: planner-effective-meal-time) is the server-resolved
 * serve time — the three-level coalesce of slot override → lifestyle-config schedule → slot-kind
 * default — and is <b>never null</b>, so the Plan grid and Today timeline render serve times and
 * "start by" lead-time hints without re-reading the preference module client-side. {@code
 * mealTimeSource} reports which level produced it (so the UI can caption a default vs an override);
 * see {@link MealTimeSource}. The raw {@code mealTime} is retained so the slot editor still knows
 * whether an override exists.
 */
public record MealSlotDto(
    UUID id,
    int slotIndex,
    SlotKind kind,
    String label,
    int timeBudgetMin,
    boolean shared,
    List<UUID> eaters,
    SlotState state,
    PinnedReason pinnedReason,
    LocalTime mealTime,
    LocalTime prepStepAtTime,
    ScheduledRecipeDto scheduledRecipe,
    LocalTime effectiveMealTime,
    MealTimeSource mealTimeSource) {}
