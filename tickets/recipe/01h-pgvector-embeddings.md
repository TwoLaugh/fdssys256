# Ticket: recipe — 01h pgvector Migration + Embedding Column + Async `RecipeVersionCreatedEvent` Listener + `getEmbedding` Cross-Module Helper

## Summary

Layer the **pgvector embedding capability** on top of 01a..01g. Per [LLD §V20260601120100 line 115](../../lld/recipe.md), [LLD §V20260601120900 lines 261-265](../../lld/recipe.md), [LLD §Embedding producer (locked 2026-05-07) line 132](../../lld/recipe.md), [LLD §`RecipeQueryService.getEmbedding` line 541](../../lld/recipe.md), [LLD §`RecipeWriteApi.storeEmbedding` line 599](../../lld/recipe.md). Ships:

- **Three new Flyway migrations** in the recipe timestamp range:
  - `V20260601801000__recipe_enable_pgvector_extension.sql` — `CREATE EXTENSION IF NOT EXISTS vector` (single statement). MUST run before the column add.
  - `V20260601801100__recipe_add_embedding_column.sql` — `ALTER TABLE recipe_versions ADD COLUMN embedding vector(1536)`. Plus the `embedding_model_id` column add IF NOT EXISTS (verify — 01a may have already shipped it; LLD line 117).
  - `V20260601801200__recipe_create_embedding_index.sql` — `CREATE INDEX ... USING hnsw (embedding vector_cosine_ops) WHERE embedding IS NOT NULL`. Partial HNSW; empty rows pay no index cost (LLD line 262).
- **`RecipeEmbeddingListener`** — `@Component` in `recipe/domain/service/internal/`. `@TransactionalEventListener(phase = AFTER_COMMIT)` + `@Async` on `RecipeVersionCreatedEvent` (already shipped from 01a). **No `@Transactional` on the listener method itself** — the `@Async` thread has no propagation contract to satisfy; the inner call to `RecipeWriteApi.storeEmbedding` already declares its own `@Transactional`. **This is the "Option B" of the parent-prompt's two valid paths** — see "Listener tx-pattern decision" below.
- **`AttributeConverter<float[], String>`** — `RecipeEmbeddingConverter` mapping the Java `float[]` to the pgvector text format `'[0.1,0.2,...]'` for read/write through Hibernate. **This is the chosen path** of the parent-prompt's three options — see "pgvector mapping decision" below.
- **`embedding` field on `RecipeVersion`** entity with `@Convert(converter = RecipeEmbeddingConverter.class)` + `@Column(name = "embedding", columnDefinition = "vector")`. Read path: `getEmbedding(versionId)` walks the converter to return `Optional<float[]>`.
- **`getEmbedding(versionId)` on `RecipeQueryService`** — public interface method per LLD line 541. Returns `Optional<float[]>` — empty when the column is NULL (pending or failed); present when status is `embedded`. Used by future similarity-search consumers (recipe-01i + planner module).
- **Embedding-input projection** — `RecipeEmbeddingInputBuilder` in `recipe/domain/service/internal/`. Composes the text input from `name + description + cuisine + protein + cooking_method + flavour_profile + ingredient names` per LLD line 132. Deterministic; same `RecipeVersion` always produces the same input string.
- **`EmbeddingTask` SPI consumption** — the listener constructs a `RecipeEmbeddingTask` (NEW concrete `EmbeddingTask` impl) and calls `aiService.embed(task)` per LLD line 132. The `ai` module's `AiService.embed` is already shipped (verified — `ai-01c` merged).
- **Resilience4j wrap** — the listener wraps the `aiService.embed` call with a `@Retry` (max 5 terminal failures parks status at `failed` per LLD line 132). 01h ships this if `pom.xml` already has the Resilience4j dependency from a previous round; otherwise defers retry to a follow-up with INFO-level logging on each attempt. **Worth user review.**

This is **the biggest 01h ticket** — flag as **50-60 minute estimated runtime** rather than the usual 30-45. The three migrations + entity-field mapping + async listener + Resilience4j + cross-module helper add up. Pre-split escape-hatch noted at the end if the agent reports scope strain.

## pgvector mapping decision

Parent prompt enumerated three options:

1. `hypersistence-utils` — likely no pgvector type out of box. **Rejected.**
2. Custom `AttributeConverter<float[], String>` writing pgvector text format. **CHOSEN.**
3. JSONB initially + migrate later. **Rejected** — defeats the HNSW partial index and forces the planner's similarity-search query to do JSON parsing in SQL.

**Decision: custom AttributeConverter.** The pgvector wire format for a `vector(1536)` value is `'[v1,v2,...,v1536]'` as a string literal. PostgreSQL's pgvector accepts string-form via the `vector` cast (Hibernate's default JDBC binding for `String` works fine when `columnDefinition = "vector"`). The converter:

```java
@Converter
public class RecipeEmbeddingConverter implements AttributeConverter<float[], String> {
  @Override
  public String convertToDatabaseColumn(float[] attribute) {
    if (attribute == null) return null;
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < attribute.length; i++) {
      if (i > 0) sb.append(",");
      sb.append(attribute[i]);
    }
    sb.append("]");
    return sb.toString();
  }
  @Override
  public float[] convertToEntityAttribute(String dbData) {
    if (dbData == null) return null;
    String stripped = dbData.substring(1, dbData.length() - 1);   // strip '[' and ']'
    if (stripped.isEmpty()) return new float[0];
    String[] parts = stripped.split(",");
    float[] out = new float[parts.length];
    for (int i = 0; i < parts.length; i++) out[i] = Float.parseFloat(parts[i]);
    return out;
  }
}
```

