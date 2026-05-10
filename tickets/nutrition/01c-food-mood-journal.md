# Ticket: nutrition — 01c Food / Mood Journal

## Summary

Layer the **food/mood journal** standalone aggregate on top of the 01a/01b nutrition module: `FoodMoodJournalEntry` (one row per `(userId, onDate, mealSlot)`; nullable `mealSlot` allows untied entries), the V…600500 migration, repository, mapper, the five `JournalController` endpoints under `/api/v1/nutrition/journal`, and the cross-module read helper `getJournalEntriesForFeedbackContext(UUID userId)` for the (not-yet-built) Feedback System. Per [`lld/nutrition.md`](../../lld/nutrition.md) §V20260502120500, §`FoodMoodEntryDto` / `UpsertFoodMoodEntryRequest` (lines 513-524), §`JournalMapper` (lines 582-585), §`FoodMoodJournalRepository` (lines 632-635), §`NutritionQueryService` journal methods (lines 681-684), §`NutritionUpdateService.upsertJournalEntry` / `.deleteJournalEntry` (lines 725-726), §`JournalController` table (lines 812-820).

**Defers** (still out of scope after 01c):
- Ingredient mapping cache + USDA / OFF clients + `IngredientMappingPipeline` lookup endpoints → **nutrition-01d**
- Health directives queue (inbound / accept / reject / safety gate / `DirectiveApplier`) → **nutrition-01e**
- `NutritionCalculationService` (recipe save-time + `RecipeEvolvedEvent` listener) → **nutrition-01f**
- `NutritionFloorGateService` for the planner → **nutrition-01g**
- `IntakeAggregator`, `WeeklyAggregateDto`, weekly-rollup endpoints, `DivergenceDetector` + `NutritionIntakeDivergedEvent` → **nutrition-01h**
- DRI seed data (`R__nutrition_seed_dri_defaults.sql`) — moved to **nutrition-01d** because that ticket layers ingredient lookups; 01c does not need DRI defaults
- `MealCookedEvent` auto-confirm listener → **nutrition-01i**
- AI-driven free-text override parsing (`IntakeOverrideParserTask`) → **nutrition-01k**
- Cross-module pantry-deduct on snack log → **nutrition-01l**
- `applyFeedback(NutritionFeedbackRequest)` from feedback module → **nutrition-01m** (depends on feedback module)
- Journal NLP / mood-correlation analysis — **out of LLD scope** per LLD §Out of Scope ("owned by the Feedback System and the separate health platform"). 01c exposes the data; analysis is separate.

01c is a **standalone aggregate** — it does NOT extend the 01b `IntakeDay` aggregate. The LLD lists the food/mood journal as a peer concern (LLD §"What this module owns" line 16: "Food/mood journal | `nutrition_food_mood_journal` | User-only"). It unblocks the (future) Feedback System's classifier-context assembly via `getJournalEntriesForFeedbackContext(userId)` (LLD line 698). Without 01c, the only journaling surface is the `IntakeSlot.overrideFreeText` field (per-slot, intake-coupled) — which is insufficient for the LLD's "free-text journal tied to meal slots OR untied" use case.

**LLD divergence note** — **endpoint shape**: the LLD §`JournalController` table (lines 812-820) anchors all journal endpoints under `/api/v1/nutrition/journal/{date}` (date in path):
- `GET /{date}` returns `List<FoodMoodEntryDto>`
- `POST /{date}` accepts an `UpsertFoodMoodEntryRequest` and returns 201
- `PUT /{date}/entries/{entryId}` updates an entry, 200
- `DELETE /{date}/entries/{entryId}` removes one, 204
- `GET ?page=&size=` returns recent entries paginated

**01c keeps the LLD's surface verbatim** — date in path; no flat `/journal` resource on POST. The reason: the LLD's 01b `IntakeController` already uses date-in-path consistently (`/intake/{date}`, `/intake/{date}/slots/{mealSlot}/...`), and the journal endpoints mirror that idiom.

**LLD divergence note** — **`from`/`to` query parameter**: the parent's instructions specified `GET /journal?from=&to=` paginated by `occurredAt desc`. The LLD §`JournalController` table (lines 812-820) actually specifies **`GET ?page=&size=`** — page-only, no date range. **01c follows the LLD** for the paginated listing. The `from`/`to` filter would necessitate either (a) the existing `/journal/{date}` per-day endpoint serving `from == to` use-cases, or (b) a third endpoint. Both are deferred — the recent-entries pagination + per-day fetch cover the v1 UI need. **Worth user review.**

## Behavioural spec

### Aggregate shape

