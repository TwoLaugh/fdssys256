-- Discovery module — 01a discovery_sources.
-- See lld/discovery.md §V20260615120000. Source-registry table; one row per DiscoverySource
-- bean. Mutable bookkeeping (failure_streak, last_*_at) is updated by the runner; admin
-- flips `enabled`; users flip `user_disabled`. crawl_config carries per-kind opaque payload
-- (sitemap URL, RSS URL, search engine id).
--
-- source_type enum strings stored UPPERCASE to match Hibernate's @Enumerated(EnumType.STRING)
-- default (Java enum.name()). DEFAULT clauses below use UPPERCASE forms accordingly.

CREATE TABLE discovery_sources (
    id                          uuid          PRIMARY KEY,
    source_key                  varchar(64)   NOT NULL UNIQUE,
    display_name                varchar(120)  NOT NULL,
    source_type                 varchar(16)   NOT NULL,
    kind                        varchar(32)   NOT NULL,
    base_url                    varchar(255)  NOT NULL,
    enabled                     boolean       NOT NULL DEFAULT true,
    user_disabled               boolean       NOT NULL DEFAULT false,
    requests_per_minute         integer       NOT NULL DEFAULT 6,
    requests_per_day            integer       NOT NULL DEFAULT 500,
    respect_robots_txt          boolean       NOT NULL DEFAULT true,
    user_agent                  varchar(160)  NOT NULL,
    crawl_config                jsonb,
    failure_streak              integer       NOT NULL DEFAULT 0,
    last_failure_at             timestamptz,
    last_success_at             timestamptz,
    last_used_at                timestamptz,
    quality_score               numeric(4,3),
    notes                       text,
    optimistic_version          bigint        NOT NULL DEFAULT 0,
    created_at                  timestamptz   NOT NULL,
    updated_at                  timestamptz   NOT NULL
);

-- Hot read: the runner enumerates enabled sources for every job.
CREATE INDEX idx_discovery_sources_enabled ON discovery_sources (enabled) WHERE enabled = true;

-- Admin lookup by kind for staged rollouts.
CREATE INDEX idx_discovery_sources_kind ON discovery_sources (kind);
