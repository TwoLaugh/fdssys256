# Ticket: infra — 01b List-Endpoint Pagination Audit + Intake Search

## Summary

Audit every existing collection endpoint for consistent `Pageable` support, add pagination where missing, and implement the intake-history search endpoint per **C-B-048** (Intake history search and filter). Per roadmap A4 in [`design/audits/2026-05-21-frontend-readiness-roadmap.md`](../../design/audits/2026-05-21-frontend-readiness-roadmap.md).

This ticket is **purely additive**: no breaking changes to any existing endpoint. Endpoints that already return a `Page<>` stay as-is. Endpoints that return a `List<>` gain a `Pageable` parameter with sensible defaults so existing clients (which omit `?page` / `?size`) continue to receive a full-page-1 result.

The audit scope: every `GET` returning a collection across all 14 modules. Anchor endpoints called out by the roadmap:
- `GET /api/v1/recipes` (browse — verify properly paginated and filtered)
- `GET /api/v1/nutrition/intake` (verify paginated + add the search filters)
- `GET /api/v1/nutrition/journal`
- `GET /api/v1/provisions/inventory`
- `GET /api/v1/feedback`

The new search capability (**C-B-048**) adds query parameters to `GET /api/v1/nutrition/intake`: `recipeId`, `mealType`, `q` (full-text-ish search across the intake notes / recipe name). These are additive query parameters; existing callers that omit them get the same result as before.

Closes: **C-B-048** (Intake history search and filter), partial close on C-E-011 (Recipe search/filter — verification track only; no new endpoints), Tier-A frontend unblock.

## Behavioural spec

### Phase 1: Audit (pre-implementation, but documented in the PR description)

1. The agent runs an inventory pass listing every controller method returning a `Collection`, `List`, `Set`, or `Iterable` of any DTO type (excluding `ResponseEntity<Resource>` and similar binary types). For each, record: HTTP method, path, current return shape, current request parameters. The audit table lands in the PR description (not committed code) so reviewers can sanity-check coverage.

2. Each list endpoint is categorised:
   - **Already paginated** (returns `Page<>` and accepts `Pageable`): no change required.
   - **Bounded by design** (e.g. `/api/v1/preferences/hard-constraints/audit-log` — limited per user, but the LLD specifies `Page<>`; or `/api/v1/recipes/{id}/branches` — typically < 10): retain as `List<>` if the LLD already accepts that bound. Annotate the controller with a `// audited: bounded-collection` comment.
   - **Needs pagination**: add `Pageable` parameter with default `Pageable.ofSize(20)`. Return `Page<>` instead of `List<>`. **This is the change set for this ticket.**

3. **Output**: the agent commits a short markdown audit at `design/audits/2026-05-21-list-endpoint-pagination-audit.md` listing every list endpoint with its categorisation. This document is the evidence of the pass; future auditors don't have to re-enumerate.

### Phase 2: Defaults and conventions

4. **Default page size**: 20 across all newly-paginated endpoints. Configurable per-request via `?size=N` up to a hard cap of **100** (Spring `@PageableDefault(size = 20, maxSize = 100)`).
5. **Default sort**: each endpoint's owning module picks the sensible default. Recipes: `name ASC`. Nutrition intake: `consumedAt DESC`. Provisions inventory: `expiryDate ASC`. Feedback: `createdAt DESC`. The defaults are documented in OpenAPI `description` fields.
6. **Page response shape**: Spring's default `PageImpl` JSON serialisation includes `content`, `pageable`, `totalElements`, `totalPages`, `last`, `first`, `numberOfElements`, `size`, `number`, `empty`. **No custom envelope** — the frontend reads `content` + `totalElements` + `number` directly.
7. **Stable sort tiebreaker**: every default sort gets an `id ASC` tiebreaker to guarantee deterministic ordering across pages. Without this, two rows with the same `createdAt` could appear on consecutive pages or not at all.

### Phase 3: Intake search (C-B-048)

8. **`GET /api/v1/nutrition/intake`** gains three new query parameters per `lld/nutrition.md` (refer to whichever section enumerates `IntakeListFilter` — if absent, this ticket adds it). New filter record:
   ```java
   public record IntakeListFilter(
       UUID recipeId,        // exact match; null = no filter
       MealType mealType,    // exact match; null = no filter
       String q              // case-insensitive substring match on notes + linked recipe name; null = no filter
   ) {}
   ```
