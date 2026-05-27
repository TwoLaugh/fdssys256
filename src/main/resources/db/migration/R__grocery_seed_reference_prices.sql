-- =============================================================================================
-- Grocery module — 01c repeatable seed for grocery_reference_prices (ReferencePriceSource).
--
-- ODbL NOTICE / ATTRIBUTION
-- -------------------------
-- The reference prices below are derived from the Open Food Facts "Open Prices" database
-- (https://prices.openfoodfacts.org), which is made available under the Open Database Licence
-- (ODbL v1.0 — https://opendatacommons.org/licenses/odbl/1-0/). Redistribution carries an
-- attribution + share-alike obligation, which the product owner has signed off for v1 (the price
-- data is NOT treated as a proprietary moat — revisit share-alike if the reference-price database is
-- ever commercialised as a core asset). EVERY row carries the attribution string; this header is the
-- file-level NOTICE. The "about" surface in the app additionally carries the attribution.
--
-- HAND-AUTHORED STARTER SET — NOT a real Open Prices dump.
-- --------------------------------------------------------
-- These rows are a small, deterministic, curated starter set covering the ingredients the grocery
-- and recipe e2e fixtures exercise (the chicken-and-rice-bowl import fixture in
-- E2eRecipeFixtureController: "chicken breast", "white rice", "broccoli", "olive oil", "salt") plus a
-- handful of common-pantry keys that the pack-size heuristics already cover. We did NOT fetch or
-- commit the full external Open Prices snapshot — the rows are representative UK retail unit prices
-- (in pence per normalised unit) chosen so GROC-03/GROC-30 surface an honest low-confidence number.
-- The ReferenceProductMapper rolls per-product Open Prices rows up to these per-mapping-key
-- estimates; sample_products records how many products notionally rolled up.
--
-- TODO(reference-data): expand beyond the e2e starter set — drop in the full Open Prices snapshot
-- (rolled per-mapping-key by ReferenceProductMapper) here. The schema + the ODbL NOTICE above are the
-- stable carrier; adding rows below does not change the table or the SPI contract.
--
-- KEY NORMALISATION: keys are stored lowercase/trim/collapsed-whitespace to match
-- IngredientMappingKeys.normalise() — note multi-word keys keep the internal SPACE ("chicken
-- breast", NOT "chicken_breast"), matching how recipe/nutrition store mapping keys.
--
-- UNIT: "per_100g" (weight), "per_litre" (liquid), or "per_item" (count). reference_unit_pence is the
-- price for ONE such unit. reference_confidence is a flat 0.200 (low — a reference is never as good
-- as a real observation).
--
-- Repeatable migration: re-runs whenever its checksum changes. Idempotent by TRUNCATE-then-INSERT
-- with deterministic literal UUIDs. grocery_reference_prices has no inbound FKs, so TRUNCATE is safe.
-- =============================================================================================

TRUNCATE TABLE grocery_reference_prices;

INSERT INTO grocery_reference_prices
    (id, ingredient_mapping_key, reference_unit_pence, unit, reference_confidence, source_as_of,
     attribution, sample_products, created_at)
VALUES
    -- ---- e2e chicken-and-rice-bowl fixture keys (GROC-03 / GROC-30) ----
    ('c0000000-0000-4000-8000-000000000001', 'chicken breast', 110, 'per_100g', 0.200, DATE '2026-01-01',
     'Open Food Facts Open Prices, ODbL v1.0 (opendatacommons.org/licenses/odbl/1-0)', 12, now()),
    ('c0000000-0000-4000-8000-000000000002', 'white rice', 18, 'per_100g', 0.200, DATE '2026-01-01',
     'Open Food Facts Open Prices, ODbL v1.0 (opendatacommons.org/licenses/odbl/1-0)', 9, now()),
    ('c0000000-0000-4000-8000-000000000003', 'broccoli', 30, 'per_100g', 0.200, DATE '2026-01-01',
     'Open Food Facts Open Prices, ODbL v1.0 (opendatacommons.org/licenses/odbl/1-0)', 7, now()),
    ('c0000000-0000-4000-8000-000000000004', 'olive oil', 70, 'per_100g', 0.200, DATE '2026-01-01',
     'Open Food Facts Open Prices, ODbL v1.0 (opendatacommons.org/licenses/odbl/1-0)', 11, now()),
    ('c0000000-0000-4000-8000-000000000005', 'salt', 8, 'per_100g', 0.200, DATE '2026-01-01',
     'Open Food Facts Open Prices, ODbL v1.0 (opendatacommons.org/licenses/odbl/1-0)', 5, now()),
    -- ---- common-pantry keys (parity with the pack-size-heuristic starter set) ----
    ('c0000000-0000-4000-8000-000000000006', 'flour', 9, 'per_100g', 0.200, DATE '2026-01-01',
     'Open Food Facts Open Prices, ODbL v1.0 (opendatacommons.org/licenses/odbl/1-0)', 8, now()),
    ('c0000000-0000-4000-8000-000000000007', 'rice', 16, 'per_100g', 0.200, DATE '2026-01-01',
     'Open Food Facts Open Prices, ODbL v1.0 (opendatacommons.org/licenses/odbl/1-0)', 10, now()),
    ('c0000000-0000-4000-8000-000000000008', 'pasta', 12, 'per_100g', 0.200, DATE '2026-01-01',
     'Open Food Facts Open Prices, ODbL v1.0 (opendatacommons.org/licenses/odbl/1-0)', 9, now()),
    ('c0000000-0000-4000-8000-000000000009', 'sugar', 10, 'per_100g', 0.200, DATE '2026-01-01',
     'Open Food Facts Open Prices, ODbL v1.0 (opendatacommons.org/licenses/odbl/1-0)', 6, now()),
    ('c0000000-0000-4000-8000-00000000000a', 'butter', 90, 'per_100g', 0.200, DATE '2026-01-01',
     'Open Food Facts Open Prices, ODbL v1.0 (opendatacommons.org/licenses/odbl/1-0)', 7, now()),
    ('c0000000-0000-4000-8000-00000000000b', 'milk', 16, 'per_litre', 0.200, DATE '2026-01-01',
     'Open Food Facts Open Prices, ODbL v1.0 (opendatacommons.org/licenses/odbl/1-0)', 8, now()),
    ('c0000000-0000-4000-8000-00000000000c', 'eggs', 22, 'per_item', 0.200, DATE '2026-01-01',
     'Open Food Facts Open Prices, ODbL v1.0 (opendatacommons.org/licenses/odbl/1-0)', 6, now()),
    ('c0000000-0000-4000-8000-00000000000d', 'onion', 12, 'per_item', 0.200, DATE '2026-01-01',
     'Open Food Facts Open Prices, ODbL v1.0 (opendatacommons.org/licenses/odbl/1-0)', 5, now()),
    ('c0000000-0000-4000-8000-00000000000e', 'canned tomatoes', 55, 'per_item', 0.200, DATE '2026-01-01',
     'Open Food Facts Open Prices, ODbL v1.0 (opendatacommons.org/licenses/odbl/1-0)', 4, now()),
    ('c0000000-0000-4000-8000-00000000000f', 'chicken_breast', 110, 'per_100g', 0.200, DATE '2026-01-01',
     'Open Food Facts Open Prices, ODbL v1.0 (opendatacommons.org/licenses/odbl/1-0)', 12, now());
