# Ticket: household — 01d Member Administration (add / update / remove / role-change)

## Summary

Layer the **member-admin CRUD** flow on top of the 01a/01b/01c household aggregate: the four endpoints `POST /api/v1/households/current/members` (PRIMARY-only direct add — bypasses the invite flow for trusted callers), `PATCH /api/v1/households/current/members/{memberId}` (PRIMARY-only update — `displayName`, `priority`), `DELETE /api/v1/households/current/members/{memberId}` (PRIMARY-only remove OR self-remove), `POST /api/v1/households/current/members/{memberId}/role` (PRIMARY-only role change). New `AddMemberRequest`, `UpdateMemberRequest`, `ChangeRoleRequest` DTOs. The `LastPrimaryRemovalException` (409) enforced consistently across remove + role-change paths. Three new events `HouseholdMemberAddedEvent`, `HouseholdMemberRemovedEvent`, `HouseholdRoleChangedEvent` published `AFTER_COMMIT` per [`lld/household.md`](../../lld/household.md) §Events lines 387-392. Per [LLD §Service Interfaces (`addMember`, `updateMember`, `removeMember`, `changeRole`) lines 296-299](../../lld/household.md), §Flow 4 (Remove member) lines 416-425, §Flow 5 (Role escalation) lines 427-429, §`HouseholdMemberDto` (existing from 01a), §Validation lines 369-376 ("removing the last primary → `LastPrimaryRemovalException`").

**LLD divergence note** — **why 01d instead of `HouseholdMergeService`**: the original 01d slot was earmarked for `HouseholdMergeService` + `MergedSoftPreferencesDto` + `SoftPreferenceMerger` (LLD §§192-207, §309-326, §Flow 7 lines 435-446). **The merge service depends on `PreferenceQueryService.getSoftPreferencesByUserIds(List<UUID>)`** (LLD line 326, line 440), which in turn depends on `SoftPreferenceBundleDto`, `TasteProfileDocument`, `LifestyleConfigDocument` — none of which exist in the preference module yet (only hard-constraints from preference-01a and the filter from preference-01b have shipped). The merge service is **deferred to household-01e**, where it lands alongside (or after) the preference module's soft-preferences ticket (`preference-01c`). 01d picks up the next-natural item from 01c's deferral list (line 8-12 of `tickets/household/01c-household-invites.md`: "Member CRUD endpoints (`addMember`, `updateMember`, `removeMember`, `changeRole`), `LastPrimaryRemovalException` enforcement, `HouseholdMemberAddedEvent` / `HouseholdMemberRemovedEvent` / `HouseholdRoleChangedEvent` for the dedicated member-admin paths"). **Worth user review** — alternative is to ship the merge service with `getSoftPreferencesByUserIds` returning `List.of()` (stub) and an empty `MergedSoftPreferencesDto`; rejected because that's three new DTO record-trees (`TasteProfileDocument`, `LifestyleConfigDocument`, `SoftPreferenceBundleDto`) just to back an empty stub, and the soft-preferences shape isn't fully nailed down in the LLD (line 207, line 444 both flag "worth user review" for the structurally-mergeable subset).

**Defers** (still out of scope after 01d):

- `HouseholdMergeService`, `MergedSoftPreferencesDto`, `SoftPreferenceMerger`, `getSoftPreferencesByUserIds` → **household-01e** (blocked on preference soft-prefs ticket).
- `SlotConfigurationDto` planner-friendly view (LLD §211-217) → **household-01f** (depends on planner module).
- Multi-household-per-user (LLD §Out of Scope) — `UNIQUE (user_id)` partial index on `household_member` stays.
- Leaver data scrub / transfer (LLD lines 422-425 — "Orphan model locked") — no cleanup performed; per-user data follows the user.
- Email-based invites, invite-link landing page — owned by 01c's deferral list.

