# Prompt — USDA Ingredient Mapping

*Maps a recipe ingredient line to a USDA FoodData Central entry. Cheap-tier. High-volume hot path.*

Cross-cutting conventions (confidence scale, null-population, alt-swap rule, edge-case examples, enum whitelisting, cache strategy) defer to [README.md](README.md) — this doc only restates them where the application is task-specific.

## Wiring

| | |
|---|---|
| AiTask name | `NutritionUsdaMappingTask` |
| TaskType | `NUTRITION_INGREDIENT_MAPPING` |
| Tier | Haiku 4.5 (cheap) |
| Module | `nutrition` |
| Called by | `IngredientMappingPipeline` (per [lld/nutrition.md](../nutrition.md)) — recipe save, recipe URL import, intake free-text parse |
| Input prep | Code does fuzzy text search over USDA + Open Food Facts; passes top-10 candidates to the prompt |
| Failure path | `AiUnavailable` → row persists with `mapping_status = 'pending'`, async retry; terminal → `'failed'`, surfaced for manual mapping |
| Cache | System prompt + examples (~1.5k tokens) cached `ephemeral` (5-min TTL); per-call ingredient + candidates vary |
| Cost | ~£0.0005/call; ~30 lines × 5 recipes/wk = ~£0.075/wk per active user |

## Purpose

The pipeline-level loop is *cache → AI parse + match → store*. This single prompt does both the parsing (extract quantity/unit, normalise name, identify preparation state) and the matching (pick the best USDA candidate from a pre-searched top-10) in one Haiku call. Combining them halves cost and removes a round-trip vs the original two-step plan.

The prompt receives candidates from a deterministic fuzzy text search; the LLM's job is qualitative tie-breaking and quantity parsing — never deciding "is this a real ingredient." Code does the rough work; the model does the judgement.

## Inputs / Outputs

**Inputs (passed via `AiTask.getContext()`):**

```java
Map.of(
    "ingredient_text",    String,                     // raw line, e.g. "2 tbsp olive oil"
    "recipe_cuisine",     String,                     // e.g. "Italian"; null if unknown
    "recipe_meal_type",   String,                     // e.g. "dinner"; null if unknown
    "candidates",         List<UsdaCandidateDto>      // top-10 from fuzzy search
)

record UsdaCandidateDto(String fdcId, String description, String foodCategory) {}
```

**Output (structured tool-use):**

```java
record IngredientMappingResponse(
    BestMatch bestMatch,                              // null fdcId allowed if no candidate fits
    ParsedQuantity parsedQuantity,
    List<Alternative> alternatives,                   // 0-3 entries; never null, [] when empty
    List<String> warnings                             // free-text flags; never null, [] when empty
) {}

record BestMatch(
    String fdcId,                                     // nullable — null when no candidate matches
    String matchedDescription,                        // nullable — paired-null with fdcId
    BigDecimal confidence                             // 0 when fdcId null
) {}

record ParsedQuantity(
    BigDecimal amount,                                // nullable — null for non-numeric ("to taste")
    Unit unit,                                        // enum (see tool schema below); nullable
    BigDecimal amountInGrams                          // nullable when unit can't be converted
) {}

record Alternative(String fdcId, String reason, BigDecimal confidence) {}

enum Unit { G, KG, ML, L, TBSP, TSP, CUP, OZ, LB, PIECE, CAN, BOTTLE, JAR,
            SPRIG, CLOVE, PINCH, DASH, DROP, OTHER }
```

## System Prompt

