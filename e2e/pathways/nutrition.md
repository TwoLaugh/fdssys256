# Nutrition Domain — User-Pathway Catalogue

> Code-agnostic behavioural catalogue derived purely from the HLD design docs. Double duty: (a) source for E2E test scenarios; (b) behavioural spec for the frontend. No endpoints, HTTP verbs, class names, or DB tables — pure user/behaviour language. Where the HLD is silent on something a user would obviously need, it is flagged `[HLD-GAP]` rather than invented.

---

## 1. Domain Summary

The Nutrition domain is one of the system's three **data models** — passive state, not logic. It holds *what the user's body needs* (calorie/macro/micro targets, per-meal distribution, daily floors, dietary patterns, activity adjustments) and *what it's actually getting* (planned-vs-actual intake, daily/weekly aggregates), plus a free-text food/mood journal and the **Nutrition Engine** (ingredient→nutrition mapping pipeline backed by a USDA / Open Food Facts cache). It is the quantitative counterpart to the Preference Model. In the three-loop architecture it is the **Nutrition Loop**: targets and intake are read by the week-scale Meal Planner (to score plans and drive mid-week re-optimisation) and the recipe-scale Adaptation Pipeline (to adapt recipes against macro gaps), but the model owns neither loop — it is read as input. It does NOT own dietary identity (vegetarian/keto/halal — that is the Preference Model's hard-constraints tier, consumed here as an input) and is NOT a health-tracking platform (mood scales, weight, labs, wearables, genomics live in the separate Personal Biology Platform, which integrates via dietary directives). The Nutrition Engine is the calculation layer every recipe passes through regardless of source — external nutrition is always discarded and recalculated internally.

## 2. Actors

| Actor | Role in this domain (per HLD) |
|---|---|
| **Primary user** | Sets/edits nutritional targets (calorie/macro/micro, per-meal, floors, activity, eating window); confirms/overrides/skips logged meals; logs standalone food; writes journal entries; corrects ingredient mappings; overrides nutrition values; reviews/accepts/rejects/modifies incoming dietary directives. |
| **Household member** | Has their own account + own Nutrition Model; gives feedback on their own meals (portion/health signals route here). Targets are per-user. Authority over the shared plan's nutrition is the primary user's. |
| **Nutrition Logger (system actor)** | Pre-fills each day's meals from the weekly plan with calculated nutrition; recomputes daily/weekly aggregates and "remaining" on every confirm/override/skip; AI-parses free-text deviations through the engine. |
| **Nutrition Engine (system actor)** | Maps recipe ingredients → USDA/OFF entries (AI parse → cache check → API search → AI match → calculate → cache), computes per-serving nutrition + per-ingredient confidence; recalculated on create/import/evolve/edit/mapping-correction. |
| **Meal Planner (consumer)** | Reads targets (daily floors + weekly averages), per-meal distribution, micros, activity adjustments, eating window, and actual intake. Divergence in actual intake triggers its mid-week re-optimisation (downstream event, planner-owned). |
| **Recipe Optimiser / Adaptation Pipeline (consumer)** | Reads nutrition targets to adapt recipes ("protein 25g but floor needs 50g — suggest higher-protein variant"). |
| **Notification System (consumer)** | Reads intake tracking for alerts ("100g protein with one meal left", "exceeded sodium today"). |
| **Feedback System (caller)** | Routes portion/macro/health-signal feedback here (may adjust per-meal distribution); journal entries provide classification context. Distinct from the logger. |
| **Health Platform / Personal Biology Platform (external, optional)** | Pushes dietary directives (target adjustment, macro rebalance, ingredient restriction, elimination/reintroduction protocol, sensitivity downgrade) via a propose/accept flow; consumes exported intake/composition/journal/adherence data. |
| **USDA FoodData Central / Open Food Facts (external dependencies)** | Ingredient nutrition source for mapping + standalone food search/barcode lookup. |
| **Scheduler / system clock (system actor)** | Drives daily nutrition aggregation, directive auto-expiry, day rollover for the logger. |

## 3. Action Space (frontend-spec backbone)

Flat, exhaustive list of every distinct user (or user-facing system) action the HLD permits. Each: verb-phrase + one-line description + HLD ref. Downstream pathways draw from this.

### Nutritional targets — macros & calories
1. **Set/accept onboarding calorie target** — accept the Mifflin-St Jeor estimate (from age/sex/weight/height/activity) or the population-average fallback, or customise. §Bootstrapping (calorie target).
2. **Set/edit a macro gram target** — set absolute grams (protein/carbs/fat/fibre), not ratios; override any individually. §Macro targets; §Bootstrapping (macro targets).
3. **Set a daily floor on a macro** — mark a target (e.g. protein 160g) as a non-negotiable daily minimum. §Macro targets (daily floors).
4. **Set the enforcement mode of a target** — choose `daily_floor` vs `weekly_average` per target. §Macro targets (`enforcement`).
5. **Set calorie tolerance band** — under/over tolerance around the calorie target. §Macro targets (calories.tolerance).
6. **Configure per-meal macro distribution** — spread daily targets across breakfast/lunch/dinner/snacks (calories + protein, guideline not hard). §Per-meal macro distribution; §Bootstrapping (per-meal distribution).
7. **Skip nutrition setup entirely** — decline onboarding targets; planner then runs with no nutrition constraints. §Bootstrapping (skip).

### Nutritional targets — micros
8. **Set/edit a micro target** — set a target for any tracked micro (iron, zinc, B12, vitamin D, omega-3, magnesium, calcium, potassium). §Micro targets.
9. **Set a micro upper limit** — e.g. sodium upper_limit 2300mg (cap, not floor). §Micro targets (sodium upper_limit).
10. **Accept DRI-defaulted micro targets** — defaults from dietary reference intakes adjusted for age/sex; tracked internally, not shown in v1 UI. §Micro targets; §Bootstrapping (micro targets).

### Nutritional targets — patterns & activity
11. **Enable/configure an eating window** — set IF window start/end (e.g. 16:8 12:00–20:00); a Nutrition Model hard boundary. §Dietary patterns (eating window).
12. **Disable/clear the eating window** — turn IF off (default off). §Dietary patterns; §Bootstrapping (eating window not set by default).
13. **Configure activity adjustments** — per-activity-level calorie/carb modifiers (rest/light/training/heavy). §TDEE variations.
14. **Mark a day's activity level** — manually tag a day (rest/training/etc.) so its calorie/carb targets flex. §TDEE variations (`input_method: manual`).

