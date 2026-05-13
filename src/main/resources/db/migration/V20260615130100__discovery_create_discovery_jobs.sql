-- Discovery module — 01a discovery_jobs.
-- See lld/discovery.md §V20260615120100. Async-job aggregate: state machine
-- queued → running → succeeded | failed | partial. constraints_json is a versioned
-- DiscoveryConstraints record snapshot taken at enqueue time.
--
-- sources_requested / sources_succeeded / sources_failed: spec says text[]; per ticket
-- invariant 9 the project has hit Hibernate text[] flakiness (see preference / recipe
-- modules) and the chosen fallback is JSONB list-of-strings. Adopting that fallback
-- upfront to match the rest of the repo and skip iteration churn.
--
-- status / trigger enum strings stored UPPERCASE per ticket invariant 19.

CREATE TABLE discovery_jobs (
    id                            uuid          PRIMARY KEY,
    user_id                       uuid          NOT NULL,
    trigger                       varchar(32)   NOT NULL,
    requested_count               integer       NOT NULL,
    constraints_json              jsonb         NOT NULL,
    sources_requested             jsonb         NOT NULL DEFAULT '[]'::jsonb,
    status                        varchar(16)   NOT NULL DEFAULT 'QUEUED',
    queued_at                     timestamptz   NOT NULL,
    started_at                    timestamptz,
    completed_at                  timestamptz,
    candidates_seen               integer       NOT NULL DEFAULT 0,
    candidates_after_filter       integer       NOT NULL DEFAULT 0,
    recipes_ingested              integer       NOT NULL DEFAULT 0,
    recipes_skipped_duplicate     integer       NOT NULL DEFAULT 0,
    sources_succeeded             jsonb         NOT NULL DEFAULT '[]'::jsonb,
    sources_failed                jsonb         NOT NULL DEFAULT '[]'::jsonb,
    error_summary                 text,
    trace_id                      uuid          NOT NULL,
    optimistic_version            bigint        NOT NULL DEFAULT 0,
    created_at                    timestamptz   NOT NULL,
    updated_at                    timestamptz   NOT NULL
);

-- Polling and admin list: read by user, filtered by status, newest first.
CREATE INDEX idx_discovery_jobs_user_status
    ON discovery_jobs (user_id, status, queued_at DESC);

-- Runner watchdog: pick up orphan running jobs after a heartbeat timeout.
CREATE INDEX idx_discovery_jobs_status_started
    ON discovery_jobs (status, started_at)
    WHERE status = 'RUNNING';

-- Trace-id correlation for cold-start invocations from the planner.
CREATE INDEX idx_discovery_jobs_trace ON discovery_jobs (trace_id);
