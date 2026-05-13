-- Adaptation pipeline — 01a derivation cache: adaptation_fingerprints.
-- See lld/adaptation-pipeline.md §V20260615120300 (lines 199-209).
-- Catalogue holds the fingerprint on the version row (read path); this table holds
-- derivation provenance (which job + body hash). UPSERT keyed on (recipe_id, branch_id).
-- UNIQUE version_id lets us answer "is this exact version already fingerprinted?".

CREATE TABLE adaptation_fingerprints (
    id                       uuid          PRIMARY KEY,
    recipe_id                uuid          NOT NULL,
    branch_id                uuid          NOT NULL,
    version_id               uuid          NOT NULL UNIQUE,
    body_hash                varchar(64)   NOT NULL,
    fingerprint              jsonb         NOT NULL,
    derived_by_job_id        uuid          REFERENCES adaptation_jobs(id),
    derived_at               timestamptz   NOT NULL
);

-- UPSERT key — one fingerprint cache row per (recipe, branch).
CREATE UNIQUE INDEX idx_adaptation_fingerprints_recipe_branch
    ON adaptation_fingerprints (recipe_id, branch_id);

-- Body-hash lookup for retry idempotency in 01c — if the body hash matches a
-- prior cache row, the LLM call can be skipped.
CREATE INDEX idx_adaptation_fingerprints_body_hash
    ON adaptation_fingerprints (body_hash);
