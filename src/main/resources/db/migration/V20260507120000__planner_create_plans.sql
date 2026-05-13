-- Planner module — 01a aggregate root: planner_plans.
-- See lld/planner.md §V20260507120000 — Plan aggregate root.
-- Option B (normalised) — locked decision. Option A's plan_document column is NOT shipped.
--
-- score_breakdown / rollup_summary are JSONB carriers populated by 01e (scoring) / 01f (rollup).
-- 01a only needs the JSONB round-trip working; defaults of '{}'::jsonb satisfy the NOT NULL
-- constraint for any fixture row inserted before those tickets land.

CREATE TABLE planner_plans (
    id                       uuid          PRIMARY KEY,
    household_id             uuid          NOT NULL,
    week_start_date          date          NOT NULL,
    generation               integer       NOT NULL,
    replaces_plan_id         uuid          REFERENCES planner_plans(id),
    status                   varchar(16)   NOT NULL,
    trigger_kind             varchar(32)   NOT NULL,
    trigger_event_id         uuid,
    quality_warning          boolean       NOT NULL DEFAULT false,
    cold_start               boolean       NOT NULL DEFAULT false,
    ai_augmented             boolean       NOT NULL DEFAULT false,
    trace_id                 uuid          NOT NULL,
    decision_id              uuid          NOT NULL,
    accepted_at              timestamptz,
    completed_at             timestamptz,
    rejected_at              timestamptz,
    rejected_reason          varchar(255),
    abandoned_at             timestamptz,
    abandoned_reason         varchar(255),
    score_breakdown          jsonb         NOT NULL DEFAULT '{}'::jsonb,
    rollup_summary           jsonb         NOT NULL DEFAULT '{}'::jsonb,
    version                  bigint        NOT NULL DEFAULT 0,
    created_at               timestamptz   NOT NULL,
    updated_at               timestamptz   NOT NULL
);

-- Hot read: getActivePlan(household, week). Filter by both columns + status='ACTIVE'.
CREATE INDEX idx_planner_plans_household_week_status
    ON planner_plans (household_id, week_start_date, status);

-- Plan history listing for a household; the UI's 'previous weeks' view.
CREATE INDEX idx_planner_plans_household_week_gen
    ON planner_plans (household_id, week_start_date, generation DESC);

-- Range listing: getPlansBetween(household, from, to).
CREATE INDEX idx_planner_plans_household_range
    ON planner_plans (household_id, week_start_date);

-- Trace lookup for the decision-log explanation flow.
CREATE INDEX idx_planner_plans_trace
    ON planner_plans (trace_id);
