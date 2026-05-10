package com.example.mealprep.household.api.dto;

import com.example.mealprep.household.domain.entity.HouseholdSettingsDocument;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Request body for {@code PUT /api/v1/households/{id}/settings}. {@code expectedVersion} carries
 * the {@code @Version} value the client read; a stale value triggers a 409 via {@link
 * org.springframework.dao.OptimisticLockingFailureException} (handled by the global advice). The
 * {@code @Valid} cascade ensures bean-validation runs against {@code HouseholdSettingsDocument}
 * contents.
 */
public record UpdateHouseholdSettingsRequest(
    @NotNull @Valid HouseholdSettingsDocument document, @Min(0) long expectedVersion) {}
