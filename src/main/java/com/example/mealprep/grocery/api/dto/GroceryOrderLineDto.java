package com.example.mealprep.grocery.api.dto;

import com.example.mealprep.grocery.domain.entity.OrderLineStatus;
import java.math.BigDecimal;
import java.util.UUID;

/** Read shape for a Tier-3 grocery-order line. Per lld/grocery.md lines 424-430. */
public record GroceryOrderLineDto(
    UUID id,
    UUID shoppingListLineId,
    String providerProductId,
    String ingredientMappingKey,
    String displayName,
    BigDecimal quantityRequested,
    String quantityUnit,
    Integer packSizeG,
    Integer packCountRequested,
    Integer packCountDelivered,
    Integer quotedUnitPence,
    Integer confirmedUnitPence,
    Integer paidUnitPence,
    OrderLineStatus lineStatus,
    String note) {}
