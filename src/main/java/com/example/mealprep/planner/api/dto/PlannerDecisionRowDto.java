package com.example.mealprep.planner.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

/**
 * One row of a plan's decision-log chain (planner-01l admin endpoint). A flattened, planner-facing
 * projection of the cross-cutting {@code core.audit.DecisionLogDto}: the {@code kind} is lifted out
 * of the {@code inputs} JSON (the shared table has no kind column) so a reader can reconstruct the
 * generation DAG without re-parsing JSON.
 *
 * @param decisionId the row's id
 * @param parentDecisionId the parent row id (chains the DAG); {@code null} for trace roots
 * @param traceId the cross-stage / cross-module correlation id
 * @param kind the planner decision kind (lifted from {@code inputs.kind}); {@code null} if absent
 * @param inputs structured inputs (still includes the {@code kind} key)
 * @param outputs structured outputs; {@code null} when not applicable
 * @param reasoning free-text reasoning; {@code null} when not applicable
 * @param createdAt when the row landed
 */
public record PlannerDecisionRowDto(
    UUID decisionId,
    UUID parentDecisionId,
    UUID traceId,
    String kind,
    JsonNode inputs,
    JsonNode outputs,
    String reasoning,
    Instant createdAt) {}
