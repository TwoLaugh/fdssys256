package com.example.mealprep.provisions.api.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Derived spend-tracking shape for {@link BudgetDto}. Reserved in 01c — the {@code spendTracking}
 * field on {@code BudgetDto} is always {@code null} until provisions-01f/01h wires up grocery-order
 * history and the rolling-average derivation.
 */
public record BudgetSpendTrackingDto(
    BigDecimal currentWeekTarget,
    BigDecimal currentWeekActual,
    BigDecimal currentWeekRemaining,
    List<BudgetOrderRefDto> currentWeekOrders,
    BigDecimal rollingFourWeekAverage) {}
