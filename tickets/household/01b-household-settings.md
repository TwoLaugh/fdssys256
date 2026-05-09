# Ticket: household — 01b Household Settings Aggregate

## Summary

Layer the per-household settings concern on top of the 01a aggregate: `HouseholdSettings` (one-row-per-household root, `document` JSONB mirrored by `HouseholdSettingsDocument`) + append-only `HouseholdSettingsAuditLog` + the `PUT /api/v1/households/{id}/settings` replacement endpoint + the paginated `GET /api/v1/households/{id}/settings/audit-log` reader. Per-section diff writes one audit row per changed `fieldPath` in the same `@Transactional` as the document replacement; `HouseholdSettingsChangedEvent` publishes `AFTER_COMMIT` carrying `Set<String> changedFieldPaths`. Per [`lld/household.md`](../../lld/household.md) §V20260501130200, §`HouseholdSettingsDto`, §Service Interfaces (`updateSettings`, `getSettings`, `getSettingsAuditLog`), §Flow 6 (Update settings), §Events.

**Defers to later household tickets**: `HouseholdInvite` + invite create/list/revoke/accept (→ household-01c); `HouseholdMergeService` + soft-preference merge (→ household-01d, depends on preference); member CRUD endpoints (`addMember`, `updateMember`, `removeMember`, `changeRole`) + the four other published events (→ household-01e); `SlotConfigurationDto` + `GET /slot-configuration` endpoint + `SlotConfigurationResolver` (→ also household-01b — bundled here because the document shape and the resolver share the same `SlotKind` + `SlotDefault` records).

01b unblocks any caller that needs to read shared-vs-individual slot configuration — the planner is the obvious consumer; the call lands in 01b's `SlotConfigurationResolver`.

## Behavioural spec

### Aggregate shape

