# Ticket: household — 01e Household Merge Service (Stub-Friendly Skeleton) + SoftPreferencesReader SPI

## Summary

Layer the **`HouseholdMergeService`** + `MergedSoftPreferencesDto` + `SoftPreferenceMerger` skeleton on top of the 01a/01b/01c/01d household aggregate per [LLD §`MergedSoftPreferencesDto` lines 192-207](../../lld/household.md), [LLD §`HouseholdMergeService` lines 309-326](../../lld/household.md), [LLD §Flow 7 (mergeSoftPreferences) lines 435-446](../../lld/household.md). Ships the full service interface + DTO shapes + REST endpoint `POST /api/v1/households/current/merge` returning `MergedSoftPreferencesDto`. Defines an in-module SPI **`SoftPreferencesReader`** that the household module looks up via `@Autowired(required = false)` — when preference-01c lands a soft-preferences implementation it wires the bean and the merge produces real output; until then, household-01e binds a `NoopSoftPreferencesReader` `@Component` that returns `List.of()` and the merge runs over empty input correctly (returning an empty `MergedSoftPreferencesDto`). The `SoftPreferenceMerger` (`@Component` in `domain/service/internal/`) implements the three merge rules from LLD line 441 (mean-weighted taste-profile, avoid-list union, most-restrictive lifestyle) but **degenerates to empty output cleanly when no bundles** — unit-tested at all three branches. Plus `SoftPreferenceBundleDto`, `TasteProfileDocument`, `LifestyleConfigDocument` lightweight record stubs declared in `household/api/dto/` (NOT in preference's package — see "LLD divergence note: SPI strategy" below) so the household module compiles without depending on a not-yet-existent preference soft-prefs type.

**LLD divergence note** — **SPI strategy (option b)**: per the parent's per-module guidance, household-01e ships a **`SoftPreferencesReader`** SPI interface owned by household, looked up via `@Autowired(required = false) Optional<SoftPreferencesReader>`. Two reasons:

- **Decoupling**: household merging needs the data shape, not the preference module's internals. Defining the SPI here lets preference-01c later wire `class PreferenceSoftPreferencesReader implements SoftPreferencesReader` without household-01e or preference-01a/01b changing.
- **Zero pollution of preference**: option (a) — stubbing `preference.QueryService.getSoftPreferencesByUserIds` to return `List.of()` — would commit the preference module to a `SoftPreferenceBundleDto`-shaped contract it hasn't designed yet (the LLD's TasteProfileDocument / LifestyleConfigDocument records aren't pinned in `lld/preference.md` either; they're forward-references from `lld/household.md`). Option (b) keeps that ambiguity in household until preference-01c locks the shape.

01e's `SoftPreferencesReader` interface is intentionally narrow:

```java
public interface SoftPreferencesReader {
  List<SoftPreferenceBundleDto> getSoftPreferencesByUserIds(List<UUID> userIds);
}
```

When preference-01c lands, it implements this in `preference/domain/service/internal/PreferenceSoftPreferencesReader.java` (or wherever it fits the preference package) — household-01e's autowire-by-type lookup picks it up automatically. **`NoopSoftPreferencesReader` is a `@Component` annotated `@ConditionalOnMissingBean(SoftPreferencesReader.class)`** so the real impl wins when present.

**Worth user review**: the SPI lives in household for now. When preference-01c ships, the user/parent may want to relocate it to `core/` (cross-module integration contract) or to preference itself (preference owns soft-prefs). 01e flags this in the service-impl Javadoc and the SPI interface Javadoc; relocation is a single rename + import update.

**LLD divergence note** — **DTO shape stubs**: `TasteProfileDocument`, `LifestyleConfigDocument`, `SoftPreferenceBundleDto` ship as **minimal-shape records in `household/api/dto/`** so the merge interface + REST endpoint validate against a real type rather than `Object`. Shapes:

```java
public record TasteProfileDocument(
    Map<String, BigDecimal> ingredientLikes,   // ingredientMappingKey -> like-score in [-1, 1]
    Map<String, BigDecimal> cuisineLikes,      // cuisine name -> like-score in [-1, 1]
    List<String> avoidList                     // ingredientMappingKey strings (NOT allergens — those are hard-constraints)
) {}

public record LifestyleConfigDocument(
    String mealTimingWindowStart,              // "HH:mm"; nullable
    String mealTimingWindowEnd,                // "HH:mm"; nullable
    Integer noveltyTolerancePercent,           // 0-100; nullable
    boolean batchCookingPreferred              // primitive — defaults false
) {}

public record SoftPreferenceBundleDto(
    UUID userId,
    TasteProfileDocument tasteProfile,         // nullable when user has none
    LifestyleConfigDocument lifestyleConfig    // nullable when user has none
) {}
```

