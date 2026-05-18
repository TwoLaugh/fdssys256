package com.example.mealprep.planner.api.dto;

import com.example.mealprep.planner.domain.entity.ReoptSuggestionStatus;
import com.example.mealprep.planner.domain.entity.ReoptTriggerKind;
import java.time.Instant;
import java.util.UUID;

/**
 * Read shape for a {@code MealPrepPlanReoptSuggestion} (planner-01i) — the materialised mid-week
 * re-opt proposal the user accepts/rejects via planner-01j's endpoints.
 *
 * <p>Distinct from {@link ReoptSuggestionDto} (the 01a listener-dedupe row): this carries the
 * concrete proposed slot diff ({@link ProposedReoptAssignmentsDocument}) and the 01i lifecycle
 * status set ({@link ReoptSuggestionStatus}).
 */
public record PlanReoptSuggestionDto(
    UUID id,
    UUID planId,
    ReoptTriggerKind triggerKind,
    UUID triggerEventId,
    UUID traceId,
    UUID decisionId,
    String summary,
    ReoptSuggestionStatus status,
    ProposedReoptAssignmentsDocument proposedAssignments,
    Instant createdAt,
    Instant expiresAt) {}
