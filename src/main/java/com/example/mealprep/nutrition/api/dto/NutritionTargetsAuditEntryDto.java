package com.example.mealprep.nutrition.api.dto;

import com.example.mealprep.nutrition.domain.entity.ActorKind;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

/**
 * One field-level entry in the nutrition-targets audit log. {@code previousValueJson} and {@code
 * newValueJson} carry the full old/new value for the changed field — not a diff — so safety reviews
 * can inspect the value at point-in-time.
 */
public record NutritionTargetsAuditEntryDto(
    UUID id,
    UUID targetsId,
    UUID actorUserId,
    ActorKind actorKind,
    UUID sourceDirectiveId,
    String fieldPath,
    JsonNode previousValueJson,
    JsonNode newValueJson,
    Instant occurredAt) {}
