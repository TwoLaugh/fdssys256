-- Planner-01m: per-slot wall-clock meal time + reserved pre-cook-actions time.
-- See tickets/planner/01m-slot-wall-clock-times.md (design decision Option C).
--
-- Both columns are nullable, so no backfill is needed: existing slots get NULL and the
-- getUpcomingSlots projection resolves the wall-clock time at read time via the three-level
-- coalesce (slot override -> lifestyle-config meal_timing -> slot-kind default).
--
-- No index: meal_time is read alongside the slot row inside the hydrated plan-aggregate read;
-- it is never filtered or joined on.

ALTER TABLE planner_meal_slots
    ADD COLUMN meal_time         time,  -- nullable per-slot override; NULL = resolve from lifestyle config
    ADD COLUMN prep_step_at_time time;  -- nullable; reserved for the future pre-cook-actions feature; unused in 01m

COMMENT ON COLUMN planner_meal_slots.meal_time IS
    'Optional per-slot wall-clock meal-time override. NULL = the getUpcomingSlots projection resolves it from the household owner''s lifestyle-config meal_timing, falling back to the slot-kind default. Added in tickets/planner/01m-slot-wall-clock-times.md.';
COMMENT ON COLUMN planner_meal_slots.prep_step_at_time IS
    'Reserved for the future pre-cook-actions feature; unused (always NULL) as of planner-01m. Added in tickets/planner/01m-slot-wall-clock-times.md.';
