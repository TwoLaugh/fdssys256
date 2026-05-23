package com.example.mealprep.planner.api.dto;

import com.example.mealprep.core.types.SlotKind;
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
 * wall-clock times — both nullable. {@code mealTime} is the per-slot override (null = unset; the
 * effective wall-clock time is resolved at projection time in {@code getUpcomingSlots}). {@code
 * prepStepAtTime} is reserved for the future pre-cook-actions feature and is always null as of 01m.
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
    ScheduledRecipeDto scheduledRecipe) {}
