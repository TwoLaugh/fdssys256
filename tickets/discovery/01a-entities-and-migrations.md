# Ticket: discovery — 01a Discovery Entities + Migrations + Repos + Module Facade

## Summary

Foundation slice for the `discovery` module. Per [`lld/discovery.md`](../../lld/discovery.md) §Package Layout, §Database (V20260615120000 / 120100 / 120200), §Entities, §Repositories, §Error responses (the module-root exception only). Ships the three aggregates (`DiscoverySource`, `DiscoveryJob`, `DiscoveryScrapeLog`), their Flyway migrations + empty repeatable seed, JPA mapping, package-private Spring Data repositories, module-local enums, the `DiscoveryModule` facade re-exporting the (empty-in-01a) public service interfaces, the module-root `DiscoveryException extends MealPrepException`, and the `DiscoveryBoundaryTest` ArchUnit rule.

**No service impl, no controller, no runner, no source impls in 01a.** This ticket is the bare aggregate + plumbing so 01b..01f can layer behaviour on top without touching schema or repository shape. The first round-trip caller in 01b is the empty-interface re-export from `DiscoveryModule`; that lands so cross-module imports compile from day one.

This is the **first discovery ticket**. Module package is currently empty.

**Defers** to later tickets:
- `DiscoveryService` / `DiscoveryQueryService` impl methods + REST controllers (`DiscoveryJobsController`, `DiscoverySourcesController`, `DiscoveryAdminController`) → **discovery-01b**
- `DiscoverySource` SPI + `SourceRegistry` + `RobotsTxtGate` + `RateLimiterRegistry` + `ContentFingerprintHasher` + `CandidateAiFilter` no-op → **discovery-01c**
- `DiscoveryJobRunner` async lifecycle + state machine + orphan sweep + per-step transactions + `RecipeWriteApi.saveImportedRecipe` handoff + cancellation + `DiscoveryRecipeIngestedEvent` + `DiscoveryJobCompletedEvent` → **discovery-01d**
- Curated source seed (`R__discovery_seed_curated_sources.sql` with ~25-30 INSERT rows) + Google Custom Search adapter + reference `DiscoverySource` stub → **discovery-01e**
- Sync admin endpoint (`runJobSync` + `CompletableFuture` coordination + 408 `DiscoveryJobTimeoutException` + 502 `DiscoveryAllSourcesUnavailableException`) → **discovery-01f**

The 01a interfaces (`DiscoveryService`, `DiscoveryQueryService`) ship **empty-bodied** today so the facade compiles. 01b appends methods. Empty interfaces are not a code-smell here — the alternative is to leave the facade un-exported and create churn later.

## Behavioural spec

### V20260615120000 — `discovery_sources`

1. New table `discovery_sources` per [LLD lines 71-100](../../lld/discovery.md) verbatim. Fields: `id uuid PK, source_key varchar(64) UNIQUE, display_name varchar(120), source_type varchar(16), kind varchar(32), base_url varchar(255), enabled boolean default true, user_disabled boolean default false, requests_per_minute integer default 6, requests_per_day integer default 500, respect_robots_txt boolean default true, user_agent varchar(160), crawl_config jsonb, failure_streak integer default 0, last_failure_at timestamptz, last_success_at timestamptz, last_used_at timestamptz, quality_score numeric(4,3), notes text, optimistic_version bigint default 0, created_at timestamptz, updated_at timestamptz`. Indexes: `idx_discovery_sources_enabled` partial WHERE `enabled = true`, `idx_discovery_sources_kind` on `kind`.
2. **`source_type` column width 16**: longest value is `web_discovered` from the recipe LLD's `data_quality` set — but discovery's set is `{CURATED, SEARCH}` per [LLD line 76](../../lld/discovery.md), longest is `SEARCH` (6 chars). 16 is the LLD width; keep. **Computed**.
3. **`kind` width 32**: longest value `search_api`, `category_index`, `rss_feed`, `sitemap` per [LLD line 77](../../lld/discovery.md) — longest is `category_index` (14 chars). LLD 32 is plenty; keep.
4. **`user_agent` width 160**: per LLD line 84; keep verbatim (long enough for `MealPrepAI/1.0 (+https://...)` with custom UA strings).

### V20260615120100 — `discovery_jobs`

