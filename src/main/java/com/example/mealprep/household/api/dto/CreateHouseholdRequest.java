package com.example.mealprep.household.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body of {@code POST /api/v1/households}. */
public record CreateHouseholdRequest(@NotBlank @Size(max = 128) String name) {}
