package com.example.mealprep.planner.api.dto;

import com.example.mealprep.core.types.SlotKind;
import com.example.mealprep.planner.domain.entity.PinnedReason;
import com.example.mealprep.planner.domain.entity.SlotState;
import java.util.List;
import java.util.UUID;

/**
 * One eating slot within a {@link DayDto}. {@code scheduledRecipe} is nullable when the slot is
 * empty (e.g. eating out, fasting). {@code pinnedReason} is nullable when the slot is regenerable.
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
    ScheduledRecipeDto scheduledRecipe) {}
