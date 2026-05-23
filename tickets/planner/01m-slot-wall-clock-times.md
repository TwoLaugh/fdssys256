# Ticket: planner — 01m Slot Wall-Clock Meal Times (design decision + schema + projection)

## Summary

Give the planner a real **wall-clock meal time** per slot so downstream consumers stop approximating it from slot-kind defaults. Today `UpcomingSlotView` ([`src/main/java/com/example/mealprep/planner/api/dto/UpcomingSlotView.java:14-17`](../../src/main/java/com/example/mealprep/planner/api/dto/UpcomingSlotView.java)) carries only `dayDate`, `kind`, `timeBudgetMin` and explicitly notes: *"The planner does not (yet) store a wall-clock meal time or an explicit prep-step time... this projection therefore carries the raw scheduling facts; the consuming scanner derives the prep moment."* The notification scanners then hard-code defaults — `PrepReminderScanner.defaultMealTime()` ([`src/main/java/com/example/mealprep/notification/scanner/PrepReminderScanner.java:142-150`](../../src/main/java/com/example/mealprep/notification/scanner/PrepReminderScanner.java)): `BREAKFAST 08:00 / LUNCH 12:30 / DINNER 18:00 / SNACK 15:00 / CUSTOM 12:00` — ignoring the user's actual schedule.

Per [`design/meal-planner.md` lines 150-163 (MealSlot shape — no time field today), 184-189 (slot config owned by lifestyle config)](../../design/meal-planner.md) and [`design/system-overview.md`](../../design/system-overview.md) (prep reminders). The natural source of meal times already exists: the **lifestyle-config `meal_timing.preferred_schedule`** ([`design/preference-model.md` lines 205-213](../../design/preference-model.md)), shipped as `LifestyleConfigDocument.MealTiming(MealSchedule preferredSchedule, ...)` where `MealSchedule` is a `Map<String,String>` of slot-kind → time-range string (e.g. `"breakfast": "07:00-08:00"`) ([`src/main/java/com/example/mealprep/preference/domain/document/LifestyleConfigDocument.java:58-65`](../../src/main/java/com/example/mealprep/preference/domain/document/LifestyleConfigDocument.java)).

This ticket owns the **DESIGN DECISION** (where meal times come from) + the **schema/projection** changes. The scanner re-wiring is the sibling ticket `notification-01c` (`tickets/notification/01c-scanners-real-times.md`) — split because the decision + planner-side plumbing is one reviewable unit and the cross-module scanner re-wire is another.

Numbered `01m` (planner is at 01a–01l; this is an additive schema + projection change in the same phase, not a new phase — matches the module convention).

Closes: the `UpcomingSlotView` time-approximation gap noted in its own javadoc + the scanner `defaultMealTime`/`expiryDate`-as-anchor approximations.

## THE DESIGN DECISION (surfaced explicitly, with recommendation)

**Question**: where does a slot's wall-clock meal time come from — the lifestyle-config `meal_timing`, a per-plan/per-slot override, or both?

