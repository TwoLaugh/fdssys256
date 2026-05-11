# Ticket: household — 01f SlotConfigurationDto Planner-Friendly View + `getSlotConfiguration` Query

## Summary

Layer the **planner-friendly slot-configuration view** on top of the 01a/01b/01c/01d/01e household aggregate per [LLD §`SlotConfigurationDto` lines 209-217](../../lld/household.md), [LLD §`HouseholdQueryService.getSlotConfiguration` line 281](../../lld/household.md), [LLD §REST line 346 (`GET /households/{id}/slot-configuration`)](../../lld/household.md). The slot-configuration resolver already exists from **01b** (`SlotConfigurationResolver` in `domain/service/internal/` — built-in defaults + custom slots + per-slot headcount overrides, returns `SlotConfigEntryDto[]` via the existing 01b `/api/v1/households/{id}/slot-configuration` endpoint). 01f layers a **second, planner-shaped DTO** with a different field layout (flattened slot entries + headcount per slot + cuisine preference + meal-timing windows from the household settings doc + the `MergedSoftPreferencesDto`'s `userIdsByPriority` so the planner gets the full per-slot eater + priority context in one call) plus the new endpoint **`GET /api/v1/households/current/slot-configuration/planner-view`** (current-user resolution via `CurrentUserResolver`, same pattern as 01e's `/api/v1/households/current/merge`).

**Speculatively shipped for the future planner module.** The planner LLD calls for one round-trip "given my householdId, give me everything I need to compose slots" — 01f is that one round-trip on the household side. The 01b endpoint stays as the canonical resource-shaped view (used by the household-settings UI); 01f's `planner-view` is the planner's contract.

**LLD divergence note** — **DTO shape extension**: LLD lines 211-216 declare `SlotConfigurationDto(householdId, slots, allEaterUserIds)` where `slots = List<SlotConfigEntryDto>(slotKey, kind, shared, headcount, timeBudgetMin, eaterUserIdsIfPerPerson)`. **01f keeps this base shape verbatim** and adds a **wrapping** `SlotConfigurationPlannerViewDto`:

```java
public record SlotConfigurationPlannerViewDto(
    UUID householdId,
    List<PlannerSlotEntryDto> slots,                 // flattened — see note below
    List<UUID> allEaterUserIds,                      // matches LLD's allEaterUserIds
    List<UUID> eaterUserIdsByPriority,               // descending priority; UUID tie-break — matches MergedSoftPreferencesDto.userIdsByPriority shape
    String mealTimingWindowStart,                    // "HH:mm"; nullable — copied from HouseholdSettings.scheduling
    String mealTimingWindowEnd,                      // "HH:mm"; nullable
    Instant generatedAt
) {}

public record PlannerSlotEntryDto(
    String slotKey,                                  // "BREAKFAST" / "LUNCH" / "DINNER" / "SNACK" / custom key
    SlotKind kind,                                   // resolved kind (custom slots resolve to backedByKind)
    boolean shared,
    int headcount,
    int timeBudgetMin,
    List<UUID> eaterUserIdsIfPerPerson,              // null when shared = true
    Integer cuisinePreferenceWeight                  // nullable; LLD doesn't pin this — see "cuisine preference" divergence below
) {}
```

The **flattened** shape (`PlannerSlotEntryDto` rather than the nested `SlotConfigurationDto.SlotConfigEntryDto`) keeps the planner's iteration loop simple — no nested-record access. Per-slot fields mirror 01b's `SlotConfigEntryDto` 1:1 plus the optional `cuisinePreferenceWeight` (see below). **01f explicitly does NOT modify** the 01b `SlotConfigEntryDto` record; the two DTOs co-exist.

