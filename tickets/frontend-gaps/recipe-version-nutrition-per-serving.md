# Ticket: recipe — expose `nutritionPerServing` on recipe version reads (P2)

## Summary

`RecipeDto` / `RecipeVersionDto` carry `nutritionStatus` but **no nutrition numbers** — the LLD
DTO had `nutritionPerServing`; the shipped contract dropped it. The recipe-detail hero's
"520 kcal · 38 g protein" pills are unwireable from any read: the only contract source is
`POST /nutrition/recipes/{id}/versions/{vid}/recalculate` — **a write op as a read workaround**.
Headline gap of the recipe-detail spec —
[`design/frontend/pages/recipe-detail.md` §11 Q1](../../design/frontend/pages/recipe-detail.md).

**Fix:** add a nullable `nutritionPerServing` object to `RecipeVersionDto` (and therefore
`RecipeDto.currentVersionBody`), shape mirroring `RecipeNutritionResultDto`'s figures:
`{ calories, proteinG, carbsG, fatG, fibreG, micros }`. Null until the nutrition module has
computed (`nutritionStatus = PENDING`); populated on CALCULATED and PARTIAL (PARTIAL = best
available numbers + the needs-review badge).

**Verify first (load-bearing):** the recipe-side write-back bridge (recipe-01g
nutrition-writer bridge) — the recalc endpoint may emit a `Warning` header flagging the write-back
as unwired ([recipe-detail.md §3](../../design/frontend/pages/recipe-detail.md)). If the version
entity does not yet persist the computed figures, **wiring that write-back is in scope here**
(the field is worthless without it); if it persists already, this is a mapper+schema ticket. The
alternative read (`GET /recipes/{id}/nutrition` per the LLD REST table) is rejected — an extra
round-trip for data that belongs on the version.

### OpenAPI excerpt

```yaml
# schemas/recipe.yaml — RecipeVersionDto
nutritionPerServing:
  type: object
  nullable: true
  description: 'Computed by the nutrition module; null until nutritionStatus leaves PENDING.'
  required: [calories, proteinG, carbsG, fatG, fibreG, micros]
  properties:
    calories: { type: integer, minimum: 0 }
    proteinG: { type: number, format: double, minimum: 0 }
    carbsG:   { type: number, format: double, minimum: 0 }
    fatG:     { type: number, format: double, minimum: 0 }
    fibreG:   { type: number, format: double, minimum: 0 }
    micros:
      type: object
      additionalProperties: { type: number, format: double, minimum: 0 }
```

## Edge-case checklist

- [ ] PENDING version → `nutritionPerServing: null` (pills hidden, "calculating…" caption)
- [ ] CALCULATED → full figures; PARTIAL → figures present alongside needs-review badges
- [ ] Recalculate (n1) updates the stored figures → next GET reflects them (the Warning-header caveat gone)
- [ ] New version (edit/revert/branch/promotion) starts PENDING/null until recomputed — verify the existing nutritionStatus reset behaviour and ride it
- [ ] Historical version reads (`GET …/versions/{n}`) carry their own stored figures where computed
- [ ] No cross-module boundary break: nutrition writes via the existing writer-bridge SPI, recipe never computes

## Files this ticket touches

```
MOD   src/main/resources/openapi/schemas/recipe.yaml                                  (RecipeVersionDto field)
MOD   src/main/java/com/example/mealprep/recipe/api/dto/... RecipeVersionDto          (+ mapper)
MOD?  src/main/java/com/example/mealprep/recipe/... nutrition writer-bridge + entity  (only if write-back persistence is unwired — verify)
MOD?  src/main/resources/db/migration/...                                             (only if the version table lacks the columns/JSONB — verify)
MOD   src/test/java/com/example/mealprep/recipe/...                                   (status matrix + recalc round-trip IT)
```

## Acceptance / DoD

- [ ] `verify` + `spotless` clean; CI green
- [ ] Hero pills wireable from `GET /recipes/{id}` alone; mock's "Q1 gap" flag removable
- [ ] Recalc → re-read round-trip IT proves read-after-write consistency

Squash-merge with: `feat(recipe): nutritionPerServing on RecipeVersionDto (read-side exposure of computed nutrition)`

**Not in scope:** nutrition calculation logic; per-recipe (vs per-version) rollups; the ratings
aggregate on list rows (→ [`recipe-list-search-endpoint.md`](recipe-list-search-endpoint.md)).
