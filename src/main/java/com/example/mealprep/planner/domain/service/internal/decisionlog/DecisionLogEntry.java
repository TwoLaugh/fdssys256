package com.example.mealprep.planner.domain.service.internal.decisionlog;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import org.springframework.lang.Nullable;

/**
 * Planner-facing decision-log entry (planner-01l, ticket invariant #2). {@link DecisionLogWriter}
 * maps this onto the cross-cutting {@code core.audit.DecisionLogWriteRequest}, fixing {@code
 * scope_kind = "PLANNER"} and {@code scale = WEEK}.
 *
 * <p>{@code scopeId} is always the {@code planId} (NOT the household id) per invariant #2 — every
 * planner decision row is queryable as "the decisions about <em>this plan</em>". {@code kind} is
 * persisted inside the {@code inputs} JSON under {@code "kind"} since the shared table has no kind
 * column.
 *
 * <p>{@code parentDecisionId} MUST reference a real, already-persisted {@code decision_log} row —
 * the shared table enforces {@code parent_decision_id REFERENCES decision_log(decision_id)} (NOT
 * by-convention as some prose suggests; the migration declares the FK). Passing a trace id or
 * trigger-event id here is a FK violation. {@code null} marks a trace root.
 *
 * @param kind the planner decision kind
 * @param planId the plan this decision concerns ({@code scope_id})
 * @param actorUserId the acting user; {@code null} for system-initiated decisions
 * @param parentDecisionId parent row id (a real decision id); {@code null} for roots
 * @param traceId the cross-stage / cross-module correlation id; never {@code null}
 * @param inputs structured inputs ({@code "kind"} is injected by the writer); never {@code null}
 * @param outputs structured outputs; {@code null} when not applicable
 * @param reasoning free-text reasoning; {@code null} when not applicable
 * @param triggeredBy {@code "user"} or {@code "system"}
 */
public record DecisionLogEntry(
    PlannerDecisionKind kind,
    UUID planId,
    @Nullable UUID actorUserId,
    @Nullable UUID parentDecisionId,
    UUID traceId,
    JsonNode inputs,
    @Nullable JsonNode outputs,
    @Nullable String reasoning,
    String triggeredBy) {}
