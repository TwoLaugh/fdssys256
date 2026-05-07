# AI Module — LLD

*Implementation specification for the cross-cutting `AiService` — the single Java seam every module crosses to reach Anthropic. Specifies the `AiTask<T>` SPI, dispatcher mechanics, retry / cost-tracking infrastructure, prompt-template loading, and structured-output parsing. The actual prompt content for any individual task lives with the calling module that owns the task; this LLD only specifies how those tasks are loaded, called, parsed, retried, and accounted for.*

## Scope

This document specifies the `ai` module — package layout, JPA entities, Flyway migrations, repositories, service interfaces, DTOs, mappers, REST controllers (admin/observability only), validation, events, business-logic flows, transaction boundaries, and the test plan. Conventions defer to [lld/style-guide.md](style-guide.md); this LLD restates a rule only when the module-specific application matters.

The HLD's central commitment lives in [technical-architecture.md §AI Service Architecture](../design/technical-architecture.md#ai-service-architecture): `AiService.execute(AiTask<T>)` is the only public dispatcher; `AiTask<T>` is the SPI every calling module implements; structured output is via Anthropic tool use; per-task token caps, per-user daily caps, prompt versioning, retry, circuit breaking, prompt caching, and a cost ledger are all owned here. The HLD is mostly silent on the impl-level detail under that contract — this LLD fills that in.

Cross-module references: cheap-tier classification ([feedback-system.md](../design/feedback-system.md)), mid-tier adaptation ([recipe-system.md](../design/recipe-system.md)), frontier-tier creative augmentation ([meal-planner.md](../design/meal-planner.md)), mid-tier preference delta updates ([preference-model.md](../design/preference-model.md)), cheap-tier USDA mapping + free-text parsing ([nutrition-model.md](../design/nutrition-model.md)). The Stage C LLM-context rule (LLM sees candidates + rollups, never the underlying pool) per [optimisation-loop.md §LLM context shape](../design/optimisation-loop.md) is honoured by **not** prescribing context shape — that's the calling task's responsibility — and enforced via a per-task token cap that makes "shove the whole pool in" fail loudly.

---

## Package Layout

```
com.example.mealprep.ai/
├── AiModule.java                              facade re-exporting public service interfaces
├── api/
│   ├── controller/                            CostSummaryController, CallLogController, PromptTemplateController
│   ├── dto/                                   records (see DTOs)
│   └── mapper/                                AiCallLogMapper, PromptTemplateMapper, ApiKeyRotationLogMapper
├── domain/
│   ├── entity/                                AiCallLog, PromptTemplate, ApiKeyRotationLog
│   ├── repository/                            Spring Data interfaces — package-private
│   └── service/
│       ├── AiService.java                     public interface (dispatcher)
│       ├── AiCostTrackingService.java         public interface
│       ├── PromptTemplateService.java         public interface
│       ├── AiServiceImpl.java                 single impl of the three
│       └── internal/                          AnthropicClient, ModelTierResolver, PromptTemplateLoader,
│                                              PromptTemplateRenderer, ToolUseInvoker, StructuredOutputParser,
│                                              RetryPolicy, CostCalculator, CostBudgetGuard, AiCallRecorder
├── spi/                                       AiTask, TaskType, ModelTier, ToolDefinition, PromptRef
├── event/                                     AiCallSucceededEvent, AiCallFailedEvent, CostBudgetExceededEvent
├── exception/                                 module-root + per-failure subclasses
├── validation/                                @ValidPromptRef, @ValidToolDefinition
├── config/                                    AiConfig, AnthropicSdkConfig, AiObjectMapperConfig
└── testing/                                   TestAiService — test-profile bean replacing AiServiceImpl
```

