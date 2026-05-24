# Cross-Domain Journeys — User-Pathway Catalogue

> Code-agnostic behavioural catalogue derived purely from the HLD design docs. Double duty: (a) source for E2E test scenarios; (b) behavioural spec for the frontend. No endpoints, HTTP verbs, class names, or DB tables — pure user/behaviour language. Where the HLD is silent on something a user would obviously need, it is flagged `[HLD-GAP]` rather than invented.
>
> **What this file is.** The ten per-domain catalogues each end in a *flagship* cross-module journey, but they describe that journey from their own domain's vantage point. This file is the **synthesis layer**: the multi-domain flows that *thread through* several domains, each composed entirely of **already-catalogued domain pathways** — no new atomic behaviour is defined here. Every cross-journey (`XJ-`) carries a **Step trace**: the ordered list of per-domain pathway IDs it traverses (e.g. `AUTH-01 → RCP-03 → NUT-26 → RCP-19 → NUT-31 → log`). If a step references behaviour not catalogued in a domain file, that is a coordination gap, flagged `[HLD-GAP]` and left for the aggregated triage list (`hld-gaps.md`) — never resolved here.
>
> **Relationship to the domain flagships.** Several `XJ-`s *are* a domain flagship promoted to the cross-domain view and threaded against the other domains' pathways it touches: `XJ-01 ⊇ RCP-60`, `XJ-02 ⊇ FEED-28`, `XJ-03 ⊇ PLAN-40 + NUT-41`, `XJ-04 ⊇ HH-24`, `XJ-05 ⊇ GROC-36 + PROV-43`, `XJ-06` is the onboarding spine implied across `AUTH-22 / PREF-01 / NUT-01 / HH-01 / PLAN-06`. The domain flagship remains the *single-domain* test; the `XJ-` is the *integration* test that asserts the hand-offs between domains hold.

---

## Actors note

These journeys span domains, so the actor set is the **union** of the per-domain actors, but two roles recur and are worth naming once:

- **Primary user** — the human driving the journey end-to-end (registers, imports, edits, accepts, cooks, rates, corrects). Nearly every `XJ-` opens with an authenticated primary user (the `AUTH-` gate, folded into the "Authenticated" precondition).
- **The system-actor relay** — the chain of system actors that hand work to each other *without the user in the loop between them*: Adaptation Pipeline → Nutrition Engine (recalc on `RecipeEvolvedEvent`), Feedback Classifier → the four destination update services, Meal Planner ↔ Recipe Optimiser (sync plan-time/refine), Grocery `GroceryProvider`/AI navigator → Provisions inventory, the event bus (`AFTER_COMMIT`) → Planner re-opt suggestions + Notification. The cross-journeys exist precisely to assert these relays.