These are **household-owned for now**; when preference-01c lands, the canonical preference-owned records replace them and household imports from preference. The forward-rename is mechanical (move the three records to `preference/api/dto/`, change household's imports). **Worth user review** — if the parent wants preference-01c to keep its own shape entirely separate, household keeps these as merge-time records and preference would map its native shape into them via the SPI's return value. Either way works; the shape stays small enough that the migration cost is bounded.

**LLD divergence note** — **REST exposure**: LLD §REST line 351 explicitly says "`HouseholdMergeService` is **not** exposed via REST — invoked in-process by the planner." **01e ships the REST endpoint anyway** — `POST /api/v1/households/current/merge` taking `MergeSoftPreferencesRequest { @Nullable List<UUID> eaterUserIds /* null = all current members */ }` and returning `MergedSoftPreferencesDto`. Reasons:

- **Reachability before planner exists**: the planner module is not yet built. Without REST exposure, the merge service is unreachable from outside the JVM and untestable in IT against real HTTP.
- **Admin / debugging value**: an in-app admin tool needs to see "what would the planner see for this household?" — the REST endpoint is the natural path.
- **Symmetric with 01d's `/households/{id}/members` divergence**: same pattern — LLD says in-process only, 01d added REST anyway for the same reasons.

**Worth user review**: when planner-01a lands and calls `householdMergeService.mergeSoftPreferencesForSlot(...)` directly, the REST endpoint stays as a debug / admin surface; it could be moved behind an admin role gate then. v1 leaves it open to authenticated callers.

**Defers** (still out of scope after 01e):

- **Real soft-preferences data path** — `preference-01c` (or whichever preference ticket lands soft-prefs) wires `SoftPreferencesReader` with a real implementation. Until then, every merge returns `{ contributingUserIds = [...], mergedTasteProfile = empty, mergedLifestyleConfig = empty defaults, userIdsByPriority = [...] }`.
- `SlotConfigurationDto` planner-friendly view (LLD §211-217) → **household-01f**
- `getSlotConfiguration` query method on `HouseholdQueryService` (declared in LLD line 281 but not in 01a/01b/01c/01d) → **household-01f**
- Multi-household-per-user (LLD §Out of Scope)
- Numerical weighting tuning beyond the v1 `MEAN_WEIGHTED_BY_PRIORITY` strategy (LLD line 204)

01e unblocks the **planner module's slot-composition flow**. Without a callable `HouseholdMergeService`, the planner has no way to ask "what soft preferences should I use for this shared slot?" — even if every call today returns empty, the call site is wired and the planner can be built against the contract without waiting on preference-01c.

## Behavioural spec

### `HouseholdMergeService` interface — public

1. New public interface `com.example.mealprep.household.domain.service.HouseholdMergeService`. **Verbatim from [LLD lines 314-323](../../lld/household.md)**:
   ```java
   public interface HouseholdMergeService {
     MergedSoftPreferencesDto mergeSoftPreferencesForSlot(UUID householdId, List<UUID> eaterUserIds);
     MergedSoftPreferencesDto mergeSoftPreferencesForUsers(List<UUID> userIds, List<Integer> priorities);
   }
   ```
2. **Implemented by `HouseholdServiceImpl`** (existing class from 01a/01b/01c/01d). The new methods join the existing impl class per LLD line 262 ("all three module interfaces are implemented by a single `HouseholdServiceImpl`"). **Worth user review** — alternative is a separate `HouseholdMergeServiceImpl` for narrower coupling; rejected because LLD line 262 explicitly mandates single-impl, and splitting now would need to be undone later. The merge code is ~50 lines.

### `mergeSoftPreferencesForSlot` flow

3. **Read-only**: `@Transactional(readOnly = true)`.
4. **Input handling** (LLD line 318): if `eaterUserIds == null || eaterUserIds.isEmpty()` → resolve to all current household members via existing `householdMemberRepository.findAllByHouseholdId(householdId)`.
5. **404 ladder**: `householdRepository.findById(householdId)` missing → 404 `HouseholdNotFoundException`. Members list empty (no current members) → 422 `EmptyHouseholdMergeException` (NEW exception — see Errors below). The empty-household case is reachable: 01d's `removeMember` invariant 19 permits "household with zero members preserved."
6. **Bundle fetch** (LLD line 440): `softPreferencesReader.getSoftPreferencesByUserIds(resolvedEaterUserIds)` — one round-trip returns each user's `SoftPreferenceBundleDto`. With `NoopSoftPreferencesReader` wired, returns `List.of()`. With a real reader (preference-01c), returns one bundle per resolved user (missing users → bundle with `tasteProfile = null, lifestyleConfig = null` per the reader's contract; the merger handles null).
7. **Priorities lookup**: read each resolved user's `priority` from `householdMemberRepository.findAllByHouseholdId(householdId)` (one query — same trip as step 4). Build `List<Integer> priorities` in the same order as `resolvedEaterUserIds`. Missing-from-household defaults to `100` (the same default 01d locks for `addMember`).
8. **Delegate to merger**: `softPreferenceMerger.merge(bundles, priorities, householdId, resolvedEaterUserIds)` returns `MergedSoftPreferencesDto`.
9. **Result**: `mergedAt = Instant.now()`. `strategy = MergeStrategy.MEAN_WEIGHTED_BY_PRIORITY` (only v1 value). `contributingUserIds = resolvedEaterUserIds` (order preserved). `userIdsByPriority` = same userIds sorted DESC on their priority, ties broken on `userId` (UUID natural order) for determinism.
10. **No persistence** (LLD line 326 — "Read-only — produces a transient document the planner consumes during composition; not persisted"). No events published.

### `mergeSoftPreferencesForUsers` flow (variant)

