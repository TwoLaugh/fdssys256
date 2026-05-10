package com.example.mealprep.nutrition.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Request body for {@code POST /api/v1/nutrition/intake/{date}/slots/{mealSlot}/override}. */
public record OverrideIntakeRequest(@NotBlank @Size(min = 1, max = 512) String freeText) {}
