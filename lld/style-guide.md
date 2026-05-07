# LLD Style Guide — Backend

*Conventions every backend LLD doc must follow. Backend (Java + Spring + Postgres) only — frontend conventions come later.*

## Scope

This guide defines the conventions for backend LLD work. Every module's LLD doc must respect these. The audience is the human editor and the agents writing LLD docs — both should produce work that looks like it came from one team.

**In scope:** Java 17 + Spring Boot 3.2 conventions, Postgres + JPA + Flyway, REST API shape, error handling, validation, events, testing, configuration, observability, OpenAPI.

**Out of scope:** Frontend conventions (deferred until UI/UX phase), mobile, WebSocket/SSE, browser automation specifics (the Tesco grocery integration is a separate spec).

This guide does **not** repeat what's already in [technical-architecture.md](../design/technical-architecture.md) — it builds on it. Where they overlap, technical-architecture is the authoritative source.

---

## Tech Stack — Locked

| Concern | Choice | Notes |
|---|---|---|
| Language | Java 17 | Records, sealed types, pattern matching available |
| Framework | Spring Boot 3.2.5 | (already in pom.xml) |
| ORM | Spring Data JPA + Hibernate | (already in pom.xml) |
| Database | PostgreSQL | (already in pom.xml) |
| Migrations | Flyway | `flyway-core` + `flyway-database-postgresql` |
| Boilerplate reduction | Lombok | `@Getter`, `@Setter`, `@Builder`, `@RequiredArgsConstructor` |
| Mappers | MapStruct | `mapstruct` + `mapstruct-processor` (annotation processor) |
| API documentation | springdoc-openapi | `springdoc-openapi-starter-webmvc-ui` |
| Validation | Jakarta Bean Validation | bundled with `spring-boot-starter-web` |
| Testing — unit | JUnit 5 + AssertJ + Mockito | bundled with `spring-boot-starter-test` |
| Testing — integration | Testcontainers (Postgres) | `testcontainers`, `junit-jupiter`, `postgresql` |
| Logging | SLF4J + Logback | Spring Boot default |
| HTTP error format | RFC 9457 ProblemDetail | Spring 6 native |

The H2 dependency in `pom.xml` should be **removed** — Testcontainers is the integration test database.

## Module Package Structure

Root package: **`com.example.mealprep`**.

Per-module subpackages (the canonical layout every module follows):

```
com.example.mealprep.<module>/
├── <ModuleName>Module.java         facade re-exporting public service interfaces
├── api/
│   ├── controller/                 REST controllers (@RestController). Split per resource where modules have multiple sub-resources.
│   ├── dto/                        request/response records
│   └── mapper/                     entity ↔ DTO mappers (MapStruct)
├── domain/
│   ├── entity/                     JPA @Entity classes
│   ├── repository/                 Spring Data repositories (package-private to module)
│   └── service/
│       ├── <Module>QueryService.java     public interface
│       ├── <Module>UpdateService.java    public interface
│       ├── <Module>ServiceImpl.java      single impl of both
│       └── internal/                     module-internal helpers / strategies (package-private)
├── event/                          ApplicationEvent classes published or consumed
├── exception/                      module-specific business exceptions
├── validation/                     custom validators
└── config/                         module-scoped @Configuration classes (rare)
```

`api/` is the outward-facing layer (HTTP). `domain/` is the inward-facing layer (business + persistence). Other modules can inject `domain/service/` interfaces but never `domain/repository/` directly — repositories are package-private (no `public` modifier on the interface).

**Module facade — standard.** Every module has a `<ModuleName>Module.java` at the module root that re-exports its public service interfaces. Cross-module wiring becomes one import + injection, the public surface is grep-able from one file, and refactors that move services around don't ripple through callers.

**Internal helpers — `domain/service/internal/`.** Module-internal strategies, validators, document mutators, and similar plumbing live in a `domain/service/internal/` subpackage. These are package-private and never exposed across the module boundary. Examples: a delta applier inside the preference module, a beam-search helper inside the planner module, a USDA-mapping cache inside the nutrition module. Don't pollute `domain/service/` with internals — keep that subpackage to the public-facing service interfaces and their impl.

**`core` module — shared primitives.** A `com.example.mealprep.core` module hosts cross-cutting types and infrastructure that don't fit any single domain module. Strict scope:

