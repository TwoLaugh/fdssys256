# Ticket: planner — 01g Stage C Invoker (LLM pick-of-N via prompt #8) + AiTask wiring + deterministic fallback

## Summary

Wire **Stage C**: given N candidate plans + their rollups (from 01d/01e/01f), invoke `AiService` with `StageCPickTask` (prompt #8) to ask the LLM for `chosenIndex + reasoning`. Ships `StageCInvoker` interface + impl, `StageCPickTask` (an `AiTask<StageCPickResponse>`), the `StageCPickResponse` + `StageCResult` records, `AugmentationSource` use, `CandidatePlanRollupDto` (cross-module — already exists in nutrition-01g; **01g introduces a planner-local DTO carrying both the candidate id and the per-day rollups**), and the deterministic top-scored fallback for `AiUnavailable` / `TransientAiFailureException`. Per `lld/planner.md` §`StageCInvoker` lines 821-867.

The **prompt body** lives at `src/main/resources/prompts/planner/stage-c-pick.txt` per `lld/ai.md` §Prompt loading. **01g ships a minimal pilot-quality prompt** (~10 lines) that:
- Tells the model the task is "pick the best plan from the candidates given the household's constraints"
- Provides candidate rollups + constraints summary as context
- Requests a JSON tool-call returning `{chosenIndex: int, reasoning: string}`

**Worth user review — prompt content gap**: the LLD explicitly defers prompt body refinement to a "separate prompt-engineering exercise with its own eval set." **01g's pilot prompt is intentionally minimal**, sized to validate the wiring. The user/LLM-eng workstream will refine the prompt body before launch — 01g's tests assert the **wiring** (request shape, response parsing, fallback path), not prompt-quality metrics.

**Defers**:
- Phase 2 augmenter → **planner-01h**
- Composer wiring → **planner-01j**
- Decision-log integration of Stage C reasoning → **planner-01l**
- Prompt-quality eval set → out of scope; lives in a separate prompt-engineering ticket post-pilot
- Stage D refine-directives (Phase 2's emitted directives) → **planner-01h**

## LLD divergence — `CandidatePlanRollupDto` already exists in nutrition module

LLD §`StageCInvoker` passes a `List<CandidatePlanRollupDto>` to `StageCPickTask.getContext()`. `CandidatePlanRollupDto` already exists in `nutrition.api.dto` (shipped by nutrition-01g for the floor-gate evaluate endpoint). **01g reuses the nutrition DTO** — same shape (startDate, endDate, perDay). **Worth user review** — alternative is a planner-local `PlannerCandidateRollupDto`. Rejected because the shape is identical AND the LLM context payload is the same data; importing a nutrition DTO into the planner is a one-way cross-module DTO consumption, which is allowed per the style guide ("cross-module data transfer is a DTO; entities never cross").

**However**, the Stage C prompt needs the **candidate id** (so the LLM can pick by index) + the rollup. **01g introduces a planner-local pair record** `IndexedCandidateRollup(int index, UUID candidateId, CandidatePlanRollupDto rollup)` to carry both. The list of `IndexedCandidateRollup` goes into the prompt's `candidates` context key.

## LLD divergence — `AugmentationSource.LLM` recorded on Stage C result

LLD line 828 declares `StageCResult(int chosenIndex, String reasoning, AugmentationSource source)`. The `source` field is **always `LLM`** on a successful Stage C invocation (because the LLM picked). On fallback (AiUnavailable / TransientAiFailure), the deterministic top-scored candidate is selected; **01g still records `source = LLM`** because the field tracks "Stage C selection origin" semantically, and there's no "deterministic" enum value. **Worth user review** — alternative is to add an `AugmentationSource.DETERMINISTIC` value. Rejected because the `aiAugmented` boolean on `Plan` already tracks the fallback ("`aiAugmented = false` when Stage C/Phase 2 fell back to deterministic" per LLD §Entities row `Plan.ai_augmented`). The two flags (`augmentationSource` per-recipe + `aiAugmented` per-plan) serve different concerns. 01g keeps `AugmentationSource.LLM` always for Stage C.

## Behavioural spec

### `StageCInvoker` interface + impl

1. New interface under `domain/service/internal/stagec/`:
   ```java
   public interface StageCInvoker {
     StageCResult pickOne(List<CandidatePlan> candidates,
                          List<CandidatePlanRollupDto> rollups,
                          PlanCompositionContext context,
                          UUID traceId);
   }
   ```
2. `@Component` `StageCInvokerImpl` under same package, package-private. Dependencies via constructor: `AiService`, `PlannerProperties`.
3. **`@Transactional` NOT applied** — AI calls must be outside any transaction per [style-guide §AI Service](../../lld/style-guide.md#ai-service--graceful-degradation) (HLD Tier 1 decision: AI calls in-transaction = no). Caller (01j composer) ensures the surrounding flow has no open tx at this point.
4. **Method body**:
   - Build `IndexedCandidateRollup` list pairing each candidate with its rollup.
   - Build `constraintsSummary` — short string describing the household's hard constraints (allergens, dietary identity, equipment lacks) + nutrition direction + budget. **01g ships a minimal one-line summary** ("Household of N people; primary diet: X; allergens: Y; weekly budget: £Z"); refinement is part of the prompt-engineering follow-up.
   - Resolve `primaryUserId` from `context.householdSettings().primaryUserId()` (verify field; fallback to first member).
   - Construct `StageCPickTask(indexed, constraintsSummary, context.householdSettings().householdSize(), context.weekStartDate(), TriggerKind.USER_INITIATED, primaryUserId, traceId)`.
   - Call `aiService.execute(task)`. Returns `StageCPickResponse` on success.
   - **Validate**: `response.chosenIndex() ∈ [0, candidates.size() - 1]`. Out-of-range → log WARN, fall back to deterministic (index 0; rely on candidates list already sorted by score DESC from 01d's beam search).
   - Return `StageCResult(response.chosenIndex(), response.reasoning(), AugmentationSource.LLM)`.
5. **Fallback path** — three triggers:
   - `AiService.execute` throws `AiUnavailable` (cost cap, key missing): log INFO, return `StageCResult(0, "AI ranking unavailable; deterministic top-scored candidate selected.", AugmentationSource.LLM)`. **The plan's `aiAugmented = false` flag is set by the composer (01j)** based on whether Stage C and/or Phase 2 fell back; 01g signals fallback via the reasoning text. **Worth user review** — alternative is to return a separate `fallback: boolean` flag on `StageCResult`. **01g adds the boolean** to make the composer's life easier:
     ```java
     public record StageCResult(int chosenIndex, String reasoning, AugmentationSource source, boolean fallback) {}
     ```
     **LLD divergence**: the LLD's `StageCResult` had no `fallback` field. 01g adds it for explicit composer wiring.
   - `AiService.execute` throws `TransientAiFailureException`: same fallback, log WARN with the failure cause.
   - Response validation fails (out-of-range index): same fallback, log WARN.
6. **Timeout**: `StageCPickTask.getTimeoutOverride() = Optional.of(properties.stageCTimeout())` — default `PT20S` (per the LLD-locked default; **01g extends `PlannerProperties` to add `stageCTimeout`**).

### `StageCPickTask` — AiTask impl

7. Final class under `domain/service/internal/stagec/`. Constructor params: indexed rollups, constraints summary, household size, week start, trigger, primary user id, trace id. Per LLD lines 834-854:
   ```java
   final class StageCPickTask implements AiTask<StageCPickResponse> {

     private final List<IndexedCandidateRollup> indexed;
     private final String constraintsSummary;
     private final int householdSize;
     private final LocalDate weekStartDate;
     private final TriggerKind trigger;
     private final UUID primaryUserId;
     private final UUID traceId;
     private final Duration timeoutOverride;

     @Override public TaskType getTaskType() { return TaskType.PLAN_COMPOSITION; }
     @Override public String getSystemPrompt() { /* deferred — see below */ }
     @Override public PromptRef getUserPromptRef() {
       return new PromptRef("planner/stage-c-pick", Optional.empty());
     }
     @Override public Map<String, Object> getContext() {
       return Map.of(
           "candidates", indexed,
           "constraints_summary", constraintsSummary,
           "household_size", householdSize,
           "week_start", weekStartDate.toString(),
           "trigger", trigger.name());
     }
     @Override public ToolDefinition getToolSchema() { /* see below */ }
     @Override public Class<StageCPickResponse> getResponseType() { return StageCPickResponse.class; }
     @Override public UUID getUserId() { return primaryUserId; }
     @Override public UUID getTraceId() { return traceId; }
     @Override public Optional<Duration> getTimeoutOverride() { return Optional.of(timeoutOverride); }
   }
   ```
8. **`getSystemPrompt`**: **01g returns a minimal pilot system prompt**: `"You are a meal planning assistant. Your task is to pick the best weekly meal plan from N candidates given the household's constraints and weekly rollups. Use the tool to return the index of the chosen plan and a brief one-paragraph reasoning."` Stored as a constant inside the class. **Worth user review** — alternative is to load via `PromptRef` like the user prompt. 01g uses the inline constant for the system message since `lld/ai.md`'s `AiTask` contract pairs an inline system prompt + a `PromptRef` user prompt; pilot prompt body is fine for a static system message.
9. **`getToolSchema`**: derived from `StageCPickResponse` via `jsonschema-generator` (project-wide pattern). The schema returned to the LLM forces: `{ chosenIndex: integer, reasoning: string }`, both required.
10. `StageCPickResponse` record per LLD line 856:
    ```java
    public record StageCPickResponse(
        int chosenIndex,                                   // 0..N-1
        String reasoning                                   // free-text, recorded in decision log
    ) {}
    ```
    `@JsonProperty` annotations not needed — record component names match the JSON keys.

### Prompt template file

11. New file `src/main/resources/prompts/planner/stage-c-pick.txt`. Minimal pilot body (~25 lines):

    ```
    You will receive N candidate weekly meal plans for a household, each summarised as a per-day rollup.
    Your job is to pick the single best plan and briefly explain why.

    Household constraints summary:
    {{ constraints_summary }}

    Household size: {{ household_size }}
    Week starting: {{ week_start }}
    Generation trigger: {{ trigger }}

    Candidates (indexed 0..{{ candidates | length - 1 }}):
    {% for c in candidates %}
    --- Candidate {{ c.index }} ---
    Days:
    {% for d in c.rollup.perDay %}
      {{ d.date }}: {{ d.calories }} kcal, protein {{ d.proteinG }}g, fat {{ d.fatG }}g, carbs {{ d.carbsG }}g, fibre {{ d.fibreG }}g
    {% endfor %}
    {% endfor %}

    Pick the candidate that best balances:
    1. Nutrition convergence to the household's targets.
    2. Variety across days.
    3. Constraint satisfaction (no hard-floor breaches).

    Return your choice via the tool call. Keep the reasoning under 3 sentences.
    ```

    The template uses Jinja-style `{{ }}` placeholders matching the `getContext()` map keys. **Project's prompt-loading layer handles the rendering**; 01g doesn't ship a template engine, only the prompt body file.

12. **Verify the project's prompt loader handles this template format** (verify against `ai-01`'s prompt loader implementation). If it uses Mustache or a different format, **adjust the placeholder syntax** to match.

### `PlannerProperties` extension

13. **Append** `stageCTimeout` (default `PT20S`) and `iterationBudget` (default 3 — used by Stage D in 01h, but the property key lives at the planner level and is referenced by `StageCInvokerImpl` later) to `PlannerProperties`:

    ```java
    @ConfigurationProperties(prefix = "mealprep.planner")
    @Validated
    public record PlannerProperties(
        /* ...existing fields... */
        @NotNull Duration stageCTimeout,         // default PT20S
        @Min(1) int iterationBudget              // default 3 — used by 01h/01j
    ) {}
    ```

14. `application.yml`:
    ```yaml
    mealprep:
      planner:
        stage-c-timeout: PT20S
        iteration-budget: 3
    ```

### Cross-module SPI — `AiService` injection

15. `AiService` is the public interface from `ai` module (wave-1, merged). Inject by interface. **Do NOT depend on the `AiServiceImpl` class.**
16. Constructor: `private final AiService aiService;`. `@RequiredArgsConstructor`.
17. **Verify `TaskType.PLAN_COMPOSITION` exists** as an enum value in `ai-01a`'s `TaskType`. If not, **the ai module needs an enum addition** — **out of scope for planner-01g** (cross-module change). Document this as a hard dependency: if `PLAN_COMPOSITION` is missing, the planner-01g implementer reports back and a sibling `ai-` chore ticket adds it. **01g's IT skips the AI call (uses TestAiService) so this can be deferred for unit tests; the `TaskType` reference still must compile.**

## Database

**Zero migrations.** Pure code + a properties addition + a prompt template file.

## OpenAPI updates

**No new endpoints.** `StageCInvoker` is in-process only.

## Verbatim shape snippets

### `StageCInvokerImpl`

```java
@Component
@RequiredArgsConstructor
@Slf4j
class StageCInvokerImpl implements StageCInvoker {

  private final AiService aiService;
  private final PlannerProperties properties;

  @Override
  public StageCResult pickOne(List<CandidatePlan> candidates,
                              List<CandidatePlanRollupDto> rollups,
                              PlanCompositionContext ctx,
                              UUID traceId) {
    if (candidates.isEmpty()) {
      log.warn("Stage C invoked with empty candidates list — returning deterministic empty fallback");
      return new StageCResult(0, "no candidates available", AugmentationSource.LLM, true);
    }
    if (candidates.size() != rollups.size()) {
      throw new IllegalArgumentException("candidates and rollups must be same size");
    }

    List<IndexedCandidateRollup> indexed = IntStream.range(0, candidates.size())
        .mapToObj(i -> new IndexedCandidateRollup(i, candidates.get(i).candidateId(), rollups.get(i)))
        .toList();

    String summary = buildConstraintsSummary(ctx);
    UUID primaryUserId = resolvePrimaryUserId(ctx);

    StageCPickTask task = new StageCPickTask(
        indexed, summary, ctx.householdSettings().householdSize(),
        ctx.weekStartDate(), TriggerKind.USER_INITIATED, primaryUserId, traceId,
        properties.stageCTimeout());

    try {
      StageCPickResponse response = aiService.execute(task);
      if (response.chosenIndex() < 0 || response.chosenIndex() >= candidates.size()) {
        log.warn("Stage C returned out-of-range chosenIndex {} for N={}; falling back to deterministic",
            response.chosenIndex(), candidates.size());
        return deterministicFallback(candidates);
      }
      return new StageCResult(response.chosenIndex(), response.reasoning(),
                              AugmentationSource.LLM, false);
    } catch (AiUnavailable e) {
      log.info("Stage C: AI unavailable ({}); falling back to deterministic", e.getMessage());
      return deterministicFallback(candidates);
    } catch (TransientAiFailureException e) {
      log.warn("Stage C: transient AI failure; falling back to deterministic", e);
      return deterministicFallback(candidates);
    }
  }

  private StageCResult deterministicFallback(List<CandidatePlan> candidates) {
    // candidates are pre-sorted DESC by score in 01d's beam search output
    return new StageCResult(0,
        "AI ranking unavailable; deterministic top-scored candidate selected.",
        AugmentationSource.LLM, true);
  }
}
```

### `IndexedCandidateRollup`

```java
public record IndexedCandidateRollup(
    int index,
    UUID candidateId,
    CandidatePlanRollupDto rollup) {}
```

### `StageCResult` (with `fallback` field — LLD divergence)

```java
public record StageCResult(
    int chosenIndex,
    String reasoning,
    AugmentationSource source,
    boolean fallback) {}
```

## Edge-case checklist

### Happy path

- [ ] `aiService.execute(task)` returns `StageCPickResponse(2, "...")`; result is `StageCResult(2, "...", LLM, false)`
- [ ] `chosenIndex` correctly mapped from response to result
- [ ] `reasoning` propagated verbatim
- [ ] `source = LLM`, `fallback = false`

### Fallback paths

- [ ] `AiUnavailable` thrown → result is `StageCResult(0, "AI ranking unavailable; deterministic top-scored candidate selected.", LLM, true)`
- [ ] `TransientAiFailureException` thrown → same fallback, logged WARN
- [ ] Response `chosenIndex = -1` → fallback, logged WARN with the offending index
- [ ] Response `chosenIndex = N` (one past end) → fallback
- [ ] Response `chosenIndex = N+1000` → fallback
- [ ] `candidates.size() == 0` → returns deterministic fallback without calling `aiService.execute`

### Wiring

- [ ] `StageCPickTask.getTaskType()` returns `TaskType.PLAN_COMPOSITION`
- [ ] `StageCPickTask.getUserPromptRef()` returns `new PromptRef("planner/stage-c-pick", Optional.empty())`
- [ ] `StageCPickTask.getContext()` carries keys: `candidates`, `constraints_summary`, `household_size`, `week_start`, `trigger`
- [ ] `StageCPickTask.getTimeoutOverride()` returns `Optional.of(properties.stageCTimeout())`
- [ ] `StageCPickTask.getTraceId()` returns the trace id passed in
- [ ] Prompt template file lands at `src/main/resources/prompts/planner/stage-c-pick.txt` and is non-empty
- [ ] `getToolSchema()` produces a JSON schema with `chosenIndex: integer, reasoning: string`, both required (verify via `jsonschema-generator` output)

### Determinism

- [ ] Same `candidates`, same `rollups`, same `ctx`, same `traceId` → same `StageCPickTask` payload (no `Instant.now`, no random)
- [ ] Fallback path is byte-identical reasoning string

### Cross-cutting

- [ ] `@Transactional` NOT present on `pickOne` (AI calls outside any tx)
- [ ] `StageCInvokerImpl` package-private; `StageCInvoker` interface public
- [ ] `StageCResult` and `IndexedCandidateRollup` are public records (consumed by future 01j composer)
- [ ] `PlannerProperties.stageCTimeout` defaults to PT20S
- [ ] `PlannerProperties.iterationBudget` defaults to 3
- [ ] No regression on 01a-01f tests
- [ ] No `pom.xml` dep adds (AiService + TestAiService already on classpath from `ai` module)
- [ ] No other modules' files touched (except cross-module DTO consumption is allowed)

## Files this ticket touches

```
NEW   src/main/java/com/example/mealprep/planner/domain/service/internal/stagec/StageCInvoker.java
NEW   src/main/java/com/example/mealprep/planner/domain/service/internal/stagec/StageCInvokerImpl.java
NEW   src/main/java/com/example/mealprep/planner/domain/service/internal/stagec/StageCPickTask.java
NEW   src/main/java/com/example/mealprep/planner/domain/service/internal/stagec/StageCPickResponse.java
NEW   src/main/java/com/example/mealprep/planner/api/dto/StageCResult.java
NEW   src/main/java/com/example/mealprep/planner/api/dto/IndexedCandidateRollup.java

NEW   src/main/resources/prompts/planner/stage-c-pick.txt

MOD   src/main/java/com/example/mealprep/planner/config/PlannerProperties.java                              (append stageCTimeout, iterationBudget)
MOD   src/main/resources/application.yml                                                                     (append stage-c-timeout, iteration-budget)

NEW   src/test/java/com/example/mealprep/planner/StageCInvokerImplTest.java
NEW   src/test/java/com/example/mealprep/planner/StageCPickTaskTest.java
NEW   src/test/java/com/example/mealprep/planner/StageCInvokerIT.java                                       (use TestAiService bean from ai module; happy + AiUnavailable + TransientAiFailure paths)
MOD   src/test/java/com/example/mealprep/planner/testdata/PlanTestData.java                                  (add: stageCResultLlm, stageCResultFallback, twoCandidatesAndRollups builders)
```

Count: ~12 files. Estimated agent runtime 30-40 min.

**Files this ticket does NOT modify**:
- `PlansController`, `PlannerExceptionHandler` — no controller surface.
- OpenAPI YAMLs — no endpoints.
- Migrations — none.
- Other modules — none touched. `AiService`, `AiTask`, `PromptRef`, `TaskType.PLAN_COMPOSITION`, `AiUnavailable`, `TransientAiFailureException`, `TestAiService` are all consumed via `ai` module's public surface.

## Gotchas to bake in

1. **`@Transactional` MUST NOT be on `pickOne`**. AI calls run outside any DB transaction per the style guide's locked rule. The caller (01j composer) ensures the surrounding flow opens its tx only AFTER Stage C returns.
2. **Reusing nutrition's `CandidatePlanRollupDto`**: imported from `com.example.mealprep.nutrition.api.dto`. Direct import is fine — cross-module DTO consumption is allowed; the planner can't and shouldn't redefine the shape.
3. **`AiService.execute` is the public contract** from `ai-01a`. `AiUnavailable` is a checked-like or runtime exception (verify); the `try/catch` block needs to match the actual signature.
4. **`TestAiService` bean**: the `ai-01a` ticket shipped a test-profile `TestAiService` that returns canned `AiTask` responses. **01g's IT activates the test profile (`@ActiveProfiles("test")`)** so `TestAiService` is the autowired `AiService`. Verify the test profile activation in the IT's `@SpringBootTest` setup.
5. **`@Slf4j`**: Lombok-generated logger. Don't `@Autowired` a `Logger`.
6. **JSON schema generation**: the `getToolSchema()` impl uses the project's `jsonschema-generator` adapter (verify class name in `ai-01a`). For `StageCPickResponse`, the schema is auto-derived; no manual JSON construction needed.
7. **Prompt template file paths are CASE-SENSITIVE on Linux CI** even if Windows dev is case-insensitive. Use `planner/stage-c-pick.txt` (lowercase) consistently.
8. **Prompt template line-endings**: project uses `\R` in regex / line-splitting per the Windows CRLF gotcha. File is committed as LF (Spotless / .gitattributes); verify.
9. **Cross-module DTO consumption ArchUnit rule**: planner is allowed to import `nutrition.api.dto.*`. Boundary test should pass; **verify** that `nutrition.api.dto.CandidatePlanRollupDto` is public (it is per nutrition-01g) and the rule allows cross-module `api.dto` imports.
10. **`Map.of()` and Spring's argument resolver**: `getContext()` returns an immutable `Map<String, Object>`. Don't mutate the map after construction — `Map.of()` is immutable; mutation throws.
11. **`IllegalArgumentException` for size mismatch**: the implementation throws when `candidates.size() != rollups.size()`. This is a programmer error from the composer (01j) — not user-recoverable. Maps to 500 via Spring's default; the composer should never hit it in practice.
12. **`fallback` field on `StageCResult`** is a 01g divergence from the LLD. **Document explicitly** in `StageCResult.java`'s class-level Javadoc that the LLD locked the 3-field shape and 01g added a 4th field for composer wiring.
13. **`@TestPropertySource("mealprep.planner.stage-c-timeout=PT2S")`**: useful for the IT to assert timeout-override propagation. Spring's `Duration` parser handles `PT2S`.
14. **`AugmentationSource.LLM` usage on fallback** is per the 01g divergence note above — the field's semantic is "Stage C selection origin"; deterministic-fallback is still recorded as `LLM` because the user's intent at Stage C was to invoke the LLM. The `fallback` field disambiguates.

## Dependencies

- **Hard dependency**: `planner-01a` (merged) — `AugmentationSource` enum, `TriggerKind`.
- **Hard dependency**: `planner-01d` (merged) — `CandidatePlan`, `PlanCompositionContext`, `PlannerProperties`.
- **Hard dependency**: `planner-01f` (merged) — `RollupBuilder` produces the rollup that's converted to `CandidatePlanRollupDto`. **01g does NOT call `RollupBuilder` directly** — the composer (01j) calls both 01f and 01g and passes 01f's output to 01g's input. But: 01g's IT needs a fixture rollup, which it builds either by calling 01f (cleaner) or hand-rolling the DTO (faster, no dep on 01f's wiring being exercised). **01g's IT calls 01f to produce realistic rollups** — verifies the data-flow seam between rollup and pick.
- **Hard dependency**: `ai-01a` (merged) — `AiService`, `AiTask`, `PromptRef`, `TaskType` (incl. `PLAN_COMPOSITION` value; **flag if missing** — sibling chore needed), `AiUnavailable`, `TransientAiFailureException`, `TestAiService` test bean, `jsonschema-generator` adapter for `getToolSchema`.
- **Hard dependency**: `nutrition-01g` (merged) — `CandidatePlanRollupDto`, `CandidateDailyRollupDto`.
- **Sibling tickets running in parallel** (Wave 3 round 3): `planner-01h` (Phase 2 — independent), `planner-01i` (re-opt — independent). Neither touches `stagec/`.
- **Cross-module hard dependency on adaptation-pipeline**: NONE in 01g. Stage D's `OptimiserService` is consumed in 01h (Phase 2 emits refine-directives that flow to OptimiserService).

## Acceptance / DoD

- [ ] `./mvnw -Dspotless.check.skip=true verify` passes
- [ ] `./mvnw spotless:apply` then `./mvnw spotless:check` clean
- [ ] CI green
- [ ] All edge-case items above ticked
- [ ] `@Transactional` NOT present on `StageCInvokerImpl.pickOne` — verified by grep
- [ ] Prompt template file exists and is non-empty
- [ ] `TestAiService`-backed IT passes for happy + AiUnavailable + TransientAiFailure paths
- [ ] `PlannerProperties.stageCTimeout` and `iterationBudget` validation works (test override)
- [ ] No `pom.xml` dep adds
- [ ] No other modules' files touched

Squash-merge with: `feat(planner): 01g — StageCInvoker (LLM pick-of-N) + StageCPickTask + prompt-template skeleton + deterministic fallback`

## What's NOT in scope

- Phase 2 augmenter (consumes Stage C's chosen plan) → **planner-01h**
- Composer wiring (Stage A → B → C → D loop) → **planner-01j**
- Decision-log integration of Stage C reasoning → **planner-01l**
- Prompt-quality eval set / golden-output regression → out of scope; separate prompt-engineering ticket
- Streaming AI responses — current `AiService` contract is request/response only
- Per-household prompt customisation — single prompt body v1
- Stage D's `OptimiserService` invocation — Phase 2 emits the directives; 01h owns the flow
- Per-locale prompt translations — out of scope v1
