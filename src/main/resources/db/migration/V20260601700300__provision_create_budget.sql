-- Provisions module — 01c budget aggregate.
-- See lld/provisions.md §V20260502120200. One row per user — UNIQUE (user_id) enforces this.
-- 'currency' is per-row ISO-4217 alpha-3; 'price_sensitivity' is the LLD's lowercase enum.
-- 'enabled' captures the HLD's "budget is optional" rule (users can pause without losing config).
--
-- The redundant idx_prov_budget_user mirrors the LLD's explicit index declaration; UNIQUE on the
-- column already covers the lookup, but keeping the named index makes intent explicit.

CREATE TABLE provision_budget (
    id                uuid          PRIMARY KEY,
    user_id           uuid          NOT NULL UNIQUE,
    weekly_target     numeric(8,2)  NOT NULL,
    currency          varchar(3)    NOT NULL DEFAULT 'GBP',
    tolerance_over    numeric(8,2)  NOT NULL DEFAULT 0,
    price_sensitivity varchar(16)   NOT NULL DEFAULT 'moderate',
    enabled           boolean       NOT NULL DEFAULT true,
    version           bigint        NOT NULL DEFAULT 0,
    created_at        timestamptz   NOT NULL,
    updated_at        timestamptz   NOT NULL,
    CONSTRAINT chk_weekly_target_pos CHECK (weekly_target > 0),
    CONSTRAINT chk_tolerance_nonneg  CHECK (tolerance_over >= 0)
);
CREATE UNIQUE INDEX idx_prov_budget_user ON provision_budget (user_id);
