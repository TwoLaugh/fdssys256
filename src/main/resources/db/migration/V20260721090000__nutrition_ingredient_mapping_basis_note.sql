-- G05 (graph integration): additive per-row basis/provenance note on the ingredient-mapping
-- cache. The graph seed (spike canon, consumed-basis per-100g) stamps every seeded row with
-- the convention + source it was built on; the live USDA/OFF lazy-population pipeline leaves
-- it NULL. A human debugging a G08 spike-vs-engine divergence reads this column to see which
-- basis each row is on (design doc GRAPH_IN_APP_DESIGN.md section 5b).
ALTER TABLE nutrition_ingredient_mapping
    ADD COLUMN IF NOT EXISTS basis_note varchar(255);
