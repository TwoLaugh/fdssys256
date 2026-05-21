package com.example.mealprep.preference.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

/**
 * Read shape of one row from the lifestyle-config audit log. {@code previousValueJson}/{@code
 * newValueJson} are arbitrary JSON values (a scalar, an object, a {@code null}-equivalent)
 * representing the section value at {@code fieldPath} before and after the change.
 */
public record LifestyleConfigAuditEntryDto(
    UUID id,
    UUID actorUserId,
    String fieldPath,
    JsonNode previousValueJson,
    JsonNode newValueJson,
    Instant occurredAt) {}
