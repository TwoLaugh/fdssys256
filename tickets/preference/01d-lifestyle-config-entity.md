# Ticket: preference — 01d Lifestyle Config Entity + CRUD + Audit Log

## Summary

Implement **Tier 3 Lifestyle Config** of the three-tier Preference Model — the user-set settings JSONB document described in [`design/preference-model.md` lines 179-292](../../design/preference-model.md) and specified in [`lld/preference.md` lines 195-220, 259, 383-409, 493-495, 575-577, 616-618, 733-734](../../lld/preference.md). Per roadmap §B1.2.

Ships:

- **2 new tables**: `preference_lifestyle_config` (JSONB document) + `preference_lifestyle_config_audit` (section-level diffs).
- **`LifestyleConfig` JPA entity** with `@Version` for optimistic concurrency.
- **`LifestyleConfigDocument` record-tree** mirroring the HLD's 12 nested sections (`MealStructure`, `MealTiming`, `NoveltyTolerance`, `CookingContexts`, `BatchCooking`, `ReheatingPreferences`, `EatingContext`, `SeasonalPreferences`, `MealTypePreferences`, `Accompaniments`, `GroceryQualityPreferences`, `PantryTracking`).
- **Service split**: `LifestyleConfigQueryService` + `LifestyleConfigUpdateService`, both impl'd by `LifestyleConfigServiceImpl`.
- **REST CRUD**: GET, PUT, POST `/mark-reviewed`, GET audit log.
- **Section-level audit log**: each top-level section that changes writes one row.

**Deferred to a follow-up**:

- **Behavioural drift prompt** (C-B-046) — the 2-3 month "is this still accurate?" nudge per `design/preference-model.md:184` is a scheduled trigger that doesn't belong in 01d's CRUD scope. Ship the `lastReviewPromptAt` column (so the future trigger has somewhere to write) and the `POST /mark-reviewed` endpoint (resets the timestamp), but **do not** ship the `@Scheduled` scanner that emits the prompt. **C-B-046 is the deferred ticket capability.**
- **`@ValidNoveltyTolerance` class-level validator** ships here (per `lld/preference.md:653`) — this IS in scope.

**Worth user review**: storing lifestyle config as a single JSONB document (vs ~50 columns across nested tables) is the LLD's chosen shape but flagged as "worth user review" at `lld/preference.md:197`. The trade-off: JSONB matches the planner's "read whole document" pattern but loses column-level constraints. The PR description documents the call. The alternative — split into per-section tables — is a major refactor; the JSONB-with-validated-record approach is the path of least resistance for v1.

Closes: **C-A-005** (Lifestyle config — full settings shape). Partial close on **C-G-059** (Lifestyle config audit log — the audit log ships here; the behavioural-drift prompt is the deferred half).

## Behavioural spec

### Database schema

1. Migration `V20260615160000__preference_create_lifestyle_config.sql` per [`lld/preference.md:200-209`](../../lld/preference.md):
   ```sql
   CREATE TABLE preference_lifestyle_config (
       id                       uuid PRIMARY KEY,
       user_id                  uuid NOT NULL UNIQUE,
       document                 jsonb NOT NULL,
       last_review_prompt_at    timestamptz,
       optimistic_version       bigint NOT NULL DEFAULT 0,
       created_at               timestamptz NOT NULL,
       updated_at               timestamptz NOT NULL
   );
   CREATE UNIQUE INDEX idx_pref_lifestyle_config_user ON preference_lifestyle_config (user_id);
   ```
2. Migration `V20260615160100__preference_create_lifestyle_config_audit.sql` per [`lld/preference.md:211-220`](../../lld/preference.md):
   ```sql
   CREATE TABLE preference_lifestyle_config_audit (
       id                       uuid PRIMARY KEY,
       lifestyle_config_id      uuid NOT NULL REFERENCES preference_lifestyle_config(id) ON DELETE CASCADE,
       actor_user_id            uuid NOT NULL,
       field_path               varchar(128) NOT NULL,
       previous_value_json      jsonb NOT NULL,
       new_value_json           jsonb NOT NULL,
       occurred_at              timestamptz NOT NULL
   );
   CREATE INDEX idx_pref_lc_audit_lc_time ON preference_lifestyle_config_audit (lifestyle_config_id, occurred_at DESC);
   CREATE INDEX idx_pref_lc_audit_actor ON preference_lifestyle_config_audit (actor_user_id, occurred_at DESC);
   ```

