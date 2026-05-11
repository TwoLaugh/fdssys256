package com.example.mealprep.provisions.api.dto;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Planner-facing read snapshot — aggregates active inventory, staples needing replenishment,
 * available equipment, the budget aggregate, frequently-used supplier-products, and staleness
 * metadata into a single response. Consumed by the planner module's provisions-utilisation
 * sub-score (planner-side; not built yet).
 *
 * <p>{@code budget} is nullable per the 01f LLD divergence — users without a budget row read back
 * {@code null} and the planner falls back to its no-budget gate. All collection fields are non-null
 * (empty when the user has no state).
 *
 * <p>Read-only; no events, no writes — see {@code ProvisionForPlannerService#getBundle}.
 */
public record ProvisionForPlannerBundleDto(
    UUID userId,
    List<InventoryItemDto> activeInventory,
    List<InventoryItemDto> staplesAtLowOrOut,
    List<EquipmentDto> equipment,
    BudgetDto budget,
    Map<String, SupplierProductDto> supplierPricesByMappingKey,
    BundleStaleness staleness) {}