**LLD divergence note** — **cuisine preference**: the parent's per-module guidance flags "cuisine preference per slot" as part of the planner-friendly view. The LLD doesn't pin where this comes from. **01f sources it from `HouseholdSettings.document.slotDefaults[kind].cuisinePreferenceWeight`** if the field is present, else `null`. **The 01b `HouseholdSettingsDocument.SlotDefault` record does NOT yet declare a `cuisinePreferenceWeight` field** (LLD line 146 declares `slotDefaults: Map<SlotKind, SlotDefault>` but the LLD's `SlotDefault` shape is only `(shared, headcount, timeBudgetMin)`). **01f ships `cuisinePreferenceWeight = null` for v1**; when the household settings doc gains a cuisine-preference field (out of scope here), the planner-view resolver picks it up. **Worth user review** — alternative is to compute cuisine from the merged taste profile (`MergedSoftPreferencesDto.mergedTasteProfile.cuisineLikes`) — but that requires invoking `HouseholdMergeService.mergeSoftPreferencesForSlot` per slot, which is heavy and the planner has its own merge call. 01f keeps cuisine null for v1.

**LLD divergence note** — **`eaterUserIdsByPriority`**: the LLD's base `SlotConfigurationDto` doesn't carry a "userIds sorted by priority" field. The planner needs this to pick tie-breakers when two members rank the same slot. 01f computes it via `householdMemberRepository.findAllByHouseholdId(householdId)` then sorting by `HouseholdMember.priority DESC` + UUID natural-order tie-break — **same algorithm as 01e's `MergedSoftPreferencesDto.userIdsByPriority`**. **Worth user review** — alternative is to call `HouseholdMergeService.mergeSoftPreferencesForSlot` to get the field, but that triggers the soft-preferences read (SPI call) which is unnecessary here (the planner-view is meta about slots, not soft prefs). 01f computes the ordering locally — same priority data, no SPI dependency.

**Defers** (still out of scope after 01f):

- The actual planner module — 01f's `planner-view` is consumed by planner-01a (not yet built).
- Cuisine-preference field on `HouseholdSettings.document.slotDefaults` — when added later, the resolver reads it; 01f ships `null` for now.
- Multi-household-per-user (LLD §Out of Scope).
- Soft-preferences-driven slot configuration (e.g. derive shared/per-person from preferences) — planner-internal logic, not household's concern.
- Admin overrides on the planner view (e.g. "exclude this user's preference for the next plan") — planner concern.

01f unblocks the **planner module's slot-resolution flow**. Without 01f, the planner has to make 3+ calls (slot-configuration, members, settings) and reconstruct the priority ordering itself.

## Behavioural spec

### `SlotConfigurationPlannerViewDto` shape

1. New public record `com.example.mealprep.household.api.dto.SlotConfigurationPlannerViewDto` per the LLD divergence note. Java-side shape matches the divergence-note record verbatim.
2. New public record `com.example.mealprep.household.api.dto.PlannerSlotEntryDto` (flattened slot row). Lives in the same `api/dto/` package.
3. **Re-use** `SlotKind` enum from 01b. **Do NOT redefine.**
4. `cuisinePreferenceWeight` is `Integer` (boxed) — null is meaningful ("not configured").

### `getSlotConfigurationPlannerView` service method

5. **Append** new method to the existing `HouseholdQueryService` interface (from 01a, extended in 01b/01c/01d/01e — additive only, no removals):
   ```java
   SlotConfigurationPlannerViewDto getSlotConfigurationPlannerView(UUID householdId);
   ```
6. Implementation lives on the existing `HouseholdServiceImpl` (LLD line 262 — single impl class). **Append the method; do NOT split.**
7. **Read-only**: `@Transactional(readOnly = true)`.
8. **404 ladder**: `householdRepository.findById(householdId)` missing → 404 `HouseholdNotFoundException` (existing from 01a). Household has zero settings rows → 404 `HouseholdSettingsNotFoundException` (existing from 01b). Household has zero members → return the DTO with empty `slots`, empty `allEaterUserIds`, empty `eaterUserIdsByPriority`, null windows (NO exception — the planner-view is a read; 01e's `EmptyHouseholdMergeException` is merge-specific). **Worth user review** — alternative is 422 here too; rejected because the planner is the consumer and "household with no members" is a legitimate "empty plan" path for it.
9. **Step 1 — slots**: invoke existing `SlotConfigurationResolver.resolve(householdId)` from 01b → `SlotConfigurationDto` (the 01b shape). For each `SlotConfigEntryDto`, map to a `PlannerSlotEntryDto` with the same fields plus `cuisinePreferenceWeight = null` (per LLD divergence). Order preserved.
10. **Step 2 — eater priority ordering**: `householdMemberRepository.findAllByHouseholdId(householdId)` (same call 01b's resolver already issued — **document the duplicate-call cost** in the impl Javadoc; consider extracting a private helper if profiling shows hotspot). Build `List<UUID> eaterUserIdsByPriority = members.stream().sorted(byPriorityDescThenUuid).map(HouseholdMember::getUserId).toList()`. Algorithm: `priority DESC`; tie-break on `userId.compareTo` (UUID natural ordering — deterministic across runs).
11. **Step 3 — meal-timing windows**: load `HouseholdSettings` via `householdSettingsRepository.findByHouseholdId(householdId)`. From the JSONB `document.scheduling` field — **but** `HouseholdSchedulingPreferences` from 01b is an empty record (LLD line 28 in 01b ticket). **01f reads `mealTimingWindowStart` / `mealTimingWindowEnd` if `HouseholdSchedulingPreferences` carries them; else null.** **Worth user review** — the LLD `HouseholdSchedulingPreferences` is intentionally empty in v1. 01f's planner-view reads the field defensively (null when absent). When scheduling gains real fields, the resolver picks them up; today they ship as null.
12. **Step 4 — `allEaterUserIds`**: the full member list's userIds in member-id natural order (matches 01b's existing `SlotConfigurationDto.allEaterUserIds` semantics).
13. **Step 5 — `generatedAt`**: `Instant.now()` at resolution time. The planner uses this to detect stale snapshots if it caches.
14. **No persistence, no events.** Pure read.

### `GET /api/v1/households/current/slot-configuration/planner-view`

15. Authenticated. Server resolves `actorUserId` via `CurrentUserResolver` (existing from auth-01a). Caller's household resolved via `householdMemberRepository.findByUserId(actorUserId)` → 404 `HouseholdNotFoundException` if not in a household.
16. **Authorisation**: any authenticated household member can call (read-only; matches 01e's `/current/merge` pattern). **No role gate.**
17. Invokes `householdQueryService.getSlotConfigurationPlannerView(callerHouseholdId)`. Returns 200 with `SlotConfigurationPlannerViewDto`.
18. Anonymous → 401 (existing `SessionAuthenticationFilter` from auth-01a rejects).

### Errors

19. **No new exceptions.** Re-uses `HouseholdNotFoundException` (404, from 01a) and `HouseholdSettingsNotFoundException` (404, from 01b). The "zero members" branch returns an empty DTO (not an exception) — see invariant 8.
20. **No change** to `HouseholdExceptionHandler`. **DO NOT** modify `config/GlobalExceptionHandler.java`.

### Determinism

21. Same household state → byte-identical output (modulo `generatedAt`). Verified by a determinism test that calls the resolver twice and asserts equality on every field except `generatedAt`.
22. **No N+1**: total queries per request — 1 SELECT household, 1 SELECT members, 1 SELECT settings = 3. Verified via Hibernate Statistics in the IT.

### Cross-module facade

23. **Optional**: append `getSlotConfigurationPlannerView` to the `HouseholdModule.QueryService` facade if 01a follows the nested-class re-export pattern (same pattern as 01e — verify and skip if not). The `public` interface method is directly injectable either way.

## OpenAPI spec excerpt

### Append to `src/main/resources/openapi/paths/household.yaml`

(File extended by 01a/01b/01c/01d/01e — append one new path-item below 01e's `householdMerge` block. Do NOT touch existing path-items.)

```yaml
householdSlotConfigurationPlannerView:
  get:
    tags: [Households]
    operationId: getSlotConfigurationPlannerViewForCurrentHousehold
    summary: 'Planner-friendly slot-configuration view for the calling user''s household; read-only, not persisted.'
    security: [{ cookieAuth: [] }]
    responses:
      '200':
        description: Planner-friendly slot configuration snapshot.
        content:
          application/json:
            schema: { $ref: '../schemas/household.yaml#/SlotConfigurationPlannerViewDto' }
      '401': { description: 'Unauthenticated', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '404': { description: 'Caller not in any household, or household has no settings row', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
```

### Append to `src/main/resources/openapi/schemas/household.yaml`

```yaml
SlotConfigurationPlannerViewDto:
  type: object
  required: [householdId, slots, allEaterUserIds, eaterUserIdsByPriority, generatedAt]
  properties:
    householdId: { type: string, format: uuid }
    slots:
      type: array
      items: { $ref: '#/PlannerSlotEntryDto' }
    allEaterUserIds:
      type: array
      items: { type: string, format: uuid }
    eaterUserIdsByPriority:
      type: array
      description: 'Member userIds sorted by priority descending; UUID natural-order tie-break.'
      items: { type: string, format: uuid }
    mealTimingWindowStart:
      type: string
      pattern: '^[0-2][0-9]:[0-5][0-9]$'
      nullable: true
    mealTimingWindowEnd:
      type: string
      pattern: '^[0-2][0-9]:[0-5][0-9]$'
      nullable: true
    generatedAt: { type: string, format: date-time }
PlannerSlotEntryDto:
  type: object
  required: [slotKey, kind, shared, headcount, timeBudgetMin]
  properties:
    slotKey: { type: string, maxLength: 64 }
    kind:
      type: string
      enum: [BREAKFAST, LUNCH, DINNER, SNACK, CUSTOM]
    shared: { type: boolean }
    headcount: { type: integer, minimum: 1 }
    timeBudgetMin: { type: integer, minimum: 1 }
    eaterUserIdsIfPerPerson:
      type: array
      nullable: true
      description: 'Null when shared = true; the household''s member userIds when shared = false.'
      items: { type: string, format: uuid }
    cuisinePreferenceWeight:
      type: integer
      nullable: true
      minimum: 0
      maximum: 100
      description: 'Per-slot cuisine-preference weight; null when not configured.'
```

**Gotcha applied**: every nullable field uses **inline** `nullable: true` (NOT `$ref + nullable: true`). `kind` inlines the enum rather than `$ref`-ing a separate `SlotKind` schema because the inline form avoids the `$ref + nullable` trap and the enum is the same as the household-01b inline.

**Gotcha applied**: every description containing `'` `,` `:` is single-quoted per the round-4 lesson.

### Append to entry `src/main/resources/openapi/openapi.yaml`

**Location**: under the existing `# household` block in `paths:`. Append one new path-item ref below 01e's `/api/v1/households/current/merge` line:

```yaml
  /api/v1/households/current/slot-configuration/planner-view:
    $ref: 'paths/household.yaml#/householdSlotConfigurationPlannerView'
```

**Location**: under `components.schemas:`, append two new schema refs in the existing `# household` block (alphabetical):

```yaml
    PlannerSlotEntryDto: { $ref: 'schemas/household.yaml#/PlannerSlotEntryDto' }
    SlotConfigurationPlannerViewDto: { $ref: 'schemas/household.yaml#/SlotConfigurationPlannerViewDto' }
```

## Verbatim shape snippets

### Service-impl method skeleton

```java
@Transactional(readOnly = true)
public SlotConfigurationPlannerViewDto getSlotConfigurationPlannerView(UUID householdId) {
  householdRepository.findById(householdId).orElseThrow(HouseholdNotFoundException::new);
  HouseholdSettings settings = householdSettingsRepository.findByHouseholdId(householdId)
      .orElseThrow(HouseholdSettingsNotFoundException::new);
  List<HouseholdMember> members = householdMemberRepository.findAllByHouseholdId(householdId);

  SlotConfigurationDto base = slotConfigurationResolver.resolve(householdId, members, settings);
  List<PlannerSlotEntryDto> slots = base.slots().stream()
      .map(e -> new PlannerSlotEntryDto(
          e.slotKey(), e.kind(), e.shared(), e.headcount(), e.timeBudgetMin(),
          e.eaterUserIdsIfPerPerson(), null /* cuisinePreferenceWeight */))
      .toList();

  List<UUID> eaterUserIdsByPriority = members.stream()
      .sorted(Comparator
          .comparingInt(HouseholdMember::getPriority).reversed()
          .thenComparing(HouseholdMember::getUserId))
      .map(HouseholdMember::getUserId)
      .toList();

  String windowStart = settings.getDocument().scheduling() == null
      ? null : extractWindowStart(settings.getDocument().scheduling());
  String windowEnd = settings.getDocument().scheduling() == null
      ? null : extractWindowEnd(settings.getDocument().scheduling());

  return new SlotConfigurationPlannerViewDto(
      householdId, slots, base.allEaterUserIds(),
      eaterUserIdsByPriority, windowStart, windowEnd, Instant.now());
}
```

### Controller skeleton

```java
@RestController
@RequestMapping("/api/v1/households/current/slot-configuration")
@Tag(name = "Households")
public class HouseholdSlotConfigurationPlannerViewController {

  private final HouseholdQueryService queryService;
  private final HouseholdMemberRepository memberRepository;
  private final CurrentUserResolver currentUser;

  @GetMapping("/planner-view")
  public ResponseEntity<SlotConfigurationPlannerViewDto> get() {
    UUID actorUserId = currentUser.requireUserId();
    HouseholdMember me = memberRepository.findByUserId(actorUserId)
        .orElseThrow(HouseholdNotFoundException::new);
    return ResponseEntity.ok(queryService.getSlotConfigurationPlannerView(me.getHouseholdId()));
  }
}
```

## Files this ticket touches

```
NEW   src/main/java/com/example/mealprep/household/api/controller/HouseholdSlotConfigurationPlannerViewController.java
NEW   src/main/java/com/example/mealprep/household/api/dto/SlotConfigurationPlannerViewDto.java
NEW   src/main/java/com/example/mealprep/household/api/dto/PlannerSlotEntryDto.java

MOD   src/main/java/com/example/mealprep/household/domain/service/HouseholdQueryService.java                 (append `getSlotConfigurationPlannerView(UUID): SlotConfigurationPlannerViewDto`)
MOD   src/main/java/com/example/mealprep/household/domain/service/internal/HouseholdServiceImpl.java        (implement the new method; constructor unchanged — `SlotConfigurationResolver` already wired from 01b)
MOD   src/main/java/com/example/mealprep/household/HouseholdModule.java                                      (optional — re-export the new method on QueryService facade; skip if not pattern)

MOD   src/main/resources/openapi/paths/household.yaml        (append 1 new path-item below 01e's `householdMerge`; do NOT touch existing)
MOD   src/main/resources/openapi/schemas/household.yaml      (append 2 new schemas: `SlotConfigurationPlannerViewDto`, `PlannerSlotEntryDto`)
MOD   src/main/resources/openapi/openapi.yaml                (1 line under paths in `# household` block; 2 lines under components.schemas in `# household` block)

NEW   src/test/java/com/example/mealprep/household/SlotConfigurationPlannerViewServiceTest.java             (happy path, zero members, missing settings, cuisinePreferenceWeight=null, priority ordering tie-break)
NEW   src/test/java/com/example/mealprep/household/HouseholdSlotConfigurationPlannerViewFlowIT.java         (HTTP: authenticated member; anonymous; user-not-in-household; full snapshot assertion)
MOD   src/test/java/com/example/mealprep/household/testdata/HouseholdTestData.java                          (append builders for `SlotConfigurationPlannerViewDto`, `PlannerSlotEntryDto`)
```

**Files this ticket does NOT modify** (cross-cutting; sibling round-6 tickets running in parallel must not collide):

- `src/main/java/com/example/mealprep/config/GlobalExceptionHandler.java` — no new exceptions; no change.
- `src/test/java/com/example/mealprep/archunit/ModuleBoundaryTest.java` — no new sub-packages.
- `src/main/java/com/example/mealprep/household/api/HouseholdExceptionHandler.java` — re-uses existing exceptions, no new `@ExceptionHandler`.
- 01a/01b's `SlotConfigEntryDto` record — left alone (separate from the new `PlannerSlotEntryDto`).
- 01b's `SlotConfigurationResolver` — invoked, not modified.
- Other modules' `paths/*.yaml`, `schemas/*.yaml`, `<module>ExceptionHandler.java`, migrations, entities — none touched.
- Sibling round-6 tickets: only collision point is the entry `openapi.yaml`; this ticket appends in the `# household` block.

## Edge-case checklist

- [ ] `GET /current/slot-configuration/planner-view` by MEMBER → 200; payload includes built-in slots (BREAKFAST/LUNCH/DINNER/SNACK) from 01b's defaults
- [ ] `GET /current/slot-configuration/planner-view` by PRIMARY → 200 (same response shape as MEMBER; no role gate)
- [ ] `GET /current/slot-configuration/planner-view` by anonymous → 401
- [ ] `GET /current/slot-configuration/planner-view` by user-not-in-any-household → 404 `household-not-found`
- [ ] Household with zero members → 200; `slots` populated from defaults; `allEaterUserIds = []`; `eaterUserIdsByPriority = []` (no exception per invariant 8)
- [ ] Household with no `HouseholdSettings` row → 404 `household-settings-not-found` (caller must seed settings via 01b's `PUT /settings` first; matches 01b's invariant 14)
- [ ] Custom slot defined in settings → `PlannerSlotEntryDto.kind = backedByKind`; `slotKey` = the custom key (e.g. `"late-snack"`)
- [ ] Per-slot `headcount` override in settings → reflected in `PlannerSlotEntryDto.headcount`
- [ ] `shared = true` slot → `eaterUserIdsIfPerPerson = null`
- [ ] `shared = false` slot → `eaterUserIdsIfPerPerson = full member list`
- [ ] `eaterUserIdsByPriority` ordering — 3 members `(priority=100, UUID-A), (priority=200, UUID-B), (priority=100, UUID-C)`, alphabetical-UUID order `A < C` → output `[B, A, C]` (priority DESC first, then UUID ASC)
- [ ] Determinism — same household state, two consecutive calls → byte-identical `slots`, `allEaterUserIds`, `eaterUserIdsByPriority`, windows (modulo `generatedAt`)
- [ ] `cuisinePreferenceWeight` is `null` for v1 — every slot
- [ ] `mealTimingWindowStart` / `End` null when `HouseholdSchedulingPreferences` has no fields (v1 default)
- [ ] No N+1 — Hibernate stats assert ≤ 3 SQL statements per request (household, settings, members)
- [ ] Path is exact: `/api/v1/households/current/slot-configuration/planner-view` (no trailing slash; not under `/{id}/`)
- [ ] 01b's existing `GET /api/v1/households/{id}/slot-configuration` continues to work (returns `SlotConfigurationDto` — different shape; no regression)
- [ ] OpenAPI request/response shapes match (swagger-request-validator filter active in the IT)
- [ ] `HouseholdBoundaryTest` (from 01a) still passes — only `api/dto/` + `api/controller/` + `domain/service/` packages touched
- [ ] `HouseholdExceptionHandler` unchanged — no new exceptions
- [ ] Re-uses `HouseholdNotFoundException` / `HouseholdSettingsNotFoundException` — no new classes
- [ ] No new migrations
- [ ] No event published — pure read
- [ ] No regression on 01a/01b/01c/01d/01e tests
- [ ] No `pom.xml` dependency adds
- [ ] No preference / nutrition / provisions / recipe module file touched

## Dependencies

- **Hard dependency**: `household-01a` (merged) — `Household`, `HouseholdMember`, `HouseholdRepository`, `HouseholdMemberRepository`, `HouseholdNotFoundException`, `HouseholdQueryService` (the interface 01f appends to).
- **Hard dependency**: `household-01b` (merged) — `HouseholdSettings`, `HouseholdSettingsRepository`, `HouseholdSettingsDocument`, `HouseholdSchedulingPreferences`, `SlotKind`, `SlotConfigEntryDto`, `SlotConfigurationDto`, `SlotConfigurationResolver`, `HouseholdSettingsNotFoundException`.
- **Hard dependency**: `household-01c` (merged) — pattern reuse only; invite flow not used.
- **Hard dependency**: `household-01d` (merged) — `HouseholdMember.priority` integer column populated (default 100) so the priority sort works.
- **Hard dependency**: `household-01e` (merged) — pattern reuse only (`/current/...` resource path); merge service NOT invoked.
- **Hard dependency**: `auth-01a` (merged) — `CurrentUserResolver`, `SessionAuthenticationFilter`.
- **Hard dependency**: `refactor-01-split-merge-zones` (merged) — per-module YAML / advice / boundary-test layout.
- **No cross-module SPI coupling** — this ticket is the simplest round-6 ticket; household owns everything it needs.
- **Sibling tickets running in parallel** (Wave 2 round 6): `nutrition-01f`, `provisions-01f`, `recipe-01f`. None should touch any household file or any of the cross-cutting files listed above. **The recipe-01f ↔ nutrition-01f cross-SPI coupling does NOT involve household.** Only collision point is the entry `openapi.yaml`; this ticket appends in the `# household` block, sibling tickets append in their own module's block.

## Acceptance / DoD

- [ ] `./mvnw -Dspotless.check.skip=true verify` passes locally on the agent's worktree
- [ ] `./mvnw spotless:apply` then `./mvnw spotless:check` clean
- [ ] CI green on the PR (build + spotless + OpenAPI lint + ArchUnit gate)
- [ ] All edge-case items above ticked
- [ ] OpenAPI 3.0 nullable fields use **inline** `nullable: true` (NOT `$ref + nullable: true`) on `mealTimingWindowStart`, `mealTimingWindowEnd`, `eaterUserIdsIfPerPerson`, `cuisinePreferenceWeight`
- [ ] All YAML description strings containing `,` `:` `'` are single-quoted (round-4 lesson)
- [ ] No regression on existing tests, including 01e's `HouseholdMergeFlowIT`, 01d's `HouseholdMembersFlowIT`, 01c's `HouseholdInvitesFlowIT`, 01b's `HouseholdSettingsFlowIT` (especially the existing `GET /{id}/slot-configuration` test continues to pass — different endpoint, different DTO shape)
- [ ] No N+1 — exactly 3 queries per request (household, settings, members)
- [ ] No pom.xml dependency adds
- [ ] No preference / nutrition / provisions / recipe module file touched

## What's NOT in scope

- The planner module itself — planner-01a consumes 01f's endpoint; not built yet.
- Cuisine-preference field on `HouseholdSettings.document.slotDefaults` — added later; 01f ships `cuisinePreferenceWeight = null`.
- `HouseholdSchedulingPreferences` real fields — 01b ships an empty record; 01f reads defensively.
- Caching / TTL on the planner-view — Spring `@Cacheable` is a follow-up if profiling shows hotspot.
- Admin overrides on the planner view — planner-internal concern.
- The `/api/v1/households/{id}/slot-configuration` endpoint (01b) — unchanged.
- Cross-household planner-view bulk endpoint (`getByUserIds` style) — planner-01a may need; defer.
- Schema-version on the planner-view DTO — not persisted, rule doesn't apply.

Squash-merge with: `feat(household): 01f — SlotConfigurationPlannerViewDto + GET /current/slot-configuration/planner-view (planner-facing read)`
