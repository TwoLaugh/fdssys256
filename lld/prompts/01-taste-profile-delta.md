# Prompt — Taste Profile Delta Updates

*Translates classified feedback events into structured operations on the user's taste profile JSONB document. Mid-tier; the most nuanced of the cheap-and-mid prompts.*

Cross-cutting conventions (confidence scale, null-population, edge-case-mandatory, cache strategy, TaskType banner) defer to [README.md](README.md). This doc only restates them where the application is task-specific.

## Wiring

| | |
|---|---|
| AiTask name | `PreferenceTasteProfileDeltaTask` |
| TaskType | `PREFERENCE_DELTA_UPDATE` |
| Tier | Sonnet 4.6 (mid) |
| Module | `feedback` (calls into `preference` via `applyTasteProfileDeltas`) |
| Called by | The feedback module's delta-update task, after classification has routed feedback to `Destination.PREFERENCE` |
| Failure path | `AiUnavailable` → feedback entry sits with `status = pending_delta`; retried when AI cap resets. Malformed delta → whole batch rejected per [lld/preference.md §Flow 3](../preference.md), no partial application. |
| Cache | Stable system prompt + examples + taste-profile-document schema cached `ephemeral`; per-call user feedback batch + current document varies |
| Cost | ~£0.03/call; runs ~once per 5 feedback events; ~£0.15/wk per active user |

## Purpose

