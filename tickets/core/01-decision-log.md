# Ticket: core — 01 Decision Log

## Summary

Implement the cross-cutting decision-log table + `DecisionLogService` + `LockService` in the `core` module. Every other module's optimisation loop, audit-trail need, or single-flight scope lock will depend on this. Delivers: schema, services, admin REST endpoints (read-only), unit + IT + mutation coverage.

This is **Pilot 1** of the implementation playbook. Establishes:
- The migration template
- The entity / repository / service / impl pattern
- The unit + IT split with mutation testing
- OpenAPI authoring for admin endpoints
- Contract testing wiring (swagger-request-validator)
- k6 baseline script

After this ticket merges, every module that follows uses the same shape.

## Behavioural spec (write this BEFORE implementation)

The implementation must guarantee:

1. Flyway migration `V20260601100000__core_create_decision_log.sql` creates the `decision_log` table with the schema in [`lld/core.md`](../../lld/core.md): `decision_id` (uuid PK), `trace_id` (uuid, indexed), `parent_decision_id` (uuid, nullable, FK to self), `scope_kind` (varchar 32), `scope_id` (uuid), `scale` (varchar 16), `triggered_by` (varchar 32), `actor_user_id` (uuid, nullable), `inputs` (jsonb), `candidates` (jsonb, nullable), `chosen` (jsonb, nullable), `reasoning` (text, nullable), `emitted_directive` (jsonb, nullable), `iteration` (integer, default 0), `duration_ms` (integer, nullable), `created_at` (timestamptz, NOT NULL DEFAULT now()).
2. Indexes: `(trace_id, created_at)`, `(scope_kind, scope_id, created_at DESC)`, `(actor_user_id, created_at DESC) WHERE actor_user_id IS NOT NULL`, `(parent_decision_id) WHERE parent_decision_id IS NOT NULL`.
3. `DecisionLogService.write(DecisionLogWriteRequest)` persists a row in a `REQUIRES_NEW` transaction (per [`lld/ai.md`](../../lld/ai.md) AiCallRecorder pattern — log survives caller rollback) and returns the assigned `decisionId`.
4. `decisionId` is application-generated via `UUID.randomUUID()`; never assigned by Postgres.
5. `DecisionLogQueryService.getById(decisionId)` returns the single row as a `DecisionLogDto`, or empty `Optional` if not found.
6. `DecisionLogQueryService.getByTraceId(traceId)` returns all rows for the trace, ordered by `created_at` ascending.
7. `DecisionLogQueryService.getAncestry(decisionId, maxDepth = 32)` walks `parent_decision_id` recursively via a Postgres CTE; cap at 32 to guard against malformed cycles. If the cycle is detected (depth exceeded), returns the partial ancestry plus a warning.
8. `LockService.tryAcquire(LockKey)` calls `pg_try_advisory_xact_lock(hash)` where `hash = murmur3_128(lockKey.serialize()) >> 64` (truncated to 64-bit signed bigint). Returns `true` if acquired, `false` if not.
9. The advisory lock is **transaction-scoped**, not session-scoped. Auto-released on commit/rollback. No explicit release call.
10. `LockKey` is a typed value object with factory methods: `LockKey.forPlanWeek(householdId, weekStart)`, `LockKey.forRecipe(recipeId)`, `LockKey.forCustom(scopeKind, scopeId)`. All factories produce a stable serialised form; same logical scope always maps to same hash.
11. Decision-log rows are **append-only**: no `@Version`, no `updated_at`, no UPDATE statements anywhere in the impl.
12. REST admin endpoints exist:
    - `GET /api/v1/admin/decision-log/{decisionId}` → 200 + DecisionLogDto, 404 if not found
    - `GET /api/v1/admin/decision-log/trace/{traceId}` → 200 + List<DecisionLogDto> (always; empty list if no matches; never 404)
    - `GET /api/v1/admin/decision-log/{decisionId}/ancestry?maxDepth=32` → 200 + List<DecisionLogDto> with ancestor chain
13. Admin endpoints require `ROLE_ADMIN`. For now (auth ticket lands next), gate with an `@PreAuthorize("hasRole('ADMIN')")` annotation that's not yet enforced — the auth module's filter chain will activate enforcement in a follow-up ticket. Add a `// TODO(auth-01-followup):` marker.
14. `MealPrepEvent` sealed interface defined: `interface MealPrepEvent { UUID traceId(); Instant occurredAt(); }`. `ScopeChangedEvent` sub-interface adds `String scopeKind(); UUID scopeId();`. Module-specific events later extend these.
15. `core.types` package contains: `SlotKind` enum (BREAKFAST, LUNCH, DINNER, SNACK, CUSTOM), `MealKind` enum (same shape, may be merged), `DataQuality` enum (USER_VERIFIED, IMPORTED, AI_GENERATED, WEB_DISCOVERED), `PreferenceTier` enum (HARD_CONSTRAINTS, TASTE_PROFILE, LIFESTYLE_CONFIG, PROFILE_METADATA). Types are `public final` (no inheritance).

