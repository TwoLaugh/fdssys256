-- Preference module — 01c Tier 2 taste profile aggregate.
-- See lld/preference.md §V20260501120100 — Taste profile and tickets/preference/01c-taste-profile-entity.md.
--
-- One row per user (unique on user_id). JSONB document mirrored by TasteProfileDocument record-tree.
-- Two version fields, intentionally: document_version (HLD monotonic, used for history + rollback) and
-- optimistic_version (JPA @Version for concurrent-write safety).
--
-- The taste_vector vector(1536) column + HNSW index are deferred to a follow-up ticket (per the LLD's
-- async embedding pipeline); only the status scalar fields ship here so the follow-up adds the vector
-- column + index without back-touching the row shape.

CREATE TABLE preference_taste_profile (
    id                       uuid        PRIMARY KEY,
    user_id                  uuid        NOT NULL UNIQUE,
    document                 jsonb       NOT NULL,
    document_version         integer     NOT NULL DEFAULT 1,
    feedback_cursor          varchar(64),
    based_on_feedback_count  integer     NOT NULL DEFAULT 0,
    last_delta_applied_at    timestamptz,
    last_token_estimate      integer,
    taste_vector_status      varchar(16) NOT NULL DEFAULT 'PENDING',
    taste_vector_doc_version integer,
    taste_vector_model_id    varchar(96),
    taste_vector_embedded_at timestamptz,
    optimistic_version       bigint      NOT NULL DEFAULT 0,
    created_at               timestamptz NOT NULL,
    updated_at               timestamptz NOT NULL
);
CREATE UNIQUE INDEX idx_pref_taste_profile_user
    ON preference_taste_profile (user_id);
