-- Repeatable migration: seeds the canonical equipment catalogue per LLD line 244.
-- Idempotent — INSERTs are gated by NOT EXISTS so re-runs are no-ops.
-- This seed is REFERENCE DATA: it pre-populates a per-(canonical-)name lookup
-- exposed via a separate read-only admin endpoint OR consumed by the onboarding
-- wizard. The user_id column on provision_equipment is a foreign-data field for
-- per-user customisation; the seed catalogue itself lives in a SEPARATE table:

CREATE TABLE IF NOT EXISTS provision_equipment_catalogue (
    name          varchar(64) PRIMARY KEY,
    display_name  varchar(64) NOT NULL,
    sort_order    integer NOT NULL
);

INSERT INTO provision_equipment_catalogue (name, display_name, sort_order) VALUES
  ('oven',            'Oven',            10),
  ('hob',             'Hob',             20),
  ('microwave',       'Microwave',       30),
  ('air_fryer',       'Air fryer',       40),
  ('slow_cooker',     'Slow cooker',     50),
  ('blender',         'Blender',         60),
  ('food_processor',  'Food processor',  70),
  ('grill',           'Grill',           80),
  ('bbq',             'BBQ',             90),
  ('rice_cooker',     'Rice cooker',     100),
  ('stand_mixer',     'Stand mixer',     110),
  ('pressure_cooker', 'Pressure cooker', 120),
  ('kettle',          'Kettle',          130),
  ('toaster',         'Toaster',         140),
  ('dishwasher',      'Dishwasher',      150)
ON CONFLICT (name) DO UPDATE SET
  display_name = EXCLUDED.display_name,
  sort_order   = EXCLUDED.sort_order;
