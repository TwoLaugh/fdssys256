# Adaptation Pipeline Module — LLD

*Implementation specification for the culinary-intelligence half of the Recipe System: the four trigger flows (import, post-feedback, data-model change, plan-time refine-directive), the candidate→rollup→LLM-pick pipeline, pending-change shepherding, planner-hint emission, and the `NutritionalKnowledgeService` interface. Translates the Adaptation Pipeline concern of [recipe-system.md](../design/recipe-system.md) into a buildable Spring Boot module that consumes the catalogue's `RecipeWriteApi` ([recipe.md](recipe.md)) and the dispatcher in [ai.md](ai.md).*

## Scope

This document specifies the `adaptation` module — package layout, JPA entities, Flyway migrations, repositories, service interfaces, DTOs, mappers, REST controllers, validation, events, the four trigger flows, transaction boundaries, and the test plan. Conventions defer to [lld/style-guide.md](style-guide.md); this LLD restates a rule only when the module-specific application matters.

The Recipe System HLD ([recipe-system.md](../design/recipe-system.md)) splits into two concerns: the **Catalogue** (already specced in [recipe.md](recipe.md)) and the **Adaptation Pipeline** — this module. The catalogue is pure persistence with a `RecipeWriteApi` write-seam; the pipeline owns culinary + nutritional + constraint-satisfaction reasoning, the four trigger sources, LLM dispatch, pending-change UX, and planner-hint emission. The pipeline never writes recipe data directly — it composes commands and pushes them through `RecipeWriteApi`.

This module is the recipe-scale instance of the [optimisation-loop.md](../design/optimisation-loop.md) pattern. Stage mapping:

| Loop stage | Pipeline mechanic |
|---|---|
| A — deterministic candidate generation | `CandidateGenerator` enumerates ingredient swaps, portion changes, method tweaks; `HardConstraintFilterService` drops infeasible candidates before scoring. |
| B — rollup | `ScoringEngine` produces `AdaptationCandidateDto` per candidate: macro delta, cost delta, time delta, taste alignment, character-preservation score. |
| C — choice | `AdaptationLlmInvoker` calls `AiService` (mid tier, `RECIPE_ADAPTATION` task) which picks one of N candidates with reasoning, OR auto-skips per the loop's top-2x rule. User-catalogue changes always require user approval; system-catalogue auto-applies. |
| D — refine (rare) | Recipe scale is the leaf — no downward emission. Trigger 4 receives a directive from above. |

