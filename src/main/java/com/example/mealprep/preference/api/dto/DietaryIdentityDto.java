package com.example.mealprep.preference.api.dto;

import com.example.mealprep.preference.validation.ValidDietaryIdentity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Wraps the user's dietary identity. {@code base} is one of the known dietary bases ({@code
 * omnivore}, {@code vegetarian}, {@code vegan}, {@code pescatarian}, {@code keto}, {@code paleo},
 * {@code other}); each {@code exception.allows} is a known sub-category (or a conditional "X-free"
 * qualifier) and each {@code exception.context} is one of {@code any | social | weekend | weekday}.
 * The {@code @ValidDietaryIdentity} type-level validator enforces this shape wherever the DTO is
 * validated (it fires via the {@code @Valid} cascade from {@code UpdateHardConstraintsRequest});
 * the allergy/intolerance collision check is applied at the request level.
 */
@ValidDietaryIdentity
public record DietaryIdentityDto(
    @NotBlank @Size(max = 32) String base,
    @Size(max = 64) String labelForDisplay,
    @NotNull @Valid List<DietaryIdentityExceptionDto> exceptions) {}
