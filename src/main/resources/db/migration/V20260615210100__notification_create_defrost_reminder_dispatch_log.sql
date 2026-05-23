-- notification/01b — DefrostReminderScanner idempotency log.
-- One row per (slot_id, defrost_target_time): the unique key fences a re-fire while the current time
-- stays inside the 1-hour fire window. No cross-module FK — slot_id is an opaque UUID.
CREATE TABLE defrost_reminder_dispatch_log (
    id                  uuid PRIMARY KEY,
    slot_id             uuid NOT NULL,
    defrost_target_time timestamptz NOT NULL,
    user_id             uuid NOT NULL,
    fired_at            timestamptz NOT NULL,
    UNIQUE (slot_id, defrost_target_time)
);
CREATE INDEX idx_defrost_reminder_dispatch_log_fired_at ON defrost_reminder_dispatch_log (fired_at);
