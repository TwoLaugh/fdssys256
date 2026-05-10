package com.example.mealprep.provisions.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Read shape of the budget aggregate. {@code spendTracking} is always {@code null} in v1
 * (provisions-01c) — populated by 01f/01h once grocery-order history is wired. The field is
 * declared {@link JsonInclude.Include#ALWAYS} so JSON serialisation emits {@code "spendTracking":
 * null} explicitly, matching the contract in the OpenAPI schema.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record BudgetDto(
    UUID id,
    UUID userId,
    BigDecimal weeklyTarget,
    String currency,
    BigDecimal toleranceOver,
    PriceSensitivity priceSensitivity,
    boolean enabled,
    BudgetSpendTrackingDto spendTracking,
    long version) {}