```
You are a culinary database matcher. Given a recipe ingredient line and a small set of pre-searched USDA FoodData Central candidates, pick the best match and parse the quantity.

Your output is consumed by a deterministic nutrition aggregator. Wrong matches produce wrong macros. When uncertain, lower the confidence and list alternatives — do not guess.

INPUTS:
- The raw ingredient line as it appeared in a recipe.
- Recipe context (cuisine, meal type) — use ONLY to break ties between similar candidates.
- Up to 10 candidate USDA entries returned by a fuzzy text search. The right answer is almost always in this list. If it isn't, return null fdcId with confidence 0 and a warning explaining what you'd expect.

WHAT TO DO:
1. Identify the core ingredient name (strip brand prefixes, strip vague qualifiers like "fresh" or "good-quality").
2. Identify the preparation state (raw, cooked, roasted, frozen, dried). Default raw if unspecified — most recipes work with raw ingredients and cook them.
3. Pick the candidate whose description best matches the core ingredient AND preparation state. Cuisine context is a tiebreaker only — never overrides a clearly-better description match.
4. Parse the quantity and unit:
   - **Standard units (g, ml, oz, lb, kg, l):** preserve as-is, set `amountInGrams` directly (or via density for volumetric).
   - **Culinary volumetric (tbsp, tsp, cup):** convert to grams using standard culinary references. Conversion factor varies by ingredient (1 cup flour ≈ 125g, 1 cup butter ≈ 227g, 1 cup milk ≈ 240g).
   - **Yield-from-count inputs ('juice of 1 lemon', '2 medium onions', '1 medium egg'):** set `unit = "piece"`, `amount` = the count, and estimate `amountInGrams` from standard yields (1 lemon ≈ 47g juice; 1 medium onion ≈ 110g; 1 large egg ≈ 50g; 1 medium garlic clove ≈ 3g). Add a warning noting yield variability.
   - **Drained canned ingredients ('1 can chickpeas, drained'):** parse the *drained* weight, not gross. Convention: drained legumes/vegetables ≈ 55-60% of net (e.g. 15oz / 425g can chickpeas → ~240g drained); drained fruit ≈ 65-70% of net. The candidate description should indicate the drained variant — pick that.
   - **Non-numeric ('to taste', 'a pinch', 'a sprinkle'):** set `amount = null`, `unit = null`, `amountInGrams = null`, add a warning that the aggregator should treat this as user-controlled.
5. List up to 3 alternatives with reasons — sibling candidates the user might prefer if the recipe context were different (e.g. raw vs cooked, branded vs generic).

CONFIDENCE CALIBRATION (per [prompts/README.md](README.md) standard scale):
- 0.9-1.0: exact name match + correct preparation state + unit clearly parseable.
- 0.6-0.9: minor ambiguity (e.g. "olive oil" → could be salad oil or extra-virgin; both arguably correct; you picked one).
- 0.3-0.6: meaningful ambiguity (e.g. "chicken" with no part specified; the candidate is breast but could be thigh).
- 0.0-0.3: poor match; the alternatives might actually be better. Flag in warnings.

ALTERNATIVE-VS-PRIMARY SWAP RULE:
If an alternative would score higher confidence than your primary by more than 0.2, swap them — the alternative becomes primary, the original becomes the alternative. Otherwise, keep the primary and flag any tension in warnings.

WHAT NOT TO DO:
- Do not invent fdcIds. Only return ones present in the candidates list, OR null.
- Do not assume preparation state from cuisine alone — many recipes specify raw chicken regardless of cuisine.
- Do not return more than 3 alternatives. The user only acts on the top match.
- When the candidates list is empty: return `bestMatch.fdcId = null`, `bestMatch.matchedDescription = null`, `bestMatch.confidence = 0`. The `parsedQuantity` should still parse what's parseable (numbers and units), or all-null if the input is non-food.
```

The system prompt is ~900 tokens after the trial-driven additions (drained-weight rule, yield-from-count rule, alt-swap rule); the examples block totals ~1100 tokens (now 6 examples). Cached prefix ~2000 tokens. **This is below Haiku 4.5's 4096-token cache minimum** (per [README.md §Cache strategy](README.md)). Two paths to fix:
1. Pad the cached prefix with a stable preamble — TaskType banner, project identity, governance rules, expanded edge-case discussion. Cheap to write; pushes prefix to >4096.
2. Accept that caching won't activate. Per-call cost without cache: ~£0.002. Per-week cost without cache: ~£0.30 per active user. Acceptable but ~4× the cached estimate.

