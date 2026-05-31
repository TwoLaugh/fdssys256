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
│   │                                    HouseholdSettingsController, HouseholdInvitesController,
│   │                                    HouseholdMergeController, HouseholdSlotConfigurationPlannerViewController
│   ├── dto/                            records (see DTOs)
│   └── mapper/                         MapStruct mappers (see Mappers)
├── domain/
│   ├── entity/                         JPA entities (see Entities)
│   ├── repository/                     Spring Data interfaces (public; boundary fenced by ArchUnit)
│   └── service/
│       ├── HouseholdQueryService.java, HouseholdUpdateService.java, HouseholdMergeService.java
│       ├── internal/HouseholdServiceImpl.java   single impl of all three
│       └── internal/                   SoftPreferenceMerger, SlotConfigurationResolver,
│                                        InviteCodeGenerator, HouseholdSettingsDiffer
├── event/                              7 event records (see Events)
├── exception/                          module root + per-failure subclasses
├── spi/                                SoftPreferencesReader (preference SPI; Noop until preference-01c)
└── validation/                         @ValidSlotKey, @ValidHeadcount + validators (household-2)
```

The controller split mirrors the URL surface; the merge + planner-view controllers (01e/01f) were added beyond the original four. `HouseholdServiceImpl` lives in `domain.service.internal`.

---

## Database

Migrations live under `src/main/resources/db/migration/` per [technical-architecture.md §Migrations](../design/technical-architecture.md#migrations); one concern per file. Shipped filenames (reconciled — household-6; the settings-audit table is its own migration rather than bundled into the settings migration):

```
V20260601500000__household_create_household.sql
V20260601500100__household_create_household_member.sql
V20260601500200__household_create_household_settings.sql
V20260601500300__household_create_household_settings_audit.sql
V20260601500400__household_create_household_invite.sql
```

Audit columns (`created_at`, `updated_at`, `optimistic_version`) are present on every mutable table and elided below for brevity.

```sql
-- V20260601500000
CREATE TABLE household (
    id                  uuid PRIMARY KEY,
    name                varchar(128) NOT NULL,
    created_by_user_id  uuid NOT NULL
);
CREATE INDEX idx_household_created_by_user ON household (created_by_user_id);

-- V20260601500100
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

-- V20260601500200
CREATE TABLE household_settings (
    id                  uuid PRIMARY KEY,
    household_id        uuid NOT NULL UNIQUE REFERENCES household(id) ON DELETE CASCADE,
    document            jsonb NOT NULL                   -- mirrored by HouseholdSettingsDocument
);
-- V20260601500300 (separate migration — household-6)
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

-- V20260601500400
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

`mergedLifestyleConfig` is the **structurally-mergeable subset** (meal_timing windows, novelty tolerance, batch cooking flags) — free-text notes dropped, per-field rule most-restrictive. HLD specifies only the taste-profile merge — **worth user review.**

> **Shipped note (household-6).** `TasteProfileDocument` / `LifestyleConfigDocument` here are **household-local stub records** in `household.api.dto`, NOT the preference module's canonical records. The original design said this DTO re-uses the preference record shapes; in practice `PreferenceSoftPreferencesReader` (the SPI impl) projects the canonical preference records down to these smaller household-local projections (likes/dislikes/avoid scores + the mergeable lifestyle subset), and the planner's `PlanCompositionContext` consumes the household type. Functionally equivalent; rationalising the two shapes into one is a deferred follow-up.

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

Cross-module access via service interfaces only — enforced by `HouseholdBoundaryTest` (ArchUnit), not Java visibility (the interfaces are `public` so the in-module `domain.service.internal` package can inject them; the boundary test fences cross-module reach-through). The shipped finder set (reconciled — household-6):

```java
interface HouseholdRepository extends JpaRepository<Household, UUID> {
    @EntityGraph(attributePaths = {"members"})
    Optional<Household> findWithMembersById(UUID id);
}
interface HouseholdMemberRepository extends JpaRepository<HouseholdMember, UUID> {
    Optional<HouseholdMember> findByUserId(UUID userId);
    boolean existsByHouseholdIdAndRole(UUID householdId, HouseholdRole role);
    long countByHouseholdId(UUID householdId);                              // last-primary guard
    long countByHouseholdIdAndRole(UUID householdId, HouseholdRole role);   // last-primary guard
}
interface HouseholdSettingsRepository extends JpaRepository<HouseholdSettings, UUID> {
    Optional<HouseholdSettings> findByHouseholdId(UUID householdId);
}
interface HouseholdSettingsAuditLogRepository extends JpaRepository<HouseholdSettingsAuditLog, UUID> {
    Page<HouseholdSettingsAuditLog> findByHouseholdSettingsIdOrderByOccurredAtDesc(UUID id, Pageable p);
}
interface HouseholdInviteRepository extends JpaRepository<HouseholdInvite, UUID> {
    Optional<HouseholdInvite> findByInviteCode(String inviteCode);
    List<HouseholdInvite> findByHouseholdIdAndAcceptedAtIsNullAndRevokedAtIsNullOrderByCreatedAtDesc(UUID householdId);
}
```

