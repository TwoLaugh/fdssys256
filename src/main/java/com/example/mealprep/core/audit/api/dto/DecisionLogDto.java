package com.example.mealprep.core.audit.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

/**
 * Read DTO for a single {@code decision_log} row. The JSONB columns ({@code inputs}, {@code
 * candidates}, {@code chosen}, {@code emittedDirective}) are surfaced as Jackson {@link JsonNode}
 * so callers can introspect them without a typed shape — the shape varies per scope and lives in
 * domain modules, not here.
 *
 * @param decisionId application-generated UUID
 * @param traceId groups related decisions across scales
 * @param parentDecisionId walked by the ancestry endpoint; null for roots
 * @param scopeKind e.g. {@code "plan-week"}, {@code "recipe"}; lowercase-hyphenated
 * @param scopeId UUID of the scope instance
 * @param scale see {@link DecisionLogScale}
 * @param triggeredBy e.g. {@code "user-initiated"}, {@code "feedback-event"}
 * @param actorUserId null for system-initiated decisions
 * @param inputs structured inputs that fed into this decision
 * @param candidates candidate set (Stage A); null when not applicable
 * @param chosen the chosen candidate or outcome; null when not applicable
 * @param reasoning free-text reasoning recorded with the decision
 * @param emittedDirective Stage D refine-directive emitted to a downstream loop; null when not
 *     applicable
 * @param iteration loop iteration index within this trace; 0-indexed
 * @param durationMs how long the loop iteration took; null if not measured
 * @param createdAt timestamp the row landed
 */
public record DecisionLogDto(
    UUID decisionId,
    UUID traceId,
    UUID parentDecisionId,
    String scopeKind,
    UUID scopeId,
    DecisionLogScale scale,
    String triggeredBy,
    UUID actorUserId,
    JsonNode inputs,
    JsonNode candidates,
    JsonNode chosen,
    String reasoning,
    JsonNode emittedDirective,
    int iteration,
    Integer durationMs,
    Instant createdAt) {}
