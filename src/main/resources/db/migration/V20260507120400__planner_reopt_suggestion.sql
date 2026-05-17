-- Planner module — 01i mid-week re-opt suggestion aggregate: planner_plan_reopt_suggestions.
-- See tickets/planner/01i-mid-week-reopt.md invariant #10 + lld/planner.md §Mid-week re-optimisation.
--
-- Distinct from planner_reopt_suggestions (01a — the listener-side dedupe row keyed on
-- (household, week, triggerEventId)). This table holds the MATERIALISED proposed-assignment
-- diff the MidWeekReoptCoordinator (01i) computes after running Stage A->B->C scoped to the
-- non-pinned slots. 01j's REST accept/reject endpoint reads + transitions these rows; 01k's
-- listeners drive requestReopt(...) which writes them. The proposed assignments are read
-- whole on accept (no inner filtering / no FK into them) so JSONB is the right carrier per
-- style-guide.md §JSONB.

CREATE TABLE planner_plan_reopt_suggestions (
    id                       uuid          PRIMARY KEY,
    plan_id                  uuid          NOT NULL REFERENCES planner_plans(id),
    trigger_kind             varchar(32)   NOT NULL,
    trigger_event_id         uuid          NOT NULL,
    trace_id                 uuid          NOT NULL,
    decision_id              uuid,
    summary                  varchar(255)  NOT NULL,
    status                   varchar(16)   NOT NULL DEFAULT 'PENDING',
    proposed_assignments     jsonb         NOT NULL,
    created_at               timestamptz   NOT NULL,
    expires_at               timestamptz   NOT NULL,
    swept                    boolean       NOT NULL DEFAULT false,
    version                  bigint        NOT NULL DEFAULT 0
);

-- Idempotency on the listener-retry path (invariant #4): the coordinator looks a suggestion up
-- by (plan_id, trigger_event_id) before re-running Stage A->C, so a redelivered upstream event
-- coalesces onto the existing row instead of thrashing the pipeline. UNIQUE both enforces the
-- invariant at the DB level and backs the lookup.
CREATE UNIQUE INDEX idx_planner_plan_reopt_plan_trigger_event
    ON planner_plan_reopt_suggestions (plan_id, trigger_event_id);

-- Budget guard (invariant #14): countByPlanIdAndStatusIn(planId, [PENDING, REJECTED]) reads
-- this index to decide whether the per-plan re-opt budget is exhausted.
CREATE INDEX idx_planner_plan_reopt_plan_status
    ON planner_plan_reopt_suggestions (plan_id, status);

-- Expiry sweep (01l / follow-up): findAllByStatusAndExpiresAtBefore(PENDING, now) flips stale
-- rows to EXPIRED. Partial index keeps the sweep cheap — only un-swept PENDING rows are hot.
CREATE INDEX idx_planner_plan_reopt_expiry_sweep
    ON planner_plan_reopt_suggestions (expires_at)
    WHERE status = 'PENDING' AND swept = false;
