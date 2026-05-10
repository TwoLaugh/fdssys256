-- Nutrition module — 01b daily activity log. One row per (user_id, on_date) — last write wins.
-- No version column per LLD V20260502120300; upserts via JPA find + save.

CREATE TABLE nutrition_daily_activity_log (
    id              uuid PRIMARY KEY,
    user_id         uuid NOT NULL,
    on_date         date NOT NULL,
    activity_level  varchar(24) NOT NULL,
    notes           varchar(255),
    created_at      timestamptz NOT NULL,
    UNIQUE (user_id, on_date)
);

CREATE INDEX idx_nutr_daily_activity_user_date
    ON nutrition_daily_activity_log (user_id, on_date DESC);
