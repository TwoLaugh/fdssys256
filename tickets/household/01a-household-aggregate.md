# Ticket: household — 01a Household Aggregate

## Summary

Implement the `household` module's foundation aggregate: `Household` (root) + `HouseholdMember` (child), repository, `HouseholdQueryService` (read-by-others contract: `getByUserId(userId)` for "current user's household"), `HouseholdUpdateService.createHousehold` (creator becomes the first PRIMARY member in one tx), the V…500000 migration, and ONE endpoint pair: `POST /api/v1/households` and `GET /api/v1/households/current`. Per [`lld/household.md`](../../lld/household.md) §Database, §Service Interfaces, §Flow 1.

**Defers to later household tickets**: `HouseholdSettings` and `HouseholdSettingsAuditLog` (01b — settings aggregate + PUT + audit), `HouseholdInvite` and accept-flow (01c — invite endpoints), `HouseholdMergeService` and soft-preference merge (01d — needs preference module), the four published events beyond `HouseholdCreatedEvent` (01b/01c land their own), member add/update/remove/role-change endpoints (01b — bulk member admin), `SlotConfigurationDto` (lives with settings → 01b).

01a unblocks any downstream module that needs to ask "what household is user X in?". That's the read-by-others contract.

This is the first household ticket. Module package is currently empty. Builds on conventions from `auth` and `preference`.

## Behavioural spec

### Aggregate shape

1. `Household` is the aggregate root. Fields: `id (UUID, application-set), name (varchar 128, not null), createdByUserId (UUID, not null), version (@Version Long), createdAt (@CreatedDate), updatedAt (@LastModifiedDate)`. Has `@OneToMany(mappedBy="household", cascade=ALL, orphanRemoval=true, fetch=LAZY) List<HouseholdMember> members` initialised to empty `ArrayList`.
2. `HouseholdMember` child entity. Fields: `id, householdId (via @ManyToOne(fetch=LAZY) Household household + @JoinColumn(name="household_id")), userId (UUID, not null), role (HouseholdRole enum: PRIMARY|MEMBER, @Enumerated(STRING)), displayName (varchar 64, nullable), priority (int, default 100), joinedAt (Instant, not null, set on insert), version (@Version Long), createdAt, updatedAt`.
3. **Database constraints (locked from LLD)**: `UNIQUE (household_id, user_id)`; `UNIQUE (user_id)` (v1 single-household-per-user); partial unique index `idx_household_member_one_primary ON household_member (household_id) WHERE role = 'primary'` (exactly one primary per household).
4. `HouseholdRole` enum local to the module: `PRIMARY`, `MEMBER`. Stored as the lower-case strings `'primary'` / `'member'` in the DB to match the partial-index predicate. Use `@Enumerated(STRING)` and override values via the LLD shape — easiest: declare the enum with `name()` matching the DB strings (`primary`, `member`) — **OR** use `@Convert(converter = ...)` if the agent prefers uppercase Java names. **Either is fine; pick one and be consistent across the column and the partial-index value.** Recommended: lowercase enum constants (`primary`, `member`) so JPA's default name-based mapping matches the partial index without a converter.

### `createHousehold` flow

5. `POST /api/v1/households` with `CreateHouseholdRequest { @NotBlank @Size(max=128) String name }`. Authenticated (cookie via `SessionAuthenticationFilter`). Server resolves `creatorUserId` via `CurrentUserResolver` — never accepted from the request body or query.
6. Single `@Transactional` write: reject with 409 `UserAlreadyInHouseholdException` if `householdMemberRepository.findByUserId(creatorUserId).isPresent()` (LLD Flow 1 + the `UNIQUE (user_id)` constraint).
7. Insert `Household` row with the provided name and `createdByUserId = creatorUserId`. Insert one `HouseholdMember` row in the same tx: `userId = creatorUserId`, `role = PRIMARY`, `priority = 100`, `joinedAt = Instant.now()`, `displayName = null`.
8. Return `HouseholdDto` (201). Set `Location: /api/v1/households/{id}` header.
9. Publish `HouseholdCreatedEvent(householdId, createdByUserId, traceId, occurredAt)` `AFTER_COMMIT`. `traceId` from MDC if present, else `UUID.randomUUID()`. No listeners in 01a — just emitted for downstream consumers later.

