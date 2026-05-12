-- Nutrition module — 01h divergence-detector dedup state.
-- See lld/nutrition.md §Events §Divergence threshold and detection lines 913-924.
-- One row per (user_id, on_date) tracking the last-published diverged-macros set.
-- Dedup survives JVM restart and multi-instance deployment.

CREATE TABLE nutrition_divergence_state (
    user_id          uuid          NOT NULL,
    on_date          date          NOT NULL,
    diverged_macros  jsonb         NOT NULL DEFAULT '[]'::jsonb,   -- JSON array of macro keys
    updated_at       timestamptz   NOT NULL,
    PRIMARY KEY (user_id, on_date)
);

-- Sweep query: DELETE WHERE updated_at < now() - INTERVAL '30 days'.
CREATE INDEX idx_nutrition_divergence_state_updated_at
    ON nutrition_divergence_state (updated_at);