### Entities

3. **`LifestyleConfig`** at `preference.domain.entity.LifestyleConfig`. Aggregate root. Lombok per style guide. Fields:
   - `id (UUID, @Id, application-set, updatable=false, nullable=false)`
   - `userId (UUID, NOT NULL, UNIQUE, updatable=false)`
   - `document (LifestyleConfigDocument, @Type(JsonType.class), columnDefinition="jsonb", NOT NULL)`
   - `lastReviewPromptAt (Instant, nullable)` — set by future behavioural-drift scanner; reset by `markReviewed`
   - `optimisticVersion (long, @Version)`
   - `createdAt (@CreatedDate)`, `updatedAt (@LastModifiedDate)`
4. **`LifestyleConfigAuditLog`** at `preference.domain.entity.LifestyleConfigAuditLog`. Append-only. Fields:
   - `id (UUID)`
   - `lifestyleConfig (@ManyToOne(fetch=LAZY), @JoinColumn(name="lifestyle_config_id", nullable=false, updatable=false))`
   - `actorUserId (UUID, NOT NULL)`
   - `fieldPath (String, length=128, NOT NULL)` — section-level e.g. `"batch_cooking"`, `"novelty_tolerance"`. Sub-section paths (`"batch_cooking.prep_days"`) allowed if the diff is meaningfully scoped lower.
   - `previousValueJson (JsonNode, @Type(JsonType.class), columnDefinition="jsonb", NOT NULL)`
   - `newValueJson (JsonNode, @Type(JsonType.class), columnDefinition="jsonb", NOT NULL)`
   - `occurredAt (Instant, NOT NULL)`
   - **No `@Version`**, **no `@LastModifiedDate`** (append-only).

### Document shape (mirrors HLD)

5. **`LifestyleConfigDocument`** at `preference.domain.document.LifestyleConfigDocument`. Java record. Fields per `lld/preference.md:385`:
   ```java
   public record LifestyleConfigDocument(
       MealStructure mealStructure,
       MealTiming mealTiming,
       NoveltyTolerance noveltyTolerance,
       CookingContexts cookingContexts,
       BatchCooking batchCooking,
       ReheatingPreferences reheatingPreferences,
       EatingContext eatingContext,
       SeasonalPreferences seasonalPreferences,
       MealTypePreferences mealTypePreferences,
       Accompaniments accompaniments,
       GroceryQualityPreferences groceryQualityPreferences,
       PantryTracking pantryTracking) { /* nested records */ }
   ```
6. Nested records per `design/preference-model.md:190-291`. Each is a `public static record` inside `LifestyleConfigDocument`. **Field shapes**:
   - `MealStructure(DayProfile weekday, DayProfile weekend, List<RecurringSkip> recurringSkips)`
     - `DayProfile(List<String> meals, SnackPolicy snacks)`
     - `SnackPolicy(boolean planned, String style, String notes)`
     - `RecurringSkip(String day, String meal, String reason)`
   - `MealTiming(MealSchedule preferredSchedule, String flexibility, String notes)`
     - `MealSchedule(Map<String, String> times)` — keys: `breakfast`, `lunch`, `dinner` etc. Values: `"07:00-08:00"` range string. Free-form to allow weekend-brunch values.
   - `NoveltyTolerance(Map<String, NoveltyMode> bySlot, Map<String, Integer> recipeRepeatCooldownWeeks, Map<String, String> ingredientFrequencyCaps)`
     - `NoveltyMode(String mode, Integer rotationSize, Integer maxConsecutiveSame, Integer weeklyUniqueMinimum, Integer newPerWeek)` — mode-specific fields nullable per mode.
   - `CookingContexts(Map<String, CookingContext> byContext)`
     - `CookingContext(int maxTimeMins, String complexity, List<String> preferredStyles, IngredientCountRange preferredIngredientCount, String notes, String source, String frequency)`
     - `IngredientCountRange(int min, int max)`
   - `BatchCooking(List<PrepDay> prepDays, Map<String, Integer> maxLeftoverDays, String leftoverStrategy, FreezerTolerance freezerTolerance, boolean sameProteinSameDay, String parallelCookingTolerance)`
     - `PrepDay(String day, String window, int maxSessionHours, int maxRecipes)`
     - `FreezerTolerance(boolean acceptable, int maxFrozenMealsPerWeek, List<String> exclusions)`
   - `ReheatingPreferences(List<String> availableAtWork, List<String> availableAtHome, String preferredMethod, List<ReheatRule> exclusions, List<String> coldMealTolerance)`
     - `ReheatRule(String category, String rule, String reason)`
   - `EatingContext(Map<String, ContextEntry> bySlot)`
     - `ContextEntry(String location, String format, List<String> constraints)`
   - `SeasonalPreferences(Map<String, SeasonPolicy> bySeason)`
     - `SeasonPolicy(List<String> leanToward, List<String> avoid)`
   - `MealTypePreferences(Map<String, MealTypeRule> byType)`
     - `MealTypeRule(String varietyTolerance, String complexityTolerance, List<String> staples, String notes)`
   - `Accompaniments(BeveragePolicy beverages, SidesPolicy sides)`
     - `BeveragePolicy(String withMeals, String morning, List<String> avoids)`
     - `SidesPolicy(String notes)`
   - `GroceryQualityPreferences(String organic, String freeRangeEggs, String freeRangeMeat, String brandedVsOwnLabel, String notes)`
   - `PantryTracking(boolean enabled)` — the project-wide pantry-on/off flag per `lld/preference.md:390-395`.

