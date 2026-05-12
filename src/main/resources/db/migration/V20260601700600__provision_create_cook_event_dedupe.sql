-- Provisions module — 01g cook-event idempotency table.
-- See lld/provisions.md §Flow 1 / Idempotency line 620.
-- Daily sweep deletes rows older than 24h — bounded storage.

CREATE TABLE provision_cook_event_dedupe (
    meal_slot_id  uuid          NOT NULL,
    dedupe_key    varchar(64)   NOT NULL,
    created_at    timestamptz   NOT NULL,
    PRIMARY KEY (meal_slot_id, dedupe_key)
);

-- Sweep query: DELETE WHERE created_at < now() - INTERVAL '24 hours'.
CREATE INDEX idx_provision_cook_event_dedupe_created_at
    ON provision_cook_event_dedupe (created_at);
