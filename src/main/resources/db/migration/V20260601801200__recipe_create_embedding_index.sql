-- Recipe module — 01h partial HNSW index on recipe_versions.embedding.
-- See lld/recipe.md §V20260601120900 line 263. Partial index — rows whose embedding is still
-- NULL (status pending or failed) pay no index cost.
CREATE INDEX IF NOT EXISTS idx_recipe_versions_embedding
    ON recipe_versions USING hnsw (embedding vector_cosine_ops)
    WHERE embedding IS NOT NULL;
