-- Repeatable migration: default kind toggles for new users.
-- Decision (per tickets/notification/01a-core.md): the enabled_kinds defaults live in a Java
-- constant (NotificationDefaults.DEFAULT_ENABLED_KINDS) rather than a seed table, so the kind set
-- has a single source of truth that evolves with the NotificationKind enum. A seed table would
-- create a dual source of truth (every new enum value would need both a code default and a seed
-- row). This repeatable migration ships as a placeholder for future opt-in user-side seed data.

-- (Empty body — defaults live in NotificationDefaults.java.) The no-op DO block keeps Flyway happy
-- with a non-empty repeatable migration without emitting a result set.
DO $$ BEGIN END $$;
