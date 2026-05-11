package com.example.mealprep.provisions.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Read shape of a single waste-log row. Mirrors {@link
 * com.example.mealprep.provisions.domain.entity.WasteEntry}. {@code inventoryItemId}, {@code
 * quantity}, {@code unit}, {@code costEstimate}, and {@code notes} are nullable to match the
 * append-only entity.
 */
public record WasteEntryDto(
    UUID id,
    UUID userId,
    UUID inventoryItemId,
    String itemName,
    BigDecimal quantity,
    String unit,
    WasteReason reason,
    BigDecimal costEstimate,
    LocalDate occurredOn,
    String notes,
    Instant createdAt) {}
