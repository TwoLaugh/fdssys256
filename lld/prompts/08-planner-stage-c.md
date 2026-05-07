# Prompt — Planner Stage C (Pick of N)

*The frontier-tier qualitative tie-breaker. Receives 5 deterministically-scored candidate plans, picks the one to ship to the user, with reasoning recorded in the decision log.*

Cross-cutting conventions defer to [README.md](README.md).

## Wiring

| | |
|---|---|
| AiTask name | `StageCPickTask` |
| TaskType | `PLAN_COMPOSITION` (mid → frontier per system-overview tier table) |
| Tier | Opus 4.7 (frontier), `effort = high` |
| Module | `planner` |
| Called by | `StageCInvoker` after Stage A's beam search produces top-N=5 candidates |
| Failure path | `AiUnavailable` → skip-and-flag: deterministic top-scored candidate selected, `aiAugmented = false` (per planner.md) |
| Cache | System prompt + examples + constraint-summary preamble cached; per-call rollups vary |
| Cost | ~£0.10-0.30/call; weekly per active user |

## Purpose

Stage A scored 5 candidate plans deterministically. By the time you see them, all 5:
- Pass the hard-constraint filter (allergens, dietary identity)
- Score reasonably on the seven sub-scores (preference, nutrition, cost, variety, time, batch, provisions)
- Have rollup summaries (per-day macros, weekly totals, cost projection with confidence, batch-cook session count, time fit)

Your job: **pick the one** the user is most likely to accept. The deterministic scoring produced reasonable candidates; you bring qualitative judgement that scoring can't capture — coherence across the week, narrative quality, alignment with the user's stated preferences in their lifestyle config notes.

