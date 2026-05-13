# Ticket: adaptation-pipeline — 01c Shared Worker Pipeline (Stages A → B → C → Apply)

## Summary

Implement the **shared worker pipeline** that every trigger flow runs through: deterministic candidate generation (Stage A), rollup scoring (Stage B), LLM dispatch via `AiService.execute(RecipeAdaptationTask)` (Stage C), validation gates, and apply-via-`RecipeWriteApi` (Stage Apply). Per [lld/adaptation-pipeline.md §Shared worker pipeline (lines 736-772)](../../lld/adaptation-pipeline.md). Ships the **eleven internal helpers** under `domain/service/internal/`:

- `CandidateGenerator` — enumerates ingredient swaps, portion adjusts, method tweaks (LLD §Stage A line 743).
- `HardConstraintFilterService` integration (existing service from preference/nutrition; 01c injects + calls).
- `ScoringEngine` — produces `AdaptationRollupDto` per surviving candidate; selects top-N (LLD §Stage B line 744).
- `AdaptationLlmInvoker` — builds and dispatches the AI call (LLD §Stage C line 746); wraps `AiUnavailableException` per LLD line 632. **`AiTask<T>` shape and the dispatch itself land in 01e** — 01c calls a placeholder method that 01e replaces; see "Inter-ticket coupling" below.
- `ConfidenceFloorGate` — confidence < `lowConfidenceFloor` → defensive flag (LLD line 753).
- `CharacterPreservationGate` — score < 0.6 → reject; high-coherence BRANCH candidates treated as branch (LLD lines 754-755).
- `RebaseOrchestrator` — catches `RecipeVersionConflictException` and retries up to `maxRebaseAttempts = 3` (LLD lines 762-763).
- `PendingChangeStore` — creates pending changes for USER catalogue; supersedes existing PENDING for `(recipe_id, dimension)` atomically via the partial unique index (LLD lines 759-760).
- `ChangeDimensionResolver` — maps the AI's diff + trigger payload to a `ChangeDimension` enum value (LLD line 760).
- `AdaptationTraceWriter` — writes the trace row in `REQUIRES_NEW` so a rolled-back outer tx still keeps the diagnostic (LLD line 765, mirrors `AiCallRecorder`).
- **Decision-log integration**: every job iteration writes a row via `core.audit.DecisionLogService.write(...)` per the parent's brief; same `traceId` chains parent (planner) ↔ child (adaptation) decisions.

Also ships the **worker entry point** — a private `processJob(AdaptationJob)` method on `AdaptationServiceImpl` (filling in body for what 01b stubbed) that orchestrates the ten steps from LLD lines 738-770. The four public trigger entry methods (`enqueueImportJob`, `enqueueFeedbackJob`, `enqueueDataModelChangeJobs`, `runPlanTimeRefineJob`) stay UOE until **01d** wires them — 01c is internal-helper-only, ensuring the pipeline machine is buildable end-to-end before the trigger surface plugs in.

**Decision-log writes** are per-iteration (LLD §Decisions §5; parent brief): every Stage A→B→C iteration on a job writes one `DecisionLog` row with `scale = "recipe"`, `triggered_by = source` mapped from `JobSource`, `parent_decision_id = job.parentDecisionId` (from Trigger 4) or null. Inputs JSONB carries the candidate set; chosen JSONB carries the winner; reasoning = LLM rationale OR `"auto-skip: top score 2x"` when the deterministic-skip path fires (LLD §Stage C line 749).

## Inter-ticket coupling — what 01c can/can't call

**Can call** (already shipped from other modules):

- `com.example.mealprep.ai.spi.AiTask<T>` interface + `AiService.execute(AiTask<T>)` dispatcher — **but `RecipeAdaptationTask` itself lives in `adaptation/ai/`** and is **shipped in 01e**, not 01c. 01c references the SPI via a `RecipeAdaptationTaskFactory` interface (declared here, impl deferred to 01e).
- `com.example.mealprep.recipe.spi.RecipeWriteApi.saveAdaptedVersion / saveAdaptedBranch / saveAdaptedSubstitution` — already shipped (recipe-01f); 01c injects directly.
- `com.example.mealprep.core.lock.LockService.tryAcquire(LockKey.forRecipe(recipeId))` — already shipped (core-01a).
- `com.example.mealprep.core.audit.DecisionLogService.write(...)` — already shipped (core-01a); 01c calls per-iteration.
- `com.example.mealprep.recipe.api.service.RecipeQueryService.findRecipe`, `findCurrentVersion`, `getCharacterFingerprint` — already shipped (recipe-01a/01b).
- `com.example.mealprep.preference.api.service.PreferenceQueryService.getSoftPreferences` — already shipped.
- `com.example.mealprep.nutrition.api.service.NutritionFloorGateService` — already shipped.
- `com.example.mealprep.adaptation.domain.service.NutritionalKnowledgeService` — interface shipped in 01b; Noop wired; **01e ships the real impl**. 01c calls through the interface — gets empty bundles from Noop, useful bundles once 01e lands.
- `com.example.mealprep.preference.spi.HardConstraintFilterService.filterRecipes` / `checkRecipe` — already shipped.

**Cannot call yet** (deferred coupling — 01c uses a typed placeholder):

