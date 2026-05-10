package com.example.mealprep.household.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

/**
 * Read shape of one row from the household-settings audit log. {@code previousValue}/{@code
 * newValue} are arbitrary JSON values (a scalar, an object, a {@code null}-equivalent) representing
 * the value at {@code fieldPath} before and after the change.
 */
public record HouseholdSettingsAuditEntryDto(
    UUID id,
    UUID actorUserId,
    String fieldPath,
    JsonNode previousValue,
    JsonNode newValue,
    Instant occurredAt) {}
