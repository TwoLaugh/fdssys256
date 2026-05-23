-- notification/01b — NutritionAlertScanner idempotency log.
-- One row per (user_id, alert_date, nutrient_key): the unique key fences a second alert for the same
-- nutrient on the same day. No cross-module FK — user_id is an opaque UUID.
CREATE TABLE nutrition_alert_dispatch_log (
    id            uuid PRIMARY KEY,
    user_id       uuid NOT NULL,
    alert_date    date NOT NULL,
    nutrient_key  varchar(32) NOT NULL,
    fired_at      timestamptz NOT NULL,
    UNIQUE (user_id, alert_date, nutrient_key)
);
CREATE INDEX idx_nutrition_alert_dispatch_log_fired_at ON nutrition_alert_dispatch_log (fired_at);