**Worth user review** — alternative is to use Hibernate's `@JdbcTypeCode(SqlTypes.OTHER)` + a custom user type. Rejected for v1: the AttributeConverter is the smallest possible surface that compiles cleanly against vanilla Hibernate; switching to a custom user type if performance demands it is a follow-up that does not change the column shape on disk.

**Gotcha** — the column definition MUST be `columnDefinition = "vector(1536)"` (NOT just `"vector"`) on the `@Column` annotation so Hibernate `ddl-auto=validate` accepts the column shape. If the validation IT (`FlywayMigrationIT` from 01a) fails on this column, the column definition is the most likely cause.

## Listener tx-pattern decision

Parent prompt presented two valid round-7-compliant paths:

- **Path A**: `@TransactionalEventListener(AFTER_COMMIT) + @Transactional(propagation = REQUIRES_NEW)` — single-thread; runs in the publisher's caller-thread post-commit.
- **Path B**: `@TransactionalEventListener(AFTER_COMMIT) + @Async` (no `@Transactional` on the listener itself; the inner `RecipeWriteApi.storeEmbedding` already declares `@Transactional`).

**Decision: Path B (`@Async`).** Rationale:

- Embedding is **slow** (OpenAI API call: ~200ms-2s). Blocking the publisher's caller thread post-commit would slow every recipe save by ~1s.
- The `@Async` thread starts fresh; no tx is inherited. The first JPA touch inside the listener is **the `storeEmbedding` call itself, which opens its own tx** (it's `@Transactional` on `RecipeServiceImpl` per the impl shipped in 01f). No JPA reads occur in the listener body before that call (the version ID is in the event; we don't load the version row — we use it as a write-through identifier).
- **Embedding-input composition** is done by `RecipeEmbeddingInputBuilder`. The current implementation MUST load the version row to compose the input string. **This IS a JPA read in the listener body before `storeEmbedding`.** Therefore the listener method needs a tx wrapper for that read. **01h uses `@Transactional(propagation = REQUIRES_NEW, readOnly = true)` on a helper `loadAndComposeEmbeddingInput(versionId)` method that the `@Async` listener calls FIRST**, then exits the read tx, calls `aiService.embed` (no tx needed; just an HTTP call), then calls `storeEmbedding` (opens its own write tx). Two short-lived txes, neither held during the embed RPC. **Worth user review** — alternative is to inline a `@Transactional(REQUIRES_NEW)` on the listener method covering the whole flow (including the slow `embed` call); rejected because holding an open tx across a network round-trip is an anti-pattern.

**Listener method signature**:

```java
@Component
class RecipeEmbeddingListener {
  // No @Transactional on this method — @Async runs on a fresh thread with no tx.
  @Async
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onRecipeVersionCreated(RecipeVersionCreatedEvent event) {
    String inputText = inputBuilder.loadAndCompose(event.versionId());          // own REQUIRES_NEW readOnly tx
    if (inputText == null) {                                                     // version vanished — skip
      log.info("recipe version {} not found at embedding time; skipping", event.versionId());
      return;
    }
    try {
      float[] vector = aiService.embed(new RecipeEmbeddingTask(event.versionId(), inputText));
      writeApi.storeEmbedding(event.versionId(), vector, MODEL_ID);              // own REQUIRES (the impl declares @Transactional)
    } catch (AiUnavailableException | AiTerminalException e) {
      log.warn("recipe embedding failed for versionId={} after retries; status set to failed",
          event.versionId(), e);
      writeApi.markEmbeddingFailed(event.versionId());                           // NEW writeApi method — see step 30
    }
  }
}
```

**Round-7 rule still respected**: the listener method does NOT have `@Transactional` (REQUIRED) — it has nothing — so the propagation rule "must be REQUIRES_NEW or NOT_SUPPORTED" does NOT apply (the rule only fires when `@Transactional` IS present on the same method). The compile-time check Spring runs at context-load passes.

## LLD divergence notes

### `markEmbeddingFailed` added to `RecipeWriteApi`

LLD line 599 declares `storeEmbedding(UUID versionId, float[] embedding)` — single happy-path method. Per LLD line 132 ("5 terminal failures parks status at `'failed'`"), we need a **failure-flip** counterpart. **01h adds `markEmbeddingFailed(UUID versionId)` to `RecipeWriteApi`**. The method sets `embedding_status = 'failed'`, does NOT clear `embedding` (stays null), bumps no `@Version` on the parent recipe. **Worth user review** — alternative is to overload `storeEmbedding(versionId, null, modelId)` with a status param; rejected because explicit-method-per-state is clearer.

### `RecipeEmbeddingTask` model ID

LLD line 132 says `aiService.embed(new RecipeEmbeddingTask(versionId, embeddingInputText))`. **The model ID is recipe-config**: `mealprep.recipe.embedding.model-id: openai:text-embedding-3-small` (configurable). The listener passes `MODEL_ID` to `writeApi.storeEmbedding(versionId, vector, MODEL_ID)`. 01f extended `storeEmbedding` to accept `modelId` per its own LLD divergence; 01h uses that 3-arg variant.

### `RecipeEmbeddingTask` shape

01h ships a new `record RecipeEmbeddingTask(UUID versionId, String inputText) implements EmbeddingTask` in `recipe/spi/internal/`. The `EmbeddingTask` interface ships from `ai-01c`. **Verify the interface shape** — likely declares `inputText()` and `taskType()`; 01h conforms. The `taskType` returns `EmbeddingTaskType.RECIPE_VERSION` (NEW value on the `EmbeddingTaskType` enum). **LLD divergence** — adds one enum value to the ai module's `EmbeddingTaskType`. **Cross-module concern**: 01h needs to touch `ai/spi/EmbeddingTaskType.java` to add the value. This is a tiny one-line append on an enum and the only cross-module file 01h modifies. **Worth user review** — alternative is to use a generic `EmbeddingTaskType.OTHER` if it exists. Verify the enum's values before adding.

