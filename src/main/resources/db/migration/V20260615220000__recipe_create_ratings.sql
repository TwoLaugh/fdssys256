-- Recipe module — 02b multi-dimensional rating (per-version, 4 dimensions).
-- Ratings attach to the planned slot's pinned recipe_version, NOT the recipe. The recipe_id is
-- denormalised onto the row for fast recipe-level aggregate lookups; the enforced FK is on
-- version_id (ON DELETE CASCADE — deleting a RecipeVersion removes its ratings).
--
-- Aggregate is computed server-side at write time (see RecipeRatingServiceImpl) and stored so the
-- compact planner/list displays read one indexed column. taste is required (chk_taste_required +
-- @NotNull on the entity); the other three dimensions are nullable and coalesce to taste in the
-- aggregate formula.
--
-- The aggregate materialised view (ticket §2) is intentionally skipped: live SUM/COUNT over the
-- indexed columns is cheap at the expected rating frequency. Tracked in the ticket's
-- "What's NOT in scope".

CREATE TABLE recipe_ratings (
    id                       uuid          PRIMARY KEY,
    recipe_id                uuid          NOT NULL,                  -- denormalised; FK is on version_id
    version_id               uuid          NOT NULL REFERENCES recipe_versions(id) ON DELETE CASCADE,
    user_id                  uuid          NOT NULL,                  -- the rater; no FK per the cross-module rule
    household_id             uuid,                                     -- nullable; populated when rating a household meal
    slot_id                  uuid,                                     -- nullable; the planned meal slot rated (if any)
    taste                    integer,                                  -- 0-100 nullable at column level; chk_taste_required forces non-null
    effort_worth_it          integer,                                  -- 0-100 nullable
    portion_fit              integer,                                  -- 0-100 nullable
    repeat_value             integer,                                  -- 0-100 nullable
    aggregate                integer       NOT NULL,                   -- computed, 0-100
    notes                    varchar(1000),                            -- optional free-text
    trace_id                 uuid,
    optimistic_version       bigint        NOT NULL DEFAULT 0,
    created_at               timestamptz   NOT NULL,
    updated_at               timestamptz   NOT NULL,
    CONSTRAINT chk_taste_range     CHECK (taste IS NULL OR (taste >= 0 AND taste <= 100)),
    CONSTRAINT chk_effort_range    CHECK (effort_worth_it IS NULL OR (effort_worth_it >= 0 AND effort_worth_it <= 100)),
    CONSTRAINT chk_portion_range   CHECK (portion_fit IS NULL OR (portion_fit >= 0 AND portion_fit <= 100)),
    CONSTRAINT chk_repeat_range    CHECK (repeat_value IS NULL OR (repeat_value >= 0 AND repeat_value <= 100)),
    CONSTRAINT chk_aggregate_range CHECK (aggregate >= 0 AND aggregate <= 100),
    CONSTRAINT chk_taste_required  CHECK (taste IS NOT NULL)
);

CREATE INDEX idx_recipe_ratings_version ON recipe_ratings (version_id, created_at DESC);
CREATE INDEX idx_recipe_ratings_recipe  ON recipe_ratings (recipe_id, created_at DESC);
CREATE INDEX idx_recipe_ratings_user    ON recipe_ratings (user_id, created_at DESC);
CREATE INDEX idx_recipe_ratings_slot    ON recipe_ratings (slot_id) WHERE slot_id IS NOT NULL;

-- A user gets one rating per version; POST-on-existing is a 409 (PUT updates). Enforced both here
-- and via RecipeRatingRepository.findByVersionIdAndUserId in the service.
CREATE UNIQUE INDEX uq_recipe_ratings_version_user ON recipe_ratings (version_id, user_id);