5. New table `discovery_jobs` per [LLD lines 108-138](../../lld/discovery.md) verbatim. Fields: `id uuid PK, user_id uuid, trigger varchar(32), requested_count integer, constraints_json jsonb, sources_requested text[], status varchar(16) default 'queued', queued_at timestamptz, started_at timestamptz, completed_at timestamptz, candidates_seen integer default 0, candidates_after_filter integer default 0, recipes_ingested integer default 0, recipes_skipped_duplicate integer default 0, sources_succeeded text[] default '{}', sources_failed text[] default '{}', error_summary text, trace_id uuid, optimistic_version bigint default 0, created_at timestamptz, updated_at timestamptz`. Indexes: `idx_discovery_jobs_user_status (user_id, status, queued_at DESC)`, `idx_discovery_jobs_status_started (status, started_at) WHERE status = 'running'`, `idx_discovery_jobs_trace (trace_id)`.
6. **`trigger` width 32**: longest value `user_initiated` (14 chars); 32 is the LLD width.
7. **`status` width 16**: longest value `succeeded` (9 chars); keep 16.
8. **`text[]` columns** (`sources_requested`, `sources_succeeded`, `sources_failed`) stay as native Postgres `text[]` — this differs from the recipe / preference / nutrition modules' `jsonb List<String>` workaround. The discovery module's text[] columns are short, never queried by inner element, and written only at job-completion time (the recipe module's were hot-path queried). **LLD divergence acknowledged** — see gotcha block: **Hibernate's `text[]` mapping is brittle on Spring Boot 3.2.5**. The fix is `@Type(StringArrayType.class)` from `hypersistence-utils-hibernate-63`. The dependency is already in the pom (see [style-guide.md §Quick Reference](../../lld/style-guide.md)).
9. **If `StringArrayType` fails the FlywayMigrationIT round-trip** during the agent's verify loop, fall back to `jsonb` (default `'[]'::jsonb` in SQL, `@Type(JsonBinaryType.class)` with `List<String>` in Java) — the columns are write-once-at-completion so the column type is a private implementation detail. The agent should try `StringArrayType` first (matches LLD line 194), fall back to JSONB on iteration 2 if the round-trip fails, and note the choice in the report. **Worth user review.**

### V20260615120200 — `discovery_scrape_log`

10. New table `discovery_scrape_log` per [LLD lines 148-179](../../lld/discovery.md) verbatim — append-only audit. Fields: `id uuid PK, job_id uuid NOT NULL REFERENCES discovery_jobs(id) ON DELETE CASCADE, source_key varchar(64), candidate_url varchar(2048), canonical_url varchar(2048), status varchar(24), http_status_code integer, robots_txt_outcome varchar(16), latency_ms integer, content_fingerprint varchar(64), extraction_method varchar(32), extraction_confidence numeric(4,3), recipe_id uuid, skip_reason varchar(64), error_class varchar(64), error_message text, occurred_at timestamptz NOT NULL`. Indexes per LLD lines 168-178: `idx_discovery_scrape_log_job (job_id, occurred_at)`, `idx_discovery_scrape_log_source_time (source_key, occurred_at DESC)`, `idx_discovery_scrape_log_fingerprint (content_fingerprint) WHERE content_fingerprint IS NOT NULL`, `idx_discovery_scrape_log_robots (robots_txt_outcome, occurred_at DESC) WHERE robots_txt_outcome IN ('disallowed', 'unavailable')`, `idx_discovery_scrape_log_recipe (recipe_id) WHERE recipe_id IS NOT NULL`.
11. `recipe_id` is a **soft FK** — no `REFERENCES recipe_recipes(id)`. Per [LLD line 181](../../lld/discovery.md): "discovery must not pull the recipe module's tables into its Flyway path." If a recipe is hard-deleted the row becomes a tombstone.
12. **`status` width 24**: longest enum value `HARD_CONSTRAINT_VIOLATION` (25 chars) — **off by one vs LLD's 24**. Widen to `varchar(32)`. **LLD divergence noted** — agent should compute width from the longest enum value (per the gotcha "don't trust LLD column widths blindly").
13. **`skip_reason` width 64**: longest enum value `HARD_CONSTRAINT` (15 chars); 64 is plenty.
14. **`content_fingerprint` width 64**: SHA-256 hex is 64 chars. Exact fit.

### R__discovery_seed_source_registry.sql

15. New repeatable migration `R__discovery_seed_source_registry.sql` — **empty body** with a comment header explaining that 01a ships the file as a placeholder; 01e populates the ~25-30 curated source inserts. **Empty file is a deliberate scaffold** so 01e's reviewer sees the diff against the empty seed, not the empty seed itself.

### Entities

16. `DiscoverySource` JPA entity per [LLD §Entities](../../lld/discovery.md) and verbatim shape in this ticket's snippet block. Lombok `@Getter @Setter @Builder @NoArgsConstructor(access = PROTECTED) @AllArgsConstructor`. `@Id UUID` (application-set), `@Version long optimisticVersion`, `@CreationTimestamp` / `@UpdateTimestamp`. **JSONB column** `crawl_config` mapped via `@Type(JsonBinaryType.class)` with `JsonNode` — opaque payload per LLD (sitemap URL / RSS URL / search engine ID). Enums (`DiscoverySourceKind`, `DiscoverySourceType`) `@Enumerated(EnumType.STRING)`. Mutable fields per LLD line 193: `enabled`, `failureStreak`, `lastFailureAt`, `lastSuccessAt`, `lastUsedAt`, `qualityScore`, `userDisabled`. **No `@OneToMany`** to jobs or scrape rows — those are queried separately.
17. `DiscoveryJob` JPA entity. **JSONB column** `constraints_json` mapped via `@Type(JsonBinaryType.class)` with `DiscoveryConstraints` (a record — see DTOs below). `sources_requested`, `sources_succeeded`, `sources_failed` mapped via `@Type(StringArrayType.class)` (or `JsonBinaryType` with `List<String>` if fallback per invariant 9). Enums `DiscoveryJobTrigger`, `DiscoveryJobStatus` `@Enumerated(EnumType.STRING)`. `@Version long optimisticVersion`. `traceId UUID NOT NULL`. **No `@OneToMany`** to `DiscoveryScrapeLog` — joined only by service queries.
18. `DiscoveryScrapeLog` JPA entity — **append-only**, **NO `@Version`**, **NO `@LastModifiedDate`** per [LLD line 195](../../lld/discovery.md). `@CreationTimestamp` on `occurredAt` OR application-set; pick application-set so the job runner can record per-fetch wall-clock time precisely. Enums `ScrapeOutcome`, `RobotsTxtOutcome`, `ScrapeSkipReason` `@Enumerated(EnumType.STRING)`. `recipeId` is a plain `UUID` field (no `@ManyToOne` to `Recipe` per the soft-FK rule).

### Enums (local to module)

19. New enums in `discovery.domain.entity.*`:
    - `DiscoverySourceKind` — values: `SITEMAP, RSS_FEED, CATEGORY_INDEX, SEARCH_API`. **LLD line 197 declares `SEARCH_ENGINE, RECIPE_SITE_API, RSS_FEED`** — but LLD lines 76-77 of the DB section (more recent/locked) declares `sitemap | rss_feed | category_index | search_api`. **Use the DB-section list** — it's the locked 2026-05-07 set. The entity-section list is older. **LLD divergence noted.**
    - `DiscoverySourceType` — `CURATED, SEARCH`. Per locked 2026-05-07 line 76.
    - `DiscoveryJobStatus` — `QUEUED, RUNNING, SUCCEEDED, FAILED, PARTIAL`. Verbatim LLD line 197.
    - `DiscoveryJobTrigger` — `COLD_START, USER_INITIATED, SCHEDULED`. Verbatim LLD line 197.
    - `ScrapeOutcome` — `SUCCESS, SKIPPED, RATE_LIMITED, ROBOTS_DISALLOWED, HTTP_ERROR, EXTRACTION_FAILED, DUPLICATE, HARD_CONSTRAINT_VIOLATION`. Verbatim.
    - `RobotsTxtOutcome` — `ALLOWED, DISALLOWED, UNAVAILABLE, SKIPPED`. Verbatim.
    - `ScrapeSkipReason` — `DUPLICATE, HARD_CONSTRAINT, LOW_CONFIDENCE, RATE_LIMITED, ROBOTS_DISALLOWED, JOB_QUOTA_REACHED, AI_FILTER_REJECTED`. Verbatim.

Enums are stored as `EnumType.STRING` so the SQL column values match the lowercase forms in the DB section (e.g. `'queued'`, `'running'`). **Important**: Hibernate's default `EnumType.STRING` writes the UPPERCASE Java name. The DB section says `status varchar(16) DEFAULT 'queued'`. **Fix at migration time**: lowercase the DEFAULT in SQL (`DEFAULT 'QUEUED'` not `'queued'`). The DB CHECK constraints (none ship in 01a) and the runner code use Java enum values; the column carries UPPERCASE strings. The LLD's lowercase examples are illustrative, not normative. **Worth user review.**

### DTOs

20. `DiscoverySourceDto` record per [LLD lines 206-213](../../lld/discovery.md) verbatim.
21. `DiscoveryJobDto` record per [LLD lines 215-225](../../lld/discovery.md) verbatim.
22. `DiscoveryScrapeLogEntryDto` record per [LLD lines 227-236](../../lld/discovery.md) verbatim.
23. `DiscoveryConstraints` record per [LLD lines 244-254](../../lld/discovery.md) verbatim. **Schema-versioned JSONB document** — field `int schemaVersion` is invariant 1; bumped when shape changes non-additively. **Round-trip test** in `DiscoveryConstraintsRoundTripTest` (unit) — serialise → JSONB string → deserialise; assert equality. Per [style-guide.md §JSONB §Required discipline](../../lld/style-guide.md).
24. `StartDiscoveryJobRequest` per [LLD lines 261-267](../../lld/discovery.md). Validation annotations `@NotNull DiscoveryJobTrigger trigger, @Min(1) @Max(50) int requestedCount, @NotNull @Valid @ValidDiscoveryConstraints DiscoveryConstraints constraints, @Nullable List<@NotBlank String> sourceKeys, @Nullable UUID traceId`. **`@ValidDiscoveryConstraints` validator is deferred to discovery-01b** — 01a ships the request DTO declaring the annotation; 01b ships the annotation interface + validator. **For 01a, the annotation does not exist yet — drop it from the request record's signature** (keep `@Valid` only). 01b re-introduces it. This is one-line churn in 01b.
25. **Source-internal types** (`DiscoveryQuery`, `DiscoveryCandidate`, `ParsedRecipe`) per [LLD lines 275-296](../../lld/discovery.md) — **deferred to discovery-01c** alongside the `DiscoverySource` SPI that consumes them. 01a does not need them.

### Mappers

26. `DiscoveryJobMapper`, `DiscoverySourceMapper`, `DiscoveryScrapeLogMapper` — MapStruct interfaces, `@Mapper(componentModel = "spring")`, one per entity-DTO pair. Each exposes `toDto(Entity)` and `toDtos(List<Entity>)`. Default name-matching covers all fields except:
    - `DiscoveryJobMapper.toDto`: `sources_requested / _succeeded / _failed` from `String[]` to `List<String>` — MapStruct handles arrays-to-list automatically.
    - `DiscoveryJobMapper.toDto`: `constraints` from `JsonNode` (entity field) to `DiscoveryConstraints` (DTO field) — **custom @Mapping** via a default method `defaultConstraints(JsonNode)` that uses Jackson's `ObjectMapper` to `treeToValue`. The mapper interface declares `@AfterMapping default void mapConstraints(DiscoveryJob src, @MappingTarget DiscoveryJobDto.Builder b) { ... }` — see snippet block.
27. **All three mappers compile cleanly with `mvn compile`** — verify via the agent's verify loop.

### Repositories (package-private)

28. `DiscoveryJobRepository`, `DiscoverySourceRepository`, `DiscoveryScrapeLogRepository` per [LLD lines 314-334](../../lld/discovery.md). All `extends JpaRepository<Entity, UUID>`. Methods declared in 01a (called by 01b+ services):
    - `DiscoveryJobRepository`: `findByIdAndUserId(UUID id, UUID userId)`, `findByUserIdOrderByQueuedAtDesc(UUID userId, Pageable)`, `findByStatus(DiscoveryJobStatus)`, `findOrphanRunning(Instant threshold)` (via `@Query`).
    - `DiscoverySourceRepository`: `findBySourceKey(String)`, `findByEnabledTrue()`, `findBySourceKeyIn(Collection<String>)`.
    - `DiscoveryScrapeLogRepository`: `findByJobIdOrderByOccurredAt(UUID jobId, Pageable)`, `findByJobId(UUID jobId)`, `existsByContentFingerprint(String fingerprint)`.
29. Repository interfaces are **package-private** (no `public` modifier on the interface declaration). Spring Data still proxies them. The `DiscoveryBoundaryTest` (ArchUnit) asserts no class outside `discovery..` imports `discovery.domain.repository..`.

### Service interfaces (empty-bodied in 01a)

30. `DiscoveryService` interface in `discovery.domain.service.DiscoveryService` — **public** (cross-module facade). 01a body: empty. Method signatures land in 01b. Javadoc one-liner per [LLD line 346](../../lld/discovery.md).
31. `DiscoveryQueryService` interface in `discovery.domain.service.DiscoveryQueryService` — **public**. 01a body: empty. Method signatures land in 01b.
32. **Why empty bodies in 01a, not waiting until 01b?** The `DiscoveryModule` facade re-exports both. Other modules (planner, recipe) need to `import com.example.mealprep.discovery.DiscoveryModule` from day one — if the interfaces don't exist, planner-01a can't compile against the predicted dependency. Empty is fine; appending methods in 01b is one-line churn.

### Module facade

33. `DiscoveryModule.java` at `discovery/DiscoveryModule.java` per the style-guide's facade pattern. Re-exports `DiscoveryService`, `DiscoveryQueryService` as bean references via getters. **No `@Bean` declarations** — just a `@Component` that holds the injected interfaces and exposes them.
34. Cross-module consumers (planner, recipe) inject `DiscoveryModule` → call `discoveryModule.discoveryService().startJob(...)`. Saves them needing to know the package structure.

### Module exception root

35. `DiscoveryException extends MealPrepException` (the project-wide root from `core`). No subclasses in 01a — those land with the controllers in 01b (`DiscoveryJobNotFoundException`, `DiscoverySourceNotFoundException`, etc.).
36. **`DiscoveryExceptionHandler` is deferred to discovery-01b** — 01a has no controller surface, so no advice is needed yet. The 01a exception root just compiles; nothing throws it.

### Boundary test

37. New `DiscoveryBoundaryTest` at `src/test/java/com/example/mealprep/discovery/DiscoveryBoundaryTest.java`. ArchUnit rules:
    - Classes outside `com.example.mealprep.discovery..` must NOT depend on `com.example.mealprep.discovery.domain.repository..` (the repository package-privacy backstop).
    - Classes outside `com.example.mealprep.discovery..` must NOT depend on `com.example.mealprep.discovery.domain.entity..` (entities never cross boundaries).
38. **The "DiscoverySource only inside `discovery.source.*`" rule is deferred to discovery-01c** — 01a doesn't ship the SPI.
39. **The "DiscoveryService injected only by `planner.*` and `recipe.*`" rule is deferred to discovery-01b** when the interface gains methods.

### Async config skeleton

40. `DiscoveryAsyncConfig` at `discovery/config/DiscoveryAsyncConfig.java` per [LLD line 566](../../lld/discovery.md). **Ship the bean stub in 01a** so 01d's `@Async("discoveryRunnerExecutor")` annotation resolves the bean from day one. Bean: `ThreadPoolTaskExecutor` named `discoveryRunnerExecutor` with `corePoolSize=2, maxPoolSize=4, queueCapacity=8, threadNamePrefix="discovery-runner-"`. **No `@EnableAsync` here** — that's a project-wide enable on the main config (verify it's already on; if not, defer to 01d).
41. `DiscoveryProperties` at `discovery/config/DiscoveryProperties.java` — `@ConfigurationProperties(prefix = "mealprep.discovery") @Validated`. Properties:
    - `Duration heartbeatTimeout` (default `PT10M`) — used by the orphan sweep in 01d.
    - `int duplicateLookbackDays` (default `30`) — fingerprint-dedup lookback in 01d.
    - `Duration syncTimeout` (default `PT60S`) — sync runJobSync cap in 01f.
    - `Duration robotsCacheTtl` (default `PT1H`) — per-host cache in 01c.
    Properties ship as record (Java 17). `@Validated` triggers JSR-303 on the record.

## Database

```
src/main/resources/db/migration/V20260615120000__discovery_create_discovery_sources.sql      new
src/main/resources/db/migration/V20260615120100__discovery_create_discovery_jobs.sql         new
src/main/resources/db/migration/V20260615120200__discovery_create_discovery_scrape_log.sql   new
src/main/resources/db/migration/R__discovery_seed_source_registry.sql                        new (empty body — 01e populates)
```

Schemas mirror LLD lines 71-100 / 108-138 / 148-179 verbatim, with the two adjustments above (status width 32 in scrape_log; lowercase-default-vs-uppercase-enum reconciled).

## OpenAPI updates

**Zero OpenAPI changes in 01a.** No controllers exist yet. 01b adds the path-items + schemas in one block.

## Verbatim shape snippets

### `DiscoverySource` entity — JSONB crawl config, mutable bookkeeping fields

```java
@Entity
@Table(name = "discovery_sources",
       uniqueConstraints = @UniqueConstraint(columnNames = "source_key"))
@Getter @Setter @Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class DiscoverySource {
  @Id @Column(name = "id", updatable = false, nullable = false) private UUID id;
  @Column(name = "source_key", nullable = false, length = 64, updatable = false) private String sourceKey;
  @Column(name = "display_name", nullable = false, length = 120) private String displayName;

  @Enumerated(EnumType.STRING)
  @Column(name = "source_type", nullable = false, length = 16) private DiscoverySourceType sourceType;

  @Enumerated(EnumType.STRING)
  @Column(name = "kind", nullable = false, length = 32) private DiscoverySourceKind kind;

  @Column(name = "base_url", nullable = false, length = 255) private String baseUrl;
  @Column(name = "enabled", nullable = false) private boolean enabled;
  @Column(name = "user_disabled", nullable = false) private boolean userDisabled;
  @Column(name = "requests_per_minute", nullable = false) private int requestsPerMinute;
  @Column(name = "requests_per_day", nullable = false) private int requestsPerDay;
  @Column(name = "respect_robots_txt", nullable = false) private boolean respectRobotsTxt;
  @Column(name = "user_agent", nullable = false, length = 160) private String userAgent;

  @Type(JsonBinaryType.class)
  @Column(name = "crawl_config", columnDefinition = "jsonb")
  private JsonNode crawlConfig;

  @Column(name = "failure_streak", nullable = false) private int failureStreak;
  @Column(name = "last_failure_at") private Instant lastFailureAt;
  @Column(name = "last_success_at") private Instant lastSuccessAt;
  @Column(name = "last_used_at") private Instant lastUsedAt;
  @Column(name = "quality_score", precision = 4, scale = 3) private BigDecimal qualityScore;
  @Column(name = "notes", columnDefinition = "text") private String notes;

  @Version @Column(name = "optimistic_version", nullable = false) private long optimisticVersion;
  @CreationTimestamp @Column(name = "created_at", updatable = false, nullable = false) private Instant createdAt;
  @UpdateTimestamp @Column(name = "updated_at", nullable = false) private Instant updatedAt;
}
```

### `DiscoveryJob` entity — JSONB constraints + text[] arrays

```java
@Entity
@Table(name = "discovery_jobs")
@Getter @Setter @Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class DiscoveryJob {
  @Id @Column(name = "id", updatable = false, nullable = false) private UUID id;
  @Column(name = "user_id", nullable = false, updatable = false) private UUID userId;

  @Enumerated(EnumType.STRING)
  @Column(name = "trigger", nullable = false, length = 32) private DiscoveryJobTrigger trigger;

  @Column(name = "requested_count", nullable = false) private int requestedCount;

  @Type(JsonBinaryType.class)
  @Column(name = "constraints_json", nullable = false, columnDefinition = "jsonb")
  private JsonNode constraintsJson;

  @Type(StringArrayType.class)
  @Column(name = "sources_requested", nullable = false, columnDefinition = "text[]")
  private String[] sourcesRequested;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 16) private DiscoveryJobStatus status;

  @Column(name = "queued_at", nullable = false) private Instant queuedAt;
  @Column(name = "started_at") private Instant startedAt;
  @Column(name = "completed_at") private Instant completedAt;
  @Column(name = "candidates_seen", nullable = false) private int candidatesSeen;
  @Column(name = "candidates_after_filter", nullable = false) private int candidatesAfterFilter;
  @Column(name = "recipes_ingested", nullable = false) private int recipesIngested;
  @Column(name = "recipes_skipped_duplicate", nullable = false) private int recipesSkippedDuplicate;

  @Type(StringArrayType.class)
  @Column(name = "sources_succeeded", nullable = false, columnDefinition = "text[]")
  private String[] sourcesSucceeded;

  @Type(StringArrayType.class)
  @Column(name = "sources_failed", nullable = false, columnDefinition = "text[]")
  private String[] sourcesFailed;

  @Column(name = "error_summary", columnDefinition = "text") private String errorSummary;
  @Column(name = "trace_id", nullable = false) private UUID traceId;

  @Version @Column(name = "optimistic_version", nullable = false) private long optimisticVersion;
  @CreationTimestamp @Column(name = "created_at", updatable = false, nullable = false) private Instant createdAt;
  @UpdateTimestamp @Column(name = "updated_at", nullable = false) private Instant updatedAt;
}
```

### `DiscoveryScrapeLog` entity — append-only, NO `@Version`

```java
@Entity
@Table(name = "discovery_scrape_log")
@Getter @Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class DiscoveryScrapeLog {
  @Id @Column(name = "id", updatable = false, nullable = false) private UUID id;
  @Column(name = "job_id", nullable = false, updatable = false) private UUID jobId;
  @Column(name = "source_key", nullable = false, length = 64, updatable = false) private String sourceKey;
  @Column(name = "candidate_url", nullable = false, length = 2048, updatable = false) private String candidateUrl;
  @Column(name = "canonical_url", length = 2048, updatable = false) private String canonicalUrl;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 32, updatable = false) private ScrapeOutcome status;

  @Column(name = "http_status_code", updatable = false) private Integer httpStatusCode;

  @Enumerated(EnumType.STRING)
  @Column(name = "robots_txt_outcome", nullable = false, length = 16, updatable = false) private RobotsTxtOutcome robotsTxtOutcome;

  @Column(name = "latency_ms", updatable = false) private Integer latencyMs;
  @Column(name = "content_fingerprint", length = 64, updatable = false) private String contentFingerprint;
  @Column(name = "extraction_method", length = 32, updatable = false) private String extractionMethod;
  @Column(name = "extraction_confidence", precision = 4, scale = 3, updatable = false) private BigDecimal extractionConfidence;
  @Column(name = "recipe_id", updatable = false) private UUID recipeId;

  @Enumerated(EnumType.STRING)
  @Column(name = "skip_reason", length = 64, updatable = false) private ScrapeSkipReason skipReason;

  @Column(name = "error_class", length = 64, updatable = false) private String errorClass;
  @Column(name = "error_message", columnDefinition = "text", updatable = false) private String errorMessage;
  @Column(name = "occurred_at", nullable = false, updatable = false) private Instant occurredAt;
}
```

### `DiscoveryModule` facade

```java
@Component
@RequiredArgsConstructor
public class DiscoveryModule {
  private final DiscoveryService discoveryService;
  private final DiscoveryQueryService discoveryQueryService;

  public DiscoveryService discoveryService() { return discoveryService; }
  public DiscoveryQueryService discoveryQueryService() { return discoveryQueryService; }
}
```

01b ships the impl class `DiscoveryServiceImpl` implementing both interfaces — Spring resolves the autowire then. **01a needs at least empty bean stubs** so the facade injects cleanly. **Option**: ship a placeholder `@Component class DiscoveryServiceStub implements DiscoveryService, DiscoveryQueryService {}` in 01a's `domain/service/internal/`, deleted by 01b when the real impl arrives. Single-line cost, avoids a context-load failure today. **Worth user review.** **Decision: ship the stub.**

### `DiscoveryAsyncConfig`

```java
@Configuration
public class DiscoveryAsyncConfig {
  @Bean(name = "discoveryRunnerExecutor")
  public ThreadPoolTaskExecutor discoveryRunnerExecutor() {
    ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
    exec.setCorePoolSize(2);
    exec.setMaxPoolSize(4);
    exec.setQueueCapacity(8);
    exec.setThreadNamePrefix("discovery-runner-");
    exec.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
    exec.initialize();
    return exec;
  }
}
```

`CallerRunsPolicy` per LLD line 566's "Rejected jobs surface as the queue's RejectedExecutionException and re-queue the job after a backoff" — caller-runs is the simplest backpressure mechanism for v1; 01d may revise once it sees real load. **Worth user review.**

## Edge-case checklist

- [ ] `V20260615120000`, `V20260615120100`, `V20260615120200` migrations all apply cleanly against Testcontainers Postgres — `FlywayMigrationIT` (existing) passes (a new test class isn't needed; the project-wide migration test exercises all three new files).
- [ ] `R__discovery_seed_source_registry.sql` ships as an empty-bodied file (comment header only) — Flyway treats empty repeatables as no-ops; verify the migration test doesn't trip on this.
- [ ] `ddl-auto=validate` accepts all three entities against the migration schema — Hibernate startup logs in `FlywayMigrationIT` are warning-free.
- [ ] `DiscoveryJob.constraintsJson` JSONB round-trip — save a `DiscoveryConstraints` (schemaVersion=1, two cuisines, three dietary flags) → JSONB → re-read → equal. `DiscoveryConstraintsRoundTripTest` (unit, no Spring context).
- [ ] `DiscoveryJob.sourcesRequested / Succeeded / Failed` text[] round-trip — save `{"src_a","src_b"}` → re-read equal array. If `StringArrayType` fails, fall back to JSONB `List<String>` per invariant 9; note in report.
- [ ] `DiscoveryScrapeLog` insert with all-nullable fields populated and with sparse fields (`status = SKIPPED`, `robots_txt_outcome = SKIPPED`, all error/recipe fields null) — both insert and round-trip.
- [ ] Index existence assertions on each `idx_discovery_*` index: query `pg_indexes` via `JdbcTemplate` in `DiscoveryMigrationIT` (new — single test class), assert each index from invariants 1, 5, 10 is present.
- [ ] Partial indexes have the correct `WHERE` predicate — assert via `pg_indexes.indexdef LIKE '%WHERE%'` for each.
- [ ] `discovery_scrape_log.recipe_id` is NOT a foreign key — assert via `pg_constraint` query (no FK row with `confrelid = 'recipe_recipes'::regclass`).
- [ ] `discovery_scrape_log.job_id` IS a foreign key with `ON DELETE CASCADE` — `pg_constraint` query asserts `confdeltype = 'c'`.
- [ ] No new dependencies added to `pom.xml` — `StringArrayType` / `JsonBinaryType` come from `hypersistence-utils-hibernate-63` already in the pom.
- [ ] `DiscoveryBoundaryTest` (ArchUnit) passes — exercises only the repository / entity rule today; SPI rule lands with 01c.
- [ ] `DiscoveryServiceStub` (the placeholder impl) injects without `NoUniqueBeanDefinitionException` — context loads.
- [ ] `DiscoveryAsyncConfig` registers a bean named `discoveryRunnerExecutor`. `@Autowired @Qualifier("discoveryRunnerExecutor") Executor exec` resolves in a unit `@SpringBootTest` slice.
- [ ] `DiscoveryProperties` binds: spin up `@SpringBootTest`-slice IT with `mealprep.discovery.heartbeat-timeout=PT10M` set; assert the bean reads it.

## Files this ticket touches

```
NEW   src/main/resources/db/migration/V20260615120000__discovery_create_discovery_sources.sql
NEW   src/main/resources/db/migration/V20260615120100__discovery_create_discovery_jobs.sql
NEW   src/main/resources/db/migration/V20260615120200__discovery_create_discovery_scrape_log.sql
NEW   src/main/resources/db/migration/R__discovery_seed_source_registry.sql

NEW   src/main/java/com/example/mealprep/discovery/DiscoveryModule.java
NEW   src/main/java/com/example/mealprep/discovery/api/dto/DiscoverySourceDto.java
NEW   src/main/java/com/example/mealprep/discovery/api/dto/DiscoveryJobDto.java
NEW   src/main/java/com/example/mealprep/discovery/api/dto/DiscoveryScrapeLogEntryDto.java
NEW   src/main/java/com/example/mealprep/discovery/api/dto/DiscoveryConstraints.java
NEW   src/main/java/com/example/mealprep/discovery/api/dto/StartDiscoveryJobRequest.java
NEW   src/main/java/com/example/mealprep/discovery/api/mapper/DiscoveryJobMapper.java
NEW   src/main/java/com/example/mealprep/discovery/api/mapper/DiscoverySourceMapper.java
NEW   src/main/java/com/example/mealprep/discovery/api/mapper/DiscoveryScrapeLogMapper.java

NEW   src/main/java/com/example/mealprep/discovery/domain/entity/DiscoverySource.java
NEW   src/main/java/com/example/mealprep/discovery/domain/entity/DiscoveryJob.java
NEW   src/main/java/com/example/mealprep/discovery/domain/entity/DiscoveryScrapeLog.java
NEW   src/main/java/com/example/mealprep/discovery/domain/entity/DiscoverySourceKind.java
NEW   src/main/java/com/example/mealprep/discovery/domain/entity/DiscoverySourceType.java
NEW   src/main/java/com/example/mealprep/discovery/domain/entity/DiscoveryJobStatus.java
NEW   src/main/java/com/example/mealprep/discovery/domain/entity/DiscoveryJobTrigger.java
NEW   src/main/java/com/example/mealprep/discovery/domain/entity/ScrapeOutcome.java
NEW   src/main/java/com/example/mealprep/discovery/domain/entity/RobotsTxtOutcome.java
NEW   src/main/java/com/example/mealprep/discovery/domain/entity/ScrapeSkipReason.java

NEW   src/main/java/com/example/mealprep/discovery/domain/repository/DiscoveryJobRepository.java
NEW   src/main/java/com/example/mealprep/discovery/domain/repository/DiscoverySourceRepository.java
NEW   src/main/java/com/example/mealprep/discovery/domain/repository/DiscoveryScrapeLogRepository.java

NEW   src/main/java/com/example/mealprep/discovery/domain/service/DiscoveryService.java                  (empty interface body)
NEW   src/main/java/com/example/mealprep/discovery/domain/service/DiscoveryQueryService.java             (empty interface body)
NEW   src/main/java/com/example/mealprep/discovery/domain/service/internal/DiscoveryServiceStub.java     (placeholder bean, deleted by 01b)

NEW   src/main/java/com/example/mealprep/discovery/config/DiscoveryAsyncConfig.java
NEW   src/main/java/com/example/mealprep/discovery/config/DiscoveryProperties.java

NEW   src/main/java/com/example/mealprep/discovery/exception/DiscoveryException.java

NEW   src/test/java/com/example/mealprep/discovery/DiscoveryBoundaryTest.java
NEW   src/test/java/com/example/mealprep/discovery/DiscoveryMigrationIT.java
NEW   src/test/java/com/example/mealprep/discovery/DiscoveryConstraintsRoundTripTest.java
NEW   src/test/java/com/example/mealprep/discovery/testdata/DiscoveryTestData.java
```

Count: ~30 files. Most are 1-line enums / DTO records. Logic concentration is in the three entities + three mappers + four config/facade classes. Estimated agent runtime 40-50 min (slightly above the median because of three migrations + three mappers + JSONB / text[] round-trip verification).

**Files this ticket does NOT modify**:
- `src/main/java/com/example/mealprep/config/GlobalExceptionHandler.java`
- `src/test/java/com/example/mealprep/archunit/ModuleBoundaryTest.java`
- Other modules' files
- `pom.xml`

## Dependencies

- **Hard dependency**: `core` (merged) — `MealPrepException` root, `@Type(JsonBinaryType.class)` infra.
- **Hard dependency**: `auth-01a` (merged) — referenced by 01b's controllers, not by 01a directly.
- **Hard dependency**: `refactor-01-split-merge-zones` (merged) — per-module YAML / boundary-test layout.
- **Sibling tickets running in parallel** (Wave 3 round 1): `planner-01a`, `feedback-01a`, `adaptation-pipeline-01a`. None should touch any discovery file or cross-cutting file.
- **No sibling tickets within discovery in 01a** — 01b waits on 01a.

## Acceptance / DoD

- [ ] `./mvnw -Dspotless.check.skip=true verify` passes
- [ ] `./mvnw spotless:apply` then `./mvnw spotless:check` clean
- [ ] CI green (build + spotless + OpenAPI lint + ArchUnit gate)
- [ ] All edge-case items above ticked
- [ ] `DiscoveryBoundaryTest` passes — discovery's repository + entity packages are sealed
- [ ] No regressions on existing tests
- [ ] No `pom.xml` adds
- [ ] `DiscoveryConstraintsRoundTripTest` passes — the JSONB document round-trips cleanly
- [ ] `DiscoveryMigrationIT` passes — all three migrations apply, all indexes exist, `recipe_id` is NOT an FK, `job_id` IS an FK with cascade

## Gotchas embedded (apply during implementation)

- **`text[]` mapping**: `@Type(StringArrayType.class)` from `hypersistence-utils-hibernate-63`. If FlywayMigrationIT round-trip fails (Hibernate 6 has had multiple `text[]` regressions in the SB 3.2.5 line), fall back to `jsonb` + `List<String>` via `@Type(JsonBinaryType.class)`. Document the chosen path in the agent's report.
- **`@EntityGraph` over multiple `List<>` collections** — N/A here, no `@OneToMany`; flagged for awareness for 01d when the runner reads jobs.
- **JSONB round-trip discipline** per [style-guide.md §JSONB §Required discipline](../../lld/style-guide.md): code-side record, `schemaVersion` at root, round-trip test. Applied to `DiscoveryConstraints` via `DiscoveryConstraintsRoundTripTest`.
- **Lowercase-string DEFAULT vs uppercase enum**: Hibernate `EnumType.STRING` writes `'QUEUED'` (Java enum name). Migration `DEFAULT 'queued'` would mismatch on round-trip. Fix the SQL DEFAULT to UPPERCASE; the LLD's lowercase examples are illustrative. Per invariant 19.
- **Column-width-from-format discipline**: `discovery_scrape_log.status varchar(24)` is one short of `HARD_CONSTRAINT_VIOLATION` (25 chars). Widen to 32. Per invariant 12 — the "Don't trust LLD column widths blindly" gotcha applies.
- **`@Version` is required on `DiscoveryJob` and `DiscoverySource`** but **must NOT be on `DiscoveryScrapeLog`** — the scrape log is append-only; @Version on an append-only table is harmless but wasteful and signals "this should be mutable" which it isn't. Per [LLD line 195](../../lld/discovery.md).
- **Application-side UUID generation** for all three entities — per the style-guide. The 01a stub service is empty; 01b services generate the UUIDs.
- **Empty repeatable Flyway migration**: `R__discovery_seed_source_registry.sql` ships with just a SQL comment header (`-- 01a: empty placeholder. Populated by discovery-01e.`). Flyway 9 treats empty repeatables as no-ops; the migration runs once at startup with zero side effects.
- **`@ConfigurationProperties` record validation**: `@Validated` on the record + JSR-303 annotations on fields. Spring Boot 3.x picks up `record` config classes automatically via `@ConfigurationPropertiesScan` (verify it's on the main config) OR via explicit `@EnableConfigurationProperties(DiscoveryProperties.class)` at the module's config. **Pick explicit** — robust across project setups; one extra annotation on `DiscoveryAsyncConfig`.

## What's NOT in scope

- `DiscoveryServiceImpl` real methods (`startJob`, `runJobSync`, `cancelJob`, query methods) → **discovery-01b**
- REST controllers + `DiscoveryExceptionHandler` + per-failure exception subclasses + `@ValidDiscoveryConstraints` custom validator → **discovery-01b**
- `DiscoverySource` SPI + `SourceRegistry` + `RobotsTxtGate` + `RateLimiterRegistry` + `ContentFingerprintHasher` + `CandidateAiFilter` no-op → **discovery-01c**
- `DiscoveryJobRunner` + per-step transactions + `DiscoveryRecipeIngestedEvent` + `DiscoveryJobCompletedEvent` + `DiscoveryJobStartedEvent` + orphan sweep + cancellation + `RecipeWriteApi.saveImportedRecipe` handoff → **discovery-01d**
- Curated source seed (~25-30 INSERTs) + Google Custom Search adapter + reference curated DiscoverySource stub → **discovery-01e**
- Sync admin endpoint + `runJobSync` + `CompletableFuture` coordination + 408 / 502 mappings → **discovery-01f**
- Cross-user authorisation rules in queries (LLD line 360 `getJobForUser`) — service-layer scope, lands with 01b
- `idx_discovery_sources_kind` GIN — N/A, simple B-tree index per LLD line 99
- `DiscoveryRecipeIngestedEvent` and the `DiscoveryRecipeIngestedListener` (no consumer in v1) → **discovery-01d** publishes; consumers TBD
- The actual list of curated sources (~25-30 sites) — **user-decided**; 01e ships the seed file with the user's chosen list

Squash-merge with: `feat(discovery): 01a — discovery entities, migrations, repos, module facade, boundary test`
