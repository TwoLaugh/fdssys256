-- Preference module — 01b allergen derivatives lookup table.
-- See lld/preference.md §V20260501120000 — Hard constraints (allergen_derivatives near line 122).
--
-- Reference data consumed by HardConstraintFilterService to expand a stored allergy
-- (e.g. "peanut") to its known derivative ingredient keys (e.g. "peanut_oil",
-- "peanut_butter"). Seeded by the repeatable migration R__preference_seed_allergen_derivatives.

CREATE TABLE preference_allergen_derivatives (
    id          uuid         PRIMARY KEY,
    allergen    varchar(64)  NOT NULL,
    derivative  varchar(128) NOT NULL,
    UNIQUE (allergen, derivative)
);
-- Hot lookup: HardConstraintFilterService expands the user's allergies to derivatives once per call.
CREATE INDEX idx_pref_allergen_derivatives_allergen
    ON preference_allergen_derivatives (allergen);
