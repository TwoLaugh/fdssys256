# Optimisation Loop — Pattern

*A recurring shape used at multiple scales in the system. Not a component — a contract every applicable scale implements.*

## What This Is

The system contains two real multi-constraint optimisation problems — composing a weekly plan, and adapting an individual recipe — and they have the same shape: enumerate candidates deterministically, summarise each one, let intelligence (AI or user) pick from the shortlist, and optionally feed a refinement directive down to a finer scale.

This document defines that shape once. The Meal Planner and the Recipe System's Adaptation Pipeline each implement it at their own scale. New components that need multi-constraint optimisation should match the contract.

This is **not**:
- A service or module. There is no `OptimisationLoopService`. Each scale owns its own implementation.
- A generic Java abstraction. Concrete instances differ enough in candidate type and scoring shape that a shared base class costs more than it pays. See [Implementation Note](#implementation-note--style-guide-not-base-class).
- A universal pattern. It applies cleanly at the week and recipe scales and degenerates above and below them. See [Where It Does Not Apply](#where-it-does-not-apply).

## The Loop, Abstractly

One iteration of the loop has four stages:

```
   ┌─────────────────────────────────────────────────────────────────────┐
   │  Inputs:                                                            │
   │    · scope (what's being optimised — a week, a recipe)              │
   │    · hard constraints (allergies, dietary identity, equipment)      │
   │    · soft constraints + weights (from data models above)            │
   │    · optional refine-directive (from a higher-level loop)           │
   └────────────────────────────────────┬────────────────────────────────┘
                                        │
                                        ▼
   ┌─────────────────────────────────────────────────────────────────────┐
   │  Stage A — Deterministic candidate generation                       │
   │    · hard-filter the search space (drop infeasible)                 │
   │    · score remaining via weighted scoring function                  │
   │    · search to produce top-N candidates (default N=5)               │
   └────────────────────────────────────┬────────────────────────────────┘
                                        │
                                        ▼
   ┌─────────────────────────────────────────────────────────────────────┐
   │  Stage B — Rollup                                                   │
   │    · compute per-candidate summary stats                            │
   │      (weekly nutrition totals, cost, variety index, time, …)       │
   │    · stats are level-specific but always a fixed flat shape         │
   └────────────────────────────────────┬────────────────────────────────┘
                                        │
                                        ▼
   ┌─────────────────────────────────────────────────────────────────────┐
   │  Stage C — Choice                                                   │
   │    · LLM picks from N with qualitative reasoning, OR                │
   │    · user picks (with LLM suggesting and explaining)                │
   │    · output: the chosen candidate + a recorded rationale            │
   └────────────────────────────────────┬────────────────────────────────┘
                                        │
                                        ▼
   ┌─────────────────────────────────────────────────────────────────────┐
   │  Stage D — Refine (optional)                                        │
   │    · emit a refine-directive to a lower-level loop                  │
   │      ("Wednesday's stir-fry needs 10g more protein")                │
   │    · directive names its target scope explicitly                    │
   │    · subject to the iteration budget                                │
   └─────────────────────────────────────────────────────────────────────┘
```

Stages A and B are pure deterministic computation. Stage C is where intelligence enters — LLM or user, never opaque automation. Stage D is the recursion mechanism: most iterations skip it.

The deliberate division: **deterministic stages handle constraint satisfaction (provably); the intelligent stage handles qualitative tie-breaking (transparently)**. The LLM never touches hard constraints — it picks from a pre-vetted shortlist.

---

## Stage Contract

What every concrete implementation of the loop must supply.

### Hard-filter function

Pure code, no AI. Takes a candidate, returns boolean — does it satisfy hard constraints? Hard constraints are:
- Allergies and intolerances (Preference Model's hard-locked tier)
- Dietary identity (vegan, halal, etc.)
- Equipment availability (Provisions)
- For shared meal slots: union of all eaters' hard constraints

The filter runs *before* scoring. A candidate that fails the filter is never scored — it is gone. This is the same deterministic safety net described in `system-overview.md` for allergy enforcement.

If filtering eliminates everything, the loop hands off to constraint resolution (see Meal Planner's `Constraint feasibility check`) rather than degrading silently.

### Scoring function

Maps a candidate to a scalar score against soft constraints. Per scale:

- **Soft constraints** = budget targets, nutrition convergence (when treated as a target rather than a floor), preference fit, variety, time fit.
- **Daily nutritional floors** (e.g. 180g protein/day) sit between hard and soft: enforced as hard at the day-rollup level inside scoring, not as a candidate-rejection filter, so the search can find plans that *just* clear the floor.
- **Composition rule:** weighted sum of normalised sub-scores. Normalisation matters — un-normalised scores let one dimension dominate. Each sub-score is `[0, 1]`.
- **Multiplicative gates** are reserved for "should be zero if this fails": e.g. `variety_score = 0` if the same recipe appears 4 times. Use sparingly — they create scoring cliffs that the search struggles with.

Weights are stored in config, not hardcoded. Each scale owns its own weight set. They are not user-tunable in v1 (too many footguns); they are owner-tunable as the system learns what good plans look like.

### Search algorithm

**Default: beam search.** Width 20, depth = scope size (e.g. 21 slots for a week). Keeps the top-20 partial plans at each step, expands each by candidate recipes for the next slot, prunes back to 20. Output: top-N complete plans.

Beam search is chosen because:
- Sub-second on realistic catalogue sizes (~500 recipes × 21 slots).
- Easy to implement and easy to debug — the beam at each step is inspectable.
- Tunable: width controls quality/speed tradeoff.
- Doesn't require optimality. Plans are "good and explainable," not provably best.

Alternatives considered:
- **ILP (Integer Linear Programming):** provably optimal, but reformulating qualitative constraints (variety, freshness, batch-cook compatibility) as linear constraints is painful, and explainability is worse.
- **Simulated annealing:** handles non-convex weight landscapes well, but harder to reason about and converges stochastically.
- **Exhaustive enumeration:** infeasible at week scale (combinatorial blowup); fine at recipe scale where the candidate set is small (e.g. 4 substitution candidates per ingredient).

A scale may use a different algorithm if its search space justifies it — the recipe-level Adaptation Pipeline, with smaller search spaces, can use exhaustive scoring.

### Rollup aggregator

Per-candidate summary stats. Always a fixed flat shape per scale, always computable from the candidate alone (no extra DB lookups). This is what Stage C sees. Examples:

- **Week-level rollup:** `{daily_nutrition[7], weekly_macro_totals, cost_total, variety_index, time_total_per_day[7], constraint_violations}`
- **Recipe-level rollup:** `{macro_delta, cost_delta, time_delta, ingredient_count_delta, taste_alignment_score}`

Fixed shape so the LLM prompt template is stable. Computable from the candidate alone so rollup is fast and cacheable.

### Top-N

Default `N = 5`. Reasoning:
- 3 too few — the LLM gets bullied into picking whatever the deterministic search ranked first; the choice stage adds no value.
- 10 wastes tokens and slows the user when they're picking; the marginal candidate is rarely better than the 5th.
- 5 is the smallest N that gives a real choice while keeping the prompt under 4k tokens at week scale.

Each scale may override. If the scoring function is highly confident (e.g. the top candidate scores 2× the runner-up), the loop can skip Stage C and accept the top candidate directly — log this skip as a decision-log event.

### LLM context shape

When Stage C uses an LLM, the prompt receives:
- The N candidates *as candidates* — slot/ingredient summaries plus rollup stats.
- The constraint set in plain language.
- Any refine-directive that triggered this iteration.

The prompt **does not** receive:
- The full underlying recipe pool.
- Internal scoring details (the LLM should reason about the candidates, not second-guess the scoring).
- User preference history unrelated to the scope.

Token budget at week scale: ~3-4k input. The LLM's job is qualitative tie-breaking, not constraint satisfaction — anything more than a candidate comparison is wasted context.

---

## Where It Applies

### Week level — Meal Planner

Maps to the loop as follows:

| Stage | Meal Planner mechanic |
|---|---|
| A | Phase 1 plan composition: hard-filter recipes by constraints, score against all 3 data models, beam-search across 7 days × meal slots → 5 candidate plans |
| B | Per-plan rollup: daily/weekly nutrition convergence, cost, variety, time fit, constraint violations |
| C | Phase 2: LLM picks from 5 with reasoning, plus optional creative gap-fill (e.g. "add a yoghurt to hit Wednesday's protein floor") |
| D | Refine-directives to recipe scale ("Wednesday's stir-fry needs to drop £2") routed via the Recipe Optimiser |

Full design: `meal-planner.md` (forthcoming).

### Recipe level — Adaptation Pipeline

Maps to the loop as follows:

| Stage | Adaptation Pipeline mechanic |
|---|---|
| A | Generate candidate adaptations (ingredient swap, portion change, method tweak) — usually exhaustive given the small candidate space |
| B | Per-adaptation rollup: macro delta, cost delta, time delta, taste alignment |
| C | Either user-pick (user catalogue, requires approval) or auto-apply (system catalogue) |
| D | Rare. Could emit a directive to the ingredient-substitution sub-step, but mostly the recipe scale is the leaf. |

Full design: `recipe-system.md`.

### Mid-week re-optimisation

The same week-level loop, scoped to remaining days only. Differences:
- Already-purchased ingredients are pinned (Provisions filter excludes spoilage, otherwise the existing pantry is fixed input).
- Already-eaten meals are immutable; their nutrition is folded into rollup as already-consumed.
- Stage C's LLM prompt names this as a re-optimisation — different reasoning frame ("preserve continuity, fix the disruption") than initial planning.

Same algorithm, smaller scope, more pinned constraints.

---

## Where It Does Not Apply

The loop is bounded above and below. Naming the bookends keeps the abstraction honest.

### Trend / multi-week scale

A "trend" is a goal — cutting phase, increase iron intake, follow a health-platform directive. There is no candidate enumeration at this scale: the user (or a connected health platform) sets a goal, and that goal flows into the week-level loop as an additional constraint or weight adjustment.

Trend ≠ optimisation cycle. Trend = constraint source.

### Day scale

A day is not independent of its week. A Sunday batch cook fills Monday–Thursday lunches; an over-budget Tuesday is rescued by a cheap Wednesday. Treating the day as its own optimisation scope generates plans that look fine per day and waste-prone per week.

Day is a UI view into the week-level plan. It is not its own scale of the loop.

### Ingredient parameter tuning

"Swap chicken for turkey" is candidate-choice — fits the loop. "How much salt?" is a continuous parameter — does not. The loop's discrete-candidate shape degenerates when the decision is parameter tuning rather than selection.

Parameter tuning is left to whatever component owns the parameter (recipe author for salt, nutrition mapper for ingredient quantity-to-grams, etc.) — not modelled as a loop instance.

---

## Inter-Level Protocol

### Direction of flow

- **Down** (constraints, refine-directives): higher level → lower level. Refine-directives carry a target scope, a desired delta, and the trace ID of the parent decision.
- **Up** (rollup stats, infeasibility signals, completion): lower level → higher level. A lower-level loop reporting "directive infeasible" escalates back up.

### Sync vs event

- **Synchronous service call** when the caller waits on the result. The Meal Planner invoking the Recipe Optimiser at plan-time is synchronous — the planner needs the adapted recipe before it can compose the plan.
- **Event** when the caller fires and forgets. A data model change (e.g. Provisions update from a feedback event) publishes an event; the Meal Planner subscribes and may trigger mid-week re-optimisation as a downstream effect. The publisher does not wait.

This split matches the Spring `@TransactionalEventListener(AFTER_COMMIT)` pattern in `technical-architecture.md`. Sync calls return; events propagate after commit.

### Credit assignment

When a higher-level loop emits a refine-directive, *which* lower level handles it?

**Rule: the directive names its target scope explicitly.** A week-level Stage D doesn't say "fix the protein gap"; it says "raise Wednesday's lunch protein by 10g," which routes to the Recipe Optimiser for that recipe.

If a directive arrives without an explicit target — rare, mostly from manual user feedback — the lowest level capable of resolving it takes it. This avoids the credit-assignment cycle where the week swaps a recipe to add chicken, the recipe optimiser then strips chicken to fit budget, and the week re-adds it.

---

## Termination and Cycle Prevention

The refine loop can in principle run forever. Termination is enforced.

### Iteration budget

Default: **3 refine cycles per top-level invocation.** A "cycle" is one full A→B→C→D→A pass. After three, the loop accepts the current best candidate even if Stage D would emit another directive. The unsatisfied directive is logged, not discarded — it can become a notification to the user.

Three is a guess based on rough intuition; revise once we see real refine-cycle behaviour.

### Fixed-point detection

If Stage A produces the same top-N (within a fuzzy hash of candidate IDs) twice in a row, the loop is converged and stops, regardless of remaining iteration budget. Prevents wasted cycles when the search space is genuinely settled.

### User abort

The loop is cancellable from the UI at any iteration. A cancelled loop persists nothing — the user goes back to the prior accepted plan.

---

## Decision Log

Every loop iteration writes one record to a decision log table. Schema:

```
{
  decision_id: uuid,
  trace_id: uuid,                    // links related decisions across scales
  parent_decision_id: uuid | null,   // the decision that triggered this one
  scale: 'week' | 'recipe' | …,
  triggered_by: 'user' | 'feedback' | 'data-model-change' | 'refine-directive',
  inputs: {
    scope, constraints_summary,
    refine_directive: { … } | null
  },
  candidates: [{ id, score, rollup }, …],   // up to N
  chosen: { candidate_id, source: 'llm' | 'user' | 'deterministic-skip' },
  reasoning: string,                 // LLM rationale or "auto-skip: top score 2x runner-up"
  emitted_directive: { … } | null,   // Stage D output, if any
  iteration: int,                    // within this trace
  created_at: timestamp,
  duration_ms: int
}
```

This single mechanism does the work of three separate concerns:
- **Observability** — debugging "why this plan?" reads the log.
- **User-facing explanation** — the UI's "AI suggests #3 because…" is rendered from `reasoning`.
- **Audit trail** — feedback misclassification investigations trace the `parent_decision_id` chain.

No separate explanation feature, no separate audit table. One log, multiple readers.

The trace ID lets a week-level decision and the recipe-level decisions it triggered be queried as a unit. When the user asks "why is Wednesday's stir fry different this week," the answer reconstructs from the trace.

---

## Failure Modes

| Failure | Where | Response |
|---|---|---|
| No candidate passes hard-filter | Stage A | Hand off to constraint feasibility check (Meal Planner). Surfaces conflict to user with relaxation options; never silently degrades. |
| All candidates score below threshold | Stage A → B | Surface relaxation suggestions ranked by quality recovered per unit constraint loosened. Same mechanism as the constraint feasibility logic. |
| LLM returns malformed pick or invalid candidate ID | Stage C | Deterministic fallback: accept the highest-scoring candidate. Log the failure. Retry once on a fresh prompt before falling back. |
| LLM picks a candidate but reasoning is incoherent or contradicts the rollup | Stage C | Accept the pick (the deterministic search vetted it); flag the reasoning quality for prompt-tuning. Don't second-guess the LLM mid-flight. |
| Refine-directive infeasible at target level | Stage D → next iteration's Stage A | Lower level returns infeasibility signal. Escalate up: parent loop receives the failure, can pick a different candidate or stop refining. |
| Iteration budget exhausted with directive still pending | Stage D | Accept current candidate, log the unmet directive, optionally notify the user. |
| Stage C times out (LLM API down) | Stage C | Deterministic fallback to top-scored candidate. Log the timeout. The user sees the plan plus a note that AI ranking was unavailable. |
| Concurrent invocations on the same scope | Loop entry | Reject the second invocation. The loop is single-flight per scope (one week-plan generation at a time per household, one adaptation per recipe at a time). |

---

## Implementation Note — Style Guide, Not Base Class

This pattern is documented as a contract every applicable scale follows, not as a shared Java abstraction. No `OptimisationLoop<TCandidate, TConstraint>` interface, no abstract base class.

Why:
- The two real instances differ in candidate type (a `WeeklyPlan` vs a `RecipeAdaptation`), in scoring shape (multi-day rollup vs single-recipe delta), and in search algorithm (beam search vs exhaustive). A generic abstraction has to either erase those differences (loss of type safety) or carry enough type parameters to be unreadable.
- The total surface area is two implementations. Two is below the threshold where shared infrastructure pays off; the duplicated code is small (a beam-search utility, a scoring composition helper) and can be shared as plain utility classes without enforcing a shape.
- The contract is what matters; the contract is documented here. Each module implements it idiomatically.

What *is* shared as code:
- The decision log table and write helper (one DB table, one repository).
- A scoring composition utility (normalisation, weighted sum, multiplicative gates).
- Optionally, a beam-search utility if both scales end up using it identically.

What is *not* shared as code:
- Candidate types (`WeeklyPlan`, `RecipeAdaptation` — module-owned).
- Hard-filter implementations (delegated to module-specific predicates).
- LLM prompt templates (per-scale).

If a third loop instance appears in future, revisit. Two implementations is duplication; three is a pattern worth abstracting.

---

## Glossary

- **Candidate** — one option produced by Stage A. A whole weekly plan, or a specific recipe adaptation.
- **Rollup** — Stage B's flat-shaped summary of a candidate.
- **Refine-directive** — Stage D's output: an instruction to a lower scale, naming its target scope and desired delta.
- **Trace ID** — UUID linking related decisions across scales for audit/explanation.
- **Hard constraint** — a constraint that cannot be relaxed (allergy, dietary identity, equipment). Enforced by hard-filter in Stage A.
- **Soft constraint** — a constraint that can be relaxed by user choice (budget, variety, preference fit). Enforced via the scoring function.
- **Scope** — what one invocation of the loop is optimising over (a 7-day week, a single recipe).
- **Scale** — which instance of the loop (week, recipe). The scale determines candidate type, scoring shape, and rollup shape.
- **Iteration** — one A→B→C→D pass within a single top-level invocation.
- **Top-level invocation** — the outermost call to the loop (e.g. user clicks "generate plan"). Contains up to N iterations bounded by the iteration budget.
