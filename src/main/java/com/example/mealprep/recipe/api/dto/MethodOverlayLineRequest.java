package com.example.mealprep.recipe.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Single overlay line in a {@link CreateSubstitutionRequest}. */
public record MethodOverlayLineRequest(
    @Min(1) int step, @NotBlank @Size(max = 2000) String instruction) {}
