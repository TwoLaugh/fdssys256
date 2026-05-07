# Prompts — Conventions and Index

*Cross-cutting conventions every prompt in this directory follows. Each per-prompt doc references this rather than restating.*

The 9 prompts are listed below. Each is a separate doc with the system message, user template, tool schema, examples, eval set, cost analysis, failure-mode table, and `AiTask` skeleton. Production prompt text gets lifted from these docs into `src/main/resources/prompts/<module>/<name>.txt` once implementation starts.

## Index

| # | Prompt | Tier | Module | Doc | Lines | Status |
|---|---|---|---|---|---|---|
| 1 | Taste-profile delta updates | Sonnet 4.6 | preference (called by feedback) | [01-taste-profile-delta.md](01-taste-profile-delta.md) | 397 | Drafted |
| 2 | USDA ingredient mapping | Haiku 4.5 | nutrition | [02-usda-ingredient-mapping.md](02-usda-ingredient-mapping.md) | 455 | Drafted + trial-validated (pilot) |
| 3 | Free-text intake parsing | Haiku 4.5 | nutrition | [03-free-text-intake-parse.md](03-free-text-intake-parse.md) | 336 | Drafted |
| 4 | Feedback classification + routing | Haiku 4.5 | feedback | [04-feedback-classification.md](04-feedback-classification.md) | 423 | Drafted |
| 5 | Recipe adaptation | Sonnet 4.6 | adaptation-pipeline | [05-recipe-adaptation.md](05-recipe-adaptation.md) | 481 | Drafted |
| 6 | Recipe HTML extraction (Layer 4) | Haiku 4.5 | recipe extraction pipeline | [06-recipe-html-extraction.md](06-recipe-html-extraction.md) | 391 | Drafted |
| 7 | Recipe discovery filtering | Sonnet 4.6 | discovery | [07-discovery-filtering.md](07-discovery-filtering.md) | 334 | Drafted |
| 8 | Planner Stage C — pick of N | Opus 4.7 | planner | [08-planner-stage-c.md](08-planner-stage-c.md) | 310 | Drafted |
| 9 | Planner Phase 2 — creative augmentation | Opus 4.7 | planner | [09-planner-phase2-augmentation.md](09-planner-phase2-augmentation.md) | 366 | Drafted |

---

## Standard doc structure

Every per-prompt doc has these sections in order:

1. **Wiring** — task name, TaskType, tier, module, called-by, failure path, cache strategy, cost
2. **Purpose** — one paragraph on what the prompt does and what it isn't
3. **Inputs / Outputs** — typed Java records
4. **System Prompt** — full text (the persistent role + rules)
5. **User Prompt Template** — XML-tagged with `{{PLACEHOLDER}}` substitutions
6. **Tool Schema** — JSON schema for structured output
7. **Examples** — 3-6 wrapped in `<examples>` tags, including at least one edge case
8. **Eval Set** — 15-20 regression cases beyond the in-prompt examples
9. **Cost Analysis** — per-call, per-week, cache hit rate target
10. **Failure Modes** — table mapping each to behaviour
11. **AiTask Skeleton** — Java boilerplate
12. **Decisions** — calls made worth user review

## Cross-cutting prompt-design conventions

### Confidence calibration (standard 0-1 scale)

When a prompt asks the LLM to return a confidence score, use this band:

| Confidence | Meaning |
|---|---|
| 0.9-1.0 | Exact match; minimal ambiguity. |
| 0.6-0.9 | Minor ambiguity (e.g. variant choice; both options arguably correct). |
| 0.3-0.6 | Meaningful ambiguity (the alternatives might reasonably be preferred). |
| 0.0-0.3 | Poor match; output is a best-effort guess. Flag in warnings. |

The system prompt should restate this scale verbatim — Claude calibrates better with concrete band labels than with abstract "score from 0 to 1."

### Alternative-vs-primary swap rule

When a prompt produces a primary output plus alternatives:

> **If an alternative would score higher confidence than your primary by more than 0.2, swap them — the alternative becomes primary, the original becomes the alternative.** Otherwise, keep the primary and flag any tension in `warnings`.

