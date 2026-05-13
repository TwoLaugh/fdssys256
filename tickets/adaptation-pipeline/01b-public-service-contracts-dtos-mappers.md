# Ticket: adaptation-pipeline — 01b Public Service Contracts + DTOs + Mappers (sibling-unblock)

## Summary

**THIS TICKET LOCKS THE PUBLIC API of the adaptation-pipeline module.** Sibling Wave-3 modules (`planner`, `feedback`) hard-block on 01b to compile against the typed service contracts. 01b is **interface-only at the service layer** (no impl bodies) — implementation lands in 01c/01d/01e/01f. The point of the split is to give the parent agent a small, fast-merging contract surface that the sibling agents can mock against immediately.

Ships:

- **`AdaptationService`** public interface verbatim from [LLD lines 490-513](../../lld/adaptation-pipeline.md) — the four trigger entry methods + pending-change lifecycle + planner-hint emission + expiry sweep entry.
- **`AdaptationQueryService`** public interface verbatim from [LLD lines 520-538](../../lld/adaptation-pipeline.md) — reads for pending changes + jobs + traces + planner hints.
- **`NutritionalKnowledgeService`** public interface verbatim from [LLD lines 548-571](../../lld/adaptation-pipeline.md) — the upgradeable food-science seam.
- **All public-API DTOs** verbatim from [LLD §DTOs (lines 286-402)](../../lld/adaptation-pipeline.md): `AdaptationJobDto`, `AdaptationResultDto`, `ImportJobRequest`, `FeedbackJobRequest` (+ inner `RatingDeltaDto`), `DataModelJobRequest`, `PlannerHintRequest`, `PlanTimeRefineDirectiveRequest` (+ inner `RefineDirectiveDto`), `PendingChangeDto`, `PendingChangeListItemDto`, `AcceptPendingChangeRequest`, `RejectPendingChangeRequest`, `AdaptationCandidateDto`, `AdaptationRollupDto`, `PlannerHintDto`, `AdaptationTraceDto`. Plus the **`NutritionalKnowledge*` reply DTOs** from the LLD's `lookupFor*` signatures: `NutritionalPairingDto`, `MethodBioavailabilityDto`, `PrepRequirementDto`, `AbsorptionConflictDto`, `NutritionalKnowledgeBundleDto`.
- **Sibling-facing trigger-entry enums**: `DataModelChangeType` (`PREFERENCE` | `NUTRITION_TARGETS` | `PROVISIONS_BUDGET` | `HARD_CONSTRAINTS` per LLD line 331), `DirectiveKind` (`COST_DELTA` | `NUTRITION_DELTA` | `TIME_DELTA` | `EQUIPMENT_OVERLAP` | `INGREDIENT_SWAP` per LLD line 351-353).
- **MapStruct mappers** (LLD §Mappers line 408): `AdaptationJobMapper` (`toDto`, `toDtos`), `PendingChangeMapper` (`toDto`, `toListItem`, `toListItems`), `AdaptationTraceMapper`, `PlannerHintMapper`. Custom `@Named` JSON-blob qualifiers register here. **`AdaptationCandidateMapper` IS NOT in 01b** — `AdaptationCandidate` is a domain record produced inside `CandidateGenerator` (01c), never persisted in its own row, so its DTO ↔ record mapping is internal to 01c.
- **`AdaptationServiceImpl` skeleton** — single class implementing both `AdaptationService` and `AdaptationQueryService` (LLD line 485 "Public services implemented by a single `AdaptationServiceImpl`"). 01b ships the class with **every method throwing `UnsupportedOperationException("ticket-01c/01d/01e/01f")`**. This makes the bean wire correctly into Spring's DI graph (sibling integration tests can `@MockBean` over it) while signalling clearly that the body is unfinished. 01c-01f fill in.
- **Module facade update**: `AdaptationModule.java` from 01a re-exports the three public services + the SPI-style DTOs/enums.

**This ticket exists primarily to unblock parallel work.** Once 01b merges, planner-tickets can compile their `OptimiserService injection` (see "Naming reconciliation" below) and feedback-tickets can compile their `FeedbackRouter` recipe-destination handler.

## CRITICAL — Naming reconciliation: `OptimiserService` vs `AdaptationService`

The sibling-module LLDs ([lld/planner.md lines 1198, 1223, 1308, 1314, 1326, 1348](../../lld/planner.md); [lld/feedback.md lines 889, 917](../../lld/feedback.md); [lld/core.md line 417](../../lld/core.md); [lld/prompts/04-feedback-classification.md line 408](../../lld/prompts/04-feedback-classification.md)) all reference a **public interface named `OptimiserService`** with methods including:

- `OptimiserService.adapt(directive)` — Stage D planner refine (sync) — returns adapted recipe ID OR `InfeasibleDirective` signal
- `OptimiserService.handleRecipeFeedback(...)` — feedback router's RECIPE-destination handler

