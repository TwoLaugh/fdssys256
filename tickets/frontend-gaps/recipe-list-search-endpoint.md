# Ticket: recipe — `GET /api/v1/recipes` library list/search endpoint (P1)

## Summary

The Recipes page's **core read — a paginated list/search of the library — has no endpoint in the
shipped contract**. This is the headline gap of the page-spec programme
([`design/frontend/pages/recipes.md` §2 #1 / §8 Q1](../../design/frontend/pages/recipes.md)).
The LLD specifies `GET /`, `/user-catalogue`, `/system-catalogue` plus `RecipeSearchCriteriaDto`
returning `Page<RecipeDto>` (`lld/recipe.md` §Search/§REST), but none of it shipped — the
controller's own javadoc defers it:
[`RecipesController.java` line 33](../../src/main/java/com/example/mealprep/recipe/api/controller/RecipesController.java):
*"user-private filtering belongs in search/list endpoints later."* The only listing that exists is
the planner-internal `findPlannableCandidates`.

Ship **one** paginated endpoint (not the LLD's three — a `catalogue` filter covers all three views):

```
GET /api/v1/recipes?catalogue&namePattern&cuisine&maxTotalTimeMins&minDataQuality&includeArchived&page&size
```

**Unblocks:** the entire `/recipes` library grid (load, search, filter, paginate) — the page is
specified against `RecipeDto` and "wires up the moment the listing lands."

## Behavioural spec

### Query parameters (the §3b control mapping, subset of the LLD criteria record)

| Param | Type | Semantics |
|---|---|---|
| `catalogue` | enum `USER \| SYSTEM`, optional | absent → both: the caller's USER rows + all SYSTEM rows |
| `namePattern` | string ≤160, optional | case-insensitive substring match on `name` |
| `cuisine` | string ≤64, optional | exact match on `currentVersionBody.metadata.cuisine` |
| `maxTotalTimeMins` | int ≥0, optional | `metadata.totalTimeMins <= value` |
| `minDataQuality` | enum, optional | **ordinal floor**, not equality: `USER_VERIFIED > IMPORTED ≈ AI_GENERATED > WEB_DISCOVERED` (pin the exact ordering of the tie in the OpenAPI description) |
| `includeArchived` | boolean, default `false` | `false` → `archivedAt IS NULL` only |
| `page` / `size` | Spring conventions | size default 20, max 100 (project-wide pagination rule, infra-01b) |

### Visibility rules (the "user-private filtering" the javadoc deferred)

- **USER-catalogue rows are private**: only rows with `userId = caller` are returned. Another
  user's USER recipes never appear.
- **SYSTEM-catalogue rows are shared**: visible to every authenticated caller.
- Soft-deleted rows (`deletedAt` non-null) are never returned regardless of filters.

### Response

`200` → `RecipeDtoPage` (standard page envelope over `RecipeDto` — the existing read-by-id DTO,
hydrated `currentVersionBody` included; the page renders name/meta/badges from it). Sort:
`updatedAt DESC` default (pin in the description).

**Rating aggregate (resolve the §8 Q4 N+1):** the page currently needs one
`GET /ratings/summary` per visible card. Fold `avgTaste` (nullable) + `ratingCount` into the
**list row** — either as two nullable fields on `RecipeDto` (populated only by the list mapper) or
a thin `RecipeListItemDto` wrapper. Decision baked in: **add the two nullable fields to
`RecipeDto`** (additive, no new shape; detail page ignores them and keeps the summary endpoint).
One batched aggregate query, no N+1 (Hibernate-stats assert in the IT).

### OpenAPI excerpt

```yaml
# paths/recipe.yaml
/api/v1/recipes:
  get:
    operationId: listRecipes
    summary: 'Paginated library list/search: caller-private USER rows + shared SYSTEM rows.'
    parameters:
      - { name: catalogue, in: query, schema: { $ref: '...#/RecipeCatalogue' } }
      - { name: namePattern, in: query, schema: { type: string, maxLength: 160 } }
      - { name: cuisine, in: query, schema: { type: string, maxLength: 64 } }
      - { name: maxTotalTimeMins, in: query, schema: { type: integer, minimum: 0 } }
      - { name: minDataQuality, in: query, schema: { $ref: '...#/RecipeDataQuality' } }
      - { name: includeArchived, in: query, schema: { type: boolean, default: false } }
      # + page/size
    responses:
      '200': { $ref: RecipeDtoPage }
      '400': { description: 'invalid enum / negative time / size > 100' }
```

## Edge-case checklist

- [ ] Caller A never sees caller B's USER rows (the deferred privacy rule — IT with two users)
- [ ] `catalogue` absent → own USER + all SYSTEM; `catalogue=SYSTEM` → shared pool only
- [ ] `minDataQuality=IMPORTED` includes USER_VERIFIED and IMPORTED + AI_GENERATED per the pinned ordering; excludes WEB_DISCOVERED
- [ ] `includeArchived=false` (default) hides `archivedAt` rows; `true` includes them
- [ ] `deletedAt` rows never returned under any filter combination
- [ ] `namePattern` is case-insensitive substring ("chick" matches "Chicken Stir Fry")
- [ ] Empty result → empty page, 200 (not 404)
- [ ] `size=101` → 400 (or clamp — match the project-wide infra-01b convention)
- [ ] No N+1: list query + one batched rating aggregate (Hibernate statistics in the IT)
- [ ] `avgTaste` null / `ratingCount` 0 on unrated rows

## Files this ticket touches

```
MOD   src/main/java/com/example/mealprep/recipe/api/controller/RecipesController.java        (GET / + params; update the deferral javadoc)
MOD   src/main/java/com/example/mealprep/recipe/domain/service/RecipeQueryService.java        (or the service the controller fronts — list/search method)
MOD   src/main/java/com/example/mealprep/recipe/domain/service/internal/RecipeServiceImpl.java
NEW   src/main/java/com/example/mealprep/recipe/domain/repository/...                         (specification/derived query as fits the existing repo style)
MOD   src/main/resources/openapi/paths/recipe.yaml + schemas/recipe.yaml + openapi.yaml       (listRecipes + RecipeDtoPage + avgTaste/ratingCount fields)
NEW   src/test/java/com/example/mealprep/recipe/RecipeListSearchIT.java                       (privacy, filters, pagination, N+1 stats)
MOD   src/test/java/com/example/mealprep/recipe/testdata/...
```

## Dependencies

- None hard — `RecipeDto`, ratings summary aggregation, and archive state all shipped.
- Coordinate with [`adaptation-pending-change-list-dto.md`](adaptation-pending-change-list-dto.md)-style additive-DTO conventions (nullable additions, not new required fields).

## Acceptance / DoD

- [ ] `verify` + `spotless` clean; CI green; all edge cases ticked
- [ ] swagger-request-validator passes on the new operation
- [ ] Frontend mock delta §9.7 wireable: page-20 "load more" against the real envelope
- [ ] The `RecipesController` javadoc no longer defers the listing

Squash-merge with: `feat(recipe): GET /api/v1/recipes paginated library list/search with private-catalogue filtering`

## What's NOT in scope

- Diet / meal-type / equipment / protein facets (criteria record fields) → v1.1 filter drawer.
- Free-text relevance ranking / pgvector similarity search → recipe-01h surface, separate ticket.
- The dedup-dialog gaps → [`recipe-import-dedup-consistency.md`](recipe-import-dedup-consistency.md).