### Repositories

7. **`LifestyleConfigRepository`** (package-private):
   ```java
   interface LifestyleConfigRepository extends JpaRepository<LifestyleConfig, UUID> {
     Optional<LifestyleConfig> findByUserId(UUID userId);
     List<LifestyleConfig> findByUserIdIn(Collection<UUID> userIds);
     // For the future behavioural-drift scanner (C-B-046, deferred):
     List<LifestyleConfig> findByLastReviewPromptAtBeforeOrLastReviewPromptAtIsNull(Instant threshold);
   }
   ```
8. **`LifestyleConfigAuditLogRepository`**:
   ```java
   interface LifestyleConfigAuditLogRepository extends JpaRepository<LifestyleConfigAuditLog, UUID> {
     Page<LifestyleConfigAuditLog> findByLifestyleConfigIdOrderByOccurredAtDesc(UUID id, Pageable p);
     Page<LifestyleConfigAuditLog> findByLifestyleConfigIdAndFieldPathStartingWithOrderByOccurredAtDesc(
         UUID id, String fieldPathPrefix, Pageable p);
   }
   ```

### DTOs

9. **`LifestyleConfigDto`** per `lld/preference.md:398`:
   ```java
   public record LifestyleConfigDto(
       UUID id, UUID userId,
       LifestyleConfigDocument document,
       Instant lastReviewPromptAt,
       long optimisticVersion,
       Instant createdAt, Instant updatedAt) {}
   ```
10. **`UpdateLifestyleConfigRequest`** per `lld/preference.md:405-408`:
    ```java
    public record UpdateLifestyleConfigRequest(
        @NotNull @Valid @ValidNoveltyTolerance LifestyleConfigDocument document,
        long expectedVersion) {}
    ```
11. **`LifestyleConfigAuditEntryDto`**:
    ```java
    public record LifestyleConfigAuditEntryDto(
        UUID id, UUID actorUserId,
        String fieldPath,
        JsonNode previousValueJson, JsonNode newValueJson,
        Instant occurredAt) {}
    ```

### Mapper

12. **`LifestyleConfigMapper`** — MapStruct. `toDto(LifestyleConfig)`, `toAuditEntryDto(LifestyleConfigAuditLog)`, plural variants.

### Service interfaces

13. **`LifestyleConfigQueryService`**:
    ```java
    public interface LifestyleConfigQueryService {
      Optional<LifestyleConfigDto> getLifestyleConfig(UUID userId);
      List<LifestyleConfigDto> getLifestyleConfigsByUserIds(List<UUID> userIds);
      Page<LifestyleConfigAuditEntryDto> getAuditLog(UUID userId, Pageable pageable);
      Page<LifestyleConfigAuditEntryDto> getAuditLogForSection(UUID userId, String sectionPath, Pageable pageable);
    }
    ```
14. **`LifestyleConfigUpdateService`**:
    ```java
    public interface LifestyleConfigUpdateService {
      LifestyleConfigDto initialise(UUID userId, UpdateLifestyleConfigRequest request);
      LifestyleConfigDto update(UUID userId, UpdateLifestyleConfigRequest request, UUID actorUserId);
      LifestyleConfigDto markReviewed(UUID userId);                  // resets lastReviewPromptAt
    }
    ```
