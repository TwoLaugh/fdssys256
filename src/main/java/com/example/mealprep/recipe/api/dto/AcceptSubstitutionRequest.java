package com.example.mealprep.recipe.api.dto;

import jakarta.validation.constraints.Min;

/** Request body for {@code POST /substitutions/{subId}/accept}. */
public record AcceptSubstitutionRequest(@Min(0) long expectedVersion) {}
