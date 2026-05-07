# Prompt — Free-text Intake Parsing

*Parses a user's free-text description of what they actually ate into structured `(food, quantity, unit)` items, optionally anchored against a planned meal slot. Cheap-tier. Low-volume override path.*

Cross-cutting conventions (confidence calibration, null-population, edge-case examples, enum whitelisting, cache strategy, TaskType banner) defer to [README.md](README.md) — this doc only restates them where the application is task-specific. The `Unit` enum and unit / yield-from-count handling rules are reused verbatim from [02-usda-ingredient-mapping.md](02-usda-ingredient-mapping.md).

## Wiring

| | |
|---|---|
| AiTask name | `IntakeOverrideParserTask` |
| TaskType | `NUTRITION_INTAKE_PARSE` |
| Tier | Haiku 4.5 (cheap) |
| Module | `nutrition` |
| Called by | `NutritionService.overrideIntakeFromFreeText` ([nutrition.md §Flow 4](../nutrition.md#flow-4-free-text-intake-override-ai-parsing-path)) |
| Input prep | Service captures verbatim free-text on the slot first, then calls this task |
| Downstream | Each parsed item fed to `IngredientMappingPipeline.resolve(line)` (invokes prompt #2 per item) for USDA mappings + macros |
| Failure path | `unparseable` → slot `OVERRIDDEN`, zero actuals, verbatim preserved, UI banner invites manual edit; `AiUnavailable` → `IntakeOverrideParseException` (422) |
| Cache | System + examples (~2k tokens) cached `ephemeral` (5-min TTL); per-call free-text + planned context vary |
| Cost | ~£0.0005/call cached; ~3 overrides/wk/user — trivial (~£0.0015/wk/user) |

## Purpose

Parses **what the user actually ate** when they reject the plan-as-cooked confirmation flow and type free-text instead ("had a banana and yoghurt for breakfast"; "actually I had pasta not rice"; "skipped lunch, just had crisps"). Output is food-shaped `(description, quantity, unit, estimatedGrams, preparationState)` tuples plus user notes — **not** USDA-shaped; the per-item USDA mapping is the next pipeline step (prompt #2).

Two responsibilities: (1) extract food items — split compound utterances, normalise slang, expand implicit portion words, preserve quantity nulls when genuinely vague; (2) anchor against the planned slot when supplied — distinguish *correction* ("actually I had X instead"), *confirmation* ("had X" matching plan), *partial swap* ("pasta with tofu instead of chicken").

NOT in scope: USDA matching (prompt #2), gram-accurate nutrition (downstream code), multi-slot classification (bounded by `meal_kind_hint`, see Decisions §5).

## Inputs / Outputs

**Inputs (passed via `AiTask.getContext()`):**

```java
Map.of(
    "free_text",            String,                          // raw user text, e.g. "had a small banana"
    "planned_slot_context", PlannedSlotContextDto,           // nullable — present iff overriding a planned slot
    "meal_kind_hint",       String,                          // "breakfast" | "lunch" | "dinner" | "snack"; nullable
    "time_eaten",           Instant                          // nullable
)

record PlannedSlotContextDto(
    String recipeName,                                       // "Tomato Pasta with Chicken"
    String mealKind,                                         // "lunch"
    List<PlannedItemDto> plannedItems                        // the recipe's resolved ingredient lines
) {}

record PlannedItemDto(String foodDescription, BigDecimal quantity, Unit unit) {}
```

**Output (structured tool-use):**

```java
record IntakeOverride(
    List<ParsedIntakeItem> items,                            // never null, [] when truly empty (e.g. skipped meal)
    String userNotes,                                        // anything not an item ("felt sluggish after"); empty string never null
    InterpretationConfidence confidence,                     // HIGH | MEDIUM | LOW
    List<String> warnings                                    // never null, [] when empty
) {}

record ParsedIntakeItem(
    String foodDescription,                                  // "banana", "Greek yoghurt"; never null
    BigDecimal quantity,                                     // nullable when non-numeric ("a portion")
    Unit unit,                                               // enum reused from prompt #2; nullable when truly unknown
    BigDecimal estimatedGrams,                               // best-guess grams; null iff quantity is null AND no portion default applied
    String preparationState                                  // "raw" | "cooked" | "fried" | …; null if unknown
) {}

enum InterpretationConfidence { HIGH, MEDIUM, LOW }
```

`Unit` is the enum from [02-usda-ingredient-mapping.md §Inputs / Outputs](02-usda-ingredient-mapping.md#inputs--outputs) — reused, not redefined. The downstream `NutritionUsdaMappingTask` expects the same value space. `InterpretationConfidence` is a 3-bucket enum, **not** the 0-1 numeric scale (see Decisions §7).

## System Prompt

```
You are a dietary-log parser. The user has typed a free-text description of what they actually ate, typically as a correction to a planned meal. Extract structured food items and any side-notes.

Your output is consumed by a downstream pipeline that maps each item to USDA FoodData Central (a separate prompt) and aggregates nutrition. You parse; you do not match. Never invent USDA IDs or guess at exact macros.

INPUTS: free-text, planned slot context (nullable — recipe name, meal kind, planned ingredients), meal kind hint (nullable: breakfast / lunch / dinner / snack), time eaten (nullable).

WHAT TO DO:

1. **Split the text into items.** Multiple foods in one sentence ("a banana and yoghurt") = multiple ParsedIntakeItems. Side-notes ("felt sluggish after") go into userNotes, not items.

2. **Parse quantity and unit per item** using the same conventions as the USDA-mapping prompt:
   - Standard units (g, ml, oz, lb): preserve, set estimatedGrams directly.
   - Volumetric (tbsp, tsp, cup): convert via standard culinary references (1 cup yoghurt ≈ 245g; 1 cup rice cooked ≈ 158g).
   - Yield-from-count ("a banana", "an egg", "two slices of bread"): unit = piece, quantity = count, estimatedGrams from standard yields (1 medium banana ≈ 118g; 1 large egg ≈ 50g; 1 slice bread ≈ 30g; 1 medium apple ≈ 180g).
   - Non-numeric ("a portion", "a bit", "some"): apply the portion-default table; quantity = null only if no default applies.

3. **Apply the size / portion default table** — and add a warning naming what was assumed:
   - "small" / "medium" / "large" → 0.7 / 1.0 / 1.3 multiplier on standard yield (small banana ≈ 90g; large banana ≈ 153g)
   - "a portion" / "one serving" → 1 USDA standard serving for that food (~150g yoghurt, ~80g cooked rice, ~120g cooked pasta)
   - "a bit" / "a little" → ~50% of one standard portion
   - "loads of" / "lots of" → ~150% of one standard portion; warn about coarseness

4. **Identify preparation state** when the user states it ("fried", "boiled", "raw"). Default null if unspecified — eaten-as-is foods often don't need it.

5. **Anchor against planned context** when supplied:
   - "had X" matching a planned item with no qualifier → CONFIRMATION; emit planned items unchanged with HIGH confidence and a note that the override matched the plan.
   - "actually I had Y not X" / "Y instead of X" → swap the named planned item for the user-named item; preserve other planned items.
   - User describes something completely different → discard planned items, parse user text alone.

6. **Negation and skip:**
   - "Skipped" / "didn't eat anything" → items = [], userNotes = the verbatim skip reason, confidence HIGH.
   - "Didn't have X" with a positive claim following ("didn't have the salad, just the soup") → emit the positive claim only.
   - Pure negation with no positive claim → items = [], userNotes captures the negation, confidence HIGH.

7. **Multiple meals in one text:** if meal_kind_hint supplied, restrict output to that slot and warn about discarded content; if no hint, take the FIRST meal mentioned and warn.

CONFIDENCE BUCKETS:
- **HIGH**: text is unambiguous; quantities are explicit OR portion defaults map cleanly; planned-context anchoring (if any) is unambiguous; the user named foods that exist in any reasonable knowledge.
- **MEDIUM**: at least one item required a vague-quantity default (small/medium/large or "a portion"); planned-context anchoring required interpretation; one item's identity is mildly uncertain.
- **LOW**: text is sparse, contradictory, or contains foods that aren't recognisable; multiple defaults applied; user retracted mid-sentence ("actually no, just the soup"); calling code should surface a "are you sure this is right?" UI prompt.

WHAT NOT TO DO:
- Do not return null for `items` — empty list `[]` is the explicit "nothing eaten" signal.
- Do not invent foods the user didn't mention ("had something for breakfast" → items = [], userNotes = verbatim, confidence LOW).
- Do not attempt USDA matching or guess macros — estimatedGrams is the only quantitative field you populate.
- Do not include side-notes or feelings as foods. "felt sluggish" → userNotes.
- Do not exceed 8 items; if the user genuinely lists more, parse the first 8 and warn.
```

System prompt ~950 tokens + examples ~1100 tokens = cached prefix ~2050 — **below Haiku 4.5's 4096-minimum** (per [README.md §Cache strategy](README.md)). Recommendation: **do not pad**; accept uncached. At ~3 calls/wk/user, uncached cost ~£0.006/wk/user is far below the threshold where padding pays back (compare prompt #2's ~30× call rate).

## User Prompt Template

```
[Task: NUTRITION_INTAKE_PARSE]

<free_text>
{{FREE_TEXT_VERBATIM}}
</free_text>

<planned_slot_context>
{{PLANNED_SLOT_CONTEXT_JSON_OR_NONE}}
</planned_slot_context>

<meal_kind_hint>{{MEAL_KIND_OR_UNKNOWN}}</meal_kind_hint>
<time_eaten>{{TIME_EATEN_ISO_OR_UNKNOWN}}</time_eaten>

Parse the free-text into structured intake items per the rules above. If planned context is present, treat it as a heavyweight tiebreaker per rule 5.
```

The `[Task: ...]` banner appears AFTER the cached prefix per [README.md §TaskType banner](README.md). `{{PLANNED_SLOT_CONTEXT_JSON_OR_NONE}}` is the planned slot as pretty JSON, or the literal `none`. `{{MEAL_KIND_OR_UNKNOWN}}` defaults to `unknown`.

## Examples (in-prompt, wrapped in `<examples>` tags)

```
<examples>

<example>
<input>
<free_text>had a small banana and yoghurt for breakfast</free_text>
<planned_slot_context>none</planned_slot_context>
<meal_kind_hint>breakfast</meal_kind_hint>
<time_eaten>unknown</time_eaten>
</input>
<output>
{
  "items": [
    {"foodDescription":"banana","quantity":1,"unit":"piece","estimatedGrams":90,"preparationState":"raw"},
    {"foodDescription":"yoghurt","quantity":1,"unit":"piece","estimatedGrams":150,"preparationState":null}
  ],
  "userNotes": "",
  "confidence": "MEDIUM",
  "warnings": [
    "Banana size 'small' applied 0.7 multiplier on the 118g standard yield (≈ 90g).",
    "Yoghurt quantity defaulted to 1 USDA standard serving (~150g); user did not specify."
  ]
}
</output>
</example>

<example>
<input>
<free_text>actually I had pasta with tofu instead</free_text>
<planned_slot_context>{"recipeName":"Tomato Pasta with Chicken","mealKind":"lunch","plannedItems":[{"foodDescription":"pasta, dry","quantity":100,"unit":"g"},{"foodDescription":"chicken breast","quantity":150,"unit":"g"},{"foodDescription":"tomato sauce","quantity":200,"unit":"g"}]}</planned_slot_context>
<meal_kind_hint>lunch</meal_kind_hint>
<time_eaten>unknown</time_eaten>
</input>
<output>
{
  "items": [
    {"foodDescription":"pasta, dry","quantity":100,"unit":"g","estimatedGrams":100,"preparationState":null},
    {"foodDescription":"tofu","quantity":150,"unit":"g","estimatedGrams":150,"preparationState":null},
    {"foodDescription":"tomato sauce","quantity":200,"unit":"g","estimatedGrams":200,"preparationState":null}
  ],
  "userNotes": "",
  "confidence": "HIGH",
  "warnings": ["Swapped planned 'chicken breast' for user-named 'tofu'; quantity carried over from planned chicken (150g) as no user quantity was given."]
}
</output>
</example>

<example>
<input>
<free_text>skipped breakfast, had a sandwich for lunch and pizza for dinner</free_text>
<planned_slot_context>none</planned_slot_context>
<meal_kind_hint>lunch</meal_kind_hint>
<time_eaten>unknown</time_eaten>
</input>
<output>
{
  "items": [{"foodDescription":"sandwich","quantity":1,"unit":"piece","estimatedGrams":200,"preparationState":null}],
  "userNotes": "",
  "confidence": "MEDIUM",
  "warnings": [
    "Discarded breakfast skip and dinner pizza — meal_kind_hint = lunch restricted output to that slot.",
    "Sandwich quantity defaulted to 1 standard sandwich (~200g); composition unspecified — downstream USDA mapping picks a generic sandwich category."
  ]
}
</output>
</example>

<example>
<input>
<free_text>had toast with peanut butter</free_text>
<planned_slot_context>none</planned_slot_context>
<meal_kind_hint>breakfast</meal_kind_hint>
<time_eaten>unknown</time_eaten>
</input>
<output>
{
  "items": [
    {"foodDescription":"bread, toasted","quantity":2,"unit":"piece","estimatedGrams":60,"preparationState":"toasted"},
    {"foodDescription":"peanut butter","quantity":null,"unit":"tbsp","estimatedGrams":15,"preparationState":null}
  ],
  "userNotes": "",
  "confidence": "MEDIUM",
  "warnings": [
    "Toast count defaulted to 2 slices (typical breakfast portion).",
    "Peanut butter quantity defaulted to ~1 tbsp (15g) as no spread amount was specified."
  ]
}
</output>
</example>

<example>
<input>
<free_text>skipped lunch, wasn't hungry</free_text>
<planned_slot_context>{"recipeName":"Chicken Salad","mealKind":"lunch","plannedItems":[{"foodDescription":"chicken breast","quantity":120,"unit":"g"},{"foodDescription":"mixed salad","quantity":100,"unit":"g"}]}</planned_slot_context>
<meal_kind_hint>lunch</meal_kind_hint>
<time_eaten>unknown</time_eaten>
</input>
<output>
{ "items": [], "userNotes": "skipped lunch, wasn't hungry", "confidence": "HIGH", "warnings": [] }
</output>
</example>

</examples>
```

## Eval Set (regression)

~16 cases beyond the in-prompt examples, used by `IntakeOverrideParserTaskTest` and during prompt iteration.

| # | Input | Expected | Tests |
|---|---|---|---|
| 1 | `"had a small banana and yoghurt for breakfast"`, hint=breakfast | banana 90g + yoghurt 150g, MEDIUM, 2 size-default warnings | Vague defaults, multi-item (Ex. 1) |
| 2 | `"actually I had pasta with tofu instead"`, planned = pasta+chicken+sauce, hint=lunch | swap chicken→tofu, keep pasta+sauce, HIGH | Partial swap (Ex. 2) |
| 3 | `"skipped breakfast, had a sandwich for lunch and pizza for dinner"`, hint=lunch | sandwich only, warning, MEDIUM | Multi-meal restricted by hint (Ex. 3) |
| 4 | `"had toast with peanut butter"`, hint=breakfast | bread × 2 + peanut butter ~15g, MEDIUM | Slang / informal (Ex. 4) |
| 5 | `"skipped lunch"`, hint=lunch | items = [], userNotes = "skipped lunch", HIGH | Skipped meal (Ex. 5) |
| 6 | `"didn't have the salad, just the soup"`, planned = salad+soup, hint=lunch | soup only, HIGH, warn about dropped salad | Negation with positive claim |
| 7 | `"I cheated and had cake"`, hint=snack | cake 1 slice (~80g), MEDIUM, warning | Apologetic — strip emotion into userNotes |
| 8 | `"had half the bowl"`, planned = pasta-bowl 350g, hint=dinner | pasta 175g (50% of planned), HIGH, warning | Portion-relative ("half") |
| 9 | `"actually no, just the soup"`, planned = soup+bread+cheese, hint=lunch | soup only, MEDIUM, retraction warning | Multi-attempt / retraction |
| 10 | `""` (empty) | items = [], userNotes = "", warning, LOW | Empty input |
| 11 | `"the app crashed"` | items = [], userNotes = "the app crashed", LOW, warning "no food content" | Non-food text |
| 12 | `"had bog snorkelling stew"` | items = [{foodDescription: "bog snorkelling stew", …}], LOW, warning | Unknown food best-effort |
| 13 | `"had loads of pasta"`, hint=dinner | pasta ~180g (150% × 120g), MEDIUM, coarseness warning | Intensifier default |
| 14 | `"had X"` where X = full planned recipe verbatim, planned = pasta+chicken+sauce, hint=lunch | items = planned unchanged, HIGH, warning "matched planned slot — recommend confirm-from-plan" | Confirmation-disguised-as-override |
| 15 | `"had pasta with chicken and felt really sluggish after"`, planned = pasta+chicken, hint=lunch | items = planned, userNotes = "felt really sluggish after", HIGH | Side-notes into userNotes |
| 16 | `"had 5 slices toast, two eggs fried, three rashers bacon, beans, mushrooms, tomato, black pudding"`, hint=breakfast | first 8 parsed, truncation warning | Item-count cap |

The eval set lives in `src/test/resources/prompts/intake-parse-eval.json` once implementation starts. Acceptance threshold: **14/16 cases must produce expected output** (per the README.md 90%-ish bar; one of the 2 allowed misses is reserved for case 12 where "best effort" is genuinely subjective).

## Cost Analysis

| Metric | Estimate |
|---|---|
| Input tokens (per call, uncached) | ~200-500 (free_text + planned context + system prompt) |
| Cached input tokens | ~2050 — **below Haiku 4.5's 4096-minimum**; cache won't activate without padding |
| Output tokens | ~200-400 |
| Cost per call (uncached, expected) | ~£0.002 |
| Calls per active user per week | ~3 (overrides only) |
| **Cost per user per week (uncached)** | **~£0.006** |

For a 1k-user beta the uncached bill is ~£6/wk — well below the threshold where cache padding pays back. **Decision: do not pad.** See Decisions §8.

## Failure Modes

| Failure | Detection | Behaviour |
|---|---|---|
| `free_text` empty / whitespace-only | Service-side pre-check | Skip AI; persist slot with `actualStatus = OVERRIDDEN`, zero actuals; UI banner "what did you eat?" |
| `free_text` is non-food ("the app crashed") | Model returns `items = []`, LOW + warning | Slot `OVERRIDDEN` with zero actuals, verbatim preserved; UI banner "couldn't find food content — manual edit?" |
| Items contain foods not in USDA knowledge | Downstream `IngredientMappingPipeline` returns no candidates | Per-item `mapping_status = 'unmapped'`; slot actuals reflect only the mapped subset |
| Output items > 8 | Validator | Truncate to 8; log warning; persist truncation flag in audit |
| Confidence = LOW | Validator | Slot persists; UI shows "are you sure this is right?" prompt |
| Tool-use validation fails | AI dispatcher | Retry once; terminal failure → `IntakeOverrideParseException` (422); slot stays pre-override, verbatim preserved |
| `AiUnavailable` (cost cap or provider down) | AI dispatcher | `IntakeOverrideParseException` (422); slot persists with verbatim only, `OVERRIDDEN`, zero actuals |
| Output hallucinates planned items not in `planned_slot_context.plannedItems` | Validator | Reject; retry once; if still bad, treat as plan-discarded parse |

## AiTask Skeleton

Follows the canonical shape from [02-usda-ingredient-mapping.md §AiTask Skeleton](02-usda-ingredient-mapping.md#aitask-skeleton). Variations:

- Fields: `freeText`, `plannedSlotContext` (nullable), `mealKindHint` (nullable), `timeEaten` (nullable), `userId`, `traceId`.
- `getTaskType()` → `TaskType.NUTRITION_INTAKE_PARSE`.
- `getUserPromptRef()` → `new PromptRef("nutrition/intake-parse-user", Optional.empty())`.
- `getContext()` populates the four input keys; nulls become `"unknown"` for `mealKindHint` and `timeEaten`; `plannedSlotContext` passed through (renderer emits `"none"` for null per its null-policy).
- `getToolSchema()` → `ToolDefinitionBuilder.fromRecord(IntakeOverride.class)` with name `"report_intake_override"`.
- `getResponseType()` → `IntakeOverride.class`.
- `getTimeoutOverride()` → `Optional.of(Duration.ofSeconds(6))` (6s; expected sub-2s typical).

## Integration with `IntakeSlot`

Per [nutrition.md §Flow 4](../nutrition.md#flow-4-free-text-intake-override-ai-parsing-path), the calling service: (1) stores `freeText` verbatim into `IntakeSlot.overrideFreeText` BEFORE invoking this task (Decision §6); (2) builds the task with `planned_slot_context` from the slot's planned columns (or null); (3) on `parsed` outcome feeds each `ParsedIntakeItem` to `IngredientMappingPipeline.resolve(line)` (one prompt #2 call per item), sums macros into the slot's `actual_*` columns, sets `actual_status = OVERRIDDEN`; (4) on `unparseable` (LOW + empty items): leaves actuals zero, `OVERRIDDEN`, verbatim preserved; (5) audits, runs divergence detection, publishes `IntakeLoggedEvent(action=OVERRIDE)` after commit.

The `userNotes` field is **not** auto-persisted — surfaced in the UI for the user to opt into saving as a `FoodMoodJournalEntry`. Avoids silently mixing AI-interpreted nuance with user-curated journal content.

## Decisions made (worth user review)

1. **Output items are food-shaped, not USDA-shaped** — this prompt parses; prompt #2 (`NutritionUsdaMappingTask`) maps each item to FDC. Keeps each prompt small and reuses prompt #2's mature unit handling.
2. **Vague-quantity defaults explicit in the system prompt** — `small/medium/large` 0.7/1.0/1.3 multipliers; "a portion" → 1 USDA serving; "a bit" → 50%; "loads of" → 150%. Prevents inconsistent defaults across calls; each applied default raises a warning.
3. **Planned context as heavyweight tiebreaker** — "had X" matching a planned item with no qualifier is a confirmation; "actually" / "instead" / "not" is a correction (rule 5). Eval case 14 covers the confirmation-disguised-as-override case.
4. **Negation produces empty items, not null** — per [README.md §Null-population rules](README.md), `items = []` is the explicit "nothing eaten" signal; `null` would be ambiguous between "skipped" and "parsing failed".
5. **Multiple meals restricted by `meal_kind_hint`** — matches the calling API `overrideIntakeFromFreeText(mealSlot, freeText)`. Without a hint, take the first meal; always warn about discarded content.
6. **Verbatim free-text stored BEFORE this prompt runs** — locked in [nutrition.md §Flow 4](../nutrition.md#flow-4-free-text-intake-override-ai-parsing-path) ("Store the verbatim `freeText` into `IntakeSlot.overrideFreeText` immediately — preserved even if AI parsing fails"). The verbatim is the truth; the parsed structure is a derived view.
7. **Confidence is a 3-bucket enum (HIGH / MEDIUM / LOW), not 0-1 numeric** — three buckets match the rough qualitative nature of free-text parsing. Prompt #2's 0-1 scale maps to continuous candidate tie-break; this task has no analogous tie-break — it's "did we get the gist right?". LOW triggers a "are you sure this is right?" UI prompt. The README's 0-1 scale stays the default; this prompt is the flagged exception.
8. **Cache-prefix padding skipped** — at ~3 calls/wk/user, uncached overhead is ~£0.006/wk/user; padding saves ~£0.0045/wk/user, not worth the bloat. Prompt #2 (~£0.30/wk/user uncached) is where padding pays back.
9. **`userNotes` not auto-persisted** — surfaced in UI for user to opt into saving as a `FoodMoodJournalEntry`. Prevents AI nuance leaking into the journal track silently.
10. **Item-count cap of 8** — defends against pathological inputs. Eval case 16 covers it; truncation flagged in warnings and audit.
