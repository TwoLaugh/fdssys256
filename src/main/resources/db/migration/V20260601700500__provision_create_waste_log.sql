-- Provisions module — 01e waste log (append-only).
-- See lld/provisions.md §V20260502120400 lines 210-221.
-- No version column, no updated_at: corrections create new rows (LLD line 258 / line 660).
-- inventory_item_id is intentionally NOT FK-constrained — soft FK so inventory deletions don't
-- cascade-delete waste history (same pattern as provision_inventory_audit.inventory_item_id).

CREATE TABLE provision_waste_log (
    id                   uuid PRIMARY KEY,
    user_id              uuid          NOT NULL,
    inventory_item_id    uuid,
    item_name            varchar(128)  NOT NULL,
    quantity             numeric(10,3),
    unit                 varchar(16),
    reason               varchar(32)   NOT NULL,
    cost_estimate        numeric(8,2),
    occurred_on          date          NOT NULL,
    notes                varchar(255),
    created_at           timestamptz   NOT NULL
);
CREATE INDEX idx_prov_waste_log_user_date   ON provision_waste_log (user_id, occurred_on DESC);
CREATE INDEX idx_prov_waste_log_user_reason ON provision_waste_log (user_id, reason);
