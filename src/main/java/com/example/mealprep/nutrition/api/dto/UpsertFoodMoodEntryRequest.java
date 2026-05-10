package com.example.mealprep.nutrition.api.dto;

import com.example.mealprep.nutrition.domain.entity.MealSlot;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Request body for both {@code POST /api/v1/nutrition/journal/{date}} (create) and {@code PUT
 * /api/v1/nutrition/journal/{date}/entries/{entryId}} (update). On create, {@code expectedVersion}
 * is ignored.
 */
public record UpsertFoodMoodEntryRequest(
    @NotNull LocalDate onDate,
    MealSlot mealSlot,
    @NotBlank @Size(max = 4000) String journalEntry,
    @NotNull Instant loggedAt,
    @PositiveOrZero long expectedVersion) {}