1. `FoodMoodJournalEntry` is a **standalone aggregate root** — no parent collection, not a child of `IntakeDay`. Per [LLD §Entities line 362](../../lld/nutrition.md): "One row per `(user_id, on_date, meal_slot)`. `@Version` — same entry can be edited." Fields per [LLD V20260502120500 lines 249-257](../../lld/nutrition.md):
   - `id (UUID, application-set)`
   - `userId (UUID NOT NULL)`
   - `onDate (LocalDate NOT NULL)`
   - `mealSlot (MealSlot enum from 01a — BREAKFAST|LUNCH|DINNER|SNACKS — **nullable**, varchar(24) on the wire to allow untied entries)`
   - `journalEntry (text NOT NULL — `@Column` `length` not specified; column type `text`; bean validation caps at 4000 chars per LLD line 521)`
   - `loggedAt (Instant NOT NULL — when the user wrote the entry; distinct from `createdAt`/`updatedAt` which are JPA audit columns)`
   - `optimisticVersion (@Version Long, column name `optimistic_version` per LLD line 255)`
   - `createdAt (@CreatedDate)`, `updatedAt (@LastModifiedDate)`
2. **Database constraint** per LLD line 257: `UNIQUE (user_id, on_date, meal_slot)`. Postgres semantics: nullable `meal_slot` means **multiple null-slot entries per `(user_id, on_date)` are permitted** — Postgres treats NULLs as not-equal in unique constraints. This is **intentional** (LLD line 265 — "matches the HLD's free-text journal tied to meal slots plus the implied untied entries"). **Test this explicitly** — see edge-case checklist.
3. **Indexes** per LLD lines 260-262:
   - `idx_nutr_food_mood_user_date ON nutrition_food_mood_journal (user_id, on_date DESC)` — per-day fetch + recent-entries listing.
   - `idx_nutr_food_mood_user_logged_at ON nutrition_food_mood_journal (user_id, logged_at DESC)` — feedback-context loader hits this.
4. **`@Version` is on this entry**, not "parent's covers" — per LLD line 1049 ("`@Version` on `NutritionTargets`, `IntakeDay`, `FoodMoodJournalEntry`, ..."). Edits use `expectedVersion` from the request DTO; mismatch → 409 mapped via `GlobalExceptionHandler`'s `OptimisticLockException` handler (no new advice needed).

### `getJournalEntriesForDay`

5. `GET /api/v1/nutrition/journal/{date}` returns `List<FoodMoodEntryDto>` for the calling user on `{date}`, sorted by `loggedAt ASC` per [LLD line 633](../../lld/nutrition.md) (`findByUserIdAndOnDateOrderByLoggedAtAsc`). Cookie-auth required. Empty list when no entries exist (NOT 404 — list-style endpoints return empty rather than not-found).
6. Repository: `findByUserIdAndOnDateOrderByLoggedAtAsc(UUID userId, LocalDate onDate)`. Single-table SELECT on the `(user_id, on_date)` index.

### `upsertJournalEntry` (create)

7. `POST /api/v1/nutrition/journal/{date}`. Body: `UpsertFoodMoodEntryRequest { @NotNull LocalDate onDate, MealSlot mealSlot /* nullable */, @NotBlank @Size(max=4000) String journalEntry, @NotNull Instant loggedAt, long expectedVersion /* ignored on create — see invariant 9 */ }`. Server resolves `userId` via `CurrentUserResolver`.
8. **Path/body date consistency**: if `request.onDate != {date}` from the path → 400 `MealPrepValidationException` (or simpler: `IllegalArgumentException` mapped to 400). Locking the path's `{date}` as the canonical value avoids cross-resource ambiguity.
9. **Insert path** — when no row exists for `(userId, onDate, mealSlot)` (or when `mealSlot = null`, always insert a new row):
   - Generate UUID application-side.
   - Persist row. Return 201 with `FoodMoodEntryDto` and `Location: /api/v1/nutrition/journal/{date}/entries/{entryId}`.
   - **`expectedVersion` is ignored on insert** (LLD line 522 declares the field unconditionally; on insert there is no row to lock against).
   - Audit log: **the LLD does not specify a journal audit log**. 01c writes **no audit row**. The mutation event below is the only signal.
10. **Update-on-collision is NOT here** — the spec is "create entry" semantics on POST (LLD line 817 — `POST /{date}` returns 201). Update goes to `PUT /{date}/entries/{entryId}` (invariant 12 below). If the user POSTs a slot-tied entry where `(userId, onDate, mealSlot)` already exists with `mealSlot != null` → DB unique constraint fires, mapped via `GlobalExceptionHandler`'s `DataIntegrityViolationException` handler to 409. The user is expected to call `PUT` instead. **LLD divergence note** — LLD `upsertJournalEntry(UUID userId, UpsertFoodMoodEntryRequest)` has "upsert" in the name but the REST table separates POST (create) from PUT (edit); 01c follows the REST table. The service-interface name keeps `upsertJournalEntry` for back-compat with the LLD interface declaration.
11. **`mealSlot = null`** on the request → always insert a new row (Postgres treats NULL as not-equal in the unique constraint). User can have arbitrarily many `(userId, onDate, mealSlot=null)` entries per day.

