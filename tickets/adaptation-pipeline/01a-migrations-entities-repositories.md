# Ticket: adaptation-pipeline — 01a Migrations + Entities + Repositories + Module Skeleton

## Summary

Lay the **database + JPA + repository foundation** for the adaptation pipeline module per [lld/adaptation-pipeline.md §Database (lines 64-266)](../../lld/adaptation-pipeline.md), [§Entities (lines 270-282)](../../lld/adaptation-pipeline.md), [§Repositories (lines 412-479)](../../lld/adaptation-pipeline.md). Ships the **six Flyway migrations** under `db/migration/V20260615120000__...` → `V20260615120400__...` + two `R__...` repeatable seeds; the **five JPA entities** (`AdaptationJob`, `PendingChange`, `AdaptationTrace`, `AdaptationFingerprint`, `PlannerHintRecord`) + the small read-only `NutritionalKnowledgeEntry` entity; the **six package-private repositories** verbatim from LLD lines 417-477; the **module enums** (`JobSource`, `JobPriority`, `JobStatus`, `ApprovalPolicy`, `JobFailureReason`, `PendingChangeStatus`, `AdaptationClassification`, `ValidationResult`, `OutcomeKind`, `ChangeDimension`, `HintType`, `HintSeverity`, `KnowledgeKind`); the **`MealPrepException`-rooted module exception class** `AdaptationException` plus the **per-failure exception subclasses** (LLD lines 624-632); the **`AdaptationConfig` `@ConfigurationProperties`** stub (LLD lines 705-727) with default values; and the **module facade** `AdaptationModule.java` re-exporting (initially-empty) public service interface placeholders so the rest of the project compiles.

**No service logic, no controllers, no AI dispatch, no events** — those land in 01b–01f. 01a is pure schema + persistence skeleton.

**Module package**: `com.example.mealprep.adaptation` per [LLD line 27](../../lld/adaptation-pipeline.md). The user's planning brief mentioned `com.example.mealprep.optimiser` "per LLD" — that's an error in the brief; the LLD says `adaptation`. **01a uses `adaptation`.**

**LLD divergence note** — **No service-interface package created in 01a**. LLD line 40 names `AdaptationService.java` + `AdaptationQueryService.java` + `NutritionalKnowledgeService.java` in `domain/service/`. 01a defers all three to 01b (which is the **contract-lock ticket** the planner + feedback sibling agents need). The reason: the public interface signatures themselves carry cross-module value, and shipping them in the same ticket as private entities risks the contract leaking impl detail. 01a creates the empty `domain/service/` package directory (placeholder `package-info.java`) so 01b can land cleanly. **Worth user review.**

**Defers** (still out of scope after 01a):

- All `domain/service/`, `domain/service/internal/`, `ai/`, `event/`, `validation/`, `api/` classes → 01b–01f.
- `prompts/adaptation/recipe-adaptation.txt` template file → 01e.
- The `recipe_advisory_lock` `LockService` integration → 01c (worker pipeline).
- Decision-log writes → 01c (worker pipeline).
- The unique-constraint retry handler for `PendingChange` supersession → 01d (`PendingChangeStore`).
- ArchUnit `ModuleBoundaryArchTest` — 01f (last so it can assert against the full module).

01a unblocks **01b** (interfaces import entities + enums) and **every other adaptation-pipeline ticket** (all need the tables / entities).

## Behavioural spec

### Migration timestamps and ordering

1. Migrations live under `src/main/resources/db/migration/`. Timestamps slot **after** the recipe module's `V20260601...` block per [LLD line 66](../../lld/adaptation-pipeline.md) — the pipeline depends on `recipe_recipes` + `recipe_versions` + `recipe_branches` already existing (though it does not add foreign keys onto them, since recipes can be archived post-enqueue per LLD line 86 comment).
2. The six versioned migrations (in order):
   - `V20260615120000__adaptation_create_jobs.sql`
   - `V20260615120100__adaptation_create_pending_changes.sql`
   - `V20260615120200__adaptation_create_traces.sql`
   - `V20260615120300__adaptation_create_fingerprints.sql`
   - `V20260615120400__adaptation_create_planner_hints.sql`
   - `V20260615120500__adaptation_create_nutritional_knowledge.sql` (carved out from LLD line 250-264, separated so the repeatable seed has its own DDL home)
3. The two repeatable migrations:
   - `R__adaptation_seed_change_dimensions.sql` — seeds the v1 `change_dimension` lookup. **No table** — the seed is a comment-only file in 01a; the dimension lookup is enum-backed (see below). **LLD divergence**: LLD line 244-245 implies a seed table, but LLD §Entities doesn't declare one — 01a interprets `ChangeDimension` as a Java enum (verbatim values from LLD line 126), and the `R__` file ships as a no-op stub with a comment for future contents. **Worth user review.**
   - `R__adaptation_seed_nutritional_knowledge_v1.sql` — schema fixed here, **rows deferred** to prompt-engineering work per LLD line 247. 01a ships the table DDL in the `V...500` migration and an empty `R__` repeatable file with a comment header so the file exists for later population.

