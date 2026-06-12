# Ticket: adaptation — enrich `PendingChangeListItemDto` (`optimisticVersion`, `status`, `resolvedAt`) (P2)

## Summary

Three page specs hit the same list-projection wall
([`schemas/adaptation.yaml` lines 20–35](../../src/main/resources/openapi/schemas/adaptation.yaml)):

1. **No `optimisticVersion`** — accept requires `expectedOptimisticVersion`, so one-tap accept
   from a list row is impossible; every accept is expand-then-accept (two calls). Flagged by
   [`today.md` §8 Q6](../../design/frontend/pages/today.md) (the Today teaser's Accept),
   [`recipe-detail.md` §11 Q5](../../design/frontend/pages/recipe-detail.md), and
   [`activity.md` §8 Q1](../../design/frontend/pages/activity.md) — "one backend ticket covers
   both."
2. **No `status` / `resolvedAt`** — the per-recipe pending-history rows
   (`GET /adaptation/recipes/{recipeId}/pending-history`) cannot say accepted/rejected/expired;
   the Activity drawer renders them neutrally and would otherwise N+1-hydrate
   ([`activity.md` §3d](../../design/frontend/pages/activity.md)).

**Fix:** add all three to `PendingChangeListItemDto` (the top-3 read and the pending-history read
share the projection): `status` (required — PENDING on the top-3 by definition, any state in
history), `resolvedAt` (nullable), `optimisticVersion` (required int).

### OpenAPI excerpt

```yaml
# schemas/adaptation.yaml — PendingChangeListItemDto
required: [id, recipeId, changeDimension, reasoningPreview, confidence, impactScore, createdAt, expiresAt, status, optimisticVersion]
properties:
  status: { $ref: '#/PendingChangeStatus' }
  resolvedAt: { type: string, format: date-time, nullable: true }
  optimisticVersion: { type: integer }
```

## Edge-case checklist

- [ ] Top-3 rows: `status = PENDING`, `resolvedAt = null`
- [ ] History rows: full status range incl. EXPIRED (sweep-set) and SUPERSEDED, `resolvedAt` set where decided
- [ ] Accept straight from a list row with its `optimisticVersion` works; a stale row (changed since list load) still 409s correctly
- [ ] List projection query stays single-select (fields are on the row — no join growth)
- [ ] UI guidance unchanged where it matters: the diff still lives on the detail DTO, so "expand before accept" remains the recommended flow — the version field removes the *forced* second call, not the diff review

## Files this ticket touches

```
MOD   src/main/resources/openapi/schemas/adaptation.yaml                                 (three fields + required)
MOD   src/main/java/com/example/mealprep/adaptation/api/dto/... PendingChangeListItemDto (fields)
MOD   src/main/java/com/example/mealprep/adaptation/api/mapper/...                       (projection mapping)
MOD   src/test/java/com/example/mealprep/adaptation/...                                  (top-3 + history shape assertions)
```

## Acceptance / DoD

- [ ] `verify` + `spotless` clean; CI green
- [ ] Activity history drawer shows outcomes without N+1; Today teaser Accept is GET-free

Squash-merge with: `feat(adaptation): status/resolvedAt/optimisticVersion on PendingChangeListItemDto`

**Not in scope:** `proposedDiff` schema publication and the "all my pending changes" list — P3,
see [`recipe-adaptation-p3-clarifications.md`](recipe-adaptation-p3-clarifications.md).
