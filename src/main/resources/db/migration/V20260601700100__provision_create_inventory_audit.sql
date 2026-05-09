-- Provisions module — 01a inventory audit log.
-- See lld/provisions.md §V20260502120500. One row per genuinely changed field per
-- create/update. Append-only — no @Version, no updated_at.
--
-- inventory_item_id is a soft FK (no REFERENCES) so retention sweeps (01k) can hard-delete
-- the parent row without a cascade — the audit log keeps history beyond the live aggregate's
-- lifetime.

CREATE TABLE provision_inventory_audit (
    id                       uuid          PRIMARY KEY,
    inventory_item_id        uuid          NOT NULL,
    user_id                  uuid          NOT NULL,
    actor                    varchar(32)   NOT NULL,                       -- USER | COOK_EVENT | GROCERY_IMPORT | NUTRITION_LOGGER | SYSTEM
    actor_user_id            uuid,
    field_changed            varchar(64)   NOT NULL,
    previous_value_json      jsonb         NOT NULL,
    new_value_json           jsonb         NOT NULL,
    occurred_at              timestamptz   NOT NULL
);

-- Audit-log query endpoint (01b ships the GET endpoint; index seeds it now).
CREATE INDEX idx_prov_inventory_audit_item_time
    ON provision_inventory_audit (inventory_item_id, occurred_at DESC);
