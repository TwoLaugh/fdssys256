package com.example.mealprep.nutrition.api.dto;

import com.example.mealprep.nutrition.domain.entity.ActivityLevel;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Request body for {@code PUT /api/v1/nutrition/targets/activity/{date}}. */
public record UpsertDailyActivityRequest(
    @NotNull ActivityLevel activityLevel, @Size(max = 255) String notes) {}
