-- Preference module — partial HNSW index on preference_taste_profile.taste_vector.
-- See lld/preference.md §V20260501120100 line 156-157 (HNSW for "recipes nearest to this user's
-- taste" / recommendation lookups). Partial index — rows whose taste_vector is still NULL (status
-- PENDING or FAILED) pay no index cost, mirroring the recipe_versions.embedding partial index.
CREATE INDEX IF NOT EXISTS idx_pref_taste_vector
    ON preference_taste_profile USING hnsw (taste_vector vector_cosine_ops)
    WHERE taste_vector IS NOT NULL;