`AiModule.java` re-exports the three public service interfaces and the SPI types. The `spi/` subpackage exists because every other module compiles against `AiTask<T>`, `TaskType`, `ModelTier`, `ToolDefinition`; making it a sibling of `domain/service/` signals these are part of the public surface. `testing/TestAiService.java` is a `@Profile("test") @Primary` bean returning canned responses keyed by `TaskType` — never reaches the network. See [Test Plan](#test-plan).

---

## Database

Migrations live under `src/main/resources/db/migration/` with the project-wide timestamp scheme from [technical-architecture.md §Migrations](../design/technical-architecture.md#migrations):

```
V20260501110000__ai_create_call_log.sql
V20260501110100__ai_create_prompt_template.sql
V20260501110200__ai_create_api_key_rotation_log.sql
```

Timestamps are intentionally earlier than the preference module (`V20260501120000__...`) — the AI module is ordered ahead in the build sequence. Nothing in this schema cross-references other modules.

### V20260501110000 — Call log

```sql
CREATE TABLE ai_call_log (
    id                       uuid PRIMARY KEY,
    user_id                  uuid,                          -- nullable: system-initiated
    trace_id                 uuid,                          -- decision-log correlation
    task_kind                varchar(16) NOT NULL,          -- COMPLETION | EMBEDDING (separate cost buckets)
    task_type                varchar(64) NOT NULL,          -- TaskType OR EmbeddingTaskType enum
    provider                 varchar(32) NOT NULL,          -- anthropic | openai | local
    model_tier               varchar(16),                   -- FRONTIER | MID | CHEAP — null for embeddings
    model_id                 varchar(96) NOT NULL,
    prompt_template_name     varchar(128),                  -- null for embeddings
    prompt_template_version  varchar(40),                   -- null for embeddings
    prompt_hash              varchar(64),                   -- null for embeddings
    input_tokens             integer NOT NULL,
    output_tokens            integer NOT NULL DEFAULT 0,    -- always 0 for embeddings
    cached_input_tokens      integer NOT NULL DEFAULT 0,    -- prompt-cache hit portion
    cost_gbp                 numeric(10,6) NOT NULL,        -- 6dp; sub-pence per call
    latency_ms               integer NOT NULL,
    retry_count              integer NOT NULL DEFAULT 0,
    status                   varchar(16) NOT NULL,          -- SUCCESS | RETRIED_OK | FAILED | ABORTED_CAP
    failure_kind             varchar(32),                   -- TIMEOUT | RATE_LIMIT | SEMANTIC | AUTH | POLICY | UNKNOWN
    error_excerpt            varchar(512),                  -- truncated; never the API key
    full_prompt_json         jsonb,                         -- populated only on FAILED for completions; null for embeddings
    occurred_at              timestamptz NOT NULL,
    created_at               timestamptz NOT NULL
);
-- task_kind separates the two cost-cap buckets (completions: £10/mo default; embeddings: £2/mo default).
-- Cost summary per user-day (the single most-frequent admin query and the daily-cap hot path).
CREATE INDEX idx_ai_call_log_user_day  ON ai_call_log (user_id, occurred_at DESC);
CREATE INDEX idx_ai_call_log_task_time ON ai_call_log (task_type, occurred_at DESC);
CREATE INDEX idx_ai_call_log_trace     ON ai_call_log (trace_id);
CREATE INDEX idx_ai_call_log_status    ON ai_call_log (status, occurred_at DESC) WHERE status <> 'SUCCESS';
```

`full_prompt_json` is null on the happy path and populated only when `status = FAILED` — the HLD's "log full prompt on failure only" rule. Shape: `{ "system": "...", "userMessage": "...", "context": { ... }, "toolDefinition": { ... } }`, with API key and obvious PII stripped. `error_excerpt` is capped at 512 chars and explicitly never contains credentials — `AnthropicClient` strips authorisation headers before producing error strings.

### V20260501110100 — Prompt template

```sql
CREATE TABLE prompt_template (
    id                       uuid PRIMARY KEY,
    name                     varchar(128) NOT NULL,         -- e.g. "feedback/classify-feedback"
    content_hash             varchar(64)  NOT NULL,         -- SHA-256, hex; stable id for the file
    body                     text NOT NULL,                 -- raw template text (mirror of file)
    placeholders             text[] NOT NULL DEFAULT '{}',  -- detected during loading; smoke check
    handlebars               boolean NOT NULL DEFAULT false,-- v1: false; switches once we adopt Handlebars
    description              varchar(256),                  -- first comment line of the template, optional
    first_seen_at            timestamptz NOT NULL,          -- when this hash first appeared in the deployment
    last_used_at             timestamptz,                   -- updated by AiCallRecorder for audit retention
    created_at               timestamptz NOT NULL,
    UNIQUE (name, content_hash)
);
-- Look up the active version (latest first_seen_at) for a given name.
CREATE INDEX idx_prompt_template_name_seen ON prompt_template (name, first_seen_at DESC);
```

Templates are file-based under `src/main/resources/prompts/` per [technical-architecture.md §Prompt management](../design/technical-architecture.md#prompt-management). The DB row is the audit trail: every distinct content hash ever loaded shows up once, lets the call log point at a specific version, and survives even if the file is later removed. Templates are never edited in DB — the file is the source of truth. Identified by `(name, content_hash)`; calling code references by `name` only.

### V20260501110200 — API key rotation log

```sql
CREATE TABLE api_key_rotation_log (
    id                       uuid PRIMARY KEY,
    rotated_at               timestamptz NOT NULL,
    old_key_fingerprint      varchar(64) NOT NULL,          -- last-4 + SHA-256 of full key
    new_key_fingerprint      varchar(64) NOT NULL,
    rotated_by               varchar(64) NOT NULL,          -- env-var | startup-detect | manual-admin
    notes                    varchar(255)
);
-- Audit lookup latest-first.
CREATE INDEX idx_api_key_rotation_log_time ON api_key_rotation_log (rotated_at DESC);
```

Stores fingerprints only — never key contents. Fingerprint is `last4(key) + ":" + sha256(key)`, sufficient to confirm which key was active without leaking the secret. Rotation is detected at startup: `AiConfig` reads the key, computes its fingerprint, compares against the most recent log row; a mismatch writes a new row. Manual rotation through an admin endpoint is out of scope for v1.

---

## Entities

Style-guide standard: UUID `@Id` application-side, Lombok `@Getter @Setter @Builder @NoArgsConstructor(access = PROTECTED) @AllArgsConstructor`, JSONB via `@Type(JsonType.class)` from `hypersistence-utils`. All three tables are append-only — no `@Version`. `AiCallLog.fullPromptJson` is `JsonNode`, null on success. `PromptTemplate.body` is `@Lob`; `placeholders` is `text[]`; `lastUsedAt` is the only post-insert field updated. Module-local enums: `AiCallStatus { SUCCESS, RETRIED_OK, FAILED, ABORTED_CAP }`, `FailureKind { TIMEOUT, RATE_LIMIT, SEMANTIC, AUTH, POLICY, UNKNOWN }`, `ModelTier { FRONTIER, MID, CHEAP }` (lives in `spi/`).

---

## SPI — what calling modules implement

The HLD commits to two interface skeletons. The LLD makes them concrete and adds `PromptRef` so the dispatcher knows which template (and which version) to load.

```java
public interface AiTask<T> {
    TaskType getTaskType();                       // determines tier, timeout, token cap
    String getSystemPrompt();                      // Anthropic system param
    PromptRef getUserPromptRef();                  // template name + optional pinned hash
    Map<String, Object> getContext();              // fills template placeholders
    ToolDefinition getToolSchema();                // JSON schema — driven from Class<T>
    Class<T> getResponseType();                    // Jackson reads tool_use into this type
    UUID getUserId();                              // for cost tracking; nullable for system tasks
    UUID getTraceId();                             // for decision-log correlation; nullable
    Optional<Duration> getTimeoutOverride();       // rare; default from TaskType config
}
```

`getContext()` is `Map<String, Object>` rather than `Map<String, String>` (HLD says the latter) — the optimisation-loop's [LLM context shape](../design/optimisation-loop.md#llm-context-shape) needs structured rollups; renderer treats values as Jackson-serialisable, primitive `String` still works. See Decisions §1.

`TaskType` enumerates the v1 universe per [system-overview.md §AI Model Tiers](../design/system-overview.md#ai-model-tiers): `PLAN_COMPOSITION` (mid; may go deterministic), `PLAN_AUGMENTATION` (frontier), `MID_WEEK_REOPTIMISATION`, `RECIPE_ADAPTATION`, `RECIPE_GENERATION`, `RECIPE_HTML_EXTRACTION` (cheap; Layer 4 fallback in [recipe-extraction-pipeline.md](recipe-extraction-pipeline.md) — invoked only when JSON-LD / h-recipe / per-site extractors all miss), `RECIPE_DISCOVERY_FILTER` (mid; AI filter that scores candidate URLs against user preferences before Layer 1 extraction), `PREFERENCE_DELTA_UPDATE` (mid), `FEEDBACK_CLASSIFICATION`, `NUTRITION_INGREDIENT_MAPPING`, `NUTRITION_INTAKE_PARSE`, `HEALTH_DIRECTIVE_PARSE` (cheap), `TESCO_PRODUCT_MATCH` (mid → frontier via override per HLD).

`RECIPE_HTML_EXTRACTION` notes: 32k input-token cap, ~$0.001-0.005 per call, prompt caching on the system message gives ~40% reduction in batch discovery runs. The structured output type is `ParsedRecipe` from the extraction pipeline. Prompt content is one of the 9 deferred prompts.

```java
public record ToolDefinition(String name, String description, JsonNode inputSchema) {}
public record PromptRef(String name, Optional<String> pinnedContentHash) {}
```

Default behaviour is "use current" — the loaded file is the canonical version. Tests pin a hash to exercise a specific version. The AI module never imports calling-module `AiTask` implementations.

### `EmbeddingTask` — the second SPI

Embeddings have a different shape from completion calls (no system prompt, no structured output, no tool schema; just text in, vector out). Modelled as a separate SPI in the same module:

```java
public interface EmbeddingTask {
    EmbeddingTaskType getTaskType();              // determines provider, dim, cost bucket
    String getInputText();                         // the text to embed; module composes
    UUID getUserId();                              // nullable for system tasks
    UUID getTraceId();                             // nullable
}

public enum EmbeddingTaskType {
    RECIPE_EMBEDDING,                              // 1536d via OpenAI text-embedding-3-small
    TASTE_PROFILE_EMBEDDING                        // same model; same dim
}

public record EmbeddingResult(float[] vector, String modelId, int inputTokens, BigDecimal cost) {}
```

`AiService` exposes a sibling method for embeddings:

```java
EmbeddingResult embed(EmbeddingTask task);
```

Provider abstraction lives behind a package-private `EmbeddingProvider` interface implemented by `OpenAiEmbeddingProvider` for v1. Future implementations (`VoyageEmbeddingProvider`, `LocalSentenceTransformerProvider`) plug in behind the same interface. Provider selection is per-`EmbeddingTaskType` via config — different task types could use different providers if useful.

Embeddings cost is tracked in the same `ai_call_log` table with `task_kind = EMBEDDING` and a separate cost cap (`mealprep.ai.embedding.monthly_cap_gbp`, default £2). On `AiUnavailable` for embeddings: callers fall back to the last successful embedding (recipe / taste profile) or to a deterministic neutral score (0.5 in `PreferenceSubScore`) — see `style-guide.md §Embeddings`.

---

## DTOs

All public DTOs are Java records. Internal-only types (`AiCallContext`, `RawAnthropicResponse`) live in `domain/service/internal/`.

```java
public record AiCallRecord(
    UUID id, UUID userId, UUID traceId,
    TaskType taskType, ModelTier modelTier, String modelId,
    String promptTemplateName, String promptTemplateVersion,
    int inputTokens, int outputTokens, int cachedInputTokens,
    BigDecimal costGbp, int latencyMs, int retryCount,
    AiCallStatus status, FailureKind failureKind, String errorExcerpt,
    Instant occurredAt) {}

public record CostAggregateDto(
    AggregateBucket bucket, LocalDate periodStart,           // DAY | WEEK | MONTH; UTC bucket start
    BigDecimal totalCostGbp, long callCount,
    long inputTokens, long outputTokens,
    Map<TaskType, BigDecimal> costByTaskType,
    Map<ModelTier, BigDecimal> costByTier,
    Map<UUID, BigDecimal> costByUser) {}                     // empty when filtered to one userId

public enum AggregateBucket { DAY, WEEK, MONTH }

public record PromptTemplateDto(
    UUID id, String name, String contentHash,
    boolean handlebars, List<String> placeholders,
    String description, String body,                         // body only on detail
    Instant firstSeenAt, Instant lastUsedAt) {}

public record PromptTemplateSummaryDto(
    UUID id, String name, String contentHash,
    Instant firstSeenAt, Instant lastUsedAt) {}
```

`AiCallRecord` excludes `fullPromptJson` — revealed only via the detail endpoint, so the list view stays small.

---

## Mappers

MapStruct, `@Mapper(componentModel = "spring")`, one per entity-DTO pair: `AiCallLogMapper` (`toRecord`, `toRecords`), `PromptTemplateMapper` (`toDto`, `toSummary`, `toSummaries` — `body` only on detail), `ApiKeyRotationLogMapper` (`toDto`, `toDtos`). `fullPromptJson` is mapped via a custom `@Named` qualifier and only populated on the detail-fetch path.

---

## Repositories

Package-private — cross-module callers go through service interfaces only.

```java
interface AiCallLogRepository extends JpaRepository<AiCallLog, UUID> {
    Page<AiCallLog> findByUserIdAndOccurredAtBetween(UUID u, Instant f, Instant t, Pageable p);
    Page<AiCallLog> findByTaskTypeAndOccurredAtBetween(TaskType tt, Instant f, Instant t, Pageable p);
    Page<AiCallLog> findByOccurredAtBetween(Instant f, Instant t, Pageable p);

    @Query("select coalesce(sum(c.costGbp),0) from AiCallLog c where c.userId=:u and c.occurredAt>=:since")
    BigDecimal sumCostForUserSince(@Param("u") UUID userId, @Param("since") Instant since);

    // Both aggregators accept userId=null to opt out of the user filter.
    List<TaskTypeCostRow>  aggregateByTaskType(Instant from, Instant to, UUID userId);
    List<ModelTierCostRow> aggregateByModelTier(Instant from, Instant to, UUID userId);
}

interface PromptTemplateRepository extends JpaRepository<PromptTemplate, UUID> {
    Optional<PromptTemplate> findByNameAndContentHash(String name, String contentHash);
    Optional<PromptTemplate> findFirstByNameOrderByFirstSeenAtDesc(String name);
    List<PromptTemplate> findAllByOrderByNameAscFirstSeenAtDesc();
}

interface ApiKeyRotationLogRepository extends JpaRepository<ApiKeyRotationLog, UUID> {
    Optional<ApiKeyRotationLog> findFirstByOrderByRotatedAtDesc();
    Page<ApiKeyRotationLog> findAllByOrderByRotatedAtDesc(Pageable p);
}
```

The user-day cost sum is a hot path (every dispatcher call checks the daily cap) — index `idx_ai_call_log_user_day` covers it.

---

## Service Interfaces

Per the style guide, the three module interfaces are implemented by a single `AiServiceImpl`. `AiTask<T>` is the SPI — calling modules implement it; nobody implements `AiService` outside this module except `TestAiService`.

### `AiService` — the dispatcher

```java
public interface AiService {
    /**
     * Resolves tier, renders prompt, calls Anthropic, parses tool_use, retries, logs, returns T.
     * Not @Transactional — see Concurrency. The post-call ledger insert is its own short transaction.
     *
     * Throws:
     *   AiUnavailableException — graceful-degrade signal. Cost cap reached, API key missing,
     *     or AI deliberately disabled. Callers MUST handle by degrading gracefully per their
     *     LLD's failure-modes section (skip-and-flag / defer-and-pending / block-and-prompt
     *     per [style-guide.md §AI Service — Graceful Degradation]).
     *     This is an expected outcome, not an error.
     *
     *   AiTokenCapExceededException — task context exceeds model's input limit. Caller bug.
     *   AiCircuitOpenException — task type's circuit is open after consecutive failures.
     *   AiCallFailedException — terminal failure after retries exhausted.
     *   AiResponseInvalidException — parsing failed even after corrective re-prompt.
     */
    <T> T execute(AiTask<T> task);
}
```

### `AiCostTrackingService` — admin observability

```java
public interface AiCostTrackingService {
    CostAggregateDto aggregate(Instant from, Instant to, AggregateBucket bucket,
                               Optional<TaskType> taskType, Optional<UUID> userId);
    Page<AiCallRecord> findCalls(Instant from, Instant to,
                                 Optional<TaskType> taskType, Optional<UUID> userId,
                                 Optional<AiCallStatus> status, Pageable pageable);
    Optional<AiCallRecord> getCall(UUID callId);
    BigDecimal currentDailyCostForUser(UUID userId);   // live from call log
    BigDecimal currentMonthlyCostTotal();
}
```

### `PromptTemplateService` — load + render + audit

```java
public interface PromptTemplateService {
    LoadedTemplate loadCurrent(String name);                              // classpath-backed; cached
    LoadedTemplate loadPinned(String name, String contentHash);           // golden-set replay
    String render(LoadedTemplate template, Map<String, Object> context);  // {{...}} or Handlebars
    List<PromptTemplateSummaryDto> listAll();
    PromptTemplateDto getDetail(UUID templateId);
}

public record LoadedTemplate(UUID id, String name, String contentHash,
                             String body, boolean handlebars, List<String> placeholders) {}
```

`loadCurrent` walks the classpath under `prompts/` once at startup, hashes each file, and upserts each `(name, content_hash)` into `prompt_template`. Subsequent calls hit the in-memory cache. Runtime file changes are **not** auto-detected — deploy is the trigger. `loadPinned` is the golden-set / replay path: loads from DB rather than classpath, so an old hash no longer in the file tree still resolves.

---

## REST Controllers

All endpoints under `/api/v1/ai/...`. **Admin-only** — no public-facing AI endpoints; every AI call comes through Java service injection. `@PreAuthorize("hasRole('ADMIN')")` on every controller (role taxonomy owned by the auth module).

| Method | Path | Request | Response | Status |
|---|---|---|---|---|
| GET | `/api/v1/ai/cost-summary?from=&to=&bucket=&taskType=&userId=` | — | `CostAggregateDto` | 200 / 400 |
| GET | `/api/v1/ai/call-log?from=&to=&taskType=&userId=&status=&page=&size=` | — | `Page<AiCallRecord>` | 200 |
| GET | `/api/v1/ai/call-log/{callId}` | — | `AiCallRecord` (with `fullPromptJson` if FAILED) | 200 / 404 |
| GET | `/api/v1/ai/prompt-templates` | — | `List<PromptTemplateSummaryDto>` | 200 |
| GET | `/api/v1/ai/prompt-templates/{templateId}` | — | `PromptTemplateDto` | 200 / 404 |
| GET | `/api/v1/ai/api-key-rotations?page=&size=` | — | `Page<ApiKeyRotationDto>` | 200 |

Defaults per style guide: `size` default 20, max 100. Time-range params accept ISO-8601 instants; `from` / `to` required for `/call-log`.

### Error responses

RFC 9457 ProblemDetail. Module-specific exceptions (handled in the project-wide `GlobalExceptionHandler`):

| Exception | Status | `type` URI |
|---|---|---|
| `AiCallLogNotFoundException` | 404 | `/problems/ai-call-log-not-found` |
| `PromptTemplateNotFoundException` | 404 | `/problems/prompt-template-not-found` |
| `AiUnavailableException` | 503 | `/problems/ai-unavailable` (graceful-degrade signal — admin endpoints only; calling modules handle in-process, not via HTTP) |
| `AiTokenCapExceededException` | 422 | `/problems/ai-token-cap-exceeded` |
| `AiCircuitOpenException` | 503 | `/problems/ai-circuit-open` |
| `AiCallFailedException` | 502 | `/problems/ai-call-failed` |
| `AiResponseInvalidException` | 502 | `/problems/ai-response-invalid` |

Module root: `AiException extends MealPrepException`.

---

## Validation

Standard Jakarta annotations on request records and `@ConfigurationProperties` (`@NotBlank`, `@Min`, `@Max`, `@Positive`). Custom validators in `validation/`:

- **`@ValidPromptRef`** — `name` non-blank and matches `^[a-z0-9-]+(/[a-z0-9-]+)+$` (the directory layout); pinned hash, if present, is 64-char hex.
- **`@ValidToolDefinition`** — `name` matches `^[a-zA-Z][a-zA-Z0-9_-]{0,63}$` (Anthropic's tool-name rule); `inputSchema` is a JSON object with a `type` property.

`@ConfigurationProperties` validation fails fast at startup — Spring won't boot if the API key is missing.

---

## Events

### Published

```java
public record AiCallSucceededEvent(
    UUID callId, UUID userId, UUID traceId,
    TaskType taskType, ModelTier modelTier,
    int inputTokens, int outputTokens,
    BigDecimal costGbp, int latencyMs, int retryCount, Instant occurredAt) {}

public record AiCallFailedEvent(
    UUID callId, UUID userId, UUID traceId,
    TaskType taskType, ModelTier modelTier,
    FailureKind failureKind, String errorExcerpt,
    int retryCount, Instant occurredAt) {}

public record CostBudgetExceededEvent(
    UUID userId, BudgetScope scope,            // userId null for MONTHLY_TOTAL
    BigDecimal capGbp, BigDecimal observedGbp,
    boolean hardBlockEnabled, Instant occurredAt) {}

public enum BudgetScope { DAILY_USER, MONTHLY_TOTAL }
```

`AiCallSucceededEvent` includes successful retries (`status = RETRIED_OK`); `retryCount` distinguishes them. `AiCallFailedEvent` fires on terminal failure after retries are exhausted. The Notification module ([system-overview.md §Notification System](../design/system-overview.md#notification-system)) listens for both, plus `CostBudgetExceededEvent`. `CostBudgetExceededEvent` fires once per scope per breach within a window — debounce is the listener's responsibility.

### Consumed

None. The AI module is invoked synchronously through Java; it has no async work to do in response to other modules' state changes.

---

## Configuration

`AiConfig` (`@ConfigurationProperties(prefix = "mealprep.ai") @Validated`) is a record tree:

```java
public record AiConfig(
    @NotBlank String anthropicApiKey,                          // ${ANTHROPIC_API_KEY}
    @NotNull ModelTier defaultTier,
    @NotNull Map<ModelTier, @NotBlank String> tierToModelId,   // tier → concrete model id
    @NotNull Map<TaskType, ModelTier> taskTypeOverrides,       // per-HLD: TESCO_PRODUCT_MATCH=frontier
    @NotNull Map<TaskType, @Positive Integer> taskTypeTokenCaps,
    @NotNull Map<TaskType, @Positive Integer> taskTypeTimeoutMs,
    @Min(0) @Max(5) int maxRetries,
    @NotNull RetryConfig retry,                                // initial/multiplier/max/jitter
    @NotNull CostConfig cost,                                  // pricing per tier + daily/monthly caps + hardBlock flags
    @NotNull CircuitBreakerConfig circuit                      // threshold (default 5), openWindowMs (default 5min)
) {
    public record CostConfig(
        @NotNull Map<ModelTier, ModelPricing> pricing,         // input / cachedInput / output £/M tokens
        @NotNull BigDecimal dailyCapPerUserGbp,                // default £5.00
        @NotNull BigDecimal monthlyCapTotalGbp,                // default £200.00
        boolean hardBlockOnDailyCap,                            // default false (alert only)
        boolean hardBlockOnMonthlyCap                           // default true
    ) { public record ModelPricing(BigDecimal input, BigDecimal cachedInput, BigDecimal output) {} }
    // RetryConfig, CircuitBreakerConfig elided — straightforward records
}
```

Concrete model ids (`claude-sonnet-4-20261001`, `claude-haiku-3.5-20250301`) live in `application.properties` so they can be updated without code change. `anthropicApiKey` overrides `toString` to redact; the key never reaches a log line. At startup, `AnthropicSdkConfig` computes the fingerprint and calls `ApiKeyRotationDetector.recordIfChanged(fingerprint)` which inserts a row only if the most recent row's fingerprint differs. Resilience4j config (`resilience4j.*` block) is driven from `AiConfig` via a `@Bean` adapter — the HLD pins Resilience4j as the canonical resilience library.

---

## Business Logic Flows

### Flow 1: Dispatch (`AiService.execute`)

**Not `@Transactional`** — the network call lasts seconds; a held DB transaction across it is the wrong shape.

```
1. ModelTierResolver: TaskType → ModelTier + modelId.
2. PromptTemplateService.loadCurrent(ref) → LoadedTemplate (cache hit).
3. Render: PromptTemplateRenderer.render(template, context).
4. Token-cap: count(rendered) + 2x est. output > cap → AiTokenCapExceededException.
5. Cost-cap evaluation:
   - If daily cap reached → publish CostBudgetExceededEvent (soft alert), proceed with the call.
   - If monthly cap reached → publish CostBudgetExceededEvent + throw AiUnavailableException.
     **Calling modules treat this as expected and degrade per their LLD contract** (skip-and-flag,
     defer-and-pending, or block-and-prompt — see [style-guide.md §AI Service — Graceful Degradation]).
     System never bricks; specific AI-only features surface "AI features paused" with a path to
     raise the cap (with friction).
6. Circuit breaker (Resilience4j @CircuitBreaker per task type).
7. ToolUseInvoker.call — Resilience4j @Retry handles transient (timeout/5xx/429) with exp backoff + jitter.
8. StructuredOutputParser.parse:
   a. Extract tool_use; deserialise into Class<T>; run JSR-303 if annotated.
   b. Validation failure → ONE corrective re-prompt (prior assistant message appended); re-parse.
   c. Still failing → AiResponseInvalidException.
9. AiCallRecorder.record(...) in REQUIRES_NEW so a caller-rolled-back parent tx still keeps the ledger row.
10. Publish AiCallSucceededEvent (or AiCallFailedEvent on terminal failure). Return T.
```

Step 5 fires `CostBudgetExceededEvent` regardless of soft/hard outcome — the user is always alerted on a breach. **Soft (daily) = call proceeds. Hard (monthly) = `AiUnavailableException` thrown; calling modules degrade gracefully**, system stays operational. Default monthly cap is conservative (£10/month) to avoid surprise spend; user can raise it from Settings with explicit acknowledgement of projected cost. The step 8b corrective re-prompt counts as one retry against `retry_count`.

### Flow 2: Retry policy

`RetryPolicy` (a small pure class — exhaustive unit tests) classifies failures:

| Failure | Classification | Retried? | Strategy |
|---|---|---|---|
| HTTP 5xx, network timeout | TIMEOUT | yes | exponential backoff + jitter, up to `maxRetries` |
| HTTP 429 | RATE_LIMIT | yes | exponential backoff (longer base), up to `maxRetries` |
| `tool_use` missing/malformed, JSR-303 invalid | SEMANTIC | yes, **once** | corrective re-prompt |
| HTTP 401/403 | AUTH | no | fail fast |
| `content_policy` violation | POLICY | no | fail fast |
| HTTP 4xx other | UNKNOWN | no | fail fast |

Circuit breaker: Resilience4j `@CircuitBreaker(name = "ai-${taskType}")` on `ToolUseInvoker` opens after 5 consecutive failures and stays open for 5 minutes (HLD); open breaker → `AiCircuitOpenException` (503).

### Flow 3: Structured-output parsing

1. Locate the first `tool_use` content block matching `task.getToolSchema().name()`.
2. Extract `input`; Jackson-deserialise into `task.getResponseType()`.
3. If T carries JSR-303 constraints, validate. Failure → semantic retry.

Tool-schema construction from `Class<T>` is via `victools/jsonschema-generator`, cached per `AiTask` instance.

### Flow 4: Cost tracking

Every call writes one row to `ai_call_log`, including failures and aborts. Cost:

```
cost = ((input - cached) * pricing.input / 1e6)
     + (cached         * pricing.cachedInput / 1e6)
     + (output         * pricing.output / 1e6)
```

Pricing per-tier; cached-input rate is the "10% of normal" prompt-cache discount per HLD. £, six-decimal precision. Aggregation is Postgres-side (`aggregateByTaskType`, `aggregateByModelTier`). `CostBudgetGuard.checkAndPublish` runs before every dispatch and on a `@Scheduled(fixedDelay = 5min)` job — catches concurrent slips.

### Flow 5: Prompt template loading

At startup, `PromptTemplateLoader` scans `classpath*:prompts/**/*.txt` (and `*.hbs`, currently empty), SHA-256-hashes each body, derives `name` from path (`prompts/feedback/classify-feedback.txt` → `feedback/classify-feedback`), extracts first comment line as `description`, scans `{{...}}` placeholders. Each file: `INSERT ... ON CONFLICT (name, content_hash) DO NOTHING` — idempotent. The in-memory cache is keyed by `name`. `AiCallRecorder` updates `last_used_at` once per dispatch.

### Flow 6: Stage C context shape

The rule "LLM sees N candidates + rollups, never the underlying pool" ([optimisation-loop.md §LLM context shape](../design/optimisation-loop.md#llm-context-shape)) is a calling-module concern. The AI module makes it impossible to accidentally violate by setting per-task token caps tight enough that "shove the pool in too" trips the cap check (Flow 1 step 4) before reaching the API. Cap values from the HLD's per-task table land in `AiConfig.taskTypeTokenCaps`.

### Flow 7: API key rotation

At startup, `ApiKeyRotationDetector` (`@PostConstruct`): load key → compute fingerprint (`last4 + ":" + sha256`) → read `findFirstByOrderByRotatedAtDesc()` → if no row or fingerprint differs, INSERT new row with `rotated_by = "startup-detect"`. The fingerprint stays in memory for the process. No runtime rotation in v1.

---

## Concurrency and Transactions

| Concern | Decision |
|---|---|
| `AiService.execute` transactional? | **No.** External call; transactions live around DB writes only. |
| Cost ledger write | `AiCallRecorder.record` is `@Transactional(propagation = REQUIRES_NEW)` — ledger lands even if caller's tx rolled back. |
| Repository tx | None at repository layer; service methods own their tx. Read-only services tagged `@Transactional(readOnly = true)`. |
| Optimistic locking | None — all module tables are append-only or single-column-update. |
| Rate-limit / circuit | Resilience4j `@RateLimiter` + `@CircuitBreaker` on `ToolUseInvoker.call`. |
| Single-flight | None at this layer; idempotency for double-clicks is the planner's concern. |
| Concurrent calls per user | Allowed. Cost-cap is read-then-decide with a small race window. Daily soft cap may fire the event slightly after the breach; monthly hard cap may permit one or two extra calls before `AiUnavailableException` surfaces. Acceptable given graceful-degrade semantics — no calling module breaks if it gets one stale "available" response just before the cap closes. |
| Async event listeners | `@TransactionalEventListener(phase = AFTER_COMMIT)` per style guide. |

The split:

```
Caller starts tx → reads → AiService.execute (no tx) → writes → caller commits
                                  │
                                  └─ AiCallRecorder (REQUIRES_NEW) commits its row independently
```

---

## Observability

Per [technical-architecture.md §Observability](../design/technical-architecture.md#observability): every `AiService.execute` invocation logs at INFO with `traceId`, `userId`, `taskType`, `modelTier`, `latencyMs`, tokens, `costGbp`, `retryCount`, `status`. Full prompt is DEBUG on success, WARN with redaction on failure (durable copy lives in `full_prompt_json` on the FAILED row). MDC keys: `traceId`, `userId`, `taskType`. Resilience4j retry attempts log at DEBUG. `/actuator/health/ai` returns `UP` when the most recent call succeeded within 24h or all breakers are closed.

---

## Test Plan

Unit tests use `@ExtendWith(MockitoExtension.class)`. Integration tests are `*IT.java` with Testcontainers Postgres. Anthropic is **always** mocked — we never run real API calls in CI.

### How AI is mocked in integration tests

`com.example.mealprep.ai.testing.TestAiService` is a `@Profile("test") @Primary` bean implementing `AiService`:

```java
public interface TestAiService extends AiService {
    <T> void register(TaskType taskType, T cannedResponse);
    <T> void registerForRef(PromptRef ref, T cannedResponse);
    void registerFailure(TaskType taskType, FailureKind failureKind);
    void clear();
    List<RecordedCall> recordedCalls();
}
```

Tests call `register(TaskType.FEEDBACK_CLASSIFICATION, new ClassificationResponse(...))` in `@BeforeEach`. The bean writes `AiCallLog` rows with cost = 0 by default — per [technical-architecture.md §Testing](../design/technical-architecture.md#testing), "set a cost cap of £0 in tests to catch accidental real API calls." Other modules' `@SpringBootTest` ITs pick this bean up via the test profile.

### Unit

| Class | Verifies |
|---|---|
| `AiServiceImplTest` | Happy path; soft cost-cap (event fires, call proceeds); hard cost-cap (exception); token-cap; circuit-open; corrective re-prompt; auth/policy fail-fast; retry-then-succeed. Mocks all collaborators. |
| `RetryPolicyTest` | Every (HTTP status, exception class) → expected classification + retry decision. |
| `ModelTierResolverTest` | Default vs override tier; unknown task type rejected at startup. |
| `PromptTemplateLoaderTest` | Classpath scan; SHA-256 stable; placeholder extraction; description extraction. |
| `PromptTemplateRendererTest` | `{{placeholder}}` substitution; missing placeholder throws (strict default); list/record values via Jackson. |
| `StructuredOutputParserTest` | Tool-use extraction; missing block → SEMANTIC; deserialisation; JSR-303 violation → semantic retry. |
| `CostCalculatorTest` | Tier × token combinations vs pricing fixtures; cached-input discount; six-decimal rounding. |
| `CostBudgetGuardTest` | Daily/monthly detection; soft vs hard; debounce; system-wide vs per-user scope. |
| `ApiKeyRotationDetectorTest` | First-boot inserts; same key on rebot is a no-op; rotated key inserts. Asserts no log contains the key (LogCaptor). |
| Mapper tests | MapStruct round-trips; `body` only on detail mapper. |

### Integration

| Class | Verifies |
|---|---|
| `AiServiceIT` | E2E via `TestAiService`: dispatch returns canned T; ledger row persisted; `AiCallSucceededEvent` published once (proves `REQUIRES_NEW`). |
| `AiCostTrackingServiceIT` | Aggregation queries: day/week/month buckets, per-task-type and per-tier breakdowns, filtered/unfiltered. Index hits verified via `EXPLAIN`. |
| `PromptTemplateServiceIT` | Real classpath scan vs test fixtures; cache hit on second load; pinned-hash returns historical row even if file is gone. |
| `CostBudgetGuardIT` | Seed past-cap rows; soft cap publishes event and proceeds; hard cap aborts with `status=ABORTED_CAP`. |
| `ApiKeyRotationLogIT` | Reboot with `@DynamicPropertySource`-changed fingerprint writes one new row; ledger has two rows in order. |
| `CostSummaryControllerIT`, `CallLogControllerIT`, `PromptTemplateControllerIT` | MockMvc admin endpoints: 200/400/401/403/404; ProblemDetail shape; `fullPromptJson` only on FAILED rows. |
| `FlywayMigrationIT` | Boots Postgres, runs all `ai_*` migrations, validates JPA mapping. |
| `AiServiceCircuitBreakerIT` | Five consecutive failures → next call short-circuits; after 5min (injected clock), breaker closes. |

Other modules' ITs assert on `recordedCalls()` ("the planner's frontier task was called once with these context keys") rather than mocking `AiService` per-test with Mockito.

---

## Out of Scope

Deferred deliberately — these belong elsewhere or to a later phase.

- **Prompt content for any specific task.** Every `AiTask` impl in calling modules ships its own prompt under `src/main/resources/prompts/<module>/...`. The `PromptTemplateService` loads them; the content is separate prompt-engineering work owned by the calling module.
- **TaskType→tier mappings beyond what [system-overview.md §AI Model Tiers](../design/system-overview.md#ai-model-tiers) pins.** Per-task overrides land alongside each prompt.
- **Streaming responses.** v1 is request/response only. Streaming for Phase 2 plan-assembly progress is on the roadmap.
- **Multi-turn tool-use orchestration.** v1 does single-turn tool use only.
- **Multi-provider abstraction.** Anthropic only in v1.
- **Frontend / UI concerns.** Cost dashboard, prompt-viewer UI, call-log search UI — frontend LLD.
- **Automated quality evaluation.** v1 ships mechanical metrics only. Golden-set CI job owned by the prompts repo when it lands.
- **Manual API-key rotation through an admin endpoint.** v1 rotates by redeploy.
- **Per-user dispatch rate limits.** v1 is global (Resilience4j keyed by `taskType`). Per-user is a future addition keyed `(userId, taskType)`.
- **Anthropic Message Batches API.** Not v1; would land as a sibling `AiBatchService`.
- **Per-user prompt variants** (A/B testing, per-user tuning). Templates are global today.
- **Extended thinking** (Anthropic reasoning mode). Dispatcher doesn't opt in today; would be one method on `AiTask`.

---

## Decisions where the HLD is silent (worth user review)

1. **`Map<String, Object>` context, not `Map<String, String>`** — the HLD shape doesn't carry the structured rollups [optimisation-loop.md](../design/optimisation-loop.md) needs.
2. **`spi/` subpackage** for the cross-module SPI types. Alternative: `domain/service/`.
3. **`PromptRef` carries an optional pinned hash** for golden-set tests; not in the HLD.
4. **`getResponseType()` on `AiTask<T>`** — needed for Jackson deserialisation; the HLD `ToolDefinition` only carries the JSON schema.
5. **Six-decimal cost precision in £** — covers any plausible per-call cost without aggregate rounding error.
6. **API key fingerprint = `last4 + sha256`** — captures uniqueness without leaking the secret.
7. **Hard-block the monthly cap, soft on the daily.** A single user blowing out their daily cap is annoying; system blowing out its monthly cap is a runaway.
8. **`AiCallRecorder` runs `REQUIRES_NEW`** so the ledger lands even when the caller's outer tx rolls back.
9. **`testing/` subpackage for `TestAiService`** — profile-gated bean inside the production module, close to the contract it implements.
10. **No streaming, no batches, no multi-turn tool use in v1** — see Out of Scope.
