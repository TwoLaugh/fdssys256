# Discovery Module — LLD

*Implementation specification for the recipe discovery module: async discovery jobs, a pluggable source registry, a politeness-aware scrape log, and the bounded interface around the actual scraping/extraction work. Translates the discovery concern of [recipe-system.md](../design/recipe-system.md) and the cold-start pre-step of [meal-planner.md](../design/meal-planner.md) into a buildable Spring Boot module.*

## Scope

This document specifies the `discovery` module — package layout, JPA entities, Flyway migrations, repositories, service interfaces, DTOs, REST controllers, validation, events, business-logic flows, transaction boundaries, and the test plan. Conventions defer to [lld/style-guide.md](style-guide.md); this LLD restates a rule only when the module-specific application matters.

The HLD describes online discovery as one of three sources feeding the recipe catalogue (with manual import and AI generation) — see [recipe-system.md §Import Pipeline](../design/recipe-system.md#import-pipeline) and [meal-planner.md §Cold start](../design/meal-planner.md#cold-start). This module owns the **orchestration**: job lifecycle, source registry, scheduling, rate limiting, robots.txt politeness, scrape logging, dedup hand-off. The actual URL → `ParsedRecipe` work is delegated to **`RecipeExtractionService`** specified in [recipe-extraction-pipeline.md](recipe-extraction-pipeline.md) — a shared extraction core also used by the recipe module's user-driven import flow.

**v1 sources (locked 2026-05-07):**
- **`CURATED` sources** — ~25-30 hand-picked recipe sites seeded in `R__discovery_seed_curated_sources.sql`. Each has a per-site `DiscoverySource` impl that knows how to enumerate candidate URLs (sitemap / RSS / category index). Highest reliability.
- **`SEARCH` source** — Google Custom Search JSON API, configured (out-of-band) to search across a broader corpus of recipe sites. Free tier (100 queries/day) covers v1 usage; paid above.

User can disable any source via Settings; the runner round-robins enabled sources by `last_used_at`.

Discovery has no catalogue table of its own; every successful fetch becomes a system-catalogue recipe via `RecipeWriteApi.saveImportedRecipe` with `data_quality = web_discovered`. The discovery module owns the *journey* (job, candidate, fetch attempt, fingerprint); the recipe module owns the *destination*.

The DiscoveryJob async pattern mirrors the `ai_call_log` + `AiUnavailableException` model in [ai.md](ai.md): one source down → `partial`; all sources down → `failed` with surfaced error.

---

## Package Layout

```
com.example.mealprep.discovery/
├── DiscoveryModule.java                 facade re-exporting public service interfaces
├── api/
│   ├── controller/                      DiscoveryJobsController, DiscoverySourcesController, DiscoveryAdminController
│   ├── dto/                             records (see DTOs section)
│   └── mapper/                          DiscoveryJobMapper, DiscoverySourceMapper, DiscoveryScrapeLogMapper
├── domain/
│   ├── entity/                          DiscoveryJob, DiscoverySource, DiscoveryScrapeLog
│   ├── repository/                      Spring Data interfaces — package-private
│   └── service/
│       ├── DiscoveryService.java        public interface (planner, recipe-pipeline injection)
│       ├── DiscoveryQueryService.java   public interface (admin / debug reads)
│       ├── DiscoverySource.java         public SPI for source plugins
│       ├── DiscoveryServiceImpl.java    single impl of both query + update
│       └── internal/                    DiscoveryJobRunner, SourceRegistry, RobotsTxtGate,
│                                          RateLimiterRegistry, ContentFingerprintHasher,
│                                          CandidateAiFilter (pass-through in v1)
├── source/                              concrete DiscoverySource implementations (empty in v1 — hard pocket)
├── event/                               DiscoveryJobStartedEvent, DiscoveryJobCompletedEvent, DiscoveryRecipeIngestedEvent
├── exception/                           module-root + per-failure subclasses
├── validation/                          @ValidDiscoveryConstraints + validators
└── config/                              DiscoveryProperties, DiscoveryAsyncConfig
```

`DiscoveryService` is the public facade exposed to the planner (cold-start) and the recipe module (user-initiated). `DiscoveryQueryService` is read-only for the admin/debug controller. Internal helpers are package-private; only the runner is wired as a bean.

The `source/` package is empty in v1 — every concrete `DiscoverySource` is added per the user-decided source list. ArchUnit asserts `source/` depends only on `domain/service/` interfaces and core types.

---

## Database

Migrations live under `src/main/resources/db/migration/` with the project-wide timestamp scheme:

```
V20260615120000__discovery_create_discovery_sources.sql
V20260615120100__discovery_create_discovery_jobs.sql
V20260615120200__discovery_create_discovery_scrape_log.sql
R__discovery_seed_source_registry.sql                       (repeatable, intentionally empty in v1)
```

### V20260615120000 — Discovery sources

The source registry. Each row describes a `DiscoverySource` bean and its operational policy (rate limit, robots mode, enabled-flag). Bean wired by `sourceKey`. New sources arrive as a new row + a bean — no runner change.

```sql
CREATE TABLE discovery_sources (
    id                          uuid PRIMARY KEY,
    source_key                  varchar(64) NOT NULL UNIQUE,         -- matches DiscoverySource.key()
    display_name                varchar(120) NOT NULL,
    source_type                 varchar(16) NOT NULL,                -- 'CURATED' | 'SEARCH' (locked 2026-05-07)
    kind                        varchar(32) NOT NULL,                -- 'sitemap' | 'rss_feed' | 'category_index' | 'search_api'
    base_url                    varchar(255) NOT NULL,
    enabled                     boolean NOT NULL DEFAULT true,
    user_disabled               boolean NOT NULL DEFAULT false,      -- distinguishes admin-disabled (enabled=false) from user-disabled
    requests_per_minute         integer NOT NULL DEFAULT 6,          -- per-source pacing budget
    requests_per_day            integer NOT NULL DEFAULT 500,
    respect_robots_txt          boolean NOT NULL DEFAULT true,
    user_agent                  varchar(160) NOT NULL,
    crawl_config                jsonb,                               -- per-kind config (sitemap URL, RSS URL, search engine ID, etc.)
    failure_streak              integer NOT NULL DEFAULT 0,          -- circuit-breaker bookkeeping
    last_failure_at             timestamptz,
    last_success_at             timestamptz,
    last_used_at                timestamptz,                         -- runner round-robin
    quality_score               numeric(4,3),                        -- rolling: fraction of extractions reaching reconciled status
    notes                       text,
    optimistic_version          bigint NOT NULL DEFAULT 0,           -- @Version
    created_at                  timestamptz NOT NULL,
    updated_at                  timestamptz NOT NULL
);
-- Hot read: the runner enumerates enabled sources for every job.
CREATE INDEX idx_discovery_sources_enabled ON discovery_sources (enabled) WHERE enabled = true;
-- Admin lookup by kind for staged rollouts.
CREATE INDEX idx_discovery_sources_kind ON discovery_sources (kind);
```

The repeatable seed `R__discovery_seed_source_registry.sql` ships empty in v1; once sources are chosen, it carries one `INSERT ... ON CONFLICT (source_key) DO UPDATE` per source. The registry table is the sole source identity store — no enum, no hardcoded list.

### V20260615120100 — Discovery jobs

State machine: `queued → running → succeeded | failed | partial`. Persisted so the planner can poll and so a runner restart resumes orphan `running` rows.

```sql
CREATE TABLE discovery_jobs (
    id                          uuid PRIMARY KEY,
    user_id                     uuid NOT NULL,
    trigger                     varchar(32) NOT NULL,                -- 'cold_start' | 'user_initiated' | 'scheduled'
    requested_count             integer NOT NULL,                    -- N recipes requested
    constraints_json            jsonb NOT NULL,                      -- DiscoveryConstraints snapshot
    sources_requested           text[] NOT NULL,                     -- source_keys at enqueue time
    status                      varchar(16) NOT NULL DEFAULT 'queued',
    queued_at                   timestamptz NOT NULL,
    started_at                  timestamptz,
    completed_at                timestamptz,
    candidates_seen             integer NOT NULL DEFAULT 0,
    candidates_after_filter     integer NOT NULL DEFAULT 0,          -- post-AI-filter (when filter ran)
    recipes_ingested            integer NOT NULL DEFAULT 0,
    recipes_skipped_duplicate   integer NOT NULL DEFAULT 0,
    sources_succeeded           text[] NOT NULL DEFAULT '{}',
    sources_failed              text[] NOT NULL DEFAULT '{}',
    error_summary               text,                                -- non-null only on failed | partial
    trace_id                    uuid NOT NULL,
    optimistic_version          bigint NOT NULL DEFAULT 0,           -- @Version
    created_at                  timestamptz NOT NULL,
    updated_at                  timestamptz NOT NULL
);
-- Polling and admin list.
CREATE INDEX idx_discovery_jobs_user_status ON discovery_jobs (user_id, status, queued_at DESC);
-- Runner watchdog: pick up orphan running jobs after a heartbeat timeout.
CREATE INDEX idx_discovery_jobs_status_started ON discovery_jobs (status, started_at)
    WHERE status = 'running';
-- Trace-id correlation for cold-start invocations from the planner.
CREATE INDEX idx_discovery_jobs_trace ON discovery_jobs (trace_id);
```

`constraints_json` carries the `DiscoveryConstraints` record — schema-versioned per the style guide's JSONB discipline. Read whole, never filtered on inner fields. **Worth user review:** materialising hot constraint slices (cuisine, dietary_flags) into columns is deferred until query patterns emerge.

### V20260615120200 — Scrape log

Append-only audit of every fetch attempt. One row per HTTP request to a candidate URL. Powers debugging, rate-limit diagnostics, robots.txt audit, and content-fingerprint dedup.

```sql
CREATE TABLE discovery_scrape_log (
    id                          uuid PRIMARY KEY,
    job_id                      uuid NOT NULL REFERENCES discovery_jobs(id) ON DELETE CASCADE,
    source_key                  varchar(64) NOT NULL,                -- denormalised from discovery_sources
    candidate_url               varchar(2048) NOT NULL,
    canonical_url               varchar(2048),                       -- post-redirect, post-canonicalisation
    status                      varchar(24) NOT NULL,                -- see scrape outcome enum
    http_status_code            integer,
    robots_txt_outcome          varchar(16) NOT NULL,                -- 'allowed' | 'disallowed' | 'unavailable' | 'skipped'
    latency_ms                  integer,
    content_fingerprint         varchar(64),                         -- SHA-256 hex of normalised body
    extraction_method           varchar(32),                         -- 'jsonld' | 'microdata' | 'site_template' | 'ai_extraction' | null
    extraction_confidence       numeric(4,3),
    recipe_id                   uuid,                                -- FK target only after successful ingest
    skip_reason                 varchar(64),                         -- 'duplicate' | 'hard_constraint' | 'low_confidence' | 'rate_limited' | 'robots_disallowed' | ...
    error_class                 varchar(64),
    error_message               text,
    occurred_at                 timestamptz NOT NULL
);
-- Job-scoped read: the runner aggregates per-source outcomes for the result summary.
CREATE INDEX idx_discovery_scrape_log_job ON discovery_scrape_log (job_id, occurred_at);
-- Source-scoped read: rate-limit diagnostics and circuit-breaker stats.
CREATE INDEX idx_discovery_scrape_log_source_time ON discovery_scrape_log (source_key, occurred_at DESC);
-- Dedup: the runner short-circuits on a fingerprint already seen for this user.
CREATE INDEX idx_discovery_scrape_log_fingerprint ON discovery_scrape_log (content_fingerprint)
    WHERE content_fingerprint IS NOT NULL;
-- Robots.txt audit trail.
CREATE INDEX idx_discovery_scrape_log_robots ON discovery_scrape_log (robots_txt_outcome, occurred_at DESC)
    WHERE robots_txt_outcome IN ('disallowed', 'unavailable');
-- Walk back from a system-catalogue recipe to the fetch that produced it.
CREATE INDEX idx_discovery_scrape_log_recipe ON discovery_scrape_log (recipe_id) WHERE recipe_id IS NOT NULL;
```

`recipe_id` is deliberately *not* a hard FK to `recipe_recipes` — discovery must not pull the recipe module's tables into its Flyway path. It's a uuid pointer for read-side joins; if a recipe is hard-deleted the row becomes a tombstone.

Retention: scrape rows kept 6 months, then aggregated into per-source per-day metrics on a scheduled sweep (sweep job not specified in v1 — **worth user review** when volume warrants).

---

## Entities

All entities follow the style guide: UUID `@Id` set application-side, `@Version` on every mutable aggregate root, `@CreatedDate` / `@LastModifiedDate` audit columns, Lombok `@Getter @Setter @Builder @NoArgsConstructor(access = PROTECTED) @AllArgsConstructor`. JSONB columns mapped via `@Type(JsonType.class)` from `hypersistence-utils`. `text[]` columns mapped via Hibernate's `String[]` array support.

| Entity | Notes |
|---|---|
| `DiscoverySource` | Aggregate root. Mutable on `enabled`, `failureStreak`, `lastFailureAt`, `lastSuccessAt`. `@Version`. The runner reads via `findAllEnabled` once per job and never mid-job — source toggles take effect on the next job. |
| `DiscoveryJob` | Aggregate root. State transitions in `DiscoveryJobRunner` are `@Transactional` and bump `@Version`. `constraintsJson` mapped to `DiscoveryConstraints` via JSONB. `sourcesRequested`, `sourcesSucceeded`, `sourcesFailed` as `String[]`. |
| `DiscoveryScrapeLog` | Append-only. No `@Version`, no `@LastModifiedDate`. Inserted in batches at job-completion via `saveAll`; per-fetch rows are written eagerly so partial logs survive a runner crash. |

Enums local to the module: `DiscoverySourceKind` (`SEARCH_ENGINE`, `RECIPE_SITE_API`, `RSS_FEED`), `DiscoveryJobStatus` (`QUEUED`, `RUNNING`, `SUCCEEDED`, `FAILED`, `PARTIAL`), `DiscoveryJobTrigger` (`COLD_START`, `USER_INITIATED`, `SCHEDULED`), `ScrapeOutcome` (`SUCCESS`, `SKIPPED`, `RATE_LIMITED`, `ROBOTS_DISALLOWED`, `HTTP_ERROR`, `EXTRACTION_FAILED`, `DUPLICATE`, `HARD_CONSTRAINT_VIOLATION`), `RobotsTxtOutcome` (`ALLOWED`, `DISALLOWED`, `UNAVAILABLE`, `SKIPPED`), `ScrapeSkipReason` (`DUPLICATE`, `HARD_CONSTRAINT`, `LOW_CONFIDENCE`, `RATE_LIMITED`, `ROBOTS_DISALLOWED`, `JOB_QUOTA_REACHED`, `AI_FILTER_REJECTED`).

---

## DTOs

All DTOs are Java records. They cross module boundaries; entities never do.

```java
public record DiscoverySourceDto(
    UUID id, String sourceKey, String displayName, DiscoverySourceKind kind,
    String baseUrl, boolean enabled,
    int requestsPerMinute, int requestsPerDay,
    boolean respectRobotsTxt, String userAgent,
    int failureStreak, Instant lastFailureAt, Instant lastSuccessAt,
    String notes, long optimisticVersion
) {}

public record DiscoveryJobDto(
    UUID id, UUID userId, DiscoveryJobTrigger trigger,
    int requestedCount, DiscoveryConstraints constraints,
    List<String> sourcesRequested,
    DiscoveryJobStatus status,
    Instant queuedAt, Instant startedAt, Instant completedAt,
    int candidatesSeen, int candidatesAfterFilter,
    int recipesIngested, int recipesSkippedDuplicate,
    List<String> sourcesSucceeded, List<String> sourcesFailed,
    String errorSummary, UUID traceId, long optimisticVersion
) {}

public record DiscoveryScrapeLogEntryDto(
    UUID id, UUID jobId, String sourceKey,
    String candidateUrl, String canonicalUrl,
    ScrapeOutcome status, Integer httpStatusCode,
    RobotsTxtOutcome robotsTxtOutcome, Integer latencyMs,
    String contentFingerprint, String extractionMethod,
    BigDecimal extractionConfidence, UUID recipeId,
    ScrapeSkipReason skipReason, String errorClass, String errorMessage,
    Instant occurredAt
) {}
```

### Constraints carried into a job

`DiscoveryConstraints` is the snapshot of what the planner / user wanted at job-enqueue time. Frozen at enqueue so a constraint change mid-job does not retroactively alter the search.

```java
public record DiscoveryConstraints(
    int schemaVersion,                              // 1; bump when shape changes non-additively
    List<String> requiredCuisines,                  // e.g. ["East Asian", "Mediterranean"]
    List<String> requiredMealTypes,                 // ["dinner", "lunch"]
    Integer maxTotalTimeMins,
    List<String> mustExcludeIngredientMappingKeys,  // hard-constraint snapshot — never softened
    List<String> dietaryFlags,                      // ["vegetarian", "gluten_free"]
    List<String> preferenceHints,                   // free-form hints for the AI filter, e.g. ["lighter dishes", "high-protein"]
    Integer maxRecipesPerSource                     // bounds per-source dominance, default 20
) {}
```

`mustExcludeIngredientMappingKeys` is computed by the caller (planner / pipeline), populated from the user's hard constraints via `HardConstraintFilterService` and the recipe module's existing dedup hashes. Discovery applies it as a **second** hard-filter pass after extraction — the deterministic safety net per [technical-architecture.md §Hard Constraint Filter](../design/technical-architecture.md#hard-constraint-filter) — and never trusts the AI filter to enforce it.

### Request shape

```java
public record StartDiscoveryJobRequest(
    @NotNull DiscoveryJobTrigger trigger,
    @Min(1) @Max(50) int requestedCount,           // bound matches HLD's "up to 50 recipes per cold-start run"
    @NotNull @Valid @ValidDiscoveryConstraints DiscoveryConstraints constraints,
    @Nullable List<@NotBlank String> sourceKeys,   // null → all enabled; otherwise the named subset
    @Nullable UUID traceId                          // planner / pipeline supplies; otherwise generated
) {}
```

### Source-internal types

Exposed only to `DiscoverySource` implementations — generic, source-agnostic.

```java
public record DiscoveryQuery(DiscoveryConstraints constraints, int maxResults, String userAgent) {}

public record DiscoveryCandidate(
    String sourceKey, String candidateUrl,
    String snippetTitle, String snippetDescription,
    Map<String, String> sourceMetadata              // SERP rank, RSS pub date, etc.
) {}

public record ParsedRecipe(
    String canonicalUrl, String name, String description,
    List<ParsedIngredient> ingredients,             // shape mirrors the recipe module's create payload
    List<ParsedMethodStep> method,
    ParsedRecipeMetadata metadata,
    String extractionMethod,                        // 'jsonld' | 'microdata' | 'site_template' | 'ai_extraction'
    BigDecimal extractionConfidence                 // 0..1
) {
    public record ParsedIngredient(String displayName, String ingredientMappingKey,
        BigDecimal quantity, String unit, String preparation, boolean optional) {}
    public record ParsedMethodStep(int stepNumber, String instruction, Integer durationMinutes) {}
    public record ParsedRecipeMetadata(Integer servings, Integer prepTimeMins, Integer cookTimeMins,
        Integer totalTimeMins, List<String> equipmentRequired, String cuisine, List<String> mealTypes) {}
}
```

`ParsedRecipe` is field-compatible with the recipe module's `CreateRecipeRequest`. Nutrition fields are intentionally absent — the HLD's "external nutrition data is DISCARDED — recalculated internally" rule applies.

---

## Mappers

MapStruct interfaces, `@Mapper(componentModel = "spring")`, one per entity-DTO pair: `DiscoveryJobMapper`, `DiscoverySourceMapper`, `DiscoveryScrapeLogMapper`. Each exposes `toDto` and `toDtos`. Default name-matching covers all fields; no custom `@Mapping` declarations required.

---

## Repositories

Package-private interfaces; cross-module access is via service interfaces only.

```java
interface DiscoveryJobRepository extends JpaRepository<DiscoveryJob, UUID> {
    Optional<DiscoveryJob> findByIdAndUserId(UUID id, UUID userId);
    Page<DiscoveryJob> findByUserIdOrderByQueuedAtDesc(UUID userId, Pageable pageable);
    List<DiscoveryJob> findByStatus(DiscoveryJobStatus status);

    // Watchdog: orphan running jobs whose started_at predates the heartbeat window.
    @Query("select j from DiscoveryJob j where j.status = 'RUNNING' and j.startedAt < :threshold")
    List<DiscoveryJob> findOrphanRunning(@Param("threshold") Instant threshold);
}

interface DiscoverySourceRepository extends JpaRepository<DiscoverySource, UUID> {
    Optional<DiscoverySource> findBySourceKey(String sourceKey);
    List<DiscoverySource> findByEnabledTrue();
    List<DiscoverySource> findBySourceKeyIn(Collection<String> sourceKeys);
}

interface DiscoveryScrapeLogRepository extends JpaRepository<DiscoveryScrapeLog, UUID> {
    Page<DiscoveryScrapeLog> findByJobIdOrderByOccurredAt(UUID jobId, Pageable pageable);
    List<DiscoveryScrapeLog> findByJobId(UUID jobId);
    boolean existsByContentFingerprint(String fingerprint);
}
```

---

## Service Interfaces

### `DiscoveryService` (public)

Both `DiscoveryService` and `DiscoveryQueryService` are implemented by one `DiscoveryServiceImpl` per the style guide.

```java
public interface DiscoveryService {
    // Enqueues; returns QUEUED immediately. Caller polls via DiscoveryQueryService.getJob(id).
    DiscoveryJobDto startJob(UUID userId, StartDiscoveryJobRequest request);

    // Cold-start only. Blocks until terminal or timeout (max recommended 60s).
    // On timeout returns the latest-known DTO; the job continues in the background.
    DiscoveryJobDto runJobSync(UUID userId, StartDiscoveryJobRequest request, Duration timeout);

    // Idempotent. Terminal jobs are no-ops.
    void cancelJob(UUID userId, UUID jobId);
}

public interface DiscoveryQueryService {
    Optional<DiscoveryJobDto> getJob(UUID jobId);
    Optional<DiscoveryJobDto> getJobForUser(UUID userId, UUID jobId);
    Page<DiscoveryJobDto> listJobsForUser(UUID userId, Pageable pageable);
    Page<DiscoveryScrapeLogEntryDto> getScrapeLog(UUID jobId, Pageable pageable);
    List<DiscoverySourceDto> listSources();
    Optional<DiscoverySourceDto> getSource(String sourceKey);
}
```

`DiscoveryService` is injected only by `planner` (cold-start, via `runJobSync`) and `recipe` (user-initiated and scheduled, via `startJob`). ArchUnit asserts the boundary.

### `DiscoverySource` — the SPI

Every concrete source (search engine, recipe site API, RSS feed, ...) implements this interface as a Spring `@Component`. The runner injects `List<DiscoverySource>` and dispatches by `key()`.

```java
public interface DiscoverySource {
    /** Stable key matching discovery_sources.source_key. */
    String key();
    DiscoverySourceKind kind();

    /**
     * Produce candidate URLs for the constraint set. Cheap — does not fetch full pages.
     * Implementations honour maxResults to bound per-source dominance.
     * Throws DiscoverySourceUnavailableException on permanent source-level failure.
     */
    List<DiscoveryCandidate> search(DiscoveryQuery query);

    /**
     * Fetch the full page and produce a structured ParsedRecipe. The runner invokes only
     * after robots.txt and rate-limit checks pass. The HTML extraction strategy
     * (microdata / JSON-LD / site templates / AI fallback) is the source's concern.
     * Throws ExtractionFailedException when extraction cannot produce a coherent recipe.
     */
    ParsedRecipe fetchRecipe(DiscoveryCandidate candidate);

    /** Robots.txt URI (empty for sources with their own API — runner skips robots check). */
    default Optional<URI> robotsTxtUri() { return Optional.empty(); }
}
```

Implementations live under `discovery/source/`. **Hard pocket** — see Out of Scope. The framework is locked here so future implementations slot in without touching the runner.

### Internal helper interfaces

```java
// Wraps the chosen library — the project's stable interface so the library can be swapped.
interface RobotsTxtGate {
    RobotsTxtOutcome check(URI candidateUrl, String userAgent);
}

// AI-backed second pass over candidates: filters by alignment with user preferences.
// The implementation, the prompt, the model tier, and the eval set are deferred.
interface CandidateAiFilter {
    List<DiscoveryCandidate> filter(
        List<DiscoveryCandidate> candidates,
        DiscoveryConstraints constraints,
        UUID userId
    );
}
```

`CandidateAiFilter` lives behind a Spring bean that, in v1, is a no-op pass-through (returns input unchanged). When the prompt and integration land, the bean is replaced — no caller change. The pass-through is intentional so the rest of the framework can be implemented and tested against the user's hard constraints alone, with the AI filter slotted in later.

---

## REST Controllers

All endpoints under `/api/v1/discovery/...`. `userId` from auth context. OpenAPI `@Tag(name = "Discovery")` on each controller.

| Controller | Method · Path | Body | Response | Status |
|---|---|---|---|---|
| `DiscoveryJobsController` | POST `/jobs` | `StartDiscoveryJobRequest` | `DiscoveryJobDto` (status `QUEUED`) | 202 / 400 |
|  | GET `/jobs?page=&size=` | — | `Page<DiscoveryJobDto>` | 200 |
|  | GET `/jobs/{jobId}` | — | `DiscoveryJobDto` | 200 / 404 |
|  | POST `/jobs/{jobId}/cancel` | — | `DiscoveryJobDto` | 200 / 404 / 422 |
|  | GET `/jobs/{jobId}/scrape-log?page=&size=` | — | `Page<DiscoveryScrapeLogEntryDto>` | 200 / 404 |
| `DiscoverySourcesController` | GET `/sources` | — | `List<DiscoverySourceDto>` | 200 |
|  | GET `/sources/{sourceKey}` | — | `DiscoverySourceDto` | 200 / 404 |
| `DiscoveryAdminController` (`/admin`) | POST `/jobs/sync` | `StartDiscoveryJobRequest` | `DiscoveryJobDto` (terminal) | 200 / 408 / 502 |
|  | POST `/sources/{sourceKey}/enable` · `/disable` | — | `DiscoverySourceDto` | 200 / 404 |
|  | POST `/run-orphan-sweep` | — | `{ resumedCount }` | 200 |

Async pattern per [technical-architecture.md §Async operations](../design/technical-architecture.md#async-operations): POST → `202` + `jobId`, poll GET. The sync admin variant is for the planner's cold-start only; 408 on deadline. The HLD does not specify a discovery REST surface — these endpoints are admin/debug-shaped (no end-user UI in v1). **Worth user review.**

### Error responses

RFC 9457 `ProblemDetail`. Module root: `DiscoveryException extends MealPrepException`. Each `type` URI is `https://mealprep.example.com/problems/<kebab-case-name>`.

| Exception | Status |
|---|---|
| `DiscoveryJobNotFoundException`, `DiscoverySourceNotFoundException` | 404 |
| `DiscoveryJobAlreadyTerminalException`, `DiscoveryConstraintInvalidException` | 422 |
| `DiscoveryAllSourcesUnavailableException` | 502 |
| `DiscoveryJobTimeoutException` | 408 |
| `OptimisticLockException` (JPA) | 409 |
| `MethodArgumentNotValidException` | 400 (`errors[]` extension) |

`DiscoverySourceUnavailableException` and `ExtractionFailedException` are **internal** signals thrown by source implementations; the runner catches them, writes a scrape-log row, and continues. They never reach the global handler.

---

## Validation

Standard Jakarta annotations on request records: `@NotNull`, `@NotBlank`, `@Size`, `@Min`, `@Max`, `@Valid`. Custom validator in `validation/`:

- **`@ValidDiscoveryConstraints`** (class-level) — asserts `schemaVersion` is supported, `requiredMealTypes` entries are members of the canonical meal-type set, no negative time bounds, `maxRecipesPerSource <= requestedCount` (a per-source budget exceeding the total is nonsensical), and `mustExcludeIngredientMappingKeys` entries are pre-normalised mapping keys (lowercase, no leading/trailing whitespace).

Service-layer validation in `DiscoveryServiceImpl` (not Jakarta — depends on DB state):
- `startJob` rejects if zero enabled sources match the requested subset (422, `DiscoveryConstraintInvalidException`).
- `cancelJob` throws `DiscoveryJobAlreadyTerminalException` for jobs in `SUCCEEDED`, `FAILED`, `PARTIAL`.
- `runJobSync` rejects when called for triggers other than `COLD_START` (defence in depth).

---

## Events

### Published

```java
public record DiscoveryJobStartedEvent(
    UUID jobId, UUID userId, DiscoveryJobTrigger trigger,
    int requestedCount, List<String> sourcesRequested,
    UUID traceId, Instant occurredAt
) {}

public record DiscoveryJobCompletedEvent(
    UUID jobId, UUID userId, DiscoveryJobStatus terminalStatus,
    int recipesIngested, int candidatesSeen,
    List<String> sourcesSucceeded, List<String> sourcesFailed,
    String errorSummary,
    UUID traceId, Instant occurredAt
) {}

public record DiscoveryRecipeIngestedEvent(
    UUID jobId, UUID userId, UUID recipeId,
    String sourceKey, String canonicalUrl,
    BigDecimal extractionConfidence,
    UUID traceId, Instant occurredAt
) {}
```

`DiscoveryRecipeIngestedEvent` fires per successful ingest, alongside `RecipeCreatedEvent` from the recipe module. The split is deliberate: discovery-journey subscribers (notifications, admin telemetry) listen to the discovery event with its source provenance and trace-id; new-recipe subscribers listen to `RecipeCreatedEvent`. **Worth user review.**

All published via `ApplicationEventPublisher` after the write transaction; listeners use `@TransactionalEventListener(phase = AFTER_COMMIT)`.

### Consumed

Nothing in v1 — cold-start is a direct service call. **Worth user review:** a `PreferenceChangedEvent` listener could schedule trending-preferences sweeps (per [preference-model.md §By Recipe Discovery](../design/preference-model.md#by-recipe-discovery)) — not in v1.

---

## Business Logic Flows

### Flow 1: Job enqueue (async path)

`POST /api/v1/discovery/jobs` → `startJob`. `@Transactional`. Validate request → resolve sources (null `sourceKeys` → all enabled; named subset rejected 422 if any unknown or disabled) → generate `traceId` (or use the supplied one) → persist with `status = QUEUED` → publish `DiscoveryJobStartedEvent` after commit. The `@Async` `DiscoveryJobRunner.run(jobId)` listens on `DiscoveryJobStartedEvent` and picks the job up.

### Flow 2: Job runner state machine

`DiscoveryJobRunner.run(jobId)`. Each step is its own short transaction so a partial run survives a crash.

1. **Claim:** transition `QUEUED → RUNNING`, set `startedAt`. Optimistic-version mismatch → another runner won; return.
2. **Search phase** — per requested source, parallel up to a small pool: acquire rate-limit token (Resilience4j `@RateLimiter` keyed by `source_key`); call `source.search(query)`; collect candidates capped at `constraints.maxRecipesPerSource`. `DiscoverySourceUnavailableException` → add to `sourcesFailed`, summary scrape-log row, continue. Token starvation → `RATE_LIMITED` row, skip.
3. **AI filter** — pass merged candidates through `CandidateAiFilter.filter(...)`. v1 is pass-through. `AiUnavailableException` is non-fatal (skip-and-flag): log warning, proceed unfiltered.
4. **Fetch phase** — per surviving candidate, sequential within a source, parallel across sources:
    - Robots.txt gate: `DISALLOWED` → `ROBOTS_DISALLOWED` skip. `UNAVAILABLE` with `respectRobotsTxt = true` → `SKIPPED`. Source with no robots URI → `SKIPPED`, proceed.
    - Rate-limit token; starvation → `RATE_LIMITED` skip.
    - `source.fetchRecipe(candidate)`. `ExtractionFailedException` or `extractionConfidence < 0.5` → `EXTRACTION_FAILED` / `LOW_CONFIDENCE` skip.
    - **Hard-constraint filter** — match against `constraints.mustExcludeIngredientMappingKeys` → `HARD_CONSTRAINT_VIOLATION` skip. The deterministic safety net; never trusts the AI filter or the source.
    - **Content fingerprint** — SHA-256 over normalised body (sorted ingredient mapping keys + concatenated method instructions, lowercased, whitespace-collapsed). Already in scrape log within the lookback window → `DUPLICATE` skip.
    - **Persist** — call `RecipeWriteApi.saveImportedRecipe(...)` with `catalogue = SYSTEM`, `dataQuality = WEB_DISCOVERED`, `sourceType = WEB_DISCOVERED`, the `ParsedRecipe`, and `traceId`. The discovery module **never** writes to the user catalogue — `SYSTEM` is passed explicitly.
    - Log `SUCCESS` row with `recipe_id` and confidence; publish `DiscoveryRecipeIngestedEvent` after commit.
    - Quota check — `recipesIngested == requestedCount` → stop fetching; remaining candidates skip with `JOB_QUOTA_REACHED`.
5. **Finalise** — terminal state:
    - All requested sources succeeded and quota met → `SUCCEEDED`.
    - Some succeeded, some failed → `PARTIAL`. `errorSummary` lists failed sources and last error class.
    - All sources failed → `FAILED`.
6. Publish `DiscoveryJobCompletedEvent` after commit.

Idempotent at the job level: re-entry on the same `jobId` short-circuits at step 1.

### Flow 3: Synchronous cold-start invocation

`runJobSync(userId, request, timeout)` — planner cold-start only. Enqueues per Flow 1, then blocks on a per-job `CompletableFuture` keyed by `jobId`; the runner completes it on terminal transition. Deadline expiry returns the latest-known DTO (status may still be `RUNNING`) — job continues in background, planner falls back per [meal-planner.md §Cold start](../design/meal-planner.md#cold-start). Recommended max timeout 60s. **Worth user review.**

### Flow 4: Cancellation

`cancelJob(userId, jobId)`. `@Transactional`. `QUEUED` → `FAILED` atomically. `RUNNING` → sets an in-memory `cancellation_requested` flag; the runner's per-candidate loop checks between iterations and finalises early. Already-ingested recipes are kept — they are valid system-catalogue entries.

### Flow 5: Orphan sweep

`@Scheduled` 5-minute cadence. `findOrphanRunning(now - heartbeatTimeout)` (default 10 min) → transition to `FAILED` with `errorSummary = "runner crashed; resumed by sweep"`. Manual trigger via `POST /admin/run-orphan-sweep`.

---

## Concurrency and Transactions

| Concern | Decision |
|---|---|
| `@Transactional` placement | All service-impl methods. Repositories never. Read methods `readOnly = true`. |
| Runner method propagation | The per-step transitions in `DiscoveryJobRunner` are top-level (default REQUIRED). The runner deliberately does not run the whole job in one transaction — each candidate is a fresh tx so a partial run survives a crash. |
| `RecipeWriteApi` calls from the runner | Top-level transactions in the recipe module (per the recipe LLD). The runner does not pass a transaction to it. |
| Optimistic locking | `@Version` on `DiscoveryJob` and `DiscoverySource`. Append-only `DiscoveryScrapeLog` has no `@Version`. The runner uses optimistic locking to detect concurrent claim attempts on the same job. |
| Pessimistic locking | None. |
| Rate limiting | Resilience4j `@RateLimiter` keyed by `source_key`, configured at runtime from `discovery_sources.requests_per_minute`. Configuration changes apply on next runner instantiation; in-flight tokens not redistributed. |
| Per-source circuit breaker | `failure_streak >= 5` in `discovery_sources` → source skipped for the next hour even if `enabled = true`. Bookkeeping updated by the runner; reset on a successful call. |
| Async pool | `DiscoveryAsyncConfig` declares a `discoveryRunnerExecutor` `ThreadPoolTaskExecutor` with bounded queue (default core=2, max=4, queue=8) — the runner is I/O bound and small parallelism is plenty. Rejected jobs surface as the queue's `RejectedExecutionException` and re-queue the job after a backoff. |
| `@Async` event handler | `DiscoveryJobRunner.run` is annotated `@Async("discoveryRunnerExecutor") @TransactionalEventListener(phase = AFTER_COMMIT)`. Listens to `DiscoveryJobStartedEvent`. |

---

## Failure Modes

Per the style guide's AI degradation contract: discovery's per-feature behaviour on `AiUnavailable` is **skip-and-flag** for the AI candidate filter (proceed with unfiltered candidates, log a warning) and irrelevant for the AI extraction step (handled inside the source implementation — the runner sees only `ExtractionFailedException`).

| Failure | Response |
|---|---|
| Single source down (`DiscoverySourceUnavailableException`) | Source added to `sourcesFailed`; job continues. Terminal status `PARTIAL` if any other source succeeded. `failure_streak` increments; circuit breaker trips at 5 consecutive. |
| All sources down | `FAILED` with cross-source `errorSummary`. Surfaces as 502 only on the sync admin endpoint. |
| Robots.txt unavailable (5xx / timeout) | `robotsTxtOutcome = UNAVAILABLE`, default polite-by-default skip. **Worth user review** — alternative is to proceed when source's `respectRobotsTxt = false`. |
| Rate-limit starvation | `RATE_LIMITED` scrape row; no retry within the same job. |
| Extraction garbage / low confidence | `EXTRACTION_FAILED` / `LOW_CONFIDENCE` skip. Recipe not written — discovery's bar is higher than URL-import's because no user is in the loop to correct. |
| Duplicate fingerprint | `DUPLICATE` skip. Lookback configurable via `DiscoveryProperties.duplicateLookbackDays`. **Worth user review:** the recipe module also does its own ingredient-set dedup; discovery's content-fingerprint is a cheaper pre-check for republished identical pages. Both are kept. |
| Hard-constraint violation in extracted ingredients | `HARD_CONSTRAINT_VIOLATION` skip. Discovery's stricter policy than user-initiated URL import — autonomous fetch raises the safety bar. |
| AI candidate filter unavailable | Skip-and-flag: unfiltered candidates proceed, `candidatesAfterFilter = candidatesSeen`. |
| `RecipeWriteApi` write fails | `EXTRACTION_FAILED` row carrying the recipe-module error class. Job continues. |
| Runner crash mid-job | Orphan sweep finalises as `FAILED`. Already-ingested recipes remain valid. |
| Cold-start sync timeout | Job continues; planner returns partial result and degrades per [meal-planner.md §Cold start](../design/meal-planner.md#cold-start). |

---

## Test Plan

Unit tests use `@ExtendWith(MockitoExtension.class)`. Integration tests are `*IT.java` with Testcontainers Postgres and WireMock for source-implementation testing. Naming: `methodName_scenario_expected`.

### Unit

| Class | Verifies |
|---|---|
| `DiscoveryServiceImplTest` | `startJob` persists QUEUED + publishes `DiscoveryJobStartedEvent`; unknown source keys 422; `cancelJob` idempotent for terminal states; `runJobSync` rejects non-COLD_START triggers. |
| `DiscoveryJobRunnerTest` | Claim mutex via optimistic version; one source failing → `PARTIAL`, all failing → `FAILED`; robots-disallowed candidates produce `SKIPPED` rows and never reach the recipe module; hard-constraint filter rejects pre-write; quota terminates fetches; cancellation short-circuits between candidates. |
| `RobotsTxtGateTest` | `ALLOWED` / `DISALLOWED` / `UNAVAILABLE` outcomes; per-host caching within a job. |
| `ContentFingerprintHasherTest` | Identical content yields equal fingerprints regardless of HTML formatting; stable under ingredient reordering. |
| `CandidateAiFilterPassThroughTest` | v1 returns input unchanged; logs a warning. |
| `DiscoveryConstraintValidatorTest` | Custom validator rejects negative time, unknown meal types, per-source budget exceeding total. |
| `DiscoveryJobMapperTest`, `DiscoverySourceMapperTest`, `DiscoveryScrapeLogMapperTest` | MapStruct round-trips preserve all fields including `text[]` and JSONB. |

### Integration

| Class | Verifies |
|---|---|
| `DiscoveryJobsControllerIT` | POST 202 with QUEUED dto; GET reaches terminal state after async runner; cancel transitions atomically; `DiscoveryJobStartedEvent` published exactly once after commit. |
| `DiscoveryAdminControllerIT` | Source enable/disable affects the next job; sync admin endpoint returns terminal or 408; orphan sweep finalises stale rows. |
| `DiscoveryRunnerIT` | Two stub `DiscoverySource` beans (always-success + always-fail): `PARTIAL` terminal, one scrape row per candidate per source, ingested recipes appear in `system` catalogue with `WEB_DISCOVERED`. Re-run dedup short-circuit verified. |
| `DiscoveryHardConstraintIT` | A peanut-allergy user does not see peanut-containing discovered recipes ingested. Real `HardConstraintFilterService` against Testcontainers Postgres. |
| `DiscoverySourceWireMockIT` | A test-scoped reference `DiscoverySource` backed by WireMock fixtures: robots 404 → `UNAVAILABLE`; 200 with `Disallow: /` → `DISALLOWED`; Resilience4j rate-limit produces `RATE_LIMITED` rows. |
| `DiscoveryRecipeWriteIT` | Successful ingest writes via `RecipeWriteApi.saveImportedRecipe` with correct catalogue and quality; both `DiscoveryRecipeIngestedEvent` and `RecipeCreatedEvent` fire after commit. |
| `FlywayMigrationIT` | Postgres boots, all migrations run, schema matches JPA mapping (`ddl-auto=validate`). |
| `EventPublicationIT` | All three discovery events publish only after commit; failing listener does not roll back state. |
| `ModuleBoundaryArchTest` (ArchUnit) | Nothing outside `discovery.*` imports `discovery.domain.repository.*` or `discovery.domain.entity.*`; `DiscoverySource` implemented only inside `discovery.source.*`; `DiscoveryService` injected only by `planner.*` and `recipe.*`. |

---

## Out of Scope

Hard pockets — deferred deliberately.

- **The actual list of search engines and recipe sources.** One row in `discovery_sources` + one Spring bean per source. Framework here is source-agnostic. **User-decided.**
- **HTML extraction templates per source.** Microdata, JSON-LD, site selectors, AI-fallback choreography — inside each `DiscoverySource.fetchRecipe`. Separate engineering exercise.
- **AI prompt for filtering candidates against user preferences.** `CandidateAiFilter` SPI is in place; v1 is pass-through. Prompt design (system message, structured output, eval cases, cost budget) is separate prompt-engineering work — see [README.md §LLM prompts](README.md#llm-prompts-to-design-9-distinct-prompt-engineering-exercises).
- **AI extraction call for parsing arbitrary recipe HTML.** Lives inside `DiscoverySource.fetchRecipe`, not in the runner. May share the recipe module's URL-import extraction prompt (also deferred — per [recipe.md §Flow 2](recipe.md#flow-2-url-import--data-path)) or be source-specific.
- **Robots.txt parser library choice.** `RobotsTxtGate` interface is the project's stable surface. Recommendation: Crawler-Commons (Java, mature, longest-match `User-agent`/`Allow`/`Disallow` semantics) — not locked. **Worth user review.**
- **Frontend / UI concerns.** "Find me more recipes" button, admin job view, scrape-log explorer — Figma phase.
- **Cold-start AI generation.** Cold-start is *discovery + generation* per [meal-planner.md §Cold start](../design/meal-planner.md#cold-start); generation is the recipe module's adaptation pipeline. The planner orchestrates both.
- **Notification copy** — owned by the notification module.
- **Per-source trust ranking.** v1 treats enabled sources equally. Schema leaves room (`notes` column + a future `trust_score`).
- **Trending-preferences-driven scheduled discovery.** A `PreferenceChangedEvent` listener that synthesises constraints from trending items (per [preference-model.md §By Recipe Discovery](../design/preference-model.md#by-recipe-discovery)) — natural v2.
- **Cross-user catalogue sharing.** v1 system catalogue is per-user per [README.md §Tier 1](README.md#tier-1-architectural-decisions-locked).
