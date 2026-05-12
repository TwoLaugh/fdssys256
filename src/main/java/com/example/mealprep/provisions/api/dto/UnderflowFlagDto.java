package com.example.mealprep.provisions.api.dto;

import java.math.BigDecimal;

/**
 * Per-ingredient underflow signal — emitted by the {@code InventoryDeductionEngine} when {@code
 * available < requested} and strict mode is OFF. The response body collects these so the caller can
 * decide whether to (a) post a grocery import, (b) prompt the user, or (c) treat as informational.
 */
public record UnderflowFlagDto(
    String ingredientMappingKey, BigDecimal requested, BigDecimal available) {}
