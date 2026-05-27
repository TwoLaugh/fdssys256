package com.example.mealprep.grocery.api.dto;

import com.example.mealprep.grocery.validation.ValidObservedPrice;
import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;
import java.time.Instant;

/** Tier-4 manual price capture. Per lld/grocery.md line 479. */
public record RecordManualPriceRequest(
    @NotBlank String ingredientMappingKey,
    @NotBlank String store,
    @ValidObservedPrice Integer paidTotalPence,
    BigDecimal quantity,
    String quantityUnit,
    Instant observedAt) {}
