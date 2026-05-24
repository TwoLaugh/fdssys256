# Feedback Domain — User-Pathway Catalogue

> Code-agnostic behavioural catalogue derived purely from the HLD design docs. Double duty: (a) source for E2E test scenarios; (b) behavioural spec for the frontend. No endpoints, HTTP verbs, class names, or DB tables — pure user/behaviour language. Where the HLD is silent on something a user would obviously need, it is flagged `[HLD-GAP]` rather than invented.

---

## 1. Domain Summary

The Feedback System is the system's single conversational front door for "what the user *thought*" about anything. It is deliberately a **classifier and router**, not a doer: it takes free-text from anywhere in the UI, asks a cheap AI to classify the text into one or more of **four destinations** (Recipe-via-Optimiser, Preference, Nutrition, Provisions), routes each classified slice to that destination's own update service, then **confirms** back to the user what it did and where — which is also the misclassification-detection mechanism. It owns none of the update logic and none of the data models; it only delivers signals and records the routing. It deliberately does **not** route to the Meal Planner (plan re-runs are a downstream consequence of data-model changes), and it never touches hard constraints (allergies / dietary identity go through the user's manual hard-constraint edit flow). It is distinct from the Nutrition Logger: the Logger records *what you ate* (facts); Feedback records *what you thought* (opinions). In the three-loop architecture it is the human-in-the-loop write path that feeds all three constraint loops plus the recipe-scale loop, via AI interpretation — the only such AI-mediated write path in the system.

## 2. Actors

| Actor | Role in this domain (per HLD) |
|---|---|
| **Primary user** | Submits free-text feedback from any UI screen; answers low-confidence clarification prompts; reviews the confirmation; corrects a misrouted route; views recent feedback and classification metrics. |
| **Household member** | Can give feedback on their **own** meals (own account, own preference/nutrition models). Beyond own-meal feedback, scope is unspecified `[HLD-GAP]`. |
| **Feedback Classifier (AI, system actor)** | Cheap-tier (Haiku) single AI call with tool use; emits structured `classifications[]` with destination + confidence + extracted_feedback (+ recipe_id / affects_plan); re-runs with added context after a clarification. Does NOT reason about the fix, does NOT update any model, does NOT load full recipe/plan data. |
| **Recipe Optimiser (destination, downstream)** | Receives recipe feedback via `handleRecipeFeedback(recipeId, feedback)`; does culinary/nutritional reasoning; returns an `AdaptationResult` (typically a pending change for user recipes). |
| **Preference Update Service (destination, downstream)** | Receives preference feedback; applies an immediate taste-profile delta for strong signals or batches weak ones for the next scheduled delta update. |
| **Nutrition Update Service (destination, downstream)** | Receives nutrition feedback; adjusts per-meal calorie distribution / protein floors / targets, or logs energy-mood observations to the food/mood journal. |
| **Provision Update Service (destination, downstream)** | Receives provisions feedback; logs cost concerns, updates supplier cache, equipment list, waste tracking, shelf-life estimates. |
| **Notification System (downstream)** | Listens for `FeedbackProcessedEvent(feedbackId, destinations, userId)` to confirm to the user which destinations were updated. |
| **Quality monitoring (system/dev actor)** | Consumes the routing log + corrections (ground-truth labels) to track correction rate, confidence distribution, destination distribution, low-confidence clarification rate. |

## 3. Action Space (frontend-spec backbone)

Flat, exhaustive list of every distinct user (or user-facing system) action the HLD permits. Each: verb-phrase + one-line description + HLD ref. Downstream pathways draw from this.

### Submit
1. **Submit free-text feedback** — type/speak natural-language feedback from any screen; UI attaches context metadata (screen, recipe_id, recipe_version, meal_slot_id, plan_id, date). §Entry Points and Context; §Service Interface (submitFeedback).
2. **Submit feedback with screen context** — feedback carries implicit routing signal from the originating screen (recipe detail, plan meal, plan general, grocery, nutrition dashboard, settings). §Entry Points and Context.
3. **Submit context-less / general feedback** — from a home/settings screen with no implicit signal; classifier "works harder." §Entry Points; system-overview §Entry points.

### Clarify (low-confidence path)
4. **Answer a clarification prompt** — when overall confidence < 0.5, pick one of the classifier's best-guess options (a/b/c) or type a free-text clarification; classifier re-runs with the added context. §Confidence handling; §AI Task (re-run with additional context).

### Review & confirm
5. **View the confirmation message** — see, per route, the destination, action_taken, confidence, and status (applied / pending_approval / failed / clarification_needed). §Confirmation message; §Service Interface (RouteResult).
6. **View recent feedback** — list recent feedback entries with their routing info. §Service Interface (getRecentFeedback); §API (GET /feedback).
7. **View classification metrics** — correction rate, confidence distribution, destination distribution, low-confidence clarification rate over a date range. §Quality Monitoring; §Service Interface (getClassificationMetrics).

### Correct (misclassification)
8. **Tap "this isn't right" on a route** — open the correction UI for one specific route; see the original classification + alternative destinations. §Correction flow.
9. **Re-route to a corrected destination** — select the correct destination; system undoes the original write (if possible) and routes to the corrected one; correction logged as ground truth. §Correction flow; §Service Interface (correctRoute).

### Implicit / system-side outcomes (user-facing via results)
10. **Route to a single destination** — classifier returns one destination; routed to that update service. §Four Destinations.
11. **Route to multiple destinations (split)** — classifier returns several destinations; each processed independently in its own transaction, partial success allowed. §Multi-Destination Routing.
12. **Auto-route at high confidence (≥ 0.8)** — routed silently, included in confirmation. §Confidence handling.
13. **Auto-route with a flag (0.5–0.8)** — routed but flagged "I think you meant X — correct me if wrong." §Confidence handling.
14. **Record a NO-destination / non-actionable outcome** — feedback the classifier can't or shouldn't route (see GAPs) is still stored. §Feedback Storage (stored regardless of routing).

> Boundary note (NOT feedback actions, listed to fix the contract edge): logging *what you ate* (intake correction) is the **Nutrition Logger**, not Feedback (§Interaction with the Logger). Editing **hard constraints** (allergy/dietary identity) is the manual hard-constraint flow in Preference, never Feedback (§Boundaries). Manual direct edits to any data model bypass Feedback entirely (system-overview §Manual direct edits).

## 4. State Models

### 4.1 Feedback entry lifecycle
```
SUBMITTED (free-text + context stored; one feedback_entry created)
   │  classifier runs (cheap AI, tool-use structured output)
   ├─ overall confidence < 0.5  → CLARIFICATION_NEEDED ──(user answers)──► re-classified → ROUTED
   └─ at least one destination ≥ 0.5 → ROUTED (one routing_log row per destination)
   ▼
ROUTED  (every entry is stored regardless of routing outcome — never discarded)
```
A feedback entry has **N routes** (N ≥ 0). The entry itself is never deleted; its routes carry their own status.

### 4.2 Route (per-destination) lifecycle
```
(classified)
   ├─ destination write succeeds, no approval   → APPLIED            (preference / nutrition / provisions)
   ├─ destination produces a proposal           → PENDING_APPROVAL   (recipe → Optimiser AdaptationResult = pending change)
   ├─ destination write fails                    → FAILED             (logged; other routes of the same entry unaffected)
   ├─ awaiting user disambiguation               → CLARIFICATION_NEEDED
   └─ user corrects the route                    → CORRECTED          (original write undone if possible; new route created to corrected destination)
```
Statuses per §Service Interface RouteResult = {applied, pending_approval, failed, clarification_needed} and §Feedback Storage status = {applied, pending_approval, failed, corrected}.

**Illegal / disallowed transitions (→ error pathways):**
- Feedback may NEVER write a **hard constraint** (allergy / dietary identity) — must reject/redirect to the manual hard-constraint flow.
- Feedback may NEVER route to the **Meal Planner** directly (planner re-runs are downstream of data-model changes only).
- Correcting an already-CORRECTED route, or correcting a route to the destination it already targets (no-op / illegal) `[HLD-GAP]`.
- Re-running classification on an entry that was never CLARIFICATION_NEEDED (clarify path only opens below 0.5).
- A correction must not be silently dropped: corrections are recorded **alongside** the original route, never as a replacement (full history preserved).

### 4.3 Confidence gate (decision model, not persisted state)
```
per classification:  confidence ≥ 0.8        → auto-route, plain confirm
                     0.5 ≤ confidence < 0.8  → auto-route, flagged confirm ("I think you meant X")
                     confidence < 0.5         → clarification (service call back to user, NOT AI multi-turn)
```
`[HLD-GAP]` — whether the < 0.5 gate is per-classification or over the whole response (the doc says "low-confidence classifications ask… ", but multi-destination responses can mix a 0.9 and a 0.3); the boundary values (is exactly 0.5 / exactly 0.8 the higher band?) are also unstated.

### 4.4 Correction reversibility model (per destination)
```
recipe (pending change)   → fully reversible: cancel/delete the pending change
preference (last delta)   → partially reversible: the delta is logged, can be partially rolled back
provisions (logged/updated)→ reversible: cost-feedback, equipment, waste, supplier-cache writes can be undone
nutrition (target/dist)   → may be IRREVERSIBLE if it triggered downstream effects (mid-week re-opt cascade);
                            correction logged but cascading effects may not fully reverse
```

---

## 5. Pathways

> Categories: **Happy** (default success), **Alternate** (valid non-default), **Error** (validation/not-found/unauthorized/conflict/illegal-transition), **Edge** (empty/huge/boundary/duplicate/concurrent). Cross-module touchpoints (Optimiser/Recipe, Preference, Nutrition, Provisions, Notification, AI classifier) are noted; the four destinations' internal update logic is detailed in their own domain files + the cross-journey file. Self-contained data + self-scoped assertions per `e2e/README.md` §4.

### Submit & single-destination routing

#### FEED-01 — Submit recipe-quality feedback (single destination, high confidence)
- **Category:** Happy
- **Actor:** Primary user
- **Preconditions:** Authenticated; a recipe exists; user is on the recipe detail or plan-meal screen (context carries recipe_id/version).
- **Action:** Submit free text about the recipe itself, e.g. "this needed more garlic" / "the sauce was too thick" / "too many steps for a weeknight," with screen context attached.
- **Expected outcome:** Feedback entry stored; classifier returns destination=recipe, confidence ≥ 0.8; routed to `OptimiserService.handleRecipeFeedback(recipeId, extracted)`; Optimiser returns an AdaptationResult; route status = `pending_approval` (user catalogue → pending change); confirmation surfaced ("Proposed recipe change for X…"); one routing-log row written; `FeedbackProcessedEvent` published.
- **Variations:** "more garlic" (ingredient); "too thick" (method/texture); "too many steps" (effort); recipe is a system-catalogue recipe (Optimiser applies directly → status `applied`, no approval) `[HLD-GAP]` — confirmation status for a system-catalogue recipe route isn't enumerated (only `pending_approval` shown for recipe).
- **HLD ref:** feedback-system.md §Destination 1 (Recipe via Optimiser); §Confirmation message; §Events published; system-overview.md §Trigger 2.
- **Notes:** Cross-module: Recipe Optimiser (sync), Notification. Assert via the routing-log row + the resulting pending change in Recipe — not the AI's wording.

#### FEED-02 — Submit preference feedback (single destination)
- **Category:** Happy
- **Actor:** Primary user
- **Preconditions:** Authenticated.
- **Action:** Submit a like/dislike/style statement, e.g. "I don't like coriander" / "I prefer lighter meals in summer" / "this cuisine isn't for me."
- **Expected outcome:** Classified destination=preference; routed to `PreferenceUpdateService.applyFeedback(userId, extracted)`; strong explicit signal ("I don't like X") may apply an immediate taste-profile delta, weaker signal batched for the next scheduled delta; route status = `applied`; confirmation surfaced.
- **Variations:** strong signal (immediate delta) vs weak signal ("this week was a bit boring" → batched); the recipe-vs-preference boundary case "I generally don't like very salty food" (→ preference, contrast FEED-01's "this stir fry was too salty").
- **HLD ref:** feedback-system.md §Destination 2 (Preference); §By the Preference Model.
- **Notes:** Cross-module: Preference. Immediate-vs-batched is a Preference-owned decision; assert the route landed at preference, not the delta mechanics.

#### FEED-03 — Submit nutrition feedback (single destination)
- **Category:** Happy
- **Actor:** Primary user
- **Preconditions:** Authenticated; ideally on the nutrition dashboard (context signal).
- **Action:** Submit a portion/macro/energy statement, e.g. "the portions have been too small this week" / "I'm always hungry after lunch" / "I need more protein" / "I felt sluggish after dinner."
- **Expected outcome:** Classified destination=nutrition; routed to `NutritionUpdateService.applyFeedback`; portion complaints adjust per-meal distribution, protein complaints adjust protein floors, energy/mood observations log to the food/mood journal; route status = `applied`.
- **Variations:** portion complaint (distribution); explicit target change ("increase my calorie target" → targets directly); energy/mood note (food/mood journal); "the portion was too small" ambiguous between nutrition (calorie target) and preference (portion style) — see FEED-13.
- **HLD ref:** feedback-system.md §Destination 3 (Nutrition); §By the Nutrition Model.
- **Notes:** Cross-module: Nutrition. A nutrition route may itself trigger mid-week re-opt downstream (see FEED-22 correction caveat).

#### FEED-04 — Submit provisions feedback (single destination)
- **Category:** Happy
- **Actor:** Primary user
- **Preconditions:** Authenticated; ideally on the grocery/shopping screen.
- **Action:** Submit a cost/availability/equipment/waste/shelf-life statement, e.g. "this week was too expensive" / "I couldn't find this at Tesco" / "I don't have a food processor" / "I keep throwing away lettuce" / "the chicken had a really short shelf life."
- **Expected outcome:** Classified destination=provisions; routed to `ProvisionUpdateService.applyFeedback`; cost → logged (may prompt budget review), availability → supplier cache, equipment → equipment list, waste → waste log, shelf-life → expiry estimates; route status = `applied`.
- **Variations:** cost; availability; equipment; waste; shelf-life; disruption "the chicken's gone off" (removes chicken from Provisions) — these provisions changes then **trigger the planner to re-optimise remaining days as a downstream effect** (Feedback itself still does NOT call the planner).
- **HLD ref:** feedback-system.md §Destination 4 (Provisions); §By the Provision Model; system-overview.md §Four destinations (Provisions / disruptions).
- **Notes:** Cross-module: Provisions → (downstream) Planner re-opt. Assert the provisions write + that Feedback did not directly invoke the planner.

#### FEED-05 — Context-less / general feedback, classifier resolves it
- **Category:** Alternate
- **Actor:** Primary user
- **Preconditions:** Authenticated; submitted from a home/settings screen — no implicit screen signal.
- **Action:** Submit feedback with empty/neutral context.
- **Expected outcome:** Classifier "works harder" using only the text; if it reaches ≥ 0.5 for some destination, routes normally; otherwise falls into the clarification path (FEED-10).
- **Variations:** clearly-worded text still classifiable without context; ambiguous text → clarification; context provided but contradicting the text (e.g. recipe-screen text that's actually about cost — context is a signal, not a constraint).
- **HLD ref:** feedback-system.md §Entry Points and Context (context is input not hard rule); §Classification; system-overview.md §Entry points.
- **Notes:** The "context as signal not constraint" rule is the key assertion (recipe-page feedback CAN route to provisions).

#### FEED-06 — Screen context overridden by clear text intent
- **Category:** Alternate
- **Actor:** Primary user
- **Preconditions:** On the recipe detail page (context implies recipe/preference).
- **Action:** Submit text whose content points elsewhere, e.g. on the recipe page typing "I can't afford these ingredients."
- **Expected outcome:** Classifier routes to provisions despite the recipe-page context — context did not hard-constrain the routing.
- **Variations:** recipe page → provisions; nutrition dashboard → preference; grocery screen → recipe; mismatch where context and text are equally weighted `[HLD-GAP]` (no documented tie-break between a strong context signal and a contradicting text signal).
- **HLD ref:** feedback-system.md §Entry Points and Context.
- **Notes:** Guards against context becoming a routing constraint.

### Multi-destination splitting

#### FEED-07 — Split feedback across two destinations
- **Category:** Happy
- **Actor:** Primary user
- **Preconditions:** Authenticated; meal in context.
- **Action:** Submit compound feedback, e.g. "this stir fry was too salty and really expensive."
- **Expected outcome:** Classifier returns two classifications: recipe (extracted "too salty", confidence ~0.92) and provisions (extracted "too expensive", ~0.85); each routed **independently in its own transaction**; recipe → pending_approval, provisions → applied; both appear in one confirmation message with two routes; one feedback entry, two routing-log rows.
- **Variations:** recipe+provisions; preference+nutrition ("bored of chicken and the portions are too small"); three-way split; same destination appearing twice for two different slices `[HLD-GAP]` (whether two classifications can target the same destination is not stated).
- **HLD ref:** feedback-system.md §Multi-Destination Routing; §How multi-destination works.
- **Notes:** The independent-transaction property is the core assertion; sets up FEED-08.

#### FEED-08 — Partial success in a multi-destination split
- **Category:** Error / Edge
- **Actor:** Primary user
- **Preconditions:** Compound feedback routing to ≥ 2 destinations; one destination's update service fails (e.g. Provisions write errors).
- **Action:** Submit the compound feedback; one destination write fails.
- **Expected outcome:** The succeeding route(s) still commit (own transaction); the failing route gets status `failed`; partial success is **acceptable, logged, and surfaced** to the user in the confirmation (mixed applied/failed routes).
- **Variations:** recipe applies + provisions fails; both of two fail; first of three fails, other two succeed; whether the user can retry just the failed route `[HLD-GAP]` (no documented retry affordance for a failed route).
- **HLD ref:** feedback-system.md §Multi-Destination Routing (partial success acceptable/logged/surfaced).
- **Notes:** Assert the surviving write committed AND the failed route is visibly `failed`. No retry path is specified — flagged.

### Confidence gating & clarification

#### FEED-09 — Mid-confidence route is flagged for confirmation (0.5–0.8)
- **Category:** Alternate
- **Actor:** Feedback Classifier → Primary user
- **Preconditions:** Feedback classifies to a destination with confidence in [0.5, 0.8).
- **Action:** Submit borderline feedback.
- **Expected outcome:** Routed automatically AND flagged in the confirmation: "I think you meant X — correct me if wrong." Status reflects the destination's result (applied / pending_approval); the flag invites a correction (FEED-15).
- **Variations:** at 0.5 exactly (boundary — which band owns the endpoints is unstated, see G-confidence); at just under 0.8; flagged route the user accepts (no correction) vs corrects (FEED-15).
- **HLD ref:** feedback-system.md §Confidence handling (0.5–0.8 row).
- **Notes:** Boundary endpoints are a GAP. Assert the route applied AND carries the "flagged" affordance.

#### FEED-10 — Low-confidence feedback triggers a clarification query (< 0.5)
- **Category:** Alternate
- **Actor:** Feedback Classifier → Primary user
- **Preconditions:** Feedback classifies below 0.5 overall.
- **Action:** Submit ambiguous feedback the classifier can't confidently place.
- **Expected outcome:** NO destination write yet; a **service call back to the user** (not an AI multi-turn convo) presents the classifier's best-guess options: "(a) the recipe needs changing, (b) your preferences need updating, (c) something about the cost?"; route status = `clarification_needed`; entry is still stored.
- **Variations:** three options offered; user picks one (FEED-11); user types a free-text clarification instead (FEED-11 variant); options are derived from the best-guess classifications, not a fixed menu.
- **HLD ref:** feedback-system.md §Confidence handling (< 0.5); §AI Task (re-run with additional context).
- **Notes:** Assert no destination write occurred and status `clarification_needed`. The "service call not AI multi-turn" framing is the key v1 contract.

#### FEED-11 — User answers the clarification; reclassification routes successfully
- **Category:** Happy (continuation of FEED-10)
- **Actor:** Primary user → Feedback Classifier
- **Preconditions:** A feedback entry in CLARIFICATION_NEEDED.
- **Action:** User picks one of the offered options (or types a clarification); the classifier **runs again with the additional context**.
- **Expected outcome:** Re-classification now reaches ≥ 0.5 for the chosen/clarified destination; routed; status transitions from `clarification_needed` → `applied`/`pending_approval`; confirmation surfaced.
- **Variations:** picked option resolves cleanly; free-text clarification resolves; clarification STILL classifies < 0.5 on the re-run (FEED-12).
- **HLD ref:** feedback-system.md §Confidence handling; §AI Task.
- **Notes:** Same feedback entry, re-run classification — assert the same feedback_id advances to a routed state.

#### FEED-12 — Clarification re-run is STILL low-confidence (stuck classification)
- **Category:** Error / Edge
- **Actor:** Feedback Classifier
- **Preconditions:** A clarified feedback entry; the re-run again yields < 0.5 (or the user's clarification is itself ambiguous).
- **Action:** Re-classify with the added context; still no confident destination.
- **Expected outcome:** `[HLD-GAP]` — the HLD describes ONE clarification round ("the classifier runs again") but does not define what happens if the re-run is still < 0.5: loop again? give up and store unrouted? force the user to pick? Behaviour is unspecified.
- **Variations:** still < 0.5 after one clarification; user repeatedly gives ambiguous clarifications (potential infinite-loop — no documented cap, contrast with retry caps elsewhere in the system); user abandons the clarification (entry left in `clarification_needed` indefinitely?).
- **HLD ref:** feedback-system.md §Confidence handling (single re-run described, no terminal failure path).
- **Notes:** Mirrors the "retry of stuck classifications" concern — but the HLD provides no terminal/abort rule. Pure GAP-driven error pathway.

### NO-route / non-actionable & boundary content

#### FEED-13 — Feedback the classifier cannot route to any of the four destinations
- **Category:** Edge / Error
- **Actor:** Feedback Classifier
- **Preconditions:** Free text that is on-topic but maps to no destination (e.g. "the app is slow," "thanks, this is great!", "why did you pick this recipe?").
- **Action:** Submit such feedback.
- **Expected outcome:** Feedback entry IS stored (every entry stored regardless of routing); zero routes created. `[HLD-GAP]` — the HLD never describes a "no valid destination / general non-actionable" outcome: does it fall into the < 0.5 clarification path, get stored with zero routes silently, or get a "couldn't act on this" confirmation? Unspecified.
- **Variations:** praise (no action needed); app/UX complaint (no destination owns it); a question (not feedback); empty-after-trim text (validation, FEED-14).
- **HLD ref:** feedback-system.md §Feedback Storage (stored regardless); §Four Destinations (only four exist).
- **Notes:** The "stored but unrouted" terminal state is implied by storage rules but never given a status value — flagged.

#### FEED-14 — Submit empty / malformed / oversized feedback
- **Category:** Error
- **Actor:** Primary user
- **Preconditions:** Authenticated.
- **Action:** Submit empty text, whitespace-only text, or text exceeding limits.
- **Expected outcome:** Validation should reject before classification (no AI call, no entry). `[HLD-GAP]` — the HLD specifies a per-task token cap of 5,000 for the classifier but never states user-facing input validation (min length, max length, empty rejection, what happens to text exceeding the token cap — truncate? reject?).
- **Variations:** empty string; whitespace only; text longer than the 5,000-token classifier cap (truncate vs reject is unspecified); non-text payload; missing context object entirely (is context required or optional? — system shows it populated everywhere but never says it's mandatory).
- **HLD ref:** feedback-system.md §AI Task (5,000-token cap); §Service Interface (FeedbackRequest).
- **Notes:** Input-validation rules are entirely a GAP; assert "rejected, no entry, no classifier call" once a rule is chosen.

#### FEED-15 — Feedback that names a hard constraint (allergy / dietary identity)
- **Category:** Error
- **Actor:** Primary user
- **Preconditions:** Authenticated.
- **Action:** Submit text like "I'm now allergic to nuts" or "I've gone vegan."
- **Expected outcome:** Feedback System must NOT write the hard constraint via AI interpretation — it must redirect the user to the **manual hard-constraint edit flow** (Preference, user-only, deterministic). The feedback never modifies the allergy/dietary-identity tables.
- **Variations:** new allergy; dietary-identity change; removing an allergy; a compound message mixing a hard-constraint claim with routable feedback ("I'm allergic to nuts now and this was too salty") — `[HLD-GAP]` how the split is handled (route the salty part, redirect the allergy part?) is unspecified.
- **HLD ref:** feedback-system.md §Boundaries (Hard constraint changes → Preference user-only, never via feedback); system-overview.md §Data Model 1 (hard-locked, never AI/feedback).
- **Notes:** Critical safety boundary — assert NO hard-constraint write occurs from feedback. Compound-message handling is a GAP.

### Confirmation & viewing

#### FEED-16 — View the confirmation message after routing
- **Category:** Happy
- **Actor:** Primary user
- **Preconditions:** A feedback submission just completed (≥ 1 route).
- **Action:** Read the confirmation.
- **Expected outcome:** Per-route summary: destination, action_taken (human-readable), confidence, status; an overall human sentence ("Updated: Proposed recipe change for X. Noted cost concern."); `correction_available: true` when a route can be corrected.
- **Variations:** single route; multi-route; a route flagged (0.5–0.8) reads "I think you meant…"; a `failed` route shown alongside applied ones; a `clarification_needed` confirmation (asks instead of confirms).
- **HLD ref:** feedback-system.md §Confirmation message; §Service Interface (RouteResult).
- **Notes:** Self-scoped read of this user's just-created feedback entry.

#### FEED-17 — View recent feedback history
- **Category:** Happy
- **Actor:** Primary user
- **Preconditions:** User has submitted feedback before.
- **Action:** Open the recent-feedback list (default limit ~20).
- **Expected outcome:** This user's recent feedback entries with their routing info (destinations, statuses, any corrections recorded alongside).
- **Variations:** empty (no feedback yet); exactly at the limit; more than the limit (only newest N); entries that include corrected routes (full history preserved).
- **HLD ref:** feedback-system.md §Service Interface (getRecentFeedback); §API (GET /feedback?limit=20); §Feedback Storage.
- **Notes:** Self-scoped — assert THIS user's entries only, never a global count.

#### FEED-18 — View classification metrics dashboard
- **Category:** Happy
- **Actor:** Primary user / quality monitoring
- **Preconditions:** Some feedback + corrections exist.
- **Action:** Open classification metrics for a date range.
- **Expected outcome:** Correction rate (target < 10%), confidence distribution, destination distribution, low-confidence clarification rate over the range.
- **Variations:** no data (empty metrics); rich history; range with no feedback in it; `[HLD-GAP]` — whether these metrics are per-user (the interface takes a userId) or system-wide (quality monitoring framing reads global, e.g. "if 90% of feedback goes to one destination") is contradictory.
- **HLD ref:** feedback-system.md §Quality Monitoring; §Service Interface (getClassificationMetrics takes userId + DateRange).
- **Notes:** Per-user vs global scope is a contradiction worth flagging. Assert self-scoped if per-user.

### Misclassification correction & reverters

#### FEED-19 — Correct a misrouted route (simple re-route)
- **Category:** Happy
- **Actor:** Primary user
- **Preconditions:** A completed route whose `correction_available` is true; user believes it's wrong.
- **Action:** Tap "this isn't right"; the UI shows the original classification + alternatives ("I routed 'too salty' to the recipe. Did you mean: (a) recipe is too salty, (b) you dislike salty food → preference, (c) something else?"); user selects the correct destination.
- **Expected outcome:** System **undoes the original write if possible** and **routes to the corrected destination**; a new route is created to the corrected destination; the correction is recorded **alongside** the original route (status `corrected`, correction jsonb populated) — not as a replacement; logged as ground truth for quality monitoring.
- **Variations:** recipe → preference (the canonical example); preference → nutrition; provisions → recipe; correct a route that was at high confidence (still allowed); correct then the user disagrees again (re-correct — FEED-23).
- **HLD ref:** feedback-system.md §Correction flow; §Ground truth from corrections; §Service Interface (correctRoute); §Feedback Storage (correction recorded alongside).
- **Notes:** Two assertions: (1) old destination's effect undone, (2) corrected destination's effect applied, (3) original routing-log row retained with correction recorded. Cross-module: depends on per-destination reversibility (FEED-20/21/22).

#### FEED-20 — Reverter: undo a pending recipe adaptation on correction
- **Category:** Happy
- **Actor:** Primary user
- **Preconditions:** A route to recipe produced a PENDING change (AdaptationResult, not yet accepted); user corrects the route.
- **Action:** Correct the recipe route to a different destination.
- **Expected outcome:** The **pending change is cancelled (deleted)** — fully reversible because nothing was applied to the recipe yet; then routed to the corrected destination.
- **Variations:** pending change still pending (clean cancel); `[HLD-GAP]` — what if the user already ACCEPTED the pending change (a new recipe version exists) before correcting the route? The "undo if possible" is silent on an already-applied recipe version (no revert-to-prior described here, unlike Recipe's own revert).
- **HLD ref:** feedback-system.md §Correction limitations (recipe adaptations already pending → cancelled).
- **Notes:** Cross-module: Recipe (delete pending change). Already-accepted case is a GAP; the reverter calls into the Recipe destination.

#### FEED-21 — Reverter: roll back a preference / provisions write on correction
- **Category:** Alternate
- **Actor:** Primary user
- **Preconditions:** A route to preference (last delta batch) or provisions; user corrects it.
- **Action:** Correct the route.
- **Expected outcome:** Preference — the **last delta is partially rolled back** (the delta is logged, enabling partial reversal). Provisions — cost-feedback / equipment / waste / supplier-cache writes are **undone**. Then routed to the corrected destination.
- **Variations:** preference delta partial rollback (only this feedback's contribution, not the whole batch); provisions cost log undone; provisions equipment-list change undone; `[HLD-GAP]` — "partially rolled back" for preference is not precisely defined (which part survives?).
- **HLD ref:** feedback-system.md §Correction limitations (preference partial rollback; provisions undone).
- **Notes:** Cross-module: Preference, Provisions reverters. "Partial" preference rollback semantics is a GAP.

#### FEED-22 — Reverter limitation: nutrition correction may not fully reverse
- **Category:** Error / Edge
- **Actor:** Primary user
- **Preconditions:** A route to nutrition that triggered a downstream effect (e.g. mid-week re-optimisation of remaining days); user corrects the route.
- **Action:** Correct the nutrition route.
- **Expected outcome:** The correction is **logged**, but cascading downstream effects (already-regenerated remaining-days plan) **may not fully reverse** — the system does not guarantee a clean undo for nutrition writes that cascaded.
- **Variations:** nutrition write with no downstream cascade (cleanly reversible); nutrition write that already triggered a re-opt (cascade not reversed); `[HLD-GAP]` — what the user is told when a correction can't fully reverse, and what state the cascaded plan is left in, is unspecified.
- **HLD ref:** feedback-system.md §Correction limitations (Nutrition harder to undo if downstream effects fired).
- **Notes:** Cross-module: Nutrition + (downstream) Planner. The "logged but not reversed" outcome is the asserted contract; user-facing messaging is a GAP.

#### FEED-23 — Correct an already-resolved / already-corrected route (illegal)
- **Category:** Error
- **Actor:** Primary user
- **Preconditions:** A route already in `corrected` status, or a `failed` route, or a route that doesn't exist.
- **Action:** Attempt to correct it (again).
- **Expected outcome:** `[HLD-GAP]` — the HLD defines the correction flow once but never states whether a route can be corrected twice, whether a `failed` route is correctable, or what happens for an unknown routing_id. Expected behaviour (reject as illegal vs allow re-correct, full history preserved either way) is unspecified.
- **Variations:** correct an already-corrected route; correct a failed route; correct with an unknown feedback_id/routing_id (not-found); correct to the SAME destination it already targets (no-op).
- **HLD ref:** feedback-system.md §Correction flow; §Feedback Storage (corrections recorded alongside, history preserved).
- **Notes:** Illegal-transition / validation pathway, entirely GAP-driven for the terminal rules.

#### FEED-24 — Correct a route whose destination write cannot be undone
- **Category:** Error / Edge
- **Actor:** Primary user
- **Preconditions:** A route the user wants to re-route, but the original write is in the "not fully reversible" class (nutrition cascade; or `[HLD-GAP]` provisions write that already drove a planner re-opt).
- **Action:** Correct the route.
- **Expected outcome:** "Undo the original write **if possible**" — when not possible, the correction is still logged and the corrected destination still receives the routed feedback, but the original effect persists. `[HLD-GAP]` — the user-facing contract for "we routed your correction but couldn't fully undo the first action" is not specified.
- **Variations:** nutrition-cascade case; provisions-disruption-that-triggered-re-opt case; recipe-already-accepted case (overlaps FEED-20).
- **HLD ref:** feedback-system.md §Correction limitations ("undo the original write if possible"; "may not fully reverse cascading effects").
- **Notes:** Distinct from FEED-22 by generalising the "if possible" qualifier across destinations.

### Notification & events

#### FEED-25 — Feedback processing emits a notification
- **Category:** Happy
- **Actor:** Feedback System → Notification System
- **Preconditions:** A feedback submission completed with ≥ 1 route.
- **Action:** Routing completes.
- **Expected outcome:** `FeedbackProcessedEvent(feedbackId, destinations, userId)` is published; Notification System confirms to the user which destinations were updated.
- **Variations:** single-destination event; multi-destination event (payload lists all); partial-success event (does it list only succeeded destinations? `[HLD-GAP]` payload semantics on partial failure unstated); does a `clarification_needed` outcome emit an event? (unspecified) `[HLD-GAP]`.
- **HLD ref:** feedback-system.md §Events published.
- **Notes:** Cross-module: Notification (detailed in notification domain + cross-journey). Assert the event fired with this user's id and the touched destinations.

### Concurrency / ordering / volume edges

#### FEED-26 — Two feedback submissions for the same target arrive together
- **Category:** Edge
- **Actor:** Primary user (+ household member) / two near-simultaneous submissions
- **Preconditions:** Two pieces of feedback for the same recipe/meal submitted close together (e.g. user double-submits, or user + household member both comment on a shared meal).
- **Action:** Both classify and route concurrently to the same destination.
- **Expected outcome:** Each is its own feedback entry with its own routes; the destination update services serialise their own writes. `[HLD-GAP]` — Feedback specifies no de-dup or single-flight; two recipe-feedback routes for the same recipe could produce two competing pending changes (the *Recipe* domain's supersession handles same-dimension stacking, but Feedback never references it — cross-doc coordination is unstated).
- **Variations:** same user double-submit (duplicate-ish); user + household member on a shared meal; rapid succession of three; interaction with Recipe's per-(recipe,dimension) supersession (RCP-47) is not reconciled in the Feedback doc.
- **HLD ref:** feedback-system.md (no concurrency section); cross-ref recipe-system.md §Pending change supersession.
- **Notes:** Concurrency/dedup is entirely unaddressed in the Feedback HLD — flagged. Soak-mode candidate.

#### FEED-27 — High-volume / proactive-prompt feedback (open question)
- **Category:** Edge
- **Actor:** Primary user / (proposed) system prompt
- **Preconditions:** Many feedback entries; or a proposed proactive prompt ("after cooking, how was it?").
- **Action:** Submit many entries, or respond to a proactive prompt.
- **Expected outcome:** `[HLD-GAP]` (Open Questions) — proactive feedback prompts are explicitly an unresolved open question (purely reactive in v1; prompts "could be a user setting"); implicit "didn't correct = weak positive" learning is also an open question. Neither is a defined behaviour to assert in v1.
- **Variations:** large recent-feedback list pagination/boundary; proactive prompt (not in v1); implicit no-correction-as-signal (not in v1).
- **HLD ref:** feedback-system.md §Open Questions.
- **Notes:** Both items are explicitly open — `@pending` in Stage 2. Volume pagination is assertable; the prompt/implicit-signal behaviours are not yet defined.

### Flagship cross-module journey

#### FEED-28 — Compound feedback splits four ways, one route is corrected, a reverter fires across modules, the rest confirm
- **Category:** Happy (flagship end-to-end)
- **Actor:** Primary user (+ Feedback Classifier, Optimiser, Preference/Nutrition/Provisions update services, Notification as system actors)
- **Preconditions:** Authenticated; viewing a specific meal in a plan (context: recipe_id, recipe_version, meal_slot_id, plan_id, date); the recipe is in the user catalogue.
- **Action (sequence):**
  1. User submits one compound free-text: "This stir fry was too salty, I'm bored of chicken, the portions were too small, and it was really expensive."
  2. The cheap classifier returns four classifications with confidences: recipe ("too salty"), preference ("bored of chicken"), nutrition ("portions too small"), provisions ("really expensive").
  3. Each is routed **independently, in its own transaction** to its destination update service: recipe → Optimiser proposes a pending adaptation (status `pending_approval`); preference → taste-profile delta; nutrition → per-meal distribution adjust; provisions → cost concern logged.
  4. A single **confirmation** message summarises all four routes (action_taken + confidence + status each); `FeedbackProcessedEvent` fires; Notification confirms the four touched destinations.
  5. User decides the "too salty" was actually a general preference, taps "this isn't right" on the recipe route, and selects "(b) I dislike salty food → preference."
  6. **Reverter:** the recipe route's pending change is **cancelled** (fully reversible — nothing was applied); the feedback is **re-routed to preference**; the correction is recorded **alongside** the original recipe route (status `corrected`, ground-truth logged); the other three routes are untouched.
- **Expected outcome:** One feedback entry; five routing-log rows over time (recipe[corrected], preference, nutrition, provisions, + the corrected-to preference route); the original recipe pending change no longer exists; a new/updated preference signal exists; nutrition and provisions writes persist; quality monitoring has one ground-truth correction (recipe→preference); the full history (original route + correction) is preserved, nothing overwritten.
- **Variations:** all four confidences ≥ 0.8 (no flags) vs one in 0.5–0.8 (flagged) vs one < 0.5 (that slice goes to clarification while the others route — `[HLD-GAP]` whether a mixed-confidence response partially clarifies); provisions write fails (partial success, FEED-08) while the rest commit; the corrected destination (preference) itself then needs no change (NO-op delta); household member submitting on their own meal instead of the primary user.
- **HLD ref:** feedback-system.md §Multi-Destination Routing, §Confidence handling, §Confirmation and Misclassification Correction, §Correction limitations, §Events published; system-overview.md §Feedback System (four destinations, split, misclassification).
- **Notes:** CROSS-MODULE backbone — touches all four destinations (Recipe/Optimiser, Preference, Nutrition, Provisions) plus Notification; the reverter leg calls back into the Recipe destination to cancel the pending change and into Preference to apply the corrected signal. Detailed end-to-end in the cross-journey file. Assertions span: 1 feedback entry, per-destination writes, one cancelled pending change, one preference signal, one logged correction, full preserved history — all self-scoped to this user.

---

## Appendix — `[HLD-GAP]` findings (consolidated)

| # | Gap | Pathway |
|---|---|---|
| G1 | Confirmation status for a route to a **system-catalogue** recipe (applies directly, no approval) is not enumerated — only `pending_approval` is shown for recipe. | FEED-01 |
| G2 | Tie-break when a strong screen-context signal contradicts a strong text signal (context is "a signal not a constraint," but no weighting rule). | FEED-06 |
| G3 | Whether two classifications in one response may target the **same** destination (two slices → same module). | FEED-07 |
| G4 | No retry affordance for a single `failed` route in a multi-destination split. | FEED-08 |
| G5 | Confidence-gate boundary semantics: per-classification vs whole-response; which band owns the exact endpoints (0.5, 0.8); how a mixed-confidence multi-destination response is handled (partial clarify?). | FEED-09, FEED-28 |
| G6 | **Stuck classification:** no terminal/abort rule if the post-clarification re-run is still < 0.5 — loop, give up, or force a pick? No cap (unlike retry caps elsewhere). | FEED-12 |
| G7 | No "no valid destination / general non-actionable" outcome defined (praise, app complaints, questions): stored with zero routes, sent to clarification, or "couldn't act"? No status value for stored-but-unrouted. | FEED-13 |
| G8 | User-facing **input validation** entirely unspecified: empty/whitespace rejection, min/max length, behaviour when text exceeds the 5,000-token classifier cap (truncate vs reject), whether the context object is mandatory. | FEED-14 |
| G9 | Compound message mixing a **hard-constraint** claim with routable feedback — how the split is handled (route the routable part, redirect the allergy part?). | FEED-15 |
| G10 | Classification-metrics scope contradiction: interface is per-`userId` but the quality-monitoring framing reads system-wide ("90% of feedback to one destination"). | FEED-18 |
| G11 | Correcting a recipe route after the pending change was **already accepted** (a version exists) — "undo if possible" gives no revert-to-prior path. | FEED-20 |
| G12 | "Partially rolled back" for a **preference** delta is not precisely defined (which part of the delta survives the rollback). | FEED-21 |
| G13 | When a **nutrition** correction can't fully reverse a cascade (mid-week re-opt) — what the user is told and what state the regenerated plan is left in. | FEED-22 |
| G14 | Terminal rules for the correction flow: correcting an already-`corrected` route, a `failed` route, an unknown routing_id, or re-routing to the same destination (no-op). | FEED-23 |
| G15 | Generalised "undo the original write **if possible**" — the user-facing contract when a correction routes but can't undo the first action across any destination. | FEED-24 |
| G16 | `FeedbackProcessedEvent` payload semantics on **partial failure** (only succeeded destinations?) and whether a `clarification_needed` outcome emits an event at all. | FEED-25 |
| G17 | **Concurrency / de-dup:** Feedback specifies no single-flight or dedup; two recipe-feedback routes for the same recipe could create competing pending changes — interaction with Recipe's per-(recipe,dimension) supersession (RCP-47) is never reconciled across docs. | FEED-26 |
| G18 | Open Questions (explicit): proactive feedback prompts (reactive-only in v1) and implicit "no-correction = weak positive" learning are undecided. | FEED-27 |
