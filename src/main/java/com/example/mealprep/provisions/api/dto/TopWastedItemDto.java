package com.example.mealprep.provisions.api.dto;

import java.math.BigDecimal;

/**
 * Top-N entry in {@link WasteSummaryDto#topItems()}. Aggregated over the requested window grouped
 * by {@code itemName}; sorted by {@code entryCount DESC}, tie-break {@code totalCost DESC}.
 *
 * <p>LLD divergence note: shape is inlined by ticket 01e — the LLD declares {@link WasteSummaryDto}
 * but does not pin the {@code topItems} record shape.
 *
 * <p>The {@code entryCount} field is declared {@code long} (not {@code int}) so the JPA constructor
 * expression in {@code WasteEntryRepository.findTopWastedItems} can map {@code count(w)} directly —
 * JPQL's {@code count(*)} returns {@code Long} and a {@code Long → int} cast at the constructor
 * boundary requires a custom converter.
 */
public record TopWastedItemDto(String itemName, long entryCount, BigDecimal totalCost) {}
