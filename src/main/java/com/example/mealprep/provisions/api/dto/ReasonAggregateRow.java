package com.example.mealprep.provisions.api.dto;

import java.math.BigDecimal;

/**
 * Internal JPA projection for the {@code group by reason} aggregate query on the waste log. Used
 * only by {@code WasteEntryRepository.aggregateByReason} and assembled into {@link WasteSummaryDto}
 * in the service impl — never exposed over HTTP.
 */
public record ReasonAggregateRow(WasteReason reason, long entryCount, BigDecimal totalCost) {}