01d unblocks the **household-onboarding edit loop**: PRIMARY can promote a member to PRIMARY (e.g., partner takes over after relocation), demote former primaries, remove inactive members, and adjust per-member `priority` weights used by the merge service (when 01e lands). It also unblocks **self-removal** — a member can leave a household without primary intervention, which is the most common single-flow failure mode in v1 (today they'd be stuck).

**LLD divergence note** — **endpoint paths**: the LLD §REST table lines 339-342 specifies member endpoints under `/households/{id}/members/{memberId}` (path-parameterised by household id). Since v1 enforces single-household-per-user (LLD §Out of Scope) and 01a/01b/01c already expose `/households/current` for the calling user's household, **01d uses `/households/current/members/{memberId}` for update/remove/role-change** instead of `/households/{id}/members/{memberId}` and **`/households/current/members` for add**. The service-interface signatures keep `(householdId, actorUserId, request)` per the LLD so multi-household path-parameterised forms can be added without breaking the service contract. **Mirrors 01c's same divergence — keep symmetric.**

**LLD divergence note** — **`addMember` flow**: LLD §Service Interfaces line 296 specifies `addMember(UUID householdId, UUID actorUserId, AddMemberRequest)` but the LLD's §Flows section does NOT enumerate a direct-add flow (Flow 3 is invite-driven). 01d **adds a direct-add endpoint** because:
- The LLD's `addMember` is in the interface; not surfacing it via REST leaves the method unreachable from outside the JVM.
- Trusted-caller scenarios (admin-tool, future household-merging migration) need a direct-add path that doesn't require generating + accepting an invite.
- The accept-invite flow from 01c reuses the same insert helper internally; 01d formalises the helper as `addMemberInternal(...)` and exposes the direct-add wrapper.
- **Authorisation is identical to invite-create** (PRIMARY-only); the failure mode "user already in household" is the same; the event emitted (`HouseholdMemberAddedEvent`) is the same. Cost is one DTO + one controller method.

**Worth user review**: the direct-add path's `AddMemberRequest` carries `@NotNull UUID userId` (the user being added), which means the caller needs to know the new member's UUID. In practice this is usable for admin tooling and migration scripts; the user-facing flow stays the invite/accept dance from 01c.

## Behavioural spec

### `addMember` flow

1. `POST /api/v1/households/current/members`. Authenticated. Caller resolved via `CurrentUserResolver`. Body: `AddMemberRequest { @NotNull UUID userId, @NotNull HouseholdRole role, @Min(1) @Max(1000) Integer priority /* nullable; default 100 */, @Size(max = 64) String displayName /* nullable */ }`.
2. **Authorisation**: caller must be `PRIMARY` of their household — `householdMemberRepository.findByUserId(callerUserId)` filtered to `role == PRIMARY`. If not PRIMARY → 403 `InsufficientHouseholdRoleException`. If caller is not in any household → 404 `HouseholdNotFoundException`.
3. **Conflict checks** (in order — preserve so the user gets the most-actionable error first):
   1. **Target already in a household** → 409 `UserAlreadyInHouseholdException` (existing from 01a). Lookup via `householdMemberRepository.findByUserId(request.userId())`.
   2. **`role == PRIMARY` and household already has a primary** → DB partial unique index `idx_household_member_one_primary` (from 01a) rejects the INSERT with `DataIntegrityViolationException`; mapped to 409 by `GlobalExceptionHandler`. Acceptable — the v1 multi-primary rule is "rare but legal" (LLD line 429), but only via role-change (Flow 5), not direct add.
4. Insert `HouseholdMember` row: `userId = request.userId()`, `role = request.role()`, `priority = request.priority() == null ? 100 : request.priority()`, `displayName = request.displayName()`, `householdId = caller's household`, `joinedAt = Instant.now()`. JPA assigns `id = UUID.randomUUID()` application-side.
5. Return 201 + `HouseholdMemberDto` of the just-created member. Set `Location: /api/v1/households/current/members/{memberId}`.
6. **Event**: publish `HouseholdMemberAddedEvent(UUID householdId, UUID memberId, UUID userId, HouseholdRole role, UUID traceId, Instant occurredAt)` `AFTER_COMMIT` per [LLD line 388](../../lld/household.md).
7. **Internal helper**: refactor 01c's `acceptInvite`'s member-insert block into a private `HouseholdServiceImpl#addMemberInternal(householdId, userId, role, priority, displayName, actorUserId)` so the direct-add and accept-invite paths share one insert path. The direct-add path emits `HouseholdMemberAddedEvent`; the accept-invite path emits `HouseholdInviteAcceptedEvent` (NOT `HouseholdMemberAddedEvent` — 01c locked that decision; LLD §76 of 01c's ticket file). **01d does NOT change 01c's event-emission behaviour.**

### `updateMember` flow

8. `PATCH /api/v1/households/current/members/{memberId}`. Authenticated. Body: `UpdateMemberRequest { @Min(1) @Max(1000) Integer priority /* nullable */, @Size(max = 64) String displayName /* nullable */, long expectedVersion /* required — 409 on stale */ }`. **Excludes `role`** — role changes go through the dedicated `/role` endpoint (Flow 5) so the stricter "last primary" guard is applied uniformly.
9. **Authorisation**: caller must be `PRIMARY` of the household the target member belongs to. **Self-update is NOT allowed via this endpoint** — a `MEMBER` who wants to change their own `displayName` calls a future `/me` endpoint (Out of Scope for 01d). 403 `InsufficientHouseholdRoleException` if caller is not PRIMARY.
10. **404 ladder** (don't leak existence of members belonging to other households):
    - Member not found at all → 404 `HouseholdMemberNotFoundException` (NEW exception).
    - Member found but `householdId` doesn't match the caller's household → 404 (same exception).
11. **Stale `expectedVersion`** → 409 via `OptimisticLockingFailureException` (mapped by `GlobalExceptionHandler`).
12. **Field-level update semantics**:
    - `priority`: if `request.priority() != null` and differs from existing → update. If `null` → leave unchanged. **PATCH semantics: absent field = no change**, not "set to null".
    - `displayName`: same as priority — `null` means "no change" (NOT "clear to null"). Future endpoint can offer a "clear" semantic; v1 keeps it simple.
    - **No-op detection**: if neither field actually changes → return 200 with existing DTO; **no event emitted** (per 01c's no-op pattern).
13. JPA bumps `@Version`. Return 200 with the updated `HouseholdMemberDto`.
14. **No event for member updates** — `priority` and `displayName` changes don't materially affect the planner's slot eaters or hard-constraint filter; the LLD's §Events list 387-392 does not declare a `HouseholdMemberUpdatedEvent`. Document on the service-impl Javadoc.

### `removeMember` flow (Flow 4 per LLD)

15. `DELETE /api/v1/households/current/members/{memberId}`. Authenticated.
16. **Authorisation** (LLD line 418): **PRIMARY** OR **self-remove** (`actorUserId == member.userId`). Either path is permitted; neither qualifies → 403 `InsufficientHouseholdRoleException`.
17. **404 ladder** (same as updateMember).
18. **Last-primary guard** (LLD lines 418, 429, 376): if `member.role == PRIMARY` AND the household has other members (count > 1) AND no other `PRIMARY` exists → 409 `LastPrimaryRemovalException` (NEW exception). Computed: `householdMemberRepository.findAllByHouseholdIdAndRole(householdId, PRIMARY)` size must be ≥ 2 OR `findAllByHouseholdId(householdId).size() == 1` (only-member case is permitted — LLD line 418).
19. **Only-member case** (LLD line 418 — locked decision): if the target is the only member of the household, **delete the member row but keep the household** (empty households are preserved). User can rejoin via invite without re-creating.
20. Single `@Transactional` write: `householdMemberRepository.delete(member)`. JPA cascades nothing — `HouseholdSettings` (one per household) is **not** deleted (the household persists). Invites issued for this user (`issued_for_user_id`) are NOT scrubbed — per LLD line 420 "Orphan model: data follows the user, household is a sharing context not an identity scope."
21. Return 204.
22. **Event**: publish `HouseholdMemberRemovedEvent(UUID householdId, UUID memberId, UUID userId, HouseholdRole roleAtRemoval, UUID traceId, Instant occurredAt)` `AFTER_COMMIT` per [LLD line 389](../../lld/household.md). `roleAtRemoval` is the member's role just before deletion.

### `changeRole` flow (Flow 5 per LLD)

23. `POST /api/v1/households/current/members/{memberId}/role`. Authenticated. Body: `ChangeRoleRequest { @NotNull HouseholdRole newRole, long expectedVersion }`.
24. **Authorisation**: PRIMARY only (LLD line 429). 403 `InsufficientHouseholdRoleException` otherwise.
25. **404 ladder** (same as updateMember).
26. **Stale `expectedVersion`** → 409 via `OptimisticLockingFailureException`.
27. **No-op detection**: if `member.role == request.newRole` → return 200 with existing DTO; no event.
28. **Last-primary guard** (LLD line 429): if `member.role == PRIMARY && request.newRole != PRIMARY` (demotion path) AND no other `PRIMARY` exists in the household → 409 `LastPrimaryRemovalException`. Same enforcement as remove-member's last-primary guard (consistent).
29. **Promotion to PRIMARY** (`member.role != PRIMARY && request.newRole == PRIMARY`): allowed even if a primary already exists — multi-primary is rare but legal (LLD line 429). The DB partial unique index `idx_household_member_one_primary` from 01a **does not block** multi-primary because it's `WHERE role = 'primary'` — the index is `UNIQUE (household_id, user_id) WHERE role = 'primary'` (LLD line 87) — wait, **double-check 01a's actual index shape**. If 01a's index is `UNIQUE (household_id) WHERE role = 'primary'` (one-primary-per-household), then promotion to PRIMARY with an existing primary would fail with `DataIntegrityViolationException`. **The agent MUST check 01a's migration `V20260601500100__household_create_household_member.sql` and align**: if the index enforces one-primary, document on the controller Javadoc that "v1 enforces single-primary per household at the DB layer; multi-primary requires a future migration." If the index permits multi-primary, the agent's path is clean.
30. Single `@Transactional` write: `member.setRole(request.newRole())`. JPA bumps `@Version`. Return 200 with the updated `HouseholdMemberDto`.
31. **Event**: publish `HouseholdRoleChangedEvent(UUID householdId, UUID memberId, UUID userId, HouseholdRole previousRole, HouseholdRole newRole, UUID traceId, Instant occurredAt)` `AFTER_COMMIT` per [LLD line 391](../../lld/household.md).

### Cross-module facade — append-only to existing interfaces

32. Append to existing `HouseholdUpdateService` from 01a/01b/01c (already declares `createHousehold`, `updateSettings`, `createInvite`, `acceptInvite`, `revokeInvite`):
    ```java
    HouseholdMemberDto addMember(UUID householdId, UUID actorUserId, AddMemberRequest request);
    HouseholdMemberDto updateMember(UUID memberId, UUID actorUserId, UpdateMemberRequest request);
    void removeMember(UUID memberId, UUID actorUserId);
    HouseholdMemberDto changeRole(UUID memberId, UUID actorUserId, ChangeRoleRequest request);
    ```
    Verbatim from [LLD lines 296-299](../../lld/household.md).
33. **No new methods on `HouseholdQueryService`** — 01a's `listMembers`, `getMember`, `getMembershipForUser` already cover the read paths.

### Repository — additions to existing `HouseholdMemberRepository`

34. The existing `HouseholdMemberRepository` (package-private; from 01a) already has `findByUserId`, `findAllByHouseholdId`, `findAllByHouseholdIdAndRole`, `existsByHouseholdIdAndRole`. **One new method appended**:
    ```java
    long countByHouseholdIdAndRole(UUID householdId, HouseholdRole role);
    ```
    Used by the last-primary guard. Faster than `findAllByHouseholdIdAndRole(...).size()` for the common single-row case.
35. **Boundary**: existing `HouseholdBoundaryTest` from 01a covers the repo (lives in `domain/repository/`). **No changes to the test**.

### Errors

36. New module exception subclasses extending the existing `HouseholdException` from 01a:
    - `HouseholdMemberNotFoundException` (404, `type = .../household-member-not-found`) — LLD line 359 names it.
    - `LastPrimaryRemovalException` (409, `type = .../last-primary-removal`) — LLD line 361 names it.
37. **Append two new `@ExceptionHandler` methods** to the existing `HouseholdExceptionHandler` `@RestControllerAdvice` from 01a/01b/01c (already `@Order(Ordered.HIGHEST_PRECEDENCE)`). Do **NOT** create a second handler class. Do **NOT** modify `config/GlobalExceptionHandler.java`. `OptimisticLockingFailureException` continues handled by `GlobalExceptionHandler` (409). `DataIntegrityViolationException` (one-primary partial index collision) likewise handled by `GlobalExceptionHandler` → 409. `UserAlreadyInHouseholdException` already mapped by 01a's existing handler — no new method needed. `InsufficientHouseholdRoleException` already mapped by 01b's existing handler.

## Database

**Zero migrations.** 01d is a pure code-path change — member admin writes to the existing `household_member` table from 01a's `V20260601500100`. No schema changes. The LLD's `household_member` schema already carries `displayName`, `priority`, `role`, `joined_at`, `version` columns sufficient for all four flows.

**Verify before writing**: the agent must confirm 01a's `V20260601500100__household_create_household_member.sql` already includes `display_name varchar(64) NULL` and `priority integer NOT NULL DEFAULT 100` columns. If absent, a small `V20260601500500__household_member_add_admin_columns.sql` migration is required — `ALTER TABLE household_member ADD COLUMN display_name varchar(64), ALTER COLUMN priority SET DEFAULT 100`. **Expected to be a no-op** based on 01a's LLD-spec (LLD §76 — `priority` shipped from day one; `displayName` typed in `HouseholdMemberDto` from 01a, so the column should exist).

## OpenAPI updates

### Append to `src/main/resources/openapi/paths/household.yaml`

(File created by 01a, extended by 01b/01c — append four new path-items below 01c's invite blocks. Do NOT touch existing path-items.)

```yaml
householdMembersCurrent:
  post:
    tags: [Households]
    operationId: addHouseholdMember
    summary: Directly add a member to the calling user's household (PRIMARY only). Prefer the invite flow for user-facing onboarding.
    security: [{ cookieAuth: [] }]
    requestBody:
      required: true
      content:
        application/json:
          schema: { $ref: '../schemas/household.yaml#/AddMemberRequest' }
    responses:
      '201':
        description: Member added.
        headers:
          Location:
            schema: { type: string, format: uri }
        content:
          application/json:
            schema: { $ref: '../schemas/household.yaml#/HouseholdMemberDto' }
      '400': { description: Validation error, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '401': { description: Unauthenticated, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '403': { description: Caller not primary, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '404': { description: Caller not in any household, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '409': { description: Target already in a household, or primary collision, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
householdMember:
  patch:
    tags: [Households]
    operationId: updateHouseholdMember
    summary: Update a member's priority / displayName (PRIMARY only). PATCH semantics — absent fields unchanged.
    security: [{ cookieAuth: [] }]
    parameters:
      - in: path
        name: memberId
        required: true
        schema: { type: string, format: uuid }
    requestBody:
      required: true
      content:
        application/json:
          schema: { $ref: '../schemas/household.yaml#/UpdateMemberRequest' }
    responses:
      '200':
        description: Member updated.
        content:
          application/json:
            schema: { $ref: '../schemas/household.yaml#/HouseholdMemberDto' }
      '400': { description: Validation error, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '401': { description: Unauthenticated, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '403': { description: Caller not primary, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '404': { description: Member not found / belongs to another household, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '409': { description: Stale expectedVersion, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
  delete:
    tags: [Households]
    operationId: removeHouseholdMember
    summary: Remove a member (PRIMARY removes anyone; members can self-remove).
    security: [{ cookieAuth: [] }]
    parameters:
      - in: path
        name: memberId
        required: true
        schema: { type: string, format: uuid }
    responses:
      '204': { description: Member removed. }
      '401': { description: Unauthenticated, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '403': { description: Caller is neither primary nor the target, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '404': { description: Member not found / belongs to another household, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '409': { description: Removing the last primary while other members remain, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
householdMemberRole:
  post:
    tags: [Households]
    operationId: changeHouseholdMemberRole
    summary: Change a member's role (PRIMARY only).
    security: [{ cookieAuth: [] }]
    parameters:
      - in: path
        name: memberId
        required: true
        schema: { type: string, format: uuid }
    requestBody:
      required: true
      content:
        application/json:
          schema: { $ref: '../schemas/household.yaml#/ChangeRoleRequest' }
    responses:
      '200':
        description: Role updated.
        content:
          application/json:
            schema: { $ref: '../schemas/household.yaml#/HouseholdMemberDto' }
      '400': { description: Validation error, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '401': { description: Unauthenticated, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '403': { description: Caller not primary, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '404': { description: Member not found / belongs to another household, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '409': { description: Stale expectedVersion / demoting the last primary, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
```

### Append to `src/main/resources/openapi/schemas/household.yaml`

(Append three new schemas. The existing `HouseholdMemberDto` and `HouseholdRole` schemas from 01a are reused.)

```yaml
AddMemberRequest:
  type: object
  required: [userId, role]
  properties:
    userId: { type: string, format: uuid }
    role: { $ref: '#/HouseholdRole' }
    priority:
      type: integer
      minimum: 1
      maximum: 1000
      default: 100
      nullable: true
    displayName:
      type: string
      maxLength: 64
      nullable: true
UpdateMemberRequest:
  type: object
  required: [expectedVersion]
  properties:
    priority:
      type: integer
      minimum: 1
      maximum: 1000
      nullable: true
      description: PATCH semantics — null = no change.
    displayName:
      type: string
      maxLength: 64
      nullable: true
      description: PATCH semantics — null = no change.
    expectedVersion: { type: integer, format: int64, minimum: 0 }
ChangeRoleRequest:
  type: object
  required: [newRole, expectedVersion]
  properties:
    newRole: { $ref: '#/HouseholdRole' }
    expectedVersion: { type: integer, format: int64, minimum: 0 }
```

**Gotcha applied**: nullable scalar fields (`priority`, `displayName` on both add + update) use **inline** `nullable: true` (NOT `$ref + nullable: true`). `role` and `newRole` use `$ref` to `HouseholdRole` without nullable — they are required.

### Append to entry `src/main/resources/openapi/openapi.yaml`

**Location**: under the existing `# household` block in `paths:` (after 01c's invite refs). Append three new path-item refs:

```yaml
  /api/v1/households/current/members:
    $ref: 'paths/household.yaml#/householdMembersCurrent'
  /api/v1/households/current/members/{memberId}:
    $ref: 'paths/household.yaml#/householdMember'
  /api/v1/households/current/members/{memberId}/role:
    $ref: 'paths/household.yaml#/householdMemberRole'
```

**Location**: under `components.schemas:`, append three new schema refs in the existing `# household` block (alphabetical):

```yaml
    AddMemberRequest: { $ref: 'schemas/household.yaml#/AddMemberRequest' }
    ChangeRoleRequest: { $ref: 'schemas/household.yaml#/ChangeRoleRequest' }
    UpdateMemberRequest: { $ref: 'schemas/household.yaml#/UpdateMemberRequest' }
```

## Verbatim shape snippets

### DTOs (Java records)

```java
public record AddMemberRequest(
    @NotNull UUID userId,
    @NotNull HouseholdRole role,
    @Min(1) @Max(1000) Integer priority,            // nullable — default 100
    @Size(max = 64) String displayName              // nullable
) {}

public record UpdateMemberRequest(
    @Min(1) @Max(1000) Integer priority,            // PATCH: null = no change
    @Size(max = 64) String displayName,             // PATCH: null = no change
    long expectedVersion
) {}

public record ChangeRoleRequest(
    @NotNull HouseholdRole newRole,
    long expectedVersion
) {}
```

### Service-impl — `removeMember` skeleton (last-primary guard)

```java
@Transactional
public void removeMember(UUID memberId, UUID actorUserId) {
  HouseholdMember member = householdMemberRepository.findById(memberId)
      .orElseThrow(HouseholdMemberNotFoundException::new);

  HouseholdMember actorMember = householdMemberRepository.findByUserId(actorUserId)
      .filter(m -> m.getHouseholdId().equals(member.getHouseholdId()))
      .orElseThrow(HouseholdNotFoundException::new);                       // 404 — not even in the same household

  boolean isPrimary = actorMember.getRole() == HouseholdRole.PRIMARY;
  boolean isSelfRemove = actorUserId.equals(member.getUserId());
  if (!isPrimary && !isSelfRemove) {
    throw new InsufficientHouseholdRoleException("primary or self");
  }

  // Last-primary guard
  if (member.getRole() == HouseholdRole.PRIMARY) {
    long totalMembers = householdMemberRepository.findAllByHouseholdId(member.getHouseholdId()).size();
    long primaryCount = householdMemberRepository.countByHouseholdIdAndRole(
        member.getHouseholdId(), HouseholdRole.PRIMARY);
    boolean onlyMember = totalMembers == 1;
    boolean lastPrimaryWithOthers = primaryCount == 1 && totalMembers > 1;
    if (lastPrimaryWithOthers) {
      throw new LastPrimaryRemovalException("promote another member to primary first");
    }
    // onlyMember case: permitted — empty household preserved (LLD line 418).
  }

  HouseholdRole roleAtRemoval = member.getRole();
  UUID userId = member.getUserId();
  UUID householdId = member.getHouseholdId();
  householdMemberRepository.delete(member);

  publisher.publishEvent(new HouseholdMemberRemovedEvent(
      householdId, memberId, userId, roleAtRemoval,
      traceIdFromMdcOrRandom(), Instant.now()));
}
```

### Service-impl — `changeRole` skeleton (demote-last-primary guard)

```java
@Transactional
public HouseholdMemberDto changeRole(UUID memberId, UUID actorUserId, ChangeRoleRequest request) {
  HouseholdMember member = householdMemberRepository.findById(memberId)
      .orElseThrow(HouseholdMemberNotFoundException::new);

  HouseholdMember actor = householdMemberRepository.findByUserId(actorUserId)
      .filter(m -> m.getHouseholdId().equals(member.getHouseholdId()))
      .filter(m -> m.getRole() == HouseholdRole.PRIMARY)
      .orElseThrow(() -> new InsufficientHouseholdRoleException("primary required"));

  if (member.getVersion() != request.expectedVersion()) {
    throw new OptimisticLockingFailureException("stale expectedVersion");
  }

  HouseholdRole previousRole = member.getRole();
  if (previousRole == request.newRole()) {
    return mapper.toDto(member);                                            // no-op; no event
  }

  // Demoting the last primary
  if (previousRole == HouseholdRole.PRIMARY && request.newRole() != HouseholdRole.PRIMARY) {
    long primaryCount = householdMemberRepository.countByHouseholdIdAndRole(
        member.getHouseholdId(), HouseholdRole.PRIMARY);
    if (primaryCount == 1) {
      throw new LastPrimaryRemovalException("promote another member to primary first");
    }
  }

  member.setRole(request.newRole());
  HouseholdMember saved = householdMemberRepository.saveAndFlush(member);    // gotcha: flush so @Version bumps in payload

  publisher.publishEvent(new HouseholdRoleChangedEvent(
      member.getHouseholdId(), member.getId(), member.getUserId(),
      previousRole, request.newRole(),
      traceIdFromMdcOrRandom(), Instant.now()));
  return mapper.toDto(saved);
}
```

### Exception

```java
public class LastPrimaryRemovalException extends HouseholdException {
  private static final URI TYPE = URI.create("https://mealprep.example.com/problems/last-primary-removal");

  public LastPrimaryRemovalException(String detail) {
    super(detail);
  }

  @Override public URI getType() { return TYPE; }
  @Override public HttpStatus getStatus() { return HttpStatus.CONFLICT; }
}
```

## Edge-case checklist

- [ ] `POST /current/members` by PRIMARY for a user-not-in-any-household → 201; `Location` header set; row persisted with `role`, `priority`, `displayName` from request
- [ ] `POST /current/members` by MEMBER (non-primary) → 403 `insufficient-household-role`
- [ ] `POST /current/members` by user-not-in-any-household → 404 `household-not-found`
- [ ] `POST /current/members` for a target already in a household → 409 `user-already-in-household`
- [ ] `POST /current/members` with `role = PRIMARY` when household already has a primary → 409 (DB partial unique index collision via `GlobalExceptionHandler`)
- [ ] `POST /current/members` validation: `userId = null` → 400; `role = null` → 400; `priority = 0` → 400; `priority = 1001` → 400; `displayName` > 64 chars → 400
- [ ] `POST /current/members` with `priority = null` → persisted as 100 (default)
- [ ] `PATCH /current/members/{memberId}` by PRIMARY with both fields → 200, both updated, `@Version` bumped
- [ ] `PATCH /current/members/{memberId}` with `priority = null, displayName = null` (no actual change) → 200 with existing DTO; no event; `@Version` NOT bumped
- [ ] `PATCH /current/members/{memberId}` for a member in another household → 404
- [ ] `PATCH /current/members/{memberId}` stale `expectedVersion` → 409
- [ ] `PATCH /current/members/{memberId}` by MEMBER → 403
- [ ] `DELETE /current/members/{memberId}` by PRIMARY removing a MEMBER → 204; row gone; `HouseholdMemberRemovedEvent` published with `roleAtRemoval = MEMBER`
- [ ] `DELETE /current/members/{memberId}` self-remove by MEMBER → 204; event published
- [ ] `DELETE /current/members/{memberId}` self-remove by PRIMARY (last primary with other members) → 409 `last-primary-removal`
- [ ] `DELETE /current/members/{memberId}` PRIMARY removing themselves as the only member of the household → 204; household row preserved (verified by `JdbcTemplate` SELECT after the delete)
- [ ] `DELETE /current/members/{memberId}` for a member in another household → 404 (don't leak)
- [ ] `DELETE /current/members/{memberId}` by MEMBER targeting a different MEMBER → 403 (not self, not primary)
- [ ] `POST /current/members/{memberId}/role` by PRIMARY promoting MEMBER → PRIMARY → 200; event published with `previousRole = MEMBER`, `newRole = PRIMARY`; multi-primary state persists (verify by `JdbcTemplate` count) — **assumes 01a's index permits multi-primary**; if it doesn't, replace with "→ 409 DataIntegrityViolation" and document
- [ ] `POST /current/members/{memberId}/role` by PRIMARY demoting themselves (only primary in household, other members present) → 409 `last-primary-removal`
- [ ] `POST /current/members/{memberId}/role` no-op (`newRole == currentRole`) → 200; no event; `@Version` NOT bumped
- [ ] `POST /current/members/{memberId}/role` validation: `newRole = null` → 400
- [ ] `POST /current/members/{memberId}/role` stale `expectedVersion` → 409
- [ ] `POST /current/members/{memberId}/role` by MEMBER → 403
- [ ] OpenAPI request/response shapes match (swagger-request-validator filter active in IT)
- [ ] `HouseholdBoundaryTest` (from 01a) still passes — no new repos
- [ ] `HouseholdExceptionHandler` retains `@Order(Ordered.HIGHEST_PRECEDENCE)` after appending the two new handler methods (`HouseholdMemberNotFoundException`, `LastPrimaryRemovalException`)
- [ ] No raw `userId` accepted from query string — `userId` on the `AddMemberRequest` is the **target** user; the **actor** is server-resolved via `CurrentUserResolver` on every endpoint
- [ ] No regression on existing tests, including 01c's `HouseholdInvitesFlowIT`, 01b's `HouseholdSettingsFlowIT`, 01a's `HouseholdsFlowIT`
- [ ] No N+1 — `removeMember`'s guard makes two count queries (`findAllByHouseholdId` for total, `countByHouseholdIdAndRole` for primary count); verify via Hibernate stats

## Files this ticket touches

```
NEW   src/main/java/com/example/mealprep/household/api/controller/HouseholdMembersController.java
NEW   src/main/java/com/example/mealprep/household/api/dto/AddMemberRequest.java
NEW   src/main/java/com/example/mealprep/household/api/dto/UpdateMemberRequest.java
NEW   src/main/java/com/example/mealprep/household/api/dto/ChangeRoleRequest.java
NEW   src/main/java/com/example/mealprep/household/event/HouseholdMemberAddedEvent.java
NEW   src/main/java/com/example/mealprep/household/event/HouseholdMemberRemovedEvent.java
NEW   src/main/java/com/example/mealprep/household/event/HouseholdRoleChangedEvent.java
NEW   src/main/java/com/example/mealprep/household/exception/HouseholdMemberNotFoundException.java
NEW   src/main/java/com/example/mealprep/household/exception/LastPrimaryRemovalException.java

MOD   src/main/java/com/example/mealprep/household/api/HouseholdExceptionHandler.java                  (append 2 @ExceptionHandler methods; KEEP @Order(Ordered.HIGHEST_PRECEDENCE))
MOD   src/main/java/com/example/mealprep/household/domain/repository/HouseholdMemberRepository.java   (append countByHouseholdIdAndRole)
MOD   src/main/java/com/example/mealprep/household/domain/service/HouseholdUpdateService.java         (append addMember, updateMember, removeMember, changeRole)
MOD   src/main/java/com/example/mealprep/household/domain/service/internal/HouseholdServiceImpl.java  (implement the four new methods; refactor invite-accept's member-insert path into a shared private helper)

MOD   src/main/resources/openapi/paths/household.yaml      (append 3 new path-items below 01c's; do NOT touch existing)
MOD   src/main/resources/openapi/schemas/household.yaml    (append 3 new schemas: AddMemberRequest, UpdateMemberRequest, ChangeRoleRequest)
MOD   src/main/resources/openapi/openapi.yaml              (3 lines under paths: in the `# household` block; 3 lines under components.schemas: in the `# household` block)

NEW   src/test/java/com/example/mealprep/household/HouseholdMembersServiceTest.java
NEW   src/test/java/com/example/mealprep/household/HouseholdMembersFlowIT.java
MOD   src/test/java/com/example/mealprep/household/testdata/HouseholdTestData.java                    (append add-member, update-member, change-role request builders)
```

**Files this ticket does NOT modify** (cross-cutting; sibling round-4 tickets running in parallel must not collide):
- `src/main/java/com/example/mealprep/config/GlobalExceptionHandler.java` — module exceptions go in the existing `HouseholdExceptionHandler`.
- `src/test/java/com/example/mealprep/archunit/ModuleBoundaryTest.java` — module boundary rule lives in the existing `HouseholdBoundaryTest`.
- Other modules' `paths/*.yaml`, `schemas/*.yaml`, `<module>ExceptionHandler.java`, `<module>BoundaryTest.java`, migrations, entities — none touched.
- 01a's `HouseholdMember` entity — used as-is; **no new fields**, **no new columns**.
- 01c's `HouseholdInvite` entity / repository / `acceptInvite` flow — **the accept-invite path is NOT modified by 01d**. The refactor into a shared `addMemberInternal` helper is internal to `HouseholdServiceImpl` only; the public `acceptInvite` signature and event-emission behaviour are unchanged.
- `HouseholdBoundaryTest` is unchanged.

## Dependencies

- **Hard dependency**: `household-01a` (merged) — `Household`, `HouseholdMember`, `HouseholdMemberRepository`, `HouseholdRole` enum, `HouseholdMemberDto`, `HouseholdQueryService`, `HouseholdUpdateService`, `HouseholdExceptionHandler`, `HouseholdBoundaryTest`, `HouseholdException`, `HouseholdNotFoundException`, `UserAlreadyInHouseholdException`, `idx_household_member_one_primary` partial unique index (whatever its semantics are — see invariant 29).
- **Hard dependency**: `household-01b` (merged) — `InsufficientHouseholdRoleException`; the `@ExceptionHandler` ordering pattern; the per-module YAML / advice append-only convention.
- **Hard dependency**: `household-01c` (merged) — `acceptInvite` flow + `HouseholdInviteAcceptedEvent` (01d's refactor of the member-insert path must preserve 01c's `acceptInvite` external behaviour; see invariant 7).
- **Hard dependency**: `auth-01a` (merged) — `CurrentUserResolver`, `SessionAuthenticationFilter`.
- **Hard dependency**: `refactor-01-split-merge-zones` (merged) — per-module YAML / advice / boundary-test layout.
- **Sibling tickets running in parallel** (Wave 2 round 4): `nutrition-01d`, `provisions-01d`, `recipe-01d`. None should touch any household file or any of the cross-cutting files listed above. Only collision point is the entry `openapi.yaml`; this ticket appends in the `# household` block, sibling tickets append in their own module's block.

## Acceptance / DoD

- [ ] `./mvnw -Dspotless.check.skip=true verify` passes locally on the agent's worktree
- [ ] `./mvnw spotless:apply` then `./mvnw spotless:check` clean
- [ ] CI green on the PR (build + spotless + OpenAPI lint + ArchUnit gate)
- [ ] All edge-case items above ticked
- [ ] `HouseholdExceptionHandler` retains `@Order(Ordered.HIGHEST_PRECEDENCE)` after appending the two new methods
- [ ] `saveAndFlush` used in the update + role-change paths so the response payload reflects the bumped `@Version`
- [ ] OpenAPI 3.0 nullable fields use **inline** `nullable: true` (NOT `$ref + nullable: true`) on `priority`, `displayName`
- [ ] PATCH semantics — absent-field-on-update = no change — verified via IT (PATCH `{ expectedVersion: 0 }` returns the unchanged member)
- [ ] No regression on existing tests, including 01c's `HouseholdInvitesFlowIT` (the refactored member-insert helper must not change `acceptInvite`'s external behaviour)
- [ ] `HouseholdInviteAcceptedEvent` is **still the only event emitted** by `acceptInvite` after 01d's refactor — verified by an IT assertion that `HouseholdMemberAddedEvent` is NOT published from the accept-invite path
- [ ] No N+1 on `removeMember` — two count queries max (total members, primary count); verified via Hibernate stats or statement-count assertion in IT
- [ ] No pom.xml dependency adds

## What's NOT in scope

- `HouseholdMergeService`, `MergedSoftPreferencesDto`, `SoftPreferenceMerger`, `getSoftPreferencesByUserIds` → **household-01e** (blocked on preference soft-prefs ticket; see top divergence note)
- `SlotConfigurationDto` planner-friendly view → **household-01f** (depends on planner module)
- `addMember` / `updateMember` / `removeMember` path-parameterised endpoints (`/households/{id}/members/...`) — locked-out in 01d; landed when multi-household support arrives
- Self-update endpoint (`PATCH /households/current/members/me`) for non-primary members changing their own `displayName` — Out of Scope for v1
- Bulk member operations — none specified in the LLD
- Leaver data scrub / transfer (LLD lines 422-425 — Orphan model locked)
- Audit log for membership changes — LLD does not declare one; the three events are the audit trail
- `HouseholdMemberUpdatedEvent` — LLD does not declare; updates are silent (priority + displayName changes don't affect downstream planner state)

Squash-merge with: `feat(household): 01d — member admin CRUD (add / update / remove / role-change) + LastPrimaryRemovalException + 3 events`
