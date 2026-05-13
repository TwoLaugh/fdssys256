# Ticket: planner — 01h Phase 2 Augmenter (LLM augmentations + verifier + refine-directive emission)

## Summary

Layer **Phase 2** on Stage C. Given the chosen plan from 01g, invoke `AiService` with `Phase2AugmentationTask` (prompt #9) to propose:
- Up to 5 **augmentations** (`AddSnackAugmentation`, `IngredientSwapAugmentation`, `RepairAugmentation` — sealed permits).
- Up to 2 **refine-directives** to forward to Stage D's `OptimiserService` (from adaptation-pipeline module).

Ships `Phase2Augmenter` interface + impl, `Phase2AugmentationTask` (AiTask), `Phase2AugmentationResponse`, the sealed `Augmentation` hierarchy + record subtypes, `AugmentationProposal` + `RefineDirectiveProposal` DTO records, `AugmentationVerifier` (re-runs `HardConstraintFilterService` post-hoc), the `AugmentationResult` carrier, and the deterministic fallback for `AiUnavailable`/`TransientAiFailureException`. Per `lld/planner.md` §`Phase2Augmenter` lines 868-919.

01h ships the **wiring + verifier**; the actual call into `OptimiserService.adapt(directive)` is the composer's job (01j). 01h emits the `RefineDirectiveDto`s as part of the result; the composer routes them.

**Worth user review — prompt content gap**: same as 01g — the prompt body at `src/main/resources/prompts/planner/phase2-augmentation.txt` is a **minimal pilot** (~30 lines). Refinement is a post-pilot prompt-engineering exercise with its own eval set.

**Defers**:
- Composer wiring (Stage A→B→C→D loop) → **planner-01j**
- Synchronous `OptimiserService.adapt(directive)` call → **planner-01j**
- Decision-log row per Phase 2 invocation → **planner-01l**

## LLD divergence — `RefineDirectiveDto` shape

LLD `Phase2AugmentationResponse` declares `List<RefineDirectiveProposal> refineDirectives`. The downstream consumer is `OptimiserService.adapt(RefineDirectiveDto)` from the adaptation-pipeline module. **The two types are distinct**: `RefineDirectiveProposal` is the LLM's raw output; `RefineDirectiveDto` is the typed contract to the optimiser.

**01h ships both records**:
- `RefineDirectiveProposal` (planner-local; LLM output shape) — String fields, untyped enums (`type: String`).
- `RefineDirectiveDto` (the cross-module contract). **Worth user review**: this DTO belongs in the adaptation-pipeline module. If adaptation-pipeline-01a defines it, 01h imports. If not, **01h ships a planner-local placeholder** with TODO and the wave-3 sibling agent reconciles. **Predicted sibling-ticket dependency**: `adaptation-pipeline-01a` (or similar) defines `RefineDirectiveDto` and `OptimiserService` interface.

**01h's deferral**: the `Phase2AugmentationResponse.refineDirectives` field is typed as `List<RefineDirectiveProposal>` (planner-local raw). The conversion to `RefineDirectiveDto` happens inside `Phase2Augmenter.augment` — pulling from the response, validating, and packaging as `AugmentationResult.emittedDirectives`. If `RefineDirectiveDto` isn't on classpath yet, **01h returns an empty `emittedDirectives` list** with a TODO log line. **Worth user review** — alternative is to block the ticket on the sibling. Rejected because Phase 2 augmentations (the bigger payload) are independently shippable.

## LLD divergence — `AugmentationVerifier` reuses `HardConstraintFilterService`

LLD §`Phase2Augmenter` line 917: "Every proposed augmentation passes through `AugmentationVerifier`, which runs the same `HardConstraintFilterService.check(...)` used by Stage A. Augmentations that fail are discarded silently and logged WARN — the LLM is never trusted to remember constraints."

**01h implements `AugmentationVerifier` as a thin wrapper**. The actual constraint logic lives in `HardConstraintFilterService` (preference-01b). The verifier:
1. For each `Augmentation`, extracts the ingredient mapping keys (from `recipeId` lookup if `IngredientSwapAugmentation`, or from the new recipe added by `AddSnackAugmentation`).
2. Calls `HardConstraintFilterService.check(userId, ingredientKeys)` for per-person augmentations OR `checkForHousehold(householdId, ...)` for shared-slot augmentations.
3. Filters per-time-budget — same overshoot ratio as Stage A's hard filter.
4. Returns `passes(...): boolean` per augmentation.

## Behavioural spec

### Sealed `Augmentation` hierarchy

1. New sealed interface under `domain.service.internal.stagec/` (per LLD §`Phase2Augmenter` line 883):
   ```java
   sealed interface Augmentation
       permits AddSnackAugmentation, IngredientSwapAugmentation, RepairAugmentation {
     UUID targetSlotId();   // null for plan-level repairs
   }
   ```
2. Three record subtypes (all package-private):
   - `AddSnackAugmentation(UUID targetSlotId, UUID newRecipeId, int servings, String reasoning)` — inserts a new slot or scheduled recipe.
   - `IngredientSwapAugmentation(UUID targetSlotId, String fromIngredientKey, String toIngredientKey, String reasoning)` — swaps one ingredient in a slot's recipe. **Note**: this does NOT mutate the recipe — it produces a substitution overlay. **Worth user review** — the swap is informational only in 01h; the actual recipe mutation flows through `OptimiserService.adapt(refineDirective)` (Stage D), not Phase 2. **01h treats `IngredientSwapAugmentation` as a tag-only carrier** — the augmentation surfaces in `ScheduledRecipe.augmentationNotes` for user awareness, not as a recipe edit.
   - `RepairAugmentation(UUID targetSlotId, String issue, String resolution)` — flag a known issue (e.g. "this slot's protein is below the per-person floor") with a human-readable resolution. No mutation.
3. **`AugmentationProposal`** record — the **LLM's raw output shape** (untyped, JSON-friendly):
   ```java
   public record AugmentationProposal(
       String type,                                // "ADD_SNACK" | "INGREDIENT_SWAP" | "REPAIR"
       UUID targetSlotId,                          // nullable per type
       UUID newRecipeId,                           // for ADD_SNACK
       Integer servings,                           // for ADD_SNACK
       String fromIngredientKey,                   // for INGREDIENT_SWAP
       String toIngredientKey,                     // for INGREDIENT_SWAP
       String issue,                               // for REPAIR
       String resolution,                          // for REPAIR
       String reasoning) {}                        // common
   ```
   01h converts `AugmentationProposal → Augmentation` via a `AugmentationParser` helper.

### `AugmentationVerifier`

4. Package-private `@Component` under same package:
   ```java
   class AugmentationVerifier {
     boolean passes(Augmentation aug, PlanCompositionContext ctx);
   }
   ```
5. Algorithm per LLD line 917:
   - **`AddSnackAugmentation`**: load the new recipe from `ctx.recipePool().recipes()` by `newRecipeId`. If not in pool → fail (LLM hallucinated a recipe). If in pool → extract ingredient keys, run `HardConstraintFilterService.check(...)` against the slot's eaters / household. Run time-budget check against the slot's `timeBudgetMin × maxTimeOvershootRatio`. Pass if both pass.
   - **`IngredientSwapAugmentation`**: load the slot's current recipe, swap one ingredient key in the ingredient list, run filter check. Pass if check returns `ALLOWED`. **Worth user review** — actually applying the swap requires recipe substitution logic which lives in recipe-01e; 01h's verifier just validates the swap's *allergen safety*, not its semantic correctness. Document.
   - **`RepairAugmentation`**: always passes (no constraint to verify; it's just a flag).
6. Failures logged WARN with the augmentation type + reason.

### `Phase2Augmenter` interface + impl

7. New interface under same package:
   ```java
   public interface Phase2Augmenter {
     AugmentationResult augment(CandidatePlan chosenPlan,
                                CandidatePlanRollupDto chosenRollup,
                                PlanCompositionContext context,
                                UUID traceId);
   }
   ```
8. `@Component` `Phase2AugmenterImpl`, package-private. Dependencies: `AiService`, `AugmentationVerifier`, `AugmentationParser`, `PlannerProperties`.
9. **`@Transactional` NOT applied** — same rule as 01g: AI calls outside any tx.
10. **Method body**:
    - Build `nutritionGapsPerDay` — for each `DailyRollupDocument` in the rollup, identify macros that are below target's `LOWER_FLOOR` direction or above `UPPER_LIMIT`. **01h's gap detection is minimal**: walk the rollup's per-day macros against `ctx.nutritionByUserId().get(primaryUserId).macroTargets()`. Produces `List<Map<String, Object>>` with `{date, macro, target, actual, direction}` entries. The LLM uses this to know what to fix.
    - Construct `Phase2AugmentationTask` with limits from `PlannerProperties.maxAugmentations` (5) and `PlannerProperties.maxRefineDirectives` (2).
    - Call `aiService.execute(task)`. Returns `Phase2AugmentationResponse`.
    - **Parse augmentations**: convert each `AugmentationProposal` → `Augmentation` via `AugmentationParser`. Drop proposals with unknown `type` or missing required fields (log WARN).
    - **Cap at `maxAugmentations`**: take first 5 if LLM exceeded.
    - **Verify each augmentation**: pass through `AugmentationVerifier.passes(...)`. Split into `applied` (passing) and `discardedByVerifier` (failing).
    - **Parse refine-directives**: convert each `RefineDirectiveProposal` → `RefineDirectiveDto`. **If `RefineDirectiveDto` not on classpath** → log INFO and emit empty list. Cap at `maxRefineDirectives`.
    - Return `AugmentationResult(applied, discardedByVerifier, emittedDirectives)`.
11. **Fallback** — same triggers as 01g:
    - `AiUnavailable` → `AugmentationResult(List.of(), List.of(), List.of())` and the composer flags the plan `aiAugmented = false`.
    - `TransientAiFailureException` → same empty result, logged WARN.
12. **Timeout**: `Phase2AugmentationTask.getTimeoutOverride() = Optional.of(properties.stageCTimeout())` (reuse the Stage C timeout — Phase 2 is the same model tier per LLD).

### `Phase2AugmentationTask`

13. Final class per LLD lines 889-911. `TaskType.PLAN_AUGMENTATION`. **Verify `PLAN_AUGMENTATION` exists in `ai-01a`'s `TaskType` enum**; if not, document as a sibling-ai chore.
14. `getUserPromptRef()` returns `new PromptRef("planner/phase2-augmentation", Optional.empty())`.
15. `getContext()` carries:
    ```java
    Map.of(
        "chosen_plan", chosenPlanRollup,                  // CandidatePlanRollupDto from 01f
        "constraints_summary", constraintsSummary,
        "nutrition_gaps", nutritionGapsPerDay,
        "max_augmentations", 5,
        "max_refine_directives", 2);
    ```
16. `getToolSchema()` derived from `Phase2AugmentationResponse` (sealed-permits → schema with `oneOf` per LLM tool-spec).

### Prompt template

17. New file `src/main/resources/prompts/planner/phase2-augmentation.txt`. Minimal pilot body (~30 lines) — same Jinja-style placeholders:

    ```
    The chosen weekly meal plan has been selected. Review it and propose up to 5 small augmentations
    plus up to 2 refine-directives that would improve the household's nutrition / variety / pantry use.

    Constraints summary:
    {{ constraints_summary }}

    Chosen plan (per-day rollup):
    {% for d in chosen_plan.perDay %}
      {{ d.date }}: {{ d.calories }} kcal, protein {{ d.proteinG }}g
    {% endfor %}

    Known nutrition gaps:
    {% for g in nutrition_gaps %}
      {{ g.date }}: {{ g.macro }} actual {{ g.actual }} vs target {{ g.target }} ({{ g.direction }})
    {% endfor %}

    Allowed augmentation types:
    - ADD_SNACK: insert a new snack slot or scheduled recipe (returns targetSlotId, newRecipeId, servings)
    - INGREDIENT_SWAP: swap one ingredient in a slot's recipe (targetSlotId, fromIngredientKey, toIngredientKey)
    - REPAIR: flag an issue with a resolution string (targetSlotId, issue, resolution) — no recipe change

    Allowed refine-directive types:
    - SUBSTITUTE_INGREDIENT: ask the optimiser to find an adapted recipe (slot, fromKey, toKey)
    - REDUCE_TIME: ask the optimiser for a faster variant (slot, currentTimeMin, targetTimeMin)

    Return your proposals via the tool call. Limit to {{ max_augmentations }} augmentations and {{ max_refine_directives }} directives.
    ```

### `PlannerProperties` extension

18. Append:
    ```java
    @Min(1) int maxAugmentations,         // default 5
    @Min(0) int maxRefineDirectives       // default 2
    ```
19. `application.yml`:
    ```yaml
    mealprep:
      planner:
        max-augmentations: 5
        max-refine-directives: 2
    ```

### `AugmentationResult` (re-stated for clarity)

20. New public record under `api.dto/`:
    ```java
    public record AugmentationResult(
        List<Augmentation> applied,                       // post-verifier — survives
        List<Augmentation> discardedByVerifier,           // dropped silently, logged WARN
        List<RefineDirectiveDto> emittedDirectives) {}    // forwarded to Stage D (01j composer)
    ```
    `Augmentation` here is the **typed** sealed-hierarchy (NOT `AugmentationProposal`).

## Database

**Zero migrations.**

## OpenAPI updates

**No new endpoints.** `Phase2Augmenter` is in-process only.

## Verbatim shape snippets

### `Phase2AugmenterImpl`

```java
@Component
@RequiredArgsConstructor
@Slf4j
class Phase2AugmenterImpl implements Phase2Augmenter {

  private final AiService aiService;
  private final AugmentationVerifier verifier;
  private final AugmentationParser parser;
  private final PlannerProperties properties;

  @Override
  public AugmentationResult augment(CandidatePlan chosenPlan,
                                    CandidatePlanRollupDto chosenRollup,
                                    PlanCompositionContext ctx,
                                    UUID traceId) {
    String summary = buildConstraintsSummary(ctx);
    UUID primaryUserId = resolvePrimaryUserId(ctx);
    List<Map<String, Object>> gaps = computeNutritionGaps(chosenRollup, ctx, primaryUserId);

    Phase2AugmentationTask task = new Phase2AugmentationTask(
        chosenRollup, summary, gaps,
        properties.maxAugmentations(), properties.maxRefineDirectives(),
        primaryUserId, traceId, properties.stageCTimeout());

    Phase2AugmentationResponse response;
    try {
      response = aiService.execute(task);
    } catch (AiUnavailable e) {
      log.info("Phase 2: AI unavailable; emitting empty augmentation result");
      return new AugmentationResult(List.of(), List.of(), List.of());
    } catch (TransientAiFailureException e) {
      log.warn("Phase 2: transient AI failure; emitting empty augmentation result", e);
      return new AugmentationResult(List.of(), List.of(), List.of());
    }

    List<Augmentation> proposed = response.augmentations().stream()
        .limit(properties.maxAugmentations())
        .map(parser::parse)
        .filter(Objects::nonNull)
        .toList();

    List<Augmentation> applied = new ArrayList<>();
    List<Augmentation> discarded = new ArrayList<>();
    for (Augmentation a : proposed) {
      if (verifier.passes(a, ctx)) {
        applied.add(a);
      } else {
        log.warn("Phase 2: augmentation {} discarded by verifier", a);
        discarded.add(a);
      }
    }

    List<RefineDirectiveDto> directives = parseRefineDirectives(response, properties.maxRefineDirectives());

    return new AugmentationResult(List.copyOf(applied), List.copyOf(discarded), directives);
  }

  private List<RefineDirectiveDto> parseRefineDirectives(Phase2AugmentationResponse response, int max) {
    if (!isRefineDirectiveDtoOnClasspath()) {
      log.info("RefineDirectiveDto not on classpath (adaptation-pipeline not yet merged); emitting empty");
      return List.of();
    }
    return response.refineDirectives().stream()
        .limit(max)
        .map(this::toDto)
        .toList();
  }
}
```

### Sealed hierarchy

```java
sealed interface Augmentation
    permits AddSnackAugmentation, IngredientSwapAugmentation, RepairAugmentation {
  UUID targetSlotId();
}

record AddSnackAugmentation(UUID targetSlotId, UUID newRecipeId, int servings, String reasoning)
    implements Augmentation {}

record IngredientSwapAugmentation(UUID targetSlotId, String fromIngredientKey, String toIngredientKey, String reasoning)
    implements Augmentation {}

record RepairAugmentation(UUID targetSlotId, String issue, String resolution)
    implements Augmentation {
  // reasoning is the resolution field for this variant
}
```

### `AugmentationParser`

```java
@Component
class AugmentationParser {

  Augmentation parse(AugmentationProposal p) {
    return switch (p.type() == null ? "" : p.type().toUpperCase()) {
      case "ADD_SNACK" -> {
        if (p.newRecipeId() == null || p.servings() == null) yield null;
        yield new AddSnackAugmentation(p.targetSlotId(), p.newRecipeId(), p.servings(), p.reasoning());
      }
      case "INGREDIENT_SWAP" -> {
        if (p.fromIngredientKey() == null || p.toIngredientKey() == null) yield null;
        yield new IngredientSwapAugmentation(p.targetSlotId(), p.fromIngredientKey(),
            p.toIngredientKey(), p.reasoning());
      }
      case "REPAIR" -> {
        if (p.issue() == null || p.resolution() == null) yield null;
        yield new RepairAugmentation(p.targetSlotId(), p.issue(), p.resolution());
      }
      default -> null;
    };
  }
}
```

## Edge-case checklist

### Happy path

- [ ] LLM returns 3 augmentations, 1 directive, all pass verifier → `AugmentationResult(applied=3, discarded=0, emittedDirectives=1)`
- [ ] LLM returns 7 augmentations (exceeds limit 5) → first 5 are processed; latter 2 dropped before verifier
- [ ] LLM returns 3 directives (exceeds limit 2) → first 2 are processed
- [ ] LLM returns 0 augmentations → `AugmentationResult(empty, empty, empty)`
- [ ] LLM returns 0 directives → `AugmentationResult` with `emittedDirectives=[]`

### Parser edge cases

- [ ] `type="ADD_SNACK"` with null `newRecipeId` → returned as null from parser; filtered out
- [ ] `type="UNKNOWN_TYPE"` → returned as null; filtered
- [ ] `type=null` → returned as null
- [ ] `type="add_snack"` (lowercase) → parsed correctly via `toUpperCase`
- [ ] `IngredientSwapAugmentation` with both keys present → parsed
- [ ] `RepairAugmentation` with null `issue` → returned as null

### Verifier

- [ ] `AddSnackAugmentation` with `newRecipeId` not in `ctx.recipePool()` → fails (LLM hallucination)
- [ ] `AddSnackAugmentation` with allergen-clashing recipe → fails (`HardConstraintFilterService.check` returns BLOCKED)
- [ ] `AddSnackAugmentation` exceeding slot's time budget × 1.5 → fails
- [ ] `IngredientSwapAugmentation` with allergen-clashing target ingredient → fails
- [ ] `RepairAugmentation` → always passes (no constraint)
- [ ] Failures logged WARN with the augmentation type and reason

### Fallback paths

- [ ] `AiUnavailable` → empty `AugmentationResult`, logged INFO
- [ ] `TransientAiFailureException` → empty `AugmentationResult`, logged WARN
- [ ] No augmentations applied even when verifier would accept (because the AI call failed before the response)

### Cross-classpath check

- [ ] `RefineDirectiveDto` not on classpath (adaptation-pipeline-01a not merged) → `emittedDirectives = []`, INFO log
- [ ] `RefineDirectiveDto` on classpath → directives parsed and packaged

### Wiring

- [ ] `Phase2AugmentationTask.getTaskType()` returns `TaskType.PLAN_AUGMENTATION`
- [ ] `getUserPromptRef()` returns `new PromptRef("planner/phase2-augmentation", Optional.empty())`
- [ ] `getContext()` keys: `chosen_plan`, `constraints_summary`, `nutrition_gaps`, `max_augmentations`, `max_refine_directives`
- [ ] Prompt template file at `src/main/resources/prompts/planner/phase2-augmentation.txt` exists and non-empty

### Properties

- [ ] `PlannerProperties.maxAugmentations` default = 5
- [ ] `PlannerProperties.maxRefineDirectives` default = 2
- [ ] `@TestPropertySource("mealprep.planner.max-augmentations=1")` overrides

### Cross-cutting

- [ ] `@Transactional` NOT present on `Phase2AugmenterImpl.augment`
- [ ] `Phase2AugmenterImpl` package-private; `Phase2Augmenter` interface public
- [ ] `AugmentationResult` public record
- [ ] Sealed `Augmentation` hierarchy with 3 permits
- [ ] No regression on 01a-01g tests
- [ ] No `pom.xml` dep adds
- [ ] No other modules' files touched (cross-module DTO consumption OK)
- [ ] `PlannerBoundaryTest` still passes

## Files this ticket touches

```
NEW   src/main/java/com/example/mealprep/planner/domain/service/internal/stagec/Phase2Augmenter.java
NEW   src/main/java/com/example/mealprep/planner/domain/service/internal/stagec/Phase2AugmenterImpl.java
NEW   src/main/java/com/example/mealprep/planner/domain/service/internal/stagec/Phase2AugmentationTask.java
NEW   src/main/java/com/example/mealprep/planner/domain/service/internal/stagec/Phase2AugmentationResponse.java
NEW   src/main/java/com/example/mealprep/planner/domain/service/internal/stagec/AugmentationVerifier.java
NEW   src/main/java/com/example/mealprep/planner/domain/service/internal/stagec/AugmentationParser.java
NEW   src/main/java/com/example/mealprep/planner/domain/service/internal/stagec/Augmentation.java
NEW   src/main/java/com/example/mealprep/planner/domain/service/internal/stagec/AddSnackAugmentation.java
NEW   src/main/java/com/example/mealprep/planner/domain/service/internal/stagec/IngredientSwapAugmentation.java
NEW   src/main/java/com/example/mealprep/planner/domain/service/internal/stagec/RepairAugmentation.java
NEW   src/main/java/com/example/mealprep/planner/api/dto/AugmentationResult.java
NEW   src/main/java/com/example/mealprep/planner/api/dto/AugmentationProposal.java
NEW   src/main/java/com/example/mealprep/planner/api/dto/RefineDirectiveProposal.java                      (planner-local; raw LLM output)

NEW   src/main/resources/prompts/planner/phase2-augmentation.txt

MOD   src/main/java/com/example/mealprep/planner/config/PlannerProperties.java                              (append maxAugmentations, maxRefineDirectives)
MOD   src/main/resources/application.yml                                                                     (append max-augmentations, max-refine-directives)

NEW   src/test/java/com/example/mealprep/planner/Phase2AugmenterImplTest.java
NEW   src/test/java/com/example/mealprep/planner/AugmentationVerifierTest.java
NEW   src/test/java/com/example/mealprep/planner/AugmentationParserTest.java
NEW   src/test/java/com/example/mealprep/planner/Phase2AugmenterIT.java                                     (TestAiService canned responses; verifier behaviour; classpath-conditional directive emission)
MOD   src/test/java/com/example/mealprep/planner/testdata/PlanTestData.java                                  (add: phase2Response, addSnackProposal, refineDirectiveProposal builders)
```

Count: ~17 files. Estimated agent runtime 40-50 min.

**Files this ticket does NOT modify**:
- `PlansController`, `PlannerExceptionHandler` — no surface.
- OpenAPI YAMLs — no endpoints.
- Migrations — none.
- Other modules — none touched. Cross-module: `HardConstraintFilterService` (preference-01b), `AiService` (ai-01a), `RefineDirectiveDto` + `OptimiserService` (adaptation-pipeline — soft dep, classpath-conditional).

## Gotchas to bake in

1. **`@Transactional` MUST NOT be on `Phase2AugmenterImpl.augment`**. AI calls outside any tx. Composer (01j) ensures.
2. **Sealed interface `Augmentation` permits the 3 records exactly**. Spelling and casing matter — compile error if permits don't match.
3. **Switch on sealed types** — Java 17 pattern matching: `switch (aug) { case AddSnackAugmentation a -> ...; case IngredientSwapAugmentation s -> ...; case RepairAugmentation r -> ...; }` (no `default` needed; exhaustive).
4. **`RefineDirectiveDto` classpath-conditional**: use `Class.forName("com.example.mealprep.adaptation.api.dto.RefineDirectiveDto")` wrapped in try/catch at static-init or first-call. **Verify** the class name with the adaptation-pipeline sibling agent — the predicted name may be different. **01h documents the assumption in `Phase2AugmenterImpl.isRefineDirectiveDtoOnClasspath()` Javadoc**.
5. **`AugmentationVerifier` is package-private @Component**: Spring picks it up. The verifier injects `HardConstraintFilterService` — a cross-module public interface — via constructor.
6. **`AugmentationParser.parse` returns null on invalid proposals** — caller's `filter(Objects::nonNull)` is mandatory. Don't throw; the LLM occasionally emits malformed output and the silent-drop policy is per LLD line 1342.
7. **`HardConstraintFilterService.check` vs `checkForHousehold`**: shared-slot augmentations use `checkForHousehold`; per-person use `check(userId, ...)`. The verifier determines shared-vs-per-person from the slot's `shared` flag in the slot skeleton; if no slot skeleton matches the target id (because the augmentation is plan-level / Repair), default to `checkForHousehold(householdId, ...)`.
8. **JSON schema for sealed types**: `jsonschema-generator` supports sealed interfaces by emitting `oneOf` with a `type` discriminator. Verify the generated schema includes all 3 permits.
9. **`@Slf4j`** — Lombok.
10. **Prompt template line-endings** — same `\R` / `.gitattributes` discipline as 01g.
11. **`Map.of("key", value, ...)` is limited to 10 entries** in Java 17 by default. Phase 2's context has 5 entries — fine.
12. **Cross-module DTO consumption**: `nutrition.api.dto.CandidatePlanRollupDto` (from 01g), `recipe.api.dto.RecipeDto` (from recipe-01a), `preference.domain.service.HardConstraintFilterService` (interface). All public per the style guide.

## Dependencies

- **Hard dependency**: `planner-01a` (merged) — `AugmentationSource`, `ScheduledRecipe` field shapes.
- **Hard dependency**: `planner-01d` (merged) — `PlanCompositionContext`, `CandidatePlan`.
- **Hard dependency**: `planner-01f` (merged) — `CandidatePlanRollupDto` source (via the rollup builder).
- **Hard dependency**: `planner-01g` (merged) — Stage C produces the chosen plan that 01h augments. Sibling ordering: 01g first, then 01h.
- **Hard dependency**: `ai-01a` (merged) — `AiService`, `AiTask`, `PromptRef`, `TaskType.PLAN_AUGMENTATION`, `AiUnavailable`, `TransientAiFailureException`, `TestAiService`.
- **Hard dependency**: `preference-01b` (merged) — `HardConstraintFilterService`.
- **Hard dependency**: `recipe-01a` (merged) — `RecipeDto`, `RecipeMetadataDto`.
- **Hard dependency**: `nutrition-01h` (merged) — `NutritionForPlannerBundleDto.macroTargets` (for nutrition-gap detection).
- **Soft dependency**: `adaptation-pipeline-01a` (sibling wave-3 module ticket — being written in parallel) — `RefineDirectiveDto` and `OptimiserService`. **If not yet merged**, 01h's `emittedDirectives` list is always empty; INFO log explains. Composer (01j) consumes empty list gracefully.
- **Sibling tickets running in parallel** (Wave 3 round 4): `planner-01i` (re-opt — independent). Neither touches `stagec/`.

## Acceptance / DoD

- [ ] `./mvnw -Dspotless.check.skip=true verify` passes
- [ ] `./mvnw spotless:apply` then `./mvnw spotless:check` clean
- [ ] CI green
- [ ] All edge-case items above ticked
- [ ] `@Transactional` NOT on `Phase2AugmenterImpl.augment`
- [ ] Prompt template file exists and non-empty
- [ ] `TestAiService`-backed IT covers happy + AiUnavailable + TransientAiFailure paths
- [ ] Augmentation verifier integrates with real `HardConstraintFilterService` in IT (no @MockBean)
- [ ] Sealed `Augmentation` hierarchy compiles with 3 permits
- [ ] `RefineDirectiveDto` classpath-conditional path tested both ways (or documented as deferred verification when adaptation-pipeline lands)
- [ ] No `pom.xml` dep adds
- [ ] No other modules' files touched

Squash-merge with: `feat(planner): 01h — Phase2Augmenter + AugmentationVerifier + sealed Augmentation hierarchy + refine-directive emission + prompt-template skeleton + deterministic fallback`

## What's NOT in scope

- Composer wiring (Stage A→B→C→D loop, applies augmentations to plan, calls OptimiserService.adapt) → **planner-01j**
- Decision-log row per Phase 2 invocation → **planner-01l**
- Prompt-quality eval set → out of scope; separate prompt-engineering ticket
- Augmentation application to the persisted `Plan` aggregate (creating new `ScheduledRecipe` rows for ADD_SNACK; setting `augmentationNotes`) → that's the composer's job in 01j
- Multi-round Phase 2 (re-prompt with constraint reminder on first failure) — per LLD §Failure Modes, the corrective re-prompt is owned by `AiService`'s retry policy. 01h trusts that policy.
- Per-locale prompt content — single English prompt body v1
