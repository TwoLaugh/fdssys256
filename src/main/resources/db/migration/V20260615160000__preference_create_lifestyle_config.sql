-- Preference module — 01d Tier 3 lifestyle config (single JSONB document per user).
-- See lld/preference.md §V20260501120200 — Lifestyle config (rebased to 01d's actual sequence).
--
-- Stores the user-set settings document modelled by LifestyleConfigDocument. JSONB is chosen
-- over ~50 columns across nested tables: the planner reads the whole document, columnar storage
-- would mean a brittle change process for v1. The ticket's PR documents this trade-off.
-- last_review_prompt_at supports the behavioural-drift nudge that lands in the C-B-046 follow-up;
-- this migration ships the column so that ticket has somewhere to write.

CREATE TABLE preference_lifestyle_config (
    id                       uuid        PRIMARY KEY,
    user_id                  uuid        NOT NULL UNIQUE,
    document                 jsonb       NOT NULL,
    last_review_prompt_at    timestamptz,
    optimistic_version       bigint      NOT NULL DEFAULT 0,
    created_at               timestamptz NOT NULL,
    updated_at               timestamptz NOT NULL
);
-- Hot read: planner / soft-bundle composer fetch by user_id for every plan generation.
CREATE UNIQUE INDEX idx_pref_lifestyle_config_user
    ON preference_lifestyle_config (user_id);
