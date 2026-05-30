# Recipe Module — LLD (Catalogue)

*Implementation specification for the catalogue half of the Recipe System: storage, versioning, branching, substitutions, search, and the write-API the Adaptation Pipeline uses to persist its work. Pure data — no culinary reasoning. Translates the catalogue concern of [recipe-system.md](../design/recipe-system.md) into a buildable Spring Boot module.*

## Scope

This document specifies the `recipe` module's **catalogue** — package layout, JPA entities, Flyway migrations, repositories, service interfaces (including the internal `RecipeWriteApi` the Adaptation Pipeline injects), DTOs, mappers, REST controllers, validation, events, business-logic flows, transaction boundaries, and the test plan. Conventions defer to [lld/style-guide.md](style-guide.md); this LLD restates a rule only when the module-specific application matters.

The HLD ([recipe-system.md](../design/recipe-system.md)) describes the Recipe System as one subsystem with two cleanly separated internal concerns: the **Catalogue** (storage, versioning, branching, substitutions, search — pure data) which is **in scope**, and the **Adaptation Pipeline** (LLM-driven culinary + nutritional intelligence, four trigger sources) which is **out of scope** as a separate hard pocket. The catalogue exposes a clean `RecipeWriteApi` (specified here) that the pipeline injects to save proposed versions, branches, and substitutions. The pipeline's own logic — reasoning, prompt assembly, classification, planner-hint emission, pending-change UX — is the pipeline LLD's concern. Without the WriteApi the pipeline cannot persist its work, so its shape is locked here.

The HLD assigns `Long` to all IDs in its examples; the style guide locks IDs to `UUID`. Style guide wins. **Decision flagged.**

The HLD's three structural shapes — **version** (linear change on a branch, `parent_version_id` walks back to v1), **branch** (fork from a specific version, carrying its own version history and character fingerprint), and **substitution** (constraint-driven overlay on a base version) — map to three storage tables. A substitution is conceptually "a branch-of-substitution" but is stored separately because substitutions outnumber branches ~30× per HLD volumes, the planner attaches them to plan slots rather than the recipe, and they carry their own state (`active`/`inactive`/`promoted`).

---

## Package Layout

```
com.example.mealprep.recipe/
├── RecipeModule.java                      facade re-exporting public service interfaces
├── api/
│   ├── controller/                        RecipesController, RecipeVersionsController, RecipeBranchesController,
│   │                                      RecipeSubstitutionsController, RecipeImportsController, RecipeAdminController
│   ├── dto/                               records (see DTOs section)
│   └── mapper/                            RecipeMapper, RecipeVersionMapper, RecipeBranchMapper,
│                                          RecipeSubstitutionMapper, IngredientMapper,
│                                          MethodStepMapper, RecipeMetadataMapper
├── domain/
│   ├── entity/                            JPA entities
│   ├── repository/                        Spring Data interfaces — package-private
│   └── service/
│       ├── RecipeQueryService.java        public interface
│       ├── RecipeUpdateService.java       public interface
│       ├── RecipeWriteApi.java            internal SPI for the Adaptation Pipeline
│       ├── RecipeServiceImpl.java         single impl of all three
│       └── internal/                      TagSearchHelper, VersionDiffer, SubstitutionOverlayApplier,
│                                          DeduplicationFingerprintHasher, HtmlImportParser,
│                                          ArchiveEligibilityScanner, DataQualityGate
├── event/                                 RecipeCreatedEvent, RecipeUpdatedEvent, RecipeArchivedEvent,
│                                          RecipePromotedEvent, RecipeAdaptedEvent
├── exception/                             module-root + per-failure subclasses
├── validation/                            @ValidIngredientList, @ValidMethodSteps, @ValidRecipeMetadata + validators
└── config/                                RecipeJsonConfig, RecipeSchedulingConfig
```

`RecipeWriteApi` is re-exported via the facade but documented as **internal** — only the Adaptation Pipeline's module should inject it. Spring DI cannot enforce this; an ArchUnit rule asserts no other module imports the SPI.

---

## Database

Migrations live under `src/main/resources/db/migration/` with the project-wide timestamp scheme. The recipe module owns ten migrations at module bring-up, broken by concern per "one concern per migration":

```
V20260601120000__recipe_create_recipes.sql                V20260601120500__recipe_create_method_steps.sql
V20260601120100__recipe_create_recipe_versions.sql        V20260601120600__recipe_create_recipe_metadata.sql
V20260601120200__recipe_create_recipe_branches.sql        V20260601120700__recipe_create_recipe_tags.sql
V20260601120201__recipe_add_branch_fks.sql                V20260601120800__recipe_create_recipe_imports.sql
V20260601120300__recipe_create_recipe_substitutions.sql   V20260601120900__recipe_create_recipe_embeddings.sql
V20260601120400__recipe_create_ingredients.sql            R__recipe_seed_complexity_tier_lookup.sql  (repeatable)
```

### V20260601120000 — Recipes (the aggregate root)

```sql
CREATE TABLE recipe_recipes (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL,
    catalogue varchar(16) NOT NULL,                  -- 'user' | 'system'
    name varchar(160) NOT NULL, description text,
    current_version integer NOT NULL DEFAULT 1,
    current_branch_id uuid,                          -- FK added in V...120201
    data_quality varchar(16) NOT NULL,               -- user_verified | imported | ai_generated | web_discovered
    nutrition_status varchar(16) NOT NULL DEFAULT 'pending',  -- calculated | pending | partial
    forked_from_recipe_id uuid,                      -- non-null when promoted from a divergent branch
    last_used_in_plan_at timestamptz,                -- archive-eligibility input
    archived_at timestamptz,                         -- non-null hides from planner index
    deleted_at  timestamptz,                         -- soft-delete for demoted user recipes
    optimistic_version bigint NOT NULL DEFAULT 0,    -- @Version
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL
);
-- Planner index pre-filter excludes archived/deleted, scopes by catalogue.
CREATE INDEX idx_recipe_recipes_catalogue_active
    ON recipe_recipes (catalogue) WHERE archived_at IS NULL AND deleted_at IS NULL;
CREATE INDEX idx_recipe_recipes_user_catalogue
    ON recipe_recipes (user_id, catalogue) WHERE deleted_at IS NULL;
-- Archive-eligibility scan.
CREATE INDEX idx_recipe_recipes_system_last_used
    ON recipe_recipes (last_used_in_plan_at)
    WHERE catalogue = 'system' AND archived_at IS NULL AND deleted_at IS NULL;
CREATE INDEX idx_recipe_recipes_name_lower ON recipe_recipes (lower(name));
CREATE INDEX idx_recipe_recipes_forked_from
    ON recipe_recipes (forked_from_recipe_id) WHERE forked_from_recipe_id IS NOT NULL;
```

`current_branch_id` is denormalised for the hot read path; the canonical row lives in `recipe_branches`. Soft-delete via `deleted_at` (not a boolean) per the style guide; demotion uses it. Hard-delete is reserved for tests.

### V20260601120100 — Recipe versions (linear changes per branch)

```sql
CREATE TABLE recipe_versions (
    id uuid PRIMARY KEY,
    recipe_id uuid NOT NULL REFERENCES recipe_recipes(id) ON DELETE CASCADE,
    branch_id uuid NOT NULL,                         -- FK added in V...120201
    version_number integer NOT NULL,                 -- monotonic per (recipe_id, branch_id)
    parent_version_id uuid REFERENCES recipe_versions(id),  -- null for v1 of any branch
    change_diff jsonb NOT NULL,                      -- structured RecipeDiff
    change_reason text,
    trigger varchar(32) NOT NULL,                    -- manual_create | manual_edit | import | adaptation_pipeline |
                                                     -- substitution_promotion | branch_creation | revert
    character_fingerprint jsonb,                     -- per-version snapshot, refreshed only on branch creation
    nutrition_per_serving jsonb,                     -- written by NutritionCalculationService
    embedding vector(1536),                          -- pgvector active; OpenAI text-embedding-3-small; producer is recipe module via EmbeddingTask, async defer-and-pending
    embedding_status varchar(16) NOT NULL DEFAULT 'pending',  -- pending | embedded | failed
    embedding_model_id varchar(96),                  -- e.g. 'openai:text-embedding-3-small'; null until embedded
    embedded_at timestamptz,                         -- when the vector landed
    created_at timestamptz NOT NULL,
    created_by_actor varchar(32) NOT NULL,           -- 'user:<uuid>' | 'adaptation_pipeline' | 'system_import'
    adapter_trace_id uuid,                           -- non-null when written by the pipeline
    UNIQUE (recipe_id, branch_id, version_number)
);
CREATE INDEX idx_recipe_versions_recipe_branch_ver
    ON recipe_versions (recipe_id, branch_id, version_number DESC);
CREATE INDEX idx_recipe_versions_trace_id
    ON recipe_versions (adapter_trace_id) WHERE adapter_trace_id IS NOT NULL;
```

