# Prompt — Feedback Classification + Routing

*Classifies a piece of free-text user feedback into one or more of four destinations (Preference, Nutrition, Provisions, Recipe-via-Adaptation), with per-destination confidence and a payload summary. Cheap-tier. High-volume hot path — every feedback submission flows through it.*

Cross-cutting conventions (confidence scale, null-population, alt-swap rule, edge-case examples, enum whitelisting, cache strategy, TaskType banner) defer to [README.md](README.md) — this doc only restates them where the application is task-specific.

## Wiring

| | |
|---|---|
| AiTask name | `FeedbackClassificationTask` |
| TaskType | `FEEDBACK_CLASSIFICATION` |
| Tier | Haiku 4.5 (cheap) |
| Module | `feedback` |
| Called by | `FeedbackClassifier` (per [lld/feedback.md §Classifier](../feedback.md#classifier-aitask)) — invoked from the async classification listener (Flow 2) after `FeedbackSubmittedEvent` commits |
| Input prep | None — the classifier sees the raw user text plus screen context. No fuzzy search, no candidate list. The model is the classifier. |
| Failure path | `AiUnavailable` → entry reverts to `RECEIVED`, 2-min sweep retries; `AiResponseInvalid` → terminal `FAILED`. See [feedback.md §Classifier failure handling](../feedback.md#classifier-failure-handling--graceful-degrade) |
| Cache | System prompt + 6 examples cached `ephemeral` (5-min TTL); per-call feedback text + screen context vary |
| Cost | ~£0.0005/call cached, ~£0.002 cold; ~3 feedbacks/wk per active user → trivial (~£0.0015/wk/user) |

## Purpose

The pipeline-level loop is *receive feedback → classify → route → confirm*. This single prompt does the classify step end-to-end: it identifies which of the four destinations the feedback targets, attaches a confidence to each, and emits a one-line payload summary that the destination's downstream prompt or service turns into a structured update.

Pure interpretation — no retrieval, no candidate set. Inputs are the user's text, where they typed it, an optional meal pin, and a thin window of recent classifications. The model is not asked to reason about the *fix*: "this needs more garlic" classifies as `RECIPE` with confidence 0.9 and never proposes the actual recipe edit.

One hard architectural constraint: the `RECIPE` destination is a **routing label**, not a write. Recipe feedback never reaches `RecipeUpdateService` directly — always via `OptimiserService.handleRecipeFeedback`, which runs the [recipe adaptation prompt](05-recipe-adaptation.md) in the adaptation pipeline. See Decisions §1.

## Inputs / Outputs

**Inputs (passed via `AiTask.getContext()`):**

```java
Map.of(
    "feedback_text",          String,                       // the user's free-text input
    "screen_context",         String,                       // enum see below; never null, "general" if unknown
    "current_meal_context",   MealContext,                  // nullable — present when feedback is pinned to a meal
    "recent_classifications", List<RecentClassification>    // last 5 of this user's classifications; empty list never null
)

record MealContext(UUID recipeId, UUID mealSlotId, Instant eatenAt) {}

record RecentClassification(
    Destination destination,                                // PREFERENCE | NUTRITION | PROVISIONS | RECIPE
    BigDecimal confidence,
    Instant classifiedAt
) {}
```

`screen_context` is one of: `recipe_page`, `plan_view`, `shopping_list`, `nutrition_dashboard`, `general`. It maps from the feedback module's `Screen` enum ([feedback.md §DTOs](../feedback.md#dtos)) — `RECIPE_DETAIL → "recipe_page"`, `PLAN_MEAL_DETAIL` and `PLAN_VIEW → "plan_view"`, `GROCERY → "shopping_list"`, `NUTRITION_DASHBOARD → "nutrition_dashboard"`, `SETTINGS` and `GENERAL → "general"`. The mapping happens in `FeedbackClassificationContext.toRendererMap()`.

**Output (structured tool-use):**

```java
record ClassificationResult(
    List<RoutingDecision> destinations,                     // never null; [] when requiresClarification = true
    boolean requiresClarification,
    String clarificationQuestion,                           // null unless requiresClarification = true
    String overallReasoning                                 // for the routing log; concise audit explanation
) {}

record RoutingDecision(
    Destination destination,                                // PREFERENCE | NUTRITION | PROVISIONS | RECIPE
    BigDecimal confidence,                                  // 0.5 floor — see Decision §2
    String payloadSummary,                                  // 1-line natural-language summary; the destination
                                                            //   prompt produces the actual structured update
    List<String> evidenceQuotes                             // direct phrases from the feedback; never null, [] only
                                                            //   if the destination is an inferred-from-context catch
) {}

enum Destination { PREFERENCE, NUTRITION, PROVISIONS, RECIPE }
```

Maps onto the feedback module's wire types ([feedback.md §Classification result](../feedback.md#classification-result-the-structured-ai-output)): `payloadSummary` seeds `extractedFeedback`; the destination dispatcher either forwards it to a per-destination prompt or extracts structured fields. `evidenceQuotes` surfaces in the misclassification correction UI — see Decision §4.

## System Prompt

```
You are a feedback classifier for a meal-planning system. Given a piece of user feedback in free text, decide which downstream module(s) the feedback targets, with what confidence, and what each module needs to act on.

Your output is consumed by a router that fans out to up to four destinations. Wrong routing means the wrong data model degrades silently. Multi-destination routing is welcomed when the feedback genuinely covers multiple concerns; pause for clarification when the routing is genuinely ambiguous.

DESTINATIONS — pick one or more, never invent new ones:

1. PREFERENCE — taste, likes/dislikes, cuisine preferences, cooking style, lifestyle, variety, time-to-cook constraints. Examples: "I don't like coriander", "this week's meals were boring", "I want lighter dinners". The destination updates the user's taste profile.

2. NUTRITION — portions, macro fit, hunger, energy, calorie targets, protein targets. Examples: "the portions were too small", "I'm always hungry after lunch", "I need more protein". The destination updates targets and logs mood/energy observations.

3. PROVISIONS — cost, availability, equipment, waste, supplier issues, shelf life. Examples: "this week was too expensive", "couldn't find this ingredient at Tesco", "the chicken's gone off". The destination logs the concern and may update budget, supplier cache, equipment list, or waste tracking.

4. RECIPE — feedback about a *specific* recipe's quality (taste, seasoning, method, ingredients, texture). Examples: "this needed more garlic", "the sauce was too thick", "really bland". This routes to the adaptation pipeline, which reasons about the fix. **Never use RECIPE for general taste preferences** — "I generally don't like salty food" is PREFERENCE; "this stir fry was too salty" is RECIPE.

INPUTS:
- The raw feedback text.
- Screen context — where the user typed the feedback. Provides bias, never ground truth: feedback typed on the recipe page can still go to PROVISIONS if the user explicitly mentions cost.
- An optional current meal context (recipe id + meal slot + when eaten) — present when the feedback was pinned to a specific meal.
- Up to 5 recent classifications for this user. Use these mildly to spot per-user vocabulary patterns. Never override clear textual evidence.

WHAT TO DO:
1. Identify each *aspect* the user is reacting to. One feedback can carry several ("too expensive AND bland" → two). Aspects are independent: one aspect → one destination.
2. For each aspect, pick the destination. The PREFERENCE-vs-RECIPE distinction is the most common confusion: a *general* taste statement is PREFERENCE; a *recipe-specific* taste statement is RECIPE. "This recipe" / "this dish" / `recipe_page` screen pushes toward RECIPE; "always" / "generally" / "I don't like" pushes toward PREFERENCE.
3. Score confidence on the standard 0-1 scale (per [README.md §Confidence calibration](README.md)): 0.9-1.0 unambiguous + screen aligned; 0.6-0.9 minor ambiguity; 0.3-0.6 meaningful ambiguity; 0.0-0.3 guess.
4. Write a one-line `payloadSummary` in natural language ("user prefers less salt", "portions were too small", "cost concern about this week's plan"). The downstream destination produces the structured fields — your summary is the seed.
5. Attach `evidenceQuotes` — direct substrings of the input (case-insensitive) that justify routing here. Empty list only if the destination is inferred purely from screen context (rare; flag in `overallReasoning`).
6. Decide if the feedback should pause for clarification:
   - Any destination confidence < 0.5 → `requiresClarification = true`, `destinations = []`, write a closed-form `clarificationQuestion`. Do not partial-route.
   - Off-topic for all four destinations (e.g. "the app crashed") → same shape; explain in the question that the feedback doesn't match any destination.
   - Otherwise `requiresClarification = false`, `clarificationQuestion = null`, `destinations` non-empty.
7. Write `overallReasoning` — one or two specific sentences for the audit trail.

CONFIDENCE FLOOR — 0.5 PAUSES THE ENTIRE ENTRY:
The system does NOT partial-route low-confidence aspects. If any aspect is below 0.5 confidence, pause the entire entry — even when other aspects are high-confidence. Locked decision per [feedback.md Flow 2](../feedback.md#flow-2-classification): one feedback text is one user intent; partial routing risks losing meaning.

CLARIFICATION QUESTIONS — CLOSED-FORM ONLY:
Answerable in one or two taps. Either binary ("Was your concern about the cost or the taste?") or short multiple-choice ("Did you mean: (a) the recipe needs changing, (b) your preferences need updating, (c) something about the cost?"). Open-ended questions ("could you tell me more?") are not acceptable — the user has already typed once.

ALTERNATIVE-VS-PRIMARY SWAP RULE (per [README.md](README.md)):
This prompt has no alternatives concept — multi-destination routing handles the same need. If two destinations score above 0.5 and the phrasing splits cleanly into aspects, list both. If two destinations could *both* explain the same phrase but aren't independent aspects, pick the stronger; if neither dominates, lower-confidence the call into the < 0.5 floor (pauses for clarification).

WHAT NOT TO DO:
- Do not invent destinations beyond PREFERENCE / NUTRITION / PROVISIONS / RECIPE.
- Do not return RECIPE without a clear recipe-specific signal. General taste statements are PREFERENCE.
- Do not let screen context override clear textual evidence ("too expensive" on the recipe page is PROVISIONS).
- Do not return more than four destinations. Do not duplicate destinations.
- `payloadSummary` is one line of natural language, not a structured object.
- Do not invent quotes — `evidenceQuotes` must be substrings of the input.
- Do not include the user's clarification answer (if any) in `evidenceQuotes` — only original feedback counts.
```

System prompt ~1700 tokens; examples block ~2200 tokens. **Cached prefix ~3900 — still below Haiku 4.5's 4096-token minimum.** Per-user volume is low (~3/wk) so uncached cost (~£0.006/wk/user) is acceptable, but the 4× discount is worth claiming. **Recommend padding to >4096 tokens**; specific padding deferred to Decisions §8.

## User Prompt Template

```
[Task: FEEDBACK_CLASSIFICATION]

<feedback>
{{FEEDBACK_TEXT}}
</feedback>

<screen_context>
{{SCREEN_CONTEXT}}
</screen_context>

<current_meal_context>
{{CURRENT_MEAL_CONTEXT_JSON_OR_NONE}}
</current_meal_context>

<recent_classifications>
{{RECENT_CLASSIFICATIONS_JSON_OR_EMPTY}}
</recent_classifications>

Classify this feedback into one or more destinations using the rules above. If any aspect is below 0.5 confidence, pause the entire entry with requiresClarification = true.
```

The `[Task: ...]` banner is the convention from [README.md §TaskType banner](README.md). It appears after the cached prefix and identifies the task type in the call log audit.

`{{CURRENT_MEAL_CONTEXT_JSON_OR_NONE}}` renders as either `{"recipeId":"...", "mealSlotId":"...", "eatenAt":"..."}` or the literal string `none`. `{{RECENT_CLASSIFICATIONS_JSON_OR_EMPTY}}` is a compact JSON array; `[]` when there are no recent classifications.

## Examples (in-prompt, wrapped in `<examples>` tags)

```
<examples>

<example>
<input>
<feedback>This stir fry was too salty</feedback>
<screen_context>recipe_page</screen_context>
<current_meal_context>{"recipeId":"r-042","mealSlotId":"m-156","eatenAt":"2026-04-15T19:30:00Z"}</current_meal_context>
<recent_classifications>[]</recent_classifications>
</input>
<output>
{
  "destinations": [
    {
      "destination": "RECIPE",
      "confidence": 0.95,
      "payloadSummary": "Recipe was too salty; reduce salt or add an acid for balance.",
      "evidenceQuotes": ["too salty"]
    }
  ],
  "requiresClarification": false,
  "clarificationQuestion": null,
  "overallReasoning": "Recipe-specific taste complaint pinned to a meal context. The phrase 'this stir fry' anchors the feedback to one recipe, so routes to RECIPE for the adaptation pipeline rather than to PREFERENCE."
}
</output>
</example>

<example>
<input>
<feedback>This week was too expensive and pretty bland honestly</feedback>
<screen_context>plan_view</screen_context>
<current_meal_context>none</current_meal_context>
<recent_classifications>[]</recent_classifications>
</input>
<output>
{
  "destinations": [
    {
      "destination": "PROVISIONS",
      "confidence": 0.88,
      "payloadSummary": "Cost concern about this week's plan.",
      "evidenceQuotes": ["too expensive"]
    },
    {
      "destination": "PREFERENCE",
      "confidence": 0.82,
      "payloadSummary": "User found the week's meals lacking flavour variety; bias toward more flavourful recipes.",
      "evidenceQuotes": ["pretty bland"]
    }
  ],
  "requiresClarification": false,
  "clarificationQuestion": null,
  "overallReasoning": "Two independent aspects — cost (PROVISIONS) and an at-the-week-level flavour complaint (PREFERENCE rather than RECIPE because it isn't pinned to a single recipe)."
}
</output>
</example>

<example>
<input>
<feedback>The chicken's gone off</feedback>
<screen_context>shopping_list</screen_context>
<current_meal_context>none</current_meal_context>
<recent_classifications>[]</recent_classifications>
</input>
<output>
{
  "destinations": [
    {
      "destination": "PROVISIONS",
      "confidence": 0.95,
      "payloadSummary": "Shelf-life or quality complaint about chicken; log against the supplier and adjust expiry estimate.",
      "evidenceQuotes": ["chicken's gone off"]
    }
  ],
  "requiresClarification": false,
  "clarificationQuestion": null,
  "overallReasoning": "Quality and shelf-life complaint typed on the shopping list — clearly a PROVISIONS concern, screen context aligned with textual evidence."
}
</output>
</example>

<example>
<input>
<feedback>This recipe needs more garlic, it was a bit flat</feedback>
<screen_context>recipe_page</screen_context>
<current_meal_context>{"recipeId":"r-088","mealSlotId":"m-201","eatenAt":"2026-04-22T18:45:00Z"}</current_meal_context>
<recent_classifications>[]</recent_classifications>
</input>
<output>
{
  "destinations": [
    {
      "destination": "RECIPE",
      "confidence": 0.92,
      "payloadSummary": "Recipe needs more garlic; the dish was under-seasoned. Send to adaptation pipeline.",
      "evidenceQuotes": ["This recipe needs more garlic", "a bit flat"]
    }
  ],
  "requiresClarification": false,
  "clarificationQuestion": null,
  "overallReasoning": "Recipe-specific suggestion ('this recipe', recipe page, recipe pinned to context) — routes to RECIPE for the adaptation pipeline. Both quotes describe the same aspect, so a single RECIPE routing covers it."
}
</output>
</example>

<example>
<input>
<feedback>hmm didn't really like it</feedback>
<screen_context>plan_meal_detail</screen_context>
<current_meal_context>{"recipeId":"r-114","mealSlotId":"m-238","eatenAt":"2026-04-25T19:00:00Z"}</current_meal_context>
<recent_classifications>[{"destination":"RECIPE","confidence":0.9,"classifiedAt":"2026-04-22T18:45:00Z"}]</recent_classifications>
</input>
<output>
{
  "destinations": [],
  "requiresClarification": true,
  "clarificationQuestion": "Could you say a bit more — was it: (a) something specific about this recipe (seasoning, texture, method), (b) a general preference (you don't usually enjoy this style), or (c) the portion size or how it made you feel?",
  "overallReasoning": "Vague negative reaction with no specific aspect. Could plausibly be RECIPE, PREFERENCE, or NUTRITION. Recent classification was RECIPE but that's not strong enough to override the textual ambiguity. Pause for clarification per the 0.5 confidence floor."
}
</output>
</example>

<example>
<input>
<feedback>the app keeps crashing when I try to open the planner</feedback>
<screen_context>general</screen_context>
<current_meal_context>none</current_meal_context>
<recent_classifications>[]</recent_classifications>
</input>
<output>
{
  "destinations": [],
  "requiresClarification": true,
  "clarificationQuestion": "This sounds like a technical issue rather than feedback about your meals. Would you like to: (a) report this as a bug via support, or (b) share something about the meal planning itself?",
  "overallReasoning": "Off-topic for all four destinations — a technical bug report, not meal-planning feedback. Routes to none and surfaces a clarification offering the bug-report channel."
}
</output>
</example>

</examples>
```

The 6 examples cover: single-destination clear (Recipe), multi-destination split (Provisions + Preference), context-driven (Provisions inferred from shopping_list screen), recipe-specific via Adaptation, ambiguous low-confidence pause, off-topic. The recent-classifications signal is exercised in example 5 — the model correctly weighs it lightly because the textual ambiguity dominates.

## Eval Set (regression)

Beyond the in-prompt examples, ~20 cases that exercise edge behaviour. Stored in `src/test/resources/prompts/feedback-classification-eval.json` once implementation starts. Used by `FeedbackClassificationTaskTest` and during prompt-engineering iteration.

| # | Input | Expected | Tests |
|---|---|---|---|
| 1 | `"too salty"` + recipe_page + meal context | `RECIPE`, conf ≥ 0.9 | Single-destination clear; in-prompt example variant |
| 2 | `"too expensive AND bland"` + plan_view | `PROVISIONS` + `PREFERENCE`, both ≥ 0.8 | Multi-destination split |
| 3 | `"the chicken's gone off"` + shopping_list | `PROVISIONS`, conf ≥ 0.9 | Context-driven, screen aligned |
| 4 | `"this recipe needs more garlic"` + recipe_page | `RECIPE`, conf ≥ 0.85 | Recipe-specific, adaptation pipeline |
| 5 | `"hmm"` + plan_meal_detail | `requiresClarification = true`, destinations = [] | Vague low-confidence |
| 6 | `"the app crashed"` + general | `requiresClarification = true`, destinations = [], question explains off-topic | Off-topic |
| 7 | `"I don't like coriander"` + general | `PREFERENCE`, conf ≥ 0.9 | General taste statement → Preference, NOT Recipe |
| 8 | `"this stir fry's coriander was overwhelming"` + recipe_page + meal context | `RECIPE`, conf ≥ 0.85 | Recipe-specific coriander complaint → Recipe, NOT Preference |
| 9 | `"loved it but I won't eat it again"` + recipe_page + meal context | `requiresClarification = true` (contradictory), OR `PREFERENCE` with conf 0.5-0.7 | Sarcasm/contradiction surfacing |
| 10 | `"this was worse than last week's"` + plan_view | `requiresClarification = true` (no specific aspect) | Comparison with no concrete signal |
| 11 | `"loved it!"` + recipe_page + meal context | `PREFERENCE`, conf 0.7 (positive signal — bias toward similar) | Pure positive feedback still routable |
| 12 | `"portions too small"` + nutrition_dashboard | `NUTRITION`, conf ≥ 0.9 | Screen aligned with textual signal |
| 13 | `"portions too small AND too expensive"` + plan_view | `NUTRITION` + `PROVISIONS`, both ≥ 0.8 | Multi-destination, different from example 2 |
| 14 | `"a bit pricey"` + recipe_page (recipe context present) | `PROVISIONS`, conf ≥ 0.7 (NOT Recipe despite recipe screen) | Screen context does not override textual evidence |
| 15 | `"I keep throwing away half the rocket"` + general | `PROVISIONS`, conf ≥ 0.85 (waste signal) | British English regional phrasing; waste-tracking |
| 16 | `"this brought back memories of my nan's cooking"` + recipe_page | `requiresClarification = true` (no actionable aspect) OR `PREFERENCE` conf 0.5-0.6 | Affective feedback with no actionable signal |
| 17 | `"too much salt, too much oil, way too rich"` + recipe_page + meal context | `RECIPE`, conf ≥ 0.9 (all three quotes one aspect: recipe is over-rich) | Multi-aspect collapsing into one destination |
| 18 | `"I'm always hungry around 4pm"` + nutrition_dashboard | `NUTRITION`, conf ≥ 0.85 (energy/hunger signal) | Health observation routing |
| 19 | `"don't have a food processor"` + recipe_page | `PROVISIONS`, conf ≥ 0.85 (equipment) | Equipment feedback typed on recipe page |
| 20 | `"the chicken from Tesco had a really short shelf life and the recipe was bland"` + general | `PROVISIONS` + `PREFERENCE` (no recipe pin → bland is preference, NOT recipe), both ≥ 0.7 | No-pin bland is preference; supplier complaint is provisions |

The eval set lives in `src/test/resources/prompts/feedback-classification-eval.json` once implementation starts. Acceptance threshold per [README.md §Eval-set discipline](README.md): **18/20 (90%) for production promotion**, 15/20 for the first deployment as a learning baseline. Cases 9, 10, and 16 are deliberately allowed two acceptable behaviours — the model can either pause for clarification or commit to the most plausible destination at low confidence; both are reasonable.

## Cost Analysis

| Metric | Estimate |
|---|---|
| Input tokens (after cache hit) | ~150-300 (per-call: feedback text + screen context + recent classifications) |
| Cached input tokens | ~3900 (system prompt + 6 examples + AiTask preamble) |
| Output tokens | ~150-350 (one to two destinations + reasoning) |
| Cost per call (Haiku 4.5 with cache hit) | **~£0.0005** |
| Cost per call (cold cache, < 4096 prefix) | ~£0.002 |
| Calls per active user per week | ~3 (one per feedback submission; re-classifications after clarification add ~10%) |
| **Cost per user per week** | **~£0.0015 cached, ~£0.006 uncached** |
| Cache hit rate target | >70% (cache TTL 5 min; feedbacks are spread out, lower hit rate than the high-burst USDA mapping path) |

For a household of 4: ~12 feedbacks/wk × £0.0005 = ~£0.006/wk household — completely negligible. Even at 100x volume the cost stays under a tenth of a penny per active user per day.

## Failure Modes

Per the standard table in [README.md §Failure-mode boilerplate](README.md), plus task-specific rows:

| Failure | Detection | Behaviour |
|---|---|---|
| Tool-use validation fails (output shape malformed) | AI dispatcher | Retry once with corrective re-prompt; if still malformed, terminal failure → entry → `FAILED` per [feedback.md §Classifier failure handling](../feedback.md#classifier-failure-handling--graceful-degrade) |
| `AiUnavailable` (cost cap or provider down) | AI dispatcher | Defer-and-pending: entry stays `RECEIVED`, sweep retries every 2 min; 24h escalation to `FAILED` |
| Output `requiresClarification = false` AND `destinations` is empty | Validator | Invalid output — retry once with corrective re-prompt explaining: "If destinations is empty, requiresClarification must be true." If still bad, terminal failure |
| Output `requiresClarification = true` AND `destinations` is non-empty | Validator | Invalid; same corrective re-prompt + terminal failure |
| Any destination's confidence < 0.5 AND `requiresClarification = false` | Validator | Invalid (confidence floor violated); retry once explaining the 0.5 floor; on second failure, force-pause to clarification with a generic "could you say a bit more?" question |
| Sum of confidences > 1.5 across multiple destinations claiming high certainty | Validator (soft) | Log a warning to the routing log audit but do not block — high confidence on multiple genuinely-independent aspects is legitimate. Surface in eval review for prompt tuning |
| `evidenceQuotes` contain text not present in the input | Validator | Strip the invented quote; if all quotes are invented, retry once explaining quotes must be substrings; on second failure, drop `evidenceQuotes` to `[]` and proceed |
| `clarificationQuestion` is open-ended (no `?` or "or" or "(a)/(b)/(c)" pattern) | Not detected automatically | Surface in eval review; the model is encouraged toward closed-form via the system prompt but malformed questions will leak through. v2 may add a regex check |
| Output references a destination value not in the enum | Validator | Reject; retry once; if still bad, treat as terminal failure |
| Output's destination list has duplicates | Validator | De-duplicate by destination, keep the highest-confidence row, log a warning |

## AiTask Skeleton

```java
package com.example.mealprep.feedback.domain.service.internal;

import com.example.mealprep.ai.spi.*;
import com.example.mealprep.feedback.api.dto.UiContextDto;
import java.time.Duration;
import java.util.*;

public final class FeedbackClassificationTask implements AiTask<ClassificationResult> {

    private final FeedbackClassificationContext context;

    public FeedbackClassificationTask(FeedbackClassificationContext context) {
        this.context = context;
    }

    @Override public TaskType getTaskType() { return TaskType.FEEDBACK_CLASSIFICATION; }
    @Override public String   getSystemPrompt() { return null; /* template carries it */ }
    @Override public PromptRef getUserPromptRef() {
        return new PromptRef("feedback/classify-feedback", Optional.empty());
    }
    @Override public Map<String, Object> getContext() { return context.toRendererMap(); }
    @Override public ToolDefinition getToolSchema() {
        return ToolDefinitionBuilder.fromRecord(ClassificationResult.class)
            .name("report_classification")
            .description("Report destinations, confidence, payload summaries, and clarification state.")
            .build();
    }
    @Override public Class<ClassificationResult> getResponseType() { return ClassificationResult.class; }
    @Override public UUID getUserId() { return context.userId(); }
    @Override public UUID getTraceId() { return context.traceId(); }
    @Override public Optional<Duration> getTimeoutOverride() { return Optional.empty(); }
}
```

The context record (`FeedbackClassificationContext`) is defined in [feedback.md §Classifier](../feedback.md#classifier-aitask). The renderer map keys are the placeholders used in the user template: `feedback_text`, `screen_context`, `current_meal_context`, `recent_classifications`. `userClarificationText` and `userSelectedHint` from a previous `<0.5` pause are folded into `feedback_text` (appended as `\n\n[user clarification: ...]`) before rendering — they are not separate placeholders. See Decision §6.

## Decisions made (worth user review)

1. **Recipe feedback never routes to RecipeUpdateService directly.** The `RECIPE` value here is a routing label for the adaptation pipeline ([technical-architecture.md](../../design/technical-architecture.md), [feedback.md §Service Interfaces](../feedback.md#service-interfaces)). The router calls `OptimiserService.handleRecipeFeedback`, which then runs the [recipe adaptation prompt](05-recipe-adaptation.md). No exception, no override.
2. **Confidence floor of 0.5 pauses the entire entry — not partial-route.** Locked Tier 3 decision in [feedback.md §Flow 2](../feedback.md#flow-2-classification): one feedback text is one user intent; partial routing risks losing meaning. Trade-off: occasional friction when 80% was clear. The alternative — partial-route + queue clarification for the uncertain part — is a future optimisation. **Worth user review.**
3. **Screen context is BIAS not GROUND TRUTH.** Encoded directly in the system prompt; eval case 14 verifies. The alternative — screen_context as a hard filter — was rejected because the HLD's [Entry Points and Context](../../design/feedback-system.md#entry-points-and-context) calls it an *implicit signal*.
4. **Evidence quotes are mandatory per destination.** Every routing decision cites at least one substring quote. Surfaces in the misclassification correction UI ([feedback.md Flow 4](../feedback.md#flow-4-misclassification-correction-with-replay)) — the user sees "we sent 'too expensive' to Provisions, was that right?" and corrects in one tap. Empty only when destination is purely inferred from screen context (rare; flagged in `overallReasoning`).
5. **Closed-form clarification questions only.** Binary or short multiple-choice — never open-ended. The user has already typed once; the clarifier disambiguates, doesn't re-request. Malformed-question detection is manual in v1; surfaced in eval review.
6. **Recent classifications weighted mildly, never override clear textual signals.** Eval case 5 verifies the model doesn't lean on a recent RECIPE classification when current feedback is genuinely ambiguous. Per-user calibration learning is a v2 concern (potential fine-tune from `feedback_misclassification_corrections`).
7. **Multi-destination splitting returns all destinations in one response.** No alternatives concept; independent aspects each get their own `RoutingDecision`. Cap is four (the destination universe). Downstream router fans out per [feedback.md §Flow 3](../feedback.md#flow-3-routing-multi-destination-split) with one transaction per destination.
8. **Cache-prefix padding to clear Haiku 4.5's 4096-token minimum.** Current ~3900; needs ~200+ more. Padding candidates: extended TaskType banner, expanded PROVISIONS glossary (equipment vs cost vs availability sub-types), additional false-positive examples. Deferred to first iteration; skip if uncached cost (~£0.006/wk/user) is acceptable.
9. **Confidence sum > 1.5 is a soft warning, not a block.** Two genuinely-independent aspects can each have legitimate high confidence. Warning surfaces in eval review without blocking submission.
10. **Userclarification text appended to `feedback_text`, not a separate input.** The renderer concatenates the original feedback and the clarification answer with a marker line. Both are user-authored evidence — the model treats them as one. `attemptNumber` is exposed indirectly via `recent_classifications`.
11. **No `<thinking>` block.** Haiku 4.5 on a hot path; `overallReasoning` captures the audit without an extra thinking pass. If misclassification rate exceeds the 10% target ([feedback-system.md §Metrics](../../design/feedback-system.md#metrics)), revisit — but thinking pushes toward Sonnet tier and raises cost.
12. **`payloadSummary` is one line of natural language, not structured fields.** Keeps the classifier prompt focused on routing; the destination's own prompt or service does the structured extraction. Trade-off: small re-parse cost downstream in exchange for prompt stability when destination schemas change.

## Trial history

**v1 trial — pending.** Run a 6-case agent trial covering the in-prompt patterns; iterate before promoting to the eval set. Highest-risk surfaces: the PREFERENCE-vs-RECIPE distinction (cases 7, 8, 20), screen-context-as-bias (case 14), contradictory/sarcastic edge (case 9). The destination glossary in the system prompt is deliberately strong on PREFERENCE-vs-RECIPE because that's the most common confusion; if the trial shows the model still drifts, expand in v2.