Applies to: USDA mapping, feedback classification, recipe HTML extraction, discovery filtering. Doesn't apply to taste-profile delta (no "alternatives" concept) or planner Stage C (single chosenIndex).

### Null-population rules

When the LLM cannot produce a value, what gets nulled and what gets a literal value?

- **Identifier fields** (fdcId, recipeId, destinationId): `null` is the explicit "no match" signal.
- **Description / human-readable fields** that pair with an identifier: `null` when the identifier is null, otherwise non-null.
- **Quantitative fields** (amount, confidence): `0` for confidence (matches the calibration scale's 0-band), `null` for amount when truly unparseable, `0` for amount when the input is "none" (different from "unparseable").
- **Lists** (alternatives, warnings): empty list `[]`, never null. Explicitly empty.

The tool schema should reflect this — required-with-nullable for identifier fields, required-non-nullable for lists, etc. JSON schema `{"type": ["string", "null"]}` for optional-string fields.

### Edge-case example is mandatory

Every prompt's `<examples>` block must include at least one of these:

- "I cannot match this" (empty/null primary output)
- "This input is invalid" (non-food ingredient, off-topic feedback, non-recipe HTML)
- "I'm uncertain — flagging for review" (low confidence + warnings populated)

Without an edge-case example the model defaults to confident output even when wrong.

### Enum-string fields use whitelisted values

Tool schemas list allowed string values explicitly when the field's value space is bounded:

```json
"unit": {
  "type": "string",
  "enum": ["g", "kg", "ml", "l", "tbsp", "tsp", "cup", "oz", "lb",
           "piece", "can", "bottle", "jar", "sprig", "clove",
           "pinch", "dash", "drop", "other"]
}
```

`"other"` is a permitted escape hatch for the rare case but signals to validation code that the value should be checked. Same pattern for: feedback `destinations`, intake `meal_kind`, recipe `cuisine`, etc.

### Output language and tone

- **British English in all examples and warnings** ("colour", "flavour", "optimisation", "summarised") — matches the design docs.
- **Warnings are user-facing.** Write them as if the user might read them in the UI. Avoid implementation-detail jargon ("the fuzzy search returned a low score" → bad; "couldn't find a confident match in the database" → better).
- **Reasoning fields are decision-log audit material.** More technical, more specific. The user might read these too but mostly for "why did the system choose X?"

### Cache strategy — minimum prefix length

Prompt caching activates only above the model's minimum-prefix-token threshold:

| Model | Minimum cacheable prefix |
|---|---|
| Haiku 4.5 | 4096 tokens (note: shorter prompts process without cache) |
| Sonnet 4.6 | 2048 tokens |
| Opus 4.7 | 4096 tokens |

If the system prompt + examples block falls below the threshold, the cache is silently inactive — no error, no cost discount. **Verify by checking `cache_creation_input_tokens` in the API response.** If your cached prefix is below threshold, options:

1. Pad with a stable preamble (project name, AiTask role description, governance rules)
2. Add more examples (often valuable anyway)
3. Accept that caching won't activate; the prompt is small enough that uncached cost is acceptable

Place `cache_control: {"type": "ephemeral"}` on the LAST identical block — typically the final example, which means everything before it gets cached.

### TaskType banner — prefix the user message

Every user prompt template starts with a one-line banner identifying the task:

```
[Task: NUTRITION_INGREDIENT_MAPPING]
```

This appears AFTER the cached prefix breakpoint, in the per-call user message. Two reasons:

1. **Debugging** — when scanning the call log, the task type is the first thing visible.
2. **Cost-cap and rate-limit accounting** — the dispatcher inspects this banner as a sanity check that the AiTask routed to the right TaskType.

### Eval-set discipline

- Stored in `src/test/resources/prompts/<task-name>-eval.json` once implementation starts.
- 15-20 cases per prompt. More for hot-path prompts (USDA mapping, feedback classification); fewer for low-volume prompts (Stage C, discovery filtering).
- Acceptance threshold: **18/20 (90%)** for prompts to be promoted to production. Lower threshold (15/20 = 75%) for the first deployment as a learning baseline.
- Cases must include: representative happy paths, regional variants, ambiguous inputs, edge cases (empty/null), and at least one adversarial input (nonsense, attempts to break shape).

### Failure-mode boilerplate

Every prompt's failure-mode table includes these standard rows (plus task-specific ones):

| Failure | Detection | Behaviour |
|---|---|---|
| Tool-use validation fails (output shape malformed) | AI dispatcher | Retry once with corrective re-prompt; if still malformed, terminal failure |
| `AiUnavailable` (cost cap or provider down) | AI dispatcher | Per-feature graceful-degrade per [style-guide §AI Service](../style-guide.md#ai-service--graceful-degradation) |
| Output references an identifier not in inputs | Validator | Reject; retry once; if still bad, treat as no-match |
| Output's confidence < threshold | Validator | Persist with low-confidence flag; surface for user review |

Per-prompt rows extend this for task-specific failure modes (e.g. unit conversion sanity check, drained-weight rule, household-routing fallback).

### AiTask skeleton boilerplate

Every AiTask implementation follows the same shape — see [02-usda-ingredient-mapping.md §AiTask Skeleton](02-usda-ingredient-mapping.md#aitask-skeleton) for the canonical example. Variations are: input fields, response type, TaskType, timeout override.

The skeleton lives in `<module>/domain/service/internal/`. The system prompt text lives in `<module>/src/main/resources/prompts/<task-name>-system.txt`. The user prompt template lives in `<task-name>-user.txt`. Examples live in `<task-name>-examples.txt` (loaded as a third file, concatenated to the system prompt at startup — keeps each file under reasonable line counts).

## Per-tier guidance summary

| Tier | Examples needed | Effort | Notes |
|---|---|---|---|
| Haiku 4.5 (cheap) | 4-6 | n/a | Examples are mandatory; without them Haiku underperforms Sonnet-with-examples. Keep prompt under 4k tokens (cache threshold). Match prompt complexity to model. |
| Sonnet 4.6 (mid) | 3-5 | n/a | Handles nuance well; fewer examples still effective. Can include light reasoning instructions. |
| Opus 4.7 (frontier) | 3-4 | `high` | Set `effort = high` for intelligence-sensitive tasks. Examples optional but cheap due to caching. Use chain-of-thought via `<thinking>` tags only when multi-step reasoning required. |

## Eval and acceptance flow

1. **Draft prompt** + initial example set (4-6 examples in-prompt).
2. **Build eval set** (15-20 cases). Mix sources from: design docs, user research, adversarial brainstorm, edge cases the in-prompt examples don't cover.
3. **Trial via agent** (proxy for production model — see `lld/prompts/02-usda-ingredient-mapping.md` history for the trial pattern). Identify gaps, adjust prompt.
4. **Re-trial after each material change.** Avoid changing examples and instructions in the same iteration — easier to attribute improvements.
5. **Promote to production** when eval acceptance hits 18/20 + manual review of a sample of the call log on real data confirms quality.
6. **Tune in production** based on call-log analysis. Each tuning increment bumps the prompt's content hash; old hash is preserved in `prompt_template` table for audit.

## Prompts NOT in this directory

These have AiTask types specced (in `lld/ai.md`) but live as prompt content elsewhere:

- **Embedding tasks** (`RECIPE_EMBEDDING`, `TASTE_PROFILE_EMBEDDING`) — not prompts, just text-in-vector-out calls. The "prompt" is the input-text composition, which is deterministic code (recipe name + description + cuisine + ... fields concatenated in fixed order). Lives in the calling module's helper.
- **Recipe URL extraction (HTML parsing pre-AI)** — Layers 1-3 of the extraction pipeline are deterministic, not prompts. Layer 4 is the only AI step → prompt #6 in this directory.

## Pointers

- AI Service abstraction: [../ai.md](../ai.md)
- Style guide §AI Service — Graceful Degradation: [../style-guide.md](../style-guide.md)
- Embeddings track: [../style-guide.md §Embeddings](../style-guide.md)
- Calling modules: [../nutrition.md](../nutrition.md), [../feedback.md](../feedback.md), [../recipe.md](../recipe.md), [../adaptation-pipeline.md](../adaptation-pipeline.md), [../discovery.md](../discovery.md), [../planner.md](../planner.md), [../recipe-extraction-pipeline.md](../recipe-extraction-pipeline.md)
