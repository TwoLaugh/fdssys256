# Ticket: nutrition — `WeeklyAggregateDto.floorViolations` adopts `FloorViolationDto` (P2)

## Summary

`WeeklyAggregateDto.floorViolations` is a bare `string[]` of macro/micro keys ("whose weekly total
fell below the 7-day-summed floor") with **no day attribution and no magnitudes** — the week
strip's "protein floor missed · Tue" chips must be derived client-side by re-scanning `perDay[]`,
which is ambiguous. The schema already defines exactly the right shape, used today only by the
floor gate: `FloorViolationDto { date, macroOrMicro, floor, actual }`
([`schemas/nutrition.yaml` lines 800–807](../../src/main/resources/openapi/schemas/nutrition.yaml)).
Confirmed gap — [`design/frontend/pages/nutrition.md` §10 Amendment (b)](../../design/frontend/pages/nutrition.md).

**Fix:** change `floorViolations` from `string[]` to `FloorViolationDto[]`, and make `date`
nullable on the DTO:

- **Daily-enforcement floors** → one entry per violating day: `{date: 2026-06-09,
  macroOrMicro: "protein", floor: 180, actual: 142}` — the "· Tue" chip.
- **Weekly-average enforcement floors** → one entry with `date: null`, `floor` = 7-day-summed
  floor, `actual` = weekly total — renders "missed this week".

This is a **breaking schema change** (string → object). Acceptable now: no live frontend consumes
it (the mock derives client-side); land before wiring.

### OpenAPI excerpt

```yaml
# schemas/nutrition.yaml — WeeklyAggregateDto
floorViolations:
  type: array
  description: 'Floor violations for the week. date set for daily-enforcement floors; null for weekly-average floors.'
  items: { $ref: '#/FloorViolationDto' }
# FloorViolationDto.date gains nullable: true (verify FloorGateResultDto callers tolerate it — they always set a date, so no behaviour change there)
```

## Edge-case checklist

- [ ] Daily hard floor missed on two days → two dated entries
- [ ] Weekly-enforcement floor missed → single `date: null` entry with summed figures
- [ ] No violations → empty array (unchanged)
- [ ] Micro floors (`microTargets[].isHardFloor`) produce entries with the nutrient key in `macroOrMicro`
- [ ] `FloorGateResultDto` (the other `FloorViolationDto` consumer) unaffected — its producer always sets `date`
- [ ] Week-strip chips renderable without client-side `perDay` scanning

## Files this ticket touches

```
MOD   src/main/resources/openapi/schemas/nutrition.yaml                          (floorViolations item type; FloorViolationDto.date nullable)
MOD   src/main/java/com/example/mealprep/nutrition/api/dto/...                   (WeeklyAggregateDto field type)
MOD   src/main/java/com/example/mealprep/nutrition/domain/service/internal/...   (weekly aggregator emits structured entries per enforcement mode)
MOD   src/test/java/com/example/mealprep/nutrition/...                           (daily vs weekly enforcement matrix)
```

## Acceptance / DoD

- [ ] `verify` + `spotless` clean; CI green; enforcement matrix asserted
- [ ] Week-strip red chips wireable with day labels where days are attributable

Squash-merge with: `feat(nutrition): structured FloorViolationDto entries on WeeklyAggregateDto.floorViolations`

**Not in scope:** satFat aggregate (sibling
[`nutrition-daily-aggregate-satfat.md`](nutrition-daily-aggregate-satfat.md)); floor-gate evaluator changes.
