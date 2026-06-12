# Ticket: nutrition — OVERRIDDEN + `needsAiParse` has no legal repair transition (P1)

## Summary

When a free-text override fails AI parsing, the slot lands `actual.status = OVERRIDDEN` with
`needsAiParse = true` — and the user is **stuck**: the structured-edit transition (`POST
…/slots/{mealSlot}/edit`) requires the slot to be PENDING, and there are no backwards transitions.
The UI's only honest remedy today is "log a corrective snack", which mis-attributes the meal.
Flagged as the Nutrition page's first open question and explicitly raised for a backend ticket —
[`design/frontend/pages/nutrition.md` §8 Q1](../../design/frontend/pages/nutrition.md) (§3d also
specs the amber "Couldn't read that — enter values manually" banner whose Edit CTA this unblocks).

**Fix:** allow `edit` from `OVERRIDDEN` when `needsAiParse = true`. The edit supplies the
structured actuals the parse failed to produce; the slot transitions to `EDITED` and
`needsAiParse` clears. Keep the transition narrow — `OVERRIDDEN` with a *successful* parse stays
a decided state (no backwards transition; re-deciding a parsed override remains 422).

## Behavioural spec

- `POST /nutrition/intake/{date}/slots/{mealSlot}/edit` guard becomes:
  `PENDING` → allowed (unchanged) · `OVERRIDDEN && needsAiParse` → **allowed (new)** · any other
  decided state → 422 (unchanged).
- On the repair path: structured values (`calories`*, `proteinG`*, `carbsG`*, `fatG`*, `fibreG`,
  `micros`) replace the empty/failed actuals; `status → EDITED`; `needsAiParse → false`;
  `overrideFreeText` is **retained** (provenance — the user did say "a cheese sandwich"; the audit
  trail keeps it).
- Audit-log entry records the repair (`previousValue: OVERRIDDEN/needsAiParse=true → newValue:
  EDITED`), consistent with existing intake audit rows.
- Day aggregate recomputes (same as any edit).
- OpenAPI: update the edit operation's description + 422 condition text in
  `paths/nutrition.yaml`.

### OpenAPI excerpt (description-level change)

```yaml
# paths/nutrition.yaml — slots/{mealSlot}/edit
description: >
  Structured edit of a slot's actuals. Legal from PENDING, and — as the repair path —
  from OVERRIDDEN when needsAiParse=true (parse-failed override). Transitions the slot
  to EDITED and clears needsAiParse. 422 from any other decided state.
```

## Edge-case checklist

- [ ] PENDING → edit → EDITED (unchanged happy path)
- [ ] OVERRIDDEN + `needsAiParse=true` → edit → EDITED, `needsAiParse=false`, free text retained
- [ ] OVERRIDDEN + `needsAiParse=false` (parse succeeded) → edit → 422 (no backwards transition)
- [ ] CONFIRMED / EDITED / SKIPPED → edit → 422 (unchanged)
- [ ] Repair recomputes the daily aggregate (band updates)
- [ ] Audit row written for the repair transition
- [ ] Idempotent-ish double-submit: second edit after repair → 422 (slot now EDITED)

## Files this ticket touches

```
MOD   src/main/java/com/example/mealprep/nutrition/domain/service/internal/NutritionServiceImpl.java   (edit-transition guard, ~the OVERRIDDEN handling around line 1522)
MOD   src/main/resources/openapi/paths/nutrition.yaml                                                  (edit description + 422 text)
MOD   src/test/java/com/example/mealprep/nutrition/...IntakeIT / unit tests                            (repair path + guard matrix)
```

## Dependencies

None — pure transition-guard change in the nutrition module.

## Acceptance / DoD

- [ ] `verify` + `spotless` clean; CI green; the full transition matrix asserted in tests
- [ ] Frontend §3d "Edit CTA on needsAiParse banner" wireable without the corrective-snack workaround

Squash-merge with: `feat(nutrition): allow structured edit as the repair path for parse-failed overrides`

## What's NOT in scope

- Re-running the AI parse on demand (a "retry parse" action) — not requested by the spec; the
  manual edit *is* the repair.
- Any change to override/confirm/skip transitions.
