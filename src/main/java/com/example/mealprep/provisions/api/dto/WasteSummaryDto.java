package com.example.mealprep.provisions.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Aggregate view of waste over a window {@code [from, to]} for one user. Per LLD line 298.
 *
 * <p>{@code countByReason} omits reasons with zero entries; callers iterating the map should treat
 * a missing key as zero, not error.
 */
public record WasteSummaryDto(
    LocalDate from,
    LocalDate to,
    BigDecimal totalCostEstimate,
    long totalEntries,
    Map<WasteReason, Long> countByReason,
    List<TopWastedItemDto> topItems) {}
