# Ticket: discovery — 01b Service Impl Skeleton + REST Controllers + `@ValidDiscoveryConstraints`

## Summary

Layer the **service-interface methods + REST surface** on top of 01a's empty-bodied interfaces. Per [`lld/discovery.md`](../../lld/discovery.md) §Service Interfaces (lines 339-366), §REST Controllers (lines 425-457), §Validation (lines 462-471), §Error responses (lines 444-457). Ships:

- **`DiscoveryServiceImpl`** — single `@Component @Service` class implementing both `DiscoveryService` AND `DiscoveryQueryService` per the [style guide rule](../../lld/style-guide.md#service-interfaces) (one impl, both interfaces). Replaces the 01a `DiscoveryServiceStub`. Implements every method **except** `runJobSync` (sync coordination ships with 01f) and the runner-coupled write paths inside `startJob` (the actual async runner is in 01d; `startJob` in 01b persists the job row + publishes `DiscoveryJobStartedEvent` then returns the QUEUED DTO).
- **Three REST controllers** under `discovery/api/controller/`: `DiscoveryJobsController`, `DiscoverySourcesController`, `DiscoveryAdminController` (the admin variant ships the `enable` / `disable` and `run-orphan-sweep` endpoints; `POST /admin/jobs/sync` defers to 01f).
- **`DiscoveryExceptionHandler`** `@RestControllerAdvice` at `discovery/api/`, annotated `@Order(Ordered.HIGHEST_PRECEDENCE)`. Per-failure exception subclasses: `DiscoveryJobNotFoundException` (404), `DiscoverySourceNotFoundException` (404), `DiscoveryJobAlreadyTerminalException` (422), `DiscoveryConstraintInvalidException` (422).
- **`@ValidDiscoveryConstraints`** class-level custom annotation + validator per [LLD line 465](../../lld/discovery.md). Asserts `schemaVersion` is supported (1), `requiredMealTypes` are members of the canonical meal-type set, no negative time bounds, `maxRecipesPerSource <= requestedCount`, `mustExcludeIngredientMappingKeys` entries pre-normalised.
- **`DiscoveryJobStartedEvent`** record published `AFTER_COMMIT` from `startJob`. 01a deferred all events; 01b ships this one because it triggers the async runner in 01d.
- **`@ConfigurationPropertiesScan`** verification on the main config (one-line add if missing) so `DiscoveryProperties` from 01a binds.

**Defers** still after 01b:
- `runJobSync` + `CompletableFuture` coordination + `POST /admin/jobs/sync` + `DiscoveryJobTimeoutException` (408) + `DiscoveryAllSourcesUnavailableException` (502) → **discovery-01f**
- `DiscoveryJobRunner` (async lifecycle), state transitions, per-step transactions, `DiscoveryJobCompletedEvent`, `DiscoveryRecipeIngestedEvent`, orphan sweep, cancellation in-memory flag → **discovery-01d**
- `DiscoverySource` SPI + `SourceRegistry` + `RobotsTxtGate` + rate limiter + content fingerprint + AI filter no-op → **discovery-01c**
- Curated source seed + Google CSE adapter → **discovery-01e**

## Behavioural spec

### `DiscoveryServiceImpl` — startJob (async path, persist-and-publish only)

1. `startJob(UUID userId, StartDiscoveryJobRequest request) : DiscoveryJobDto` — `@Transactional` (REQUIRED, write).
2. **Validate the request body** — Spring's `@Valid` handles Jakarta + `@ValidDiscoveryConstraints` (see invariants 18-22). Reject early.
3. **Resolve sources**: per [LLD line 515](../../lld/discovery.md):
    - `request.sourceKeys() == null` → all currently-enabled sources via `discoverySourceRepository.findByEnabledTrue()`.
    - Non-null → `discoverySourceRepository.findBySourceKeyIn(keys)`; if any requested key is missing OR `!enabled`, throw `DiscoveryConstraintInvalidException` (422) with `detail = "unknown or disabled source keys: ..."` and an `errors[]` extension listing the offending keys.
    - **No enabled source matches** → `DiscoveryConstraintInvalidException` (422) with detail `"zero enabled sources match the requested subset"`.
4. **Generate or use the supplied trace id**: `traceId = request.traceId() != null ? request.traceId() : UUID.randomUUID()`.
5. **Persist the `DiscoveryJob`**:
    - `id = UUID.randomUUID()`.
    - `userId = userId`, `trigger = request.trigger()`, `requestedCount = request.requestedCount()`.
    - `constraintsJson = objectMapper.valueToTree(request.constraints())` — serialise the `DiscoveryConstraints` record to `JsonNode` for the JSONB column.
    - `sourcesRequested = resolvedSources.stream().map(DiscoverySource::getSourceKey).toArray(String[]::new)`.
    - `status = DiscoveryJobStatus.QUEUED`, `queuedAt = Instant.now()`.
    - All counters default 0.
    - `sourcesSucceeded = sourcesFailed = new String[0]`.
    - `traceId = traceId`.
    - Save and flush — `saveAndFlush` because the response payload depends on `@Version = 0`. The "use saveAndFlush when response depends on @Version" gotcha applies (see embedded gotchas).
6. **Publish `DiscoveryJobStartedEvent`** `AFTER_COMMIT` via `ApplicationEventPublisher`:
    ```java
    eventPublisher.publishEvent(new DiscoveryJobStartedEvent(
        job.getId(), userId, job.getTrigger(),
        job.getRequestedCount(), Arrays.asList(job.getSourcesRequested()),
        job.getTraceId(), Instant.now()));
    ```
    Listener subscribes in 01d's `DiscoveryJobRunner.run`. In 01b the event publishes and no one consumes — that's fine; Spring drops unhandled events.
7. **Map and return** `DiscoveryJobMapper.toDto(savedJob)` — status `QUEUED`. Response 202 from the controller.

### `cancelJob` (idempotent, terminal-aware)

8. `cancelJob(UUID userId, UUID jobId) : void` — `@Transactional`.
9. `findByIdAndUserId(jobId, userId)` → if missing, throw `DiscoveryJobNotFoundException` (404).
10. **Terminal states** (`SUCCEEDED`, `FAILED`, `PARTIAL`) → throw `DiscoveryJobAlreadyTerminalException` (422) with detail `"job already in terminal state: <status>"`.
11. `QUEUED` → set `status = FAILED`, `completedAt = Instant.now()`, `errorSummary = "cancelled by user"`. Save. (In-memory cancellation flag for `RUNNING` jobs is part of 01d's runner; 01b only handles QUEUED — see "Cancellation surface in 01b" below.)
12. `RUNNING` → for 01b only: throw `DiscoveryJobAlreadyTerminalException` (422) with detail `"cancellation of in-flight jobs not supported in this build; queued-only"` — **temporary**; 01d wires the in-memory `cancellation_requested` flag and changes this branch to set the flag and return 200. **LLD divergence noted** — LLD line 547 says cancellation also covers RUNNING via in-memory flag; 01b ships only the QUEUED path because the runner doesn't exist yet. **Worth user review.**

### Cancellation surface in 01b

13. The REST endpoint exists in 01b: `POST /api/v1/discovery/jobs/{jobId}/cancel`. Body-less. Returns the current `DiscoveryJobDto` (200) after the cancel (QUEUED) or 422 (terminal / RUNNING-in-01b).
14. 01d will update the impl branch for RUNNING; the endpoint contract is unchanged.

### Query methods — `DiscoveryQueryService`

15. `getJob(UUID jobId) : Optional<DiscoveryJobDto>` — `@Transactional(readOnly = true)`. Looks up by id without userId filter (admin path). Returns DTO if found.
16. `getJobForUser(UUID userId, UUID jobId) : Optional<DiscoveryJobDto>` — `findByIdAndUserId`. Empty if either userId mismatch or jobId not found. **Single Optional return shape** — controller maps to 404 if empty.
17. `listJobsForUser(UUID userId, Pageable pageable) : Page<DiscoveryJobDto>` — `findByUserIdOrderByQueuedAtDesc`. Map `Page<Entity>` to `Page<Dto>` via Spring Data's `.map(mapper::toDto)`.
18. `getScrapeLog(UUID jobId, Pageable pageable) : Page<DiscoveryScrapeLogEntryDto>` — `findByJobIdOrderByOccurredAt`. **Does not verify userId** — admin/debug endpoint. Pre-checks `discoveryJobRepository.existsById(jobId)`; throws `DiscoveryJobNotFoundException` if absent. **LLD divergence**: the LLD doesn't specify a 404 for missing job on the scrape-log endpoint; LLD line 435 says 200/404. 404 makes the user-facing semantics clearer (vs. silently returning an empty page).
19. `listSources() : List<DiscoverySourceDto>` — `findAll()` → map. Sort by `displayName` ASC for stable UI ordering. **Worth user review** — alternative is to sort by `sourceKey`.
20. `getSource(String sourceKey) : Optional<DiscoverySourceDto>` — `findBySourceKey`.

### Admin update endpoints

21. `enableSource(String sourceKey) : DiscoverySourceDto` — public method on `DiscoveryService` (not `QueryService`). `@Transactional`. Loads by key; throws `DiscoverySourceNotFoundException` (404) if missing. Sets `enabled = true`, `userDisabled = false`. Saves. Returns DTO.
22. `disableSource(String sourceKey) : DiscoverySourceDto` — analogous. Sets `enabled = false`. Does NOT set `userDisabled = true` — distinguishes admin-driven disable from user-driven (the LLD makes this distinction per line 81; admin-only path here).
23. `runOrphanSweep() : OrphanSweepResult` — **defers the actual sweep logic to 01d**; in 01b the method is a placeholder returning `new OrphanSweepResult(0)` with a log warning. The endpoint exists; the runner-side implementation lands with 01d. **Worth user review** — alternative is to defer the endpoint too. Chosen: ship the endpoint contract in 01b so the OpenAPI surface is complete; 01d's implementation is a code-only change behind the endpoint.

### REST Controllers

24. `DiscoveryJobsController` under `/api/v1/discovery/jobs/...`:
    - `POST /jobs` → `startJob` — 202 + `DiscoveryJobDto`. `Location: /api/v1/discovery/jobs/{jobId}`.
    - `GET /jobs?page=&size=` → `listJobsForUser(userId, Pageable)` — 200 + `Page<DiscoveryJobDto>`. Default page=0, size=20, max size=100 (`@Max(100)`).
    - `GET /jobs/{jobId}` → `getJobForUser(userId, jobId)` — 200 + DTO; 404 if not present.
    - `POST /jobs/{jobId}/cancel` → `cancelJob` then return latest DTO — 200 + DTO (or 422 if already terminal / RUNNING).
    - `GET /jobs/{jobId}/scrape-log?page=&size=` → `getScrapeLog` — 200 + `Page<DiscoveryScrapeLogEntryDto>`; 404 if job not present.
25. `DiscoverySourcesController` under `/api/v1/discovery/sources/...`:
    - `GET /sources` → `listSources` — 200 + `List<DiscoverySourceDto>`.
    - `GET /sources/{sourceKey}` → `getSource` — 200 + DTO; 404 if not present.
26. `DiscoveryAdminController` under `/api/v1/discovery/admin/...`:
    - `POST /admin/sources/{sourceKey}/enable` → `enableSource` — 200 + DTO; 404.
    - `POST /admin/sources/{sourceKey}/disable` → `disableSource` — 200 + DTO; 404.
    - `POST /admin/run-orphan-sweep` → `runOrphanSweep` — 200 + `OrphanSweepResultDto { int resumedCount }`. In 01b this always returns `{ resumedCount: 0 }`.
    - `POST /admin/jobs/sync` → **deferred to discovery-01f** (the sync run endpoint needs `runJobSync` + `CompletableFuture` plumbing).

27. All controllers `@RestController` + `@RequestMapping("/api/v1/discovery/...")` + `@RequiredArgsConstructor` + `@Tag(name = "Discovery")` (Springdoc). The `userId` argument is resolved via `@AuthenticationPrincipal` (or whatever `CurrentUserResolver` from `auth-01a` exposes — the recipe controllers use the same pattern; mirror them).

### Validation

28. New annotation `@ValidDiscoveryConstraints` at `discovery/validation/ValidDiscoveryConstraints.java`. Class-level (`@Target({ElementType.PARAMETER, ElementType.FIELD, ElementType.TYPE_USE})`). `@Constraint(validatedBy = DiscoveryConstraintsValidator.class)`.
29. New validator `DiscoveryConstraintsValidator implements ConstraintValidator<ValidDiscoveryConstraints, DiscoveryConstraints>`. Rules (each a separate `addConstraintViolation` for clarity):
    - `schemaVersion != 1` → "unsupported schema version: <value>"
    - `requiredMealTypes` entries not in `{breakfast, lunch, dinner, snack}` → "unknown meal type: <value>"
    - `maxTotalTimeMins != null && maxTotalTimeMins < 0` → "maxTotalTimeMins must be non-negative"
    - `maxRecipesPerSource != null && maxRecipesPerSource > requestedCount` — **but**: the validator only has the `DiscoveryConstraints` value, not the parent `StartDiscoveryJobRequest`. Cross-record validation needs either (a) class-level validation on the request record itself, or (b) defer to service-layer. **Decision**: ship the check at the request-record level via a separate class-level `@AssertTrue` method on `StartDiscoveryJobRequest` itself (`@AssertTrue(message = "maxRecipesPerSource must be ≤ requestedCount")`), keeping `@ValidDiscoveryConstraints` focused on the constraints document alone. **Worth user review.**
    - `mustExcludeIngredientMappingKeys` entries not all lowercase / whitespace-trimmed → "ingredientMappingKey '<value>' must be lowercase and trimmed"

30. **Re-apply** `@Valid @ValidDiscoveryConstraints DiscoveryConstraints constraints` on the `StartDiscoveryJobRequest` record (01a's request had `@Valid` only because the annotation didn't exist; 01b creates the annotation and updates the request).

### Errors + `DiscoveryExceptionHandler`

31. New module exception subclasses under `discovery/exception/`:
    - `DiscoveryJobNotFoundException extends DiscoveryException` (404, `type = .../discovery-job-not-found`)
    - `DiscoverySourceNotFoundException extends DiscoveryException` (404, `type = .../discovery-source-not-found`)
    - `DiscoveryJobAlreadyTerminalException extends DiscoveryException` (422, `type = .../discovery-job-already-terminal`)
    - `DiscoveryConstraintInvalidException extends DiscoveryException` (422, `type = .../discovery-constraint-invalid`)
32. New `DiscoveryExceptionHandler` at `discovery/api/DiscoveryExceptionHandler.java`. `@RestControllerAdvice` + `@Order(Ordered.HIGHEST_PRECEDENCE)`. Maps each to `ProblemDetail.forStatusAndDetail(...)` with `type` URI per [LLD line 446](../../lld/discovery.md).
33. **Do NOT modify `config/GlobalExceptionHandler.java`.** The module-specific advice with `HIGHEST_PRECEDENCE` takes the discovery exceptions; the global handler keeps its catch-all role.
34. **`OptimisticLockException`** is handled by the global handler already (per [LLD line 454](../../lld/discovery.md) and the standard pattern); 01b doesn't duplicate.

### Events

35. New event record `DiscoveryJobStartedEvent` at `discovery/event/DiscoveryJobStartedEvent.java` per [LLD lines 479-483](../../lld/discovery.md) verbatim:
    ```java
    public record DiscoveryJobStartedEvent(
        UUID jobId, UUID userId, DiscoveryJobTrigger trigger,
        int requestedCount, List<String> sourcesRequested,
        UUID traceId, Instant occurredAt
    ) {}
    ```
36. `DiscoveryJobCompletedEvent` and `DiscoveryRecipeIngestedEvent` → **deferred to 01d** (the runner publishes them).

### OpenAPI

37. New `src/main/resources/openapi/paths/discovery.yaml` with the path-items in invariants 24-26.
38. New `src/main/resources/openapi/schemas/discovery.yaml` with schemas for `DiscoverySourceDto`, `DiscoveryJobDto`, `DiscoveryScrapeLogEntryDto`, `DiscoveryConstraints`, `StartDiscoveryJobRequest`, `DiscoveryJobStatus`, `DiscoveryJobTrigger`, `DiscoverySourceKind`, `DiscoverySourceType`, `ScrapeOutcome`, `RobotsTxtOutcome`, `ScrapeSkipReason`, `OrphanSweepResultDto`, `DiscoveryJobDtoPage`, `DiscoveryScrapeLogEntryDtoPage`.
39. **Page schema shape** per [template gotcha](../../../ai-workflow/templates/ticket-template.md): `additionalProperties: true`, flat `number`/`size`/`totalElements`/`totalPages`, NOT nested `page: { ... }`.
40. **Nullable fields** use **INLINE** `nullable: true` — never `$ref + nullable: true` (sibling keywords on `$ref` are silently ignored by swagger-parser).
41. Append to `src/main/resources/openapi/openapi.yaml`: 8 path entries under `paths:` (in a contiguous `# ===== discovery =====` anchor block) and ~14 schema refs under `components.schemas:`.

### Cross-module facade

42. The 01a `DiscoveryModule` re-export covers both interfaces; no facade changes in 01b.
43. **Delete the 01a `DiscoveryServiceStub`** — `DiscoveryServiceImpl` now implements both interfaces; the stub creates `NoUniqueBeanDefinitionException` otherwise. Single-file delete.

## Database

**Zero migrations in 01b.** All schema landed in 01a.

## OpenAPI updates

### New `src/main/resources/openapi/paths/discovery.yaml`

(One contiguous file holding the 8 path-items; structure mirrors the 01a path files in other modules.)

```yaml
discoveryJobs:
  post:
    tags: [Discovery]
    operationId: startDiscoveryJob
    summary: 'Enqueue a discovery job; returns the queued DTO. The async runner picks it up via DiscoveryJobStartedEvent.'
    security: [{ cookieAuth: [] }]
    requestBody:
      required: true
      content:
        application/json:
          schema: { $ref: '../schemas/discovery.yaml#/StartDiscoveryJobRequest' }
    responses:
      '202':
        description: 'Job queued.'
        headers:
          Location:
            schema: { type: string, format: uri }
        content:
          application/json:
            schema: { $ref: '../schemas/discovery.yaml#/DiscoveryJobDto' }
      '400': { description: 'Validation error', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '401': { description: 'Unauthenticated', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '422': { description: 'No enabled source matched / unknown sources', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
  get:
    tags: [Discovery]
    operationId: listDiscoveryJobs
    summary: 'Paginated discovery-job history for the current user, queued-at descending.'
    security: [{ cookieAuth: [] }]
    parameters:
      - in: query
        name: page
        schema: { type: integer, minimum: 0, default: 0 }
      - in: query
        name: size
        schema: { type: integer, minimum: 1, maximum: 100, default: 20 }
    responses:
      '200':
        description: 'Page of jobs.'
        content:
          application/json:
            schema: { $ref: '../schemas/discovery.yaml#/DiscoveryJobDtoPage' }
      '401': { description: 'Unauthenticated', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }

discoveryJobById:
  get:
    tags: [Discovery]
    operationId: getDiscoveryJob
    security: [{ cookieAuth: [] }]
    parameters:
      - in: path
        name: jobId
        required: true
        schema: { type: string, format: uuid }
    responses:
      '200': { description: 'Job DTO', content: { application/json: { schema: { $ref: '../schemas/discovery.yaml#/DiscoveryJobDto' } } } }
      '401': { description: 'Unauthenticated', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '404': { description: 'Not found', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }

discoveryJobCancel:
  post:
    tags: [Discovery]
    operationId: cancelDiscoveryJob
    summary: 'Cancel a queued discovery job. Idempotent for terminal states (returns 422).'
    security: [{ cookieAuth: [] }]
    parameters:
      - in: path
        name: jobId
        required: true
        schema: { type: string, format: uuid }
    responses:
      '200': { description: 'Cancelled (now FAILED).', content: { application/json: { schema: { $ref: '../schemas/discovery.yaml#/DiscoveryJobDto' } } } }
      '401': { description: 'Unauthenticated', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '404': { description: 'Not found', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '422': { description: 'Already terminal or in-flight (01b limitation)', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }

discoveryJobScrapeLog:
  get:
    tags: [Discovery]
    operationId: getDiscoveryJobScrapeLog
    security: [{ cookieAuth: [] }]
    parameters:
      - in: path
        name: jobId
        required: true
        schema: { type: string, format: uuid }
      - in: query
        name: page
        schema: { type: integer, minimum: 0, default: 0 }
      - in: query
        name: size
        schema: { type: integer, minimum: 1, maximum: 100, default: 20 }
    responses:
      '200': { description: 'Page of scrape rows.', content: { application/json: { schema: { $ref: '../schemas/discovery.yaml#/DiscoveryScrapeLogEntryDtoPage' } } } }
      '401': { description: 'Unauthenticated', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '404': { description: 'Job not found', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }

discoverySources:
  get:
    tags: [Discovery]
    operationId: listDiscoverySources
    security: [{ cookieAuth: [] }]
    responses:
      '200':
        description: 'All sources.'
        content:
          application/json:
            schema:
              type: array
              items: { $ref: '../schemas/discovery.yaml#/DiscoverySourceDto' }
      '401': { description: 'Unauthenticated', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }

discoverySourceById:
  get:
    tags: [Discovery]
    operationId: getDiscoverySource
    security: [{ cookieAuth: [] }]
    parameters:
      - in: path
        name: sourceKey
        required: true
        schema: { type: string, maxLength: 64 }
    responses:
      '200': { description: 'Source DTO.', content: { application/json: { schema: { $ref: '../schemas/discovery.yaml#/DiscoverySourceDto' } } } }
      '401': { description: 'Unauthenticated', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '404': { description: 'Not found', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }

discoveryAdminSourceEnable:
  post:
    tags: [Discovery]
    operationId: enableDiscoverySource
    security: [{ cookieAuth: [] }]
    parameters:
      - in: path
        name: sourceKey
        required: true
        schema: { type: string, maxLength: 64 }
    responses:
      '200': { description: 'Source enabled.', content: { application/json: { schema: { $ref: '../schemas/discovery.yaml#/DiscoverySourceDto' } } } }
      '401': { description: 'Unauthenticated', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '404': { description: 'Source not found', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }

discoveryAdminSourceDisable:
  post:
    tags: [Discovery]
    operationId: disableDiscoverySource
    security: [{ cookieAuth: [] }]
    parameters:
      - in: path
        name: sourceKey
        required: true
        schema: { type: string, maxLength: 64 }
    responses:
      '200': { description: 'Source disabled.', content: { application/json: { schema: { $ref: '../schemas/discovery.yaml#/DiscoverySourceDto' } } } }
      '401': { description: 'Unauthenticated', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '404': { description: 'Source not found', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }

discoveryAdminOrphanSweep:
  post:
    tags: [Discovery]
    operationId: runDiscoveryOrphanSweep
    summary: 'Resume orphan RUNNING jobs (heartbeat-stale). Returns count resumed.'
    security: [{ cookieAuth: [] }]
    responses:
      '200': { description: 'Sweep complete.', content: { application/json: { schema: { $ref: '../schemas/discovery.yaml#/OrphanSweepResultDto' } } } }
      '401': { description: 'Unauthenticated', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
```

### New `src/main/resources/openapi/schemas/discovery.yaml`

Schemas: the enums (one per — `DiscoveryJobStatus`, `DiscoveryJobTrigger`, `DiscoverySourceKind`, `DiscoverySourceType`, `ScrapeOutcome`, `RobotsTxtOutcome`, `ScrapeSkipReason`), the records (`DiscoverySourceDto`, `DiscoveryJobDto`, `DiscoveryScrapeLogEntryDto`, `DiscoveryConstraints`, `StartDiscoveryJobRequest`, `OrphanSweepResultDto`), and the two Page wrappers (`DiscoveryJobDtoPage`, `DiscoveryScrapeLogEntryDtoPage`).

**Critical gotchas to apply** when writing this file:

- **Nullable fields** (e.g. `DiscoveryJobDto.startedAt`, `completedAt`, `errorSummary`) use inline `nullable: true` — NEVER `$ref + nullable: true`.
- **`Page<T>` shape**: flat `number`/`size`/`totalElements`/`totalPages`, NOT nested `page: { ... }`. Add `additionalProperties: true` to every `<X>DtoPage` schema so swagger-parser tolerates Spring's `pageable` / `sort` extras (per [ticket-template.md](../../../ai-workflow/templates/ticket-template.md)).
- **Description strings containing `,` `:` `'`** must be single-quoted (per the YAML inline-flow trap gotcha — see embedded gotchas below).
- **Enum schemas with nullable** — `DiscoveryJobDto.errorSummary` is a string, not an enum, so the `$ref + nullable` trap doesn't apply. But `RecipeBranchDto.label`-style fields use inline shape. Watch for it.

### Append to `src/main/resources/openapi/openapi.yaml`

Add a new `# ===== discovery =====` anchor block under `paths:`:

```yaml
  # ===== discovery =====
  /api/v1/discovery/jobs: { $ref: 'paths/discovery.yaml#/discoveryJobs' }
  /api/v1/discovery/jobs/{jobId}: { $ref: 'paths/discovery.yaml#/discoveryJobById' }
  /api/v1/discovery/jobs/{jobId}/cancel: { $ref: 'paths/discovery.yaml#/discoveryJobCancel' }
  /api/v1/discovery/jobs/{jobId}/scrape-log: { $ref: 'paths/discovery.yaml#/discoveryJobScrapeLog' }
  /api/v1/discovery/sources: { $ref: 'paths/discovery.yaml#/discoverySources' }
  /api/v1/discovery/sources/{sourceKey}: { $ref: 'paths/discovery.yaml#/discoverySourceById' }
  /api/v1/discovery/admin/sources/{sourceKey}/enable: { $ref: 'paths/discovery.yaml#/discoveryAdminSourceEnable' }
  /api/v1/discovery/admin/sources/{sourceKey}/disable: { $ref: 'paths/discovery.yaml#/discoveryAdminSourceDisable' }
  /api/v1/discovery/admin/run-orphan-sweep: { $ref: 'paths/discovery.yaml#/discoveryAdminOrphanSweep' }
```

And under `components.schemas:` (one ref per schema, alphabetical):

```yaml
    # ===== discovery =====
    DiscoveryConstraints:          { $ref: 'schemas/discovery.yaml#/DiscoveryConstraints' }
    DiscoveryJobDto:               { $ref: 'schemas/discovery.yaml#/DiscoveryJobDto' }
    DiscoveryJobDtoPage:           { $ref: 'schemas/discovery.yaml#/DiscoveryJobDtoPage' }
    DiscoveryJobStatus:            { $ref: 'schemas/discovery.yaml#/DiscoveryJobStatus' }
    DiscoveryJobTrigger:           { $ref: 'schemas/discovery.yaml#/DiscoveryJobTrigger' }
    DiscoveryScrapeLogEntryDto:    { $ref: 'schemas/discovery.yaml#/DiscoveryScrapeLogEntryDto' }
    DiscoveryScrapeLogEntryDtoPage:{ $ref: 'schemas/discovery.yaml#/DiscoveryScrapeLogEntryDtoPage' }
    DiscoverySourceDto:            { $ref: 'schemas/discovery.yaml#/DiscoverySourceDto' }
    DiscoverySourceKind:           { $ref: 'schemas/discovery.yaml#/DiscoverySourceKind' }
    DiscoverySourceType:           { $ref: 'schemas/discovery.yaml#/DiscoverySourceType' }
    OrphanSweepResultDto:          { $ref: 'schemas/discovery.yaml#/OrphanSweepResultDto' }
    RobotsTxtOutcome:              { $ref: 'schemas/discovery.yaml#/RobotsTxtOutcome' }
    ScrapeOutcome:                 { $ref: 'schemas/discovery.yaml#/ScrapeOutcome' }
    ScrapeSkipReason:              { $ref: 'schemas/discovery.yaml#/ScrapeSkipReason' }
    StartDiscoveryJobRequest:      { $ref: 'schemas/discovery.yaml#/StartDiscoveryJobRequest' }
```

## Verbatim shape snippets

### `DiscoveryServiceImpl` — single impl of both interfaces

```java
@Service
@RequiredArgsConstructor
public class DiscoveryServiceImpl implements DiscoveryService, DiscoveryQueryService {

  private final DiscoveryJobRepository jobRepository;
  private final DiscoverySourceRepository sourceRepository;
  private final DiscoveryScrapeLogRepository scrapeLogRepository;
  private final DiscoveryJobMapper jobMapper;
  private final DiscoverySourceMapper sourceMapper;
  private final DiscoveryScrapeLogMapper scrapeLogMapper;
  private final ApplicationEventPublisher eventPublisher;
  private final ObjectMapper objectMapper;

  @Override
  @Transactional
  public DiscoveryJobDto startJob(UUID userId, StartDiscoveryJobRequest request) {
    List<DiscoverySource> resolved = resolveSources(request.sourceKeys());
    if (resolved.isEmpty()) {
      throw new DiscoveryConstraintInvalidException("zero enabled sources match the requested subset");
    }
    UUID traceId = request.traceId() != null ? request.traceId() : UUID.randomUUID();
    DiscoveryJob job = DiscoveryJob.builder()
        .id(UUID.randomUUID())
        .userId(userId)
        .trigger(request.trigger())
        .requestedCount(request.requestedCount())
        .constraintsJson(objectMapper.valueToTree(request.constraints()))
        .sourcesRequested(resolved.stream().map(DiscoverySource::getSourceKey).toArray(String[]::new))
        .status(DiscoveryJobStatus.QUEUED)
        .queuedAt(Instant.now())
        .candidatesSeen(0).candidatesAfterFilter(0)
        .recipesIngested(0).recipesSkippedDuplicate(0)
        .sourcesSucceeded(new String[0]).sourcesFailed(new String[0])
        .traceId(traceId)
        .build();
    DiscoveryJob saved = jobRepository.saveAndFlush(job);
    eventPublisher.publishEvent(new DiscoveryJobStartedEvent(
        saved.getId(), userId, saved.getTrigger(),
        saved.getRequestedCount(), Arrays.asList(saved.getSourcesRequested()),
        saved.getTraceId(), Instant.now()));
    return jobMapper.toDto(saved);
  }

  @Override
  public DiscoveryJobDto runJobSync(UUID userId, StartDiscoveryJobRequest request, Duration timeout) {
    throw new UnsupportedOperationException("runJobSync ships with discovery-01f");
  }

  @Override
  @Transactional
  public void cancelJob(UUID userId, UUID jobId) {
    DiscoveryJob job = jobRepository.findByIdAndUserId(jobId, userId)
        .orElseThrow(() -> new DiscoveryJobNotFoundException(jobId));
    if (EnumSet.of(DiscoveryJobStatus.SUCCEEDED, DiscoveryJobStatus.FAILED, DiscoveryJobStatus.PARTIAL)
        .contains(job.getStatus())) {
      throw new DiscoveryJobAlreadyTerminalException(jobId, job.getStatus());
    }
    if (job.getStatus() == DiscoveryJobStatus.RUNNING) {
      // 01b limitation: in-memory cancellation flag for RUNNING jobs ships with 01d
      throw new DiscoveryJobAlreadyTerminalException(jobId, job.getStatus());
    }
    job.setStatus(DiscoveryJobStatus.FAILED);
    job.setCompletedAt(Instant.now());
    job.setErrorSummary("cancelled by user");
    // saveAndFlush so the returned DTO reflects @Version bump
    jobRepository.saveAndFlush(job);
  }
  // ... query methods + enable/disable + runOrphanSweep
}
```

### `DiscoveryExceptionHandler` (HIGHEST_PRECEDENCE)

```java
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DiscoveryExceptionHandler {
  private static final String TYPE_PREFIX = "https://mealprep.example.com/problems/";

  @ExceptionHandler(DiscoveryJobNotFoundException.class)
  public ProblemDetail handle(DiscoveryJobNotFoundException ex) {
    ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    pd.setType(URI.create(TYPE_PREFIX + "discovery-job-not-found"));
    pd.setTitle("Discovery job not found");
    return pd;
  }
  // ... DiscoverySourceNotFound (404), DiscoveryJobAlreadyTerminal (422), DiscoveryConstraintInvalid (422)
}
```

### `DiscoveryJobStartedEvent`

```java
public record DiscoveryJobStartedEvent(
    UUID jobId, UUID userId, DiscoveryJobTrigger trigger,
    int requestedCount, List<String> sourcesRequested,
    UUID traceId, Instant occurredAt
) {}
```

### `@ValidDiscoveryConstraints` annotation + validator skeleton

```java
@Target({ElementType.PARAMETER, ElementType.FIELD, ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = DiscoveryConstraintsValidator.class)
public @interface ValidDiscoveryConstraints {
  String message() default "discovery constraints invalid";
  Class<?>[] groups() default {};
  Class<? extends Payload>[] payload() default {};
}

public class DiscoveryConstraintsValidator
    implements ConstraintValidator<ValidDiscoveryConstraints, DiscoveryConstraints> {

  private static final Set<String> MEAL_TYPES = Set.of("breakfast", "lunch", "dinner", "snack");

  @Override
  public boolean isValid(DiscoveryConstraints value, ConstraintValidatorContext ctx) {
    if (value == null) return true;
    boolean ok = true;
    ctx.disableDefaultConstraintViolation();
    if (value.schemaVersion() != 1) {
      ctx.buildConstraintViolationWithTemplate("unsupported schema version: " + value.schemaVersion())
          .addPropertyNode("schemaVersion").addConstraintViolation();
      ok = false;
    }
    if (value.requiredMealTypes() != null) {
      for (String mt : value.requiredMealTypes()) {
        if (!MEAL_TYPES.contains(mt)) {
          ctx.buildConstraintViolationWithTemplate("unknown meal type: " + mt)
              .addPropertyNode("requiredMealTypes").addConstraintViolation();
          ok = false;
        }
      }
    }
    if (value.maxTotalTimeMins() != null && value.maxTotalTimeMins() < 0) {
      ctx.buildConstraintViolationWithTemplate("maxTotalTimeMins must be non-negative")
          .addPropertyNode("maxTotalTimeMins").addConstraintViolation();
      ok = false;
    }
    if (value.mustExcludeIngredientMappingKeys() != null) {
      for (String k : value.mustExcludeIngredientMappingKeys()) {
        if (k == null || !k.equals(k.trim()) || !k.equals(k.toLowerCase(Locale.ROOT))) {
          ctx.buildConstraintViolationWithTemplate(
              "ingredientMappingKey must be lowercase and trimmed: '" + k + "'")
              .addPropertyNode("mustExcludeIngredientMappingKeys").addConstraintViolation();
          ok = false;
        }
      }
    }
    return ok;
  }
}
```

The cross-record `maxRecipesPerSource <= requestedCount` check goes on the `StartDiscoveryJobRequest` record itself via `@AssertTrue` (see invariant 29 alternative).

## Edge-case checklist

### `startJob`

- [ ] `POST /jobs` with valid body, `sourceKeys = null` → 202, all currently-enabled sources used, `DiscoveryJob` row inserted with `status = QUEUED`, `sourcesRequested` populated, `traceId` set
- [ ] `POST /jobs` with `sourceKeys = ["src_a", "src_b"]` (both enabled) → 202, `sourcesRequested = ["src_a", "src_b"]`
- [ ] `POST /jobs` with `sourceKeys = ["src_unknown"]` → 422 `discovery-constraint-invalid`
- [ ] `POST /jobs` with `sourceKeys = ["src_disabled"]` (exists, `enabled=false`) → 422 `discovery-constraint-invalid`
- [ ] `POST /jobs` with `requestedCount = 0` → 400 (`@Min(1)`)
- [ ] `POST /jobs` with `requestedCount = 51` → 400 (`@Max(50)`)
- [ ] `POST /jobs` with `constraints.schemaVersion = 99` → 400 via `@ValidDiscoveryConstraints`
- [ ] `POST /jobs` with `constraints.requiredMealTypes = ["brunch"]` → 400
- [ ] `POST /jobs` with `constraints.mustExcludeIngredientMappingKeys = ["Peanut"]` (non-lowercase) → 400
- [ ] `POST /jobs` with `constraints.maxRecipesPerSource = 100`, `requestedCount = 10` → 400 via `@AssertTrue` cross-record check
- [ ] `POST /jobs` with `traceId` supplied → echoed back on the response DTO
- [ ] `POST /jobs` without cookie → 401
- [ ] `POST /jobs` publishes exactly one `DiscoveryJobStartedEvent` AFTER_COMMIT — verify via `@RecordApplicationEvents` in IT

### `cancelJob`

- [ ] `POST /jobs/{jobId}/cancel` on QUEUED job → 200, status flips to FAILED, `errorSummary = "cancelled by user"`, `completedAt` set
- [ ] `POST /jobs/{jobId}/cancel` on SUCCEEDED job → 422 `discovery-job-already-terminal`
- [ ] `POST /jobs/{jobId}/cancel` on FAILED job → 422 `discovery-job-already-terminal`
- [ ] `POST /jobs/{jobId}/cancel` on RUNNING job → 422 `discovery-job-already-terminal` (01b limitation; 01d will replace this branch)
- [ ] `POST /jobs/{jobId}/cancel` on unknown jobId → 404 `discovery-job-not-found`
- [ ] `POST /jobs/{jobId}/cancel` for another user's job → 404 (`findByIdAndUserId` returns empty)

### Query / pagination

- [ ] `GET /jobs?page=0&size=20` → 200, queued-at descending
- [ ] `GET /jobs?size=101` → 400 (`@Max(100)`)
- [ ] `GET /jobs` Page<T> response shape is flat (`number`, `size`, `totalElements`, `totalPages`) — swagger-request-validator passes
- [ ] `GET /jobs/{jobId}` for own job → 200; for other user's job → 404; for missing → 404
- [ ] `GET /jobs/{jobId}/scrape-log` for missing job → 404; for own job → 200 + empty page (no scrape rows in 01b)
- [ ] `GET /sources` → 200 + sorted-by-display-name list
- [ ] `GET /sources/{sourceKey}` for unknown → 404 `discovery-source-not-found`

### Admin endpoints

- [ ] `POST /admin/sources/{sourceKey}/enable` flips `enabled = true`; idempotent
- [ ] `POST /admin/sources/{sourceKey}/disable` flips `enabled = false`; does NOT set `user_disabled`
- [ ] `POST /admin/sources/{sourceKey}/enable` on unknown → 404
- [ ] `POST /admin/run-orphan-sweep` → 200 + `{ resumedCount: 0 }` (01d makes this real)

### Cross-cutting

- [ ] `DiscoveryExceptionHandler` has `@Order(Ordered.HIGHEST_PRECEDENCE)` — verified by grep
- [ ] OpenAPI nullable fields use **INLINE** `nullable: true` (zero `$ref + nullable` sibling pairs — verify by grep on `schemas/discovery.yaml`)
- [ ] YAML description strings containing `,` `:` `'` are single-quoted
- [ ] `DiscoveryJobDtoPage` and `DiscoveryScrapeLogEntryDtoPage` have `additionalProperties: true`
- [ ] `swagger-request-validator` passes on every controller IT
- [ ] `DiscoveryBoundaryTest` from 01a still passes
- [ ] 01a's `DiscoveryServiceStub` deleted — `DiscoveryServiceImpl` is the sole bean
- [ ] No regression on 01a tests (`DiscoveryMigrationIT`, `DiscoveryConstraintsRoundTripTest`)
- [ ] No `pom.xml` adds

## Files this ticket touches

```
NEW   src/main/java/com/example/mealprep/discovery/api/controller/DiscoveryJobsController.java
NEW   src/main/java/com/example/mealprep/discovery/api/controller/DiscoverySourcesController.java
NEW   src/main/java/com/example/mealprep/discovery/api/controller/DiscoveryAdminController.java
NEW   src/main/java/com/example/mealprep/discovery/api/DiscoveryExceptionHandler.java
NEW   src/main/java/com/example/mealprep/discovery/api/dto/OrphanSweepResultDto.java

NEW   src/main/java/com/example/mealprep/discovery/domain/service/internal/DiscoveryServiceImpl.java

NEW   src/main/java/com/example/mealprep/discovery/event/DiscoveryJobStartedEvent.java

NEW   src/main/java/com/example/mealprep/discovery/exception/DiscoveryJobNotFoundException.java
NEW   src/main/java/com/example/mealprep/discovery/exception/DiscoverySourceNotFoundException.java
NEW   src/main/java/com/example/mealprep/discovery/exception/DiscoveryJobAlreadyTerminalException.java
NEW   src/main/java/com/example/mealprep/discovery/exception/DiscoveryConstraintInvalidException.java

NEW   src/main/java/com/example/mealprep/discovery/validation/ValidDiscoveryConstraints.java
NEW   src/main/java/com/example/mealprep/discovery/validation/DiscoveryConstraintsValidator.java

MOD   src/main/java/com/example/mealprep/discovery/domain/service/DiscoveryService.java                    (append method signatures: startJob, runJobSync (throws UnsupportedOperationException in 01b), cancelJob, enableSource, disableSource, runOrphanSweep)
MOD   src/main/java/com/example/mealprep/discovery/domain/service/DiscoveryQueryService.java               (append method signatures: getJob, getJobForUser, listJobsForUser, getScrapeLog, listSources, getSource)
MOD   src/main/java/com/example/mealprep/discovery/api/dto/StartDiscoveryJobRequest.java                   (re-apply @ValidDiscoveryConstraints + @AssertTrue cross-record)

DEL   src/main/java/com/example/mealprep/discovery/domain/service/internal/DiscoveryServiceStub.java       (01a placeholder; replaced by DiscoveryServiceImpl)

NEW   src/main/resources/openapi/paths/discovery.yaml                                                       (the 9 path-items above)
NEW   src/main/resources/openapi/schemas/discovery.yaml                                                     (~14 schemas)
MOD   src/main/resources/openapi/openapi.yaml                                                               (add # ===== discovery ===== block with 9 path entries + 14 schema refs)

NEW   src/test/java/com/example/mealprep/discovery/DiscoveryServiceImplTest.java                            (unit: startJob persists + publishes; unknown source 422; cancel idempotent; runJobSync throws)
NEW   src/test/java/com/example/mealprep/discovery/DiscoveryJobsControllerIT.java                           (HTTP IT: 202 / 400 / 401 / 404 / 422 / event publication via @RecordApplicationEvents)
NEW   src/test/java/com/example/mealprep/discovery/DiscoverySourcesControllerIT.java                        (HTTP IT: list / get / 404)
NEW   src/test/java/com/example/mealprep/discovery/DiscoveryAdminControllerIT.java                          (HTTP IT: enable/disable affect findByEnabledTrue; orphan sweep returns 0)
NEW   src/test/java/com/example/mealprep/discovery/DiscoveryConstraintsValidatorTest.java                   (unit: each rule rejection)
MOD   src/test/java/com/example/mealprep/discovery/testdata/DiscoveryTestData.java                          (add builders for request bodies + DTOs)
```

Count: ~21 NEW + 4 MOD + 1 DEL = ~26 files. Logic concentration in `DiscoveryServiceImpl` + the validator + the three controllers + the exception handler. Estimated agent runtime 45-55 min (OpenAPI authoring is the slowest piece).

**Files this ticket does NOT modify**:
- `src/main/java/com/example/mealprep/config/GlobalExceptionHandler.java`
- `src/test/java/com/example/mealprep/archunit/ModuleBoundaryTest.java`
- Other modules' files
- Discovery 01a entities, migrations, repos (frozen)

## Dependencies

- **Hard dependency**: `discovery-01a` (merged) — entities, migrations, repos, mappers, DTOs, module facade, `DiscoveryProperties`, async config, boundary test, `DiscoveryException` root.
- **Hard dependency**: `auth-01a` (merged) — `CurrentUserResolver`, `SessionAuthenticationFilter`.
- **Hard dependency**: `core` (merged) — `MealPrepException` root, decision-log infra.
- **Sibling tickets running in parallel** (Wave 3 round 2): `planner-01b`, `feedback-01b`, `adaptation-pipeline-01b`. None should touch any discovery file or `config/GlobalExceptionHandler.java`. Only shared file is `src/main/resources/openapi/openapi.yaml`; this ticket appends in its own `# ===== discovery =====` block, siblings append in their own module blocks.

## Acceptance / DoD

- [ ] `./mvnw -Dspotless.check.skip=true verify` passes
- [ ] `./mvnw spotless:apply` then `./mvnw spotless:check` clean
- [ ] CI green (build + spotless + OpenAPI lint + ArchUnit gate)
- [ ] All edge-case items above ticked
- [ ] `DiscoveryExceptionHandler` annotated `@Order(Ordered.HIGHEST_PRECEDENCE)` — verified
- [ ] Zero `$ref + nullable: true` pairs in `discovery.yaml` (grep) — sticky trap
- [ ] `DiscoveryJobDtoPage` / `DiscoveryScrapeLogEntryDtoPage` carry `additionalProperties: true`
- [ ] `runJobSync` throws `UnsupportedOperationException` — clearly flagged to 01f
- [ ] `DiscoveryBoundaryTest` from 01a still passes
- [ ] No `pom.xml` adds; no other modules touched

## Gotchas embedded (apply during implementation)

- **HIGHEST_PRECEDENCE** on `DiscoveryExceptionHandler`. Without it, a catch-all `@ExceptionHandler(Exception.class)` in `GlobalExceptionHandler` may swallow the discovery exceptions and ship 500s.
- **OpenAPI 3.0 `$ref + nullable: true` is silently ignored** — sticky trap, hit in Round 1/4/6 of the source project. For nullable scalars (`startedAt`, `completedAt`, `errorSummary`, ...), use INLINE `nullable: true`. For nullable named-schema fields (e.g. nullable `RobotsTxtOutcome`), inline the enum's `type: string, enum: [...], nullable: true` — don't `$ref`.
- **Spring `Page<T>` response bodies** include `pageable` and `sort` extras that named page schemas reject unless `additionalProperties: true`.
- **YAML inline-flow `{ ... }` strings with internal commas/colons/quotes**: in flow style, commas separate map entries. The description `'Idempotent on (userId, source, orderRef); re-submission yields 409'` contains a comma + semicolon and MUST be single-quoted. swagger-cli fails otherwise.
- **`saveAndFlush` when the response payload depends on `@Version`**: `startJob` returns a DTO whose `optimisticVersion` reflects the freshly-saved row. `save()` doesn't flush — the response would carry stale state. Use `saveAndFlush()`. The preference-01a trial logged this as a sticky bug.
- **HTTP-client adapters (RestClient / WebClient)** must live in `..api..` or `..config..` — NOT `domain.service.internal`. The project's ArchUnit `springWebStaysInApi` rule enforces this. **01b does NOT add any HTTP-client adapters** — those land with 01c's `RobotsTxtGate` and 01e's Google CSE. Flag for awareness.
- **MockMvc URL-template double-encoding**: 01b's tests use `sourceKey` path-vars (max 64 chars, kebab/snake). Use plain ASCII keys like `bbc_good_food` — no URL-encoding hazards.
- **Boolean DTO fields with default**: `DiscoverySourceDto.enabled / userDisabled / respectRobotsTxt` are primitives in the entity; the DTO record uses primitive `boolean` too so unset stays `false` (no null in JSON). Matches the schema's non-nullable `boolean` declaration.
- **SPI-with-Noop pattern**: N/A for 01b — `DiscoveryServiceImpl` is the only impl of `DiscoveryService` and `DiscoveryQueryService`. No `@ConditionalOnMissingBean` needed. The 01a `DiscoveryServiceStub` is deleted because the real impl supersedes it (NOT a Noop pattern — a placeholder pattern).
- **`@TransactionalEventListener` + `@Transactional` propagation**: N/A in 01b (no listeners ship here; 01d adds the runner's listener with `REQUIRES_NEW`).
- **Don't trust LLD column widths blindly**: N/A in 01b — no schema changes.

## What's NOT in scope

- `runJobSync` + `CompletableFuture` coordination + `POST /admin/jobs/sync` endpoint + 408 / 502 mappings → **discovery-01f**
- `DiscoveryJobRunner` (async lifecycle), claim-step optimistic-version, per-step transactions, `DiscoveryJobCompletedEvent`, `DiscoveryRecipeIngestedEvent`, orphan sweep impl, in-memory cancellation flag for RUNNING jobs → **discovery-01d**
- `DiscoverySource` SPI + `SourceRegistry` + `RobotsTxtGate` (interface + impl) + `RateLimiterRegistry` + `ContentFingerprintHasher` + `CandidateAiFilter` no-op → **discovery-01c**
- Curated source seed migration (`R__discovery_seed_source_registry.sql` populated with ~25-30 INSERTs — 01a shipped it empty) + Google Custom Search adapter + reference curated `DiscoverySource` impl → **discovery-01e**
- `DiscoveryService` injected only by `planner.*` and `recipe.*` ArchUnit rule (LLD line 368) — defer until both modules are actually injecting; or land with 01d when the surface stabilises
- Service-layer validation: zero enabled sources, unknown subset member — **partially in scope** (the unknown-source 422 is shipped in 01b's startJob); the "RUNNING cancellation rejected" branch is a temporary 422
- Decision-log integration — discovery's loop iterations write to `decision_log` via `DecisionLogService` once 01d's runner exists; 01b's `startJob` is too early in the flow to log usefully

Squash-merge with: `feat(discovery): 01b — service impl skeleton + REST controllers + @ValidDiscoveryConstraints validator`
