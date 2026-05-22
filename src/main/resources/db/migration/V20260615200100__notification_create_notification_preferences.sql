CREATE TABLE notification_preferences (
    id                       uuid PRIMARY KEY,
    user_id                  uuid NOT NULL UNIQUE,
    enabled_kinds            jsonb NOT NULL,                          -- map<NotificationKind, boolean>
    quiet_hours_enabled      boolean NOT NULL DEFAULT false,
    quiet_hours_start        time,                                    -- local time in user's tz
    quiet_hours_end          time,                                    -- may wrap midnight
    timezone                 varchar(64) NOT NULL DEFAULT 'Europe/London',
    debounce_window_minutes  integer NOT NULL DEFAULT 30,
    optimistic_version       bigint NOT NULL DEFAULT 0,
    created_at               timestamptz NOT NULL,
    updated_at               timestamptz NOT NULL
);
CREATE UNIQUE INDEX idx_notification_preferences_user ON notification_preferences (user_id);