9. Repository method extension (Spring Data JPA `Specification` is the cleanest fit — it composes the three optional filters without N method overloads):
   ```java
   interface NutritionIntakeRepository extends JpaRepository<NutritionIntake, UUID>,
                                                JpaSpecificationExecutor<NutritionIntake> {
     // existing methods unchanged
   }
   ```
   New `IntakeSpecifications` helper class in `nutrition/domain/repository/`:
   ```java
   class IntakeSpecifications {
     static Specification<NutritionIntake> byUserId(UUID userId);
     static Specification<NutritionIntake> byRecipeId(UUID recipeId);
     static Specification<NutritionIntake> byMealType(MealType mealType);
     static Specification<NutritionIntake> matchesQuery(String q);   // ILIKE on notes + JOIN on recipe.name
   }
   ```
10. Service method `NutritionQueryService.listIntake(UUID userId, IntakeListFilter filter, Pageable pageable): Page<IntakeDto>` composes the specifications via `Specification.where(byUserId(userId)).and(byRecipeId(filter.recipeId())).and(...)`. Null filter components are short-circuited inside each helper (e.g. `byRecipeId(null)` returns the no-op `(root, q, cb) -> cb.conjunction()`).
11. **Full-text-ish search**: `matchesQuery(q)` uses Postgres `ILIKE %q%` against `intake.notes` and a `LEFT JOIN` on `recipe.name`. This is not true FTS (no `tsvector` index) — it's a substring match that's "good enough" for v1 list filtering. **Worth user review** — true FTS lands in a follow-up if substring-match performance bites.
12. The controller binds the filters via `@RequestParam(required = false)`. No custom validator for `q`; cap to 200 chars via `@Size(max=200)`.

### Phase 4: Verify (not change) recipe browse

13. **`GET /api/v1/recipes`**: this ticket confirms the endpoint is paginated and filterable per the LLD. If the recipe LLD's `Page<RecipeDto> search(RecipeSearchCriteriaDto, Pageable)` is implemented, no change. If not, **DO NOT** add new search functionality here — that belongs to `recipe-01i`. This ticket only verifies the basic `?page=&size=` round-trip works.

### Phase 5: Light touch on every other list endpoint

14. For each endpoint flagged "needs pagination" in Phase 1, add `Pageable` and switch return type to `Page<DtoType>`. Service interfaces gain the `Pageable` parameter. Repository methods either already accept `Pageable` or gain it. **Avoid breaking changes**: existing clients that call `GET /api/v1/foo` without `?page` get page-0-size-20 (the default), which matches the previous "first-N items" behaviour for any endpoint that was already capping its result implicitly.

15. **Critical**: endpoints under `/api/v1/admin/...` (e.g. decision-log trace endpoint) follow the same pagination convention. The `GET /api/v1/admin/decision-log/trace/{traceId}` endpoint per `core-01` currently returns `List<DecisionLogDto>` — leave as-is per its LLD (a trace is bounded; the LLD declares the list shape). Tag `// audited: bounded-by-trace`.

### Cross-cutting

