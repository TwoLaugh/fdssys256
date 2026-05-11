package com.example.mealprep.household.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Planner-friendly slot-configuration snapshot (01f). Layered on top of the 01b {@code
 * SlotConfigurationDto} but with a flattened slot list ({@link PlannerSlotEntryDto}) plus the
 * descending-priority {@code eaterUserIdsByPriority} ordering and the meal-timing window fields the
 * planner uses to compose a one-round-trip slot-resolution.
 *
 * <p>{@code mealTimingWindowStart} / {@code End} are sourced from {@code
 * HouseholdSettings.document.scheduling}; both are {@code null} in v1 because {@code
 * HouseholdSchedulingPreferences} is an empty marker record. When scheduling gains real fields the
 * resolver picks them up.
 *
 * <p>{@code generatedAt} is the resolution-time wall-clock; the planner uses it to detect stale
 * snapshots if it caches.
 */
public record SlotConfigurationPlannerViewDto(
    UUID householdId,
    List<PlannerSlotEntryDto> slots,
    List<UUID> allEaterUserIds,
    List<UUID> eaterUserIdsByPriority,
    String mealTimingWindowStart,
    String mealTimingWindowEnd,
    Instant generatedAt) {}
