# Meal Planner — Design

*The orchestrator. Reads from the three data models and the Recipe System, produces a plan for the planning period, and reruns itself when conditions change.*

## What It Is

The Meal Planner is the system's coordinator. It is the only component that **composes plans** — sequences of recipes scheduled across days and meal slots that simultaneously satisfy preferences, nutrition targets, and provisions/budget constraints.

It implements the [optimisation loop pattern](optimisation-loop.md) at the **week scale**. The loop's abstract contract is documented separately; this doc specifies what plugs into each stage at this scale.

This is **not**:
- A recipe adaptation system (that's the [Recipe System's Adaptation Pipeline](recipe-system.md)). The planner queries adapted recipes; it doesn't propose changes to them.
- A feedback router (that's the [Feedback System](feedback-system.md)). The planner is downstream of feedback — it sees the resulting data-model state, not the raw feedback.
- A data store for constraints. Constraints live in the three data models. The planner reads them; it never writes to them.
- A grocery integration. The planner emits an output that the grocery module turns into an order. It doesn't talk to Tesco.

The planner is **read-only against the data models**. Its only writes are to its own plan storage and the decision log.

## Architectural Position

```
   ┌─────────────┐  ┌─────────────┐  ┌─────────────┐
   │ PREFERENCE  │  │  NUTRITION  │  │ PROVISIONS  │
   │   MODEL     │  │    MODEL    │  │             │
   └──────┬──────┘  └──────┬──────┘  └──────┬──────┘
          │                │                │
          └────constraints + targets────────┘
                           │
                           ▼
   ┌─────────────────────────────────────────────────────┐
   │                                                     │
   │                  MEAL PLANNER                       │
   │                                                     │
   │   Stage A: deterministic candidate generation       │
   │   Stage B: rollup                                   │
   │   Stage C: LLM/user choice + creative gap-fill      │
   │   Stage D: refine-directives → Recipe Optimiser     │
   │                                                     │
   └─────────┬──────────────────┬────────────────────────┘
             │                  │
             │           ┌──────▼──────────┐
             │           │  RECIPE SYSTEM  │
             │           │  (catalogue +   │
             │           │   adaptation)   │
             │           └─────────────────┘
             │
             ▼
        ┌─────────┐
        │  PLAN   │ ──── ► Grocery, Notification, UI
        └─────────┘
```

The planner sits below the data models and above the plan output. It calls the Recipe System for both reads (candidate recipes) and adaptation (Stage D refine-directives). It does not call the Feedback System; the feedback path is upstream and separate.

---

## The Loop Applied — Stage Mapping

Concrete week-scale instantiation of the [optimisation loop](optimisation-loop.md) stages.

### Stage A — Deterministic candidate generation

- **Hard-filter** drops recipes that violate any household member's allergy or dietary identity for the relevant slot, or require equipment not in Provisions, or whose total time exceeds the slot's time budget. The filter is pure code; the LLM never sees rejected candidates.
- **Scoring** runs the [scoring function](#scoring-function) below over the surviving recipes for each slot.
- **Search** uses beam search (width 20, depth = total slots in the planning period — typically 21 for a 7-day week with 3 meal slots/day). Output: top-N=5 complete plans.

### Stage B — Rollup

Per-candidate plan summary:

```json
{
  "plan_id": "candidate-1",
  "daily": [
    { "day": "Mon", "kcal": 2150, "protein_g": 180, "fat_g": 70, "carbs_g": 230,
      "cost_gbp": 6.40, "total_time_min": 75, "violations": [] },
    /* ... 6 more days ... */
  ],
  "weekly": {
    "kcal_total": 15050,
    "macro_avg": { "protein_g": 175, "fat_g": 68, "carbs_g": 220 },
    "cost_estimate_gbp": 47.10,
    "cost_confidence": 0.83,
    "stale_ingredient_count": 4,
    "variety_index": 0.78,
    "batch_cook_sessions": 2,
    "constraint_violations": []
  },
  "score_breakdown": {
    "preference": 0.82, "nutrition": 0.91, "cost": 0.74,
    "variety": 0.78, "time": 0.85, "batch": 0.90, "provisions": 0.80
  },
  "score_total": 0.83
}
```

This is what Stage C sees. The LLM does not receive the underlying recipes — only the candidate plans summarised this way.

### Stage C — Choice + creative augmentation

Two sub-stages:

1. **Pick from N=5.** LLM (frontier tier — Sonnet/Opus) picks one candidate plan with reasoning. Reasoning is recorded in the decision log.
2. **Creative augmentation.** The LLM may add or swap *plan-level* items the deterministic search couldn't produce: snacks, side dishes, drinks, ingredient swaps within a slot. See [Phase 2 Creative Augmentation](#phase-2-creative-augmentation).

The user can override the LLM's pick — the UI presents all 5 candidates with the LLM's recommendation highlighted. Override is logged as `chosen.source = 'user'`.

### Stage D — Refine-directives

If Stage C identifies a recipe-level fix that's better handled by the Recipe Optimiser than by swapping recipes wholesale ("Wednesday's stir-fry needs to drop £2 to make budget"), the planner emits a refine-directive to the Recipe Optimiser, waits synchronously for the adaptation, and re-runs Stage A on the affected slot. Subject to the loop's iteration budget (3 cycles).

---

## Plan Data Model & Slot Structure

### Plan

```json
{
  "plan_id": "uuid",
  "household_id": "uuid",
  "week_start_date": "2026-05-11",
  "generation": 1,
  "replaces_plan_id": null,
  "trigger": "user-initiated" | "mid-week-reopt" | "scheduled-weekly",
  "trigger_event_id": "uuid | null",
  "status": "draft" | "generated" | "active" | "superseded" | "completed" | "rejected" | "abandoned",
  "created_at": "timestamp",
  "accepted_at": "timestamp | null",
  "completed_at": "timestamp | null",
  "trace_id": "uuid",
  "decision_id": "uuid",
  "days": [ /* Day[] */ ]
}
```

`generation` increments per `(household_id, week_start_date)` — every regeneration of the same week creates a new Plan with `generation = previous + 1` and `replaces_plan_id` pointing at the previous one. Old plans are never mutated.

### Day

```json
{
  "day_id": "uuid",
  "date": "2026-05-11",
  "slots": [ /* MealSlot[] */ ],
  "notes": "string | null"   // user-set, e.g. "eating out tonight"
}
```

### MealSlot

```json
{
  "slot_id": "uuid",
  "kind": "breakfast" | "lunch" | "dinner" | "snack" | "custom",
  "label": "string",          // e.g. "Lunch" or user's "Post-gym snack"
  "time_budget_min": 30,
  "shared": true,             // shared across household, or per-person
  "eaters": ["user_id", ...], // null/empty for shared
  "scheduled_recipe": { /* ScheduledRecipe | null */ },
  "state": "planned" | "cooking" | "cooked" | "eaten" | "skipped"
}
```

A slot's `state` is what drives mid-week pinning — see [Mid-Week Re-Optimisation](#mid-week-re-optimisation).

### ScheduledRecipe

A scheduled instance of a recipe in a slot. Distinct from the recipe itself (which lives in the Recipe System).

```json
{
  "recipe_id": 42,
  "recipe_version": 3,
  "recipe_branch": "main",
  "servings": 2,
  "batch_cook_session_id": "uuid | null",  // links slots that share one cook
  "augmentation_notes": "string | null"     // Phase 2 plan-level additions
}
```

`batch_cook_session_id` lets the planner schedule "cook 5 servings on Sunday, eat across Mon–Fri lunches" as a single cook with five consumption slots. The grocery module aggregates ingredient quantities by session.

### Slot configuration

Slot structure is owned by the household's lifestyle config (Preference Model's lifestyle tier). Defaults: breakfast / lunch / dinner / one snack window per day. Per-day overrides include:

- **Eating out** — slot still exists for nutrition logging but the planner doesn't compose a recipe for it.
- **Fasting window** — slot is absent (intermittent fasting from the Nutrition Model).
- **Custom slots** — user-defined (e.g. "post-workout shake").
- **Time budget** — per slot, in minutes, defaulted by slot kind (breakfast 15, lunch 20, dinner 45, snack 5) and overrideable per slot.
- **Shared vs per-person** — shared slots use household-union constraints; per-person slots use that person's individual constraints.

---

## Plan Lifecycle

```
                                   ┌─────────────┐
                                   │    DRAFT    │
                                   │ (composing) │
                                   └──────┬──────┘
                                          │ Stage A-C complete
                                          ▼
                                   ┌─────────────┐    user rejects
                                   │  GENERATED  │ ──────────────► REJECTED
                                   │ (awaiting   │
                                   │  approval)  │
                                   └──────┬──────┘
                                          │ user accepts
                                          ▼
                ┌─────────────────► ┌─────────────┐
                │                   │   ACTIVE    │ ◄────┐
                │                   │ (current    │      │
                │  mid-week         │  plan)      │      │
                │  re-opt           └──────┬──────┘      │
                │  triggers                │             │ revert
                │                          │             │
                │                          │             │
            ┌───┴────────┐            ┌────▼─────┐  ┌────┴──────┐
            │ SUPERSEDED │ ◄───────── │ COMPLETED│  │ Plan(N-1) │
            │ (replaced) │            │ (week    │  │ promoted  │
            └────────────┘            │  ended)  │  │ via copy  │
                                      └──────────┘  └───────────┘
                                          │
                                          ▼
                                      ABANDONED
                                      (user gave up
                                       mid-week)
```

### State definitions

| State | Meaning | Mutable? |
|---|---|---|
| `draft` | Stage A-C in progress. Hidden from user. | Internal state only. |
| `generated` | Offered to user. Not yet accepted. | Slot edits replace whole plan via re-generation. |
| `active` | Current accepted plan for the week. | Slot states transition (cooking → cooked → eaten); recipe content is immutable. |
| `superseded` | Replaced by a newer-generation plan for the same week. | Immutable. |
| `completed` | Week ended; all slots reached terminal state. | Immutable. |
| `rejected` | User rejected at `generated` stage, never became active. | Immutable. |
| `abandoned` | User explicitly gave up mid-week. | Immutable. |

Only one `active` plan per `(household_id, week_start_date)` at a time. Mid-week regeneration transitions the current active to `superseded` and creates a new active plan.

### Plan history and revert

Every plan record is retained indefinitely. The user can browse historical plans for any past week and any previous generation of the current week.

Revert is **copy-forward**: reverting to plan version N when version N+M is currently active creates a brand-new plan with `generation = M+2` and `replaces_plan_id` pointing at the current active. Content is copied from N. The currently active plan transitions to `superseded`. This preserves immutability — N stays as it was.

Revert is allowed regardless of current Provisions state. If the reverted plan calls for ingredients that are no longer in pantry, the user sees a warning ("3 ingredients no longer available — re-optimise these slots?") and can choose to accept the partial plan or trigger a mid-week re-opt over the affected slots.

### Cold start

Both catalogues start empty. For the first 1–2 weeks the planner cannot rely on Phase 1 composition — there are no recipes to compose from. The cold-start path:

1. Before Stage A, planner checks catalogue size against a minimum (heuristic: ≥3× slot count, e.g. 63 recipes for 21 slots/week).
2. If below the minimum, planner triggers a **discovery + generation pre-step** via the Recipe System: discovery searches the web for candidate recipes matching the data model, and AI generation fills specific gaps. Both write to the system catalogue. Bounded — adds up to 50 recipes per cold-start run.
3. Stage A proceeds. Phase 2 (creative augmentation) does heavier lifting than usual — typical cold-start plans have 3-5 augmentations versus 0-2 in steady state.
4. Plan is presented to the user with a `cold_start: true` flag in the UI, signalling that quality may be lower and feedback is especially valuable.

After ~2 weeks of feedback and user-imported recipes, the catalogues are typically large enough that the cold-start path is no longer triggered.

---

## Scoring Function

Maps a (slot → recipe) assignment, and aggregations thereof, to a scalar in [0, 1]. Used during Stage A's beam search and exposed in Stage B's `score_breakdown`.

### Sub-scores

Seven sub-scores, each normalised to [0, 1]:

| Sub-score | What it measures | Source |
|---|---|---|
| `preference` | Recipe taste alignment with the eater's taste profile | Preference Model |
| `nutrition` | Plan-level nutrition convergence vs daily/weekly targets | Nutrition Model |
| `cost` | Plan-level cost vs budget target, computed from confidence-weighted price-history aggregates | Provisions (budget) + Grocery price history (see [grocery.md §Tier 4](grocery.md#tier-4-price-history)) |
| `variety` | Heterogeneity across recipes, ingredients, cuisines, cooking methods | Computed from selected recipes |
| `time` | Recipe time fit within slot time budgets | Recipe metadata + slot time budget |
| `batch` | Batch-cook compatibility — fewer cook sessions is better, given storage/packability | Recipe metadata + batch-cook flags |
| `provisions` | Utilisation of existing pantry stock (rewards using what's already there) | Provisions inventory |

### Composition

Weighted sum over normalised sub-scores:

```
score = w_pref · preference
      + w_nutr · nutrition
      + w_cost · cost
      + w_var  · variety
      + w_time · time
      + w_batch· batch
      + w_prov · provisions
```

with two **multiplicative gates** layered on top:

- `nutrition_floor_gate`: if any day's macro floor (e.g. 180g protein) is unmet, multiply the whole score by 0 — that plan is dead. Floors come from the Nutrition Model's daily-floor configuration.
- `variety_gate`: if any recipe appears more than `max_repeat` times in the week (default 2), multiply by 0.

Hard constraints are *not* in the scoring function — they're handled by Stage A's hard-filter, which never produces violating candidates.

### Cost sub-score and price confidence

The `cost` sub-score reads from the grocery module's price history (see [grocery.md §Tier 4](grocery.md#tier-4-price-history)) — confidence-weighted aggregates per `ingredient_mapping_key`, not from any supplier API. Each ingredient contributes a `(estimate, confidence)` pair; the plan's projected cost is the confidence-weighted sum.

Because confidence affects how much the cost dimension can swing the overall score, low-confidence projections (cold start, stale data, novel ingredients) have **less score-pulling power** than high-confidence ones. A plan whose cost is dominated by ingredients with confidence < 0.4 has its `cost` sub-score regressed toward 0.5 (neutral) by an amount proportional to the missing confidence. This avoids two failure modes:

- **Old prices look better than they are** — stale data dominates the score and pulls the planner toward recipes whose prices are out of date. Confidence regression mutes this.
- **Cold-start cost-blindness picks bad plans** — at week 1 with no price data, the cost score is essentially uninformed; regression toward neutral lets the other six sub-scores drive plan selection until prices accumulate.

Stage B's rollup carries `(cost_estimate_total, cost_confidence, stale_ingredient_count)` so the user sees freshness alongside the projected total. The Phase 2 LLM picker also sees this — it can prefer a higher-confidence candidate when totals are similar.

### Initial weights

**All weights uniform at 1/7 ≈ 0.143** for v1. The scoring function is deliberately undertuned — there's no real plan data yet to calibrate against, and any committed weight scheme would be a guess masquerading as a decision.

Calibration plan: after ~10 generated plans with user accept/reject + post-meal feedback, examine which sub-scores correlate with high-rated plans and adjust weights. This is a manual once-or-twice-then-stable activity, not a continuous learning system in v1.

This is documented as **Initial Weights v1** — explicit version label so future-us doesn't confuse "the chosen weights" with "the placeholder weights."

### Why uniform and not a guess

A 0.3/0.3/0.2/0.1/0.1 split feels reasonable on paper but encodes assumptions (preference and nutrition matter more than time and batch) that haven't been tested. Uniform makes the placeholder visible: when v2 weights ship, the change is loud, not subtle. Guesses tend to outlive their evidentiary basis; uniform doesn't pretend to be calibrated.

---

## Phase 2 Creative Augmentation

Stage C's second sub-stage. After picking from N=5, the LLM is asked to identify and fill gaps the deterministic search couldn't address.

### When invoked

After the LLM picks a candidate plan but before the plan is presented to the user. Always runs. If there are no useful augmentations, the LLM returns an empty augmentation list — that's the success path, not a failure.

### What it can do

- **Add a snack or side dish** to hit a daily nutrition target the composition couldn't reach with main meals alone (e.g. "+ Greek yoghurt as Wednesday snack to close protein gap").
- **Swap one ingredient** within a scheduled recipe via a refine-directive to the Recipe Optimiser (e.g. "swap chicken for turkey in Wednesday lunch to drop cost").
- **Re-pair sides with mains** when the deterministic search assigned them suboptimally (e.g. "move the salad from Tuesday to Friday so the Friday cottage pie has greens").

### What it cannot do

- Modify allergy or dietary identity constraints — those are deterministically enforced.
- Replace a whole main with an unrelated recipe — that's Stage A's job; if Stage C wants this, it picks a different candidate plan from the N=5.
- Generate a brand-new recipe inline — that's the Recipe System's responsibility, invoked via the cold-start path or Stage D.

### Output validation

Every augmentation is run through the same hard-filter as Stage A. An augmentation that would introduce an allergen, exceed the slot time budget, or violate equipment is discarded silently and logged. The LLM is never trusted to remember constraints — augmentations are vetted post-hoc by code.

### Limits

- Maximum 5 augmentations per plan (prevents the LLM from rewriting the whole plan as augmentations).
- Maximum 2 ingredient-swap directives to the Recipe Optimiser per plan (bounds Stage D iteration cost).

If the LLM proposes more than the limit, the planner takes the first N (in the order the LLM returned them) and discards the rest, logging the truncation.

---

## Constraint Feasibility Check

Runs **before** Stage A. Detects situations where the constraint set cannot produce a viable plan and surfaces resolutions to the user rather than producing a degraded plan or silently failing.

### Algorithm

1. Compute the post-hard-filter recipe pool size per slot.
2. Identify slots with fewer than `min_pool_per_slot` (default 3) candidates.
3. Classify the cause of each constrained slot.
4. Compute a global minimum-viable score by simulating the best-possible plan under current constraints — if even the optimum cannot meet daily nutrition floors, flag as infeasible.

### Conflict types and resolutions

Per the [system overview](system-overview.md#constraint-resolution), four conflict types:

| Conflict | Detection | Resolution surfaced |
|---|---|---|
| Household hard-constraint collision (e.g. one vegan, one keto) | Shared-slot recipe pool < `min_pool_per_slot` | Split the slot into per-person meals |
| Nutrition vs budget tension | Optimum plan misses daily floor or exceeds budget by margin | Ranked relaxation options ("drop protein floor to 160g opens 12 recipes" vs "raise budget to £60 opens 8 recipes") |
| Provisions bottleneck | Slot pool small due to equipment or pantry limits | Suggest workarounds (simpler recipes, alternative cooking methods, mid-week shop) |
| Over-specified preferences | Slot pool small due to soft preference cap (e.g. "max 3x chicken/week") | Suggest temporarily widening the most restrictive preference |

The user always chooses; the planner never silently relaxes a hard constraint nor degrades a soft one without consent.

### What happens if the user declines all resolutions

Planner generates the best plan it can under the current constraints, marks it as `quality_warning: true`, and surfaces the unmet floor or unfilled slot in the plan UI. No silent failure.

---

## Mid-Week Re-Optimisation

The planner does not only run at the start of the week. It can re-run, scoped to the remaining unconsumed slots, when conditions change.

### Triggers

| Trigger | Source | Sync/event |
|---|---|---|
| Provisions changed (ingredient spoiled, Tesco substituted, ran out) | `ProvisionChangedEvent` from Provisions module | Event subscription |
| Nutrition divergence (logged actual ≠ planned by margin) | `NutritionLoggerEvent` from Nutrition Logger | Event subscription |
| Preference change (allergy added, time constraint tightened) | `PreferenceChangedEvent` from Preference Model | Event subscription |
| User-initiated re-opt | Manual button on plan UI | Sync service call |

The first three are subscriptions — the planner registers as a listener via Spring `@TransactionalEventListener(AFTER_COMMIT)` per the [technical architecture](technical-architecture.md). The fourth is a direct service call.

Not every event triggers a re-opt — only events that *materially* change the constraint surface. Trigger filters:

- Provisions: a spoiled ingredient or substitution that affects an unconsumed slot's recipe. Stocking up after a shop does not trigger re-opt.
- Nutrition: divergence beyond a configurable threshold (default: ≥15% variance on any macro for the day).
- Preference: any hard-constraint change (allergy/identity). Soft preference tweaks do not auto-trigger.

### Pinning rules

Slot state determines what's locked:

| Slot state | Behaviour in re-opt |
|---|---|
| `eaten` | Immutable. Logged actuals fold into rollup as already-consumed. |
| `cooked` | Immutable. The cooked food gets eaten; it's not going back. |
| `cooking` | Immutable. Mid-cook means the recipe is committed to. |
| `planned` (in the past, e.g. yesterday's lunch never logged) | Pinned to whatever is in the plan. The user can manually mark it `skipped` if it didn't happen. |
| `planned` (in the future) | Regenerable. Becomes the search space for the re-opt. |
| `skipped` | Pinned as skipped. The macro/cost contribution is zero. |

### Provisions utilisation

The user's intuition: "existing food in provisions should take precedence to avoid wasting." This is **not** modelled as a hard pin. Instead:

- The Provisions data model contains current pantry/freezer state, including ingredients that were purchased for this week's plan but not yet consumed.
- The scoring function's `provisions` sub-score rewards plans that consume existing stock.
- A plan that wastes already-purchased chicken thighs scores worse than a plan that uses them, *all else equal*.

This gets the right behaviour without the brittleness of explicit ingredient pinning. If the user genuinely wants to abandon an unused ingredient (it spoiled, they changed their mind), they update Provisions directly — and the next plan stops trying to use it.

The only cases where waste-avoidance is overridden are the regular hard constraints (allergy added that excludes a purchased ingredient — drop it; ingredient spoiled — Provisions removes it).

### Scope and confirmation

Re-opt scope: the affected day onwards, restricted to slots in `planned` state. The user sees a "regenerate plan from [day]?" prompt with a diff preview (what changes, what's preserved) and confirms before the new plan replaces the active one.

Auto-trigger UX: events fire a notification ("ingredient ran out — regenerate remaining days?"), not an automatic re-opt. The user is always in the loop.

### Iteration budget across re-opts

Each top-level invocation gets the loop's standard 3-cycle budget. A mid-week re-opt is a fresh invocation, so it gets its own budget. Refine-directives to the Recipe Optimiser are bounded per invocation.

---

## Household Integration

Households share Provisions and contain multiple eaters. The planner handles this through slot configuration, not through a separate planning algorithm.

### Shared vs per-person slots

- **Shared slot** (`shared: true, eaters: null`) — composed against the union of all household members' hard constraints. Soft preferences are aggregated (mean of taste-profile vectors, weighted by per-person priority).
- **Per-person slot** (`shared: false, eaters: [user_id]`) — composed against that person's individual constraints only.

A plan can mix shared and per-person slots freely. Typical pattern: shared dinners, per-person breakfasts and lunches.

### Constraint feasibility at household level

Household-level hard-constraint collisions (one vegan, one keto for a shared slot) are caught by the constraint feasibility check before Stage A. The resolution it surfaces is "split this slot into per-person meals," not "relax one person's diet."

If the user accepts the split, the slot's `shared` flag flips to false and per-person sub-slots are created — one per eater. The planner then composes them independently.

### Per-person plan output

Per-person slots produce per-person scheduled recipes. Aggregated nutrition tracking still rolls up to per-person totals (each user has their own Nutrition Model). The Plan as a whole is shared across the household, but its slots may belong to different eaters.

---

## Failure Modes & Data Volumes

### Failure modes

| Failure | When | Response |
|---|---|---|
| No viable plan (Stage A returns empty) | Hard constraints over-restrictive | Constraint feasibility check kicks in *before* Stage A; this case is the "all resolutions declined" path → quality-flagged partial plan |
| All plans score low (top candidate < 0.4) | Soft constraints over-restrictive | Surface relaxation suggestions ranked by score recovered per unit constraint loosened |
| Phase 2 returns invalid output (allergen, malformed JSON) | LLM error | Discard invalid augmentations, retry once with explicit constraint reminder, then accept the un-augmented plan |
| LLM API down at Stage C | External | Fall back to highest-scoring deterministic candidate; UI flags "AI ranking unavailable" |
| Concurrent invocation on same `(household_id, week_start_date)` | User clicks regenerate twice, or event arrives during user-initiated run | Single-flight per scope. Second invocation rejected with "regeneration already in progress." |
| Generation timeout (Stage A > 30s, Stage C > 20s) | Catalogue too large, LLM slow | Stage A timeout: reduce beam width, retry once, then degrade to greedy selection. Stage C timeout: deterministic fallback. Plan is flagged. |
| Recipe goes stale during composition (catalogue update mid-run) | Concurrent recipe edit | Plan composed against snapshot taken at Stage A start. Doesn't re-read mid-flight. Stale recipes are caught at slot rendering, not at composition. |
| Mid-week re-opt during active Phase 1 | Race between event listener and user-initiated regeneration | Event-triggered re-opt is enqueued, not run, while a user-initiated run is active. Processed once the active run completes. |
| Refine-directive to Recipe Optimiser fails | Optimiser can't satisfy directive | Loop's standard infeasibility-escalation: planner picks a different candidate plan from the N=5, or stops refining |

### Data volumes — back of envelope

For a household of 2 with weekly planning:

| Quantity | Estimate | Notes |
|---|---|---|
| Catalogue size | 50 (cold start) → 500 (mature, ~1 year in) | User catalogue + system catalogue combined |
| Slots per plan | ~21 | 3 meals/day × 7 days; +0-7 for snacks |
| Candidate recipes per slot, post-hard-filter | 20–50 | Depends on constraint tightness |
| Beam width × depth | 20 × 21 = 420 active partial plans during search | Manageable |
| Stage A duration | <1s for catalogue ≤500, <5s for ≤2000 | Beam search complexity |
| Stage C tokens | ~3–5k input, ~1–2k output | Sonnet/Opus, ~£0.05–0.15 per pick |
| Plan generations per household per week | 1 (initial) + 0–2 (mid-week re-opts) | Avg ~1.5 |
| Decision log writes per generation | 5–15 | One per stage iteration plus refines |
| Plan storage size | ~5–10 KB JSON per plan | Indefinite retention; ~52 plans/year × 10 KB ≈ 500 KB/year/household |
| Plan retention | Indefinite | Storage cost is negligible; users want history |

These estimates are rough and worth revisiting once early plan generation produces real numbers. Tracked as the v1 sizing assumption set; revise on the first month's telemetry.

---

## Observability

The decision log (defined in [optimisation-loop.md](optimisation-loop.md#decision-log)) is the canonical record of every loop iteration. The planner additionally emits **plan lifecycle events**:

```
PlanGeneratedEvent      { plan_id, trace_id, trigger, generation, decision_id }
PlanAcceptedEvent       { plan_id, trace_id, accepted_at }
PlanSupersededEvent     { plan_id, replaced_by_plan_id, trace_id }
PlanCompletedEvent      { plan_id, trace_id, completed_at }
PlanRejectedEvent       { plan_id, trace_id, rejected_at, reason }
PlanAbandonedEvent      { plan_id, trace_id, abandoned_at }
ReoptTriggeredEvent     { plan_id, trigger_event_id, source }
```

These are Spring `ApplicationEvent`s on the `@TransactionalEventListener(AFTER_COMMIT)` bus. Subscribers include Notification (alerting), grocery (handling regenerated plans), and analytics (out of scope for v1 but the events are pre-laid).

**Pinning provenance.** When a slot is locked during a re-opt, the reason is recorded on the slot itself (`pinned_reason: "eaten" | "cooked" | "cooking" | "skipped"`). This is what the UI renders when a user asks "why didn't this slot change?"

**Trace IDs.** The decision log's `trace_id` propagates from week-level decisions to the recipe-level decisions they triggered (Stage D refine-directives to the Recipe Optimiser). Querying a trace gives the full reasoning chain — useful both for debugging and for the user-facing "why did Wednesday's stir-fry change?" explanation.

---

## API Surface

Per the [technical architecture](technical-architecture.md), the module exposes Java service interfaces on the boundary. Internal repositories are not exposed.

### Query services (read-only, widely injected)

```java
public interface PlanQueryService {
    Optional<PlanDto> getActivePlan(UUID householdId, LocalDate weekStartDate);
    List<PlanDto> getPlanHistory(UUID householdId, LocalDate weekStartDate);
    List<PlanDto> getPlansBetween(UUID householdId, LocalDate from, LocalDate to);
    PlanDto getPlanById(UUID planId);
    List<PlanDto> getPlansByIds(List<UUID> planIds);
}
```

### Update services (writes, narrowly injected)

```java
public interface PlannerService {
    PlanDto generatePlan(GeneratePlanRequest request);          // user-initiated
    PlanDto reoptimisePlan(ReoptRequest request);               // user-initiated mid-week re-opt
    PlanDto acceptPlan(UUID planId);                            // generated → active
    PlanDto rejectPlan(UUID planId, String reason);             // generated → rejected
    PlanDto revertToPlan(UUID targetHistoricalPlanId);          // copy-forward revert
    PlanDto abandonPlan(UUID planId, String reason);            // active → abandoned
    void markSlotState(UUID slotId, SlotState newState);        // planned → cooking → cooked → eaten
}
```

### Event listeners

The planner subscribes to (not exposes) these events:

```
ProvisionChangedEvent       — may trigger re-opt
NutritionLoggerEvent        — may trigger re-opt
PreferenceChangedEvent      — may trigger re-opt
HouseholdConfigChangedEvent — may invalidate active plan's slot configuration
```

Listeners are filtered (only material changes trigger re-opt) and queued (one re-opt at a time per scope). Listeners do not auto-replace the active plan; they emit a `ReoptSuggestedEvent` for the UI to surface, awaiting user confirmation.

### Inter-module dependencies (per technical-architecture.md)

| Injects | Purpose |
|---|---|
| `PreferenceQueryService` | Read constraints + taste profile |
| `NutritionQueryService` | Read targets + recent intake |
| `ProvisionQueryService` | Read pantry + budget + equipment |
| `RecipeQueryService` | Read candidate recipes (catalogue) |
| `OptimiserService` | Stage D refine-directives |
| `AiService` | Stage C LLM call |

The planner injects no update services — it is read-only against the data models.

---

## Out of Scope (Deferred)

The following are deliberately not specified in this HLD:

- **Specific LLM prompts** for Stage C and Phase 2 augmentation — LLD.
- **Database schema** — column types, indexes, partitioning. LLD.
- **Plan presentation UI** — visual layout, gestures, calendar view. Figma phase.
- **Notification copy** — exact text for re-opt suggestions, quality warnings, completion alerts. UX writing phase.
- **Per-household scoring weight tuning** — the calibration plan is committed; the actual tuned weights are produced from real data, not designed ahead.
- **Multi-week / monthly horizon plans** — v1 is week-scoped. Multi-week planning is a future scale, not part of the current loop.
- **Shopping list generation algorithm** — owned by the grocery module; the planner emits the plan, the grocery module computes the order.
