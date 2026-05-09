-- Provisions module — 01a inventory aggregate.
-- See lld/provisions.md §V20260502120000. Single-table aggregate, discriminated by
-- tracking_mode ∈ {quantity, status}. Equipment, budget, supplier_products, waste_log all
-- defer to provisions-01b/01c/01d/01e — only the inventory table lands here.
--
-- All enum-like columns store lower-case values matching the JPA enum constant names
-- (StorageLocation.FRIDGE → 'FRIDGE') — Hibernate stores @Enumerated(EnumType.STRING) in
-- the constant name's exact case, so the CHECK literals below mirror that.

CREATE TABLE provision_inventory (
    id                       uuid          PRIMARY KEY,
    user_id                  uuid          NOT NULL,
    name                     varchar(128)  NOT NULL,
    category                 varchar(64)   NOT NULL,
    storage_location         varchar(16)   NOT NULL,                       -- FRIDGE | FREEZER | CUPBOARD | SPICE_RACK
    tracking_mode            varchar(16)   NOT NULL,                       -- QUANTITY | STATUS
    -- Quantity-tracked (nullable when status-tracked)
    quantity                 numeric(10,3),
    unit                     varchar(16),
    cost_paid                numeric(8,2),
    -- Status-tracked (nullable when quantity-tracked)
    status                   varchar(16),                                  -- STOCKED | LOW | OUT
    is_staple                boolean       NOT NULL DEFAULT false,
    -- Common
    expiry_date              date,
    ingredient_mapping_key   varchar(128),
    notes                    varchar(255),
    -- Provenance and lifecycle
    source                   varchar(16)   NOT NULL,                       -- TESCO_ORDER | OTHER_SHOP | MANUAL_ADD | BATCH_COOK | GIFT
    source_ref               varchar(128),
    item_status              varchar(16)   NOT NULL DEFAULT 'ACTIVE',      -- ACTIVE | EXHAUSTED | SPOILED | WASTED
    -- Freezer-only (nullable otherwise)
    frozen_at                date,
    max_freeze_weeks         integer,
    defrost_method           varchar(32),
    defrost_lead_time_hours  integer,
    source_recipe_id         uuid,                                         -- soft FK; cross-module ID only
    version                  bigint        NOT NULL DEFAULT 0,
    created_at               timestamptz   NOT NULL,
    updated_at               timestamptz   NOT NULL,
    CONSTRAINT chk_tracking_quantity CHECK (tracking_mode <> 'QUANTITY' OR (quantity IS NOT NULL AND unit IS NOT NULL)),
    CONSTRAINT chk_tracking_status   CHECK (tracking_mode <> 'STATUS'   OR status IS NOT NULL),
    CONSTRAINT chk_quantity_nonneg   CHECK (quantity IS NULL OR quantity >= 0)
);

-- Hot read: planner / query service "what's in the house".
CREATE INDEX idx_prov_inventory_user_status
    ON provision_inventory (user_id, item_status);

-- Expiry-driven scheduling and ItemNearingExpiryEvent sweep (lands in 01k).
CREATE INDEX idx_prov_inventory_user_expiry
    ON provision_inventory (user_id, expiry_date)
    WHERE item_status = 'ACTIVE' AND expiry_date IS NOT NULL;

-- Recipe → inventory match during cook-event deduction; planner's stock-utilisation scoring (01g).
CREATE INDEX idx_prov_inventory_user_mapping_key
    ON provision_inventory (user_id, ingredient_mapping_key)
    WHERE item_status = 'ACTIVE' AND ingredient_mapping_key IS NOT NULL;

-- Staple replenishment list (01i).
CREATE INDEX idx_prov_inventory_user_staples
    ON provision_inventory (user_id, status)
    WHERE is_staple = true;

-- Idempotency for grocery imports (01h).
CREATE INDEX idx_prov_inventory_source_ref
    ON provision_inventory (user_id, source, source_ref)
    WHERE source_ref IS NOT NULL;
