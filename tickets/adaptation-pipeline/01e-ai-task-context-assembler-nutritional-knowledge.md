# Ticket: adaptation-pipeline — 01e `RecipeAdaptationTask` + `AdaptationContextAssembler` + `NutritionalKnowledgeService` Impl + Prompt Stub

## Summary

Ship the **`AiTask<T>` SPI implementation** that bridges the worker pipeline to `AiService` (LLD §The `RecipeAdaptationTask` (lines 818-868)), the **`AdaptationContextAssembler`** helper that builds the typed context from a job + trigger inputs (LLD line 868), the **v1 `NutritionalKnowledgeServiceImpl`** reading `adaptation_nutritional_knowledge` (LLD §V1 line 573), and the **prompt template stub** at `prompts/adaptation/recipe-adaptation.txt`. Per [lld/adaptation-pipeline.md §The RecipeAdaptationTask (lines 816-868)](../../lld/adaptation-pipeline.md), [§NutritionalKnowledgeService (lines 543-573)](../../lld/adaptation-pipeline.md), [lld/prompts/05-recipe-adaptation.md](../../lld/prompts/05-recipe-adaptation.md).

Ships:

- **`RecipeAdaptationTask`** at `adaptation/ai/RecipeAdaptationTask.java` — implements `com.example.mealprep.ai.spi.AiTask<RecipeAdaptationResponse>` per [LLD lines 821-855](../../lld/adaptation-pipeline.md). Per-mode timeouts via `getTimeoutOverride` (FEEDBACK 8s, PLAN_TIME 10s, IMPORT/DATA_MODEL 12s).
- **`RecipeAdaptationTaskFactory`** real impl at `adaptation/ai/internal/RecipeAdaptationTaskFactoryImpl.java` — replaces 01c's Noop. The `NoopRecipeAdaptationTaskFactory` from 01c stays in place but is **superseded** by the real `@Component` impl via `@ConditionalOnMissingBean` cascade — when this ticket ships, the Noop's `@ConditionalOnMissingBean(RecipeAdaptationTaskFactory.class)` no longer fires.
- **`AdaptationContextAssembler`** at `adaptation/ai/AdaptationContextAssembler.java` — builds `AdaptationContext` (declared in 01c) from `AdaptationJob` + trigger-specific request payload. 01c shipped a minimal version inside the worker; 01e refactors into the dedicated assembler class.
- **`NutritionalKnowledgeServiceImpl`** at `adaptation/domain/service/internal/NutritionalKnowledgeServiceImpl.java` — single impl of `NutritionalKnowledgeService` (interface from 01b). Reads `adaptation_nutritional_knowledge` via `NutritionalKnowledgeRepository.findIntersectingSubjects(kind, keys)`. **Sparse-hit behaviour**: empty bundle without erroring per [LLD line 573](../../lld/adaptation-pipeline.md). The Noop from 01b is **superseded** via `@ConditionalOnMissingBean` cascade.
- **Prompt template stub** at `src/main/resources/prompts/adaptation/recipe-adaptation.txt` — placeholder per [LLD §Out of Scope line 945](../../lld/adaptation-pipeline.md) "Prompt content deferred." Ships with **just the system message frame + the placeholders the renderer expects**, populated to a working-but-mock-friendly form. Content is the responsibility of the prompt engineer; this ticket gets the file on disk so `AiService.execute` can resolve the `PromptRef`.
- **Single-prompt-per-trigger note**: the parent's brief mentioned "4 prompts: one per trigger; drafted in `lld/prompts/`". The LLD ships **one prompt** `prompts/adaptation/recipe-adaptation.txt` that branches per `mode` placeholder (LLD line 832 — `"mode"` is one of `"IMPORT"`, `"FEEDBACK"`, `"DATA_MODEL_CHANGE"`, `"PLAN_TIME_REFINE"`). The drafted file in `lld/prompts/05-recipe-adaptation.md` confirms this: it's a single document covering all four triggers with branching. **01e ships one runtime prompt file** (matching the LLD), not four. **Worth user review** — the parent's "4 prompts" framing may be a misread of the LLD.
- **Block-and-prompt fallback wiring confirmation**: 01c already wraps `AiUnavailableException` → `AdaptationAiUnavailableException`. 01e doesn't add new exception handling; it just confirms (in an IT) that the flow surfaces correctly when the `AiService` throws.