**Recommend: pad to >4096 tokens.** The additional preamble is documentation-flavoured (helps debugging) and the 4× cost saving compounds across the high-volume path. Specific padding content is a separate prompt-engineering decision; sketched in Decisions §8 below.

## User Prompt Template

```
[Task: NUTRITION_INGREDIENT_MAPPING]

<ingredient>
{{INGREDIENT_TEXT}}
</ingredient>

<recipe_context>
cuisine: {{RECIPE_CUISINE_OR_UNKNOWN}}
meal_type: {{RECIPE_MEAL_TYPE_OR_UNKNOWN}}
</recipe_context>

<candidates>
{{CANDIDATES_JSON_PRETTY}}
</candidates>

Match this ingredient to the best candidate using the rules above. If candidates list is empty, return null fdcId per the rules.
```

The `[Task: ...]` banner is the convention from [README.md §TaskType banner](README.md). Goes after the cached prefix; identifies the task in call-log audit.

`{{CANDIDATES_JSON_PRETTY}}` is the candidate list serialised as a numbered JSON array — one candidate per line for readability.

## Examples (in-prompt, wrapped in `<examples>` tags)

```
<examples>

<example>
<input>
<ingredient>2 tbsp olive oil</ingredient>
<recipe_context>cuisine: Italian, meal_type: dinner</recipe_context>
<candidates>
[
  {"fdcId":"171413","description":"Oil, olive, salad or cooking","foodCategory":"Fats and Oils"},
  {"fdcId":"748609","description":"Olive Oil, Extra Virgin","foodCategory":"Fats and Oils"},
  {"fdcId":"172336","description":"Oil, vegetable, household, soybean and soybean (partially hydrogenated)","foodCategory":"Fats and Oils"}
]
</candidates>
</input>
<output>
{
  "bestMatch": { "fdcId": "171413", "matchedDescription": "Oil, olive, salad or cooking", "confidence": 0.92 },
  "parsedQuantity": { "amount": 2, "unit": "tbsp", "amountInGrams": 27 },
  "alternatives": [
    { "fdcId": "748609", "reason": "Extra-virgin variant; pick this if the recipe specifies extra-virgin or finishing oil", "confidence": 0.75 }
  ],
  "warnings": []
}
</output>
</example>

<example>
<input>
<ingredient>1 jar (460g) Heinz Tomato Ketchup</ingredient>
<recipe_context>cuisine: British, meal_type: dinner</recipe_context>
<candidates>
[
  {"fdcId":"169873","description":"Ketchup","foodCategory":"Soups, Sauces, and Gravies"},
  {"fdcId":"175179","description":"Sauce, tomato, canned, with salt added","foodCategory":"Soups, Sauces, and Gravies"}
]
</candidates>
</input>
<output>
{
  "bestMatch": { "fdcId": "169873", "matchedDescription": "Ketchup", "confidence": 0.95 },
  "parsedQuantity": { "amount": 460, "unit": "g", "amountInGrams": 460 },
  "alternatives": [],
  "warnings": ["Brand 'Heinz' was stripped; mapping is to generic ketchup. Macros will be approximate."]
}
</output>
</example>

<example>
<input>
<ingredient>100g rocket</ingredient>
<recipe_context>cuisine: Italian, meal_type: lunch</recipe_context>
<candidates>
[
  {"fdcId":"169387","description":"Arugula, raw","foodCategory":"Vegetables and Vegetable Products"},
  {"fdcId":"168398","description":"Watercress, raw","foodCategory":"Vegetables and Vegetable Products"}
]
</candidates>
</input>
<output>
{
  "bestMatch": { "fdcId": "169387", "matchedDescription": "Arugula, raw", "confidence": 0.97 },
  "parsedQuantity": { "amount": 100, "unit": "g", "amountInGrams": 100 },
  "alternatives": [],
  "warnings": ["British English 'rocket' resolved to USDA 'Arugula'. Same plant."]
}
</output>
</example>

<example>
<input>
<ingredient>juice of 1 lemon</ingredient>
<recipe_context>cuisine: Mediterranean, meal_type: dinner</recipe_context>
<candidates>
[
  {"fdcId":"167747","description":"Lemon juice, raw","foodCategory":"Fruits and Fruit Juices"},
  {"fdcId":"169147","description":"Lemon juice, canned or bottled","foodCategory":"Fruits and Fruit Juices"}
]
</candidates>
</input>
<output>
{
  "bestMatch": { "fdcId": "167747", "matchedDescription": "Lemon juice, raw", "confidence": 0.9 },
  "parsedQuantity": { "amount": 1, "unit": "piece", "amountInGrams": 47 },
  "alternatives": [
    { "fdcId": "169147", "reason": "Bottled variant; pick if recipe specifies bottled lemon juice (slightly different acidity)", "confidence": 0.3 }
  ],
  "warnings": ["Yield estimated from 1 medium lemon (~47ml/47g). Actual yield varies 30-60ml depending on lemon size and ripeness."]
}
</output>
</example>

<example>
<input>
<ingredient>1 (15oz) can chickpeas, drained</ingredient>
<recipe_context>cuisine: Mediterranean, meal_type: dinner</recipe_context>
<candidates>
[
  {"fdcId":"173757","description":"Chickpeas (garbanzo beans, bengal gram), mature seeds, canned, drained, rinsed","foodCategory":"Legumes and Legume Products"},
  {"fdcId":"173756","description":"Chickpeas (garbanzo beans, bengal gram), mature seeds, canned, solids and liquids","foodCategory":"Legumes and Legume Products"},
  {"fdcId":"173759","description":"Chickpeas (garbanzo beans, bengal gram), mature seeds, raw","foodCategory":"Legumes and Legume Products"}
]
</candidates>
</input>
<output>
{
  "bestMatch": { "fdcId": "173757", "matchedDescription": "Chickpeas (garbanzo beans, bengal gram), mature seeds, canned, drained, rinsed", "confidence": 0.95 },
  "parsedQuantity": { "amount": 1, "unit": "can", "amountInGrams": 240 },
  "alternatives": [
    { "fdcId": "173756", "reason": "Solids-and-liquids variant; pick if recipe uses the aquafaba/canning liquid (~425g total)", "confidence": 0.3 }
  ],
  "warnings": ["Drained weight estimated as ~240g from a 15oz (425g net) can — drained legumes are typically 55-60% of net per industry convention."]
}
</output>
</example>

<example>
<input>
<ingredient>150g chicken breast</ingredient>
<recipe_context>cuisine: unknown, meal_type: unknown</recipe_context>
<candidates>
[
  {"fdcId":"171477","description":"Chicken, broiler or fryers, breast, skinless, boneless, meat only, raw","foodCategory":"Poultry Products"},
  {"fdcId":"171534","description":"Chicken, broiler or fryers, breast, skinless, boneless, meat only, cooked, roasted","foodCategory":"Poultry Products"},
  {"fdcId":"172847","description":"Chicken, broilers or fryers, breast, meat and skin, raw","foodCategory":"Poultry Products"}
]
</candidates>
</input>
<output>
{
  "bestMatch": { "fdcId": "171477", "matchedDescription": "Chicken, broiler or fryers, breast, skinless, boneless, meat only, raw", "confidence": 0.7 },
  "parsedQuantity": { "amount": 150, "unit": "g", "amountInGrams": 150 },
  "alternatives": [
    { "fdcId": "172847", "reason": "Skin-on variant; pick if recipe specifies skin-on or rotisserie style", "confidence": 0.4 },
    { "fdcId": "171534", "reason": "Already-cooked variant; pick if the recipe assumes pre-cooked chicken", "confidence": 0.3 }
  ],
  "warnings": ["Preparation state defaulted to raw (no cuisine or meal context to disambiguate)."]
}
</output>
</example>

<example>
<input>
<ingredient>salt to taste</ingredient>
<recipe_context>cuisine: any, meal_type: any</recipe_context>
<candidates>
[
  {"fdcId":"173468","description":"Salt, table","foodCategory":"Spices and Herbs"},
  {"fdcId":"168577","description":"Salt, table, iodized","foodCategory":"Spices and Herbs"}
]
</candidates>
</input>
<output>
{
  "bestMatch": { "fdcId": "173468", "matchedDescription": "Salt, table", "confidence": 0.95 },
  "parsedQuantity": { "amount": null, "unit": null, "amountInGrams": null },
  "alternatives": [],
  "warnings": ["Quantity is non-numeric ('to taste'); aggregator should treat sodium contribution as user-controlled."]
}
</output>
</example>

<example>
<input>
<ingredient>1 unicorn</ingredient>
<recipe_context>cuisine: unknown, meal_type: unknown</recipe_context>
<candidates>
[]
</candidates>
</input>
<output>
{
  "bestMatch": { "fdcId": null, "matchedDescription": null, "confidence": 0 },
  "parsedQuantity": { "amount": 1, "unit": "piece", "amountInGrams": null },
  "alternatives": [],
  "warnings": ["Ingredient 'unicorn' is not a recognised food. Candidate list is empty. Recommend manual mapping or rejecting the ingredient line entirely."]
}
</output>
</example>

</examples>
```

