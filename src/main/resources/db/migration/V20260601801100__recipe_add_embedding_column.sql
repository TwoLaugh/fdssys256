-- Recipe module — 01h add embedding vector(1536) column on recipe_versions plus the model id
-- and embedded_at timestamp columns. See lld/recipe.md §V20260601120100 line 115.
--
-- 01a shipped embedding_status (varchar(16) NOT NULL DEFAULT 'pending') but deferred the vector,
-- model id and embedded_at columns to 01h. IF NOT EXISTS keeps the migration idempotent in case
-- of partial re-runs in tests.
ALTER TABLE recipe_versions ADD COLUMN IF NOT EXISTS embedding vector(1536);
ALTER TABLE recipe_versions ADD COLUMN IF NOT EXISTS embedding_model_id varchar(96);
ALTER TABLE recipe_versions ADD COLUMN IF NOT EXISTS embedded_at timestamptz;
