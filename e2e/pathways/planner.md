# Planner Domain — User-Pathway Catalogue

> Code-agnostic behavioural catalogue derived purely from the HLD design docs. Double duty: (a) source for E2E test scenarios; (b) behavioural spec for the frontend. No endpoints, HTTP verbs, class names, or DB tables — pure user/behaviour language. Where the HLD is silent on something a user would obviously need, it is flagged `[HLD-GAP]` rather than invented. Sources read (code-blind): `design/meal-planner.md` (primary), `design/optimisation-loop.md`, `design/system-overview.md`, `design/README.md`.

---

## 1. Domain Summary

The Planner is the system's **week-scale orchestrator** — the only component that *composes plans*: sequences of recipes scheduled across days and meal slots that simultaneously satisfy preferences, nutrition targets, and provisions/budget. It is the **week-level instance of the optimisation loop**: Stage A (deterministic hard-filter → score → beam search → top-N=5 candidate plans), Stage B (per-candidate rollup), Stage C (LLM/user pick from N + creative augmentation), Stage D (refine-directives to the Recipe Optimiser, bounded by a 3-cycle iteration budget). It is **read-only against the three data models** (Preference, Nutrition, Provisions) — it reads constraints/targets and never writes them — and reads candidate recipes from the Recipe System, which it may also invoke synchronously for plan-time adaptation. Its only writes are to its own plan storage and the decision log. It does **not** adapt recipes (Recipe Optimiser does), route feedback (Feedback System does), or talk to suppliers (Grocery does). Downstream consumers of its output: Grocery, Notification, the Nutrition Logger, and the UI.

## 2. Actors

| Actor | Role in this domain (per HLD) |
|---|---|
| **Primary user** | Triggers plan generation; picks/overrides the Stage C candidate; accepts/rejects a generated plan; marks slot states (cooking→cooked→eaten→skipped); confirms or declines mid-week re-opt; reverts to a historical plan; abandons a plan; chooses constraint-feasibility resolutions; sets per-day slot overrides (eating out, notes). Manages the shared household plan. |
| **Household member** | Eats per-person slots; gives feedback on their own meals (upstream, not direct to planner). Each has their own Preference + Nutrition model feeding shared-slot unions / per-person slots. Authority to trigger/accept the *shared* plan beyond own-meal feedback is unspecified `[HLD-GAP]`. |
| **Meal Planner (system actor, this domain)** | Runs Stages A–D; runs the constraint feasibility check before Stage A; runs the cold-start discovery+generation pre-step; composes against a Stage-A snapshot; emits plan-lifecycle events; writes the decision log. |
| **LLM / AI Service (Stage C)** | Picks one of N=5 candidates with reasoning; performs Phase 2 creative augmentation (add snack/side, swap-via-directive, re-pair sides). Never touches hard constraints. Frontier tier. |
| **Recipe Optimiser (callee)** | Receives synchronous Stage-D refine-directives and the cold-start generation pre-step requests; returns adapted recipes. Planner waits on it. |
| **Recipe System / catalogue (callee)** | Supplies the candidate recipe pool (both catalogues) for Stage A reads. |
| **Preference / Nutrition / Provisions models (upstream)** | Provide hard constraints, soft constraints+weights, nutrition floors/targets, budget, pantry, equipment, slot/lifestyle config. Read-only to the planner. |
| **Event bus (system actor)** | Delivers `ProvisionChangedEvent`, `NutritionLoggerEvent`, `PreferenceChangedEvent`, `HouseholdConfigChangedEvent` (after-commit) that may *suggest* a mid-week re-opt. |
| **Scheduler / system clock (system actor)** | Drives the default weekly cadence (`trigger: scheduled-weekly`) and week-end completion. |
| **Grocery / Notification / Nutrition Logger (downstream)** | Consume the plan output + plan-lifecycle events; planner emits, doesn't call (except it's read-only against the models). |

## 3. Action Space (frontend-spec backbone)

Flat, exhaustive list of every distinct user (or user-facing system) action the HLD permits. Each: verb-phrase + one-line description + HLD ref. Downstream pathways draw from this.

### Generate / compose
1. **Generate a plan (user-initiated)** — request a new plan for a week; runs feasibility check then Stages A–D → a `generated` plan awaiting approval (`trigger: user-initiated`). meal-planner §API (generatePlan), §Plan Lifecycle.
2. **Generate the scheduled weekly plan** — the default weekly cadence fires generation automatically (`trigger: scheduled-weekly`). system-overview §planning cadence; meal-planner §Plan (trigger).
3. **Run the constraint feasibility check** — pre-Stage-A check of post-hard-filter pool sizes + global minimum-viable score; surfaces conflicts/resolutions rather than degrading silently. meal-planner §Constraint Feasibility Check.
4. **Run the cold-start discovery+generation pre-step** — when the catalogue is below the minimum (heuristic ≥3× slot count), planner triggers Recipe-System discovery + generation (bounded ≤50 recipes) before Stage A; plan flagged `cold_start: true`. meal-planner §Cold start.
5. **Choose a constraint-feasibility resolution** — user picks a surfaced resolution (split slot per-person, relax a soft constraint, raise budget, drop a floor, workaround) or declines all. meal-planner §Conflict types; system-overview §Constraint resolution.
6. **Decline all resolutions → accept a quality-flagged plan** — planner builds the best plan it can, marks `quality_warning: true`, surfaces the unmet floor/unfilled slot. meal-planner §What happens if the user declines all resolutions.

### Stage C — choose / augment
7. **View the N=5 candidate plans** — UI shows all 5 with the LLM's recommendation highlighted + each candidate's rollup. meal-planner §Stage C; optimisation-loop §Top-N.
8. **Accept the LLM's recommended candidate** — keep the LLM pick (`chosen.source = 'llm'`). meal-planner §Stage C.
9. **Override the pick (choose a different candidate)** — user selects another of the 5 (`chosen.source = 'user'`, logged). meal-planner §Stage C.
10. **Receive creative augmentations** — Phase 2 adds snack/side, swaps an ingredient via directive, or re-pairs sides; always runs; empty list is success. meal-planner §Phase 2 Creative Augmentation.

### Accept / reject / lifecycle
11. **Accept a generated plan** — `generated → active`; becomes the current plan for the week (`accepted_at` set). meal-planner §API (acceptPlan), §Plan Lifecycle.
12. **Reject a generated plan** — `generated → rejected` with a reason; never became active. meal-planner §API (rejectPlan).
13. **Abandon an active plan** — user gives up mid-week; `active → abandoned` with reason. meal-planner §API (abandonPlan).
14. **Complete a plan (auto)** — week ends with all slots terminal → `active → completed`. meal-planner §Plan Lifecycle.

### Slot state
15. **Mark a slot cooking** — `planned → cooking`; commits the recipe (pins it in re-opt). meal-planner §API (markSlotState), §Pinning rules.
16. **Mark a slot cooked** — `cooking → cooked`; immutable thereafter. meal-planner §MealSlot, §Pinning rules.
17. **Mark a slot eaten** — `cooked → eaten`; logged actuals fold into rollup as consumed. meal-planner §Pinning rules.
18. **Mark a slot skipped** — past `planned` slot that didn't happen → `skipped`; macro/cost contribution zero. meal-planner §Pinning rules.