**Decision-log integration confirmation**: 01c already writes the decision-log row per iteration. 01e doesn't add new decision-log writes; the `AdaptationContext.mode` field maps to the `DecisionLog.inputs.mode` payload field for downstream observability.

## Behavioural spec

### `RecipeAdaptationTask`

1. New class `com.example.mealprep.adaptation.ai.RecipeAdaptationTask implements com.example.mealprep.ai.spi.AiTask<RecipeAdaptationResponse>`. Per [LLD lines 821-855](../../lld/adaptation-pipeline.md) verbatim:
   ```java
   public final class RecipeAdaptationTask implements AiTask<RecipeAdaptationResponse> {

     private final AdaptationJob job;
     private final AdaptationContext context;
     private final PromptRef userPromptRef;
     private final AdaptationConfig config;

     public RecipeAdaptationTask(AdaptationJob job, AdaptationContext context, PromptRef ref, AdaptationConfig config) {
       this.job = job; this.context = context; this.userPromptRef = ref; this.config = config;
     }

     @Override public TaskType getTaskType() { return TaskType.RECIPE_ADAPTATION; }
     @Override public String getSystemPrompt() {
       // v1: loaded via PromptTemplateService for the "adaptation/recipe-adaptation-system" name.
       // For 01e the system prompt is a constant string (see step 5); refactored to file load in a follow-up.
       return SYSTEM_PROMPT;
     }
     @Override public PromptRef getUserPromptRef() { return userPromptRef; }    // "adaptation/recipe-adaptation"
     @Override public Map<String, Object> getContext() {
       return Map.of(
         "mode",                 context.mode(),
         "recipe",               context.recipeSummary(),
         "candidates",           context.candidates(),
         "softPreferences",      context.softPreferences(),
         "hardConstraintsHash",  context.hardConstraintsHash(),
         "nutritionTargets",     context.nutritionTargets(),
         "knowledgeBundle",      context.knowledgeBundle(),
         "feedbackText",         orEmpty(context.feedbackText()),
         "ratingDelta",          orEmpty(context.ratingDelta()),
         "directive",            orEmpty(context.directive()),
         "dataModelChange",      orEmpty(context.dataModelChange())
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
   ```

2. **`Map.of()` vs `getContext()` nullable values**: `Map.of(key, value)` throws NPE on null values per JDK semantics. The context's `feedbackText`, `ratingDelta`, `directive`, `dataModelChange` are nullable per LLD line 837-841. **Use `HashMap` + put** OR an `orEmpty(...)` helper substituting `Map.of()` with a non-null sentinel. **01e ships an `orEmpty` helper** that returns `Map.of()` for null Maps, `""` for null Strings, etc. — the prompt template handles empty values cleanly.

3. **`ToolDefinitionFactory.from(Class)` exists in the AI module** (ai-01a, deferred — agent verifies). If the factory doesn't ship the auto-derivation, 01e ships a hand-written `RECIPE_ADAPTATION_TOOL_SCHEMA` JSON-schema constant matching `RecipeAdaptationResponse`'s record shape. **Worth user review.**

### `RecipeAdaptationTaskFactory` real impl

4. New `@Component class RecipeAdaptationTaskFactoryImpl implements RecipeAdaptationTaskFactory`:
   ```java
   @Component
   public class RecipeAdaptationTaskFactoryImpl implements RecipeAdaptationTaskFactory {

     private final AdaptationConfig config;
     private static final PromptRef PROMPT_REF = new PromptRef("adaptation/recipe-adaptation", Optional.empty());

     @Override
     public RecipeAdaptationTask build(AdaptationJob job, AdaptationContext context) {
       return new RecipeAdaptationTask(job, context, PROMPT_REF, config);
     }
   }
   ```

5. **Removes the Noop from 01c** — the `NoopRecipeAdaptationTaskFactoryConfiguration` stays in place; its `@ConditionalOnMissingBean(RecipeAdaptationTaskFactory.class)` no longer fires once the real `@Component` registers. **No file deletion** — leaves the safety net for parallel-development of related modules.

### System prompt constant

