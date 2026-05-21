-- Preference module — 01c per-delta-batch version snapshot of taste-profile documents.
-- Append-only. One row per delta-batch apply, manual override, or rollback.
-- See lld/preference.md §V20260501120100 — Taste profile, versions, archive.

CREATE TABLE preference_taste_profile_versions (
    id                       uuid        PRIMARY KEY,
    taste_profile_id         uuid        NOT NULL REFERENCES preference_taste_profile(id) ON DELETE CASCADE,
    document_version         integer     NOT NULL,
    document_snapshot        jsonb       NOT NULL,
    feedback_range_start     varchar(64),
    feedback_range_end       varchar(64),
    trigger                  varchar(16) NOT NULL,
    deltas_applied           jsonb       NOT NULL,
    model_tier_used          varchar(16) NOT NULL,
    generated_at             timestamptz NOT NULL,
    UNIQUE (taste_profile_id, document_version)
);
-- Hot read: version history listing (newest-first) and rollback target lookup.
CREATE INDEX idx_pref_tp_versions_tp_ver
    ON preference_taste_profile_versions (taste_profile_id, document_version DESC);
