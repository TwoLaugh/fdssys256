package com.example.mealprep.provisions.api.dto;

import java.util.List;

/**
 * Result of a {@link GroceryOrderImportCommand} apply. Partitioned by what happened to each row:
 * {@code addedItems} got fresh inventory rows; {@code mergedItems} folded into existing rows.
 * {@code updatedSupplierProducts} carries each supplier-product row refreshed by the import. {@code
 * warnings} carries advisory messages (e.g. an un-cached supplier product for a substitution).
 */
public record GroceryImportResultDto(
    List<InventoryItemDto> addedItems,
    List<InventoryItemDto> mergedItems,
    List<SupplierProductDto> updatedSupplierProducts,
    List<String> warnings) {}
