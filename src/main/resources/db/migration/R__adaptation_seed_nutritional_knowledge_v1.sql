-- Adaptation pipeline — 01a repeatable stub.
-- See lld/adaptation-pipeline.md §Repeatable migrations (lines 247-264) and the 01a
-- ticket §Migration timestamps and ordering ("R__adaptation_seed_nutritional_knowledge_v1.sql").
--
-- Table DDL lives in V20260615120500__adaptation_create_nutritional_knowledge.sql.
-- Rows are deferred to prompt-engineering work per LLD line 247 — pairings, bioavailability,
-- soaks, conflicts will land here once curated. This stub keeps the filename reserved so adding
-- rows later is a content-only change with no Flyway versioning churn.
SELECT 1 WHERE FALSE;
