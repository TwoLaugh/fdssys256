package com.example.mealprep.nutrition.api.dto;

import com.example.mealprep.nutrition.domain.entity.IntakeAuditAction;
import com.example.mealprep.nutrition.domain.entity.MealSlot;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

/** Read shape of a single intake audit row. */
public record IntakeAuditEntryDto(
    UUID id,
    UUID intakeDayId,
    UUID actorUserId,
    IntakeAuditAction action,
    MealSlot mealSlot,
    UUID snackId,
    JsonNode previousValue,
    JsonNode newValue,
    Instant occurredAt) {}