### Intake tracking (Nutrition Logger)
15. **View today's planned-vs-actual log** — pre-filled day with planned + actual per meal + daily_totals (planned/actual_so_far/remaining). §Planned vs actual tracking.
16. **Confirm a meal as eaten-as-planned** — single-tap confirm → actual = planned. §How it works (confirm).
17. **Override a meal via free-text** — "I had a cheese sandwich instead" → AI parses, maps through engine, logs actual nutrition. §How it works (override, free-text).
18. **Override a meal via manual edit** — adjust quantities / swap ingredients directly. §How it works (override, manual edit).
19. **Skip a meal** — mark as skipped → logged as zero intake. §How it works (skip).
20. **Log a standalone food item** — search USDA/OFF for a snack/drink/unplanned item and log it directly (MyFitnessPal-style). §Standalone food logging.
21. **Accept a pre-suggested accompaniment** — log a habitual accompaniment (yoghurt, fruit, coffee) pre-suggested from the Preference Model. §Standalone food logging.
22. **View daily/weekly aggregates** — daily totals + weekly convergence against targets/floors. §Planned vs actual; §How It Gets Used (planner).

### Food/mood journal
23. **Write a journal entry for a meal** — free-text note tied to a meal slot ("felt bloated after dinner"). §Food/Mood Journal.
24. **Review the journal** — read back personal food-diary entries to spot patterns (no in-app AI analysis). §How it's used (by the user).

### Nutrition engine & corrections
25. **View a recipe's calculated nutrition** — per-serving macro/micro + mapping status (engine-owned, stored on the recipe). §Nutrition Engine; system-overview §Data quality.
26. **Correct an ingredient's USDA mapping** — "that's not the right match" → re-map; feeds the cache; triggers recalculation. §User Override; §Recalculation triggers; §Ingredient Mapping Cache.
27. **Manually override a nutrition value** — set a specific value ("actually ~500 cal"); override flagged so recalculation won't overwrite. §User Override.
28. **Trigger recalculation (implicit)** — via create/import/evolve/manual-ingredient-edit/mapping-correction. §Recalculation triggers.

### Health-platform directives (propose/accept)
29. **Review an incoming dietary directive** — see notification + evidence summary + proposed action. §Propose, not apply.
30. **Accept a directive** — apply to the mapped model with metadata (source/confidence/expiry) preserved. §Propose, not apply.
31. **Reject a directive** — decline; no change applied. §Propose, not apply.
32. **Modify-then-accept a directive** — e.g. "reduce eggs but don't eliminate". §Propose, not apply.
33. **Export nutrition data to the health platform** — actual intake, meal composition, journal entries, directive adherence (bidirectional integration). §What MealPrep AI exports.

## 4. State Models

### 4.1 Nutrition Model configuration lifecycle
```
UNCONFIGURED (cold start — both catalogues/targets empty)
   │  ├─ user accepts/customises onboarding targets → CONFIGURED
   │  └─ user skips nutrition setup → SKIPPED (planner runs with NO nutrition constraints)
   ▼
CONFIGURED (targets active; planner optimises against them)
   │  ├─ user edits a target (manual direct edit, immediate)        → CONFIGURED (new values)
   │  ├─ accepted directive adjusts a target/activity               → CONFIGURED (with directive metadata)
   │  └─ user clears all targets                                    → SKIPPED
```
Cross-cutting attributes (not lifecycle states): each macro carries `enforcement ∈ {daily_floor, weekly_average}`; micros carry `target` or `upper_limit`; the eating window is `enabled true|false`.

**Illegal / disallowed transitions (→ error pathways):**
- A directive may NOT silently change a target — every directive requires user review (propose, not apply); even high-confidence directives.
- The eating window (Nutrition Model) must take precedence over Preference `meal_timing`; a configuration where preferred breakfast falls outside the eating window is an **incompatibility to be flagged**, not silently honoured either way.
- Macro targets are grams, not ratios — a ratio-only target is not the model's native form `[HLD-GAP]` (onboarding *derives* grams from a ratio split, but whether a user can store a ratio is unstated).

### 4.2 Meal intake (logger) lifecycle
```
PLANNED (pre-filled from the weekly plan, nutrition pre-calculated)
   ├─ user confirms                 → CONFIRMED   (actual = planned)
   ├─ user overrides (free-text)    → OVERRIDDEN  (AI-parsed actual, may differ wildly)
   ├─ user overrides (manual edit)  → OVERRIDDEN  (user-edited actual)
   └─ user skips                    → SKIPPED     (zero intake)
PENDING (slot not yet acted on; e.g. dinner earlier in the day)
   └─ rolls to one of the above, or day rolls over still PENDING
```
Standalone logged items are appended (not slot-bound) and carry a `source` (usda | open_food_facts | manual).

**Illegal / disallowed transitions (→ error/edge pathways):**
- Logging intake against a day/meal that has no plan (no PLANNED baseline) — standalone logging is allowed, but confirming a non-existent planned meal is not.
- Re-acting on an already-resolved slot (re-confirm a CONFIRMED, un-skip a SKIPPED) — re-edit semantics unspecified `[HLD-GAP]`.
- Overriding with a free-text the AI cannot parse / maps to nothing in USDA/OFF — must not silently log zero or garbage.

### 4.3 Ingredient mapping / nutrition-status lifecycle (engine)
```
UNMAPPED ingredient
   │  AI parse → cache check
   ├─ cache HIT  → MAPPED (cached USDA id + per-100g nutrition)
   └─ cache MISS → USDA/OFF search → AI match
                      ├─ confident match (≥ threshold)        → MAPPED (cached for next time)
                      ├─ low-confidence match (< threshold)   → MAPPED-NEEDS-REVIEW (flagged)
                      └─ no match at all                       → UNMAPPABLE (flagged for review)
```
A recipe's roll-up `nutrition_status` ∈ {calculated, partial, pending} (consumed from the Recipe domain): all ingredients mapped → calculated; some flagged/unmappable → partial; none mapped → pending.

