-- Repeatable migration: seed minimal-viable DRI (Daily Recommended Intake) defaults.
-- Values sourced from NIH Office of Dietary Supplements (ODS) reference data
-- (https://ods.od.nih.gov/HealthInformation/Dietary_Reference_Intakes.aspx).
--
-- Coverage: 28 tracked micronutrients × 3 adult age groups × 2 sexes = 168 rows.
-- The original 7 are seeded with explicit per-age/sex rows below (they carry genuine age/sex
-- deltas — e.g. iron, magnesium, calcium). The remaining 21 (fat-soluble vitamins A/D/E/K, the
-- B-complex, choline; minerals phosphorus/potassium/sodium/chloride/copper/manganese/selenium/
-- iodine/chromium/molybdenum) are seeded via a sex-differentiated cross-join using the 19-50 adult
-- reference value across all three adult bands (the minor 51-70 deltas for B6/chromium are deferred
-- — they do not change the planner's behaviour for the common adult case).

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

-- Expanded coverage (nutrition-driven planning): the remaining 21 tracked micros. Same
-- deterministic md5 id scheme + idempotent UPSERT as the explicit block above. Sex-differentiated;
-- the 19-50 adult reference value is applied across all three adult bands via the cross-join.
INSERT INTO nutrition_dri_defaults (id, age_group, sex, micro_name, rda_value, unit)
SELECT md5(ag.age_group || '|' || s.sex || '|' || n.micro_name)::uuid,
       ag.age_group,
       s.sex,
       n.micro_name,
       CASE WHEN s.sex = 'male' THEN n.male_val ELSE n.female_val END,
       n.unit
FROM (VALUES ('19-30'), ('31-50'), ('51-70')) AS ag(age_group),
     (VALUES ('male'), ('female')) AS s(sex),
     (VALUES
        -- fat-soluble + B-complex vitamins (NIH ODS adult RDA/AI)
        ('vitamin_a_mcg',       'mcg', 900.0,  700.0),
        ('vitamin_d_mcg',       'mcg', 15.0,   15.0),
        ('vitamin_e_mg',        'mg',  15.0,   15.0),
        ('vitamin_k_mcg',       'mcg', 120.0,  90.0),
        ('thiamin_mg',          'mg',  1.2,    1.1),
        ('riboflavin_mg',       'mg',  1.3,    1.1),
        ('niacin_mg',           'mg',  16.0,   14.0),
        ('vitamin_b6_mg',       'mg',  1.3,    1.3),
        ('pantothenic_acid_mg', 'mg',  5.0,    5.0),
        ('biotin_mcg',          'mcg', 30.0,   30.0),
        ('choline_mg',          'mg',  550.0,  425.0),
        -- minerals (NIH ODS adult RDA/AI)
        ('phosphorus_mg',       'mg',  700.0,  700.0),
        ('potassium_mg',        'mg',  3400.0, 2600.0),
        ('sodium_mg',           'mg',  1500.0, 1500.0),
        ('chloride_mg',         'mg',  2300.0, 2300.0),
        ('copper_mg',           'mg',  0.9,    0.9),
        ('manganese_mg',        'mg',  2.3,    1.8),
        ('selenium_mcg',        'mcg', 55.0,   55.0),
        ('iodine_mcg',          'mcg', 150.0,  150.0),
        ('chromium_mcg',        'mcg', 35.0,   25.0),
        ('molybdenum_mcg',      'mcg', 45.0,   45.0)
     ) AS n(micro_name, unit, male_val, female_val)
ON CONFLICT (age_group, sex, micro_name) DO UPDATE
    SET rda_value = EXCLUDED.rda_value,
        unit = EXCLUDED.unit;