### `getByUserId` (read-by-others)

10. `GET /api/v1/households/current` returns the calling user's `HouseholdDto` (200) or 404 `HouseholdNotFoundException` if the user has no `HouseholdMember` row. Authenticated.
11. The query path: `householdMemberRepository.findByUserId(userId)` → if present, fetch `Household` by id with members eager-loaded via `@EntityGraph(attributePaths = {"members"}) findWithMembersById(UUID id)`. Map to DTO; embed `List<HouseholdMemberDto>`.
12. `HouseholdQueryService.getByUserId(UUID userId): Optional<HouseholdDto>` is the cross-module read-by-others method — used by future `planner`, `provisions`, `nutrition` callers. **No HTTP exposure beyond `/current`.**

### Cross-module facade + boundary

13. `HouseholdModule.java` facade re-exports `HouseholdQueryService` and `HouseholdUpdateService` (interfaces only — no impl exposed across the module boundary).
14. Repositories (`HouseholdRepository`, `HouseholdMemberRepository`) are package-private (no `public` modifier on the interface) per the style guide. Cross-module access is via service interfaces only — **enforced by a new `HouseholdBoundaryTest`** at `src/test/java/com/example/mealprep/household/HouseholdBoundaryTest.java` mirroring `CoreBoundaryTest` / `AuthBoundaryTest` (rule: classes outside `com.example.mealprep.household..` must not depend on `com.example.mealprep.household..domain.repository..`).

### Errors

15. New module-root `HouseholdException extends RuntimeException`; subclasses `HouseholdNotFoundException` (404) and `UserAlreadyInHouseholdException` (409). Both extend `HouseholdException`.
16. New `HouseholdExceptionHandler` `@RestControllerAdvice` at `com.example.mealprep.household.api`, **annotated `@Order(Ordered.HIGHEST_PRECEDENCE)`** (mirrors `AuthExceptionHandler` — see "Gotchas" below). Maps:
    - `HouseholdNotFoundException` → 404 ProblemDetail, type `https://mealprep.example.com/problems/household-not-found`.
    - `UserAlreadyInHouseholdException` → 409 ProblemDetail, type `.../user-already-in-household`.
17. `OptimisticLockingFailureException` continues to be handled by `GlobalExceptionHandler` — do NOT add a duplicate handler.

## Database

```
src/main/resources/db/migration/V20260601500000__household_create_household.sql        new
src/main/resources/db/migration/V20260601500100__household_create_household_member.sql new
```

Schema mirrors [`lld/household.md` lines 60-83](../../lld/household.md). Two migrations (one concern per file per style guide). `V20260601500000` creates `household`. `V20260601500100` creates `household_member` with the three uniqueness constraints (composite, single-household, single-primary partial index).

**Excluded from 01a** (deferred to later sub-tickets): `household_settings`, `household_settings_audit`, `household_invite` and their indexes. Do NOT create those tables — leaving them out keeps 01a's migration footprint small.

Timestamps after preference (V20260601300000+). Use `V20260601500000` and `V20260601500100`.

## OpenAPI updates

Per the just-shipped refactor (commit `1a09d04`), each module owns two new YAML files plus exactly TWO ref lines added to the entry `openapi.yaml`. **Do NOT touch any other module's path/schema YAML.**

### New `src/main/resources/openapi/paths/household.yaml`