- **`RecipeAdaptationTask` construction**. 01c injects a `RecipeAdaptationTaskFactory` interface (defined here at `adaptation/ai/RecipeAdaptationTaskFactory.java`); the impl ships in 01e. 01c also ships a **Noop factory** that throws `AdaptationAiUnavailableException("RecipeAdaptationTask factory not wired — ticket 01e")` so the pipeline fails loudly until 01e merges. **Pattern same as the recipe-01f / nutrition-01f cross-SPI bridge** — see [decisions/0010-wave2-round7 §What worked](../../../../ai-workflow/decisions/0010-wave2-round7-transactionaleventlistener-propagation.md). The Noop uses **`@Configuration` class + `@Bean @ConditionalOnMissingBean` with a different bean method name** (round-5 bug-2 fix from [decisions/0010](../../../../ai-workflow/decisions/0010-wave2-round7-transactionaleventlistener-propagation.md)).

**`AdaptationLlmInvoker` shape in 01c**:

```java
@Component
public class AdaptationLlmInvoker {
  private final AiService aiService;
  private final RecipeAdaptationTaskFactory taskFactory;     // interface in 01c; Noop now, real in 01e
  private final AdaptationConfig config;

  RecipeAdaptationResponse invoke(AdaptationJob job, AdaptationContext context) {
    RecipeAdaptationTask task = taskFactory.build(job, context);     // 01e fills in
    try {
      return aiService.execute(task);
    } catch (AiUnavailableException e) {
      throw new AdaptationAiUnavailableException("ai-unavailable: " + e.getMessage(), e);
    }
  }
}
```

