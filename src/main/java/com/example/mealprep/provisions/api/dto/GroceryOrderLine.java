package com.example.mealprep.provisions.api.dto;

import jakarta.annotation.Nullable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * One line of a grocery order's inbound import. LLD line 461-463 verbatim. Drives the expiry-aware
 * merge-or-create in the inventory aggregate.
 */
public record GroceryOrderLine(
    @NotBlank @Size(max = 128) String productId,
    @NotBlank @Size(max = 255) String name,
    @Nullable @Size(max = 128) String ingredientMappingKey,
    @NotNull @PositiveOrZero BigDecimal quantity,
    @NotBlank @Size(max = 16) String unit,
    @Nullable @PositiveOrZero BigDecimal pricePaid,
    @Nullable @Size(max = 64) String category,
    @Nullable @Positive Integer packSizeG) {}
