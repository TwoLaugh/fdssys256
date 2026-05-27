package com.example.mealprep.grocery.api.dto;

import com.example.mealprep.grocery.validation.ValidObservedPrice;
import com.example.mealprep.grocery.validation.ValidQuantityUnit;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Tier-2 single-line mark-bought. Per lld/grocery.md line 473. Price/store/timestamp optional. */
public record MarkBoughtRequest(
    @NotNull UUID shoppingListLineId,
    @NotNull @ValidQuantityUnit BigDecimal boughtQuantity,
    @NotNull String boughtUnit,
    @ValidObservedPrice Integer boughtPricePence,
    String store,
    Instant boughtAt) {}