Versions are append-only — never updated after initial save (with three well-defined exceptions: nutrition recalculation, fingerprint refresh on branch, and embedding population, all via WriteApi).

**Embedding producer (locked 2026-05-07).** When a version is saved the row commits with `embedding = NULL` and `embedding_status = 'pending'` (a separate column on the version row). An `@Async` listener on `RecipeVersionCreatedEvent` calls `aiService.embed(new RecipeEmbeddingTask(versionId, embeddingInputText))` — `embeddingInputText` is composed from name + description + cuisine + protein + cooking_method + flavour_profile + ingredient names (a fixed projection). On success the listener writes the vector via `RecipeWriteApi.storeEmbedding(versionId, vector)` and flips status to `'embedded'`; on `AiUnavailable` or terminal failure status flips to `'failed'` and the planner's `PreferenceSubScore` falls back to neutral 0.5 for slots filled by that recipe. Retries via Resilience4j with exponential backoff; 5 terminal failures parks status at `'failed'` for manual review (rare).

### V20260601120200 / 120201 — Recipe branches

```sql
CREATE TABLE recipe_branches (
    id uuid PRIMARY KEY,
    recipe_id uuid NOT NULL REFERENCES recipe_recipes(id) ON DELETE CASCADE,
    parent_branch_id uuid REFERENCES recipe_branches(id),         -- null only for 'main'
    branch_point_version_id uuid REFERENCES recipe_versions(id),
    name varchar(64) NOT NULL,                       -- 'main' | 'beef-variant' | 'lighter-coconut'
    label varchar(120), reason text,
    current_version integer NOT NULL DEFAULT 1,
    divergence_score numeric(4,3) NOT NULL DEFAULT 0.000,         -- 0..1
    created_at timestamptz NOT NULL,
    created_by_actor varchar(32) NOT NULL,
    adapter_trace_id uuid,
    UNIQUE (recipe_id, name)
);
CREATE INDEX idx_recipe_branches_recipe ON recipe_branches (recipe_id);
CREATE INDEX idx_recipe_branches_divergence
    ON recipe_branches (divergence_score DESC) WHERE divergence_score > 0.7;
```

`V20260601120201__recipe_add_branch_fks.sql` adds the deferred FKs from `recipe_recipes.current_branch_id` and `recipe_versions.branch_id` — kept as a follow-up migration to respect "one concern per migration" cleanly.

### V20260601120300 / 120400 — Substitutions and ingredients

```sql
CREATE TABLE recipe_substitutions (
    id uuid PRIMARY KEY,
    recipe_id  uuid NOT NULL REFERENCES recipe_recipes(id)  ON DELETE CASCADE,
    version_id uuid NOT NULL REFERENCES recipe_versions(id) ON DELETE CASCADE,
    branch_id  uuid NOT NULL REFERENCES recipe_branches(id) ON DELETE CASCADE,
    original_mapping_key   varchar(160) NOT NULL,
    original_quantity      numeric(10,3) NOT NULL, original_unit   varchar(16) NOT NULL,
    substitute_mapping_key varchar(160) NOT NULL,
    substitute_quantity    numeric(10,3) NOT NULL, substitute_unit varchar(16) NOT NULL,
    reason varchar(32) NOT NULL,                     -- budget | availability | dietary_temp | equipment
    constraint_ref varchar(160),                     -- e.g. 'budget-cap-2026-w15'
    method_overlay jsonb,                            -- list of {step, instruction}
    notes text, temporary boolean NOT NULL DEFAULT true,
    applied_in_plan_ids uuid[] NOT NULL DEFAULT '{}',
    application_count integer NOT NULL DEFAULT 0, last_applied_at timestamptz,
    state varchar(16) NOT NULL DEFAULT 'active',     -- active | inactive | promoted
    promoted_to_version_id uuid REFERENCES recipe_versions(id),
    created_at timestamptz NOT NULL, created_by_actor varchar(32) NOT NULL, adapter_trace_id uuid
);
CREATE INDEX idx_recipe_substitutions_version    ON recipe_substitutions (version_id) WHERE state = 'active';
CREATE INDEX idx_recipe_substitutions_promotion  ON recipe_substitutions (recipe_id, application_count DESC) WHERE state = 'active';

CREATE TABLE recipe_ingredients (
    id uuid PRIMARY KEY,
    version_id uuid NOT NULL REFERENCES recipe_versions(id) ON DELETE CASCADE,
    line_order integer NOT NULL,
    ingredient_mapping_key varchar(160) NOT NULL,
    display_name varchar(160) NOT NULL,
    quantity numeric(10,3),                          -- nullable for "to taste" items
    unit varchar(16), preparation varchar(80),
    optional boolean NOT NULL DEFAULT false,
    needs_review boolean NOT NULL DEFAULT false,     -- true when USDA confidence < 0.7
    mapping_confidence numeric(4,3),
    UNIQUE (version_id, line_order)
);
CREATE INDEX idx_recipe_ingredients_version      ON recipe_ingredients (version_id);
-- Used by HardConstraintFilterService callers when they ask for keys per recipe.
CREATE INDEX idx_recipe_ingredients_mapping_key  ON recipe_ingredients (ingredient_mapping_key);
CREATE INDEX idx_recipe_ingredients_version_key  ON recipe_ingredients (version_id, ingredient_mapping_key);
```

Editing ingredients requires creating a new version — never UPDATE in place — so historical comparison stays clean.

### V20260601120500 / 120600 / 120700 — Per-version body

Three child tables of `recipe_versions`, one-row-per-version (or per-step for method). Storing per-version means a version that adjusts cook time updates only its own row — history stays accurate. Tag dimensions are columns (not JSONB) per the style guide's stable-shape rule; GIN covers the array dimensions. Free values within each dimension are not FK-constrained — AI tag inference normalises.

```sql
CREATE TABLE recipe_method_steps (
    id uuid PRIMARY KEY,
    version_id uuid NOT NULL REFERENCES recipe_versions(id) ON DELETE CASCADE,
    step_number integer NOT NULL, instruction text NOT NULL, duration_minutes integer,
    UNIQUE (version_id, step_number)
);
CREATE INDEX idx_recipe_method_steps_version ON recipe_method_steps (version_id);

CREATE TABLE recipe_metadata (
    id uuid PRIMARY KEY,
    version_id uuid NOT NULL UNIQUE REFERENCES recipe_versions(id) ON DELETE CASCADE,
    servings integer NOT NULL,
    prep_time_mins integer NOT NULL, cook_time_mins integer NOT NULL, total_time_mins integer NOT NULL,
    equipment_required text[] NOT NULL DEFAULT '{}',
    fridge_days integer, freezer_weeks integer, packable boolean NOT NULL DEFAULT false,
    cuisine varchar(64), meal_types text[] NOT NULL DEFAULT '{}'
);
CREATE INDEX idx_recipe_metadata_total_time     ON recipe_metadata (total_time_mins);
CREATE INDEX idx_recipe_metadata_cuisine        ON recipe_metadata (cuisine);
CREATE INDEX idx_recipe_metadata_equipment_gin  ON recipe_metadata USING gin (equipment_required);
CREATE INDEX idx_recipe_metadata_meal_types_gin ON recipe_metadata USING gin (meal_types);

CREATE TABLE recipe_tags (
    id uuid PRIMARY KEY,
    version_id uuid NOT NULL UNIQUE REFERENCES recipe_versions(id) ON DELETE CASCADE,
    protein varchar(64), cooking_method varchar(64),
    complexity varchar(16),                                -- minimal | moderate | involved
    flavour_profile text[] NOT NULL DEFAULT '{}',
    dietary_flags   text[] NOT NULL DEFAULT '{}'
);
CREATE INDEX idx_recipe_tags_protein        ON recipe_tags (protein);
CREATE INDEX idx_recipe_tags_cooking_method ON recipe_tags (cooking_method);
CREATE INDEX idx_recipe_tags_complexity     ON recipe_tags (complexity);
CREATE INDEX idx_recipe_tags_flavour_gin    ON recipe_tags USING gin (flavour_profile);
CREATE INDEX idx_recipe_tags_dietary_gin    ON recipe_tags USING gin (dietary_flags);
```

