-- Grocery module — 01a Tier 3 grocery-order aggregate (parent + lines, one migration).
-- See lld/grocery.md §V20260601120100 (lines 152-216). Explicit top-down lifecycle state machine.
--
-- DIVERGENCE (ticket 01a, locked): the LLD lists `grocery_order_lines` under the same heading as
-- `grocery_orders` with no dedicated migration file — 01a folds the child table into this one
-- migration (the order aggregate = parent + child lines = one concern).
--
-- `automation_failure_log` is JSONB (append-only diagnostic array, read whole). `status` /
-- `line_status` store the UPPERCASE @Enumerated(EnumType.STRING) constant name.

CREATE TABLE grocery_orders (
    id                          uuid PRIMARY KEY,
    user_id                     uuid NOT NULL,
    household_id                uuid,
    shopping_list_id            uuid NOT NULL REFERENCES shopping_lists(id),
    provider_key                varchar(32) NOT NULL,             -- "tesco", "sainsburys", ...
    provider_order_id           varchar(128),                     -- nullable until placed
    status                      varchar(32) NOT NULL,             -- DRAFT | QUOTED | PLACED | PLACED_PARTIAL | AWAITING_USER_CONFIRMATION | CONFIRMED | DELIVERED | RECONCILED | CANCELLED | ARCHIVED | PROVIDER_UNAVAILABLE
    status_reason               varchar(255),                     -- free-text on terminal/error states
    quoted_total_pence          integer,
    confirmed_total_pence       integer,
    paid_total_pence            integer,
    currency                    varchar(3) NOT NULL DEFAULT 'GBP',
    delivery_slot_start         timestamptz,
    delivery_slot_end           timestamptz,
    confirm_link                text,                             -- the user-confirmation URL the provider exposed
    placed_at                   timestamptz,
    confirmed_at                timestamptz,
    delivered_at                timestamptz,
    reconciled_at               timestamptz,
    cancelled_at                timestamptz,
    cancel_reason               varchar(64),
    last_status_check_at        timestamptz,
    automation_failure_log      jsonb NOT NULL DEFAULT '[]'::jsonb, -- array of {step, message, occurredAt}
    trace_id                    uuid NOT NULL,
    version                     bigint NOT NULL DEFAULT 0,
    created_at                  timestamptz NOT NULL,
    updated_at                  timestamptz NOT NULL
);
-- look up the order from a provider callback / status webhook (when they exist)
CREATE INDEX idx_grocery_orders_provider_order
    ON grocery_orders (provider_key, provider_order_id) WHERE provider_order_id IS NOT NULL;
-- list view "my orders" excluding archived
CREATE INDEX idx_grocery_orders_user_status_created
    ON grocery_orders (user_id, status, created_at DESC);
-- single-flight per user-week (advisory lock keyed off the active shopping list)
CREATE INDEX idx_grocery_orders_shopping_list ON grocery_orders (shopping_list_id);

CREATE TABLE grocery_order_lines (
    id                          uuid PRIMARY KEY,
    grocery_order_id            uuid NOT NULL REFERENCES grocery_orders(id) ON DELETE CASCADE,
    shopping_list_line_id       uuid REFERENCES shopping_list_lines(id) ON DELETE SET NULL,
    provider_product_id         varchar(128),                     -- supplier SKU
    ingredient_mapping_key      varchar(128) NOT NULL,
    display_name                varchar(255) NOT NULL,
    quantity_requested          numeric(10,3) NOT NULL,
    quantity_unit               varchar(16) NOT NULL,
    pack_size_g                 integer,
    pack_count_requested        integer,
    pack_count_delivered        integer,
    quoted_unit_pence           integer,
    confirmed_unit_pence        integer,
    paid_unit_pence             integer,
    line_status                 varchar(16) NOT NULL,             -- QUEUED | ADDED | ADDED_PARTIAL | UNAVAILABLE | SUBSTITUTED | DELIVERED | REJECTED
    note                        varchar(255),
    created_at                  timestamptz NOT NULL,
    updated_at                  timestamptz NOT NULL
);
CREATE INDEX idx_grocery_order_lines_order ON grocery_order_lines (grocery_order_id);
