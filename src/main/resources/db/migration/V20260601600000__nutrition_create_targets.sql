-- Nutrition module — 01a aggregate root: nutrition_targets.
-- See lld/nutrition.md §V20260502120000. Renumbered to V20260601600000 to sequence
-- after household (V…1500xxx) and before provisions (V…1700xxx).
--
-- One row per user (UNIQUE on user_id). user_overridden_directions stored as jsonb
-- list-of-strings, not text[] — Hibernate's text[] mapping is brittle on SB 3.2.5 /
-- hypersistence-utils-63 (same workaround as preference_hard_constraints.allergies).

CREATE TABLE nutrition_targets (
    id                              uuid          PRIMARY KEY,
    user_id                         uuid          NOT NULL UNIQUE,

    goal                            varchar(24)   NOT NULL DEFAULT 'MAINTAIN',

    daily_calorie_target            integer       NOT NULL DEFAULT 0,
    calorie_tolerance_under         integer       NOT NULL DEFAULT 100,
    calorie_tolerance_over          integer       NOT NULL DEFAULT 150,
    calorie_enforcement             varchar(24)   NOT NULL DEFAULT 'weekly_average',
    calorie_direction               varchar(24)   NOT NULL DEFAULT 'BOTH_BOUNDED',

    protein_target_g                numeric(6,1)  NOT NULL DEFAULT 0,
    protein_floor_g                 numeric(6,1),
    protein_enforcement             varchar(24)   NOT NULL DEFAULT 'daily_floor',
    protein_direction               varchar(24)   NOT NULL DEFAULT 'LOWER_FLOOR',

    carbs_target_g                  numeric(6,1)  NOT NULL DEFAULT 0,
    carbs_floor_g                   numeric(6,1),
    carbs_enforcement               varchar(24)   NOT NULL DEFAULT 'weekly_average',
    carbs_direction                 varchar(24)   NOT NULL DEFAULT 'BOTH_BOUNDED',

    fat_target_g                    numeric(6,1)  NOT NULL DEFAULT 0,
    fat_floor_g                     numeric(6,1),
    fat_enforcement                 varchar(24)   NOT NULL DEFAULT 'weekly_average',
    fat_direction                   varchar(24)   NOT NULL DEFAULT 'BOTH_BOUNDED',

    fibre_target_g                  numeric(6,1)  NOT NULL DEFAULT 0,
    fibre_floor_g                   numeric(6,1),
    fibre_enforcement               varchar(24)   NOT NULL DEFAULT 'daily_floor',
    fibre_direction                 varchar(24)   NOT NULL DEFAULT 'LOWER_FLOOR',

    sat_fat_target_g                numeric(6,1),
    sat_fat_direction               varchar(24)   NOT NULL DEFAULT 'UPPER_LIMIT',

    notes                           varchar(512),

    user_overridden_directions      jsonb         NOT NULL DEFAULT '[]'::jsonb,

    version                         bigint        NOT NULL DEFAULT 0,
    created_at                      timestamptz   NOT NULL,
    updated_at                      timestamptz   NOT NULL
);

CREATE UNIQUE INDEX idx_nutrition_targets_user
    ON nutrition_targets (user_id);
