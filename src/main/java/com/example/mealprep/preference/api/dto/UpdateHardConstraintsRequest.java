package com.example.mealprep.preference.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Full-replacement update for a user's hard-constraints aggregate. The {@code expectedVersion}
 * field is matched against the row's current {@code @Version}; mismatch → 409. Each child
 * collection is replaced wholesale (cascade + orphanRemoval handle delete + insert).
 *
 * <p>The {@code @ValidDietaryIdentity} cross-field validator is intentionally NOT applied here —
 * 01c will add it; today {@code dietaryIdentity.base} is just length-bounded.
 */
public record UpdateHardConstraintsRequest(
    @NotNull List<@NotBlank @Size(max = 64) String> allergies,
    @NotNull @Valid DietaryIdentityDto dietaryIdentity,
    @NotNull List<@NotBlank @Size(max = 64) String> medicalDiets,
    @NotNull @Valid List<HardIntoleranceDto> intolerances,
    @NotNull @Valid List<AgeRestrictionDto> ageRestrictions,
    @Min(0) long expectedVersion) {}
