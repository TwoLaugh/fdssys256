package com.example.mealprep.provisions.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * Request body for {@code POST /api/v1/provisions/supplier-products/{id}/substitutions}. {@code
 * expectedVersion} is required — stale values surface as 409 via {@code
 * OptimisticLockingFailureException}.
 */
public record RecordSubstitutionRequest(
    @NotNull @Valid SubstitutionRecordDto record,
    boolean userAccepted,
    @NotNull @PositiveOrZero Long expectedVersion) {}
