package com.example.mealprep.provisions.api.dto;

import com.example.mealprep.provisions.domain.entity.AuditActor;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

/**
 * Read shape of one row from the inventory audit log. {@code previousValue}/{@code newValue} carry
 * the full per-field old/new value (not a diff) so safety reviews can inspect the value at
 * point-in-time.
 */
public record InventoryAuditEntryDto(
    UUID id,
    UUID inventoryItemId,
    AuditActor actor,
    UUID actorUserId,
    String fieldChanged,
    JsonNode previousValue,
    JsonNode newValue,
    Instant occurredAt) {}