### V20260615120000 — `adaptation_jobs`

4. Schema verbatim from [LLD lines 83-110](../../lld/adaptation-pipeline.md):
   ```sql
   CREATE TABLE adaptation_jobs (
       id                       uuid PRIMARY KEY,
       recipe_id                uuid NOT NULL,
       user_id                  uuid NOT NULL,
       catalogue                varchar(16) NOT NULL,
       source                   varchar(24) NOT NULL,
       priority                 varchar(8)  NOT NULL,
       approval_policy          varchar(16) NOT NULL,
       status                   varchar(16) NOT NULL DEFAULT 'PENDING',
       failure_reason           varchar(64),
       failure_excerpt          varchar(512),
       inputs                   jsonb NOT NULL,
       prompt_template_version  varchar(40),
       trace_id                 uuid NOT NULL,
       parent_decision_id       uuid,
       enqueued_at              timestamptz NOT NULL,
       started_at               timestamptz, completed_at timestamptz, duration_ms integer,
       optimistic_version       bigint NOT NULL DEFAULT 0,
       created_at               timestamptz NOT NULL, updated_at timestamptz NOT NULL
   );
   CREATE INDEX idx_adaptation_jobs_status_priority
       ON adaptation_jobs (status, priority, enqueued_at) WHERE status IN ('PENDING', 'RUNNING');
   CREATE INDEX idx_adaptation_jobs_recipe_time ON adaptation_jobs (recipe_id, enqueued_at DESC);
   CREATE INDEX idx_adaptation_jobs_trace       ON adaptation_jobs (trace_id);
   CREATE INDEX idx_adaptation_jobs_user_status ON adaptation_jobs (user_id, status) WHERE status <> 'DONE';
   ```
   **No FK to `recipe_recipes`** per LLD line 86 comment — recipes can be archived post-enqueue.

### V20260615120100 — `adaptation_pending_changes`

5. Schema verbatim from [LLD lines 120-153](../../lld/adaptation-pipeline.md):
   ```sql
   CREATE TABLE adaptation_pending_changes (
       id                       uuid PRIMARY KEY,
       recipe_id                uuid NOT NULL, user_id uuid NOT NULL,
       job_id                   uuid NOT NULL REFERENCES adaptation_jobs(id) ON DELETE CASCADE,
       trace_id                 uuid NOT NULL,
       change_dimension         varchar(48) NOT NULL,
       proposed_diff            jsonb NOT NULL,
       proposed_classification  varchar(16) NOT NULL,
       base_version_id          uuid NOT NULL, base_branch_id uuid NOT NULL,
       reasoning                text NOT NULL, nutritional_notes text,
       confidence               numeric(4,3) NOT NULL,
       impact_score             numeric(4,3) NOT NULL,
       prompt_template_version  varchar(40) NOT NULL,
       status                   varchar(16) NOT NULL DEFAULT 'PENDING',
       superseded_by            uuid REFERENCES adaptation_pending_changes(id),
       accepted_version_id      uuid, user_edits jsonb,
       created_at               timestamptz NOT NULL,
       expires_at               timestamptz NOT NULL,
       resolved_at              timestamptz,
       optimistic_version       bigint NOT NULL DEFAULT 0
   );
   CREATE UNIQUE INDEX idx_adaptation_pending_recipe_dim_active
       ON adaptation_pending_changes (recipe_id, change_dimension) WHERE status = 'PENDING';
   CREATE INDEX idx_adaptation_pending_user_pending_rank
       ON adaptation_pending_changes (user_id, impact_score DESC, confidence DESC) WHERE status = 'PENDING';
   CREATE INDEX idx_adaptation_pending_expiry
       ON adaptation_pending_changes (expires_at) WHERE status = 'PENDING';
   CREATE INDEX idx_adaptation_pending_recipe_time
       ON adaptation_pending_changes (recipe_id, created_at DESC);
   ```
   **FK on `job_id` ON DELETE CASCADE** — if a job is purged the pending-change rows it generated go too.

### V20260615120200 — `adaptation_traces`

6. Schema verbatim from [LLD lines 164-190](../../lld/adaptation-pipeline.md). One row per LLM-touch job; FK `(job_id)` UNIQUE → adaptation_jobs(id) ON DELETE CASCADE; indexes on `(prompt_template_name, prompt_template_version, created_at DESC)`, `(recipe_id, created_at DESC)`, `(trace_id)`.

### V20260615120300 — `adaptation_fingerprints`

