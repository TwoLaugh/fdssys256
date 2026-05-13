-- Adaptation pipeline — 01a aggregate root: adaptation_jobs.
-- See lld/adaptation-pipeline.md §V20260615120000 (lines 83-110).
-- No FK to recipe_recipes(id) on purpose — recipes can be archived between enqueue
-- and processing per LLD line 86 comment. Status walks PENDING -> RUNNING -> DONE | FAILED.
-- prompt_template_version is pinned at first AI touch, not at insert (LLD line 113).

CREATE TABLE adaptation_jobs (
    id                       uuid          PRIMARY KEY,
    recipe_id                uuid          NOT NULL,
    user_id                  uuid          NOT NULL,
    catalogue                varchar(16)   NOT NULL,
    source                   varchar(24)   NOT NULL,
    priority                 varchar(8)    NOT NULL,
    approval_policy          varchar(16)   NOT NULL,
    status                   varchar(16)   NOT NULL DEFAULT 'PENDING',
    failure_reason           varchar(64),
    failure_excerpt          varchar(512),
    inputs                   jsonb         NOT NULL,
    prompt_template_version  varchar(40),
    trace_id                 uuid          NOT NULL,
    parent_decision_id       uuid,
    enqueued_at              timestamptz   NOT NULL,
    started_at               timestamptz,
    completed_at             timestamptz,
    duration_ms              integer,
    optimistic_version       bigint        NOT NULL DEFAULT 0,
    created_at               timestamptz   NOT NULL,
    updated_at               timestamptz   NOT NULL
);

-- Worker scan in source-priority order — PENDING and RUNNING only so the index
-- doesn't bloat with terminal rows.
CREATE INDEX idx_adaptation_jobs_status_priority
    ON adaptation_jobs (status, priority, enqueued_at)
    WHERE status IN ('PENDING', 'RUNNING');

-- Recipe history view (admin run-history endpoint scans this).
CREATE INDEX idx_adaptation_jobs_recipe_time
    ON adaptation_jobs (recipe_id, enqueued_at DESC);

-- Trace-id join for the optimisation loop's decision-log walk.
CREATE INDEX idx_adaptation_jobs_trace
    ON adaptation_jobs (trace_id);

-- "What's running for this user?" — excludes DONE to keep the partial index slim.
CREATE INDEX idx_adaptation_jobs_user_status
    ON adaptation_jobs (user_id, status)
    WHERE status <> 'DONE';
