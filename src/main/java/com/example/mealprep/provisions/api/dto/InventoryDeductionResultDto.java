package com.example.mealprep.provisions.api.dto;

import java.util.List;
import java.util.UUID;

/**
 * Response shape for cook-event / meal-consumption flows. {@code updatedItems} carries the
 * post-deduction read-back of each affected row; {@code exhaustedItems} lists the IDs that hit
 * {@code itemStatus = EXHAUSTED}; {@code underflows} carries one entry per ingredient where the
 * pantry couldn't cover the requested amount (only populated when {@code strict == false}).
 */
public record InventoryDeductionResultDto(
    List<InventoryItemDto> updatedItems,
    List<UUID> exhaustedItems,
    List<UnderflowFlagDto> underflows) {}