7. Schema verbatim from [LLD lines 199-209](../../lld/adaptation-pipeline.md). UPSERT keyed on UNIQUE `(recipe_id, branch_id)`; UNIQUE `version_id`; index on `body_hash` for hash-lookup idempotency in 01c.

### V20260615120400 — `adaptation_planner_hints`

8. Schema verbatim from [LLD lines 218-238](../../lld/adaptation-pipeline.md). FK `(emitted_by_job_id)` ON DELETE SET NULL — preserves hints even if the originating job is purged. Two partial indexes on `(version_id) WHERE invalidated_at IS NULL` and `(recipe_id) WHERE invalidated_at IS NULL`.

### V20260615120500 — `adaptation_nutritional_knowledge`

9. Schema verbatim from [LLD lines 250-264](../../lld/adaptation-pipeline.md):
   ```sql
   CREATE TABLE adaptation_nutritional_knowledge (
       id                       uuid PRIMARY KEY,
       knowledge_kind           varchar(32) NOT NULL,
       subject_keys             text[] NOT NULL,
       payload                  jsonb NOT NULL,
       confidence_tier          varchar(16) NOT NULL,
       source                   varchar(64) NOT NULL,
       created_at               timestamptz NOT NULL,
       UNIQUE (knowledge_kind, subject_keys)
   );
   CREATE INDEX idx_adaptation_nut_knowledge_kind ON adaptation_nutritional_knowledge (knowledge_kind);
   CREATE INDEX idx_adaptation_nut_knowledge_subjects_gin
       ON adaptation_nutritional_knowledge USING gin (subject_keys);
   ```

### Module-local enums (Java)

10. Define in `com.example.mealprep.adaptation.domain.enums` (or `api/dto` — choose `domain/enums` for visibility; expose via the module facade). Each enum value matches the corresponding DB string verbatim from LLD §Entities line 282 / §V… migration columns.

    - `JobSource { IMPORT, FEEDBACK, DATA_MODEL_CHANGE, PLAN_TIME }`
    - `JobPriority { SYNC, ASYNC, BATCH }`
    - `JobStatus { PENDING, RUNNING, DONE, FAILED }`
    - `ApprovalPolicy { PENDING_CHANGE, DIRECT, PLAN_OVERLAY }`
    - `JobFailureReason { HARD_FILTER, LOW_CONFIDENCE, CHARACTER_BREAK, AI_UNAVAILABLE, LLM_ERROR, REBASE_EXHAUSTED, WRITE_API_CONFLICT, TIMEOUT, UNKNOWN }` — verbatim from LLD line 94.
    - `PendingChangeStatus { PENDING, ACCEPTED, REJECTED, MODIFIED, SUPERSEDED, EXPIRED }`
    - `AdaptationClassification { VERSION, BRANCH, SUBSTITUTION, NO_CHANGE }`
    - `ValidationResult { PASSED, FAILED_HARD, LOW_CONFIDENCE, CHARACTER_BREAK, NO_CHANGE }`
    - `OutcomeKind { VERSION_CREATED, BRANCH_CREATED, SUBSTITUTION_CREATED, PENDING_CREATED, NO_OP, FAILED }`
    - `ChangeDimension { SALT_LEVEL, PROTEIN, METHOD_SIMPLIFICATION, PORTION_SIZE, FLAVOUR_BALANCE, ACID_BALANCE, TEXTURE, COOKING_TIME, SUBSTITUTION_PROMOTION, GENERAL }` — verbatim values from LLD line 126; `GENERAL` is the fallback per LLD line 245 "Unseeded values surface a WARN log and fall back to general".
    - `HintType { PREP_LEAD_TIME, ABSORPTION_CONFLICT, NUTRITION_TRADEOFF, EQUIPMENT_OVERLAP, BATCH_COMPATIBILITY }` — verbatim from LLD line 224.
    - `HintSeverity { INFO, WARN, BLOCK }` — verbatim from LLD line 229.
    - `KnowledgeKind { PAIRING, METHOD_BIOAVAILABILITY, SOAK_NEEDED, ABSORPTION_CONFLICT }` — verbatim from LLD line 251.

11. **DB string casing**: DB columns store the **uppercase enum name verbatim** (e.g. `'IMPORT'`, `'PENDING'`) — `@Enumerated(EnumType.STRING)`. The DDL `CHECK` constraints are NOT added (enums enforce at app layer; future hardening can add CHECKs in a follow-up migration). **Worth user review** — adding CHECK constraints would catch schema/code drift but inflates migration count for every enum value addition.

### Entities

12. Five aggregate roots + one read-only reference entity, all under `com.example.mealprep.adaptation.domain.entity`. Style-guide standard: UUID `@Id` application-set, `@Version Long optimisticVersion` on mutable aggregates only (LLD line 879 — append-only `AdaptationTrace` and `AdaptationFingerprint` have no version; `PlannerHintRecord` is single-column-update on `invalidatedAt` only so no version), `@CreatedDate Instant createdAt` / `@LastModifiedDate Instant updatedAt` via Spring Data auditing, Lombok `@Getter @Setter @Builder @NoArgsConstructor(access = PROTECTED) @AllArgsConstructor`, JSONB via `@Type(JsonType.class)` from `hypersistence-utils`.

