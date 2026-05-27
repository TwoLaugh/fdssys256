package com.example.mealprep.grocery.api.dto;

import com.example.mealprep.grocery.validation.ValidObservedPrice;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Tier-2 bulk mark-bought. Per lld/grocery.md line 474. When {@code totalSpendPence} is present,
 * the spend is distributed proportionally across the lines.
 */
public record BulkMarkBoughtRequest(
    @NotNull UUID shoppingListId,
    @NotEmpty List<UUID> shoppingListLineIds,
    @ValidObservedPrice Integer totalSpendPence,
    String store,
    Instant boughtAt) {}
