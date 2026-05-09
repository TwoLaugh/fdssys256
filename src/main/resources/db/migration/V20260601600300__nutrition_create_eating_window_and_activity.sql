-- Nutrition module — 01a eating-window (one-to-one) and activity-adjustment children.
-- Excludes nutrition_daily_activity_log — that ships in 01b.
-- nutrition_eating_window: at most one row per targets row (UNIQUE on targets_id).
-- nutrition_activity_adjustment: one row per (targets_id, activity_level).

CREATE TABLE nutrition_eating_window (
    id              uuid          PRIMARY KEY,
    targets_id      uuid          NOT NULL UNIQUE REFERENCES nutrition_targets(id) ON DELETE CASCADE,
    enabled         boolean       NOT NULL DEFAULT false,
    window_start    time,
    window_end      time,
    notes           varchar(255)
);

CREATE TABLE nutrition_activity_adjustment (
    id                 uuid          PRIMARY KEY,
    targets_id         uuid          NOT NULL REFERENCES nutrition_targets(id) ON DELETE CASCADE,
    activity_level     varchar(24)   NOT NULL,
    calorie_modifier   integer       NOT NULL DEFAULT 0,
    carb_modifier_g    integer       NOT NULL DEFAULT 0,
    CONSTRAINT uq_nutrition_activity_level UNIQUE (targets_id, activity_level)
);

CREATE INDEX idx_nutrition_activity_targets
    ON nutrition_activity_adjustment (targets_id);