13. **`AdaptationJob`** — `@Table(name = "adaptation_jobs")`. `inputs` typed `JsonNode`. Enum columns `@Enumerated(EnumType.STRING)`. `@Version private long optimisticVersion`. Fields per LLD lines 83-110.

14. **`PendingChange`** — `@Table(name = "adaptation_pending_changes")`. `proposedDiff` + `userEdits` typed `JsonNode`. `@Version`. **Supersession atomicity** comes from the partial unique index (LLD line 277), not the `@Version` field — both still ship.

15. **`AdaptationTrace`** — `@Table(name = "adaptation_traces")`. Append-only; **no `@Version`**. JSON fields (`inputsSnapshot`, `rawAiResponse`, `candidates`, `finalDiff`) typed `JsonNode`.

16. **`AdaptationFingerprint`** — `@Table(name = "adaptation_fingerprints")`. UPSERT keyed on `(recipeId, branchId)` UNIQUE. `fingerprint` typed `JsonNode` (LLD line 279 mentions a typed record `CharacterFingerprintDocument` but that record lives in the recipe module — 01a stores as `JsonNode` to avoid cross-module type coupling; 01f's `FingerprintRefresher` will deserialise where needed). **LLD divergence**: JSONB stays raw at the entity layer. **Worth user review.**

17. **`PlannerHintRecord`** — `@Table(name = "adaptation_planner_hints")`. `payload` typed `JsonNode`. **No `@Version`**. `invalidatedAt` is the only post-insert-mutable column.

18. **`NutritionalKnowledgeEntry`** — `@Table(name = "adaptation_nutritional_knowledge")`. Read-only reference data; no `@Version`. `subjectKeys` typed `String[]` mapped to Postgres `text[]` via `@Type(StringArrayType.class)` from `hypersistence-utils`. `payload` typed `JsonNode`. `knowledgeKind` `@Enumerated(EnumType.STRING)`.

### Repositories

19. Six package-private interfaces under `com.example.mealprep.adaptation.domain.repository` (no `public` modifier — LLD line 414 "cross-module access goes through service interfaces only"):

    - `AdaptationJobRepository` — verbatim from [LLD lines 417-430](../../lld/adaptation-pipeline.md). Includes the JPQL `findNextPendingJobs(Pageable)` with the CASE-priority sort.
    - `PendingChangeRepository` — verbatim from [LLD lines 432-445](../../lld/adaptation-pipeline.md). Includes `findRankedPending(UUID, Pageable)` for the budget-cap query.
    - `AdaptationTraceRepository` — verbatim from [LLD lines 447-452](../../lld/adaptation-pipeline.md).
    - `AdaptationFingerprintRepository` — verbatim from [LLD lines 454-458](../../lld/adaptation-pipeline.md).
    - `PlannerHintRecordRepository` — verbatim from [LLD lines 460-468](../../lld/adaptation-pipeline.md). Includes the `@Modifying @Query("update ... set invalidated_at = :now ...")` method.
    - `NutritionalKnowledgeRepository` — verbatim from [LLD lines 470-476](../../lld/adaptation-pipeline.md). Includes the **native query** with `subject_keys && cast(:keys as text[])` exploiting the GIN index.

20. **Native-query SQL note** (LLD line 471-475): the binding uses `cast(:keys as text[])` because Hibernate's parameter binding for `String[]` requires the explicit cast for the GIN-index intersect to work. Worth a sanity test in 01a — see edge-case checklist.

### Module enums imported from other modules

21. **`Catalogue` enum** (`USER` | `SYSTEM`) — used on `AdaptationJob.catalogue` and several DTOs. **This enum lives in the recipe module** per the recipe LLD's catalogue conventions; 01a imports it via `com.example.mealprep.recipe.api.dto.Catalogue` (or similar — agent confirms the recipe-01a/01b path). **Do not re-declare locally.** If the recipe module hasn't exposed `Catalogue` publicly, ship a TODO comment on `AdaptationJob.catalogue` and store as `String` in the entity for now; 01b can refine once the import path is confirmed. **Worth user review.**

22. **`DataQuality` enum** — used on `ImportJobRequest` (01b territory) but not on any entity in 01a; not imported here.

### Exceptions

23. New module-root exception class:
    ```java
    package com.example.mealprep.adaptation.exception;
    public class AdaptationException extends com.example.mealprep.core.exception.MealPrepException {
      public AdaptationException(String message) { super(message); }
      public AdaptationException(String message, Throwable cause) { super(message, cause); }
    }
    ```
24. Per-failure subclasses verbatim from [LLD lines 624-632](../../lld/adaptation-pipeline.md):
    - `AdaptationJobNotFoundException extends AdaptationException` (404)
    - `PendingChangeNotFoundException extends AdaptationException` (404)
    - `AdaptationTraceNotFoundException extends AdaptationException` (404)
    - `PendingChangeNotPendingException extends AdaptationException` (422)
    - `PendingChangeExpiredException extends AdaptationException` (422)
    - `AdaptationLowConfidenceException extends AdaptationException` (422)
    - `AdaptationCharacterBreakException extends AdaptationException` (422)
    - `AdaptationHardConstraintViolationException extends AdaptationException` (422)
    - `PendingChangeSupersededException extends AdaptationException` (409)
    - `AdaptationAiUnavailableException extends AdaptationException` (503) — wraps `com.example.mealprep.ai.exception.AiUnavailableException` (LLD line 629). Carries the wrapped exception as `cause`.
    - `LockTimeoutException extends AdaptationException` (409) — Trigger 2's lock-acquire failure surface per LLD line 786.
    - `RebaseExhaustedException extends AdaptationException` (409) — emitted by 01c's `RebaseOrchestrator` after 3 attempts. NEW class declared in 01a so 01b interfaces can reference; impl thrown in 01c.

25. **`OptimisticLockException` and `RecipeVersionConflictException`** are NOT new exceptions — `OptimisticLockException` is Spring Data's standard `org.springframework.dao.OptimisticLockingFailureException`, and `RecipeVersionConflictException` lives in the recipe module (recipe-01a/01f). The `GlobalExceptionHandler` (project-wide) handles both per LLD line 628.

26. **No `@RestControllerAdvice` in 01a** — exception → HTTP mapping lives in the project-wide `config/GlobalExceptionHandler` (LLD line 622 "Mappings handled in the project-wide `GlobalExceptionHandler`"). 01a adds new entries there: six 404/422/409/503 mappings keyed by the new exception classes. **Verify the project-wide handler exists** — if it does, append entries; if not, ship a per-module `@RestControllerAdvice` `AdaptationExceptionHandler` as a fallback (the parent project pattern uses module-local advice per other modules' tickets; agent picks the matching style).

### Configuration

27. **`AdaptationConfig`** `@ConfigurationProperties(prefix = "mealprep.adaptation") @Validated` record verbatim from [LLD lines 705-724](../../lld/adaptation-pipeline.md). Default values in `application.properties` under `mealprep.adaptation.*` keys per LLD line 727. The `BudgetConfig` inner record is declared but `sourceBudgets` defaults to an empty map per LLD line 715 "Default values deferred".

28. **`application.properties` (or `application.yml`) additions**:
    ```properties
    mealprep.adaptation.candidate-top-n=5
    mealprep.adaptation.plan-time-timeout-ms=10000
    mealprep.adaptation.feedback-timeout-ms=8000
    mealprep.adaptation.import-timeout-ms=12000
    mealprep.adaptation.max-rebase-attempts=3
    mealprep.adaptation.pending-change-budget-per-week=3
    mealprep.adaptation.pending-change-expiry-days=14
    mealprep.adaptation.low-confidence-floor=0.50
    mealprep.adaptation.auto-skip-top-ratio=2.00
    mealprep.adaptation.recipe-advisory-lock-seconds=30
    mealprep.adaptation.pending-expiry-sweep-cron=0 0 4 * * *
    mealprep.adaptation.batch-orchestrator-cron=0 30 4 * * *
    ```

29. **`AdaptationModule.java` facade** — `@Component` (or no annotation, just a marker) under the module root. **Empty in 01a** beyond a package-info-style class comment naming "module facade — public services re-exported in 01b". Ships so other modules can `import com.example.mealprep.adaptation.AdaptationModule;` once 01b adds the typed re-exports.

## Database

```
src/main/resources/db/migration/V20260615120000__adaptation_create_jobs.sql                       new
src/main/resources/db/migration/V20260615120100__adaptation_create_pending_changes.sql            new
src/main/resources/db/migration/V20260615120200__adaptation_create_traces.sql                     new
src/main/resources/db/migration/V20260615120300__adaptation_create_fingerprints.sql               new
src/main/resources/db/migration/V20260615120400__adaptation_create_planner_hints.sql              new
src/main/resources/db/migration/V20260615120500__adaptation_create_nutritional_knowledge.sql      new
src/main/resources/db/migration/R__adaptation_seed_change_dimensions.sql                          new (stub-only)
src/main/resources/db/migration/R__adaptation_seed_nutritional_knowledge_v1.sql                   new (stub-only)
```

All migrations follow the project's existing style: leading SQL comment naming the migration + the LLD section it implements; SQL identifiers lowercase; index names match LLD verbatim.

## OpenAPI updates

**None in 01a.** No HTTP surface ships here. 01d/01e/01f add OpenAPI excerpts when controllers land.

## Edge-case checklist

- [ ] All six versioned migrations run cleanly against a fresh Postgres (Testcontainers) — `FlywayMigrationIT` extension covers this
- [ ] `ddl-auto=validate` passes — every entity column matches the migration column (name, type, nullability)
- [ ] `@Version` is present on `AdaptationJob` + `PendingChange` only; absent on `AdaptationTrace`, `AdaptationFingerprint`, `PlannerHintRecord`, `NutritionalKnowledgeEntry`
- [ ] `JsonNode` round-trip: persist a multi-key `inputs` JSON on an `AdaptationJob`, re-read, all keys present
- [ ] `JsonNode` round-trip on `PendingChange.proposedDiff` + `userEdits` (latter starts null, then set to a multi-key blob)
- [ ] `String[]` round-trip on `NutritionalKnowledgeEntry.subjectKeys` via hypersistence `StringArrayType`
- [ ] Native query `findIntersectingSubjects` returns rows whose `subject_keys` overlap the queried array — write 2 rows with `{"a","b"}` and `{"b","c"}`, query for `{"b"}`, expect 2 results
- [ ] Partial unique index on `adaptation_pending_changes (recipe_id, change_dimension) WHERE status = 'PENDING'` enforces atomically: insert two rows with same `(recipe_id, change_dimension)` both PENDING — second insert fails with `DataIntegrityViolationException`
- [ ] Two PENDING rows for same `(recipe_id, change_dimension)` allowed once first is flipped to `SUPERSEDED` — verifies the WHERE clause works
- [ ] Two rows with same `(recipe_id, change_dimension)` allowed when one is REJECTED and one PENDING — verifies the WHERE filter excludes non-PENDING
- [ ] FK `adaptation_pending_changes.job_id` cascade — delete an `AdaptationJob`, dependent pending-change rows are deleted
- [ ] FK `adaptation_traces.job_id` cascade behaves the same way
- [ ] UNIQUE `adaptation_fingerprints (recipe_id, branch_id)` blocks duplicate inserts; UPSERT via Hibernate `merge` updates instead
- [ ] `adaptation_jobs.failure_reason` accepts all 9 enum values (set them in sequence via raw JDBC, no CHECK constraint failures)
- [ ] `adaptation_planner_hints.invalidated_at` null on insert; setting it to `Instant.now()` works
- [ ] `AdaptationConfig` `@ConfigurationProperties` binds correctly from `application.properties`; `pendingChangeBudgetPerWeek = 3`, `lowConfidenceFloor = BigDecimal("0.50")`, `maxRebaseAttempts = 3`
- [ ] Validation on `AdaptationConfig`: `candidateTopN = 0` → context-load fails (Jakarta `@Min(1)`)
- [ ] **No service interface defined in `domain/service/`** — placeholder `package-info.java` ships; 01b refines
- [ ] **No event class defined in `event/`** — package empty (or omitted) in 01a
- [ ] All new exception classes load (`AdaptationException` extends `MealPrepException`, subclasses extend `AdaptationException`); a smoke unit test instantiates each
- [ ] **Catalogue enum import**: agent confirms recipe-01a exposes `Catalogue` publicly; if not, document the temporary `String` storage in `AdaptationJob.catalogue` and TODO comment pointing at 01b for refinement

## Files this ticket touches

```
NEW   src/main/resources/db/migration/V20260615120000__adaptation_create_jobs.sql
NEW   src/main/resources/db/migration/V20260615120100__adaptation_create_pending_changes.sql
NEW   src/main/resources/db/migration/V20260615120200__adaptation_create_traces.sql
NEW   src/main/resources/db/migration/V20260615120300__adaptation_create_fingerprints.sql
NEW   src/main/resources/db/migration/V20260615120400__adaptation_create_planner_hints.sql
NEW   src/main/resources/db/migration/V20260615120500__adaptation_create_nutritional_knowledge.sql
NEW   src/main/resources/db/migration/R__adaptation_seed_change_dimensions.sql
NEW   src/main/resources/db/migration/R__adaptation_seed_nutritional_knowledge_v1.sql

NEW   src/main/java/com/example/mealprep/adaptation/AdaptationModule.java
NEW   src/main/java/com/example/mealprep/adaptation/package-info.java

NEW   src/main/java/com/example/mealprep/adaptation/domain/entity/AdaptationJob.java
NEW   src/main/java/com/example/mealprep/adaptation/domain/entity/PendingChange.java
NEW   src/main/java/com/example/mealprep/adaptation/domain/entity/AdaptationTrace.java
NEW   src/main/java/com/example/mealprep/adaptation/domain/entity/AdaptationFingerprint.java
NEW   src/main/java/com/example/mealprep/adaptation/domain/entity/PlannerHintRecord.java
NEW   src/main/java/com/example/mealprep/adaptation/domain/entity/NutritionalKnowledgeEntry.java

NEW   src/main/java/com/example/mealprep/adaptation/domain/enums/JobSource.java
NEW   src/main/java/com/example/mealprep/adaptation/domain/enums/JobPriority.java
NEW   src/main/java/com/example/mealprep/adaptation/domain/enums/JobStatus.java
NEW   src/main/java/com/example/mealprep/adaptation/domain/enums/ApprovalPolicy.java
NEW   src/main/java/com/example/mealprep/adaptation/domain/enums/JobFailureReason.java
NEW   src/main/java/com/example/mealprep/adaptation/domain/enums/PendingChangeStatus.java
NEW   src/main/java/com/example/mealprep/adaptation/domain/enums/AdaptationClassification.java
NEW   src/main/java/com/example/mealprep/adaptation/domain/enums/ValidationResult.java
NEW   src/main/java/com/example/mealprep/adaptation/domain/enums/OutcomeKind.java
NEW   src/main/java/com/example/mealprep/adaptation/domain/enums/ChangeDimension.java
NEW   src/main/java/com/example/mealprep/adaptation/domain/enums/HintType.java
NEW   src/main/java/com/example/mealprep/adaptation/domain/enums/HintSeverity.java
NEW   src/main/java/com/example/mealprep/adaptation/domain/enums/KnowledgeKind.java

NEW   src/main/java/com/example/mealprep/adaptation/domain/repository/AdaptationJobRepository.java
NEW   src/main/java/com/example/mealprep/adaptation/domain/repository/PendingChangeRepository.java
NEW   src/main/java/com/example/mealprep/adaptation/domain/repository/AdaptationTraceRepository.java
NEW   src/main/java/com/example/mealprep/adaptation/domain/repository/AdaptationFingerprintRepository.java
NEW   src/main/java/com/example/mealprep/adaptation/domain/repository/PlannerHintRecordRepository.java
NEW   src/main/java/com/example/mealprep/adaptation/domain/repository/NutritionalKnowledgeRepository.java

NEW   src/main/java/com/example/mealprep/adaptation/domain/service/package-info.java                (placeholder; 01b adds interfaces)

NEW   src/main/java/com/example/mealprep/adaptation/exception/AdaptationException.java
NEW   src/main/java/com/example/mealprep/adaptation/exception/AdaptationJobNotFoundException.java
NEW   src/main/java/com/example/mealprep/adaptation/exception/PendingChangeNotFoundException.java
NEW   src/main/java/com/example/mealprep/adaptation/exception/AdaptationTraceNotFoundException.java
NEW   src/main/java/com/example/mealprep/adaptation/exception/PendingChangeNotPendingException.java
NEW   src/main/java/com/example/mealprep/adaptation/exception/PendingChangeExpiredException.java
NEW   src/main/java/com/example/mealprep/adaptation/exception/AdaptationLowConfidenceException.java
NEW   src/main/java/com/example/mealprep/adaptation/exception/AdaptationCharacterBreakException.java
NEW   src/main/java/com/example/mealprep/adaptation/exception/AdaptationHardConstraintViolationException.java
NEW   src/main/java/com/example/mealprep/adaptation/exception/PendingChangeSupersededException.java
NEW   src/main/java/com/example/mealprep/adaptation/exception/AdaptationAiUnavailableException.java
NEW   src/main/java/com/example/mealprep/adaptation/exception/LockTimeoutException.java
NEW   src/main/java/com/example/mealprep/adaptation/exception/RebaseExhaustedException.java

NEW   src/main/java/com/example/mealprep/adaptation/config/AdaptationConfig.java

MOD   src/main/resources/application.properties                                                    (add 12 mealprep.adaptation.* keys)
MOD   src/main/java/com/example/mealprep/config/GlobalExceptionHandler.java                       (append @ExceptionHandler methods for the new exceptions — IF the project uses a global handler; otherwise ship `AdaptationExceptionHandler` per-module advice)

NEW   src/test/java/com/example/mealprep/adaptation/AdaptationFlywayMigrationIT.java               (verifies all migrations run cleanly)
NEW   src/test/java/com/example/mealprep/adaptation/AdaptationEntitiesJpaIT.java                   (round-trip each entity through the repo)
NEW   src/test/java/com/example/mealprep/adaptation/AdaptationPendingChangeIndexIT.java            (partial unique index behaviour)
NEW   src/test/java/com/example/mealprep/adaptation/NutritionalKnowledgeRepositoryIT.java          (native query GIN intersect)
NEW   src/test/java/com/example/mealprep/adaptation/AdaptationConfigBindingTest.java               (@ConfigurationProperties binding)
NEW   src/test/java/com/example/mealprep/adaptation/AdaptationExceptionSmokeTest.java              (each new exception loads + extends correctly)
```

**Files this ticket does NOT touch**:

- No service-interface files (`AdaptationService.java`, `AdaptationQueryService.java`, `NutritionalKnowledgeService.java`) — 01b.
- No controllers, no DTOs, no mappers, no events, no validators — 01b–01f.
- No ArchUnit `ModuleBoundaryArchTest` — 01f.
- No `RecipeWriteApi` / `PreferenceUpdateService` / `NutritionUpdateService` injection wiring — 01c.
- No `LockService` / `DecisionLogService` wiring — 01c.
- No prompt template file — 01e.

## Dependencies

- **Hard dependency**: `core-01a` (merged, assumed) — `MealPrepException` root + `LockService` + `DecisionLogService` interfaces + JSONB plumbing config (`CoreJsonConfig` provides the Hibernate `JsonType` bean). 01a's exceptions extend `MealPrepException`.
- **Hard dependency**: `recipe-01a` / earlier recipe tickets (merged) — `recipe_recipes` / `recipe_versions` / `recipe_branches` tables exist (no FKs added, but the timestamp ordering relies on them being created first). Also: `Catalogue` enum (`USER` / `SYSTEM`) public from recipe — agent verifies.
- **Hard dependency**: `ai-01a` (merged) — `AiUnavailableException` class lives in `com.example.mealprep.ai.exception`; `AdaptationAiUnavailableException` wraps it.
- **Soft dependency**: `nutrition-01a` (merged) — only relevant once 01c wires `NutritionUpdateService`; 01a doesn't import nutrition.
- **No dependency on planner / feedback** — 01a is upstream of both.
- **Sibling tickets running in parallel (Wave 3)**: `planner-01*`, `feedback-01*`. Neither touches 01a's files. Both will hard-block on **01b** (the contract-lock ticket), not 01a.

## Acceptance / DoD

- [ ] `./mvnw -Dspotless.check.skip=true verify` passes locally
- [ ] `./mvnw spotless:apply` then `./mvnw spotless:check` clean
- [ ] CI green (build + spotless + OpenAPI lint — though no OpenAPI changes — + ArchUnit; if `ModuleBoundaryArchTest` exists project-wide, it doesn't yet rule out adaptation imports so it should stay green)
- [ ] `ddl-auto=validate` passes against the Testcontainers Postgres after all migrations
- [ ] All edge-case items above ticked
- [ ] No new pom.xml dependency adds (hypersistence-utils + JsonType already present from core-01a or earlier modules)
- [ ] No file outside `src/main/java/com/example/mealprep/adaptation/`, `src/main/resources/db/migration/V20260615*`, `src/main/resources/db/migration/R__adaptation_*`, `src/test/java/com/example/mealprep/adaptation/`, `src/main/resources/application.properties`, and (conditionally) `src/main/java/com/example/mealprep/config/GlobalExceptionHandler.java` touched
- [ ] Module package is `com.example.mealprep.adaptation`, NOT `com.example.mealprep.optimiser` (the parent brief had a typo)

Squash-merge with: `feat(adaptation): 01a — migrations + entities + repositories + module skeleton (6 tables, 5 aggregate entities, 6 repos, 13 enums, 12 exceptions)`

## What's NOT in scope

- All public service interfaces (`AdaptationService`, `AdaptationQueryService`, `NutritionalKnowledgeService`) → **01b**.
- All DTOs (records under `api/dto/`) → **01b**.
- All MapStruct mappers → **01b** (mappers move with their DTOs).
- All controllers (`PendingChangesController`, `AdaptationAdminController`, `AdapterRunHistoryController`) → **01d** / **01f**.
- All event records (`AdaptationJob*Event`, `PendingChange*Event`, `PlannerHintEmittedEvent`) → **01c** (worker pipeline emits them).
- Custom validators `@ValidPlannerHint` / `@ValidRecipeDiff` → **01d** (controllers need them).
- Shared worker pipeline (`CandidateGenerator`, `ScoringEngine`, `AdaptationLlmInvoker`, `PendingChangeStore`, `RebaseOrchestrator`, gate components, `AdaptationTraceWriter`) → **01c**.
- All four trigger entry methods on the impl → **01d**.
- `RecipeAdaptationTask` + `AdaptationContextAssembler` → **01e**.
- `NutritionalKnowledgeService` interface + impl → **01e** (interface is consumed by 01c but defined alongside its impl in 01e).
- `BatchJobOrchestrator` + `@Scheduled` jobs (expiry sweep, batch orchestrator) → **01d** / **01f**.
- `FingerprintRefresher`, `PlannerHintEmitter` → **01f**.
- ArchUnit boundary test → **01f**.
- Test-scaffold `TestAiService` wiring or any `AiService` invocation — 01a has no AI dependency.
- Any AnthropicSDK or batch-API plumbing.