### V20260601120800 / 120900 — Import provenance and embeddings

```sql
CREATE TABLE recipe_imports (
    id uuid PRIMARY KEY,
    recipe_id uuid NOT NULL UNIQUE REFERENCES recipe_recipes(id) ON DELETE CASCADE,
    source_type varchar(16) NOT NULL,                      -- manual | url | ai_generated | web_discovered
    source_url text, source_payload jsonb,                 -- raw HTML excerpt — audit/replay only, lazy-loaded
    extraction_method varchar(32),
    duplicate_of_recipe_id uuid REFERENCES recipe_recipes(id),
    imported_at timestamptz NOT NULL, imported_by_user_id uuid NOT NULL
);
CREATE INDEX idx_recipe_imports_url    ON recipe_imports (source_url)             WHERE source_url IS NOT NULL;
CREATE INDEX idx_recipe_imports_dedupe ON recipe_imports (duplicate_of_recipe_id) WHERE duplicate_of_recipe_id IS NOT NULL;

CREATE EXTENSION IF NOT EXISTS vector;
-- The vector column lives on recipe_versions per V...120100. Partial HNSW — empty rows pay no index cost.
CREATE INDEX idx_recipe_versions_embedding
    ON recipe_versions USING hnsw (embedding vector_cosine_ops) WHERE embedding IS NOT NULL;
```

`source_payload` is loaded on demand only via `getImportProvenance`. Repeatable migration `R__recipe_seed_complexity_tier_lookup.sql` seeds the `complexity_tier` lookup (`minimal`, `moderate`, `involved`) used by tag inference and validation.

---

## Entities

All entities follow the style guide: UUID `@Id` set application-side, `@Version` on every mutable aggregate root, `@CreatedDate`/`@LastModifiedDate` audit columns, Lombok `@Getter @Setter @Builder @NoArgsConstructor(access = PROTECTED) @AllArgsConstructor`. JSONB columns via `@Type(JsonType.class)` (hypersistence-utils). `embedding` maps to `float[]` via the pgvector dialect; producer deferred.

| Entity | Notes |
|---|---|
| `Recipe` | Aggregate root. `currentBranchId` non-null after the first branch exists (initial save creates `main` atomically). `@Version Long optimisticVersion`. No `@OneToMany` to versions/branches — queried separately to keep the root small. |
| `RecipeVersion` | Append-only. No `@Version`. Owns `@OneToMany(cascade = ALL)` for `RecipeIngredient`, `RecipeMethodStep`, plus `@OneToOne` for `RecipeMetadata` and `RecipeTags`. JSON fields (`changeDiff`, `characterFingerprint`, `nutritionPerServing`) typed `JsonNode`. |
| `RecipeBranch` | `currentVersion`, `divergenceScore` mutated post-creation. `@Version`. |
| `RecipeSubstitution` | Mutable on `applicationCount`, `lastAppliedAt`, `appliedInPlanIds`, `state`, `promotedToVersionId`. `@Version`. `methodOverlay` mapped as `List<MethodOverlayLine>` via JSONB. |
| `RecipeIngredient`, `RecipeMethodStep` | Children of `RecipeVersion`. `@ManyToOne(fetch = LAZY)` back. No `@Version`. |
| `RecipeMetadata`, `RecipeTags` | One row per version. `@OneToOne(fetch = LAZY)` back. No `@Version`. |
| `RecipeImport` | One row per recipe. `sourcePayload` `@Basic(fetch = LAZY)` to avoid hydrating audit blobs on hot reads. |

Enums local to the module: `Catalogue`, `DataQuality`, `NutritionStatus`, `VersionTrigger`, `SubstitutionReason`, `SubstitutionState`, `Complexity`, `MealType`, `ImportSource`, `ChangeAction`, `ArchiveCause`, `AdaptationOutcomeType`.

---

## DTOs

All DTOs are Java records. Field lists track entity columns one-for-one.

```java
public record IngredientDto(UUID id, int lineOrder, String ingredientMappingKey, String displayName,
    BigDecimal quantity, String unit, String preparation,
    boolean optional, boolean needsReview, BigDecimal mappingConfidence) {}

public record MethodStepDto(int stepNumber, String instruction, Integer durationMinutes) {}

public record RecipeMetadataDto(int servings, int prepTimeMins, int cookTimeMins, int totalTimeMins,
    List<String> equipmentRequired, StoresWellDto storesWell,
    boolean packable, String cuisine, List<MealType> mealTypes) {
    public record StoresWellDto(Integer fridgeDays, Integer freezerWeeks) {}
}

public record RecipeTagsDto(String protein, String cookingMethod, Complexity complexity,
    List<String> flavourProfile, List<String> dietaryFlags) {}

public record CharacterFingerprintDto(
    List<String> definingIngredients, List<String> definingTechniques,
    List<String> textureEssentials,   List<String> flavourAnchors,
    Complexity complexityTier, String cuisineAnchor) {}

public record RecipeVersionDto(UUID id, UUID recipeId, UUID branchId, int versionNumber, UUID parentVersionId,
    List<IngredientDto> ingredients, List<MethodStepDto> method,
    RecipeMetadataDto metadata, RecipeTagsDto tags, CharacterFingerprintDto characterFingerprint,
    JsonNode changeDiff, String changeReason, VersionTrigger trigger,
    Instant createdAt, String createdByActor, UUID adapterTraceId,
    JsonNode nutritionPerServing /* opaque pass-through */) {}

public record RecipeBranchDto(UUID id, UUID recipeId, UUID parentBranchId, UUID branchPointVersionId,
    String name, String label, String reason,
    int currentVersion, BigDecimal divergenceScore, CharacterFingerprintDto characterFingerprint,
    Instant createdAt, String createdByActor, UUID adapterTraceId) {}

public record RecipeSubstitutionDto(UUID id, UUID recipeId, UUID versionId, UUID branchId,
    SubstitutedItemDto original, SubstitutedItemDto substitute,
    SubstitutionReason reason, String constraintRef, List<MethodOverlayLineDto> methodOverlay,
    String notes, boolean temporary,
    int applicationCount, Instant lastAppliedAt,
    SubstitutionState state, UUID promotedToVersionId,
    Instant createdAt, String createdByActor, UUID adapterTraceId) {
    public record SubstitutedItemDto(String ingredientMappingKey, BigDecimal quantity, String unit) {}
    public record MethodOverlayLineDto(int step, String instruction) {}
}

public record RecipeDto(UUID id, UUID userId, Catalogue catalogue, String name, String description,
    int currentVersion, UUID currentBranchId,
    DataQuality dataQuality, NutritionStatus nutritionStatus, UUID forkedFromRecipeId,
    Instant lastUsedInPlanAt, Instant archivedAt, Instant deletedAt,
    Instant createdAt, Instant updatedAt, long optimisticVersion,
    RecipeVersionDto currentVersionDetails /* null for index views */,
    List<RecipeBranchDto> branches         /* empty for index views */) {}
```

### Search

```java
public record RecipeSearchCriteriaDto(
    @Nullable Catalogue catalogue, @Nullable UUID userId,
    @Nullable String namePattern, @Nullable String cuisine,
    @Nullable List<MealType> mealTypes, @Nullable Integer maxTotalTimeMins,
    @Nullable List<String> equipmentRequired,        // recipe must use only equipment in this set
    @Nullable List<String> protein,                  // any-of
    @Nullable List<String> cookingMethod,            // any-of
    @Nullable List<Complexity> complexity,
    @Nullable List<String> flavourProfile,           // any-of
    @Nullable List<String> dietaryFlags,             // all-of
    @Nullable Boolean packable, @Nullable Integer minFridgeDays,
    @Nullable DataQuality minDataQuality,            // ordinal floor — see DataQualityGate
    @Nullable Boolean includeArchived,
    @Nullable List<String> mustNotContainMappingKeys // for HardConstraintFilterService callers
) {}
```

`TagSearchHelper` translates this to a JPA `Specification` plus native predicates for the GIN-indexed columns.

### Diff

