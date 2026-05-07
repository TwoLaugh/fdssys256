# Household Module — LLD

*Implementation specification for the Household Model: one household per user (v1), the primary/member role split, slot-sharing settings, and the canonical soft-preference merge for shared meal slots. Translates the Household sections of [system-overview.md](../design/system-overview.md#household-model) and [meal-planner.md](../design/meal-planner.md#household-integration) into a buildable Spring Boot module.*

## Scope

Specifies the `household` module — package layout, JPA entities, Flyway migrations, repositories, service interfaces, DTOs, mappers, REST controllers, validation, events, business-logic flows, transaction boundaries, and the test plan. Conventions defer to [lld/style-guide.md](style-guide.md); restated here only where the module-specific application matters.

The HLD positions this module around three responsibilities:

| Concern | Source | This module's job |
|---|---|---|
| Membership | [system-overview.md §Household Model](../design/system-overview.md#household-model) | Owns `household`, `household_member`, role state machine (primary, member). |
| Slot configuration | [meal-planner.md §Slot configuration](../design/meal-planner.md#slot-configuration) | Per-household defaults for shared-vs-individual, headcount per slot, custom slot definitions. The planner reads them. |
| Soft-preference merge | [meal-planner.md §Household Integration](../design/meal-planner.md#household-integration) | Canonical place where per-eater taste profiles are combined for a shared slot ("mean of taste-profile vectors, weighted by per-person priority"). No other module replicates this. |

What we do **not** own: hard constraints (per-user, `preference`), provisions, the recipe pool, planner runtime state. The hard-constraint *union* for shared slots is performed by `HardConstraintFilterService.checkForHousehold` in the preference module (see [preference.md](preference.md)); we supply the eater list, not the union logic.

---

## Package Layout

```
com.example.mealprep.household/
├── HouseholdModule.java                facade re-exporting public service interfaces
├── api/
│   ├── controller/                     HouseholdsController, HouseholdMembersController,
│   │                                    HouseholdSettingsController, HouseholdInvitesController
│   ├── dto/                            records (see DTOs)
│   └── mapper/                         MapStruct mappers (see Mappers)
├── domain/
│   ├── entity/                         JPA entities (see Entities)
│   ├── repository/                     Spring Data interfaces — package-private
│   └── service/
│       ├── HouseholdQueryService.java, HouseholdUpdateService.java, HouseholdMergeService.java
│       ├── HouseholdServiceImpl.java   single impl of all three
│       └── internal/                   SoftPreferenceMerger, SlotConfigurationResolver, InviteCodeGenerator
├── event/                              5 event records (see Events)
├── exception/                          module root + per-failure subclasses
└── validation/                         @ValidSlotKey, @ValidHeadcount + validators
```

The four-controller split mirrors the URL surface (`/households`, `/.../members`, `/.../settings`, `/.../invites`) — resource shapes differ enough that one omnibus controller would be awkward to OpenAPI-tag.

---

## Database

Migrations live under `src/main/resources/db/migration/` per [technical-architecture.md §Migrations](../design/technical-architecture.md#migrations); one concern per file:

```
V20260501130000__household_create_household.sql
V20260501130100__household_create_household_member.sql
V20260501130200__household_create_household_settings.sql
V20260501130300__household_create_household_invite.sql
```

Audit columns (`created_at`, `updated_at`, `optimistic_version`) are present on every mutable table and elided below for brevity.

```sql
-- V20260501130000
CREATE TABLE household (
    id                  uuid PRIMARY KEY,
    name                varchar(128) NOT NULL,
    created_by_user_id  uuid NOT NULL
);
CREATE INDEX idx_household_created_by_user ON household (created_by_user_id);

-- V20260501130100
CREATE TABLE household_member (
    id                  uuid PRIMARY KEY,
    household_id        uuid NOT NULL REFERENCES household(id) ON DELETE CASCADE,
    user_id             uuid NOT NULL,
    role                varchar(16) NOT NULL,            -- 'primary' | 'member'
    display_name        varchar(64),
    priority            integer NOT NULL DEFAULT 100,    -- per-person merge weight (HLD)
    joined_at           timestamptz NOT NULL,
    UNIQUE (household_id, user_id),
    UNIQUE (user_id)                                     -- v1: one household per user
);
CREATE INDEX idx_household_member_household ON household_member (household_id);
-- Exactly one primary per household, enforced at the DB.
CREATE UNIQUE INDEX idx_household_member_one_primary
    ON household_member (household_id) WHERE role = 'primary';

-- V20260501130200
CREATE TABLE household_settings (
    id                  uuid PRIMARY KEY,
    household_id        uuid NOT NULL UNIQUE REFERENCES household(id) ON DELETE CASCADE,
    document            jsonb NOT NULL                   -- mirrored by HouseholdSettingsDocument
);
CREATE TABLE household_settings_audit (
    id                      uuid PRIMARY KEY,
    household_settings_id   uuid NOT NULL REFERENCES household_settings(id) ON DELETE CASCADE,
    actor_user_id           uuid NOT NULL,
    field_path              varchar(128) NOT NULL,       -- e.g. "slotDefaults.dinner.shared"
    previous_value_json     jsonb NOT NULL,
    new_value_json          jsonb NOT NULL,
    occurred_at             timestamptz NOT NULL
);
CREATE INDEX idx_household_settings_audit_hs_time
    ON household_settings_audit (household_settings_id, occurred_at DESC);

-- V20260501130300
CREATE TABLE household_invite (
    id                       uuid PRIMARY KEY,
    household_id             uuid NOT NULL REFERENCES household(id) ON DELETE CASCADE,
    invite_code              varchar(32) NOT NULL UNIQUE,
    issued_by_user_id        uuid NOT NULL,
    issued_for_user_id       uuid,                        -- pre-targeted, optional
    intended_role            varchar(16) NOT NULL DEFAULT 'member',
    expires_at               timestamptz NOT NULL,
    accepted_by_user_id      uuid,
    accepted_at              timestamptz,
    revoked_at               timestamptz
);
-- Lookup by code on accept; pending-invites listing for admin UI.
CREATE INDEX idx_household_invite_code     ON household_invite (invite_code)  WHERE accepted_at IS NULL AND revoked_at IS NULL;
CREATE INDEX idx_household_invite_household ON household_invite (household_id) WHERE accepted_at IS NULL AND revoked_at IS NULL;
```

`priority` (default 100 = equal voice) is the per-person weight referenced by the meal-planner HLD's merge formula; the column exists from day one because adding it later would touch every household. `UNIQUE (user_id)` on `household_member` encodes the v1 single-household rule (dropped in a follow-up if multi-household lands). Settings JSONB mirrors the preference module's lifestyle config — read-whole, written via single PUT — **worth user review**, same flag the preference LLD raised.

---

## Entities

All entities follow the style guide: UUID `@Id` set application-side, `@Version` on every mutable aggregate root, `@CreatedDate`/`@LastModifiedDate` audit columns, Lombok `@Getter @Setter @Builder @NoArgsConstructor(access = PROTECTED) @AllArgsConstructor`. JSONB via `@Type(JsonType.class)` from `hypersistence-utils`.

| Entity | Notes |
|---|---|
| `Household` | Aggregate root. Owns `@OneToMany` to `HouseholdMember` and `@OneToOne` to `HouseholdSettings` (both cascade ALL, orphanRemoval where applicable). |
| `HouseholdMember` | Child. `@ManyToOne(fetch = LAZY)` back via `@JoinColumn(name = "household_id")`. `role` → `HouseholdRole` enum. Own `@Version` — independently editable. |
| `HouseholdSettings` | One per household. `document` mapped to `HouseholdSettingsDocument` via JSONB. |
| `HouseholdSettingsAuditLog` | Append-only. No `@Version`, no `@LastModifiedDate`. JSON values as `JsonNode`. |
| `HouseholdInvite` | Aggregate root. Status derived from `acceptedAt` / `revokedAt` / `expiresAt`. |

Module-local enums: `HouseholdRole` (`PRIMARY`, `MEMBER`); `SlotKind` (`BREAKFAST`, `LUNCH`, `DINNER`, `SNACK`, `CUSTOM` — mirrors the planner HLD). Sharing `SlotKind` cross-module via `core` is a defensible refactor, bigger than household scope — **worth user review.**

### `HouseholdSettingsDocument`

Java mirror of the JSONB shape — read whole by the planner; schema owned application-side.

```java
public record HouseholdSettingsDocument(
    Map<SlotKind, SlotDefault> slotDefaults,
    List<CustomSlotDefinition> customSlots,
    Integer defaultHeadcount,
    HouseholdSchedulingPreferences scheduling          // empty in v1; reserved for per-day overrides
) {
    public record SlotDefault(boolean shared, Integer headcount, Integer timeBudgetMin) {}
    public record CustomSlotDefinition(String key, String label, SlotKind backedByKind,
                                       boolean shared, Integer headcount, Integer timeBudgetMin) {}
    public record HouseholdSchedulingPreferences() {}
}
```

---

## DTOs

All DTOs are Java records per the style guide.

```java
public record HouseholdDto(UUID id, String name, UUID createdByUserId, Instant createdAt, long version) {}
public record HouseholdMemberDto(UUID id, UUID householdId, UUID userId, HouseholdRole role,
                                 String displayName, int priority, Instant joinedAt, long version) {}
public record HouseholdSettingsDto(UUID id, UUID householdId, HouseholdSettingsDocument document, long version) {}
public record HouseholdSettingsAuditEntryDto(UUID id, UUID actorUserId, String fieldPath,
                                             JsonNode previousValue, JsonNode newValue, Instant occurredAt) {}
public record HouseholdInviteDto(UUID id, UUID householdId,
                                 String inviteCode,                            // ONLY populated at creation; omitted from lists
                                 UUID issuedByUserId, UUID issuedForUserId, HouseholdRole intendedRole,
                                 Instant expiresAt, Instant acceptedAt, Instant revokedAt,
                                 InviteStatus status) {}                       // PENDING | ACCEPTED | REVOKED | EXPIRED — derived
public enum InviteStatus { PENDING, ACCEPTED, REVOKED, EXPIRED }
```

### Request bodies

```java
public record CreateHouseholdRequest(@NotBlank @Size(max = 128) String name) {}
public record UpdateHouseholdSettingsRequest(@NotNull @Valid HouseholdSettingsDocument document, long expectedVersion) {}
public record AddMemberRequest(@NotNull UUID userId, @Size(max = 64) String displayName,
                               @NotNull HouseholdRole role, @Min(0) @Max(1000) Integer priority) {}
public record UpdateMemberRequest(@Size(max = 64) String displayName, @Min(0) @Max(1000) Integer priority, long expectedVersion) {}
public record ChangeRoleRequest(@NotNull HouseholdRole newRole, long expectedVersion) {}
public record CreateInviteRequest(UUID issuedForUserId, @NotNull HouseholdRole intendedRole, @NotNull @Future Instant expiresAt) {}
public record AcceptInviteRequest(@NotBlank String inviteCode) {}
```

### `MergedSoftPreferencesDto`

Output of `HouseholdMergeService` — fed into the planner's scoring stage for shared slots.

```java
public record MergedSoftPreferencesDto(
    UUID householdId, List<UUID> contributingUserIds,
    TasteProfileDocument mergedTasteProfile,        // re-uses preference module's record shape
    LifestyleConfigDocument mergedLifestyleConfig,
    List<UUID> userIdsByPriority,                   // descending — for tie-breaks
    MergeStrategy strategy,                         // MEAN_WEIGHTED_BY_PRIORITY in v1
    Instant mergedAt
) { public enum MergeStrategy { MEAN_WEIGHTED_BY_PRIORITY } }
```

Re-using `TasteProfileDocument` means the planner has no special-case rendering path for shared slots. `mergedLifestyleConfig` is the **structurally-mergeable subset** (meal_timing windows, novelty tolerance, batch cooking flags) — free-text notes dropped, per-field rule most-restrictive. HLD specifies only the taste-profile merge — **worth user review.**

`SlotConfigurationDto` is the planner-friendly view of the settings document — called once per planning run:

```java
public record SlotConfigurationDto(UUID householdId, List<SlotConfigEntryDto> slots, List<UUID> allEaterUserIds) {
    public record SlotConfigEntryDto(String slotKey, SlotKind kind, boolean shared,
                                     int headcount, int timeBudgetMin,
                                     List<UUID> eaterUserIdsIfPerPerson /* null when shared */) {}
}
```

---

## Mappers

MapStruct interfaces, `@Mapper(componentModel = "spring")`, one per entity-DTO pair: `HouseholdMapper` (uses `HouseholdMemberMapper`), `HouseholdMemberMapper`, `HouseholdSettingsMapper` (also maps audit-log entries), `HouseholdInviteMapper`. Each exposes `toDto(entity)` and `toDtos(List<entity>)`. `HouseholdInviteMapper` declares `@Mapping(target = "inviteCode", ignore = true)` — codes populate only at creation, never in lists (bearer-only secrecy). The merge service's output is built by `SoftPreferenceMerger` directly (not an entity-DTO conversion).

---

## Repositories

Package-private; cross-module access via service interfaces only.

```java
interface HouseholdRepository extends JpaRepository<Household, UUID> {
    @EntityGraph(attributePaths = {"members", "settings"})
    Optional<Household> findWithMembersAndSettingsById(UUID id);
    @EntityGraph(attributePaths = {"members"})
    Optional<Household> findWithMembersById(UUID id);
}
interface HouseholdMemberRepository extends JpaRepository<HouseholdMember, UUID> {
    Optional<HouseholdMember> findByUserId(UUID userId);
    List<HouseholdMember> findAllByHouseholdId(UUID householdId);
    List<HouseholdMember> findAllByHouseholdIdAndRole(UUID householdId, HouseholdRole role);
    boolean existsByHouseholdIdAndRole(UUID householdId, HouseholdRole role);
}
interface HouseholdSettingsRepository extends JpaRepository<HouseholdSettings, UUID> {
    Optional<HouseholdSettings> findByHouseholdId(UUID householdId);
}
interface HouseholdSettingsAuditLogRepository extends JpaRepository<HouseholdSettingsAuditLog, UUID> {
    Page<HouseholdSettingsAuditLog> findByHouseholdSettingsIdOrderByOccurredAtDesc(UUID id, Pageable p);
}
interface HouseholdInviteRepository extends JpaRepository<HouseholdInvite, UUID> {
    Optional<HouseholdInvite> findByInviteCode(String inviteCode);
    List<HouseholdInvite> findByHouseholdIdAndAcceptedAtIsNullAndRevokedAtIsNull(UUID householdId);
}
```

`@EntityGraph` keeps the most common UI read to one JOIN — no N+1.

---

## Service Interfaces

Per the style guide, all three module interfaces are implemented by a single `HouseholdServiceImpl`. `HouseholdMergeService` is its own interface — narrower API, narrower coupling, same pattern as the preference module's `HardConstraintFilterService`.

### `HouseholdQueryService`

```java
public interface HouseholdQueryService {
    Optional<HouseholdDto> getById(UUID householdId);
    List<HouseholdDto> getByIds(List<UUID> householdIds);
    Optional<HouseholdDto> getByUserId(UUID userId);                       // v1: at most one

    List<HouseholdMemberDto> listMembers(UUID householdId);
    List<HouseholdMemberDto> listMembersByHouseholdIds(List<UUID> ids);    // batch sibling
    Optional<HouseholdMemberDto> getMember(UUID memberId);
    Optional<HouseholdMemberDto> getMembershipForUser(UUID userId);

    Optional<HouseholdSettingsDto> getSettings(UUID householdId);
    List<HouseholdSettingsDto> getSettingsByHouseholdIds(List<UUID> ids);
    Page<HouseholdSettingsAuditEntryDto> getSettingsAuditLog(UUID householdId, Pageable pageable);

    SlotConfigurationDto getSlotConfiguration(UUID householdId);

    List<HouseholdInviteDto> listPendingInvites(UUID householdId);
    Optional<HouseholdInviteDto> getInviteByCode(String inviteCode);
}
```

### `HouseholdUpdateService`

```java
public interface HouseholdUpdateService {
    // Creator becomes the first PRIMARY member in the same transaction.
    HouseholdDto createHousehold(UUID creatorUserId, CreateHouseholdRequest request);
    HouseholdSettingsDto updateSettings(UUID householdId, UUID actorUserId, UpdateHouseholdSettingsRequest request);

    HouseholdMemberDto addMember(UUID householdId, UUID actorUserId, AddMemberRequest request);
    HouseholdMemberDto updateMember(UUID memberId, UUID actorUserId, UpdateMemberRequest request);
    void removeMember(UUID memberId, UUID actorUserId);
    HouseholdMemberDto changeRole(UUID memberId, UUID actorUserId, ChangeRoleRequest request);

    HouseholdInviteDto createInvite(UUID householdId, UUID actorUserId, CreateInviteRequest request);
    HouseholdMemberDto acceptInvite(UUID accepterUserId, AcceptInviteRequest request);
    void revokeInvite(UUID inviteId, UUID actorUserId);
}
```

`actorUserId` threads the acting user through every mutation for audit and authorisation. The auth module resolves it from the session/token; this module trusts it as input.

### `HouseholdMergeService`

The canonical place for soft-preference merging. **No other module replicates this logic.**

```java
public interface HouseholdMergeService {
    // Mean of taste-profile vectors weighted by per-person priority (meal-planner.md §Household
    // Integration). Lifestyle merged most-restrictive; free-text notes dropped. Hard constraints
    // are NOT merged here — that is HardConstraintFilterService.checkForHousehold's job.
    // eaterUserIds: empty/null means "all current household members".
    MergedSoftPreferencesDto mergeSoftPreferencesForSlot(UUID householdId, List<UUID> eaterUserIds);

    // Variant bypassing household lookup — used during feasibility checks and by tests.
    MergedSoftPreferencesDto mergeSoftPreferencesForUsers(List<UUID> userIds, List<Integer> priorities);
}
```

Injects `PreferenceQueryService.getSoftPreferencesByUserIds` — one round-trip fetches every eater's bundle. Per-eater priorities come from `HouseholdMember.priority`. **Read-only** — produces a transient document the planner consumes during composition; not persisted. Numerical weighting evolves (Out of Scope); the interface freezes.

---

## REST Controllers

All endpoints under `/api/v1/households/...`. `actorUserId` resolved server-side from auth context per [technical-architecture.md §REST API](../design/technical-architecture.md#rest-api-with-json) — never in the URL. OpenAPI: `@Tag(name = "Households")` on Households + Settings, `@Tag(name = "Household Members")` on Members, `@Tag(name = "Household Invites")` on Invites.

| Method | Path | Body → Response |
|---|---|---|
| POST   | `/households` | `CreateHouseholdRequest` → `HouseholdDto` (201) |
| GET    | `/households/current`, `/households/{id}` | → `HouseholdDto` (200/404) |
| GET    | `/households/{id}/members` | → `List<HouseholdMemberDto>` (200) |
| POST   | `/households/{id}/members` | `AddMemberRequest` → `HouseholdMemberDto` (201) |
| PATCH  | `/households/{id}/members/{memberId}` | `UpdateMemberRequest` → `HouseholdMemberDto` (200) |
| DELETE | `/households/{id}/members/{memberId}` | → 204 |
| POST   | `/households/{id}/members/{memberId}/role` | `ChangeRoleRequest` → `HouseholdMemberDto` (200) |
| GET    | `/households/{id}/settings` | → `HouseholdSettingsDto` (200/404) |
| PUT    | `/households/{id}/settings` | `UpdateHouseholdSettingsRequest` → `HouseholdSettingsDto` (200) |
| GET    | `/households/{id}/settings/audit-log?page=&size=` | → `Page<HouseholdSettingsAuditEntryDto>` |
| GET    | `/households/{id}/slot-configuration` | → `SlotConfigurationDto` (200/404) |
| GET    | `/households/{id}/invites`, POST same path | `CreateInviteRequest` → `List<…>` / `HouseholdInviteDto` |
| DELETE | `/households/{id}/invites/{inviteId}` | → 204 |
| POST   | `/households/invites/accept` | `AcceptInviteRequest` → `HouseholdMemberDto` (200/404/409/410) |

All paths prefixed `/api/v1`. Mutations may also return 400 (validation), 403 (insufficient role), 409 (stale version, single-household / last-primary conflicts). `HouseholdMergeService` is **not** exposed via REST — invoked in-process by the planner.

### Error responses

RFC 9457 `ProblemDetail` (handled by the project-wide `GlobalExceptionHandler`); `type` URIs follow `https://mealprep.example.com/problems/<kebab-case-name>`:

| Exception | Status |
|---|---|
| `HouseholdNotFoundException`, `HouseholdMemberNotFoundException`, `HouseholdSettingsNotFoundException`, `HouseholdInviteNotFoundException` | 404 |
| `HouseholdInviteExpiredException`, `HouseholdInviteRevokedException` | 410 |
| `HouseholdInviteAlreadyAcceptedException`, `UserAlreadyInHouseholdException`, `LastPrimaryRemovalException`, `OptimisticLockException` (JPA) | 409 |
| `InsufficientHouseholdRoleException` | 403 |
| `MethodArgumentNotValidException` | 400 (with `errors[]` extension) |

Module root: `HouseholdException extends MealPrepException`.

---

## Validation

Standard Jakarta annotations on request records (`@NotNull`, `@NotBlank`, `@Size`, `@Min`/`@Max`, `@Future`, `@Valid`). Custom validators in `validation/`:

- **`@ValidSlotKey`** — kebab-case, 1–48 chars, no collision with built-in slot kind names.
- **`@ValidHeadcount`** — between 1 and 16 (matches the planner's per-eater sanity check).

Cross-field rules enforced **service-layer** (need DB state): removing the last primary → `LastPrimaryRemovalException` (409); adding a user already in a household → `UserAlreadyInHouseholdException` (409); demoting yourself when no other primary exists → reject (promote someone else first). Detailed admin escalation beyond primary/member is deferred.

---

## Events

### Published

The technical-architecture catalogue lists a single `HouseholdConfigChangedEvent`. The LLD splits it into five — membership and settings churn have different planner consequences. **Worth user review.**

```java
public record HouseholdCreatedEvent       (UUID householdId, UUID createdByUserId, UUID traceId, Instant occurredAt) {}
public record HouseholdMemberAddedEvent   (UUID householdId, UUID memberId, UUID userId, HouseholdRole role, UUID traceId, Instant occurredAt) {}
public record HouseholdMemberRemovedEvent (UUID householdId, UUID memberId, UUID userId, HouseholdRole roleAtRemoval, UUID traceId, Instant occurredAt) {}
public record HouseholdSettingsChangedEvent(UUID householdId, UUID settingsId, Set<String> changedFieldPaths, UUID traceId, Instant occurredAt) {}
public record HouseholdRoleChangedEvent   (UUID householdId, UUID memberId, UUID userId, HouseholdRole previousRole, HouseholdRole newRole, UUID traceId, Instant occurredAt) {}
```

Published via `ApplicationEventPublisher` after the relevant write transaction; listeners use `@TransactionalEventListener(phase = AFTER_COMMIT)`. The planner is the primary downstream — settings changes may invalidate the active plan's slot configuration; member events change the eater set for shared slots. The planner's response (re-opt suggestion) is its concern.

### Consumed

None at v1.

---

## Business Logic Flows

### Flow 1: Create household

`POST /api/v1/households` → `createHousehold(creatorUserId, request)`. `@Transactional`. In one tx: reject if creator already in any household (409); insert `Household`; insert primary `HouseholdMember` (`priority = 100`); insert default `HouseholdSettings` — built-in slot kinds with `shared = true`, `headcount = 1`, time budgets cribbed from [meal-planner.md §Slot configuration](../design/meal-planner.md#slot-configuration). The HLD does not specify default-shared-vs-not; **choosing shared = true** (typical onboarding is "I cook for my household"). **Worth user review.** Publish `HouseholdCreatedEvent` after commit.

### Flow 2: Invite a member

`POST /api/v1/households/{id}/invites`. `@Transactional`. Authorisation: `PRIMARY` only. Generate a 16-char opaque code via `InviteCodeGenerator` (alphanumeric, secure-random); persist with `expiresAt` from the request (Jakarta `@Future`; service caps at 30 days — **worth user review**). Return `HouseholdInviteDto` **including the code** — the only response that ever surfaces it. No `HouseholdInviteCreatedEvent` at v1 (no listener cares yet).

### Flow 3: Accept invite

`POST /api/v1/households/invites/accept`. `@Transactional`. Look up by `inviteCode` — missing → 404; revoked/expired → 410; already accepted → 409. If invite specifies `issuedForUserId ≠ accepterUserId` → 403. If accepter already in any household → 409. Insert `HouseholdMember` (`role = invite.intendedRole`, `priority = 100`); stamp `acceptedAt` / `acceptedByUserId` on the invite. Publish `HouseholdMemberAddedEvent` after commit.

### Flow 4: Remove member

`DELETE /api/v1/households/{id}/members/{memberId}`. `@Transactional`. Authorisation: actor must be `PRIMARY`, OR `actorUserId == member.userId` (self-remove). 404 if not found. If only `PRIMARY` and other members exist → `LastPrimaryRemovalException` (409): promote first. If target is the only member, **delete the member row but keep the household** — empty households are preserved (the user can rejoin later or invite new members without re-creating). Delete the member row only. Publish `HouseholdMemberRemovedEvent` after commit. **Locked decision (2026-05-07).**

**Leaver's per-user data — Orphan model (locked 2026-05-07).** Per-user data (preferences, nutrition logs, feedback, journal) stays attached to the user's account. The household removes only its own member row and emits the event; downstream modules read the event but do not scrub the user's data. Rationale: data follows the user, household is a sharing context not an identity scope. Future paths kept open:

- **Transfer** — relevant when multi-household-per-user lands; same data, new household tag. Already supported in principle by Orphan since the data is not bound to a household.
- **Cleanse** — relevant if a household-admin needs to purge a former member's traces. Adds an admin flow; not needed for v1.

Listeners on `HouseholdMemberRemovedEvent` are free to no-op or perform module-specific cleanup; the household module makes no demand of them.

### Flow 5: Role escalation

`POST /api/v1/households/{id}/members/{memberId}/role`. `@Transactional`. Authorisation: `PRIMARY` only. Stale `expectedVersion` → 409. Demoting the only primary → 409 (promote another member first; multi-primary is rare but legal at v1). No dedicated audit table — transitions captured in the event payload (`previousRole` → `newRole`). Publish `HouseholdRoleChangedEvent` after commit.

### Flow 6: Update settings

`PUT /api/v1/households/{id}/settings`. `@Transactional`. Authorisation: `PRIMARY` only. Load existing (404 if missing). Stale version → 409. Validate (Jakarta + custom validators). Section-level diff: walk top-level fields and `slotDefaults` keys, write one `HouseholdSettingsAuditLog` row per changed path. Replace `document`. Publish `HouseholdSettingsChangedEvent` with the changed field paths — the planner uses these to decide whether to invalidate the active plan.

### Flow 7: Soft-preference merge for shared slots

The canonical merge. Called in-process by the planner once per shared slot per planning run. **Read-only**, `@Transactional(readOnly = true)`. `HouseholdMergeService.mergeSoftPreferencesForSlot(householdId, eaterUserIds)`:

1. Resolve eaters: null/empty → all current household members; otherwise every supplied id must be a current member (else `HouseholdMemberNotFoundException`). Look up each eater's `priority` from `HouseholdMember` (single batch query).
2. Call `PreferenceQueryService.getSoftPreferencesByUserIds(...)` — one round-trip returns each user's `SoftPreferenceBundleDto` per [preference.md](preference.md). Hard constraints deliberately not bundled — the planner calls the hard-constraint filter directly.
3. `SoftPreferenceMerger.merge(bundles, priorities)` returns `MergedSoftPreferencesDto` with these per-section rules:
   - **Taste-profile vectors** (flavour, cuisine, ingredient preference scores): mean weighted by priority.
   - **Recipe lists** (`recipesToRepeat`, `recipesToAvoid`): union, deduped; on the avoid list if **any** eater has it there (most-restrictive — safer for shared slots).
   - **Active experiments**, **free-text learned insights**: dropped (individual concerns / noise in shared-slot context). **Worth user review.**
   - **Lifestyle config**: most-restrictive — shorter eating window, lower novelty tolerance, intersection of cooking methods. Notes dropped.
4. Return `MergedSoftPreferencesDto`. Not persisted. Numerical weights tuneable later (Out of Scope) — the interface freezes; the formula evolves.

---

## Concurrency and Transactions

- **`@Transactional`** placed on all service-impl methods (never on repositories). Reads use `readOnly = true`; the merge service's calls into `PreferenceQueryService` participate in that read-only tx. Writes default REQUIRED — all household writes are top-level (no joining other modules' transactions).
- **Optimistic locking** via `@Version` on `Household`, `HouseholdMember`, `HouseholdSettings`, `HouseholdInvite`. The audit log is append-only and has none.
- **Pessimistic locking — none.** The single-primary partial unique index (V20260501130100) is sufficient for race-free promotion/demotion: two concurrent demotions both succeed only if both insert a successor first; otherwise one fails the constraint and 409s.
- **Cascades.** DB `ON DELETE CASCADE` on member, settings, audit, invite → household. JPA `cascade = ALL, orphanRemoval = true` on `Household → members` and `Household → settings`.
- **Single-flight not required.** The planner's single-flight per `(household_id, week_start_date)` is the planner's concern.

---

## Test Plan

Unit tests: `@ExtendWith(MockitoExtension.class)`. Integration tests: `*IT.java` with Testcontainers Postgres. Names follow `methodName_scenario_expected`.

### Unit

| Class | Verifies |
|---|---|
| `HouseholdServiceImplTest` | All query / update happy paths and error mappings; mocked repositories, `PreferenceQueryService`, `SoftPreferenceMerger`. |
| `SoftPreferenceMergerTest` | Pure logic. Equal-priority taste-profile mean; differing-priority case; avoid-list union; single-user degenerate; most-restrictive lifestyle merge across windows and novelty tolerance. |
| `SlotConfigurationResolverTest` | Built-in defaults plus custom slot produce expected `SlotConfigurationDto`; custom slot inherits from `backedByKind`; per-slot headcount overrides default. |
| `InviteCodeGeneratorTest` | Codes are 16 chars, alphanumeric, secure-random, distinct across 10k invocations. |
| `LastPrimaryGuardTest` | Cannot demote/remove only primary; can demote when a second primary exists; cannot remove only member without dissolving the household. |
| Mapper tests (4×) | MapStruct round-trips preserve all fields including nested document trees and derived invite status. |
| `SlotKeyValidatorTest`, `HeadcountValidatorTest` | Custom-validator coverage. |

### Integration

| Class | Verifies |
|---|---|
| `HouseholdsControllerIT` | Full MockMvc cycle: POST creates household + primary member + default settings in one tx; GET `/current` resolves; 409 when creator already in a household. `HouseholdCreatedEvent` and `HouseholdMemberAddedEvent` published exactly once after commit. |
| `HouseholdMembersControllerIT` | Add / update / change role / remove — happy paths plus 409 on stale version and 409 on last-primary removal. |
| `HouseholdSettingsControllerIT` | GET/PUT happy paths; validation rejection; 409 stale version; audit log pagination; `HouseholdSettingsChangedEvent` payload carries changed field paths. |
| `HouseholdInvitesControllerIT` | Create (code returned only at creation); list pending (code omitted); revoke; accept (200 + member + event); 409 already-in-household; 410 expired/revoked. |
| `HouseholdMergeServiceIT` | Real DB + real `PreferenceQueryService`: two-user household with distinct profiles produces expected mean-weighted document; avoid-list union verified; lifestyle merge takes more-restrictive eating window; merge does not write. |
| `SinglePrimaryConstraintIT` | Two concurrent demotions on the same primary produce exactly one success and one 409. |
| `FlywayMigrationIT` | Boots Postgres, runs all household migrations, validates schema against JPA (`ddl-auto=validate`). |
| `EventPublicationIT` | Each mutation publishes its event only after commit; a failing test-scoped listener does not roll back the underlying state. |

---

## Out of Scope

- **Email-based invites.** No email infrastructure yet — in-app codes only. The table shape supports email later as a pure delivery-layer concern.
- **Multiple households per user.** Enforced via `UNIQUE (user_id)` on `household_member`; revisit cost is one constraint drop plus a settings-UI decision on the active household.
- **Detailed admin escalation beyond primary/member.** `varchar(16)` accommodates sub-roles (viewer, kid account, guest) without migration when needed.
- **Specific weight calculation in the soft-preference merge.** Interface and approach (mean weighted by priority, most-restrictive for lifestyle) are fixed; numerics tuneable from real data.
- **Frontend / UI / API consumer concerns.** Settings UI, member list, invite-code modal — Figma phase, then frontend LLD.
- **Multi-location / per-environment splits.** Flagged in [provision-model.md](../design/provision-model.md). v1: one location per household.
- **Concurrent inventory edits across members.** Open question in [provision-model.md](../design/provision-model.md). Resolution belongs in provisions; we expose the eater list, not the conflict policy.
- **Cross-module orchestration on member changes.** What the planner / feedback / preference / nutrition do with `HouseholdMemberRemovedEvent` is each module's concern — this LLD specifies what we publish.
- **Per-eater meal scheduling overrides.** "Bob is out for dinner on Tuesday" belongs on the planner's `MealSlot.eaters` per the planner HLD; household settings are steady-state defaults only.
