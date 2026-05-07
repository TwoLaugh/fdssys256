# Recipe System — Design

*Catalogue and culinary intelligence in one subsystem. Stores recipes, adapts them against constraints, evolves them from feedback.*

## What It Is

The Recipe System is the only component that reasons about food as food. It holds every recipe in the system and is the sole place where adaptation — ingredient swaps, portion adjustments, method changes — is proposed and evaluated. It has two cleanly separated internal concerns:

| Concern | Role | Previously called |
|---|---|---|
| **Catalogue** | Stores recipes, versions, branches, and substitutions. Exposes query services. Pure data — no reasoning. | "Recipe Engine" |
| **Adaptation Pipeline** | Proposes changes to recipes using culinary and nutritional intelligence. Classifies changes as version, branch, or substitution. | "Recipe Optimiser" |

These concerns live in one subsystem because they are tightly coupled: every adaptation is a write to the catalogue, and every catalogue change that isn't a manual edit passes through the pipeline. Separating them into two services would create a chatty, high-coupling boundary for no gain.

This is **not**:
- The Meal Planner (which composes plans from recipes — the Recipe System is the pool it draws from)
- The Nutrition Model (which owns targets and does the USDA math — the Recipe System stores the results)
- The Preference Model (which owns taste profile and hard constraints — the Recipe System reads them as inputs)
- The Feedback System (which classifies feedback and routes it — the Recipe System receives routed recipe feedback and does the creative work)
- A recommendation engine (scoring and similarity ranking — planner concerns)

## Three Pillars

The system has three structural pillars that organise the rest of this doc:

1. **Two catalogues** — user (curated, approval required) and system (AI-managed, direct write).
2. **Three change types** — version (linear improvement), branch (creative fork), substitution (constraint-driven overlay).
3. **One adaptation pipeline** — a single job abstraction fed by four sources (import, feedback, data-model change, plan-time).

Everything else — storage schemas, approval UX, observability, failure handling — hangs off these three.

---

## Two Catalogues

### User catalogue

Recipes the user has imported, entered manually, or promoted from the system catalogue. The user's curated library.

- The adaptation pipeline **proposes** changes but never applies them without approval.
- Manual edits by the user are always permitted.
- The user can demote a recipe back to the system catalogue (soft delete; data preserved).

### System catalogue

AI-managed pool: discovered online, AI-generated to fill planner gaps, or adapted from imports.

- The adaptation pipeline **applies changes directly**, giving the planner a flexible set of options.
- The user can **promote** any system recipe to their library with one tap.
- System recipes unused for 3 months (no feedback, no promotion, not in any plan) are **archived** — retained in storage but excluded from the planner's index.

Both catalogues share the same schema, versioning, branching, and substitution mechanisms. The only difference is the approval model.

---

## Recipe Data Model

> **ID type note:** Recipe IDs (and all other entity IDs) are `UUID`s in storage and Java code, per the LLD style guide. The integer values shown in the JSON examples below (`"recipe_id": 42`) are illustrative for readability; the actual storage and wire types are UUIDs. Java service signatures shown later in this doc that use `Long recipeId` should be read as `UUID recipeId` — the LLD applies the conversion uniformly.

### Core recipe

```json
{
  "recipe_id": 42,
  "catalogue": "user",
  "name": "Chicken Stir Fry",
  "description": "Quick weeknight stir fry with crispy vegetables",
  "current_version": 3,
  "current_branch": "main",

  "ingredients": [
    {
      "name": "chicken thighs",
      "quantity": 400,
      "unit": "g",
      "preparation": "sliced",
      "ingredient_mapping_key": "chicken thigh skinless raw",
      "optional": false
    }
  ],

  "method": [
    {"step": 1, "instruction": "Slice the chicken thighs into strips."}
  ],

  "metadata": {
    "servings": 2,
    "prep_time_mins": 10,
    "cook_time_mins": 15,
    "total_time_mins": 25,
    "equipment_required": ["wok", "hob"],
    "stores_well": {"fridge_days": 3, "freezer_weeks": 8},
    "packable": true,
    "cuisine": "East Asian",
    "meal_types": ["lunch", "dinner"],
    "tags": {
      "protein": "chicken",
      "cooking_method": "stir-fry",
      "complexity": "minimal",
      "flavour_profile": ["umami", "savoury"],
      "dietary_flags": []
    }
  },

  "character_fingerprint": { ... },
  "nutrition_per_serving": { ... },
  "rating": { ... },

  "data_quality": "user_verified",
  "source": {"type": "imported", "url": "https://example.com/..."},
  "created_at": "2026-04-01T12:00:00Z",
  "updated_at": "2026-04-15T10:00:00Z"
}
```

**Ingredients** are structured data, not free text. Every ingredient has an `ingredient_mapping_key` linking to the Nutrition Model's USDA mapping cache. The `optional` flag tells the planner which ingredients can be dropped (garnishes, toppings) without breaking the recipe.

