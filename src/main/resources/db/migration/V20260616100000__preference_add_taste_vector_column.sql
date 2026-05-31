-- Preference module — taste-vector embedding pipeline (preference-5 audit finding).
-- See lld/preference.md §V20260501120100 (the taste_vector column + HNSW index) and Flow 3 step 10.
--
-- 01c shipped the scalar status fields (taste_vector_status / _doc_version / _model_id / _embedded_at)
-- and deferred the pgvector column + ANN index to this follow-up, so the row shape is not back-touched.
-- The pgvector extension is already installed by V20260601100000__core_install_pgvector.sql (which runs
-- ahead of every module migration), so `CREATE EXTENSION` is not repeated here. CREATE ... IF NOT EXISTS
-- keeps the migration idempotent against partial re-runs in tests.
ALTER TABLE preference_taste_profile ADD COLUMN IF NOT EXISTS taste_vector vector(1536);