## Eval Set (regression)

Beyond the in-prompt examples, ~15 cases that exercise edge behaviour. Used by `NutritionUsdaMappingTaskTest` and during prompt-engineering iteration. Each case: input → expected output (or expected behaviour).

| # | Input | Expected | Tests |
|---|---|---|---|
| 1 | `"1 cup all-purpose flour"`, candidates include flour variants | `bestMatch` = plain flour, `amountInGrams ≈ 125` | Volumetric → grams conversion |
| 2 | `"2 large eggs"`, candidates: whole eggs / egg whites | whole egg fdcId, `amountInGrams ≈ 100` | "Large egg" canonical mass (~50g each) |
| 3 | `"a handful of basil"`, candidates: basil fresh/dried | fresh basil, `amount = null`, warning about handful | Vague quantity |
| 4 | `"500 ml whole milk"`, candidates: 2%/whole/skim | whole milk, `amountInGrams ≈ 515` | Volumetric → grams using density |
| 5 | `"1 (15oz) can chickpeas, drained"`, candidates: canned drained / canned solids+liquids / raw | drained variant, `amountInGrams ≈ 240` (15oz net × ~57% drained convention; not gross 425g) | Parenthetical mass + drained-weight inference |
| 6 | `"a few drops of vanilla extract"` | best match found, `amount = null`, warning | Sub-gram quantities |
| 7 | `"2 medium onions"`, candidates: onion raw/cooked | raw onion, `amountInGrams ≈ 220` (110g/medium) | "Medium" canonical mass |
| 8 | `"3 sprigs thyme"`, candidates: fresh thyme/dried | fresh thyme, `amount = null`, alternative for dried with reason | Aromatic herb measurement; dried alternative |
| 9 | `"1 lb ground beef (85/15)"` | ground beef 85% lean, `amountInGrams ≈ 454` | Lean ratio in description; lb conversion |
| 10 | `"100g extra-firm tofu"`, candidates: silken/firm/extra-firm | extra-firm if present, else firm with explanation | Sub-variant matching |
| 11 | `"juice of 1 lemon"` | lemon juice, `amount ≈ 47ml`, `amountInGrams ≈ 47`, warning about variability | Yield estimation from whole fruit |
| 12 | `"1 unicorn"` (silly nonsense), no real candidates returned by fuzzy search | `bestMatch.fdcId = null`, `confidence = 0`, warning "ingredient not recognised" | Hard-fail behaviour |
| 13 | `"1 cup cooked rice"`, candidates: long grain raw + cooked variants | cooked variant, `amountInGrams ≈ 158` | Preparation state in input overrides default |
| 14 | `"olive oil"` (no quantity at all) | best match, `amount = null`, warning "no quantity specified" | Missing quantity ≠ vague quantity |
| 15 | `"50g Cheddar"` (no qualifiers) + UK cuisine context | Cheddar (mature default), confidence 0.85 | Regional default expectation |
| 16 | `"2 servings of pasta"` (serving size unknown) | pasta dry, `amount = null`, warning "serving size unspecified" | Servings vs grams |
| 17 | `"Tesco British smoked streaky bacon, 250g pack"` + UK | brand-stripped to "bacon, smoked", grams 250 | Brand stripping; UK qualifier |
| 18 | `"a pinch of saffron"` | saffron, `amount = null`, warning | Spice with token-quantity unit |
| 19 | Two clear candidates with very similar fit (e.g. raw whole milk vs raw 3.25% milk) | Pick one with higher confidence (~0.7), other in alternatives | Ambiguity surfacing |
| 20 | `"chicken breast"` (no quantity, no candidates returned by search at all — empty list) | `bestMatch.fdcId = null`, warning "search returned no candidates"; pipeline retries with broadened query | Empty-candidates degradation |

