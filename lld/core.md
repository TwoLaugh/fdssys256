# Core Module — LLD

*Implementation specification for the cross-cutting primitives every domain module depends on: shared enums and value objects, sealed event base interfaces, the decision-log audit trail from [optimisation-loop.md](../design/optimisation-loop.md#decision-log), and the single-flight `LockService` over Postgres advisory locks. No business logic — `core` is the floor every other module stands on.*

## Scope

This document specifies the `core` module — package layout, JPA entities, Flyway migrations, repositories, service interfaces, DTOs, mappers (sparse), the minimal admin REST surface, validation, sealed event bases, business-logic flows, transaction boundaries, and the test plan. Conventions defer to [lld/style-guide.md](style-guide.md); this LLD restates a rule only when the module-specific application matters.

`core`'s scope is deliberately narrow:

| Sub-area | Purpose | Consumers |
|---|---|---|
| `core.types` | Shared enums and value objects used in cross-module DTOs and event payloads | Every domain module |
| `core.events` | Sealed marker interfaces (`MealPrepEvent`, `ScopeChangedEvent`) for the project-wide event hierarchy | Every module that publishes or consumes events |
| `core.audit` | The shared `decision_log` table, `DecisionLogService`, trace-ID propagation helpers | Every module that runs an optimisation loop (planner, adaptation pipeline) |
| `core.lock` | `LockService` — two single-flight primitives: a tx-scoped advisory lock (`pg_try_advisory_xact_lock`) and a connection-free TTL **lease** (`core_lock_leases` table) | Advisory: adaptation pipeline (per-recipe), grocery batch jobs. Lease: planner (per-week, start-of-generation) |

`core` depends only on Spring + Hibernate + JDK — no domain module, no AI service, no domain JPA mappings. If a piece of code wants domain knowledge, it does not belong here.

---

## Package Layout

```
com.example.mealprep.core/
├── CoreModule.java                              facade re-exporting public services
├── api/
│   ├── controller/DecisionLogAdminController.java   read-only admin observability endpoint
│   ├── dto/                                          records (see DTOs section)
│   └── mapper/DecisionLogMapper.java                 entity → DTO; lock service has no DTO surface
├── audit/
│   ├── domain/
│   │   ├── entity/DecisionLog.java + payload/         JSONB payload record-tree
│   │   ├── repository/DecisionLogRepository.java      package-private
│   │   └── service/
│   │       ├── DecisionLogService.java                public — write side
│   │       ├── DecisionLogQueryService.java           public — read side
│   │       ├── DecisionLogServiceImpl.java            single impl of both
│   │       └── internal/
│   │           ├── TraceContext.java                  MDC trace-id helpers
│   │           └── DecisionLogTokenBudgetGuard.java   caps the JSONB payloads
│   └── trace/TraceIdFilter.java                       servlet filter — populates MDC per request
├── events/                                            MealPrepEvent (sealed), ScopeChangedEvent (sealed),
│                                                      EventScope, EventScopeKind
├── lock/                                              LockService, LockKey (sealed), LeaseHandle,
│                                                      internal/LockServiceImpl (composite),
│                                                      internal/AdvisoryLockServiceImpl (tx-advisory),
│                                                      internal/LeaseLockServiceImpl (TTL lease),
│                                                      internal/LockLease (entity), internal/LockLeaseRepository,
│                                                      internal/LockKeyHasher
├── types/                                             SlotKind, MealKind, DataQuality, PreferenceTier,
│                                                      value/IngredientMappingKey, value/ScopeId
├── exception/                                         MealPrepException (project root), CoreException,
│                                                      DecisionLogNotFoundException,
│                                                      DecisionLogPayloadOversizedException
└── config/                                            JpaAuditingConfig, CoreJsonConfig (Hibernate JsonType
                                                       bean), TraceIdFilterConfig (highest filter precedence)
```

`MealPrepException` lives in `core.exception` rather than the project root because every module's exceptions extend it, and `core` is the only module everyone already depends on.

---

## Database

Migrations live under `src/main/resources/db/migration/` per the project-wide timestamp scheme. `core` migrations run earliest because every other module's tables and FKs depend on the schema being clean.

```
V20260101000000__core_create_decision_log.sql
V20260101000100__core_decision_log_indexes.sql
```

Two migrations, not one — DDL and the index set are independent concerns.

### V20260101000000 — Decision log

Schema follows [optimisation-loop.md §Decision Log](../design/optimisation-loop.md#decision-log). JSONB for the variable-shape payloads (read-whole, no inner-field filtering on the roadmap).

```sql
CREATE TABLE decision_log (
    decision_id          uuid PRIMARY KEY,
    trace_id             uuid NOT NULL,
    parent_decision_id   uuid REFERENCES decision_log(decision_id) ON DELETE SET NULL,
    scale                varchar(32) NOT NULL,             -- 'week' | 'recipe' | future
    triggered_by         varchar(32) NOT NULL,             -- 'user' | 'feedback' | 'data-model-change' | 'refine-directive'
    scope_kind           varchar(16) NOT NULL,             -- mirrors EventScopeKind
    scope_id             varchar(128) NOT NULL,            -- 'household:<uuid>:week:2026-W18', etc.
    inputs               jsonb NOT NULL,                   -- { scope, constraints_summary, refine_directive | null }
    candidates           jsonb NOT NULL,                   -- [{ id, score, rollup }, ...] up to N
    chosen               jsonb NOT NULL,                   -- { candidate_id, source }
    reasoning            text,                             -- LLM rationale or "auto-skip: top score 2x"
    emitted_directive    jsonb,                            -- Stage D output, if any
    iteration            integer NOT NULL,
    duration_ms          integer NOT NULL,
    actor_user_id        uuid,                             -- null for system-driven invocations
    created_at           timestamptz NOT NULL
);
```

Notable schema decisions:

- **`parent_decision_id ON DELETE SET NULL`, not CASCADE** — preserves descendant audit history when an old root is purged.
- **`scope_kind` + `scope_id` denormalised onto columns** — the HLD keeps `scope` inside `inputs` JSONB; lifting these out enables "all decisions for this week" without `jsonb` operators in WHERE. **Worth user review.**
- **`actor_user_id` is nullable** — background-triggered loops (mid-week re-opt from a Provisions update) have no human actor. Not in the HLD schema; **worth user review.**
- **No `@Version`, no `updated_at`** — append-only.

### V20260101000100 — Decision log indexes

```sql
-- Trace reconstruction: "show me the chain of decisions that produced this plan."
CREATE INDEX idx_decision_log_trace_iteration ON decision_log (trace_id, iteration);

-- Parent-pointer walks for the explanation feature.
CREATE INDEX idx_decision_log_parent ON decision_log (parent_decision_id) WHERE parent_decision_id IS NOT NULL;

-- "All decisions for household X this week" — the dashboard's most common audit query.
CREATE INDEX idx_decision_log_scope_created ON decision_log (scope_kind, scope_id, created_at DESC);

-- Recent-activity admin endpoint.
CREATE INDEX idx_decision_log_created_at ON decision_log (created_at DESC);

-- User-scoped activity feed. Partial — most rows have a non-null actor.
CREATE INDEX idx_decision_log_actor_created ON decision_log (actor_user_id, created_at DESC) WHERE actor_user_id IS NOT NULL;
```

No GIN index on JSONB. If analytics later needs `inputs->>'constraint_summary'`, that index lands in its own migration.

### Lock service — advisory (no schema) + lease (`core_lock_leases`)

The advisory half of `LockService` (`pg_try_advisory_xact_lock`) requires no table; lock state lives in the Postgres backend's lock manager and is auto-released at transaction end. Zero schema, zero stale-row cleanup — the reason advisory locks beat a `locks` table for tx-scoped critical sections.

The **lease** half does need a committed row, so it ships its own versioned migration `V20260615270000__core_create_lock_leases.sql`:

```sql
CREATE TABLE core_lock_leases (
    lock_key     varchar(160) PRIMARY KEY,   -- LockKey.serialize(); PK is the single-flight gate
    holder_token uuid        NOT NULL,        -- per-acquisition secret; only the holder can release/renew
    acquired_at  timestamptz NOT NULL,
    expires_at   timestamptz NOT NULL         -- lease becomes reclaimable once this passes (liveness)
);
CREATE INDEX idx_core_lock_leases_expires_at ON core_lock_leases (expires_at);
```

Why a committed lease and not an advisory lock here: a tx-scoped advisory lock is released the instant its transaction ends, and a session-scoped one (`pg_advisory_lock`) pins a DB connection for as long as it is held. The planner's plan-generation runs a ~20s AI pipeline that must hold **no** DB connection across the LLM latency (the Tier-1 "AI calls outside any transaction" rule). The only way to single-flight that work *before* it starts, without pinning a connection for the whole window, is a committed marker: a short transaction inserts a lease row (connection released on commit), the `lock_key` PK enforces single-flight, and `expires_at` makes a crashed holder's lease reclaimable. See §LockService and §Flow 5.

---

## Entities

`DecisionLog` is the only JPA entity in `core`. Append-only: `@Id UUID decisionId` set application-side, `@Type(JsonType.class)` on the JSONB columns mapping to record-tree types, no `@Version`, `@CreatedDate Instant createdAt` populated by Spring Data auditing, `@ManyToOne(fetch = LAZY)` self-reference on `parentDecisionId` (lazy because trace reconstruction loads the chain explicitly via `DecisionLogQueryService.getTrace`).

JSONB record types under `core.audit.domain.entity.payload`:

```java
public record DecisionLogInputs(
    String scopeSummary, String constraintsSummary,
    DecisionLogRefineDirective refineDirective                  // nullable
) {}

public record DecisionLogCandidate(
    String candidateId, BigDecimal score,
    Map<String, Object> rollup                                  // opaque to core; per-scale shape
) {}

public record DecisionLogChoice(String candidateId, ChoiceSource source) {}

public record DecisionLogRefineDirective(
    String targetScopeKind, String targetScopeId,
    String desiredDelta, UUID parentDecisionId
) {}
```

`Map<String, Object>` for `rollup` is intentional — `core` knows nothing about week- vs recipe-level rollup shapes; forcing a sealed type here would require `core` to depend on every domain module's rollup types. The record-tree above is the maximum schema `core` enforces. **Worth user review** — the alternative is per-scale JSONB sub-documents, which adds friction without locking down anything Java's type system can enforce against opaque scoring data.

Enums local to `core`: `ChoiceSource` (`LLM`, `USER`, `DETERMINISTIC_SKIP`), `EventScopeKind` (`WEEK`, `RECIPE`, `HOUSEHOLD`, `USER`, `GLOBAL`), `DecisionScale` (`WEEK`, `RECIPE` — open to extension), `TriggeredBy` (`USER`, `FEEDBACK`, `DATA_MODEL_CHANGE`, `REFINE_DIRECTIVE`).

Shared `core.types` enums (consumed by domain modules): `SlotKind` (`BREAKFAST`, `LUNCH`, `DINNER`, `SNACK`, `CUSTOM` — promoted from household per its LLD's review flag), `MealKind` (`MAIN`, `SIDE`, `DRINK`, `DESSERT`), `DataQuality` (`GOLD`, `SILVER`, `BRONZE`, `UNVERIFIED` — ordinal floor), `PreferenceTier` (`HARD_CONSTRAINTS`, `TASTE_PROFILE`, `LIFESTYLE_CONFIG`).

---

## DTOs

```java
public record DecisionLogEntryDto(
    UUID decisionId, UUID traceId, UUID parentDecisionId,
    DecisionScale scale, TriggeredBy triggeredBy,
    EventScopeKind scopeKind, String scopeId,
    DecisionLogInputs inputs,
    List<DecisionLogCandidate> candidates,
    DecisionLogChoice chosen,
    String reasoning,
    DecisionLogRefineDirective emittedDirective,
    int iteration, int durationMs,
    UUID actorUserId, Instant createdAt
) {}

public record WriteDecisionLogRequest(
    @NotNull UUID decisionId,                          // caller-supplied for idempotency
    @NotNull UUID traceId,
    UUID parentDecisionId,
    @NotNull DecisionScale scale,
    @NotNull TriggeredBy triggeredBy,
    @NotNull EventScopeKind scopeKind,
    @NotBlank @Size(max = 128) String scopeId,
    @NotNull @Valid DecisionLogInputs inputs,
    @NotNull @Valid @Size(max = 10) List<DecisionLogCandidate> candidates,
    @NotNull @Valid DecisionLogChoice chosen,
    @Size(max = 4000) String reasoning,
    @Valid DecisionLogRefineDirective emittedDirective,
    @Min(0) int iteration, @Min(0) int durationMs,
    UUID actorUserId
) {}

public record DecisionTraceDto(UUID traceId, List<DecisionLogEntryDto> entries) {}
```

`WriteDecisionLogRequest` is an in-process command, not a REST request — there is no public POST endpoint for the decision log. Validation annotations are still applied at the service-method boundary via `@Valid` so misuse from a sibling module fails fast.

---

## Mappers

One MapStruct mapper, sparse usage. The lock service has no DTO surface (returns `boolean` and `LeaseHandle`); no mapper.

```java
@Mapper(componentModel = "spring")
public interface DecisionLogMapper {
    DecisionLogEntryDto toDto(DecisionLog entity);
    List<DecisionLogEntryDto> toDtos(List<DecisionLog> entities);
    DecisionLog toEntity(WriteDecisionLogRequest request);   // createdAt left null — JPA auditing fills it
}
```

---

## Repositories

Package-private; cross-module access via `DecisionLogQueryService`.

```java
interface DecisionLogRepository extends JpaRepository<DecisionLog, UUID> {

    List<DecisionLog> findByTraceIdOrderByIterationAscCreatedAtAsc(UUID traceId);
    Page<DecisionLog> findByScopeKindAndScopeIdOrderByCreatedAtDesc(EventScopeKind k, String id, Pageable p);
    Page<DecisionLog> findByActorUserIdOrderByCreatedAtDesc(UUID actorUserId, Pageable p);
    Page<DecisionLog> findAllByOrderByCreatedAtDesc(Pageable p);

    // Recursive ancestor walk for the explanation feature; depth-capped at 32.
    @Query(value = """
        WITH RECURSIVE chain AS (
            SELECT * FROM decision_log WHERE decision_id = :leaf
            UNION ALL
            SELECT d.* FROM decision_log d JOIN chain c ON d.decision_id = c.parent_decision_id
        )
        SELECT * FROM chain LIMIT 32
    """, nativeQuery = true)
    List<DecisionLog> findAncestorChain(@Param("leaf") UUID leafDecisionId);
}
```

Native CTE because JPQL has no `WITH RECURSIVE`. The 32-row cap prevents a malformed cycle (which the schema permits but Stage D should never produce) from blowing up the query. **Worth user review** — alternatives are an iterative service-layer loop (cleaner, more round-trips) or a closure table (more storage and write cost).

---

## Service Interfaces

### `DecisionLogService`

Write side. Method is `@Transactional` (REQUIRED): callers are inside an existing optimisation-loop transaction and the log row should roll back together with the work it describes.

```java
public interface DecisionLogService {
    /**
     * Idempotent on decisionId. Caller supplies the UUID so it can carry the value in
     * downstream events and refine-directives before the row hits the DB. Repeated calls
     * with the same decisionId return the existing row without a second insert.
     */
    DecisionLogEntryDto write(WriteDecisionLogRequest request);
}
```

### `DecisionLogQueryService`

```java
public interface DecisionLogQueryService {
    Optional<DecisionLogEntryDto> getById(UUID decisionId);
    DecisionTraceDto getTrace(UUID traceId);                                                   // ordered iteration ASC, created_at ASC
    List<DecisionLogEntryDto> getAncestorChain(UUID leafDecisionId);                           // capped at 32
    Page<DecisionLogEntryDto> findByScope(EventScopeKind scopeKind, String scopeId, Pageable p);
    Page<DecisionLogEntryDto> findByActor(UUID actorUserId, Pageable p);
    Page<DecisionLogEntryDto> findRecent(Pageable p);                                          // admin only
}
```

### `LockService`

Implements [style-guide.md §Concurrency](style-guide.md#concurrency) "single-flight per scope". Two mechanisms, for two shapes of work — both behind one interface.

```java
public interface LockService {

    // --- 1. Transaction-scoped advisory lock (pg_try_advisory_xact_lock) -------------------
    /** Non-throwing. False on contention. MUST be called inside an active @Transactional;
     *  auto-releases at commit/rollback — no explicit unlock. */
    boolean tryAcquire(LockKey key);

    // --- 2. Connection-free TTL lease (core_lock_leases) -----------------------------------
    /** Claim a committed lease in a short REQUIRES_NEW tx (connection released on commit). Empty on
     *  contention; reclaims a lease whose TTL has passed (lazy reclaim of a crashed holder). */
    Optional<LeaseHandle> acquireLease(LockKey key, Duration ttl);

    /** Delete the lease by (lockKey, holderToken). Only the holder may release; a stale handle whose
     *  lease was reclaimed by another caller is a silent no-op. True iff this call removed it. */
    boolean releaseLease(LeaseHandle handle);

    /** Extend a still-held lease's TTL; empty if no longer the holder. Optional — the planner sets
     *  its TTL generously above max generation time and does not renew. */
    Optional<LeaseHandle> renewLease(LeaseHandle handle, Duration ttl);
}

public sealed interface LockKey {           // sealed so all scopes are explicit; serialize() is the wire form
    String serialize();
    static LockKey forPlanWeek(UUID householdId, LocalDate weekStart);   // "plan-week|<uuid>|<iso>"
    static LockKey forRecipe(UUID recipeId);                             // "recipe|<uuid>"
    static LockKey forCustom(String scopeKind, UUID scopeId);            // "custom|<kind>|<uuid>"
}

public record LeaseHandle(LockKey key, UUID holderToken, Instant acquiredAt, Instant expiresAt) {}
```

`LockKey` is a sealed typed value object with factories rather than a free-form `String` so callers can't typo a scope; `serialize()` is the stable form hashed for the advisory `bigint` and stored as the lease `lock_key`.

**Mechanism 1 — advisory, transaction-scoped over session-scoped.** Session-scoped (`pg_advisory_lock`) requires explicit unlock, a pool that respects session affinity, AND it pins a connection for as long as it is held; transaction-scoped is automatic and leak-free, matching the "one short critical section = one transaction" pattern the adaptation pipeline (per-recipe) and grocery batch jobs use. **Preserved unchanged** — those callers depend on `tryAcquire`.

**Mechanism 2 — lease, the connection-free single-flight.** No held lock can single-flight the planner's generation: a tx-advisory lock only lives inside the persist transaction (so it rejects a duplicate only *after* both ran the full ~20s AI pipeline — wasted tokens), and a session-advisory lock held across the pipeline would pin a connection for the whole AI-latency window — exactly the connection-pinning the "AI outside any transaction" rule forbids. The lease sidesteps both: `acquireLease` writes a committed `core_lock_leases` row in a short `REQUIRES_NEW` tx (connection released on commit), the `lock_key` PK enforces single-flight, and the row persists across the connection-free pipeline. On a holder crash the lease is never released, so `expires_at` (a TTL safely larger than max generation time, configurable; planner default 10 min) makes it reclaimable on the next `acquireLease` (lazy reclaim-on-acquire — no sweeper in v1). Acquire is `INSERT ... ON CONFLICT (lock_key) DO NOTHING`; on a 0-row insert it tries a conditional `UPDATE ... WHERE expires_at < now` reclaim — both atomic, so racing acquirers can't both win.

**Note on prior LLD divergence (closed here).** Earlier drafts of this section specced `LockHandle acquire(...)`/`Optional<LockHandle> tryAcquire(...)` with a `LockHandle(key, advisoryKey, acquiredAt)` record and a `LockKey(EventScopeKind, String)` shape; the shipped advisory primitive was actually `boolean tryAcquire(LockKey)` over a sealed `LockKey`. This revision documents the as-built advisory contract and adds the lease primitive — the doc now matches the code.

---

## REST Controllers

`core` exposes a deliberately tiny REST surface — only the decision log, only for read-only admin observability. The lock service has no HTTP surface.

`/api/v1/admin/decision-log/*`. Gated behind admin role (auth concern — owned by [auth.md](auth.md)). OpenAPI: `@Tag(name = "Admin — Decision Log")`.

| Method | Path | Request | Response | Status |
|---|---|---|---|---|
| GET | `/api/v1/admin/decision-log/{decisionId}` | — | `DecisionLogEntryDto` | 200 / 404 |
| GET | `/api/v1/admin/decision-log/traces/{traceId}` | — | `DecisionTraceDto` | 200 |
| GET | `/api/v1/admin/decision-log/{decisionId}/ancestors` | — | `List<DecisionLogEntryDto>` | 200 / 404 |
| GET | `/api/v1/admin/decision-log?scopeKind=&scopeId=&page=&size=` | — | `Page<DecisionLogEntryDto>` | 200 |
| GET | `/api/v1/admin/decision-log?actorUserId=&page=&size=` | — | `Page<DecisionLogEntryDto>` | 200 |
| GET | `/api/v1/admin/decision-log?page=&size=` | — | `Page<DecisionLogEntryDto>` | 200 |

The user-facing "why this plan?" UI does **not** read the decision log directly — the planner module renders an explanation from the trace via its own endpoint. Exposing the raw decision log to end users would leak internal scoring details that the LLM-prompt design deliberately keeps out of the user view.

There is no POST. Decision-log entries are written in-process by the loop owners (planner, adaptation pipeline) via direct `DecisionLogService.write` injection.

### Error responses

RFC 9457 ProblemDetail per the style guide. Module exceptions and their mappings (handled in the project-wide `GlobalExceptionHandler`):

| Exception | Status | `type` URI |
|---|---|---|
| `DecisionLogNotFoundException` | 404 | `https://mealprep.example.com/problems/decision-log-not-found` |
| `DecisionLogPayloadOversizedException` | 422 | `https://mealprep.example.com/problems/decision-log-payload-oversized` |
| `MethodArgumentNotValidException` | 400 | `errors[]` extension on ProblemDetail |

`MealPrepException` (the project-wide root) lives here in `core.exception`. `LockService` itself throws no contention exception: `tryAcquire` returns `boolean` and `acquireLease` returns `Optional` — each caller maps "lost" to its own domain exception and 409 (planner `ConcurrentGenerationInProgressException`, adaptation `LockTimeoutException`, grocery `OrderConcurrencyConflictException`). There is no `core`-level `LockAcquisitionException`.

---

## Validation

Standard Jakarta annotations on `WriteDecisionLogRequest` and embedded record types: `@NotNull`, `@NotBlank`, `@Size`, `@Valid` for nested records, `@Min(0)` on `iteration` and `durationMs`, `@Size(max = 4000)` on `reasoning`.

Service-layer validation in `DecisionLogTokenBudgetGuard` (not Jakarta — depends on serialised size, which annotations can't see):

- Total serialised JSONB payload (`inputs` + `candidates` + `chosen` + `emittedDirective`) ≤ 64 KB. Above that → `DecisionLogPayloadOversizedException` (422). The HLD predicts ~3-4k tokens at week scale (~16 KB JSON); 64 KB leaves headroom and prevents a runaway prompt from dumping multi-MB candidate detail into the audit log.
- `candidates.size()` ≤ 10 (HLD spec'd N=5 default; cap at 10 to allow defensive overrides).
- `parentDecisionId`, when present, must resolve to an existing row — one indexed PK lookup, throws `DecisionLogNotFoundException` upfront for a clearer error than the FK constraint would give.

`LockKey.scopeId` validation: `@NotBlank @Size(max = 128) @Pattern(regexp = "^[a-z0-9:.\\-]+$")`. Restricting the alphabet keeps log lines greppable and prevents accidental newline injection.

---

## Events

### Sealed bases

`MealPrepEvent` is the project-wide event root. Every domain module's event class implements either it directly or a sealed sub-root. Sealed across the board per the locked Tier 1 decision.

```java
public sealed interface MealPrepEvent
    permits ScopeChangedEvent /*, other domain sub-roots added by their modules */ {

    UUID traceId();           // every event carries a traceId; auto-generated if not part of a chain
    Instant occurredAt();     // event-time set at construction
}

public sealed interface ScopeChangedEvent extends MealPrepEvent
    permits /* RecipeUpdatedEvent, ProvisionChangedEvent, PreferenceChangedEvent, HardConstraintsChangedEvent, ... */ {

    EventScope scope();
}

public sealed interface EventScope
    permits EventScope.Week, EventScope.Recipe, EventScope.Household, EventScope.User, EventScope.Global {

    EventScopeKind kind();

    record Week(UUID householdId, LocalDate weekStart) implements EventScope { public EventScopeKind kind() { return WEEK; } }
    record Recipe(UUID recipeId)                       implements EventScope { public EventScopeKind kind() { return RECIPE; } }
    record Household(UUID householdId)                 implements EventScope { public EventScopeKind kind() { return HOUSEHOLD; } }
    record User(UUID userId)                           implements EventScope { public EventScopeKind kind() { return USER; } }
    record Global()                                    implements EventScope { public EventScopeKind kind() { return GLOBAL; } }
}
```

The `permits` clauses on `MealPrepEvent` and `ScopeChangedEvent` are deliberately left open — each domain module adds its event records to the appropriate `permits` list as part of that module's LLD. The locked Tier 1 decision was that sealed events are worth the maintenance cost of editing `core` when a new event type lands; the alternative (open `interface`) gives up exhaustive `switch` in listeners, which the planner relies on for re-optimisation routing.

Constraints on sub-types: **records only** (style-guide rule, fits sealed naturally); **implementations live in their owning module** (`core` knows nothing about specific events; the compiler walks `permits` to find them).

`occurredAt()` is set at construction; listeners that need the post-commit time should use the `@TransactionalEventListener` invocation timestamp.

### Published from `core`

None. The decision-log write is direct service invocation; "decision happened" semantics belong to the loop owners (planner, adaptation pipeline).

### Consumed in `core`

None. `core` does not subscribe to anything — that would create circular knowledge of domain modules.

---

## Trace ID Propagation

### MDC

`TraceIdFilter` is a servlet filter at the highest filter precedence:

1. Inspects inbound `X-Trace-Id` header. If present and well-formed (UUID), uses it; otherwise generates a fresh UUID.
2. `MDC.put("traceId", traceId.toString())`. Logback pattern includes `%X{traceId}` so every log line carries it.
3. Echoes `X-Trace-Id` back on the response for client correlation.
4. `MDC.remove("traceId")` in `finally` so a thread returning to the pool starts clean.

Background threads (`@Async`, `@Scheduled`) use `TraceContext.runWithTraceId(UUID, Runnable)` — push trace-ID onto MDC for the lambda's duration, remove after. The helper is the only sanctioned way to trace background work; ad-hoc `MDC.put` is a code-review anti-pattern.

### Method args

Per the style guide, trace IDs cross module boundaries via explicit method arguments, never thread-locals. Cross-module service interfaces accept `UUID traceId` where the call participates in a multi-stage flow (e.g. `OptimiserService.adapt(recipeId, traceId)`). `core` does not police this — the convention lives in each consumer's interface — but `TraceContext` exposes:

```java
public final class TraceContext {
    public static UUID currentTraceId();                        // reads MDC; null if not set
    public static UUID requireTraceId();                        // same, throws if null
    public static void runWithTraceId(UUID traceId, Runnable body);
    public static <T> T callWithTraceId(UUID traceId, Callable<T> body) throws Exception;
}
```

so async dispatchers and test fixtures don't have to touch MDC directly.

---

## Business Logic Flows

### Flow 1: Decision-log write

`DecisionLogService.write(request)` is called from inside an optimisation-loop transaction. `@Transactional` (REQUIRED — joins caller's tx).

1. Jakarta validation (`@Valid` on parameter).
2. `DecisionLogTokenBudgetGuard.assertWithinBudget(request)` — serialise via the project `ObjectMapper`, total ≤ 64 KB else `DecisionLogPayloadOversizedException` (422).
3. **Idempotency check.** `repository.findById(decisionId)` — if present, return mapped existing row. Repeats happen because the planner can hit this method twice if its outer transaction retries on optimistic-lock conflict.
4. **Parent existence check.** If `parentDecisionId != null`, `repository.existsById(parentDecisionId)`; throw `DecisionLogNotFoundException` if missing.
5. `mapper.toEntity(request)`. JPA auditing populates `createdAt`. `repository.save(entity)`. Flush deferred to commit — the row appears together with the loop's other writes.
6. Log INFO: `decisionId`, `traceId`, `scale`, `triggeredBy`, `iteration`, `durationMs`. Full payload at DEBUG only (PII rule).
7. Return `mapper.toDto(saved)`.

No event published. The decision-log write is a side-effect of an existing flow, not an event source.

### Flow 2: Trace reconstruction

`DecisionLogQueryService.getTrace(traceId)`. `@Transactional(readOnly = true)`.

1. `repository.findByTraceIdOrderByIterationAscCreatedAtAsc(traceId)`. Indexed lookup; one query.
2. If empty, return `new DecisionTraceDto(traceId, List.of())` — not 404. A trace with zero decisions is meaningful (the loop ran, terminated immediately on hard-filter failure, and logged elsewhere via the constraint-feasibility path).
3. Map and return.

### Flow 3: Advisory lock acquisition (`tryAcquire`)

`LockService.tryAcquire(key)` wraps `pg_try_advisory_xact_lock`. Must be called inside a `@Transactional` method (lock is auto-released at transaction end); the impl is `@Transactional(MANDATORY)` so calling it without an active tx fails fast rather than acquiring a meaningless, instantly-released lock.

1. `long advisoryKey = LockKeyHasher.hash(key)`. Stable hash — same `LockKey` always maps to the same `advisoryKey`. Shipped impl uses SHA-256 truncated to 64 bits (Murmur3 is not on the main classpath; documented deviation); collision probability at expected scope volumes is negligible. The hash function is fixed in `core`; changing it requires a project-wide release because in-flight locks would not collide correctly across versions.
2. `SELECT pg_try_advisory_xact_lock(:advisoryKey)` via native query.
3. `true` → return `true` (caller holds the lock for the rest of its tx).
4. `false` → return `false` (caller decides: 409, silent skip, etc.).
5. SQL exception → propagate; caller's `@Transactional` rolls back, no lock to release.

Used by short critical sections already inside one transaction: the adaptation pipeline (per-recipe) and grocery batch jobs (per-list). A `false` is mapped by those callers to their own 409 / silent-skip.

### Flow 5: Lease acquisition / release (`acquireLease` / `releaseLease`)

`LockService.acquireLease(key, ttl)` claims a committed lease for `key`, runnable with or without a surrounding transaction (it opens its own `REQUIRES_NEW`). Used by long, connection-free operations — the planner's start-of-generation single-flight.

Acquire:
1. `token = randomUUID()`, `now = clock.instant()`, `expiresAt = now + ttl`.
2. `INSERT INTO core_lock_leases (...) VALUES (...) ON CONFLICT (lock_key) DO NOTHING`. 1 row → won; return `LeaseHandle(key, token, now, expiresAt)`.
3. 0 rows (row exists) → `UPDATE core_lock_leases SET holder_token = token, acquired_at = now, expires_at = :expiresAt WHERE lock_key = :k AND expires_at < :now`. 1 row → reclaimed an expired (crashed-holder) lease under the fresh token; return the handle. 0 rows → a live holder owns it → `Optional.empty()` (contended).
4. The short tx commits and releases its connection; the lease then lives as a committed row, held with no connection pinned.

Release: `DELETE FROM core_lock_leases WHERE lock_key = :k AND holder_token = :token`. Only the holder's `(key, token)` matches — a stale handle whose lease was reclaimed by someone else deletes nothing (no-op, returns `false`). Idempotent. The planner calls this in a `finally`, so a normal completion frees the lease immediately; the TTL is only the crash backstop.

`renewLease` extends a still-held lease (`UPDATE ... WHERE lock_key = :k AND holder_token = :token`); not needed by the planner.

### Flow 4: Trace-ID inbound — request lifecycle

1. `TraceIdFilter.doFilter` runs first.
2. Extract `X-Trace-Id`; validate as UUID; generate if missing/malformed.
3. `MDC.put("traceId", traceId.toString())`.
4. Add `X-Trace-Id` response header (echo or generated).
5. `chain.doFilter(...)` — every subsequent log line carries the trace-id.
6. `finally` → `MDC.remove("traceId")`.

Background work mirrors this via `TraceContext.runWithTraceId` for the lambda's lifetime.

---

## Concurrency and Transactions

| Concern | Decision |
|---|---|
| `@Transactional` placement | All service-impl methods. Repositories never. |
| Read-method propagation | `@Transactional(readOnly = true)`. |
| Write-method propagation | Default REQUIRED. `DecisionLogService.write` joins the caller's transaction by design — log entry rolls back if the work it describes does. |
| Optimistic locking | None. Decision log is append-only; no `@Version`. |
| Pessimistic locking | The advisory-lock service IS the project's pessimistic-locking primitive. The lease lock is the connection-free single-flight primitive (committed marker, not a held lock). No row-level locks anywhere in `core`. |
| Idempotency | `DecisionLogService.write` is idempotent on `decisionId`; caller responsibility to reuse the same UUID across retries. |
| MDC cleanup | `TraceIdFilter` and `TraceContext` always clean up in `finally`. Verified by `TraceIdFilterIT` checking thread-local cleanliness after each request. |

---

## Test Plan

Unit tests use `@ExtendWith(MockitoExtension.class)`. Integration tests are `*IT.java` with Testcontainers Postgres. Names follow `methodName_scenario_expected`.

### Unit

| Class | Verifies |
|---|---|
| `DecisionLogServiceImplTest` | Happy-path write returns mapped DTO; idempotency on `decisionId` returns existing row without second insert; parent-not-found throws `DecisionLogNotFoundException`; oversized payload throws `DecisionLogPayloadOversizedException`. |
| `DecisionLogQueryServiceImplTest` | `getTrace` returns ordered entries; empty trace returns empty list (not 404); `getAncestorChain` walks recursive query and tolerates a null parent. |
| `DecisionLogTokenBudgetGuardTest` | Exact-size payloads pass; +1-byte payloads throw. Three fixtures: small, near-budget, oversized. |
| `DecisionLogMapperTest` | MapStruct round-trips preserve all fields including the JSONB record-tree. |
| `LockKeyHasherTest` | Same key → same `long` across instances; different keys → different hashes; special-character `scopeId` doesn't break determinism. |
| `LockKeyTest` | `forPlanWeek`, `forRecipe`, `forCustom` factories produce the documented `serialize()` strings. |
| `AdvisoryLockServiceImplTest` | `tryAcquire` null-key throws; no-active-tx throws; `pg_try_advisory_xact_lock` true/false/null mapped to `true`/`false`/`false` (repository mocked). |
| `LeaseLockServiceImplTest` | `acquireLease` returns a handle on insert-won; reclaims on expired existing lease; empty on live contention; bad/zero TTL and null key throw. `releaseLease` true on holder-delete, false (no-op) for a non-holder. `renewLease` refreshes for the holder, empty for a non-holder. Repository mocked; fixed `Clock`. |
| `TraceContextTest` | `runWithTraceId` puts and removes MDC entry; nested calls restore the outer trace ID; exception in body still cleans up. |

### Integration

| Class | Verifies |
|---|---|
| `DecisionLogServiceIT` | Real DB write, trace reconstruction, ancestor chain, scope-filtered pagination. Recursive CTE handles a 5-deep chain and stops at 32-row cap on a synthetic 50-deep chain. |
| `DecisionLogAdminControllerIT` | HTTP layer: GET single (200/404), GET trace, GET ancestors, GET listings with each query-param shape, ProblemDetail shape on errors. Admin auth gating asserted via security test config. |
| `AdvisoryLockServiceIT` | Real DB: `tryAcquire` requires an active tx; a second concurrent `tryAcquire` on the same key from a separate tx returns `false`; the lock auto-releases on commit AND on rollback (a fresh acquire then succeeds); different keys never contend. Two `@Transactional` methods on separate connections exercise contention. |
| `LeaseLockServiceIT` | Real DB + Flyway-applied `core_lock_leases`: acquire → contend (second acquire empty while live); acquire → release → re-acquire (succeeds); acquire → expire (past TTL via direct insert) → reclaim-by-other (succeeds, fresh token); release-by-non-holder is a no-op that does not evict the owner; `renewLease` extends a held lease and is a no-op for a non-holder; different keys never contend. Each `acquireLease` is `REQUIRES_NEW` so the test (no surrounding tx) sees committed rows — the connection-free property the planner relies on. |
| `TraceIdFilterIT` | MockMvc request with `X-Trace-Id` propagates to log MDC; missing header generates a UUID; response carries the trace-id back; thread-local empty after request completion (verified by a second request on the same thread observing no leakage). |
| `FlywayMigrationIT` | Boots Postgres, runs `core` migrations, asserts schema matches JPA mapping (`spring.jpa.hibernate.ddl-auto=validate`). |
| `DecisionLogPayloadIT` | A planner-shaped payload (5 candidates, week-level rollup, ~16 KB serialised) writes and reads back unchanged. The 64 KB cap rejects a synthetic oversize. |
| `SealedEventCompilationIT` | A test-only domain event extending `ScopeChangedEvent` compiles and dispatches via a stub `ApplicationEventPublisher`. If `core`'s sealed-event surface ever broke, this test fails to compile — the desired safety net. |

---

## Out of Scope

Deferred deliberately — these belong elsewhere or to a later phase:

- **The optimisation loops themselves.** `core` writes the audit trail and provides the lock; the planner LLD and adaptation-pipeline LLD spec the loop bodies, candidate generation, scoring, and the LLM prompt.
- **Per-scale rollup shapes.** `Map<String, Object>` is the bound `core` enforces. The planner specs week-level rollup; the adaptation pipeline specs recipe-level rollup. Type safety stops at the module boundary.
- **Auth and admin role checks.** Owned by [auth.md](auth.md). The admin controller references the role check by name; the implementation is auth's concern.
- **Spring Modulith / ArchUnit enforcement.** [technical-architecture.md §Module boundary enforcement](../design/technical-architecture.md#module-boundary-enforcement) recommends one of the two; the choice and configuration belong to the implementation phase.
- **Decision-log retention / archival.** No purge job in v1. If the table becomes a storage problem (~6-12 months in), add a scheduled archive job in a separate LLD addendum.
- **The "why this plan?" user-facing endpoint.** Lives in the planner LLD. `core` exposes the trace; the planner translates it into a user-readable explanation.
- **Cross-instance distributed locking.** The system is single-instance per the system overview's local-first architecture; advisory locks are per-database, which is sufficient. Multi-instance deploy → revisit (or move to Redis-based locking).
- **Per-event payload schema enforcement.** Sealed bases enforce the structural contract; per-event-record fields are each module's concern.
- **Trace-ID propagation across HTTP egress** (Tesco grocery API, Anthropic API). Inbound MDC is solved here; outbound header injection is a Spring `RestClient` interceptor that lives in the calling module's config (or a future `core.http` package if it duplicates across modules — not v1).