## OpenAPI spec excerpt

Adds 3 paths and 1 schema to `openapi.yaml`.

```yaml
paths:
  /api/v1/admin/decision-log/{decisionId}:
    get:
      tags: [DecisionLog]
      operationId: getDecisionLogEntry
      summary: Get a single decision-log entry by ID.
      security:
        - cookieAuth: [admin]
      parameters:
        - in: path
          name: decisionId
          required: true
          schema: { type: string, format: uuid }
      responses:
        '200':
          description: The decision-log entry.
          content:
            application/json:
              schema: { $ref: '#/components/schemas/DecisionLogDto' }
        '404': { $ref: '#/components/responses/NotFound' }
        '401': { $ref: '#/components/responses/Unauthorized' }
  /api/v1/admin/decision-log/trace/{traceId}:
    get:
      tags: [DecisionLog]
      operationId: getDecisionLogByTrace
      summary: Get all decision-log entries for a trace, ordered by creation time.
      security:
        - cookieAuth: [admin]
      parameters:
        - in: path
          name: traceId
          required: true
          schema: { type: string, format: uuid }
      responses:
        '200':
          description: Decisions in the trace; empty list if none exist.
          content:
            application/json:
              schema:
                type: array
                items: { $ref: '#/components/schemas/DecisionLogDto' }
        '401': { $ref: '#/components/responses/Unauthorized' }
  /api/v1/admin/decision-log/{decisionId}/ancestry:
    get:
      tags: [DecisionLog]
      operationId: getDecisionAncestry
      summary: Walk parent_decision_id recursively up to maxDepth.
      security:
        - cookieAuth: [admin]
      parameters:
        - in: path
          name: decisionId
          required: true
          schema: { type: string, format: uuid }
        - in: query
          name: maxDepth
          schema: { type: integer, minimum: 1, maximum: 32, default: 32 }
      responses:
        '200':
          description: Ancestor chain ordered root-first; warning included if cycle detected.
          content:
            application/json:
              schema:
                type: object
                properties:
                  ancestors:
                    type: array
                    items: { $ref: '#/components/schemas/DecisionLogDto' }
                  cycleDetected: { type: boolean }
        '404': { $ref: '#/components/responses/NotFound' }
        '401': { $ref: '#/components/responses/Unauthorized' }

components:
  schemas:
    DecisionLogDto:
      type: object
      required: [decisionId, traceId, scopeKind, scopeId, scale, triggeredBy, inputs, iteration, createdAt]
      properties:
        decisionId: { type: string, format: uuid }
        traceId: { type: string, format: uuid }
        parentDecisionId: { type: string, format: uuid, nullable: true }
        scopeKind: { type: string, example: "plan-week" }
        scopeId: { type: string, format: uuid }
        scale: { type: string, enum: [WEEK, RECIPE, OTHER] }
        triggeredBy: { type: string, example: "user-initiated" }
        actorUserId: { type: string, format: uuid, nullable: true }
        inputs: { type: object, additionalProperties: true }
        candidates:
          type: array
          nullable: true
          items: { type: object, additionalProperties: true }
        chosen:
          type: object
          nullable: true
          additionalProperties: true
        reasoning: { type: string, nullable: true }
        emittedDirective:
          type: object
          nullable: true
          additionalProperties: true
        iteration: { type: integer, minimum: 0 }
        durationMs: { type: integer, nullable: true }
        createdAt: { type: string, format: date-time }
```

## Edge-case checklist

