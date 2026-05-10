-- Household module — 01c invites aggregate.
-- Codes are globally unique (UNIQUE invite_code) so accept-by-code lookup needs no household_id.
-- Two partial indexes restrict to pending invites: lookup-by-code on accept hits the partial index
-- only against open invites; the household-scoped pending list is partial too.

CREATE TABLE household_invite (
    id                       uuid PRIMARY KEY,
    household_id             uuid NOT NULL REFERENCES household(id) ON DELETE CASCADE,
    invite_code              varchar(32) NOT NULL UNIQUE,
    issued_by_user_id        uuid NOT NULL,
    issued_for_user_id       uuid,
    intended_role            varchar(16) NOT NULL DEFAULT 'member',
    expires_at               timestamptz NOT NULL,
    accepted_by_user_id      uuid,
    accepted_at              timestamptz,
    revoked_at               timestamptz,
    version                  bigint NOT NULL DEFAULT 0,
    created_at               timestamptz NOT NULL
);

-- Lookup by code on accept; pending-invites listing for admin UI.
CREATE INDEX idx_household_invite_code
    ON household_invite (invite_code)
    WHERE accepted_at IS NULL AND revoked_at IS NULL;

CREATE INDEX idx_household_invite_household
    ON household_invite (household_id)
    WHERE accepted_at IS NULL AND revoked_at IS NULL;
