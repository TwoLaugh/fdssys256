-- Preference module — 01e Tier-2 taste-profile archive (pruned-item retention + re-emergence).
-- See lld/preference.md:175-191, design/preference-model.md:71, and
-- tickets/preference/01e-preference-archive.md.
--
-- Unbounded retention target for items pruned from the taste profile under token pressure. Three
-- roles: audit/history, re-emergence detection (RE_PROMOTE vs ADD), and a user-facing archive view.
--
-- Append-only-with-a-single-mutable-column: re_promoted_at is the only column ever updated (flipped
-- by markRePromoted). A re-promoted row is NOT deleted — it remains as history. Re-archiving the
-- same logical item inserts a NEW row (no upsert); the (user_id, field_path, item_key,
-- re_promoted_at IS NULL) predicate locates the currently-archived instance.
--
-- No optimistic_version column: the only writer is the future TasteProfileDeltaApplier, which runs
-- under the same single-flight-per-user @Transactional boundary as the taste-profile update.

CREATE TABLE preference_taste_profile_archive (
    id                       uuid        PRIMARY KEY,
    user_id                  uuid        NOT NULL,
    field_path               varchar(128) NOT NULL,
    item_key                 varchar(128) NOT NULL,
    item_payload             jsonb       NOT NULL,
    evidence_count           integer     NOT NULL DEFAULT 0,
    last_signal_at           date,
    archived_at              timestamptz NOT NULL,
    archived_reason          varchar(32) NOT NULL,
    re_promoted_at           timestamptz
);
CREATE INDEX idx_pref_archive_user_field_key
    ON preference_taste_profile_archive (user_id, field_path, item_key);
CREATE INDEX idx_pref_archive_user_archived_at
    ON preference_taste_profile_archive (user_id, archived_at DESC);
