package com.example.mealprep.provisions.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One delivered grocery-order row referenced from {@link BudgetSpendTrackingDto}. Ships in the
 * shape only in 01c (the wrapping {@code spendTracking} field is always null until 01f/01h).
 */
public record BudgetOrderRefDto(
    String supplier, String orderRef, BigDecimal totalCost, LocalDate deliveredOn) {}
