CREATE TABLE notification_delivery_log (
    id              uuid PRIMARY KEY,
    notification_id uuid NOT NULL REFERENCES notifications(id) ON DELETE CASCADE,
    channel         varchar(32) NOT NULL,    -- IN_APP | PUSH (future) | EMAIL (future)
    outcome         varchar(16) NOT NULL,    -- DELIVERED | SKIPPED | DEFERRED | FAILED
    skip_reason     varchar(64),             -- DISABLED_BY_PREF | QUIET_HOURS | DEDUPED_INTO_BUNDLE | CHANNEL_UNAVAILABLE
    detail          jsonb,                   -- channel-specific
    attempted_at    timestamptz NOT NULL
);
CREATE INDEX idx_delivery_log_notification ON notification_delivery_log (notification_id);
CREATE INDEX idx_delivery_log_channel_attempted ON notification_delivery_log (channel, attempted_at DESC);
