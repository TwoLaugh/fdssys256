# Prompt — Planner Phase 2 (Creative Augmentation)

*Frontier-tier creative gap-fill. After Stage C picks the winning plan, this prompt fills remaining gaps with snack additions, ingredient swaps via refine-directives, or side-pairing rearrangements.*

Cross-cutting conventions defer to [README.md](README.md).

## Wiring

| | |
|---|---|
| AiTask name | `Phase2AugmentationTask` |
| TaskType | `PLAN_AUGMENTATION` |
| Tier | Opus 4.7 (frontier), `effort = high` |
| Module | `planner` |
| Called by | `Phase2Augmenter` after Stage C produces the chosen candidate |
| Failure path | `AiUnavailable` → plan ships without augmentation, flagged `aiAugmented = false` |
| Cache | System prompt + examples + constraint preamble cached; per-call plan + gap analysis vary |
| Cost | ~£0.15-0.40/call; weekly per active user |

## Purpose

After Stage C picks the winning plan from N=5, gaps remain — daily protein floors not quite hit, cost projection over budget, a Friday slot with no obvious side dish, slots scheduling near-identical recipes adjacent to each other. Phase 2's job is to **propose plan-level augmentations** that close gaps without rewriting the plan. The hard-filter then validates every augmentation post-hoc — the model's job is creative, the filter's job is safety.

Three operations are permitted:

- **Add** a snack, side, or drink to a slot (or alongside one). Doesn't modify any recipe; adds an ad-hoc plan-level item with its own nutrition contribution.
- **Swap** an ingredient within a scheduled recipe via a refine-directive to the Recipe Optimiser (becomes a SUBSTITUTION at the recipe-system level; doesn't modify the canonical recipe in the user's catalogue).
- **Rearrange** which side accompanies which main, when the deterministic search assigned them suboptimally.

