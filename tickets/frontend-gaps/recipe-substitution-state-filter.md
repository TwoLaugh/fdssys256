# Ticket: recipe — `state` filter on the substitutions list (PROPOSED rows are unlistable) (P2)

## Summary

The substitution read endpoints (`GET /recipes/{recipeId}/substitutions?versionId` and
`…/substitutions/active`) filter to **ACCEPTED only**. A substitution in PROPOSED state — whether
user-proposed via `POST /substitutions` or pipeline-proposed — has **no read endpoint**: the
accept/reject UI only works on rows the client happens to remember from the 201 response. After a
reload, pending proposals vanish from the panel. Flagged by
[`design/frontend/pages/recipe-detail.md` §6 + §11 Q2](../../design/frontend/pages/recipe-detail.md).

**Fix:** add a `state` query param to `GET /recipes/{recipeId}/substitutions`:
`state = PROPOSED | ACCEPTED | REJECTED | SUPERSEDED | ALL`, **default ACCEPTED** (back-compat —
existing callers unchanged). `…/substitutions/active` stays as-is (it is the "active overlays"
semantic read).

### OpenAPI excerpt

```yaml
# paths/recipe.yaml — /recipes/{recipeId}/substitutions
parameters:
  - name: state
    in: query
    schema:
      type: string
      enum: [PROPOSED, ACCEPTED, REJECTED, SUPERSEDED, ALL]
      default: ACCEPTED
```

## Edge-case checklist

- [ ] Default (no param) returns exactly what it returns today (ACCEPTED)
- [ ] `state=PROPOSED` lists user- and pipeline-proposed rows with their `version` (the lifecycle calls need `expectedVersion`)
- [ ] `state=ALL` returns every state; `versionId` filter composes with `state`
- [ ] Unknown state value → 400
- [ ] Rows carry `state` + `promotedToVersionId` already (DTO unchanged — this is a query-surface ticket only)

## Files this ticket touches

```
MOD   src/main/java/com/example/mealprep/recipe/api/controller/RecipeSubstitutionsController.java  (param)
MOD   src/main/java/com/example/mealprep/recipe/domain/service/internal/RecipeServiceImpl.java     (query filter)
MOD   src/main/resources/openapi/paths/recipe.yaml                                                 (param)
MOD   src/test/java/com/example/mealprep/recipe/...                                                (filter matrix)
```

## Acceptance / DoD

- [ ] `verify` + `spotless` clean; CI green
- [ ] Recipe-detail substitutions panel can list PROPOSED rows after a reload (accept/reject buttons work from a fresh GET)

Squash-merge with: `feat(recipe): state query filter on the substitutions list (PROPOSED rows listable)`

**Not in scope:** substitution lifecycle semantics (REJECTED→re-accept drift is P3 —
[`recipe-adaptation-p3-clarifications.md`](recipe-adaptation-p3-clarifications.md)).