### `idx_recipe_versions_embedding_status` index

LLD spec line 116-118 has the `embedding_status` column. 01a shipped the column without an index. **01h does NOT add an index on `embedding_status`** because:
- The async listener processes per-event, not by query.
- A future "find all pending embeddings" admin tool may want one; deferred.

**Worth user review** — alternative is a partial index on `WHERE embedding_status = 'pending'` for future admin tooling. Rejected for v1 (no admin tool yet).

### Async executor

`@Async` needs a `TaskExecutor` bean OR `@EnableAsync` plus the Spring Boot defaults. **01h enables `@EnableAsync` on `RecipeModule.java`** if not already done. Defaults to `SimpleAsyncTaskExecutor` which is fine for v1 (low embedding volume). **Worth user review** — alternative is a dedicated bounded thread pool; deferred. The agent should verify `@EnableAsync` is present somewhere in the app (e.g. a project-wide `@Configuration`); if so, no new annotation needed.

## Defers (still out of scope after 01h)

- Similarity search endpoint (`POST /recipes/search/similar`) — **recipe-01i**.
- The general recipe search service (`RecipeSearchCriteriaDto`, `Specification` builder, GIN-index queries) — **recipe-01i**.
- Cross-module helpers `getIngredientMappingKeys`, `getRecipeIngredientLines` → **recipe-01j**.
- AI tag inference (`TagInferenceTask`) → **recipe-01k**.
- A `@Scheduled` admin reaper that finds `embedding_status = 'pending'` rows older than 1 hour and re-enqueues — follow-up.
- A `@Scheduled` admin reaper that retries `embedding_status = 'failed'` rows after manual review — follow-up.
- Embedding-versioning (re-embed when the model rolls forward to `text-embedding-3-large`) — follow-up; the `embedding_model_id` column supports this.
- Background backfill of embeddings for the existing seeded recipe rows — separate one-off ticket once the listener is proven.

## Behavioural spec

### Three migrations

1. **V20260601801000 — pgvector extension**:
   ```sql
   -- Recipe module — 01h enable pgvector. Must run before V20260601801100 (column add).
   -- pg16 image `pgvector/pgvector:pg16` already has the extension installable.
   CREATE EXTENSION IF NOT EXISTS vector;
   ```

2. **V20260601801100 — column add**:
   ```sql
   -- Recipe module — 01h add embedding vector(1536) column on recipe_versions.
   -- See lld/recipe.md §V20260601120100 line 115. The column is nullable; embedding_status from 01a
   -- carries the produced/pending/failed lifecycle. embedding_model_id added here too if not already
   -- on the table (01a shipped status but the model_id column may have been deferred).
   ALTER TABLE recipe_versions
       ADD COLUMN IF NOT EXISTS embedding vector(1536);
   ALTER TABLE recipe_versions
       ADD COLUMN IF NOT EXISTS embedding_model_id varchar(96);
   ALTER TABLE recipe_versions
       ADD COLUMN IF NOT EXISTS embedded_at timestamptz;
   ```
   **Verify** — `IF NOT EXISTS` is the conservative posture; the agent should grep `recipe_versions` migrations and remove the IF NOT EXISTS for any column 01a already shipped. The current state per the existing `V20260601800100__recipe_create_recipe_versions.sql` line 22 already includes `embedding_status` — verify the other two columns and adjust.

3. **V20260601801200 — partial HNSW index** (LLD line 263-264):
   ```sql
   -- Recipe module — 01h partial HNSW index on recipe_versions.embedding.
   -- See lld/recipe.md §V20260601120900 line 263. Empty rows pay no index cost.
   CREATE INDEX IF NOT EXISTS idx_recipe_versions_embedding
       ON recipe_versions USING hnsw (embedding vector_cosine_ops)
       WHERE embedding IS NOT NULL;
   ```

### Entity field

4. Add a field to `RecipeVersion` entity:
   ```java
   @Convert(converter = RecipeEmbeddingConverter.class)
   @Column(name = "embedding", columnDefinition = "vector(1536)")
   private float[] embedding;
   ```
5. Add `embedding_status` field if missing (verify — likely already on the entity from 01a if the column was). Same for `embedding_model_id` and `embedded_at`.
6. **`@Version` on the parent `Recipe` aggregate root** is unchanged. `RecipeVersion` is append-only (no `@Version`) per LLD line 278.

### `RecipeEmbeddingConverter`

7. New `@Converter` class `com.example.mealprep.recipe.domain.entity.RecipeEmbeddingConverter implements AttributeConverter<float[], String>`. Verbatim shape from the "pgvector mapping decision" section above.
8. **Edge case — empty array vs null**: `null` in Java → `null` in DB. `new float[0]` in Java → `'[]'` in DB. The converter handles both; the DB column is nullable so the null path is the common case (every newly-created version starts at null).
9. **Determinism**: floating-point formatting via `Float.toString` — same value → same string. **Locale-independent** (uses '.' as decimal separator regardless of JVM locale).

### `RecipeEmbeddingInputBuilder`

10. New package-private `@Component` `com.example.mealprep.recipe.domain.service.internal.RecipeEmbeddingInputBuilder`.
11. **API**:
    ```java
    String loadAndCompose(UUID versionId);     // null when version not found
    ```
