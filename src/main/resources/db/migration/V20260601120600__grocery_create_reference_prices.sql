-- Grocery module — 01c Tier 4 reference prices (cold-start cost source).
-- NOT in 01a's migration list — this table is owned by ticket grocery-01c. Extends the
-- V20260601120000..120500 grocery sequence; 120600 confirmed unclaimed by any module.
--
-- The ReferencePriceSource SPI's cold-start estimate per normalised ingredient_mapping_key. Rows
-- are seeded by the repeatable R__grocery_seed_reference_prices.sql migration from a hand-authored,
-- attributed Open Food Facts "Open Prices" STARTER SET (rolled up per-product → per-mapping-key by
-- the ReferenceProductMapper). reference_confidence is a low fixed value (e.g. 0.200) — a reference
-- estimate is never as good as a real household observation. attribution carries the ODbL string on
-- every row (the data is ODbL; redistribution requires attribution + share-alike — owner signed off,
-- see the seed-file header NOTICE).

CREATE TABLE grocery_reference_prices (
    id                       uuid PRIMARY KEY,
    ingredient_mapping_key   varchar(128) NOT NULL,    -- normalised (lowercase/trim); the rolled-up key
    reference_unit_pence     integer NOT NULL,         -- per normalised unit (per 100g / per litre / per item)
    unit                     varchar(16) NOT NULL,
    reference_confidence     numeric(4,3) NOT NULL,    -- low fixed value (e.g. 0.200)
    source_as_of             date NOT NULL,            -- snapshot date
    attribution              varchar(255) NOT NULL,    -- ODbL attribution string
    sample_products          integer,                  -- how many Open Prices products rolled up
    created_at               timestamptz NOT NULL
);
-- one reference estimate per mapping key (the mapper rolls many products into one row)
CREATE UNIQUE INDEX idx_grocery_ref_prices_key ON grocery_reference_prices (ingredient_mapping_key);
