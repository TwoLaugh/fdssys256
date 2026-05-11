-- Provisions module — 01d supplier-products aggregate.
-- See lld/provisions.md §V20260502120300. One row per (supplier, product_id) — UNIQUE enforces this.
-- 'substitution_history' is the only JSONB column: append-only, read whole. The @Version column
-- guards JSONB-append concurrency (two appenders collide on optimistic lock, the loser retries).
--
-- idx_prov_supplier_products_mapping_key backs cross-module batch reads (getSupplierProductsByMappingKeys).
-- idx_prov_supplier_products_last_checked backs the deferred staleness sweep (provisions-01j).

CREATE TABLE provision_supplier_products (
    id                       uuid          PRIMARY KEY,
    product_id               varchar(128)  NOT NULL,
    supplier                 varchar(32)   NOT NULL,
    name                     varchar(255)  NOT NULL,
    price                    numeric(8,2),
    price_per_unit           numeric(8,4),
    unit                     varchar(16),
    pack_size_g              integer,
    pack_size_unit           varchar(16),
    category                 varchar(64),
    clubcard_price           numeric(8,2),
    last_checked             date          NOT NULL,
    substitution_history     jsonb         NOT NULL DEFAULT '[]'::jsonb,
    ingredient_mapping_key   varchar(128),
    version                  bigint        NOT NULL DEFAULT 0,
    created_at               timestamptz   NOT NULL,
    updated_at               timestamptz   NOT NULL,
    CONSTRAINT uq_prov_supplier_products UNIQUE (supplier, product_id)
);
CREATE INDEX idx_prov_supplier_products_mapping_key
    ON provision_supplier_products (ingredient_mapping_key);
CREATE INDEX idx_prov_supplier_products_last_checked
    ON provision_supplier_products (last_checked);
