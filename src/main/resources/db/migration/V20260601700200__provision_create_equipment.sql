-- Provisions module — 01b equipment aggregate.
-- See lld/provisions.md §V20260502120100. Per-user equipment list keyed by free-text name
-- (lowercase + digits + underscore). UNIQUE (user_id, name) covers the only access path.
--
-- The seed catalogue (provision_equipment_catalogue) lives in the repeatable migration
-- R__provision_seed_equipment_catalogue.sql; this aggregate references it by name only
-- (no DB-level FK — users may add equipment names not in the canonical catalogue).

CREATE TABLE provision_equipment (
    id            uuid         PRIMARY KEY,
    user_id       uuid         NOT NULL,
    name          varchar(64)  NOT NULL,
    available     boolean      NOT NULL,
    details       varchar(255),
    version       bigint       NOT NULL DEFAULT 0,
    created_at    timestamptz  NOT NULL,
    updated_at    timestamptz  NOT NULL,
    UNIQUE (user_id, name)
);
-- The (user_id, name) unique covers the only access path. Skipping a separate index.