The eval set lives in `src/test/resources/prompts/usda-mapping-eval.json` once implementation starts. Acceptance threshold: **18/20 cases must produce expected output** for prompt to ship; currently a target.

## Cost Analysis

| Metric | Estimate |
|---|---|
| Input tokens (after cache hit) | ~200-400 (per-call: ingredient + candidates + minimal context) |
| Cached input tokens | ~1500 (system prompt + examples + AiTask preamble) |
| Output tokens | ~200-400 |
| Cost per call (Haiku 4.5 with cache hit) | **~£0.0005** |
| Cost per call (cold cache) | ~£0.002 |
| Calls per recipe save | ~30 (one per ingredient line) |
| Recipes per active user per week | ~5 |
| **Cost per user per week** | **~£0.075** |
| Cache hit rate target | >85% (cache TTL 5 min; bulk recipe imports during cold start are within window) |

For cold-start (first-time user importing 50 recipes): ~1500 calls × £0.0005 = ~£0.75 once-off.

## Failure Modes

| Failure | Detection | Behaviour |
|---|---|---|
| Model returns invalid `fdcId` not in candidates | Validator checks all returned IDs against the input candidate list | Reject; retry once with corrective re-prompt; if still bad, treat as `confidence = 0` and persist with `mapping_status = 'failed'` |
| Quantity unit is unrecognised | Code-side unit-parser fallback after model returns | If unit is unrecognisable, log WARN and store `amountInGrams = null`; nutrition aggregator skips this ingredient with a flag |
| Confidence < 0.5 | Validator | Persist with `mapping_status = 'low_confidence'`; surface in UI for user review on next pantry/recipe view |
| Candidates list was empty (search returned nothing) | Pipeline-side check before AI call | Skip AI call entirely; broaden the fuzzy-search query and retry; if still empty, mark `mapping_status = 'unmapped'` |
| Output shape malformed (tool-use validation fails) | AI dispatcher | Retry once with corrective re-prompt; if still malformed, terminal failure |
| `AiUnavailable` (cost cap) | AI dispatcher | Persist row with `mapping_status = 'pending'`, async retry tomorrow when cap resets |

