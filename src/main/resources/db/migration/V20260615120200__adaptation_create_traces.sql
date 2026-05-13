-- Adaptation pipeline — 01a append-only trace log: adaptation_traces.
-- See lld/adaptation-pipeline.md §V20260615120200 (lines 164-190).
-- One row per LLM-touch job; FK (job_id) UNIQUE so the trace co-lives with the job.
-- raw_ai_response is null when Stage C is auto-skipped (top score > 2x runner-up).
-- ai_call_id is conceptually FK to ai_call_log but stays unconstrained to allow
-- the AI module to retire old call rows independently.

CREATE TABLE adaptation_traces (
    id                              uuid          PRIMARY KEY,
    job_id                          uuid          NOT NULL UNIQUE REFERENCES adaptation_jobs(id) ON DELETE CASCADE,
    recipe_id                       uuid          NOT NULL,
    trace_id                        uuid          NOT NULL,
    source                          varchar(24)   NOT NULL,
    prompt_template_name            varchar(128)  NOT NULL,
    prompt_template_version         varchar(40)   NOT NULL,
    ai_call_id                      uuid,
    inputs_snapshot                 jsonb         NOT NULL,
    raw_ai_response                 jsonb,
    candidates                      jsonb         NOT NULL,
    chosen_candidate_index          integer,
    classification_decision         varchar(16),
    final_diff                      jsonb,
    confidence                      numeric(4,3),
    character_preservation_score    numeric(4,3),
    validation_result               varchar(16)   NOT NULL,
    outcome_kind                    varchar(24)   NOT NULL,
    outcome_target_id               uuid,
    duration_ms                     integer       NOT NULL,
    created_at                      timestamptz   NOT NULL
);

-- Used by AdapterRunHistoryController.by-prompt-version to compare runs across
-- prompt revisions.
CREATE INDEX idx_adaptation_traces_prompt
    ON adaptation_traces (prompt_template_name, prompt_template_version, created_at DESC);

-- Per-recipe trace history.
CREATE INDEX idx_adaptation_traces_recipe
    ON adaptation_traces (recipe_id, created_at DESC);

-- Trace-id join for decision-log walks.
CREATE INDEX idx_adaptation_traces_trace
    ON adaptation_traces (trace_id);
