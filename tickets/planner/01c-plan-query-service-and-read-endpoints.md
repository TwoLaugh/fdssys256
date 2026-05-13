# Ticket: planner — 01c Plan Query Service + Read Endpoints (active / history / range / suggestions)

## Summary

Expand the `PlanQueryService` surface and the read endpoints on `PlansController`. Lands on top of 01a (which shipped only `getPlanById` + `GET /plans/{planId}`). Per `lld/planner.md` §Service Interfaces §`PlanQueryService` lines 564-579, §REST Controllers §`PlansController`. Ships:

- `Optional<PlanDto> getActivePlan(UUID householdId, LocalDate weekStartDate)` + `GET /api/v1/plans/active`
- `List<PlanDto> getPlanHistory(UUID householdId, LocalDate weekStartDate)` + `GET /api/v1/plans/history`
- `Page<PlanDto> getPlansBetween(UUID householdId, LocalDate from, LocalDate to, Pageable pageable)` + `GET /api/v1/plans` (paginated)
- `List<PlanDto> getPlansByIds(List<UUID> planIds)` — in-process method only (no REST surface). Used by future grocery / notification readers.
- `Page<ReoptSuggestionDto> getPendingSuggestions(UUID householdId, Pageable pageable)` + `GET /api/v1/plans/suggestions`
- `Optional<ReoptSuggestionDto> getSuggestion(UUID suggestionId)` — in-process only; no separate endpoint v1 (the future per-suggestion REST surface lands with `dismissSuggestion` in 01k).

**Defers**:
- `checkFeasibility(...)` + `GET /api/v1/plans/feasibility` + `FeasibilityCheckResultDto` family — needs `ConstraintFeasibilityCheck` which depends on `PlanCompositionContext` builder which depends on the bundle DTOs from every wave-2 module. **Defer to 01j (controllers + composer wiring)**, NOT here, because the dependency surface is too wide for a read ticket. **Worth user review** — alternative is a stub endpoint returning `feasible=true` always. Rejected because the LLD makes feasibility a real check; a stub would be misleading.
- `POST /api/v1/plans/suggestions/{suggestionId}/dismiss` → **planner-01k**
- All write endpoints → **planner-01j**

## LLD divergence — `getPlansBetween` returns `Page<PlanDto>` not `List`

LLD §Service Interfaces line 567 says `Page<PlanDto> getPlansBetween(...)` and explicitly flags this as a deliberate deviation from the HLD's `List<PlanDto>` shape, **worth user review**. **01c ships the `Page<PlanDto>` form** per the LLD's locked decision. Frontend paginates. Single-page fetch is `PageRequest.of(0, 100)`.

## LLD divergence — `getPlanHistory` returns `List<PlanDto>` not `Page<PlanDto>`

LLD §Service Interfaces line 566 says `List<PlanDto> getPlanHistory(UUID, LocalDate)`. **01c ships `List`** — history for one specific `(household, week)` rarely exceeds ~10 rows (one per regeneration), so pagination would be theatre. The IT checks the typical case fits under a reasonable cap.

## LLD divergence — `getPlansByIds` is in-process only

LLD §Service Interfaces line 569 declares `List<PlanDto> getPlansByIds(List<UUID> planIds)`. **01c ships the method** but **adds no REST endpoint**. Justification: the consumer pattern is "I'm a sibling module (grocery, notification) and I have a list of plan IDs from my own event payload; I want hydrated `PlanDto`s." HTTP is the wrong channel for in-process aggregation; the future planner-bundle-style endpoints for cross-module readers can be added when a consumer ships.

## Behavioural spec

### `PlanQueryService` — expand the surface

1. **Append** to the `PlanQueryService` interface (from 01a):
   ```java
   Optional<PlanDto> getActivePlan(UUID householdId, LocalDate weekStartDate);
   List<PlanDto> getPlanHistory(UUID householdId, LocalDate weekStartDate);
   Page<PlanDto> getPlansBetween(UUID householdId, LocalDate from, LocalDate to, Pageable pageable);
   List<PlanDto> getPlansByIds(List<UUID> planIds);
   Page<ReoptSuggestionDto> getPendingSuggestions(UUID householdId, Pageable pageable);
   Optional<ReoptSuggestionDto> getSuggestion(UUID suggestionId);
   ```
   The existing `getPlanById` stays. **Do NOT remove or rename it.**