**Method** is a structured step list. Each step is a discrete instruction — enables AI reasoning about recipes ("which step uses the food processor?") and future features like interactive cooking mode.

**Metadata tags** are AI-inferred on import/creation and refreshed on version. Dimensions are fixed (protein, cooking_method, complexity, flavour_profile, dietary_flags); values within each dimension are free. The Recipe System stores them; the planner decides how to weight them. If embeddings are added later (`pgvector`), the Recipe System stores the vector column but the embedding pipeline is planner-owned.

**`nutrition_per_serving`** is always calculated by the Nutrition Engine, never taken from external sources. On import, any external nutrition data is discarded. `nutrition_status`: `calculated`, `pending` (waiting for USDA mapping), or `partial` (some ingredients unmapped).

**`data_quality`** reflects trust in the ingredient list:
- `user_verified` — manually entered or confirmed. Highest trust.
- `imported` — extracted from URL. Medium trust.
- `ai_generated` — created by the system. Medium trust.
- `web_discovered` — scraped from search. Lowest trust.

Low-trust recipes get a visual indicator in the plan.

Two fields depart from the older engine doc and warrant their own sections below: **character fingerprint** and **multi-dimensional rating**.

### Character fingerprint

A structured summary of what makes this recipe *this dish and not a different dish*. Extracted once on recipe entry (by the adaptation pipeline on the import job) and refreshed only on branch creation. Stored as a stable constant for the recipe's life on its current branch.

```json
"character_fingerprint": {
  "defining_ingredients": ["soy sauce", "sesame oil", "ginger"],
  "defining_techniques": ["high-heat wok cooking", "short cook time"],
  "texture_essentials": ["crispy vegetables", "tender protein"],
  "flavour_anchors": ["umami", "aromatic"],
  "complexity_tier": "minimal",
  "cuisine_anchor": "East Asian — stir fry"
}
```

**Why this exists.** Without a fingerprint, the adaptation pipeline has to re-derive the dish's "character" from the full recipe text on every job. That is expensive (whole recipe in context every call), inconsistent (the AI's sense of character drifts between calls), and unauditable (no record of what was being preserved). A fingerprint makes character explicit, cheap to pass, and stable.

**How it's used.** When the pipeline proposes a change, it checks the change against the fingerprint rather than re-deriving the target. Classification into version vs branch (see below) also uses the fingerprint: if the change contradicts a defining ingredient or technique, it's a branch candidate.

**When it changes.** Only on branch creation — by definition a branch is a character shift, so the child branch extracts its own fingerprint. A version never changes the fingerprint; if an adaptation would, it should be classified as a branch instead.

### Multi-dimensional rating

```json
"rating": {
  "taste": {"score": 82, "count": 5},
  "effort_worth_it": {"score": 70, "count": 5},
  "portion_fit": {"score": 85, "count": 5},
  "repeat_value": {"score": 78, "count": 5},
  "aggregate": 78,
  "last_rated": "2026-04-14"
}
```

A single 0-100 rating collapses too many signals into one number. A "tasty but too much effort" meal and a "dull but efficient" meal can both land at 65, and the adaptation pipeline can't tell them apart.

Four dimensions, each 0-100 with a confidence count:

- **taste** — did it taste good? The core signal.
- **effort_worth_it** — was the prep/cook time worth the result? Signals whether to simplify the method.
- **portion_fit** — were the portions right for this user's appetite?
- **repeat_value** — would they want this again soon?

`aggregate` is a weighted blend for compact displays (planner index, list views). The detailed rating UI still presents stars per dimension — it's just four half-star pickers instead of one. The default rating path asks only for `taste` (one tap); the user can open "rate in detail" for the others.

**How the pipeline uses it.** Low `effort_worth_it` with high `taste` → propose method simplification, leave flavour alone. Low `portion_fit` → adjust serving sizes. Low `repeat_value` with high `taste` → a once-in-a-while dish; planner weights it lower for frequency. Low `taste` → primary signal for flavour adaptation.

---

## Versioning

Every recipe change on a branch creates a new version. Versions represent the system's best current guess at the recipe — usually strictly better than prior versions, but a user can revert to any prior version if a newer one turns out worse in practice.

```json
{
  "version_id": 3,
  "recipe_id": 42,
  "branch": "main",
  "parent_version_id": 2,
  "changes": {
    "ingredients": [{"action": "modified", "field": "soy sauce", "from": "1 tbsp", "to": "2 tbsp"}],
    "method": [],
    "metadata": [{"action": "modified", "field": "total_time_mins", "from": 20, "to": 25}]
  },
  "change_reason": "User feedback: needed more flavour. Doubled soy sauce.",
  "trigger": "feedback",
  "created_at": "2026-04-15T10:00:00Z",
  "created_by": "adaptation_pipeline",
  "trace_id": "trace-abc-123"
}
```

