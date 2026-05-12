-- Recipe module — 01h enable pgvector. Idempotent because the core install migration
-- (V20260601100000__core_install_pgvector.sql) already runs `CREATE EXTENSION IF NOT EXISTS
-- vector` ahead of recipe migrations; this re-run is a no-op but keeps the recipe-01h migration
-- bundle self-contained so a future tooling-driven extraction can read just the recipe range.
-- MUST come before V20260601801100 (vector(1536) column add).
-- pg16 image `pgvector/pgvector:pg16` already has the extension installable.
CREATE EXTENSION IF NOT EXISTS vector;
