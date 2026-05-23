-- Preference module — nutrition/01j: directive-sourced hard-constraint provenance.
-- See tickets/nutrition/01j-directive-apply-target-preference-model.md §5 (option a) and
-- lld/nutrition.md:1016 ("temporary and autoExpiresAt persisted with the constraint so preference
-- can expire it").
--
-- A health directive routed to preference_model (e.g. an INGREDIENT_RESTRICTION or a time-boxed
-- ELIMINATION_TRIAL) adds an intolerance to the user's hard constraints. When that directive is
-- temporary, the added intolerance row records:
--   source_directive_id — links the row back to the source HealthDirective so the deferred
--                          auto-expiry sweep (PreferenceUpdateService.removeTemporaryConstraint)
--                          can reverse exactly the directive's additions by a clean by-column query.
--   auto_expires_at      — the directive's expiry instant, for the sweep's cutoff comparison.
--
-- Both columns are nullable + additive: user-authored intolerances (set via the hard-constraints
-- PUT) and existing rows leave both NULL. Only directive-sourced temporary additions are stamped.

ALTER TABLE preference_hard_intolerances
    ADD COLUMN auto_expires_at     timestamptz NULL,
    ADD COLUMN source_directive_id uuid        NULL;

-- The auto-expiry sweep + removeTemporaryConstraint reversal both look up rows by source_directive_id.
-- Partial index: only directive-sourced rows are ever queried this way, so skip the (many) NULLs.
CREATE INDEX idx_pref_hard_intolerances_source_directive
    ON preference_hard_intolerances (source_directive_id)
    WHERE source_directive_id IS NOT NULL;
