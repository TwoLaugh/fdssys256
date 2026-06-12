# Ticket: recipe — import-dedup consistency: "import anyway" override + one-shot dedup gate (P2)

## Summary

The HLD's dedup dialog offers *"Merge, import as a variant branch, or import anyway?"* — but only
the variant branch is backed. Two gaps, plus an inconsistency
([`design/frontend/pages/recipes.md` §4c + §8 Q2/Q3](../../design/frontend/pages/recipes.md)):

1. **"Import anyway" has no override flag.** The 422 `recipe-import-duplicate` is deterministic —
   re-POSTing the same `ConfirmImportRequest` (or `CreateRecipeRequest`) 422s again forever. The
   dialog's third option is a dead button.
2. **One-shot `POST /imports/url` bypasses the dedup gate entirely** (LLD: backward-compat,
   "persists directly without the dedup gate"). Inconsistent safety: the same URL 422s on the
   preview→confirm path but slips through one-shot.
3. ("Merge" has no endpoint — stays v2; the dialog renders it as "open existing and edit".)

**Fix:**

- Add `ignoreDuplicateOfRecipeId: uuid (nullable)` to both `ConfirmImportRequest` and
  `CreateRecipeRequest`. Chosen over a bare `forceImport` boolean: the client must name the
  candidate it is overriding, so a *different* collision (new candidate appears between preview
  and confirm) still 422s — no blind force. On match: persist, and record
  `duplicateOfRecipeId` on the import-provenance row (field exists on `RecipeImportDto`).
- Run the same dedup gate inside `POST /imports/url` (one-shot). It gains the same 422 +
  override-field behaviour. The page doesn't expose one-shot, but the API stops being the
  workaround that skips safety.

### OpenAPI excerpt

```yaml
# schemas/recipe.yaml — ConfirmImportRequest + CreateRecipeRequest (+ the one-shot import request)
ignoreDuplicateOfRecipeId:
  type: string
  format: uuid
  nullable: true
  description: >
    Dedup override: persist despite an ingredient-overlap collision with exactly this recipe.
    A collision with any other recipe still returns 422 recipe-import-duplicate.
```

## Edge-case checklist

- [ ] 422 carries `candidateRecipeId`; re-submit with `ignoreDuplicateOfRecipeId = candidateRecipeId` → 201
- [ ] Re-submit naming a *different* id than the actual collision candidate → 422 again (no blind force)
- [ ] Field set but no collision occurs → ignored, normal 201
- [ ] Provenance row records `duplicateOfRecipeId`; recipe-detail's "imported as a duplicate of …" link renders
- [ ] One-shot `/imports/url`: duplicate URL now 422s; override field honoured; non-duplicate behaviour unchanged
- [ ] Manual create (`POST /recipes`) override path identical

## Files this ticket touches

```
MOD   src/main/java/com/example/mealprep/recipe/api/controller/RecipeImportsController.java + RecipesController.java
MOD   src/main/java/com/example/mealprep/recipe/domain/service/internal/...                 (dedup gate: override check + one-shot wiring)
MOD   src/main/resources/openapi/schemas/recipe.yaml + paths/recipe.yaml                    (field + one-shot 422 response)
MOD   src/test/java/com/example/mealprep/recipe/...                                         (override matrix on confirm/create/one-shot)
```

## Acceptance / DoD

- [ ] `verify` + `spotless` clean; CI green
- [ ] The §4c dialog's "Import anyway" wireable; mock's gap-tooltip removable
- [ ] One-shot and preview→confirm paths give the same answer for the same URL

Squash-merge with: `feat(recipe): ignoreDuplicateOfRecipeId dedup override + dedup gate on one-shot import`

**Not in scope:** "Merge" (v2 — needs a merge model, not a flag); deprecating one-shot (kept,
now gated); the dedup threshold/algorithm.
