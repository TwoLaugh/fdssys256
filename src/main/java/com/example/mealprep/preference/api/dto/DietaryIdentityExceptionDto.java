package com.example.mealprep.preference.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * One exception widening or narrowing the user's base dietary identity. {@code allows} is a
 * sub-category (e.g. {@code "fish"}); {@code frequency} is informational and not evaluated by the
 * filter (planner enforces it). {@code context} is one of {@code any}, {@code social}, {@code
 * weekend}, {@code weekday}.
 */
public record DietaryIdentityExceptionDto(
    @NotBlank @Size(max = 64) String allows,
    @Size(max = 32) String frequency,
    @NotBlank @Size(max = 32) String context) {}