### Re-optimise (mid-week)
19. **Run a user-initiated mid-week re-opt** — manual button; fresh invocation scoped to the affected day onwards over `planned` future slots. meal-planner §Mid-Week Re-Optimisation, §API (reoptimisePlan).
20. **Receive an auto re-opt suggestion** — a material `ProvisionChangedEvent` / `NutritionLoggerEvent` / `PreferenceChangedEvent` emits a `ReoptSuggestedEvent`/notification ("ingredient ran out — regenerate remaining days?"). meal-planner §Triggers, §Event listeners.
21. **Confirm / decline a re-opt** — user sees a "regenerate plan from [day]?" diff preview (what changes / what's preserved) and confirms or declines before replacement. meal-planner §Scope and confirmation.

### History / revert
22. **View the active plan** — read the current `active` plan for a week. meal-planner §API (getActivePlan).
23. **View plan history for a week** — list all generations of a given week. meal-planner §API (getPlanHistory); §Plan history and revert.
24. **View plans across a date range** — list plans between two dates. meal-planner §API (getPlansBetween).
25. **View a plan by id / by ids** — open a specific plan (or a batch). meal-planner §API (getPlanById/getPlansByIds).
26. **Revert to a historical plan (copy-forward)** — restore a past generation; copies content forward into a new generation, supersedes current active. meal-planner §Plan history and revert, §API (revertToPlan).
27. **Resolve a revert's stale-ingredient warning** — on revert, if reverted ingredients are no longer in pantry, accept the partial plan or trigger a re-opt over affected slots. meal-planner §Plan history and revert.

### Slot configuration & per-day overrides
28. **Set a per-day "eating out" override** — slot kept for nutrition logging but no recipe composed. meal-planner §Slot configuration.
29. **Set a fasting-window override** — slot absent (intermittent fasting from Nutrition Model). meal-planner §Slot configuration.
30. **Define a custom slot** — user-defined slot (e.g. "post-workout shake"). meal-planner §MealSlot, §Slot configuration.
31. **Override a slot's time budget** — per-slot minutes, defaulted by kind (breakfast 15 / lunch 20 / dinner 45 / snack 5). meal-planner §Slot configuration.
32. **Set a slot shared vs per-person** — shared uses household-union constraints; per-person uses that person's constraints. meal-planner §Slot configuration, §Household Integration.
33. **Add a day note** — free-text note on a day (e.g. "eating out tonight"). meal-planner §Day.
34. **Accept a household slot split** — feasibility check proposes splitting a shared slot into per-person sub-slots (one per eater); accepting flips `shared` to false. meal-planner §Constraint feasibility at household level.

### Cancel / abort (loop)
35. **Cancel a running generation** — abort the loop from the UI at any iteration; nothing persists, user keeps the prior accepted plan. optimisation-loop §User abort.

## 4. State Models

### 4.1 Plan lifecycle
```
DRAFT (composing — Stages A–C; hidden from user)
  │  Stage A–C complete
  ▼
GENERATED (offered to user; not yet accepted)
  ├─ user accepts            → ACTIVE
  └─ user rejects            → REJECTED (terminal, immutable)
ACTIVE (current accepted plan for the week)
  ├─ mid-week re-opt accepted → new-generation plan becomes ACTIVE; this one → SUPERSEDED
  ├─ revert to a prior gen    → copy-forward new ACTIVE; this one → SUPERSEDED
  ├─ user abandons            → ABANDONED (terminal)
  └─ week ends, all slots terminal → COMPLETED (terminal)
SUPERSEDED (replaced by a newer-generation plan for the same week; immutable)
```
- `generation` increments per `(household_id, week_start_date)`; every regeneration creates a NEW plan with `generation = previous + 1` and `replaces_plan_id` → previous. **Old plans are never mutated.**
- **Exactly one `active` plan** per `(household_id, week_start_date)` at a time.
- Mutability: `draft` internal-only; `generated` "edits" replace the whole plan via re-generation; `active` allows slot-state transitions but recipe content is immutable; `superseded`/`completed`/`rejected`/`abandoned` are immutable. All plan records retained indefinitely.

**Illegal / disallowed transitions (→ error pathways):**
- Acting on an immutable plan: accept/reject a `superseded`/`completed`/`rejected`/`abandoned` plan; edit recipe content of an `active` plan; mutate any non-`generated`/non-`active` plan.
- Two `active` plans for the same `(household, week)` simultaneously (single-active invariant; re-opt/revert must supersede the prior).
- Accepting a plan that is still `draft` (not yet offered) — only `generated` is acceptable.
- Mutating an old generation in place (revert is copy-forward, never in-place edit).
- A second concurrent generation/re-opt on the same `(household, week)` scope (single-flight — second rejected).

### 4.2 MealSlot state lifecycle
```
PLANNED ── mark cooking ──► COOKING ── mark cooked ──► COOKED ── mark eaten ──► EATEN (terminal)
   │
   └── (past, never happened) ── mark skipped ──► SKIPPED (terminal)
```
- Re-opt **pinning** is driven by slot state: `eaten`/`cooked`/`cooking` immutable (pinned); `planned` in the past pinned (user may mark skipped); `planned` in the future regenerable; `skipped` pinned (zero macro/cost). Pinning provenance recorded on the slot (`pinned_reason`).

**Illegal transitions:** skipping a slot already `cooking`/`cooked`/`eaten` (it happened); transitioning a terminal slot (`eaten`/`skipped`); backward transitions (e.g. `cooked → planned`) `[HLD-GAP]` (the HLD lists the forward order and immutability but never explicitly enumerates legal/illegal slot transitions or whether un-marking is allowed).

### 4.3 Re-opt invocation lifecycle (system-internal, surfaced via suggestions/confirmation)
```
EVENT (material change) → ReoptSuggestedEvent → user confirms → fresh loop invocation (own 3-cycle budget)
                                              └ user declines → no change, active plan stays
```
- Auto-triggers never auto-replace the active plan — they suggest; the user is always in the loop.
- Event-triggered re-opt is **enqueued, not run**, while a user-initiated run is active (processed after).

### 4.4 Loop iteration lifecycle (Stages A→B→C→D, within one invocation)
```
A (filter→score→beam→top-N) → B (rollup) → C (pick + augment) → D (refine-directive?)
   ↑___________________________ up to 3 cycles _____________________________│
```
- Terminates on: iteration budget exhausted (3 cycles, accept current best, log unmet directive); fixed-point detection (same top-N twice → converged); user abort (persist nothing); a confident top candidate (top score ~2× runner-up) may skip Stage C (logged as `deterministic-skip`).

---

## 5. Pathways

> Categories: **Happy** (default success), **Alternate** (valid non-default), **Error** (validation/not-found/unauthorized/conflict/illegal-transition), **Edge** (empty/huge/boundary/duplicate/concurrent). Cross-module touchpoints (Recipe candidate pool, Recipe Optimiser plan-time/refine, the three data models, Grocery price history, Notification) are noted; they are fully detailed in their own domain files + the cross-journey file.

### Generate / compose

#### PLAN-01 — Generate a plan for a week (user-initiated, happy path)
- **Category:** Happy
- **Actor:** Primary user → Meal Planner
- **Preconditions:** Authenticated; catalogue above the cold-start minimum; constraints exist in all three models; no active generation in flight for this `(household, week)`.
- **Action:** Request a plan for a `week_start_date`. Feasibility check passes; Stage A hard-filters + scores + beam-searches (width 20, depth = slot count) → top-N=5; Stage B rolls up each; Stage C LLM picks one with reasoning + runs Phase 2 augmentation; Stage D emits no directive (or resolves within budget).
- **Expected outcome:** A `generated` plan (`generation: 1` or `previous+1`, `trigger: user-initiated`, `trace_id`, `decision_id`) with days/slots/scheduled-recipes, the LLM recommendation highlighted, decision-log records written; `PlanGeneratedEvent` emitted. Not yet active.
- **Variations:** first-ever generation vs regeneration of the same week (generation increments, `replaces_plan_id` set); plan with snacks (+0–7 slots) vs three meals/day only; some slots shared, some per-person; Phase 2 returns 0 augmentations (success) vs 1–5; a confident top candidate skips Stage C (`deterministic-skip`).
- **HLD ref:** meal-planner §The Loop Applied, §Plan Lifecycle, §API (generatePlan); optimisation-loop §The Loop, Abstractly.
- **Notes:** Cross-module: Recipe catalogue (Stage A reads), all three data models (constraints/targets), Grocery price history (cost sub-score), AI Service (Stage C). Self-contained: a fresh household + week; assert on this plan's id + this household's events.

#### PLAN-02 — Scheduled weekly auto-generation
- **Category:** Alternate
- **Actor:** Scheduler / system clock → Meal Planner
- **Preconditions:** Weekly cadence reached for a household; no in-flight run.
- **Action:** The default weekly cadence fires generation automatically.
- **Expected outcome:** A `generated` plan with `trigger: scheduled-weekly`; surfaced to the user for approval (same as PLAN-01 from `generated` onward).
- **Variations:** cadence configurable away from weekly `[HLD-GAP]` (system-overview says "defaults to weekly but is configurable"; the configuration surface/granularity isn't specified in the planner doc); scheduled run while a user-initiated run is in flight (single-flight — deferred/rejected, PLAN-31).
- **HLD ref:** meal-planner §Plan (trigger=scheduled-weekly); system-overview §planning cadence.
- **Notes:** Time-driven; assert the trigger field + a generated plan, not wall-clock timing.

#### PLAN-03 — Generation blocked by an infeasible constraint set (feasibility check)
- **Category:** Error / Alternate
- **Actor:** Meal Planner → Primary user
- **Preconditions:** Constraint set too restrictive to produce a viable plan (a slot's post-hard-filter pool < `min_pool_per_slot` default 3, or the simulated optimum can't meet a daily nutrition floor).
- **Action:** Request a plan; the feasibility check runs before Stage A, classifies the conflict, and surfaces ranked resolutions instead of composing.
- **Expected outcome:** No `generated` plan yet; the user is shown the conflict type + resolution options; the planner never silently relaxes a hard constraint nor degrades a soft one without consent.
- **Variations (conflict types):** household hard-constraint collision (one vegan, one keto → "split the slot per-person"); nutrition-vs-budget tension (ranked relaxations: "drop protein floor to 160g opens 12 recipes" vs "raise budget to £60 opens 8"); provisions bottleneck (equipment/pantry → simpler recipes / alternative methods / mid-week shop); over-specified preferences ("relax 3x→4x chicken cap opens 6 dinners").
- **HLD ref:** meal-planner §Constraint Feasibility Check; system-overview §Constraint resolution.
- **Notes:** Cross-module: all three models (constraint inputs), Household (union). Safety/UX-critical: assert "resolutions surfaced, no plan composed."

#### PLAN-04 — User chooses a feasibility resolution, then generation proceeds
- **Category:** Alternate
- **Actor:** Primary user → Meal Planner
- **Preconditions:** PLAN-03 surfaced ≥1 resolution.
- **Action:** Pick a resolution (split slot per-person / relax a soft constraint / raise budget / drop a floor / workaround); planner re-runs feasibility then Stages A–D.
- **Expected outcome:** A `generated` plan reflecting the chosen relaxation (e.g. split slot now has per-person scheduled recipes; or a wider soft cap opens more candidates).
- **Variations:** split-slot resolution flips `shared → false` and creates per-person sub-slots (PLAN-34); a relaxation that *still* yields too few candidates (re-surface feasibility — does it loop, and how many times? `[HLD-GAP]`).
- **HLD ref:** meal-planner §Conflict types and resolutions, §Constraint feasibility at household level.
- **Notes:** Cross-module: the model whose constraint was relaxed (read-only — planner never *writes* the relaxation; how a chosen relaxation is applied/persisted vs one-shot for this plan is unspecified `[HLD-GAP]`).

#### PLAN-05 — User declines all resolutions → quality-flagged partial plan
- **Category:** Alternate / Edge
- **Actor:** Primary user → Meal Planner
- **Preconditions:** PLAN-03 surfaced resolutions; user declines all.
- **Action:** Decline every resolution; ask for a plan anyway.
- **Expected outcome:** Planner generates the best plan it can under current constraints, marks `quality_warning: true`, and surfaces the unmet floor / unfilled slot in the plan UI. No silent failure.
- **Variations:** unmet daily nutrition floor flagged; an unfilled slot flagged; both. Note the tension with the scoring `nutrition_floor_gate` (a plan missing a daily macro floor scores ×0) — how a "best plan it can" survives a zero-gate is not reconciled `[HLD-GAP]`.
- **HLD ref:** meal-planner §What happens if the user declines all resolutions; §Scoring Function (gates).
- **Notes:** Assert the `quality_warning` flag + surfaced unmet item; the gate-vs-partial-plan contradiction is a finding.

#### PLAN-06 — Cold-start generation (catalogue below minimum)
- **Category:** Edge
- **Actor:** Meal Planner (+ Recipe System discovery/generation)
- **Preconditions:** Catalogue size below the minimum heuristic (≥3× slot count, e.g. < 63 for 21 slots).
- **Action:** Before Stage A, planner triggers a discovery + generation pre-step (bounded ≤50 recipes/run) to fill the system catalogue, then proceeds; Phase 2 does heavier lifting (typically 3–5 augmentations).
- **Expected outcome:** A `generated` plan flagged `cold_start: true`, signalling possibly-lower quality and high feedback value; system catalogue gained up to 50 recipes.
- **Variations:** completely empty catalogue (both empty); just-below-minimum boundary (exactly 3× slot count → not cold-start; one fewer → cold-start); pre-step adds the full 50 cap vs fewer; cold-start path no longer triggered after ~2 weeks.
- **HLD ref:** meal-planner §Cold start; system-overview §Cold start, §System catalogue.
- **Notes:** Cross-module: Recipe System (discovery + AI generation), all three models (constraint-matched discovery). Assert `cold_start` flag + bounded new recipes (self-scoped to this household's run).

### Stage C — choose / augment

#### PLAN-07 — Accept the LLM's recommended candidate
- **Category:** Happy
- **Actor:** Primary user
- **Preconditions:** Stage C produced N=5 candidates with one LLM-recommended.
- **Action:** Keep the LLM's pick.
- **Expected outcome:** Chosen candidate recorded with `chosen.source = 'llm'`; reasoning stored in the decision log; plan proceeds to `generated`.
- **Variations:** LLM reasoning coherent vs incoherent-but-vetted (accepted anyway; reasoning quality flagged for prompt-tuning — never second-guessed mid-flight); confident skip already pre-picked (`deterministic-skip`).
- **HLD ref:** meal-planner §Stage C; optimisation-loop §Failure Modes (incoherent reasoning).
- **Notes:** Cross-module: AI Service. Decision-log `chosen.source` is the key assertion.

#### PLAN-08 — Override the pick (choose a different candidate)
- **Category:** Alternate
- **Actor:** Primary user
- **Preconditions:** N=5 candidates shown with the LLM recommendation highlighted.
- **Action:** Select a different candidate from the 5.
- **Expected outcome:** Chosen candidate recorded with `chosen.source = 'user'` (override logged); plan proceeds to `generated` with the user's pick.
- **Variations:** override to the 2nd-ranked vs the 5th-ranked; override on a `cold_start` plan; the underlying candidate set is identical, only the choice differs.
- **HLD ref:** meal-planner §Stage C ("The user can override… Override is logged as `chosen.source = 'user'`").
- **Notes:** Self-scoped on the chosen candidate id + source.

#### PLAN-09 — Creative augmentation adds plan-level items
- **Category:** Happy
- **Actor:** LLM (Stage C, Phase 2)
- **Preconditions:** A candidate plan picked; gaps the deterministic search couldn't fill.
- **Action:** Phase 2 adds a snack/side to hit a daily target, swaps one ingredient via a Stage-D directive, or re-pairs sides with mains. Always runs.
- **Expected outcome:** Up to 5 augmentations recorded as `augmentation_notes` on scheduled recipes (plan-level, not recipe versions); each vetted post-hoc by the hard-filter.
- **Variations:** 0 augmentations (success path); add-snack to close a protein gap; re-pair a salad from Tuesday to Friday; an ingredient-swap directive (≤2/plan); exactly 5 augmentations (cap) vs >5 proposed (truncate to first 5 in returned order, log truncation).
- **HLD ref:** meal-planner §Phase 2 Creative Augmentation (when invoked, what it can/can't do, limits).
- **Notes:** Cross-module: AI Service, Recipe Optimiser (for swap directives), Provisions/Nutrition (gap detection). Augmentations don't go through recipe versioning.

#### PLAN-10 — Augmentation violates a hard constraint (discarded)
- **Category:** Error
- **Actor:** LLM (Stage C) → hard-filter (code)
- **Preconditions:** Phase 2 proposes an augmentation that would introduce an allergen / exceed a slot time budget / require missing equipment.
- **Action:** Output validation runs every augmentation through the same Stage-A hard-filter.
- **Expected outcome:** The offending augmentation is discarded silently and logged; the rest of the plan stands; the LLM is never trusted to remember constraints.
- **Variations:** allergen introduced; slot time-budget exceeded; equipment violation; all augmentations invalid (none applied — plan stands un-augmented); malformed augmentation JSON (retry once with explicit constraint reminder, then accept un-augmented plan).
- **HLD ref:** meal-planner §Phase 2 Output validation; §Failure Modes (Phase 2 invalid output).
- **Notes:** Cross-module: Preference (allergy), Provisions (equipment). Safety pathway — assert the bad item is absent + plan still valid.

#### PLAN-11 — LLM unavailable / malformed at Stage C (deterministic fallback)
- **Category:** Error / Edge
- **Actor:** Meal Planner (AI Service down)
- **Preconditions:** Stage C LLM API down, times out (>20s), or returns a malformed pick / invalid candidate id.
- **Action:** Stage C attempted; AI fails.
- **Expected outcome:** Retry once on a fresh prompt; then deterministic fallback to the highest-scoring candidate; the failure/timeout is logged; UI flags "AI ranking unavailable." Plan still produced.
- **Variations:** malformed pick (retry→fallback); invalid candidate id; API down (straight fallback); Stage C timeout vs Stage A timeout (PLAN-12). `chosen.source` becomes deterministic-fallback `[HLD-GAP]` (the enum lists `'llm' | 'user' | 'deterministic-skip'`; a *fallback-after-failure* source value isn't named).
- **HLD ref:** meal-planner §Failure Modes (LLM API down, Phase 2 invalid); optimisation-loop §Failure Modes (malformed pick, timeout).
- **Notes:** Cross-module: AI Service. Assert a plan is still generated + the "AI unavailable" flag.

#### PLAN-12 — Generation timeout / large catalogue (degrade gracefully)
- **Category:** Edge
- **Actor:** Meal Planner
- **Preconditions:** Stage A > 30s (catalogue too large) or Stage C > 20s.
- **Action:** A stage exceeds its time budget.
- **Expected outcome:** Stage A timeout → reduce beam width, retry once, then degrade to greedy selection; Stage C timeout → deterministic fallback. The plan is flagged (degraded).
- **Variations:** catalogue ~500 (<1s, no degrade) vs ~2000 (<5s) vs over-large (degrade); beam-width reduction succeeds vs falls through to greedy.
- **HLD ref:** meal-planner §Failure Modes (generation timeout); §Data volumes.
- **Notes:** Hard to make deterministic in E2E; assert the degraded-flag contract, not timing.

### Accept / reject / lifecycle

#### PLAN-13 — Accept a generated plan
- **Category:** Happy
- **Actor:** Primary user
- **Preconditions:** A `generated` plan exists; no `active` plan for the same `(household, week)` (or the prior active will be superseded by the lifecycle).
- **Action:** Accept the plan.
- **Expected outcome:** `generated → active`; `accepted_at` set; becomes the single current plan for the week; `PlanAcceptedEvent` emitted (Grocery/Notification subscribe). Recipe content now immutable; only slot states transition.
- **Variations:** first acceptance for the week; accept a regenerated (`generation: N`) plan while a prior is active — prior must transition to `superseded` (single-active invariant); accept a `cold_start` / `quality_warning` plan.
- **HLD ref:** meal-planner §API (acceptPlan), §Plan Lifecycle, §Observability.
- **Notes:** Cross-module downstream: Grocery (builds order), Notification. Assert single active + the accepted event.

#### PLAN-14 — Reject a generated plan
- **Category:** Alternate
- **Actor:** Primary user
- **Preconditions:** A `generated` plan, not yet accepted.
- **Action:** Reject it with a reason.
- **Expected outcome:** `generated → rejected` (immutable, retained); never became active; `PlanRejectedEvent` emitted with reason.
- **Variations:** reject then re-generate (new generation); reject without a reason `[HLD-GAP]` (rejectPlan takes a reason string — whether it's mandatory is unspecified).
- **HLD ref:** meal-planner §API (rejectPlan), §Plan Lifecycle.
- **Notes:** Self-scoped on plan status + event.

#### PLAN-15 — Abandon an active plan mid-week
- **Category:** Alternate
- **Actor:** Primary user
- **Preconditions:** An `active` plan; user gives up mid-week.
- **Action:** Abandon it with a reason.
- **Expected outcome:** `active → abandoned` (terminal, immutable); `PlanAbandonedEvent` emitted; slots stop being actionable.
- **Variations:** abandon with some slots already `eaten`/`cooked` (those stay as logged); abandon then generate a fresh plan for the same week (new generation, new active) `[HLD-GAP]` (whether a new plan can be generated for a week whose plan was abandoned, and its generation numbering, isn't spelled out).
- **HLD ref:** meal-planner §API (abandonPlan), §Plan Lifecycle.
- **Notes:** Terminal transition; assert status + event.

#### PLAN-16 — Plan auto-completes at week end
- **Category:** Edge
- **Actor:** Scheduler / system clock
- **Preconditions:** An `active` plan; the week has ended and all slots reached a terminal state (`eaten`/`skipped`).
- **Action:** Week-end completion runs.
- **Expected outcome:** `active → completed` (immutable); `completed_at` set; `PlanCompletedEvent` emitted.
- **Variations:** all slots eaten; mix of eaten + skipped; week ends with non-terminal slots still `planned`/`cooking` `[HLD-GAP]` (the doc says completion requires "all slots terminal" but doesn't say what happens to a plan at week-end with lingering non-terminal slots — auto-skip? stay active? flagged?).
- **HLD ref:** meal-planner §Plan Lifecycle (completed), §Observability (PlanCompletedEvent).
- **Notes:** Time-boundary; the lingering-slot case is a finding.

#### PLAN-17 — Act on an immutable / already-resolved plan
- **Category:** Error
- **Actor:** Primary user
- **Preconditions:** A plan in `superseded`/`completed`/`rejected`/`abandoned`, or a `draft` (hidden).
- **Action:** Attempt to accept / reject / abandon / edit recipe content.
- **Expected outcome:** Rejected — illegal state transition; clear error; the plan is unchanged.
- **Variations:** accept a `superseded` plan; reject a `completed` plan; abandon a `rejected` plan; accept a still-`draft` plan (only `generated` is acceptable); edit recipe content of an `active` plan (content is immutable — only slot states change).
- **HLD ref:** meal-planner §State definitions (mutability), §Plan Lifecycle.
- **Notes:** Illegal-transition pathway; derived from the mutability table.

### Slot state

#### PLAN-18 — Mark slot states forward (cooking → cooked → eaten)
- **Category:** Happy
- **Actor:** Primary user / Household member (own per-person slot)
- **Preconditions:** An `active` plan with a `planned` slot.
- **Action:** Mark the slot `cooking`, then `cooked`, then `eaten` as the meal progresses.
- **Expected outcome:** Each transition recorded; `eaten` folds logged actuals into the rollup as already-consumed; `cooking`/`cooked`/`eaten` are immutable (pin the slot in any later re-opt).
- **Variations:** straight through to `eaten`; stop at `cooking`/`cooked` then a re-opt runs (slot pinned, PLAN-22); a household member marking their own per-person slot.
- **HLD ref:** meal-planner §MealSlot, §API (markSlotState), §Pinning rules.
- **Notes:** Cross-module touchpoint: Nutrition Logger (actuals). Assert slot state + pin behaviour.

#### PLAN-19 — Mark a past unhappened slot as skipped
- **Category:** Alternate
- **Actor:** Primary user
- **Preconditions:** A past `planned` slot that never happened (e.g. yesterday's lunch never logged).
- **Action:** Mark it `skipped`.
- **Expected outcome:** `planned → skipped`; macro/cost contribution zero; pinned as skipped in re-opt.
- **Variations:** skip a single past slot; skip multiple; a future slot the user knows they'll skip `[HLD-GAP]` (the doc frames skip for *past* unhappened slots and as a re-opt pin; whether a *future* slot can be pre-skipped isn't stated).
- **HLD ref:** meal-planner §Pinning rules (planned-in-past, skipped).
- **Notes:** Zero-contribution assertion.

#### PLAN-20 — Illegal slot-state transition
- **Category:** Error
- **Actor:** Primary user
- **Preconditions:** A slot already in a terminal/committed state.
- **Action:** Attempt to skip a `cooking`/`cooked`/`eaten` slot, transition a terminal `eaten`/`skipped` slot, or move backward (e.g. `cooked → planned`).
- **Expected outcome:** Rejected; the slot is unchanged. (Per "the cooked food gets eaten; it's not going back"; "mid-cook means the recipe is committed.")
- **Variations:** skip an `eaten` slot; un-cook a `cooked` slot; re-plan a `cooking` slot. `[HLD-GAP]` — the explicit set of legal vs illegal slot transitions (and whether any un-marking exists) is never enumerated; derived from immutability prose.
- **HLD ref:** meal-planner §Pinning rules, §MealSlot state.
- **Notes:** Illegal-transition pathway; the precise rule set is a GAP.

### Re-optimise (mid-week)

#### PLAN-21 — User-initiated mid-week re-opt (happy path)
- **Category:** Happy
- **Actor:** Primary user → Meal Planner
- **Preconditions:** An `active` plan with future `planned` slots; no in-flight run for this scope.
- **Action:** Hit the re-opt button; planner runs a fresh invocation scoped to the affected day onwards over `planned` future slots, pinning `eaten`/`cooked`/`cooking`/past-`planned`/`skipped`; shows a "regenerate from [day]?" diff preview; user confirms.
- **Expected outcome:** A new-generation plan becomes `active`; the prior active → `superseded`; `ReoptTriggeredEvent` + `PlanSupersededEvent` emitted; eaten meals folded as consumed; the re-opt gets its own 3-cycle iteration budget.
- **Variations:** re-opt from today vs from a future day; re-opt that pins several already-cooked slots; user declines at the diff-preview (no change, PLAN-23); re-opt invokes the Recipe Optimiser as a pre-step.
- **HLD ref:** meal-planner §Mid-Week Re-Optimisation (triggers, pinning, scope, budget), §API (reoptimisePlan).
- **Notes:** Cross-module: Recipe Optimiser (pre-step), Provisions/Nutrition (changed inputs), Notification. Assert new active + superseded prior + pinned slots preserved.

#### PLAN-22 — Auto re-opt suggestion from a material data-model change
- **Category:** Alternate
- **Actor:** Event bus → Meal Planner → Primary user
- **Preconditions:** An `active` plan; a *material* `ProvisionChangedEvent` (spoiled/substituted ingredient affecting an unconsumed slot), `NutritionLoggerEvent` (≥15% macro variance for the day), or `PreferenceChangedEvent` (hard-constraint change).
- **Action:** The event fires after-commit; the planner (a filtered listener) emits a `ReoptSuggestedEvent` / notification ("ingredient ran out — regenerate remaining days?"). It does **not** auto-replace the plan.
- **Expected outcome:** User sees a suggestion; on confirm, PLAN-21's re-opt runs; on decline, the active plan stays.
- **Variations:** provisions spoilage vs Tesco substitution vs ran-out; nutrition divergence at exactly the 15% threshold (boundary); a hard preference/allergy change; **non-material** change that must NOT trigger (stocking up after a shop; a soft-preference tweak) — assert no suggestion; `HouseholdConfigChangedEvent` invalidating slot config.
- **HLD ref:** meal-planner §Triggers (filters), §Event listeners; system-overview §Mid-week re-optimisation; optimisation-loop §Sync vs event.
- **Notes:** Cross-module: Provisions, Nutrition Logger, Preference, Household, Notification. The trigger *filter* (material vs not) is the key assertion. Provisions utilisation here is scoring-driven, not a hard pin (see PLAN-25).

#### PLAN-23 — User declines / cancels a re-opt
- **Category:** Alternate
- **Actor:** Primary user
- **Preconditions:** A re-opt suggested (PLAN-22) or a re-opt loop running (PLAN-21).
- **Action:** Decline the "regenerate?" prompt, or abort the running loop from the UI.
- **Expected outcome:** No change; the active plan stands; a cancelled loop persists nothing (user keeps the prior accepted plan).
- **Variations:** decline at the suggestion stage; abort mid-loop (iteration in progress); abort after Stage A but before acceptance.
- **HLD ref:** meal-planner §Auto-trigger UX, §Scope and confirmation; optimisation-loop §User abort.
- **Notes:** Assert the active plan is unchanged + nothing persisted.

#### PLAN-24 — Re-opt pinning correctness across slot states
- **Category:** Edge
- **Actor:** Meal Planner
- **Preconditions:** An `active` plan with a mix of `eaten`, `cooked`, `cooking`, past-`planned`, future-`planned`, and `skipped` slots.
- **Action:** Run a re-opt.
- **Expected outcome:** Only future `planned` slots are regenerable; everything else is pinned with the correct `pinned_reason`; eaten actuals fold into rollup as consumed; skipped slots contribute zero.
- **Variations:** all future slots planned (full regen window); only the last day open; everything pinned except one slot (degenerate single-slot search); past `planned` slot left pinned vs user marks it `skipped` first.
- **HLD ref:** meal-planner §Pinning rules, §Observability (pinning provenance).
- **Notes:** Pin-provenance (`pinned_reason`) is the user-facing "why didn't this slot change?" — assert it per pinned slot.

#### PLAN-25 — Provisions utilisation favoured (not hard-pinned)
- **Category:** Alternate / Edge
- **Actor:** Meal Planner
- **Preconditions:** Pantry holds already-purchased-but-unconsumed ingredients (e.g. chicken thighs bought for this week).
- **Action:** Run a plan/re-opt; the `provisions` sub-score rewards plans that consume existing stock.
- **Expected outcome:** A plan that uses the existing stock scores higher than one that wastes it, *all else equal* — but it is **not** a hard pin; waste-avoidance is overridden only by hard constraints (allergy added that excludes a purchased item; spoilage that Provisions removes).
- **Variations:** stock used (higher score) vs deliberately wasted (lower score); user abandons an unused ingredient by updating Provisions directly (next plan stops trying to use it); a purchased ingredient newly excluded by an added allergy (dropped, not used).
- **HLD ref:** meal-planner §Provisions utilisation; §Scoring Function (provisions sub-score).
- **Notes:** Cross-module: Provisions inventory. Asserts a *scoring tendency*, not a hard rule — equivalence-class, not exact-number test.

#### PLAN-26 — Refine-directive to the Recipe Optimiser (Stage D)
- **Category:** Alternate
- **Actor:** Meal Planner → Recipe Optimiser
- **Preconditions:** Stage C identifies a recipe-level fix better handled by adaptation than wholesale swap ("Wednesday's stir-fry needs to drop £2 to make budget").
- **Action:** Planner emits a refine-directive (named target scope + desired delta + parent trace_id), waits synchronously for the adaptation, then re-runs Stage A on the affected slot.
- **Expected outcome:** The slot's recipe is replaced by the adapted version; the directive and the recipe-level decision share the parent's `trace_id`; subject to the 3-cycle budget and ≤2 swap-directives/plan.
- **Variations:** one directive resolves within budget; the same top-N appears twice → fixed-point stop; budget exhausted with a directive still pending (accept current best, log the unmet directive, optionally notify); directive named explicitly (routes to that recipe) vs an un-targeted directive (lowest capable level takes it).
- **HLD ref:** meal-planner §Stage D, §Phase 2 (swap limits); optimisation-loop §Stage D, §Inter-Level Protocol, §Termination.
- **Notes:** Cross-module: Recipe Optimiser (sync). Trace-id propagation is the auditability assertion.

#### PLAN-27 — Refine-directive infeasible at the recipe level
- **Category:** Error
- **Actor:** Recipe Optimiser → Meal Planner
- **Preconditions:** A Stage-D directive the Recipe Optimiser cannot satisfy.
- **Action:** Lower level returns an infeasibility signal.
- **Expected outcome:** Escalate up: the planner picks a different candidate plan from the N=5, or stops refining; no degraded recipe is forced.
- **Variations:** pick a different candidate; stop refining (accept current best); infeasibility on the last available candidate `[HLD-GAP]` (what happens if *all* N=5 are exhausted by escalation isn't stated).
- **HLD ref:** meal-planner §Failure Modes (refine-directive fails); optimisation-loop §Failure Modes (directive infeasible), §Inter-Level Protocol (up).
- **Notes:** Cross-module: Recipe Optimiser. Assert the planner recovers (different candidate / stop), not a forced bad recipe.

### History / revert

#### PLAN-28 — View active plan / history / range / by id
- **Category:** Happy
- **Actor:** Primary user
- **Preconditions:** ≥1 plan exists for the household.
- **Action:** Read the active plan for a week; list all generations of a week; list plans across a date range; open one plan by id (or several by ids).
- **Expected outcome:** The correct plan(s) returned; history shows every generation (retained indefinitely); active query returns the single current plan or empty if none.
- **Variations:** week with one generation vs many (history); range spanning many weeks; active query for a week with no active plan (empty, not error); huge history (bounded — see PLAN-32); id that doesn't exist (PLAN-29).
- **HLD ref:** meal-planner §API (getActivePlan/getPlanHistory/getPlansBetween/getPlanById/getPlansByIds), §Plan history and revert.
- **Notes:** Pure reads; self-scoped to this household.

#### PLAN-29 — View a plan that does not exist
- **Category:** Error
- **Actor:** Primary user
- **Preconditions:** No plan with the given id (or no active plan for the week).
- **Action:** Request a non-existent plan id, or an active plan for a never-planned week.
- **Expected outcome:** Not-found for an unknown id; empty (not error) for "no active plan this week."
- **Variations:** unknown plan id; another household's plan id (authorization vs not-found `[HLD-GAP]` — cross-household visibility/authorization isn't specified); active-plan query for an unplanned week (empty).
- **HLD ref:** meal-planner §API (getActivePlan, getPlanById).
- **Notes:** Distinguish "missing" from "no active" — plans are retained, so missing = genuinely unknown id.

#### PLAN-30 — Revert to a historical plan (copy-forward)
- **Category:** Alternate
- **Actor:** Primary user
- **Preconditions:** A week with ≥2 generations; generation N+M currently active; user wants to restore N.
- **Action:** Revert to plan version N.
- **Expected outcome:** A brand-new plan is created with `generation = M+2` and `replaces_plan_id` → current active; content copied from N; current active → `superseded`. N stays unchanged (immutability preserved).
- **Variations:** revert with all ingredients still available (clean); revert whose ingredients are no longer in pantry (stale-ingredient warning — PLAN-31); revert regardless of current Provisions state (allowed); revert to the immediately-prior generation vs a much older one.
- **HLD ref:** meal-planner §Plan history and revert, §API (revertToPlan).
- **Notes:** Assert new generation number + copied content + prior superseded + N untouched. Copy-forward is the key mechanic.

#### PLAN-31 — Revert with stale ingredients (warning + partial-accept or re-opt)
- **Category:** Edge
- **Actor:** Primary user
- **Preconditions:** Reverting to a plan whose ingredients are no longer all in pantry.
- **Action:** Revert; the system warns ("3 ingredients no longer available — re-optimise these slots?").
- **Expected outcome:** User chooses: accept the partial plan as-is, or trigger a mid-week re-opt over the affected slots.
- **Variations:** accept partial (plan active with unavailable ingredients flagged); choose re-opt (PLAN-21 over affected slots); zero ingredients missing (no warning — clean revert).
- **HLD ref:** meal-planner §Plan history and revert ("revert is allowed regardless of current Provisions state").
- **Notes:** Cross-module: Provisions (availability check). Assert the warning + the chosen branch.

### Concurrency / scale

#### PLAN-32 — Concurrent generation/re-opt on the same week (single-flight)
- **Category:** Edge / Error
- **Actor:** Primary user (double-click) / event arriving during a user run
- **Preconditions:** A generation or re-opt already in flight for a `(household_id, week_start_date)`.
- **Action:** A second generation/re-opt invocation arrives for the same scope.
- **Expected outcome:** Single-flight per scope — the second invocation is rejected ("regeneration already in progress."); an *event-triggered* re-opt that arrives during an active user-initiated run is **enqueued, not run**, and processed once the active run completes.
- **Variations:** user clicks "regenerate" twice (second rejected); an event arrives mid-user-run (enqueued, runs after); two events for the same scope (queued, one re-opt at a time); different weeks for the same household (independent — both allowed).
- **HLD ref:** meal-planner §Failure Modes (concurrent invocation; mid-week re-opt during active Phase 1), §Event listeners (queued); optimisation-loop §Failure Modes (concurrent invocations).
- **Notes:** Concurrency core. Note vs Recipe-loop single-flight: same family of guarantee, different scope key.

#### PLAN-33 — Recipe goes stale during composition (snapshot isolation)
- **Category:** Edge
- **Actor:** Meal Planner + a concurrent recipe edit
- **Preconditions:** A plan composing; a recipe in the candidate pool is edited mid-run.
- **Action:** The catalogue changes after Stage A started.
- **Expected outcome:** The plan is composed against a snapshot taken at Stage A start; it does not re-read mid-flight; stale recipes are caught at slot rendering, not at composition.
- **Variations:** recipe edited mid-Stage-A; recipe demoted/archived mid-run; the staleness surfaced only when the slot renders. `[HLD-GAP]` — what "caught at slot rendering" does for the user (warn? re-opt prompt? swap?) isn't specified.
- **HLD ref:** meal-planner §Failure Modes (recipe goes stale during composition).
- **Notes:** Cross-module: Recipe System (concurrent edit). Snapshot-at-Stage-A is the assertion; rendering-time handling is a GAP.

#### PLAN-34 — Household shared slot split into per-person sub-slots
- **Category:** Alternate
- **Actor:** Primary user → Meal Planner
- **Preconditions:** A shared slot whose household-union hard constraints are irreconcilable (one vegan, one keto), caught by the feasibility check.
- **Action:** Accept the proposed "split this slot into per-person meals."
- **Expected outcome:** The slot's `shared` flag flips to false; per-person sub-slots are created (one per eater) and composed independently against each person's constraints; nutrition rolls up per person.
- **Variations:** split one slot, keep others shared; mixed plan (shared dinners, per-person breakfasts/lunches); a per-person slot that itself becomes infeasible (re-runs feasibility for that person).
- **HLD ref:** meal-planner §Household Integration (shared vs per-person, split), §Constraint feasibility at household level; system-overview §Household Model.
- **Notes:** Cross-module: Household, Preference/Nutrition per member. Assert `shared:false` + one scheduled recipe per eater.

#### PLAN-35 — Batch-cook session spanning multiple consumption slots
- **Category:** Alternate / Edge
- **Actor:** Meal Planner
- **Preconditions:** A recipe with suitable servings + stores-well/packable metadata; user wants a single cook spread across days.
- **Action:** Planner schedules one cook ("cook 5 servings Sunday") linked via `batch_cook_session_id` to multiple consumption slots (Mon–Fri lunches).
- **Expected outcome:** Slots share one `batch_cook_session_id`; the `batch` sub-score rewards fewer cook sessions; Grocery aggregates ingredient quantities by session.
- **Variations:** one cook → 5 consumption slots; two batch sessions in a week; a stores-well boundary (recipe keeps N days — scheduling within the safe window); re-opt that pins a batch's already-cooked session while regenerating its un-eaten consumption slots `[HLD-GAP]` (interaction of batch sessions with re-opt pinning isn't detailed).
- **HLD ref:** meal-planner §ScheduledRecipe (batch_cook_session_id), §Scoring (batch); system-overview §Recipe properties (batch cooking).
- **Notes:** Cross-module: Recipe metadata (servings/stores-well/packable), Grocery (aggregation). Assert shared session id across slots.

### Scoring (observable via candidate rollups / outcomes)

#### PLAN-36 — Nutrition-floor gate kills a non-compliant plan
- **Category:** Edge
- **Actor:** Meal Planner (scoring)
- **Preconditions:** A candidate plan where any day's macro floor (e.g. 180g protein) is unmet.
- **Action:** Scoring applies the multiplicative `nutrition_floor_gate`.
- **Expected outcome:** That candidate's whole score is multiplied by 0 (dead); it cannot be selected over a floor-meeting plan.
- **Variations:** plan just clears the floor (survives) vs just misses (gated to 0); floor met all days vs missed one day; tension with the "declines all resolutions → partial plan" path (PLAN-05) where a floor-missing plan is nonetheless surfaced `[HLD-GAP]`.
- **HLD ref:** meal-planner §Scoring Function (nutrition_floor_gate); optimisation-loop §Scoring (daily floors between hard and soft).
- **Notes:** Cross-module: Nutrition Model (daily floors). Assert the gated candidate is never the chosen plan.

#### PLAN-37 — Variety gate kills an over-repetitive plan
- **Category:** Edge
- **Actor:** Meal Planner (scoring)
- **Preconditions:** A candidate where one recipe appears more than `max_repeat` (default 2) times in the week.
- **Action:** Scoring applies the `variety_gate`.
- **Expected outcome:** That candidate's score → 0; it cannot win.
- **Variations:** recipe appears exactly 2× (allowed) vs 3× (gated); `max_repeat` configurable `[HLD-GAP]` (default given, configuration surface not specified).
- **HLD ref:** meal-planner §Scoring Function (variety_gate).
- **Notes:** Boundary at `max_repeat`. Assert gated candidate excluded.

#### PLAN-38 — Cost sub-score with price confidence (uniform v1 weights)
- **Category:** Alternate / Edge
- **Actor:** Meal Planner (scoring)
- **Preconditions:** Plans whose cost is computed from confidence-weighted price-history aggregates (Grocery Tier 4).
- **Action:** Scoring composes 7 sub-scores at uniform weights (1/7 each, "Initial Weights v1"); low-confidence cost projections are regressed toward 0.5 (neutral) proportional to missing confidence.
- **Expected outcome:** A plan dominated by ingredients with confidence < 0.4 has its `cost` sub-score muted (less score-pulling power); cold-start cost is near-neutral so the other six sub-scores drive selection; rollup carries `(cost_estimate_total, cost_confidence, stale_ingredient_count)` so the user/picker sees freshness.
- **Variations:** high-confidence prices (full cost influence) vs cold-start (near-neutral); stale prices (regressed); the Phase 2 picker preferring a higher-confidence candidate when totals are similar.
- **HLD ref:** meal-planner §Scoring Function (cost sub-score, price confidence, initial weights); system-overview §Provisions (learned prices).
- **Notes:** Cross-module: Grocery price history, Provisions budget. v1 weights are an explicit placeholder; assert rollup freshness fields + that low-confidence cost doesn't dominate (equivalence-class).

### Slot wall-clock scheduling

#### PLAN-39 — Slot has a time *budget* but no wall-clock time
- **Category:** Edge / Error
- **Actor:** Primary user / Meal Planner
- **Preconditions:** A composed plan with slots carrying `time_budget_min` (duration) but no scheduled clock time.
- **Action:** The user (or a downstream prep-reminder consumer) asks "what time is dinner?" / "when do I start marinating?"
- **Expected outcome:** **Undefined in the allowed HLDs.** A `MealSlot` carries `kind`, `label`, `time_budget_min`, `shared`, `eaters`, `state`, `scheduled_recipe` — a *duration* budget, not a wall-clock start/serve time. `[HLD-GAP]` — the planner doc never models slot wall-clock times.
- **Variations:** breakfast/lunch/dinner default *budgets* (15/20/45 min) are durations, not clock times; a "post-workout shake" custom slot has no time-of-day; the Notification prep-reminder ("start marinating at 6pm" — system-overview §Notification System) and defrost-lead-time scheduling presuppose a wall-clock slot time the planner doesn't define.
- **HLD ref:** meal-planner §MealSlot, §Slot configuration (time budget); system-overview §Notification System (prep reminders), §Provisions (defrost). 
- **Notes:** `[HLD-GAP]` (significant): the brief calls for "slot wall-clock times," but across the four allowed docs slots have only a *duration budget*. Prep reminders and defrost lead-times in system-overview imply a meal-time clock value, yet no field, default, or scheduling rule for it exists in the planner HLD. Pre-cook actions / lead-time scheduling ("what must happen the day before") are referenced as a planner concern elsewhere but are not designed here. Flagged, not resolved.

### Flagship cross-module journey

#### PLAN-40 — Cold start → generate → constraint conflict resolved → accept → cook & eat → ingredient spoils → mid-week re-opt → complete
- **Category:** Happy (flagship end-to-end)
- **Actor:** Primary user (+ Meal Planner, LLM, Recipe System, Recipe Optimiser, the three data models, Provisions/Nutrition events as system actors)
- **Preconditions:** Authenticated household; week with no plan yet; catalogue below the cold-start minimum; a household hard-constraint collision on one shared slot.
- **Action (sequence):**
  1. User requests a plan for the week; the catalogue is below minimum → planner runs the **cold-start discovery+generation pre-step** (Recipe System fills the system catalogue, bounded ≤50). *(cross-module: Recipe System)*
  2. The **constraint feasibility check** runs before Stage A, detects the shared-slot collision (one vegan, one keto), and surfaces "split the slot per-person." *(cross-module: Household, Preference, Nutrition)*
  3. User accepts the split → the slot flips `shared:false` into per-person sub-slots.
  4. Stage A hard-filters + scores (7 sub-scores, uniform v1 weights, cost regressed toward neutral at cold start) + beam-searches → N=5; Stage B rolls up; Stage C LLM picks one with reasoning; Phase 2 adds 3–5 augmentations (cold-start heavy); one ingredient-swap routes a **Stage-D refine-directive** to the Recipe Optimiser (Wednesday's main needs to drop £2). *(cross-module: Grocery price history, AI Service, Recipe Optimiser)*
  5. A `generated`, `cold_start: true` plan is presented; user **accepts** → `active`; `PlanAcceptedEvent` → Grocery + Notification. 
  6. User marks Monday's slots `cooking → cooked → eaten`; eaten actuals fold into the rollup. *(cross-module: Nutrition Logger)*
  7. A `ProvisionChangedEvent` fires (Wednesday's chicken spoiled) → a material change → planner emits a **re-opt suggestion** notification.
  8. User confirms; the planner runs a **fresh re-opt** scoped to the affected day onwards, **pinning** the eaten Monday slots and any cooking/cooked slots, regenerating only future `planned` slots; the new plan becomes `active`, the prior → `superseded`. *(cross-module: Provisions)*
  9. The week ends with all slots terminal → the plan **auto-completes**; `PlanCompletedEvent` emitted. The full reasoning chain (week-level decision → Stage-D recipe decision) is queryable via the shared `trace_id`.
- **Expected outcome:** One active→superseded→active→completed lineage for the week (immutable old generations retained), with a per-person split slot, augmentations, a recipe-level refine traced to the week decision, pinned consumed slots across the re-opt, and a complete decision-log + plan-lifecycle-event trail.
- **HLD ref:** meal-planner §Cold start, §Constraint Feasibility Check, §The Loop Applied, §Phase 2, §Stage D, §Mid-Week Re-Optimisation, §Plan Lifecycle, §Observability; optimisation-loop §The Loop/Decision Log/Termination; system-overview §Meal Planner/Constraint resolution.
- **Notes:** CROSS-MODULE backbone — steps 1 (Recipe System pre-step), 4 (Recipe Optimiser refine + Grocery price history + AI Service), 6 (Nutrition Logger actuals), 7–8 (Provisions event → re-opt) are owned by their domains and detailed there + in the cross-journey file. Assertions span plan lineage + slot states + decision-log trace + emitted lifecycle events, self-scoped to this household + week.

---

## Appendix — `[HLD-GAP]` findings (consolidated)

| # | Gap | Pathway |
|---|---|---|
| P1 | Planning-cadence configuration surface/granularity ("defaults to weekly but configurable") never specified. | PLAN-02 |
| P2 | How a user-chosen constraint *relaxation* is applied/persisted (one-shot for this plan vs written somewhere) — planner is read-only against the models, so the mechanism is unclear. | PLAN-04 |
| P3 | Whether the feasibility check can loop (relaxation still yields too few candidates) and how many times. | PLAN-04 |
| P4 | Contradiction: scoring's `nutrition_floor_gate` multiplies a floor-missing plan by 0, yet the "decline all resolutions" path surfaces a floor-missing partial plan — how a zero-scored plan is still produced/ranked is unreconciled. | PLAN-05, PLAN-36 |
| P5 | `chosen.source` enum (`'llm' \| 'user' \| 'deterministic-skip'`) has no value for a *deterministic fallback after LLM failure*. | PLAN-11 |
| P6 | Whether the reject reason is mandatory (rejectPlan takes a reason string). | PLAN-14 |
| P7 | Whether a new plan can be generated for a week whose plan was abandoned, and how its generation is numbered. | PLAN-15 |
| P8 | Week-end completion when slots are still non-terminal (`planned`/`cooking`) — auto-skip, stay active, or flag? | PLAN-16 |
| P9 | The explicit legal/illegal `MealSlot` state-transition set (and whether any un-marking/backward transition exists) is never enumerated — only forward order + immutability prose. | PLAN-19, PLAN-20 |
| P10 | Whether a *future* slot can be pre-emptively marked `skipped` (skip is framed for past unhappened slots). | PLAN-19 |
| P11 | Cross-household plan visibility / authorization (another household's plan id → not-found vs unauthorized). | PLAN-29 |
| P12 | What "stale recipe caught at slot rendering" actually does for the user (warn / re-opt prompt / swap). | PLAN-33 |
| P13 | What happens if all N=5 candidates are exhausted by refine-directive infeasibility escalation. | PLAN-27 |
| P14 | Interaction of batch-cook sessions with re-opt pinning (a cooked session vs its un-eaten consumption slots). | PLAN-35 |
| P15 | `max_repeat` (variety gate) and other scoring params are given defaults but no configuration surface. | PLAN-37 |
| P16 | **Slot wall-clock times are unmodelled**: slots carry a `time_budget_min` *duration* only; no scheduled meal-time/serve-time field, default, or scheduling rule — yet Notification prep reminders ("start marinating at 6pm") and defrost lead-times presuppose one. Pre-cook actions / lead-time scheduling referenced as a planner concern but not designed in the planner HLD. | PLAN-39 |
| P17 | Household authority: whether a household member (vs only the primary user) can trigger/accept the *shared* plan beyond own-meal feedback. | §Actors |