Per `README §4`: every scenario is **self-contained** (fresh user/household with a random handle, its own recipes/plans) and every assertion is **self-scoped** (this user's audit log / this plan's lineage / this household's roster) — never a global count. Soak mode is where the relays' ordering/concurrency edges surface.

---

## Pathways

> Categories carry over from the domain files: **Happy** (default success), **Alternate** (valid non-default), **Error/Edge** (the key non-happy variant). Each `XJ-` lists a **happy** end-to-end and folds its **key non-happy variant** into the Variations field with its own step trace. Cross-module touchpoints are the whole point here, so they are called out per step rather than only in Notes.

---

### XJ-01 — Web recipe → internal format → nutrition derived → manual ingredient edit → recalculation → logged (the flagship example)

- **Category:** Happy (flagship end-to-end)
- **Domains traversed:** Auth → Recipe → Nutrition → (Recipe versioning + decision/recalc log)
- **Step trace:** `AUTH-05 → RCP-03 → NUT-26 → RCP-34 → RCP-19 → RCP-20 → NUT-31 → RCP-15`
  - non-happy variant (edited ingredient goes unmappable → `partial`): `AUTH-05 → RCP-03 → NUT-26 → RCP-19 → NUT-27 → NUT-28 → RCP-21/NUT-29 → RCP-15`
- **Actor:** Primary user (+ Adaptation Pipeline, Nutrition Engine as system actors)
- **Preconditions:** Authenticated session (`AUTH-05`); a reachable recipe web page (supplied directly or surfaced via discovery `RCP-10`).
- **Action (sequence):**
  1. **`RCP-03`** — user imports the recipe from a URL; AI extracts the page to the internal structured format; external nutrition is discarded; `data_quality: imported`; `source.url` retained.
  2. **`NUT-26`** — the Nutrition Engine maps each ingredient to USDA/OFF (AI parse → cache check → search → match → calculate → cache), producing per-serving nutrition + per-ingredient confidence. *(cross-module: Recipe → Nutrition; external USDA/OFF real, AI faked in E2E.)*
  3. **`RCP-34`** — the import-time adaptation job runs, extracts the character fingerprint, usually `NO_CHANGE`.
  4. **`RCP-19`** — user manually edits an ingredient (changes a quantity or swaps an ingredient); always permitted on a user recipe; creates a new version on the current branch.
  5. **`RCP-20` → `NUT-31`** — the edit publishes `RecipeEvolvedEvent`; the Nutrition Engine recalculates the new version's nutrition (preserving any prior manual override per `NUT-31`). *(cross-module: Recipe → Nutrition via event.)*
  6. **`RCP-15`** — the change is recorded in a log: version diff + `change_reason` + `trigger` + `trace_id`, queryable in version history; the recalculation is observable on the new version.
- **Expected outcome:** A user-catalogue recipe with `data_quality: imported`, internally-calculated nutrition (recalculated after the edit), a new version capturing the manual edit, and an auditable record (version diff + decision/recalc trace). Assertions span recipe state + nutrition state + one new log entry — self-scoped to this user's recipe id.
- **Variations:** discovery-sourced `web_discovered` (`RCP-10 → …`) vs direct `imported`; edit a quantity vs swap an ingredient; **edited ingredient maps cleanly vs goes `partial`/`pending`** → the key non-happy variant routes through `NUT-27` (below-threshold → needs-review) or `NUT-28` (all unmappable → `pending`), then the user resolves it via `RCP-21`/`NUT-29` (correct the mapping, cache write benefits similar recipes, status flips back to `calculated`); recipe with vs without an image; the imported page flags a household allergy (flagged, not rejected — `RCP-03` variation) before the edit.
- **HLD ref:** recipe-system.md §Import Pipeline, §USDA mapping confidence, §Versioning, §Guardrails (recalculation), §Events; nutrition-model.md §The Mapping Pipeline, §User Override, §Recalculation triggers; system-overview.md §Recipe Engine.
- **Notes:** This is the user's stated flagship and the integration backbone for Recipe↔Nutrition. The "logged" leg spans **both** the recipe version diff (`RCP-15`) **and** the adaptation/decision trace. `[HLD-GAP]` — *recipe-system* uses a 0.7 needs-review confidence threshold but *nutrition-model* never names the threshold (recipe G-ref vs N14), so the boundary between step-2 clean-map and the non-happy `partial` branch is doc-inconsistent. `[HLD-GAP]` — whether a character-breaking *manual* edit at step 4 must become a branch rather than a version is unspecified (recipe G9): the manual-edit path is not gated by the version-vs-branch classifier.

---

### XJ-02 — Feedback learning loop: one input classified → routed to all four destinations → applied → a misclassification correction fires the reverters

- **Category:** Happy (flagship end-to-end) + the correction is the key non-happy variant
- **Domains traversed:** Auth → Feedback → {Recipe, Preference, Nutrition, Provisions} → Notification → (correction) Feedback → {Recipe reverter, Preference reverter}
- **Step trace:** `AUTH-05 → FEED-07 → [RCP-32 ∥ PREF-16 ∥ NUT-38 ∥ PROV-32] → FEED-16 → FEED-25/NOTIF-16 → FEED-19 → FEED-20 (recipe reverter) → FEED-21 (preference reverter) → PREF-13`
  - misclassification-only sub-trace (the reverter leg): `FEED-19 → FEED-20 → FEED-21`
- **Actor:** Primary user (+ Feedback Classifier, Optimiser/Recipe, Preference/Nutrition/Provisions update services, Notification as system actors)
- **Preconditions:** Authenticated; viewing a specific meal in a plan (context: `recipe_id`, `recipe_version`, `meal_slot_id`, `plan_id`, `date`); the recipe is in the user catalogue.
- **Action (sequence):**
  1. **`FEED-07`** — user submits one compound free-text: "this stir fry was too salty, I'm bored of chicken, the portions were too small, and it was really expensive." The cheap classifier returns four classifications (recipe / preference / nutrition / provisions), each with a confidence; one feedback entry, four routes.
  2. **Four routes, each in its own transaction** *(independent-transaction property — the core assertion):*
     - **`RCP-32`** — recipe "too salty" → Optimiser proposes a PENDING change (user catalogue → no auto-apply); route status `pending_approval`.
     - **`PREF-16`** — preference "bored of chicken" → taste-profile delta (immediate or batched); route status `applied`.
     - **`NUT-38`** — nutrition "portions too small" → per-meal distribution adjust; route status `applied`.
     - **`PROV-32`** — provisions "really expensive" → cost concern logged (may adjust budget/sensitivity); route status `applied`.
  3. **`FEED-16`** — one confirmation message summarises all four routes (action_taken + confidence + status each).
  4. **`FEED-25` → `NOTIF-16`** — `FeedbackProcessedEvent` fires (one event per entry); Notification produces one confirmation notification listing the touched destinations. *(cross-module: Feedback → Notification; the single-event-per-entry guarantee = no notification storm.)*
  5. **`FEED-19`** — user decides "too salty" was actually a general preference, taps "this isn't right" on the recipe route, selects "(b) I dislike salty food → preference."
  6. **Reverters fire** *(the key non-happy variant):*
     - **`FEED-20`** — recipe reverter: the PENDING change is **cancelled/deleted** (fully reversible — nothing was applied to the recipe yet).
     - **`FEED-21`** — the corrected feedback is **re-routed to preference**; a new preference signal is applied; the correction is recorded **alongside** the original recipe route (status `corrected`, ground-truth logged); the other three routes are untouched.
     - **`PREF-13`** — the corrected-to preference signal lands as an override-flagged item so the learning loop won't re-learn the wrong thing.
- **Expected outcome:** One feedback entry; five routing-log rows over time (recipe[corrected], preference, nutrition, provisions, + the corrected-to preference route); the original recipe PENDING change no longer exists; a new/updated preference signal exists; nutrition + provisions writes persist; quality monitoring has one ground-truth correction (recipe→preference); full history preserved, nothing overwritten — all self-scoped to this user.
- **Variations:** all four confidences ≥ 0.8 (no flags) vs one in 0.5–0.8 (flagged, `FEED-09`) vs one < 0.5 (that slice goes to clarification `FEED-10`→`FEED-11` while the others route); **provisions write fails** → partial success `FEED-08` (the surviving three commit, the failed route shows `failed`); the correction targets a destination whose write **cannot fully reverse** — e.g. the nutrition route already triggered a mid-week re-opt cascade → `FEED-22`/`FEED-24` ("logged but not reversed"); household member submits on their own meal (`HH-22`) instead of the primary user.
- **HLD ref:** feedback-system.md §Multi-Destination Routing, §Confidence handling, §Confirmation and Misclassification Correction, §Correction limitations, §Events published; recipe-system.md §Job sources (FEEDBACK), §Approval Model; preference-model.md §How It Gets Updated, §Guardrails (overrides flagged); nutrition-model.md §How It Gets Used (Feedback System); provision-model.md §How It Gets Used (Feedback System, cost complaints); system-overview.md §Feedback System (four destinations, split, misclassification).
- **Notes:** The integration backbone for the human-in-the-loop write path. `[HLD-GAP]` — if the user had already **accepted** the recipe pending change before correcting (a version now exists), `FEED-20`'s "undo if possible" gives no revert-to-prior path (feedback G11). `[HLD-GAP]` — two recipe-feedback routes for the same recipe could create competing pending changes; Feedback specifies no dedup and never reconciles with Recipe's per-`(recipe,dimension)` supersession `RCP-47` (feedback G17). `[HLD-GAP]` — Feedback never routes to the Planner, yet the nutrition/provisions writes here can each *downstream* trigger a planner re-opt (`PLAN-22`) — the cascade is real but the doc keeps it out of Feedback's contract; this is where `XJ-02` hands to `XJ-03`. `[HLD-GAP]` — "partially rolled back" for the preference reverter (`FEED-21`) is not precisely defined (feedback G12).

---

### XJ-03 — Week-planner critical path: generate → beam-search/rollup/choose → accept → cook → eat (nutrition actuals) → mid-week re-optimise

- **Category:** Happy (flagship end-to-end)
- **Domains traversed:** Auth → Planner → (Recipe candidate pool + Recipe Optimiser) → Planner lifecycle → Nutrition Logger → (event bus) → Planner re-opt → Notification
- **Step trace:** `AUTH-05 → PLAN-01 → PLAN-07 → PLAN-09 → PLAN-26 → PLAN-13 → PLAN-18 → NUT-14 → NUT-15 → NUT-22 → PLAN-22 → PLAN-21 → PLAN-16`
  - non-happy variant (constraint infeasible up front): `PLAN-01 → PLAN-03 → PLAN-04 → PLAN-07 → PLAN-13 → …`
  - non-happy variant (LLM down at Stage C): `… → PLAN-11 (deterministic fallback) → PLAN-13 → …`
- **Actor:** Primary user (+ Meal Planner, LLM, Recipe System/Optimiser, Nutrition Logger, event bus as system actors)
- **Preconditions:** Authenticated; catalogue above the cold-start minimum; constraints exist in all three models; no in-flight generation for this `(household, week)`.
- **Action (sequence):**
  1. **`PLAN-01`** — user requests a plan; feasibility check passes; **Stage A** hard-filters + scores (7 sub-scores) + beam-searches → top-N=5; **Stage B** rolls up each candidate. *(cross-module: Recipe catalogue reads, all three data models, Grocery price history for the cost sub-score.)*
  2. **`PLAN-07`** — **Stage C**: the LLM picks one of the 5 with reasoning (`chosen.source = 'llm'`).
  3. **`PLAN-09`** — **Phase 2** creative augmentation adds a snack/side / re-pairs sides (≤5, each re-vetted by the hard-filter).
  4. **`PLAN-26`** — **Stage D** emits a refine-directive to the **Recipe Optimiser** for a recipe-level fix ("Wednesday's main needs to drop £2"); planner waits, then re-runs Stage A on the affected slot; the directive shares the parent `trace_id`. *(cross-module: Recipe Optimiser, sync.)*
  5. **`PLAN-13`** — user **accepts** the generated plan → `active`; `PlanAcceptedEvent` → Grocery + Notification subscribe. Recipe content now immutable; only slot states transition.
  6. **`PLAN-18`** — user marks Monday's slots `cooking → cooked → eaten`; the `eaten` transition folds logged actuals into the rollup.
  7. **`NUT-14` → `NUT-15` → `NUT-22`** — the Nutrition Logger pre-fills the day from the plan; the user confirms breakfast as planned (`NUT-14`) but at lunch eats off-plan and free-text overrides (`NUT-15`, AI-parsed through the engine); daily/weekly aggregates recompute (`NUT-22`) and a daily floor is now at risk. *(cross-module: Planner output + Recipe nutrition + USDA/OFF.)*
  8. **`PLAN-22`** — a material change fires on the event bus (`NutritionLoggerEvent` ≥15% variance, *or* a `ProvisionChangedEvent` for a spoiled ingredient) → the planner (a filtered listener) emits a **re-opt suggestion** notification; it does **not** auto-replace the plan.
  9. **`PLAN-21`** — user confirms; the planner runs a **fresh re-opt** scoped to the affected day onwards, **pinning** `eaten`/`cooked`/`cooking`/past-`planned`/`skipped` slots and regenerating future `planned` slots; the new plan becomes `active`, the prior → `superseded`; the re-opt gets its own 3-cycle budget.
  10. **`PLAN-16`** — the week ends with all slots terminal → the plan **auto-completes**; `PlanCompletedEvent` emitted. The full reasoning chain (week decision → Stage-D recipe decision → re-opt) is queryable via the shared `trace_id`.
- **Expected outcome:** One `active → superseded → active → completed` lineage for the week (immutable old generations retained), with augmentations, a recipe-level refine traced to the week decision, pinned consumed slots across the re-opt, correctly recomputed nutrition actuals, and a complete decision-log + plan-lifecycle-event trail — self-scoped to this household + week.
- **Variations:** **feasibility blocks generation** → `PLAN-03` surfaces ranked resolutions, user picks one (`PLAN-04`) or declines all → quality-flagged partial plan (`PLAN-05`); **LLM unavailable at Stage C** → `PLAN-11` retries once then deterministic-fallback to the highest scorer (plan still produced, flagged); **generation timeout / huge catalogue** → `PLAN-12` degrade-gracefully; user **overrides** the Stage-C pick (`PLAN-08`); re-opt **declined** at the diff-preview (`PLAN-23`, active plan stands); divergence small enough to **not** trigger re-opt (`NUT-41` boundary); the off-plan source is a **skip** (`NUT-17`) rather than an override.
- **HLD ref:** meal-planner.md §The Loop Applied, §Constraint Feasibility Check, §Phase 2, §Stage D, §Plan Lifecycle, §Mid-Week Re-Optimisation, §Pinning rules, §Observability; nutrition-model.md §Intake Tracking, §Divergence detection, §How It Gets Used; optimisation-loop.md §The Loop/Decision Log/Termination; system-overview.md §Meal Planner, §Mid-week re-optimisation.
- **Notes:** Combines two domain flagships — `PLAN-40` (planner lineage) and `NUT-41` (intake divergence → re-opt). The re-opt is **suggested, never auto-applied** — the user is always in the loop. `[HLD-GAP]` — `chosen.source` has no value for a *deterministic-fallback-after-LLM-failure* (planner P5), so the `PLAN-11` branch can't record its provenance cleanly. `[HLD-GAP]` — the `nutrition_floor_gate` multiplies a floor-missing candidate by 0, yet the "decline all resolutions" path (`PLAN-05`) surfaces a floor-missing partial plan — unreconciled (planner P4). `[HLD-GAP]` — the ≥15% nutrition divergence threshold and "material change" filter (`PLAN-22`) are stated but the exact divergence/aggregation timing that fires the event is loosely specified (nutrition N23 alert-threshold gap).

---

### XJ-04 — Household merge → shared plan: members' models merged (hard-constraint union + soft-merge) → shared-slot plan → irreconcilable slot splits → per-member re-plan & feedback

- **Category:** Happy (flagship, spanning the Household merge → Planner hand-off) + the split is the key non-happy variant
- **Domains traversed:** Auth → Household → Preference (+ Nutrition per member) → Hard Constraint Filter → Planner → (collision) Planner constraint resolution → Feedback
- **Step trace:** `AUTH-01 → AUTH-03 → HH-01 → HH-02 → HH-17 → HH-18 → HH-11 → HH-12 → HH-19 → HH-20 → PLAN-34 → HH-21 → PLAN-03 → PLAN-04 → HH-22`
  - happy-only (union satisfiable, no split): `… → HH-19 → HH-20 → PLAN-01 (shared slot composed) → PLAN-13`
  - non-happy variant (irreconcilable union → split): `… → HH-21 → PLAN-03 → PLAN-34 → PLAN-04`
- **Actor:** Primary user (+ a second member; Hard Constraint Filter, Planner, Notification as system/downstream actors)
- **Preconditions:** Authenticated primary user (`AUTH-01`); a second registered account (`AUTH-03`); shared Provisions established.
- **Action (sequence):**
  1. **`HH-01` → `HH-02`** — primary creates a household (`HouseholdCreatedEvent`) and adds the second member (`HouseholdMemberAddedEvent`); each member's own Preference + Nutrition models are *referenced*, not copied.
  2. **`HH-17` → `HH-18`** — each member configures their own models — member A vegetarian (dietary-identity hard constraint), member B peanut-allergic; A tags "very spicy" as `individual_only`.
  3. **`HH-11` → `HH-12`** — primary marks weekday dinner a **shared slot** with both eaters, headcount 2 (`HouseholdSettingsChangedEvent`); the eater set is fixed for the slot.
  4. **`HH-19`** — at plan time the **Hard Constraint Filter** computes `checkForHousehold(eaterIds, …)` = the **union (most restrictive)**: vegetarian ∧ peanut-free, deterministically, code-never-AI. *(the one fully-specified piece of the merge.)*
  5. **`HH-20`** — the **soft-preference merge** runs over both taste profiles (favour broadly-liked, exclude A's individual-only spicy preference). *(merge weighting is `[HLD-GAP]`.)*
  6. **`PLAN-34`** (when satisfiable) — the planner composes one shared, vegetarian, peanut-free, mild dinner scaled to headcount 2; nutrition rolls up per person.
  7. **`HH-21` → `PLAN-03` → `PLAN-34` (split) → `PLAN-04`** *(the key non-happy variant):* the primary tightens member B (B adopts strict keto while A stays vegetarian) → the union becomes irreconcilable → the planner's **constraint-feasibility check** (`PLAN-03`) detects a household hard-constraint collision and proposes **splitting** the slot into per-person meals (`PLAN-34`: `shared → false`, one scheduled recipe per eater); hard constraints are **never relaxed**; the user chooses (`PLAN-04`).
  8. **`HH-22`** — each member is re-planned independently for that slot against their own models; later each gives feedback **only on their own meal**, routing to their own preference/nutrition (and any cost feedback to the shared Provisions, i.e. into `XJ-02`'s `PROV-32`).
- **Expected outcome:** A household with two members + per-member models; a shared dinner correctly filtered by the union and shaped by the merge while satisfiable; on conflict, a clean split into individual meals (no hard constraint ever relaxed); per-member re-plans and per-member feedback scoping. Assertions span household roster + per-slot sharing state + the union result + the split outcome + per-member feedback routing — self-scoped to this household.
- **Variations:** disjoint vs overlapping allergies at the union step (`HH-19`); satisfiable union (no split, ends at `PLAN-13`) vs irreconcilable (split, `PLAN-34`); add a third **child** member with auto-populated age restrictions mid-journey (`HH-03` → `PREF-11` → folds into the `HH-19` union); a member **removed** at step 7 instead of constrained → union *relaxes* rather than splits (`HH-04`); shared-meal **cost** feedback hitting shared Provisions vs **taste** feedback hitting one profile (`HH-22`).
- **HLD ref:** system-overview.md §Household Model, §Constraint resolution; preference-model.md §How It Gets Used (Household Model), §household_context; meal-planner.md §Household Integration (shared vs per-person, split), §Constraint feasibility at household level; nutrition-model.md §Bootstrapping (per-member, skippable).
- **Notes:** The union (`HH-19`) is fully specified and deterministic; everything else in the merge is soft. `[HLD-GAP]` (**central**) — the **soft-preference merge algorithm** (cross-member weighting, A-likes-X / B-dislikes-X resolution, evidence-count weighting between people, child down-weighting) is explicitly deferred to a Household Model design that does not exist (household G17 / preference GP23); tests can assert only weak invariants (no eater's hard constraint violated; an item every eater dislikes is not chosen). `[HLD-GAP]` — how one eater's feedback on a *shared* meal propagates (only their own profile, or the shared-slot merge) is undefined (household G18). `[HLD-GAP]` — whether headcount is raw bodies or the sum of per-member `portion_scale`s (children) is unspecified (household G14). `[HLD-GAP]` — whether a household member (vs only the primary) may trigger/accept the *shared* plan is unspecified (planner P17 / household actors).

---

### XJ-05 — Grocery loop: plan → shopping list → order → reconcile → inventory write → cook → deduction → staple depletion → next shopping list

- **Category:** Happy (flagship end-to-end)
- **Domains traversed:** Auth → Planner → Grocery (Tier 1/3/4) → Provisions (inventory + supplier cache + staples) → Recipe (cook deduction) → Planner (next list) → Notification
- **Step trace:** `AUTH-05 → PLAN-13 → GROC-01 → GROC-15 → GROC-16 → GROC-17 → GROC-19 → GROC-18 → PROV-03 → GROC-29 → PROV-08 → PROV-09 → PROV-17 → PROV-37/GROC-05 → NOTIF-09`
  - manual-only variant (no provider): `PLAN-13 → GROC-01 → GROC-08/GROC-10 → PROV-03 → GROC-29 → PROV-08 → …`
  - rejected-substitution variant: `… → GROC-19 (reject) → PLAN-22 (planner re-opt of the affected slot) → …`
- **Actor:** Primary user (+ Meal Planner, GroceryProvider/AI navigator, Provisions, Tier 4, Notification as system actors)
- **Preconditions:** Authenticated; Tesco provider configured (`GROC-13`); an active plan with unmet ingredient demand (`PLAN-13`); some price history exists.
- **Action (sequence):**
  1. **`GROC-01`** — the grocery module exposes the **derived shopping list** = aggregated plan demand − inventory + low/out staples, with a Tier 4 cost projection. *(cross-module: Planner demand, Provisions inventory + staples, Preference quality notes.)*
  2. **`GROC-15`** — user opts this shop into provider automation and runs a **quote**; `quote` rows feed Tier 4; order → `quoted`.
  3. **`GROC-16` → `GROC-17`** — user **places** the order (AI navigator drives the basket to checkout and **stops** — never auto-confirms; order → `placed` → `awaiting_user_confirmation`); user **confirms** in the Tesco UI (payment captured provider-side; order → `confirmed`).
  4. **`GROC-19`** — delivery arrives with **one substitution** (`pending_user_review`, never auto-accepted); user **resolves** it (accept → substitute enters Provisions; reject → original logged unmet).
  5. **`GROC-18`** — with all proposals resolved, the order **reconciles** → `reconciled`.
  6. **`PROV-03` → `GROC-29`** — delivered items are auto-added to **Provisions inventory** (`source: tesco_order`, `cost_paid`, mapping keys, category-default expiry); the **supplier cache** is refreshed; `paid` price rows (highest confidence) feed Tier 4 (`GROC-29`). *(cross-module: Grocery → Provisions; Tier 4 sharpens the next plan's cost projection.)*
  7. **`PROV-08`** — a few days later the user marks a recipe **cooked**; the cook-event deduction confirmation shows the recipe's ingredient list; on confirm, quantities deduct from inventory (from any user-corrected base).
  8. **`PROV-09` → `PROV-17`** — one ingredient's deduction would underflow → floored at zero + alert; a **staple** spice is now `out` → its status flips (`PROV-17`).
  9. **`PROV-37` / `GROC-05`** — because the staple is `out` and `is_staple`, it is **auto-added to the next shopping list** regardless of plan need; the list re-derives (`GROC-05`) and recipes needing the staple are flagged.
  10. **`NOTIF-09`** — the expiry scanner surfaces an expiry warning for a near-expiry item from the same order (and a defrost reminder for any frozen portion, `NOTIF-11`). *(cross-module: Notification.)*
- **Expected outcome:** A reconciled order; Provisions inventory reflects what actually arrived (substitute included if accepted, no negative quantities); Tier 4 carries fresh `quote` + `paid` observations; the depleted staple is queued for the next shop with affected recipes flagged; expiry/defrost notifications fired — all without any feature failing, every override auditable, self-scoped to this household.
- **Variations:** **no-substitution** delivery (skip step 4, straight to reconcile); **substitution rejected** → planner re-opt of the affected slot (`GROC-19` reject → `PLAN-22`); **partial-basket failure** at place (`GROC-22` `placed_partial` → manual completion); **AI cost-cap** at place (`GROC-28` → fall back to the Tier 1/2 manual path); **provider down** at quote (`GROC-27` `provider_unavailable`, retry scheduled, manual entry still works); **manual-only whole journey** (no provider: `GROC-01 → GROC-08`/`GROC-10` mark-bought → `PROV-03` inventory + `manual` price rows — Tiers 1/2/4 only); user **under-marks** a key item on a manual shop (`GROC-12` → planner re-opt).
- **HLD ref:** grocery.md §Tier 1, §Tier 3 (`GroceryProvider`, order lifecycle), §Substitution flow, §Tier 4 (`paid`/`quote`); provision-model.md §Inventory updates (grocery order, cook-event deduction), §How supplier data builds up, §Spice rack and staples, §Shopping List Calculation, §Guardrails (zero-floor), §Expiry tracking; system-overview.md §Provisions, §Mid-week re-optimisation.
- **Notes:** Combines two domain flagships — `GROC-36` (order lifecycle) and `PROV-43` (inventory/cache/staple spine). Largely **designed-but-unbuilt** (Tier 3 provider automation): Stage 2 will likely tag the provider legs `@pending` while the manual-only variant is buildable today. `[HLD-GAP]` — **shopping-list ownership contradiction**: grocery.md says the list is "exposed by" the grocery module while provision-model.md says it is "owned by the Planner" (grocery GG1) — `GROC-01`/`PROV-37` straddle the seam. `[HLD-GAP]` — **price-sourcing model contradiction**: provision-model.md says the supplier cache is searched/scraped from products; system-overview.md says costs are *learned from actual paid prices, not scraped* (provisions GP20) — affects what `GROC-29`/`PROV-33` actually write. `[HLD-GAP]` — the "if material" threshold that decides whether under-marking (`GROC-12`) triggers a planner re-opt is undefined (grocery GG5).

---

### XJ-06 — Onboarding cold start: register → seed preference/nutrition/household → first plan from an empty catalogue

- **Category:** Happy (flagship end-to-end)
- **Domains traversed:** Auth → Preference → Nutrition → Provisions → Household → Recipe (discovery/generation pre-step) → Planner (cold start)
- **Step trace:** `AUTH-01 → AUTH-05 → PREF-01 → PREF-02 → PREF-04 → NUT-01 → PROV-05 → PROV-26 → HH-01 → PLAN-06 → RCP-10/RCP-09 → PLAN-07 → PLAN-13`
  - non-happy variant (completely empty catalogue + infeasible seed): `… → PLAN-06 → PLAN-03 → PLAN-04 → PLAN-07 → PLAN-13`
  - minimal-seed variant (skip nutrition + skip staples + skip quiz): `AUTH-01 → PREF-01 → NUT-07 → PROV-26 (skip) → HH-01 → PLAN-06 → …`
- **Actor:** Primary user (+ Auth subsystem, Preference/Nutrition/Provisions models, Recipe System discovery/generation, Meal Planner as system actors)
- **Preconditions:** No session; username available; brand-new install (empty catalogue, no models configured).
- **Action (sequence):**
  1. **`AUTH-01` → `AUTH-05`** — register a new account (username + hashed password; canonical `user_id` minted; linked to fresh Preference + Nutrition models + a household membership); log in. *(cross-module: model provisioning implied — `[HLD-GAP]` eager vs lazy.)*
  2. **`PREF-01`** — collect **hard constraints** at onboarding (allergies + dietary identity) — safety-critical, available to the deterministic filter immediately.
  3. **`PREF-02` → `PREF-04`** — take the quick preference **quiz** (10–15 swipes → low-confidence seeded taste profile) and configure Day-1 **lifestyle** essentials (meal structure, weeknight time tolerance).
  4. **`NUT-01`** — accept onboarding calorie + macro targets (or skip → `NUT-07`); model → CONFIGURED (or SKIPPED — planner still produces valid plans).
  5. **`PROV-05` → `PROV-26`** — staples quick-start (tick cupboard basics → all `stocked`) and set a budget (or skip → budget UNSET, still works).
  6. **`HH-01`** — create the household (sole member + primary; shared Provisions context established).
  7. **`PLAN-06`** — user requests the first plan; the catalogue is **below the cold-start minimum** (heuristic ≥3× slot count) → the planner runs the **discovery + generation pre-step**. *(cross-module: Recipe System.)*
  8. **`RCP-10` / `RCP-09`** — Recipe System **discovery** (constraint-filtered web search → URL import → `web_discovered`) and **AI generation** against constraint briefs fill the system catalogue (bounded ≤50/run); each new recipe is USDA-mapped (`NUT-26`) and hard-constraint-filtered.
  9. **`PLAN-07` → `PLAN-13`** — Stage A–C run (cost regressed toward neutral at cold start; Phase 2 does heavier lifting); the LLM picks a candidate; a `generated`, `cold_start: true` plan is presented; user **accepts** → `active`.
- **Expected outcome:** A working account with a stable `user_id`; safety-critical hard constraints set before any food output; a low-confidence taste profile + (optional) configured nutrition + staples/budget; a household with one primary; the system catalogue bootstrapped up to 50 recipes; a first `cold_start`-flagged plan accepted — all self-scoped to this fresh user/household.
- **Variations:** **completely empty catalogue + over-restrictive seed** → `PLAN-06` pre-step still under-fills → `PLAN-03` feasibility surfaces resolutions → `PLAN-04` (key non-happy variant); **minimal seed** (skip the quiz `PREF-02`, skip nutrition `NUT-07`, skip staples + budget) — planner runs degraded-but-valid; just-below vs just-above the cold-start boundary (`PLAN-06` boundary: exactly 3× slot count → not cold-start); the cold-start path no longer triggers after ~2 weeks; an **additional household member** onboarding into an existing household (`AUTH-03` → `HH-02`) rather than a first user (this is where `XJ-06` feeds `XJ-04`).
- **HLD ref:** system-overview.md §User Accounts, §Household, §Cold start; preference-model.md §Bootstrapping; nutrition-model.md §Bootstrapping; provision-model.md §Bootstrapping; meal-planner.md §Cold start, §Constraint Feasibility Check; recipe-system.md §Import Pipeline (discovery, generation); auth.md §AUTH-22.
- **Notes:** The "fresh user with a random handle" spine that the README's self-contained-data rule mandates and that every other `XJ-` (and domain pathway) folds into its "Authenticated" precondition. `[HLD-GAP]` — whether registration **eagerly provisions** the linked Preference/Nutrition models + household or creates them lazily on first use is unspecified (auth AG3); the step-1 → step-2 hand-off depends on it. `[HLD-GAP]` — the household **onboarding model for an additional member** (invite vs self-signup vs primary-creates) is entirely unspecified (auth AG4 / household G2/G5), so the "additional member" variant has no concrete flow. `[HLD-GAP]` — **dietary identity appears in both** the Tier-1 hard-constraint store (`PREF-01`) and the Day-1 lifestyle/bootstrap step (`PREF-04`); which is canonical at onboarding is ambiguous (preference GP4). `[HLD-GAP]` — whether onboarding *blocks* progression until hard constraints are confirmed (vs allows skipping) is unspecified (preference GP1) — affects whether step 2 is a hard gate before step 7.

---

## Cross-journey coordination `[HLD-GAP]` findings (consolidated)

> These are gaps that surface **specifically at the seams between domains** — i.e. they were not (or only partially) visible inside a single domain file because they concern a *hand-off*. Per-domain gaps already in each file's appendix are not duplicated here; only the cross-doc coordination issues are listed. None resolved — all feed `hld-gaps.md`.

| # | Cross-domain coordination gap | XJ | Related domain gap |
|---|---|---|---|
| X1 | Needs-review confidence threshold mismatch: recipe-system uses 0.7, nutrition-model names none — the Recipe↔Nutrition map step has no single agreed boundary. | XJ-01 | recipe (USDA conf.) / nutrition N14 |
| X2 | A character-breaking *manual* recipe edit (XJ-01 step 4) is not gated by the version-vs-branch classifier — unclear if it may stay a version. | XJ-01 | recipe G9 |
| X3 | Correcting a recipe feedback route *after* the pending change was already accepted (a version exists) has no revert-to-prior path in the reverter. | XJ-02 | feedback G11 |
| X4 | Feedback's nutrition/provisions writes can *downstream* trigger a planner re-opt, but Feedback's contract explicitly never routes to the Planner — the cascade crosses a boundary the docs keep separate (XJ-02 → XJ-03 seam). | XJ-02, XJ-03 | feedback (boundaries) / planner PLAN-22 |
| X5 | Two same-recipe feedback routes can create competing pending changes; Feedback specifies no dedup and never reconciles with Recipe's per-`(recipe,dimension)` supersession (`RCP-47`). | XJ-02 | feedback G17 / recipe RCP-47 |
| X6 | `chosen.source` enum has no value for a deterministic fallback after LLM failure — the `PLAN-11` branch inside the critical path can't record provenance. | XJ-03 | planner P5 |
| X7 | `nutrition_floor_gate` (score ×0) vs the "decline all resolutions → partial plan" path that still surfaces a floor-missing plan — unreconciled across scoring and feasibility. | XJ-03 | planner P4 |
| X8 | The **soft-preference merge algorithm** for shared slots (cross-member weighting / conflict resolution / child down-weighting) is deferred to a non-existent Household Model design — the central gap of the whole household→planner hand-off. | XJ-04 | household G17 / preference GP23 |
| X9 | How one eater's feedback on a *shared* meal propagates (own profile only vs the shared-slot merge / others' models) is undefined. | XJ-04 | household G18 |
| X10 | Headcount semantics for portion scaling — raw bodies vs sum of per-member `portion_scale`s (children) — unspecified, affecting the shared-slot cook quantity. | XJ-04 | household G14 |
| X11 | Whether a household member (vs only the primary user) may trigger/accept the *shared* plan beyond own-meal feedback is unspecified. | XJ-04 | planner P17 / household actors |
| X12 | **Shopping-list ownership contradiction** across grocery.md ("exposed by grocery") and provision-model.md ("owned by Planner") — the list-derivation step straddles two docs that disagree on ownership. | XJ-05 | grocery GG1 |
| X13 | **Price-sourcing model contradiction**: provision-model.md (cache searched/scraped from products) vs system-overview.md (costs learned from actual paid prices, not scraped) — the reconcile→Tier-4 write means different things in each doc. | XJ-05 | provisions GP20 |
| X14 | The "if material" threshold that decides whether under-marking / a missed item triggers a planner re-opt is undefined (the Grocery→Planner seam). | XJ-05 | grocery GG5 |
| X15 | Whether registration **eagerly provisions** the linked Preference/Nutrition/Household models or creates them lazily — the Auth→models hand-off that every onboarding journey depends on. | XJ-06 | auth AG3 |
| X16 | The household **onboarding model for an additional member** (invite vs self-signup vs primary-creates) is entirely unspecified — no concrete flow for the Auth→Household join (the XJ-06 → XJ-04 seam). | XJ-06, XJ-04 | auth AG4 / household G2, G5 |
| X17 | Dietary identity stored in **both** Preference Tier-1 (hard constraint) and the Day-1 lifestyle/bootstrap step — which store is canonical at onboarding is ambiguous, so two domains may diverge. | XJ-06 | preference GP4 |
| X18 | A nutrition correction that already triggered a mid-week re-opt cascade "may not fully reverse" — the Feedback→Nutrition→Planner reverter chain has no clean undo and no user-facing contract for the partial reversal. | XJ-02, XJ-03 | feedback G13 |
