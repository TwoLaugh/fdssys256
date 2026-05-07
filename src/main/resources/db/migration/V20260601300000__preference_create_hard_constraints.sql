-- Preference module — 01a hard-constraints aggregate.
-- See lld/preference.md §V20260501120000 — Hard constraints
-- (the LLD's V-timestamps were rebased to the project's actual sequence;
-- 01a lives at V20260601300000 after core (V…1000xx) and auth (V…2000xx).)
--
-- Aggregate root + 4 child tables. Excludes preference_allergen_derivatives —
-- that ships with 01b alongside HardConstraintFilterService which consumes it.

CREATE TABLE preference_hard_constraints (
    id                       uuid        PRIMARY KEY,
    user_id                  uuid        NOT NULL UNIQUE,
    -- LLD specced text[] for allergies/medical_diets, but Hibernate's text[] mapping via
    -- hypersistence ListArrayType throws InvalidDataAccessApiUsageException on read at runtime
    -- in this Spring Boot 3.2.5 / hibernate-utils-63 combination (caught by IT, missed by unit
    -- tests with mocked repos). Switching to jsonb List<String> via JsonBinaryType — same shape,
    -- battle-tested in this repo (DecisionLog, audit log).
    allergies                jsonb       NOT NULL DEFAULT '[]'::jsonb,
    dietary_identity_base    varchar(32) NOT NULL DEFAULT 'omnivore',
    dietary_identity_label   varchar(64),
    medical_diets            jsonb       NOT NULL DEFAULT '[]'::jsonb,
    version                  bigint      NOT NULL DEFAULT 0,
    created_at               timestamptz NOT NULL,
    updated_at               timestamptz NOT NULL
);
-- Hot read: HardConstraintFilterService.check(userId, ...) on every food output (filter ships with 01b).
CREATE UNIQUE INDEX idx_pref_hard_constraints_user
    ON preference_hard_constraints (user_id);

CREATE TABLE preference_dietary_identity_exceptions (
    id                       uuid        PRIMARY KEY,
    hard_constraints_id      uuid        NOT NULL REFERENCES preference_hard_constraints(id) ON DELETE CASCADE,
    allows                   varchar(64) NOT NULL,
    frequency                varchar(32),
    context                  varchar(32) NOT NULL DEFAULT 'any'
);
-- Used when the filter widens the base diet during ingredient checks.
CREATE INDEX idx_pref_dietary_exceptions_hc
    ON preference_dietary_identity_exceptions (hard_constraints_id);

CREATE TABLE preference_hard_intolerances (
    id                       uuid         PRIMARY KEY,
    hard_constraints_id      uuid         NOT NULL REFERENCES preference_hard_constraints(id) ON DELETE CASCADE,
    substance                varchar(64)  NOT NULL,
    severity                 varchar(32)  NOT NULL,
    notes                    varchar(255)
);
CREATE INDEX idx_pref_hard_intolerances_hc
    ON preference_hard_intolerances (hard_constraints_id);

CREATE TABLE preference_age_restrictions (
    id                       uuid        PRIMARY KEY,
    hard_constraints_id      uuid        NOT NULL REFERENCES preference_hard_constraints(id) ON DELETE CASCADE,
    rule_key                 varchar(64) NOT NULL,
    auto_populated           boolean     NOT NULL DEFAULT false
);
CREATE INDEX idx_pref_age_restrictions_hc
    ON preference_age_restrictions (hard_constraints_id);

CREATE TABLE preference_hard_constraints_audit (
    id                       uuid        PRIMARY KEY,
    hard_constraints_id      uuid        NOT NULL REFERENCES preference_hard_constraints(id) ON DELETE CASCADE,
    actor_user_id            uuid        NOT NULL,
    field_changed            varchar(64) NOT NULL,
    previous_value_json      jsonb       NOT NULL,
    new_value_json           jsonb       NOT NULL,
    occurred_at              timestamptz NOT NULL
);
-- Audit-log query endpoint and safety reviews.
CREATE INDEX idx_pref_hc_audit_hc_time
    ON preference_hard_constraints_audit (hard_constraints_id, occurred_at DESC);
