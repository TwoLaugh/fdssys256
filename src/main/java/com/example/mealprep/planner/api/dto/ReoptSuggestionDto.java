package com.example.mealprep.planner.api.dto;

import com.example.mealprep.planner.domain.entity.ReoptStatus;
import com.example.mealprep.planner.domain.entity.ReoptTriggerKind;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Pending or resolved re-opt prompt. 01a defines the DTO so listener (01k) and read API (01c)
 * tickets can wire against it; only the {@link
 * com.example.mealprep.planner.api.mapper.ReoptSuggestionMapper} round-trip is exercised in 01a.
 */
public record ReoptSuggestionDto(
    UUID id,
    UUID householdId,
    LocalDate weekStartDate,
    UUID planId,
    ReoptTriggerKind triggerKind,
    UUID triggerEventId,
    List<UUID> affectedSlotIds,
    String summary,
    ReoptStatus status,
    Instant expiresAt,
    Instant createdAt,
    Instant resolvedAt) {}