- `trace_id` ties the version to its adaptation trace (see [Observability](#observability)).
- `changes` is a structured diff — not free text — so any two versions compare cleanly.
- Ratings are **per version**. Version 2 scoring 60 and version 3 (with doubled soy sauce) scoring 85 confirms the change was an improvement. If version 3 under-performs, the user reverts and the pipeline's quality dashboard picks up the miss.

**Retention.** Keep all versions. Diffs are small (a few KB) and the audit trail is valuable.

---

## Branching

Branches are creative forks where both variants have genuine independent merit. A branch forks from a version and carries its own character fingerprint.

```json
{
  "branch_id": "beef-variant",
  "recipe_id": 42,
  "parent_branch": "main",
  "branch_point_version": 2,
  "label": "Beef Stir Fry",
  "reason": "Protein swap: chicken → beef. Different cooking approach, richer flavour.",
  "current_version": 1,
  "character_fingerprint": { ... },
  "created_at": "2026-04-10T14:00:00Z",
  "created_by": "adaptation_pipeline"
}
```

**When to branch:** protein swap that changes character (chicken stir fry → beef stir fry), flavour-direction fork (rich coconut curry vs lighter tomato-based), cooking method change (oven-roasted vs pan-fried).

**When NOT to branch** (use a version instead): seasoning tweaks, garnish additions, method error fixes, portion scaling.

Branch selection at plan-time is a planner concern. The Recipe System exposes branches and the planner picks — e.g., beef branch is over budget this week, use the chicken branch.

### Branch divergence

Over time a branch may drift so far from its parent that linking them is misleading. The system tracks divergence per branch (proportion of shared ingredients, method similarity). If divergence crosses a threshold, the system surfaces: *"this branch has become its own dish — promote to a standalone recipe?"* Promotion copies the branch out as a new recipe with a `forked_from` reference preserved for history.

---

## Substitutions

Substitutions are plan-level overlays on an existing version. Not a new version (not strictly better), not a branch (not equally desirable) — a constrained variation made because a limitation forces a change.

```json
{
  "substitution_id": "sub-007",
  "recipe_id": 42,
  "version_id": 3,
  "original": {"ingredient_mapping_key": "fillet steak raw", "quantity": 300, "unit": "g"},
  "substitute": {"ingredient_mapping_key": "rump steak raw", "quantity": 300, "unit": "g"},
  "reason": "budget",
  "constraint_ref": "budget-cap-2026-w15",
  "temporary": true,
  "applied_in_plans": ["plan-2026-w15"],
  "notes": "Cheaper cut, needs longer cooking.",
  "method_overlay": [{"step": 3, "instruction": "Cook for 6-8 minutes instead of 3-4."}]
}
```

**When substitutions happen:** budget (fillet → rump), availability (coriander out of stock → parsley), temporary dietary (elimination protocol), equipment (food processor missing → knife prep).

**Key distinction from branches:** substitutions are not ideal. If the constraint lifts, the original ingredient should return. Branches are both desirable; substitutions have a clear preferred version.

Substitutions are **overlays** — the base recipe is unchanged. The planner applies them at plan-time. This keeps the catalogue clean: no "Chicken Stir Fry (rump version, budget)" polluting the library.

### Substitution → version promotion

When a substitution has been applied N times (default: 3 plan weeks) without the user reverting or complaining, the system surfaces: *"You've used rump instead of fillet in the last 3 plans — make this permanent?"*

One tap promotes the overlay to a new version on the main branch (with `trigger: "substitution_promotion"`). This converts a silent drift pattern into explicit user intent, rather than leaving the catalogue forever misaligned with actual use.

---

## Adaptation Pipeline

All adaptation — import-time, feedback-driven, data-model-change, plan-time — flows through a single pipeline. Four sources produce `AdaptationJob` records; one pipeline processes them.

### Why unified

Earlier versions of this design had four parallel trigger paths (Trigger 1/2/3/4) that all did essentially the same work: load recipe + constraints, reason against them, emit an adaptation. Unifying them:

- Eliminates duplicated orchestration logic.
- Makes the data-model-change batch path fall out of the same implementation — it's just "enqueue N jobs."
- Gives one place to plug in cross-cutting concerns (trace logging, prompt versioning, rate limiting, approval policy).

### Three layers of intelligence

Every job runs through three layers. All three are active on every adaptation — a constraint swap that violates culinary integrity or misses a nutritional opportunity is a bad adaptation even if it satisfies the constraint.

| Layer | What it knows | Example |
|---|---|---|
| **Culinary** | What makes food taste good. Flavour balance, texture contrast, cooking chemistry. | "Swapping cream for coconut cream changes the sweetness — rebalance with acid." |
| **Nutritional** | Food science at the ingredient level. Absorption, preparation impact, pairing. | "Pair this spinach with lemon to boost iron absorption. Soak these lentils overnight." |
| **Constraint satisfaction** | Mechanical swaps for budget, availability, equipment, dietary restrictions. | "Fillet → rump for budget. Adjust method step 3 for longer cooking." |

### Interface Everything for Upgrade

Every intelligence source is behind an interface so v1 implementations (prompt-based) can be swapped without changing consumers.

| Capability | v1 | Future upgrade path |
|---|---|---|
| Culinary reasoning | Prompt rules + AI knowledge | Specialised food AI, or culinary knowledge graph |
| Nutritional intelligence | Prompt rules | Structured food-science knowledge base queried before each job |
| Constraint checking | Query services pass context snapshot | Real-time tool use — AI calls services during reasoning |
| Recipe understanding | Full recipe + fingerprint in context | Recipe embeddings; reason about position in embedding space |
| Adaptation quality evaluation | User ratings (post-hoc) | Second AI call scores the proposal before it reaches the user |

The pattern: define the interface by what it does ("assess whether this substitution preserves the recipe's character"), not how. The v1 is a prompt. The v2 might be a knowledge-base query plus AI reasoning. The consumers don't change.

### Job model

```java
public class AdaptationJob {
    Long jobId;
    Long recipeId;
    JobSource source;                 // IMPORT, FEEDBACK, DATA_MODEL_CHANGE, PLAN_TIME
    JobPriority priority;             // SYNC, ASYNC, BATCH
    ApprovalPolicy approvalPolicy;    // derived from catalogue + source
    Map<String, Object> inputs;       // feedback text, plan constraints, etc.
    String promptTemplateVersion;     // pinned at enqueue time
    JobStatus status;                 // PENDING, RUNNING, DONE, FAILED
    Long traceId;
}
```

### Job sources

| Source | Input | Urgency | Default outcome | Approval |
|---|---|---|---|---|
| **IMPORT** | New recipe just stored | Async | Usually no change; sometimes version | Pending change (user) / direct (system) |
| **FEEDBACK** | Recipe-specific feedback from Feedback System | Sync — user waiting | Version or branch | Pending change (user) / direct (system) |
| **DATA_MODEL_CHANGE** | Preferences / nutrition / budget changed | Batch — use Anthropic Batches API (50% cost, 24h) | Version (fix violations) | Pending change (user) / direct (system) |
| **PLAN_TIME** | Planner needs adaptation for this week | Sync — planner waiting | Substitution; version if genuinely better | Direct (planner-scoped overlay) |

### Job flow

```
Job enqueued (source-specific inputs)
         │
         ▼
Load recipe + current version + character fingerprint
Load constraints: PreferenceQueryService, NutritionQueryService, ProvisionQueryService
         │
         ▼
Assemble prompt context (prompt template version pinned on enqueue)
         │
         ▼
AI call via AiService (tool use, structured output):
    Reasons with culinary + nutritional intelligence
    Checks proposed change against character fingerprint
    Classifies: VERSION | BRANCH | SUBSTITUTION | NO_CHANGE
    Returns structured diff + reasoning + confidence + planner hints
         │
         ▼
Validate:
    HardConstraintFilterService → no allergy violations
    Character self-check → AI confirms preservation
    Confidence floor (< 0.5 → flag for user review even for system catalogue)
         │
         ▼
Apply per catalogue + approval policy:
    User catalogue → pending change (diff queued for user review)
    System catalogue → direct write + new version
    Plan-time → substitution overlay attached to plan
         │
         ▼
Write adaptation trace (inputs, prompt version, raw output, decision)
         │
         ▼
Publish RecipeEvolvedEvent → Nutrition Engine recalculates
```

### Classification logic

```
Is this change objectively better regardless of constraints?
    YES → VERSION  (e.g., "doubled soy sauce based on feedback")
    NO  → continue

Does this change produce a dish with different character worth keeping?
    (check against character_fingerprint)
    YES → BRANCH  (e.g., "coconut cream version — lighter, sweeter")
    NO  → continue

Is this change driven by a temporary or situational constraint?
    YES → SUBSTITUTION  (e.g., "rump steak because fillet is over budget this week")
    NO  → VERSION (default for non-temporary improvements)
```

The AI returns the classification; the pipeline stores the result in the right shape.

### Planner hints

The pipeline often discovers plan-level concerns while working on individual recipes: "needs overnight soak," "don't pair with calcium-heavy side," "this substitution loses omega-3 — compensate elsewhere this week." These surface as `PlannerHint` objects in the result.

```java
public class PlannerHint {
    HintType type;     // PREP_LEAD_TIME, ABSORPTION_CONFLICT, NUTRITION_TRADEOFF
    String description;
    Map<String, Object> data;  // e.g., {"lead_time_hours": 12}
}
```

The pipeline never modifies the plan directly — it emits hints; the planner decides.

---

## Approval Model

### Pending changes (user catalogue)

When a job produces an adaptation for a user catalogue recipe, the pipeline writes a **pending change** instead of a new version. The user sees a notification with a side-by-side diff and accepts, rejects, or modifies before accepting.

```json
{
  "pending_change_id": "pc-456",
  "recipe_id": 42,
  "job_id": 789,
  "trace_id": "trace-abc-123",
  "change_dimension": "salt_level",
  "proposed_diff": { ... },
  "reasoning": "User feedback said 'bland' across last two ratings of v2.",
  "status": "pending",
  "created_at": "2026-04-15T10:00:00Z",
  "expires_at": "2026-04-29T10:00:00Z"
}
```

A conversational AI suggestion box is available alongside the diff so the user can discuss or refine the proposal before accepting. Accepted proposals create a new version with `trigger: "adaptation_pipeline"`.

### Pending change supersession

Pending changes are keyed by `(recipe_id, change_dimension)`. When a new pending change arrives for the same key, it **supersedes** the unreviewed prior. Dimensions are coarse categories: `salt_level`, `protein`, `method_simplification`, `portion_size`, `flavour_balance`, etc.

This prevents two failure modes:
- **Stacking** — three unreviewed salt-adjustment proposals piling up on one recipe, each slightly different.
- **Stale proposals** — a month-old "too salty" proposal outranked by fresher feedback.

Supersession is tracked in history — the old proposal isn't lost; the user can see *"this proposal replaced an earlier one from 2026-04-08."* Proposals in different dimensions (salt + protein) coexist.

### Optimisation budget

Users see at most **3 pending changes per week** (configurable; default 3). Additional proposals go into a ranking pool keyed by `confidence × impact`. Each week, the top 3 surface.

The cap prevents notification fatigue turning the review UI into background noise. Once users stop reviewing, the adaptation feedback loop dies — the pipeline keeps generating proposals no one looks at, and the catalogue drifts away from what the user actually wants.

System catalogue adaptations bypass the budget (no user attention cost).

### Expiry

Pending changes expire after 14 days. Expired proposals move to history — visible in "show dismissed" but no longer surfaced.

---

## Import Pipeline

Four entry points, one common pipeline:

- **Manual entry** — user fills a form; simplest path.
- **URL import** — AI extraction from a web page (mid-tier model, tool-use structured output).
- **AI generation** — pipeline-triggered when the planner identifies a gap; generates a recipe against a constraint brief.
- **Online discovery** — weekly, or gap-triggered. AI searches the web, filters by constraints, runs top results through URL import.

All four converge on:

```
Recipe data arriving
    │
    ▼
Nutrition Engine: map ingredients to USDA via ingredient_mapping_key
    AI parsing returns per-ingredient confidence
    External nutrition data from imports is DISCARDED — recalculated internally
    │
    ▼
AI tag inference (cheap model):
    cuisine, protein, cooking_method, complexity, flavour_profile
    │
    ▼
Hard constraint filter: check against allergies / dietary identity
    Violations flagged, not rejected (could be for a household member)
    │
    ▼
Store with appropriate data_quality:
    manual → user_verified
    url → imported
    ai_generated → ai_generated
    discovered → web_discovered
    │
    ▼
Publish RecipeImportedEvent → Adaptation Pipeline (IMPORT job)
    Character fingerprint is extracted during this job.
```

### USDA mapping confidence

The AI parsing step returns confidence per ingredient. Below a threshold (default 0.7), the ingredient is flagged `needs_review` and the recipe's `nutrition_status` is set to `partial`. The UI surfaces a badge: *"3 ingredients need review"* with a one-tap correct flow.

This turns a silent failure mode (bad USDA mapping → wrong nutrition → stale plan) into a guided fix. Mapping corrections feed back into the Nutrition Model's cache so similar recipes benefit.

### Recipe deduplication

On import, a normalised ingredient-set hash (sorted mapping keys, ignoring quantities) is computed. Collisions above a threshold (default: 80% ingredient overlap + method length within ±20%) surface a dialog:

> *This looks similar to "Chicken Stir Fry" in your library. Merge, import as a variant branch, or import anyway?*

Prevents duplicate proliferation from users importing the same recipe from multiple sources, or from discovery finding near-duplicates of recipes the user already has.

### Failure handling

- **URL unreachable** — fail fast with a clear error. No stored partial.
- **AI extraction produces garbage** (missing ingredients, nonsensical method) — store with `needs_review` flag and `data_quality: imported`. User can correct. Never silently discard.
- **Foreign-language page** — AI extracts what it can; untranslated method steps are flagged for review.
- **USDA mapping fails for all ingredients** — store with `nutrition_status: pending`. Planner can still use it but flags "nutrition incomplete."

### Recipe images

Imported recipes often include images — stored as URLs. Manual entries can upload image files. Images are optional — a recipe without one is fully functional. AI-generated recipes do not produce images in v1 (cosmetic enhancement, no planning impact).

---

## Observability

### Adaptation trace log

Every job writes a trace row, keyed by `trace_id`:

```json
{
  "trace_id": "trace-abc-123",
  "job_id": 789,
  "recipe_id": 42,
  "source": "FEEDBACK",
  "prompt_template_version": "v2.3.1",
  "ai_model": "claude-sonnet-4-6",
  "inputs_snapshot": {
    "recipe_version_id": 3,
    "feedback_text": "needs more flavour",
    "constraints_hash": "sha256:..."
  },
  "raw_ai_response": { ... },
  "classification_decision": "VERSION",
  "final_diff": { ... },
  "confidence": 0.82,
  "validation_result": "passed",
  "outcome": {
    "version_id_created": 4,
    "pending_change_id": null
  },
  "duration_ms": 3421,
  "created_at": "2026-04-15T10:00:00Z"
}
```

**Why this exists.** When a version tanks ratings, the trace lets you see exactly what the AI was told, what it said, and which prompt version produced it. Without this the adaptation loop is open — you ship prompt changes and hope. With this you can diagnose regressions and prove improvements.

**Retention.** Raw traces for 6 months. Older traces aggregate into per-prompt-version metrics (accept rate, rating delta, revert rate) and raw rows are deleted.

### Prompt version as first-class data

Every job pins a `prompt_template_version` at enqueue time. Prompt templates live in a config store with version history. Rollouts can be staged — start plan-time jobs on a new prompt with 10% canary traffic, watch metrics, ramp if they hold.

Bucketed metrics make "Interface Everything for Upgrade" operational:
- Accept rate by prompt version.
- Rating delta (per-version score change) by prompt version.
- Revert rate (accepted changes later reverted) by prompt version.
- Character-preservation self-check pass rate by prompt version.

When v2.3.2 ships, you don't have to hope it's better — you can prove it.

### Quality dashboard

Surfaced to the user (and dev) — no automatic action in v1:

- Accept / reject / modify rate on pending changes (by recipe, by source type, by prompt version).
- Rating delta per version (did v3 beat v2?).
- Revert rate (accepted changes users later reverted).
- Per-prompt-version aggregates.
- Adaptation volume by source (helps tune batch scheduling).

---

## Failure Modes

### Adaptation pipeline
- **AI low confidence (< 0.5)** — flag for user review even on system catalogue. Don't auto-apply.
- **AI fails character preservation self-check** — never auto-apply. Propose as branch if the result is a coherent different dish; otherwise discard.
- **Hard constraint violation in proposed change** — reject at validation. Log. Retry once with a stronger constraint directive in the prompt; if it fails again, the recipe is flagged for manual review.
- **AI task fails entirely** (timeout, rate limit, malformed tool response) — recipe unchanged. Trace records failure. For PLAN_TIME jobs the planner uses the unadapted recipe.
- **Batch partial failure** (DATA_MODEL_CHANGE) — adapted recipes written individually; failed jobs retry with exponential backoff in the next scheduled run.

### Catalogue writes
- **Concurrent writes to the same recipe** — versions include `parent_version_id`. Writes race-check against current version; on conflict the second write rebases the diff onto the new current version and retries (up to 3 times). See [Concurrency](#concurrency).
- **Pending change supersession race** — two jobs produce proposals in the same dimension. Unique constraint on `(recipe_id, change_dimension, status='pending')` serialises them; second one supersedes atomically.

### Feedback routing
- **Feedback for a recipe that has since been branched** — routed to the branch the user last interacted with. Ambiguity surfaced to user.
- **Feedback for a version the user has reverted** — the current active version is the target; the prior version is informational.

---

## Concurrency

The system assumes single-user, but multiple jobs can race on one recipe.

**Version writes** use optimistic concurrency. Each write includes the parent version ID; the catalogue rejects writes whose parent is not the current version. On rejection the pipeline rebases the diff onto the new current version and retries (up to 3 times). After that the job fails and logs — this is a signal something is wrong (a loop, a stuck manual edit) rather than honest contention.

**Pending change writes** use a unique constraint on `(recipe_id, change_dimension, status='pending')`. Second writer wins by superseding the first.

**Manual edits vs pipeline jobs** — a manual edit in flight takes a short advisory lock on the recipe (30s TTL). Pipeline writes wait for the lock or retry after expiry. This prevents the common case where the user tweaks a recipe in the middle of a background adaptation job overwriting their edit.

**DATA_MODEL_CHANGE batch vs FEEDBACK job** — if a FEEDBACK job arrives for a recipe currently in a batch, the batch defers that recipe. The FEEDBACK job processes first (user waiting); the batch re-evaluates fit after it completes.

---

## State Lifecycles

### Recipe

```
CREATED (import / manual / generation / discovery)
    ↓  (adaptation pipeline, feedback, edits — linear versions on current branch)
ACTIVE
    ↓  (user demotes OR no system-catalogue use for 3 months)
ARCHIVED (excluded from planner index, retained in storage; no hard delete)
    ↑
    └─ user promotes back to user catalogue (one tap)
```

### Pending change

```
PENDING (created by pipeline)
    │
    ├── user accepts         → ACCEPTED  → creates new version
    ├── user rejects         → REJECTED  → history
    ├── user modifies+accepts → MODIFIED  → creates new version with user edits
    ├── superseded by newer proposal in same dimension → SUPERSEDED → history
    └── 14 days elapsed      → EXPIRED   → history
```

### Substitution

```
ACTIVE (applied in one or more plans)
    │
    ├── constraint lifted / user reverts → INACTIVE (retained in history)
    └── applied N times without revert → prompt surfaced → (user confirms) → PROMOTED (overlay converted to version)
```

---

## Data Volumes (back-of-envelope)

Assumptions: single active user, ~200-recipe user catalogue at 1 year, system catalogue 500-1000 recipes steady state after pruning.

| Data | Steady-state | Growth driver |
|---|---|---|
| Recipes | 1000-1200 rows | Import ~2/week, generation + discovery ~5/week, pruning ~3/week |
| Versions | ~6,000 rows (5 per recipe avg) | Feedback + constraint-change adaptation |
| Branches | ~200 rows (0.2 per recipe) | Rare — only real character forks |
| Substitutions | ~300 rows | Plan-scoped, pruned with plans |
| Pending changes (active) | 3-10 | Capped by optimisation budget |
| Pending changes (history) | ~500/year | All resolutions retained |
| Adaptation traces | ~10,000/year | One per job; 6-month retention on raw rows |

Per-row sizes: recipe ~3KB, version diff ~500B, trace ~5KB (includes raw AI response). Total: **<100 MB per year of single-user data** — comfortable on any reasonable database.

**AI call volume:** ~4 jobs/day average (1 feedback, 1 import, 2 plan-time during weekly planning). DATA_MODEL_CHANGE batches add spikes (50-200 jobs in a burst, but run through Batches API at 50% cost). Roughly 1,500-2,000 AI calls/year per user — single-digit dollars at mid-tier pricing.

---

## Bootstrapping

Both catalogues start empty.

**Week 1-2.** No import corpus, minimal feedback. The planner leans on AI generation for the first plan. Pending changes are minimal — nothing to adapt yet.

**Quick-start option.** Onboarding offers a batch import: *"Paste your 5 favourite recipe URLs."* This seeds the user catalogue, gives the planner real recipes for week 1, and generates enough signal to start the taste-profile bootstrap on the preference side.

**System catalogue** grows from discovery (weekly), generation (planner gaps), and pipeline adaptations. 3-month archival prevents unbounded growth.

---

## Service Interfaces

### RecipeQueryService (read)

```java
public interface RecipeQueryService {
    RecipeDTO getById(Long recipeId);
    List<RecipeDTO> getByIds(List<Long> recipeIds);
    RecipeIndexDTO getRecipeIndex(Long userId, RecipeFilter filter);
    List<RecipeDTO> search(RecipeSearchCriteria criteria);
    List<VersionDTO> getVersionHistory(Long recipeId, String branch);
    List<BranchDTO> getBranches(Long recipeId);
    List<SubstitutionDTO> getSubstitutions(Long recipeId);
    List<String> getIngredientMappingKeys(Long recipeId);
    CharacterFingerprintDTO getFingerprint(Long recipeId, String branch);
    NutritionDTO getNutrition(Long recipeId);
}
```

### RecipeUpdateService (write — direct operations)

```java
public interface RecipeUpdateService {
    RecipeDTO create(CreateRecipeRequest request);
    RecipeDTO importFromUrl(String url, Long userId);
    RecipeDTO generate(RecipeGenerationRequest request);
    VersionDTO createVersion(Long recipeId, CreateVersionRequest request);
    BranchDTO createBranch(Long recipeId, CreateBranchRequest request);
    SubstitutionDTO createSubstitution(Long recipeId, CreateSubstitutionRequest request);
    void updateNutrition(Long recipeId, NutritionDTO nutrition);
    void promoteToUserCatalogue(Long recipeId, Long userId);
    void archiveSystemRecipe(Long recipeId);
    void promoteSubstitutionToVersion(Long substitutionId);
    void revertToVersion(Long recipeId, Long versionId);
    void storeEmbedding(Long recipeId, float[] embedding);  // future — planner-owned
}
```

### AdaptationService (pipeline entry)

```java
public interface AdaptationService {
    Long enqueueImportJob(Long recipeId);
    Long enqueueFeedbackJob(Long recipeId, FeedbackDTO feedback);
    List<Long> enqueueDataModelChangeJobs(Long userId, DataModelChangeType changeType);
    AdaptationResult runPlanTimeJob(Long recipeId, PlanConstraints planConstraints);  // sync

    List<PendingChangeDTO> getPendingChanges(Long userId);
    void acceptPendingChange(Long pendingChangeId);
    void rejectPendingChange(Long pendingChangeId);
    void modifyPendingChange(Long pendingChangeId, RecipeDiff userEdits);
}

public class AdaptationResult {
    Long recipeId;
    AdaptationType type;            // VERSION, BRANCH, SUBSTITUTION, NO_CHANGE
    RecipeDiff proposedChanges;
    String reasoning;
    String nutritionalNotes;
    boolean requiresApproval;
    List<PlannerHint> plannerHints;
    Long traceId;
    double confidence;
}
```

### Events

**Listens to:**
- `RecipeImportedEvent` → IMPORT job
- `DataModelChangedEvent` → DATA_MODEL_CHANGE batch

**Publishes:**
- `RecipeEvolvedEvent(recipeId, versionId, branchId, trigger, traceId)` → Nutrition Engine recalculates nutrition for the new version/branch

Feedback is **not** an event. The Feedback System calls `AdaptationService.enqueueFeedbackJob()` directly (synchronously for FEEDBACK jobs) because it needs the trace ID and resulting version ID to confirm back to the user.

---

## Guardrails

### What the pipeline must never do

- **Violate hard constraints.** Every adaptation passes through `HardConstraintFilterService` before being stored. AI reasoning is not trusted for allergy safety — the deterministic filter is the final gate.
- **Auto-apply to user catalogue recipes.** Always pending change with user approval. No exceptions.
- **Produce inedible results.** The AI's own `preserves_character` self-check is a gate. If the AI says it broke the dish, the result is flagged rather than applied.
- **Silently change nutrition.** Every adaptation that touches ingredients triggers a Nutrition Engine recalculation via `RecipeEvolvedEvent`. The user sees updated nutrition on the adapted version.

---

## Boundaries With Other Components

| Concern | Lives in | Why not here |
|---|---|---|
| Taste preferences, hard constraints, lifestyle config | Preference Model | We read these as adaptation inputs; we don't own them. |
| Nutrition targets, intake tracking | Nutrition Model | We read targets as constraints; we don't set or track them. |
| Nutrition calculation (USDA math) | Nutrition Engine | We store the results; it does the arithmetic. |
| Ingredient availability, pricing, inventory | Provision Model | We read as constraints; provisions owns the state. |
| Plan composition and scheduling | Meal Planner | We adapt individual recipes; the planner arranges them. |
| Feedback classification and routing | Feedback System | It decides where feedback goes; we do the creative work on recipe feedback. |
| Hard constraint enforcement | Hard Constraint Filter | We propose; the filter is the deterministic final gate. |
| Recipe embeddings (if added) | Meal Planner (future) | Embedding pipeline is planner-owned; we store the vectors. |

### Key interaction rules

**Recipe System → Planner (via PlannerHints).** The pipeline sometimes discovers plan-level concerns while working on individual recipes. These surface as `PlannerHint` objects in `AdaptationResult`. The planner reads and adjusts. The Recipe System never modifies plans directly.

**Feedback System → Recipe System (service call, not event).** The Feedback System calls `AdaptationService.enqueueFeedbackJob()` synchronously because it needs the result to confirm back to the user.

**Recipe System → Nutrition Engine (via event).** `RecipeEvolvedEvent` triggers nutrition recalculation. The Recipe System does not calculate nutrition itself — the pipeline reasons about food science qualitatively; the Nutrition Engine does the numbers.

---

## Open Questions

- **Adaptation aggressiveness on import.** Conservative (leave mostly alone, let feedback drive change) vs aggressive (tweak to 95% alignment immediately). Probably a user setting, but the right default is unclear — some people want recipes adapted to them, others want to try recipes as-is.
- **Cross-recipe adaptation.** The pipeline reasons per-recipe, with plan-level concerns escaping as PlannerHints. Richer plan-wide nutritional optimisation (iron/calcium pairing across a week) may require giving the pipeline plan context in sources other than PLAN_TIME. Tradeoff: much bigger context per job, harder concurrency story.
- **Deduplication threshold.** 80% ingredient overlap is a starting point — needs validation once the corpus exists. Too strict → duplicates slip through; too loose → false merges annoy users.
- **Rating dimensions.** Four is a starting point. `portion_fit` might be noisy given the planner already adjusts portions; v1 may start with just `taste` and `effort_worth_it`, adding the others once the first two prove load-bearing.
- **Collaborative pool.** Multiple households sharing system catalogue entries. Architecture supports it (system catalogue + data_quality) but semantics of merged feedback across users is tricky. Out of scope for v1.
- **Character fingerprint drift.** Extracted once on import and held constant until branch. If the AI's sense of "character" gets better over time, old fingerprints are stale. Should there be a periodic re-extraction pass for long-lived recipes?
