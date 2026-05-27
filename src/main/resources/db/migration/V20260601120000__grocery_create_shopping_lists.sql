-- Grocery module — 01a Tier 1 shopping-list aggregate (parent + lines, one migration).
-- See lld/grocery.md §V20260601120000 (lines 86-150). A shopping list is DERIVED STATE: a
-- snapshot rendered from a plan + provisions at a moment in time, kept for history. Regenerated
-- when the underlying plan/provisions change; rendered snapshots are not edited as source of truth.
--
-- DIVERGENCE (ticket 01a, locked): the LLD's `plan_revision` is renamed to `plan_generation`
-- here (column + UNIQUE constraint + active-list index predicate) to map 1:1 onto the shipped
-- planner's `planner_plans.generation` counter — the planner has no `plan_revision` concept.
--
-- Enum-like columns store the UPPERCASE @Enumerated(EnumType.STRING) constant name (Hibernate's
-- default casing) — matches provisions/recipe convention.

CREATE TABLE shopping_lists (
    id                       uuid PRIMARY KEY,
    user_id                  uuid NOT NULL,
    household_id             uuid,                                -- nullable: single-user mode; populated when household scope known
    plan_id                  uuid NOT NULL,                       -- soft FK to planner_plans
    plan_generation          integer NOT NULL,                    -- == planner_plans.generation (see divergence note)
    generated_at             timestamptz NOT NULL,
    superseded_at            timestamptz,                         -- non-null when a newer generation is generated
    estimated_total_pence    integer,                             -- nullable: no price data yet
    estimated_total_currency varchar(3) NOT NULL DEFAULT 'GBP',
    cost_confidence          numeric(4,3),                        -- 0..1; null when no price history
    stale_ingredient_count   integer NOT NULL DEFAULT 0,
    pantry_tracking_enabled  boolean NOT NULL,                    -- snapshot of the lifestyle flag at calc time
    notes                    varchar(255),
    version                  bigint NOT NULL DEFAULT 0,
    created_at               timestamptz NOT NULL,
    updated_at               timestamptz NOT NULL,
    UNIQUE (plan_id, plan_generation)
);
-- "show me the current list for this plan"
CREATE INDEX idx_shop_lists_user_plan_active
    ON shopping_lists (user_id, plan_id) WHERE superseded_at IS NULL;
-- history view: list of past shopping lists by user
CREATE INDEX idx_shop_lists_user_generated
    ON shopping_lists (user_id, generated_at DESC);

CREATE TABLE shopping_list_lines (
    id                       uuid PRIMARY KEY,
    shopping_list_id         uuid NOT NULL REFERENCES shopping_lists(id) ON DELETE CASCADE,
    ingredient_mapping_key   varchar(128) NOT NULL,               -- lowercased per technical-architecture §Cross-module references
    display_name             varchar(128) NOT NULL,
    requested_quantity       numeric(10,3) NOT NULL,
    requested_unit           varchar(16) NOT NULL,
    suggested_pack_size_g    integer,                             -- output of the pack-size optimiser
    suggested_pack_count     integer,
    suggested_pack_unit      varchar(16),
    line_type                varchar(16) NOT NULL,                -- PLANNED_DEMAND | STAPLE_REPLENISHMENT
    quality_notes            varchar(255),                        -- e.g. "organic where available"
    estimated_unit_pence     integer,                             -- from price-history aggregate; null if no data
    estimated_line_pence     integer,
    estimated_confidence     numeric(4,3),
    is_stale_estimate        boolean NOT NULL DEFAULT false,      -- price > 3 months old
    fulfilment_status        varchar(16) NOT NULL DEFAULT 'UNFILLED', -- UNFILLED | PARTIAL | BOUGHT | SUBSTITUTED | DROPPED
    bought_quantity          numeric(10,3),
    bought_unit              varchar(16),
    bought_price_pence       integer,
    bought_at                timestamptz,
    bought_via               varchar(16),                         -- MANUAL | ORDER | BULK_TOTAL
    grocery_order_id         uuid,                                -- soft FK; populated when fulfilled via order
    created_at               timestamptz NOT NULL,
    updated_at               timestamptz NOT NULL
);
-- the dominant read: "render the current list with all lines"
CREATE INDEX idx_shop_lines_list ON shopping_list_lines (shopping_list_id);
-- bulk mark-bought / per-mapping-key fulfilment lookups
CREATE INDEX idx_shop_lines_list_mapping_key
    ON shopping_list_lines (shopping_list_id, ingredient_mapping_key);