16. **No new module-root exceptions.** Pagination doesn't introduce new failure modes — invalid `?page=` / `?size=` are handled by Spring's `MethodArgumentTypeMismatchException` → 400 via the existing `GlobalExceptionHandler`.
17. **No new entities, no migrations** (specifications don't require schema changes).
18. **ArchUnit** rule (new): controller methods returning `java.util.List<*Dto>` or `java.util.Collection<*Dto>` are flagged unless annotated `@BoundedCollection` (new marker annotation in `core.api.markers`). The annotation is the explicit opt-out for endpoints that legitimately return a small unpaginated list (e.g. branches per recipe, < 10). This rule applies project-wide and is the **automated enforcement** mechanism for future drift.
19. The `@BoundedCollection` annotation ships in this ticket; existing legitimate List-returning endpoints get the annotation applied (audit-log endpoints, branches, etc.). The PR description lists every annotation added.

### Events

20. **None.** Pagination is a read-side concern.

## Database

**No migration in this ticket.**

**Performance index review** (deferred — not a 01b change): the new intake-search `ILIKE` patterns may benefit from a `gin_trgm_ops` index on `nutrition_intake.notes` and `recipe_recipes.name` (`CREATE EXTENSION pg_trgm` + index). Defer until k6 perf testing shows the search is slow against realistic data volumes. **Worth user review** if the user wants the indexes shipped now (pg_trgm extension cost is negligible).

## OpenAPI updates

For each newly-paginated endpoint, update its OpenAPI path entry to:
- Add `page`, `size`, `sort` query parameters (`type: integer`, `minimum: 0`, `default: 0` for page; `type: integer`, `minimum: 1`, `maximum: 100`, `default: 20` for size; `type: string` for sort).
- Update the response schema to wrap the existing DTO in a `Page<DtoType>` envelope. A shared `#/components/schemas/PageEnvelope` exists in `openapi.yaml`; reference it.

For `GET /api/v1/nutrition/intake`, add the three new filter params: `recipeId` (uuid), `mealType` (enum referencing `MealType`), `q` (string, max 200).

Update `schemas/nutrition.yaml` if `IntakeListFilter` isn't already represented (Spring binds query params individually so the schema isn't strictly required, but documenting it aids the frontend's type generation).

**Existing `Page<>` endpoints**: no OpenAPI change required — they already document the envelope.

## Edge-case checklist

- [ ] **Audit doc** committed at `design/audits/2026-05-21-list-endpoint-pagination-audit.md` enumerating every list endpoint with its category.
- [ ] **Default page**: `GET /api/v1/nutrition/intake` without query params returns up to 20 rows, page 0, with the full Spring `PageImpl` envelope.
- [ ] **`?page=1&size=10`** returns rows 11-20.
- [ ] **`?size=101`** → 400 (max-size cap enforced).
- [ ] **`?page=-1`** → 400.
- [ ] **`?size=0`** → 400.
- [ ] **`?sort=consumedAt,desc`** orders newest-first.
- [ ] **`?sort=consumedAt,desc&sort=id,asc`** applies both (tiebreaker).
- [ ] **Stable ordering**: two rows with identical `consumedAt` always appear in the same order across pages (id tiebreaker).
- [ ] **Filter `?recipeId=<uuid>`** returns only intakes for that recipe.
- [ ] **Filter `?mealType=DINNER`** returns only DINNER intakes.
- [ ] **Filter `?q=chicken`** matches intakes where `notes ILIKE '%chicken%'` OR `recipe.name ILIKE '%chicken%'`. Case-insensitive.
- [ ] **Combined filters**: `?recipeId=X&mealType=LUNCH&q=salad` AND-composes the three filters.
- [ ] **`?q=` empty string**: treated as no filter (not "match all empty notes").
- [ ] **`?q=` 201 chars**: 400 (size validator).
- [ ] **Cross-tenant**: filter applies WITHIN the calling user's intakes only (the `byUserId` specification is always composed first).
- [ ] **Empty results**: a filter that matches no rows returns `Page<>` with `content: []`, `totalElements: 0`, `empty: true`.
- [ ] **ArchUnit `@BoundedCollection`**: removing the annotation from `GET /api/v1/recipes/{id}/branches` fails the rule (verifies the gate works).
- [ ] **Existing paginated endpoints** unchanged in shape: `GET /api/v1/feedback` continues to return the same `Page<FeedbackEntryDto>` envelope.
- [ ] **Backward compat**: a client calling `GET /api/v1/nutrition/intake` with no query params gets functionally the same first-20 rows as before this ticket landed.
- [ ] **OpenAPI**: the spec lints clean and every newly-paginated path documents the three pagination query params.
- [ ] **Contract test**: `MockMvc` calls assert the response shape matches the OpenAPI schema for at least three newly-paginated endpoints.
- [ ] **Specification composition**: a null filter component (e.g. `IntakeListFilter(null, null, null)`) produces a Spec that matches all rows for the user — verified via `IntakeSpecificationsTest` unit class.
- [ ] **SQL inspection** (via Hibernate logger): `?q=foo` generates exactly one query with `ILIKE %foo%` clauses on the two columns — no N+1.

## Files this ticket touches

```
NEW   design/audits/2026-05-21-list-endpoint-pagination-audit.md               (audit doc — not code)

NEW   src/main/java/com/example/mealprep/core/api/markers/BoundedCollection.java  (marker annotation)

NEW   src/main/java/com/example/mealprep/nutrition/api/dto/IntakeListFilter.java
NEW   src/main/java/com/example/mealprep/nutrition/domain/repository/IntakeSpecifications.java

MOD   src/main/java/com/example/mealprep/nutrition/api/controller/NutritionIntakeController.java
MOD   src/main/java/com/example/mealprep/nutrition/domain/service/NutritionQueryService.java
MOD   src/main/java/com/example/mealprep/nutrition/domain/service/internal/NutritionServiceImpl.java
MOD   src/main/java/com/example/mealprep/nutrition/domain/repository/NutritionIntakeRepository.java

(many other MODs across modules — one per newly-paginated controller method;
 these are mechanical: change List<X> → Page<X>, add Pageable parameter,
 propagate through the service interface + impl. Aim is ~6-10 files
 modified across recipe, nutrition, provisions, feedback, household.)

MOD   src/main/resources/openapi/paths/nutrition.yaml                          (intake search params + Page envelope)
MOD   src/main/resources/openapi/paths/recipe.yaml                             (verify Page envelope on list endpoints)
MOD   src/main/resources/openapi/paths/provisions.yaml                         (Page envelope on inventory list)
MOD   src/main/resources/openapi/paths/feedback.yaml                           (verify Page envelope)
MOD   src/main/resources/openapi/schemas/nutrition.yaml                        (IntakeListFilter schema; PageEnvelope ref if absent)

MOD   src/test/java/com/example/mealprep/archunit/ModuleBoundaryTest.java      (add @BoundedCollection rule)
NEW   src/test/java/com/example/mealprep/nutrition/IntakeSpecificationsTest.java
NEW   src/test/java/com/example/mealprep/nutrition/IntakeSearchIT.java         (Testcontainers; combined filters)
```

Total: 4 new + many mods + 1 audit doc. Estimated agent runtime 90-150 min (the audit pass is the time-consuming part).

## Dependencies

- **Hard dependency**: every existing list-returning controller across the 14 modules. This ticket touches several modules' service interfaces — coordinate that no parallel ticket is mid-change on those.
- **Hard dependency**: `nutrition-01a..01n` (whichever ticket landed the `NutritionIntake` entity) — this ticket assumes the entity + base repository are present.
- **Soft dependency**: future `recipe-01i` (search) — this ticket verifies but does not extend recipe search.

## Acceptance / DoD

- [ ] `./mvnw -Dspotless.check.skip=true verify` passes
- [ ] `./mvnw spotless:apply` then `./mvnw spotless:check` clean
- [ ] CI green (build + spotless + OpenAPI lint + ArchUnit + contract tests)
- [ ] All edge-case items above ticked
- [ ] Audit doc committed and enumerates every list endpoint
- [ ] **No breaking changes**: every previously-working frontend call (if a frontend existed) continues to return functionally-equivalent data when called with no new query params
- [ ] Manual smoke: `curl 'http://localhost:8080/api/v1/nutrition/intake?q=chicken&mealType=DINNER&page=0&size=10'` returns a `Page` envelope filtered as expected
- [ ] The `@BoundedCollection` ArchUnit rule gates future drift (verified by deliberately removing one annotation)

## What's NOT in scope

- **Adding pg_trgm indexes for `q` search performance** — deferred until perf testing shows substring search is slow.
- **Recipe search extensions (C-E-011 expansion)** — that's `recipe-01i`.
- **Cursor-based pagination** — offset-pagination is sufficient for v1 lists; cursor pagination is a stretch goal if/when intake history grows past 100k rows per user.
- **Full-text search via `tsvector`** — substring search is the v1 strategy.
- **Custom `Page<>` envelope** — Spring's default JSON envelope is the contract.

Squash-merge with: `feat(infra): 01b — list-endpoint pagination audit + intake search (C-B-048)`