- [ ] Migration runs cleanly on fresh DB; idempotent if re-run (Flyway handles)
- [ ] Indexes verified by inspecting the test container's `pg_indexes`
- [ ] Append-only confirmed by attempting an UPDATE on the entity; spec'd as no `set*` exposed for non-construction fields
- [ ] `write` runs in `REQUIRES_NEW`; verified by IT that rolls back the caller's tx and asserts the row remains
- [ ] `getById` returns `Optional.empty()` (not throws) for missing IDs
- [ ] `getByTraceId` returns empty list (not 404) for unknown trace
- [ ] `getAncestry` cap at 32 verified; cycle detection produces a warning rather than infinite loop
- [ ] `LockService.tryAcquire` returns false when another transaction holds the lock (concurrent test)
- [ ] Lock auto-releases on tx rollback (verified by acquiring, rolling back, re-acquiring)
- [ ] Same `LockKey` factory inputs produce same hash
- [ ] Different `LockKey` factory inputs produce different hashes (collision check on ~1000 generated keys)
- [ ] `decisionId` always set application-side; verified by inspecting the entity's pre-persist state
- [ ] Admin endpoints return 401 when no auth; once auth lands, 403 for non-admin (TODO marker)
- [ ] OpenAPI request/response shape matches actual responses (contract test)
- [ ] DecisionLogDto JSONB fields round-trip cleanly (write deeply-nested object, read back, assert equal)
- [ ] Decision row with `null` candidates/chosen/reasoning persists and round-trips
- [ ] Concurrent write with the same `parentDecisionId` works (no FK contention)
- [ ] Fuzzed inputs: very long reasoning strings (10KB), very large `inputs` objects (100KB JSONB) succeed

## Files this ticket touches

```
src/main/java/com/example/mealprep/core/CoreModule.java                       new (facade)
src/main/java/com/example/mealprep/core/types/SlotKind.java                   new
src/main/java/com/example/mealprep/core/types/MealKind.java                   new
src/main/java/com/example/mealprep/core/types/DataQuality.java                new
src/main/java/com/example/mealprep/core/types/PreferenceTier.java             new
src/main/java/com/example/mealprep/core/events/MealPrepEvent.java             new (sealed interface)
src/main/java/com/example/mealprep/core/events/ScopeChangedEvent.java         new
src/main/java/com/example/mealprep/core/audit/api/dto/DecisionLogDto.java     new (record)
src/main/java/com/example/mealprep/core/audit/api/dto/DecisionLogWriteRequest.java   new (record)
src/main/java/com/example/mealprep/core/audit/api/dto/AncestryResponse.java   new (record)
src/main/java/com/example/mealprep/core/audit/api/controller/AdminDecisionLogController.java   new
src/main/java/com/example/mealprep/core/audit/api/mapper/DecisionLogMapper.java        new (MapStruct)
src/main/java/com/example/mealprep/core/audit/domain/entity/DecisionLog.java  new (JPA entity)
src/main/java/com/example/mealprep/core/audit/domain/repository/DecisionLogRepository.java   new
src/main/java/com/example/mealprep/core/audit/domain/service/DecisionLogService.java         new (interface)
src/main/java/com/example/mealprep/core/audit/domain/service/DecisionLogQueryService.java    new (interface)
src/main/java/com/example/mealprep/core/audit/domain/service/internal/DecisionLogServiceImpl.java    new
src/main/java/com/example/mealprep/core/lock/LockKey.java                     new (sealed value object)
src/main/java/com/example/mealprep/core/lock/LockService.java                 new (interface)
src/main/java/com/example/mealprep/core/lock/internal/AdvisoryLockServiceImpl.java     new
src/main/resources/db/migration/V20260601100000__core_create_decision_log.sql new
src/main/resources/openapi/openapi.yaml                                       modified (3 paths + 1 schema)
src/test/java/com/example/mealprep/core/audit/DecisionLogServiceImplTest.java new (unit)
src/test/java/com/example/mealprep/core/audit/DecisionLogServiceIT.java       new (Testcontainers; full write/read; REQUIRES_NEW behaviour)
src/test/java/com/example/mealprep/core/audit/DecisionLogControllerIT.java    new (MockMvc + OpenApiValidatorConfig contract test)
src/test/java/com/example/mealprep/core/lock/LockKeyTest.java                 new (unit; hash collision check)
src/test/java/com/example/mealprep/core/lock/AdvisoryLockServiceIT.java       new (Testcontainers; concurrent acquire/release)
src/test/java/com/example/mealprep/core/testdata/DecisionLogTestData.java     new (Test Data Builder)
perf/core/decision-log.js                                                     new (k6 script)
```

## Performance budget

| Endpoint / call | Median | p95 |
|---|---|---|
| `DecisionLogService.write` (in-process) | 5ms | 15ms |
| `GET /api/v1/admin/decision-log/{id}` | 15ms | 40ms |
| `GET /api/v1/admin/decision-log/trace/{id}` (10-row trace) | 30ms | 80ms |
| `GET /api/v1/admin/decision-log/{id}/ancestry` (5-deep ancestry) | 30ms | 100ms |
| `LockService.tryAcquire` | 5ms | 15ms |

k6 script asserts via repeated calls against a seeded test database with 10,000 decision-log rows across 1,000 traces of variable depth.

## Test plan

### Unit tests

