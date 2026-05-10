package com.example.mealprep.nutrition.api.dto;

import com.example.mealprep.nutrition.domain.entity.MealSlot;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Read shape of a single {@code FoodMoodJournalEntry}. {@code mealSlot} is nullable to allow untied
 * entries.
 */
public record FoodMoodEntryDto(
    UUID id,
    UUID userId,
    LocalDate onDate,
    MealSlot mealSlot,
    String journalEntry,
    Instant loggedAt,
    long optimisticVersion) {}