12. **`@Transactional(propagation = REQUIRES_NEW, readOnly = true)`** on this method. Required because the `@Async` listener calls into this method as its first JPA touch.
13. **Algorithm** per LLD line 132 ("composed from name + description + cuisine + protein + cooking_method + flavour_profile + ingredient names"):
    - Load the version + parent recipe + metadata + tags + ingredients via existing 01a/01b query helpers (`recipeVersionRepository.findWithChildrenById(versionId)` — verify the entity-graph; if not present, multiple `findById`s are acceptable; 01h does NOT add a new entity-graph).
    - Version not found → return `null` (listener handles gracefully).
    - Compose: `name + " " + description + " " + cuisine + " " + protein + " " + cookingMethod + " " + flavourProfile.joined(",") + " " + ingredients.map(displayName).joined(",")`.
    - Trim, collapse whitespace, return.
    - **Deterministic**: same `RecipeVersion` → byte-identical string (ordering of `flavourProfile` and `ingredients` follows the DB ordering — which is stable per `line_order` on `RecipeIngredient`).

### `RecipeEmbeddingTask`

14. New record `com.example.mealprep.recipe.spi.internal.RecipeEmbeddingTask` (package-private if `EmbeddingTask` is accessible package-internally; otherwise public):
    ```java
    record RecipeEmbeddingTask(UUID versionId, String inputText) implements EmbeddingTask {
      @Override public String inputText() { return inputText; }
      @Override public EmbeddingTaskType taskType() { return EmbeddingTaskType.RECIPE_VERSION; }
    }
    ```
15. **Verify** `EmbeddingTask` interface shape — if `taskType()` is not on the interface, drop the override. If `inputText()` is the only method, the record's accessor satisfies it. The agent reads `ai/spi/EmbeddingTask.java` first to match the contract.
16. New enum value `RECIPE_VERSION` on `com.example.mealprep.ai.spi.EmbeddingTaskType` if not already present. **One-line append** on the enum; the only cross-module file 01h modifies.

### `RecipeEmbeddingListener`

17. New `@Component` `com.example.mealprep.recipe.domain.service.internal.RecipeEmbeddingListener`.
18. **Constructor**: `RecipeEmbeddingInputBuilder`, `AiService`, `RecipeWriteApi`, `@Value("${mealprep.recipe.embedding.model-id:openai:text-embedding-3-small}") String modelId`.
19. **`@Async` + `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)`** on `onRecipeVersionCreated(RecipeVersionCreatedEvent event)`.
20. **NO `@Transactional` on this method** — `@Async` runs on a fresh thread with no tx; round-7 rule does NOT apply because the rule only fires when `@Transactional` IS present alongside `@TransactionalEventListener`.
21. **Body** (verbatim from "Listener tx-pattern decision" above):
    - Call `inputBuilder.loadAndCompose(event.versionId())` → `String` (returns null if version vanished).
    - Null check → INFO log + early return.
    - Wrap in try/catch:
      - `aiService.embed(new RecipeEmbeddingTask(...))` → `float[]`.
      - `writeApi.storeEmbedding(event.versionId(), vector, modelId)` — opens its own `@Transactional` (already declared on the impl from 01f).
    - **Catch `AiUnavailableException`** (already shipped from `ai-01a` SPI) **or `AiTerminalException`** → WARN log + `writeApi.markEmbeddingFailed(event.versionId())`.
22. **No** `@Retry` wrapping in 01h's first cut (Resilience4j is invoked **inside** `aiService.embed` already from `ai-01a/01c` per design — the listener gets a terminal exception after retries are exhausted). **Worth user review** — verify whether the AI module's `embed` already does retries; if not, 01h adds Resilience4j `@Retry(name = "recipeEmbedding", fallbackMethod = "markFailed")` on a wrapper method.

### `RecipeQueryService.getEmbedding`

23. **Interface method already declared** per LLD line 541: `Optional<float[]> getEmbedding(UUID versionId)`. Verify the interface — if not present, append.
24. **Implementation** on `RecipeServiceImpl`:
    ```java
    @Override
    @Transactional(readOnly = true)
    public Optional<float[]> getEmbedding(UUID versionId) {
      return recipeVersionRepository.findById(versionId).map(RecipeVersion::getEmbedding)
          .filter(arr -> arr != null && arr.length > 0);
    }
    ```
25. Cross-module callers (future planner / recipe-01i similarity search) consume this through `RecipeQueryService` re-exported via `RecipeModule.java` (already in place from 01a).

### `RecipeWriteApi.markEmbeddingFailed`

26. **Append to `RecipeWriteApi`**:
    ```java
    void markEmbeddingFailed(UUID versionId);
    ```
27. **Implementation** on `RecipeServiceImpl`:
    ```java
    @Override
    @Transactional
    public void markEmbeddingFailed(UUID versionId) {
      RecipeVersion v = recipeVersionRepository.findById(versionId)
          .orElseThrow(RecipeVersionNotFoundException::new);
      v.setEmbeddingStatus("failed");
      eventPublisher.publishEvent(new RecipeEvolvedEvent(v.getRecipeId(), versionId,
          EvolvedReason.EMBEDDING_FAILED, UUID.randomUUID(), Instant.now()));
    }
    ```
