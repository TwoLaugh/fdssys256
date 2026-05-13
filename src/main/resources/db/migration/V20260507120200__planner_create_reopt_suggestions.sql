-- Planner module — 01a re-opt suggestions table: planner_reopt_suggestions.
-- See lld/planner.md §V20260507120200.

CREATE TABLE planner_reopt_suggestions (
    id                       uuid          PRIMARY KEY,
    household_id             uuid          NOT NULL,
    week_start_date          date          NOT NULL,
    plan_id                  uuid          NOT NULL REFERENCES planner_plans(id),
    trigger_kind             varchar(32)   NOT NULL,
    trigger_event_id         uuid,
    affected_slot_ids        uuid[]        NOT NULL DEFAULT '{}',
    summary                  varchar(255)  NOT NULL,
    status                   varchar(16)   NOT NULL DEFAULT 'PENDING',
    expires_at               timestamptz,
    created_at               timestamptz   NOT NULL,
    resolved_at              timestamptz,
    version                  bigint        NOT NULL DEFAULT 0
);
-- Hot read: notification module + UI list pending suggestions.
CREATE INDEX idx_planner_reopt_pending
    ON planner_reopt_suggestions (household_id, status, created_at DESC);