### `updateJournalEntry`

12. `PUT /api/v1/nutrition/journal/{date}/entries/{entryId}`. Body: same `UpsertFoodMoodEntryRequest` shape. Server resolves `userId`.
13. Single `@Transactional` write:
    - Load by `entryId` — 404 `JournalEntryNotFoundException` if missing.
    - Authorisation: `entity.userId == callerUserId` else 404 (don't leak existence — same rule as 01a's `getTargets` ownership check).
    - Path date consistency: `entity.onDate == {date}` from the path else 404 (treat as not-found from the URL's perspective).
    - **`expectedVersion` mismatch** → 409 via `OptimisticLockException` from the JPA save (let JPA throw; mapped by `GlobalExceptionHandler`). Alternative: explicit comparison + custom exception. **Pick JPA-driven** — simpler, matches 01b's `IntakeDay` edit path.
    - Update `mealSlot`, `journalEntry`, `loggedAt` from the request. **Do NOT update `onDate`** — `onDate` is part of the natural key and would shift the row to a different day; if the user wants to move an entry, they DELETE + POST. Reject `request.onDate != entity.onDate` with 400.
    - JPA bumps `@Version`; return 200 with the updated DTO.

### `deleteJournalEntry`

14. `DELETE /api/v1/nutrition/journal/{date}/entries/{entryId}`. 204 on success. 404 if not found / not owned / wrong date. **Hard delete** — the LLD does not specify a soft-delete column on this table. Audit log absent. **Idempotency**: a second delete on the same id → 404 (the row is gone; non-idempotent on the wire is acceptable per the LLD's REST table line 819).

### `getRecentJournalEntries` (paginated)

15. `GET /api/v1/nutrition/journal?page=&size=` returns `Page<FoodMoodEntryDto>` for the calling user, sorted `loggedAt DESC` (newest first), default size 20, max 100. Spring `Pageable`. Repository: `findByUserIdOrderByLoggedAtDesc(UUID userId, Pageable p)` per [LLD line 634](../../lld/nutrition.md).
16. Hits the `(user_id, logged_at DESC)` index. Single SELECT — no joins, no children.

### Cross-module helper — `getJournalEntriesForFeedbackContext`

17. Append `List<FoodMoodEntryDto> getJournalEntriesForFeedbackContext(UUID userId)` to the existing `NutritionQueryService` interface from 01a/01b. Returns the **top-20** entries for `userId` ordered `loggedAt DESC`. Repository: `findTop20ByUserIdOrderByLoggedAtDesc(UUID userId)` per [LLD line 635](../../lld/nutrition.md). **No HTTP exposure** — invoked in-process when the Feedback System lands.
18. **Feedback module is not built yet** — 01c adds the interface method but no caller. Test coverage: a service-impl unit test invoking `getJournalEntriesForFeedbackContext` directly + an IT verifying the `LIMIT 20` clause is honoured (write 25 entries; assert exactly 20 returned, newest first).

### Service interfaces — append-only to existing 01a/01b interfaces

19. Append to `NutritionQueryService`:
    ```java
    List<FoodMoodEntryDto> getJournalEntriesForDay(UUID userId, LocalDate onDate);
    Page<FoodMoodEntryDto> getRecentJournalEntries(UUID userId, Pageable pageable);
    List<FoodMoodEntryDto> getJournalEntriesForFeedbackContext(UUID userId);
    ```
20. Append to `NutritionUpdateService`:
    ```java
    FoodMoodEntryDto upsertJournalEntry(UUID userId, UpsertFoodMoodEntryRequest request);   // create-only in 01c
    FoodMoodEntryDto updateJournalEntry(UUID userId, UUID entryId, UpsertFoodMoodEntryRequest request);
    void deleteJournalEntry(UUID userId, UUID entryId);
    ```
    The LLD's `upsertJournalEntry` line 725 is preserved verbatim; `updateJournalEntry` is **added** because 01c separates create from update on the wire (LLD §`JournalController` distinguishes POST from PUT).

### Repository — package-private

21. ```java
    interface FoodMoodJournalRepository extends JpaRepository<FoodMoodJournalEntry, UUID> {
      List<FoodMoodJournalEntry> findByUserIdAndOnDateOrderByLoggedAtAsc(UUID userId, LocalDate onDate);
      Page<FoodMoodJournalEntry> findByUserIdOrderByLoggedAtDesc(UUID userId, Pageable p);
      List<FoodMoodJournalEntry> findTop20ByUserIdOrderByLoggedAtDesc(UUID userId);
    }
    ```
22. **Boundary**: existing `NutritionBoundaryTest` from 01a covers the new repo (lives in `domain/repository/`). **No changes to the test**.

### Events

23. `FoodMoodEntryWrittenEvent(UUID entryId, UUID userId, LocalDate onDate, MealSlot mealSlot /* nullable */, JournalAction action /* CREATED | UPDATED | DELETED */, UUID traceId, Instant occurredAt)` published `AFTER_COMMIT` on each of the three write paths. **LLD divergence note** — the LLD §Events (line 1049 area) lists no specific journal event. 01c **adds the event** because the (future) Feedback System needs to subscribe to journal mutations to refresh classifier context. Cost is one record class. No listeners in 01c.
24. `JournalAction` enum local to the module: `CREATED`, `UPDATED`, `DELETED`.

### Errors

25. New module exception subclass `JournalEntryNotFoundException` (404, `type = .../journal-entry-not-found`) extending the existing `NutritionException` from 01a.
26. **Append one new `@ExceptionHandler` method** to the existing `NutritionExceptionHandler` `@RestControllerAdvice` from 01a (which is already `@Order(Ordered.HIGHEST_PRECEDENCE)`). Do **NOT** create a second handler class. Do **NOT** modify `config/GlobalExceptionHandler.java`. `OptimisticLockingFailureException` continues handled by `GlobalExceptionHandler` (handles 409 for stale `expectedVersion`). `DataIntegrityViolationException` from the unique-constraint collision continues handled by `GlobalExceptionHandler` (409). `MethodArgumentNotValidException` (validation) likewise handled globally.

## Database

```
src/main/resources/db/migration/V20260601600500__nutrition_create_food_mood_journal.sql   new
```

Schema mirrors [LLD V20260502120500 lines 249-262](../../lld/nutrition.md), renumbered to the nutrition timestamp range (`V20260601600xxx+`, after 01b's intake / activity migrations which occupy `V20260601600500..V20260601600x00` — coordinate with 01b's actual chosen range; **01c's migration goes at the next free slot — `V20260601600500` if 01b's last is below it, else bump**). **Important**: cross-check 01b's merged migration timestamps before locking. As of round-3, 01b is merged.

```sql
-- V20260601600500 (or the next free slot after 01b)
CREATE TABLE nutrition_food_mood_journal (
    id                  uuid PRIMARY KEY,
    user_id             uuid NOT NULL,
    on_date             date NOT NULL,
    meal_slot           varchar(24),                          -- nullable: untied entries
    journal_entry       text NOT NULL,
    logged_at           timestamptz NOT NULL,
    optimistic_version  bigint NOT NULL DEFAULT 0,
    created_at          timestamptz NOT NULL,
    updated_at          timestamptz NOT NULL,
    UNIQUE (user_id, on_date, meal_slot)
);
CREATE INDEX idx_nutr_food_mood_user_date
    ON nutrition_food_mood_journal (user_id, on_date DESC);
CREATE INDEX idx_nutr_food_mood_user_logged_at
    ON nutrition_food_mood_journal (user_id, logged_at DESC);
```

`meal_slot` width: actual enum values from 01a are `BREAKFAST` (9 chars) / `LUNCH` (5) / `DINNER` (6) / `SNACKS` (6). LLD's `varchar(24)` carries headroom; preserve. `journal_entry` as `text` (not `varchar`) per LLD; bean validation `@Size(max=4000)` enforces the application-side ceiling.

## OpenAPI updates

### Append to `src/main/resources/openapi/paths/nutrition.yaml`

(File created by 01a, extended by 01b — append five new path-items below 01b's intake/activity blocks. Do NOT touch existing path-items.)

```yaml
nutritionJournalDay:
  get:
    tags: [Nutrition]
    operationId: getNutritionJournalForDay
    summary: List the calling user's food/mood journal entries for a date, oldest first.
    security: [{ cookieAuth: [] }]
    parameters:
      - in: path
        name: date
        required: true
        schema: { type: string, format: date }
    responses:
      '200':
        description: Journal entries for the day (possibly empty).
        content:
          application/json:
            schema:
              type: array
              items: { $ref: '../schemas/nutrition.yaml#/FoodMoodEntryDto' }
      '401': { description: Unauthenticated, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
  post:
    tags: [Nutrition]
    operationId: createNutritionJournalEntry
    summary: Create a food/mood journal entry for the calling user.
    security: [{ cookieAuth: [] }]
    parameters:
      - in: path
        name: date
        required: true
        schema: { type: string, format: date }
    requestBody:
      required: true
      content:
        application/json:
          schema: { $ref: '../schemas/nutrition.yaml#/UpsertFoodMoodEntryRequest' }
    responses:
      '201':
        description: Entry created.
        headers:
          Location:
            schema: { type: string, format: uri }
        content:
          application/json:
            schema: { $ref: '../schemas/nutrition.yaml#/FoodMoodEntryDto' }
      '400': { description: Validation error / path-body date mismatch, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '401': { description: Unauthenticated, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '409': { description: A slot-tied entry already exists for (userId, onDate, mealSlot), content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
nutritionJournalEntry:
  put:
    tags: [Nutrition]
    operationId: updateNutritionJournalEntry
    summary: Update an existing food/mood journal entry.
    security: [{ cookieAuth: [] }]
    parameters:
      - in: path
        name: date
        required: true
        schema: { type: string, format: date }
      - in: path
        name: entryId
        required: true
        schema: { type: string, format: uuid }
    requestBody:
      required: true
      content:
        application/json:
          schema: { $ref: '../schemas/nutrition.yaml#/UpsertFoodMoodEntryRequest' }
    responses:
      '200':
        description: Entry updated.
        content:
          application/json:
            schema: { $ref: '../schemas/nutrition.yaml#/FoodMoodEntryDto' }
      '400': { description: Validation error / path mismatch, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '401': { description: Unauthenticated, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '404': { description: Entry not found / not owned / wrong date, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '409': { description: Stale expectedVersion, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
  delete:
    tags: [Nutrition]
    operationId: deleteNutritionJournalEntry
    summary: Hard-delete a food/mood journal entry.
    security: [{ cookieAuth: [] }]
    parameters:
      - in: path
        name: date
        required: true
        schema: { type: string, format: date }
      - in: path
        name: entryId
        required: true
        schema: { type: string, format: uuid }
    responses:
      '204': { description: Entry deleted. }
      '401': { description: Unauthenticated, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '404': { description: Entry not found / not owned / wrong date, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
nutritionJournalRecent:
  get:
    tags: [Nutrition]
    operationId: getRecentNutritionJournalEntries
    summary: Paginated recent journal entries newest-first.
    security: [{ cookieAuth: [] }]
    parameters:
      - in: query
        name: page
        schema: { type: integer, minimum: 0, default: 0 }
      - in: query
        name: size
        schema: { type: integer, minimum: 1, maximum: 100, default: 20 }
    responses:
      '200':
        description: Page of journal entries.
        content:
          application/json:
            schema: { $ref: '../schemas/nutrition.yaml#/FoodMoodEntryDtoPage' }
      '401': { description: Unauthenticated, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
```

### Append to `src/main/resources/openapi/schemas/nutrition.yaml`

(Append four new schemas; `MealSlot` from 01a is reused. Do NOT touch 01a/01b schemas.)

```yaml
FoodMoodEntryDto:
  type: object
  required: [id, userId, onDate, journalEntry, loggedAt, optimisticVersion]
  properties:
    id: { type: string, format: uuid }
    userId: { type: string, format: uuid }
    onDate: { type: string, format: date }
    mealSlot:
      type: string
      enum: [BREAKFAST, LUNCH, DINNER, SNACKS]
      nullable: true
    journalEntry: { type: string, maxLength: 4000 }
    loggedAt: { type: string, format: date-time }
    optimisticVersion: { type: integer, format: int64 }
UpsertFoodMoodEntryRequest:
  type: object
  required: [onDate, journalEntry, loggedAt]
  properties:
    onDate: { type: string, format: date }
    mealSlot:
      type: string
      enum: [BREAKFAST, LUNCH, DINNER, SNACKS]
      nullable: true
    journalEntry: { type: string, minLength: 1, maxLength: 4000 }
    loggedAt: { type: string, format: date-time }
    expectedVersion: { type: integer, format: int64, minimum: 0, default: 0 }
FoodMoodEntryDtoPage:
  type: object
  additionalProperties: true       # gotcha: Spring Page<T> ships pageable + sort
  required: [content, totalElements, totalPages, number, size]
  properties:
    content:
      type: array
      items: { $ref: '#/FoodMoodEntryDto' }
    totalElements: { type: integer, format: int64 }
    totalPages: { type: integer }
    number: { type: integer }
    size: { type: integer }
    first: { type: boolean }
    last: { type: boolean }
    empty: { type: boolean }
    numberOfElements: { type: integer }
JournalAction:
  type: string
  enum: [CREATED, UPDATED, DELETED]
  description: Used internally by FoodMoodEntryWrittenEvent; not surfaced via REST.
```

**Gotcha applied**: `mealSlot` uses **inline** `nullable: true` with the enum (NOT `$ref + nullable: true` — sibling keywords on `$ref` are silently ignored by swagger-parser per the agent-prompt-template gotcha list). The enum is duplicated inline rather than `$ref`'d to 01a's `MealSlot`; that's the documented workaround.

**Gotcha applied**: `FoodMoodEntryDtoPage` uses the **flat** Page<T> shape (top-level `content` / `totalElements` / `totalPages` / `number` / `size`) — NOT a nested `page: { number, size, ... }` object. Spring Boot 3.2.5+ serialises `Page<T>` to flat properties; nesting silently doesn't match (per the gotcha list). `additionalProperties: true` so swagger-parser tolerates Spring's `pageable` / `sort` extras.

### Append to entry `src/main/resources/openapi/openapi.yaml`

**Location**: under the existing `# nutrition` block in `paths:` (after 01b's intake/activity refs). Append four new path-item refs:

```yaml
  /api/v1/nutrition/journal/{date}:
    $ref: 'paths/nutrition.yaml#/nutritionJournalDay'
  /api/v1/nutrition/journal/{date}/entries/{entryId}:
    $ref: 'paths/nutrition.yaml#/nutritionJournalEntry'
  /api/v1/nutrition/journal:
    $ref: 'paths/nutrition.yaml#/nutritionJournalRecent'
```

**Location**: under `components.schemas:`, append four new schema refs in the existing `# nutrition` block (alphabetical order):

```yaml
    FoodMoodEntryDto: { $ref: 'schemas/nutrition.yaml#/FoodMoodEntryDto' }
    FoodMoodEntryDtoPage: { $ref: 'schemas/nutrition.yaml#/FoodMoodEntryDtoPage' }
    JournalAction: { $ref: 'schemas/nutrition.yaml#/JournalAction' }
    UpsertFoodMoodEntryRequest: { $ref: 'schemas/nutrition.yaml#/UpsertFoodMoodEntryRequest' }
```

## Verbatim shape snippets

### Entity

Mirrors 01b's standalone `DailyActivityLog` shape. No JSONB. No collections.

```java
@Entity
@Table(name = "nutrition_food_mood_journal")
@Getter @Setter @Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class FoodMoodJournalEntry {

  @Id @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @Column(name = "user_id", nullable = false, updatable = false)
  private UUID userId;

  @Column(name = "on_date", nullable = false, updatable = false)
  private LocalDate onDate;

  @Enumerated(EnumType.STRING)
  @Column(name = "meal_slot", length = 24)
  private MealSlot mealSlot;                       // nullable: untied entries

  @Column(name = "journal_entry", nullable = false, columnDefinition = "text")
  private String journalEntry;

  @Column(name = "logged_at", nullable = false)
  private Instant loggedAt;

  @Version @Column(name = "optimistic_version", nullable = false)
  private long optimisticVersion;

  @CreationTimestamp
  @Column(name = "created_at", updatable = false, nullable = false)
  private Instant createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;
}
```

### Repository — package-private

```java
interface FoodMoodJournalRepository extends JpaRepository<FoodMoodJournalEntry, UUID> {
  List<FoodMoodJournalEntry> findByUserIdAndOnDateOrderByLoggedAtAsc(UUID userId, LocalDate onDate);
  Page<FoodMoodJournalEntry> findByUserIdOrderByLoggedAtDesc(UUID userId, Pageable p);
  List<FoodMoodJournalEntry> findTop20ByUserIdOrderByLoggedAtDesc(UUID userId);
}
```

### Mapper

```java
@Mapper(componentModel = "spring")
public interface JournalMapper {
  FoodMoodEntryDto toDto(FoodMoodJournalEntry entity);
  List<FoodMoodEntryDto> toDtos(List<FoodMoodJournalEntry> entities);
}
```

### Service-impl — update path skeleton

```java
@Transactional
public FoodMoodEntryDto updateJournalEntry(UUID userId, UUID entryId,
                                           UpsertFoodMoodEntryRequest request) {
  FoodMoodJournalEntry existing = repo.findById(entryId)
      .filter(e -> e.getUserId().equals(userId))                           // 404 not 403 — don't leak
      .orElseThrow(JournalEntryNotFoundException::new);
  if (!existing.getOnDate().equals(request.onDate())) {
    throw new JournalEntryNotFoundException();                              // path-body mismatch hidden as 404
  }
  if (existing.getOptimisticVersion() != request.expectedVersion()) {
    throw new OptimisticLockingFailureException("stale expectedVersion");   // → 409 via GlobalExceptionHandler
  }
  existing.setMealSlot(request.mealSlot());
  existing.setJournalEntry(request.journalEntry());
  existing.setLoggedAt(request.loggedAt());
  FoodMoodJournalEntry saved = repo.saveAndFlush(existing);                  // gotcha: flush so @Version increments
  publisher.publishEvent(new FoodMoodEntryWrittenEvent(
      saved.getId(), saved.getUserId(), saved.getOnDate(), saved.getMealSlot(),
      JournalAction.UPDATED, traceIdFromMdcOrRandom(), Instant.now()));
  return mapper.toDto(saved);
}
```

## Edge-case checklist

- [ ] `POST /journal/{date}` with valid body, no existing row → 201 with `FoodMoodEntryDto`; `Location` header set; row visible on subsequent `GET /journal/{date}`
- [ ] `POST /journal/{date}` with `mealSlot = null` × 3 in succession on the same date → 3 rows persisted (NULL not unique-constrained)
- [ ] `POST /journal/{date}` with `mealSlot = LUNCH` then a second `POST` same slot → 409 (`DataIntegrityViolationException` mapped via `GlobalExceptionHandler`)
- [ ] `POST /journal/{date}` with `request.onDate` ≠ path `{date}` → 400
- [ ] `POST /journal/{date}` validation: `journalEntry` empty → 400; `journalEntry` > 4000 chars → 400; `loggedAt = null` → 400
- [ ] `GET /journal/{date}` returns entries sorted `loggedAt ASC`; empty list when none (NOT 404)
- [ ] `GET /journal?page=&size=` returns paginated newest-first; default size 20; size > 100 clamped to 100; valid Spring `Page<T>` JSON shape (flat, `additionalProperties: true` accepted by swagger-validator)
- [ ] `PUT /journal/{date}/entries/{entryId}` with stale `expectedVersion` → 409 via `OptimisticLockException`
- [ ] `PUT /journal/{date}/entries/{entryId}` for a different user's entry → 404 (don't leak)
- [ ] `PUT /journal/{date}/entries/{entryId}` with `request.onDate ≠ entity.onDate` → 400 (cannot move across days via PUT)
- [ ] `PUT /journal/{date}/entries/{entryId}` happy path → 200, `optimisticVersion` bumped, fields updated
- [ ] `DELETE /journal/{date}/entries/{entryId}` happy path → 204; row gone; second DELETE → 404
- [ ] `DELETE /journal/{date}/entries/{entryId}` for different user's entry → 404 (don't leak)
- [ ] `getJournalEntriesForFeedbackContext` returns max 20 entries newest-first when 25 exist; returns all when fewer than 20 exist
- [ ] `FoodMoodEntryWrittenEvent` published `AFTER_COMMIT` exactly once on each of CREATE, UPDATE, DELETE (with the corresponding `action` enum)
- [ ] OpenAPI request/response shapes match (swagger-request-validator filter active in IT)
- [ ] `NutritionBoundaryTest` (from 01a) still passes — new repo in `domain/repository/` package
- [ ] `NutritionExceptionHandler` retains `@Order(Ordered.HIGHEST_PRECEDENCE)` after appending the new `JournalEntryNotFoundException` handler method
- [ ] No N+1 on any read path — single SELECT verified via Hibernate stats or statement-count assertion in IT
- [ ] No raw `userId` accepted from request body / query — server-resolved via `CurrentUserResolver` on every endpoint

## Files this ticket touches

```
NEW   src/main/resources/db/migration/V20260601600500__nutrition_create_food_mood_journal.sql

NEW   src/main/java/com/example/mealprep/nutrition/api/controller/JournalController.java
NEW   src/main/java/com/example/mealprep/nutrition/api/dto/FoodMoodEntryDto.java
NEW   src/main/java/com/example/mealprep/nutrition/api/dto/UpsertFoodMoodEntryRequest.java
NEW   src/main/java/com/example/mealprep/nutrition/api/dto/JournalAction.java
NEW   src/main/java/com/example/mealprep/nutrition/api/mapper/JournalMapper.java
NEW   src/main/java/com/example/mealprep/nutrition/domain/entity/FoodMoodJournalEntry.java
NEW   src/main/java/com/example/mealprep/nutrition/domain/repository/FoodMoodJournalRepository.java
NEW   src/main/java/com/example/mealprep/nutrition/event/FoodMoodEntryWrittenEvent.java
NEW   src/main/java/com/example/mealprep/nutrition/exception/JournalEntryNotFoundException.java

MOD   src/main/java/com/example/mealprep/nutrition/api/NutritionExceptionHandler.java                 (append 1 @ExceptionHandler method; KEEP @Order(Ordered.HIGHEST_PRECEDENCE))
MOD   src/main/java/com/example/mealprep/nutrition/domain/service/NutritionQueryService.java         (append getJournalEntriesForDay, getRecentJournalEntries, getJournalEntriesForFeedbackContext)
MOD   src/main/java/com/example/mealprep/nutrition/domain/service/NutritionUpdateService.java        (append upsertJournalEntry, updateJournalEntry, deleteJournalEntry)
MOD   src/main/java/com/example/mealprep/nutrition/domain/service/internal/NutritionServiceImpl.java (implement the six new methods)

MOD   src/main/resources/openapi/paths/nutrition.yaml      (append 4 new path-items below 01b's; do NOT touch existing)
MOD   src/main/resources/openapi/schemas/nutrition.yaml    (append 4 new schemas)
MOD   src/main/resources/openapi/openapi.yaml              (3 lines under paths: in the `# nutrition` block; 4 lines under components.schemas: in the `# nutrition` block)

NEW   src/test/java/com/example/mealprep/nutrition/JournalServiceTest.java
NEW   src/test/java/com/example/mealprep/nutrition/JournalFlowIT.java
MOD   src/test/java/com/example/mealprep/nutrition/testdata/NutritionTestData.java                   (append journal-entry builder fixture)
```

**Files this ticket does NOT modify** (cross-cutting; sibling round-3 tickets running in parallel must not collide):
- `src/main/java/com/example/mealprep/config/GlobalExceptionHandler.java` — module exceptions go in the existing `NutritionExceptionHandler`.
- `src/test/java/com/example/mealprep/archunit/ModuleBoundaryTest.java` — module boundary rule lives in the existing `NutritionBoundaryTest`.
- Other modules' `paths/*.yaml`, `schemas/*.yaml`, `<module>ExceptionHandler.java`, `<module>BoundaryTest.java`, migrations, entities — none touched.
- 01a's `NutritionTargets` aggregate / 01b's `IntakeDay` and `DailyActivityLog` aggregates — journal is a peer aggregate; no `@OneToMany` added to existing entities.
- `NutritionBoundaryTest` is unchanged.

## Dependencies

- **Hard dependency**: `nutrition-01a` (merged) — `MealSlot` enum, `NutritionQueryService`, `NutritionUpdateService`, `NutritionExceptionHandler`, `NutritionBoundaryTest`, `NutritionException`.
- **Hard dependency**: `nutrition-01b` (merged) — extends the same two service interfaces; the `@ExceptionHandler` ordering pattern; the per-module YAML / advice append-only convention.
- **Hard dependency**: `auth-01a` (merged) — `CurrentUserResolver`, `SessionAuthenticationFilter`.
- **Hard dependency**: `refactor-01-split-merge-zones` (merged) — per-module YAML / advice / boundary-test layout.
- **Sibling tickets running in parallel** (Wave 2 round 3): `household-01c`, `provisions-01c`, `recipe-01c`. None should touch any nutrition file or any of the cross-cutting files listed above. Only collision point is the entry `openapi.yaml`; this ticket appends in the `# nutrition` block, sibling tickets append in their own module's block.

## Acceptance / DoD

- [ ] `./mvnw -Dspotless.check.skip=true verify` passes locally on the agent's worktree
- [ ] `./mvnw spotless:apply` then `./mvnw spotless:check` clean
- [ ] CI green on the PR (build + spotless + OpenAPI lint + ArchUnit gate)
- [ ] All edge-case items above ticked
- [ ] `NutritionExceptionHandler` retains `@Order(Ordered.HIGHEST_PRECEDENCE)` after the new method is appended
- [ ] `saveAndFlush` used in the update path so the response payload reflects the bumped `optimisticVersion` (gotcha #3 from preference-01a / replaceChildren-flush note)
- [ ] OpenAPI 3.0 nullable enum on `mealSlot` uses **inline** `nullable: true` (not `$ref + nullable: true`)
- [ ] `FoodMoodEntryDtoPage` uses the **flat** Page<T> shape with `additionalProperties: true`
- [ ] No regression on existing tests, including 01a's `TargetsFlowIT` and 01b's intake / activity ITs
- [ ] No N+1 on any read path — single SELECT verified via Hibernate stats or statement-count assertion in IT
- [ ] No pom.xml dependency adds (uses existing JPA / MapStruct / `hypersistence-utils-hibernate-63`)

## What's NOT in scope

- Ingredient mapping cache + USDA / OFF clients + `IngredientMappingPipeline` lookup endpoints → **nutrition-01d**
- Health directives queue (inbound / accept / reject / safety gate) → **nutrition-01e**
- `NutritionCalculationService` (recipe save-time + `RecipeEvolvedEvent` listener) → **nutrition-01f**
- `NutritionFloorGateService` for the planner → **nutrition-01g**
- `IntakeAggregator`, `WeeklyAggregateDto`, weekly-rollup endpoints, `DivergenceDetector` + `NutritionIntakeDivergedEvent` → **nutrition-01h**
- `MealCookedEvent` auto-confirm listener → **nutrition-01i**
- AI-driven free-text override parsing (`IntakeOverrideParserTask`) → **nutrition-01k**
- Cross-module pantry-deduct on snack log → **nutrition-01l**
- `applyFeedback(NutritionFeedbackRequest)` from feedback module — needs the feedback module → **nutrition-01m**
- Journal NLP / mood-correlation analysis (LLD §Out of Scope — owned by Feedback System / health platform)
- `from`/`to` date-range query on the recent-entries listing (LLD doesn't specify; deferred — `worth user review`)
- Audit log for journal entries (LLD doesn't specify one; mutations recorded only via the `FoodMoodEntryWrittenEvent`)
- Soft-delete on journal entries (LLD doesn't specify; hard-delete is the contract)

Squash-merge with: `feat(nutrition): 01c — food/mood journal aggregate + CRUD endpoints + feedback-context helper`