The **adaptation-pipeline LLD** ([lld/adaptation-pipeline.md lines 490-513](../../lld/adaptation-pipeline.md)) names the interface **`AdaptationService`** with methods:

- `runPlanTimeRefineJob(PlanTimeRefineDirectiveRequest)` — sync; planner Stage D entry
- `enqueueFeedbackJob(FeedbackJobRequest)` — sync; feedback module entry
- `enqueueImportJob(ImportJobRequest)` — async; recipe-import entry
- `enqueueDataModelChangeJobs(DataModelJobRequest)` — async batch
- pending-change lifecycle, planner-hint emission, expiry sweep

**Resolution (worth user review — escalate during ticket impl):** The adaptation-pipeline LLD is the source of truth per the parent's brief. **01b ships `AdaptationService` with the LLD's signatures verbatim.** The sibling LLDs' `OptimiserService.adapt` / `OptimiserService.handleRecipeFeedback` references are **forward references to be reconciled when planner-tickets and feedback-tickets are written**. The parent agent should expect to relax the planner/feedback ticket prose to map:

| Sibling LLD reference | This module's actual symbol (per LLD) |
|---|---|
| `OptimiserService.adapt(directive)` | `AdaptationService.runPlanTimeRefineJob(PlanTimeRefineDirectiveRequest)` |
| `OptimiserService.handleRecipeFeedback(...)` | `AdaptationService.enqueueFeedbackJob(FeedbackJobRequest)` |
| `OptimiserService.handleNutritionDivergence(...)` | (no direct match — covered by Trigger 3's data-model-change listener on `NutritionTargetsChangedEvent`; data-model-change is broader than nutrition-divergence) |
| `OptimiserService.handleMidWeekTrigger(...)` | (no match — mid-week re-opt is a planner concern per [optimisation-loop.md line 175](../../design/optimisation-loop.md); not in the adaptation-pipeline LLD) |

**Two safety-net options** the implementing agent should choose between:

- **Option A (preferred per LLD-as-truth)**: 01b ships ONLY `AdaptationService`. Sibling LLDs will be updated separately to reference `AdaptationService` directly. The naming friction surfaces to the parent immediately.
- **Option B (transition alias)**: 01b ships `AdaptationService` AND a **typealias-style `OptimiserService` interface** that extends `AdaptationService` with no new methods, so legacy sibling-LLD references compile cleanly. **Rejected** because: (a) it cements the naming inconsistency; (b) the LLD doesn't authorise the alias; (c) the sibling tickets are being written in parallel and can adopt the LLD name from the start.

**01b chooses Option A.** The ticket report MUST surface this naming friction to the parent so the sibling-ticket authors update their references.

## Behavioural spec

### Public service interfaces

1. **`AdaptationService`** at `com.example.mealprep.adaptation.domain.service.AdaptationService`. Verbatim from [LLD lines 490-512](../../lld/adaptation-pipeline.md):
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

2. **`AdaptationQueryService`** at `com.example.mealprep.adaptation.domain.service.AdaptationQueryService`. Verbatim from [LLD lines 520-538](../../lld/adaptation-pipeline.md):
   ```java
   public interface AdaptationQueryService {
     List<PendingChangeListItemDto> listPendingForUser(UUID userId);       // top-3 per HLD budget rule
     List<PendingChangeListItemDto> listPendingHistoryForRecipe(UUID recipeId);
     Optional<PendingChangeDto> getPendingChange(UUID pendingChangeId);

     Page<AdaptationJobDto> getJobsForRecipe(UUID recipeId, Pageable pageable);
     Page<AdaptationJobDto> getActiveJobsForUser(UUID userId, Pageable pageable);
     Page<AdaptationTraceDto> getTracesForRecipe(UUID recipeId, Pageable pageable);
     Page<AdaptationTraceDto> getTracesForPromptVersion(String name, String version, Pageable pageable);
     Optional<AdaptationTraceDto> getTraceForJob(UUID jobId);

     List<PlannerHintDto> getActiveHintsForVersion(UUID versionId);
     Map<UUID, List<PlannerHintDto>> getActiveHintsForVersions(List<UUID> versionIds);

     Optional<AdaptationResultDto> getMostRecentResultForRecipe(UUID recipeId);
   }
   ```

3. **`NutritionalKnowledgeService`** at `com.example.mealprep.adaptation.domain.service.NutritionalKnowledgeService`. Verbatim from [LLD lines 548-571](../../lld/adaptation-pipeline.md):
   ```java
   public interface NutritionalKnowledgeService {
     List<NutritionalPairingDto> lookupPairings(List<String> ingredientMappingKeys);
     List<MethodBioavailabilityDto> lookupMethodEffects(String ingredientMappingKey, List<String> methods);
     List<PrepRequirementDto> lookupPrepRequirements(List<String> ingredientMappingKeys);
     List<AbsorptionConflictDto> lookupConflicts(List<String> ingredientMappingKeys);
     NutritionalKnowledgeBundleDto lookupForRecipe(UUID versionId, List<String> ingredientMappingKeys);
   }
   ```

### DTOs — public-API records

4. All under `com.example.mealprep.adaptation.api.dto`. Java records, package-public. Each field's nullability mirrors the LLD's `@Nullable` annotations; missing `@Nullable` means non-null.

5. **Core job + result shapes** (LLD lines 292-307):
   - `AdaptationJobDto(UUID id, UUID recipeId, UUID userId, Catalogue catalogue, JobSource source, JobPriority priority, ApprovalPolicy approvalPolicy, JobStatus status, JobFailureReason failureReason, String failureExcerpt, JsonNode inputs, String promptTemplateVersion, UUID traceId, UUID parentDecisionId, Instant enqueuedAt, Instant startedAt, Instant completedAt, Integer durationMs, long optimisticVersion)`
   - `AdaptationResultDto(UUID jobId, UUID recipeId, AdaptationClassification classification, Optional<UUID> versionIdCreated, Optional<UUID> branchIdCreated, Optional<UUID> substitutionIdCreated, Optional<UUID> pendingChangeIdCreated, JsonNode proposedDiff, String reasoning, String nutritionalNotes, boolean requiresApproval, List<PlannerHintDto> plannerHints, UUID traceId, BigDecimal confidence)`

6. **Trigger-specific request shapes** (LLD lines 313-355). All with Jakarta `@NotNull`/`@NotBlank`/`@Size`/`@Valid` per the LLD's verbatim annotations:
   - `ImportJobRequest(@NotNull UUID recipeId, @NotNull UUID userId, @NotNull Catalogue catalogue, @NotNull DataQuality dataQuality, @Nullable JsonNode rawImportContext, @Nullable UUID parentTraceId)`
   - `FeedbackJobRequest(@NotNull UUID recipeId, @NotNull UUID userId, @NotNull UUID feedbackId, @NotNull String feedbackText, @NotNull RatingDeltaDto ratingDelta, @NotNull UUID traceId, @Nullable UUID parentDecisionId)` with inner `record RatingDeltaDto(BigDecimal taste, BigDecimal effortWorthIt, BigDecimal portionFit, BigDecimal repeatValue)`
   - `DataModelJobRequest(@NotNull UUID userId, @NotNull DataModelChangeType changeType, @NotNull JsonNode changeSummary, @NotNull @Size(max = 5000) Set<UUID> affectedRecipeIds, @NotNull UUID traceId)`
   - `PlannerHintRequest(@NotNull UUID recipeId, @NotNull UUID versionId, @NotNull UUID branchId, @NotNull HintType hintType, @NotBlank @Size(max = 500) String description, @NotNull JsonNode payload, @NotNull HintSeverity severity, @Nullable UUID emittedByJobId, @NotNull UUID traceId)`
   - `PlanTimeRefineDirectiveRequest(@NotNull UUID recipeId, @NotNull UUID userId, @NotNull UUID planId, @NotNull UUID slotId, @NotNull RefineDirectiveDto directive, @NotNull PlanConstraintsSnapshotDto constraints, @NotNull UUID parentDecisionId, @NotNull UUID traceId)` with inner `record RefineDirectiveDto(DirectiveKind kind, String description, JsonNode targetDelta)`.

7. **`PlanConstraintsSnapshotDto`** — LLD line 347 declares the field but doesn't spec the shape (cross-module — pinned by the planner). 01b defines a minimal record + `JsonNode payload` carrier:
   ```java
   public record PlanConstraintsSnapshotDto(
       JsonNode pantrySnapshot,
       BigDecimal weeklyBudgetGbp,
       Set<String> equipmentAvailable,
       Map<String, BigDecimal> nutritionTargets,
       Instant pinnedAt) {}
   ```
   **LLD divergence**: shape inferred from LLD line 348 "pinned by planner: pantry, budget, equipment". The planner sibling ticket may refine; 01b's typed wrapper is the contract until then. **Worth user review.**

8. **Pending-change, candidate, trace shapes** (LLD lines 360-401):
   - `PendingChangeDto` — all fields per LLD lines 360-369, including `@Nullable` `supersededBy` / `acceptedVersionId` / `userEdits` / `resolvedAt`.
   - `PendingChangeListItemDto(UUID id, UUID recipeId, ChangeDimension changeDimension, String reasoningPreview, BigDecimal confidence, BigDecimal impactScore, Instant createdAt, Instant expiresAt)` — `reasoningPreview` is a server-side truncation (max 200 chars; mapper handles).
   - `AcceptPendingChangeRequest(@Nullable @Valid JsonNode userEdits, long expectedOptimisticVersion)`
   - `RejectPendingChangeRequest(@Size(max = 200) String reasonNote)` — reasonNote nullable (no `@NotBlank`).
   - `AdaptationCandidateDto(int index, AdaptationClassification proposedClassification, JsonNode proposedDiff, AdaptationRollupDto rollup, String culinaryNotes, String nutritionalNotes, BigDecimal characterPreservationScore, BigDecimal estimatedConfidence, List<PlannerHintDto> plannerHints)`
   - `AdaptationRollupDto(BigDecimal macroDeltaProteinG, BigDecimal macroDeltaCarbsG, BigDecimal macroDeltaFatG, BigDecimal macroDeltaKcal, Map<String, BigDecimal> microDeltas, BigDecimal costDeltaGbp, Integer timeDeltaMins, Integer ingredientCountDelta, BigDecimal tasteAlignmentScore, Set<String> equipmentDelta, List<String> warnings)`
   - `PlannerHintDto(UUID id, HintType type, String description, JsonNode payload, HintSeverity severity)`
   - `AdaptationTraceDto` — all fields per LLD lines 394-401.

9. **`NutritionalKnowledge*` reply DTOs** — declared but the LLD doesn't fully spec the shape (LLD line 547 punts on contents). 01b ships minimal `record` shells with a `JsonNode payload` carrier so the v1 lookup-table impl in 01e can fill them:
   ```java
   public record NutritionalPairingDto(List<String> subjectKeys, String description, BigDecimal confidence, JsonNode payload) {}
   public record MethodBioavailabilityDto(String subjectKey, String method, String effect, BigDecimal magnitude, JsonNode payload) {}
   public record PrepRequirementDto(List<String> subjectKeys, String requirement, Integer leadTimeHours, JsonNode payload) {}
   public record AbsorptionConflictDto(List<String> subjectKeys, String conflict, String severity, JsonNode payload) {}
   public record NutritionalKnowledgeBundleDto(List<NutritionalPairingDto> pairings, List<MethodBioavailabilityDto> methodEffects, List<PrepRequirementDto> prepRequirements, List<AbsorptionConflictDto> conflicts)
   ```
   **LLD divergence**: shapes inferred. **Worth user review** — could be tightened once the v1 knowledge table is seeded in 01e.

### Enums

10. New under `com.example.mealprep.adaptation.api.dto` (or `domain/enums` — agent picks per the 01a convention):
    - `DataModelChangeType { PREFERENCE, NUTRITION_TARGETS, PROVISIONS_BUDGET, HARD_CONSTRAINTS }`
    - `DirectiveKind { COST_DELTA, NUTRITION_DELTA, TIME_DELTA, EQUIPMENT_OVERLAP, INGREDIENT_SWAP }`

### Mappers

11. **MapStruct interfaces, `@Mapper(componentModel = "spring")`**, under `com.example.mealprep.adaptation.api.mapper`. Verbatim from [LLD §Mappers line 408](../../lld/adaptation-pipeline.md):
    - `AdaptationJobMapper` — `AdaptationJobDto toDto(AdaptationJob)`; `List<AdaptationJobDto> toDtos(List<AdaptationJob>)`
    - `PendingChangeMapper` — `PendingChangeDto toDto(PendingChange)`; `PendingChangeListItemDto toListItem(PendingChange)` (custom `@Named` qualifier `truncateReasoning` for the 200-char preview); `List<PendingChangeListItemDto> toListItems(List<PendingChange>)`
    - `AdaptationTraceMapper` — `AdaptationTraceDto toDto(AdaptationTrace)`
    - `PlannerHintMapper` — `PlannerHintDto toDto(PlannerHintRecord)`

12. **Custom `@Named` qualifiers** for JSONB → typed record-tree conversions. 01b registers helpers but for v1 the entity JSONB columns are already `JsonNode` (per 01a) — the mappers pass `JsonNode` through unchanged to the DTOs (which also carry `JsonNode`). The `CharacterFingerprintDocument` typed record-tree mentioned in LLD line 279 / 408 is **deferred to 01f** where `FingerprintRefresher` consumes it.

### `AdaptationServiceImpl` skeleton

13. New class `com.example.mealprep.adaptation.domain.service.AdaptationServiceImpl`:
    - `@Service`
    - Implements `AdaptationService`, `AdaptationQueryService` — single impl per LLD line 485.
    - Constructor takes the six repositories (constructor injection); 01c/01d wire helper components (LockService, RecipeWriteApi, etc.) by extending the constructor.
    - **Every method body** is `throw new UnsupportedOperationException("ticket-01c/01d/01e/01f");` with a brief Javadoc naming which ticket implements which method. This makes the bean wire correctly while signalling unfinished state. Subsequent tickets unblock methods one by one.
    - **Exception**: `sweepExpiredPendingChanges()` returns `0` — it's idempotent and safe to call (it would just no-op if there are no PENDING expired rows). This lets the `@Scheduled` cron job registered in 01d safely call even if its impl drift is between tickets. **Worth user review**; alternative is to throw like every other method. The benefit of the no-op is that the scheduled job (registered later) won't generate spurious errors during the inter-ticket window.

### `NutritionalKnowledgeService` placeholder

14. **`NoopNutritionalKnowledgeService`** at `com.example.mealprep.adaptation.domain.service.internal.NoopNutritionalKnowledgeService`. `@Service` + `@ConditionalOnMissingBean(NutritionalKnowledgeService.class)` per the SPI-Noop pattern from [decisions/0010 §What worked](../../../../ai-workflow/decisions/0010-wave2-round7-transactionaleventlistener-propagation.md). Every method returns an empty list / empty bundle. **Removed in 01e** when the real `NutritionalKnowledgeServiceImpl` lands; 01e adds `@Primary` or replaces the Noop. The Noop ships under `internal/` to make module-boundary intent clear.

    **Gotcha note (per Wave 2 round 5 SPI-Noop pattern, in 0010 retro)**: `@Component @ConditionalOnMissingBean` on the **same class** is the round-5 bug — the conditional fires at component-scan, before other beans register. **Use `@Configuration` class + `@Bean @ConditionalOnMissingBean` factory method** with **the @Bean method name DIFFERENT from the class name** to avoid the ordering bug:
    ```java
    @Configuration
    public class NoopNutritionalKnowledgeConfiguration {
      @Bean
      @ConditionalOnMissingBean(NutritionalKnowledgeService.class)
      NutritionalKnowledgeService defaultNutritionalKnowledgeService() {
        return new NoopNutritionalKnowledgeService();
      }
    }
    ```
    **Critical**: `defaultNutritionalKnowledgeService` (the bean method name) MUST differ from the class name `NoopNutritionalKnowledgeService` per round-5 bug-2 fix. **Document explicitly in the impl Javadoc.**

### Module facade update

15. `AdaptationModule.java` (created in 01a as empty marker) **gets typed re-exports**:
    - `public AdaptationService adaptationService()` — returns the injected `AdaptationServiceImpl` bean.
    - `public AdaptationQueryService adaptationQueryService()` — same bean cast to the query interface.
    - `public NutritionalKnowledgeService nutritionalKnowledgeService()` — Noop in 01b, real impl in 01e.
    - **`@Component`** annotation added.
    - Constructor takes the three services; `@Lazy` on the field injections to break circular load order if any sibling module references `AdaptationModule` during their own startup. **Worth user review** — alternative is no facade at all; the `@Service` beans are autowired directly. The facade exists per LLD line 28 "facade re-exporting public service interfaces" and matches every other module's pattern.

### Event records — declared, NOT implemented in 01b

16. **`event/` package created** with the LLD's event record types from [LLD lines 654-682](../../lld/adaptation-pipeline.md), all extending the project's sealed `MealPrepEvent` (from core-01a). These are RECORDS only — no listeners, no publishers in 01b:
    - `AdaptationJobStartedEvent(UUID jobId, UUID recipeId, UUID userId, JobSource source, JobPriority priority, UUID traceId, Instant occurredAt)`
    - `AdaptationCandidateProducedEvent(UUID jobId, UUID recipeId, int candidateCount, BigDecimal topCandidateScore, UUID traceId, Instant occurredAt)`
    - `AdaptationJobCompletedEvent(UUID jobId, UUID recipeId, OutcomeKind outcomeKind, @Nullable UUID outcomeTargetId, AdaptationClassification classification, BigDecimal confidence, UUID traceId, Instant occurredAt)`
    - `AdaptationJobFailedEvent(UUID jobId, UUID recipeId, JobFailureReason reason, String excerpt, UUID traceId, Instant occurredAt)`
    - `PendingChangeCreatedEvent(UUID pendingChangeId, UUID recipeId, UUID userId, ChangeDimension dimension, BigDecimal confidence, BigDecimal impactScore, UUID traceId, Instant occurredAt)`
    - `PendingChangeSupersededEvent(UUID supersededId, UUID supersedingId, UUID recipeId, ChangeDimension dimension, UUID traceId, Instant occurredAt)`
    - `PendingChangeAcceptedEvent(UUID pendingChangeId, UUID recipeId, UUID userId, UUID resultingVersionId, boolean wasModified, UUID traceId, Instant occurredAt)`
    - `PendingChangeRejectedEvent(UUID pendingChangeId, UUID recipeId, UUID userId, UUID traceId, Instant occurredAt)`
    - `PlannerHintEmittedEvent(UUID hintId, UUID recipeId, UUID versionId, HintType type, HintSeverity severity, UUID traceId, Instant occurredAt)`

    **Sealed-permits clause**: each event must be added to `core.events.MealPrepEvent`'s `permits` clause (or a sub-root if the project uses one). **Verify project convention** — if the project uses a single permits clause on `MealPrepEvent`, append nine new entries. If sub-roots exist (e.g. `AdaptationEvent extends MealPrepEvent permits ...`), create the sub-root in 01b. **Reference** [decisions/0011-wave2-round8 §async-listener pattern](../../../../ai-workflow/decisions/0011-wave2-round8-pgvector-stale-state-and-async-race.md) and [lld/core.md §Sealed bases lines 357-369](../../lld/core.md).

## Database

**None.** 01b is interface + DTO + skeleton only. No schema changes.

## OpenAPI updates

**None.** 01b has no HTTP surface. 01d/01e/01f add OpenAPI when controllers land.

## Edge-case checklist

- [ ] `AdaptationService` interface compiles cleanly with all nine methods; signatures match LLD verbatim
- [ ] `AdaptationQueryService` interface compiles with all twelve methods
- [ ] `NutritionalKnowledgeService` interface compiles with all five methods
- [ ] All sixteen public-API DTO records compile; Jakarta annotations resolve
- [ ] `AdaptationServiceImpl` bean loads in Spring DI without errors; every method throws `UnsupportedOperationException` except `sweepExpiredPendingChanges` (returns 0)
- [ ] **Sibling-unblock smoke**: a one-method `@Test` injects `AdaptationService` from a minimal `@SpringBootTest`, calls `enqueueImportJob` with a valid `ImportJobRequest`, expects `UnsupportedOperationException` with message naming "ticket-01d"
- [ ] **MapStruct round-trip**: persist an `AdaptationJob`, map to `AdaptationJobDto`, all fields match (including `JsonNode inputs` preserved as-is)
- [ ] `PendingChangeMapper.toListItem` truncates `reasoning` to 200 chars (test with a 500-char reasoning input)
- [ ] `NoopNutritionalKnowledgeService` wires when no other impl is present; an explicit `@TestConfiguration` providing a fake `NutritionalKnowledgeService @Bean` overrides Noop (validates the SPI-Noop pattern)
- [ ] All nine event records compile; each is a `record` that implements `MealPrepEvent` (or the sub-root); `traceId()` + `occurredAt()` accessors exist
- [ ] **Sealed-permits sanity**: project compiles after adding the nine new event records to `MealPrepEvent`'s (or sub-root's) permits clause — `ddl-auto=validate` AND sealed-hierarchy check both clean
- [ ] `AdaptationModule.java` facade compiles; `adaptationService()` / `adaptationQueryService()` / `nutritionalKnowledgeService()` return the wired beans
- [ ] **Jakarta validation smoke**: a `Validator.validate()` on an `ImportJobRequest` with null `recipeId` returns one constraint violation; same for `DataModelJobRequest` with `affectedRecipeIds.size() == 5001` (over `@Size(max = 5000)`)
- [ ] **`AdaptationServiceImpl` IS NOT `@Primary` and IS NOT `@Lazy`** — it's the only impl of the two interfaces; standard `@Service` annotation. Adding `@Primary` would be a smell.
- [ ] **No service method body returns a real result** — every method (except `sweepExpiredPendingChanges → 0`) throws. 01c/01d/01e/01f fill in.
- [ ] `Catalogue` and `DataQuality` enums import cleanly from the recipe module (or wherever they actually live per 01a's confirmation)
- [ ] `PlanConstraintsSnapshotDto` shape is consumed by `PlanTimeRefineDirectiveRequest` cleanly — agent verifies a fixture record can be constructed

## Files this ticket touches

```
NEW   src/main/java/com/example/mealprep/adaptation/domain/service/AdaptationService.java
NEW   src/main/java/com/example/mealprep/adaptation/domain/service/AdaptationQueryService.java
NEW   src/main/java/com/example/mealprep/adaptation/domain/service/NutritionalKnowledgeService.java
NEW   src/main/java/com/example/mealprep/adaptation/domain/service/AdaptationServiceImpl.java        (skeleton — every method throws UOE)

NEW   src/main/java/com/example/mealprep/adaptation/domain/service/internal/NoopNutritionalKnowledgeService.java
NEW   src/main/java/com/example/mealprep/adaptation/domain/service/internal/NoopNutritionalKnowledgeConfiguration.java   (the @Configuration carrying @Bean @ConditionalOnMissingBean)

NEW   src/main/java/com/example/mealprep/adaptation/api/dto/AdaptationJobDto.java
NEW   src/main/java/com/example/mealprep/adaptation/api/dto/AdaptationResultDto.java
NEW   src/main/java/com/example/mealprep/adaptation/api/dto/ImportJobRequest.java
NEW   src/main/java/com/example/mealprep/adaptation/api/dto/FeedbackJobRequest.java                  (with inner RatingDeltaDto)
NEW   src/main/java/com/example/mealprep/adaptation/api/dto/DataModelJobRequest.java
NEW   src/main/java/com/example/mealprep/adaptation/api/dto/PlannerHintRequest.java
NEW   src/main/java/com/example/mealprep/adaptation/api/dto/PlanTimeRefineDirectiveRequest.java     (with inner RefineDirectiveDto)
NEW   src/main/java/com/example/mealprep/adaptation/api/dto/PlanConstraintsSnapshotDto.java
NEW   src/main/java/com/example/mealprep/adaptation/api/dto/PendingChangeDto.java
NEW   src/main/java/com/example/mealprep/adaptation/api/dto/PendingChangeListItemDto.java
NEW   src/main/java/com/example/mealprep/adaptation/api/dto/AcceptPendingChangeRequest.java
NEW   src/main/java/com/example/mealprep/adaptation/api/dto/RejectPendingChangeRequest.java
NEW   src/main/java/com/example/mealprep/adaptation/api/dto/AdaptationCandidateDto.java
NEW   src/main/java/com/example/mealprep/adaptation/api/dto/AdaptationRollupDto.java
NEW   src/main/java/com/example/mealprep/adaptation/api/dto/PlannerHintDto.java
NEW   src/main/java/com/example/mealprep/adaptation/api/dto/AdaptationTraceDto.java

NEW   src/main/java/com/example/mealprep/adaptation/api/dto/NutritionalPairingDto.java
NEW   src/main/java/com/example/mealprep/adaptation/api/dto/MethodBioavailabilityDto.java
NEW   src/main/java/com/example/mealprep/adaptation/api/dto/PrepRequirementDto.java
NEW   src/main/java/com/example/mealprep/adaptation/api/dto/AbsorptionConflictDto.java
NEW   src/main/java/com/example/mealprep/adaptation/api/dto/NutritionalKnowledgeBundleDto.java

NEW   src/main/java/com/example/mealprep/adaptation/api/dto/DataModelChangeType.java               (enum)
NEW   src/main/java/com/example/mealprep/adaptation/api/dto/DirectiveKind.java                      (enum)

NEW   src/main/java/com/example/mealprep/adaptation/api/mapper/AdaptationJobMapper.java
NEW   src/main/java/com/example/mealprep/adaptation/api/mapper/PendingChangeMapper.java
NEW   src/main/java/com/example/mealprep/adaptation/api/mapper/AdaptationTraceMapper.java
NEW   src/main/java/com/example/mealprep/adaptation/api/mapper/PlannerHintMapper.java

NEW   src/main/java/com/example/mealprep/adaptation/event/AdaptationJobStartedEvent.java
NEW   src/main/java/com/example/mealprep/adaptation/event/AdaptationCandidateProducedEvent.java
NEW   src/main/java/com/example/mealprep/adaptation/event/AdaptationJobCompletedEvent.java
NEW   src/main/java/com/example/mealprep/adaptation/event/AdaptationJobFailedEvent.java
NEW   src/main/java/com/example/mealprep/adaptation/event/PendingChangeCreatedEvent.java
NEW   src/main/java/com/example/mealprep/adaptation/event/PendingChangeSupersededEvent.java
NEW   src/main/java/com/example/mealprep/adaptation/event/PendingChangeAcceptedEvent.java
NEW   src/main/java/com/example/mealprep/adaptation/event/PendingChangeRejectedEvent.java
NEW   src/main/java/com/example/mealprep/adaptation/event/PlannerHintEmittedEvent.java

MOD   src/main/java/com/example/mealprep/adaptation/AdaptationModule.java                          (re-export the 3 services + key DTOs)
MOD   src/main/java/com/example/mealprep/core/events/MealPrepEvent.java                            (add 9 entries to the permits clause)
                                                                                                    (or — if sub-roots are the project pattern — create AdaptationEvent.java sub-root sealed interface with permits)

NEW   src/test/java/com/example/mealprep/adaptation/AdaptationServiceContractTest.java             (interface signature smoke — Reflection asserts every LLD-listed method exists with the right signature)
NEW   src/test/java/com/example/mealprep/adaptation/AdaptationServiceImplSkeletonTest.java         (every method throws UOE with the right ticket marker; sweepExpiredPendingChanges returns 0)
NEW   src/test/java/com/example/mealprep/adaptation/AdaptationDtoValidationTest.java               (Jakarta @Valid smoke on every Request DTO)
NEW   src/test/java/com/example/mealprep/adaptation/AdaptationMapperRoundTripTest.java             (each mapper round-trips its entity)
NEW   src/test/java/com/example/mealprep/adaptation/NoopNutritionalKnowledgeServiceTest.java        (empty results; overridden by @TestConfiguration impl)
```

**Files this ticket does NOT touch**:

- Migrations (01a shipped the schema; 01b ships zero SQL)
- `AdaptationServiceImpl` method bodies beyond UOE + `sweepExpiredPendingChanges → 0` (01c/01d/01e/01f)
- All `internal/` helper classes (`CandidateGenerator`, `ScoringEngine`, etc.) — 01c
- Controllers — 01d/01f
- `RecipeAdaptationTask` + `AdaptationContextAssembler` — 01e
- Custom validators (`@ValidPlannerHint`, `@ValidRecipeDiff`) — 01d
- ArchUnit `ModuleBoundaryArchTest` — 01f

## Dependencies

- **Hard dependency**: `adaptation-pipeline-01a` (merged) — all entities, enums, repositories, exceptions.
- **Hard dependency**: `core-01a` (merged) — `MealPrepEvent` sealed root.
- **Hard dependency**: `ai-01a` (merged) — `AiUnavailableException` reference (already imported by `AdaptationAiUnavailableException` in 01a).
- **Hard dependency**: `recipe-01a` (merged) — `Catalogue` enum public availability.
- **Soft dependency**: any module providing the `DataQuality` enum (likely `recipe-01b`); agent verifies.
- **Sibling tickets (Wave 3) hard-blocking on this ticket**: `planner-01*` (Stage D injects `AdaptationService`), `feedback-01*` (RecipeDestination handler injects `AdaptationService`). 01b unblocks both.

## Acceptance / DoD

- [ ] `./mvnw -Dspotless.check.skip=true verify` passes
- [ ] CI green (build + spotless + OpenAPI lint — no changes — + ArchUnit)
- [ ] All edge-case items above ticked
- [ ] **`AdaptationService` and `AdaptationQueryService` signatures match LLD verbatim** — the `AdaptationServiceContractTest` enforces this via reflection
- [ ] `NoopNutritionalKnowledgeConfiguration` follows the round-5 bug-2 fix pattern (`@Bean` method name differs from class name)
- [ ] Sealed `MealPrepEvent` (or sub-root) compiles after the nine new event records are added to its permits clause
- [ ] No regression on existing tests
- [ ] No pom.xml dependency adds (MapStruct, Jakarta validation, hypersistence-utils — all present from earlier modules)
- [ ] **No file outside the listed paths is touched** — in particular, NO change to other modules' src/, NO change to other modules' tickets

Squash-merge with: `feat(adaptation): 01b — public service contracts + DTOs + mappers (sibling-unblock; 3 interfaces, 21 DTOs, 4 mappers, 9 events)`

## What's NOT in scope

- **Any service-method body**. Every `AdaptationServiceImpl` method throws UOE; `sweepExpiredPendingChanges` returns 0.
- All `internal/` helpers (`CandidateGenerator`, `ScoringEngine`, `AdaptationLlmInvoker`, `PendingChangeStore`, `RebaseOrchestrator`, gate components, `AdaptationTraceWriter`, `BatchJobOrchestrator`, `FingerprintRefresher`, `PlannerHintEmitter`, `AdaptationContextAssembler`, `NutritionalKnowledgeRegistry`) — **01c–01f**.
- `RecipeAdaptationTask` + `RecipeAdaptationResponse` — **01e** (the prompt file in `prompts/adaptation/recipe-adaptation.txt` also ships in 01e per LLD §Out of Scope point 1).
- All controllers (`PendingChangesController`, `AdaptationAdminController`, `AdapterRunHistoryController`) — **01d** / **01f**.
- All custom validators — **01d**.
- All event LISTENERS (`onRecipeImported`, `onPreferenceChanged`, `onHardConstraintsChanged`, `onNutritionTargetsChanged`, `onProvisionsBudgetChanged`) — **01d** (with `@TransactionalEventListener(AFTER_COMMIT)` + `@Transactional(REQUIRES_NEW)` per decisions/0010 round-7 lesson).
- Event PUBLISHING — **01c** (worker pipeline publishes them).
- `@Scheduled` jobs (`pendingExpirySweepCron`, `batchOrchestratorCron`) — **01d** / **01f**.
- The OpenAPI YAML excerpts — **01d** / **01f** (only when controllers land).
- `OptimiserService` interface (the sibling-LLD-referenced name) — **NOT shipped**. Per Option A in §Naming reconciliation, the LLD's `AdaptationService` is the canonical name. Sibling tickets adopt this name.
- `InfeasibleDirective` signal — the LLD says the planner reads `AdaptationResultDto.classification == NO_CHANGE` as the infeasibility signal (LLD line 812); no dedicated signal type. Planner-ticket authors should consume the result classification directly.
