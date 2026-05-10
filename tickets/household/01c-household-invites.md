# Ticket: household — 01c Household Invites (create / list / revoke / accept)

## Summary

Layer the **invite-code flow** on top of the 01a/01b household aggregate: `HouseholdInvite` table + entity + repository + the `InviteCodeGenerator` (cryptographically random 16-char alphanumeric) + the four endpoints `POST /api/v1/households/current/invites` (PRIMARY-only create), `GET /api/v1/households/current/invites` (list pending — never returns the code), `DELETE /api/v1/households/current/invites/{inviteId}` (PRIMARY-only revoke), and `POST /api/v1/invites/accept` (the accept-by-code flow that lands the accepter as a `HouseholdMember`). New events `HouseholdInviteCreatedEvent` and `HouseholdInviteAcceptedEvent` published `AFTER_COMMIT`. Per [`lld/household.md`](../../lld/household.md) §V20260501130300, §`HouseholdInviteDto`, §`AcceptInviteRequest`, §`HouseholdInviteRepository`, §Service Interfaces (`createInvite`, `listPendingInvites`, `revokeInvite`, `acceptInvite`, `getInviteByCode`), §Flow 2 (Invite a member), §Flow 3 (Accept invite).

**Defers to later household tickets**:
- `HouseholdMergeService`, `MergedSoftPreferencesDto`, `SoftPreferenceMerger` → **household-01d** (depends on `preference.QueryService.getSoftPreferencesByUserIds`).
- Member CRUD endpoints (`addMember`, `updateMember`, `removeMember`, `changeRole`), `LastPrimaryRemovalException` enforcement, `HouseholdMemberAddedEvent` / `HouseholdMemberRemovedEvent` / `HouseholdRoleChangedEvent` for the dedicated member-admin paths → **household-01e** (the accept-flow's "promotes accepter to member" path is in 01c because the LLD pins it to Flow 3; 01e reuses the same insert helper).
- Email-based invites (Out of Scope per LLD §Out of Scope).
- Invite-link landing page / pre-signup invite redemption (frontend concern; the API is designed so that an unauthenticated `POST /invites/accept` returns 401 — frontend handles login first).

01c unblocks the household-onboarding loop: the primary invites a partner; partner clicks-or-types the code; partner becomes a `MEMBER` (or `PRIMARY` if `intendedRole = PRIMARY`) of the same household. Without 01c the only way to add household members is direct DB insertion.

**LLD divergence note** — **endpoint paths**: the LLD §REST table line 347-349 specifies the invite endpoints under `/households/{id}/invites` (path-parameterised by household id). Since v1 enforces single-household-per-user (LLD §Out of Scope) and 01a/01b already expose `/households/current` for the calling user's household, **01c uses `/households/current/invites` for create/list/revoke** instead of `/households/{id}/invites`. The accept endpoint stays at the LLD's `/api/v1/invites/accept` (LLD-spec is `/households/invites/accept`; **01c shortens to `/api/v1/invites/accept`** because accepting an invite is logically pre-household — the accepter has no household yet — so nesting under `/households/...` is misleading). When multi-household lands, the path-parameterised form returns; the contract for `current` callers is unchanged.

**LLD divergence note** — **`createInvite` `actorUserId` resolution**: LLD line 410 says "Authorisation: `PRIMARY` only", which 01c enforces by `householdMemberRepository.findByUserId(callerUserId)` + role-check. The LLD's `createInvite(UUID householdId, UUID actorUserId, CreateInviteRequest)` signature takes `householdId` explicitly; 01c's controller derives it from `findByUserId(callerUserId).getHouseholdId()` because the URL is `/current/invites`. The service interface keeps the `(householdId, actorUserId, request)` signature so 01e's path-parameterised endpoints can reuse it.

## Behavioural spec

### Aggregate shape

1. `HouseholdInvite` is its own aggregate root per [LLD §Entities lines 136 + V20260501130300 lines 105-119](../../lld/household.md). Fields: `id (UUID, application-set), householdId (UUID NOT NULL FK → household.id ON DELETE CASCADE), inviteCode (varchar 32 NOT NULL UNIQUE), issuedByUserId (UUID NOT NULL), issuedForUserId (UUID nullable — pre-targeted invite), intendedRole (HouseholdRole enum, NOT NULL, default MEMBER, stored as lowercase to match the existing `HouseholdRole` mapping from 01a), expiresAt (Instant NOT NULL), acceptedByUserId (UUID nullable), acceptedAt (Instant nullable), revokedAt (Instant nullable), version (@Version Long), createdAt (@CreatedDate)`. **No `@LastModifiedDate`** — invites are accept/revoke-once, no mid-life mutations. **No `@OneToMany`** from `Household` to `HouseholdInvite` — kept as a separate aggregate root with `householdId` as a soft-style FK (DB FK exists; no JPA association from the parent, because including it in 01a's `Household` `@OneToMany` set would force a re-write of 01a's `HouseholdMapper`). LLD §Entities aligns with this — invites are listed as their own aggregate root (line 136).
2. `InviteStatus` is a derived enum (NOT a column) per [LLD §DTOs line 176](../../lld/household.md): `PENDING | ACCEPTED | REVOKED | EXPIRED`. Computed from `acceptedAt` / `revokedAt` / `expiresAt` at mapping time:
   - `revokedAt != null` → `REVOKED`
   - else `acceptedAt != null` → `ACCEPTED`
   - else `expiresAt < now` → `EXPIRED`
   - else → `PENDING`
3. **Database constraints**: `UNIQUE (invite_code)` (full unique — codes must be globally unique, not just within a household). Two **partial** indexes per LLD lines 118-119: `idx_household_invite_code ON household_invite (invite_code) WHERE accepted_at IS NULL AND revoked_at IS NULL` (lookup-by-code on accept hits the partial index — only pending invites are candidates; accepted/revoked rows are skipped); `idx_household_invite_household ON household_invite (household_id) WHERE accepted_at IS NULL AND revoked_at IS NULL` (pending-listing for the admin UI is partial, keeps it tiny).

### `InviteCodeGenerator`

4. `InviteCodeGenerator` is a `@Component` in `domain/service/internal/`. Single method `String generate()`. **Cryptographically random** — uses `java.security.SecureRandom` (NOT `Math.random` / `Random`). Output: 16 chars, alphanumeric (`A-Z` + `0-9`, no lowercase to keep codes case-insensitive-friendly when typed; **NO ambiguous characters**: exclude `0`, `O`, `1`, `I`, `L` from the alphabet to avoid OCR/typo collision). Effective alphabet: `ABCDEFGHJKMNPQRSTUVWXYZ23456789` (31 chars). At 16 chars × log2(31) ≈ 79 bits of entropy — well above the LLD's "alphanumeric, secure-random" floor and above the `varchar(32)` column width.
5. **Collision handling**: the controller / service inserts the row with `inviteCode = generator.generate()`; on `DataIntegrityViolationException` from the `UNIQUE (invite_code)` constraint, retry up to 3 times (each retry generates a fresh code). Beyond 3 retries → propagate the exception (this would only happen at multi-billion-row scale; not a realistic v1 path).

### `createInvite` flow (Flow 2)

6. `POST /api/v1/households/current/invites`. Authenticated. Server resolves `creatorUserId` via `CurrentUserResolver`. Body: `CreateInviteRequest { UUID issuedForUserId /* nullable */, @NotNull HouseholdRole intendedRole, @NotNull @Future Instant expiresAt }` per [LLD line 188](../../lld/household.md).
7. Single `@Transactional` write. Authorisation: caller must be `PRIMARY` of their household — `householdMemberRepository.findByUserId(creatorUserId)` filtered to `role == PRIMARY`. If not a primary member → 403 `InsufficientHouseholdRoleException` (existing exception from 01b). If caller is not in any household at all → 404 `HouseholdNotFoundException` (existing from 01a).
8. **Expiry cap**: server caps `expiresAt` at `now + 30 days` (LLD line 410 — "service caps at 30 days"). If the request's `expiresAt > now + 30 days` → silently truncate to `now + 30 days` (do NOT reject; the cap is a hard ceiling, not a validation rule). If `expiresAt <= now` → Jakarta `@Future` rejects at controller layer (400). **Worth a flag — LLD line 410 says "worth user review"**; 01c locks "silently cap to 30 days" because the alternative (`@Past` rejection at the cap boundary) is surprising for the calling user.
9. Generate `inviteCode` via `InviteCodeGenerator.generate()` with the 3-retry collision loop. Persist `HouseholdInvite` row.
10. Return 201 + `HouseholdInviteDto` **including the `inviteCode`** (the **only** response that ever includes the code — see invariant 12). Set `Location: /api/v1/households/current/invites/{inviteId}`.
11. Publish `HouseholdInviteCreatedEvent(UUID householdId, UUID inviteId, UUID issuedByUserId, UUID issuedForUserId /* nullable */, HouseholdRole intendedRole, Instant expiresAt, UUID traceId, Instant occurredAt)` `AFTER_COMMIT`. **LLD divergence note** — LLD line 410 says "No `HouseholdInviteCreatedEvent` at v1 (no listener cares yet)". 01c **adds the event anyway** because:
    - Cost is one record class; declaring it now means future listeners (notification module — "you've been invited") have a stable contract.
    - Symmetry with `HouseholdInviteAcceptedEvent` (which the LLD does require for member-add notifications via `HouseholdMemberAddedEvent`).
    - No listeners in 01c — emitted for downstream consumers later.

### `listPendingInvites`

12. `GET /api/v1/households/current/invites`. Authenticated. Caller must be a `MEMBER` or `PRIMARY` of their household (any role can read; the LLD doesn't restrict to PRIMARY for read). Returns `List<HouseholdInviteDto>` of pending invites only — `where accepted_at IS NULL AND revoked_at IS NULL`. **`inviteCode` field omitted from every entry** — set to `null` in the DTO (the field is bearer-only secrecy; only the create response surfaces it). Caller not in any household → 404 `HouseholdNotFoundException`.
13. Repository method: `findByHouseholdIdAndAcceptedAtIsNullAndRevokedAtIsNull(UUID householdId)` per [LLD line 252](../../lld/household.md). Hits the partial index `idx_household_invite_household` from invariant 3. **Sort by `createdAt DESC`** — newest first. Mapper sets `inviteCode = null` on every entry.

### `revokeInvite`

14. `DELETE /api/v1/households/current/invites/{inviteId}`. Authenticated. Caller must be `PRIMARY` of their household. **404 ladder** (don't leak existence of invites belonging to other households):
    - Invite not found at all → 404 `HouseholdInviteNotFoundException`.
    - Invite found but `householdId` doesn't match the caller's household → 404 (same exception — don't differentiate; treats other households' invites as non-existent to the caller).
    - Caller is a `MEMBER` (not `PRIMARY`) of the matching household → 403 `InsufficientHouseholdRoleException`.
    - Invite already `acceptedAt != null` → 409 `HouseholdInviteAlreadyAcceptedException` (existing exception name from LLD line 361; new in 01c).
    - Invite already `revokedAt != null` → 409 (idempotency choice: **reject** rather than silently re-revoke, because the user might be confused about which invite they're acting on; mirrors the LLD's 410 mapping for revoked invites on accept).
15. Single `@Transactional` write: set `revokedAt = Instant.now()`. Bump `@Version`. Return 204. **No revoke event** — symmetric with create's "no listener cares" rationale; revoke is more clearly internal-state-only (the accepter never knew the code).

### `acceptInvite` flow (Flow 3)

16. `POST /api/v1/invites/accept`. Authenticated (the accepter must be logged in — invite acceptance does not bootstrap an account). Server resolves `accepterUserId` via `CurrentUserResolver`. Body: `AcceptInviteRequest { @NotBlank @Size(max = 32) String inviteCode }` per [LLD line 189](../../lld/household.md).
17. Single `@Transactional` write. **Status ladder** (preserve order — LLD Flow 3 spells these out):
    1. **Lookup**: `householdInviteRepository.findByInviteCode(inviteCode)` — missing → 404 `HouseholdInviteNotFoundException`.
    2. **Revoked**: `revokedAt != null` → **410** `HouseholdInviteRevokedException` (LLD line 360). Mapping: this is a **new** exception subclass; new `@ExceptionHandler` method on the existing `HouseholdExceptionHandler`.
    3. **Expired**: `expiresAt < now()` → **410** `HouseholdInviteExpiredException` (LLD line 360). New exception subclass; new handler method.
    4. **Already accepted**: `acceptedAt != null` → **409** `HouseholdInviteAlreadyAcceptedException` (LLD line 361). New exception subclass; new handler method.
    5. **Wrong recipient**: `issuedForUserId != null && issuedForUserId != accepterUserId` → **403** `InsufficientHouseholdRoleException` (LLD Flow 3 — "If invite specifies `issuedForUserId ≠ accepterUserId` → 403"). Reuses the existing 403 exception from 01b (no new type — the existing `type = .../insufficient-household-role` is fine; the `detail` carries "invite is for a different user").
    6. **Already in a household**: if `householdMemberRepository.findByUserId(accepterUserId).isPresent()` → **409** `UserAlreadyInHouseholdException` (existing from 01a).
18. **Insert `HouseholdMember`**: `userId = accepterUserId`, `role = invite.intendedRole`, `priority = 100`, `joinedAt = Instant.now()`, `displayName = null`, `householdId = invite.householdId`. **DB partial unique index `idx_household_member_one_primary` from 01a** still applies — if `intendedRole = PRIMARY` and the household already has a primary, the insert fails with `DataIntegrityViolationException` mapped to 409 by `GlobalExceptionHandler`. Acceptable; rare; not specifically caught.
19. **Stamp the invite**: set `acceptedAt = Instant.now()`, `acceptedByUserId = accepterUserId`. Bump `@Version`.
20. **Return 200** with `HouseholdMemberDto` (the just-created member row, with the joined `householdId`). **LLD divergence**: LLD §REST line 349 specifies `→ HouseholdMemberDto (200/404/409/410)` — 01c matches.
21. **Events**: publish `HouseholdInviteAcceptedEvent(UUID householdId, UUID inviteId, UUID acceptedByUserId, HouseholdRole grantedRole, UUID traceId, Instant occurredAt)` `AFTER_COMMIT`. **Do NOT publish `HouseholdMemberAddedEvent`** in 01c — that event is for the `addMember` flow that 01e introduces; 01c's accept-driven member insert emits only the invite-accepted event. **LLD divergence noted** — LLD Flow 3 mentions "Publish `HouseholdMemberAddedEvent` after commit" but the cost of declaring that event here is that 01e then has a partial-implementation; cleaner to keep all `HouseholdMemberAddedEvent` emissions in 01e and have downstream listeners subscribe to `HouseholdInviteAcceptedEvent` for the v1 onboarding flow. Document this on the event class Javadoc so 01e knows to consider unifying.

### `getInviteByCode` (cross-module facade — read-only for future delivery layers)

22. Append `Optional<HouseholdInviteDto> getInviteByCode(String inviteCode)` to the existing `HouseholdQueryService` interface (LLD line 284). **Used internally** for accept-flow validation; also exposed across the module boundary for a future notification module that wants to render "you have a pending invite" state. **No HTTP endpoint** for raw lookup-by-code (security — only `POST /accept` exposes the code).

### Cross-module facade + boundary

23. Append to existing `HouseholdQueryService` from 01a/01b:
    ```java
    List<HouseholdInviteDto> listPendingInvites(UUID householdId);
    Optional<HouseholdInviteDto> getInviteByCode(String inviteCode);
    ```
24. Append to existing `HouseholdUpdateService`:
    ```java
    HouseholdInviteDto createInvite(UUID householdId, UUID actorUserId, CreateInviteRequest request);
    HouseholdMemberDto acceptInvite(UUID accepterUserId, AcceptInviteRequest request);
    void revokeInvite(UUID inviteId, UUID actorUserId);
    ```
25. New repository `HouseholdInviteRepository` is **package-private** per the 01a/01b pattern. Existing `HouseholdBoundaryTest` from 01a covers it (sits in the same `domain/repository/` package). **No changes to the test**.
26. `HouseholdModule.java` facade is unchanged — it re-exports the same two interfaces; the interfaces just gained methods.

### Errors

27. New module exception subclasses extending the existing `HouseholdException` from 01a:
    - `HouseholdInviteNotFoundException` (404, `type = .../household-invite-not-found`)
    - `HouseholdInviteExpiredException` (410, `type = .../household-invite-expired`)
    - `HouseholdInviteRevokedException` (410, `type = .../household-invite-revoked`)
    - `HouseholdInviteAlreadyAcceptedException` (409, `type = .../household-invite-already-accepted`)
28. **Append four new `@ExceptionHandler` methods** to the existing `HouseholdExceptionHandler` `@RestControllerAdvice` from 01a (which is already `@Order(Ordered.HIGHEST_PRECEDENCE)`). Do **NOT** create a second handler class. Do **NOT** modify `config/GlobalExceptionHandler.java`. The 410 status is unusual — confirm `ProblemDetail.forStatusAndDetail(HttpStatus.GONE, ...)` is used, not 404. `OptimisticLockingFailureException` continues handled by `GlobalExceptionHandler`. `UserAlreadyInHouseholdException` (existing from 01a) and `InsufficientHouseholdRoleException` (existing from 01b) are already mapped — no new handler methods needed for those.

## Database

```
src/main/resources/db/migration/V20260601500400__household_create_household_invite.sql   new
```

Schema mirrors [LLD V20260501130300 lines 105-119](../../lld/household.md), renumbered to the household timestamp range (`V20260601500xxx+` after 01b's `V20260601500300__household_create_household_settings_audit.sql`):

```sql
-- V20260601500400
CREATE TABLE household_invite (
    id                       uuid PRIMARY KEY,
    household_id             uuid NOT NULL REFERENCES household(id) ON DELETE CASCADE,
    invite_code              varchar(32) NOT NULL UNIQUE,
    issued_by_user_id        uuid NOT NULL,
    issued_for_user_id       uuid,
    intended_role            varchar(16) NOT NULL DEFAULT 'member',
    expires_at               timestamptz NOT NULL,
    accepted_by_user_id      uuid,
    accepted_at              timestamptz,
    revoked_at               timestamptz,
    version                  bigint NOT NULL DEFAULT 0,
    created_at               timestamptz NOT NULL
);
-- Lookup by code on accept; pending-invites listing for admin UI.
CREATE INDEX idx_household_invite_code
    ON household_invite (invite_code)
    WHERE accepted_at IS NULL AND revoked_at IS NULL;
CREATE INDEX idx_household_invite_household
    ON household_invite (household_id)
    WHERE accepted_at IS NULL AND revoked_at IS NULL;
```

`varchar(32)` width: actual codes from `InviteCodeGenerator` are 16 chars; column carries headroom for future width changes. `intended_role` width follows LLD's `varchar(16)` (matches the household_member.role column from 01a). Lowercase enum values match the existing `HouseholdRole` Java enum (LLD-pinned from 01a).

## OpenAPI updates

### Append to `src/main/resources/openapi/paths/household.yaml`

(File created by 01a, extended by 01b — append four new path-items below 01b's three. Do NOT touch existing path-items.)

```yaml
householdInvitesCurrent:
  get:
    tags: [Households]
    operationId: listPendingHouseholdInvites
    summary: List the calling user's household's pending invites (codes redacted).
    security: [{ cookieAuth: [] }]
    responses:
      '200':
        description: Pending invites; inviteCode is null on every entry.
        content:
          application/json:
            schema:
              type: array
              items: { $ref: '../schemas/household.yaml#/HouseholdInviteDto' }
      '401': { description: Unauthenticated, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '404': { description: Caller not in any household, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
  post:
    tags: [Households]
    operationId: createHouseholdInvite
    summary: Create an invite for the calling user's household (PRIMARY only).
    security: [{ cookieAuth: [] }]
    requestBody:
      required: true
      content:
        application/json:
          schema: { $ref: '../schemas/household.yaml#/CreateInviteRequest' }
    responses:
      '201':
        description: Invite created; the response carries the inviteCode (the only response that does).
        headers:
          Location:
            schema: { type: string, format: uri }
        content:
          application/json:
            schema: { $ref: '../schemas/household.yaml#/HouseholdInviteDto' }
      '400': { description: Validation error, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '401': { description: Unauthenticated, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '403': { description: Caller not primary, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '404': { description: Caller not in any household, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
householdInviteRevoke:
  delete:
    tags: [Households]
    operationId: revokeHouseholdInvite
    summary: Revoke a pending invite (PRIMARY only).
    security: [{ cookieAuth: [] }]
    parameters:
      - in: path
        name: inviteId
        required: true
        schema: { type: string, format: uuid }
    responses:
      '204': { description: Invite revoked. }
      '401': { description: Unauthenticated, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '403': { description: Caller not primary, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '404': { description: Invite not found / belongs to another household, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '409': { description: Invite already accepted or already revoked, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
inviteAccept:
  post:
    tags: [Households]
    operationId: acceptHouseholdInvite
    summary: Accept an invite by code; the accepter joins the inviting household.
    security: [{ cookieAuth: [] }]
    requestBody:
      required: true
      content:
        application/json:
          schema: { $ref: '../schemas/household.yaml#/AcceptInviteRequest' }
    responses:
      '200':
        description: Invite accepted; returns the new HouseholdMemberDto for the accepter.
        content:
          application/json:
            schema: { $ref: '../schemas/household.yaml#/HouseholdMemberDto' }
      '400': { description: Validation error, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '401': { description: Unauthenticated, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '403': { description: Invite issued for a different user, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '404': { description: Invite code not found, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '409': { description: Already accepted or accepter already in a household, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '410': { description: Invite expired or revoked, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
```

### Append to `src/main/resources/openapi/schemas/household.yaml`

```yaml
InviteStatus:
  type: string
  enum: [PENDING, ACCEPTED, REVOKED, EXPIRED]
  description: Derived from acceptedAt / revokedAt / expiresAt; never persisted.
HouseholdInviteDto:
  type: object
  required: [id, householdId, issuedByUserId, intendedRole, expiresAt, status]
  properties:
    id: { type: string, format: uuid }
    householdId: { type: string, format: uuid }
    inviteCode:
      type: string
      maxLength: 32
      nullable: true
      description: Returned non-null ONLY on the create response. Always null on list / accept responses.
    issuedByUserId: { type: string, format: uuid }
    issuedForUserId:
      type: string
      format: uuid
      nullable: true
    intendedRole: { $ref: '#/HouseholdRole' }
    expiresAt: { type: string, format: date-time }
    acceptedAt:
      type: string
      format: date-time
      nullable: true
    revokedAt:
      type: string
      format: date-time
      nullable: true
    status: { $ref: '#/InviteStatus' }
CreateInviteRequest:
  type: object
  required: [intendedRole, expiresAt]
  properties:
    issuedForUserId:
      type: string
      format: uuid
      nullable: true
    intendedRole: { $ref: '#/HouseholdRole' }
    expiresAt:
      type: string
      format: date-time
      description: Must be in the future. Server caps at now + 30 days; values beyond the cap are silently truncated.
AcceptInviteRequest:
  type: object
  required: [inviteCode]
  properties:
    inviteCode:
      type: string
      minLength: 1
      maxLength: 32
```

**Gotcha applied**: `inviteCode`, `issuedForUserId`, `acceptedAt`, `revokedAt` use **inline** `nullable: true` (NOT `$ref + nullable: true` — sibling keywords on `$ref` are silently ignored by swagger-parser per the agent-prompt-template gotcha list).

### Append to entry `src/main/resources/openapi/openapi.yaml`

**Location**: under the existing `# household` block in `paths:` (after 01b's `householdSettings` / `householdSettingsAuditLog` / `householdSlotConfiguration` lines and before the next module's block — household is alphabetically before nutrition / preference / provisions / recipe). Append four new path-item refs:

```yaml
  /api/v1/households/current/invites:
    $ref: 'paths/household.yaml#/householdInvitesCurrent'
  /api/v1/households/current/invites/{inviteId}:
    $ref: 'paths/household.yaml#/householdInviteRevoke'
  /api/v1/invites/accept:
    $ref: 'paths/household.yaml#/inviteAccept'
```

**Location**: under `components.schemas:`, append three new schema refs in the existing `# household` block (alphabetically — `AcceptInviteRequest`, `CreateInviteRequest`, `HouseholdInviteDto`, `InviteStatus`):

```yaml
    AcceptInviteRequest: { $ref: 'schemas/household.yaml#/AcceptInviteRequest' }
    CreateInviteRequest: { $ref: 'schemas/household.yaml#/CreateInviteRequest' }
    HouseholdInviteDto: { $ref: 'schemas/household.yaml#/HouseholdInviteDto' }
    InviteStatus: { $ref: 'schemas/household.yaml#/InviteStatus' }
```

## Verbatim shape snippets

### Entity

Mirrors 01a's `Household` shape. No JSONB; no parent-child collection.

```java
@Entity
@Table(name = "household_invite")
@Getter @Setter @Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class HouseholdInvite {

  @Id @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @Column(name = "household_id", nullable = false, updatable = false)
  private UUID householdId;

  @Column(name = "invite_code", nullable = false, updatable = false, unique = true, length = 32)
  private String inviteCode;

  @Column(name = "issued_by_user_id", nullable = false, updatable = false)
  private UUID issuedByUserId;

  @Column(name = "issued_for_user_id")
  private UUID issuedForUserId;

  @Enumerated(EnumType.STRING)
  @Column(name = "intended_role", nullable = false, length = 16)
  private HouseholdRole intendedRole;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(name = "accepted_by_user_id")
  private UUID acceptedByUserId;

  @Column(name = "accepted_at")
  private Instant acceptedAt;

  @Column(name = "revoked_at")
  private Instant revokedAt;

  @Version @Column(name = "version", nullable = false)
  private long version;

  @CreationTimestamp
  @Column(name = "created_at", updatable = false, nullable = false)
  private Instant createdAt;
}
```

### Repository — package-private

```java
interface HouseholdInviteRepository extends JpaRepository<HouseholdInvite, UUID> {
  Optional<HouseholdInvite> findByInviteCode(String inviteCode);
  List<HouseholdInvite> findByHouseholdIdAndAcceptedAtIsNullAndRevokedAtIsNullOrderByCreatedAtDesc(
      UUID householdId);
}
```

### `InviteCodeGenerator`

```java
@Component
public class InviteCodeGenerator {
  private static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"; // 31 chars; no 0/O/1/I/L
  private static final int CODE_LENGTH = 16;
  private final SecureRandom random = new SecureRandom();

  public String generate() {
    StringBuilder out = new StringBuilder(CODE_LENGTH);
    for (int i = 0; i < CODE_LENGTH; i++) {
      out.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
    }
    return out.toString();
  }
}
```

### Mapper — `inviteCode` redacted on list responses

```java
@Mapper(componentModel = "spring")
public interface HouseholdInviteMapper {
  /** Used by createInvite — code is included in the response (the only path that ever exposes it). */
  @Mapping(target = "status", expression = "java(deriveStatus(entity))")
  HouseholdInviteDto toDtoWithCode(HouseholdInvite entity);

  /** Used by listPendingInvites — code is always null. */
  @Mapping(target = "inviteCode", ignore = true)
  @Mapping(target = "status", expression = "java(deriveStatus(entity))")
  HouseholdInviteDto toDtoCodeRedacted(HouseholdInvite entity);

  default List<HouseholdInviteDto> toDtosCodeRedacted(List<HouseholdInvite> es) {
    return es.stream().map(this::toDtoCodeRedacted).toList();
  }

  default InviteStatus deriveStatus(HouseholdInvite e) {
    if (e.getRevokedAt() != null) return InviteStatus.REVOKED;
    if (e.getAcceptedAt() != null) return InviteStatus.ACCEPTED;
    if (e.getExpiresAt().isBefore(Instant.now())) return InviteStatus.EXPIRED;
    return InviteStatus.PENDING;
  }
}
```

## Edge-case checklist

- [ ] `POST /current/invites` by a `PRIMARY` of the caller's household → 201, response body `inviteCode` non-null and 16 chars from the generator's alphabet
- [ ] `POST /current/invites` by a `MEMBER` (non-primary) → 403 `insufficient-household-role`
- [ ] `POST /current/invites` by a user not in any household → 404 `household-not-found`
- [ ] `POST /current/invites` with `expiresAt` 60 days in the future → 201, persisted `expiresAt` is exactly `now + 30 days` (capped, not rejected)
- [ ] `POST /current/invites` with `expiresAt` in the past → 400 (Jakarta `@Future` rejects)
- [ ] `POST /current/invites` validation: `intendedRole = null` → 400; `expiresAt = null` → 400
- [ ] `GET /current/invites` returns only `where accepted_at IS NULL AND revoked_at IS NULL`, sorted `createdAt DESC`, **every entry has `inviteCode = null`**
- [ ] `GET /current/invites` for a `MEMBER` (non-primary) → 200 (read is open to any role)
- [ ] `DELETE /current/invites/{inviteId}` for an invite belonging to a different household → 404 (not 403; don't leak existence)
- [ ] `DELETE /current/invites/{inviteId}` for an already-accepted invite → 409 `household-invite-already-accepted`
- [ ] `DELETE /current/invites/{inviteId}` for an already-revoked invite → 409 (do not silently re-revoke)
- [ ] `POST /api/v1/invites/accept` with a non-existent code → 404 `household-invite-not-found`
- [ ] `POST /api/v1/invites/accept` with a revoked invite → **410** `household-invite-revoked`
- [ ] `POST /api/v1/invites/accept` with an expired invite → **410** `household-invite-expired`
- [ ] `POST /api/v1/invites/accept` with an already-accepted invite → 409 `household-invite-already-accepted`
- [ ] `POST /api/v1/invites/accept` where `issuedForUserId != accepterUserId` → 403 `insufficient-household-role`
- [ ] `POST /api/v1/invites/accept` by an accepter already in a household → 409 `user-already-in-household`
- [ ] `POST /api/v1/invites/accept` happy path → 200 with `HouseholdMemberDto`; member row exists with `role = invite.intendedRole`, `priority = 100`, `joinedAt` set; invite row has `acceptedAt`, `acceptedByUserId` set
- [ ] `POST /api/v1/invites/accept` where `intendedRole = PRIMARY` and the household already has a primary → 409 (the partial unique index `idx_household_member_one_primary` rejects; mapped via `GlobalExceptionHandler`'s data-integrity handler)
- [ ] `InviteCodeGenerator` produces 16-char codes from the 31-char alphabet (no `0`/`O`/`1`/`I`/`L`); 10,000-call distinctness test
- [ ] `InviteCodeGenerator` collision retry: simulated duplicate via mocked repo throwing `DataIntegrityViolationException` once → succeeds on the second attempt; three retries → propagates
- [ ] `HouseholdInviteCreatedEvent` published `AFTER_COMMIT` exactly once on create
- [ ] `HouseholdInviteAcceptedEvent` published `AFTER_COMMIT` exactly once on accept (and `HouseholdMemberAddedEvent` is **NOT** published — that's 01e's path)
- [ ] OpenAPI request/response shapes match (swagger-request-validator filter active in IT)
- [ ] `HouseholdBoundaryTest` (from 01a) still passes — new repo in `domain/repository/` package
- [ ] `HouseholdExceptionHandler` retains `@Order(Ordered.HIGHEST_PRECEDENCE)` after appending the four new handler methods
- [ ] No raw `userId` accepted from request body / query — server-resolved via `CurrentUserResolver` on every endpoint

## Files this ticket touches

```
NEW   src/main/resources/db/migration/V20260601500400__household_create_household_invite.sql

NEW   src/main/java/com/example/mealprep/household/api/controller/HouseholdInvitesController.java
NEW   src/main/java/com/example/mealprep/household/api/dto/HouseholdInviteDto.java
NEW   src/main/java/com/example/mealprep/household/api/dto/CreateInviteRequest.java
NEW   src/main/java/com/example/mealprep/household/api/dto/AcceptInviteRequest.java
NEW   src/main/java/com/example/mealprep/household/api/dto/InviteStatus.java
NEW   src/main/java/com/example/mealprep/household/api/mapper/HouseholdInviteMapper.java
NEW   src/main/java/com/example/mealprep/household/domain/entity/HouseholdInvite.java
NEW   src/main/java/com/example/mealprep/household/domain/repository/HouseholdInviteRepository.java
NEW   src/main/java/com/example/mealprep/household/domain/service/internal/InviteCodeGenerator.java
NEW   src/main/java/com/example/mealprep/household/event/HouseholdInviteCreatedEvent.java
NEW   src/main/java/com/example/mealprep/household/event/HouseholdInviteAcceptedEvent.java
NEW   src/main/java/com/example/mealprep/household/exception/HouseholdInviteNotFoundException.java
NEW   src/main/java/com/example/mealprep/household/exception/HouseholdInviteExpiredException.java
NEW   src/main/java/com/example/mealprep/household/exception/HouseholdInviteRevokedException.java
NEW   src/main/java/com/example/mealprep/household/exception/HouseholdInviteAlreadyAcceptedException.java

MOD   src/main/java/com/example/mealprep/household/api/HouseholdExceptionHandler.java                (append 4 @ExceptionHandler methods; KEEP @Order(Ordered.HIGHEST_PRECEDENCE))
MOD   src/main/java/com/example/mealprep/household/domain/service/HouseholdQueryService.java         (append listPendingInvites, getInviteByCode)
MOD   src/main/java/com/example/mealprep/household/domain/service/HouseholdUpdateService.java        (append createInvite, acceptInvite, revokeInvite)
MOD   src/main/java/com/example/mealprep/household/domain/service/internal/HouseholdServiceImpl.java (implement the three new write methods + two new read methods)

MOD   src/main/resources/openapi/paths/household.yaml      (append 4 new path-items below 01b's; do NOT touch existing)
MOD   src/main/resources/openapi/schemas/household.yaml    (append 4 new schemas: InviteStatus, HouseholdInviteDto, CreateInviteRequest, AcceptInviteRequest)
MOD   src/main/resources/openapi/openapi.yaml              (3 lines under paths: in the `# household` block; 4 lines under components.schemas: in the `# household` block)

NEW   src/test/java/com/example/mealprep/household/HouseholdInvitesServiceTest.java
NEW   src/test/java/com/example/mealprep/household/HouseholdInvitesFlowIT.java
NEW   src/test/java/com/example/mealprep/household/InviteCodeGeneratorTest.java
MOD   src/test/java/com/example/mealprep/household/testdata/HouseholdTestData.java                   (append invite-builder fixture)
```

**Files this ticket does NOT modify** (cross-cutting; sibling round-3 tickets running in parallel must not collide):
- `src/main/java/com/example/mealprep/config/GlobalExceptionHandler.java` — module exceptions go in the existing `HouseholdExceptionHandler`.
- `src/test/java/com/example/mealprep/archunit/ModuleBoundaryTest.java` — module boundary rule lives in the existing `HouseholdBoundaryTest`.
- Other modules' `paths/*.yaml`, `schemas/*.yaml`, `<module>ExceptionHandler.java`, `<module>BoundaryTest.java`, migrations, entities — none touched.
- 01a's `Household` entity / 01b's `HouseholdSettings` entity — invites are a separate aggregate root; no `@OneToMany` added to existing entities.
- `HouseholdBoundaryTest` is unchanged (new repo lives in the same `domain/repository` package; rule already covers it).
- 01a's `HouseholdsController` and 01b's `HouseholdSettingsController` — invites get their own controller.

## Dependencies

- **Hard dependency**: `household-01a` (merged) — `Household`, `HouseholdMember`, `HouseholdMemberRepository`, `HouseholdRole`, `HouseholdMemberDto`, `HouseholdQueryService`, `HouseholdUpdateService`, `HouseholdExceptionHandler`, `HouseholdBoundaryTest`, `HouseholdException`, `HouseholdNotFoundException`, `UserAlreadyInHouseholdException`, `idx_household_member_one_primary` partial unique index.
- **Hard dependency**: `household-01b` (merged) — extends the same two service interfaces; reuses `InsufficientHouseholdRoleException`; the `@ExceptionHandler` ordering pattern; the per-module YAML / advice append-only convention.
- **Hard dependency**: `auth-01a` (merged) — `CurrentUserResolver`, `SessionAuthenticationFilter`.
- **Hard dependency**: `refactor-01-split-merge-zones` (merged) — per-module YAML / advice / boundary-test layout.
- **Sibling tickets running in parallel** (Wave 2 round 3): `nutrition-01c`, `provisions-01c`, `recipe-01c`. None should touch any household file or any of the cross-cutting files listed above. Only collision point is the entry `openapi.yaml`; this ticket appends in the `# household` block, sibling tickets append in their own module's block.

## Acceptance / DoD

- [ ] `./mvnw -Dspotless.check.skip=true verify` passes locally on the agent's worktree
- [ ] `./mvnw spotless:apply` then `./mvnw spotless:check` clean
- [ ] CI green on the PR (build + spotless + OpenAPI lint + ArchUnit gate)
- [ ] All edge-case items above ticked
- [ ] `HouseholdExceptionHandler` retains `@Order(Ordered.HIGHEST_PRECEDENCE)` after the four new methods are appended (gotcha: without it, `GlobalExceptionHandler`'s catch-all swallows 403/404/409/410 into 500)
- [ ] `InviteCodeGenerator` uses `SecureRandom`, NOT `java.util.Random` (security-critical — verify by reflection or by reading the implementation in code review)
- [ ] OpenAPI 3.0 nullable fields use **inline** `nullable: true` (NOT `$ref + nullable: true`) on `inviteCode`, `issuedForUserId`, `acceptedAt`, `revokedAt`
- [ ] No regression on existing tests, including 01a's `HouseholdsFlowIT` and 01b's `HouseholdSettingsFlowIT`
- [ ] No N+1 in `listPendingInvites` — single SELECT verified via Hibernate stats or statement-count assertion in IT
- [ ] No pom.xml dependency adds (uses only `java.security.SecureRandom` + existing `spring-boot-starter-data-jpa` + existing JPA / MapStruct)

## What's NOT in scope

- `HouseholdMergeService`, `MergedSoftPreferencesDto`, `SoftPreferenceMerger` → **household-01d**
- `addMember`, `updateMember`, `removeMember`, `changeRole` REST endpoints + `LastPrimaryRemovalException` enforcement + `HouseholdMemberAddedEvent` / `HouseholdMemberRemovedEvent` / `HouseholdRoleChangedEvent` for the dedicated member-admin paths → **household-01e**
- Email-based invites (Out of Scope per LLD)
- Invite-link landing page / pre-signup invite redemption (frontend)
- `HouseholdMemberAddedEvent` emission from the accept flow (deferred to 01e per the divergence note above)
- Bulk-invite endpoint, invite resend, reminder cron — none specified in the LLD

Squash-merge with: `feat(household): 01c — invites aggregate + create/list/revoke/accept endpoints + InviteCodeGenerator`