```yaml
households:
  post:
    tags: [Households]
    operationId: createHousehold
    summary: Create a new household; the calling user becomes its primary member.
    security:
      - cookieAuth: []
    requestBody:
      required: true
      content:
        application/json:
          schema: { $ref: '../schemas/household.yaml#/CreateHouseholdRequest' }
    responses:
      '201':
        description: Household created.
        headers:
          Location:
            schema: { type: string, format: uri }
        content:
          application/json:
            schema: { $ref: '../schemas/household.yaml#/HouseholdDto' }
      '400':
        description: Validation error.
        content:
          application/problem+json:
            schema: { $ref: '../schemas/common.yaml#/ProblemDetail' }
      '401':
        description: Unauthenticated.
        content:
          application/problem+json:
            schema: { $ref: '../schemas/common.yaml#/ProblemDetail' }
      '409':
        description: User already a member of a household.
        content:
          application/problem+json:
            schema: { $ref: '../schemas/common.yaml#/ProblemDetail' }
currentHousehold:
  get:
    tags: [Households]
    operationId: getCurrentHousehold
    summary: Return the calling user's household, or 404 if none.
    security:
      - cookieAuth: []
    responses:
      '200':
        description: The calling user's household.
        content:
          application/json:
            schema: { $ref: '../schemas/household.yaml#/HouseholdDto' }
      '401':
        description: Unauthenticated.
        content:
          application/problem+json:
            schema: { $ref: '../schemas/common.yaml#/ProblemDetail' }
      '404':
        description: User is not in any household.
        content:
          application/problem+json:
            schema: { $ref: '../schemas/common.yaml#/ProblemDetail' }
```

### New `src/main/resources/openapi/schemas/household.yaml`

```yaml
HouseholdRole:
  type: string
  enum: [primary, member]
HouseholdMemberDto:
  type: object
  required: [id, householdId, userId, role, priority, joinedAt, version]
  properties:
    id: { type: string, format: uuid }
    householdId: { type: string, format: uuid }
    userId: { type: string, format: uuid }
    role: { $ref: '#/HouseholdRole' }
    displayName: { type: string, maxLength: 64, nullable: true }
    priority: { type: integer, minimum: 0, maximum: 1000 }
    joinedAt: { type: string, format: date-time }
    version: { type: integer, format: int64 }
HouseholdDto:
  type: object
  required: [id, name, createdByUserId, members, createdAt, version]
  properties:
    id: { type: string, format: uuid }
    name: { type: string, maxLength: 128 }
    createdByUserId: { type: string, format: uuid }
    members:
      type: array
      items: { $ref: '#/HouseholdMemberDto' }
    createdAt: { type: string, format: date-time }
    version: { type: integer, format: int64 }
CreateHouseholdRequest:
  type: object
  required: [name]
  properties:
    name: { type: string, minLength: 1, maxLength: 128 }
```

### Two-line edit to `src/main/resources/openapi/openapi.yaml`

Append under `paths:`:

```yaml
  /api/v1/households:
    $ref: 'paths/household.yaml#/households'
  /api/v1/households/current:
    $ref: 'paths/household.yaml#/currentHousehold'
```

