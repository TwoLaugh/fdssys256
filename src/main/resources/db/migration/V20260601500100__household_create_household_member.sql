-- Household module — 01a aggregate child: members.
-- Three uniqueness constraints (locked from LLD §Database):
--   - UNIQUE (household_id, user_id) — same user can't appear twice in one household.
--   - UNIQUE (user_id) — v1 single-household-per-user invariant.
--   - partial unique idx on (household_id) WHERE role = 'primary' — exactly one PRIMARY per household.
-- The partial index references the lower-case literal 'primary'; the JPA enum is stored in lower-case
-- form to match (HouseholdRole.primary / HouseholdRole.member).

CREATE TABLE household_member (
    id              uuid         PRIMARY KEY,
    household_id    uuid         NOT NULL REFERENCES household(id) ON DELETE CASCADE,
    user_id         uuid         NOT NULL,
    role            varchar(16)  NOT NULL,
    display_name    varchar(64),
    priority        integer      NOT NULL DEFAULT 100,
    joined_at       timestamptz  NOT NULL,
    version         bigint       NOT NULL DEFAULT 0,
    created_at      timestamptz  NOT NULL,
    updated_at      timestamptz  NOT NULL,
    CONSTRAINT uq_household_member_household_user UNIQUE (household_id, user_id),
    CONSTRAINT uq_household_member_user           UNIQUE (user_id)
);

CREATE UNIQUE INDEX idx_household_member_one_primary
    ON household_member (household_id)
    WHERE role = 'primary';

CREATE INDEX idx_household_member_household
    ON household_member (household_id);
