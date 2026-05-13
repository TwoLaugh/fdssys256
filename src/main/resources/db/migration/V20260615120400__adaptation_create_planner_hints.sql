-- Adaptation pipeline — 01a planner-hints store: adaptation_planner_hints.
-- See lld/adaptation-pipeline.md §V20260615120400 (lines 218-238).
-- Per HLD §Planner hints, some hints (overnight soak, absorption conflict) outlive
-- a single job; planner reads them at plan-composition time.
-- FK on emitted_by_job_id ON DELETE SET NULL — preserves hints even when the job
-- that emitted them is purged. invalidated_at = now() hides the row from planner reads
-- without deleting it (history kept for audit).

CREATE TABLE adaptation_planner_hints (
    id                       uuid          PRIMARY KEY,
    recipe_id                uuid          NOT NULL,
    version_id               uuid          NOT NULL,
    branch_id                uuid          NOT NULL,
    hint_type                varchar(48)   NOT NULL,
    description              text          NOT NULL,
    payload                  jsonb         NOT NULL,
    severity                 varchar(16)   NOT NULL DEFAULT 'INFO',
    emitted_by_job_id        uuid          REFERENCES adaptation_jobs(id) ON DELETE SET NULL,
    trace_id                 uuid          NOT NULL,
    created_at               timestamptz   NOT NULL,
    invalidated_at           timestamptz
);

-- Planner's per-version active-hint read.
CREATE INDEX idx_adaptation_planner_hints_version
    ON adaptation_planner_hints (version_id)
    WHERE invalidated_at IS NULL;

-- Per-recipe active-hint read (e.g. cross-version scope).
CREATE INDEX idx_adaptation_planner_hints_recipe_active
    ON adaptation_planner_hints (recipe_id)
    WHERE invalidated_at IS NULL;
