package com.example.mealprep.nutrition.api.dto;

import com.example.mealprep.nutrition.domain.entity.ActivityLevel;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Read shape of a single {@code DailyActivityLog} row. */
public record DailyActivityDto(
    UUID id,
    UUID userId,
    LocalDate onDate,
    ActivityLevel activityLevel,
    String notes,
    Instant createdAt) {}
