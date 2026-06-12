# Ticket: nutrition — add `satFat` aggregate to `DailyAggregateDto` (P2)

## Summary

The Nutrition page's six-cell stat band needs a sat-fat cell, but `DailyAggregateDto` carries
`MacroAggregateDto` rows for protein/carbs/fat/fibre only — saturated fat rides the
`microsActualSoFar` map by key convention (`"saturated_fat_g"`), so the cell has no
`plannedG`/`remainingG` and no first-class aggregate. Targets already treat satFat as a macro
(`TargetsDto.satFat` is a required `MacroTargetDto` —
[`schemas/nutrition.yaml` lines 88–150](../../src/main/resources/openapi/schemas/nutrition.yaml));
the aggregate side never caught up. Confirmed gap —
[`design/frontend/pages/nutrition.md` §10 Amendment (a)](../../design/frontend/pages/nutrition.md).

**Fix:** add `satFat: MacroAggregateDto` to `DailyAggregateDto` (`plannedG` / `actualSoFarG` /
`remainingG`), populated by the daily-aggregate calculator from the same per-slot saturated-fat
figures that currently feed the micros map. `WeeklyAggregateDto.perDay[]` and `weeklyTotal` reuse
`DailyAggregateDto`, so the week strip gets it for free.

### OpenAPI excerpt

```yaml
# schemas/nutrition.yaml — DailyAggregateDto
required: [caloriesPlanned, caloriesActualSoFar, caloriesRemaining, protein, carbs, fat, fibre, satFat, microsActualSoFar]
properties:
  satFat: { $ref: '#/MacroAggregateDto' }
```

## Edge-case checklist

- [ ] `plannedG` sums planned slot saturated fat; `actualSoFarG` sums decided actuals; `remainingG = target − actual` (consistent with how the other four macros compute remaining — mirror exactly)
- [ ] Slots without saturated-fat data contribute 0 (not null-poisoning the sum)
- [ ] `microsActualSoFar["saturated_fat_g"]` retained for one release (frontend cutover) or removed now — pin the choice in the PR; spec leans **keep** (other consumers may read the map)
- [ ] Weekly endpoint (`perDay` + `weeklyTotal`) carries the new field with no extra work
- [ ] Required-field addition: server always emits it (zero-aggregate on empty days), so codegen consumers don't break

## Files this ticket touches

```
MOD   src/main/resources/openapi/schemas/nutrition.yaml                                   (DailyAggregateDto + required)
MOD   src/main/java/com/example/mealprep/nutrition/api/dto/... DailyAggregateDto          (field)
MOD   src/main/java/com/example/mealprep/nutrition/domain/service/internal/...            (daily-aggregate calculator)
MOD   src/test/java/com/example/mealprep/nutrition/...                                    (aggregate maths + weekly pass-through)
```

## Acceptance / DoD

- [ ] `verify` + `spotless` clean; CI green
- [ ] Stat-band cell wireable: actual / target / remaining for sat fat, direction-aware colouring from `TargetsDto.satFat.direction`

Squash-merge with: `feat(nutrition): satFat MacroAggregateDto on DailyAggregateDto`

**Not in scope:** the floor-violations shape (sibling ticket
[`nutrition-weekly-floor-violations.md`](nutrition-weekly-floor-violations.md)).
