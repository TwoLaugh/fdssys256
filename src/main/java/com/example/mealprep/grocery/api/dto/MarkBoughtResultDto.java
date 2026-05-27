package com.example.mealprep.grocery.api.dto;

import com.example.mealprep.grocery.domain.entity.LineFulfilmentStatus;
import java.util.UUID;

/**
 * Result of a Tier-2 mark-bought. Per lld/grocery.md line 463. {@code priceObservationId} is null
 * when no price was supplied; {@code inventoryItemId} is the inventory row added/merged by the
 * provisions write; {@code note} carries an advisory (e.g. the over-mark warning when {@code
 * boughtQuantity > requestedQuantity}, GROC-11) — null when there is nothing to flag.
 */
public record MarkBoughtResultDto(
    UUID shoppingListLineId,
    LineFulfilmentStatus newStatus,
    UUID priceObservationId,
    UUID inventoryItemId,
    String note) {}