```java
public record RecipeDiffDto(UUID fromVersionId, UUID toVersionId,
    List<IngredientChangeDto> ingredientChanges, List<MethodChangeDto> methodChanges,
    List<MetadataChangeDto>   metadataChanges,   List<TagChangeDto>    tagChanges) {
    public record IngredientChangeDto(ChangeAction action, IngredientDto from, IngredientDto to, String fieldChanged) {}
    public record MethodChangeDto   (ChangeAction action, Integer step, String from, String to) {}
    public record MetadataChangeDto (ChangeAction action, String field, JsonNode from, JsonNode to) {}
    public record TagChangeDto      (ChangeAction action, String dimension, JsonNode from, JsonNode to) {}
}
```

`change_diff` on each version row is the same shape persisted as JSONB — `VersionDiffer` builds it once at write time, so the diff endpoint is a key-value lookup, not a recompute.

### Request shapes

```java
public record CreateRecipeRequest(
    @NotBlank @Size(max = 160) String name, @Size(max = 2000) String description,
    @NotEmpty @Valid @ValidIngredientList List<CreateIngredientRequest> ingredients,
    @NotEmpty @Valid @ValidMethodSteps   List<CreateMethodStepRequest>  method,
    @NotNull @Valid @ValidRecipeMetadata CreateRecipeMetadataRequest    metadata,
    @Valid CreateRecipeTagsRequest tags                 // optional — AI inference fills if absent
) {}

public record ImportRecipeFromUrlRequest(@NotBlank @URL String url, @Nullable Catalogue catalogue) {}

public record UpdateRecipeManualEditRequest(
    @NotBlank String name, @Size(max = 2000) String description,
    @NotEmpty @Valid List<CreateIngredientRequest> ingredients,
    @NotEmpty @Valid List<CreateMethodStepRequest> method,
    @NotNull  @Valid CreateRecipeMetadataRequest   metadata,
    @Valid CreateRecipeTagsRequest tags,
    @NotBlank String changeReason,
    long expectedOptimisticVersion                       // 409 on stale
) {}

public record CreateBranchRequest(
    @NotBlank @Size(max = 64) @Pattern(regexp = "[a-z0-9-]+") String name,
    @Size(max = 120) String label, @NotBlank String reason,
    @NotNull UUID branchPointVersionId,
    @NotNull @Valid CreateRecipeBodyRequest body,
    @Valid CharacterFingerprintDto fingerprintOverride) {}

public record CreateSubstitutionRequest(
    @NotNull UUID versionId,
    @NotNull @Valid SubstitutionItemRequest original,
    @NotNull @Valid SubstitutionItemRequest substitute,
    @NotNull SubstitutionReason reason, @Size(max = 160) String constraintRef,
    @Valid List<MethodOverlayLineRequest> methodOverlay,
    @Size(max = 1000) String notes, boolean temporary) {}
```

`expectedOptimisticVersion` on manual edit guards against silently overwriting an in-flight pipeline write.

---

## Mappers

MapStruct interfaces, `@Mapper(componentModel = "spring")`. One per entity-DTO pair. `RecipeMapper.toDto` is overloaded — the index variant skips current-version hydration for cheap list endpoints; the hydrated variant takes the prefetched current-version entity, populated via `@EntityGraph` to avoid N+1.

```java
@Mapper(componentModel = "spring", uses = { RecipeVersionMapper.class, RecipeBranchMapper.class })
public interface RecipeMapper {
    @Mapping(target = "currentVersionDetails", source = "currentVersionEntity")
    @Mapping(target = "branches",              source = "branchEntities")
    RecipeDto toDto(Recipe entity, RecipeVersion currentVersionEntity, List<RecipeBranch> branchEntities);
    RecipeDto toIndexDto(Recipe entity);
    List<RecipeDto> toIndexDtos(List<Recipe> entities);
}

@Mapper(componentModel = "spring",
        uses = { IngredientMapper.class, MethodStepMapper.class, RecipeMetadataMapper.class, RecipeTagsMapper.class })
public interface RecipeVersionMapper { RecipeVersionDto toDto(RecipeVersion e); List<RecipeVersionDto> toDtos(List<RecipeVersion> es); }

// One Mapper per remaining entity-DTO pair: RecipeBranchMapper, RecipeSubstitutionMapper, IngredientMapper,
// MethodStepMapper, RecipeMetadataMapper, RecipeTagsMapper. IngredientMapper additionally provides
// fromCreate(CreateIngredientRequest) so the service layer builds entities without manual copy.
```

---

## Repositories

Package-private interfaces (no `public`); cross-module access goes through service interfaces only. `@EntityGraph` keeps the by-id read to a single JOIN spanning version + ingredients + method + metadata + tags — no N+1.

```java
interface RecipeRepository extends JpaRepository<Recipe, UUID>, JpaSpecificationExecutor<Recipe> {
    Optional<Recipe> findByIdAndDeletedAtIsNull(UUID id);
    List<Recipe> findAllByIdInAndDeletedAtIsNull(List<UUID> ids);

    @Query("select r from Recipe r where r.userId = :userId and r.catalogue = 'USER' and r.deletedAt is null")
    Page<Recipe> findUserCatalogue(@Param("userId") UUID userId, Pageable pageable);

    @Query("select r from Recipe r where r.catalogue = 'SYSTEM' and r.archivedAt is null and r.deletedAt is null")
    Page<Recipe> findSystemCatalogue(Pageable pageable);

    // Daily archive scan: rows untouched-by-plan for 90+ days.
    @Query("""
        select r.id from Recipe r
         where r.catalogue = 'SYSTEM' and r.archivedAt is null and r.deletedAt is null
           and ((r.lastUsedInPlanAt is null and r.createdAt < :cutoff) or r.lastUsedInPlanAt < :cutoff)""")
    List<UUID> findArchiveEligibleSystemRecipes(@Param("cutoff") Instant cutoff);
}

interface RecipeVersionRepository extends JpaRepository<RecipeVersion, UUID> {
    @EntityGraph(attributePaths = {"ingredients", "methodSteps", "metadata", "tags"})
    Optional<RecipeVersion> findWithBodyById(UUID id);

    Page<RecipeVersion> findByRecipeIdAndBranchIdOrderByVersionNumberDesc(UUID recipeId, UUID branchId, Pageable p);
    Optional<RecipeVersion> findTopByRecipeIdAndBranchIdOrderByVersionNumberDesc(UUID recipeId, UUID branchId);

    // Hot batch: hydrate current versions for a list of recipe IDs in one round trip.
    @EntityGraph(attributePaths = {"ingredients", "methodSteps", "metadata", "tags"})
    @Query("""
        select v from RecipeVersion v
         where v.recipe.id in :recipeIds
           and v.branch.id = v.recipe.currentBranchId
           and v.versionNumber = v.recipe.currentVersion""")
    List<RecipeVersion> findCurrentVersionsForRecipes(@Param("recipeIds") List<UUID> recipeIds);
}

interface RecipeBranchRepository extends JpaRepository<RecipeBranch, UUID> {
    List<RecipeBranch> findAllByRecipeId(UUID recipeId);
    Optional<RecipeBranch> findByRecipeIdAndName(UUID recipeId, String name);
}
interface RecipeSubstitutionRepository extends JpaRepository<RecipeSubstitution, UUID> {
    List<RecipeSubstitution> findAllByRecipeIdAndStateOrderByLastAppliedAtDesc(UUID recipeId, SubstitutionState s);
    List<RecipeSubstitution> findAllByVersionIdAndStateOrderByLastAppliedAtDesc(UUID versionId, SubstitutionState s);
}
interface RecipeImportRepository      extends JpaRepository<RecipeImport, UUID> {
    Optional<RecipeImport> findByRecipeId(UUID recipeId);
    Optional<RecipeImport> findBySourceUrl(String sourceUrl);
}
interface RecipeIngredientRepository  extends JpaRepository<RecipeIngredient, UUID> {
    @Query("select i.ingredientMappingKey from RecipeIngredient i where i.version.id = :versionId order by i.lineOrder")
    List<String> findMappingKeysByVersionId(@Param("versionId") UUID versionId);
}
```

---

## Service Interfaces

Both module interfaces and the internal SPI are implemented by a single `RecipeServiceImpl`.

### `RecipeQueryService`

