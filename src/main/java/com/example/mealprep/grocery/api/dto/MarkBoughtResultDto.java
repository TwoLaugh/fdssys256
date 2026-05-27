package com.example.mealprep.grocery.api.dto;

import com.example.mealprep.grocery.domain.entity.LineFulfilmentStatus;
import java.util.UUID;

/** Result of a Tier-2 mark-bought. Per lld/grocery.md line 463. */
public record MarkBoughtResultDto(
    UUID shoppingListLineId,
    LineFulfilmentStatus newStatus,
    Integer priceObservationId,
    UUID inventoryItemId) {}
