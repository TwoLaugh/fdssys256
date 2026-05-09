-- Household module — 01a aggregate root.
-- See lld/household.md §Database. The 01a footprint is intentionally narrow:
-- only `household` and `household_member` (next migration); settings/audit/invite tables
-- ship with later sub-tickets (01b/01c).

CREATE TABLE household (
    id                    uuid         PRIMARY KEY,
    name                  varchar(128) NOT NULL,
    created_by_user_id    uuid         NOT NULL,
    version               bigint       NOT NULL DEFAULT 0,
    created_at            timestamptz  NOT NULL,
    updated_at            timestamptz  NOT NULL
);
