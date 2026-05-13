-- Planner module — 01a partial unique index: only one ACTIVE plan per (household, week_start_date).
-- See lld/planner.md §V20260507120300.
--
-- Value casing is UPPERCASE 'ACTIVE' to match Hibernate's default @Enumerated(EnumType.STRING)
-- persistence form on Spring Boot 3.2.5 — the LLD's lowercase 'active' is corrected here per the
-- ticket's locked decision (gotchas #6).

CREATE UNIQUE INDEX uq_planner_plans_active_per_household_week
    ON planner_plans (household_id, week_start_date)
    WHERE status = 'ACTIVE';
