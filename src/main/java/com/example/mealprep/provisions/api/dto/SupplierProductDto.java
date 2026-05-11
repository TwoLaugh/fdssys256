package com.example.mealprep.provisions.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Read shape of a supplier-product row. {@code substitutionHistory} is always present (empty list
 * when no substitutions yet) — matches the JSONB column's NOT NULL constraint. Nullable scalars
 * correspond to the entity's nullable columns.
 */
public record SupplierProductDto(
    UUID id,
    String productId,
    String supplier,
    String name,
    BigDecimal price,
    BigDecimal pricePerUnit,
    String unit,
    Integer packSizeG,
    String packSizeUnit,
    String category,
    BigDecimal clubcardPrice,
    LocalDate lastChecked,
    List<SubstitutionRecordDto> substitutionHistory,
    String ingredientMappingKey,
    long version) {}