2. `PlannerServiceImpl` implements every new method. **All `@Transactional(readOnly = true)`**.

### `getActivePlan`

3. Loads via `planRepository.findFirstByHouseholdIdAndWeekStartDateAndStatus(householdId, weekStartDate, PlanStatus.ACTIVE)` (this method was added in 01b).
4. **Lazy-touch pattern**: same as 01a's `getPlanById` — inside `@Transactional(readOnly = true)`, walk `plan.getDays().forEach(day -> day.getSlots().forEach(slot -> slot.getScheduledRecipe()))` to materialise children, then map. **Do NOT use `@EntityGraph` over the three-deep `List<>` chain** (MultipleBagFetchException trap, same as 01a).
5. Returns `Optional<PlanDto>` — empty when no `ACTIVE` plan exists for that `(household, week)`.

### `getPlanHistory`

6. Loads via **new** repo method on `PlanRepository`:
   ```java
   List<Plan> findByHouseholdIdAndWeekStartDateOrderByGenerationDesc(UUID householdId, LocalDate weekStartDate);
   ```
   **No `@EntityGraph`**. Same lazy-touch pattern, applied to each plan in the returned list.
7. Returns the list sorted **by generation DESC** (latest regeneration first). The repo's `OrderBy...Desc` enforces this.
8. Empty result → empty list (never null).
9. **Capped at 100** — LLD doesn't specify but a runaway loop scenario (user mashes regenerate 500 times) shouldn't OOM the response. Add a `PageRequest.of(0, 100)` cap by switching to `Page<Plan>` internally and `.getContent()`. **Worth user review** — alternative is to leave it uncapped per the LLD's plain `List` signature; rejected because plan history is fed verbatim to the UI and a 500-element response is a real failure mode.

### `getPlansBetween`

10. **New repo method**:
    ```java
    Page<Plan> findByHouseholdIdAndWeekStartDateBetweenOrderByWeekStartDateDescGenerationDesc(
        UUID householdId, LocalDate from, LocalDate to, Pageable pageable);
    ```
    No `@EntityGraph`. Lazy-touch pattern applied to each `Plan` inside the service's `@Transactional` block.