Append under `components.schemas:` (per the refactor's 1-line-per-DTO convention):

```yaml
    HouseholdRole: { $ref: 'schemas/household.yaml#/HouseholdRole' }
    HouseholdDto: { $ref: 'schemas/household.yaml#/HouseholdDto' }
    HouseholdMemberDto: { $ref: 'schemas/household.yaml#/HouseholdMemberDto' }
    CreateHouseholdRequest: { $ref: 'schemas/household.yaml#/CreateHouseholdRequest' }
```

## Verbatim shape snippets

### Entity — copy this shape

The pattern below mirrors `preference/domain/entity/HardConstraints.java` (already in the repo). For `HouseholdMember`, drop the JSONB import — there are none.

```java
@Entity
@Table(name = "household")
@Getter @Setter @Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class Household {

  @Id
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @Column(name = "name", nullable = false, length = 128)
  private String name;

  @Column(name = "created_by_user_id", nullable = false, updatable = false)
  private UUID createdByUserId;

  @OneToMany(mappedBy = "household", cascade = CascadeType.ALL,
             orphanRemoval = true, fetch = FetchType.LAZY)
  @Builder.Default
  private List<HouseholdMember> members = new ArrayList<>();

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @CreationTimestamp
  @Column(name = "created_at", updatable = false, nullable = false)
  private Instant createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  /** Replace all members in-place; preserves the parent's collection identity for Hibernate. */
  public void replaceMembers(List<HouseholdMember> replacements) {
    this.members.clear();
    if (replacements != null) {
      for (HouseholdMember child : replacements) {
        child.setHousehold(this);
        this.members.add(child);
      }
    }
  }
}
```

### Repository — package-private

```java
// no `public` modifier — package-private cross-module isolation
interface HouseholdRepository extends JpaRepository<Household, UUID> {
  @EntityGraph(attributePaths = {"members"})
  Optional<Household> findWithMembersById(UUID id);
}

interface HouseholdMemberRepository extends JpaRepository<HouseholdMember, UUID> {
  Optional<HouseholdMember> findByUserId(UUID userId);
  boolean existsByHouseholdIdAndRole(UUID householdId, HouseholdRole role);
}
```

### Service interfaces

```java
public interface HouseholdQueryService {
  Optional<HouseholdDto> getById(UUID householdId);
  Optional<HouseholdDto> getByUserId(UUID userId);   // v1: at most one — read-by-others contract
}

public interface HouseholdUpdateService {
  HouseholdDto createHousehold(UUID creatorUserId, CreateHouseholdRequest request);
}
```

The full LLD interfaces (LLD §Service Interfaces lines 266-304) include settings, invites, members CRUD, role change, merge — **all out of scope for 01a**. Only the two methods above land here.

## Edge-case checklist

- [ ] `POST /households` with no cookie → 401 (filter chain rejects before controller)
- [ ] `POST /households` with valid cookie + valid body for a fresh user → 201, member row exists with `role = PRIMARY`, `priority = 100`, `joinedAt` set
- [ ] `POST /households` for a user already in a household → 409 `user-already-in-household` ProblemDetail (no second household row, no second member row)
- [ ] `POST /households` validation: empty name → 400; name > 128 chars → 400
- [ ] `GET /households/current` for a member → 200 with `HouseholdDto` carrying the single-member collection
- [ ] `GET /households/current` for a user with no membership → 404 `household-not-found`
- [ ] `GET /households/current` without cookie → 401
- [ ] DB partial unique index actually rejects a second PRIMARY for the same household (covered by an IT that bypasses the service and inserts directly via JdbcTemplate)
- [ ] `HouseholdCreatedEvent` published exactly once after commit (verified via test-scoped `@TransactionalEventListener` capturing the event)
- [ ] OpenAPI request/response schemas match (swagger-request-validator filter active in IT)
- [ ] `HouseholdBoundaryTest` passes — outside-module classes cannot import `household.domain.repository`
- [ ] No raw `userId` accepted from request body or query string on either endpoint (always resolved server-side)

## Files this ticket touches

```
NEW   src/main/resources/db/migration/V20260601500000__household_create_household.sql
NEW   src/main/resources/db/migration/V20260601500100__household_create_household_member.sql
NEW   src/main/java/com/example/mealprep/household/HouseholdModule.java
NEW   src/main/java/com/example/mealprep/household/api/controller/HouseholdsController.java
NEW   src/main/java/com/example/mealprep/household/api/HouseholdExceptionHandler.java
NEW   src/main/java/com/example/mealprep/household/api/dto/HouseholdDto.java
NEW   src/main/java/com/example/mealprep/household/api/dto/HouseholdMemberDto.java
NEW   src/main/java/com/example/mealprep/household/api/dto/CreateHouseholdRequest.java
NEW   src/main/java/com/example/mealprep/household/api/mapper/HouseholdMapper.java
NEW   src/main/java/com/example/mealprep/household/api/mapper/HouseholdMemberMapper.java
NEW   src/main/java/com/example/mealprep/household/domain/entity/Household.java
NEW   src/main/java/com/example/mealprep/household/domain/entity/HouseholdMember.java
NEW   src/main/java/com/example/mealprep/household/domain/entity/HouseholdRole.java
NEW   src/main/java/com/example/mealprep/household/domain/repository/HouseholdRepository.java
NEW   src/main/java/com/example/mealprep/household/domain/repository/HouseholdMemberRepository.java
NEW   src/main/java/com/example/mealprep/household/domain/service/HouseholdQueryService.java
NEW   src/main/java/com/example/mealprep/household/domain/service/HouseholdUpdateService.java
NEW   src/main/java/com/example/mealprep/household/domain/service/internal/HouseholdServiceImpl.java
NEW   src/main/java/com/example/mealprep/household/event/HouseholdCreatedEvent.java
NEW   src/main/java/com/example/mealprep/household/exception/HouseholdException.java
NEW   src/main/java/com/example/mealprep/household/exception/HouseholdNotFoundException.java
NEW   src/main/java/com/example/mealprep/household/exception/UserAlreadyInHouseholdException.java

NEW   src/main/resources/openapi/paths/household.yaml
NEW   src/main/resources/openapi/schemas/household.yaml
MOD   src/main/resources/openapi/openapi.yaml                                              (2 lines added under paths:; 4 lines added under components.schemas:)

NEW   src/test/java/com/example/mealprep/household/HouseholdServiceImplTest.java
NEW   src/test/java/com/example/mealprep/household/HouseholdsFlowIT.java
NEW   src/test/java/com/example/mealprep/household/HouseholdBoundaryTest.java
NEW   src/test/java/com/example/mealprep/household/testdata/HouseholdTestData.java
```

**Files this ticket does NOT modify** (cross-cutting; sibling tickets running in parallel must not collide):
- `src/main/java/com/example/mealprep/config/GlobalExceptionHandler.java` — module exceptions go in the new `household.api.HouseholdExceptionHandler`, not here.
- `src/test/java/com/example/mealprep/archunit/ModuleBoundaryTest.java` — module boundary rule goes in the new `HouseholdBoundaryTest`, not here.
- Other modules' `paths/*.yaml`, `schemas/*.yaml`, `<module>ExceptionHandler.java`, `<module>BoundaryTest.java`.

## Dependencies

- **Hard dependency**: `auth-01a` (merged) — uses `CurrentUserResolver` and `SessionAuthenticationFilter`.
- **Hard dependency**: `refactor-01-split-merge-zones` (merged) — relies on the per-module YAML / advice / boundary-test layout.
- **Sibling tickets running in parallel** (Wave 2 round 1, won't collide if each respects per-module file scope): `nutrition-01a`, `provisions-01a`, `recipe-01a`. Confirm none of those touch any household file or any of the cross-cutting files listed above.

## Acceptance / DoD

- [ ] `./mvnw -Dspotless.check.skip=true verify` passes locally on the agent's worktree
- [ ] `./mvnw spotless:apply` then `./mvnw spotless:check` clean
- [ ] CI green on the PR (build + spotless + OpenAPI lint + ArchUnit gate)
- [ ] All edge-case items above ticked
- [ ] `HouseholdExceptionHandler` annotated `@Order(Ordered.HIGHEST_PRECEDENCE)` (gotcha: without it, `GlobalExceptionHandler`'s catch-all `@ExceptionHandler(Exception.class)` swallows module-specific 4xx into 500s)
- [ ] No regression on existing tests
- [ ] No ArchUnit violations

## What's NOT in scope

- `HouseholdSettings`, `HouseholdSettingsAuditLog`, `HouseholdSettingsDocument`, settings PUT, audit-log GET, `SlotConfigurationDto` → **household-01b**
- `HouseholdInvite`, invite create/list/revoke/accept endpoints, `InviteCodeGenerator` → **household-01c**
- `HouseholdMergeService`, `MergedSoftPreferencesDto`, `SoftPreferenceMerger` → **household-01d** (depends on `preference.QueryService.getSoftPreferencesByUserIds` which 01a doesn't need)
- Member CRUD endpoints (add/update/role-change/remove), `LastPrimaryRemovalException` enforcement → **household-01b** (the schema's partial unique index is here so 01b can layer logic on)
- All four other published events (`HouseholdMemberAddedEvent`, `HouseholdMemberRemovedEvent`, `HouseholdSettingsChangedEvent`, `HouseholdRoleChangedEvent`)
- `@ValidSlotKey`, `@ValidHeadcount` validators (used only by settings)
- Email-based invites (Out of Scope per LLD)

Squash-merge with: `feat(household): 01a — household aggregate + create/getCurrent endpoints + boundary test`
