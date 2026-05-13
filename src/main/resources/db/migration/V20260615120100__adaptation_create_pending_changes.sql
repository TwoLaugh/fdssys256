-- Adaptation pipeline — 01a aggregate root: adaptation_pending_changes.
-- See lld/adaptation-pipeline.md §V20260615120100 (lines 120-153).
-- Per the recipe LLD's handoff: pending-change storage lives in adaptation, not the
-- catalogue. The partial unique index on (recipe_id, change_dimension) WHERE
-- status = 'PENDING' atomically enforces supersession — only one PENDING per pair.
-- FK on job_id ON DELETE CASCADE: purging a job purges its pending-change children.
-- 14-day expiry per HLD; daily sweep flips expired PENDINGs to EXPIRED.

CREATE TABLE adaptation_pending_changes (
    id                       uuid          PRIMARY KEY,
    recipe_id                uuid          NOT NULL,
    user_id                  uuid          NOT NULL,
    job_id                   uuid          NOT NULL REFERENCES adaptation_jobs(id) ON DELETE CASCADE,
    trace_id                 uuid          NOT NULL,
    change_dimension         varchar(48)   NOT NULL,
    proposed_diff            jsonb         NOT NULL,
    proposed_classification  varchar(16)   NOT NULL,
    base_version_id          uuid          NOT NULL,
    base_branch_id           uuid          NOT NULL,
    reasoning                text          NOT NULL,
    nutritional_notes        text,
    confidence               numeric(4,3)  NOT NULL,
    impact_score             numeric(4,3)  NOT NULL,
    prompt_template_version  varchar(40)   NOT NULL,
    status                   varchar(16)   NOT NULL DEFAULT 'PENDING',
    superseded_by            uuid          REFERENCES adaptation_pending_changes(id),
    accepted_version_id      uuid,
    user_edits               jsonb,
    created_at               timestamptz   NOT NULL,
    expires_at               timestamptz   NOT NULL,
    resolved_at              timestamptz,
    optimistic_version       bigint        NOT NULL DEFAULT 0
);

-- HLD: supersession keyed by (recipe_id, change_dimension); only one PENDING per pair.
CREATE UNIQUE INDEX idx_adaptation_pending_recipe_dim_active
    ON adaptation_pending_changes (recipe_id, change_dimension)
    WHERE status = 'PENDING';

-- Per-week ranking pool — surface top-N PENDING per user by impact x confidence.
CREATE INDEX idx_adaptation_pending_user_pending_rank
    ON adaptation_pending_changes (user_id, impact_score DESC, confidence DESC)
    WHERE status = 'PENDING';

-- Daily expiry sweep scans this.
CREATE INDEX idx_adaptation_pending_expiry
    ON adaptation_pending_changes (expires_at)
    WHERE status = 'PENDING';

-- Per-recipe history view.
CREATE INDEX idx_adaptation_pending_recipe_time
    ON adaptation_pending_changes (recipe_id, created_at DESC);
