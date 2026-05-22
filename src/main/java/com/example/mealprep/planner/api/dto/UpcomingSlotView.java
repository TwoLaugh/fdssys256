package com.example.mealprep.planner.api.dto;

import com.example.mealprep.core.types.SlotKind;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Flat read projection of an upcoming planned meal slot, returned by {@link
 * com.example.mealprep.planner.domain.service.PlanQueryService#getUpcomingSlots}. Built for the
 * notification/01b {@code PrepReminderScanner}, which needs the slot's identity, day date, kind and
 * time budget to derive a deterministic prep moment — without hydrating the whole {@link PlanDto}
 * graph.
 *
 * <p>The planner does not (yet) store a wall-clock meal time or an explicit prep-step time — those
 * are an unbuilt "pre-cook actions" planner concern (see {@code design/provision-model.md}). This
 * projection therefore carries the raw scheduling facts; the consuming scanner derives the prep
 * moment from {@code dayDate}, the slot {@code kind} and {@code timeBudgetMin}.
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
    UUID recipeId) {}
