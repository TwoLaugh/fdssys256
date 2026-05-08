-- AI module — 01a call log.
-- See lld/ai.md §Database (V20260501110000__ai_create_call_log).
-- The LLD's V-timestamps were rebased to the project's actual sequence;
-- 01a's call log lands at V20260601400000 after preference (V…300000__).
--
-- Append-only-with-one-update: rows are INSERTed PENDING and UPDATEd exactly
-- once to SUCCEEDED or FAILED. No `version` column — only one race participant
-- per call ever issues that update (the dispatcher is single-threaded inside
-- AiServiceImpl.execute).

CREATE TABLE ai_call_log (
    id                  uuid        PRIMARY KEY,
    user_id             uuid,                              -- nullable: system-initiated tasks
    trace_id            uuid,                              -- decision-log correlation; nullable
    task_type           varchar(64) NOT NULL,
    model_tier          varchar(16) NOT NULL,
    model_id            varchar(96) NOT NULL,
    prompt_ref_name     varchar(128),
    prompt_ref_version  integer,
    request_tokens      integer,
    response_tokens     integer,
    cost_micro_pence    bigint      NOT NULL DEFAULT 0,    -- 01b will compute and update
    status              varchar(16) NOT NULL,              -- PENDING | SUCCEEDED | FAILED
    error_kind          varchar(32),                       -- AI_UNAVAILABLE | INVALID_REQUEST | INVALID_RESPONSE
    latency_ms          integer,
    created_at          timestamptz NOT NULL DEFAULT now(),
    completed_at        timestamptz
);

-- Hot read: per-user cost-cap evaluation (01b will lean on this index).
-- Partial index keeps system-initiated rows out of the way.
CREATE INDEX idx_ai_call_log_user_created
    ON ai_call_log (user_id, created_at DESC)
    WHERE user_id IS NOT NULL;

-- Trace correlation — joins ai_call_log to decision_log when investigating a flow.
CREATE INDEX idx_ai_call_log_trace
    ON ai_call_log (trace_id)
    WHERE trace_id IS NOT NULL;

-- Per-task-type aggregates for the admin dashboard (lands in 01d).
CREATE INDEX idx_ai_call_log_task_created
    ON ai_call_log (task_type, created_at DESC);
