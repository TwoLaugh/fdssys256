package com.example.mealprep.preference.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Age-derived rule key applied by the filter. {@code autoPopulated} flags rules seeded by profile
 * metadata changes vs. user-set ones.
 */
public record AgeRestrictionDto(@NotBlank @Size(max = 64) String ruleKey, boolean autoPopulated) {}