`RecipeAdaptationResponse` is the structured-output type from LLD lines 857-865; 01c ships the record (it's the input to validation gates which all live in 01c). The `RecipeAdaptationTask` class itself lands in 01e.

## Behavioural spec

### Worker entry point: `processJob(AdaptationJob)` on `AdaptationServiceImpl`

1. New private method `void processJob(AdaptationJob job)` on `AdaptationServiceImpl`. **NOT** `@Transactional` on the whole method per [LLD §Concurrency line 877](../../lld/adaptation-pipeline.md) "Worker processing is **not** transactional; each DB-touching step opens its own short tx." Each helper method below uses its own `@Transactional` scope.

2. The method runs the ten-step pipeline verbatim from [LLD §Shared worker pipeline lines 738-770](../../lld/adaptation-pipeline.md). 01c implements the orchestration; each step delegates to a helper component. The method is called by the four public trigger methods (01d wires).

### Step 1 — Acquire advisory lock

3. `Optional<LockHandle> handle = lockService.tryAcquire(LockKey.forRecipe(job.getRecipeId()));`
4. **Lock-acquire failure** branches per trigger source (LLD §Concurrency line 880):
   - `IMPORT` (Trigger 1) → defer + schedule retry via `@Scheduled` poll-fallback at +5 min; mark job `status = PENDING` with `failure_excerpt = "lock-deferred"`; **do not** transition to FAILED. **01c ships the defer-marker**; the actual re-poll scheduling lives in **01d** (`@Scheduled` infrastructure).
   - `FEEDBACK` (Trigger 2) → throw `LockTimeoutException` per LLD line 880. The 01d feedback-entry method (synchronous) catches and surfaces "couldn't propose; please retry" to the user.
   - `DATA_MODEL_CHANGE` (Trigger 3) → defer to next batch sweep; mark `status = PENDING` with `failure_excerpt = "lock-deferred-batch"`. Same as Trigger 1 in mechanism, different excerpt.
   - `PLAN_TIME` (Trigger 4) → throw `LockTimeoutException` per LLD line 880. The planner reads as infeasibility-escalate.

5. **`LockService.tryAcquire` MUST be called inside an active `@Transactional`** (per [lld/core.md line 285](../../lld/core.md)). So the dispatcher private method that wraps step 1 is `@Transactional(propagation = REQUIRED, noRollbackFor = LockTimeoutException.class)`. **`noRollbackFor`** matters because the lock-deferred branches for IMPORT/DATA_MODEL_CHANGE write a `failure_excerpt` to the job row and need that write to commit even though the method "fails" the work (per [agent-prompt-template gotcha line 256](../../../../ai-workflow/templates/agent-prompt-template.md)): **the audit write needs to commit before the 4xx-equivalent surfaces.**

### Step 2 — Load context

6. **Single read transaction** (`@Transactional(readOnly = true)`):
   - `RecipeQueryService.findRecipe(job.recipeId)` + `findCurrentVersion(recipe.currentVersionId)` — loads body and ingredients.
   - `getCharacterFingerprint(versionId)` — may return empty for first-import (LLD §Trigger 1 line 778).
   - `PreferenceQueryService.getSoftPreferences(job.userId)` — fallback soft prefs if user has none.
   - `NutritionFloorGateService.getTargets(job.userId)` — returns user's daily/weekly nutrition floors.
   - `NutritionalKnowledgeService.lookupForRecipe(versionId, ingredientMappingKeys)` — 01c calls; in 01b/01c the Noop returns an empty bundle.
   - **Assemble `AdaptationContext`** typed record (declared in 01c at `adaptation/ai/AdaptationContext.java`) carrying all the above + source-specific payload (feedback text, directive, data-model-change summary).

7. **`AdaptationContext` shape**:
   ```java
   public record AdaptationContext(
       String mode,                                       // "IMPORT" | "FEEDBACK" | "DATA_MODEL_CHANGE" | "PLAN_TIME_REFINE"
       RecipeSummaryView recipeSummary,                   // current version body, fingerprint
       List<AdaptationCandidateDto> candidates,           // pre-vetted N from Stage A/B (added in step 4)
       SoftPreferenceSummary softPreferences,
       String hardConstraintsHash,                        // for prompt-cache stability
       NutritionTargetsSummary nutritionTargets,
       NutritionalKnowledgeBundleDto knowledgeBundle,
       @Nullable String feedbackText,
       @Nullable FeedbackJobRequest.RatingDeltaDto ratingDelta,
       @Nullable PlanTimeRefineDirectiveRequest.RefineDirectiveDto directive,
       @Nullable JsonNode dataModelChange) {}
   ```
   Cross-module types `RecipeSummaryView`, `SoftPreferenceSummary`, `NutritionTargetsSummary` — agent reuses the existing DTOs from recipe/preference/nutrition modules (the LLD doesn't specify; 01c verifies the actual names during impl).

### Step 3 — Stage A: candidate generation + hard-constraint filter

8. **`CandidateGenerator`** at `adaptation/domain/service/internal/CandidateGenerator.java`. Strategy pattern; one strategy per generation kind:
   - `IngredientSwapStrategy` — emit candidates substituting each top-3 ingredient with v1 hard-coded substitutes (`beef → chicken`, `wheat flour → gluten-free flour`, etc.). **v1 hard-coded list** per the LLD's silence on the swap-knowledge source; full LLM-guided exploration deferred.
   - `PortionAdjustStrategy` — emit candidates at 0.75×, 0.875×, 1.25×, 1.5× current serving.
   - `MethodSimplificationStrategy` — emit candidates dropping the longest method step OR collapsing two consecutive prep steps.
   - `IngredientRemoveStrategy` — emit candidates dropping each optional ingredient.

9. **Source-biased seeding** per LLD §Trigger 2 line 784 ("low taste → flavour-balance candidates; low effort-worth-it → method-simplification"):
   - `FEEDBACK` source + `ratingDelta.taste < -0.5` → seed `FlavourBalanceStrategy` (subset of `IngredientSwapStrategy` biased to salt/acid/spice swaps).
   - `FEEDBACK` source + `ratingDelta.effortWorthIt < -0.5` → seed `MethodSimplificationStrategy`.
   - `PLAN_TIME` source + `directive.kind == COST_DELTA` → seed `IngredientSwapStrategy` biased to cost reduction.
   - `PLAN_TIME` source + `directive.kind == NUTRITION_DELTA` → seed `IngredientSwapStrategy` filtered to nutrient-matching swaps.
   - `PLAN_TIME` source + `directive.kind == TIME_DELTA` → seed `MethodSimplificationStrategy`.
   - `PLAN_TIME` source + `directive.kind == EQUIPMENT_OVERLAP` → seed `IngredientSwapStrategy` filtered to equipment-shedding swaps.
   - `PLAN_TIME` source + `directive.kind == INGREDIENT_SWAP` → seed `IngredientSwapStrategy` exact-targeted (3 substitutes for the named ingredient).

10. **Hard-constraint filter** — call `HardConstraintFilterService.filterRecipes(candidates, userId)` per LLD §Stage A line 743 "drops infeasible candidates BEFORE scoring — never bypassed; the safety net is invariant per HLD §Guardrails." Zero survivors → job FAILED(`HARD_FILTER`); trace row records the attempt.

### Step 4 — Stage B: scoring + top-N

11. **`ScoringEngine`** at `adaptation/domain/service/internal/ScoringEngine.java`. For each surviving candidate, compute `AdaptationRollupDto` (LLD lines 386-389):
    - `macroDeltaProteinG / Carbs / Fat / Kcal` — sum the per-ingredient nutrition deltas via `NutritionCalculationService.calculateDelta(beforeIngredients, afterIngredients)` (already shipped).
    - `microDeltas` — same path, microsection.
    - `costDeltaGbp` — pull from the ingredient mapping cache (nutrition-01d's `IngredientMappingPipeline.lookup`).
    - `timeDeltaMins` — sum the method-step time deltas.
    - `ingredientCountDelta` — `after.size() - before.size()`.
    - `tasteAlignmentScore` — 1.0 minus the L2 distance between the candidate's flavour-profile vector and the user's soft-preference vector (v1 deterministic; LLD doesn't fully spec — placeholder formula sufficient).
    - `equipmentDelta` — symmetric difference of the candidate's required equipment vs. the user's available equipment.
    - `warnings` — populated from constraint-filter near-misses + deterministic culinary heuristics (e.g. "removed a salt-balancing acid").

12. **Top-N selection**: sort by `tasteAlignmentScore DESC, |macroDeltaKcal| ASC` (taste-first, smallest macro disruption second). Take `config.candidateTopN()` (default 5 per LLD line 706).

13. **Auto-skip Stage C** when `top score > 2.00 × runner-up score` per [LLD §Stage C line 749](../../lld/adaptation-pipeline.md) (the loop's deterministic-skip rule). Skip writes a trace row marked `validation_result = NO_CHANGE` (no — actually `PASSED`) and `outcome_kind = NO_OP` (no — the top candidate is applied). **Correction per LLD**: auto-skip means the LLM is bypassed; the deterministic top candidate is **applied as the winner**. Trace records "deterministic-skip" in `raw_ai_response` (null) and `reasoning = "auto-skip: top score 2x"`. **Worth user review** — alternative would be to record `NO_CHANGE` and apply nothing; the LLD says "auto-skips per the loop's top-2x rule" (line 17) and the loop-pattern doc treats this as "no LLM needed, apply deterministic winner."

### Step 5 — Stage C: AI dispatch

14. **`AdaptationLlmInvoker.invoke(job, context)`** — shape per "Inter-ticket coupling" above. Returns `RecipeAdaptationResponse` per LLD lines 857-865:
    ```java
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
    **`RecipeDiffDto` lives in the recipe module** (recipe-01c manual-edit-and-diff) — 01c imports it. Agent verifies the actual type name during impl.

15. **`AiUnavailableException` handling** (LLD §Stage C line 747): the wrapping into `AdaptationAiUnavailableException` happens **inside** `AdaptationLlmInvoker.invoke` (see skeleton above). The orchestrator catches `AdaptationAiUnavailableException`, transitions the job to FAILED with `failure_reason = AI_UNAVAILABLE`, writes the trace row, publishes `AdaptationJobFailedEvent`, and **rethrows** for sync triggers (FEEDBACK, PLAN_TIME) — async triggers (IMPORT, DATA_MODEL_CHANGE) just persist the FAILED state and return.

    **Block-and-prompt fallback**: per LLD line 632 the failure-reason `AI_UNAVAILABLE` triggers the `Notification` module's listener on `AdaptationJobFailedEvent` (filtered to `reason = AI_UNAVAILABLE`) which surfaces "AI features paused" to the user. **The listener lives in the Notification module** — 01c just publishes the event with the right reason; the listener is out of scope.

### Step 6 — Validation gates (all must pass)

16. **`ConfidenceFloorGate`** at `adaptation/domain/service/internal/ConfidenceFloorGate.java`. `evaluate(response)` returns `PASSED` if `confidence ≥ config.lowConfidenceFloor()` (default 0.50 per LLD line 713); else `LOW_CONFIDENCE` — but **does not throw**. Per LLD line 753 ("defensive flag for user review even on SYSTEM catalogue"), low-confidence does NOT reject the candidate — it forces `approval_policy = PENDING_CHANGE` even when the job's source policy would be `DIRECT`. **01c implements this fallback**: if `ConfidenceFloorGate.evaluate(response) == LOW_CONFIDENCE`, override `effectiveApprovalPolicy = PENDING_CHANGE`.

17. **`CharacterPreservationGate`** at `adaptation/domain/service/internal/CharacterPreservationGate.java`. `evaluate(response)`:
    - `characterPreservationScore < 0.6` → reject UNLESS the LLM also returned `classification = BRANCH` AND `coherenceScore > 0.7` (the latter is part of `RecipeAdaptationResponse` — see LLD line 863 line `BigDecimal characterPreservationScore`; **`coherenceScore` is not explicitly spec'd in the LLD's response shape** — agent uses `confidence` as proxy if no separate field is needed).
    - Reject: throw `AdaptationCharacterBreakException`.
    - High-coherence branch: continue with `classification = BRANCH` regardless of the job's expected output type.

18. **Re-run `HardConstraintFilterService.checkRecipe`** against the FINAL diff per LLD line 757 "guards against the LLM stitching a candidate together post-hoc." Failure → throw `AdaptationHardConstraintViolationException`.

### Step 7 — Apply per approval_policy

19. **`PendingChangeStore.create(job, response, dimension, baseVersionId, baseBranchId)`** at `adaptation/domain/service/internal/PendingChangeStore.java`. Used when `effectiveApprovalPolicy == PENDING_CHANGE` (USER catalogue or low-confidence override). Flow:
    - Resolve `ChangeDimension` via `ChangeDimensionResolver` (step 20).
    - Open a `@Transactional` scope.
    - `UPDATE adaptation_pending_changes SET status = 'SUPERSEDED', superseded_by = :newId, resolved_at = now() WHERE recipe_id = :rid AND change_dimension = :dim AND status = 'PENDING';` — single-shot atomic SQL via `@Modifying @Query`. Per LLD line 157 the partial unique index serialises concurrent supersession.
    - `INSERT` new `PendingChange` row with `status = PENDING`, `expires_at = now() + 14 days` (from `config.pendingChangeExpiryDays()`).
    - **On unique-constraint violation** (`DataIntegrityViolationException` with constraint name `idx_adaptation_pending_recipe_dim_active`): retry once (concurrent supersession winner finished between our UPDATE and INSERT) — fetch the now-existing PENDING row, mark our work-in-progress as SUPERSEDED preemptively, and write a new SUPERSEDED-flagged row attached to our job. Document the race in Javadoc.
    - Publish `PendingChangeCreatedEvent` `AFTER_COMMIT` (deferred to step 25 — events fire after the trace + status updates commit).

20. **`ChangeDimensionResolver`** at `adaptation/domain/service/internal/ChangeDimensionResolver.java`. Pure function:
    - `FEEDBACK` source + `ratingDelta.taste < -0.5` → `SALT_LEVEL` (default) or `FLAVOUR_BALANCE` if the response's diff mentions acid/spice.
    - `FEEDBACK` source + `ratingDelta.effortWorthIt < -0.5` → `METHOD_SIMPLIFICATION`.
    - `FEEDBACK` source + `ratingDelta.portionFit < -0.5` → `PORTION_SIZE`.
    - Otherwise inspect the `response.refinedDiff` for the most-changed delta-kind: ingredient → `PROTEIN` if protein-bearing ingredient swapped, `FLAVOUR_BALANCE` else; method-step change → `COOKING_TIME` if time delta non-zero, `METHOD_SIMPLIFICATION` else.
    - Unmatched → `GENERAL` per LLD line 245 "Unseeded values surface a WARN log and fall back to general."

21. **`RecipeWriteApi.saveAdaptedVersion(SaveAdaptedVersionCommand)`** — when `effectiveApprovalPolicy == DIRECT` (SYSTEM catalogue) AND `classification == VERSION`. Build the command from `response.refinedDiff` + `adapterTraceId = job.traceId`.

22. **`RecipeWriteApi.saveAdaptedBranch(SaveAdaptedBranchCommand)`** — when `classification == BRANCH`. Build the command including the character fingerprint from `response`.

23. **`RecipeWriteApi.saveAdaptedSubstitution(SaveAdaptedSubstitutionCommand)`** — when `effectiveApprovalPolicy == PLAN_OVERLAY` (Trigger 4 always) OR `classification == SUBSTITUTION`.

24. **`RebaseOrchestrator`** at `adaptation/domain/service/internal/RebaseOrchestrator.java`. Wraps every `RecipeWriteApi.saveAdaptedVersion / saveAdaptedBranch` call:
    ```java
    public RecipeVersionDto saveAdaptedVersionWithRebase(SaveAdaptedVersionCommand command) {
      for (int attempt = 1; attempt <= config.maxRebaseAttempts(); attempt++) {
        try {
          return recipeWriteApi.saveAdaptedVersion(command);
        } catch (RecipeVersionConflictException e) {
          if (attempt == config.maxRebaseAttempts()) throw new RebaseExhaustedException("rebase exhausted after " + attempt + " attempts", e);
          command = rebase(command);                              // re-read parent version + rebuild expected{Version,Number}
        }
      }
      throw new IllegalStateException("unreachable");             // for the compiler
    }
    ```
    `rebase(command)` re-fetches the current version of the recipe and updates `expectedParentVersionId` + `expectedParentVersionNumber`. Throws `RebaseExhaustedException` (declared in 01a) after `maxRebaseAttempts` failures; the orchestrator catches and transitions the job to FAILED with `failure_reason = REBASE_EXHAUSTED`.

### Step 8 — Trace write (`REQUIRES_NEW`)

25. **`AdaptationTraceWriter.write(traceData)`** at `adaptation/domain/service/internal/AdaptationTraceWriter.java`. **CRITICAL**: `@Transactional(propagation = REQUIRES_NEW)` per [LLD line 765-766](../../lld/adaptation-pipeline.md) "writes the trace row in REQUIRES_NEW so a rolled-back outer tx still keeps the diagnostic." Same pattern as `AiCallRecorder` in [lld/ai.md](../../lld/ai.md). **Verified against [decisions/0010 §round-7 gotcha](../../../../ai-workflow/decisions/0010-wave2-round7-transactionaleventlistener-propagation.md)**: `REQUIRES_NEW` is one of the two allowed propagations alongside `NOT_SUPPORTED`. Not a `@TransactionalEventListener` here (this is called from within the worker, not from a listener), but the same propagation correctness rules apply.

26. **Trace row content** (LLD lines 167-185): copies `job_id`, `recipe_id`, `trace_id`, `source`, prompt-template metadata, `candidates` snapshot (the Stage A/B output), `chosen_candidate_index` (or null for auto-skip), `classification_decision`, `final_diff`, `confidence`, `character_preservation_score`, `validation_result`, `outcome_kind`, `outcome_target_id`, `duration_ms`.

### Decision-log integration

27. **One `DecisionLog` row per worker iteration** per parent brief + [lld/core.md §DecisionLog write line 436](../../lld/core.md). Called from inside the worker after the trace write but before the events fire:
    ```java
    DecisionLogService.write(WriteDecisionLogRequest.builder()
        .decisionId(UUID.randomUUID())
        .traceId(job.getTraceId())
        .parentDecisionId(job.getParentDecisionId())             // non-null for Trigger 4
        .scale("recipe")
        .triggeredBy(mapSource(job.getSource()))                 // "user" | "feedback" | "data-model-change" | "refine-directive"
        .scopeKind(EventScopeKind.RECIPE)
        .scopeId("recipe:" + job.getRecipeId())
        .inputs(buildInputsPayload(context))
        .candidates(buildCandidatesPayload(stageBOutput))
        .chosen(buildChoicePayload(response))
        .reasoning(response.reasoning())
        .iteration(0)                                            // recipe-scale jobs run a single iteration
        .durationMs(durationMs)
        .actorUserId(job.getUserId())
        .build());
    ```

28. **Source-to-`triggered_by` mapping**:
    - `IMPORT` → `"user"` (manual create / URL import — the user initiated)
    - `FEEDBACK` → `"feedback"`
    - `DATA_MODEL_CHANGE` → `"data-model-change"`
    - `PLAN_TIME` → `"refine-directive"`

29. **DecisionLog write joins the caller's tx** (`@Transactional(REQUIRED)` per [lld/core.md line 436](../../lld/core.md)). It's called inside the orchestrator's transactional step around steps 7-8, NOT outside.

### Status updates + event publishing

30. After successful apply: update `job.status = DONE`, `completed_at = now()`, `duration_ms`. Publish `AdaptationJobCompletedEvent` `AFTER_COMMIT`.

31. On failure (any of the exceptions thrown above): update `job.status = FAILED`, `failure_reason`, `failure_excerpt`. Publish `AdaptationJobFailedEvent` `AFTER_COMMIT`.

32. **Event publishing pattern**: use `ApplicationEventPublisher` injected on `AdaptationServiceImpl`. Per [decisions/0010 §round-7 propagation rule](../../../../ai-workflow/decisions/0010-wave2-round7-transactionaleventlistener-propagation.md): downstream `@TransactionalEventListener(AFTER_COMMIT)` listeners (e.g. Notification's `AdaptationJobFailedEvent` filter on `AI_UNAVAILABLE`) MUST use `@Transactional(propagation = REQUIRES_NEW)` if they do JPA work — but those listeners live in other modules; 01c just publishes.

### Status transition helper (`@Transactional`)

33. `transitionJobStatus(jobId, newStatus, failureReason, excerpt)` private method on `AdaptationServiceImpl`. `@Transactional(REQUIRED, noRollbackFor = AdaptationException.class)` so audit-status writes commit even when the calling code is about to throw an exception that maps to 4xx. **This is the round-7 lesson applied** — per [agent-prompt-template line 256](../../../../ai-workflow/templates/agent-prompt-template.md): the status write needs to commit before the exception propagates to the controller.

### Anti-deadlock: order of lock acquisition

34. **Always** acquire the `LockService.tryAcquire(LockKey.forRecipe(...))` BEFORE any `recipeWriteApi.saveAdapted*` call (which internally does `SELECT ... FOR UPDATE` on the recipe row). The advisory lock + row lock together cover manual-edit vs pipeline contention per LLD line 883.

### Helper component shapes

35. **`HardConstraintFilterService` injection** — the LLD references this service at line 743; agent confirms it lives in `preference` or `nutrition` module. 01c constructor-injects the existing bean.

### What 01c does NOT update on `AdaptationServiceImpl`

36. **The four public trigger entry methods stay UOE in 01c**. 01c's surface to the world is just `processJob(AdaptationJob)` (private). 01d wires the public methods (each enqueues a job row and routes to `processJob` either sync or async).

37. **`acceptPendingChange` / `rejectPendingChange` / `emitPlannerHint` / `sweepExpiredPendingChanges`** stay UOE/0 — 01d handles pending-change lifecycle; 01f handles `emitPlannerHint` + `sweepExpiredPendingChanges`.

## Database

**None.** All schema landed in 01a; 01c is helper logic only.

## OpenAPI updates

**None.** No HTTP surface ships in 01c.

## Edge-case checklist

- [ ] `CandidateGenerator` emits expected candidates for each strategy: protein swap, portion adjust, method simplification, ingredient remove
- [ ] Zero survivors after `HardConstraintFilterService.filterRecipes` → job transitions to FAILED(`HARD_FILTER`); trace written; no AI call
- [ ] Single-candidate survivor → bypass top-N selection; still scored; auto-skip rule fires if score solo
- [ ] Auto-skip Stage C fires when top score > 2× runner-up; trace `raw_ai_response = null`; `reasoning = "auto-skip: top score 2x"`; outcome applies deterministic winner
- [ ] `AdaptationLlmInvoker` happy path returns `RecipeAdaptationResponse`; chosen-index out of range → `IllegalStateException`
- [ ] `AiService` throws `AiUnavailableException` → wrapped as `AdaptationAiUnavailableException`; job FAILED(`AI_UNAVAILABLE`); `AdaptationJobFailedEvent` published; trace row written (raw_ai_response = null, validation_result = FAILED_HARD)
- [ ] Source = FEEDBACK + lock-acquire fails → `LockTimeoutException` thrown (sync surface — 01d's feedback method catches and surfaces 409)
- [ ] Source = IMPORT + lock-acquire fails → job stays PENDING with `failure_excerpt = "lock-deferred"`; no FAILED transition; status row commits (verified by JdbcTemplate read after method returns)
- [ ] Source = PLAN_TIME + lock-acquire fails → `LockTimeoutException` thrown
- [ ] `ConfidenceFloorGate` confidence = 0.45 → effectiveApprovalPolicy forced to PENDING_CHANGE even when source = SYSTEM catalogue
- [ ] `CharacterPreservationGate` score = 0.55 + classification = VERSION → `AdaptationCharacterBreakException`
- [ ] `CharacterPreservationGate` score = 0.55 + classification = BRANCH + coherence-proxy ≥ 0.7 → continues as BRANCH
- [ ] Hard-constraint re-check on final diff fails → `AdaptationHardConstraintViolationException`
- [ ] `RebaseOrchestrator` first attempt throws `RecipeVersionConflictException` → re-fetches parent version, retries; second attempt succeeds → returns version DTO
- [ ] `RebaseOrchestrator` three attempts all fail → `RebaseExhaustedException`; job FAILED(`REBASE_EXHAUSTED`)
- [ ] `PendingChangeStore.create` on existing `(recipe_id, dimension)` PENDING row → existing flips SUPERSEDED, new row inserted, both written atomically (one tx)
- [ ] `PendingChangeStore.create` concurrent insert race: second writer hits unique constraint, retry path executes, settles to existing PENDING row + new SUPERSEDED row attached to second job
- [ ] `ChangeDimensionResolver` unmatched diff → `GENERAL` + WARN log
- [ ] `AdaptationTraceWriter.write` runs in `REQUIRES_NEW` — IT verifies via JdbcTemplate that the trace row commits even when the outer tx rolls back (simulate by throwing in the orchestrator's outer step after the trace write)
- [ ] DecisionLog row written for every job; `triggered_by` matches source mapping; `parent_decision_id` = job.parentDecisionId for Trigger 4
- [ ] `AdaptationJobCompletedEvent` published exactly once `AFTER_COMMIT` on success; never on failure
- [ ] `AdaptationJobFailedEvent` published exactly once `AFTER_COMMIT` on failure; never on success
- [ ] `transitionJobStatus` with `noRollbackFor = AdaptationException.class` — verify: throw `AdaptationCharacterBreakException` after the status write; JdbcTemplate read confirms `status = FAILED` committed before the exception surfaces
- [ ] Locks: advisory `tryAcquire(LockKey.forRecipe(rid))` then `RecipeWriteApi.saveAdaptedVersion` (which internally does `SELECT ... FOR UPDATE`) — order is correct; reverse order in a second test confirms the same outcome but documents the deadlock risk
- [ ] **`RecipeAdaptationTaskFactory` Noop**: when 01e hasn't shipped, factory throws `AdaptationAiUnavailableException("RecipeAdaptationTask factory not wired — ticket 01e")` and the job FAILS(`AI_UNAVAILABLE`); pipeline reports cleanly
- [ ] When a real `RecipeAdaptationTaskFactory` `@Bean` is provided (via `@TestConfiguration` or 01e), Noop steps aside via `@ConditionalOnMissingBean`
- [ ] `AdaptationCandidateProducedEvent` published once after Stage A/B; before Stage C; `candidateCount` = surviving candidate count; `topCandidateScore` = highest-scored rollup's `tasteAlignmentScore`

## Files this ticket touches

```
NEW   src/main/java/com/example/mealprep/adaptation/ai/AdaptationContext.java                                (typed record)
NEW   src/main/java/com/example/mealprep/adaptation/ai/RecipeAdaptationTaskFactory.java                      (interface; impl in 01e)
NEW   src/main/java/com/example/mealprep/adaptation/ai/RecipeAdaptationResponse.java                         (record; consumed by validation gates here, produced by 01e's task)
NEW   src/main/java/com/example/mealprep/adaptation/ai/internal/NoopRecipeAdaptationTaskFactory.java          (Noop)
NEW   src/main/java/com/example/mealprep/adaptation/ai/internal/NoopRecipeAdaptationTaskFactoryConfiguration.java  (@Configuration with @Bean @ConditionalOnMissingBean; different method name from class)

NEW   src/main/java/com/example/mealprep/adaptation/domain/service/internal/CandidateGenerator.java
NEW   src/main/java/com/example/mealprep/adaptation/domain/service/internal/IngredientSwapStrategy.java
NEW   src/main/java/com/example/mealprep/adaptation/domain/service/internal/PortionAdjustStrategy.java
NEW   src/main/java/com/example/mealprep/adaptation/domain/service/internal/MethodSimplificationStrategy.java
NEW   src/main/java/com/example/mealprep/adaptation/domain/service/internal/IngredientRemoveStrategy.java
NEW   src/main/java/com/example/mealprep/adaptation/domain/service/internal/CandidateGenerationStrategy.java (interface)

NEW   src/main/java/com/example/mealprep/adaptation/domain/service/internal/ScoringEngine.java

NEW   src/main/java/com/example/mealprep/adaptation/domain/service/internal/AdaptationLlmInvoker.java

NEW   src/main/java/com/example/mealprep/adaptation/domain/service/internal/ConfidenceFloorGate.java
NEW   src/main/java/com/example/mealprep/adaptation/domain/service/internal/CharacterPreservationGate.java

NEW   src/main/java/com/example/mealprep/adaptation/domain/service/internal/PendingChangeStore.java
NEW   src/main/java/com/example/mealprep/adaptation/domain/service/internal/ChangeDimensionResolver.java

NEW   src/main/java/com/example/mealprep/adaptation/domain/service/internal/RebaseOrchestrator.java

NEW   src/main/java/com/example/mealprep/adaptation/domain/service/internal/AdaptationTraceWriter.java

MOD   src/main/java/com/example/mealprep/adaptation/domain/service/AdaptationServiceImpl.java                (add private processJob + transitionJobStatus; keep public methods UOE)

NEW   src/test/java/com/example/mealprep/adaptation/CandidateGeneratorTest.java
NEW   src/test/java/com/example/mealprep/adaptation/ScoringEngineTest.java
NEW   src/test/java/com/example/mealprep/adaptation/AdaptationLlmInvokerTest.java                            (mocks AiService; AiUnavailableException → AdaptationAiUnavailableException)
NEW   src/test/java/com/example/mealprep/adaptation/ConfidenceFloorGateTest.java
NEW   src/test/java/com/example/mealprep/adaptation/CharacterPreservationGateTest.java
NEW   src/test/java/com/example/mealprep/adaptation/PendingChangeStoreTest.java                              (supersession; race retry)
NEW   src/test/java/com/example/mealprep/adaptation/ChangeDimensionResolverTest.java
NEW   src/test/java/com/example/mealprep/adaptation/RebaseOrchestratorTest.java
NEW   src/test/java/com/example/mealprep/adaptation/AdaptationTraceWriterIT.java                             (verifies REQUIRES_NEW behaviour via outer-rollback)
NEW   src/test/java/com/example/mealprep/adaptation/PendingChangeSupersessionIT.java                         (concurrent insert race)
NEW   src/test/java/com/example/mealprep/adaptation/AdaptationDecisionLogIT.java                             (decision-log row per processJob iteration)
NEW   src/test/java/com/example/mealprep/adaptation/AdaptationAiUnavailableIT.java                           (TestAiService throws; job FAILED(AI_UNAVAILABLE); event published; no pending change)
NEW   src/test/java/com/example/mealprep/adaptation/AdaptationLockingIT.java                                 (two concurrent processJob on same recipe; second blocks/errors per trigger)
```

**Files this ticket does NOT touch**:

- Public service method bodies for the four trigger entries + lifecycle + planner hint (still UOE) → **01d**
- `RecipeAdaptationTask` class itself (the `AiTask<T>` impl) → **01e** (01c ships the **factory interface** + Noop only)
- `AdaptationContextAssembler` (the helper that takes a job + request and builds `AdaptationContext`) → **01e** (01c calls a placeholder; the actual cross-module reads pull from peer QueryServices the assembler centralises)
- Wait — 01c DOES need to load context. Resolution: 01c ships a **minimal `AdaptationContextAssembler` in 01c** with the loading logic from Step 2. **01e refines** the assembler if needed (e.g. to add the typed AI-prompt context map). Same file path; 01e edits not replaces. **NOTE TO IMPLEMENTING AGENT**: file moves from "01e new" to "01c new + 01e modify".
- Controllers — **01d** / **01f**
- `BatchJobOrchestrator` — **01d**
- `FingerprintRefresher`, `PlannerHintEmitter` — **01f**
- Custom validators — **01d**
- ArchUnit `ModuleBoundaryArchTest` — **01f**
- `application.properties` — already set in 01a

## Dependencies

- **Hard dependency**: `adaptation-pipeline-01b` (merged) — `AdaptationService` + `AdaptationQueryService` interfaces, all DTOs, `AdaptationServiceImpl` skeleton, event records, mappers.
- **Hard dependency**: `core-01a` (merged) — `LockService`, `DecisionLogService`, sealed `MealPrepEvent`.
- **Hard dependency**: `ai-01a` (merged) — `AiService.execute`, `AiUnavailableException`, `AiTask<T>` SPI.
- **Hard dependency**: `recipe-01f` (merged) — `RecipeWriteApi` SPI + commands + `RecipeVersionConflictException`.
- **Hard dependency**: `recipe-01a` + `recipe-01b` (merged) — `RecipeQueryService`, `Catalogue` enum, `RecipeDiffDto`.
- **Hard dependency**: `preference-01b` (merged, assumed) — `PreferenceQueryService.getSoftPreferences`, `HardConstraintFilterService.filterRecipes / checkRecipe`.
- **Hard dependency**: `nutrition-01a` + `nutrition-01d` + `nutrition-01g` (merged) — `NutritionFloorGateService`, `NutritionCalculationService`, `IngredientMappingPipeline`.
- **Soft dependency**: `adaptation-pipeline-01e` (planned) — `RecipeAdaptationTaskFactory` real impl. 01c works with Noop until 01e ships (AI calls fail loudly).
- **Sibling tickets**: `adaptation-pipeline-01d` (planned in same wave) — wires the four public trigger methods. Two tickets share `AdaptationServiceImpl.java` MOD — **scope wall**: 01d edits ONLY the four public trigger entry methods + `acceptPendingChange` + `rejectPendingChange`; 01c edits ONLY `processJob` + `transitionJobStatus` + constructor (helper injections). No overlap.

## Acceptance / DoD

- [ ] `./mvnw -Dspotless.check.skip=true verify` passes
- [ ] CI green
- [ ] All edge-case items above ticked
- [ ] **Per [decisions/0010 §round-7](../../../../ai-workflow/decisions/0010-wave2-round7-transactionaleventlistener-propagation.md)**: `AdaptationTraceWriter` uses `@Transactional(propagation = REQUIRES_NEW)`; the test verifies outer-rollback preserves the trace row
- [ ] **Per [agent-prompt-template line 256](../../../../ai-workflow/templates/agent-prompt-template.md)**: `transitionJobStatus` uses `@Transactional(noRollbackFor = AdaptationException.class)`; the test verifies status writes commit before the exception surfaces
- [ ] `NoopRecipeAdaptationTaskFactoryConfiguration` follows round-5 bug-2 fix pattern (`@Bean` method name differs from class name)
- [ ] `PendingChangeStore.create` race retry handles `DataIntegrityViolationException` cleanly; concurrent IT proves this
- [ ] `RebaseOrchestrator` is config-driven (`maxRebaseAttempts`); changing the config to 1 fails on first conflict
- [ ] `AdaptationLlmInvoker` is the ONLY component that catches `AiUnavailableException`; no other helper retries or hides it
- [ ] All `@Transactional` placements match [LLD §Concurrency table lines 875-883](../../lld/adaptation-pipeline.md): enqueue methods REQUIRED (still UOE in 01c — 01d's concern); worker steps each their own short tx; trace write REQUIRES_NEW; reads readOnly = true
- [ ] No regression on existing tests
- [ ] No pom.xml dependency adds
- [ ] No file outside the adaptation-pipeline module touched

Squash-merge with: `feat(adaptation): 01c — shared worker pipeline (CandidateGenerator + ScoringEngine + LlmInvoker + gates + RebaseOrchestrator + PendingChangeStore + TraceWriter + DecisionLog)`

## What's NOT in scope

- The four public trigger entry methods (`enqueueImportJob`, `enqueueFeedbackJob`, `enqueueDataModelChangeJobs`, `runPlanTimeRefineJob`) → **01d**.
- Pending-change lifecycle controllers (`acceptPendingChange` / `rejectPendingChange` HTTP + service body) → **01d**.
- `BatchJobOrchestrator` for Trigger 3 → **01d**.
- `@Scheduled` poll-fallback for orphan PENDING jobs → **01d**.
- `RecipeAdaptationTask` class + prompt template file → **01e**.
- `NutritionalKnowledgeService` real impl → **01e**.
- `FingerprintRefresher`, `PlannerHintEmitter`, `BatchJobOrchestrator` cron, `pendingExpirySweepCron` registration → **01d** / **01f**.
- Admin controllers + `AdapterRunHistoryController` → **01f**.
- ArchUnit `ModuleBoundaryArchTest` → **01f**.
- Notification module's `AdaptationJobFailedEvent(AI_UNAVAILABLE)` listener — **Notification module's concern**; 01c just publishes the event.
- Anthropic Batches API integration for Trigger 3 — **deferred** per LLD line 794 "v1 runs serially via the standard sync API."
- `RecipeFingerprintTask` (the deferred v2 split-out of fingerprint derivation) — per LLD line 946.