- `core.types` — shared enums (`SlotKind`, `MealKind`, `DataQuality`) and value objects (e.g. `IngredientMappingKey` if it grows beyond a `String`)
- `core.events` — sealed marker interfaces for the event hierarchy (`MealPrepEvent`, `ScopeChangedEvent`); module-specific event classes still live in their own modules but extend these
- `core.audit` — the shared `decision_log` table, `DecisionLogService`, and trace-id propagation helpers (per [optimisation-loop.md](../design/optimisation-loop.md#decision-log))
- `core.lock` — `LockService` for single-flight scope locks (Postgres advisory locks)

**`core` does NOT contain business logic.** No domain entities, no repositories that touch domain tables, no AI calls. If you find yourself wanting to put domain code in `core`, it belongs in a domain module instead.

`core` is depended on by every other module; `core` itself depends only on Spring, Hibernate, and the JDK.

---

## Naming

| Element | Convention | Example |
|---|---|---|
| Java packages | lowercase, dotted | `com.example.mealprep.preference.domain.service` |
| Entity class | PascalCase singular noun | `Recipe`, `MealSlot`, `TasteProfile` |
| Repository | `<Entity>Repository` | `RecipeRepository` |
| Service interface | `<Module>QueryService`, `<Module>UpdateService` | `RecipeQueryService` |
| Service impl | `<Module>ServiceImpl` (one impl, both interfaces) | `RecipeServiceImpl` |
| DTO | `<Domain>Dto`, request/response `<Action><Domain>Request/Response` | `RecipeDto`, `CreateRecipeRequest` |
| Mapper | `<Entity>Mapper` | `RecipeMapper` |
| Controller | `<Resource>Controller` | `RecipesController` (URL is the source of truth for plurality) |
| Event class | `<Subject><Verb>Event` | `RecipeCreatedEvent`, `ProvisionChangedEvent` |
| Exception | `<Subject><Problem>Exception` | `RecipeNotFoundException`, `BudgetExceededException` |
| DB table | snake_case, plural | `recipes`, `meal_slots`, `taste_profiles` |
| DB column | snake_case | `recipe_id`, `created_at`, `nutrition_json` |
| REST resource path | kebab-case, plural | `/api/v1/recipes`, `/api/v1/meal-slots` |
| Flyway migration | `V<YYYYMMDDhhmmss>__<module>_<description>.sql` | `V20260501120000__preference_create_hard_constraints.sql` |

---

## Identifiers

- **All entity IDs are UUIDs.** Postgres column type `uuid`. Java type `java.util.UUID`.
- **Application-generated, not DB-default.** Generate via `UUID.randomUUID()` in the service layer before persistence. This lets events carry the ID before the transaction commits.
- **No `GenerationType.IDENTITY`** anywhere. Every entity's ID is set by the service layer.
- **Foreign keys are also UUIDs.** Use `@ManyToOne` / `@OneToMany` with explicit `@JoinColumn(name = "<entity>_id")`.
- **Trace IDs and decision IDs** (per [optimisation-loop.md](../design/optimisation-loop.md)) are UUIDs too. Propagate via MDC in logs and via service method args where flow tracing matters.

```java
@Entity
@Table(name = "recipes")
public class Recipe {
    @Id
    private UUID id;
    // ...
}
```

---

## DTOs and Entities

Per [technical-architecture.md](../design/technical-architecture.md): **entities never cross module boundaries.** Every cross-module data transfer is a DTO.

### DTOs

- **Java records.** No setters, no Lombok needed.
- **Public, immutable.** Constructed via canonical constructor or via builder if many fields.
- **No JPA annotations on records.** DTOs are pure data carriers.

```java
public record RecipeDto(
    UUID id,
    String name,
    int currentVersion,
    String catalogue,
    List<IngredientDto> ingredients,
    RecipeMetadataDto metadata
) {}
```

### Entities

- **Lombok where it helps.** `@Getter`, `@Setter` for state, `@NoArgsConstructor(access = PROTECTED)` to satisfy JPA, `@AllArgsConstructor` + `@Builder` for service-layer construction.
- **No `@Data` on entities** — it generates `equals` and `hashCode` based on all fields, which JPA hates. Use `@EqualsAndHashCode(of = "id")` if needed at all (rarely is).
- **`@Version`** on every mutable entity for optimistic locking — see [Concurrency](#concurrency).
- **Audit columns** (`createdAt`, `updatedAt`) on every entity. Use Spring Data JPA's `@CreatedDate` / `@LastModifiedDate` with `@EnableJpaAuditing` once globally.

```java
@Entity
@Table(name = "recipes")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class Recipe {
    @Id
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Version
    private Long version;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;
}
```

### Mappers

- **MapStruct** for entity ↔ DTO mapping. One mapper interface per entity-DTO pair.
- **`@Mapper(componentModel = "spring")`** so mappers are Spring beans and inject normally.
- **Custom mappings** declared with `@Mapping(target = "...", source = "...")`. Defaults match by name.
- **Nested mappers** referenced via `uses = { ... }`.

```java
@Mapper(componentModel = "spring", uses = { IngredientMapper.class })
public interface RecipeMapper {
    RecipeDto toDto(Recipe entity);
    List<RecipeDto> toDtos(List<Recipe> entities);
}
```

---

## Service Interfaces

Per technical-architecture.md, every module exposes:

- **`<Module>QueryService`** — read-only, widely injected. All `get*` / `find*` / `list*` methods.
- **`<Module>UpdateService`** — writes, narrowly injected. Methods named after business intent (`adapt`, `promote`, `archive`), not CRUD verbs (`create`, `update`).

Both are implemented by **one** `<Module>ServiceImpl` class. Splitting into separate impl classes creates coordination headaches.

**Batch methods from day one.** Every query method that takes a single ID also has a batch sibling.

```java
public interface RecipeQueryService {
    Optional<RecipeDto> getById(UUID id);
    List<RecipeDto> getByIds(List<UUID> ids);             // batch sibling
    Page<RecipeDto> search(RecipeSearchCriteria criteria, Pageable pageable);
}

public interface RecipeUpdateService {
    RecipeDto importFromUrl(ImportRecipeRequest request);
    RecipeDto promoteToUserCatalogue(UUID systemRecipeId);
    void archive(UUID recipeId);
}
```

Update methods return the updated DTO when there's anything useful to return; otherwise `void`. Don't return entities — ever.

### Bundle-DTO-for-planner convention

Every domain module the planner queries during plan composition exposes a single bundled read method that returns everything the planner needs in one round trip. The convention:

- **DTO name:** `<Module>ForPlannerBundleDto` — flat record aggregating the fields the planner consumes.
- **Method name:** `getForPlanner(UUID userId)` plus batch sibling `getForPlannerByUserIds(List<UUID> userIds)`.
- **Lives on the module's `QueryService`** — same interface as other reads, not a separate service.
- **Never includes hard-safety data.** Allergies and dietary identity are read separately via `HardConstraintFilterService` so the safety-critical read path stays explicit and uncached.

Confirmed instances: `SoftPreferenceBundleDto` (preference), `ProvisionForPlannerBundleDto` (provisions). To add: `NutritionForPlannerBundleDto` (nutrition), and `MergedSoftPreferencesDto` (household, already exists, conforms).

The point of the convention is to bound the planner's read fan-out: N modules × M reads per slot becomes N bundled reads per planning run.

---

## REST API Conventions

### Versioning

- **All endpoints prefixed `/api/v1/`.** No unversioned endpoints.
- New major versions get `/api/v2/`. Old versions stay until deprecated and removed deliberately.

### Resource paths

- **Plural nouns, kebab-case.** `/api/v1/recipes`, `/api/v1/meal-slots`.
- **Path params for IDs.** `/api/v1/recipes/{recipeId}`.
- **Sub-resources** for owned collections. `/api/v1/recipes/{recipeId}/versions`.
- **Verbs for non-CRUD actions.** `/api/v1/recipes/{id}/promote` (POST). Don't try to RPC-everything as `PUT`.

### HTTP methods and status codes

| Method | Use | Success status |
|---|---|---|
| `GET` | Read single or list | 200 |
| `POST` | Create or non-idempotent action | 201 (create) / 200 (action) |
| `PUT` | Full replacement of an existing resource | 200 |
| `PATCH` | Partial update | 200 |
| `DELETE` | Delete or soft-delete | 204 |

Common error codes: 400 (validation), 401 (unauth), 403 (forbidden), 404 (not found), 409 (conflict — e.g. version mismatch on optimistic locking), 422 (semantic error — e.g. constraint feasibility failure), 500 (unexpected).

### Pagination

- **`page` and `size` query params.** `?page=0&size=20`.
- **Default size 20, max 100.** Enforce in the controller via `@Max(100)`.
- **Return `Page<T>` payload** — Spring Data shape:

```json
{
  "content": [ /* T[] */ ],
  "page": { "number": 0, "size": 20, "totalElements": 142, "totalPages": 8 }
}
```

### Filtering

Per-endpoint, documented in OpenAPI. Prefer specific query params (`?cuisine=East+Asian&maxTimeMin=30`) over generic filter DSLs.

### Request bodies

- **Records as request types.** `CreateRecipeRequest`, `UpdateRecipeRequest`.
- **`@Valid`** on the controller method param. Validation annotations on the record.
- **Never use `Map<String,Object>`** as a request body. Always a typed record.

### Error responses

- **RFC 9457 ProblemDetail.** Spring 6 has `org.springframework.http.ProblemDetail` natively.
- **`Content-Type: application/problem+json`** on error responses.
- **Standard fields:** `type`, `title`, `status`, `detail`, `instance`. Extension fields for module-specific info (`errors[]` for validation, `conflictingResource` for 409s).

```json
{
  "type": "https://mealprep.example.com/problems/recipe-not-found",
  "title": "Recipe not found",
  "status": 404,
  "detail": "No recipe with ID 8c4...",
  "instance": "/api/v1/recipes/8c4..."
}
```

---

## Validation

- **Jakarta Bean Validation** annotations on DTOs and request records.
- **`@Valid`** at the controller method param.
- **Custom validators** in `<module>/validation/` — implement `ConstraintValidator<TAnnotation, TValue>`.
- **Cross-field validation** via class-level constraints (`@AssertTrue` method or custom annotation), not via service-layer if-statements.

Validation failures bubble up to a global handler (next section) and are returned as 400 with a `errors[]` extension on ProblemDetail.

---

## Error Handling

- **Module-specific business exceptions** in `<module>/exception/`. Subclass a single project-wide root: `MealPrepException` (or per-module `<Module>Exception`).
- **Global `@RestControllerAdvice`** at the root package level. Maps each known exception type to a ProblemDetail.
- **Never leak stack traces** to API responses. Map unknown exceptions to a generic 500 ProblemDetail; log the full trace.
- **`MethodArgumentNotValidException`** (validation failures) handled in the global advice; produces 400 with field-level `errors[]`.

```java
@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(RecipeNotFoundException.class)
    public ProblemDetail handleNotFound(RecipeNotFoundException ex) {
        return ProblemDetail.forStatusAndDetail(NOT_FOUND, ex.getMessage());
    }
    // ...
}
```

---

## Embeddings

Embeddings are a separate AI track from completion calls. Different shape, different provider, different cost model.

**v1 model:** OpenAI `text-embedding-3-small` (1536 dims) for v1. Cheap (~$0.02 per million tokens), high quality, well-documented. The abstraction (`EmbeddingTask` in `ai` module) lets us swap to Voyage, Anthropic when they ship embeddings, or a local `sentence-transformers` model later — implementations plug in behind one interface.

**Storage:** `pgvector` extension in PostgreSQL. Add `vector(1536)` columns where needed (`recipe_recipes.embedding`, `preference_taste_profile.taste_vector`). Use `hypersistence-utils-hibernate-63`'s `VectorType` for JPA mapping (already in the pom additions).

**Indexes:** HNSW or IVFFlat index per vector column where similarity search is a hot read path. Recipe similarity ("find recipes like this one") wants HNSW; per-user score-one-recipe-at-a-time queries don't need the index. Document choice per column in the migration's SQL comment.

**Cosine similarity** is the standard distance: pgvector's `<=>` operator. Returns `[0, 2]`; convert to `[0, 1]` similarity via `1 - (distance / 2)`.

**Recompute discipline:**
- **Recipes:** embed on save and on version-bump. The `nutrition_status = pending` defer-and-pending pattern from `style-guide.md §AI Service` applies — save the row immediately, queue the embedding work async.
- **Taste profiles:** embed when the document materially changes. Threshold-based — small delta-updates (e.g. one liked-ingredient added) don't trigger re-embedding; periodic batch (every N feedback events or weekly, whichever comes first) does.

**Cost track separate from completion calls.** Embeddings have their own budget bucket in `AiCostTrackingService`. They don't compete with the £10/month completion cap; default monthly embedding cap is £2 (covers ~10M tokens, far more than any household needs).

**On `AiUnavailable` for embeddings:** the calling code uses the **last successful embedding** if one exists. New recipes / first-time taste profiles without any embedding fall back to a deterministic "no preference signal" score (typically 0.5 — neutral). Both are valid degraded states; the system never blocks on embedding availability.

---

## AI Service — Graceful Degradation

AI is a dependency, not a hard requirement. Modules calling `AiService` must handle its unavailability without bricking the system.

**Two failure modes any AI call may produce:**

1. **Transient failure** (timeout, 5xx, parse failure, rate limit) — `AiService` retries internally per its policy and eventually surfaces a `TransientAiFailureException` if retries exhaust.
2. **`AiUnavailable`** — soft response indicating cost cap reached, API key missing, or service deliberately disabled. Not an exception; a typed return signalling "no AI right now."

**Per-feature degradation contract** — every module that calls AI must spec what happens on `AiUnavailable` in its LLD's failure-modes section. Three permitted patterns:

- **Skip-and-flag** — feature runs without the AI step; result is flagged as degraded in the response (e.g. plan ships without Phase 2 augmentation, marked `aiAugmented: false`).
- **Defer-and-pending** — work is persisted with a status marker (`pending`, `unmapped`, `unparsed`); a scheduled job retries when AI becomes available (e.g. ingredient → USDA mapping, recipe URL extraction with HTML fallback as interim).
- **Block-and-prompt** — feature blocks; user sees "AI features paused" with a path to raise the cap. Reserved for features with no useful non-AI fallback (recipe adaptation, recipe generation, recipe discovery).

**Cost cap behaviour:**
- **Daily cap** — alert via Notification + banner; calls continue.
- **Monthly cap** — `AiService` returns `AiUnavailable` from this point. Calling modules degrade per their contract. User can raise the cap in Settings (with friction — must acknowledge projected spend).

The `AiService` LLD owns the `AiUnavailable` signalling and the cap state machine. Calling modules own their per-feature degradation behaviour.

---

## Events

Per [technical-architecture.md](../design/technical-architecture.md):

- **Spring `ApplicationEvent`.** No external broker.
- **`@TransactionalEventListener(phase = AFTER_COMMIT)`** is the default for listeners.
- **`@Async` + `@TransactionalEventListener`** for async listeners.
- **`@EventListener` (synchronous, in-tx) only when the listener must participate in the publisher's transaction.** Document why.

### Event class shape

- Records.
- Carry IDs, not full entities.
- Carry trace IDs where the event participates in a multi-stage flow.
- Suffix `Event`.

```java
public record RecipeCreatedEvent(
    UUID recipeId,
    String catalogue,
    UUID traceId,
    Instant occurredAt
) {}
```

### Event debouncing / coalescing

Where multiple events of the same type might fire in rapid succession (e.g. several pantry updates from one Tesco order), the listener is responsible for debouncing. Spec the debounce strategy in the listener's LLD.

---

## Database

### Migrations — Flyway

- **All schema changes via Flyway.** No `ddl-auto=update` ever.
- **`spring.jpa.hibernate.ddl-auto=validate`** in production and CI. Hibernate will fail fast if entities and schema diverge.
- **`spring.jpa.hibernate.ddl-auto=none`** in tests using Testcontainers (Flyway runs first; `validate` would be redundant).
- **Migrations live in `src/main/resources/db/migration/`.** Filename: `V<YYYYMMDDhhmmss>__<module>_<description>.sql` (timestamp-based, not per-module sequence — see [technical-architecture.md §Migrations](../design/technical-architecture.md#migrations) for rationale: globally unique versions across modules sharing one DB).
- **Repeatable migrations** for reference-data seeds: `R__<module>_seed_<topic>.sql`.
- **One concern per migration.** Don't bundle "add table + add column to other table + reseed reference data" into one file.
- **Migrations are append-only.** Don't edit a committed migration; add a new one.

### Indexes

- **Explicit and named.** `CREATE INDEX idx_recipes_user_catalogue_name ON recipes (catalogue, name);`.
- **Justified in a SQL comment** above the index in the migration. "Used by RecipeQueryService.search() filter on catalogue + name pattern."
- **Foreign keys are indexed** unless deliberately not. Note when not.

### JSONB

JSONB is appropriate for **read-whole, no-inner-filter, schema-evolves** data. It is not a free upgrade — it trades SQL-level type safety for storage flexibility.

**Use JSONB when:**
- The document is read whole. Filtering or joining on inner fields is rare or absent.
- The shape evolves moderately and you want to add fields without migrations.
- No other table FKs into inner data.
- ~5-30 fields. (More: split into sub-documents or normalise.)

Confirmed JSONB targets in this project: `preference.lifestyle_config`, `preference.taste_profile`, `household.settings`, `notification.preference`, decision-log `inputs` field.

**Use normalised relational when:**
- You filter on the inner data (`WHERE status = 'active'`, `WHERE storage_location = 'freezer'`).
- Other tables FK to it.
- The schema is stable AND fields are queried separately.

Confirmed relational targets: `provision_inventory`, `recipes` (per-recipe scalar fields), `meal_slots`, `nutrition_targets`.

**Required discipline when using JSONB:**
- **Code-side record class** mapped to/from the JSONB column. Java enforces shape; the DB doesn't.
- **`schema_version` field** at the document root. Increment when the record class shape changes in a non-additive way.
- **Round-trip test** — a startup or unit test that loads a fixture document into the record and back to JSON, confirming the shape is preserved. Catches silent field drift.
- **`hypersistence-utils-hibernate-63`** for `@Type(JsonType.class)` mapping. Already in the pom.xml additions.

**GIN index** on the JSONB column when you need to filter on inner fields after all (rare in this project; flag any case in the LLD).

### Soft-delete vs hard-delete

- **Default: hard-delete.** Simpler, no global filter required, no "but is it deleted?" gotcha.
- **Soft-delete only when explicitly justified per table** (e.g. recipe demotion preserves data). Document the decision in that module's LLD.
- **Soft-delete via `deleted_at TIMESTAMP NULL`** column, not a boolean. Captures the when.

---

## Concurrency

### Optimistic locking

- **`@Version`** on every entity that has a state machine or is concurrently editable. JPA increments it; conflicting writes throw `OptimisticLockException` → 409 ProblemDetail.
- **Plan, Recipe, Provisions inventory** all need `@Version`.
- **Reference data** (allergens, units of measure) doesn't.

### Pessimistic locking

- **Discouraged.** If used, document why in the LLD.
- **Postgres advisory locks** (`pg_advisory_xact_lock`) preferred over row-level when it's "single-flight per scope" (per the optimisation loop).

### Single-flight per scope

The optimisation loop requires that one plan generation runs at a time per `(household_id, week_start_date)`. Implement via a Spring-managed `LockService` that wraps `pg_try_advisory_xact_lock(hash(scope))` — a service-layer concern, not infrastructure.

### Transaction boundaries

- **Service methods are the unit of transaction.** `@Transactional` at the service impl method, not the repository.
- **Read methods**: `@Transactional(readOnly = true)`.
- **Write methods**: `@Transactional` (default propagation REQUIRED).
- **Document any non-default propagation** (REQUIRES_NEW etc.) in the LLD with a one-line justification.

---

## Decision Log

The shared decision-log table (per [optimisation-loop.md](../design/optimisation-loop.md)) lives in a shared module — call it `core` — at `com.example.mealprep.core.decisionlog`. Every module that runs an optimisation loop writes to it via `DecisionLogService`. The schema is defined in [optimisation-loop.md](../design/optimisation-loop.md#decision-log) and committed as a Flyway migration in the `core` module.

Trace IDs propagate via:
- **MDC** in logs (`MDC.put("traceId", ...)`).
- **Method args** in cross-module calls (`OptimiserService.adapt(recipeId, traceId)`).

Don't try to thread trace IDs via thread-locals — explicit args are clearer and survive async boundaries.

---

## Configuration

- **Spring profiles:** `dev`, `test`, `prod`. Active profile via `SPRING_PROFILES_ACTIVE` env var.
- **`application.properties`** holds non-secret defaults; profile-specific overrides in `application-<profile>.properties`.
- **Secrets via env vars only.** Never commit secrets. `application.properties` references them via `${ENV_VAR_NAME}`.
- **`@ConfigurationProperties`** for typed config beans. Validation via `@Validated` on the config class.

```java
@ConfigurationProperties(prefix = "mealprep.ai")
@Validated
public record AiConfig(
    @NotEmpty String anthropicApiKey,
    @NotNull AiModelTier defaultTier,
    @Min(1) int maxRetries
) {}
```

```properties
mealprep.ai.anthropic-api-key=${ANTHROPIC_API_KEY}
mealprep.ai.default-tier=mid
mealprep.ai.max-retries=3
```

---

## Logging

- **SLF4J interface, Logback impl.** Spring Boot default.
- **`@Slf4j`** Lombok annotation (or manually `private static final Logger log = LoggerFactory.getLogger(...)` when not using Lombok in that file).
- **Structured logging via MDC** for trace IDs, user IDs, plan IDs.
- **No `e.printStackTrace()`.** Always `log.error("...", e)`.
- **Log levels:**
  - `ERROR` — unrecoverable, needs human attention
  - `WARN` — recoverable but unusual (e.g. retry succeeded, fallback hit)
  - `INFO` — significant state transitions (plan generated, slot completed)
  - `DEBUG` — flow detail useful in dev/staging
  - `TRACE` — fine-grained, off in prod
- **No PII in logs.** User content (taste profile contents, recipe details) at DEBUG only.

---

## Testing

### Unit tests

- **`@ExtendWith(MockitoExtension.class)`** — no Spring context.
- **Pure Mockito** for collaborators.
- **Naming:** `methodName_scenario_expected` — e.g. `archive_systemCatalogueRecipeUnusedFor3Months_marksArchived`.
- **One assertion concept per test** (multiple AssertJ assertions in a single concept are fine).
- **No DB.** If you need a repository, mock it.

### Integration tests

- **Testcontainers + Postgres.** One `@Container` per test class (or shared via static fields for speed).
- **`@SpringBootTest`** with `@AutoConfigureMockMvc` for full HTTP-layer testing, or `@DataJpaTest` for repository-only.
- **Filename suffix `IT.java`** to distinguish from unit tests. Maven Surefire runs `*Test.java`; Failsafe runs `*IT.java` — keeps the fast unit tests on every build, slow ITs on the integration phase.
- **DB cleanup between tests** via `@Sql(scripts = "/cleanup.sql", executionPhase = AFTER_TEST_METHOD)` or `@Transactional` rollback. Pick one strategy per module.
- **Flyway migrations run automatically** at Testcontainers startup — tests run against the same schema as prod.

```java
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class RecipesControllerIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    // tests
}
```

### Coverage

Not a hard target. The right test suite covers business logic, edge cases, and integration paths — not getters and setters. Aim for "would I trust a deploy on green tests alone?" not for a percentage.

---

## OpenAPI / API documentation

- **springdoc-openapi** generates an OpenAPI 3 spec from controllers automatically.
- **Spec exposed at `/v3/api-docs`** (JSON) and `/swagger-ui.html` (UI). Both gated behind a non-prod profile or behind auth in prod.
- **Annotate controllers with `@Operation(summary = "...", description = "...")`** for clarity.
- **Schemas** are inferred from records; override with `@Schema` only when the inference is wrong.
- **Tag controllers with `@Tag(name = "Recipes")`** to group in the UI.

The OpenAPI spec is **not hand-written** — it's generated. If the spec is wrong, the controller is the source of truth to fix.

---

## Out of Scope

The following are deliberately not specified here. They live in their own docs or come in later phases.

- **Frontend / UI conventions** — Figma phase, then frontend LLD.
- **WebSocket / SSE** — not yet needed; revisit if real-time updates become a feature.
- **Authentication / authorization details** — covered by the `auth` module's LLD.
- **Browser automation (Tesco)** — the grocery integration has its own design; not a generic concern.
- **CI / CD** — separate concern, separate doc when the time comes.
- **Infrastructure / hosting** — local-first per the system overview's tech stack; production hosting decisions deferred.
- **Specific LLM prompts** — owned by individual module LLDs and the AI Service LLD; not a global concern.
- **Specific scoring sub-score formulas** — owned by the meal-planner LLD.

---

## Quick Reference — pom.xml additions

**The block below is the verified, build-green dependency set as of 2026-05-07.** Project-setup pilot ran `./mvnw clean test` against this exact set and it compiled + tested green. Subsequent agents and tickets MUST use this set verbatim; do not invent artefact names or versions, and do not add deps from the "anti-list" below without justifying.

### Verified deps (2026-05-07; Spring Boot 3.2.5 / Java 17)

```xml
<!-- Already present from Spring Initializr scaffold:
     spring-boot-starter-web, spring-boot-starter-data-jpa, postgresql, spring-boot-starter-test -->

<!-- Migrations: Spring Boot 3.2.5 ships Flyway 9; Postgres support is in flyway-core.
     ⚠ DO NOT add flyway-database-postgresql here — that artefact is Flyway 10+ only and
     missing from Spring Boot 3.2's BOM. (Bump to Boot 3.3+ first if you want it.) -->
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-core</artifactId>
</dependency>

<!-- Spring Security 6 baseline -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-security</artifactId>
</dependency>

<!-- Validation -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-validation</artifactId>
</dependency>

<!-- Lombok -->
<dependency>
    <groupId>org.projectlombok</groupId>
    <artifactId>lombok</artifactId>
    <optional>true</optional>
</dependency>

<!-- MapStruct (annotation-processor wired in maven-compiler-plugin AFTER Lombok) -->
<dependency>
    <groupId>org.mapstruct</groupId>
    <artifactId>mapstruct</artifactId>
    <version>1.5.5.Final</version>
</dependency>

<!-- OpenAPI doc generation from controllers (production runtime) -->
<dependency>
    <groupId>org.springdoc</groupId>
    <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
    <version>2.5.0</version>
</dependency>

<!-- JSONB mapping for JPA -->
<dependency>
    <groupId>io.hypersistence</groupId>
    <artifactId>hypersistence-utils-hibernate-63</artifactId>
    <version>3.7.5</version>
</dependency>

<!-- pgvector — Postgres vector type for recipe + taste-profile embeddings -->
<dependency>
    <groupId>com.pgvector</groupId>
    <artifactId>pgvector</artifactId>
    <version>0.1.6</version>
</dependency>

<!-- OpenAI client for embeddings (v1 only; abstraction in ai module allows swap) -->
<dependency>
    <groupId>com.openai</groupId>
    <artifactId>openai-java</artifactId>
    <version>0.20.0</version>
</dependency>

<!-- Testcontainers BOM + concrete deps -->
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>junit-jupiter</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>postgresql</artifactId>
    <scope>test</scope>
</dependency>

<!-- @ServiceConnection support for Testcontainers (required for Spring Boot 3.1+) -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-testcontainers</artifactId>
    <scope>test</scope>
</dependency>

<!-- swagger-request-validator for OpenAPI ↔ controller contract testing.
     Two artefacts; both needed:
       - mockmvc: assertion-style matchers (OpenApiValidationMatchers.openApi())
       - springmvc: servlet Filter (OpenApiValidationFilter) for runtime validation in @SpringBootTest
     ⚠ DO NOT use OpenApiValidationInterceptor — it extends Spring 5's deprecated
       HandlerInterceptorAdapter, removed in Spring 6 / Spring Boot 3.x. Use the Filter. -->
<dependency>
    <groupId>com.atlassian.oai</groupId>
    <artifactId>swagger-request-validator-mockmvc</artifactId>
    <version>2.40.0</version>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>com.atlassian.oai</groupId>
    <artifactId>swagger-request-validator-springmvc</artifactId>
    <version>2.40.0</version>
    <scope>test</scope>
</dependency>

<!-- ArchUnit for module-boundary rules (lives as a JUnit5 test) -->
<dependency>
    <groupId>com.tngtech.archunit</groupId>
    <artifactId>archunit-junit5</artifactId>
    <version>1.3.0</version>
    <scope>test</scope>
</dependency>
```

### Anti-list — artefacts that look right but DON'T exist or break the build

These are the bugs caught in the project-setup pilot. Each surfaces compile-time, none surface at design-time. Future tickets must avoid:

| ❌ Don't use | Reason | ✅ Use instead |
|---|---|---|
| `org.flywaydb:flyway-database-postgresql` | Flyway 10+ only; not in Spring Boot 3.2.5's BOM | Just `flyway-core` (bundles Postgres support) |
| `com.atlassian.oai:swagger-request-validator-spring` | Wrong artefact name; doesn't exist | `swagger-request-validator-springmvc` (with `mvc` suffix) |
| `OpenApiValidationInterceptor` (Java import) | Extends deprecated `HandlerInterceptorAdapter` (Spring 5); removed in Spring 6 | `OpenApiValidationFilter` — servlet filter; works fine in Spring 6 |
| `com.h2database:h2` | Diverges from prod Postgres (different SQL, no JSONB) | Testcontainers `postgresql` |
| `spring-boot-starter-test` without `spring-boot-testcontainers` | `@ServiceConnection` doesn't resolve | Add `spring-boot-testcontainers` test-scoped |

### Plugin configuration notes

- **maven-compiler-plugin**: annotation processors in this order: `lombok` → `mapstruct-processor`. Order matters; reverse breaks Lombok-generated getters being visible to MapStruct.
- **failsafe-plugin**: configured to run `*IT.java` separately from `*Test.java` (surefire). Keeps fast unit tests on every build, slow ITs on the integration phase.
- **jacoco-plugin**: 80% line / 70% branch coverage thresholds; `haltOnFailure=false` for the project-setup ticket only (no production code yet to cover); flip to `true` once first feature ticket lands.
- **pitest-plugin**: ≥70% mutation score; `failWhenNoMutations=false` for project-setup ticket only; flip after first feature ticket lands.
- **spotless-plugin**: google-java-format. Run `./mvnw spotless:apply` to auto-format; `:check` in CI fails on unformatted code.

### Pre-flight verification one-liner

Before launching any ticket-implementer agent, run from the project root:

```
./mvnw clean test 2>&1 | grep -E "BUILD|FAIL|Tests run" | tail -10
```

Should end with `BUILD SUCCESS` and a `Tests run: N, Failures: 0, Errors: 0` line. If not, fix the prior state — don't pile bugs on bugs.

### Post-flight verification one-liner

After the agent reports its ticket done, before user review:

```
./mvnw clean verify 2>&1 | tail -40                  # full suite incl. ITs (needs Docker)
./mvnw pitest:mutationCoverage 2>&1 | tail -20       # mutation score
./mvnw spotless:check                                # formatting
npx -y @apidevtools/swagger-cli validate src/main/resources/openapi/openapi.yaml
```

If any fails, send the failure log back to the agent for a follow-up via `SendMessage` (preserves agent context); don't spawn a fresh agent unless the original timed out.
