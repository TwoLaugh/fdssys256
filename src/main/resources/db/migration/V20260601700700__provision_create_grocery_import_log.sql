-- Provisions module — 01h grocery-import idempotency log.
-- See lld/provisions.md §Flow 2 / Idempotency line 632.
-- One row per accepted grocery order import. The PK enforces "no replays".
-- Retention sweep deferred to a follow-up (rows older than 12 months can be hard-deleted).

CREATE TABLE provision_grocery_import_log (
    user_id       uuid          NOT NULL,
    source        varchar(16)   NOT NULL,      -- tesco_order | other_shop (matches ItemSource enum)
    source_ref    varchar(128)  NOT NULL,      -- the orderRef
    trace_id      uuid,
    processed_at  timestamptz   NOT NULL,
    PRIMARY KEY (user_id, source, source_ref)
);

-- Retention sweep target (a follow-up @Scheduled deletes rows > 12 months).
CREATE INDEX idx_provision_grocery_import_log_processed_at
    ON provision_grocery_import_log (processed_at);
