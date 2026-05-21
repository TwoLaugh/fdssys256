-- Recipe / Discovery — 01g `saveImportedRecipe` SPI.
-- Extends the existing recipe_imports table (from 01b) with the fields the discovery pipeline
-- needs to populate when persisting WEB_DISCOVERED recipes. content_fingerprint is the dedup
-- handle (UNIQUE) so a re-scrape of the same canonical content collapses to the existing recipe.
--
-- imported_by_user_id remains NOT NULL on the existing table; discovery-side imports populate
-- it with a sentinel system user UUID (Recipe.userId is the same value). source_type continues
-- to carry WEB_DISCOVERED for these rows.

ALTER TABLE recipe_imports
    ADD COLUMN content_fingerprint   varchar(64),
    ADD COLUMN source_key            varchar(64),
    ADD COLUMN canonical_url         varchar(2048),
    ADD COLUMN extraction_confidence numeric(4,3),
    ADD COLUMN job_id                uuid;

-- Dedup probe: a second import attempt with the same fingerprint short-circuits to the existing
-- recipe. Partial unique index keeps the original null-allowing column compatible with the 01b
-- URL-import path (which doesn't yet populate fingerprints).
CREATE UNIQUE INDEX uq_recipe_imports_content_fingerprint
    ON recipe_imports (content_fingerprint)
    WHERE content_fingerprint IS NOT NULL;

-- Source-scoped audit: "what came in from bbcgoodfood last week?"
CREATE INDEX idx_recipe_imports_source_key_time
    ON recipe_imports (source_key, imported_at DESC)
    WHERE source_key IS NOT NULL;