11. **Read-only**: `@Transactional(readOnly = true)`. Bypasses household lookup (LLD line 321 — "used during feasibility checks and by tests").
12. **Validation**: `userIds.size() == priorities.size()` else 400 (via `@Valid` on a wrapping record at the controller; service throws `IllegalArgumentException` if invoked directly with mismatched lengths, mapped to 400).
13. Same bundle fetch + merger delegate as above. `householdId = null` in the output (the variant doesn't know which household; planner uses this for "user X + user Y, what's their combined taste?").
14. **Worth user review**: the LLD's `mergeSoftPreferencesForUsers` returns `MergedSoftPreferencesDto` whose `householdId` field is required — `null` violates the record's non-null-by-default semantics. 01e ships `householdId` as a `@Nullable UUID` on the record (changes `MergedSoftPreferencesDto.householdId` from "required" to "nullable"); the LLD line 198 declares it `UUID` without explicit nullability. **Document the LLD divergence** at the record's Javadoc.

### `SoftPreferenceMerger` — internal `@Component`

15. `com.example.mealprep.household.domain.service.internal.SoftPreferenceMerger`. Single public method:
    ```java
    MergedSoftPreferencesDto merge(List<SoftPreferenceBundleDto> bundles, List<Integer> priorities,
                                   UUID householdId, List<UUID> contributingUserIds);
    ```
16. **Empty-input branch**: `bundles.isEmpty()` OR all bundles' `tasteProfile == null && lifestyleConfig == null` → return `MergedSoftPreferencesDto(householdId, contributingUserIds, emptyTasteProfile(), emptyLifestyleConfig(), sortedByPriority(contributingUserIds, priorities), MergeStrategy.MEAN_WEIGHTED_BY_PRIORITY, Instant.now())`. Where `emptyTasteProfile()` returns `new TasteProfileDocument(Map.of(), Map.of(), List.of())` and `emptyLifestyleConfig()` returns `new LifestyleConfigDocument(null, null, null, false)`.
17. **Taste-profile merge** (LLD line 441 — "mean of taste-profile vectors weighted by per-person priority"):
    - **`ingredientLikes`** (Map<String, BigDecimal>): for each key present in any bundle, compute `sum(bundle.tasteProfile.ingredientLikes.get(key) * priority) / sum(priorityForBundlesContainingKey)`. Missing-from-a-user counts as zero contribution to the sum AND zero contribution to the denominator weight (so absent-user doesn't pull the mean toward zero — only present users contribute).
    - **`cuisineLikes`** (Map<String, BigDecimal>): same formula as `ingredientLikes`.
    - **`avoidList`** (List<String>): set-union across all bundles' avoidLists. Deduplicated; sorted alphabetically for deterministic output.
    - **Null bundles**: `bundle.tasteProfile == null` is skipped entirely (treated as "user has no taste profile yet"). If ALL bundles have `tasteProfile == null` → return `emptyTasteProfile()`.
18. **Lifestyle merge** (LLD line 441 — "most-restrictive"):
    - **`mealTimingWindow`**: intersection of windows across bundles. If any bundle declares both `windowStart` and `windowEnd`, the merged window is `(max of all starts, min of all ends)`. If the intersection is empty (max > min) → return the latest start + earliest end **and add `avoidList` warning entry "WINDOW_INTERSECTION_EMPTY"** (LLD line 207 "free-text notes dropped"; 01e uses the avoid-list as the warning surface since it's the only list field on the merged DTO). **Worth user review** — the LLD doesn't specify the empty-intersection behaviour; 01e picks "warn don't reject" because rejecting at merge time would block plans for irreconcilable schedules.
    - **`noveltyTolerancePercent`**: minimum across all non-null values. All null → null.
    - **`batchCookingPreferred`**: AND across all bundles (`true` only if every user prefers batch cooking).
    - **Null bundles**: `bundle.lifestyleConfig == null` is skipped.
19. **`userIdsByPriority`**: list of `contributingUserIds` sorted by their corresponding `priorities` DESC; ties broken on `userId` natural ordering (UUID `compareTo`). Deterministic across runs.
20. **`schemaVersion` field**: NOT shipped in 01e — LLD line 197 doesn't declare one. Per `lld/style-guide.md` §JSONB "schema_version field at the document root" — but `MergedSoftPreferencesDto` is **not** persisted (LLD line 326 read-only), so the rule doesn't apply. Document on the record's Javadoc.

### `SoftPreferencesReader` SPI

21. New public interface `com.example.mealprep.household.spi.SoftPreferencesReader`:
    ```java
    public interface SoftPreferencesReader {
      List<SoftPreferenceBundleDto> getSoftPreferencesByUserIds(List<UUID> userIds);
    }
    ```
    Lives in a NEW `household/spi/` subpackage (not in `domain/service/` because it's a cross-module integration contract; not in `api/` because no HTTP exposure). **Worth user review** — alternative is `domain/service/spi/`; 01e picks the top-level `spi/` to make it grep-able as "this is the cross-module integration contract."
22. **Wire-up**: `HouseholdServiceImpl` constructor takes `Optional<SoftPreferencesReader>`. Resolved at runtime — Spring injects the real impl if any exists, else the `NoopSoftPreferencesReader`. **Per Spring 6 idiom, prefer `@Autowired(required = false)` on the field** OR use `ObjectProvider<SoftPreferencesReader>` for lazy lookup. **01e uses `ObjectProvider`** because constructor-injection of optional dependencies is cleaner than field injection. Document the choice in the impl's Javadoc.
23. `NoopSoftPreferencesReader` (`@Component`, `@ConditionalOnMissingBean(SoftPreferencesReader.class)`) returns `List.of()` for any input. Logs at DEBUG: `"NoopSoftPreferencesReader returning empty bundles for {} userIds — preference-01c not yet wired"`. Lives in `household/spi/internal/` (package-private impl — never directly injected).
24. **Note**: `@ConditionalOnMissingBean` is a Spring Boot autoconfig annotation; works on `@Component` classes too via the standard Spring Boot conditional infrastructure. Verify the autoconfig fires correctly under `@SpringBootTest` (it does — confirmed by existing `@ConditionalOnMissingBean` usage in Spring Boot Boot 3.2). If issues arise, fall back to a `@Configuration` class with `@Bean @ConditionalOnMissingBean` — same effect, more verbose. **Document either way.**

### `POST /api/v1/households/current/merge`

25. Authenticated. Server resolves `actorUserId` via `CurrentUserResolver`. Caller's household resolved via `householdMemberRepository.findByUserId(actorUserId)` → 404 `HouseholdNotFoundException` if not in a household.
26. Body: `MergeSoftPreferencesRequest { List<UUID> eaterUserIds /* nullable; null = all current members */ }`. Validation: each UUID `@NotNull` (rejects null list elements).
27. **Authorisation**: any authenticated household member can call (read-only operation; no role gate). Caller's `eaterUserIds` MUST all belong to the caller's household — if any UUID is not a member → 403 `InsufficientHouseholdRoleException` (re-used; "not authorised to merge for outside-household users"). Computed via existing `findAllByHouseholdId` set membership check.
28. Invokes `householdMergeService.mergeSoftPreferencesForSlot(callerHouseholdId, request.eaterUserIds())`. Returns 200 with `MergedSoftPreferencesDto`.

### Service interfaces — append-only

29. **No new methods on `HouseholdQueryService` / `HouseholdUpdateService`**. `HouseholdMergeService` is a **third interface** on `HouseholdServiceImpl` (per LLD line 262 / line 309 — narrower API, narrower coupling).
30. Append to the existing `HouseholdModule.java` facade (from 01a) so cross-module callers can `@Autowired HouseholdModule.MergeService`:
    ```java
    public class HouseholdModule {
      // existing: QueryService, UpdateService
      public static class MergeService { /* re-export HouseholdMergeService */ }
    }
    ```
    **Verify 01a's actual `HouseholdModule.java` shape**; if it doesn't follow the nested-class re-export pattern, just add `public HouseholdMergeService mergeService()` as a getter or skip the facade update (the interface is `public` and `@Autowired` works directly). **The facade update is optional** — flag in the report.

### Errors

31. New module exception subclass extending the existing `HouseholdException` from 01a:
    - `EmptyHouseholdMergeException` (422, `type = .../empty-household-merge`) — thrown when the household has zero members and merge is invoked. **NEW** — not named in the LLD; 01e introduces the exception to surface the case cleanly. **Worth user review**: alternative is returning a `MergedSoftPreferencesDto` with empty `contributingUserIds` and no error — that's symmetric with the empty-bundles path, but 01e prefers 422 because "merge for an empty household" almost certainly indicates a caller bug.
32. **Append one new `@ExceptionHandler` method** to the existing `HouseholdExceptionHandler` `@RestControllerAdvice` from 01a/01b/01c/01d (already `@Order(Ordered.HIGHEST_PRECEDENCE)`). Do **NOT** create a second handler class. Do **NOT** modify `config/GlobalExceptionHandler.java`.

## Database

**Zero migrations.** 01e is a pure code-path change. The merge is read-only and not persisted (LLD line 326).

## OpenAPI updates

### Append to `src/main/resources/openapi/paths/household.yaml`

(File created by 01a, extended by 01b/01c/01d — append one new path-item below 01d's member-admin blocks. Do NOT touch existing path-items.)

```yaml
householdMerge:
  post:
    tags: [Households]
    operationId: mergeSoftPreferencesForCurrentHousehold
    summary: 'Merge the soft-preferences of the calling user''s household members (or a subset). Read-only; not persisted.'
    security: [{ cookieAuth: [] }]
    requestBody:
      required: true
      content:
        application/json:
          schema: { $ref: '../schemas/household.yaml#/MergeSoftPreferencesRequest' }
    responses:
      '200':
        description: Merged soft preferences for the requested eater set.
        content:
          application/json:
            schema: { $ref: '../schemas/household.yaml#/MergedSoftPreferencesDto' }
      '400': { description: 'Validation error', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '401': { description: 'Unauthenticated', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '403': { description: 'eaterUserIds contains a user not in the caller''s household', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '404': { description: 'Caller not in any household', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '422': { description: 'Merge requested on an empty household (zero members)', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
```

### Append to `src/main/resources/openapi/schemas/household.yaml`

(Append five new schemas. Do NOT touch existing 01a/01b/01c/01d schemas.)

```yaml
MergeStrategy:
  type: string
  enum: [MEAN_WEIGHTED_BY_PRIORITY]
TasteProfileDocument:
  type: object
  description: 'Lightweight stub shape; preference-01c will own the canonical record.'
  properties:
    ingredientLikes:
      type: object
      additionalProperties: { type: number, format: double, minimum: -1, maximum: 1 }
    cuisineLikes:
      type: object
      additionalProperties: { type: number, format: double, minimum: -1, maximum: 1 }
    avoidList:
      type: array
      items: { type: string, maxLength: 160 }
LifestyleConfigDocument:
  type: object
  description: 'Lightweight stub shape; preference-01c will own the canonical record.'
  properties:
    mealTimingWindowStart:
      type: string
      pattern: '^[0-2][0-9]:[0-5][0-9]$'
      nullable: true
    mealTimingWindowEnd:
      type: string
      pattern: '^[0-2][0-9]:[0-5][0-9]$'
      nullable: true
    noveltyTolerancePercent:
      type: integer
      minimum: 0
      maximum: 100
      nullable: true
    batchCookingPreferred: { type: boolean, default: false }
SoftPreferenceBundleDto:
  type: object
  required: [userId]
  properties:
    userId: { type: string, format: uuid }
    tasteProfile:
      type: object
      nullable: true
      properties:
        ingredientLikes:
          type: object
          additionalProperties: { type: number, format: double, minimum: -1, maximum: 1 }
        cuisineLikes:
          type: object
          additionalProperties: { type: number, format: double, minimum: -1, maximum: 1 }
        avoidList:
          type: array
          items: { type: string, maxLength: 160 }
    lifestyleConfig:
      type: object
      nullable: true
      properties:
        mealTimingWindowStart:
          type: string
          pattern: '^[0-2][0-9]:[0-5][0-9]$'
          nullable: true
        mealTimingWindowEnd:
          type: string
          pattern: '^[0-2][0-9]:[0-5][0-9]$'
          nullable: true
        noveltyTolerancePercent:
          type: integer
          minimum: 0
          maximum: 100
          nullable: true
        batchCookingPreferred: { type: boolean, default: false }
MergedSoftPreferencesDto:
  type: object
  required: [contributingUserIds, mergedTasteProfile, mergedLifestyleConfig, userIdsByPriority, strategy, mergedAt]
  properties:
    householdId:
      type: string
      format: uuid
      nullable: true
      description: 'Null for the mergeSoftPreferencesForUsers variant which bypasses household lookup.'
    contributingUserIds:
      type: array
      items: { type: string, format: uuid }
    mergedTasteProfile: { $ref: '#/TasteProfileDocument' }
    mergedLifestyleConfig: { $ref: '#/LifestyleConfigDocument' }
    userIdsByPriority:
      type: array
      items: { type: string, format: uuid }
    strategy: { $ref: '#/MergeStrategy' }
    mergedAt: { type: string, format: date-time }
MergeSoftPreferencesRequest:
  type: object
  properties:
    eaterUserIds:
      type: array
      nullable: true
      description: 'Null or empty = all current household members.'
      items: { type: string, format: uuid }
```

**Gotcha applied**: `householdId` on `MergedSoftPreferencesDto`, `mealTimingWindowStart`/`End` / `noveltyTolerancePercent` on `LifestyleConfigDocument`, `tasteProfile` / `lifestyleConfig` on `SoftPreferenceBundleDto`, `eaterUserIds` on the request use **inline** `nullable: true` (NOT `$ref + nullable: true`). `tasteProfile` and `lifestyleConfig` inline the full object shape rather than `$ref`-ing because `$ref + nullable` is silently dropped.

**Gotcha applied**: every YAML description containing a comma, colon, or apostrophe is **single-quoted** per the round-4 lesson.

### Append to entry `src/main/resources/openapi/openapi.yaml`

**Location**: under the existing `# household` block in `paths:`. Append one new path-item ref:

```yaml
  /api/v1/households/current/merge:
    $ref: 'paths/household.yaml#/householdMerge'
```

**Location**: under `components.schemas:`, append five new schema refs in the existing `# household` block (alphabetical):

```yaml
    LifestyleConfigDocument: { $ref: 'schemas/household.yaml#/LifestyleConfigDocument' }
    MergeSoftPreferencesRequest: { $ref: 'schemas/household.yaml#/MergeSoftPreferencesRequest' }
    MergeStrategy: { $ref: 'schemas/household.yaml#/MergeStrategy' }
    MergedSoftPreferencesDto: { $ref: 'schemas/household.yaml#/MergedSoftPreferencesDto' }
    SoftPreferenceBundleDto: { $ref: 'schemas/household.yaml#/SoftPreferenceBundleDto' }
    TasteProfileDocument: { $ref: 'schemas/household.yaml#/TasteProfileDocument' }
```

## Verbatim shape snippets

### Service interface

```java
package com.example.mealprep.household.domain.service;

public interface HouseholdMergeService {
  MergedSoftPreferencesDto mergeSoftPreferencesForSlot(UUID householdId, List<UUID> eaterUserIds);
  MergedSoftPreferencesDto mergeSoftPreferencesForUsers(List<UUID> userIds, List<Integer> priorities);
}
```

### SPI interface

```java
package com.example.mealprep.household.spi;

public interface SoftPreferencesReader {
  /**
   * Return one {@link SoftPreferenceBundleDto} per input user id, in input order.
   * Implementations MAY return null-fielded bundles for users with no soft preferences yet;
   * MUST NOT return fewer bundles than userIds (callers index by position).
   * <p>Until preference-01c lands a real implementation, {@code NoopSoftPreferencesReader}
   * returns {@code List.of()} — the merger handles that branch cleanly.
   */
  List<SoftPreferenceBundleDto> getSoftPreferencesByUserIds(List<UUID> userIds);
}
```

### Noop reader

```java
package com.example.mealprep.household.spi.internal;

@Component
@ConditionalOnMissingBean(SoftPreferencesReader.class)
class NoopSoftPreferencesReader implements SoftPreferencesReader {
  private static final Logger log = LoggerFactory.getLogger(NoopSoftPreferencesReader.class);
  @Override public List<SoftPreferenceBundleDto> getSoftPreferencesByUserIds(List<UUID> userIds) {
    log.debug("NoopSoftPreferencesReader returning empty bundles for {} userIds — preference-01c not yet wired", userIds.size());
    return List.of();
  }
}
```

### Service-impl — `mergeSoftPreferencesForSlot` skeleton

```java
@Transactional(readOnly = true)
public MergedSoftPreferencesDto mergeSoftPreferencesForSlot(UUID householdId, List<UUID> eaterUserIds) {
  householdRepository.findById(householdId).orElseThrow(HouseholdNotFoundException::new);
  List<HouseholdMember> members = householdMemberRepository.findAllByHouseholdId(householdId);
  if (members.isEmpty()) {
    throw new EmptyHouseholdMergeException(householdId);
  }
  List<UUID> resolved = (eaterUserIds == null || eaterUserIds.isEmpty())
      ? members.stream().map(HouseholdMember::getUserId).toList()
      : eaterUserIds;
  Map<UUID, Integer> priorityByUser = members.stream()
      .collect(Collectors.toMap(HouseholdMember::getUserId, HouseholdMember::getPriority));
  List<Integer> priorities = resolved.stream()
      .map(u -> priorityByUser.getOrDefault(u, 100))
      .toList();
  List<SoftPreferenceBundleDto> bundles = readerProvider.getIfAvailable(NoopSoftPreferencesReader::new)
      .getSoftPreferencesByUserIds(resolved);
  return softPreferenceMerger.merge(bundles, priorities, householdId, resolved);
}
```

### `SoftPreferenceMerger` empty-input branch

```java
public MergedSoftPreferencesDto merge(List<SoftPreferenceBundleDto> bundles,
                                       List<Integer> priorities,
                                       UUID householdId,
                                       List<UUID> contributingUserIds) {
  boolean allEmpty = bundles.isEmpty()
      || bundles.stream().allMatch(b -> b.tasteProfile() == null && b.lifestyleConfig() == null);
  TasteProfileDocument mergedTaste = allEmpty
      ? new TasteProfileDocument(Map.of(), Map.of(), List.of())
      : mergeTaste(bundles, priorities);
  LifestyleConfigDocument mergedLifestyle = allEmpty
      ? new LifestyleConfigDocument(null, null, null, false)
      : mergeLifestyle(bundles);
  return new MergedSoftPreferencesDto(
      householdId, contributingUserIds, mergedTaste, mergedLifestyle,
      sortByPriorityDesc(contributingUserIds, priorities),
      MergeStrategy.MEAN_WEIGHTED_BY_PRIORITY,
      Instant.now());
}
```

### Exception

```java
public class EmptyHouseholdMergeException extends HouseholdException {
  private static final URI TYPE = URI.create("https://mealprep.example.com/problems/empty-household-merge");

  public EmptyHouseholdMergeException(UUID householdId) {
    super("Cannot merge soft preferences — household " + householdId + " has zero members");
  }

  @Override public URI getType() { return TYPE; }
  @Override public HttpStatus getStatus() { return HttpStatus.UNPROCESSABLE_ENTITY; }
}
```

## Edge-case checklist

- [ ] `POST /current/merge` by PRIMARY with `eaterUserIds = null` → 200; `contributingUserIds` lists all members; merged DTO has empty taste / lifestyle (NoopReader)
- [ ] `POST /current/merge` by MEMBER → 200 (no role gate; read-only)
- [ ] `POST /current/merge` by anonymous → 401
- [ ] `POST /current/merge` by user-not-in-any-household → 404
- [ ] `POST /current/merge` with `eaterUserIds = [outsideUserId]` → 403 `insufficient-household-role`
- [ ] `POST /current/merge` with `eaterUserIds = []` → treated as null per LLD line 318 → 200 with all-members merge
- [ ] `POST /current/merge` validation: `eaterUserIds` containing null element → 400
- [ ] Empty household (zero members) — directly call `mergeSoftPreferencesForSlot` (IT helper inserts a household with zero members, e.g. after 01d's `removeMember` only-member path) → 422 `empty-household-merge`
- [ ] `mergeSoftPreferencesForUsers([u1, u2], [10, 20])` with `NoopSoftPreferencesReader` → returns empty merged DTO, `householdId = null`, `contributingUserIds = [u1, u2]`, `userIdsByPriority = [u2, u1]` (priority 20 > 10)
- [ ] `mergeSoftPreferencesForUsers` mismatched lengths → 400 (controller `@Valid`) / IllegalArgumentException (service)
- [ ] `userIdsByPriority` deterministic — same input twice → same output (UUID tie-break)
- [ ] `SoftPreferenceMerger.merge`: all-null bundles → `emptyTasteProfile()` + `emptyLifestyleConfig()`; `strategy = MEAN_WEIGHTED_BY_PRIORITY`; `mergedAt` non-null
- [ ] `SoftPreferenceMerger.merge`: 2 bundles with `ingredientLikes = {"onion": 0.5}, {"onion": -0.3}` and priorities `[100, 200]` → merged `ingredientLikes.onion = (0.5*100 + -0.3*200) / 300 = -0.033`
- [ ] `SoftPreferenceMerger.merge`: 2 bundles, only one has `ingredientLikes.onion = 0.5` → merged `ingredientLikes.onion = 0.5` (absent user doesn't pull mean toward zero)
- [ ] `SoftPreferenceMerger.merge`: avoid-list union — bundle1 `["onion"]`, bundle2 `["garlic", "onion"]` → merged `["garlic", "onion"]` (sorted)
- [ ] `SoftPreferenceMerger.merge`: lifestyle windows `(08:00, 20:00)` and `(09:30, 19:00)` → merged `(09:30, 19:00)`
- [ ] `SoftPreferenceMerger.merge`: lifestyle windows `(08:00, 12:00)` and `(13:00, 17:00)` → empty intersection → merged `(13:00, 12:00)` with `WINDOW_INTERSECTION_EMPTY` appended to `avoidList`
- [ ] `SoftPreferenceMerger.merge`: `noveltyTolerancePercent = [50, 30, null]` → merged `30` (min of non-null)
- [ ] `SoftPreferenceMerger.merge`: `batchCookingPreferred = [true, true, false]` → merged `false` (AND)
- [ ] `NoopSoftPreferencesReader` autowired when no other `SoftPreferencesReader` bean exists — verified via `@SpringBootTest` context dump
- [ ] Test-scoped `@TestConfiguration` providing a fake `SoftPreferencesReader @Bean` wins over the Noop — verified by an IT that injects a fake returning `[bundle with onion-like = 0.7]`, then asserts the merge output reflects it
- [ ] OpenAPI request/response shapes match (swagger-request-validator filter active in IT)
- [ ] `HouseholdBoundaryTest` (from 01a) still passes — new packages (`spi/`, `spi/internal/`) added; the existing rule allows them per the per-module convention. **Verify the existing rule** doesn't restrict to known subpackages; if it does, append `spi` and `spi.internal` to the allow-list.
- [ ] `HouseholdExceptionHandler` retains `@Order(Ordered.HIGHEST_PRECEDENCE)` after appending the new handler method
- [ ] `MergedSoftPreferencesDto.mergedAt` reflects merge time (within 1s of `Instant.now()` in the IT)
- [ ] No event published on merge (read-only; no `MergedEvent` in LLD)
- [ ] No N+1 — merge issues at most: 1 SELECT for household, 1 SELECT for members, 1 reader call (which may issue its own queries — out of household's concern); Hibernate stats verify ≤ 2 SQL statements from household's side
- [ ] Cross-module facade — append `HouseholdMergeService` bean wiring via the existing `HouseholdModule` if it follows the re-export pattern; else `@Autowired HouseholdMergeService` works directly
- [ ] No regression on existing tests, including 01d's `HouseholdMembersFlowIT`, 01c's `HouseholdInvitesFlowIT`

## Files this ticket touches

```
NEW   src/main/java/com/example/mealprep/household/api/controller/HouseholdMergeController.java
NEW   src/main/java/com/example/mealprep/household/api/dto/MergeSoftPreferencesRequest.java
NEW   src/main/java/com/example/mealprep/household/api/dto/MergedSoftPreferencesDto.java
NEW   src/main/java/com/example/mealprep/household/api/dto/MergeStrategy.java
NEW   src/main/java/com/example/mealprep/household/api/dto/SoftPreferenceBundleDto.java
NEW   src/main/java/com/example/mealprep/household/api/dto/TasteProfileDocument.java
NEW   src/main/java/com/example/mealprep/household/api/dto/LifestyleConfigDocument.java
NEW   src/main/java/com/example/mealprep/household/domain/service/HouseholdMergeService.java
NEW   src/main/java/com/example/mealprep/household/domain/service/internal/SoftPreferenceMerger.java
NEW   src/main/java/com/example/mealprep/household/spi/SoftPreferencesReader.java
NEW   src/main/java/com/example/mealprep/household/spi/internal/NoopSoftPreferencesReader.java
NEW   src/main/java/com/example/mealprep/household/exception/EmptyHouseholdMergeException.java

MOD   src/main/java/com/example/mealprep/household/domain/service/internal/HouseholdServiceImpl.java    (implements HouseholdMergeService; constructor takes ObjectProvider<SoftPreferencesReader> + SoftPreferenceMerger)
MOD   src/main/java/com/example/mealprep/household/api/HouseholdExceptionHandler.java                    (append 1 @ExceptionHandler method; KEEP @Order(Ordered.HIGHEST_PRECEDENCE))
MOD   src/main/java/com/example/mealprep/household/HouseholdModule.java                                  (optional — re-export MergeService if 01a follows the nested-class facade pattern; skip otherwise)

MOD   src/main/resources/openapi/paths/household.yaml      (append 1 new path-item below 01d's; do NOT touch existing)
MOD   src/main/resources/openapi/schemas/household.yaml    (append 6 new schemas: MergeStrategy, TasteProfileDocument, LifestyleConfigDocument, SoftPreferenceBundleDto, MergedSoftPreferencesDto, MergeSoftPreferencesRequest)
MOD   src/main/resources/openapi/openapi.yaml              (1 line under paths: in the `# household` block; 6 lines under components.schemas: in the `# household` block)

NEW   src/test/java/com/example/mealprep/household/HouseholdMergeServiceTest.java                       (mocked reader + merger; happy path, empty household, mismatched-length variant)
NEW   src/test/java/com/example/mealprep/household/SoftPreferenceMergerTest.java                       (LLD §SoftPreferenceMergerTest line 469: equal-priority, differing-priority, avoid-list union, single-user degenerate, most-restrictive lifestyle, empty-intersection-window warning)
NEW   src/test/java/com/example/mealprep/household/HouseholdMergeFlowIT.java                            (full HTTP: NoopReader path; @TestConfiguration with a fake reader producing non-empty merge)
MOD   src/test/java/com/example/mealprep/household/testdata/HouseholdTestData.java                      (append merge-request builders + bundle / merged DTO builders)
```

**Files this ticket does NOT modify** (cross-cutting; sibling round-5 tickets running in parallel must not collide):

- `src/main/java/com/example/mealprep/config/GlobalExceptionHandler.java` — module exception goes in the existing `HouseholdExceptionHandler`.
- `src/test/java/com/example/mealprep/archunit/ModuleBoundaryTest.java` — module boundary rule lives in the existing `HouseholdBoundaryTest`.
- Other modules' `paths/*.yaml`, `schemas/*.yaml`, `<module>ExceptionHandler.java`, `<module>BoundaryTest.java`, migrations, entities — none touched.
- The preference module (any file) — **explicitly not modified**. The SPI lives in household; preference-01c will implement against it later.
- 01a/01b/01c/01d's existing tests — none modified; only `HouseholdTestData.java` gets new builder methods.
- `HouseholdBoundaryTest` — verify it permits new sub-packages `spi/` and `spi/internal/`; if it whitelists only `api/`, `domain/`, `event/`, `exception/`, `validation/`, `config/`, then **append the two new sub-packages to the rule** (this is the ONE shared file the agent may touch on the household side). **Document the choice in the report.**

## Dependencies

- **Hard dependency**: `household-01a` (merged) — `Household`, `HouseholdMember`, `HouseholdRepository`, `HouseholdMemberRepository`, `HouseholdQueryService`, `HouseholdUpdateService`, `HouseholdExceptionHandler`, `HouseholdBoundaryTest`, `HouseholdException`, `HouseholdNotFoundException`.
- **Hard dependency**: `household-01b` (merged) — `InsufficientHouseholdRoleException`; the `@ExceptionHandler` ordering pattern.
- **Hard dependency**: `household-01c` (merged) — invite flow not directly used by merge; pattern reuse only.
- **Hard dependency**: `household-01d` (merged) — `HouseholdMember.priority` integer column populated (default 100), `findAllByHouseholdId` returning members for the priority lookup, `LastPrimaryRemovalException` etc unchanged.
- **Hard dependency**: `auth-01a` (merged) — `CurrentUserResolver`, `SessionAuthenticationFilter`.
- **Hard dependency**: `refactor-01-split-merge-zones` (merged) — per-module YAML / advice / boundary-test layout.
- **No hard dependency on preference module** — 01e ships independently of preference-01c. The SPI's `NoopSoftPreferencesReader` keeps the wire green.
- **Sibling tickets running in parallel** (Wave 2 round 5): `nutrition-01e`, `provisions-01e`, `recipe-01e`. None should touch any household file or any of the cross-cutting files listed above. Only collision point is the entry `openapi.yaml`; this ticket appends in the `# household` block, sibling tickets append in their own module's block.

## Acceptance / DoD

- [ ] `./mvnw -Dspotless.check.skip=true verify` passes locally on the agent's worktree
- [ ] `./mvnw spotless:apply` then `./mvnw spotless:check` clean
- [ ] CI green on the PR (build + spotless + OpenAPI lint + ArchUnit gate)
- [ ] All edge-case items above ticked
- [ ] `HouseholdExceptionHandler` retains `@Order(Ordered.HIGHEST_PRECEDENCE)` after appending the new method
- [ ] OpenAPI 3.0 nullable fields use **inline** `nullable: true` (NOT `$ref + nullable: true`) on `householdId`, `mealTimingWindowStart`/`End`, `noveltyTolerancePercent`, `eaterUserIds`, `tasteProfile`, `lifestyleConfig`
- [ ] All YAML description strings containing `,` `:` `'` are single-quoted (round-4 lesson)
- [ ] `NoopSoftPreferencesReader` autowires when no other bean exists; a test-scoped `@TestConfiguration` reader wins (verified by IT)
- [ ] No regression on existing tests, including 01d's `HouseholdMembersFlowIT`, 01c's `HouseholdInvitesFlowIT`, 01b's `HouseholdSettingsFlowIT`, 01a's `HouseholdsFlowIT`
- [ ] `SoftPreferenceMerger` is deterministic — same input twice → byte-identical output (modulo `mergedAt`)
- [ ] No N+1 — household + members load = 2 queries; merger does no DB work
- [ ] No pom.xml dependency adds
- [ ] No preference module file touched

## What's NOT in scope

- **Real soft-preferences implementation** — `preference-01c` lands the `PreferenceSoftPreferencesReader implements SoftPreferencesReader` bean. Until then, every merge returns empty.
- `SlotConfigurationDto` planner-friendly view → **household-01f**
- `getSlotConfiguration` query method → **household-01f**
- Numerical weighting tuning beyond v1 `MEAN_WEIGHTED_BY_PRIORITY` (LLD line 204 — "Numerical weighting evolves (Out of Scope)")
- Persistence of merge results (LLD line 326 — read-only / transient)
- `MergedSoftPreferencesEvent` — none in the LLD; merge is silent
- Admin role gate on the REST endpoint — open to any authenticated household member in v1
- Relocation of the `SoftPreferencesReader` SPI to `core/` or `preference/` — flagged as worth user review; v1 keeps it in household
- Schema-version on the merged DTO — not persisted, rule doesn't apply (per style-guide §JSONB which conditions schema-version on persistence)

Squash-merge with: `feat(household): 01e — HouseholdMergeService skeleton + SoftPreferencesReader SPI + Noop fallback + REST endpoint`
