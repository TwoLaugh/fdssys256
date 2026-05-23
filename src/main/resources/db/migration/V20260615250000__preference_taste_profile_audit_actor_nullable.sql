-- preference-01f: AI-applied taste-profile deltas are authored by the AI (actor_type = 'AI'),
-- not a human user, so their audit rows legitimately carry no actor_user_id. Relax the NOT NULL
-- constraint added in V20260615150200 (created when only user-driven taste-profile updates existed).
-- USER-driven changes (manual override) still populate actor_user_id; only AI_DELTA_APPLIED /
-- AI-sourced rows leave it null, with actor_type recording the AI provenance.
ALTER TABLE preference_taste_profile_audit
    ALTER COLUMN actor_user_id DROP NOT NULL;