11. `from` and `to` are **inclusive** at the SQL level (Spring Data's `Between` is inclusive).
12. **Validation**: if `from > to` → IllegalArgumentException (mapped to 400 via Spring's default; **01c does NOT add a new exception type** for this). The controller's `@RequestParam` binding handles the param shape; the service is strict about ordering.
13. **`Pageable` defaults**: when controller passes a `Pageable` with no sort, the repo method's `OrderBy...` clause takes precedence. **Worth user review** — alternative is to let the caller override via `?sort=...`. Rejected because the LLD locks the sort order ("week_start_date DESC, generation DESC") and a frontend re-sort would mis-represent history.
14. Returns `Page<PlanDto>` — Spring's `Page` carries pagination metadata (`number`, `size`, `totalElements`, etc.) per the style guide's `Page<T>` shape.

### `getPlansByIds`

15. **New repo method**:
    ```java
    List<Plan> findByIdIn(List<UUID> ids);
    ```
    Lazy-touch + map. Returns plans in **arbitrary order** (`List<UUID>` input order is NOT preserved by `findByIdIn` per JPA spec). **Worth user review** — caller-side reordering if input order matters. The LLD doesn't pin the contract; 01c documents "arbitrary order" in the Javadoc.
16. Empty input → empty result. No DB call.
17. Unknown ids → silently dropped (no exception). The bulk-fetch contract is permissive.

### `getPendingSuggestions`

18. **New repo method on `ReoptSuggestionRepository`**:
    ```java
    Page<ReoptSuggestion> findByHouseholdIdAndStatusOrderByCreatedAtDesc(
        UUID householdId, ReoptStatus status, Pageable pageable);
    ```
19. Service: `findByHouseholdIdAndStatusOrderByCreatedAtDesc(householdId, ReoptStatus.PENDING, pageable).map(reoptSuggestionMapper::toDto)`. **No lazy children to touch** — `ReoptSuggestion` is a flat aggregate.
20. Returns `Page<ReoptSuggestionDto>`.

### `getSuggestion`

21. `reoptSuggestionRepository.findById(id).map(reoptSuggestionMapper::toDto)`.
22. Returns `Optional<ReoptSuggestionDto>`. No `Pageable`, no list semantics.

### Endpoints

23. **Append four `@GetMapping` handlers** to the existing `PlansController`. Keep the existing `GET /{planId}` from 01a unchanged.

| Method | Path | Query params | Response | Status |
|---|---|---|---|---|
| GET | `/active` | `householdId` (UUID), `weekStartDate` (date) | `PlanDto` | 200 / 404 |
| GET | `/history` | `householdId` (UUID), `weekStartDate` (date) | `List<PlanDto>` | 200 |
| GET | `` (root, `/`) | `householdId` (UUID), `from` (date), `to` (date), `page` (int default 0), `size` (int default 20) | `Page<PlanDto>` | 200 |
| GET | `/suggestions` | `householdId` (UUID), `page`, `size` | `Page<ReoptSuggestionDto>` | 200 |

24. `GET /active` returns 404 (not empty body) when no active plan exists. Maps to `PlanNotFoundException` (existing from 01a) with message `"no active plan for household " + householdId + " week " + weekStartDate`. **Worth user review** — alternative is 204 No Content or 200 with empty body. Rejected because the cross-module pattern is "404 means absent"; downstream callers (notification, frontend) interpret 404 consistently.
25. `GET /history` returns `200 + []` for empty history. **No 404** — listing endpoints return empty arrays, not missing.
26. `GET /` (range) returns `200 + Page` for any valid `(from, to)`; empty range returns empty `Page` with `totalElements=0`. **400** when `from > to` via `IllegalArgumentException` handler.
27. `GET /suggestions` returns `200 + Page`. Empty when no pending suggestions.
28. **Authorisation**: any authenticated caller can read any household's plans in v1. Same v1 stance as 01a (household authorisation rules deferred to a follow-up).

### `@RequestParam` validation

29. **Pagination params**: `@RequestParam(defaultValue = "0") @Min(0) int page`, `@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size`. Use `Pageable` injection via Spring's argument resolver (`@PageableDefault(size = 20, sort = "weekStartDate", direction = Sort.Direction.DESC)` is **NOT applied** because the repo method's `OrderBy` overrides; document this in the controller's class-level Javadoc).
30. **Date params**: `@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate weekStartDate` (and `from`, `to`).
31. **UUID params**: Spring auto-converts.

## Database

**Zero migrations.** All new repo methods rely on existing indexes from 01a:
- `idx_planner_plans_household_week_status` for `findFirstByHouseholdIdAndWeekStartDateAndStatus`
- `idx_planner_plans_household_week_gen` for `findByHouseholdIdAndWeekStartDateOrderByGenerationDesc`
- `idx_planner_plans_household_range` for `findByHouseholdIdAndWeekStartDateBetween...`
- Default PK index for `findByIdIn`
- `idx_planner_reopt_pending` for `findByHouseholdIdAndStatusOrderByCreatedAtDesc`

Verify each index is hit via `EXPLAIN` in the IT (optional but recommended; flag if any falls back to seq scan on a 100-row fixture).

## OpenAPI updates

### Append to `src/main/resources/openapi/paths/planner.yaml`

Append below 01a's `planById` block:

```yaml
plansActive:
  get:
    tags: [Plans]
    operationId: getActivePlan
    summary: 'Fetch the currently ACTIVE plan for a household and week.'
    security: [{ cookieAuth: [] }]
    parameters:
      - in: query
        name: householdId
        required: true
        schema: { type: string, format: uuid }
      - in: query
        name: weekStartDate
        required: true
        schema: { type: string, format: date }
    responses:
      '200':
        description: 'Active plan.'
        content:
          application/json:
            schema: { $ref: '../schemas/planner.yaml#/PlanDto' }
      '401': { description: 'Unauthenticated', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '404': { description: 'No active plan for this household and week', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }

plansHistory:
  get:
    tags: [Plans]
    operationId: getPlanHistory
    summary: 'Fetch all plan generations for a specific (household, weekStartDate), ordered by generation DESC.'
    security: [{ cookieAuth: [] }]
    parameters:
      - in: query
        name: householdId
        required: true
        schema: { type: string, format: uuid }
      - in: query
        name: weekStartDate
        required: true
        schema: { type: string, format: date }
    responses:
      '200':
        description: 'Plan history list (may be empty); latest generation first; capped at 100.'
        content:
          application/json:
            schema:
              type: array
              items: { $ref: '../schemas/planner.yaml#/PlanDto' }
      '401': { description: 'Unauthenticated', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }

plansRange:
  get:
    tags: [Plans]
    operationId: getPlansBetween
    summary: 'Paginated list of plans for a household over a date range; sorted by weekStartDate DESC, generation DESC.'
    security: [{ cookieAuth: [] }]
    parameters:
      - in: query
        name: householdId
        required: true
        schema: { type: string, format: uuid }
      - in: query
        name: from
        required: true
        schema: { type: string, format: date }
      - in: query
        name: to
        required: true
        schema: { type: string, format: date }
      - in: query
        name: page
        required: false
        schema: { type: integer, default: 0, minimum: 0 }
      - in: query
        name: size
        required: false
        schema: { type: integer, default: 20, minimum: 1, maximum: 100 }
    responses:
      '200':
        description: 'Page of plans.'
        content:
          application/json:
            schema: { $ref: '../schemas/planner.yaml#/PlanDtoPage' }
      '400': { description: 'Validation error (e.g. from > to)', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '401': { description: 'Unauthenticated', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }

plansSuggestions:
  get:
    tags: [Plans]
    operationId: getPendingSuggestions
    summary: 'List PENDING re-opt suggestions for a household, sorted by createdAt DESC.'
    security: [{ cookieAuth: [] }]
    parameters:
      - in: query
        name: householdId
        required: true
        schema: { type: string, format: uuid }
      - in: query
        name: page
        required: false
        schema: { type: integer, default: 0, minimum: 0 }
      - in: query
        name: size
        required: false
        schema: { type: integer, default: 20, minimum: 1, maximum: 100 }
    responses:
      '200':
        description: 'Page of PENDING suggestions.'
        content:
          application/json:
            schema: { $ref: '../schemas/planner.yaml#/ReoptSuggestionDtoPage' }
      '401': { description: 'Unauthenticated', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
```

### Append to `src/main/resources/openapi/schemas/planner.yaml`

Two new page schemas using the **flat Spring `Page<T>` shape with `additionalProperties: true`** (style-guide locked):

```yaml
PlanDtoPage:
  type: object
  additionalProperties: true
  required: [content, totalElements, totalPages, number, size]
  properties:
    content:
      type: array
      items: { $ref: '#/PlanDto' }
    totalElements: { type: integer, format: int64 }
    totalPages: { type: integer }
    number: { type: integer }
    size: { type: integer }
    first: { type: boolean }
    last: { type: boolean }
    empty: { type: boolean }
    numberOfElements: { type: integer }

ReoptSuggestionDtoPage:
  type: object
  additionalProperties: true
  required: [content, totalElements, totalPages, number, size]
  properties:
    content:
      type: array
      items: { $ref: '#/ReoptSuggestionDto' }
    totalElements: { type: integer, format: int64 }
    totalPages: { type: integer }
    number: { type: integer }
    size: { type: integer }
    first: { type: boolean }
    last: { type: boolean }
    empty: { type: boolean }
    numberOfElements: { type: integer }
```

**Gotcha applied**: `additionalProperties: true` is mandatory — Spring's `Page<T>` includes `pageable` and `sort` properties that named page schemas reject without it. Trap from the locked-in gotchas list.

### Append to `src/main/resources/openapi/openapi.yaml`

Under `paths:` in the existing `# planner` block (from 01a), append:

```yaml
  /api/v1/plans/active:               { $ref: 'paths/planner.yaml#/plansActive' }
  /api/v1/plans/history:              { $ref: 'paths/planner.yaml#/plansHistory' }
  /api/v1/plans:                      { $ref: 'paths/planner.yaml#/plansRange' }
  /api/v1/plans/suggestions:          { $ref: 'paths/planner.yaml#/plansSuggestions' }
```

Under `components.schemas:` in the existing `# planner` block, append:

```yaml
    PlanDtoPage:             { $ref: 'schemas/planner.yaml#/PlanDtoPage' }
    ReoptSuggestionDtoPage:  { $ref: 'schemas/planner.yaml#/ReoptSuggestionDtoPage' }
```

## Verbatim shape snippets

### Service method — `getActivePlan` with the lazy-touch helper

```java
@Override
@Transactional(readOnly = true)
public Optional<PlanDto> getActivePlan(UUID householdId, LocalDate weekStartDate) {
  return planRepository
      .findFirstByHouseholdIdAndWeekStartDateAndStatus(householdId, weekStartDate, PlanStatus.ACTIVE)
      .map(this::hydrateAndMap);
}

private PlanDto hydrateAndMap(Plan plan) {
  // Touch lazy children inside the @Transactional boundary so the mapper sees materialised collections.
  plan.getDays().forEach(day -> {
    day.getSlots().forEach(slot -> slot.getScheduledRecipe());
  });
  return planMapper.toDto(plan);
}
```

### Controller — append to existing `PlansController`

```java
@GetMapping("/active")
public ResponseEntity<PlanDto> getActive(
    @RequestParam UUID householdId,
    @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate weekStartDate) {
  return planQueryService.getActivePlan(householdId, weekStartDate)
      .map(ResponseEntity::ok)
      .orElseThrow(() -> new PlanNotFoundException(
          "no active plan for household " + householdId + " week " + weekStartDate));
}

@GetMapping("/history")
public ResponseEntity<List<PlanDto>> getHistory(
    @RequestParam UUID householdId,
    @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate weekStartDate) {
  return ResponseEntity.ok(planQueryService.getPlanHistory(householdId, weekStartDate));
}

@GetMapping
public ResponseEntity<Page<PlanDto>> getBetween(
    @RequestParam UUID householdId,
    @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
    @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
    @RequestParam(defaultValue = "0") @Min(0) int page,
    @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
  if (from.isAfter(to)) {
    throw new IllegalArgumentException("from must be <= to");
  }
  return ResponseEntity.ok(
      planQueryService.getPlansBetween(householdId, from, to, PageRequest.of(page, size)));
}

@GetMapping("/suggestions")
public ResponseEntity<Page<ReoptSuggestionDto>> getSuggestions(
    @RequestParam UUID householdId,
    @RequestParam(defaultValue = "0") @Min(0) int page,
    @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
  return ResponseEntity.ok(
      planQueryService.getPendingSuggestions(householdId, PageRequest.of(page, size)));
}
```

## Edge-case checklist

### `getActivePlan` / `GET /active`

- [ ] Happy path: 200 + hydrated `PlanDto` with `status=ACTIVE`
- [ ] No active plan → 404 `plan-not-found`
- [ ] Plan exists but status=GENERATED → 404 (no active plan)
- [ ] Plan exists but status=SUPERSEDED → 404
- [ ] Missing `weekStartDate` param → 400
- [ ] Unauthenticated → 401
- [ ] No N+1 — single SELECT for the Plan plus the lazy-load chain bounded by `days * slots`

### `getPlanHistory` / `GET /history`

- [ ] Multiple generations → returned in DESC order (latest first)
- [ ] Single plan → list of 1
- [ ] No plans → empty list (200, not 404)
- [ ] Caller passes a future `weekStartDate` with no plans → empty list

### `getPlansBetween` / `GET /` (range)

- [ ] Range covering multiple weeks → all plans returned, sorted by (weekStartDate DESC, generation DESC)
- [ ] Page metadata correct: `totalElements`, `totalPages`, `number`, `size`, `first`, `last`
- [ ] `from > to` → 400 with IllegalArgumentException via Spring's default handler
- [ ] `from == to` (single-day range) → returns the matching weekStartDate's plans (inclusive)
- [ ] Size 100 (max) → 200; size 101 → 400
- [ ] Default size 20 when omitted
- [ ] OpenAPI `additionalProperties: true` on `PlanDtoPage` — Spring's `pageable` / `sort` extra properties don't break the contract test

### `getPlansByIds` (in-process)

- [ ] Empty input → empty result, no SQL issued
- [ ] All valid ids → all hydrated
- [ ] Mix of valid + invalid → only valid ones returned
- [ ] All invalid → empty list

### `getPendingSuggestions` / `GET /suggestions`

- [ ] Multiple PENDING → returned in createdAt DESC order
- [ ] DISMISSED / ACCEPTED / EXPIRED suggestions excluded
- [ ] Empty pending → empty Page
- [ ] Page metadata correct

### `getSuggestion` (in-process)

- [ ] Existing id → `Optional.of(...)`
- [ ] Unknown id → `Optional.empty()`

### Cross-cutting

- [ ] OpenAPI request/response shapes match (swagger-request-validator filter active in IT)
- [ ] Page schemas use `additionalProperties: true` — verified by contract test against actual Spring serialisation
- [ ] `PlannerBoundaryTest` still passes
- [ ] No N+1 on any read path (verified via Hibernate statement counter on a 5-day-fixture plan: bounded statement count)
- [ ] No regression on 01a / 01b tests

## Files this ticket touches

```
MOD   src/main/java/com/example/mealprep/planner/api/controller/PlansController.java                       (append 4 GET handlers)
MOD   src/main/java/com/example/mealprep/planner/domain/service/PlanQueryService.java                      (append 6 method declarations)
MOD   src/main/java/com/example/mealprep/planner/domain/service/internal/PlannerServiceImpl.java           (implement 6 new methods + hydrateAndMap helper)
MOD   src/main/java/com/example/mealprep/planner/domain/repository/PlanRepository.java                     (3 new methods: findByHouseholdIdAndWeekStartDateOrderByGenerationDesc, findByHouseholdIdAndWeekStartDateBetween..., findByIdIn)
MOD   src/main/java/com/example/mealprep/planner/domain/repository/ReoptSuggestionRepository.java          (1 new method: findByHouseholdIdAndStatusOrderByCreatedAtDesc)

MOD   src/main/resources/openapi/paths/planner.yaml                                                        (append 4 new path-items)
MOD   src/main/resources/openapi/schemas/planner.yaml                                                      (append PlanDtoPage, ReoptSuggestionDtoPage)
MOD   src/main/resources/openapi/openapi.yaml                                                              (4 path entries + 2 schema entries in `# planner` block)

NEW   src/test/java/com/example/mealprep/planner/PlanQueryServiceImplTest.java                             (unit: each new method against mocked repos)
NEW   src/test/java/com/example/mealprep/planner/PlansControllerReadIT.java                                (HTTP: each endpoint happy + edge + 404 + 401 + 400)
MOD   src/test/java/com/example/mealprep/planner/testdata/PlanTestData.java                                (append: multi-generation plan fixture, range fixture spanning 3 weeks)
```

Count: ~13 files modified/created. Estimated agent runtime 30-40 min.

**Files this ticket does NOT modify**:
- `GlobalExceptionHandler.java` — `IllegalArgumentException → 400` already handled there (verify; if not, add a one-liner in `PlannerExceptionHandler` mapping `IllegalArgumentException → 400 ProblemDetail`).
- `PlannerExceptionHandler.java` — no new exception types in 01c.
- Migrations / entities — none added.
- Other modules — none touched.

## Gotchas to bake in

1. **`MultipleBagFetchException`**: same trap as 01a. Use lazy-touch-inside-`@Transactional` for every method that returns a hydrated `PlanDto`. **Do NOT add `@EntityGraph`** to any of the new repo methods.
2. **`LazyInitializationException`**: map to DTO inside the service's `@Transactional`. The controller receives a fully-materialised DTO.
3. **`Page<T>` schema needs `additionalProperties: true`**. Spring's `Page<T>` serialisation includes `pageable` + `sort` properties; named page schemas reject without `additionalProperties: true`. Both new page schemas MUST have it.
4. **Don't nest `page: { number, size }`**. Spring Boot 3.2.5 serialises `Page<T>` flat (`number`, `size` as top-level fields). The schema must mirror that — see the locked verbatim shape in the OpenAPI section above.
5. **`Pageable` argument resolver collision**: if you switch to `@PageableDefault` injection, it bypasses the controller method's explicit `@Min(0) @Max(100)` validation. **Stick with explicit `@RequestParam` page/size** and construct `PageRequest.of(page, size)` in the controller; cleaner validation surface.
6. **404 vs empty list semantics**: `getActivePlan` returns 404 on absence (because the endpoint shape promises a single resource); `getPlanHistory` and `getPlansBetween` return 200 + empty (because they're collection endpoints). Style-guide consistency.
7. **`from.isAfter(to)`** uses `LocalDate.isAfter` (strict); the swap `from.compareTo(to) > 0` is equivalent. Don't use the inverted form by accident — same-date ranges are valid.
8. **`@DateTimeFormat(iso = DateTimeFormat.ISO.DATE)`** is required on the controller's `LocalDate` `@RequestParam`s; without it Spring expects `?weekStartDate=2026-05-12T00:00:00` (ISO_INSTANT) instead of `?weekStartDate=2026-05-12`. Easy to miss; test catches it.
9. **`@MockBean` on multi-interface impl**: `PlannerServiceImpl` implements `PlanQueryService` only in 01c (no `PlannerService` yet — that lands in 01j). Any unit test that `@MockBean private PlanQueryService planQueryService` is fine in 01c. **When 01j lands**, ITs that `@MockBean` either interface need both. Document the assumption in `PlanQueryServiceImplTest`'s class header so the future agent doesn't break it.
10. **YAML description strings with `,` `:` `'`**: single-quote them. Round-4 lesson.

## Dependencies

- **Hard dependency**: `planner-01a` (merged) — `PlanQueryService`, `PlannerServiceImpl`, `PlansController`, `PlanRepository`, `ReoptSuggestionRepository`, all DTOs, `PlanNotFoundException`.
- **Hard dependency**: `planner-01b` (merged) — `findFirstByHouseholdIdAndWeekStartDateAndStatus` repo method added there.
- **Sibling tickets running in parallel** (Wave 3 round 2): potentially `planner-01d` (beam search — pure logic) and `planner-01e` (scoring — pure logic). Neither touches `PlanQueryService`, `PlansController`, or any read repo method. Only collision point is `PlannerServiceImpl.java` — 01c appends 6 method bodies; 01d/01e don't modify the impl (they ship under `internal/`). And `PlannerProperties.java` if 01d/01e add config keys before 01c lands — sequencing should land 01c first because it has no dependencies on those.

## Acceptance / DoD

- [ ] `./mvnw -Dspotless.check.skip=true verify` passes
- [ ] `./mvnw spotless:apply` then `./mvnw spotless:check` clean
- [ ] CI green
- [ ] All edge-case items above ticked
- [ ] Page schemas have `additionalProperties: true`
- [ ] OpenAPI inlined-nullable patterns from 01a still hold (verify no accidental edit to `MealSlotDto.scheduledRecipe`)
- [ ] All YAML description strings containing `,` `:` `'` single-quoted
- [ ] `MultipleBagFetchException` does not occur on any new read path — verified by IT
- [ ] No `pom.xml` dependency adds
- [ ] No other modules' files touched

Squash-merge with: `feat(planner): 01c — read API (getActive / getHistory / getPlansBetween / getPendingSuggestions) + 4 GET endpoints`

## What's NOT in scope

- `checkFeasibility` + `GET /feasibility` + `FeasibilityCheckResultDto` family → **planner-01j** (depends on Stage A composer wiring)
- `POST /suggestions/{suggestionId}/dismiss` → **planner-01k**
- Any write endpoint → **planner-01j**
- `AdminPlannerController` → **planner-01l**
- Authorisation / household-membership checks on reads — v1 defers; lands with the auth/household roles ticket