Notes on the divergence from the original design: there is no `findWithMembersAndSettingsById` (settings are loaded separately via `HouseholdSettingsRepository.findByHouseholdId`); the member repo uses `countByHouseholdId*` for the last-primary guard rather than `findAll*` list reads; the pending-invites finder is suffixed `OrderByCreatedAtDesc` (newest-first). `@EntityGraph` keeps the common members read to one JOIN — no N+1.

---

## Service Interfaces

Per the style guide, all three module interfaces are implemented by a single `HouseholdServiceImpl`. `HouseholdMergeService` is its own interface — narrower API, narrower coupling, same pattern as the preference module's `HardConstraintFilterService`.

### `HouseholdQueryService`

Reconciled to the shipped interface (household-6): the speculative batch methods (`getByIds`, `listMembersByHouseholdIds`, `getSettingsByHouseholdIds`, `getMember`, `getMembershipForUser`, `listMembers`) were never built — members ride along on `HouseholdDto.members`, so a separate `listMembers` read was unnecessary. Settings/audit/slot-config reads carry a `callerUserId` for the member-only authorisation gate (non-members get empty/404, no existence leak). The planner-view reader (01f) lives here.

```java
public interface HouseholdQueryService {
    Optional<HouseholdDto> getById(UUID householdId);                 // members eager
    Optional<HouseholdDto> getByUserId(UUID userId);                  // v1: at most one

    Optional<HouseholdSettingsDto> getSettings(UUID householdId, UUID callerUserId);
    Page<HouseholdSettingsAuditEntryDto> getSettingsAuditLog(UUID householdId, UUID callerUserId, Pageable pageable);
    SlotConfigurationDto getSlotConfiguration(UUID householdId, UUID callerUserId);
    SlotConfigurationPlannerViewDto getSlotConfigurationPlannerView(UUID householdId);   // 01f planner-facing

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

All endpoints under `/api/v1/...`. `actorUserId` is resolved server-side from the auth context via `CurrentUserResolver` per [technical-architecture.md §REST API](../design/technical-architecture.md#rest-api-with-json) — never in the URL. All controllers carry `@Tag(name = "Households")` (single OpenAPI tag for the whole module).

**Shipped URL scheme (reconciled — household-5/6).** Member, invite, merge and planner-view endpoints are rooted at `/households/current/...` (the caller's own household is resolved from the session, so no `{householdId}` in the path); settings + slot-configuration are rooted at `/households/{householdId}/...`. Invite accept is `/invites/accept` (a bearer accepting a code does not yet have a "current household"). This is the surface the OpenAPI spec (`openapi/openapi.yaml` + `openapi/paths/household.yaml`) declares and the `*FlowIT` swagger-request-validator tests pin; the table below matches both. (The original design used `/households/{id}/...` uniformly; the `/current/...` convention shipped instead and is the contract of record.)

| Method | Path | Body → Response | Controller |
|---|---|---|---|
| POST   | `/households` | `CreateHouseholdRequest` → `HouseholdDto` (201) | `HouseholdsController` |
| GET    | `/households/current` | → `HouseholdDto` (200/404) | `HouseholdsController` |
| GET    | `/households/{householdId}/settings` | → `HouseholdSettingsDto` (200/404) | `HouseholdSettingsController` |
| PUT    | `/households/{householdId}/settings` | `UpdateHouseholdSettingsRequest` → `HouseholdSettingsDto` (200) | `HouseholdSettingsController` |
| GET    | `/households/{householdId}/settings/audit-log?page=&size=` | → `Page<HouseholdSettingsAuditEntryDto>` | `HouseholdSettingsController` |
| GET    | `/households/{householdId}/slot-configuration` | → `SlotConfigurationDto` (200/404) | `HouseholdSettingsController` |
| GET    | `/households/current/invites` | → `List<HouseholdInviteDto>` (codes redacted) | `HouseholdInvitesController` |
| POST   | `/households/current/invites` | `CreateInviteRequest` → `HouseholdInviteDto` (201, code surfaced) | `HouseholdInvitesController` |
| DELETE | `/households/current/invites/{inviteId}` | → 204 | `HouseholdInvitesController` |
| POST   | `/invites/accept` | `AcceptInviteRequest` → `HouseholdMemberDto` (200/404/409/410) | `HouseholdInvitesController` |
| POST   | `/households/current/members` | `AddMemberRequest` → `HouseholdMemberDto` (201) | `HouseholdMembersController` |
| PATCH  | `/households/current/members/{memberId}` | `UpdateMemberRequest` → `HouseholdMemberDto` (200) | `HouseholdMembersController` |
| DELETE | `/households/current/members/{memberId}` | → 204 | `HouseholdMembersController` |
| POST   | `/households/current/members/{memberId}/role` | `ChangeRoleRequest` → `HouseholdMemberDto` (200) | `HouseholdMembersController` |
| POST   | `/households/current/merge` | `MergeSoftPreferencesRequest` → `MergedSoftPreferencesDto` (200) | `HouseholdMergeController` |
| GET    | `/households/current/slot-configuration/planner-view` | → `SlotConfigurationPlannerViewDto` (200/404) | `HouseholdSlotConfigurationPlannerViewController` |

All paths prefixed `/api/v1`. Mutations may also return 400 (validation), 403 (insufficient role), 409 (stale version, single-household / last-primary conflicts). The `/households/current/merge` endpoint validates that every supplied `eaterUserId` is in the caller's household (403 otherwise) and 422s an empty household. Note: the design originally said `HouseholdMergeService` is "not exposed via REST"; a thin `HouseholdMergeController` shipped anyway as a planner-reachable / debug seam (it is also invoked in-process). The planner-view endpoint is the planner-facing flattened slot view (01f).

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

Standard Jakarta annotations on request records (`@NotNull`, `@NotBlank`, `@Size`, `@Min`/`@Max`, `@Future`, `@Valid`). Custom validators in `household.validation/` (implemented — household-2), applied to the `HouseholdSettingsDocument` record components and reached via the `@Valid` cascade from `UpdateHouseholdSettingsRequest`:

- **`@ValidSlotKey`** (`SlotKeyValidator`) — kebab-case (`^[a-z0-9-]+$`), 1–48 chars, no collision with a built-in `SlotKind` name (breakfast/lunch/dinner/snack/custom). Applied to `CustomSlotDefinition.key`. A null key is left to the schema's required-ness; the collision/format rules are the rule this validator adds (a kebab-case key like `dinner` passes the OpenAPI pattern but is rejected here).
- **`@ValidHeadcount`** (`HeadcountValidator`) — null accepted (optional, falls back at resolve time); otherwise 1–16 (matches the planner's per-eater sanity check and the OpenAPI bound). Applied to `SlotDefault.headcount`, `CustomSlotDefinition.headcount`, and `HouseholdSettingsDocument.defaultHeadcount`.

Bounds are kept identical to the OpenAPI schema so a contract-valid request is never rejected by bean-validation; the validators add the same enforcement on the in-process service path (where there is no OpenAPI gate) plus the slot-key collision rule the schema pattern alone cannot express.

Cross-field rules enforced **service-layer** (need DB state): removing the last primary → `LastPrimaryRemovalException` (409); adding a user already in a household → `UserAlreadyInHouseholdException` (409); demoting yourself when no other primary exists → reject (promote someone else first). Detailed admin escalation beyond primary/member is deferred.

---

## Events

### Published

The technical-architecture catalogue lists a single `HouseholdConfigChangedEvent`. The LLD splits it into per-concern events — membership and settings churn have different planner consequences. **Worth user review.** All implement `core.events.ScopeChangedEvent` with `scopeKind = "household"`, `scopeId = householdId`. The shipped set (reconciled — household-6) is **seven**: the original five plus the two invite events:

```java
public record HouseholdCreatedEvent        (UUID householdId, UUID createdByUserId, UUID traceId, Instant occurredAt) {}
public record HouseholdMemberAddedEvent    (UUID householdId, UUID memberId, UUID userId, HouseholdRole role, UUID traceId, Instant occurredAt) {}
public record HouseholdMemberRemovedEvent  (UUID householdId, UUID memberId, UUID userId, HouseholdRole roleAtRemoval, UUID traceId, Instant occurredAt) {}
public record HouseholdSettingsChangedEvent(UUID householdId, UUID settingsId, Set<String> changedFieldPaths, UUID traceId, Instant occurredAt) {}
public record HouseholdRoleChangedEvent    (UUID householdId, UUID memberId, UUID userId, HouseholdRole previousRole, HouseholdRole newRole, UUID traceId, Instant occurredAt) {}
public record HouseholdInviteCreatedEvent  (UUID householdId, UUID inviteId, UUID issuedByUserId, UUID issuedForUserId, HouseholdRole intendedRole, Instant expiresAt, UUID traceId, Instant occurredAt) {}
public record HouseholdInviteAcceptedEvent (UUID householdId, UUID inviteId, UUID acceptedByUserId, HouseholdRole grantedRole, UUID traceId, Instant occurredAt) {}
```

Published via `ApplicationEventPublisher` after the relevant write transaction; listeners use `@TransactionalEventListener(phase = AFTER_COMMIT)`. The planner is the primary downstream — settings changes may invalidate the active plan's slot configuration; member events change the eater set for shared slots.

**Member-event emission (household-4).** Both the direct-add admin path AND the invite-accept path emit `HouseholdMemberAddedEvent`; the accept path ALSO emits `HouseholdInviteAcceptedEvent` for invite-flow-specific consumers. (The earlier "accept emits only the invite event" decision was reversed: the most common onboarding path must raise the member-added event so the planner reacts to the new eater — see household-7.)

**Planner reaction (household-7).** `PlannerEventListener` consumes `HouseholdSettingsChangedEvent` (gated by `HouseholdMaterialityFilter` on the changed field paths) AND the three membership/role events (`HouseholdMemberAddedEvent` / `HouseholdMemberRemovedEvent` / `HouseholdRoleChangedEvent`), routing each to the mid-week re-opt path under `ReoptTriggerKind.HOUSEHOLD_SETTINGS`. A membership/role change is always treated as material (it changes the shared-slot eater set), so it bypasses the field-path materiality filter; the filter no longer carries `members*` prefixes (those paths never appear on a settings event).

### Consumed

None at v1.

---

## Business Logic Flows

### Flow 1: Create household

`POST /api/v1/households` → `createHousehold(creatorUserId, request)`. `@Transactional`. In one tx: reject if creator already in any household (409); insert `Household`; insert primary `HouseholdMember` (`priority = 100`); insert default `HouseholdSettings` — built-in slot kinds with `shared = true`, `headcount = 1`, and per-kind time budgets cribbed from [meal-planner.md §Slot configuration](../design/meal-planner.md#slot-configuration): **breakfast 15, lunch 20, dinner 45, snack 5** (household-3; previously a flat 30 for all kinds). The HLD does not specify default-shared-vs-not; **choosing shared = true** (typical onboarding is "I cook for my household"). **Worth user review.** Publish `HouseholdCreatedEvent` after commit.

### Flow 2: Invite a member

`POST /api/v1/households/current/invites` (shipped path — household-5). `@Transactional`. Authorisation: `PRIMARY` only. Generate a 16-char opaque code via `InviteCodeGenerator` (alphanumeric, secure-random); persist with `expiresAt` from the request (Jakarta `@Future`; service caps at 30 days — **worth user review**), retrying on code collision. Return `HouseholdInviteDto` **including the code** — the only response that ever surfaces it. Publish `HouseholdInviteCreatedEvent` after commit (shipped — household-6; the original "no event at v1" note is stale).

### Flow 3: Accept invite

`POST /api/v1/invites/accept` (shipped path — household-5). `@Transactional`. Look up by `inviteCode` — missing → 404; revoked/expired → 410; already accepted → 409. If invite specifies `issuedForUserId ≠ accepterUserId` → 403. If accepter already in any household → 409. Insert `HouseholdMember` (`role = invite.intendedRole`, `priority = 100`); stamp `acceptedAt` / `acceptedByUserId` on the invite. After commit, publish **both** `HouseholdMemberAddedEvent` (household-4 — so the planner reacts to the new eater) **and** `HouseholdInviteAcceptedEvent` (for invite-flow consumers).

### Flow 4: Remove member

`DELETE /api/v1/households/current/members/{memberId}` (shipped path — household-5). `@Transactional`. Authorisation: actor must be `PRIMARY`, OR `actorUserId == member.userId` (self-remove). 404 if not found. If only `PRIMARY` and other members exist → `LastPrimaryRemovalException` (409): promote first. If target is the only member, **delete the member row but keep the household** — empty households are preserved (the user can rejoin later or invite new members without re-creating). Delete the member row only. Publish `HouseholdMemberRemovedEvent` after commit. **Locked decision (2026-05-07).**

**Leaver's per-user data — Orphan model (locked 2026-05-07).** Per-user data (preferences, nutrition logs, feedback, journal) stays attached to the user's account. The household removes only its own member row and emits the event; downstream modules read the event but do not scrub the user's data. Rationale: data follows the user, household is a sharing context not an identity scope. Future paths kept open:

- **Transfer** — relevant when multi-household-per-user lands; same data, new household tag. Already supported in principle by Orphan since the data is not bound to a household.
- **Cleanse** — relevant if a household-admin needs to purge a former member's traces. Adds an admin flow; not needed for v1.

Listeners on `HouseholdMemberRemovedEvent` are free to no-op or perform module-specific cleanup; the household module makes no demand of them.

### Flow 5: Role escalation

`POST /api/v1/households/current/members/{memberId}/role` (shipped path — household-5). `@Transactional`. Authorisation: `PRIMARY` only. Stale `expectedVersion` → 409. Demoting the only primary → 409 (promote another member first; multi-primary is rare but legal at v1). No dedicated audit table — transitions captured in the event payload (`previousRole` → `newRole`). Publish `HouseholdRoleChangedEvent` after commit (the planner consumes it — household-7).

### Flow 6: Update settings

`PUT /api/v1/households/{id}/settings`. `@Transactional`. Authorisation: `PRIMARY` only. Load existing (404 if missing). Stale version → 409. Validate (Jakarta + custom validators). Section-level diff: walk top-level fields and `slotDefaults` keys, write one `HouseholdSettingsAuditLog` row per changed path. Replace `document`. Publish `HouseholdSettingsChangedEvent` with the changed field paths — the planner uses these to decide whether to invalidate the active plan.

### Flow 7: Soft-preference merge for shared slots

The canonical merge. Called in-process by the planner once per shared slot per planning run. **Read-only**, `@Transactional(readOnly = true)`. `HouseholdMergeService.mergeSoftPreferencesForSlot(householdId, eaterUserIds)`:

1. Resolve eaters: null/empty → all current household members; otherwise every supplied id must be a current member (else `HouseholdMemberNotFoundException`, mapped 404). This membership check is enforced in `mergeSoftPreferencesForSlot` itself (household-1) — i.e. on the planner's in-process call path, not only at the REST seam (the `/households/current/merge` controller also pre-checks, surfacing 403 for the HTTP caller). Look up each eater's `priority` from `HouseholdMember`.
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
- **Pessimistic locking — none.** The single-primary partial unique index (`V20260601500100`) is sufficient for race-free promotion/demotion: two concurrent demotions both succeed only if both insert a successor first; otherwise one fails the constraint and 409s.
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

Shipped IT classes are named `*FlowIT` (swagger-request-validator-backed MockMvc flows) rather than `*ControllerIT`:

| Class | Verifies |
|---|---|
| `HouseholdsFlowIT` | Full MockMvc cycle: POST creates household + primary member + default settings in one tx; GET `/current` resolves; 409 when creator already in a household. `HouseholdCreatedEvent` published after commit. |
| `HouseholdMembersFlowIT` | Add / update / change role / remove — happy paths plus 409 on stale version and 409 on last-primary removal; the accept path emits BOTH `HouseholdMemberAddedEvent` and `HouseholdInviteAcceptedEvent` (household-4). |
| `HouseholdSettingsFlowIT` | GET/PUT happy paths; validation rejection (incl. `@ValidSlotKey` collision + `@ValidHeadcount` bound → 400, household-2); 409 stale version; audit log pagination; `HouseholdSettingsChangedEvent` payload carries changed field paths. |
| `HouseholdInvitesFlowIT` | Create (code returned only at creation); list pending (code omitted); revoke; accept (200 + member + event); 409 already-in-household; 410 expired/revoked. |
| `HouseholdMergeFlowIT` / `HouseholdMergeWithFakeReaderIT` | Real DB + `SoftPreferencesReader`: two-user household with distinct profiles produces expected mean-weighted document; avoid-list union verified; lifestyle merge takes more-restrictive eating window; merge does not write. |
| `HouseholdSlotConfigurationPlannerViewFlowIT` | Planner-view (01f) returns flattened slots (per-kind budgets) + priority-ordered eaters; 401/404 ladder. |
| Planner `PlannerEventListenerIT` (cross-module) | A `HouseholdMemberAddedEvent` writes a re-opt suggestion with `HOUSEHOLD_SETTINGS` trigger (household-7). |

Unit-level mutation/kill coverage lives in `HouseholdMutationKillsTest`; validator coverage in `SlotKeyValidatorTest` / `HeadcountValidatorTest`.

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