The feedback classifier (prompt #4) routes preference-flavoured feedback to this task. This prompt's job: read the user's current taste profile document and a batch of classified feedback events, produce a list of `TasteProfileDelta` operations that, when applied, refine the document in the right direction.

It's the **only AI path that can write to the taste profile**. The deterministic application (`TasteProfileDeltaApplier` in [lld/preference.md](../preference.md)) validates each delta and either applies the whole batch or rejects it. This prompt's responsibility is producing well-formed, well-justified deltas — not deciding whether they're safe to apply.

This is **not**:
- The feedback classifier (that's prompt #4 — it decides whether feedback is preference-flavoured at all)
- The hard-constraint editor (allergies/dietary identity are user-only via direct edit, never AI; per [lld/preference.md §Three tiers](../preference.md))
- A general taste-profile editor (no full re-writes; only delta operations)

## Inputs / Outputs

**Inputs (`AiTask.getContext()`):**

```java
Map.of(
    "current_taste_profile",  TasteProfileDocument,         // ~2.5k token JSON document
    "feedback_batch",         List<ClassifiedFeedbackEvent>, // 1-10 events the classifier routed here
    "recent_archive_ids",     List<String>,                  // last 30 archived item keys; helps detect re-emergence
    "user_id",                UUID
)

record ClassifiedFeedbackEvent(
    UUID feedbackId,
    String userText,                       // verbatim
    String contextSummary,                 // "feedback on Wednesday's chicken stir-fry, eaten 2 days ago"
    BigDecimal classifierConfidence,
    Instant occurredAt
) {}
```

The `recent_archive_ids` field exists so the model can detect re-emerging preferences ("I changed my mind about coriander" → `RePromote` op rather than fresh `Add`).

**Output (structured tool-use):**

```java
record TasteProfileDeltaResponse(
    List<TasteProfileDelta> deltas,        // 0-50; empty if feedback doesn't warrant changes
    String overallReasoning,                // for decision log
    List<String> warnings
) {}

sealed interface TasteProfileDelta {
    String fieldPath();                    // "likes.cuisines" / "dislikes.ingredients" / "lifestyle.cooking_style.preferences" / etc.
    String evidenceFeedbackId();           // which feedback event drove this delta
    String reasoning();                    // 1-line; recorded with the delta in version history

    record Add(String fieldPath, String item, String notes,
               String evidenceFeedbackId, String reasoning, Confidence confidence) implements TasteProfileDelta {}

    record Remove(String fieldPath, String item,
                  String evidenceFeedbackId, String reasoning) implements TasteProfileDelta {}

    record Update(String fieldPath, String item, String newNotes,
                  String evidenceFeedbackId, String reasoning) implements TasteProfileDelta {}

    record Archive(String fieldPath, String item, String archiveReason,
                   String evidenceFeedbackId, String reasoning) implements TasteProfileDelta {}

    record RePromote(String archivedItemKey, String fieldPath,
                     String evidenceFeedbackId, String reasoning) implements TasteProfileDelta {}

    record PromoteExperiment(String hypothesisId, String fieldPath, String item,
                             String evidenceFeedbackId, String reasoning) implements TasteProfileDelta {}

    record DiscardExperiment(String hypothesisId,
                             String evidenceFeedbackId, String reasoning) implements TasteProfileDelta {}

    record UpdateNotes(String fieldPath, String newNotes,
                       String evidenceFeedbackId, String reasoning) implements TasteProfileDelta {}
}

enum Confidence { HIGH, MEDIUM, LOW }
```

The eight delta op types match `lld/preference.md`'s sealed interface exactly. The response includes `overallReasoning` (a per-batch summary for the decision log) plus `warnings` (per-call notes the user might want to know — e.g. "this batch contains 3 archive ops, surfacing for confirmation").

## System Prompt

```
You are a careful editor of a user's taste profile, a JSON document representing their food preferences. Each call you receive: (1) the current profile, (2) a batch of recent feedback the classifier routed to you. Your job: produce a list of well-formed delta operations that refine the profile based on what the feedback genuinely tells you.

You are NOT the only signal feeding this profile. The user can edit it directly. You should be conservative — better to skip a delta than to make a wrong one. Wrong deltas accumulate as bad recommendations downstream.

THE PROFILE'S STRUCTURE (read carefully):
- likes.cuisines, likes.ingredients, likes.cooking_methods, likes.flavour_notes
- dislikes.cuisines, dislikes.ingredients, dislikes.cooking_methods, dislikes.flavour_notes
- experiments.hypotheses[]: things the system is currently testing about the user (e.g. "user might like bitter flavours when paired with sweet")
- archive: items previously in likes/dislikes that the user moved away from; archive entries can be re-promoted

EACH DELTA OP:
- Add — add a new item to a likes/dislikes list. Use when feedback introduces a preference not yet captured.
- Remove — remove an item from a list. Use sparingly — usually you want Archive (preserves history) over Remove.
- Update — modify the notes on an existing item without changing the item itself. Use when feedback adds nuance to an existing preference.
- Archive — move an item from a list into the archive. Use when feedback indicates the user has moved away from a preference (not just one-off dislike).
- RePromote — bring an archived item back into a list. Use when feedback indicates re-emergence of a preference.
- PromoteExperiment — convert a hypothesis from experiments into a confirmed like/dislike. Use when feedback aligns with the hypothesis.
- DiscardExperiment — remove a hypothesis as disproven. Use when feedback contradicts the hypothesis.
- UpdateNotes — modify free-text notes on a section of the profile (e.g. "summary of this user's flavour preferences"). Rare; use when feedback warrants a meta-level annotation.

WHAT FEEDBACK USUALLY DOES NOT WARRANT A DELTA:
- One-off "this dish was too salty" → could be the recipe, the cook, the user's mood that day. Do NOT add "salty" to dislikes from a single event.
- Feedback referencing an experiment without aligning with the hypothesis → leave the experiment alone.
- Mood/health complaints ("felt sluggish after") → not preference; the classifier shouldn't have routed this here, but if it did, return empty deltas with a warning.
- Comments about cost, time, equipment → not preference (those route to Provisions/Lifestyle); return empty deltas with a warning.

CONFIDENCE FOR ADD OPS:
- HIGH: clear, repeated signal across multiple feedback events; or a single very explicit statement ("I've decided I really like coriander").
- MEDIUM: single explicit statement that doesn't reference a one-off context.
- LOW: inferential ("they cleaned the plate" → likely positive but not stated). Often skip rather than Add at LOW.

THE THREE-EVENT RULE:
For Add of new likes/dislikes: only act when at least 2-3 events agree, OR a single explicit statement is made. Single ambiguous events → consider Update (add nuance to existing item) or empty (no delta).

ARCHIVE VS REMOVE:
Default to Archive. Remove only when the user explicitly says "delete that" or when the item was clearly added in error.

EVIDENCE AND REASONING:
Every delta cites the specific feedback event that drove it (`evidenceFeedbackId`). The `reasoning` is one short sentence the user might read in the version history — write it as a justification, not an internal note.

THREE-EVENT RULE EDGE CASE: when a single feedback event is so explicit it warrants a HIGH-confidence Add ("I've stopped eating dairy"), apply but flag in warnings. The user should review.

NUMERICAL LIMITS:
- Maximum 50 deltas per response (locked in lld/preference.md).
- Maximum 3 archive ops per batch — if more warranted, surface in warnings; the user reviews before applying.
- Maximum 1 UpdateNotes op per response.
```

## User Prompt Template

```
[Task: PREFERENCE_DELTA_UPDATE]

<current_taste_profile>
{{TASTE_PROFILE_JSON}}
</current_taste_profile>

<feedback_batch>
{{FEEDBACK_EVENTS_JSON}}
</feedback_batch>

<recent_archive_ids>
{{RECENT_ARCHIVE_IDS}}
</recent_archive_ids>

Produce delta operations that refine the profile based on the feedback. Be conservative; cite evidence; respect the three-event rule. If no deltas are warranted, return an empty list with a brief overallReasoning.
```

## Examples (in-prompt, wrapped in `<examples>`)

Five examples covering: clear add, three-event rule (single ambiguous → no delta), archive over remove, re-emergence (RePromote), off-topic (empty + warning).

```
<examples>

<example>
<input>
<current_taste_profile>
{ "likes": { "cuisines": [...], "ingredients": [{"item":"chicken","notes":"versatile, weeknight staple"}, ...], "cooking_methods": [...] },
  "dislikes": { "ingredients": [], "cuisines": [], "cooking_methods": [], "flavour_notes": [] },
  "experiments": { "hypotheses": [] },
  "archive": [] }
</current_taste_profile>
<feedback_batch>
[
  { "feedbackId":"f1", "userText":"I really love how prawns work in stir fries — that quick high-heat thing", "contextSummary":"feedback on Tuesday's prawn stir fry", "classifierConfidence":0.92, "occurredAt":"..." }
]
</feedback_batch>
<recent_archive_ids>[]</recent_archive_ids>
</input>
<output>
{
  "deltas": [
    { "type": "Add", "fieldPath": "likes.ingredients", "item": "prawns",
      "notes": "Especially in quick high-heat preparations like stir-fry",
      "evidenceFeedbackId": "f1", "reasoning": "Single explicit positive statement about prawns in a specific cooking context.",
      "confidence": "MEDIUM" }
  ],
  "overallReasoning": "Single explicit positive statement; adding prawns to likes with the cooking-context note.",
  "warnings": []
}
</output>
</example>

<example>
<input>
<current_taste_profile>
{ "likes": {"ingredients":[{"item":"chicken","notes":"weeknight staple"}], ...},
  "dislikes": {"ingredients":[], ...}, ... }
</current_taste_profile>
<feedback_batch>
[
  { "feedbackId":"f1", "userText":"this was too salty", "contextSummary":"feedback on Tuesday's chicken stir fry", "classifierConfidence":0.85, "occurredAt":"..." }
]
</feedback_batch>
<recent_archive_ids>[]</recent_archive_ids>
</input>
<output>
{
  "deltas": [],
  "overallReasoning": "Single one-off 'too salty' on one dish. Insufficient signal to add salt or saltiness as a dislike — could be the recipe, the cook, or mood. Three-event rule: wait for repeated signal.",
  "warnings": []
}
</output>
</example>

<example>
<input>
<current_taste_profile>
{ "likes": {"ingredients":[{"item":"coriander","notes":"liked in Thai dishes"}], ...},
  "dislikes": {"ingredients":[], ...},
  "archive":[] }
</current_taste_profile>
<feedback_batch>
[
  { "feedbackId":"f1", "userText":"I've actually gone off coriander, please stop putting it in everything", "contextSummary":"general preference feedback on plan view", "classifierConfidence":0.95, "occurredAt":"..." }
]
</feedback_batch>
<recent_archive_ids>[]</recent_archive_ids>
</input>
<output>
{
  "deltas": [
    { "type": "Archive", "fieldPath": "likes.ingredients", "item": "coriander",
      "archiveReason": "User explicitly said they've gone off coriander.",
      "evidenceFeedbackId": "f1",
      "reasoning": "Explicit statement that the user has moved away from coriander; archive (not remove) preserves history in case they come back." }
  ],
  "overallReasoning": "Explicit move-away statement on an existing like. Archived rather than removed; preserves history.",
  "warnings": []
}
</output>
</example>

<example>
<input>
<current_taste_profile>
{ "likes": {"ingredients":[], ...},
  "dislikes": {"ingredients":[{"item":"olives","notes":"can't stand them"}], ...},
  "archive":[ { "key":"olives_arch_2025", "fieldPath":"dislikes.ingredients", "item":"olives", "archivedAt":"2025-...", "archiveReason":"" } ] }
</current_taste_profile>
<feedback_batch>
[
  { "feedbackId":"f1", "userText":"actually had olives at a tapas place last week and they were great, I think I was wrong about them", "contextSummary":"general preference feedback on settings page", "classifierConfidence":0.9, "occurredAt":"..." }
]
</feedback_batch>
<recent_archive_ids>["olives_arch_2025"]</recent_archive_ids>
</input>
<output>
{
  "deltas": [
    { "type": "RePromote", "archivedItemKey": "olives_arch_2025", "fieldPath": "likes.ingredients",
      "evidenceFeedbackId": "f1",
      "reasoning": "User explicitly recanted their previous dislike; re-promoting from archive into likes." }
  ],
  "overallReasoning": "Re-emergence of a previously-archived item. Archive entry exists; RePromote is the right op.",
  "warnings": ["User changed their mind on a previously-archived item; surfacing in case the user wants to confirm before this lands in recommendations."]
}
</output>
</example>

<example>
<input>
<current_taste_profile>
{ "likes":{...}, "dislikes":{...}, "experiments":{"hypotheses":[]}, "archive":[] }
</current_taste_profile>
<feedback_batch>
[
  { "feedbackId":"f1", "userText":"this was way too expensive, I can't keep buying ingredients like this", "contextSummary":"feedback on weekend's lamb roast", "classifierConfidence":0.55, "occurredAt":"..." }
]
</feedback_batch>
<recent_archive_ids>[]</recent_archive_ids>
</input>
<output>
{
  "deltas": [],
  "overallReasoning": "Feedback is about cost, not preference. Classifier confidence is low (0.55) and the content is clearly Provisions-flavoured. No taste-profile delta is warranted.",
  "warnings": ["Feedback was routed to PREFERENCE but content is about cost. Classifier should have routed to PROVISIONS — possible misclassification worth review."]
}
</output>
</example>

</examples>
```

## Eval Set (regression)

| # | Input | Expected | Tests |
|---|---|---|---|
| 1 | Two feedback events both saying "I love spicy food" → no current spicy preference recorded | Add spicy/heat to likes.flavour_notes, HIGH confidence | Multiple-event consensus |
| 2 | Five "too salty" events on five different dishes over a week | Add "salty preparations" to dislikes with note about consistency | Three-event rule positive case |
| 3 | One feedback "I think I'm becoming vegetarian" + classifier confidence 0.95 | Empty deltas with warning that this looks like a hard-constraint signal (dietary identity), should be user-confirmed via direct edit not via AI delta | Hard-constraint boundary respect |
| 4 | Feedback praises specific recipe ("this carbonara was incredible") with no general preference signal | Empty deltas (recipe-specific, not preference-general) | Recipe-vs-preference distinction |
| 5 | Feedback "I liked the texture of the tofu but the flavour was bland" | Possible UpdateNotes on existing tofu like with texture-positive note; or Add to likes.flavour_notes for "texture importance"; pick one with rationale | Multi-aspect feedback |
| 6 | Feedback explicitly references experiments hypothesis ("you suggested I might like bitter+sweet, the rocket-and-honey salad confirmed it") | PromoteExperiment op | Experiment confirmation path |
| 7 | Feedback "the rocket-and-honey thing didn't work for me, too weird" → matching active experiment | DiscardExperiment | Experiment disproof |
| 8 | Feedback "I've decided" / "I've started" / "I no longer" — explicit move statements | Add or Archive accordingly, HIGH confidence, single-event-acceptable | Three-event-rule edge case |
| 9 | Feedback that's ambiguous between two items ("the dish") | Empty deltas, warning about ambiguity | Ambiguity safety |
| 10 | Same item already in likes; feedback adds nuance ("I've realised I prefer the chicken when it's char-grilled") | UpdateNotes on existing chicken like with method preference | Update vs Add |
| 11 | Same item appearing in both likes and dislikes (data inconsistency from earlier bad delta) | Empty deltas, warning about data state, suggest user manual review | Defensive handling |
| 12 | 11+ feedback events in batch | Process all, but cap deltas at limit; warning if hitting cap | Volume handling |
| 13 | Feedback in another language ("muy salado") | Best-effort processing if recognisable; warning if not; never invent a delta from un-parseable text | Language robustness |
| 14 | Mood feedback "felt sluggish after the curry, like I always do with curries" | Borderline — could route to a "curries don't work for me" Add but the language is mood-flavoured. Conservative: empty + warning. | Mood-vs-preference judgement |
| 15 | Feedback contradicts itself within one entry ("loved it but won't eat again") | Empty + warning about contradiction | Self-contradiction |
| 16 | Empty feedback batch (zero events) | Empty deltas, no warnings, overallReasoning notes empty input | Empty-input handling |
| 17 | Feedback + recent_archive_ids contains a matching item: "I've started eating fish again" + "fish" in archive | RePromote fish from archive | Archive lookup correctness |
| 18 | Feedback that adds 5+ separate preferences in one entry ("I want to try Korean food, I love spicy, I'm avoiding gluten now") | Multiple deltas; flag the dietary-identity signal ("avoiding gluten") as a warning that the user should confirm via direct edit | Multi-signal entry |

Acceptance threshold: **15/18** for ship; **17/18** to consider mature. Lower than prompt #2's 18/20 because preference judgement is genuinely subjective and mistakes recover via the next feedback batch — false-add isn't catastrophic, just adds an item the user can remove.

## Cost Analysis

| Metric | Estimate |
|---|---|
| Input tokens (cache hit) | ~500-1500 (per-call: profile + feedback batch) |
| Cached input tokens | ~3500 (system prompt + 5 examples) |
| Output tokens | ~300-800 |
| Cost per call (Sonnet 4.6 with cache hit) | **~£0.03** |
| Cost per call (cold cache) | ~£0.06 |
| Calls per active user per week | ~1-2 (one per ~5 feedback events) |
| **Cost per user per week** | **~£0.03-0.06** |

## Failure Modes

Beyond the README boilerplate:

| Failure | Behaviour |
|---|---|
| Delta references a `fieldPath` that doesn't exist in the profile | Validator (deterministic) rejects whole batch; retry once with corrective re-prompt; if still bad, surface as `delta_invalid` for the feedback module to log and skip |
| Delta references an `archivedItemKey` not in `recent_archive_ids` | Validator rejects the specific delta; other deltas in batch continue |
| > 3 archive ops in one batch | Apply but populate warnings; UI surfaces for user confirmation per the system-prompt rule |
| > 50 deltas total | Validator caps at 50; warns; user reviews truncation |
| `overallReasoning` is empty when `deltas` is non-empty | Validator rejects; require reasoning for audit |
| Hard-constraint signal detected (allergy/dietary identity) in feedback | Empty deltas (hard constraints are user-only) + warning surfaces "this looks like a {allergy|dietary} signal — please confirm via Settings" |

## AiTask Skeleton

```java
public final class PreferenceTasteProfileDeltaTask implements AiTask<TasteProfileDeltaResponse> {
    private final TasteProfileDocument currentProfile;
    private final List<ClassifiedFeedbackEvent> feedbackBatch;
    private final List<String> recentArchiveIds;
    private final UUID userId;
    private final UUID traceId;

    @Override public TaskType getTaskType() { return TaskType.PREFERENCE_DELTA_UPDATE; }
    @Override public String getSystemPrompt() { return SYSTEM_PROMPT; }
    @Override public PromptRef getUserPromptRef() {
        return new PromptRef("preference/taste-profile-delta-user", Optional.empty());
    }
    @Override public Map<String, Object> getContext() {
        return Map.of(
            "current_taste_profile", currentProfile,
            "feedback_batch",        feedbackBatch,
            "recent_archive_ids",    recentArchiveIds
        );
    }
    @Override public ToolDefinition getToolSchema() {
        return ToolDefinitionBuilder.fromRecord(TasteProfileDeltaResponse.class).build();
    }
    @Override public Class<TasteProfileDeltaResponse> getResponseType() { return TasteProfileDeltaResponse.class; }
    @Override public UUID getUserId() { return userId; }
    @Override public UUID getTraceId() { return traceId; }
    @Override public Optional<Duration> getTimeoutOverride() { return Optional.of(Duration.ofSeconds(15)); }
}
```

## Decisions made (worth user review)

1. **Confidence as enum (HIGH/MEDIUM/LOW)** rather than 0-1 scale, only on `Add`. Other ops don't have confidence — they're decisive moves with explicit user signals. The enum matches the qualitative nature of preference judgement better than fake precision.
2. **Three-event rule for Add** baked into the system prompt: single ambiguous events skip; only act on consensus or explicit statements. Conservative bias.
3. **Archive over Remove as default** — preservation matters; the user can re-promote.
4. **Hard-constraint signals are explicitly excluded** — if feedback looks like an allergy or dietary identity signal, return empty + warning. This prompt never mutates hard constraints.
5. **Off-topic feedback returns empty + warning about classifier misroute** — gives downstream visibility into classifier quality without rejecting the feedback.
6. **Maximum 3 archive ops per batch** is a guardrail against over-pruning. Anything more surfaces for user review.
7. **Eval threshold lowered to 15/18 (vs prompt #2's 18/20)** — preference judgement is genuinely subjective. False-add is recoverable; false-archive is recoverable. The cost of being too cautious is mostly drift over time.
8. **`recent_archive_ids` is contextual not authoritative** — the model uses it to detect re-emergence but the validator checks every `archivedItemKey` against the actual archive table.