```java
public interface RecipeQueryService {
    Optional<RecipeDto> getById(UUID recipeId);
    List<RecipeDto> getByIds(List<UUID> recipeIds);                  // batch sibling, current-version bodies hydrated

    Page<RecipeDto> listUserCatalogue(UUID userId, Pageable pageable);
    Page<RecipeDto> listSystemCatalogue(Pageable pageable);
    Page<RecipeDto> search(RecipeSearchCriteriaDto criteria, Pageable pageable);

    Page<RecipeVersionDto> getVersionHistory(UUID recipeId, UUID branchId, Pageable pageable);
    Optional<RecipeVersionDto> getVersion(UUID recipeId, UUID branchId, int versionNumber);
    List<RecipeBranchDto> getBranches(UUID recipeId);
    Optional<RecipeBranchDto> getBranch(UUID branchId);

    List<RecipeSubstitutionDto> getActiveSubstitutions(UUID recipeId);
    List<RecipeSubstitutionDto> getSubstitutionsForVersion(UUID versionId);

    RecipeDiffDto diff(UUID fromVersionId, UUID toVersionId);

    // Cross-module helpers.
    List<String> getIngredientMappingKeys(UUID recipeId);
    Map<UUID, List<String>> getIngredientMappingKeysByIds(List<UUID> recipeIds);
    Optional<CharacterFingerprintDto> getFingerprint(UUID recipeId, UUID branchId);
    JsonNode getNutritionPerServing(UUID recipeId);
    Optional<float[]> getEmbedding(UUID versionId);
    Optional<JsonNode> getImportProvenance(UUID recipeId);
}
```

### `RecipeUpdateService`

```java
public interface RecipeUpdateService {
    // Manual create / edit / import — invoked by REST. URL import delegates to
    // RecipeExtractionService (see lld/recipe-extraction-pipeline.md) — the same shared
    // pipeline used by autonomous discovery. Two import variants per the Paprika-style flow:
    //   - importFromUrl: server fetches the URL itself
    //   - importFromHtml: frontend in-app browser supplies pre-fetched HTML + URL
    // Both return a preview that the user reviews/edits before final save.
    RecipeDto create(UUID userId, CreateRecipeRequest request);
    RecipeDto manualEdit(UUID recipeId, UpdateRecipeManualEditRequest request, UUID actorUserId);
    RecipeImportPreview previewImportFromUrl(UUID userId, ImportRecipeFromUrlRequest request);
    RecipeImportPreview previewImportFromHtml(UUID userId, ImportRecipeFromHtmlRequest request);
    RecipeDto confirmImport(UUID userId, ConfirmImportRequest request);   // user clicks Save on the preview

    // Catalogue moves.
    RecipeDto promoteToUserCatalogue(UUID systemRecipeId, UUID userId);
    void demoteToSystemCatalogue(UUID userRecipeId, UUID actorUserId);
    void archive(UUID recipeId, UUID actorUserId);                   // soft — sets archived_at
    void unarchive(UUID recipeId, UUID actorUserId);
    void softDelete(UUID recipeId, UUID actorUserId);                // sets deleted_at
    RecipeVersionDto revertToVersion(UUID recipeId, UUID branchId, int versionNumber, UUID actorUserId);

    // Substitution lifecycle (catalogue-level — planner attaches at plan time).
    RecipeSubstitutionDto createSubstitution(UUID recipeId, CreateSubstitutionRequest request, UUID actorUserId);
    void recordSubstitutionApplication(UUID substitutionId, UUID planId);
    void deactivateSubstitution(UUID substitutionId, UUID actorUserId);
    RecipeVersionDto promoteSubstitutionToVersion(UUID substitutionId, UUID actorUserId);

    // Plan-time touch — planner / cook listener bumps last_used_in_plan_at.
    void markUsedInPlan(List<UUID> recipeIds);
}
```

`RecipeUpdateService` exposes business-intent methods only. Adapted versions and branches do **not** appear here — they go through `RecipeWriteApi`.

### `RecipeWriteApi` (internal SPI for the Adaptation Pipeline)

```java
public interface RecipeWriteApi {
    // Persist a new version. Race-checked: throws RecipeVersionConflictException if
    // expectedParentVersionId is no longer the current version. Pipeline rebases up to 3 times.
    RecipeVersionDto      saveAdaptedVersion     (SaveAdaptedVersionCommand command);
    // Create a branch with v1 body. Pipeline supplies the fingerprint; catalogue does not re-derive.
    RecipeBranchDto       saveAdaptedBranch      (SaveAdaptedBranchCommand command);
    // Persist a substitution overlay attached to a base version. Plan-time jobs use this.
    RecipeSubstitutionDto saveAdaptedSubstitution(SaveAdaptedSubstitutionCommand command);

    // Pipeline owns "adapt → recalc → notify" orchestration; catalogue exposes the write hooks.
    void updateNutritionStatus(UUID versionId, NutritionStatus status, JsonNode nutritionPerServing);
    void updateCharacterFingerprint(UUID versionId, CharacterFingerprintDto fingerprint);
    void updateBranchDivergence(UUID branchId, BigDecimal divergenceScore);
    void storeEmbedding(UUID versionId, float[] embedding);                      // future planner-owned producer
}

public record SaveAdaptedVersionCommand(
    UUID recipeId, UUID branchId, int expectedParentVersionNumber, UUID expectedParentVersionId,
    List<CreateIngredientRequest> ingredients, List<CreateMethodStepRequest> method,
    CreateRecipeMetadataRequest metadata, CreateRecipeTagsRequest tags,
    CharacterFingerprintDto characterFingerprint,
    JsonNode changeDiff, String changeReason, UUID adapterTraceId) {}

public record SaveAdaptedBranchCommand(
    UUID recipeId, UUID parentBranchId, UUID branchPointVersionId,
    String name, String label, String reason,
    List<CreateIngredientRequest> ingredients, List<CreateMethodStepRequest> method,
    CreateRecipeMetadataRequest metadata, CreateRecipeTagsRequest tags,
    CharacterFingerprintDto characterFingerprint, UUID adapterTraceId) {}

public record SaveAdaptedSubstitutionCommand(
    UUID recipeId, UUID versionId, UUID branchId,
    SubstitutionItemRequest original, SubstitutionItemRequest substitute,
    SubstitutionReason reason, String constraintRef,
    List<MethodOverlayLineRequest> methodOverlay,
    String notes, boolean temporary, UUID adapterTraceId) {}
```

`expectedParentVersionId` + `expectedParentVersionNumber` give value-level and surrogate-key-level guards for race detection. `adapterTraceId` threads through every write so `created_by_actor` and `adapter_trace_id` columns tie back to the pipeline trace — the catalogue stores the ID but never reads from the trace itself (cross-pocket boundary).

**Decision flagged.** The HLD shows a single `AdaptationService` with `enqueue*Job` and `acceptPendingChange` methods — those are pipeline-owned, they shepherd AI work and pending changes through user review. The catalogue's responsibility is pure persistence. Pending-change storage (queued user-catalogue proposals) lives in the **pipeline's** module: it carries pipeline-internal fields like `prompt_template_version` and `change_dimension`, and the catalogue should be free of "did the user accept this yet?" state. **Worth user review.**

---

## REST Controllers

All endpoints under `/api/v1/recipes/...`. `userId` resolved server-side from auth context. OpenAPI: `@Tag(name = "Recipes")` on each controller, `@Operation` on each handler.

