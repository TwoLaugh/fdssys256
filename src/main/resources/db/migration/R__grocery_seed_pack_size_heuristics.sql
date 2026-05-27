-- Grocery module — 01a repeatable seed for grocery_pack_size_heuristics.
-- See lld/grocery.md §R__grocery_seed_pack_size_heuristics (lines 347-349). Repeatable so additions
-- don't pollute the version sequence. The actual reference data is filled in over time; 01a ships a
-- v1 STARTER SET — enough rows that PackSizeOptimiserTest (01b) and the calculator have fixtures
-- (flour 500g/1kg/1.5kg; eggs 6/12; milk 1pt/2pt/4pt; plus ~10 more common categories).
--
-- Repeatable migrations re-run whenever their checksum changes. This file is idempotent by
-- TRUNCATE-then-INSERT with deterministic literal UUIDs — re-running yields the exact same rows,
-- never duplicates. grocery_pack_size_heuristics has no inbound FKs, so TRUNCATE is safe.
--
-- pack_unit is one of 'g' | 'ml' | 'items'. rank: 1 = smallest typical pack, ascending.

TRUNCATE TABLE grocery_pack_size_heuristics;

INSERT INTO grocery_pack_size_heuristics
    (id, ingredient_mapping_key, category, pack_size_g, pack_count, pack_unit, rank, notes)
VALUES
    -- flour (by mapping key) — 500g / 1kg / 1.5kg
    ('a0000000-0000-4000-8000-000000000001', 'flour',         NULL, 500,  NULL, 'g',     1, 'small bag'),
    ('a0000000-0000-4000-8000-000000000002', 'flour',         NULL, 1000, NULL, 'g',     2, 'standard bag'),
    ('a0000000-0000-4000-8000-000000000003', 'flour',         NULL, 1500, NULL, 'g',     3, 'large bag'),
    -- eggs (by mapping key) — 6 / 12 (count-based)
    ('a0000000-0000-4000-8000-000000000004', 'eggs',          NULL, NULL, 6,    'items', 1, 'half dozen'),
    ('a0000000-0000-4000-8000-000000000005', 'eggs',          NULL, NULL, 12,   'items', 2, 'dozen'),
    -- milk (by mapping key) — 1pt (568ml) / 2pt (1136ml) / 4pt (2272ml)
    ('a0000000-0000-4000-8000-000000000006', 'milk',          NULL, 568,  NULL, 'ml',    1, '1 pint'),
    ('a0000000-0000-4000-8000-000000000007', 'milk',          NULL, 1136, NULL, 'ml',    2, '2 pint'),
    ('a0000000-0000-4000-8000-000000000008', 'milk',          NULL, 2272, NULL, 'ml',    3, '4 pint'),
    -- butter (by mapping key) — 250g / 500g
    ('a0000000-0000-4000-8000-000000000009', 'butter',        NULL, 250,  NULL, 'g',     1, 'standard block'),
    ('a0000000-0000-4000-8000-00000000000a', 'butter',        NULL, 500,  NULL, 'g',     2, 'large block'),
    -- sugar (by mapping key) — 500g / 1kg
    ('a0000000-0000-4000-8000-00000000000b', 'sugar',         NULL, 500,  NULL, 'g',     1, 'small bag'),
    ('a0000000-0000-4000-8000-00000000000c', 'sugar',         NULL, 1000, NULL, 'g',     2, 'standard bag'),
    -- rice (by mapping key) — 500g / 1kg / 2kg
    ('a0000000-0000-4000-8000-00000000000d', 'rice',          NULL, 500,  NULL, 'g',     1, 'small bag'),
    ('a0000000-0000-4000-8000-00000000000e', 'rice',          NULL, 1000, NULL, 'g',     2, 'standard bag'),
    ('a0000000-0000-4000-8000-00000000000f', 'rice',          NULL, 2000, NULL, 'g',     3, 'large bag'),
    -- pasta (by mapping key) — 500g / 1kg
    ('a0000000-0000-4000-8000-000000000010', 'pasta',         NULL, 500,  NULL, 'g',     1, 'standard bag'),
    ('a0000000-0000-4000-8000-000000000011', 'pasta',         NULL, 1000, NULL, 'g',     2, 'large bag'),
    -- chicken_breast (by mapping key) — 300g / 600g / 1kg
    ('a0000000-0000-4000-8000-000000000012', 'chicken_breast', NULL, 300, NULL, 'g',     1, 'small tray'),
    ('a0000000-0000-4000-8000-000000000013', 'chicken_breast', NULL, 600, NULL, 'g',     2, 'standard tray'),
    ('a0000000-0000-4000-8000-000000000014', 'chicken_breast', NULL, 1000, NULL, 'g',    3, 'family tray'),
    -- onion (by mapping key) — single / 1kg net
    ('a0000000-0000-4000-8000-000000000015', 'onion',         NULL, NULL, 1,    'items', 1, 'loose single'),
    ('a0000000-0000-4000-8000-000000000016', 'onion',         NULL, 1000, NULL, 'g',     2, 'net bag'),
    -- canned_tomatoes (by mapping key) — 400g can
    ('a0000000-0000-4000-8000-000000000017', 'canned_tomatoes', NULL, 400, NULL, 'g',    1, 'standard can'),
    -- category fallbacks (ingredient_mapping_key NULL, matched by category)
    ('b0000000-0000-4000-8000-000000000001', NULL, 'dairy',     NULL, 1,    'items', 1, 'category fallback: single item'),
    ('b0000000-0000-4000-8000-000000000002', NULL, 'baking',    500,  NULL, 'g',     1, 'category fallback: 500g'),
    ('b0000000-0000-4000-8000-000000000003', NULL, 'produce',   NULL, 1,    'items', 1, 'category fallback: single item'),
    ('b0000000-0000-4000-8000-000000000004', NULL, 'meat',      300,  NULL, 'g',     1, 'category fallback: small tray'),
    ('b0000000-0000-4000-8000-000000000005', NULL, 'pantry',    400,  NULL, 'g',     1, 'category fallback: standard pack');
