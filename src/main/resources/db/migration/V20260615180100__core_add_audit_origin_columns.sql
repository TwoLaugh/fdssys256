-- core-02b: add (actor_type, origin_trace) to every existing per-module audit-log table so
-- subsequent tickets can attribute writes to USER / AI / SYSTEM origin without further DDL.
-- Per tickets/core/02b-origin-tracking-foundation.md §Migration — audit log columns + the
-- design doc's §"Where origin information is persisted".
--
-- Both columns are nullable and additive — existing audit rows (pre-migration) and existing
-- audit writers (which this ticket does NOT modify; that's per-consumer follow-ups in 01g+)
-- continue to insert NULL for both. Read paths must treat NULL actor_type as USER.
--
-- actor_type matches com.example.mealprep.core.origin.ActorType (USER | AI | SYSTEM).
-- No DB check constraint — the Java enum is the whitelist; allows future additions without
-- coupling DDL to enum evolution.
--
-- origin_trace matches the X-Origin-Trace header (typically `feedback-<uuid>`,
-- `scheduled-<cron>-<instance>`, etc.). 128 chars is the OpenAPI maxLength.

ALTER TABLE preference_hard_constraints_audit
    ADD COLUMN actor_type   varchar(16),
    ADD COLUMN origin_trace varchar(128);

ALTER TABLE household_settings_audit
    ADD COLUMN actor_type   varchar(16),
    ADD COLUMN origin_trace varchar(128);

ALTER TABLE nutrition_targets_audit
    ADD COLUMN actor_type   varchar(16),
    ADD COLUMN origin_trace varchar(128);

ALTER TABLE nutrition_intake_audit
    ADD COLUMN actor_type   varchar(16),
    ADD COLUMN origin_trace varchar(128);

ALTER TABLE provision_inventory_audit
    ADD COLUMN actor_type   varchar(16),
    ADD COLUMN origin_trace varchar(128);

-- preference_taste_profile_audit and preference_lifestyle_config_audit do not yet exist
-- (their owning tickets — preference-01c/01d — ship the tables with these columns built in).
-- recipe_versions has a created_by_actor column already (per the ticket's enumeration note);
-- no change required there.