## AiTask Skeleton

```java
package com.example.mealprep.nutrition.domain.service.internal;

import com.example.mealprep.ai.spi.*;
import java.time.Duration;
import java.util.*;

public final class NutritionUsdaMappingTask implements AiTask<IngredientMappingResponse> {

    private final String ingredientText;
    private final String recipeCuisine;        // nullable
    private final String recipeMealType;       // nullable
    private final List<UsdaCandidateDto> candidates;
    private final UUID userId;
    private final UUID traceId;

    public NutritionUsdaMappingTask(
            String ingredientText, String recipeCuisine, String recipeMealType,
            List<UsdaCandidateDto> candidates, UUID userId, UUID traceId) {
        this.ingredientText = ingredientText;
        this.recipeCuisine = recipeCuisine;
        this.recipeMealType = recipeMealType;
        this.candidates = List.copyOf(candidates);
        this.userId = userId;
        this.traceId = traceId;
    }

    @Override public TaskType getTaskType() { return TaskType.NUTRITION_INGREDIENT_MAPPING; }
    @Override public String getSystemPrompt() { return SYSTEM_PROMPT; }   // loaded from prompts/nutrition/usda-mapping-system.txt
    @Override public PromptRef getUserPromptRef() {
        return new PromptRef("nutrition/usda-mapping-user", Optional.empty());
    }
    @Override public Map<String, Object> getContext() {
        return Map.of(
            "ingredient_text",  ingredientText,
            "recipe_cuisine",   recipeCuisine == null ? "unknown" : recipeCuisine,
            "recipe_meal_type", recipeMealType == null ? "unknown" : recipeMealType,
            "candidates",       candidates
        );
    }
    @Override public ToolDefinition getToolSchema() {
        return ToolDefinitionBuilder.fromRecord(IngredientMappingResponse.class)
            .name("report_ingredient_mapping")
            .description("Report the best USDA match and parsed quantity for the ingredient.")
            .build();
    }
    @Override public Class<IngredientMappingResponse> getResponseType() { return IngredientMappingResponse.class; }
    @Override public UUID getUserId() { return userId; }
    @Override public UUID getTraceId() { return traceId; }
    @Override public Optional<Duration> getTimeoutOverride() { return Optional.of(Duration.ofSeconds(8)); }
}
```