**Illegal / disallowed transitions (→ error pathways):**
- Recalculation overwriting a user-flagged manual override (overrides are preserved — the documented exception).
- Using external (imported/discovered) nutrition numbers instead of internal recalculation (external nutrition is always discarded).
- The exact confidence threshold for needs-review is unstated here `[HLD-GAP]` (recipe-system uses 0.7; nutrition-model doesn't name it).

### 4.4 Dietary directive lifecycle (health-platform integration)
```
PENDING_REVIEW (pushed by health platform; user notified)
   ├─ user accepts          → ACCEPTED  → applied to mapped model w/ metadata (may be temporary, auto_expires)
   ├─ user modifies+accepts  → ACCEPTED (modified) → applied with user's adjustment
   ├─ user rejects          → REJECTED  → no change
   └─ (temporary, accepted)  → on auto_expires date → EXPIRED → mapped change auto-reverts
```
Multi-phase directives (elimination → reintroduction → resolution) carry staged phases; resolution may be user-decided from journal entries.

**Illegal / disallowed transitions (→ error pathways):**
- Auto-applying a directive without review (never permitted, even high-confidence).
- A directive whose `maps_to` model/tier the user can't accept here (ingredient restriction → Preference hard constraints, not Nutrition) — routing/ownership.

---

## 5. Pathways

> Categories: **Happy** (default success), **Alternate** (valid non-default), **Error** (validation/not-found/unauthorized/conflict/illegal-transition), **Edge** (empty/huge/boundary/duplicate/concurrent). Cross-module touchpoints (USDA/OFF, recipe recalculation, planner re-optimisation, feedback adjustments, health-platform directives) are noted; they are fully detailed in their own domain files + the cross-journey file.

### Nutritional targets — macros & calories

#### NUT-01 — Accept onboarding calorie + macro targets
- **Category:** Happy
- **Actor:** Primary user
- **Preconditions:** Onboarding; profile metadata (age/sex/weight/height/activity) supplied or skipped.
- **Action:** Accept the system-estimated calorie target and the derived macro gram split (or customise individual values).
- **Expected outcome:** Calorie target stored with a tolerance band; protein/carbs/fat/fibre stored as absolute grams (derived from the calorie target via the standard split); micros DRI-defaulted internally; per-meal distribution defaulted from the calorie target + meal structure; model → CONFIGURED.
- **Variations:** profile supplied (Mifflin-St Jeor estimate) vs skipped (population average ~2000 women / ~2500 men); accept defaults wholesale vs override one macro; with vs without a meal structure from the Preference Model (affects per-meal default).
- **HLD ref:** nutrition-model.md §Bootstrapping; §Macro targets.
- **Notes:** Cross-module: Preference Model (meal structure for per-meal default). Self-contained per fresh user.

#### NUT-02 — Set a macro as an absolute-gram daily floor
- **Category:** Happy
- **Actor:** Primary user
- **Preconditions:** Model CONFIGURED.
- **Action:** Set protein to a daily target with a non-negotiable daily floor (e.g. target 180g, floor 160g, `enforcement: daily_floor`).
- **Expected outcome:** Floor stored as grams; planner must hit ≥ floor every day, with the weekly average meeting the higher target; the floor is not relaxable day-to-day.
- **Variations:** floor = target (no flex); floor < target (daily floor + weekly target both stored); set floor on protein vs another macro; floor on a macro whose enforcement is `weekly_average` (contradiction — see NUT-04).
- **HLD ref:** nutrition-model.md §Macro targets (daily floors vs weekly averages).
- **Notes:** Floors-vs-averages is the key Nutrition Loop semantic the planner reads.

#### NUT-03 — Configure per-meal macro distribution
- **Category:** Happy
- **Actor:** Primary user
- **Preconditions:** Model CONFIGURED; daily targets set.
- **Action:** Set per-meal calorie + protein guidelines for breakfast/lunch/dinner/snacks.
- **Expected outcome:** Distribution stored as *guidelines* (planner may redistribute if a recipe fits better elsewhere, as long as daily totals + floors hold).
- **Variations:** distribution sums to the daily total; distribution under-sums or over-sums the daily total `[HLD-GAP]` (no stated rule that per-meal must reconcile with daily); 3-meal vs 3-meal+snacks structure; only some meals given a target.
- **HLD ref:** nutrition-model.md §Per-meal macro distribution.
- **Notes:** Guideline-not-constraint nature is the assertion. Reconciliation rule with daily totals is a GAP.

#### NUT-04 — Set a contradictory enforcement/floor combination
- **Category:** Error / Edge
- **Actor:** Primary user
- **Preconditions:** Model CONFIGURED.
- **Action:** Set a macro with `enforcement: weekly_average` but also a `daily_floor`, or set a daily floor above the daily target.
- **Expected outcome:** The combination is self-contradictory; system should flag/reject. `[HLD-GAP]` — the HLD defines `daily_floor` and `weekly_average` as distinct enforcement modes but never states what happens if a floor is attached to a weekly-average target, or floor > target.
- **Variations:** floor on a weekly-average macro; floor > target; tolerance band wider than the target itself.
- **HLD ref:** nutrition-model.md §Macro targets (enforcement; floor notes).
- **Notes:** Validation/consistency pathway derived from the model's own fields; resolution is a GAP.

#### NUT-05 — Set an invalid target value
- **Category:** Error
- **Actor:** Primary user
- **Preconditions:** Model CONFIGURED.
- **Action:** Submit a negative/zero/absurd target (e.g. -50g protein, 0 calories, 99999 kcal).
- **Expected outcome:** Validation rejects; existing targets unchanged. `[HLD-GAP]` — the HLD never enumerates target validation bounds (min/max plausible ranges) or required-vs-optional target fields.
- **Variations:** negative gram target; zero calorie target; implausibly high value; non-numeric input.
- **HLD ref:** nutrition-model.md §Macro targets (implied numeric structure).
- **Notes:** Validation rules entirely unspecified — assert "rejected", precise bounds are a GAP.

#### NUT-06 — Edit a target after the plan is already running
- **Category:** Alternate
- **Actor:** Primary user
- **Preconditions:** Model CONFIGURED; an active weekly plan exists.
- **Action:** Manually edit a macro/calorie target mid-week (direct edit, no AI).
- **Expected outcome:** Change takes effect immediately; this is a data-model change that propagates to the planner as a downstream event → may trigger mid-week re-optimisation of remaining days.
- **Variations:** raise protein floor; lower calorie target; change per-meal distribution; edit that makes the current plan infeasible (planner surfaces a constraint conflict with relaxation options).
- **HLD ref:** nutrition-model.md §How It Gets Used (planner); system-overview.md §Manual direct edits; §Mid-week re-optimisation; optimisation-loop.md §Sync vs event.
- **Notes:** CROSS-MODULE: Planner re-optimisation (event, not sync). Assert the model change + that a re-optimisation is offered, not the plan contents.

#### NUT-07 — Skip nutrition setup entirely
- **Category:** Alternate
- **Actor:** Primary user
- **Preconditions:** Onboarding.
- **Action:** Decline to set any nutritional targets.
- **Expected outcome:** Model → SKIPPED; the planner still produces valid plans from preferences + provisions only, with no macro/micro optimisation; nutrition tracking is an optional enhancement.
- **Variations:** skip entirely; set calorie only (partial); skip then configure later (SKIPPED → CONFIGURED).
- **HLD ref:** nutrition-model.md §Bootstrapping (skip); "everything is optional" principle.
- **Notes:** CROSS-MODULE: Planner operates degraded-but-valid. Assert plans still generate.

### Nutritional targets — micros

#### NUT-08 — Set a micronutrient target
- **Category:** Happy
- **Actor:** Primary user
- **Preconditions:** Model CONFIGURED.
- **Action:** Set/edit a target for a tracked micro (e.g. iron 18mg).
- **Expected outcome:** Micro target stored and tracked internally; full micro data is calculated from USDA from day one even though the v1 UI shows macros only.
- **Variations:** set iron higher (menstruating-women note); set vitamin D (supplemented — food contribution tracked separately); accept the DRI default vs override; set a micro the UI doesn't display (still tracked).
- **HLD ref:** nutrition-model.md §Micro targets.
- **Notes:** `[HLD-GAP]` — vitamin D "track food contribution separately" implies a supplement-vs-food split the model shape doesn't represent. v1-UI-hidden but tracked is a key assertion.

#### NUT-09 — Set a micronutrient upper limit
- **Category:** Alternate
- **Actor:** Primary user
- **Preconditions:** Model CONFIGURED.
- **Action:** Set sodium as an `upper_limit` (e.g. 2300mg) rather than a target floor.
- **Expected outcome:** Stored as a cap; the Notification System can alert when exceeded ("you've exceeded your sodium target today").
- **Variations:** sodium upper limit; a micro that has both a target (floor) and an upper limit `[HLD-GAP]` (model shows target XOR upper_limit per micro — both-at-once unmodelled); exceed the cap in actual intake (notification cross-module).
- **HLD ref:** nutrition-model.md §Micro targets (sodium upper_limit); §How It Gets Used (notification).
- **Notes:** CROSS-MODULE: Notification. Upper-limit vs target is the distinction.

### Nutritional targets — patterns & activity

#### NUT-10 — Enable an intermittent-fasting eating window
- **Category:** Happy
- **Actor:** Primary user
- **Preconditions:** Model CONFIGURED.
- **Action:** Enable an eating window (e.g. 12:00–20:00, 16:8).
- **Expected outcome:** Window stored as a Nutrition Model hard boundary; the planner constrains which meal slots are active to within the window.
- **Variations:** standard 16:8; narrow window; window enabled then disabled (NUT-12); window with no meal-structure conflict vs one that conflicts with preferred breakfast (NUT-11).
- **HLD ref:** nutrition-model.md §Dietary patterns (eating window); §How It Gets Used (planner).
- **Notes:** CROSS-MODULE: Planner (constrains active slots). Eating window is a *hard* boundary.

#### NUT-11 — Eating window conflicts with preferred meal timing
- **Category:** Error / Edge
- **Actor:** Primary user / system
- **Preconditions:** Eating window 12:00–20:00 set; Preference Model `meal_timing` says breakfast 07:00.
- **Action:** The two are active simultaneously.
- **Expected outcome:** The eating window takes precedence; the planner FLAGS the incompatibility and suggests the user update their meal structure or eating window — it does not silently honour breakfast at 07:00, nor silently drop it.
- **Variations:** breakfast just before the window opens (boundary); the user updates meal structure to resolve; the user widens the eating window to resolve.
- **HLD ref:** nutrition-model.md §Dietary patterns; §Boundaries with Other Models (eating windows vs meal timing).
- **Notes:** CROSS-MODULE: Preference Model (meal_timing) + Planner (flags incompatibility). Precedence rule is the assertion. `[HLD-GAP]` — *who* surfaces the flag (planner at plan-time vs the model on save) is not pinned.

#### NUT-12 — Disable the eating window
- **Category:** Alternate
- **Actor:** Primary user
- **Preconditions:** An eating window is enabled.
- **Action:** Disable it.
- **Expected outcome:** All meal slots become available again; default state is no eating window.
- **Variations:** disable mid-week (data-model change → planner event); disable when none was set (no-op).
- **HLD ref:** nutrition-model.md §Dietary patterns; §Bootstrapping (not set by default).
- **Notes:** CROSS-MODULE: Planner (slot availability). Self-scoped.

#### NUT-13 — Configure activity adjustments and mark a training day
- **Category:** Happy
- **Actor:** Primary user
- **Preconditions:** Model CONFIGURED.
- **Action:** Configure per-activity-level calorie/carb modifiers, then manually mark a given day as a training day.
- **Expected outcome:** That day's calorie/carb targets flex by the configured modifier (e.g. +300 cal, +50g carbs on training_day); planner reads the adjusted targets for that day.
- **Variations:** rest day (0 modifier); heavy training (+500/+80); mark today vs a future day; activity input is manual in v1 (wearable-driven later); mark a day with no activity adjustments configured (no flex).
- **HLD ref:** nutrition-model.md §TDEE variations.
- **Notes:** CROSS-MODULE: Planner (flexed targets). `[HLD-GAP]` — interaction between a marked activity day and an already-generated plan (does it trigger re-optimisation?) is not stated.

### Intake tracking (Nutrition Logger)

#### NUT-14 — View the pre-filled day and confirm a meal as planned
- **Category:** Happy
- **Actor:** Primary user
- **Preconditions:** A weekly plan exists; the logger has pre-filled today.
- **Action:** Open today's log (planned meals with calculated nutrition) and single-tap confirm a meal.
- **Expected outcome:** Meal → CONFIRMED with actual = planned; daily_totals recompute (actual_so_far up, remaining down); other meals stay PLANNED/PENDING.
- **Variations:** confirm breakfast; confirm all meals; confirm a meal whose nutrition_status is partial/pending (still confirmable, numbers carry the caveat); first meal of the day vs last.
- **HLD ref:** nutrition-model.md §Intake Tracking (pre-filled, confirm); §Planned vs actual tracking.
- **Notes:** CROSS-MODULE: pre-fill depends on Planner output + Recipe nutrition. Self-scoped on this user's day.

#### NUT-15 — Override a planned meal with free-text ("I had X instead")
- **Category:** Happy
- **Actor:** Primary user
- **Preconditions:** A PLANNED meal exists.
- **Action:** Enter free-text describing what was actually eaten ("grabbed a burrito from the shop").
- **Expected outcome:** AI parses the text → maps through the nutrition engine → logs actual nutrition; meal → OVERRIDDEN with the actual (possibly very different) macros; daily_totals + remaining recompute; large divergence may trigger planner mid-week re-optimisation.
- **Variations:** small deviation vs large (750-cal burrito vs 620-cal stir fry); free-text that maps cleanly vs ambiguously; free-text the AI cannot parse (NUT-18); deviation large enough to shift remaining targets (cross-module re-opt).
- **HLD ref:** nutrition-model.md §How it works (override, free-text); §Divergence detection; §Logger vs Feedback System.
- **Notes:** CROSS-MODULE: AI parse + Nutrition Engine mapping + Planner re-optimisation on divergence. Logger writes intake only (not preference/quality).

#### NUT-16 — Override a planned meal via manual edit
- **Category:** Alternate
- **Actor:** Primary user
- **Preconditions:** A PLANNED meal exists.
- **Action:** Adjust quantities or swap ingredients directly (no AI).
- **Expected outcome:** Meal → OVERRIDDEN with user-edited actual nutrition; aggregates recompute.
- **Variations:** adjust a quantity; swap one ingredient; edit to zero (≈ skip?); manual edit vs free-text override produce the same OVERRIDDEN state by different routes.
- **HLD ref:** nutrition-model.md §How it works (override, manual edit).
- **Notes:** `[HLD-GAP]` — boundary between "manual edit to zero" and an explicit Skip is not stated.

#### NUT-17 — Skip a meal
- **Category:** Alternate
- **Actor:** Primary user
- **Preconditions:** A PLANNED meal exists.
- **Action:** Mark the meal as skipped.
- **Expected outcome:** Meal → SKIPPED, logged as zero intake; daily/weekly remaining targets shift upward; a large skip (e.g. lunch) may trigger mid-week re-optimisation to compensate on later meals.
- **Variations:** skip one meal; skip a whole day; skip then later un-skip `[HLD-GAP]` (no un-skip / re-edit flow described); skip that triggers re-opt vs one too small to.
- **HLD ref:** nutrition-model.md §How it works (skip); §Divergence detection; system-overview.md §Mid-week re-optimisation.
- **Notes:** CROSS-MODULE: Planner re-optimisation. Re-edit-after-resolution is a GAP.

#### NUT-18 — Free-text override the AI cannot parse / map
- **Category:** Error
- **Actor:** Primary user
- **Preconditions:** A PLANNED meal; user enters unparseable or unmappable free-text.
- **Action:** Submit free-text that the AI can't interpret or that maps to no USDA/OFF entry.
- **Expected outcome:** Must not silently log zero/garbage; the user should be prompted to clarify or fall back to manual entry. `[HLD-GAP]` — the failure handling for an unparseable logger override is not specified (unlike the recipe engine, which has explicit garbage-extraction rules).
- **Variations:** gibberish text; a food with no USDA/OFF match; ambiguous text needing a clarifying question.
- **HLD ref:** nutrition-model.md §How it works (override, free-text) — failure path absent.
- **Notes:** CROSS-MODULE: AI parse + USDA/OFF. Derived error pathway; handling is a GAP.

#### NUT-19 — Log a standalone food item from USDA/OFF
- **Category:** Happy
- **Actor:** Primary user
- **Preconditions:** Logger available (with or without a plan — standalone needs no PLANNED baseline).
- **Action:** Search USDA/OFF for a snack/drink/unplanned item and log it (e.g. "banana", "greek yoghurt 150g").
- **Expected outcome:** Item logged with nutrition pulled from USDA/OFF, carrying `source` (usda | open_food_facts); appended to the day's snacks/logged list; aggregates recompute.
- **Variations:** generic food (USDA); branded/packaged item or barcode (Open Food Facts); item with no match (NUT-20); quantity specified vs default serving; logging before any plan exists.
- **HLD ref:** nutrition-model.md §Standalone food logging; §Data Sources.
- **Notes:** CROSS-MODULE: USDA + Open Food Facts (real external deps). Self-scoped on this user's log.

#### NUT-20 — Standalone food search returns no match
- **Category:** Error / Edge
- **Actor:** Primary user
- **Preconditions:** Logger available.
- **Action:** Search for a food that exists in neither USDA nor OFF.
- **Expected outcome:** No match surfaced; the user can't log nutrition for it via search. `[HLD-GAP]` — no described fallback (manual macro entry for an unmatched standalone item) for the logger.
- **Variations:** truly obscure item; misspelled search; an item OFF has but USDA doesn't (fallback to OFF succeeds — boundary with NUT-19).
- **HLD ref:** nutrition-model.md §Standalone food logging; §Data Sources (fallback to OFF).
- **Notes:** CROSS-MODULE: USDA + OFF. Unmatched-item fallback is a GAP.

#### NUT-21 — Accept a pre-suggested accompaniment
- **Category:** Alternate
- **Actor:** Primary user
- **Preconditions:** Preference Model holds habitual accompaniments (yoghurt, fruit, coffee).
- **Action:** Accept a pre-suggested accompaniment when logging.
- **Expected outcome:** The habitual item is logged with its USDA/OFF nutrition; aggregates recompute.
- **Variations:** accept the suggestion; ignore it and log something else; no habits recorded yet (no suggestions surface).
- **HLD ref:** nutrition-model.md §Standalone food logging (pre-suggested accompaniments).
- **Notes:** CROSS-MODULE: Preference Model (habits) + USDA/OFF.

#### NUT-22 — View daily and weekly aggregates / divergence
- **Category:** Happy
- **Actor:** Primary user
- **Preconditions:** Some meals logged today/this week.
- **Action:** Open the daily/weekly totals view.
- **Expected outcome:** Daily planned/actual_so_far/remaining shown; weekly convergence against targets + daily-floor adherence shown; significant divergence is detectable (feeds planner re-opt).
- **Variations:** mid-day (PENDING meals not yet counted); end of day; a day under a floor; a week converging vs diverging; nothing logged yet (empty/zero aggregates).
- **HLD ref:** nutrition-model.md §Planned vs actual tracking; §How It Gets Used (planner); system-overview.md §AI Model Tiers (nutrition aggregation = deterministic, daily).
- **Notes:** Aggregation is deterministic code. Self-scoped — assert *this user's* totals, never global.

### Food/mood journal

#### NUT-23 — Write a journal entry tied to a meal
- **Category:** Happy
- **Actor:** Primary user / Household member (own meal)
- **Preconditions:** A meal slot exists for the day.
- **Action:** Write a free-text note tied to a meal ("felt sluggish about an hour after eating").
- **Expected outcome:** Entry stored against the meal slot with a timestamp; available as context to the Feedback System classifier; exportable to the health platform if connected; no in-app AI analysis.
- **Variations:** entry on a confirmed meal; on an overridden meal; multiple entries same day (different slots); journal entry with no corresponding logged meal `[HLD-GAP]` (whether a journal entry can exist without an intake record is unstated).
- **HLD ref:** nutrition-model.md §Food/Mood Journal; §How it's used.
- **Notes:** CROSS-MODULE: Feedback System (context), Health Platform (export). No AI analysis inside MealPrep AI is the boundary.

#### NUT-24 — Review the journal
- **Category:** Happy
- **Actor:** Primary user
- **Preconditions:** ≥1 journal entry exists.
- **Action:** Read back the food diary.
- **Expected outcome:** Entries returned chronologically per meal/day; purely a personal diary — no correlations computed in-app.
- **Variations:** empty journal; many entries; entries across multiple days.
- **HLD ref:** nutrition-model.md §How it's used (by the user); §What this is NOT.
- **Notes:** Pure read. Deeper food-vs-mood correlation is explicitly out of scope (health platform).

### Nutrition engine & corrections

#### NUT-25 — View a recipe's calculated nutrition
- **Category:** Happy
- **Actor:** Primary user
- **Preconditions:** A recipe exists with nutrition calculated.
- **Action:** Open the recipe's per-serving nutrition.
- **Expected outcome:** Full macro + micro per serving returned (micros tracked even if v1 UI shows macros only), rounded for appropriate precision ("~250 calories"), with mapping status; external nutrition (if the recipe was imported) is NOT shown — only the internally recalculated values.
- **Variations:** user_verified vs imported vs ai_generated vs web_discovered recipe (all recalculated internally); recipe with a needs-review ingredient (partial); recipe with no mappable ingredients (pending).
- **HLD ref:** nutrition-model.md §Nutrition Engine; system-overview.md §Data quality and nutrition ownership.
- **Notes:** CROSS-MODULE: values are stored on the Recipe but owned/computed here. Rounding-as-precision-signal is an assertion.

#### NUT-26 — Map ingredients on recipe create/import (cache miss → API)
- **Category:** Happy
- **Actor:** Nutrition Engine (system) triggered by a recipe entering the system
- **Preconditions:** A recipe is created/imported/generated/discovered with ingredients not yet in the cache.
- **Action:** Run the mapping pipeline: AI parse (name/quantity/unit/grams_estimate/search_term/is_cooked) → cache check (miss) → USDA search → AI match (FDC id + confidence) → calculate per-serving → cache the mapping.
- **Expected outcome:** Each ingredient mapped to a USDA/OFF entry with a confidence; per-ingredient nutrition summed and divided by servings; mapping cached so the next recipe using the same ingredient is a local lookup; recipe nutrition stored.
- **Variations:** all clean high-confidence; a vague quantity ("a glug", "handful") relying on the standard-conversion guardrails (±30% acknowledged); cooked-vs-raw flagged; a branded ingredient → Open Food Facts fallback; second recipe with the same ingredient → cache HIT (no API call).
- **HLD ref:** nutrition-model.md §The Mapping Pipeline; §Data Sources; §Ingredient Mapping Cache; §Recalculation triggers.
- **Notes:** CROSS-MODULE: USDA (primary) + OFF (branded) real; AI parse/match faked deterministically in E2E. Assert mapping/cache state + nutrition_status, not exact numbers (±10-15% by design).

#### NUT-27 — Mapping below confidence threshold → needs-review
- **Category:** Edge
- **Actor:** Nutrition Engine
- **Preconditions:** An ingredient's best USDA match is low-confidence.
- **Action:** The AI match returns a below-threshold confidence.
- **Expected outcome:** Ingredient flagged needs-review; recipe `nutrition_status` → partial; the recipe is still usable (planner can use it, flagging "nutrition incomplete"); user can correct (NUT-29).
- **Variations:** one of several flagged (partial); confidence exactly at the threshold (boundary — threshold value itself is a GAP, see §4.3); a low-trust web_discovered recipe (lower base reliability).
- **HLD ref:** nutrition-model.md §Accuracy Expectations; §The Mapping Pipeline; system-overview.md §Data quality.
- **Notes:** CROSS-MODULE: surfaces in Recipe domain as a review badge. `[HLD-GAP]` — exact threshold unnamed in nutrition-model.md.

#### NUT-28 — USDA/OFF mapping fails for all ingredients
- **Category:** Edge
- **Actor:** Nutrition Engine
- **Preconditions:** A recipe whose ingredients map to nothing in USDA or OFF.
- **Action:** Run the pipeline.
- **Expected outcome:** Recipe `nutrition_status` → pending; planner may still use the recipe but flags nutrition incomplete; nothing fabricated.
- **Variations:** all unmappable (pending) vs some unmappable (partial — boundary with NUT-27); USDA down / API error (external-dependency failure) `[HLD-GAP]` — the HLD describes caching to reduce API calls but never specifies behaviour when the USDA/OFF API is unreachable mid-mapping.
- **HLD ref:** nutrition-model.md §The Mapping Pipeline; §Data Sources (caching).
- **Notes:** CROSS-MODULE: USDA/OFF. API-unreachable handling is a notable GAP.

#### NUT-29 — Correct an ingredient's USDA mapping
- **Category:** Happy
- **Actor:** Primary user
- **Preconditions:** A recipe with a flagged / wrong ingredient mapping.
- **Action:** "That's not the right match" → choose/correct the mapping.
- **Expected outcome:** Mapping corrected; the correction updates the ingredient_mapping cache (fix once, correct forever — similar recipes benefit); recipe nutrition recalculated; if it was the last flag, `nutrition_status` → calculated.
- **Variations:** correct a needs-review ingredient; correct an outright-wrong-but-confident mapping; correct to "free-range organic chicken" (a more specific entry); correct the last flag (partial → calculated); correct one of several (stays partial).
- **HLD ref:** nutrition-model.md §User Override; §Recalculation triggers; §Ingredient Mapping Cache.
- **Notes:** CROSS-MODULE: cache write benefits other recipes; recalculation. The cache-propagation is a key assertion.

#### NUT-30 — Manually override a nutrition value
- **Category:** Alternate
- **Actor:** Primary user
- **Preconditions:** A recipe with calculated nutrition.
- **Action:** Override a specific value ("this recipe is actually ~500 calories, not 400").
- **Expected outcome:** Override stored and flagged so recalculation does not overwrite it; manual overrides take precedence over calculated values — the documented exception to "always recalculated internally".
- **Variations:** override calories; override a macro; subsequent ingredient edit triggers recalc that must PRESERVE the override; later remove the override (revert to calculated) `[HLD-GAP]` (no un-override flow described); override on a recipe with still-pending mapping.
- **HLD ref:** nutrition-model.md §User Override; system-overview.md §Data quality (override exception).
- **Notes:** CROSS-MODULE: precedence over the recalc engine. Override-survives-recalc is the assertion; un-override is a GAP.

#### NUT-31 — Recalculation preserves an existing override
- **Category:** Edge
- **Actor:** Nutrition Engine (triggered by an ingredient edit)
- **Preconditions:** A recipe with a manual nutrition override on one value; an ingredient is then edited (recalc trigger).
- **Action:** Edit an ingredient → recalculation runs.
- **Expected outcome:** All non-overridden values recompute; the overridden value is left untouched (flag respected); recipe reflects mixed calculated + overridden values.
- **Variations:** edit an unrelated ingredient (override clearly survives); edit the very ingredient whose value was overridden `[HLD-GAP]` (does editing the source ingredient of an override invalidate the override? unstated); recalc from a mapping correction vs a manual edit (both must preserve).
- **HLD ref:** nutrition-model.md §User Override (flagged so recalculation doesn't overwrite); §Recalculation triggers.
- **Notes:** CROSS-MODULE: recalc trigger originates in Recipe (manual edit / evolve). Edit-the-overridden-ingredient interaction is a GAP.

### Health-platform directives

#### NUT-32 — Review and accept a target-adjustment directive
- **Category:** Happy
- **Actor:** Health Platform (push) → Primary user (review)
- **Preconditions:** Personal Biology Platform connected; a directive arrives ("increase iron target to 25mg based on low ferritin").
- **Action:** User reviews the notification (evidence summary + proposed action) and accepts.
- **Expected outcome:** Directive → ACCEPTED; the Nutrition Model micro target updated with metadata preserved (source, confidence, expiry); never auto-applied.
- **Variations:** target adjustment (micro); macro rebalance (activity adjustments — e.g. more carbs on training days from HRV data); accept as-is; high-confidence directive (still requires review).
- **HLD ref:** nutrition-model.md §Propose, not apply; §Directive types; §Directive structure.
- **Notes:** CROSS-MODULE: Health Platform (external), Notification (the alert). Metadata-preservation + review-required are the assertions.

#### NUT-33 — Reject or modify-then-accept a directive
- **Category:** Alternate
- **Actor:** Primary user
- **Preconditions:** A PENDING_REVIEW directive.
- **Action:** Reject it, or modify it before accepting ("I'll reduce eggs but not eliminate completely").
- **Expected outcome:** Reject → no model change; modify-then-accept → applied with the user's adjustment, metadata preserved.
- **Variations:** reject outright; modify a restriction's strictness; modify a numeric target down; modify an elimination into a reduction.
- **HLD ref:** nutrition-model.md §Propose, not apply (steps 3–4).
- **Notes:** CROSS-MODULE: an *ingredient-restriction* directive maps to the Preference Model's hard constraints, NOT the Nutrition Model — routing matters (NUT-35).

#### NUT-34 — Temporary directive auto-expires
- **Category:** Edge
- **Actor:** Scheduler / system clock
- **Preconditions:** An ACCEPTED temporary directive with `auto_expires` (e.g. a 6-week elimination, auto_expires 2026-07-01).
- **Action:** The expiry date is reached.
- **Expected outcome:** Directive → EXPIRED; the mapped change auto-reverts (e.g. the temporary restriction lifts); multi-phase protocols may move to a reintroduction/resolution phase rather than a clean revert.
- **Variations:** simple temporary target adjustment (clean revert); multi-phase elimination → reintroduction → resolution (staged, resolution user-decided from journal); exactly at the expiry boundary; a directive whose phases overrun `[HLD-GAP]` (no rule for a phase that ends before the user logs the required journal responses).
- **HLD ref:** nutrition-model.md §Directive structure (duration/phases, auto_expires); §Directive types (elimination/reintroduction).
- **Notes:** CROSS-MODULE: Preference hard constraints (for restriction-type), Planner scheduling + journal prompts (reintroduction). Time-boundary + multi-phase complexity.

#### NUT-35 — Directive maps to a model the Nutrition domain doesn't own
- **Category:** Error / Edge
- **Actor:** Health Platform → system routing
- **Preconditions:** An ingredient-restriction or elimination-trial directive arrives.
- **Action:** The directive's `maps_to` targets the Preference Model's hard-constraints tier (temporary), not the Nutrition Model.
- **Expected outcome:** Applied (on accept) to the Preference hard-constraints tier — the Nutrition Model consumes dietary identity/restriction as an input, it does not store it; a sensitivity-downgrade directive moves a constraint from Preference hard to Preference soft. The Nutrition Model only receives target/macro-type directives.
- **Variations:** restriction → Preference hard (temporary); sensitivity downgrade → Preference soft; reintroduction → Planner scheduling + journal prompts; a directive with an ambiguous/unknown `maps_to` `[HLD-GAP]` (handling of an unrecognised directive type unstated).
- **HLD ref:** nutrition-model.md §Directive types (Maps to column); §Boundaries with Other Models.
- **Notes:** CROSS-MODULE: Preference Model, Planner. Ownership/routing boundary — the key cross-model assertion for directives.

#### NUT-36 — Export nutrition data to the health platform
- **Category:** Happy
- **Actor:** System (on health-platform request) / Primary user (consent)
- **Preconditions:** Health platform connected.
- **Action:** The health platform reads the exported data set.
- **Expected outcome:** Actual intake (macro/micro per meal/day), meal composition (ingredients + quantities), food/mood journal entries, and directive adherence are made available; this is the data the health platform correlates with biomarkers.
- **Variations:** export with full intake history; export with sparse data (early user); journal entries included vs none written; adherence to an active elimination protocol.
- **HLD ref:** nutrition-model.md §What MealPrep AI exports; §Health Platform Integration (bidirectional).
- **Notes:** CROSS-MODULE: external Health Platform. `[HLD-GAP]` — export trigger/cadence/consent mechanics are unspecified (it states *what* is exported, not *when/how*).

#### NUT-37 — Operate fully standalone (no health platform)
- **Category:** Alternate
- **Actor:** Primary user
- **Preconditions:** No health platform connected.
- **Action:** Use the Nutrition Model without the integration.
- **Expected outcome:** Targets set manually by the user; no directives arrive; the food/mood journal is a personal diary with no automated analysis; the system is still a complete meal planner with full nutrition tracking.
- **Variations:** never connected; connected then disconnected `[HLD-GAP]` (fate of in-flight/accepted temporary directives on disconnect unstated); standalone forever.
- **HLD ref:** nutrition-model.md §Without the health platform.
- **Notes:** Confirms the integration is optional. Disconnect-cleanup is a GAP.

### Feedback-driven adjustment

#### NUT-38 — Portion-complaint feedback adjusts per-meal distribution
- **Category:** Alternate
- **Actor:** Feedback System (caller) → Nutrition Model
- **Preconditions:** The user gives portion/health-signal feedback ("too much food", "still hungry").
- **Action:** The feedback classifier routes the portion/macro/health-signal feedback to the Nutrition Model.
- **Expected outcome:** May adjust the per-meal calorie distribution; the journal entry (if any) provides classification context. This is distinct from the logger — feedback records *what you thought*, not *what you ate*.
- **Variations:** "too much food" (down-adjust that slot); "still hungry" (up-adjust); feedback that also splits to Provisions/Preference ("too expensive and too small"); feedback with a journal entry as context; feedback the classifier routes here but should route to Preference `[HLD-GAP]` (the exact portion-feedback → distribution-change rule is not specified, only that it "may adjust").
- **HLD ref:** nutrition-model.md §How It Gets Used (Feedback System); §Logger vs Feedback System; system-overview.md §Feedback System (four destinations).
- **Notes:** CROSS-MODULE: Feedback System (sync/AI classifier), Journal (context). The precise adjustment rule is a GAP. Logger-vs-feedback separation is the boundary assertion.

#### NUT-39 — Notification fires from intake tracking
- **Category:** Alternate
- **Actor:** Notification System (consumer) reading intake
- **Preconditions:** Intake logged such that a target/floor/limit is at risk or breached.
- **Action:** Aggregation detects the condition.
- **Expected outcome:** A nutrition alert is surfaced ("you're at 100g protein with one meal left — dinner needs to be protein-heavy"; "you've exceeded your sodium target today").
- **Variations:** under a daily protein floor with meals remaining; over a sodium upper limit; on track (no alert); no targets set (no alert).
- **HLD ref:** nutrition-model.md §How It Gets Used (Notification System); system-overview.md §Notification System.
- **Notes:** CROSS-MODULE: Notification. `[HLD-GAP]` — alert thresholds/timing (how far under a floor before alerting) are unspecified.

### Concurrency / edge

#### NUT-40 — Concurrent edits to targets / the same day's log
- **Category:** Edge
- **Actor:** Primary user (two devices) / user + accepted directive
- **Preconditions:** Two writes target the same nutrition state (e.g. a manual target edit + an accepted directive both touching iron; or two devices confirming the same meal).
- **Action:** Both writes land near-simultaneously.
- **Expected outcome:** A consistent last-write-wins or conflict-resolution outcome with no lost aggregate. `[HLD-GAP]` — the Nutrition Model HLD specifies no concurrency/locking semantics (unlike the Recipe domain's optimistic-lock + rebase). Derived purely from the obvious multi-device + directive-vs-manual collision.
- **Variations:** two manual target edits; manual edit vs accepted directive on the same micro; two confirmations of the same meal slot; standalone log + meal confirm racing on the same day's aggregate.
- **HLD ref:** nutrition-model.md (concurrency unaddressed); system-overview.md §Manual direct edits (immediate).
- **Notes:** Whole-domain concurrency model is a GAP. Soak mode likely to surface this.

### Flagship cross-module journey

#### NUT-41 — Targets set → plan pre-fills logger → user eats off-plan → intake diverges → recalculation/aggregation → planner re-optimises remaining days
- **Category:** Happy (flagship end-to-end)
- **Actor:** Primary user (+ Nutrition Logger, Nutrition Engine, Meal Planner, USDA/OFF as system/external actors)
- **Preconditions:** Authenticated user; Nutrition Model CONFIGURED with a daily protein floor + per-meal distribution; a weekly plan generated (so the logger is pre-filled).
- **Action (sequence):**
  1. User has nutritional targets set (calorie target, protein daily floor, per-meal distribution) — Nutrition Loop is active.
  2. The weekly plan pre-fills today's logger with planned meals and their calculated nutrition *(cross-module: Planner output + Recipe nutrition computed by the engine via USDA/OFF)*.
  3. User confirms breakfast as planned (CONFIRMED; actual = planned).
  4. At lunch the user eats off-plan and free-text overrides ("grabbed a burrito") → AI parses → engine maps through USDA/OFF → logs the actual (higher-calorie, lower-protein) intake *(cross-module: AI parse + Nutrition Engine + USDA/OFF)*.
  5. Daily/weekly aggregates recompute: actual_so_far and remaining shift; the protein floor is now at risk for the day *(deterministic aggregation)*.
  6. The divergence is large enough to be flagged; as a downstream data-model-change event, the Meal Planner offers mid-week re-optimisation of the remaining days (e.g. shift dinner to higher protein to compensate) *(cross-module: Planner, event-driven re-optimisation)*.
  7. A nutrition notification surfaces ("you're under your protein floor with one meal left") *(cross-module: Notification)*.
- **Expected outcome:** A CONFIGURED Nutrition Model whose actual intake diverged from planned; correctly recalculated daily/weekly aggregates reflecting the override; a planner re-optimisation offered for the remaining days; an auditable trail (the logger's planned-vs-actual record + the planner's decision-log trace for the re-optimisation).
- **Variations:** override vs skip as the divergence source; divergence small enough to NOT trigger re-opt (boundary); standalone snack logging nudging the day back toward the floor instead; eating-window-constrained day (fewer slots to compensate in); household member's own divergence on a shared-plan day.
- **HLD ref:** nutrition-model.md §Nutritional Targets, §Intake Tracking, §Divergence detection, §How It Gets Used; system-overview.md §Mid-week re-optimisation, §Notification System; optimisation-loop.md §Mid-week re-optimisation, §Sync vs event, §Decision Log.
- **Notes:** CROSS-MODULE backbone — step 2 (plan pre-fill + recipe nutrition), step 4 (AI parse + USDA/OFF mapping), step 6 (planner mid-week re-optimisation as an event), step 7 (notification). The "audit trail" leg spans the logger's planned-vs-actual record AND the optimisation decision-log trace. Assertions span Nutrition Model state (targets + logged intake + aggregates) + that a re-optimisation/notification was produced — never global counts.

---

## Appendix — `[HLD-GAP]` findings (consolidated)

| # | Gap | Pathway |
|---|---|---|
| N1 | Whether a user can store a *ratio* macro target (onboarding derives grams from a ratio, but native form is grams-only). | §4.1, NUT-01 |
| N2 | Target validation bounds (min/max plausible values) and required-vs-optional target fields never enumerated. | NUT-05 |
| N3 | Behaviour when a `daily_floor` is attached to a `weekly_average` macro, or floor > target, or tolerance wider than the target. | NUT-04 |
| N4 | Whether per-meal distribution must reconcile (sum) to the daily total. | NUT-03 |
| N5 | Vitamin D "track food contribution separately" implies a food-vs-supplement split the model shape doesn't represent. | NUT-08 |
| N6 | A micro having both a target (floor) and an upper_limit simultaneously is unmodelled. | NUT-09 |
| N7 | Who surfaces the eating-window-vs-meal-timing incompatibility (model on save vs planner at plan-time). | NUT-11 |
| N8 | Whether marking an activity day after a plan is generated triggers re-optimisation. | NUT-13 |
| N9 | Boundary between "manual edit a logged meal to zero" and an explicit Skip. | NUT-16 |
| N10 | No un-skip / re-edit flow for an already-resolved (confirmed/overridden/skipped) meal slot. | §4.2, NUT-17 |
| N11 | Failure handling for an unparseable / unmappable free-text logger override (no garbage-handling rule like the recipe engine has). | NUT-18 |
| N12 | No fallback (manual macro entry) for a standalone food with no USDA/OFF match. | NUT-20 |
| N13 | Whether a journal entry can exist without a corresponding logged-meal record. | NUT-23 |
| N14 | Exact USDA-match confidence threshold for needs-review is unnamed in nutrition-model.md (recipe-system uses 0.7). | §4.3, NUT-27 |
| N15 | Behaviour when the USDA/OFF API is unreachable mid-mapping (caching reduces calls but failure path is absent). | NUT-28 |
| N16 | No un-override flow to revert a manual nutrition override back to calculated. | NUT-30 |
| N17 | Whether editing the very ingredient whose value was overridden invalidates the override. | NUT-31 |
| N18 | Rule for a multi-phase directive phase that ends before the user logs the required journal responses. | NUT-34 |
| N19 | Handling of a directive with an ambiguous/unrecognised `maps_to` or unknown directive type. | NUT-35 |
| N20 | Health-platform export trigger/cadence/consent mechanics ("what" is exported is stated, not "when/how"). | NUT-36 |
| N21 | Fate of in-flight / accepted temporary directives when the health platform is disconnected. | NUT-37 |
| N22 | The precise portion-feedback → per-meal-distribution adjustment rule (only "may adjust" is stated); risk of mis-routing portion vs preference feedback. | NUT-38 |
| N23 | Nutrition-alert thresholds/timing (how far under a floor / over a limit before alerting). | NUT-39 |
| N24 | No concurrency/locking model for the Nutrition domain (multi-device target edits, directive-vs-manual collisions, racing logger writes) — unlike the Recipe domain's optimistic-lock + rebase. | NUT-40 |
