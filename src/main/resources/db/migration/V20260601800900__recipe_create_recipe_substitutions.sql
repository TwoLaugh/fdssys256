-- Recipe module — 01e recipe_substitutions.
-- State machine: PROPOSED -> ACCEPTED / REJECTED / SUPERSEDED
-- (renamed from LLD's active|inactive|promoted per ticket 01e — see ticket for rationale).
--
-- Layered on top of 01a/01b/01c/01d. The base recipe version is never mutated; the
-- substitution sits as an overlay applied at read-time via SubstitutionOverlayApplier
-- (LLD line 749).

CREATE TABLE recipe_substitutions (
    id                       uuid          PRIMARY KEY,
    recipe_id                uuid          NOT NULL REFERENCES recipe_recipes(id)  ON DELETE CASCADE,
    version_id               uuid          NOT NULL REFERENCES recipe_versions(id) ON DELETE CASCADE,
    branch_id                uuid          NOT NULL REFERENCES recipe_branches(id) ON DELETE CASCADE,
    original_mapping_key     varchar(160)  NOT NULL,
    original_quantity        numeric(10,3) NOT NULL,
    original_unit            varchar(16)   NOT NULL,
    substitute_mapping_key   varchar(160)  NOT NULL,
    substitute_quantity      numeric(10,3) NOT NULL,
    substitute_unit          varchar(16)   NOT NULL,
    reason                   varchar(32)   NOT NULL,
    constraint_ref           varchar(160),
    method_overlay           jsonb,
    notes                    text,
    temporary                boolean       NOT NULL DEFAULT true,
    applied_in_plan_ids      uuid[]        NOT NULL DEFAULT '{}',
    application_count        integer       NOT NULL DEFAULT 0,
    last_applied_at          timestamptz,
    state                    varchar(16)   NOT NULL DEFAULT 'PROPOSED',
    promoted_to_version_id   uuid          REFERENCES recipe_versions(id),
    created_at               timestamptz   NOT NULL,
    created_by_actor         varchar(64)   NOT NULL,
    adapter_trace_id         uuid,
    version                  bigint        NOT NULL DEFAULT 0
);

CREATE INDEX idx_recipe_substitutions_version
    ON recipe_substitutions (version_id)
    WHERE state = 'ACCEPTED';

CREATE INDEX idx_recipe_substitutions_promotion
    ON recipe_substitutions (recipe_id, application_count DESC)
    WHERE state = 'ACCEPTED';