15. **`LifestyleConfigServiceImpl`** at `preference.domain.service.internal`. Single impl. `@Transactional` on writes; `@Transactional(readOnly = true)` on reads.

### REST controller

16. **`LifestyleConfigController`** at `preference.api.controller`. `@RequestMapping("/api/v1/preferences/lifestyle-config")`. Endpoints:

| Method | Path | Request | Response | Status |
|---|---|---|---|---|
| GET | `/api/v1/preferences/lifestyle-config` | — | `LifestyleConfigDto` | 200 / 404 |
| PUT | `/api/v1/preferences/lifestyle-config` | `UpdateLifestyleConfigRequest` | `LifestyleConfigDto` | 200 / 400 / 404 / 409 |
| POST | `/api/v1/preferences/lifestyle-config/mark-reviewed` | — | `LifestyleConfigDto` | 200 / 404 |
| GET | `/api/v1/preferences/lifestyle-config/audit-log` | query `?page=&size=&section=` | `Page<LifestyleConfigAuditEntryDto>` | 200 |

Authentication required on every endpoint. `userId` via `CurrentUserResolver`.

### Section-level diff (for audit log)

17. **Diff algorithm** (in `LifestyleConfigServiceImpl.update`):
    - For each of the 12 top-level sections in `LifestyleConfigDocument`, JSON-serialise old + new values.
    - Compare via `JsonNode.equals` (structural equality).
    - If different → write one `LifestyleConfigAuditLog` row with `fieldPath = "<sectionName>"` (e.g. `"batchCooking"`), `previousValueJson` and `newValueJson` set to the JSON-tree of the section.
    - No-op sections (unchanged) → no audit row.
    - **Atomic batch**: all audit rows written in the same `@Transactional` boundary as the document update.

### Behavioural-drift scanner (DEFERRED)

18. **NOT IN SCOPE**: the `@Scheduled` scanner that fires `LifestyleConfigStaleEvent` after `lastReviewPromptAt` ages past 2-3 months. Ship the `lastReviewPromptAt` column + `markReviewed` endpoint here; the scanner ships in the C-B-046 follow-up ticket.
19. **`LifestyleConfigStaleEvent` event class**: also deferred. The event will be defined alongside the scanner.

### Validation

20. **`@ValidNoveltyTolerance`** custom validator per `lld/preference.md:653` — class-level on the `NoveltyTolerance` nested record. Asserts:
    - Each per-slot `mode ∈ {rotation, batch_repeat, high_variety, static}`.
    - `rotation` requires `rotationSize > 0`; `batch_repeat` requires `maxConsecutiveSame > 0`; `high_variety` requires `newPerWeek > 0`; `static` accepts no mode-specific fields.
    - All `recipeRepeatCooldownWeeks` values ≥ 0.
    - `ingredientFrequencyCaps` keys are non-blank.
21. Standard Jakarta annotations everywhere: `@NotNull`, `@NotBlank`, `@Min(0)`, `@Size(max=N)` on string fields and lists.

### Events

22. **Published**:
    - `LifestyleConfigChangedEvent(UUID userId, UUID lifestyleConfigId, Set<String> changedSections, UUID traceId, Instant occurredAt)` — extends `core.events.ScopeChangedEvent` with `scopeKind="lifestyle-config"`, `scopeId=userId`. Fired `AFTER_COMMIT` on `update`. Carries the **section names that changed** so listeners (planner re-opt, future notification "lifestyle update" surfacing) can act selectively.
    - `LifestyleConfigInitialisedEvent(UUID userId, UUID lifestyleConfigId, UUID traceId, Instant occurredAt)` — fired by `initialise()`. Separate event because consumers may want to react differently to "user just onboarded" vs "user changed an existing setting".
23. **Consumed**: none.

### Cross-cutting

24. New exceptions:
    - `LifestyleConfigNotFoundException` (404) — per `lld/preference.md:635`. Ship here.
    - `InvalidNoveltyToleranceException` (400) — thrown by the `@ValidNoveltyTolerance` validator. Maps to ProblemDetail with the offending mode + field.
25. `GlobalExceptionHandler` handlers added for both.
26. `PreferenceModule` facade adds the two new service accessors.
27. ArchUnit: existing module-boundary rule covers the new repos.

## Database

```
NEW   src/main/resources/db/migration/V20260615160000__preference_create_lifestyle_config.sql
NEW   src/main/resources/db/migration/V20260615160100__preference_create_lifestyle_config_audit.sql
```

