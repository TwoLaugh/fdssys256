-- preference-01g: per-user cursor driving the AI delta-generation pipeline's BATCH trigger.
--
-- The feedback module owns the per-destination processed count (the preference module does NOT
-- consume FeedbackProcessedEvent — locked decision, lld/preference.md:692). No pre-existing table
-- tracks a per-user PREFERENCE-routed processed count, so this cursor is added.
--
-- One row per user. `pending_count` increments on each PREFERENCE-routed feedback and resets to 0
-- after a delta-update run; `last_processed_feedback_id` advances to the most-recent processed
-- feedback so the orchestrator can gather the batch "since the last cursor". `last_run_at` /
-- `last_run_trigger` record the most recent run for the WEEKLY sweep (it sweeps users with
-- pending_count > 0). `optimistic_version` backs JPA @Version concurrency between the BATCH
-- increment path and a concurrent run.
CREATE TABLE feedback_preference_delta_cursor (
    id                          uuid PRIMARY KEY,
    user_id                     uuid NOT NULL,
    last_processed_feedback_id  uuid,
    pending_count               integer NOT NULL DEFAULT 0,
    last_run_at                 timestamptz,
    last_run_trigger            varchar(16),
    optimistic_version          bigint NOT NULL DEFAULT 0,
    created_at                  timestamptz NOT NULL,
    updated_at                  timestamptz NOT NULL
);

-- One cursor per user; also the lookup index used by every read (findByUserId).
CREATE UNIQUE INDEX idx_feedback_pref_delta_cursor_user
    ON feedback_preference_delta_cursor (user_id);

-- WEEKLY sweep query: "users with pending_count > 0". Partial index keeps it small (most users
-- have no pending feedback at sweep time).
CREATE INDEX idx_feedback_pref_delta_cursor_pending
    ON feedback_preference_delta_cursor (pending_count)
    WHERE pending_count > 0;
