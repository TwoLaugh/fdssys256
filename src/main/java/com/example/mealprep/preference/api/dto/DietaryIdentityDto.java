package com.example.mealprep.preference.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Wraps the user's dietary identity. {@code base} is a free-form label at this stage (e.g. {@code
 * "omnivore"}, {@code "vegetarian"}); 01c will introduce {@code @ValidDietaryIdentity} that
 * constrains it against a known enum and cross-checks {@code exceptions} against the user's
 * allergies.
 */
public record DietaryIdentityDto(
    @NotBlank @Size(max = 32) String base,
    @Size(max = 64) String labelForDisplay,
    @NotNull @Valid List<DietaryIdentityExceptionDto> exceptions) {}