Schemas per §1, §2.

## OpenAPI updates

Add 4 paths + 3 schemas to `paths/preference.yaml` and `schemas/preference.yaml`:

**Paths**: the four endpoints in §16. All `security: [cookieAuth: []]`.

**Schemas**:
- `LifestyleConfigDto`
- `LifestyleConfigDocument` (with all 12 nested record types — large schema; structure mirrors §6)
- `LifestyleConfigAuditEntryDto`
- `UpdateLifestyleConfigRequest`

The nested-record types use `additionalProperties: false`. `Map<String, X>` types use `additionalProperties: { $ref: '#/components/schemas/X' }` per OpenAPI convention.

## Edge-case checklist

- [ ] Two migrations apply cleanly; `FlywayMigrationIT` passes.
- [ ] `ddl-auto=validate` accepts the entity-to-schema mapping.
- [ ] `LifestyleConfigDocument` JSONB round-trip with all 12 sections populated — `LifestyleConfigDocumentRoundTripTest` (unit) succeeds.
- [ ] GET on user with no config → 404 `LifestyleConfigNotFoundException`.
- [ ] `initialise()` creates a config with sensible empty defaults and writes audit rows for each non-default section (or one summary audit row with `fieldPath = "*"` — pick the latter for simplicity; **worth implementer review**).
- [ ] PUT with `expectedVersion = 0` on a config at version 5 → 409.
- [ ] PUT with valid document → 200, `optimisticVersion` bumped, one audit row per changed section.
- [ ] PUT with **only `batchCooking` changed** writes exactly one audit row with `fieldPath = "batchCooking"`.
- [ ] PUT with **no-op** (identical document) → 200, no audit rows written, `optimisticVersion` still bumps (JPA's intrinsic behaviour — accepted).
- [ ] `@ValidNoveltyTolerance` rejects mode=`rotation` with `rotationSize=0` → 400.
- [ ] `@ValidNoveltyTolerance` rejects mode=`batch_repeat` with `maxConsecutiveSame=null` → 400.
- [ ] `@ValidNoveltyTolerance` accepts mode=`static` with no mode-specific fields.
- [ ] `@ValidNoveltyTolerance` rejects `recipeRepeatCooldownWeeks.default = -1` → 400.
- [ ] POST `/mark-reviewed` resets `lastReviewPromptAt` to NULL and bumps `optimisticVersion`.
- [ ] POST `/mark-reviewed` on user with no config → 404.
- [ ] Audit log: `GET /audit-log?section=batchCooking` returns only rows where `field_path = 'batchCooking'` or starts with `'batchCooking.'`.
- [ ] Audit log pagination works (default size 20, max 100).
- [ ] **Cross-tenant**: user A cannot read user B's config or audit log.
- [ ] `LifestyleConfigChangedEvent` fires exactly once per successful PUT, `AFTER_COMMIT`, with the correct `changedSections` set.
- [ ] `LifestyleConfigInitialisedEvent` fires exactly once per `initialise`.
- [ ] **PantryTracking flag**: setting `document.pantryTracking.enabled = false` persists; future modules (planner, notification, provisions) read it via `LifestyleConfigQueryService.getLifestyleConfig(userId)` and respect it (verified later as those modules consume the flag).
- [ ] OpenAPI contract test passes.
- [ ] ArchUnit: no class outside `preference..` imports the two new repositories.
- [ ] Free-text `notes` fields accept Unicode (emoji, non-ASCII).
- [ ] Very deeply-nested document (e.g. 15-key `cookingContexts` map) persists and round-trips.
- [ ] `Map<String, X>` serialisation uses string keys; Jackson default behaviour suffices.
- [ ] **No `@ValidQuietHours` here** — that's the notification module's validator (`tickets/notification/01a-core.md`).
- [ ] The `lastReviewPromptAt` column starts NULL on initialisation (no prompt has been emitted yet).

## Files this ticket touches

```
NEW   src/main/resources/db/migration/V20260615160000__preference_create_lifestyle_config.sql
NEW   src/main/resources/db/migration/V20260615160100__preference_create_lifestyle_config_audit.sql

NEW   src/main/java/com/example/mealprep/preference/domain/entity/LifestyleConfig.java
NEW   src/main/java/com/example/mealprep/preference/domain/entity/LifestyleConfigAuditLog.java

NEW   src/main/java/com/example/mealprep/preference/domain/document/LifestyleConfigDocument.java   (top-level + 12 nested record families)

NEW   src/main/java/com/example/mealprep/preference/domain/repository/LifestyleConfigRepository.java
NEW   src/main/java/com/example/mealprep/preference/domain/repository/LifestyleConfigAuditLogRepository.java

NEW   src/main/java/com/example/mealprep/preference/domain/service/LifestyleConfigQueryService.java
NEW   src/main/java/com/example/mealprep/preference/domain/service/LifestyleConfigUpdateService.java
NEW   src/main/java/com/example/mealprep/preference/domain/service/internal/LifestyleConfigServiceImpl.java

NEW   src/main/java/com/example/mealprep/preference/api/controller/LifestyleConfigController.java
NEW   src/main/java/com/example/mealprep/preference/api/dto/LifestyleConfigDto.java
NEW   src/main/java/com/example/mealprep/preference/api/dto/UpdateLifestyleConfigRequest.java
NEW   src/main/java/com/example/mealprep/preference/api/dto/LifestyleConfigAuditEntryDto.java
NEW   src/main/java/com/example/mealprep/preference/api/mapper/LifestyleConfigMapper.java

NEW   src/main/java/com/example/mealprep/preference/validation/ValidNoveltyTolerance.java
NEW   src/main/java/com/example/mealprep/preference/validation/NoveltyToleranceValidator.java

NEW   src/main/java/com/example/mealprep/preference/event/LifestyleConfigChangedEvent.java
NEW   src/main/java/com/example/mealprep/preference/event/LifestyleConfigInitialisedEvent.java

NEW   src/main/java/com/example/mealprep/preference/exception/LifestyleConfigNotFoundException.java
NEW   src/main/java/com/example/mealprep/preference/exception/InvalidNoveltyToleranceException.java

MOD   src/main/java/com/example/mealprep/preference/PreferenceModule.java
MOD   src/main/java/com/example/mealprep/config/GlobalExceptionHandler.java
MOD   src/main/resources/openapi/paths/preference.yaml
MOD   src/main/resources/openapi/schemas/preference.yaml

NEW   src/test/java/com/example/mealprep/preference/LifestyleConfigServiceImplTest.java
NEW   src/test/java/com/example/mealprep/preference/LifestyleConfigDocumentRoundTripTest.java
NEW   src/test/java/com/example/mealprep/preference/NoveltyToleranceValidatorTest.java
NEW   src/test/java/com/example/mealprep/preference/LifestyleConfigFlowIT.java
NEW   src/test/java/com/example/mealprep/preference/LifestyleConfigEventPublicationIT.java
NEW   src/test/java/com/example/mealprep/preference/testdata/LifestyleConfigTestData.java
```

Total: ~22 new files + 4 mods. Estimated agent runtime 3-4 hours.

## Dependencies

- **Hard dependency**: `core-01-decision-log` (merged) — `ScopeChangedEvent`.
- **Hard dependency**: `auth-01a` (merged) — session-cookie auth, `CurrentUserResolver`.
- **Hard dependency**: `preference-01a` (merged) — `PreferenceModule` facade.
- **Independent of** `preference-01c` (taste profile) — can ship in parallel.

## Acceptance / DoD

- [ ] `./mvnw -Dspotless.check.skip=true verify` passes
- [ ] `./mvnw spotless:apply` then `./mvnw spotless:check` clean
- [ ] CI green (build + spotless + OpenAPI lint + ArchUnit + contract tests)
- [ ] All edge-case items above ticked
- [ ] Manual smoke documented in PR: register user → GET (404) → POST init → GET (200) → PUT with `batchCooking` change → audit log shows 1 row → POST `/mark-reviewed` → `lastReviewPromptAt` now NULL.

## What's NOT in scope

- **Behavioural-drift scanner / `LifestyleConfigStaleEvent`** — C-B-046 follow-up.
- **Frontend settings UI** — out of scope.
- **Sensible defaults for unconfigured fields** — per `lld/preference.md:801`, deferred until all three preference tiers ship.
- **Per-section validation** beyond `@ValidNoveltyTolerance` — additional validators (e.g. coherent meal-timing windows) ship as needed when failure modes surface.
- **PantryTracking consumers** (planner, notification, provisions reading the flag) — those modules' tickets.
- **Onboarding wizard composition** — UX.

Squash-merge with: `feat(preference): 01d — lifestyle config entity + CRUD + audit log (Tier B B1.2)`