Trigger 4 (plan-time) is **synchronous** — the planner waits. The other three are async (Triggers 1 and 3 batch-friendly; Trigger 2 sync-from-the-user's-perspective per HLD §Job sources). The actual prompt content for `RecipeAdaptationTask` is **deferred** — this LLD specifies the `AiTask<T>` shape, required context keys, and structured-output type only.

---

## Package Layout

```
com.example.mealprep.adaptation/
├── AdaptationModule.java                 facade re-exporting public service interfaces
├── api/
│   ├── controller/                       PendingChangesController, AdaptationAdminController,
│   │                                     AdapterRunHistoryController
│   ├── dto/                              records (see DTOs section)
│   └── mapper/                           PendingChange, AdaptationCandidate, AdaptationJob,
│                                         AdaptationTrace, PlannerHint mappers
├── domain/
│   ├── entity/                           AdaptationJob, PendingChange, AdaptationTrace,
│   │                                     AdaptationFingerprint, PlannerHintRecord
│   ├── repository/                       Spring Data interfaces — package-private
│   └── service/
│       ├── AdaptationService.java        public interface (enqueue + pending lifecycle)
│       ├── AdaptationQueryService.java   public interface (reads)
│       ├── NutritionalKnowledgeService.java public interface (upgradeable food science)
│       ├── AdaptationServiceImpl.java    single impl
│       └── internal/                     CandidateGenerator, ScoringEngine, AdaptationLlmInvoker,
│                                         PendingChangeStore, FingerprintRefresher,
│                                         CharacterPreservationGate, ConfidenceFloorGate,
│                                         BatchJobOrchestrator, RebaseOrchestrator,
│                                         ChangeDimensionResolver, PlannerHintEmitter,
│                                         AdaptationTraceWriter, NutritionalKnowledgeRegistry
├── ai/                                   RecipeAdaptationTask, RecipeAdaptationResponse,
│                                         AdaptationContextAssembler
├── event/                                AdaptationJob{Started,CandidateProduced,Completed,Failed}Event,
│                                         PendingChange{Created,Superseded,Accepted,Rejected}Event,
│                                         PlannerHintEmittedEvent
├── exception/                            module-root + per-failure subclasses
├── validation/                           @ValidPlannerHint, @ValidRecipeDiff
└── config/                               AdaptationConfig, AdaptationSchedulingConfig
```

`ai/` sits beside `domain/service/` — `RecipeAdaptationTask` implements the SPI from [ai.md](ai.md). `RecipeWriteApi` is constructor-injected; the `ModuleBoundaryArchTest` in `recipe.md` asserts the SPI is imported only from here, and the catalogue never imports `adaptation.*`.

---

## Database

Migrations live under `src/main/resources/db/migration/`. Module timestamps slot **after** the recipe module's `V20260601...` block — the pipeline depends on `recipe_recipes`/`recipe_versions`/`recipe_branches` existing.

```
V20260615120000__adaptation_create_jobs.sql
V20260615120100__adaptation_create_pending_changes.sql
V20260615120200__adaptation_create_traces.sql
V20260615120300__adaptation_create_fingerprints.sql
V20260615120400__adaptation_create_planner_hints.sql
R__adaptation_seed_change_dimensions.sql                  (repeatable)
R__adaptation_seed_nutritional_knowledge_v1.sql           (repeatable, reference data only)
```

### V20260615120000 — Adaptation jobs

One row per enqueued job; status walks `PENDING → RUNNING → DONE | FAILED`. `inputs` is JSONB — shape differs per source and is never filtered on inner fields.

```sql
CREATE TABLE adaptation_jobs (
    id                       uuid PRIMARY KEY,
    recipe_id                uuid NOT NULL,                  -- FK omitted: recipe may be archived post-enqueue
    user_id                  uuid NOT NULL,
    catalogue                varchar(16) NOT NULL,           -- snapshot at enqueue: 'user' | 'system'
    source                   varchar(24) NOT NULL,           -- IMPORT | FEEDBACK | DATA_MODEL_CHANGE | PLAN_TIME
    priority                 varchar(8)  NOT NULL,           -- SYNC | ASYNC | BATCH
    approval_policy          varchar(16) NOT NULL,           -- PENDING_CHANGE | DIRECT | PLAN_OVERLAY
    status                   varchar(16) NOT NULL DEFAULT 'PENDING',
    failure_reason           varchar(64),                    -- HARD_FILTER | LOW_CONFIDENCE | CHARACTER_BREAK |
                                                             -- AI_UNAVAILABLE | LLM_ERROR | REBASE_EXHAUSTED |
                                                             -- WRITE_API_CONFLICT | TIMEOUT | UNKNOWN
    failure_excerpt          varchar(512),
    inputs                   jsonb NOT NULL,                 -- source-specific payload
    prompt_template_version  varchar(40),                    -- pinned when AI is invoked
    trace_id                 uuid NOT NULL,
    parent_decision_id       uuid,                           -- non-null for refine-directive (Trigger 4)
    enqueued_at              timestamptz NOT NULL,
    started_at               timestamptz, completed_at timestamptz, duration_ms integer,
    optimistic_version       bigint NOT NULL DEFAULT 0,      -- @Version
    created_at               timestamptz NOT NULL, updated_at timestamptz NOT NULL
);
-- Worker scan in source-priority order.
CREATE INDEX idx_adaptation_jobs_status_priority
    ON adaptation_jobs (status, priority, enqueued_at) WHERE status IN ('PENDING', 'RUNNING');
CREATE INDEX idx_adaptation_jobs_recipe_time ON adaptation_jobs (recipe_id, enqueued_at DESC);
CREATE INDEX idx_adaptation_jobs_trace       ON adaptation_jobs (trace_id);
CREATE INDEX idx_adaptation_jobs_user_status ON adaptation_jobs (user_id, status) WHERE status <> 'DONE';
```

`prompt_template_version` is pinned at first-AI-touch (when `AdaptationLlmInvoker` resolves the template), not at row insert — async jobs may run under a later prompt than they enqueued under, and that drift is what makes the field useful for diagnosis.

### V20260615120100 — Pending changes

Per the recipe LLD's handoff: pending-change storage lives **here**. The catalogue stays free of pipeline-internal state. Fields per HLD shape plus the version + dimension + expiry + supersession bookkeeping the recipe LLD called out:

```sql
CREATE TABLE adaptation_pending_changes (
    id                       uuid PRIMARY KEY,
    recipe_id                uuid NOT NULL, user_id uuid NOT NULL,
    job_id                   uuid NOT NULL REFERENCES adaptation_jobs(id) ON DELETE CASCADE,
    trace_id                 uuid NOT NULL,
    change_dimension         varchar(48) NOT NULL,           -- salt_level | protein | method_simplification |
                                                             -- portion_size | flavour_balance | acid_balance |
                                                             -- texture | cooking_time | substitution_promotion
    proposed_diff            jsonb NOT NULL,                 -- mirrors RecipeDiffDto
    proposed_classification  varchar(16) NOT NULL,           -- VERSION | BRANCH
    base_version_id          uuid NOT NULL, base_branch_id uuid NOT NULL,
    reasoning                text NOT NULL, nutritional_notes text,
    confidence               numeric(4,3) NOT NULL,
    impact_score             numeric(4,3) NOT NULL,          -- ranking-pool sort key
    prompt_template_version  varchar(40) NOT NULL,
    status                   varchar(16) NOT NULL DEFAULT 'PENDING',  -- PENDING | ACCEPTED | REJECTED |
                                                             -- MODIFIED | SUPERSEDED | EXPIRED
    superseded_by            uuid REFERENCES adaptation_pending_changes(id),
    accepted_version_id      uuid, user_edits jsonb,
    created_at               timestamptz NOT NULL,
    expires_at               timestamptz NOT NULL,           -- created_at + 14 days
    resolved_at              timestamptz,
    optimistic_version       bigint NOT NULL DEFAULT 0
);
-- HLD: supersession keyed by (recipe_id, change_dimension); only one PENDING per pair.
CREATE UNIQUE INDEX idx_adaptation_pending_recipe_dim_active
    ON adaptation_pending_changes (recipe_id, change_dimension) WHERE status = 'PENDING';
CREATE INDEX idx_adaptation_pending_user_pending_rank
    ON adaptation_pending_changes (user_id, impact_score DESC, confidence DESC) WHERE status = 'PENDING';
CREATE INDEX idx_adaptation_pending_expiry
    ON adaptation_pending_changes (expires_at) WHERE status = 'PENDING';
CREATE INDEX idx_adaptation_pending_recipe_time
    ON adaptation_pending_changes (recipe_id, created_at DESC);
```

14-day expiry per HLD, enforced by a daily sweep. The 3-per-week user budget is enforced at **surface time** — the list endpoint caps + ranks; the row exists, it just isn't surfaced beyond rank 3. **Worth user review** — the alternative is a `QUEUED` status, but rank-at-read lets newly fresh proposals overtake stale ones automatically.

The partial unique index on `(recipe_id, change_dimension) WHERE status = 'PENDING'` enforces supersession atomically: in one tx the existing row flips `SUPERSEDED` (with `superseded_by = newId`), then the new row inserts. Concurrent supersessions: second writer hits the unique-constraint violation, retries, succeeds.

### V20260615120200 — Adaptation traces

HLD's adaptation trace log — one row per LLM-touch job. Lives here (not in `ai_call_log`) because it carries pipeline-specific fields (`classification_decision`, `validation_result`, `outcome`) that don't generalise.

```sql
CREATE TABLE adaptation_traces (
    id                       uuid PRIMARY KEY,
    job_id                   uuid NOT NULL UNIQUE REFERENCES adaptation_jobs(id) ON DELETE CASCADE,
    recipe_id                uuid NOT NULL, trace_id uuid NOT NULL, source varchar(24) NOT NULL,
    prompt_template_name     varchar(128) NOT NULL, prompt_template_version varchar(40) NOT NULL,
    ai_call_id               uuid,                           -- conceptually FK to ai_call_log
    inputs_snapshot          jsonb NOT NULL,
    raw_ai_response          jsonb,                          -- null when deterministic auto-skip
    candidates               jsonb NOT NULL,                 -- top-N AdaptationCandidate snapshots
    chosen_candidate_index   integer,
    classification_decision  varchar(16),                    -- VERSION | BRANCH | SUBSTITUTION | NO_CHANGE
    final_diff               jsonb,
    confidence               numeric(4,3), character_preservation_score numeric(4,3),
    validation_result        varchar(16) NOT NULL,           -- PASSED | FAILED_HARD | LOW_CONFIDENCE |
                                                             -- CHARACTER_BREAK | NO_CHANGE
    outcome_kind             varchar(24) NOT NULL,           -- VERSION_CREATED | BRANCH_CREATED |
                                                             -- SUBSTITUTION_CREATED | PENDING_CREATED |
                                                             -- NO_OP | FAILED
    outcome_target_id        uuid,
    duration_ms              integer NOT NULL,
    created_at               timestamptz NOT NULL
);
CREATE INDEX idx_adaptation_traces_prompt
    ON adaptation_traces (prompt_template_name, prompt_template_version, created_at DESC);
CREATE INDEX idx_adaptation_traces_recipe ON adaptation_traces (recipe_id, created_at DESC);
CREATE INDEX idx_adaptation_traces_trace  ON adaptation_traces (trace_id);
```

Retention: HLD says 6 months raw, then aggregate. v1 keeps raw indefinitely; future sweep deletes `raw_ai_response` blobs older than 6 months while preserving metrics columns. **Worth user review.**

### V20260615120300 — Recipe fingerprints

Per HLD the character fingerprint is "extracted once on import and refreshed only on branch creation." The fingerprint **storage** is the catalogue's (`recipe_versions.character_fingerprint`); the **derivation** is the pipeline's. This module owns a small derivation-cache so retries don't re-run the LLM:

```sql
CREATE TABLE adaptation_fingerprints (
    id                       uuid PRIMARY KEY,
    recipe_id                uuid NOT NULL, branch_id uuid NOT NULL,
    version_id               uuid NOT NULL UNIQUE,           -- version whose body was hashed
    body_hash                varchar(64) NOT NULL,           -- SHA-256 of normalised body
    fingerprint              jsonb NOT NULL,                 -- mirrors CharacterFingerprintDto
    derived_by_job_id        uuid REFERENCES adaptation_jobs(id),
    derived_at               timestamptz NOT NULL
);
CREATE UNIQUE INDEX idx_adaptation_fingerprints_recipe_branch ON adaptation_fingerprints (recipe_id, branch_id);
CREATE INDEX        idx_adaptation_fingerprints_body_hash     ON adaptation_fingerprints (body_hash);
```

The catalogue holds the fingerprint on the version row (read path); this table holds **derivation provenance** (which job, which body hash). On branch events, `FingerprintRefresher` derives, writes here, and pushes through `RecipeWriteApi.updateCharacterFingerprint`. **Worth user review** — duplication is mild waste vs. polluting the catalogue with provenance.

### V20260615120400 — Planner hints

Per HLD §Planner hints. Most hints are per-job ephemera on `AdaptationCandidateDto`, but some — overnight soak, absorption conflict — outlive a single job and need retrieval at plan composition:

```sql
CREATE TABLE adaptation_planner_hints (
    id                       uuid PRIMARY KEY,
    recipe_id                uuid NOT NULL,
    version_id               uuid NOT NULL,                  -- hint scoped to a specific version
    branch_id                uuid NOT NULL,
    hint_type                varchar(48) NOT NULL,           -- PREP_LEAD_TIME | ABSORPTION_CONFLICT |
                                                             -- NUTRITION_TRADEOFF | EQUIPMENT_OVERLAP |
                                                             -- BATCH_COMPATIBILITY
    description              text NOT NULL,
    payload                  jsonb NOT NULL,
    severity                 varchar(16) NOT NULL DEFAULT 'INFO',  -- INFO | WARN | BLOCK
    emitted_by_job_id        uuid REFERENCES adaptation_jobs(id) ON DELETE SET NULL,
    trace_id                 uuid NOT NULL,
    created_at               timestamptz NOT NULL,
    invalidated_at           timestamptz                     -- non-null hides from planner reads
);
CREATE INDEX idx_adaptation_planner_hints_version
    ON adaptation_planner_hints (version_id) WHERE invalidated_at IS NULL;
CREATE INDEX idx_adaptation_planner_hints_recipe_active
    ON adaptation_planner_hints (recipe_id)  WHERE invalidated_at IS NULL;
```

On new-version writes, `PlannerHintEmitter` invalidates hints attached to the prior version (`invalidated_at = now()`) — body change makes them stale. Re-deriving per planner read would burn LLM tokens. **Worth user review.**

### Repeatable migrations

`R__adaptation_seed_change_dimensions.sql` seeds v1 dimensions: `salt_level`, `protein`, `method_simplification`, `portion_size`, `flavour_balance`, `acid_balance`, `texture`, `cooking_time`, `substitution_promotion`. Unseeded values surface a WARN log and fall back to `general`.

`R__adaptation_seed_nutritional_knowledge_v1.sql` seeds the food-science table (pairings, bioavailability, soaks, conflicts). Schema fixed here; **rows deferred** to prompt-engineering work:

```sql
CREATE TABLE adaptation_nutritional_knowledge (
    id                       uuid PRIMARY KEY,
    knowledge_kind           varchar(32) NOT NULL,        -- PAIRING | METHOD_BIOAVAILABILITY |
                                                          -- SOAK_NEEDED | ABSORPTION_CONFLICT
    subject_keys             text[] NOT NULL,             -- ingredient_mapping_key array
    payload                  jsonb NOT NULL,              -- shape varies per knowledge_kind
    confidence_tier          varchar(16) NOT NULL,        -- HIGH | MEDIUM | LOW
    source                   varchar(64) NOT NULL,        -- 'usda' | 'who' | 'manual'
    created_at               timestamptz NOT NULL,
    UNIQUE (knowledge_kind, subject_keys)
);
CREATE INDEX idx_adaptation_nut_knowledge_kind ON adaptation_nutritional_knowledge (knowledge_kind);
CREATE INDEX idx_adaptation_nut_knowledge_subjects_gin
    ON adaptation_nutritional_knowledge USING gin (subject_keys);
```

`NutritionalKnowledgeService.lookup` reads them; the calling job folds the facts into the LLM context.

---

## Entities

Style-guide standard: UUID `@Id` application-side, `@Version Long optimisticVersion` on mutable aggregates, `@CreatedDate`/`@LastModifiedDate`, Lombok `@Getter @Setter @Builder @NoArgsConstructor(access = PROTECTED) @AllArgsConstructor`, JSONB via `@Type(JsonType.class)`.

| Entity | Notes |
|---|---|
| `AdaptationJob` | Aggregate root. `inputs` as `JsonNode`. `@Version`. |
| `PendingChange` | Aggregate root. `proposedDiff`, `userEdits` as `JsonNode`. `@Version`. Supersession atomicity comes from the partial unique index, not the version field. |
| `AdaptationTrace` | Append-only; no `@Version`. JSON fields typed `JsonNode`. |
| `AdaptationFingerprint` | UPSERT on unique `(recipeId, branchId)`; `fingerprint` as `CharacterFingerprintDocument` record-tree via JSONB. |
| `PlannerHintRecord` | Mutable on `invalidatedAt` only — no `@Version`. `payload` as `JsonNode`. |

Module-local enums: `JobSource` (`IMPORT|FEEDBACK|DATA_MODEL_CHANGE|PLAN_TIME`), `JobPriority` (`SYNC|ASYNC|BATCH`), `JobStatus`, `ApprovalPolicy` (`PENDING_CHANGE|DIRECT|PLAN_OVERLAY`), `JobFailureReason`, `PendingChangeStatus`, `AdaptationClassification` (`VERSION|BRANCH|SUBSTITUTION|NO_CHANGE`), `ValidationResult`, `OutcomeKind`, `ChangeDimension`, `HintType`, `HintSeverity`, `KnowledgeKind`.

---

## DTOs

All DTOs are Java records per the style guide.

### Core job + result shapes

```java
public record AdaptationJobDto(UUID id, UUID recipeId, UUID userId, Catalogue catalogue,
    JobSource source, JobPriority priority, ApprovalPolicy approvalPolicy,
    JobStatus status, JobFailureReason failureReason, String failureExcerpt,
    JsonNode inputs, String promptTemplateVersion, UUID traceId, UUID parentDecisionId,
    Instant enqueuedAt, Instant startedAt, Instant completedAt, Integer durationMs,
    long optimisticVersion) {}

public record AdaptationResultDto(UUID jobId, UUID recipeId,
    AdaptationClassification classification,
    Optional<UUID> versionIdCreated, Optional<UUID> branchIdCreated,
    Optional<UUID> substitutionIdCreated, Optional<UUID> pendingChangeIdCreated,
    JsonNode proposedDiff, String reasoning, String nutritionalNotes,
    boolean requiresApproval, List<PlannerHintDto> plannerHints,
    UUID traceId, BigDecimal confidence) {}
```

`AdaptationResultDto` is the sync return type of Triggers 2 and 4; for async triggers it's attached to `AdaptationJobCompletedEvent`. Optional IDs make the four classification outcomes legible without sibling result types.

### Trigger-specific request shapes

```java
public record ImportJobRequest(
    @NotNull UUID recipeId, @NotNull UUID userId,
    @NotNull Catalogue catalogue, @NotNull DataQuality dataQuality,
    @Nullable JsonNode rawImportContext, @Nullable UUID parentTraceId) {}

public record FeedbackJobRequest(
    @NotNull UUID recipeId, @NotNull UUID userId, @NotNull UUID feedbackId,
    @NotNull String feedbackText,
    @NotNull RatingDeltaDto ratingDelta,            // dimension drops biasing CandidateGenerator
    @NotNull UUID traceId, @Nullable UUID parentDecisionId) {

    public record RatingDeltaDto(BigDecimal taste, BigDecimal effortWorthIt,
                                  BigDecimal portionFit, BigDecimal repeatValue) {}
}

public record DataModelJobRequest(
    @NotNull UUID userId,
    @NotNull DataModelChangeType changeType,        // PREFERENCE | NUTRITION_TARGETS |
                                                    // PROVISIONS_BUDGET | HARD_CONSTRAINTS
    @NotNull JsonNode changeSummary,
    @NotNull @Size(max = 5000) Set<UUID> affectedRecipeIds,
    @NotNull UUID traceId) {}

public record PlannerHintRequest(
    @NotNull UUID recipeId, @NotNull UUID versionId, @NotNull UUID branchId,
    @NotNull HintType hintType,
    @NotBlank @Size(max = 500) String description,
    @NotNull JsonNode payload, @NotNull HintSeverity severity,
    @Nullable UUID emittedByJobId, @NotNull UUID traceId) {}

public record PlanTimeRefineDirectiveRequest(
    @NotNull UUID recipeId, @NotNull UUID userId, @NotNull UUID planId, @NotNull UUID slotId,
    @NotNull RefineDirectiveDto directive,           // "drop £2", "raise protein 10g"
    @NotNull PlanConstraintsSnapshotDto constraints, // pinned by planner: pantry, budget, equipment
    @NotNull UUID parentDecisionId, @NotNull UUID traceId) {

    public record RefineDirectiveDto(
        DirectiveKind kind,                          // COST_DELTA | NUTRITION_DELTA | TIME_DELTA |
                                                     // EQUIPMENT_OVERLAP | INGREDIENT_SWAP
        String description, JsonNode targetDelta) {}
}
```

### Pending-change, candidate, trace shapes

```java
public record PendingChangeDto(
    UUID id, UUID recipeId, UUID userId, UUID jobId, UUID traceId,
    ChangeDimension changeDimension, AdaptationClassification proposedClassification,
    UUID baseVersionId, UUID baseBranchId, JsonNode proposedDiff,
    String reasoning, String nutritionalNotes,
    BigDecimal confidence, BigDecimal impactScore, String promptTemplateVersion,
    PendingChangeStatus status, @Nullable UUID supersededBy, @Nullable UUID acceptedVersionId,
    @Nullable JsonNode userEdits,
    Instant createdAt, Instant expiresAt, @Nullable Instant resolvedAt,
    long optimisticVersion) {}

public record PendingChangeListItemDto(UUID id, UUID recipeId, ChangeDimension changeDimension,
    String reasoningPreview, BigDecimal confidence, BigDecimal impactScore,
    Instant createdAt, Instant expiresAt) {}

public record AcceptPendingChangeRequest(@Nullable @Valid JsonNode userEdits,
    long expectedOptimisticVersion) {}
public record RejectPendingChangeRequest(@Size(max = 200) String reasonNote) {}

public record AdaptationCandidateDto(int index, AdaptationClassification proposedClassification,
    JsonNode proposedDiff, AdaptationRollupDto rollup,
    String culinaryNotes, String nutritionalNotes,
    BigDecimal characterPreservationScore, BigDecimal estimatedConfidence,
    List<PlannerHintDto> plannerHints) {}

public record AdaptationRollupDto(
    BigDecimal macroDeltaProteinG, BigDecimal macroDeltaCarbsG, BigDecimal macroDeltaFatG, BigDecimal macroDeltaKcal,
    Map<String, BigDecimal> microDeltas,
    BigDecimal costDeltaGbp, Integer timeDeltaMins, Integer ingredientCountDelta,
    BigDecimal tasteAlignmentScore, Set<String> equipmentDelta, List<String> warnings) {}

public record PlannerHintDto(UUID id, HintType type, String description,
    JsonNode payload, HintSeverity severity) {}

public record AdaptationTraceDto(UUID id, UUID jobId, UUID recipeId, UUID traceId, JobSource source,
    String promptTemplateName, String promptTemplateVersion, @Nullable UUID aiCallId,
    JsonNode inputsSnapshot, @Nullable JsonNode rawAiResponse, JsonNode candidates,
    @Nullable Integer chosenCandidateIndex, @Nullable AdaptationClassification classificationDecision,
    @Nullable JsonNode finalDiff,
    @Nullable BigDecimal confidence, @Nullable BigDecimal characterPreservationScore,
    ValidationResult validationResult, OutcomeKind outcomeKind, @Nullable UUID outcomeTargetId,
    int durationMs, Instant createdAt) {}
```

---

## Mappers

MapStruct interfaces, `@Mapper(componentModel = "spring")`, one per entity-DTO pair: `AdaptationJobMapper` (`toDto`, `toDtos`), `PendingChangeMapper` (`toDto`, `toListItem`, `toListItems`), `AdaptationTraceMapper`, `PlannerHintMapper`, `AdaptationCandidateMapper`. Custom `@Named` qualifiers map JSONB blobs to/from `JsonNode` and typed record trees (`CharacterFingerprintDocument`, candidate snapshots). `AdaptationCandidate` is a domain record (not persisted in its own row) — produced inside `CandidateGenerator` and turned into the `candidates` JSON column on the trace via Jackson.

---

## Repositories

Package-private interfaces (no `public`); cross-module access goes through service interfaces only.

```java
interface AdaptationJobRepository extends JpaRepository<AdaptationJob, UUID> {
    Optional<AdaptationJob> findByIdAndStatusIn(UUID id, Collection<JobStatus> statuses);

    @Query("""
        select j from AdaptationJob j where j.status = 'PENDING'
         order by case j.priority when 'SYNC' then 0 when 'ASYNC' then 1 else 2 end, j.enqueuedAt""")
    List<AdaptationJob> findNextPendingJobs(Pageable pageable);

    Page<AdaptationJob> findByRecipeIdOrderByEnqueuedAtDesc(UUID recipeId, Pageable p);
    Page<AdaptationJob> findByUserIdAndStatusOrderByEnqueuedAtDesc(UUID userId, JobStatus s, Pageable p);

    @Query("select j from AdaptationJob j where j.recipeId = :rid and j.status in ('PENDING','RUNNING')")
    List<AdaptationJob> findActiveByRecipeId(@Param("rid") UUID recipeId);
}

interface PendingChangeRepository extends JpaRepository<PendingChange, UUID> {
    Optional<PendingChange> findByRecipeIdAndChangeDimensionAndStatus(UUID rid, ChangeDimension d, PendingChangeStatus s);
    List<PendingChange> findAllByUserIdAndStatus(UUID userId, PendingChangeStatus status, Sort sort);
    Page<PendingChange> findByRecipeIdOrderByCreatedAtDesc(UUID recipeId, Pageable pageable);

    @Query("select pc from PendingChange pc where pc.status = 'PENDING' and pc.expiresAt < :now")
    List<PendingChange> findExpiredPending(@Param("now") Instant now, Pageable pageable);

    // Per-week budget query: top-3 PENDING by impact × confidence.
    @Query("""
        select pc from PendingChange pc where pc.userId = :uid and pc.status = 'PENDING'
         order by pc.impactScore desc, pc.confidence desc, pc.createdAt asc""")
    List<PendingChange> findRankedPending(@Param("uid") UUID userId, Pageable pageable);
}

interface AdaptationTraceRepository extends JpaRepository<AdaptationTrace, UUID> {
    Optional<AdaptationTrace> findByJobId(UUID jobId);
    Page<AdaptationTrace> findByRecipeIdOrderByCreatedAtDesc(UUID recipeId, Pageable p);
    Page<AdaptationTrace> findByPromptTemplateNameAndPromptTemplateVersionOrderByCreatedAtDesc(
        String name, String version, Pageable p);
}

interface AdaptationFingerprintRepository extends JpaRepository<AdaptationFingerprint, UUID> {
    Optional<AdaptationFingerprint> findByRecipeIdAndBranchId(UUID recipeId, UUID branchId);
    Optional<AdaptationFingerprint> findByVersionId(UUID versionId);
    Optional<AdaptationFingerprint> findByBodyHash(String bodyHash);
}

interface PlannerHintRecordRepository extends JpaRepository<PlannerHintRecord, UUID> {
    @Query("select h from PlannerHintRecord h where h.versionId = :vid and h.invalidatedAt is null")
    List<PlannerHintRecord> findActiveForVersion(@Param("vid") UUID versionId);

    @Modifying
    @Query("update PlannerHintRecord h set h.invalidatedAt = :now " +
           "where h.versionId = :oldVersionId and h.invalidatedAt is null")
    int invalidateForOldVersion(@Param("oldVersionId") UUID oldVersionId, @Param("now") Instant now);
}

interface NutritionalKnowledgeRepository extends JpaRepository<NutritionalKnowledgeEntry, UUID> {
    @Query(value = """
        select * from adaptation_nutritional_knowledge
         where knowledge_kind = :kind and subject_keys && cast(:keys as text[])""", nativeQuery = true)
    List<NutritionalKnowledgeEntry> findIntersectingSubjects(@Param("kind") String kind,
                                                              @Param("keys") String[] subjectKeys);
}
```

The native intersect exploits the GIN index on `subject_keys`.

---

## Service Interfaces

Public services implemented by a single `AdaptationServiceImpl`. Internal helpers (`CandidateGenerator`, `ScoringEngine`, `AdaptationLlmInvoker`, `PendingChangeStore`, `RebaseOrchestrator`, `BatchJobOrchestrator`) live under `domain/service/internal/` and are package-private.

### `AdaptationService` — pipeline write surface

```java
public interface AdaptationService {

    // Trigger 1: async — returns jobId; worker processes the row.
    UUID enqueueImportJob(ImportJobRequest request);

    // Trigger 2: sync — enqueues + processes, returns result. Feedback module waits.
    AdaptationResultDto enqueueFeedbackJob(FeedbackJobRequest request);

    // Trigger 3: async batch — returns the list of enqueued job ids.
    List<UUID> enqueueDataModelChangeJobs(DataModelJobRequest request);

    // Trigger 4: sync — planner waits during Stage D; returns within ~10s or AiUnavailable.
    AdaptationResultDto runPlanTimeRefineJob(PlanTimeRefineDirectiveRequest request);

    // Pending-change lifecycle.
    PendingChangeDto acceptPendingChange(UUID pendingChangeId, AcceptPendingChangeRequest request, UUID actorUserId);
    PendingChangeDto rejectPendingChange(UUID pendingChangeId, RejectPendingChangeRequest request, UUID actorUserId);

    // Public so peer modules (notably the planner) can emit a hint they noticed.
    PlannerHintDto emitPlannerHint(PlannerHintRequest request, UUID actorUserId);

    int sweepExpiredPendingChanges();                  // scheduled-job entry; returns rows touched
}
```

Trigger 4's signature is distinct from `enqueueFeedbackJob` because the inputs are different (directive payload, not feedback text) and the outcome shape differs (always a substitution overlay, never a pending change — plan-time bypasses user approval per HLD §Job sources).

### `AdaptationQueryService` — read fan-out

```java
public interface AdaptationQueryService {
    // Pending changes — user surface.
    List<PendingChangeListItemDto> listPendingForUser(UUID userId);       // top-3 per HLD budget rule
    List<PendingChangeListItemDto> listPendingHistoryForRecipe(UUID recipeId);
    Optional<PendingChangeDto> getPendingChange(UUID pendingChangeId);

    // Job + trace history (admin/debug).
    Page<AdaptationJobDto> getJobsForRecipe(UUID recipeId, Pageable pageable);
    Page<AdaptationJobDto> getActiveJobsForUser(UUID userId, Pageable pageable);
    Page<AdaptationTraceDto> getTracesForRecipe(UUID recipeId, Pageable pageable);
    Page<AdaptationTraceDto> getTracesForPromptVersion(String name, String version, Pageable pageable);
    Optional<AdaptationTraceDto> getTraceForJob(UUID jobId);

    // Planner hints — read during plan composition.
    List<PlannerHintDto> getActiveHintsForVersion(UUID versionId);
    Map<UUID, List<PlannerHintDto>> getActiveHintsForVersions(List<UUID> versionIds);

    Optional<AdaptationResultDto> getMostRecentResultForRecipe(UUID recipeId);
}
```

`listPendingForUser` enforces the HLD's 3-per-week budget (ordered by `impactScore × confidence`, capped at 3).

### `NutritionalKnowledgeService` — upgradeable food science

Per HLD's "Interface Everything for Upgrade" pattern: v1 is a lookup table seeded by repeatable migration; v2 is a structured knowledge base; v3 is real-time tool-use. The interface is locked; the v1 implementation, concrete knowledge tables, and upgrade are deferred.

```java
public interface NutritionalKnowledgeService {

    // Pairings: "iron-rich + lemon = boosted absorption."
    List<NutritionalPairingDto> lookupPairings(List<String> ingredientMappingKeys);

    // Cooking-method bioavailability: "raw spinach > steamed for folate; reverse for lycopene."
    List<MethodBioavailabilityDto> lookupMethodEffects(String ingredientMappingKey, List<String> methods);

    // Soaks, ferments, prep windows. Surfaces as PREP_LEAD_TIME planner hints.
    List<PrepRequirementDto> lookupPrepRequirements(List<String> ingredientMappingKeys);

    // Absorption conflicts: "calcium blocks non-haem iron; don't pair this stir-fry with milk."
    List<AbsorptionConflictDto> lookupConflicts(List<String> ingredientMappingKeys);

    // Bulk pull for a whole recipe — assembles all four kinds for the LLM context.
    NutritionalKnowledgeBundleDto lookupForRecipe(UUID versionId, List<String> ingredientMappingKeys);
}

public record NutritionalKnowledgeBundleDto(
    List<NutritionalPairingDto> pairings,
    List<MethodBioavailabilityDto> methodEffects,
    List<PrepRequirementDto> prepRequirements,
    List<AbsorptionConflictDto> conflicts) {}
```

v1 reads `adaptation_nutritional_knowledge` rows; sparse hits return an empty bundle without erroring — the LLM still produces useful output without curated facts. **Worth user review** — alternative is WARN-on-cold-lookup to surface missing pairings during prompt evaluation.

---

## REST Controllers

All endpoints under `/api/v1/adaptation/...` (the user-facing pending-change controller follows recipe-system.md's user surface; admin/debug endpoints are gated to `ROLE_ADMIN`). `userId` resolved server-side from auth context per [technical-architecture.md §Frontend-Backend Contract](../design/technical-architecture.md#frontend-backend-contract).

Three controllers split per audience:

### `PendingChangesController` — user surface

| Method | Path | Body | Response | Status |
|---|---|---|---|---|
| GET    | `/api/v1/adaptation/pending-changes`                     | — | `List<PendingChangeListItemDto>` (top-3 ranked) | 200 |
| GET    | `/api/v1/adaptation/pending-changes/{id}`                | — | `PendingChangeDto` | 200 / 404 |
| POST   | `/api/v1/adaptation/pending-changes/{id}/accept`         | `AcceptPendingChangeRequest` | `PendingChangeDto` | 200 / 400 / 404 / 409 / 422 |
| POST   | `/api/v1/adaptation/pending-changes/{id}/reject`         | `RejectPendingChangeRequest` | `PendingChangeDto` | 200 / 404 / 422 |
| GET    | `/api/v1/adaptation/recipes/{recipeId}/pending-history?page=&size=` | — | `Page<PendingChangeListItemDto>` | 200 |

The list endpoint applies the 3-per-week cap and the `impact × confidence` ranking pool via `findRankedPending` with `Pageable.ofSize(3)`. The cap is a ceiling, not a floor.

`accept` requires `expectedOptimisticVersion` so two tabs can't accept a stale proposal — mismatch returns 409. Handler: assert `status == PENDING` (else 422 `PendingChangeNotPendingException`); assert `expires_at > now` (else 422 `PendingChangeExpiredException`); call `RecipeWriteApi.saveAdaptedVersion` with the diff (or `userEdits` variant when present) race-checked against catalogue's current version; update `status = ACCEPTED | MODIFIED`, set `accepted_version_id`, publish `PendingChangeAcceptedEvent`. `reject` simply transitions status.

### `AdaptationAdminController` — admin / debug

| Method | Path | Body | Response | Status |
|---|---|---|---|---|
| GET    | `/api/v1/adaptation/jobs/{jobId}`                        | — | `AdaptationJobDto` | 200 / 404 |
| GET    | `/api/v1/adaptation/jobs/{jobId}/trace`                  | — | `AdaptationTraceDto` | 200 / 404 |
| GET    | `/api/v1/adaptation/recipes/{recipeId}/jobs?page=&size=` | — | `Page<AdaptationJobDto>` | 200 |
| GET    | `/api/v1/adaptation/recipes/{recipeId}/traces?page=&size=` | — | `Page<AdaptationTraceDto>` | 200 |
| POST   | `/api/v1/adaptation/admin/sweep-expired-pending`         | — | `{ touched: int }` | 200 |
| POST   | `/api/v1/adaptation/admin/retry-failed-job`              | `{ jobId }` | `AdaptationJobDto` | 200 / 404 / 409 |
| GET    | `/api/v1/adaptation/admin/prompt-versions/{name}/{version}/traces?page=&size=` | — | `Page<AdaptationTraceDto>` | 200 |

`POST /admin/retry-failed-job` re-enqueues a `FAILED` job by copying its `inputs` to a fresh row, linked via `parent_decision_id` for audit. Useful after transient failures (AI down, conflict storm) clear.

### `AdapterRunHistoryController` — admin run history

| Method | Path | Body | Response | Status |
|---|---|---|---|---|
| GET    | `/api/v1/adaptation/run-history?source=&from=&to=&page=&size=` | — | `Page<AdaptationJobDto>` | 200 |
| GET    | `/api/v1/adaptation/run-history/by-prompt-version?name=&version=&page=&size=` | — | `Page<AdaptationTraceDto>` | 200 |

Pulls quality-dashboard data via direct repository queries; dashboard UI is frontend-LLD-owned. **No write surface** for triggering jobs from the UI in v1 — Triggers 1/2/3 are event-driven from peer modules, Trigger 4 is the planner's sync call.

### Error responses

RFC 9457 `ProblemDetail`. Module root: `AdaptationException extends MealPrepException`. Mappings handled in the project-wide `GlobalExceptionHandler`:

| Exception | Status |
|---|---|
| `AdaptationJobNotFoundException`, `PendingChangeNotFoundException`, `AdaptationTraceNotFoundException` | 404 |
| `PendingChangeNotPendingException`, `PendingChangeExpiredException`, `AdaptationLowConfidenceException`, `AdaptationCharacterBreakException`, `AdaptationHardConstraintViolationException` | 422 |
| `OptimisticLockException`, `RecipeVersionConflictException`, `PendingChangeSupersededException` | 409 |
| `AdaptationAiUnavailableException` (wraps `AiUnavailableException`) | 503 |
| `MethodArgumentNotValidException` | 400 |

`AdaptationAiUnavailableException` is the graceful-degrade bridge: when `AiService` throws `AiUnavailableException` (monthly cap closed), the pipeline degrades **block-and-prompt** per the style guide — no pending change created, job persists with `failure_reason = AI_UNAVAILABLE`, user sees "AI features paused" via Notification. **Worth user review** — `block-and-prompt` is reserved for "no useful non-AI fallback" and adaptation qualifies because deterministic candidates without LLM tie-breaking are mostly useless.

---

## Validation

Standard Jakarta annotations on request records (`@NotNull`, `@NotBlank`, `@Size`, `@Min`/`@Max`, `@Valid`). Custom validators in `validation/`:

- **`@ValidPlannerHint`** — asserts `payload` shape matches `hintType` (`PREP_LEAD_TIME` requires `payload.lead_time_hours`; `ABSORPTION_CONFLICT` requires `payload.blocked_by`).
- **`@ValidRecipeDiff`** on `AcceptPendingChangeRequest.userEdits` — asserts diff still references the same `baseVersionId`; ingredient mapping keys exist in the catalogue's known universe (cached via `IngredientKeyValidator`).

Service-layer validation in `AdaptationServiceImpl`: `acceptPendingChange` requires `status == PENDING` and `expires_at > now`; `rejectPendingChange` requires `status == PENDING` (already-rejected is 422, not idempotent 200); `enqueueDataModelChangeJobs` requires `affectedRecipeIds.size() ≤ 5000`.

Validation failures bubble up as 400 (request-level) or 422 (service-level).

---

## Events

### Published

```java
public record AdaptationJobStartedEvent(UUID jobId, UUID recipeId, UUID userId, JobSource source,
    JobPriority priority, UUID traceId, Instant occurredAt) {}

public record AdaptationCandidateProducedEvent(UUID jobId, UUID recipeId, int candidateCount,
    BigDecimal topCandidateScore, UUID traceId, Instant occurredAt) {}

public record AdaptationJobCompletedEvent(UUID jobId, UUID recipeId, OutcomeKind outcomeKind,
    @Nullable UUID outcomeTargetId, AdaptationClassification classification,
    BigDecimal confidence, UUID traceId, Instant occurredAt) {}

public record AdaptationJobFailedEvent(UUID jobId, UUID recipeId, JobFailureReason reason,
    String excerpt, UUID traceId, Instant occurredAt) {}

public record PendingChangeCreatedEvent(UUID pendingChangeId, UUID recipeId, UUID userId,
    ChangeDimension dimension, BigDecimal confidence, BigDecimal impactScore,
    UUID traceId, Instant occurredAt) {}

public record PendingChangeSupersededEvent(UUID supersededId, UUID supersedingId, UUID recipeId,
    ChangeDimension dimension, UUID traceId, Instant occurredAt) {}

public record PendingChangeAcceptedEvent(UUID pendingChangeId, UUID recipeId, UUID userId,
    UUID resultingVersionId, boolean wasModified, UUID traceId, Instant occurredAt) {}

public record PendingChangeRejectedEvent(UUID pendingChangeId, UUID recipeId, UUID userId,
    UUID traceId, Instant occurredAt) {}

public record PlannerHintEmittedEvent(UUID hintId, UUID recipeId, UUID versionId,
    HintType type, HintSeverity severity, UUID traceId, Instant occurredAt) {}
```

`AdaptationCandidateProducedEvent` is emitted **after Stage A/B**, before Stage C, so admin dashboards see candidate volume separately from outcome — useful signal for "lots of candidates, none chosen" prompt regressions. **Worth user review** — could be inferred from the trace; explicit event is cheaper for streaming.

`RecipeAdaptedEvent` is **published by the catalogue side** (per [recipe.md](recipe.md)), not here. The pipeline publishes `AdaptationJobCompletedEvent`; the catalogue's `RecipeWriteApi` write publishes `RecipeAdaptedEvent`. Subscribers needing both join via `traceId` per the project-wide pattern.

All events published via `ApplicationEventPublisher` after commit; listeners use `@TransactionalEventListener(phase = AFTER_COMMIT)`. Notification subscribes to `PendingChangeCreatedEvent` (proposal ready) and `AdaptationJobFailedEvent` filtered to `reason = AI_UNAVAILABLE` (block-and-prompt surface).

### Consumed

All `@TransactionalEventListener(AFTER_COMMIT)`:
- `onRecipeImported(RecipeCreatedEvent)` — Trigger 1 entry; calls `enqueueImportJob`.
- `onPreferenceChanged(PreferenceChangedEvent)`, `onHardConstraintsChanged(HardConstraintsChangedEvent)`, `onNutritionTargetsChanged(NutritionTargetsChangedEvent)`, `onProvisionsBudgetChanged(ProvisionsBudgetChangedEvent)` — Trigger 3 entries. Each listener resolves the affected recipe set via the publishing module's `QueryService` (filtering user catalogue), composes a `DataModelJobRequest`, calls `enqueueDataModelChangeJobs`.

`FeedbackProcessedEvent` is **not** consumed — the feedback module calls `enqueueFeedbackJob` directly per HLD §Job sources ("Feedback is **not** an event"); the result must be returned synchronously to confirm to the user. The plan-time directive is also **not** an event — the planner calls `runPlanTimeRefineJob` synchronously during Stage D per [optimisation-loop.md §Sync vs event](../design/optimisation-loop.md#sync-vs-event).

---

## Configuration

`AdaptationConfig` (`@ConfigurationProperties(prefix = "mealprep.adaptation") @Validated`). Values are starting defaults — **specific tuning is implementation-phase**.

```java
public record AdaptationConfig(
    @Min(1) @Max(20) int candidateTopN,                                    // default 5 per loop
    @Positive int planTimeTimeoutMs,                                        // default 10000
    @Positive int feedbackTimeoutMs,                                        // default 8000
    @Positive int importTimeoutMs,                                          // default 12000 (async — generous)
    @Min(0) @Max(20) int maxRebaseAttempts,                                 // default 3 per HLD
    @Min(0) @Max(7) int pendingChangeBudgetPerWeek,                         // default 3 per HLD
    @Positive int pendingChangeExpiryDays,                                  // default 14 per HLD
    @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal lowConfidenceFloor,    // default 0.50 per HLD
    @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal autoSkipTopRatio,      // default 2.00 — top scores >2x runner-up
    @NotNull Map<JobSource, BudgetConfig> sourceBudgets,                    // declared structure; values tuned later
    @Positive int recipeAdvisoryLockSeconds,                                // default 30 per HLD
    @NotBlank String pendingExpirySweepCron,                                // default "0 0 4 * * *"
    @NotBlank String batchOrchestratorCron) {                               // default "0 30 4 * * *"

    public record BudgetConfig(
        @Positive Integer maxConcurrentJobs,
        @PositiveOrZero BigDecimal dailyCostBudgetGbp,
        @Positive Integer maxJobsPerHour) {}
}
```

`sourceBudgets` is per-trigger LLM cost budget — distinct buckets reflect HLD's per-source cost shape (FEEDBACK sync per user; DATA_MODEL_CHANGE 50%-cost batch). **Default values deferred** to the first month of real traffic. Defaults shown above land in `application.properties` as `mealprep.adaptation.*` keys.

---

## The four trigger flows

Each flow follows the loop's A→B→C structure. The shared worker pipeline is described once below; the trigger sections describe only the source-specific differences.

### Shared worker pipeline (Stages A → B → C → Apply)

```
1. Acquire LockService.tryAcquire(recipeId, 30s) — single-flight per recipe per HLD.
2. Load Recipe + current RecipeVersion (with body) + CharacterFingerprint via RecipeQueryService.
   Load PreferenceQueryService.getSoftPreferences, NutritionFloorGateService targets,
   NutritionalKnowledgeService.lookupForRecipe.
3. Stage A — CandidateGenerator enumerates ingredient swaps, portion adjusts, method tweaks
   (source-biased; see triggers). HardConstraintFilterService.filterRecipes drops infeasible
   candidates BEFORE scoring — never bypassed; the safety net is invariant per HLD §Guardrails.
4. Stage B — ScoringEngine assigns AdaptationRollupDto per surviving candidate; top-N=5 selected.
5. Stage C — AdaptationLlmInvoker builds RecipeAdaptationTask, dispatches via AiService.
       AiUnavailableException → job FAILED(reason=AI_UNAVAILABLE); Notification surfaces
       "AI features paused" (block-and-prompt per style guide).
       Auto-skip Stage C when top score > 2× runner-up per loop pattern (logged as
       deterministic-skip in the trace).
6. Validation gates (in order, all must pass):
       ConfidenceFloorGate: confidence < 0.50 → defensive flag for user review even on
           SYSTEM catalogue per HLD failure mode.
       CharacterPreservationGate: score < 0.6 → reject; if AI also returned classification=BRANCH
           with high coherence, treat as branch candidate via saveAdaptedBranch.
       HardConstraintFilterService.checkRecipe re-runs against the FINAL diff — guards against
           the LLM stitching a candidate together post-hoc.
7. Apply per approval_policy:
       PENDING_CHANGE (USER catalogue) → PendingChangeStore.create with dimension resolved
           by ChangeDimensionResolver; supersedes any existing PENDING for (recipe_id, dimension).
       DIRECT (SYSTEM catalogue) → RecipeWriteApi.saveAdaptedVersion or saveAdaptedBranch;
           RebaseOrchestrator retries on RecipeVersionConflictException up to 3 times,
           then fails with REBASE_EXHAUSTED per HLD.
       PLAN_OVERLAY (Trigger 4) → RecipeWriteApi.saveAdaptedSubstitution.
8. AdaptationTraceWriter writes the trace row in REQUIRES_NEW so a rolled-back outer tx
   still keeps the diagnostic — same pattern as AI module's call ledger.
9. FingerprintRefresher upserts adaptation_fingerprints when a branch was created.
10. Update AdaptationJob.status = DONE; publish AdaptationJobCompletedEvent. PlannerHintEmitter
    invalidates hints attached to the prior version (body change makes them stale).
```

The enqueue method is `@Transactional` — row insert + trace_id allocation in one tx. Worker processing is **not** transactional; each DB-touching step opens its own short tx (LLM call durations don't fit transactions, same shape as the AI module). The async worker is a `@Async` listener of an internal `JobReadyEvent` published after enqueue commit; a `@Scheduled` poll-fallback re-picks orphan PENDING jobs older than 5 minutes after JVM restart.

### Trigger 1: Import (`enqueueImportJob`, async)

**Source.** `RecipeCreatedEvent` from manual create / URL import / AI generation / online discovery.

**Differences from shared pipeline.** Approval policy is catalogue-derived (USER → PENDING_CHANGE; SYSTEM → DIRECT). HLD outcome bias: usually NO_CHANGE; sometimes a small VERSION. Fingerprint may be absent on first-import — v1 derives it inline within the adaptation prompt (Decisions §3); v2 splits into a sibling `RecipeFingerprintTask`. `priority = ASYNC`, `importTimeoutMs` default 12 000 (generous since no user is waiting).

### Trigger 2: Feedback (`enqueueFeedbackJob`, sync)

**Source.** Feedback module synchronously calls this method per HLD §Job sources ("Feedback is **not** an event"). The user is still in the rating UI; the pipeline returns the result (typically a pending change) so the feedback module can confirm back.

**Differences.** `priority = SYNC`; lock-acquire failure → wait + retry once within timeout, else throw `LockTimeoutException` (feedback surfaces "couldn't propose; please retry"). Stage A inputs additionally include `feedbackText` (LLM context) and `ratingDelta` (biases CandidateGenerator: low taste → flavour-balance candidates; low effort-worth-it → method-simplification). The pending change's `change_dimension` is biased by the rating delta — a "too salty" rating resolves to `salt_level` even if the AI's diff touches a side ingredient.

`feedbackTimeoutMs = 8000` is a hard ceiling on Stage C. On timeout the dispatcher throws `AiCallFailedException`, the job fails, and the feedback module presents "couldn't generate a proposal right now — your rating was saved." **Worth user review** — alternative is to fall back to the deterministic top-scored candidate, but recipe adaptation without LLM tie-breaking risks suggesting culinary nonsense (HLD §Failure modes).

### Trigger 3: Data-model change (`enqueueDataModelChangeJobs`, async batch)

**Source.** This module's listeners on `PreferenceChangedEvent`, `HardConstraintsChangedEvent`, `NutritionTargetsChangedEvent`, `ProvisionsBudgetChangedEvent` filter the user's catalogue to affected recipes (HARD_CONSTRAINTS: recipes with the new allergen; PREFERENCE: overlap with changed taste fields; NUTRITION_TARGETS: per-serving violators; PROVISIONS_BUDGET: cost over weekly budget) and call `enqueueDataModelChangeJobs(affectedRecipeIds)`.

**Differences.** `priority = BATCH`. `BatchJobOrchestrator` (`@Scheduled` daily) pulls PENDING-BATCH jobs in `enqueued_at` order, batches of 50, runs the shared pipeline serially. Per-job lock check via `findActiveByRecipeId`: if a SYNC/ASYNC job is RUNNING for the same recipe, BATCH defers that recipe to the next sweep — FEEDBACK proceeds first per HLD §Concurrency.

The HLD specifies the Anthropic Batches API (50% cost, 24h SLA) for this trigger. **Deferred** — v1 runs serially via the standard sync API. Schema, enqueue behaviour, and events are batch-friendly so the swap is localised. **Worth user review** — the cost win is real but pulls in async polling, idempotency, and observability changes warranting their own LLD chapter.

### Trigger 4: Plan-time refine-directive (`runPlanTimeRefineJob`, sync)

**Source.** Planner's Stage D per [meal-planner.md §Stage D](../design/meal-planner.md#stage-d--refine-directives). Directive carries explicit target (recipe + slot) and desired delta per [optimisation-loop.md §Credit assignment](../design/optimisation-loop.md#credit-assignment).

**Differences.** `priority = SYNC`, `approval_policy = PLAN_OVERLAY`. `parent_decision_id` set from the planner's Stage D decision; `trace_id` threaded from the planner. Lock-acquire failure → throw — planner Stage D handler treats this as the loop's infeasibility-escalate signal. Stage A is **directive-narrowed** by `DirectiveKind`:

| DirectiveKind | Candidate generation |
|---|---|
| `COST_DELTA` | Ingredient-swap candidates ranked by cost reduction. |
| `NUTRITION_DELTA` | Swaps moving the named nutrient in the requested direction. |
| `TIME_DELTA` | Method simplification candidates. |
| `EQUIPMENT_OVERLAP` | Swaps that drop the conflicting equipment. |
| `INGREDIENT_SWAP` | Exact ingredient targeted; produce 3 alternative substitutes. |

Stage C dispatches with `mode = "PLAN_TIME_REFINE"` — the prompt picks the candidate that best satisfies the delta with minimal character disruption. **Outcome is always `SUBSTITUTION`** — plan-time changes are scoped to the plan; the master recipe never mutates. `RecipeWriteApi.saveAdaptedSubstitution` writes the overlay; the planner records it against the slot via `recordSubstitutionApplication`.

If the LLM returns `classification = NO_CHANGE` (low confidence, character preservation impossible), the planner reads this as the loop's infeasibility signal — picks a different candidate plan from its top-N or stops refining per the iteration budget. The pipeline does **not** escalate further down — recipe scale is the leaf.

---

## The `RecipeAdaptationTask` — `AiTask` shape

The `AiTask<T>` SPI lives in [ai.md](ai.md); this module supplies one concrete implementation in `adaptation/ai/`.

```java
public final class RecipeAdaptationTask implements AiTask<RecipeAdaptationResponse> {

    public RecipeAdaptationTask(AdaptationJob job, AdaptationContext context, PromptRef ref) { ... }

    @Override public TaskType getTaskType() { return TaskType.RECIPE_ADAPTATION; }

    @Override public String getSystemPrompt() { /* loaded via PromptTemplateService for the system part */ }
    @Override public PromptRef getUserPromptRef() { return ref; }    // "adaptation/recipe-adaptation"
    @Override public Map<String, Object> getContext() {
        return Map.of(
            "mode",                 context.mode(),                    // "IMPORT" | "FEEDBACK" | "DATA_MODEL_CHANGE" | "PLAN_TIME_REFINE"
            "recipe",               context.recipeSummary(),            // current version body, fingerprint
            "candidates",           context.candidates(),               // pre-vetted N from Stage A/B
            "softPreferences",      context.softPreferences(),
            "hardConstraintsHash",  context.hardConstraintsHash(),
            "nutritionTargets",     context.nutritionTargets(),
            "knowledgeBundle",      context.knowledgeBundle(),          // NutritionalKnowledgeService output
            "feedbackText",         context.feedbackText(),             // null unless mode = FEEDBACK
            "ratingDelta",          context.ratingDelta(),              // null unless mode = FEEDBACK
            "directive",            context.directive(),                // null unless mode = PLAN_TIME_REFINE
            "dataModelChange",      context.dataModelChange()           // null unless mode = DATA_MODEL_CHANGE
        );
    }
    @Override public ToolDefinition getToolSchema() { return ToolDefinitionFactory.from(RecipeAdaptationResponse.class); }
    @Override public Class<RecipeAdaptationResponse> getResponseType() { return RecipeAdaptationResponse.class; }
    @Override public UUID getUserId()  { return job.getUserId(); }
    @Override public UUID getTraceId() { return job.getTraceId(); }
    @Override public Optional<Duration> getTimeoutOverride() {
        return Optional.of(switch (job.getSource()) {
            case PLAN_TIME -> Duration.ofMillis(config.planTimeTimeoutMs());
            case FEEDBACK  -> Duration.ofMillis(config.feedbackTimeoutMs());
            default        -> Duration.ofMillis(config.importTimeoutMs());
        });
    }
}

public record RecipeAdaptationResponse(
    int chosenCandidateIndex,                        // 0 .. N-1; -1 means NO_CHANGE
    AdaptationClassification classification,
    String reasoning,
    String nutritionalNotes,
    BigDecimal confidence,
    BigDecimal characterPreservationScore,
    @Nullable RecipeDiffDto refinedDiff,             // optional refinement of the chosen candidate
    List<PlannerHintDto> plannerHints) {}
```

**Prompt content deferred.** This LLD locks the context keys, structured-output type, per-mode timeouts, and dispatch contract. The prompt file `prompts/adaptation/recipe-adaptation.txt` is a stub until the prompt engineer ships v1. `AdaptationContextAssembler` is the helper that takes an `AdaptationJob` + trigger inputs and produces the typed `AdaptationContext` — separates context-loading (calling peer modules' QueryServices) from task dispatch.

---

## Concurrency and Transactions

| Concern | Decision |
|---|---|
| Enqueue methods | `@Transactional` — row insert + trace allocation + JobReadyEvent publish in one tx. |
| Worker pipeline | Multi-step (load → AI → apply → trace); **not** wrapped in a single tx — LLM durations don't fit. Each DB-touching step opens its own short tx. Trace write is `REQUIRES_NEW` so a rolled-back outer tx still keeps the diagnostic row, mirroring `AiCallRecorder` in [ai.md](ai.md). |
| Read methods | `@Transactional(readOnly = true)` on all `AdaptationQueryService`. |
| Optimistic locking | `@Version` on `AdaptationJob`, `PendingChange`. None on append-only `AdaptationTrace`/`AdaptationFingerprint` or single-column-update `PlannerHintRecord.invalidatedAt`. |
| Single-flight per recipe | `core.lock.LockService.tryAcquire(recipeId, 30s)` per HLD. Failed acquire: Trigger 1 defers + retry; Trigger 2 throws to feedback; Trigger 3 defers to next sweep; Trigger 4 throws to planner Stage D as infeasibility-escalate. |
| Pending-change supersession | Partial unique `(recipe_id, change_dimension) WHERE status = 'PENDING'` serialises atomically. One-tx flow: `UPDATE existing SET status = 'SUPERSEDED'; INSERT new`. Concurrent race → loser hits unique constraint, retries via `DataIntegrityViolationException` handler. |
| WriteApi conflicts | `RebaseOrchestrator` catches `RecipeVersionConflictException` and rebases up to `maxRebaseAttempts = 3`; after 3 → `REBASE_EXHAUSTED`. |
| Manual edit vs pipeline | Advisory lock + catalogue's `FOR UPDATE` row-lock on `Recipe` together cover the contention: advisory keeps LLM work from racing manual edits; row-lock keeps the actual write atomic. |

No other long-held locks; long transactions avoided per the AI module's pattern.

---

## Observability

Every job logs at INFO with `traceId`, `userId`, `recipeId`, `source`, `priority`, `status`, `durationMs`. Per-stage timings at DEBUG. Full prompt at DEBUG on success; durable copy in `adaptation_traces.raw_ai_response`. MDC keys: `traceId`, `userId`, `recipeId`, `jobId`, `source`; propagated across `@Async` via `MdcTaskDecorator`.

Quality dashboard reads:
- `adaptation_jobs` joined to pending-change status for accept/reject/modify rate.
- `adaptation_traces` for per-prompt-version metrics (rating delta joins catalogue version → feedback module's per-version rating).
- `adaptation_planner_hints` for hint-emission rate per source.

A future scheduled job aggregates per-prompt-version metrics and trims `raw_ai_response` blobs per HLD's 6-month rule — declared, mechanically delivered later.

---

## Test Plan

Unit tests use `@ExtendWith(MockitoExtension.class)`. Integration tests are `*IT.java` with Testcontainers Postgres. Anthropic is **always** mocked via the `TestAiService` bean from [ai.md](ai.md). Names follow `methodName_scenario_expected`.

### Unit

| Class | Verifies |
|---|---|
| `AdaptationServiceImplTest` | Each trigger's enqueue happy path; status transitions; lock-acquire failure surfaces correctly. Mocks `LockService`, helpers, `RecipeWriteApi`. |
| `CandidateGeneratorTest` | Each strategy (protein swap, portion adjust, method tweak, ingredient substitution) emits the expected candidate set; zero candidates after hard-filter; single-candidate auto-top. |
| `ScoringEngineTest` | Rollup arithmetic; taste alignment from soft-preference fixture; equipment delta against provisions snapshot. |
| `AdaptationLlmInvokerTest` | Right context keys per source; right timeout per source; `AiUnavailableException` surfaces as `AdaptationAiUnavailableException`. |
| `PendingChangeStoreTest` | Create; supersede on dimension collision; supersession atomicity (concurrent insert → second-writer retry); expiry only flips when `expires_at < now`; ranked query returns top-3 by `(impactScore DESC, confidence DESC, createdAt ASC)`. |
| `ChangeDimensionResolverTest`, `CharacterPreservationGateTest`, `ConfidenceFloorGateTest` | Pure-logic mappings; gate decisions per threshold and catalogue type. |
| `RebaseOrchestratorTest` | First-try success; up to 3 rebases on `RecipeVersionConflictException`; throws `REBASE_EXHAUSTED` on attempt 4. |
| `BatchJobOrchestratorTest`, `FingerprintRefresherTest`, `PlannerHintEmitterTest`, `NutritionalKnowledgeRegistryTest` | Pure-logic tests over each helper — batch sizing + same-recipe deferral; body-hash idempotency; hint invalidation; lookup composition. |
| `RecipeAdaptationTaskTest`, `AdaptationContextAssemblerTest` | Context map per source; tool schema from `RecipeAdaptationResponse` matches expected JSON; assembler pulls correct slices. |
| `Adaptation*MapperTest` | MapStruct round-trips preserve all fields including JSON blobs. |

### Integration

| Class | Verifies |
|---|---|
| `AdaptationServiceIT` | E2E with `TestAiService`: `enqueueImportJob` inserts row + trace, worker processes, writes through `RecipeWriteApi` (catalogue mocked at boundary), publishes `AdaptationJobCompletedEvent` exactly once. |
| `Trigger1ImportFlowIT`, `Trigger2FeedbackFlowIT`, `Trigger3DataModelFlowIT`, `Trigger4PlanTimeFlowIT` | Full flow per trigger against real catalogue. Trigger 1: USER → pending change, SYSTEM → new version. Trigger 2: sync return within timeout, dimension biased by ratingDelta. Trigger 3: `HardConstraintsChangedEvent` → BatchJobOrchestrator processes affected recipes serially. Trigger 4: `COST_DELTA` directive → `recipe_substitutions` row written within `planTimeTimeoutMs`. |
| `PendingChangesControllerIT` | MockMvc full HTTP cycle: GET top-3 ranked, accept/reject (200, 422 expired, 409 stale version, 422 superseded). `PendingChangeAcceptedEvent` published exactly once after commit. |
| `AdaptationAdminControllerIT` | Admin endpoints gated to `ROLE_ADMIN`; 403 without; happy paths return expected pages. |
| `AdaptationLockingIT` | Two concurrent jobs on the same recipe — second blocks on advisory lock ≤ 30s then errors per trigger contract. |
| `PendingChangeSupersessionIT` | Concurrent inserts for `(recipe_id, dimension)` — one wins; loser hits unique constraint, retries, succeeds with the existing row flipped SUPERSEDED. |
| `RebaseOrchestratorIT` | Manual edit lands mid-pipeline → WriteApi throws conflict → orchestrator rebases and the second attempt succeeds; after 3 failures the job fails `REBASE_EXHAUSTED`. |
| `AdaptationAiUnavailableIT` | TestAiService throws `AiUnavailableException`; jobs FAIL with `AI_UNAVAILABLE`; `AdaptationJobFailedEvent` published; no PendingChange or version created. |
| `PendingChangeExpirySweepIT` | Backdated PENDING rows past `expires_at` flip EXPIRED; in-window rows untouched. |
| `PlannerHintInvalidationIT` | New version V2 invalidates V1 hints; `getActiveHintsForVersion` returns the right set per version. |
| `FlywayMigrationIT` | Migrations run after recipe migrations; schema matches JPA mapping (`ddl-auto=validate`). |
| `EventPublicationIT` | Events fire exactly once, after commit; failing test-listener doesn't roll back state. |
| `ModuleBoundaryArchTest` (ArchUnit) | No package outside `adaptation.*` imports `adaptation.domain.repository.*` or `adaptation.domain.entity.*`. `RecipeWriteApi` is the only `recipe.*` symbol injected here; `AiTask` SPI is the only `ai.*` symbol injected here. |

---

## Out of Scope

Deferred deliberately — these belong elsewhere or to a later phase:

- **The actual AI prompt content** for `RecipeAdaptationTask` (system message + user template + structured-output schema text). The placeholder `prompts/adaptation/recipe-adaptation.txt` ships with a stub. This LLD specifies only the dispatch contract, context keys, and structured-output Java type.
- **The fingerprint-derivation prompt** — `FingerprintRefresher` runs inline within the adaptation prompt for v1 (Decisions §3) or via a sibling `RecipeFingerprintTask` for v2. Dispatch shape locked here; prompt deferred.
- **Anthropic Batches API integration** for Trigger 3. v1 runs serially via the sync API; the Batches API would land as a sibling `AiBatchService` per [ai.md §Out of Scope](ai.md).
- **Specific `NutritionalKnowledgeService` implementations** — interface and storage shape locked; the rows in `adaptation_nutritional_knowledge` (bioavailability, pairings) are reference data filled in alongside prompt engineering.
- **Specific LLM cost budgeting per trigger.** `BudgetConfig` structure declared; default values are implementation-phase tuning.
- **Per-prompt-version aggregate roll-up table** and **raw-trace retention sweep** — HLD's 6-month policy declared; mechanically delivered later.
- **Frontend / UI / API consumer concerns.** Pending-change review UI, side-by-side diff, conversational suggestion box, dashboards — frontend LLD.
- **Cross-recipe adaptation** (HLD open question). Per-recipe focus is intentional in v1; cross-recipe directives would land as a new `JobSource` + context shape.
- **Periodic fingerprint re-extraction** for long-lived recipes (HLD open question).
- **Per-user prompt variants**, **streaming**, **multi-turn tool-use** — per the AI module's deferral.
- **Authentication.** `userId` / `actorUserId` resolution owned by the auth module.
- **The conversational suggestion-box-alongside-diff flow** (HLD §Approval Model). Lives in a separate frontend-driven surface calling `AiService.execute` with a different `TaskType`.

---

## Decisions where the HLD is silent (worth user review)

1. **Pending-change storage lives here, not the catalogue.** Per recipe LLD's explicit handoff — `prompt_template_version`, `change_dimension`, `expires_at`, `superseded_by` are pipeline-internal.
2. **The 3-per-week budget is rank-at-read**, not status-at-write — fresh high-impact proposals overtake stale ones without status churn.
3. **Fingerprint derivation runs inline within the adaptation prompt** for v1 (one AI call returns adaptation + refreshed fingerprint when classification = BRANCH). v2 splits into `RecipeFingerprintTask`.
4. **`adaptation_fingerprints` duplicates `recipe_versions.character_fingerprint`** — provenance here, read path there. Mild waste vs. polluting catalogue with provenance.
5. **`RecipeAdaptedEvent` is catalogue-published**, not here. Pipeline publishes `AdaptationJobCompletedEvent`; subscribers join via `traceId`.
6. **`AdaptationAiUnavailableException` follows `block-and-prompt`** — adaptation has no useful non-AI fallback.
7. **Trigger 3 v1 is serial via the sync API**, not the Anthropic Batches API. Schema/events are batch-friendly so later swap is localised.
8. **`feedbackTimeoutMs` = 8s with no deterministic fallback** — adaptation without LLM tie-breaking risks suggesting culinary nonsense.
9. **Plan-time refine output is always a substitution** — plan-scoped, master recipe doesn't mutate.
10. **Per-trigger lock-acquire behaviour differs**: Trigger 1 defers + retry; Trigger 2 throws to feedback; Trigger 3 defers to next sweep; Trigger 4 throws to planner Stage D as infeasibility-escalate.
11. **`AdaptationCandidateProducedEvent`** explicit (not inferred from trace) — cheap signal for streaming dashboards on prompt regressions.
12. **Hint invalidation on new version** is automatic — body change makes hints stale; re-deriving per planner read would burn tokens.