This is **not**:
- Composition (Stage A did that)
- Augmentation (Phase 2 does that, via prompt #9)
- A second-pass scoring (the deterministic scores already happened; trust them as inputs)

## Inputs / Outputs

**Inputs (`AiTask.getContext()`):**

```java
Map.of(
    "candidates",         List<CandidatePlanRollupDto>,  // exactly 5; ordered by deterministic score (best-first)
    "constraints_summary", ConstraintSummaryDto,          // hard + soft constraints in user-readable form
    "household_size",     int,
    "week_start",         String,                         // ISO 8601 date
    "trigger",            PlanTrigger                     // INITIAL | MID_WEEK_REOPT | USER_INITIATED_REOPT
)

record CandidatePlanRollupDto(
    int candidateIndex,                                    // 0-4
    BigDecimal totalScore,                                 // deterministic Stage-A score
    Map<String, BigDecimal> scoreBreakdown,                // 7 sub-scores per planner.md
    List<DayRollup> daily,                                 // 7 entries
    WeeklyRollup weekly,
    int batchCookSessions,
    BigDecimal costEstimateGbp,
    BigDecimal costConfidence,                             // 0-1; per planner cost-confidence regression rule
    int staleIngredientCount,
    List<String> constraintViolations,                     // empty in well-formed candidates
    List<String> notes                                     // diversity hints; e.g. "uses Sunday batch cook for Mon-Wed lunches"
) {}
```

The candidate list is **pre-sorted by deterministic score**. You do not need to ignore that signal — it's an input. But scoring isn't perfect; if candidate 2 narrowly trails candidate 1 in score but is materially better on a qualitative dimension the user cares about, picking 2 is correct.

**Output:**

```java
record StageCPickResponse(
    int chosenIndex,                       // 0-4
    String reasoning,                      // 2-4 sentences, user-facing in the plan-detail view
    List<String> qualitativeFactors,       // tags surfaced in the decision log
    BigDecimal confidence                  // 0-1 — how clear-cut the pick was
) {}
```

## System Prompt

```
You are the final picker for a household's weekly meal plan. Five candidate plans are produced by deterministic search; your job is to choose which one to present to the user.

YOUR INPUTS:
- The 5 candidates, with score breakdown and per-day rollups.
- The household's constraints (already-applied — the candidates respect them).
- The trigger that caused this plan generation (initial weekly plan, mid-week re-optimisation, user-initiated re-optimisation).

YOUR OUTPUT:
- The chosen index (0-4).
- 2-4 sentences of reasoning that the user will read in the plan-detail view.
- A confidence in 0-1 — how clear-cut the pick was. Below 0.6 means "the candidates were close; this is a judgement call."

WHAT MATTERS:
The candidates' deterministic scores are already a strong signal. You should not second-guess scores that are well-separated. When scores are within ~0.05 of each other, qualitative factors break the tie:

1. **Variety across the week** — does the week feel like a coherent rhythm or a random shuffle? Does it have a pleasing arc? (Mon-Tue weeknight quick, Wed-Thu batch-cook, Fri-Sat treat, Sun batch-cook.)
2. **Cost confidence** — if two candidates have similar projected cost but very different cost confidence, prefer the higher-confidence one (the user's projection is more trustworthy).
3. **Mid-week re-optimisation continuity** — if the trigger is `MID_WEEK_REOPT`, prefer candidates that preserve as much of the user's already-eaten or in-progress meals; minimise jarring changes.
4. **Stale-data avoidance** — penalise candidates with high `staleIngredientCount` when cost-sensitivity is signalled.
5. **Narrative quality** — does the plan tell a story? "Big roast on Sunday → leftovers Mon-Tue → fresh fish midweek → Friday treat" is more pleasing than the same recipes shuffled randomly.

WHAT DOES NOT MATTER (do not weight these):
- Which candidate was ranked first by the deterministic search (it's already a strong input via `totalScore`; don't double-count).
- Notional novelty — the deterministic variety sub-score handles repetition checks.
- Aesthetic hunches not grounded in the rollup data.

REASONING:
2-4 sentences, written for the user. Frame it as "I picked plan X because..." — concrete reasons grounded in the rollups, not generic praise. Avoid jargon. The user reads this in the UI; if they push back, the misclassification feedback loop adjusts the weights for next week.

If the deterministic score-1 candidate is the right pick (often it is), still articulate why — confirms the reasoning path was followed.

CONFIDENCE:
- 0.9-1.0: clear winner; the chosen candidate beats the next-best on multiple qualitative dimensions and on score.
- 0.6-0.9: defensible pick; the chosen candidate slightly beats alternatives.
- Below 0.6: close call; surface the runner-up in qualitativeFactors for the decision log.

EFFORT GUIDANCE:
This task uses Opus 4.7 with effort = high. Take time to compare candidates carefully. Don't rush to the first plausible pick.
```

## User Prompt Template

```
[Task: PLAN_COMPOSITION]

<household>
size: {{HOUSEHOLD_SIZE}}
week_start: {{WEEK_START}}
trigger: {{TRIGGER}}
</household>

<constraints_summary>
{{CONSTRAINTS_SUMMARY_JSON}}
</constraints_summary>

<candidates>
{{CANDIDATES_JSON_PRETTY}}
</candidates>

Pick one of the 5 candidates per the rules. Output the chosen index, reasoning, qualitative factors, and confidence.
```

## Examples (in-prompt, wrapped in `<examples>`)

Three examples covering: clear winner, close call broken by qualitative factor, mid-week re-opt with continuity preference.

```
<examples>

<example>
<input>
<household>size: 2, week_start: 2026-05-12, trigger: INITIAL</household>
<constraints_summary>
{ "budget":{"weeklyTargetGbp":50}, "nutrition":{"daily_protein_floor_g":120}, "preferences":{"cuisinesLiked":["Italian","Thai","British"]}, ... }
</constraints_summary>
<candidates>
[
  { "candidateIndex":0, "totalScore":0.82, "scoreBreakdown":{...},
    "weekly":{"costEstimateGbp":47.5,"costConfidence":0.85,"varietyIndex":0.78}, ...,
    "notes":["Sunday roast → Mon-Tue leftovers → Wed fish → Thu-Fri quick weeknight → Sat takeaway-style treat"] },
  { "candidateIndex":1, "totalScore":0.79, "scoreBreakdown":{...},
    "weekly":{"costEstimateGbp":52.1,"costConfidence":0.6,"varietyIndex":0.85}, ...,
    "notes":["High variety; no batch cook; over budget by ~£2"] },
  { "candidateIndex":2, "totalScore":0.78, ... },
  { "candidateIndex":3, "totalScore":0.76, ... },
  { "candidateIndex":4, "totalScore":0.74, ... }
]
</candidates>
</input>
<output>
{
  "chosenIndex":0,
  "reasoning":"Plan 1 has the strongest narrative — Sunday roast carrying through to Tuesday's leftovers, midweek fish, then quick weeknights — which fits a 2-person household well. It's also under budget with high cost confidence (0.85), and hits the protein floor every day without straining variety.",
  "qualitativeFactors":["narrative_arc","cost_confidence_high","under_budget"],
  "confidence":0.85
}
</output>
</example>

<example>
<input>
<household>size: 2, trigger: INITIAL</household>
<constraints_summary>{...}</constraints_summary>
<candidates>
[
  { "candidateIndex":0, "totalScore":0.78, "weekly":{"costEstimateGbp":48,"costConfidence":0.55,"varietyIndex":0.75},
    "notes":["Cost projection uncertain — 8 ingredients have no recent price data"] },
  { "candidateIndex":1, "totalScore":0.77, "weekly":{"costEstimateGbp":49,"costConfidence":0.92,"varietyIndex":0.74},
    "notes":["Cost projection well-grounded; uses ingredients you've shopped recently"] },
  ...
]
</candidates>
</input>
<output>
{
  "chosenIndex":1,
  "reasoning":"Plans 1 and 2 are nearly tied on score (0.78 vs 0.77), but plan 2's cost projection is much more trustworthy — confidence 0.92 vs 0.55. Plan 1 has 8 ingredients without recent price data, which means the £48 estimate could easily be £40 or £58 in reality. For a tight-budget household plan 2's predictable spend matters more than plan 1's marginal score advantage.",
  "qualitativeFactors":["cost_confidence_decisive","stale_data_penalty"],
  "confidence":0.7
}
</output>
</example>

<example>
<input>
<household>size: 2, trigger: MID_WEEK_REOPT</household>
<constraints_summary>{ "preserveSlotsBefore":"Wed dinner; user has eaten Mon-Wed lunch and Mon-Tue dinner", ... }</constraints_summary>
<candidates>
[
  { "candidateIndex":0, "totalScore":0.82,
    "notes":["Preserves all already-eaten meals; minor swap on Wed dinner, fresh re-plan Thu-Sun"] },
  { "candidateIndex":1, "totalScore":0.85,
    "notes":["Higher absolute score but rebuilds the week from scratch — different cuisine on Wed dinner, swaps Sunday roast for stew"] },
  ...
]
</candidates>
</input>
<output>
{
  "chosenIndex":0,
  "reasoning":"Mid-week re-opt should minimise disruption; the user's already invested in Mon-Wed meals. Plan 1 preserves all of that and adjusts only the affected days (Wed dinner onwards), where plan 2 — despite scoring slightly higher — would change the week's narrative for no clear reason. The 0.03 score gap doesn't justify the discontinuity cost.",
  "qualitativeFactors":["mid_week_continuity","minimum_disruption"],
  "confidence":0.85
}
</output>
</example>

</examples>
```

## Eval Set

| # | Scenario | Expected |
|---|---|---|
| 1 | Score-clear winner (top by 0.10+) | Pick rank-0; high confidence; short reasoning citing score gap + the dominant sub-score |
| 2 | Three-way tie (top three within 0.02) | Tie-broken by qualitative factor (variety, batch, cost confidence); confidence ~0.65 |
| 3 | Top score has high stale-data ratio; rank-2 has lower score but high cost confidence | Pick rank-2 with explanation grounded in cost confidence |
| 4 | Mid-week re-opt; rank-0 disrupts continuity; rank-1 preserves it | Pick rank-1 (continuity preference for mid-week trigger) |
| 5 | All 5 candidates very similar (max-min < 0.04) | Pick rank-0 with low confidence; surface "candidates very similar — first picked by tie-breaking on variety" |
| 6 | Initial trigger; rank-0 has narrative arc, rank-1 is shuffled | Pick rank-0 with narrative-arc reasoning |
| 7 | One candidate has constraint violations (warned, didn't get filtered) | Pick a different candidate; flag the violation in qualitativeFactors |
| 8 | User-initiated re-opt with directive "more variety" | Pick the highest-variety candidate even if score is rank-2 |
| 9 | Cost projection confidence is uniformly low (cold-start week) | Acknowledge in reasoning; pick by other factors; don't penalise heavily |
| 10 | Rank-0 has 0 batch sessions, rank-1 has 2 (better per the user's lifestyle "I prefer batching") | Pick rank-1 if lifestyle config preference is signalled |
| 11 | All candidates fail to hit a daily protein floor (passed gate but barely) | Pick on other dimensions; flag the marginal floor compliance in qualitativeFactors |
| 12 | Rank-0 reasoning would be repetitive — same dish twice in 7 days | Pick rank-2; explain the repetition concern |
| 13 | Rank-0 has explicit annotation matching user-stated preference notes ("you said you prefer to cook fresh on Fridays") | Pick rank-0 with reference to that note |

Acceptance threshold: **11/13** for ship; **12/13** mature.

## Cost Analysis

| Metric | Estimate |
|---|---|
| Input tokens (cache hit) | ~2000-4000 (5 candidate rollups + constraints) |
| Cached input tokens | ~3500 (system prompt + 3 examples) |
| Output tokens | ~200-400 |
| Cost per call (Opus 4.7, cached, effort=high) | **~£0.15** |
| Calls per active user per week | ~1-2 (initial + 0-1 re-opts) |
| **Cost per user per week** | **~£0.15-0.30** |

## Failure Modes

Beyond README boilerplate:

| Failure | Behaviour |
|---|---|
| Output `chosenIndex` is out of range | Validator rejects; retry once; if still bad, fall back to rank-0 deterministic with `aiAugmented = false` |
| Output reasoning is empty or repeats the input verbatim | Reject as malformed; retry once with corrective prompt |
| Output `confidence` is below 0.3 (model is itself unsure) | Accept the pick but flag in decision log as `low_confidence_pick`; UI shows "AI was uncertain — feel free to swap" |
| Output picks a candidate with `constraintViolations.size() > 0` | Allowed but warning surfaced — the violations passed the gate so they're acceptable, but flagged |

## AiTask Skeleton

```java
public final class StageCPickTask implements AiTask<StageCPickResponse> {
    private final List<CandidatePlanRollupDto> candidates;
    private final ConstraintSummaryDto constraints;
    private final int householdSize;
    private final LocalDate weekStartDate;
    private final PlanTrigger trigger;
    private final UUID userId;
    private final UUID traceId;

    @Override public TaskType getTaskType() { return TaskType.PLAN_COMPOSITION; }
    @Override public String getSystemPrompt() { return SYSTEM_PROMPT; }
    @Override public PromptRef getUserPromptRef() {
        return new PromptRef("planner/stage-c-pick", Optional.empty());
    }
    @Override public Map<String, Object> getContext() {
        return Map.of("candidates", candidates, "constraints_summary", constraints,
                      "household_size", householdSize, "week_start", weekStartDate.toString(),
                      "trigger", trigger.name());
    }
    @Override public ToolDefinition getToolSchema() {
        return ToolDefinitionBuilder.fromRecord(StageCPickResponse.class).build();
    }
    @Override public Class<StageCPickResponse> getResponseType() { return StageCPickResponse.class; }
    @Override public UUID getUserId() { return userId; }
    @Override public UUID getTraceId() { return traceId; }
    @Override public Optional<Duration> getTimeoutOverride() { return Optional.of(Duration.ofSeconds(20)); }
}
```

## Decisions made (worth user review)

1. **Three examples, not five** — Opus 4.7 needs fewer examples to perform well. Cache budget allows more if quality demands; current 3 cover the patterns.
2. **`effort = high` is mandated** in the AiTask wiring (`getTimeoutOverride` 20s for some headroom). `xhigh` would be more expensive without clear quality gain for this scope.
3. **Confidence < 0.3 → accept but flag** rather than retry. Retrying a "model is unsure" call usually produces another unsure response; the user can easily override.
4. **Mid-week trigger biases toward continuity** — the system prompt explicitly calls this out. Without it, Opus 4.7 might pick high-score candidates that are jarring mid-week.
5. **No chain-of-thought / `<thinking>` tags in this prompt** — Opus 4.7's adaptive thinking handles this internally at `effort = high`. Adding explicit thinking tags is redundant per the model's current behaviour.
6. **Eval threshold 11/13** — qualitative judgement, lower than mechanical prompts. False picks recover via the user's accept/reject feedback loop.
7. **The "narrative arc" criterion is named in the system prompt** — it's qualitative but Anthropic-style instruction-following picks up on it. Track in eval whether this generalises beyond the example.