## Decisions made (worth user review)

1. **One AI call combining parse + match**, vs the nutrition LLD's two-step flow. Halves cost; retains correct behaviour because both steps share the same context. Update nutrition.md `IngredientMappingPipeline` description to reflect single-call.
2. **Top-10 candidates** from fuzzy search, not 5 or 20. 5 misses too often; 20 wastes tokens on clearly-wrong matches.
3. **Confidence < 0.5 persists with `low_confidence` status, not pending or failed.** User can inspect and correct; nutrition aggregator uses the mapping but flags the recipe.
4. **Prep state defaults to raw** when ambiguous — most recipes specify raw ingredients and cook them. Cuisine is tiebreaker only.
5. **Volumetric → grams conversion** is the model's responsibility, not deterministic code. Conversion factors vary by ingredient (1 cup of flour ≠ 1 cup of butter); the model handles this in context. Code validates the result is sensible (within 50%-200% of expected for the unit).
6. **Brand stripping** is the model's responsibility — instructed in the system prompt. Validates the warning lists the stripped brand.
7. **Eval set acceptance threshold: 18/20**. Aggressive but achievable.
8. **Cache-prefix padding to clear Haiku 4.5's 4096-token minimum** — current ~2000 tokens needs ~2000 more of stable content. Padding candidates: TaskType banner, project preamble, expanded edge-case discussion, glossary of culinary unit conventions. Decision deferred to first iteration; if uncached cost (~£0.30/wk/user) is acceptable, skip padding.
9. **Drained-weight convention baked into the system prompt** — 55-60% of net for legumes/vegetables, 65-70% for fruit. Industry standard. Caught during the trial — eval entry 5 originally said 425g (gross), corrected to 240g (drained).
10. **Yield-from-count rule** — explicit handling for "juice of 1 lemon", "2 medium onions", "1 large egg" with named yields. Caught during the trial; example added.
11. **Unit field is enum, not free-form string** — whitelisted via the `Unit` enum in the response record. Caught during the trial.
12. **Alternative-vs-primary swap rule (>0.2 confidence delta)** — promoted to a cross-cutting convention in [README.md](README.md). Avoids ambiguity about whether to swap or just warn.
13. **Explicit no-match example added** — covers the empty-candidates case showing exact null population for fdcId, matchedDescription, and parsedQuantity. Was previously underspecified.

## Trial history

**v1 trial (2026-05-07)** — agent run on 6 cases. Surfaced: drained-weight bug (eval entry 5 wrong), yield-from-count gap (no example), null-output shape ambiguity (Case 6), unit value-space unbounded (Case 5/6 mix), alt-vs-primary rule missing. All 5 issues resolved in the v2 update — cross-cutting conventions promoted to [README.md](README.md), task-specific fixes inline. Re-trial pending.
