-- notification/01b — PrepReminderScanner idempotency log.
-- One row per (slot_id, prep_step_at_time): the unique key fences a re-fire while the current time
-- stays inside the 15-minute fire window. No cross-module FK — slot_id is an opaque UUID.
CREATE TABLE prep_reminder_dispatch_log (
    id                uuid PRIMARY KEY,
    slot_id           uuid NOT NULL,
    prep_step_at_time timestamptz NOT NULL,
    user_id           uuid NOT NULL,
    fired_at          timestamptz NOT NULL,
    UNIQUE (slot_id, prep_step_at_time)
);
CREATE INDEX idx_prep_reminder_dispatch_log_fired_at ON prep_reminder_dispatch_log (fired_at);
