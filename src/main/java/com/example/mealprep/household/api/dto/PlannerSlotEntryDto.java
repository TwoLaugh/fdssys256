package com.example.mealprep.household.api.dto;

import com.example.mealprep.household.domain.entity.SlotKind;
import java.util.List;
import java.util.UUID;

/**
 * Flattened, planner-friendly slot row (01f). One entry per resolved slot in a household's
 * configuration — co-exists with the 01b {@code SlotConfigEntryDto} (separate record; the planner
 * iterates a flat list rather than the nested 01b shape, and carries an optional {@code
 * cuisinePreferenceWeight} the 01b shape lacks).
 *
 * <p>{@code cuisinePreferenceWeight} is {@code null} for v1 — the {@code
 * HouseholdSettingsDocument.SlotDefault} record does not yet carry a cuisine-preference field. When
 * settings gains the field, the planner-view resolver picks it up without DTO changes.
 */
public record PlannerSlotEntryDto(
    String slotKey,
    SlotKind kind,
    boolean shared,
    int headcount,
    int timeBudgetMin,
    List<UUID> eaterUserIdsIfPerPerson,
    Integer cuisinePreferenceWeight) {}