6. Module-level constant `RecipeAdaptationTask.SYSTEM_PROMPT`. v1 content is a placeholder (per LLD line 945 "Prompt content deferred"); ships as:
   ```
   You are MealPrep's recipe-adaptation assistant. Given a recipe and a list of pre-validated candidate
   adaptations, choose the best candidate that satisfies the trigger goal while preserving the recipe's
   culinary character. Output a single structured response matching the RecipeAdaptationResponse tool
   schema. Reasoning must be one or two sentences. Confidence is a 0..1 scalar. characterPreservationScore
   is a 0..1 scalar measuring how well the adapted recipe preserves the original's identity. When
   classification=NO_CHANGE, set chosenCandidateIndex=-1 and explain why no candidate satisfies the goal.
   ```
   **Worth user review** — this string is intentionally minimal; the prompt engineer ships the real version.

### `AdaptationContextAssembler`

7. New `@Component class AdaptationContextAssembler` at `adaptation/ai/AdaptationContextAssembler.java`:
   ```java
   @Component
   public class AdaptationContextAssembler {

     private final RecipeQueryService recipeQueryService;
     private final PreferenceQueryService preferenceQueryService;
     private final NutritionFloorGateService nutritionFloorGateService;
     private final NutritionalKnowledgeService nutritionalKnowledgeService;

     @Transactional(readOnly = true)
     public AdaptationContext assemble(AdaptationJob job,
                                        List<AdaptationCandidateDto> candidates,
                                        TriggerInputs triggerInputs) {
       Recipe recipe = recipeQueryService.findRecipe(job.getRecipeId());
       RecipeVersion currentVersion = recipeQueryService.findCurrentVersion(recipe.getCurrentVersionId());
       RecipeSummaryView recipeSummary = buildRecipeSummary(recipe, currentVersion);

       SoftPreferenceSummary softPrefs = preferenceQueryService.getSoftPreferences(job.getUserId());
       NutritionTargetsSummary targets = nutritionFloorGateService.getTargetsSummary(job.getUserId());
       NutritionalKnowledgeBundleDto knowledge = nutritionalKnowledgeService.lookupForRecipe(
           currentVersion.getId(), extractMappingKeys(currentVersion));

       return new AdaptationContext(
           mapMode(job.getSource()),
           recipeSummary,
           candidates,
           softPrefs,
           hashHardConstraints(job.getUserId()),                          // for prompt-cache stability
           targets,
           knowledge,
           triggerInputs.feedbackText(),
           triggerInputs.ratingDelta(),
           triggerInputs.directive(),
           triggerInputs.dataModelChange());
     }
   }
   ```

8. **`TriggerInputs`** is a typed sum-type carrier (record or sealed interface) — agent uses sealed interface with the four trigger variants per the LLD's trigger-specific payload shapes from [lld/prompts/05-recipe-adaptation.md lines 70-93](../../lld/prompts/05-recipe-adaptation.md):
   ```java
   public sealed interface TriggerInputs permits ImportTriggerInputs, FeedbackTriggerInputs,
                                                  DataModelTriggerInputs, PlanTimeTriggerInputs {
     @Nullable default String feedbackText() { return null; }
     @Nullable default FeedbackJobRequest.RatingDeltaDto ratingDelta() { return null; }
     @Nullable default PlanTimeRefineDirectiveRequest.RefineDirectiveDto directive() { return null; }
     @Nullable default JsonNode dataModelChange() { return null; }
   }
   public record ImportTriggerInputs(@Nullable JsonNode rawImportContext) implements TriggerInputs {}
   public record FeedbackTriggerInputs(String feedbackText, FeedbackJobRequest.RatingDeltaDto ratingDelta)
       implements TriggerInputs {
     @Override public String feedbackText() { return feedbackText; }
     @Override public FeedbackJobRequest.RatingDeltaDto ratingDelta() { return ratingDelta; }
   }
   public record DataModelTriggerInputs(DataModelChangeType changeType, JsonNode changeSummary) implements TriggerInputs {
     @Override public JsonNode dataModelChange() { return changeSummary; }
   }
   public record PlanTimeTriggerInputs(PlanTimeRefineDirectiveRequest.RefineDirectiveDto directive,
                                        PlanConstraintsSnapshotDto constraints) implements TriggerInputs {
     @Override public PlanTimeRefineDirectiveRequest.RefineDirectiveDto directive() { return directive; }
   }
   ```

