-- notification/01b — ExpiryWarningScanner idempotency log.
-- One row per (user_id, scan_date): the unique key fences a second same-day scan for the same user
-- so the expiry warning fires at most once per user per day. No FK to other modules' tables per the
-- module-boundary convention (lld/notification.md:69) — user_id is an opaque UUID.
CREATE TABLE expiry_warning_dispatch_log (
    id          uuid PRIMARY KEY,
    user_id     uuid NOT NULL,
    scan_date   date NOT NULL,
    fired_at    timestamptz NOT NULL,
    item_count  integer NOT NULL,
    UNIQUE (user_id, scan_date)
);
CREATE INDEX idx_expiry_warning_dispatch_log_fired_at ON expiry_warning_dispatch_log (fired_at);
