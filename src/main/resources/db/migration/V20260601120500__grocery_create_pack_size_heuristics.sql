-- Grocery module — 01a Tier 1 pack-size heuristics (reference data).
-- See lld/grocery.md §V20260601120500 (lines 323-345). Maps ingredient categories to typical pack
-- sizes — the provider-agnostic v1 fallback. The schema is fixed here; the data is reference data
-- seeded via the repeatable migration (R__grocery_seed_pack_size_heuristics.sql).
--
-- Two CHECK constraints: at least one of (pack_size_g, pack_count) is present, and at least one of
-- (ingredient_mapping_key, category) is the match target. PackSizeHeuristic is reference data —
-- no @Version (refreshed via the repeatable migration).

CREATE TABLE grocery_pack_size_heuristics (
    id                          uuid PRIMARY KEY,
    ingredient_mapping_key      varchar(128),                     -- nullable: matches by category if null
    category                    varchar(64),                      -- nullable: matches by mapping key if specified
    pack_size_g                 integer,                          -- one of pack_size_g or pack_count is required
    pack_count                  integer,
    pack_unit                   varchar(16) NOT NULL,             -- "g" | "ml" | "items"
    rank                        integer NOT NULL,                 -- 1 = smallest typical pack, 2 = next, ...
    notes                       varchar(255),
    CONSTRAINT chk_packsize_or_count CHECK (pack_size_g IS NOT NULL OR pack_count IS NOT NULL),
    CONSTRAINT chk_match_target      CHECK (ingredient_mapping_key IS NOT NULL OR category IS NOT NULL)
);
-- lookup: "what packs exist for chicken thighs?" or "for the dairy category?"
CREATE INDEX idx_grocery_pack_heur_key
    ON grocery_pack_size_heuristics (ingredient_mapping_key, rank);
CREATE INDEX idx_grocery_pack_heur_category
    ON grocery_pack_size_heuristics (category, rank);