9. **01c's worker pipeline (`processJob`) refactors to use the assembler**:
   - 01c shipped the context-loading inline. 01e moves it into `AdaptationContextAssembler.assemble`. 01c's `processJob` becomes:
     ```java
     // ... after Stage A/B produces candidates ...
     TriggerInputs inputs = triggerInputsFromJob(job);                 // parses job.getInputs() JSONB by source
     AdaptationContext context = contextAssembler.assemble(job, candidates, inputs);
     // ... pass context to AdaptationLlmInvoker ...
     ```
   - This is a refactor — the worker logic doesn't change, just gets cleaner.

### `NutritionalKnowledgeServiceImpl`

10. New `@Component class NutritionalKnowledgeServiceImpl implements NutritionalKnowledgeService` at `adaptation/domain/service/internal/`:
    ```java
    @Component
    @Transactional(readOnly = true)
    public class NutritionalKnowledgeServiceImpl implements NutritionalKnowledgeService {

      private final NutritionalKnowledgeRepository repo;
      private final NutritionalKnowledgeMapper mapper;                  // new — see step 14

      @Override
      public List<NutritionalPairingDto> lookupPairings(List<String> keys) {
        if (keys.isEmpty()) return List.of();
        return repo.findIntersectingSubjects(KnowledgeKind.PAIRING.name(), keys.toArray(String[]::new)).stream()
            .map(mapper::toPairingDto).toList();
      }
      // similar for lookupMethodEffects, lookupPrepRequirements, lookupConflicts

      @Override
      public NutritionalKnowledgeBundleDto lookupForRecipe(UUID versionId, List<String> keys) {
        // versionId currently unused — kept for future scoping (e.g. "facts learned from this specific version")
        return new NutritionalKnowledgeBundleDto(
            lookupPairings(keys),
            keys.isEmpty() ? List.of() : keys.stream().flatMap(k -> lookupMethodEffects(k, defaultMethods()).stream()).toList(),
            lookupPrepRequirements(keys),
            lookupConflicts(keys));
      }
    }
    ```

11. **Empty results on sparse hits** per LLD line 573 "sparse hits return an empty bundle without erroring — the LLM still produces useful output without curated facts." No WARN log in v1 — that's the alternative considered and rejected.

12. **`versionId` parameter unused** in v1 per LLD line 553 — `lookupForRecipe(UUID versionId, List<String> keys)` carries it for future use (e.g. version-specific facts) but v1 ignores it. **Document on the impl Javadoc.**

13. **`@ConditionalOnMissingBean` cascade**: the Noop from 01b stays in place; once this real `@Component` registers, the Noop's condition fails. **No file deletion** — the Noop is the parallel-development safety net for any module that compiles before this lands.

### Mapper

14. New `@Mapper(componentModel = "spring") interface NutritionalKnowledgeMapper` at `adaptation/api/mapper/`. Maps `NutritionalKnowledgeEntry` (entity) → the four DTOs (`NutritionalPairingDto`, `MethodBioavailabilityDto`, `PrepRequirementDto`, `AbsorptionConflictDto`). Custom `@Named` qualifier `toSubjectKeysList` converts `String[]` to `List<String>`.

### Prompt template stub

15. New file `src/main/resources/prompts/adaptation/recipe-adaptation.txt`. **Working stub content** — sufficient for the renderer to load successfully and produce a valid call, but the actual recipe-adaptation reasoning awaits the prompt engineer:

    ```
    # Recipe Adaptation — Mode: {{mode}}

    ## Recipe
    {{recipe}}

    ## Candidates
    {{candidates}}

    ## Soft preferences
    {{softPreferences}}

    ## Nutrition targets
    {{nutritionTargets}}

    ## Curated nutritional knowledge
    {{knowledgeBundle}}

    ## Trigger-specific context

    {{#if feedbackText}}Feedback: {{feedbackText}}{{/if}}
    {{#if ratingDelta}}Rating delta: {{ratingDelta}}{{/if}}
    {{#if directive}}Refine directive: {{directive}}{{/if}}
    {{#if dataModelChange}}Data-model change: {{dataModelChange}}{{/if}}

    ## Hard constraints hash (for prompt-cache stability)
    {{hardConstraintsHash}}

    ## Instructions

    Select the best candidate from the list above that satisfies the trigger goal while preserving the
    recipe's culinary character. Respond with a structured RecipeAdaptationResponse via the tool. When
    no candidate adequately satisfies the goal, set classification = NO_CHANGE and chosenCandidateIndex = -1.
    ```

