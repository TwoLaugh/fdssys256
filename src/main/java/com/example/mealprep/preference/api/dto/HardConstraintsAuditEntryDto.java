package com.example.mealprep.preference.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

/**
 * One field-level entry in the hard-constraints audit log. The {@code previousValueJson} and {@code
 * newValueJson} fields carry the full old/new value for the changed field — not a diff — because
 * the safety-review workflow needs to inspect the value at point-in-time.
 */
public record HardConstraintsAuditEntryDto(
    UUID id,
    UUID hardConstraintsId,
    UUID actorUserId,
    String fieldChanged,
    JsonNode previousValueJson,
    JsonNode newValueJson,
    Instant occurredAt) {}