1. `HouseholdSettings` is a separate aggregate root (NOT a child of `Household` for cascade purposes — this LLD §Entities lists it as `@OneToOne` from `Household` but in 01b we model it as a standalone root linked by `householdId UNIQUE FK`. Justification: the 01a `Household` entity was shipped without an `@OneToOne settings` field; adding it now would force a re-write of 01a's `HouseholdMapper`. **LLD divergence noted**.) Fields: `id (UUID, application-set), householdId (UUID, NOT NULL UNIQUE FK → household.id ON DELETE CASCADE), document (JSONB → HouseholdSettingsDocument), version (@Version Long), createdAt (@CreatedDate), updatedAt (@LastModifiedDate)`.
2. `HouseholdSettingsAuditLog` — append-only. Fields: `id, householdSettingsId (UUID NOT NULL FK → household_settings.id ON DELETE CASCADE), actorUserId (UUID NOT NULL), fieldPath (varchar 128), previousValueJson (JsonNode JSONB NOT NULL), newValueJson (JsonNode JSONB NOT NULL), occurredAt (Instant NOT NULL)`. No `@Version`, no `@LastModifiedDate`. Index `(household_settings_id, occurred_at DESC)`.
3. `HouseholdSettingsDocument` Java mirror per [LLD lines 144-156](../../lld/household.md):
   ```java
   public record HouseholdSettingsDocument(
       Map<SlotKind, SlotDefault> slotDefaults,
       List<CustomSlotDefinition> customSlots,
       Integer defaultHeadcount,
       HouseholdSchedulingPreferences scheduling
   ) {
     public record SlotDefault(boolean shared, Integer headcount, Integer timeBudgetMin) {}
     public record CustomSlotDefinition(String key, String label, SlotKind backedByKind,
                                        boolean shared, Integer headcount, Integer timeBudgetMin) {}
     public record HouseholdSchedulingPreferences() {}
   }
   ```
4. `SlotKind` enum module-local (`BREAKFAST`, `LUNCH`, `DINNER`, `SNACK`, `CUSTOM`).

### `getSettings`

5. `GET /api/v1/households/{id}/settings` returns `HouseholdSettingsDto` (200) or 404 `HouseholdSettingsNotFoundException`. Cookie-auth required. Authorisation: caller must be a member of `{id}` — reject as 404 (don't leak existence) when not.
6. Repository: `householdSettingsRepository.findByHouseholdId(UUID)`. Single SELECT — JSONB document loaded eagerly (one column).

### `updateSettings` (Flow 6 — primary-only, version-checked, per-field audit)

7. `PUT /api/v1/households/{id}/settings` accepts `UpdateHouseholdSettingsRequest { @NotNull @Valid HouseholdSettingsDocument document, long expectedVersion }`. **Authorisation: caller must be `PRIMARY` of `{id}`** — else 403 `InsufficientHouseholdRoleException`. Membership lookup via `householdMemberRepository.findByUserId(callerUserId)` + role check.
8. Single `@Transactional` write: load existing (404 if missing); stale `expectedVersion` → `OptimisticLockException` mapped by `GlobalExceptionHandler` to 409 (no new advice needed).
9. **Per-section diff**: walk top-level fields of `HouseholdSettingsDocument` AND every key of `slotDefaults` AND every entry of `customSlots` (keyed by `key`). For each changed path, write one `HouseholdSettingsAuditLog` row with `fieldPath` like:
   - `defaultHeadcount`
   - `slotDefaults.dinner.shared`
   - `slotDefaults.dinner.headcount`
   - `customSlots.late-snack.timeBudgetMin`
   - `scheduling`
   `previousValueJson` / `newValueJson` carry the value at that path serialised via Jackson (`ObjectMapper.valueToTree`). No row written for no-op fields.
10. Replace the JSONB document wholesale (the LLD specifies "read whole, written via single PUT"). Bump `@Version`. `actorUserId = callerUserId` on every audit row.
11. **Gotcha — replaceChildren-style flush trap doesn't apply here** because `HouseholdSettingsAuditLog` is append-only with surrogate-PK only (no `(parent, business_key)` unique constraint). Inserting N audit rows + updating the settings root in one tx is safe.
12. Publish `HouseholdSettingsChangedEvent(UUID householdId, UUID settingsId, Set<String> changedFieldPaths, UUID traceId, Instant occurredAt)` `AFTER_COMMIT`. `traceId` from MDC if present, else `UUID.randomUUID()`. **Skip the publish entirely when `changedFieldPaths.isEmpty()`** — no-op PUTs (re-submit of identical doc) emit no event.

### `getSettingsAuditLog`

13. `GET /api/v1/households/{id}/settings/audit-log?page=&size=` returns paginated `Page<HouseholdSettingsAuditEntryDto>` newest-first. Spring `Pageable` with default size 20, max 100. Authorisation: caller must be a member of `{id}` (any role; non-PRIMARY can read history). 404 if no settings row exists for `{id}`.

### Default-on-create — settings auto-creation hook

14. **Locked decision**: 01b does NOT retro-create settings rows for households that landed via 01a. Instead, the first `GET /settings` for an 01a-era household returns 404; the user must `PUT /settings` once to seed. **Alternative considered**: a follow-up migration `INSERT INTO household_settings (id, household_id, document, ...) SELECT ...` for every existing `household` row. **Rejected** — keeps the migration small and 01a-era households are an empty set in production (pre-launch). The flow 1 (`createHousehold`) write path **is updated** in 01b: append `householdSettingsRepository.save(defaultSettings)` to the `createHousehold` transaction so newly-created households get a settings row. Default document: built-in slot kinds (`BREAKFAST`, `LUNCH`, `DINNER`, `SNACK`) all `shared=true, headcount=1, timeBudgetMin=30` (LLD Flow 1 line 406). **Modifies** `HouseholdServiceImpl.createHousehold` from 01a (one-line addition).

### `SlotConfigurationDto` + `GET /slot-configuration`

15. `SlotConfigurationDto` per [LLD lines 211-216](../../lld/household.md):
    ```java
    public record SlotConfigurationDto(UUID householdId, List<SlotConfigEntryDto> slots, List<UUID> allEaterUserIds) {
      public record SlotConfigEntryDto(String slotKey, SlotKind kind, boolean shared,
                                       int headcount, int timeBudgetMin,
                                       List<UUID> eaterUserIdsIfPerPerson /* null when shared */) {}
    }
    ```
16. `GET /api/v1/households/{id}/slot-configuration` returns the resolved view. Implemented by `SlotConfigurationResolver` (in `domain/service/internal/`): walks `slotDefaults` (one entry per built-in `SlotKind` actually present) + appends one `SlotConfigEntryDto` per entry of `customSlots`; `eaterUserIdsIfPerPerson = null` when `shared`, else the household's full member list (from `householdMemberRepository.findAllByHouseholdId`). `allEaterUserIds` always carries the full member list.
17. `NutritionQueryService` / `PreferenceQueryService` are NOT injected here — 01b's resolver is read-only over household state.

### Cross-module facade + boundary

18. Append `getSettings(UUID householdId): Optional<HouseholdSettingsDto>`, `getSettingsAuditLog(UUID, Pageable)`, and `getSlotConfiguration(UUID): SlotConfigurationDto` to the existing `HouseholdQueryService` interface from 01a. Append `updateSettings(UUID householdId, UUID actorUserId, UpdateHouseholdSettingsRequest)` to the existing `HouseholdUpdateService` interface from 01a. **Modifies** the two interfaces (additive only — sibling 01c/01d/01e tickets append further methods later, no collision).
19. Repositories `HouseholdSettingsRepository`, `HouseholdSettingsAuditLogRepository` are **package-private** per 01a's pattern. Cross-module callers go through the service interface.
20. The existing `HouseholdBoundaryTest` from 01a continues to enforce the rule — the new repos sit in `domain/repository/` so they're already covered. **No changes to the test**.

### Errors

21. New module exception subclasses extending the existing `HouseholdException` from 01a:
    - `HouseholdSettingsNotFoundException` (404, `type = .../household-settings-not-found`)
    - `InsufficientHouseholdRoleException` (403, `type = .../insufficient-household-role`)
22. **Append** two new `@ExceptionHandler` methods to the existing `HouseholdExceptionHandler` `@RestControllerAdvice` from 01a (which is already `@Order(Ordered.HIGHEST_PRECEDENCE)`). Do **NOT** create a second handler class. Do **NOT** modify `config/GlobalExceptionHandler.java`. `OptimisticLockingFailureException` continues handled by `GlobalExceptionHandler`.

## Database

```
src/main/resources/db/migration/V20260601500200__household_create_household_settings.sql       new
src/main/resources/db/migration/V20260601500300__household_create_household_settings_audit.sql new
```

Schema mirrors [LLD lines 86-103](../../lld/household.md) renumbered to the household timestamp range (`V20260601500xxx+`):

```sql
-- V20260601500200
CREATE TABLE household_settings (
    id              uuid PRIMARY KEY,
    household_id    uuid NOT NULL UNIQUE REFERENCES household(id) ON DELETE CASCADE,
    document        jsonb NOT NULL,
    version         bigint NOT NULL DEFAULT 0,
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL
);
-- The UNIQUE on household_id is the only access path needed; a separate index
-- would duplicate it. Skipped.

-- V20260601500300
CREATE TABLE household_settings_audit (
    id                      uuid PRIMARY KEY,
    household_settings_id   uuid NOT NULL REFERENCES household_settings(id) ON DELETE CASCADE,
    actor_user_id           uuid NOT NULL,
    field_path              varchar(128) NOT NULL,
    previous_value_json     jsonb NOT NULL,
    new_value_json          jsonb NOT NULL,
    occurred_at             timestamptz NOT NULL
);
CREATE INDEX idx_household_settings_audit_hs_time
    ON household_settings_audit (household_settings_id, occurred_at DESC);
```

`field_path` width: dotted paths like `customSlots.<48-char-key>.timeBudgetMin` could reach ~70 chars; LLD's `varchar(128)` carries headroom. Computed, not parroted.

## OpenAPI updates

### Append to `src/main/resources/openapi/paths/household.yaml`

(File created by 01a — append three new path-items; do NOT touch `households` / `currentHousehold` from 01a.)

```yaml
householdSettings:
  get:
    tags: [Households]
    operationId: getHouseholdSettings
    summary: Return a household's settings document.
    security: [{ cookieAuth: [] }]
    parameters:
      - in: path
        name: householdId
        required: true
        schema: { type: string, format: uuid }
    responses:
      '200':
        description: Settings.
        content:
          application/json:
            schema: { $ref: '../schemas/household.yaml#/HouseholdSettingsDto' }
      '401': { description: Unauthenticated, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '404': { description: Settings not found or caller not a member, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
  put:
    tags: [Households]
    operationId: updateHouseholdSettings
    summary: Replace a household's settings document (primary-only).
    security: [{ cookieAuth: [] }]
    parameters:
      - in: path
        name: householdId
        required: true
        schema: { type: string, format: uuid }
    requestBody:
      required: true
      content:
        application/json:
          schema: { $ref: '../schemas/household.yaml#/UpdateHouseholdSettingsRequest' }
    responses:
      '200': { description: Updated, content: { application/json: { schema: { $ref: '../schemas/household.yaml#/HouseholdSettingsDto' } } } }
      '400': { description: Validation error, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '401': { description: Unauthenticated, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '403': { description: Caller not primary, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '404': { description: Settings not found, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '409': { description: Stale expectedVersion, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
householdSettingsAuditLog:
  get:
    tags: [Households]
    operationId: getHouseholdSettingsAuditLog
    summary: Paginated audit-log of changes to a household's settings.
    security: [{ cookieAuth: [] }]
    parameters:
      - in: path
        name: householdId
        required: true
        schema: { type: string, format: uuid }
      - in: query
        name: page
        schema: { type: integer, minimum: 0, default: 0 }
      - in: query
        name: size
        schema: { type: integer, minimum: 1, maximum: 100, default: 20 }
    responses:
      '200': { description: Audit-log page, content: { application/json: { schema: { $ref: '../schemas/household.yaml#/HouseholdSettingsAuditEntryDtoPage' } } } }
      '401': { description: Unauthenticated, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '404': { description: Settings not found or caller not a member, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
householdSlotConfiguration:
  get:
    tags: [Households]
    operationId: getHouseholdSlotConfiguration
    summary: Return resolved slot configuration (defaults + custom slots + eater lists).
    security: [{ cookieAuth: [] }]
    parameters:
      - in: path
        name: householdId
        required: true
        schema: { type: string, format: uuid }
    responses:
      '200': { description: Resolved configuration, content: { application/json: { schema: { $ref: '../schemas/household.yaml#/SlotConfigurationDto' } } } }
      '401': { description: Unauthenticated, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '404': { description: Household not found or caller not a member, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
```

### Append to `src/main/resources/openapi/schemas/household.yaml`

```yaml
SlotKind:
  type: string
  enum: [breakfast, lunch, dinner, snack, custom]
SlotDefault:
  type: object
  required: [shared]
  properties:
    shared: { type: boolean }
    headcount: { type: integer, minimum: 1, maximum: 16, nullable: true }
    timeBudgetMin: { type: integer, minimum: 0, maximum: 480, nullable: true }
CustomSlotDefinition:
  type: object
  required: [key, label, backedByKind, shared]
  properties:
    key: { type: string, minLength: 1, maxLength: 48, pattern: '^[a-z0-9-]+$' }
    label: { type: string, minLength: 1, maxLength: 64 }
    backedByKind: { $ref: '#/SlotKind' }
    shared: { type: boolean }
    headcount: { type: integer, minimum: 1, maximum: 16, nullable: true }
    timeBudgetMin: { type: integer, minimum: 0, maximum: 480, nullable: true }
HouseholdSchedulingPreferences:
  type: object
  additionalProperties: false
  description: Reserved for v2 per-day overrides; empty object in v1.
HouseholdSettingsDocument:
  type: object
  required: [slotDefaults, customSlots]
  properties:
    slotDefaults:
      type: object
      additionalProperties: { $ref: '#/SlotDefault' }
    customSlots:
      type: array
      items: { $ref: '#/CustomSlotDefinition' }
    defaultHeadcount: { type: integer, minimum: 1, maximum: 16, nullable: true }
    scheduling: { $ref: '#/HouseholdSchedulingPreferences' }
HouseholdSettingsDto:
  type: object
  required: [id, householdId, document, version, createdAt]
  properties:
    id: { type: string, format: uuid }
    householdId: { type: string, format: uuid }
    document: { $ref: '#/HouseholdSettingsDocument' }
    version: { type: integer, format: int64 }
    createdAt: { type: string, format: date-time }
UpdateHouseholdSettingsRequest:
  type: object
  required: [document, expectedVersion]
  properties:
    document: { $ref: '#/HouseholdSettingsDocument' }
    expectedVersion: { type: integer, format: int64, minimum: 0 }
HouseholdSettingsAuditEntryDto:
  type: object
  required: [id, actorUserId, fieldPath, previousValue, newValue, occurredAt]
  properties:
    id: { type: string, format: uuid }
    actorUserId: { type: string, format: uuid }
    fieldPath: { type: string, maxLength: 128 }
    previousValue: {}    # arbitrary JSON
    newValue: {}
    occurredAt: { type: string, format: date-time }
HouseholdSettingsAuditEntryDtoPage:
  type: object
  additionalProperties: true       # gotcha: Spring Page<T> ships pageable + sort
  required: [content, page]
  properties:
    content:
      type: array
      items: { $ref: '#/HouseholdSettingsAuditEntryDto' }
    page:
      type: object
      additionalProperties: true
      properties:
        number: { type: integer, minimum: 0 }
        size: { type: integer, minimum: 1 }
        totalElements: { type: integer, format: int64 }
        totalPages: { type: integer }
SlotConfigEntryDto:
  type: object
  required: [slotKey, kind, shared, headcount, timeBudgetMin]
  properties:
    slotKey: { type: string, minLength: 1, maxLength: 48 }
    kind: { $ref: '#/SlotKind' }
    shared: { type: boolean }
    headcount: { type: integer, minimum: 1, maximum: 16 }
    timeBudgetMin: { type: integer, minimum: 0, maximum: 480 }
    eaterUserIdsIfPerPerson:
      type: array
      items: { type: string, format: uuid }
      nullable: true
SlotConfigurationDto:
  type: object
  required: [householdId, slots, allEaterUserIds]
  properties:
    householdId: { type: string, format: uuid }
    slots:
      type: array
      items: { $ref: '#/SlotConfigEntryDto' }
    allEaterUserIds:
      type: array
      items: { type: string, format: uuid }
```

**Gotcha applied**: `HouseholdSettingsAuditEntryDtoPage` includes `additionalProperties: true` so Spring's `Page<T>` `pageable` and `sort` properties pass validation.

**Gotcha applied**: nullable scalars use inline `nullable: true` (not `$ref + nullable: true`).

### Append to entry `src/main/resources/openapi/openapi.yaml`

Three lines under `paths:` (under the existing 01a household refs):

```yaml
  /api/v1/households/{householdId}/settings:
    $ref: 'paths/household.yaml#/householdSettings'
  /api/v1/households/{householdId}/settings/audit-log:
    $ref: 'paths/household.yaml#/householdSettingsAuditLog'
  /api/v1/households/{householdId}/slot-configuration:
    $ref: 'paths/household.yaml#/householdSlotConfiguration'
```

~10 lines under `components.schemas:` (one per new schema name), append-only — do NOT touch the 01a household block:

```yaml
    SlotKind: { $ref: 'schemas/household.yaml#/SlotKind' }
    SlotDefault: { $ref: 'schemas/household.yaml#/SlotDefault' }
    CustomSlotDefinition: { $ref: 'schemas/household.yaml#/CustomSlotDefinition' }
    HouseholdSchedulingPreferences: { $ref: 'schemas/household.yaml#/HouseholdSchedulingPreferences' }
    HouseholdSettingsDocument: { $ref: 'schemas/household.yaml#/HouseholdSettingsDocument' }
    HouseholdSettingsDto: { $ref: 'schemas/household.yaml#/HouseholdSettingsDto' }
    UpdateHouseholdSettingsRequest: { $ref: 'schemas/household.yaml#/UpdateHouseholdSettingsRequest' }
    HouseholdSettingsAuditEntryDto: { $ref: 'schemas/household.yaml#/HouseholdSettingsAuditEntryDto' }
    SlotConfigEntryDto: { $ref: 'schemas/household.yaml#/SlotConfigEntryDto' }
    SlotConfigurationDto: { $ref: 'schemas/household.yaml#/SlotConfigurationDto' }
```

## Verbatim shape snippets

### Settings entity — JSONB document via hypersistence-utils

Mirrors `core/audit/domain/entity/DecisionLog.java` for the JSONB pattern.

```java
@Entity
@Table(name = "household_settings")
@Getter @Setter @Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class HouseholdSettings {

  @Id @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @Column(name = "household_id", nullable = false, updatable = false)
  private UUID householdId;

  @Type(JsonBinaryType.class)
  @Column(name = "document", nullable = false, columnDefinition = "jsonb")
  private HouseholdSettingsDocument document;

  @Version @Column(name = "version", nullable = false)
  private long version;

  @CreationTimestamp @Column(name = "created_at", updatable = false, nullable = false)
  private Instant createdAt;
  @UpdateTimestamp  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;
}
```

### Audit-log entity (JSONB before/after)

```java
@Entity @Table(name = "household_settings_audit")
@Getter @Setter @Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class HouseholdSettingsAuditLog {
  @Id @Column(name = "id", updatable = false, nullable = false) private UUID id;

  @Column(name = "household_settings_id", nullable = false) private UUID householdSettingsId;
  @Column(name = "actor_user_id",          nullable = false) private UUID actorUserId;
  @Column(name = "field_path", nullable = false, length = 128) private String fieldPath;

  @Type(JsonBinaryType.class)
  @Column(name = "previous_value_json", nullable = false, columnDefinition = "jsonb")
  private JsonNode previousValueJson;

  @Type(JsonBinaryType.class)
  @Column(name = "new_value_json", nullable = false, columnDefinition = "jsonb")
  private JsonNode newValueJson;

  @Column(name = "occurred_at", nullable = false) private Instant occurredAt;
}
```

### Repositories — package-private

```java
interface HouseholdSettingsRepository extends JpaRepository<HouseholdSettings, UUID> {
  Optional<HouseholdSettings> findByHouseholdId(UUID householdId);
}

interface HouseholdSettingsAuditLogRepository extends JpaRepository<HouseholdSettingsAuditLog, UUID> {
  Page<HouseholdSettingsAuditLog> findByHouseholdSettingsIdOrderByOccurredAtDesc(UUID id, Pageable p);
}
```

### Service-impl method skeleton — diff + per-field audit + AFTER_COMMIT event

```java
@Transactional
public HouseholdSettingsDto updateSettings(UUID householdId, UUID actorUserId,
                                           UpdateHouseholdSettingsRequest request) {
  HouseholdMember caller = householdMemberRepository.findByUserId(actorUserId)
      .filter(m -> m.getHousehold().getId().equals(householdId))
      .orElseThrow(() -> new InsufficientHouseholdRoleException("not a member"));
  if (caller.getRole() != HouseholdRole.PRIMARY) {
    throw new InsufficientHouseholdRoleException("primary role required");
  }
  HouseholdSettings existing = householdSettingsRepository.findByHouseholdId(householdId)
      .orElseThrow(HouseholdSettingsNotFoundException::new);
  if (existing.getVersion() != request.expectedVersion()) {
    throw new OptimisticLockingFailureException("stale expectedVersion");
  }

  Set<String> changedPaths = new LinkedHashSet<>();
  List<HouseholdSettingsAuditLog> auditRows =
      diffAndBuildAudit(existing.getId(), actorUserId, existing.getDocument(), request.document(), changedPaths);

  if (!changedPaths.isEmpty()) {
    existing.setDocument(request.document());
    householdSettingsRepository.saveAndFlush(existing);  // gotcha: flush so @Version increments before mapping
    householdSettingsAuditLogRepository.saveAll(auditRows);
    publisher.publishEvent(new HouseholdSettingsChangedEvent(
        householdId, existing.getId(), changedPaths, traceIdFromMdcOrRandom(), Instant.now()));
  }
  return mapper.toDto(existing);
}
```

## Edge-case checklist

- [ ] `PUT /settings` by a `PRIMARY` of the target household → 200, document replaced, audit rows written for changed paths only
- [ ] `PUT /settings` by a `MEMBER` (non-primary) of the target household → 403 `insufficient-household-role`
- [ ] `PUT /settings` by someone NOT a member of the target household → 403 (do NOT 404; this is `InsufficientHouseholdRoleException`)
- [ ] `PUT /settings` with stale `expectedVersion` → 409 `concurrent-update` ProblemDetail (from `GlobalExceptionHandler`)
- [ ] `PUT /settings` with `expectedVersion < 0` → 400 (Jakarta `@Min(0)`)
- [ ] `PUT /settings` with identical document (no diff) → 200, document untouched, **NO** audit rows written, **NO** event published
- [ ] `PUT /settings` toggling exactly one nested field (e.g. `slotDefaults.dinner.shared`) → exactly one audit row with `fieldPath = "slotDefaults.dinner.shared"`
- [ ] `PUT /settings` adding a new `customSlots[].key` → audit row with `fieldPath = "customSlots.<key>"` and `previousValueJson = null`-equivalent JSON
- [ ] `PUT /settings` removing a `customSlots[].key` → audit row with `fieldPath = "customSlots.<key>"` and `newValueJson = null`-equivalent JSON
- [ ] `PUT /settings` validation: `customSlots[].key` not matching `^[a-z0-9-]+$` → 400; `headcount > 16` → 400
- [ ] `GET /settings` for a member → 200; for a non-member → 404; for a household with no settings row (01a-era) → 404
- [ ] `GET /settings/audit-log` paginated newest-first; default size 20; `size > 100` clamped
- [ ] `GET /slot-configuration` for a member → 200, includes one entry per non-null `slotDefaults` key + one per `customSlots[]`; `eaterUserIdsIfPerPerson` non-null only when `shared = false`
- [ ] `HouseholdSettingsChangedEvent` published exactly once after commit, with `changedFieldPaths` matching the audit rows; **NOT** published when changedPaths empty
- [ ] OpenAPI request/response shapes match (swagger-request-validator filter active in IT)
- [ ] `HouseholdBoundaryTest` (from 01a) still passes — new repos sit in the same `domain/repository` package
- [ ] No raw `actorUserId` accepted from request body or query — always resolved server-side via `CurrentUserResolver`
- [ ] `POST /households` (01a flow) now also writes a default `HouseholdSettings` row in the same tx — verified by an IT that asserts both rows after a single create

## Files this ticket touches

```
NEW   src/main/resources/db/migration/V20260601500200__household_create_household_settings.sql
NEW   src/main/resources/db/migration/V20260601500300__household_create_household_settings_audit.sql

NEW   src/main/java/com/example/mealprep/household/api/controller/HouseholdSettingsController.java
NEW   src/main/java/com/example/mealprep/household/api/dto/HouseholdSettingsDto.java
NEW   src/main/java/com/example/mealprep/household/api/dto/HouseholdSettingsAuditEntryDto.java
NEW   src/main/java/com/example/mealprep/household/api/dto/UpdateHouseholdSettingsRequest.java
NEW   src/main/java/com/example/mealprep/household/api/dto/SlotConfigurationDto.java
NEW   src/main/java/com/example/mealprep/household/api/mapper/HouseholdSettingsMapper.java
NEW   src/main/java/com/example/mealprep/household/api/mapper/HouseholdSettingsAuditMapper.java
NEW   src/main/java/com/example/mealprep/household/domain/entity/HouseholdSettings.java
NEW   src/main/java/com/example/mealprep/household/domain/entity/HouseholdSettingsAuditLog.java
NEW   src/main/java/com/example/mealprep/household/domain/entity/HouseholdSettingsDocument.java       (record + nested records: SlotDefault, CustomSlotDefinition, HouseholdSchedulingPreferences)
NEW   src/main/java/com/example/mealprep/household/domain/entity/SlotKind.java
NEW   src/main/java/com/example/mealprep/household/domain/repository/HouseholdSettingsRepository.java
NEW   src/main/java/com/example/mealprep/household/domain/repository/HouseholdSettingsAuditLogRepository.java
NEW   src/main/java/com/example/mealprep/household/domain/service/internal/HouseholdSettingsDiffer.java
NEW   src/main/java/com/example/mealprep/household/domain/service/internal/SlotConfigurationResolver.java
NEW   src/main/java/com/example/mealprep/household/event/HouseholdSettingsChangedEvent.java
NEW   src/main/java/com/example/mealprep/household/exception/HouseholdSettingsNotFoundException.java
NEW   src/main/java/com/example/mealprep/household/exception/InsufficientHouseholdRoleException.java

MOD   src/main/java/com/example/mealprep/household/HouseholdModule.java                              (no change to re-exports — service interfaces grow but module facade still exposes the same two interfaces)
MOD   src/main/java/com/example/mealprep/household/api/HouseholdExceptionHandler.java                (append two @ExceptionHandler methods; KEEP @Order(HIGHEST_PRECEDENCE))
MOD   src/main/java/com/example/mealprep/household/domain/service/HouseholdQueryService.java         (append getSettings, getSettingsAuditLog, getSlotConfiguration)
MOD   src/main/java/com/example/mealprep/household/domain/service/HouseholdUpdateService.java        (append updateSettings)
MOD   src/main/java/com/example/mealprep/household/domain/service/internal/HouseholdServiceImpl.java (implement new methods; append default-settings insert to createHousehold tx)

MOD   src/main/resources/openapi/paths/household.yaml                                                (append 3 new path-items)
MOD   src/main/resources/openapi/schemas/household.yaml                                              (append 10 new schemas)
MOD   src/main/resources/openapi/openapi.yaml                                                        (3 lines under paths:; 10 lines under components.schemas:)

NEW   src/test/java/com/example/mealprep/household/HouseholdSettingsServiceTest.java
NEW   src/test/java/com/example/mealprep/household/HouseholdSettingsFlowIT.java
NEW   src/test/java/com/example/mealprep/household/HouseholdSettingsDifferTest.java
NEW   src/test/java/com/example/mealprep/household/SlotConfigurationResolverTest.java
MOD   src/test/java/com/example/mealprep/household/HouseholdsFlowIT.java                             (append assertion: createHousehold also writes a default settings row)
MOD   src/test/java/com/example/mealprep/household/testdata/HouseholdTestData.java                   (append default-document fixture builder)
```

**Files this ticket does NOT modify** (cross-cutting; sibling round-2 tickets must not collide):
- `src/main/java/com/example/mealprep/config/GlobalExceptionHandler.java` — module exceptions go in the existing `HouseholdExceptionHandler`.
- `src/test/java/com/example/mealprep/archunit/ModuleBoundaryTest.java` — module boundary rule lives in the existing `HouseholdBoundaryTest`.
- Other modules' `paths/*.yaml`, `schemas/*.yaml`, `<module>ExceptionHandler.java`, `<module>BoundaryTest.java`.
- `HouseholdBoundaryTest` is unchanged (new repos in the existing package; rule already covers them).

## Dependencies

- **Hard dependency**: `household-01a` (merged) — `Household`, `HouseholdMember`, `HouseholdRole`, `HouseholdMemberRepository`, `HouseholdQueryService`, `HouseholdUpdateService`, `HouseholdExceptionHandler`, `HouseholdBoundaryTest`, `HouseholdException`.
- **Hard dependency**: `auth-01a` (merged) — `CurrentUserResolver`, `SessionAuthenticationFilter`.
- **Hard dependency**: `refactor-01-split-merge-zones` (merged) — per-module YAML / advice / boundary-test layout.
- **Sibling tickets running serially in this round** (Wave 2 round 2): `nutrition-01b`, `provisions-01b`, `recipe-01b`. Each ticket is independent — none touch household files; this ticket touches no nutrition / provisions / recipe files.

## Acceptance / DoD

- [ ] `./mvnw -Dspotless.check.skip=true verify` passes locally
- [ ] `./mvnw spotless:apply` then `./mvnw spotless:check` clean
- [ ] CI green on the PR (build + spotless + OpenAPI lint + ArchUnit gate)
- [ ] All edge-case items above ticked
- [ ] **`HouseholdExceptionHandler` continues to be `@Order(Ordered.HIGHEST_PRECEDENCE)`** — appending handlers must not remove the annotation. Without it, `GlobalExceptionHandler`'s catch-all swallows 403/404 into 500.
- [ ] `saveAndFlush` used in `updateSettings` so the response payload reflects the bumped `@Version` (gotcha #3 from preference-01a)
- [ ] No regression on existing tests, including 01a's `HouseholdsFlowIT` (which gets one new assertion about the default settings row)
- [ ] No N+1 on `getSettings` — single SELECT verified via Hibernate stats or statement-count assertion in IT

## What's NOT in scope

- `HouseholdInvite`, invite create / list / revoke / accept endpoints, `InviteCodeGenerator`, `HouseholdInviteCreatedEvent` → **household-01c**
- `HouseholdMergeService`, `MergedSoftPreferencesDto`, `SoftPreferenceMerger`, `mergeSoftPreferencesForSlot` / `mergeSoftPreferencesForUsers` → **household-01d** (depends on `preference.QueryService.getSoftPreferencesByUserIds`)
- Member CRUD endpoints — `addMember`, `updateMember`, `changeRole`, `removeMember`, `LastPrimaryRemovalException` enforcement → **household-01e**
- The four other published events: `HouseholdMemberAddedEvent`, `HouseholdMemberRemovedEvent`, `HouseholdRoleChangedEvent` → **household-01e**; `HouseholdInviteAccepted/Created` → **household-01c**
- Email-based invites (Out of Scope per LLD)
- 01a-era retro-creation of settings rows (locked: lazy-on-first-PUT; no migration)

Squash-merge with: `feat(household): 01b — settings aggregate + PUT/audit-log/slot-configuration endpoints + default-on-create`