**Option A — lifestyle-config only (derive at read time).** The planner never stores a time; `getUpcomingSlots` joins the household owner's `LifestyleConfig.meal_timing.preferred_schedule`, parses the range start (e.g. `"18:30-19:30"` → `18:30`) for the slot's `kind`, and projects it onto `dayDate`. No planner schema change.
- **Pros**: zero planner schema/migration; single source of truth; respects the HLD's "slot structure is owned by lifestyle config" ([`design/meal-planner.md:185`](../../design/meal-planner.md)); automatically tracks the user when they change their schedule.
- **Cons**: a cross-module read (planner → preference) inside a hot projection; no per-plan override (a one-off "dinner at 20:00 this Friday" can't be expressed); range→single-time parsing lives in the planner.

**Option B — per-slot stored time on `MealSlot`.** Add `meal_time LocalTime` (and optional `prep_step_at_time`) to `planner_meal_slots`, populated at plan-composition time from lifestyle config, overridable per slot.
- **Pros**: per-slot/per-plan overrides; the projection is a pure planner read (no cross-module join); supports the future "pre-cook actions" concern the `UpcomingSlotView` javadoc references.
- **Cons**: planner schema + migration + backfill of existing plans; the stored time goes stale if the user changes their schedule after composition (acceptable — a plan is a point-in-time artefact); composition must read lifestyle config to seed it.

**Option C — both: store a nullable per-slot override, fall back to lifestyle-config at read.** `meal_time LocalTime NULL` on the slot; the projection coalesces `slot.meal_time ?? lifestyleConfig.meal_timing(kind) ?? slotKindDefault`.

**RECOMMENDATION: Option C (store-nullable-override + lifestyle-config fallback + slot-kind default as last resort).**
- It honours the HLD's source-of-truth (lifestyle config) for the common case while leaving room for the per-plan override the future pre-cook-actions feature needs — without forcing that feature now (the override column ships nullable + unused).
- The three-level coalesce means the system degrades gracefully: a user with no lifestyle config still gets the slot-kind default (today's behaviour), so this is strictly additive — no regression.
- The cross-module read is bounded: the projection batch-loads the household owner's lifestyle config ONCE per `getUpcomingSlots` call (not per slot), so the hot-path cost is one extra query per scan-user, not per slot.
- **What ships in THIS ticket**: the nullable `meal_time` column (override, populated only by a future feature — left null at composition for now), the lifestyle-config-fallback resolution **in the projection**, and the slot-kind default as the last-resort floor. The scanners consume the resolved time in `01c`.

**Worth user review**: whether to also seed `meal_time` from lifestyle config at composition time (making it non-null for new plans) vs leaving it null + resolving at read. The recommendation leaves it null-at-composition + resolve-at-read (avoids stale-on-schedule-change and avoids touching the composition path in this ticket); seeding-at-composition is a clean follow-up if per-plan snapshots are later wanted.

## Behavioural spec

### Schema

1. Migration `V20260615240200__planner_add_meal_slot_time.sql`:
   ```sql
   ALTER TABLE planner_meal_slots ADD COLUMN meal_time time;            -- nullable per-slot override; null = resolve from lifestyle config
   ALTER TABLE planner_meal_slots ADD COLUMN prep_step_at_time time;    -- nullable; reserved for the future pre-cook-actions feature; unused in 01m
   ```
   (Timestamp continues the scheme; latest in-tree is `V20260615230000`. `240000`/`240100` are reserved by `preference-01g`/`nutrition-01i`; this uses `240200`. Coordinate the exact free slot at merge.) Both nullable → no backfill needed; existing plans get null → resolve-at-read.
2. Add to `MealSlot` entity ([`MealSlot.java`](../../src/main/java/com/example/mealprep/planner/domain/entity/MealSlot.java)): `@Column(name="meal_time") private LocalTime mealTime;` and `@Column(name="prep_step_at_time") private LocalTime prepStepAtTime;`. No `@Version` change (parent `Plan.version` covers the aggregate per [`Day.java:25-26`](../../src/main/java/com/example/mealprep/planner/domain/entity/Day.java)).

### Projection resolution

3. **Extend `UpcomingSlotView`** ([`UpcomingSlotView.java`](../../src/main/java/com/example/mealprep/planner/api/dto/UpcomingSlotView.java)) with a resolved `LocalTime mealTime` (the coalesced wall-clock time, never null) and a nullable `LocalTime prepStepAtTime` (the stored override, or null). Update the javadoc to remove the "does not yet store a wall-clock meal time" caveat and document the three-level coalesce.
4. **Resolution logic** in `getUpcomingSlots` ([`PlanQueryService.getUpcomingSlots`](../../src/main/java/com/example/mealprep/planner/domain/service/PlanQueryService.java), impl in `PlannerServiceImpl`):
   - Batch-load the household **owner's** `LifestyleConfig` via `PreferenceQueryService.getLifestyleConfig(ownerUserId)` ONCE per call (not per slot). The household→owner mapping comes from the household module (the scanners already resolve `householdId` → and the planner has `householdId` on the slot). **Worth implementer review** — confirm how to get the owner userId from `householdId` (likely `HouseholdQueryService`); if the planner can't reach it cleanly, pass the resolved lifestyle config (or its meal-timing map) into `getUpcomingSlots` from the caller. Prefer the planner resolving it internally for a clean projection.
   - For each slot: `resolvedMealTime = slot.mealTime` (override) `?? parseRangeStart(lifestyleConfig.mealTiming.preferredSchedule.times.get(kindKey))` `?? slotKindDefault(kind)`.
   - `parseRangeStart("18:30-19:30")` → `LocalTime 18:30` (take the start of the range; tolerate a bare `"18:30"`). Malformed string → fall through to the slot-kind default + DEBUG log.
   - `kindKey`: map `SlotKind` → the lifestyle-config map key (`BREAKFAST`→`breakfast`, etc.; the map keys are canonical-cased by convention per [`LifestyleConfigDocument.java:61-63`](../../src/main/java/com/example/mealprep/preference/domain/document/LifestyleConfigDocument.java)).
   - **Slot-kind default floor** (last resort, preserves today's behaviour): the same table currently in `PrepReminderScanner.defaultMealTime` ([`PrepReminderScanner.java:142-150`](../../src/main/java/com/example/mealprep/notification/scanner/PrepReminderScanner.java)) — `BREAKFAST 08:00 / LUNCH 12:30 / DINNER 18:00 / SNACK 15:00 / CUSTOM 12:00`. Move this constant into the planner (a `SlotKindDefaultTimes` helper) so it has ONE home; `01c` deletes the scanner's copy.

### Cross-cutting

5. **No cross-module cycle**: planner already depends on preference for other reads? Verify — if planner→preference is a new edge, confirm it doesn't create a cycle (preference must not depend on planner; it doesn't — preference is upstream). If a cycle risk exists, the alternative is to resolve the time in the **scanner** (notification depends on both planner and preference already — see `01c`) rather than the projection. **Worth implementer review — this is the load-bearing architectural call.** Recommendation: resolve in the planner projection if planner→preference is acyclic; otherwise resolve in the notification scanner (`01c`) which already injects both. (`PrepReminderScanner` already imports both `PlanQueryService` and would import `LifestyleConfig`.)
6. ArchUnit: no new repository. The `LifestyleConfig` read goes through the public `PreferenceQueryService` facade (already re-exported).

### Events

7. **Published / Consumed**: none. This is a read-projection + schema change.

## Database

```
NEW   src/main/resources/db/migration/V20260615240200__planner_add_meal_slot_time.sql
```

Schema per §1. Both columns nullable → no backfill.

## OpenAPI updates

**No OpenAPI changes.** `getUpcomingSlots`/`UpcomingSlotView` is an internal cross-module read with no HTTP exposure ([`PlanQueryService.getUpcomingSlots` javadoc: "No HTTP exposure"](../../src/main/java/com/example/mealprep/planner/domain/service/PlanQueryService.java)). The slots inside `PlanDto` (which IS HTTP-exposed via the planner controllers) gain a `mealTime` field on `MealSlotDto` — **if** `MealSlotDto` is extended too (recommended for consistency): then add `mealTime` (and `prepStepAtTime` nullable) to the `MealSlotDto` schema in `src/main/resources/openapi/schemas/planner.yaml`. **GOTCHA (Redocly nullable-type-sibling)**: `prepStepAtTime` with `nullable: true` needs a `type: string` sibling (`format: time`). **Worth implementer review** — whether to surface meal time on the public `PlanDto` now or keep it projection-only; recommendation: extend `MealSlotDto` too (the frontend will want it), accepting the OpenAPI touch.

## Edge-case checklist

- [ ] Migration applies cleanly; `FlywayMigrationIT` passes; `ddl-auto=validate` accepts the two new nullable `time` columns.
- [ ] **Override precedence**: a slot with `meal_time = 20:00` set → projection returns 20:00 regardless of lifestyle config.
- [ ] **Lifestyle-config fallback**: slot `meal_time` null + owner's `meal_timing.preferred_schedule = {"dinner":"18:30-19:30"}` + slot kind DINNER → projection returns 18:30 (range start).
- [ ] **Bare time string**: `"19:00"` (no range) → 19:00.
- [ ] **Slot-kind default floor**: slot `meal_time` null + no lifestyle config (or no entry for that kind) → slot-kind default (DINNER → 18:00); identical to today's behaviour (no regression).
- [ ] **Malformed range string**: `"evening"` → falls through to slot-kind default + DEBUG log; never throws.
- [ ] **Resolved `mealTime` never null** in `UpcomingSlotView` (the coalesce always terminates at the slot-kind default).
- [ ] **Batch load**: lifestyle config loaded ONCE per `getUpcomingSlots` call, not per slot (verified via Hibernate statistics — query count independent of slot count).
- [ ] **No cross-module cycle**: ArchUnit confirms planner→preference (if added) is acyclic; OR the resolution lives in the scanner per §5.
- [ ] **CUSTOM slot**: maps to its lifestyle-config key if present, else CUSTOM default 12:00.
- [ ] **Weekend brunch**: a `meal_timing` key like `"brunch"` with no matching `SlotKind` → no fallback hit, slot-kind default used (documented limitation; brunch slots are CUSTOM-kind in practice).
- [ ] **`prepStepAtTime`** column is null for all existing + new slots (reserved; not populated in 01m).
- [ ] **`MealSlotDto`/OpenAPI** (if extended): `mealTime` serialises as `"18:30"`; `prepStepAtTime` nullable with a `type` sibling; redocly lint clean.
- [ ] **Existing planner ITs** (`PlanQueryServiceIT` etc.) still green — the projection change is additive.

## Files this ticket touches

```
NEW   src/main/resources/db/migration/V20260615240200__planner_add_meal_slot_time.sql

MOD   src/main/java/com/example/mealprep/planner/domain/entity/MealSlot.java                                     (mealTime + prepStepAtTime columns)
MOD   src/main/java/com/example/mealprep/planner/api/dto/UpcomingSlotView.java                                   (resolved mealTime + prepStepAtTime; javadoc)
MOD   src/main/java/com/example/mealprep/planner/domain/service/internal/PlannerServiceImpl.java                 (getUpcomingSlots resolution + lifestyle-config batch read)
NEW   src/main/java/com/example/mealprep/planner/domain/service/internal/SlotKindDefaultTimes.java               (the one-home slot-kind default table)

MOD   src/main/java/com/example/mealprep/planner/api/dto/MealSlotDto.java                                        (mealTime + prepStepAtTime — if surfaced on PlanDto)
MOD   src/main/java/com/example/mealprep/planner/api/mapper/PlanMapper.java                                      (map the new fields — if surfaced)
MOD   src/main/resources/openapi/schemas/planner.yaml                                                            (MealSlotDto fields — if surfaced; nullable-type-sibling)

NEW   src/test/java/com/example/mealprep/planner/UpcomingSlotTimeResolutionTest.java                             (unit — three-level coalesce, range parse, malformed)
MOD   src/test/java/com/example/mealprep/planner/PlanQueryServiceIT.java                                         (assert resolved mealTime; batch-load query count)
```

Total: ~3 new + 6 mods. Estimated agent runtime 3-4 hours (the design decision is made here; implementation is a column + a coalesce + a batch read).

## Dependencies

- **Hard dependency**: `planner-01a` (merged) — `planner_meal_slots`, `MealSlot`, `Day`, `Plan`.
- **Hard dependency**: `planner-01c` (merged) — `PlanQueryService.getUpcomingSlots` + `UpcomingSlotView` (this ticket extends them).
- **Hard dependency**: `preference` lifestyle-config (merged — `LifestyleConfigDocument.MealTiming`, `PreferenceQueryService.getLifestyleConfig`).
- **Downstream consumer**: `tickets/notification/01c-scanners-real-times.md` (consumes the resolved `mealTime`).

## Acceptance / DoD

- [ ] `./mvnw -Dspotless.check.skip=true verify` passes
- [ ] `./mvnw spotless:apply` then `./mvnw spotless:check` clean
- [ ] **Run the full planner module IT suite locally with Docker** + `redocly lint` (if `MealSlotDto` extended) + spotless before pushing. Docker IS available locally. Run ITs in ~3-class batches (Hikari pool-exhaustion flake on big sweeps).
- [ ] CI green (build + spotless + OpenAPI lint + ArchUnit)
- [ ] All edge-case items above ticked
- [ ] **The design decision is recorded** in the PR description (Option C, with the rationale + the "worth user review" seed-at-composition open question).
- [ ] PR description traces: a slot with no override + owner lifestyle config `dinner: 18:30-19:30` → `UpcomingSlotView.mealTime = 18:30`; a slot with no config → 18:00 default (no regression).

## What's NOT in scope

- **Re-wiring the notification scanners** to consume the resolved time — `notification-01c`.
- **Seeding `meal_time` at plan composition** from lifestyle config (the open question) — clean follow-up if per-plan snapshots are wanted.
- **The "pre-cook actions" feature** that populates `prep_step_at_time` — the column ships reserved/unused.
- **Per-day meal-time overrides** (e.g. "dinner at 20:00 only on Fridays") — the per-slot override column supports it structurally but no UI/composition path populates it here.
- **Defrost anchoring off real meal times** — `DefrostReminderScanner` currently anchors on the provisions `expiryDate` ([`DefrostReminderScanner.java:89`](../../src/main/java/com/example/mealprep/notification/scanner/DefrostReminderScanner.java)); switching it to slot meal times is part of `notification-01c`'s scope.

Squash-merge with: `feat(planner): 01m — slot wall-clock meal times (lifestyle-config fallback + nullable override; design decision Option C)`