16. **Handlebars vs straight Mustache** — depends on the `PromptTemplateRenderer` from ai-01a. v1 of ai.md (line 107) says `handlebars = false`; agent confirms and falls back to plain `{{key}}` substitution. **Worth user review.**

17. **Path discovery**: the template is loaded by `PromptTemplateService` from `src/main/resources/prompts/adaptation/recipe-adaptation.txt` (per [lld/ai.md line 116](../../lld/ai.md)). 01e ensures the file exists; no DB migration needed (the prompt module's `prompt_template` table auto-populates on first load).

### Block-and-prompt fallback confirmation

18. **No new code in 01e for the block-and-prompt fallback.** 01c already wraps `AiUnavailableException` → `AdaptationAiUnavailableException` in `AdaptationLlmInvoker`, and `processJob` already transitions the job to FAILED(`AI_UNAVAILABLE`) + publishes `AdaptationJobFailedEvent`. 01e adds **one integration test** verifying the end-to-end behaviour with the real task in play (vs. 01c's Noop-only test):
    - `TestAiService` (from ai-01a) throws `AiUnavailableException`.
    - Submit a feedback job through `enqueueFeedbackJob`.
    - Expect `AdaptationAiUnavailableException` (503).
    - Verify job row: `status = FAILED`, `failure_reason = 'AI_UNAVAILABLE'`.
    - Verify `AdaptationJobFailedEvent` published with `reason = AI_UNAVAILABLE`.
    - Verify NO `PendingChange` row created.

## Database

**Nutritional knowledge seed rows** — out of scope per LLD line 248. The `R__adaptation_seed_nutritional_knowledge_v1.sql` file is created in 01a as an empty stub. 01e **does not populate** the table. The prompt-engineering work (separate ticket / sibling project) seeds the rows. The empty bundle path works cleanly.

## OpenAPI updates

**None.** No new HTTP surface in 01e.

## Edge-case checklist

- [ ] `RecipeAdaptationTask.getTaskType()` returns `TaskType.RECIPE_ADAPTATION`
- [ ] `RecipeAdaptationTask.getTimeoutOverride()` returns 10000ms for `PLAN_TIME`, 8000ms for `FEEDBACK`, 12000ms (importTimeoutMs default) for `IMPORT` and `DATA_MODEL_CHANGE`
- [ ] `RecipeAdaptationTask.getContext()` includes all 12 keys; nullable values produce empty Map / empty String — never NPE from `Map.of`
- [ ] `RecipeAdaptationTaskFactoryImpl` `@Component` registers; the Noop from 01c's config no longer fires (`@ConditionalOnMissingBean` evaluates to false)
- [ ] **Round-5 bug-2 holdover check**: the Noop config from 01c still has its `@Bean` method name different from the class name; the Noop bean factory method name doesn't collide with the real `@Component`'s class name
- [ ] `AdaptationContextAssembler.assemble` loads recipe + soft prefs + nutrition targets + knowledge bundle for a USER-catalogue job; all fields populated
- [ ] `AdaptationContextAssembler.assemble` for a job with no `feedbackText` (e.g. IMPORT) populates the trigger-specific fields with null; the `getContext()` map handles cleanly via `orEmpty`
- [ ] `TriggerInputs` sealed-interface variants serialise + deserialise cleanly through the existing Jackson `ObjectMapper`
- [ ] `NutritionalKnowledgeServiceImpl.lookupPairings` with empty keys → returns empty list (no DB call)
- [ ] `NutritionalKnowledgeServiceImpl.lookupPairings` with 3 keys, 0 rows in DB → empty list, no error
- [ ] `NutritionalKnowledgeServiceImpl.lookupPairings` with 3 keys, 2 matching rows → 2 DTOs returned via the native GIN-intersect query
- [ ] `NutritionalKnowledgeServiceImpl.lookupForRecipe` composes the four lookups into a single bundle; empty bundle when no facts curated
- [ ] **Noop bean cascade**: starting the application with the Noop config from 01b in place + this real `@Component` → real impl wins; Noop's `@ConditionalOnMissingBean` returns false
- [ ] **Prompt template loading**: `PromptTemplateService.loadByRef("adaptation/recipe-adaptation")` returns the stub content; the file exists at `src/main/resources/prompts/adaptation/recipe-adaptation.txt`
- [ ] **Prompt template DB row**: first load inserts a row into `prompt_template(name, content_hash, body, ...)`; second load is idempotent (same content_hash)
- [ ] `RecipeAdaptationTask.getToolSchema()` returns a `ToolDefinition` matching `RecipeAdaptationResponse`'s record shape (8 fields: `chosenCandidateIndex`, `classification`, `reasoning`, `nutritionalNotes`, `confidence`, `characterPreservationScore`, `refinedDiff` nullable, `plannerHints` list)
- [ ] **Block-and-prompt IT**: TestAiService throws `AiUnavailableException` → feedback job FAILS(AI_UNAVAILABLE); `AdaptationJobFailedEvent` published; no PendingChange row; **`AdaptationAiUnavailableException` surfaces as 503**
- [ ] `NutritionalKnowledgeMapper` round-trips: persist a `NutritionalKnowledgeEntry` with `knowledgeKind = PAIRING`, map to `NutritionalPairingDto`, all fields preserved including `subjectKeys` array → `List<String>`
- [ ] **No new schema changes** — `ddl-auto=validate` still passes
- [ ] **01c's worker pipeline refactor**: the `processJob` orchestration now calls `AdaptationContextAssembler.assemble` instead of inline loading; existing tests from 01c still pass

## Files this ticket touches

```
NEW   src/main/java/com/example/mealprep/adaptation/ai/RecipeAdaptationTask.java
NEW   src/main/java/com/example/mealprep/adaptation/ai/internal/RecipeAdaptationTaskFactoryImpl.java
MOD   src/main/java/com/example/mealprep/adaptation/ai/AdaptationContextAssembler.java                    (refactored from 01c's inline shape; 01c added it as a minimal in-worker helper, 01e extracts and tightens)
NEW   src/main/java/com/example/mealprep/adaptation/ai/TriggerInputs.java                                  (sealed interface + 4 variants)

NEW   src/main/java/com/example/mealprep/adaptation/domain/service/internal/NutritionalKnowledgeServiceImpl.java

NEW   src/main/java/com/example/mealprep/adaptation/api/mapper/NutritionalKnowledgeMapper.java

NEW   src/main/resources/prompts/adaptation/recipe-adaptation.txt                                          (working stub; prompt engineer ships the real content later)

MOD   src/main/java/com/example/mealprep/adaptation/domain/service/AdaptationServiceImpl.java              (processJob now calls AdaptationContextAssembler.assemble instead of inline loading; surface unchanged)

NEW   src/test/java/com/example/mealprep/adaptation/RecipeAdaptationTaskTest.java                          (taskType, timeouts, context keys per source, tool schema)
NEW   src/test/java/com/example/mealprep/adaptation/RecipeAdaptationTaskFactoryImplTest.java               (real factory replaces Noop)
NEW   src/test/java/com/example/mealprep/adaptation/AdaptationContextAssemblerTest.java                    (each TriggerInputs variant populates the right context slice)
NEW   src/test/java/com/example/mealprep/adaptation/NutritionalKnowledgeServiceImplTest.java               (empty-on-no-match; GIN intersect via repo)
NEW   src/test/java/com/example/mealprep/adaptation/AdaptationAiUnavailableE2EIT.java                       (TestAiService throws; feedback flow 503; row FAILED; event published; no pending change)
NEW   src/test/java/com/example/mealprep/adaptation/AdaptationPromptTemplateLoadIT.java                     (PromptTemplateService loads the file; row inserted in prompt_template)
```

**Files this ticket does NOT touch**:

- 01a's migrations (none added)
- 01b's interfaces (the `NutritionalKnowledgeService` interface stays — only the impl ships here)
- 01b's Noop config (stays in place; superseded but not removed)
- 01c's `processJob` orchestration logic beyond the assembler refactor
- 01d's controllers + listeners
- `FingerprintRefresher`, `PlannerHintEmitter` → **01f**
- ArchUnit `ModuleBoundaryArchTest` → **01f**

## Dependencies

- **Hard dependency**: `adaptation-pipeline-01c` (merged) — `AdaptationContext`, `RecipeAdaptationResponse`, `RecipeAdaptationTaskFactory` interface + Noop, `AdaptationLlmInvoker`, `processJob` orchestration.
- **Hard dependency**: `adaptation-pipeline-01b` (merged) — `NutritionalKnowledgeService` interface + Noop, `NutritionalKnowledgeBundleDto` + the four payload DTOs.
- **Hard dependency**: `adaptation-pipeline-01a` (merged) — `NutritionalKnowledgeEntry` entity + `NutritionalKnowledgeRepository`.
- **Hard dependency**: `ai-01a` (merged) — `AiTask<T>`, `TaskType.RECIPE_ADAPTATION`, `PromptRef`, `ToolDefinition`, `PromptTemplateService`, `TestAiService` (for IT), `ToolDefinitionFactory.from(Class)` (agent verifies presence; ships hand-written constant if missing).
- **Hard dependency**: `recipe-01a` + `recipe-01b` (merged) — `RecipeQueryService`, `RecipeVersion`, ingredient mapping key extraction.
- **Hard dependency**: `preference-01b` (merged) — `PreferenceQueryService.getSoftPreferences`, `hashHardConstraints` (agent finds the actual location of the hash helper — likely a shared utility somewhere; if not, ships locally as `HardConstraintsHasher`).
- **Hard dependency**: `nutrition-01a` + `nutrition-01g` (merged) — `NutritionFloorGateService.getTargetsSummary` (or equivalent).
- **Sibling tickets**: `adaptation-pipeline-01d` shares some files conceptually but the scope walls are clean — 01d doesn't touch `ai/` or the assembler.

## Acceptance / DoD

- [ ] `./mvnw -Dspotless.check.skip=true verify` passes
- [ ] CI green
- [ ] All edge-case items above ticked
- [ ] `RecipeAdaptationTaskFactoryImpl` `@Component` annotation present; the Noop from 01c is overridden (verify via `ApplicationContext.getBeansOfType(RecipeAdaptationTaskFactory.class)` returning exactly one bean named "recipeAdaptationTaskFactoryImpl")
- [ ] `NutritionalKnowledgeServiceImpl` `@Component` annotation present; same override check for `NutritionalKnowledgeService.class`
- [ ] Prompt template file exists; loads via `PromptTemplateService`; round-trips cleanly through the prompt_template DB row
- [ ] Block-and-prompt fallback proven end-to-end via IT
- [ ] No regression on existing tests
- [ ] No pom.xml dependency adds
- [ ] No file outside the adaptation-pipeline module touched (incl. NO change to ai/recipe/preference/nutrition/feedback/planner src/)

Squash-merge with: `feat(adaptation): 01e — RecipeAdaptationTask + AdaptationContextAssembler + NutritionalKnowledgeServiceImpl + prompt template stub + block-and-prompt IT`

## What's NOT in scope

- **The actual prompt content** for `recipe-adaptation.txt` — the stub ships; the prompt engineer's PR refines.
- **Curated nutritional-knowledge rows** in `adaptation_nutritional_knowledge` — schema ships in 01a; rows are deferred to prompt-engineering work.
- **`RecipeFingerprintTask`** (v2 split of fingerprint derivation) — deferred per LLD line 946.
- **Anthropic Batches API** integration → deferred per LLD line 794.
- **Per-user prompt variants**, **streaming**, **multi-turn tool-use** → per LLD line 954.
- `emitPlannerHint` impl → **01f**
- `sweepExpiredPendingChanges` impl → **01f**
- Admin controllers (`AdaptationAdminController`, `AdapterRunHistoryController`) → **01f**
- `FingerprintRefresher`, `PlannerHintEmitter` → **01f**
- ArchUnit `ModuleBoundaryArchTest` → **01f**
