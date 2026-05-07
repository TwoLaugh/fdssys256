# Prompt — Recipe Adaptation

*Proposes structured changes to a recipe based on a trigger (import, feedback, data-model change, plan-time refine). The culinary intelligence at the heart of the Adaptation Pipeline.*

Cross-cutting conventions defer to [README.md](README.md).

## Wiring

| | |
|---|---|
| AiTask name | `RecipeAdaptationTask` |
| TaskType | `RECIPE_ADAPTATION` |
| Tier | Sonnet 4.6 (mid) |
| Module | `adaptation-pipeline` |
| Called by | `AdaptationService` for each of the four triggers (per [lld/adaptation-pipeline.md](../adaptation-pipeline.md)) |
| Failure path | `AiUnavailable` → block-and-prompt (per the adaptation pipeline's locked degradation pattern); user sees "AI features paused" |
| Cache | System prompt + examples cached; per-trigger user template variant; per-call recipe + constraints vary |
| Cost | ~£0.05/call; ~5 adaptations/wk (across all triggers) per active user → ~£0.25/wk |

## Purpose

The recipe-scale instance of the optimisation loop's Stage A→C-with-LLM (per [optimisation-loop.md](../../design/optimisation-loop.md)). Given a recipe and an adaptation goal, propose a structured change. The change ships as one of three types:

- **VERSION** — linear refinement on the same branch (e.g. "reduce salt by 30%"). Same dish, evolved.
- **BRANCH** — meaningful fork into a variant (e.g. "make this with chicken instead of beef" — same base recipe, different character). Coexists with the original.
- **SUBSTITUTION** — a constraint-driven overlay (e.g. "swap olive oil for rapeseed when budget is tight"). Plan-slot-attached, not recipe-mutating.

Per the adaptation pipeline LLD, the user catalogue requires user approval before any change applies; the system catalogue applies freely. This prompt's output is the same either way — the pipeline decides what to do with it.

This is **not**:
- The hard-constraint filter (allergies/dietary identity are deterministic; never proposed by AI)
- A new-recipe generator (separate task type)
- A nutrition recompute (deterministic via `NutritionCalculationService`)

## Inputs / Outputs

**Inputs (`AiTask.getContext()`):**

```java
Map.of(
    "trigger",                AdaptationTrigger,           // IMPORT | FEEDBACK | DATA_MODEL_CHANGE | PLAN_TIME_REFINE
    "recipe",                 RecipeForAdaptationDto,       // current recipe + version content
    "constraint_summary",     ConstraintSummaryDto,         // hard + soft constraints in effect
    "trigger_payload",        Map<String, Object>,          // trigger-specific context (see below)
    "user_id",                UUID
)

record RecipeForAdaptationDto(
    UUID recipeId, UUID currentVersionId, String name, String description,
    Catalogue catalogue,                                    // USER | SYSTEM
    Servings servings,
    Integer prepTimeMin, Integer cookTimeMin, Integer totalTimeMin,
    List<RecipeIngredientForAdaptation> ingredients,        // each: name, quantity, unit, mappingKey, optional
    List<MethodStepDto> methodSteps,
    RecipeMetadataDto metadata                              // cuisine, cooking_method, complexity, flavour_profile, dietary_flags
) {}

record ConstraintSummaryDto(
    HouseholdHardConstraints hardConstraints,               // allergens, dietary identity (filter informs you; you don't propose against)
    BudgetTargetDto budget,                                 // weekly cap; per-recipe expected fraction
    NutritionTargetSummary nutrition,                       // weekly macro shape, daily floors
    TimeContext timeContext,                                // typical slot time budgets
    PreferenceSummary preferences                           // taste-profile-derived high-level summary
) {}
```

The `trigger_payload` shape varies per trigger:

```java
// IMPORT
record ImportTriggerPayload(String sourceUrl, BigDecimal estimatedCostGbp, List<String> initialFlags) {}

// FEEDBACK
record FeedbackTriggerPayload(
    String feedbackText,                                    // verbatim user feedback
    UUID feedbackId,
    String feedbackContext                                   // "after eating Wednesday's plan"
) {}

// DATA_MODEL_CHANGE
record DataModelChangeTriggerPayload(
    String changedModel,                                     // "preference" | "nutrition" | "provisions"
    String changedField,                                     // e.g. "dietary_identity_base"
    Object oldValue, Object newValue                          // nullable; small types only
) {}

// PLAN_TIME_REFINE
record PlanTimeRefinePayload(
    String directive,                                         // "reduce cost by ~£3" / "increase protein by 20g"
    UUID planId, UUID slotId,
    BigDecimal currentRecipeCost, BigDecimal currentRecipeProtein
) {}
```

**Output:**

```java
record AdaptationProposal(
    ChangeType changeType,                                   // VERSION | BRANCH | SUBSTITUTION
    List<AdaptationDelta> deltas,                            // structured changes to apply
    String rationale,                                         // user-facing 1-2 sentence justification
    BigDecimal confidence,                                    // 0-1 per README scale
    List<String> warnings,
    Optional<AdaptationProposal> branchAlternative            // only for BRANCH type — the un-adapted original
) {}

sealed interface AdaptationDelta {
    String reasoning();                                       // 1 line; recorded in version history

    record IngredientSwap(String originalIngredientMappingKey, String newName, String newMappingKey,
                          BigDecimal newQuantity, Unit newUnit, String reasoning) implements AdaptationDelta {}

    record IngredientQuantityChange(String ingredientMappingKey, BigDecimal newQuantity,
                                    Unit newUnit, String reasoning) implements AdaptationDelta {}

    record IngredientAdd(String name, String mappingKey, BigDecimal quantity, Unit unit,
                         boolean optional, String reasoning) implements AdaptationDelta {}

    record IngredientRemove(String ingredientMappingKey, String reasoning) implements AdaptationDelta {}

    record MethodStepReplace(int stepNumber, String newInstruction, String reasoning) implements AdaptationDelta {}

    record MethodStepAdd(int afterStepNumber, String instruction, String reasoning) implements AdaptationDelta {}

    record MethodStepRemove(int stepNumber, String reasoning) implements AdaptationDelta {}

    record MetadataUpdate(String fieldPath, Object newValue, String reasoning) implements AdaptationDelta {}
}

enum ChangeType { VERSION, BRANCH, SUBSTITUTION }
```

## System Prompt

```
You are a culinary intelligence engine. Given a recipe and an adaptation goal, you propose structured changes that improve the recipe against the goal — without breaking what makes it work.

CHANGE TYPE — you decide one of three:
- VERSION: linear refinement; same dish, evolved (e.g. reduce salt, sub one ingredient with a near-equivalent, add a herb). Use for tweaks that preserve the dish's character.
- BRANCH: creative fork that produces a meaningfully different variant (e.g. swap protein, change cooking method, restructure flavour profile). Use when the change is large enough that the user might want both the original and the variant.
- SUBSTITUTION: constraint-driven overlay attached to a plan slot, not the recipe (e.g. "swap olive oil for rapeseed when budget is tight"). Use for one-off plan-time accommodations that shouldn't change the canonical recipe.

DECISION HEURISTIC:
- Trigger IMPORT or DATA_MODEL_CHANGE → usually VERSION (refining the recipe to fit constraints) or BRANCH (when the change is large)
- Trigger FEEDBACK → usually VERSION (responding to specific dish-level feedback)
- Trigger PLAN_TIME_REFINE → usually SUBSTITUTION (one-off plan accommodation)

EXCEPTIONS exist; pick what fits the change. If unsure between VERSION and BRANCH, ask: "would the user want both versions in their library?" — if yes, BRANCH.

CONSTRAINT RESPECT:
- Hard constraints (allergens, dietary identity) are enforced by deterministic code AFTER your output. Do not introduce ingredients that violate the household's hard constraints. The deterministic filter will reject your output if you do; the user sees a wasted attempt.
- Soft constraints (budget, nutrition, preferences, time) are what your adaptation responds to. Trade them off intelligently — don't sacrifice one beyond what's needed for the trigger.

DELTA OPS — keep changes minimal:
- IngredientSwap: most common. New ingredient must be a real, recognisable food. Specify mapping key (best guess; the validator checks it).
- IngredientQuantityChange: tweak amounts. Use when the dish needs more/less of something already present.
- IngredientAdd / IngredientRemove: add or remove an ingredient. Add only when essential to the change.
- MethodStepReplace / Add / Remove: alter cooking instructions. Method changes often follow ingredient changes (substituting tofu for chicken means changing the cooking method).
- MetadataUpdate: rarely. Only when the change materially affects time, complexity, or cuisine tags.

KEEP IT TIGHT. Most adaptations need 1-3 deltas. If you find yourself proposing 8+, you're proposing a new recipe — return BRANCH with a clear rationale, or push back on the trigger via warnings.

CULINARY KNOWLEDGE EXPECTATIONS:
You should know: common protein equivalents, fat-source equivalents (olive oil ↔ rapeseed for cost; butter ↔ olive oil for dairy-free), salt-reduction techniques (lemon, vinegar, herbs), protein-boost ingredients (eggs, Greek yoghurt, beans), method-level swaps (oven-roast vs pan-fry trade-offs), pack-size rounding ("recipe needs 600g, you can buy 500g packs" → adjust to 500g or 1kg), seasoning balance.

You should NOT invent: brand-name ingredients, regional ingredients you don't know, fictional cooking techniques, claims about nutrition (deterministic recompute handles this).

CONFIDENCE CALIBRATION (per README):
- 0.9-1.0: clear, well-tested adaptation pattern (e.g. swap rapeseed for olive oil to drop cost — well-known, low-risk).
- 0.6-0.9: judgement call but defensible (e.g. swap chicken thigh for whole chicken — works but changes texture).
- 0.3-0.6: experimental (e.g. swap protein on a recipe whose flavour balance was protein-driven).
- 0.0-0.3: shouldn't be shipping; flag in warnings, return empty deltas, suggest the user keep the original.

RATIONALE:
1-2 sentences, user-facing. Frame as "what changed and why" — the user reads this in the approval UI. Avoid jargon. "Swapped chicken thighs for skinless breast to reduce fat by 8g per serving" — concrete and grounded.

WHEN TO RETURN EMPTY DELTAS:
- The trigger asks for something the recipe genuinely can't accommodate (e.g. "make this lower-carb" on a pasta dish — empty + warning suggesting the user pick a different recipe for low-carb slots).
- Hard constraints are violated by the recipe itself (an allergen is core; the deterministic filter will reject the recipe entirely; your job is to surface this rather than propose around it).
- The recipe is fine as-is for this trigger (e.g. plan-time refine asking for cost reduction but the recipe is already cheap).

In all empty cases, populate `rationale` with the reason and `warnings` with what the user might want to do instead.
```

## User Prompt Template

```
[Task: RECIPE_ADAPTATION]

<trigger>{{TRIGGER}}</trigger>

<recipe>
{{RECIPE_JSON}}
</recipe>

<constraint_summary>
{{CONSTRAINT_SUMMARY_JSON}}
</constraint_summary>

<trigger_payload>
{{TRIGGER_PAYLOAD_JSON}}
</trigger_payload>

Propose an adaptation per the rules. Pick a single ChangeType (VERSION, BRANCH, or SUBSTITUTION). Keep deltas minimal.
```

## Examples (in-prompt, wrapped in `<examples>`)

Five examples — one per trigger plus one edge case:

```
<examples>

<example>
<input>
<trigger>IMPORT</trigger>
<recipe>
{ "name":"Pad Thai", "ingredients":[
  {"name":"rice noodles","quantity":200,"unit":"g","mappingKey":"rice noodles dry"},
  {"name":"prawns","quantity":250,"unit":"g","mappingKey":"prawns raw"},
  {"name":"peanuts crushed","quantity":40,"unit":"g","mappingKey":"peanuts roasted"},
  ... ], ... }
</recipe>
<constraint_summary>
{ "hardConstraints":{"allergens":["peanut","tree nut"],"dietary":"omnivore"}, ... }
</constraint_summary>
<trigger_payload>
{ "sourceUrl":"...","initialFlags":["allergen_present:peanut"], "estimatedCostGbp":7.20 }
</trigger_payload>
</input>
<output>
{
  "changeType":"VERSION",
  "deltas":[
    { "type":"IngredientSwap",
      "originalIngredientMappingKey":"peanuts roasted",
      "newName":"toasted pumpkin seeds",
      "newMappingKey":"pumpkin seeds toasted",
      "newQuantity":40, "newUnit":"g",
      "reasoning":"Peanut allergen present in household; toasted pumpkin seeds give similar crunch and nutty depth without the allergen." }
  ],
  "rationale":"Swapped crushed peanuts for toasted pumpkin seeds — allergen-safe and keeps the dish's nutty crunch.",
  "confidence":0.9,
  "warnings":[],
  "branchAlternative":null
}
</output>
</example>

<example>
<input>
<trigger>FEEDBACK</trigger>
<recipe>
{ "name":"Beef Bourguignon", "ingredients":[
  {"name":"beef chuck","quantity":800,"unit":"g","mappingKey":"beef chuck stewing raw"},
  {"name":"salt","quantity":2,"unit":"tsp","mappingKey":"salt table"},
  {"name":"red wine","quantity":500,"unit":"ml","mappingKey":"red wine"},
  ... ], ... }
</recipe>
<constraint_summary>{...}</constraint_summary>
<trigger_payload>
{ "feedbackText":"This was way too salty for me, especially given the wine reduction also concentrates flavours.", "feedbackId":"f1" }
</trigger_payload>
</input>
<output>
{
  "changeType":"VERSION",
  "deltas":[
    { "type":"IngredientQuantityChange",
      "ingredientMappingKey":"salt table",
      "newQuantity":1, "newUnit":"tsp",
      "reasoning":"Reducing initial salt by 50% to account for the wine reduction's concentration; user can always season at the table." },
    { "type":"MethodStepAdd",
      "afterStepNumber":-1,
      "instruction":"Taste before serving; season to taste. The wine reduction will have concentrated existing salt — add gradually if more is needed.",
      "reasoning":"Explicit gating step prevents over-salting via the same mechanism the user complained about." }
  ],
  "rationale":"Reduced initial salt by half and added a 'taste before serving' step — the wine reduction concentrates seasoning; saltiness should now build to user preference at the table rather than upfront.",
  "confidence":0.85,
  "warnings":[],
  "branchAlternative":null
}
</output>
</example>

<example>
<input>
<trigger>DATA_MODEL_CHANGE</trigger>
<recipe>
{ "name":"Lamb Tagine", "ingredients":[
  {"name":"lamb shoulder","quantity":600,"unit":"g","mappingKey":"lamb shoulder boneless raw"},
  ... ], ... }
</recipe>
<constraint_summary>
{ "budget":{"weeklyTargetGbp":50,"averagePerRecipeGbp":7.5}, ... }
</constraint_summary>
<trigger_payload>
{ "changedModel":"provisions", "changedField":"budget.weeklyTargetGbp", "oldValue":75, "newValue":50 }
</trigger_payload>
</input>
<output>
{
  "changeType":"BRANCH",
  "deltas":[
    { "type":"IngredientSwap",
      "originalIngredientMappingKey":"lamb shoulder boneless raw",
      "newName":"chicken thighs skinless",
      "newMappingKey":"chicken thigh skinless raw",
      "newQuantity":600, "newUnit":"g",
      "reasoning":"Chicken thigh costs ~£3.50/600g vs lamb shoulder ~£10/600g; tagine spice profile carries chicken well, this is a recognised variant." },
    { "type":"MetadataUpdate",
      "fieldPath":"name",
      "newValue":"Chicken Tagine",
      "reasoning":"Renamed to reflect the protein change — meaningfully different dish, deserves its own identity in the catalogue." }
  ],
  "rationale":"Created a chicken-based variant — meaningfully different dish from the lamb original; both worth having in the catalogue. Cost drops from ~£12/recipe to ~£5.",
  "confidence":0.85,
  "warnings":[],
  "branchAlternative":null
}
</output>
</example>

<example>
<input>
<trigger>PLAN_TIME_REFINE</trigger>
<recipe>
{ "name":"Spaghetti Carbonara", "ingredients":[
  {"name":"guanciale","quantity":120,"unit":"g","mappingKey":"guanciale cured"},
  ... ], ... }
</recipe>
<constraint_summary>{...}</constraint_summary>
<trigger_payload>
{ "directive":"reduce cost by ~£2", "planId":"...", "slotId":"...",
  "currentRecipeCost":8.40, "currentRecipeProtein":28 }
</trigger_payload>
</input>
<output>
{
  "changeType":"SUBSTITUTION",
  "deltas":[
    { "type":"IngredientSwap",
      "originalIngredientMappingKey":"guanciale cured",
      "newName":"pancetta",
      "newMappingKey":"pancetta cured",
      "newQuantity":120, "newUnit":"g",
      "reasoning":"Pancetta is the standard non-traditional substitute for guanciale; ~£2 cheaper per 120g; flavour profile slightly less complex but carries the dish." }
  ],
  "rationale":"Swapped guanciale for pancetta this week — saves ~£2; standard substitute used in most carbonara recipes outside Italy. The original carbonara stays in your library; this is just a one-off plan adjustment.",
  "confidence":0.9,
  "warnings":[],
  "branchAlternative":null
}
</output>
</example>

<example>
<input>
<trigger>PLAN_TIME_REFINE</trigger>
<recipe>
{ "name":"Vegan Mushroom Risotto", "ingredients":[...] }
</recipe>
<constraint_summary>{...}</constraint_summary>
<trigger_payload>
{ "directive":"increase protein by 15g per serving",
  "currentRecipeCost":4.20, "currentRecipeProtein":12 }
</trigger_payload>
</input>
<output>
{
  "changeType":"SUBSTITUTION",
  "deltas":[
    { "type":"IngredientAdd",
      "name":"firm tofu cubes",
      "mappingKey":"tofu firm raw",
      "quantity":150, "unit":"g",
      "optional":false,
      "reasoning":"150g firm tofu adds ~12g protein per serving (2 servings); cubed and stirred in late preserves the risotto texture." },
    { "type":"MethodStepAdd",
      "afterStepNumber":3,
      "instruction":"Add cubed tofu in the last 5 minutes of stirring; warm through with the rice.",
      "reasoning":"Late addition keeps tofu's texture; aligns with risotto's typical late-add ingredient timing." }
  ],
  "rationale":"Added firm tofu cubes in the last 5 minutes — adds ~12g protein per serving without disrupting the risotto's texture or flavour. Plan-only change; canonical recipe stays as-is.",
  "confidence":0.85,
  "warnings":["Hits 12g of the 15g target; full 15g would need ~190g tofu which starts to dominate the dish."],
  "branchAlternative":null
}
</output>
</example>

</examples>
```

## Eval Set

| # | Scenario | Expected behaviour |
|---|---|---|
| 1 | IMPORT with allergen | Swap allergen for safe equivalent, VERSION, HIGH confidence |
| 2 | FEEDBACK "too bland" | Add aromatic/herb at appropriate step, VERSION |
| 3 | FEEDBACK "took twice as long as said" | MetadataUpdate to time fields + warning if no recipe-content change is warranted |
| 4 | DATA_MODEL_CHANGE: dietary identity flip to vegetarian | BRANCH if recipe is meat-centric; deltas substitute protein |
| 5 | PLAN_TIME_REFINE: budget reduction; expensive recipe | SUBSTITUTION with cheaper ingredient |
| 6 | PLAN_TIME_REFINE: protein increase needed; low-protein dessert recipe | Empty deltas + warning ("desserts not the right slot for protein boost") |
| 7 | IMPORT: recipe is fine as-is | Empty deltas with rationale "no adaptation needed for this trigger" |
| 8 | FEEDBACK contradicts the recipe's intent ("this stew shouldn't be slow-cooked") | Empty + warning suggesting recipe might not match user's expectation |
| 9 | DATA_MODEL_CHANGE: time-budget reduction | Method step simplification or warning that recipe is fundamentally slow |
| 10 | PLAN_TIME_REFINE with two contradictory directives ("cheaper AND higher protein") | BRANCH with creative solution (legumes for cheap protein), warning about tradeoff |
| 11 | IMPORT recipe has 12 ingredients, all reasonable | No adaptation; the prompt isn't a quality scorer |
| 12 | FEEDBACK references a recipe element that doesn't exist ("the curry leaves were stale" but no curry leaves in recipe) | Empty + warning about feedback-recipe mismatch |
| 13 | Recipe with hard-constraint violation (an allergen) | Empty deltas + warning that the recipe should be filtered out, not adapted |
| 14 | PLAN_TIME_REFINE on a recipe already minimised for the constraint | Empty + warning "already at floor for cost" |
| 15 | DATA_MODEL_CHANGE: novelty tolerance increased | No automatic action; this isn't a recipe-level concern |
| 16 | IMPORT recipe with method steps in wrong order | No method-reorder delta (out of scope); warning suggesting user manual review |

Acceptance threshold: **13/16** for ship; **15/16** for mature. Lower than mechanical prompts because adaptation judgement is genuinely subjective.

## Cost Analysis

| Metric | Estimate |
|---|---|
| Input tokens (cache hit) | ~1500-3000 (recipe + constraints) |
| Cached input tokens | ~4000 (system prompt + 5 examples) |
| Output tokens | ~400-800 |
| Cost per call (Sonnet 4.6, cached) | **~£0.05** |
| Adaptations per active user per week | ~5 across all triggers |
| **Cost per user per week** | **~£0.25** |

## Failure Modes

Beyond README boilerplate:

| Failure | Behaviour |
|---|---|
| Output references an ingredient mapping key that doesn't resolve | Validator rejects the specific delta; if all deltas reject, return as `adaptation_invalid` |
| ChangeType doesn't match the recommendation heuristic (e.g. SUBSTITUTION for an IMPORT trigger) | Allowed but logged for review — heuristic isn't a hard rule |
| Empty deltas + populated rationale | Valid; pipeline records "considered, no change" |
| Hard-constraint violation in proposed deltas (allergen introduced) | Deterministic filter rejects; pipeline marks `terminal_failure_filtered` and surfaces to user |
| BRANCH with no `branchAlternative` populated | Validator rejects; BRANCH must include the unmodified original |
| SUBSTITUTION with a non-trivial method change (>2 method deltas) | Reject — substitutions are ingredient-level overlays, not method rewrites; warn |

## AiTask Skeleton

```java
public final class RecipeAdaptationTask implements AiTask<AdaptationProposal> {
    private final AdaptationTrigger trigger;
    private final RecipeForAdaptationDto recipe;
    private final ConstraintSummaryDto constraints;
    private final Map<String, Object> triggerPayload;
    private final UUID userId;
    private final UUID traceId;

    @Override public TaskType getTaskType() { return TaskType.RECIPE_ADAPTATION; }
    @Override public String getSystemPrompt() { return SYSTEM_PROMPT; }
    @Override public PromptRef getUserPromptRef() {
        return new PromptRef("adaptation/recipe-adaptation-user-" + trigger.name().toLowerCase(),
                             Optional.empty());
    }
    @Override public Map<String, Object> getContext() {
        return Map.of("trigger", trigger, "recipe", recipe,
                      "constraint_summary", constraints, "trigger_payload", triggerPayload);
    }
    @Override public ToolDefinition getToolSchema() {
        return ToolDefinitionBuilder.fromRecord(AdaptationProposal.class).build();
    }
    @Override public Class<AdaptationProposal> getResponseType() { return AdaptationProposal.class; }
    @Override public UUID getUserId() { return userId; }
    @Override public UUID getTraceId() { return traceId; }
    @Override public Optional<Duration> getTimeoutOverride() { return Optional.of(Duration.ofSeconds(20)); }
}
```

## Decisions made (worth user review)

1. **Per-trigger user template variant** — `recipe-adaptation-user-import.txt`, `-feedback.txt`, `-datamodel.txt`, `-plantime.txt`. Each has subtly different framing for the trigger. The system prompt is shared; only the user template differs. Cache breakpoint sits between system+examples and per-trigger user template — cache works across triggers if the system+examples is the cached prefix.
2. **Pipeline decides VERSION vs BRANCH from the prompt's recommendation** — the prompt produces a `changeType` field; the pipeline accepts it but may override (e.g. for system-catalogue recipes, BRANCH is rare since system catalogue is fluid).
3. **SUBSTITUTION limits enforced** — locked at "≤2 method deltas" by validator. SUBSTITUTION is an ingredient overlay, not a method rewrite. Bigger changes → BRANCH.
4. **Empty-deltas-with-rationale is a valid output** — explicitly modelled. Captures "the prompt considered, decided no change is right." Logged distinctly from failures.
5. **Hard-constraint introduction is the deterministic filter's responsibility** — the prompt is encouraged not to introduce allergens, but the safety net is post-AI deterministic enforcement. Don't trust the model alone with safety-critical filtering.
6. **Confidence threshold for shipping** — confidence < 0.5 deltas are surfaced for the user to review with a "low-confidence adaptation" badge in the UI; system-catalogue auto-apply is gated above 0.7.
7. **Eval threshold 13/16** — adaptation is judgement-heavy; perfect agreement isn't a realistic bar. False adaptations recover via feedback in the next iteration.