| Class | Verifies |
|---|---|
| `DecisionLogServiceImplTest` | `write` calls repo with the right entity shape; `getById`/`getByTraceId`/`getAncestry` map repo results correctly; ancestry depth-cap enforcement; cycle detection; transaction-propagation annotation present on `write` (verified by reflection or AspectJ proxy check) |
| `LockKeyTest` | All 3 factory methods produce stable serialised forms; equal inputs yield equal hashes; 1000 random inputs yield <2 collisions on the truncated 64-bit hash |
| `DecisionLogMapperTest` | Entity ↔ DTO round-trip; null fields handled; JSONB shape preserved |

### Integration tests (Testcontainers Postgres)

| Class | Verifies |
|---|---|
| `DecisionLogServiceIT` | Full write/read via real DB. JSONB round-trip with deeply-nested `inputs`. `REQUIRES_NEW` survival: caller's tx rollback leaves the log row in place (this is the test that catches a forgotten `@Transactional(propagation = REQUIRES_NEW)`) |
| `DecisionLogControllerIT` | Each of the 3 admin endpoints with `OpenApiValidatorConfig` imported. Status codes, request/response schemas validated against the OpenAPI spec. 404 for unknown id; 200 + empty list for unknown traceId; ancestry response matches schema. |
| `AdvisoryLockServiceIT` | Two concurrent transactions try `tryAcquire(sameKey)` — first succeeds, second returns false. After first commits, third succeeds. After first rolls back, third also succeeds (auto-release). Different keys never contend. |
| `DecisionLogPerformanceIT` | Smoke test: 1000 inserts complete in < 5 seconds (loose; the real test is k6) |

### Mutation testing

Pitest must score ≥70% on `core.audit.domain.service.internal.*` and `core.lock.internal.*`. Lower thresholds for trivial classes (DTOs, mappers) are acceptable but should be explicit in `pitest.properties`.

### Contract test

Every IT that calls a controller endpoint imports `OpenApiValidatorConfig`. Each call validates request and response against the spec. Failures throw at the test method.

### Performance test

k6 script `perf/core/decision-log.js` runs against the seeded test DB at the perf phase of CI. Asserts the budgets above. Threshold breach fails the PR.

## Dependencies on other tickets

- **Hard dependency**: `project-setup-00-bootstrap` must merge first. This ticket relies on the pom additions, the OpenAPI base, the test infrastructure, and the CI workflow.

## Time estimate

**1 day.** Mechanical bones; tests dominate. Mutation testing thresholds may need a couple of iterations to hit 70% on the recursive CTE / lock service code paths.

## Decisions left to the implementor

1. **Recursive CTE exact phrasing** — Postgres native CTE (`WITH RECURSIVE ancestors AS ...`) vs application-layer iteration. CTE preferred for transactional consistency and atomicity; depth-cap enforced via `WHERE depth < :maxDepth`.
2. **Murmur3 implementation** — Guava's `Hashing.murmur3_128()` is fine; or `org.apache.commons.codec.digest.MurmurHash3`. Pick whichever is already on the classpath after Wave 1's pom additions.
3. **`LockKey` sealed-vs-final** — sealed interface with three permitted record types is cleaner than a single class with a discriminator. Recommended.
4. **Whether to ship the `core.types` enums in this ticket or split** — they're tiny and fit naturally; keep here.
5. **Test data builder shape** — `DecisionLogTestData.builder().withTraceId(...).withScope("plan-week", randomUUID()).build()`. One builder; methods for each field; sensible defaults.

## Acceptance / DoD

When the PR is opened, all CI gates green:

- [ ] `mvn verify` passes (unit + IT + JaCoCo ≥80% line / 70% branch)
- [ ] `mvn pitest:mutationCoverage` ≥70% on the production code in this ticket's packages
- [ ] OpenAPI spec lints; controller responses match schema (contract test passes in IT)
- [ ] ArchUnit passes (no cross-module imports introduced)
- [ ] Spotless format check passes
- [ ] `k6 run perf/core/decision-log.js` passes within budget
- [ ] PR template fully filled in; edge-case checklist all ticked
- [ ] Reviewer eyeballs ≥3 random tests for hollowness; pass

Squash-merge with commit message:

```
feat(core): add decision-log table + DecisionLogService + LockService

Pilot 1 of the implementation playbook. Establishes the per-module
patterns: migration, entity, repository, service interfaces + impl,
admin REST endpoints, OpenAPI authoring, contract test wiring,
mutation testing, k6 baseline.

See lld/core.md for design; tickets/core/01-decision-log.md for spec.
```

- Client-supplied LocalDate fields accept up to server-today+1 to absorb client TZ skew (provisions/02a, @PastOrNextDay).
