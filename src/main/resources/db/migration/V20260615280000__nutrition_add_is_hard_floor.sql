-- Nutrition module — nutrition-4 (v1 conformance): per-target is_hard_floor flag.
--
-- LLD §NutritionFloorGateService (lines 774-776): "Each target (macro AND micro) carries an
-- is_hard_floor: boolean flag. When true, that target participates in the multiplicative gate;
-- when false, it surfaces as a warning only. Defaults: macros default to true (hard floor
-- enforcement); micros default to false (warning only)."
--
-- Previously the floor gate keyed off floorG-presence for macros and treated NO micro as
-- hard-floored. This migration adds the explicit per-target flag the gate now keys off, so a user
-- can opt a specific micro (e.g. iron in pregnancy) into the multiplicative gate.

-- Macro hard-floor flags. Default true per the LLD (macro hard-floor enforcement). Existing rows
-- adopt the default; the gate still only raises a macro violation when the macro ALSO carries a
-- non-null <macro>_floor_g (nothing to compare against otherwise).
ALTER TABLE nutrition_targets
    ADD COLUMN protein_is_hard_floor boolean NOT NULL DEFAULT true,
    ADD COLUMN carbs_is_hard_floor   boolean NOT NULL DEFAULT true,
    ADD COLUMN fat_is_hard_floor     boolean NOT NULL DEFAULT true,
    ADD COLUMN fibre_is_hard_floor   boolean NOT NULL DEFAULT true;

-- Micro hard-floor flag. Default false per the LLD (micros surface as warning only unless the user
-- toggles a specific micro on). Existing rows adopt the default (warning-only).
ALTER TABLE nutrition_micro_target
    ADD COLUMN is_hard_floor boolean NOT NULL DEFAULT false;
