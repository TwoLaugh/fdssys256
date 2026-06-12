# Ticket: planner — resolved `effectiveMealTime` on `MealSlotDto` (P2)

## Summary

`MealSlotDto.mealTime` is the **raw nullable per-slot override** (planner-01m). The three-level
resolution — slot override → lifestyle-config meal schedule → slot-kind default — lives only in
the internal `UpcomingSlotView` projection with no HTTP exposure. The Plan grid and Today timeline
therefore either replicate the coalesce client-side (re-reading preferences lifestyle-config) or
show times only when the override is set — and the "start by 18:35" lead-time hints degrade with
it. Flagged by [`design/frontend/pages/plan.md` §8 Q3](../../design/frontend/pages/plan.md) and
[`today.md` §3b](../../design/frontend/pages/today.md).

**Fix:** add a server-resolved, **non-null** `effectiveMealTime` (HH:mm) to `MealSlotDto`,
computed by the DTO mapper with the same coalesce `UpcomingSlotView` uses (extract/reuse that
resolver — one source of truth). Keep raw `mealTime` (the editor still needs to know whether an
override exists). Optionally add `mealTimeSource: SLOT_OVERRIDE | LIFESTYLE_SCHEDULE |
KIND_DEFAULT` — cheap while in there, lets the UI caption "default time"; include it.

### OpenAPI excerpt

```yaml
# schemas/planner.yaml — MealSlotDto
effectiveMealTime:
  type: string
  format: time
  description: 'Resolved serve time: slot override → lifestyle-config schedule → slot-kind default. Never null.'
mealTimeSource:
  type: string
  enum: [SLOT_OVERRIDE, LIFESTYLE_SCHEDULE, KIND_DEFAULT]
```

## Edge-case checklist

- [ ] Override set → `effectiveMealTime == mealTime`, source SLOT_OVERRIDE
- [ ] No override, lifestyle schedule has the slot kind → schedule time, source LIFESTYLE_SCHEDULE
- [ ] Neither → kind default, source KIND_DEFAULT (CUSTOM kinds: pin which default applies — verify what `UpcomingSlotView` does and match it)
- [ ] User without a lifestyle config (pre-onboarding) → kind default, no 500
- [ ] Resolution logic shared with `UpcomingSlotView` (no second copy — refactor to a common resolver if needed)
- [ ] Plan reads don't N+1 the preference module (one lifestyle-config read per plan mapping, not per slot)

## Files this ticket touches

```
MOD   src/main/resources/openapi/schemas/planner.yaml                              (two fields)
MOD   src/main/java/com/example/mealprep/planner/api/dto/... MealSlotDto           (fields)
MOD   src/main/java/com/example/mealprep/planner/api/mapper/...                    (resolver wiring)
MOD/NEW src/main/java/com/example/mealprep/planner/domain/service/internal/...     (shared MealTimeResolver extracted from the UpcomingSlotView path)
MOD   src/test/java/com/example/mealprep/planner/...                               (coalesce matrix + N+1 stats)
```

## Acceptance / DoD

- [ ] `verify` + `spotless` clean; CI green; coalesce matrix asserted
- [ ] Grid serve times + "start by" hints wireable with no client-side preference reads

Squash-merge with: `feat(planner): resolved effectiveMealTime (+source) on MealSlotDto`

**Not in scope:** editing meal times (planner-01m shipped the write); lifestyle-config schedule shape.
