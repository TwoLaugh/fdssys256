package com.example.mealprep.preference.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Substance the user cannot tolerate (lactose, FODMAPs, ...) plus a severity descriptor. */
public record HardIntoleranceDto(
    @NotBlank @Size(max = 64) String substance,
    @NotBlank @Size(max = 32) String severity,
    @Size(max = 255) String notes) {}
