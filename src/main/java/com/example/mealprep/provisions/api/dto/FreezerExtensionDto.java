package com.example.mealprep.provisions.api.dto;

import com.example.mealprep.provisions.domain.entity.DefrostMethod;
import jakarta.validation.constraints.Min;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Wire shape for the freezer-only extension fields. Carried on {@link InventoryItemDto} when {@code
 * storageLocation = FREEZER}, and required (non-null) on the create/update requests in that case
 * (enforced by {@code @ValidStorageLocation}).
 */
public record FreezerExtensionDto(
    LocalDate frozenAt,
    @Min(0) Integer maxFreezeWeeks,
    DefrostMethod defrostMethod,
    @Min(0) Integer defrostLeadTimeHours,
    UUID sourceRecipeId) {}
