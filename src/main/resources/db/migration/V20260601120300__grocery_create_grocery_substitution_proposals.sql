-- Grocery module — 01a Tier 3 substitution proposals.
-- See lld/grocery.md §V20260601120300 (lines 248-275). The provider proposes substitutions at
-- checkout; the user approves each (auto-accept forbidden). The `(grocery_order_id, proposal_status)`
-- index gates reconciliation — an order cannot move to RECONCILED while any proposal is
-- PENDING_USER_REVIEW.
--
-- `proposal_status` stores the UPPERCASE @Enumerated(EnumType.STRING) constant name. `raw_payload`
-- is JSONB (opaque diagnostic blob, for the UNPARSED "DOM differs from expected" case).

CREATE TABLE grocery_substitution_proposals (
    id                          uuid PRIMARY KEY,
    grocery_order_id            uuid NOT NULL REFERENCES grocery_orders(id) ON DELETE CASCADE,
    grocery_order_line_id       uuid REFERENCES grocery_order_lines(id) ON DELETE SET NULL,
    original_product_id         varchar(128) NOT NULL,
    original_display_name       varchar(255) NOT NULL,
    original_ingredient_mapping_key varchar(128),
    substitute_product_id       varchar(128) NOT NULL,
    substitute_display_name     varchar(255) NOT NULL,
    substitute_ingredient_mapping_key varchar(128),
    substitute_quantity         numeric(10,3),
    substitute_unit             varchar(16),
    substitute_unit_pence       integer,
    reason                      varchar(255),                     -- provider-supplied: "out of stock", "size up", ...
    proposal_status             varchar(32) NOT NULL,             -- PENDING_USER_REVIEW | ACCEPTED | REJECTED | UNPARSED
    raw_payload                 jsonb,                            -- the provider's raw substitution record, for unparsed cases
    resolved_at                 timestamptz,
    resolved_by_user_id         uuid,
    version                     bigint NOT NULL DEFAULT 0,         -- @Version: stale-resolve race protection (LLD line 364)
    created_at                  timestamptz NOT NULL,
    updated_at                  timestamptz NOT NULL
);
-- "what's outstanding for this order" — gates reconciliation
CREATE INDEX idx_grocery_subs_order_status
    ON grocery_substitution_proposals (grocery_order_id, proposal_status);
