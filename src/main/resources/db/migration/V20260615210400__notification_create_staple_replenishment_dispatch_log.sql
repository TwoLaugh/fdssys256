-- notification/01b — StapleReplenishmentScanner idempotency log.
-- One row per (user_id, scan_date): the unique key fences a re-fire within the same weekly scan day
-- for the same user. No cross-module FK — user_id is an opaque UUID.
CREATE TABLE staple_replenishment_dispatch_log (
    id          uuid PRIMARY KEY,
    user_id     uuid NOT NULL,
    scan_date   date NOT NULL,
    fired_at    timestamptz NOT NULL,
    item_count  integer NOT NULL,
    UNIQUE (user_id, scan_date)
);
CREATE INDEX idx_staple_replenishment_dispatch_log_fired_at ON staple_replenishment_dispatch_log (fired_at);
