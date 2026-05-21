package com.example.mealprep.nutrition.api.dto;

import com.example.mealprep.nutrition.domain.entity.IntakeSlotStatus;
import com.example.mealprep.nutrition.domain.entity.MealSlot;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Flat read shape for an intake-slot returned by the search endpoint ({@code GET
 * /api/v1/nutrition/intake/search}). Joins {@code IntakeDay} (for {@code onDate}) and exposes the
 * slot's identifying fields + the override free-text used for substring search.
 *
 * <p>This is intentionally a different shape from {@link IntakeDayDto} (the day-aggregate read);
 * search returns rows, day-fetch returns aggregates.
 */
public record IntakeSlotSearchResultDto(
    UUID slotId,
    UUID intakeDayId,
    LocalDate onDate,
    MealSlot mealSlot,
    IntakeSlotStatus actualStatus,
    UUID plannedRecipeId,
    String overrideFreeText) {}