| Controller | Method · Path | Body | Response | Status |
|---|---|---|---|---|
| `RecipesController` | GET    `/{id}` | — | `RecipeDto` | 200 / 404 |
|                     | POST   `/` | `CreateRecipeRequest` | `RecipeDto` | 201 / 400 |
|                     | PUT    `/{id}` | `UpdateRecipeManualEditRequest` | `RecipeDto` | 200 / 400 / 404 / 409 |
|                     | DELETE `/{id}` | — | — | 204 / 404 |
|                     | GET    `/` | `RecipeSearchCriteriaDto`, `Pageable` (query params) | `Page<RecipeDto>` | 200 |
|                     | GET    `/user-catalogue` | `Pageable` | `Page<RecipeDto>` | 200 |
|                     | GET    `/system-catalogue` | `Pageable` | `Page<RecipeDto>` | 200 |
|                     | GET    `/{id}/nutrition` | — | nutrition JSON | 200 / 404 |
|                     | POST   `/{id}/promote` · `/{id}/demote` · `/{id}/archive` · `/{id}/unarchive` | — | `RecipeDto` or `204` | 200 / 404 / 422 |
| `RecipeVersionsController` (`/{id}/versions`) | GET `?branchId=&page=&size=` | — | `Page<RecipeVersionDto>` | 200 |
|  | GET `/{versionNumber}?branchId=` | — | `RecipeVersionDto` | 200 / 404 |
|  | POST `/revert` | `{ branchId, versionNumber }` | `RecipeVersionDto` | 200 / 404 / 409 |
|  | GET `/diff?fromVersionId=&toVersionId=` | — | `RecipeDiffDto` | 200 / 404 |
| `RecipeBranchesController` (`/{id}/branches`) | GET `/` | — | `List<RecipeBranchDto>` | 200 |
|  | POST `/` | `CreateBranchRequest` | `RecipeBranchDto` | 201 / 400 / 404 / 409 |
|  | GET  `/{branchId}` | — | `RecipeBranchDto` | 200 / 404 |
| `RecipeSubstitutionsController` (`/{id}/substitutions`) | GET `?state=active` | — | `List<RecipeSubstitutionDto>` | 200 |
|  | POST `/` | `CreateSubstitutionRequest` | `RecipeSubstitutionDto` | 201 / 400 / 404 |
|  | POST `/{subId}/promote` · `/{subId}/deactivate` | — | `RecipeVersionDto` or `204` | 200 / 404 |
| `RecipeImportsController` (`/imports`) | POST `/url` | `ImportRecipeFromUrlRequest` | `RecipeDto` | 201 / 400 / 422 |
| `RecipeAdminController` (`/admin`) | POST `/run-archive-scan` | — | `{ flaggedCount }` | 200 |

The AI-extraction import path is **deferred**. The v1 `HtmlImportParser` extracts what it can deterministically (microdata, JSON-LD `Recipe` schema, common selectors); ambiguous fields are stored with `needs_review = true`. When the AI extractor lands, the endpoint is unchanged; only the parser strategy changes. The admin `/run-archive-scan` is a manual trigger for the daily archive-eligibility scan.

### Error responses

All error responses use RFC 9457 `ProblemDetail`. Module root: `RecipeException extends MealPrepException`. Mappings (handled in the project-wide `GlobalExceptionHandler`):

| Exception | Status |
|---|---|
| `RecipeNotFoundException`, `RecipeVersionNotFoundException`, `RecipeBranchNotFoundException`, `RecipeSubstitutionNotFoundException` | 404 |
| `RecipeVersionConflictException`, `OptimisticLockException` (JPA) | 409 |
| `RecipeImportFailureException`, `RecipeImportDuplicateException`, `RecipeCatalogueViolationException`, `RecipeDataQualityException` | 422 |
| `MethodArgumentNotValidException` | 400 (`errors[]` extension on ProblemDetail) |

Each exception carries a `type` URI under `https://mealprep.example.com/problems/<kebab-case-name>`.

---

## Validation

Standard Jakarta annotations on request records: `@NotNull`, `@NotBlank`, `@Size`, `@Min`/`@Max`, `@Valid`, `@Pattern`, `@URL`. Custom validators in `validation/`:

- **`@ValidIngredientList`** — at least one ingredient, no duplicate `(ingredientMappingKey, preparation)` pairs (two "1 chopped onion" lines is almost always a mistake), `quantity` null only when `unit` is null ("to taste" items).
- **`@ValidMethodSteps`** — `step_number` contiguous from 1, no gaps.
- **`@ValidRecipeMetadata`** — `total_time_mins == prep_time_mins + cook_time_mins ± 5` (rounding tolerance), `freezer_weeks` populated only when `fridge_days` is, `servings >= 1`.

Service-layer validation (not Jakarta) in `RecipeServiceImpl`: manual edit cannot promote a system recipe (use `promoteToUserCatalogue`); substitution promotion requires `state == ACTIVE`; branch creation requires `branchPointVersionId` to belong to the recipe; `revertToVersion` fails for cross-branch targets; `DataQualityGate.assertMinimum(criteria, recipe)` enforces `minDataQuality` floor at query time.

---

## Events

### Published

```java
public record RecipeCreatedEvent (UUID recipeId, UUID userId, Catalogue catalogue,
                                  DataQuality dataQuality, UUID traceId, Instant occurredAt) {}

public record RecipeUpdatedEvent (UUID recipeId, UUID branchId, UUID newVersionId, int newVersionNumber,
                                  VersionTrigger trigger, UUID traceId, Instant occurredAt) {}

public record RecipeArchivedEvent(UUID recipeId, ArchiveCause cause,    // INACTIVITY_3_MONTHS | USER_DEMOTION | MANUAL_ADMIN
                                  UUID traceId, Instant occurredAt) {}

public record RecipePromotedEvent(UUID recipeId, UUID userId, Catalogue fromCatalogue, Catalogue toCatalogue,
                                  UUID traceId, Instant occurredAt) {}

public record RecipeAdaptedEvent (UUID recipeId, UUID branchId, UUID newVersionId,
                                  AdaptationOutcomeType outcomeType, UUID adapterTraceId,
                                  UUID traceId, Instant occurredAt) {}
```

`RecipeAdaptedEvent` fires whenever the Adaptation Pipeline writes through `RecipeWriteApi` — the payload's `adapterTraceId` lets downstream listeners (notably `NutritionCalculationService` for recipe-side recalculation, planner for mid-week re-opt offers) join back to the pipeline trace.

**Worth user review.** The HLD describes a single `RecipeEvolvedEvent`. This LLD splits it into `RecipeUpdatedEvent` (any version write — manual or otherwise) and `RecipeAdaptedEvent` (pipeline-only with trace ID) so subscribers can target precisely. The Nutrition recalculation listener subscribes to both.

All published via `ApplicationEventPublisher` after the relevant write transaction; listeners use `@TransactionalEventListener(phase = AFTER_COMMIT)` per the style guide.

### Consumed

`onMealCooked(MealCookedEvent)` and `onPlanCreated(PlanCreatedEvent)` — both `@TransactionalEventListener(AFTER_COMMIT)`, both route into `markUsedInPlan(recipeIds)` to bump `last_used_in_plan_at`. The catalogue does **not** consume `FeedbackProcessedEvent` — feedback that lands on a recipe goes through the Adaptation Pipeline (per [feedback-system.md](../design/feedback-system.md)); only the pipeline's resulting WriteApi calls touch the catalogue. **Worth user review** — explicit per the HLD's routing rules.

---

## Business Logic Flows

### Flow 1: Manual create

