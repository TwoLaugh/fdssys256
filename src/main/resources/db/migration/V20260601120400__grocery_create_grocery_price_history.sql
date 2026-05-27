-- Grocery module — 01a Tier 4 price history.
-- See lld/grocery.md §V20260601120400 (lines 279-317). Append-only, household-scoped
-- ("Same shopper, same prices — the household shares price history"). `household_id` nullable for
-- single-user mode where `user_id` doubles as the household scope until household is configured.
--
-- `source` stores the UPPERCASE @Enumerated(EnumType.STRING) constant name. No `version` /
-- `updated_at` columns — the row is written once, never updated (PriceObservation is append-only:
-- no @Version, no @LastModifiedDate on the entity).

CREATE TABLE grocery_price_history (
    id                          uuid PRIMARY KEY,
    user_id                     uuid NOT NULL,                    -- the observer; for audit
    household_id                uuid,                             -- aggregation scope
    ingredient_mapping_key      varchar(128) NOT NULL,
    store                       varchar(64) NOT NULL,             -- "tesco_online", "sainsburys_online", "tesco_metro_high_street", "manual"
    provider_product_id         varchar(128),                     -- supplier SKU when known
    pack_size_g                 integer,
    pack_count                  integer,
    quantity                    numeric(10,3),
    quantity_unit               varchar(16),
    paid_unit_pence             integer,                          -- normalised to a unit (per 100g, per litre, per item) — see notes
    paid_total_pence            integer,
    currency                    varchar(3) NOT NULL DEFAULT 'GBP',
    source                      varchar(24) NOT NULL,             -- PAID | QUOTE | MANUAL | MANUAL_ESTIMATED | INFLATION_INDEXED
    confidence_weight           numeric(4,3) NOT NULL,            -- 0..1, source-weighted at write time per GroceryConfig
    grocery_order_id            uuid,
    shopping_list_line_id       uuid,
    observed_at                 timestamptz NOT NULL,
    note                        varchar(255),
    created_at                  timestamptz NOT NULL
);
-- aggregation hot path: per-household, per-mapping-key, recency-ordered
CREATE INDEX idx_grocery_price_hh_key_observed
    ON grocery_price_history (household_id, ingredient_mapping_key, observed_at DESC);
-- per-store breakdown for cross-store comparison
CREATE INDEX idx_grocery_price_hh_key_store_observed
    ON grocery_price_history (household_id, ingredient_mapping_key, store, observed_at DESC);
-- "recent activity for this user" (audit / debug)
CREATE INDEX idx_grocery_price_user_observed
    ON grocery_price_history (user_id, observed_at DESC);
-- "old rows compactable to aggregates" — append-only retention sweep
CREATE INDEX idx_grocery_price_observed ON grocery_price_history (observed_at);
