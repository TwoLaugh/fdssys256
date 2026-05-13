-- Adaptation pipeline — 01a reference-data table: adaptation_nutritional_knowledge.
-- See lld/adaptation-pipeline.md §V20260615120500 (lines 250-264, carved out into its
-- own versioned migration so the matching R__adaptation_seed_nutritional_knowledge_v1.sql
-- repeatable can populate rows later — schema fixed here, rows deferred per LLD line 247).
--
-- subject_keys stored as Postgres text[] so the GIN index can answer the
-- "do these candidate ingredients intersect any knowledge entry?" question
-- cheaply via the && operator. NutritionalKnowledgeRepository's native query
-- exploits this index.

CREATE TABLE adaptation_nutritional_knowledge (
    id                       uuid          PRIMARY KEY,
    knowledge_kind           varchar(32)   NOT NULL,
    subject_keys             text[]        NOT NULL,
    payload                  jsonb         NOT NULL,
    confidence_tier          varchar(16)   NOT NULL,
    source                   varchar(64)   NOT NULL,
    created_at               timestamptz   NOT NULL,
    UNIQUE (knowledge_kind, subject_keys)
);

-- Per-kind filter (e.g. "all PAIRING entries").
CREATE INDEX idx_adaptation_nut_knowledge_kind
    ON adaptation_nutritional_knowledge (knowledge_kind);

-- GIN array intersect for the native lookup (subject_keys && cast(:keys as text[])).
CREATE INDEX idx_adaptation_nut_knowledge_subjects_gin
    ON adaptation_nutritional_knowledge USING gin (subject_keys);