`POST /api/v1/recipes` → `create`. `@Transactional`. One transaction so a partial recipe never reaches the DB. Sequence: validate → generate UUIDs application-side → `DeduplicationFingerprintHasher.hash` (collision above the HLD's 80% threshold → `RecipeImportDuplicateException` carrying the candidate ID; 422 with payload so the UI can prompt "merge / variant / import anyway") → persist `Recipe` (`catalogue = USER`, `dataQuality = USER_VERIFIED`, `nutritionStatus = PENDING`, `currentVersion = 1`) → persist `RecipeBranch main` → persist `RecipeVersion` v1 (`parentVersionId = null`, `trigger = MANUAL_CREATE`) + children → set `Recipe.currentBranchId = main.id` → `RecipeImport(sourceType = MANUAL)`. After commit publishes `RecipeCreatedEvent`. The Nutrition listener calls `updateNutritionStatus` once USDA mapping completes.

### Flow 2: URL / HTML import — preview-then-confirm (Paprika-style)

Two preview endpoints + one confirm endpoint, all delegating extraction to **`RecipeExtractionService`** (see [recipe-extraction-pipeline.md](recipe-extraction-pipeline.md)) — the same shared pipeline used by autonomous discovery.

**`POST /api/v1/recipes/imports/preview-url`** → `previewImportFromUrl`. Server fetches the URL. Calls `recipeExtractionService.extract(new ExtractionInput.FromUrl(url, traceId))`. **Not** `@Transactional` — extraction is read-only and may take seconds. Returns `RecipeImportPreview { parsedRecipe, validationWarnings, dedupCandidate, extractionProvenance, previewToken }`. The `previewToken` is a short-lived (15-minute) signed reference the frontend includes in the confirm call.

**`POST /api/v1/recipes/imports/preview-html`** → `previewImportFromHtml`. Frontend's in-app browser supplies `{ url, html }`. Calls `recipeExtractionService.extract(new ExtractionInput.FromHtml(url, html, traceId))`. Same response shape. Used when (a) the page is JS-rendered (server fetch returns shell HTML), (b) the user is authenticated to a paywalled source, or (c) we want exact-rendering fidelity for sites known to differ between bot and browser views.

**`POST /api/v1/recipes/imports/confirm`** → `confirmImport`. `@Transactional`. The user has reviewed and possibly edited the preview; the request carries `previewToken` + the (possibly-edited) `CreateRecipeRequest`. Validates the token (signed, not expired, matches the parsedRecipe in the cache), runs `DeduplicationFingerprintHasher.hash` (collision above 80% → `RecipeImportDuplicateException` → 422 with candidate ID for "merge / variant / import anyway"), persists the recipe (`catalogue = USER`, `dataQuality = IMPORTED`, `nutritionStatus = PENDING`), persists `RecipeBranch main`, persists `RecipeVersion v1` (`trigger = URL_IMPORT_USER` or `HTML_IMPORT_USER`), records provenance in `recipe_imports` (source URL, extraction layer that won, AI cost if any). Publishes `RecipeCreatedEvent`. Nutrition recompute and embedding pipeline trigger off the event.

**Failure modes:** preview extraction errors (`RecipeExtractionService.ExtractionResult.Failure`) translate to 422 with the failure reason. The user can fall back to manual entry without involving the extraction pipeline.

The preview cache reuses the same `recipe_extraction_cache` table from the pipeline LLD. Repeat preview calls for the same URL within 7 days are free (no fetch, no AI).

### Flow 3: Manual edit → version increment

`PUT /api/v1/recipes/{recipeId}` → `manualEdit`. `@Transactional`. Loads recipe (404 if missing/soft-deleted); asserts `expectedOptimisticVersion` matches (mismatch → 409 — pipeline or another tab wrote between read and edit); loads current version body via `findWithBodyById`; `VersionDiffer.diff` produces the structured diff (empty → return unchanged DTO with no write); builds the new version (`versionNumber + 1`, `parentVersionId = currentVersionId`, `trigger = MANUAL_EDIT`, fingerprint copied from parent — only branch creation refreshes it); inserts version + children; updates `Recipe.currentVersion` (JPA bumps `optimisticVersion`) and `RecipeBranch.currentVersion`. Publishes `RecipeUpdatedEvent`; Nutrition listener triggers recalculation, results land via `updateNutritionStatus`.

### Flow 4: Branch creation

User-initiated (`POST /api/v1/recipes/{recipeId}/branches`) and pipeline-initiated (`RecipeWriteApi.saveAdaptedBranch`) share a private `branchInternal(...)` helper. Both `@Transactional`. Loads parent recipe + `branchPointVersionId` (must belong to the recipe); asserts branch-name unique within `(recipe_id, name)` (DB unique constraint catches concurrent creates with 409); inserts `RecipeBranch` (`currentVersion = 1`, `divergenceScore = 0`, fingerprint as supplied by the pipeline or copied from the parent for the manual flow); inserts `RecipeVersion` v1 on the new branch with `parentVersionId = branchPointVersionId` (cross-branch parent — diff path remains valid). Publishes `RecipeUpdatedEvent` (manual) or `RecipeAdaptedEvent` (pipeline).

### Flow 5: Substitution overlay

`POST /api/v1/recipes/{recipeId}/substitutions` → `createSubstitution` (manual/planner) **or** `RecipeWriteApi.saveAdaptedSubstitution` (pipeline). `@Transactional`. Loads base version; validates `original.ingredientMappingKey` exists in the version's ingredient list (substitution must reference a real ingredient); inserts `RecipeSubstitution` with `state = ACTIVE`, `applicationCount = 0`. The base version is **not** mutated — the substitution stays an overlay. The planner attaches it to a plan slot and calls `recordSubstitutionApplication(substitutionId, planId)` to bump `applicationCount` and append the plan ID to `appliedInPlanIds`.

### Flow 6: Save-from-adaptation (pipeline write-through)

The pipeline owns ingredient-swap reasoning, character checks, classification, planner-hint emission. The catalogue receives one of three commands and persists: `saveAdaptedVersion` (same write as Flow 3 but `trigger = ADAPTATION_PIPELINE`, race-checked, publishes `RecipeUpdatedEvent` + `RecipeAdaptedEvent`); `saveAdaptedBranch` (same as Flow 4 pipeline path, publishes both events); `saveAdaptedSubstitution` (same as Flow 5, publishes `RecipeAdaptedEvent`).

The race check is the most important contract. The catalogue does `findById ... FOR UPDATE` on `Recipe`, asserts `recipe.currentVersion == command.expectedParentVersionNumber` and the corresponding version-row id matches `command.expectedParentVersionId`. Mismatch throws `RecipeVersionConflictException`. The pipeline catches and rebases up to 3 times before failing the job — the rebase loop is the **pipeline's** concern.

### Flow 7 / 8: Promote system → user, demote user → system

Both `@Transactional`, both flip-in-place rather than copy.

- **Promote** (`promoteToUserCatalogue`): asserts `catalogue == SYSTEM`, sets `catalogue = USER`, `userId = promotingUserId`. Versions and branches are preserved; existing plans referencing the recipe by ID continue to work. **Worth user review.** The HLD says "promote with one tap" — silent on copy-vs-flip. Flip is simpler and preserves cross-references. Multi-user household: first user wins; a "copy to my library" flow can be added when the household LLD lands. Publishes `RecipePromotedEvent`.
- **Demote** (`demoteToSystemCatalogue`): asserts `catalogue == USER`, sets `catalogue = SYSTEM`, retains `userId` for provenance, `deletedAt` left null — the recipe stays pool-accessible. The HLD's "soft delete; data preserved" maps cleanly to "no longer in your library, still in the pool." **Worth user review.** Publishes `RecipeArchivedEvent(cause = USER_DEMOTION)`.

### Flow 9: Archive — system catalogue 3-month inactivity

HLD rule: system-catalogue recipes unused for 3 months (no feedback, no promotion, not in any plan) are archived — retained, but excluded from the planner's index.

`ArchiveEligibilityScanner` is a `@Scheduled(cron = "0 30 3 * * *")` job in `domain/service/internal/` (configurable via `mealprep.recipe.archive.cron`). Sequence: compute `cutoff = now - 90 days` → `findArchiveEligibleSystemRecipes(cutoff)` returns IDs only → per batch of 100, `UPDATE recipe_recipes SET archived_at = now() WHERE id IN (:ids) AND archived_at IS NULL` → publish one `RecipeArchivedEvent(cause = INACTIVITY_3_MONTHS)` per ID → INFO-log the batch summary (count flagged, oldest `last_used_in_plan_at`, ms).

Archived recipes are **not** deleted. The planner pre-filter excludes them; user search includes them when `criteria.includeArchived == true`. `markUsedInPlan(recipeIds)` — called by the planner on plan composition and by the cook listener on `MealCookedEvent` — bumps `last_used_in_plan_at` so active use defers archival.

### Flow 10: Data quality enforcement

`RecipeSearchCriteriaDto.minDataQuality` lets callers (planner, browser) require a floor. `DataQualityGate` ranks `USER_VERIFIED > IMPORTED ≈ AI_GENERATED > WEB_DISCOVERED` and filters at query time. The catalogue surfaces the tier as a column; the visual indicator mentioned in the HLD is a UI concern. The planner is expected to set `minDataQuality = IMPORTED` by default for plan composition, falling back to `WEB_DISCOVERED` only when the candidate set is too small — that policy lives in the planner LLD. **Decision flagged.**

---

## Concurrency and Transactions

| Concern | Decision |
|---|---|
| `@Transactional` placement | All service-impl methods. Repositories never. Read methods `readOnly = true`; writes default REQUIRED. `RecipeWriteApi.*` are top-level — each call is its own atomic write. |
| Optimistic locking | `@Version` on `Recipe`, `RecipeBranch`, `RecipeSubstitution`. Versions and ingredients are append-only — no `@Version`. Stale `expectedOptimisticVersion` on manual edit → 409. |
| Version-bump race | `RecipeWriteApi.saveAdaptedVersion` does `findById ... FOR UPDATE` on the parent `Recipe` row, asserts `recipe.currentVersion == command.expectedParentVersionNumber` and the version-row id matches. Mismatch → `RecipeVersionConflictException`. Pipeline rebases up to 3 times. |
| Manual-vs-pipeline contention | The same `FOR UPDATE` serialises manual edits and pipeline writes. HLD also describes a 30s advisory lock for in-flight manual edits — the catalogue exposes `LockService.tryAcquire(recipeId, ttl)` backed by `pg_try_advisory_xact_lock(hash(recipeId))`. The pipeline calls it before its WriteApi sequence; failed acquire → defer. **Worth user review** — row-lock is sufficient for consistency; the advisory lock is a UX guard. |
| Substitution promotion | Runs the same `FOR UPDATE` and calls into the version-write path internally. No separate lock. |
| Document mutation | `change_diff`, `character_fingerprint`, `nutrition_per_serving`, `method_overlay` are immutable JSON blobs from the catalogue's perspective — written once, replaced wholesale by `updateCharacterFingerprint` / `updateNutritionStatus` for the two cases that mutate. |

The "rebase up to 3 times then fail" loop is the **pipeline's** concern. The catalogue's job is to throw `RecipeVersionConflictException` on each conflicting attempt.

---

## Test Plan

Unit tests use `@ExtendWith(MockitoExtension.class)`. Integration tests are `*IT.java` with Testcontainers Postgres. Names follow `methodName_scenario_expected`.

### Unit

| Class | Verifies |
|---|---|
| `RecipeServiceImplTest` | All `RecipeQueryService` / `RecipeUpdateService` happy paths and error mappings; mocked repos and helpers. |
| `RecipeWriteApiImplTest` | `saveAdaptedVersion` race-check throws on stale parent; `saveAdaptedBranch` rejects duplicate names; `saveAdaptedSubstitution` requires original ingredient to exist; `updateNutritionStatus` writes the JSON blob. |
| `VersionDifferTest` | Pure logic. Empty diff for identical bodies; ingredient ADDED/REMOVED/MODIFIED; method renumbering; metadata field changes; tag-array changes. |
| `TagSearchHelperTest` | Translates a populated `RecipeSearchCriteriaDto` to the expected `Specification` tree; `mustNotContainMappingKeys` produces the negative-existence subquery; empty criteria → no predicate. |
| `SubstitutionOverlayApplierTest`, `DeduplicationFingerprintHasherTest`, `HtmlImportParserTest`, `ArchiveEligibilityScannerTest`, `DataQualityGateTest` | Pure-logic tests over each helper — overlay step substitution, hash equality across reorders, HTML fixture parsing, cutoff math + batching, ordering / floor enforcement. |
| `Recipe*MapperTest`, `IngredientMapperTest` | MapStruct round-trips preserve all fields including child collections. |
| `IngredientListValidatorTest`, `MethodStepsValidatorTest`, `RecipeMetadataValidatorTest` | Custom validators accept valid bodies, reject precise rule violations. |

### Integration

| Class | Verifies |
|---|---|
| `RecipesControllerIT` | MockMvc full HTTP cycle: GET (200/404), POST create (201, 422 on duplicate), PUT manual edit (200/400/409 on stale optimistic version), search page shape, ProblemDetail on errors. `RecipeCreatedEvent` published exactly once after commit. |
| `RecipeVersionsControllerIT` | Version listing pagination; retrieval (200/404); diff matches `change_diff` on the row; revert produces a version with `trigger = REVERT`; cross-branch revert is 422. |
| `RecipeBranchesControllerIT`, `RecipeSubstitutionsControllerIT` | Branch creation 201, duplicate name 409, foreign `branchPointVersionId` 422. Active sub listing; promote produces a new version on `main` and marks substitution `PROMOTED`; deactivate → `INACTIVE`. |
| `RecipeImportsControllerIT` | URL import with fixture page → `dataQuality = IMPORTED`, `nutritionStatus = PENDING`; URL unreachable → 422 with no DB row written; duplicate ingredient hash → 422 with candidate ID. |
| `RecipeServiceIT` | End-to-end: create + edit produce v1, v2 with correct `parent_version_id` and `change_diff`; current-version hot read executes one statement (Hibernate stats); promote/demote round-trips; `markUsedInPlan` updates `last_used_in_plan_at`. |
| `RecipeWriteApiIT` | `saveAdaptedVersion` succeeds when parent matches, throws `RecipeVersionConflictException` on concurrent bump; rebase from a fake pipeline driver eventually succeeds; `saveAdaptedBranch` creates branch + v1 + children + publishes `RecipeAdaptedEvent`; `saveAdaptedSubstitution` publishes `outcomeType = SUBSTITUTION`. |
| `ArchiveScanIT` | System recipes with `last_used_in_plan_at < cutoff` get archived; recipes touched within window are preserved; one event per archived row. |
| `FlywayMigrationIT` | Boots Postgres, runs all recipe migrations, validates schema matches JPA mapping (`ddl-auto=validate`). pgvector extension and partial HNSW index present. |
| `EventPublicationIT` | Updates publish events only after commit. A failing test-scoped listener does not roll back state. |
| `ModuleBoundaryArchTest` (ArchUnit) | No package outside `recipe.*` imports `recipe.domain.repository.*` or `recipe.domain.entity.*`. `RecipeWriteApi` imported only by `recipe.*` test code (asserts the isolation in place once the pipeline module exists). |

---

## Out of Scope

Deferred deliberately — these belong elsewhere or to a later phase:

- **The Adaptation Pipeline** in its entirety: culinary / nutritional / constraint-satisfaction intelligence layers; the four trigger flows (IMPORT / FEEDBACK / DATA_MODEL_CHANGE / PLAN_TIME); the LLM-driven adaptation logic; `AdaptationService.enqueue*Job` and pending-change shepherding; the `NutritionalKnowledgeService` implementation; the `PlannerHint` mechanism; the adaptation trace log and `prompt_template_version` discipline; AI prompt templates; character-preservation self-check; confidence-floor handling; pending-change supersession; the optimisation budget; the conversational suggestion-box-alongside-diff flow. The catalogue exposes `RecipeWriteApi`; the pipeline is a separate hard pocket. Pending-change storage (the `recipe_pending_changes` table from the archived superseded HLD) belongs to the pipeline module — it carries pipeline-specific state (`prompt_template_version`, `change_dimension`, `expires_at`, `superseded_by`) that the catalogue should be free of.
- **Recipe discovery / scraping pipeline.** Search engine choice, web crawl, robots.txt, rate limits, the discovery → URL-import handoff. The catalogue's URL-import endpoint is the persistence sink the discovery pipeline will write into.
- **URL import AI extraction prompt.** The deterministic shared `RecipeExtractionService` (5-layer pipeline; see [recipe-extraction-pipeline.md](recipe-extraction-pipeline.md)) covers the v1 data path — the recipe URL-import adapter (`HtmlImportParser`) is the recipe module's view of it. The AI extractor (Layer 4, mid-tier model, tool-use structured output per HLD) is reserved; it activates when its prompt and schema are specified — the catalogue endpoint is unchanged.
- **Per-section AI generation of new recipes.** Enters via `RecipeWriteApi.saveAdaptedVersion` after the pipeline produces a body.
- **Recipe images.** Image storage **shipped in recipe-02a**: there is an `image_url` column, a `RecipeImageStore` SPI with a `LocalFilesystemImageStore` v1 implementation, the `RecipeImageController` upload/serve endpoints, and Tika magic-byte MIME validation. (The extraction pipeline reads a source page's `schema.org/Recipe` `image` field but does **not** yet populate `image_url` from it at import time — wiring import-time image capture is a follow-up.)
- **Embedding pipeline.** The `embedding` column is reserved with the HNSW index; `RecipeWriteApi.storeEmbedding` exposes the write path. The producer is the planner LLD's concern.
- **Frontend / UI / API consumer concerns.** Recipe browser, diff view, import progress UI, divergence-promotion prompt — all deferred to the frontend LLD.
- **Branch divergence formula.** Catalogue stores `divergence_score` and exposes `updateBranchDivergence`; the formula and threshold for "promote to standalone" prompts are pipeline LLD concerns.
- **Nutrition recalculation arithmetic.** Catalogue stores `nutrition_per_serving` and exposes `updateNutritionStatus`; USDA mapping and macro/micro computation belong to the nutrition module per [nutrition-model.md](../design/nutrition-model.md).
- **Cross-recipe adaptation.** HLD open question about plan-wide nutritional optimisation — out of scope for both the catalogue and pipeline v1.
- **Authentication.** Owned by the auth module — `userId` resolution from session/token.
- **Household member promotion semantics.** This LLD treats recipe ownership as single-user (`recipe_recipes.user_id`). When the household LLD lands, expect a follow-up migration adding `household_id` and revising the user-catalogue listing query. **Worth user review.**
