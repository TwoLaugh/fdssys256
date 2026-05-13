-- Discovery module — 01a discovery_scrape_log.
-- See lld/discovery.md §V20260615120200. Append-only audit: one row per HTTP fetch
-- attempt to a candidate URL. Powers debugging, dedup (content_fingerprint), rate-limit
-- diagnostics, and robots.txt audit.
--
-- recipe_id is deliberately NOT a hard FK to recipe_recipes — discovery must not pull
-- the recipe module's tables into its Flyway path (lld/discovery.md line 181). job_id IS
-- a hard FK with ON DELETE CASCADE so deleting a job sweeps its scrape rows.
--
-- status column width: LLD spec'd varchar(24) but the longest enum value
-- 'HARD_CONSTRAINT_VIOLATION' is 25 chars. Widening to 32 (ticket invariant 12).
--
-- All enum-backed varchar columns store UPPERCASE Java enum names per ticket invariant 19.

CREATE TABLE discovery_scrape_log (
    id                       uuid          PRIMARY KEY,
    job_id                   uuid          NOT NULL REFERENCES discovery_jobs(id) ON DELETE CASCADE,
    source_key               varchar(64)   NOT NULL,
    candidate_url            varchar(2048) NOT NULL,
    canonical_url            varchar(2048),
    status                   varchar(32)   NOT NULL,
    http_status_code         integer,
    robots_txt_outcome       varchar(16)   NOT NULL,
    latency_ms               integer,
    content_fingerprint      varchar(64),
    extraction_method        varchar(32),
    extraction_confidence    numeric(4,3),
    recipe_id                uuid,
    skip_reason              varchar(64),
    error_class              varchar(64),
    error_message            text,
    occurred_at              timestamptz   NOT NULL
);

-- Job-scoped read: the runner aggregates per-source outcomes for the job result summary.
CREATE INDEX idx_discovery_scrape_log_job
    ON discovery_scrape_log (job_id, occurred_at);

-- Source-scoped read: rate-limit diagnostics and circuit-breaker stats.
CREATE INDEX idx_discovery_scrape_log_source_time
    ON discovery_scrape_log (source_key, occurred_at DESC);

-- Dedup: the runner short-circuits on a fingerprint already seen within the lookback window.
CREATE INDEX idx_discovery_scrape_log_fingerprint
    ON discovery_scrape_log (content_fingerprint)
    WHERE content_fingerprint IS NOT NULL;

-- Robots.txt audit trail.
CREATE INDEX idx_discovery_scrape_log_robots
    ON discovery_scrape_log (robots_txt_outcome, occurred_at DESC)
    WHERE robots_txt_outcome IN ('DISALLOWED', 'UNAVAILABLE');

-- Walk back from a system-catalogue recipe to the fetch that produced it.
CREATE INDEX idx_discovery_scrape_log_recipe
    ON discovery_scrape_log (recipe_id)
    WHERE recipe_id IS NOT NULL;
