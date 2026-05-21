-- Preference module — 01c entity-level audit log for taste profile.
-- Distinct from preference_taste_profile_versions: this table is the change PROVENANCE log
-- (who, when, what kind of change — manual override vs AI delta vs refresh trigger);
-- the versions table is the per-snapshot document store.
--
-- actor_type values: USER | AI | SYSTEM — anticipating tickets/core/02b origin-tracking pattern.
-- change_type values: INITIALIZED | MANUAL_OVERRIDE | AI_DELTA_APPLIED | REFRESH_TRIGGERED | ROLLED_BACK.

CREATE TABLE preference_taste_profile_audit (
    id                        uuid        PRIMARY KEY,
    taste_profile_id          uuid        NOT NULL REFERENCES preference_taste_profile(id) ON DELETE CASCADE,
    actor_user_id             uuid        NOT NULL,
    actor_type                varchar(16) NOT NULL,
    change_type               varchar(32) NOT NULL,
    previous_document_version integer,
    new_document_version      integer     NOT NULL,
    summary                   varchar(512),
    trace_id                  uuid,
    occurred_at               timestamptz NOT NULL
);
-- Hot read: per-profile audit-log query, newest-first.
CREATE INDEX idx_pref_tp_audit_tp_time
    ON preference_taste_profile_audit (taste_profile_id, occurred_at DESC);
-- Cross-profile actor lookup (e.g. "what has user X done across all aggregates" admin queries).
CREATE INDEX idx_pref_tp_audit_actor
    ON preference_taste_profile_audit (actor_user_id, occurred_at DESC);
