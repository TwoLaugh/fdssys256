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
    -- --- canonical-name packs (ingredient-normalisation backfill, 2026-06-18) ---
    -- weight/volume staples keyed on the parser's canonical names; demand is converted to grams by
    -- IngredientUnitConverter so these gram packs pack-match cleanly. Count-bought items use 'items'.
    ('c0000000-0000-4000-8000-000000000001', 'salt',              NULL, 500,  NULL, 'g',     1, 'small'),
    ('c0000000-0000-4000-8000-000000000002', 'salt',              NULL, 1000, NULL, 'g',     2, 'standard'),
    ('c0000000-0000-4000-8000-000000000003', 'olive oil',         NULL, 500,  NULL, 'g',     1, '500ml bottle'),
    ('c0000000-0000-4000-8000-000000000004', 'olive oil',         NULL, 1000, NULL, 'g',     2, '1L bottle'),
    ('c0000000-0000-4000-8000-000000000005', 'vegetable oil',     NULL, 1000, NULL, 'g',     1, '1L bottle'),
    ('c0000000-0000-4000-8000-000000000006', 'all-purpose flour', NULL, 1000, NULL, 'g',     1, 'standard bag'),
    ('c0000000-0000-4000-8000-000000000007', 'all-purpose flour', NULL, 1500, NULL, 'g',     2, 'large bag'),
    ('c0000000-0000-4000-8000-000000000008', 'brown sugar',       NULL, 500,  NULL, 'g',     1, 'standard'),
    ('c0000000-0000-4000-8000-000000000009', 'black pepper',      NULL, 50,   NULL, 'g',     1, 'spice jar'),
    ('c0000000-0000-4000-8000-00000000000a', 'black pepper',      NULL, 100,  NULL, 'g',     2, 'large jar'),
    ('c0000000-0000-4000-8000-00000000000b', 'soy sauce',         NULL, 250,  NULL, 'g',     1, 'small bottle'),
    ('c0000000-0000-4000-8000-00000000000c', 'soy sauce',         NULL, 500,  NULL, 'g',     2, 'standard bottle'),
    ('c0000000-0000-4000-8000-00000000000d', 'honey',             NULL, 340,  NULL, 'g',     1, 'standard jar'),
    ('c0000000-0000-4000-8000-00000000000e', 'parmesan cheese',   NULL, 200,  NULL, 'g',     1, 'wedge'),
    ('c0000000-0000-4000-8000-00000000000f', 'cheddar cheese',    NULL, 250,  NULL, 'g',     1, 'block'),
    ('c0000000-0000-4000-8000-000000000010', 'mayonnaise',        NULL, 400,  NULL, 'g',     1, 'jar'),
    ('c0000000-0000-4000-8000-000000000011', 'dijon mustard',     NULL, 215,  NULL, 'g',     1, 'jar'),
    ('c0000000-0000-4000-8000-000000000012', 'lemon juice',       NULL, 250,  NULL, 'g',     1, 'bottle'),
    ('c0000000-0000-4000-8000-000000000013', 'chicken broth',     NULL, 500,  NULL, 'g',     1, 'carton'),
    ('c0000000-0000-4000-8000-000000000014', 'chicken broth',     NULL, 1000, NULL, 'g',     2, 'large carton'),
    ('c0000000-0000-4000-8000-000000000015', 'vinegar',           NULL, 500,  NULL, 'g',     1, 'bottle'),
    ('c0000000-0000-4000-8000-000000000016', 'cumin',             NULL, 50,   NULL, 'g',     1, 'spice jar'),
    ('c0000000-0000-4000-8000-000000000017', 'ground cumin',      NULL, 50,   NULL, 'g',     1, 'spice jar'),
    ('c0000000-0000-4000-8000-000000000018', 'paprika',           NULL, 50,   NULL, 'g',     1, 'spice jar'),
    ('c0000000-0000-4000-8000-000000000019', 'cinnamon',          NULL, 50,   NULL, 'g',     1, 'spice jar'),
    ('c0000000-0000-4000-8000-00000000001a', 'chili powder',      NULL, 50,   NULL, 'g',     1, 'spice jar'),
    ('c0000000-0000-4000-8000-00000000001b', 'garlic powder',     NULL, 50,   NULL, 'g',     1, 'spice jar'),
    ('c0000000-0000-4000-8000-00000000001c', 'cayenne pepper',    NULL, 50,   NULL, 'g',     1, 'spice jar'),
    ('c0000000-0000-4000-8000-00000000001d', 'egg',               NULL, NULL, 6,    'items', 1, 'half dozen'),
    ('c0000000-0000-4000-8000-00000000001e', 'egg',               NULL, NULL, 12,   'items', 2, 'dozen'),
    ('c0000000-0000-4000-8000-00000000001f', 'carrot',            NULL, NULL, 6,    'items', 1, 'loose'),
    ('c0000000-0000-4000-8000-000000000020', 'celery',            NULL, NULL, 1,    'items', 1, 'head'),
    ('c0000000-0000-4000-8000-000000000021', 'green onion',       NULL, NULL, 1,    'items', 1, 'bunch'),
    -- common perishables / proteins by weight (grams)
    ('c0000000-0000-4000-8000-000000000022', 'vegetable broth',   NULL, 500,  NULL, 'g',     1, 'carton'),
    ('c0000000-0000-4000-8000-000000000023', 'vegetable broth',   NULL, 1000, NULL, 'g',     2, 'large carton'),
    ('c0000000-0000-4000-8000-000000000024', 'spinach',           NULL, 200,  NULL, 'g',     1, 'bag'),
    ('c0000000-0000-4000-8000-000000000025', 'butternut squash',  NULL, 1000, NULL, 'g',     1, 'whole'),
    ('c0000000-0000-4000-8000-000000000026', 'almond milk',       NULL, 1000, NULL, 'g',     1, '1L carton'),
    ('c0000000-0000-4000-8000-000000000027', 'salmon',            NULL, 250,  NULL, 'g',     1, 'fillet'),
    ('c0000000-0000-4000-8000-000000000028', 'salmon',            NULL, 500,  NULL, 'g',     2, 'two fillets'),
    ('c0000000-0000-4000-8000-000000000029', 'couscous',          NULL, 500,  NULL, 'g',     1, 'box'),
    ('c0000000-0000-4000-8000-00000000002a', 'oats',              NULL, 500,  NULL, 'g',     1, 'standard'),
    ('c0000000-0000-4000-8000-00000000002b', 'oats',              NULL, 1000, NULL, 'g',     2, 'large'),
    ('c0000000-0000-4000-8000-00000000002c', 'mushroom',          NULL, 250,  NULL, 'g',     1, 'punnet'),
    ('c0000000-0000-4000-8000-00000000002d', 'cherry tomato',     NULL, 250,  NULL, 'g',     1, 'punnet'),
    ('c0000000-0000-4000-8000-00000000002e', 'ground beef',       NULL, 500,  NULL, 'g',     1, 'pack'),
    ('c0000000-0000-4000-8000-00000000002f', 'beef',              NULL, 500,  NULL, 'g',     1, 'pack'),
    ('c0000000-0000-4000-8000-000000000030', 'chicken breast',    NULL, 300,  NULL, 'g',     1, 'small tray'),
    ('c0000000-0000-4000-8000-000000000031', 'chicken breast',    NULL, 600,  NULL, 'g',     2, 'standard tray'),
    ('c0000000-0000-4000-8000-000000000032', 'tomato',            NULL, NULL, 6,    'items', 1, 'loose'),
    -- category fallbacks (ingredient_mapping_key NULL, matched by category)
    ('b0000000-0000-4000-8000-000000000001', NULL, 'dairy',     NULL, 1,    'items', 1, 'category fallback: single item'),
    ('b0000000-0000-4000-8000-000000000002', NULL, 'baking',    500,  NULL, 'g',     1, 'category fallback: 500g'),
    ('b0000000-0000-4000-8000-000000000003', NULL, 'produce',   NULL, 1,    'items', 1, 'category fallback: single item'),
    ('b0000000-0000-4000-8000-000000000004', NULL, 'meat',      300,  NULL, 'g',     1, 'category fallback: small tray'),
    ('b0000000-0000-4000-8000-000000000005', NULL, 'pantry',    400,  NULL, 'g',     1, 'category fallback: standard pack');
