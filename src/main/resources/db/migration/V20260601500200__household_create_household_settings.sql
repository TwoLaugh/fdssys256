-- Household module — 01b settings aggregate.
-- One row per household; modelled as a separate aggregate root rather than a child of
-- `household` to avoid an entity-rewrite of the 01a `Household` graph. The UNIQUE on
-- household_id is the only access path needed; a separate index would duplicate it.
-- See lld/household.md §V20260501130200, §Entities.

CREATE TABLE household_settings (
    id              uuid        PRIMARY KEY,
    household_id    uuid        NOT NULL UNIQUE REFERENCES household(id) ON DELETE CASCADE,
    document        jsonb       NOT NULL,
    version         bigint      NOT NULL DEFAULT 0,
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL
);