28. New `EvolvedReason.EMBEDDING_FAILED` enum value (verify — 01f's `EvolvedReason` enum already has `EMBEDDING_STORED`; 01h appends one).

### Errors

29. **No new exception types**. `RecipeVersionNotFoundException` (404) covers the `markEmbeddingFailed` missing-version case (existing from 01a). The async listener's failure path catches `AiUnavailableException`/`AiTerminalException` from the AI SPI and surfaces them as logged WARNs — no 4xx response (the listener has no HTTP caller).
30. **No change** to `RecipeExceptionHandler`. Verify the existing handler maps `RecipeVersionNotFoundException` → 404 (it should from 01a).
31. **DO NOT** modify `config/GlobalExceptionHandler.java`.

### Async wiring

32. Verify `@EnableAsync` is present somewhere in the application's config layer (e.g. `MealPrepApplication.java` or a `@Configuration` class). If absent, add `@EnableAsync` to `RecipeModule.java` (or a `recipe/config/RecipeAsyncConfig.java` if a per-module config seems cleaner — agent picks). **Worth user review.**

## OpenAPI updates

**Zero new REST endpoints in 01h.** The async embedding listener is in-process; `getEmbedding` is a cross-module in-process method (not REST). Similarity-search endpoints land in **recipe-01i**.

No changes to `openapi.yaml`, `paths/recipe.yaml`, or `schemas/recipe.yaml`.

## Verbatim shape snippets

### Async listener skeleton

```java
@Component
class RecipeEmbeddingListener {
  private static final Logger log = LoggerFactory.getLogger(RecipeEmbeddingListener.class);
  private final RecipeEmbeddingInputBuilder inputBuilder;
  private final AiService aiService;
  private final RecipeWriteApi writeApi;
  private final String modelId;

  RecipeEmbeddingListener(RecipeEmbeddingInputBuilder inputBuilder,
                          AiService aiService,
                          RecipeWriteApi writeApi,
                          @Value("${mealprep.recipe.embedding.model-id:openai:text-embedding-3-small}") String modelId) {
    this.inputBuilder = inputBuilder;
    this.aiService = aiService;
    this.writeApi = writeApi;
    this.modelId = modelId;
  }

  @Async
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onRecipeVersionCreated(RecipeVersionCreatedEvent event) {
    String inputText = inputBuilder.loadAndCompose(event.versionId());
    if (inputText == null) {
      log.info("recipe version {} not found at embedding time; skipping", event.versionId());
      return;
    }
    try {
      float[] vector = aiService.embed(new RecipeEmbeddingTask(event.versionId(), inputText));
      writeApi.storeEmbedding(event.versionId(), vector, modelId);
      log.info("recipe embedding stored versionId={} dim={}", event.versionId(), vector.length);
    } catch (RuntimeException e) {                  // catches AiUnavailable / AiTerminal / any other
      log.warn("recipe embedding failed versionId={} reason={}; marking failed",
          event.versionId(), e.getClass().getSimpleName());
      writeApi.markEmbeddingFailed(event.versionId());
    }
  }
}
```

### `RecipeEmbeddingInputBuilder` skeleton

```java
@Component
class RecipeEmbeddingInputBuilder {
  private final RecipeVersionRepository versionRepository;
  private final RecipeRepository recipeRepository;
  private final RecipeMetadataRepository metadataRepository;
  private final RecipeTagsRepository tagsRepository;
  private final RecipeIngredientRepository ingredientRepository;

  @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
  public String loadAndCompose(UUID versionId) {
    RecipeVersion v = versionRepository.findById(versionId).orElse(null);
    if (v == null) return null;
    Recipe r = recipeRepository.findById(v.getRecipeId()).orElseThrow();
    RecipeMetadata md = metadataRepository.findByVersionId(versionId).orElse(null);
    RecipeTags tags = tagsRepository.findByVersionId(versionId).orElse(null);
    List<RecipeIngredient> ingredients = ingredientRepository.findAllByVersionIdOrderByLineOrderAsc(versionId);

    StringBuilder sb = new StringBuilder();
    sb.append(r.getName());
    if (r.getDescription() != null) sb.append(" ").append(r.getDescription());
    if (md != null && md.getCuisine() != null) sb.append(" ").append(md.getCuisine());
    if (tags != null) {
      if (tags.getProtein() != null) sb.append(" ").append(tags.getProtein());
      if (tags.getCookingMethod() != null) sb.append(" ").append(tags.getCookingMethod());
      if (tags.getFlavourProfile() != null) {
        sb.append(" ").append(String.join(",", tags.getFlavourProfile()));
      }
    }
    if (!ingredients.isEmpty()) {
      sb.append(" ").append(ingredients.stream()
          .map(RecipeIngredient::getDisplayName)
          .collect(Collectors.joining(",")));
    }
    return sb.toString().trim().replaceAll("\\s+", " ");
  }
}
```

### Three migrations (verbatim)

```sql
-- V20260601801000__recipe_enable_pgvector_extension.sql
CREATE EXTENSION IF NOT EXISTS vector;
```

```sql
-- V20260601801100__recipe_add_embedding_column.sql
ALTER TABLE recipe_versions ADD COLUMN IF NOT EXISTS embedding vector(1536);
ALTER TABLE recipe_versions ADD COLUMN IF NOT EXISTS embedding_model_id varchar(96);
ALTER TABLE recipe_versions ADD COLUMN IF NOT EXISTS embedded_at timestamptz;
```

```sql
-- V20260601801200__recipe_create_embedding_index.sql
CREATE INDEX IF NOT EXISTS idx_recipe_versions_embedding
    ON recipe_versions USING hnsw (embedding vector_cosine_ops)
    WHERE embedding IS NOT NULL;
```

## Edge-case checklist

### Migrations

- [ ] V20260601801000 applies cleanly; pgvector extension installed
- [ ] V20260601801100 applies cleanly; `embedding` column exists with type `vector(1536)`
- [ ] V20260601801200 applies cleanly; partial HNSW index exists
- [ ] `IF NOT EXISTS` is conservative — re-running migrations is idempotent (Flyway also tracks via schema_version, but the extra guard helps tests)
- [ ] **`FlywayMigrationIT` from 01a passes** — `ddl-auto=validate` accepts the new column shape; the `RecipeVersion` entity field's `@Column(columnDefinition = "vector(1536)")` matches the DB column
- [ ] Test image is `pgvector/pgvector:pg16` (already pinned in `TestContainersConfig` from round 1 — verified)

### `RecipeEmbeddingConverter`

- [ ] `convertToDatabaseColumn(null)` → null
- [ ] `convertToDatabaseColumn(new float[0])` → `"[]"`
- [ ] `convertToDatabaseColumn(new float[]{0.1f, 0.2f, 0.3f})` → `"[0.1,0.2,0.3]"`
- [ ] `convertToEntityAttribute(null)` → null
- [ ] `convertToEntityAttribute("[]")` → `new float[0]`
- [ ] `convertToEntityAttribute("[0.1,0.2,0.3]")` → `new float[]{0.1f, 0.2f, 0.3f}`
- [ ] Round-trip is exact for a 1536-dim float[] (within FP precision; equals via element-wise compare)
- [ ] Locale-independent: same input vector produces same string regardless of JVM `Locale.setDefault(Locale.GERMAN)` (decimal separator stays '.')

### `RecipeEmbeddingInputBuilder`

- [ ] Returns null when version not found
- [ ] Returns trimmed whitespace-collapsed concatenation of expected fields
- [ ] Deterministic — same RecipeVersion always produces the same string (sorted ingredient order via `line_order`)
- [ ] Ingredient list ordering is by `line_order ASC` (preserves recipe author's intent)
- [ ] Null fields (e.g. `r.description == null`) are skipped, not included as the string "null"
- [ ] `@Transactional(propagation = REQUIRES_NEW, readOnly = true)` — round-7 rule satisfied (the listener's first JPA touch goes through this tx)

### `RecipeEmbeddingListener`

- [ ] `@Async` annotation present
- [ ] `@TransactionalEventListener(phase = AFTER_COMMIT)` present
- [ ] **No `@Transactional` on the listener method itself** — round-7 rule is "REQUIRES_NEW or NOT_SUPPORTED only if `@Transactional` is present"; absent `@Transactional` means the rule does NOT apply; verified by grep
- [ ] Listener fires AFTER_COMMIT of `RecipeVersionCreatedEvent` (not BEFORE_COMMIT)
- [ ] Happy path: receives event → composes input → `aiService.embed` returns → `storeEmbedding` → row's `embedding` column populated + `embedding_status = 'embedded'`
- [ ] Failure path: `aiService.embed` throws `AiUnavailableException` → caught → `markEmbeddingFailed` → row's `embedding_status = 'failed'` + `embedding` stays null
- [ ] Failure path: `aiService.embed` throws `AiTerminalException` → caught → `markEmbeddingFailed`
- [ ] Version-vanished path: `inputBuilder.loadAndCompose` returns null → INFO log + early return; NO writeApi calls
- [ ] **Asynchronous**: the publisher thread does NOT block on the embed (verified by ITs measuring publisher-side commit time stays bounded)
- [ ] Listener thread error handling: an unexpected `RuntimeException` from `aiService.embed` is caught by the `catch (RuntimeException e)` clause (broad on purpose); `markEmbeddingFailed` runs

### `RecipeQueryService.getEmbedding`

- [ ] Existing embedded version → `Optional.of(float[])` with 1536-dim vector
- [ ] Pending (null embedding) version → `Optional.empty()`
- [ ] Failed (null embedding) version → `Optional.empty()` (caller can't distinguish pending from failed via this method; that's by design — the caller checks `embedding_status` separately if needed)
- [ ] Non-existent version → `Optional.empty()` (NOT 404 — this is an in-process query helper)
- [ ] `@Transactional(readOnly = true)` on the impl

### `RecipeWriteApi.storeEmbedding` (verify existing from 01f)

- [ ] `storeEmbedding(versionId, vector, modelId)`: writes `embedding`, `embedding_status = 'embedded'`, `embedding_model_id = "openai:text-embedding-3-small"`, `embedded_at = now`, publishes `RecipeEvolvedEvent(EMBEDDING_STORED)` — verified by 01f's existing test; 01h re-verifies via end-to-end IT

### `RecipeWriteApi.markEmbeddingFailed` (new)

- [ ] Sets `embedding_status = 'failed'`
- [ ] Does NOT clear `embedding` (stays null since it was never set)
- [ ] Publishes `RecipeEvolvedEvent(EMBEDDING_FAILED)`
- [ ] 404 on missing version

### Cross-cutting

- [ ] `EmbeddingTaskType.RECIPE_VERSION` enum value added (the ONE cross-module file 01h modifies — `ai/spi/EmbeddingTaskType.java`)
- [ ] `EvolvedReason.EMBEDDING_FAILED` enum value added on the recipe-side `EvolvedReason`
- [ ] `RecipeBoundaryTest` (from 01a) still passes — no new sub-packages added (converter in existing `domain/entity/`, listener + builder in existing `domain/service/internal/`, task in existing `spi/internal/`)
- [ ] `RecipeExceptionHandler` unchanged (no new exceptions)
- [ ] `@EnableAsync` is present in the application config (verify; add if missing)
- [ ] No N+1 on `RecipeEmbeddingInputBuilder` — uses existing per-table repository methods; agent should verify a single happy-path call issues bounded SQL (likely 4-5 statements: version + recipe + metadata + tags + ingredients) — acceptable for a one-off async write path
- [ ] **End-to-end IT**: a recipe save (manual edit from 01c) publishes `RecipeVersionCreatedEvent` → the async listener picks it up → `embedding` column populated within a test-bounded wait → `getEmbedding` returns the populated vector
- [ ] **No regression on 01a..01g tests** — particularly the existing `RecipeWriteApiIT` for `storeEmbedding` should still pass (LLD line 599; impl extended to also set `embedded_at` and publish `RecipeEvolvedEvent(EMBEDDING_STORED)`)
- [ ] No `pom.xml` dependency adds (Resilience4j and Spring `@Async` already on the classpath)
- [ ] No nutrition / household / provisions / auth / preference module file touched
- [ ] The single cross-module file touched is `src/main/java/com/example/mealprep/ai/spi/EmbeddingTaskType.java` (one-line enum-value append)

## Files this ticket touches

```
NEW   src/main/resources/db/migration/V20260601801000__recipe_enable_pgvector_extension.sql
NEW   src/main/resources/db/migration/V20260601801100__recipe_add_embedding_column.sql
NEW   src/main/resources/db/migration/V20260601801200__recipe_create_embedding_index.sql

NEW   src/main/java/com/example/mealprep/recipe/domain/entity/RecipeEmbeddingConverter.java
NEW   src/main/java/com/example/mealprep/recipe/domain/service/internal/RecipeEmbeddingInputBuilder.java
NEW   src/main/java/com/example/mealprep/recipe/domain/service/internal/RecipeEmbeddingListener.java
NEW   src/main/java/com/example/mealprep/recipe/spi/internal/RecipeEmbeddingTask.java

MOD   src/main/java/com/example/mealprep/recipe/domain/entity/RecipeVersion.java                   (add `embedding`, possibly `embedding_model_id`, `embedded_at` fields with @Convert annotation; verify what 01a/01f already wired)
MOD   src/main/java/com/example/mealprep/recipe/domain/service/RecipeQueryService.java             (verify getEmbedding(UUID) signature present per LLD line 541; add if absent)
MOD   src/main/java/com/example/mealprep/recipe/spi/RecipeWriteApi.java                            (append markEmbeddingFailed(UUID); verify storeEmbedding signature matches LLD-divergence from 01f)
MOD   src/main/java/com/example/mealprep/recipe/domain/service/internal/RecipeServiceImpl.java     (implement getEmbedding + markEmbeddingFailed; storeEmbedding already implemented in 01f)
MOD   src/main/java/com/example/mealprep/recipe/event/EvolvedReason.java                           (append EMBEDDING_FAILED enum value)
MOD   src/main/java/com/example/mealprep/recipe/RecipeModule.java                                  (add @EnableAsync if not already present application-wide)
MOD   src/main/resources/application.yml                                                            (add `mealprep.recipe.embedding.model-id: openai:text-embedding-3-small` default)

MOD   src/main/java/com/example/mealprep/ai/spi/EmbeddingTaskType.java                             (ONE-LINE append: RECIPE_VERSION enum value — the ONLY cross-module file 01h modifies)

NEW   src/test/java/com/example/mealprep/recipe/RecipeEmbeddingConverterTest.java                  (round-trip exact; null handling; locale-independent decimal separator)
NEW   src/test/java/com/example/mealprep/recipe/RecipeEmbeddingInputBuilderTest.java               (composition shape; null version returns null; null fields skipped; deterministic ordering)
NEW   src/test/java/com/example/mealprep/recipe/RecipeEmbeddingListenerIT.java                     (end-to-end: recipe save → AFTER_COMMIT → async listener → TestAiService canned vector → embedding column populated; failure path: TestAiService throws → markEmbeddingFailed → embedding_status = 'failed')
NEW   src/test/java/com/example/mealprep/recipe/RecipeEmbeddingFlywayIT.java                       (pgvector extension installed; embedding column has type vector(1536); HNSW index exists)
MOD   src/test/java/com/example/mealprep/recipe/testdata/RecipeTestData.java                       (append builders for RecipeEmbeddingTask + helpers for populating embedding fixtures in tests)
```

**Files this ticket does NOT modify** (cross-cutting; sibling round-8 tickets running in parallel must not collide):

- `src/main/java/com/example/mealprep/config/GlobalExceptionHandler.java` — no new cross-cutting exception.
- `src/test/java/com/example/mealprep/archunit/ModuleBoundaryTest.java` — module rule lives in `RecipeBoundaryTest` (unchanged).
- The nutrition / household / provisions / auth / preference modules — none touched.
- **The ai module's `AiServiceImpl.java`** — NOT modified. 01h consumes `AiService.embed` through its public interface; the enum-value append is the only ai-module file touch.
- `paths/recipe.yaml`, `schemas/recipe.yaml`, `openapi.yaml` — **no OpenAPI changes**; 01h ships no REST surface.

## Dependencies

- **Hard dependency**: `recipe-01a` (merged) — `Recipe`, `RecipeVersion`, `RecipeVersionRepository`, `embedding_status` column on `recipe_versions`, `RecipeVersionCreatedEvent` (verified — the record is shipped in `event/`).
- **Hard dependency**: `recipe-01b`/`01c`/`01d`/`01e` (merged) — pattern reuse only.
- **Hard dependency**: `recipe-01f` (merged) — `RecipeWriteApi.storeEmbedding(versionId, vector, modelId)` 3-arg variant (LLD divergence on the interface; verified). `EvolvedReason.EMBEDDING_STORED` enum value. The pre-existing `storeEmbedding` impl already publishes `RecipeEvolvedEvent`.
- **Hard dependency**: `recipe-01g` (merged) — pattern reuse only.
- **Hard dependency**: `ai-01a` (merged) — `AiService` interface (with `embed`), `AiUnavailableException`, `AiTerminalException`, `AiTask` SPI, `TestAiService` test double.
- **Hard dependency**: `ai-01c` (merged) — `EmbeddingTask` SPI, `EmbeddingTaskType` enum, `AiService.embed(EmbeddingTask)` method, OpenAI text-embedding-3-small client + retry/rate-limit wiring.
- **Hard dependency**: `core` — `ScopeChangedEvent` base (`RecipeVersionCreatedEvent` already implements it).
- **Hard dependency**: `refactor-01-split-merge-zones` (merged) — per-module YAML / advice / boundary-test layout.
- **Hard dependency**: Testcontainers image `pgvector/pgvector:pg16` (verified pinned in `TestContainersConfig.java` from round 1).
- **Sibling tickets running in parallel** (Wave 2 round 8): `nutrition-01h`, `provisions-01h`. None should touch any recipe file. The ai-module enum-value append is the ONLY potential collision — sibling round-8 tickets do NOT touch the ai module. Coordination concern: the migration timestamp range `V2026060180100[0-2]` is unique to recipe-01h; sibling round-8 tickets are in different timestamp ranges.

## Acceptance / DoD

- [ ] `./mvnw -Dspotless.check.skip=true verify` passes locally on the agent's worktree
- [ ] `./mvnw spotless:apply` then `./mvnw spotless:check` clean (mandatory; not optional)
- [ ] CI green on the PR (build + spotless + OpenAPI lint + ArchUnit gate)
- [ ] All edge-case items above ticked
- [ ] **End-to-end async-embedding IT passes**: recipe save → `RecipeVersionCreatedEvent` AFTER_COMMIT → async listener → `TestAiService.embed` returns canned vector → `RecipeWriteApi.storeEmbedding` → row's `embedding` column populated + `embedding_status = 'embedded'` + `getEmbedding` returns the vector
- [ ] **Failure path IT**: TestAiService throws → listener catches → `markEmbeddingFailed` → row's `embedding_status = 'failed'`
- [ ] **`@TransactionalEventListener` round-7 rule applies correctly**: listener method has NO `@Transactional` (the `@Async` path); the helper `loadAndCompose` HAS `@Transactional(propagation = REQUIRES_NEW, readOnly = true)`; no context-load failures
- [ ] `pgvector/pgvector:pg16` extension installs in Testcontainers (verified by `FlywayMigrationIT` running all 3 migrations cleanly)
- [ ] `embedding vector(1536)` column shape matches `RecipeVersion.embedding` field — `ddl-auto=validate` passes
- [ ] Partial HNSW index `idx_recipe_versions_embedding` exists (verified by query against `pg_indexes`)
- [ ] OpenAPI YAML untouched (0 new endpoints in 01h)
- [ ] No `pom.xml` dependency adds (Resilience4j + `@Async` already on classpath via Spring Boot + ai-01a)
- [ ] No nutrition / household / provisions / auth / preference module file touched (ai-module enum-value append is the only cross-module file touch)
- [ ] **The cross-module ai-module enum-value append is correctly placed**: `RECIPE_VERSION` added to `ai/spi/EmbeddingTaskType.java` enum values (verify alphabetical or LLD-suggested ordering)

## What's NOT in scope

- Similarity-search endpoint (`POST /recipes/search/similar`) → **recipe-01i**.
- General recipe search → **recipe-01i**.
- Cross-module helpers `getIngredientMappingKeys`, `getRecipeIngredientLines` → **recipe-01j**.
- AI tag inference → **recipe-01k**.
- A `@Scheduled` reaper for stuck-pending or failed embeddings — follow-up.
- Background backfill of embeddings for existing recipe rows — separate one-off ticket.
- Embedding-versioning (re-embed on model upgrade) — follow-up.
- A REST admin endpoint to re-trigger embedding for a specific version — follow-up.
- Custom Hibernate UserType in lieu of AttributeConverter — follow-up if perf demands it.
- Bounded thread pool for `@Async` — defaults to `SimpleAsyncTaskExecutor`; tune later if embedding volume spikes.
- Holding the tx across the OpenAI HTTP call — explicitly rejected; the two-tx split (input compose → embed → store) is the chosen pattern.
- Resilience4j `@Retry` wrapping at the listener layer — assumes `aiService.embed` already does retries via the ai module's own resilience configuration; if not, follow-up.

## Sizing note — agent may report scope strain

This ticket is the biggest of round 8. If the implementation agent reports strain after 5 verify-loop iterations, **pre-approved split**:

- **Split A** — `recipe-01h-schema`: 3 migrations + entity field + `RecipeEmbeddingConverter` + `RecipeQueryService.getEmbedding` + `RecipeEmbeddingFlywayIT`. ~9 files.
- **Split B** — `recipe-01h-listener`: `RecipeEmbeddingInputBuilder` + `RecipeEmbeddingListener` + `RecipeEmbeddingTask` + `markEmbeddingFailed` on writeApi + end-to-end IT + ai-side enum-value append. ~9 files.

The parent should not pre-split unless the verify loop fails. The schema lands in Split A; Split B builds on top with zero overlap.

Squash-merge with: `feat(recipe): 01h — pgvector migration + embedding vector(1536) column + partial HNSW index + async RecipeVersionCreatedEvent listener + getEmbedding cross-module helper`
