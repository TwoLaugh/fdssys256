package com.example.mealprep.recipe.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Request body for {@code POST /substitutions/{subId}/promote-to-version}. */
public record PromoteSubstitutionRequest(
    @Min(0) long expectedVersion, @NotBlank @Size(max = 2000) String changeReason) {}