This is **not**:
- Stage A composition (already done; trust the plan)
- Stage C selection (already done; the chosen plan is fixed)
- Recipe-level adaptation (use the Recipe Adaptation prompt #5 for that, invoked via Stage D refine-directive — Phase 2 may emit such a directive but doesn't perform the adaptation itself)
- Hard-constraint enforcement (deterministic filter does that on every Phase 2 output)

## Inputs / Outputs

**Inputs (`AiTask.getContext()`):**

```java
Map.of(
    "chosen_plan",        ChosenPlanFullDto,             // full plan structure (~5k tokens)
    "gap_analysis",       GapAnalysisDto,                 // structured "what's not quite right"
    "constraints_summary", ConstraintSummaryDto,
    "household_size",     int,
    "trace_id",           UUID
)

record ChosenPlanFullDto(
    UUID planId, LocalDate weekStart,
    List<DayWithSlots> days,                              // 7 days × meal slots × scheduled recipe + nutrition + cost
    WeeklyAggregateDto weeklyAggregate
) {}

record GapAnalysisDto(
    List<NutritionGap> nutritionGaps,                     // e.g. "Wednesday protein floor 180g, plan provides 158g"
    List<CostConcern> costConcerns,                       // e.g. "weekly projection £52 vs target £50; £2 over"
    List<VarietyConcern> varietyConcerns,                 // e.g. "Tuesday and Wednesday both feature chicken"
    List<SlotConcern> slotConcerns                        // e.g. "Friday dinner has no carb side; lifestyle config flags Friday as treat day"
) {}
```

**Output:**

```java
record Phase2AugmentationsResponse(
    List<Addition> additions,              // 0-5; total additions+swaps+rearrangements ≤5
    List<IngredientSwapDirective> swaps,   // 0-2; routed to Recipe Optimiser as Trigger 4
    List<SideRearrangement> rearrangements,// 0-3; in-plan slot reorderings
    String overallReasoning,
    List<String> warnings
) {}

record Addition(
    LocalDate day,
    SlotKind targetSlot,                                  // BREAKFAST | LUNCH | DINNER | SNACK | (custom)
    String addedItemName,                                 // e.g. "Greek yoghurt with honey"
    String mappingKeyHint,                                 // best-guess ingredient_mapping_key for nutrition lookup
    BigDecimal estimatedQuantity, Unit unit,
    String reasoning
) {}

record IngredientSwapDirective(
    UUID recipeId,
    LocalDate day,
    SlotKind slot,
    String originalIngredient,
    String proposedSwap,
    String reasoning
) {}

record SideRearrangement(
    LocalDate dayA, SlotKind slotA,
    LocalDate dayB, SlotKind slotB,
    String reasoning
) {}
```

## System Prompt

```
You are creatively augmenting a chosen weekly meal plan to close remaining gaps. The deterministic search and the LLM picker have done their work; you now have one chosen plan and a list of gaps the search couldn't fully close. Your job: propose at most 5 plan-level interventions that improve the plan against the gaps.

YOU CAN DO THREE THINGS:
1. ADD a snack, side, or drink to a slot. The added item is plan-level — it doesn't change any recipe; it just appears alongside whatever's scheduled. Use to close nutrition gaps (yoghurt for protein, fruit for fibre, salad for variety) or fill empty slots.
2. SWAP an ingredient within a scheduled recipe via a refine-directive. The directive gets routed to the recipe adaptation pipeline; the user catalogue's canonical recipe is not modified — the swap is a SUBSTITUTION attached to that plan slot. Use for cost savings or constraint accommodation when adding isn't enough.
3. REARRANGE sides between slots — move the salad from Tuesday to Friday because Friday's main is heavier, or pair the cottage pie on Sunday with the greens that were inexplicably scheduled for Monday breakfast. In-plan only; no recipe changes.

WHAT YOU CANNOT DO:
- Add an ingredient that violates the household's hard constraints (allergens, dietary identity). The deterministic filter will reject your output if you do; the user sees a wasted attempt.
- Replace a whole main recipe — that's Stage A's job; if the plan needs a different main, you push back via warnings.
- Generate a brand-new recipe — that's a different task type; not your concern.
- Modify the plan's day-level structure (slot kinds, meal slots themselves).

LIMITS (hard):
- Max 5 augmentations total across additions + swaps + rearrangements.
- Max 2 swap directives — they go to the recipe optimiser which has its own iteration budget.
- Max 5 additions.
- Max 3 rearrangements.
- If the plan needs more interventions than this, return an empty list with warnings explaining what's left unfixed; the user reviews and accepts the un-augmented plan.

PRIORITISATION (when gaps compete for the 5-slot budget):
1. Daily nutrition floor unmet (most critical; safety-flavoured).
2. Weekly nutrition target underperformance.
3. Cost over budget (especially when cost confidence is high).
4. Slot has no recipe (rare — Stage A usually handles this — but if it slipped through, fill it).
5. Variety / batch / time fit (least critical at this stage).

CREATIVITY EXPECTATIONS:
You can be inventive about additions — a yoghurt-with-fruit snack is fine; "leftover roast chicken sandwich for lunch" is fine when leftovers exist; "a glass of milk before bed" is fine for a calcium gap. Don't invent meal names that aren't food. Don't invent recipes — additions are simple ingredient combinations that don't need cooking instructions.

REASONING:
Each augmentation has 1-2 sentence reasoning. The user reads these in the plan-detail view; the system records them in the decision log. Frame as "what changed and why" — concrete reference to the gap being closed.

CONFIDENCE:
You don't return a numeric confidence per augmentation. Instead, augmentations you're uncertain about should land in `warnings` with a sentence like "added a banana to Wednesday breakfast for variety; consider replacing with another fruit if bananas are tiring."

NO USEFUL AUGMENTATION:
If the plan is fine as-is — gaps within the noise floor, no clear leverage — return empty lists with `overallReasoning = "No augmentations needed; the plan addresses all material gaps within deterministic Phase 1."` This is a valid output and the right call sometimes.

EFFORT GUIDANCE:
Opus 4.7, effort = high. Take time; the augmentations affect the user's actual week.
```

## User Prompt Template

```
[Task: PLAN_AUGMENTATION]

<household>
size: {{HOUSEHOLD_SIZE}}
</household>

<chosen_plan>
{{CHOSEN_PLAN_JSON}}
</chosen_plan>

<gap_analysis>
{{GAP_ANALYSIS_JSON}}
</gap_analysis>

<constraints_summary>
{{CONSTRAINTS_SUMMARY_JSON}}
</constraints_summary>

Propose at most 5 plan-level augmentations per the rules. Prioritise hard-floor gaps first; cost gaps second; variety/batch last.
```

## Examples (in-prompt, wrapped in `<examples>`)

Four examples covering: protein-gap addition, cost-pressure swap directive, side rearrangement, no-augmentation-needed.

```
<examples>

<example>
<input>
<household>size: 2</household>
<chosen_plan>{ "weeklyAggregate":{"protein_g_total":1140,"protein_g_target":1260}, "days":[
  ..., {"date":"2026-05-14", "slots":[
    {"kind":"BREAKFAST","recipe":{"name":"toast and jam","protein_g":4}, ...},
    {"kind":"LUNCH","recipe":{"name":"chickpea salad","protein_g":18}, ...},
    {"kind":"DINNER","recipe":{"name":"chicken stir-fry","protein_g":42}, ...}
  ], "dailyAggregate":{"protein_g":64,"protein_floor_g":80} }, ...
] }</chosen_plan>
<gap_analysis>
{ "nutritionGaps":[{"day":"2026-05-14","macro":"protein","actual":64,"target":80,"floorMissed":true,"shortfall_g":16}], ... }
</gap_analysis>
</input>
<output>
{
  "additions":[
    { "day":"2026-05-14","targetSlot":"BREAKFAST",
      "addedItemName":"Greek yoghurt (170g pot) with honey",
      "mappingKeyHint":"yogurt greek plain whole",
      "estimatedQuantity":170, "unit":"g",
      "reasoning":"Adds ~17g protein to a low-protein breakfast — closes the 16g daily floor gap and pairs naturally with toast." }
  ],
  "swaps":[],
  "rearrangements":[],
  "overallReasoning":"Wednesday is below the protein floor by 16g. Added a Greek yoghurt at breakfast — closes the gap and is a natural addition rather than a recipe change.",
  "warnings":[]
}
</output>
</example>

<example>
<input>
<household>size: 2</household>
<chosen_plan>{ "weeklyAggregate":{"costEstimateGbp":52.40,"costConfidence":0.85,"costTargetGbp":50}, ... }</chosen_plan>
<gap_analysis>
{ "costConcerns":[{"weeklyOverGbp":2.40,"confidence":0.85,"highestCostRecipe":{"day":"2026-05-15","slot":"DINNER","recipe":"Beef Bourguignon","cost":11.20}}], ... }
</gap_analysis>
</input>
<output>
{
  "additions":[],
  "swaps":[
    { "recipeId":"...","day":"2026-05-15","slot":"DINNER",
      "originalIngredient":"beef chuck stewing",
      "proposedSwap":"beef shin (cheaper, comparable braise)",
      "reasoning":"Beef shin is ~£3 cheaper than chuck for the same braise; brings the recipe in line with the weekly budget without changing the dish character." }
  ],
  "rearrangements":[],
  "overallReasoning":"Plan is £2.40 over budget at high cost confidence (0.85). Single swap on the most expensive recipe closes the gap; routed to recipe optimiser as a SUBSTITUTION so the canonical recipe stays as-is.",
  "warnings":[]
}
</output>
</example>

<example>
<input>
<household>size: 2</household>
<chosen_plan>{ "days":[
  {"date":"2026-05-15","slots":[..., {"kind":"DINNER","recipe":{"name":"green salad","protein_g":3, ...}}, ...]},
  {"date":"2026-05-17","slots":[..., {"kind":"DINNER","recipe":{"name":"cottage pie","needsSide":true, ...}}, ...]}
], ... }</chosen_plan>
<gap_analysis>
{ "slotConcerns":[
  {"day":"2026-05-17","slot":"DINNER","concern":"main has no green side"},
  {"day":"2026-05-15","slot":"DINNER","concern":"green salad scheduled but lunch already had a salad"}
] }
</gap_analysis>
</input>
<output>
{
  "additions":[],
  "swaps":[],
  "rearrangements":[
    { "dayA":"2026-05-15","slotA":"DINNER",
      "dayB":"2026-05-17","slotB":"DINNER",
      "reasoning":"Move the green salad from Friday dinner (which already had a salad at lunch) to Sunday's cottage pie dinner (which currently has no green side). Both dinners gain coherence; nothing else changes." }
  ],
  "overallReasoning":"Side-rearrangement only; the salad belongs on Sunday with the cottage pie, not on a Friday that already had a lunchtime salad.",
  "warnings":[]
}
</output>
</example>

<example>
<input>
<household>size: 2</household>
<chosen_plan>{ ... }</chosen_plan>
<gap_analysis>
{ "nutritionGaps":[], "costConcerns":[{"weeklyOverGbp":0.20,"confidence":0.5}],
  "varietyConcerns":[{"concern":"Tuesday and Wednesday both feature chicken thigh","severity":"low"}], "slotConcerns":[] }
</gap_analysis>
</input>
<output>
{
  "additions":[],
  "swaps":[],
  "rearrangements":[],
  "overallReasoning":"No augmentations needed. The £0.20 cost overshoot is within the noise floor and confidence is low (0.5) — possibly the actual cost is under target. The chicken-thigh repeat is mild and the variety gate already verified compliance.",
  "warnings":[]
}
</output>
</example>

</examples>
```

## Eval Set

| # | Scenario | Expected |
|---|---|---|
| 1 | Single-day protein floor gap | One Addition; closes the gap |
| 2 | Two-day protein floor gap | Two Additions (or one Addition + one Swap if more elegant) |
| 3 | Cost over budget by >£3 with high confidence | One Swap directive on highest-cost recipe |
| 4 | Cost over budget by <£0.50 with low confidence | Empty (within noise floor) |
| 5 | Side mis-assignment (salad on day with lunch salad) | One Rearrangement |
| 6 | Multiple gaps competing for 5-slot budget (4 nutrition gaps + 2 cost concerns + variety) | Top-priority 5; warnings list what was deferred |
| 7 | Nutrition gap is on a slot the user has already eaten | Skip; warn that retroactive augmentation isn't possible |
| 8 | Plan is genuinely fine | Empty; overallReasoning explains |
| 9 | Cost gap requires a SWAP that would introduce an allergen (per gap analysis flagging) | Skip swap; warn that the cost gap can't be closed safely; suggest alternative such as reducing portion |
| 10 | An Addition of a yoghurt would close protein but household is dairy-free per hard constraints | Skip; deterministic filter would reject anyway; warn |
| 11 | Ingredient swap directive on a recipe currently being adapted (in-flight Trigger 2 from feedback) | Skip the swap; warn about pipeline contention |
| 12 | All gaps are slot-level concerns (no nutrition, no cost) | Up to 3 Rearrangements; no additions or swaps |
| 13 | A gap needs an ingredient swap that the recipe doesn't currently use (mismatch) | Skip; warn about the input mismatch |
| 14 | Friday dinner has no main (Stage A miss) | One Addition or warning depending on whether augmentation is enough or a re-plan is needed |

Acceptance threshold: **11/14**.

## Cost Analysis

| Metric | Estimate |
|---|---|
| Input tokens (cache hit) | ~3000-5000 (full plan + gap analysis + constraints) |
| Cached input tokens | ~4500 (system prompt + 4 examples) |
| Output tokens | ~300-700 |
| Cost per call (Opus 4.7, cached, effort=high) | **~£0.20** |
| Calls per active user per week | ~1-2 |
| **Cost per user per week** | **~£0.20-0.40** |

## Failure Modes

Beyond README boilerplate:

| Failure | Behaviour |
|---|---|
| Output exceeds total cap (>5 augmentations) | Validator truncates to first 5; warning logged |
| Output's Addition has hard-constraint violation | Deterministic filter (Provisions/Preference) rejects that specific Addition; others ship; warning surfaces |
| IngredientSwapDirective references a recipe not in the plan | Validator rejects the directive; others ship |
| Rearrangement references slots that don't both exist | Validator rejects; others ship |
| Output empty when gaps clearly call for action | Allowed but logged as `phase2_empty_with_unfilled_gaps` for review |

## AiTask Skeleton

```java
public final class Phase2AugmentationTask implements AiTask<Phase2AugmentationsResponse> {
    private final ChosenPlanFullDto chosenPlan;
    private final GapAnalysisDto gapAnalysis;
    private final ConstraintSummaryDto constraints;
    private final int householdSize;
    private final UUID userId;
    private final UUID traceId;

    @Override public TaskType getTaskType() { return TaskType.PLAN_AUGMENTATION; }
    @Override public String getSystemPrompt() { return SYSTEM_PROMPT; }
    @Override public PromptRef getUserPromptRef() {
        return new PromptRef("planner/phase2-augmentation", Optional.empty());
    }
    @Override public Map<String, Object> getContext() {
        return Map.of("chosen_plan", chosenPlan, "gap_analysis", gapAnalysis,
                      "constraints_summary", constraints, "household_size", householdSize);
    }
    @Override public ToolDefinition getToolSchema() {
        return ToolDefinitionBuilder.fromRecord(Phase2AugmentationsResponse.class).build();
    }
    @Override public Class<Phase2AugmentationsResponse> getResponseType() { return Phase2AugmentationsResponse.class; }
    @Override public UUID getUserId() { return userId; }
    @Override public UUID getTraceId() { return traceId; }
    @Override public Optional<Duration> getTimeoutOverride() { return Optional.of(Duration.ofSeconds(30)); }
}
```

## Decisions made (worth user review)

1. **Three operations, not more** — Add, Swap-via-directive, Rearrange. Anything more invasive belongs to Stage A composition or Recipe Adaptation.
2. **Hard 5-augmentation cap** at the response level; the system prompt explicitly states this. Without the cap Opus tends to over-augment.
3. **Swap is via directive, not direct edit** — preserves the canonical recipe in the user catalogue; the swap is a plan-slot SUBSTITUTION at the recipe-system level.
4. **No numeric confidence per augmentation** — qualitative warnings instead. Adding numeric confidence to ~5 augmentations and aggregating doesn't improve decision quality.
5. **"No augmentations needed" is a valid output** — explicitly modelled in the system prompt.
6. **Gap-priority order baked in**: floor-missed > target-undershoot > cost > variety > batch. Without this Opus picks creative-feeling augmentations that don't close the most urgent gap.
7. **30-second timeout** — longer than other Opus prompts because Phase 2 has to reason over the full plan + multiple gap types.
8. **Eval threshold 11/14** — judgement-heavy; recovery via the user's accept/reject feedback.
