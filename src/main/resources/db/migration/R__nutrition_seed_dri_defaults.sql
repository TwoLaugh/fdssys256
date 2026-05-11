-- Repeatable migration: seed minimal-viable DRI (Daily Recommended Intake) defaults.
-- Values sourced from NIH Office of Dietary Supplements (ODS) reference data
-- (https://ods.od.nih.gov/HealthInformation/Dietary_Reference_Intakes.aspx).
--
-- v1 coverage: 7 most-tracked micronutrients × 3 adult age groups × 2 sexes = 42 rows.
-- The seed table sits dormant until nutrition-01a's initialiseTargets wires the lookup;
-- adding the data here per the parent ticket's deferral list.

CREATE TABLE IF NOT EXISTS nutrition_dri_defaults (
    id              uuid PRIMARY KEY,
    age_group       varchar(16) NOT NULL,
    sex             varchar(8) NOT NULL,
    micro_name      varchar(64) NOT NULL,
    rda_value       numeric(10,3) NOT NULL,
    unit            varchar(16) NOT NULL,
    UNIQUE (age_group, sex, micro_name)
);

-- Idempotent UPSERTs. The UUIDs are deterministically derived via uuid_generate_v5-style stable
-- hashing using md5() so re-running yields the same id for the same business key.

INSERT INTO nutrition_dri_defaults (id, age_group, sex, micro_name, rda_value, unit) VALUES
    -- 19-30 male
    (md5('19-30|male|iron_mg')::uuid,         '19-30', 'male',   'iron_mg',         8.0,   'mg'),
    (md5('19-30|male|calcium_mg')::uuid,      '19-30', 'male',   'calcium_mg',      1000.0,'mg'),
    (md5('19-30|male|vitamin_c_mg')::uuid,    '19-30', 'male',   'vitamin_c_mg',    90.0,  'mg'),
    (md5('19-30|male|vitamin_b12_mcg')::uuid, '19-30', 'male',   'vitamin_b12_mcg', 2.4,   'mcg'),
    (md5('19-30|male|folate_mcg')::uuid,      '19-30', 'male',   'folate_mcg',      400.0, 'mcg'),
    (md5('19-30|male|magnesium_mg')::uuid,    '19-30', 'male',   'magnesium_mg',    400.0, 'mg'),
    (md5('19-30|male|zinc_mg')::uuid,         '19-30', 'male',   'zinc_mg',         11.0,  'mg'),
    -- 19-30 female
    (md5('19-30|female|iron_mg')::uuid,         '19-30', 'female', 'iron_mg',         18.0,  'mg'),
    (md5('19-30|female|calcium_mg')::uuid,      '19-30', 'female', 'calcium_mg',      1000.0,'mg'),
    (md5('19-30|female|vitamin_c_mg')::uuid,    '19-30', 'female', 'vitamin_c_mg',    75.0,  'mg'),
    (md5('19-30|female|vitamin_b12_mcg')::uuid, '19-30', 'female', 'vitamin_b12_mcg', 2.4,   'mcg'),
    (md5('19-30|female|folate_mcg')::uuid,      '19-30', 'female', 'folate_mcg',      400.0, 'mcg'),
    (md5('19-30|female|magnesium_mg')::uuid,    '19-30', 'female', 'magnesium_mg',    310.0, 'mg'),
    (md5('19-30|female|zinc_mg')::uuid,         '19-30', 'female', 'zinc_mg',         8.0,   'mg'),
    -- 31-50 male
    (md5('31-50|male|iron_mg')::uuid,         '31-50', 'male',   'iron_mg',         8.0,   'mg'),
    (md5('31-50|male|calcium_mg')::uuid,      '31-50', 'male',   'calcium_mg',      1000.0,'mg'),
    (md5('31-50|male|vitamin_c_mg')::uuid,    '31-50', 'male',   'vitamin_c_mg',    90.0,  'mg'),
    (md5('31-50|male|vitamin_b12_mcg')::uuid, '31-50', 'male',   'vitamin_b12_mcg', 2.4,   'mcg'),
    (md5('31-50|male|folate_mcg')::uuid,      '31-50', 'male',   'folate_mcg',      400.0, 'mcg'),
    (md5('31-50|male|magnesium_mg')::uuid,    '31-50', 'male',   'magnesium_mg',    420.0, 'mg'),
    (md5('31-50|male|zinc_mg')::uuid,         '31-50', 'male',   'zinc_mg',         11.0,  'mg'),
    -- 31-50 female
    (md5('31-50|female|iron_mg')::uuid,         '31-50', 'female', 'iron_mg',         18.0,  'mg'),
    (md5('31-50|female|calcium_mg')::uuid,      '31-50', 'female', 'calcium_mg',      1000.0,'mg'),
    (md5('31-50|female|vitamin_c_mg')::uuid,    '31-50', 'female', 'vitamin_c_mg',    75.0,  'mg'),
    (md5('31-50|female|vitamin_b12_mcg')::uuid, '31-50', 'female', 'vitamin_b12_mcg', 2.4,   'mcg'),
    (md5('31-50|female|folate_mcg')::uuid,      '31-50', 'female', 'folate_mcg',      400.0, 'mcg'),
    (md5('31-50|female|magnesium_mg')::uuid,    '31-50', 'female', 'magnesium_mg',    320.0, 'mg'),
    (md5('31-50|female|zinc_mg')::uuid,         '31-50', 'female', 'zinc_mg',         8.0,   'mg'),
    -- 51-70 male
    (md5('51-70|male|iron_mg')::uuid,         '51-70', 'male',   'iron_mg',         8.0,   'mg'),
    (md5('51-70|male|calcium_mg')::uuid,      '51-70', 'male',   'calcium_mg',      1000.0,'mg'),
    (md5('51-70|male|vitamin_c_mg')::uuid,    '51-70', 'male',   'vitamin_c_mg',    90.0,  'mg'),
    (md5('51-70|male|vitamin_b12_mcg')::uuid, '51-70', 'male',   'vitamin_b12_mcg', 2.4,   'mcg'),
    (md5('51-70|male|folate_mcg')::uuid,      '51-70', 'male',   'folate_mcg',      400.0, 'mcg'),
    (md5('51-70|male|magnesium_mg')::uuid,    '51-70', 'male',   'magnesium_mg',    420.0, 'mg'),
    (md5('51-70|male|zinc_mg')::uuid,         '51-70', 'male',   'zinc_mg',         11.0,  'mg'),
    -- 51-70 female
    (md5('51-70|female|iron_mg')::uuid,         '51-70', 'female', 'iron_mg',         8.0,   'mg'),
    (md5('51-70|female|calcium_mg')::uuid,      '51-70', 'female', 'calcium_mg',      1200.0,'mg'),
    (md5('51-70|female|vitamin_c_mg')::uuid,    '51-70', 'female', 'vitamin_c_mg',    75.0,  'mg'),
    (md5('51-70|female|vitamin_b12_mcg')::uuid, '51-70', 'female', 'vitamin_b12_mcg', 2.4,   'mcg'),
    (md5('51-70|female|folate_mcg')::uuid,      '51-70', 'female', 'folate_mcg',      400.0, 'mcg'),
    (md5('51-70|female|magnesium_mg')::uuid,    '51-70', 'female', 'magnesium_mg',    320.0, 'mg'),
    (md5('51-70|female|zinc_mg')::uuid,         '51-70', 'female', 'zinc_mg',         8.0,   'mg')
ON CONFLICT (age_group, sex, micro_name) DO UPDATE
    SET rda_value = EXCLUDED.rda_value,
        unit = EXCLUDED.unit;
