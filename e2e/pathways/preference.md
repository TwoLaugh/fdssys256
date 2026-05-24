# Preference Domain — User-Pathway Catalogue

> Code-agnostic behavioural catalogue derived purely from the HLD design docs. Double duty: (a) source for E2E test scenarios; (b) behavioural spec for the frontend. No endpoints, HTTP verbs, class names, or DB tables — pure user/behaviour language. Where the HLD is silent on something a user would obviously need, it is flagged `[HLD-GAP]` rather than invented.

---

## 1. Domain Summary

The Preference domain is everything the system knows about *who the eater is* — their taste, their hard limits, and the logistics of how they eat. It is **one of the three data models the system optimises against** (the Preference Loop in the three-loop architecture); it holds state and constraints, it owns no optimisation logic. It is split into **three tiers with fundamentally different update patterns**: **Hard Constraints** (allergies, dietary identity, severe intolerances, age restrictions — a hard-locked store, user-only edits, never touched by AI, enforced by a deterministic code hard-filter); the **Taste Profile** (an AI-maintained ~2500-token JSON cheat-sheet, delta-updated from feedback every 5 feedbacks or weekly, with an unbounded preference archive that prevents lossy pruning and preference cycling); and the **Lifestyle Config** (user-set planner parameters — meal structure, timing, batch cooking, reheating, eating context, seasonal, novelty tolerance — stable for months, changed when life changes, never self-updating). Each consumer (Meal Planner, Recipe Optimiser, Recipe Discovery, Household Model) reads a different slice. The domain is *not* the raw feedback (that lives in Feedback), the nutrition targets (Nutrition Model), the physical constraints (Provisions), or household structure (Household Model) — it reads/feeds those at clearly-named boundaries.

## 2. Actors

| Actor | Role in this domain (per HLD) |
|---|---|
| **Primary user** | Owns all three tiers as the editor of record. Sets hard constraints at onboarding and edits them through a dedicated labelled UI; configures lifestyle settings; views/corrects the taste profile ("here's what I think you like"), overrides any learned preference, requests a manual taste-profile update, rolls back a profile version. |
| **Household member** | Has their own account / Preference Model / Nutrition Model. Gives feedback only on *their own* meals. Their hard constraints participate in the union for shared meal slots. Recipe-write authority is out of scope here. |
| **Child profile (sub-actor)** | A profile with `age_group ∈ {young_child, child, teen}`; drives auto-populated age restrictions and `preference_volatility: high` (higher confirmation threshold). Edited by the managing adult, not itself `[HLD-GAP]`. |
| **Taste-Profile Update Pipeline (AI, system actor)** | Loads current profile + archive + new feedback since the cursor; proposes deltas (ADD/REMOVE/UPDATE/PROMOTE/DISCARD/ARCHIVE/RE-PROMOTE); never regenerates wholesale; never touches hard constraints or lifestyle config; writes a new versioned profile + archive moves. |
| **Feedback System (caller)** | Routes preference-relevant feedback into the preference update pipeline; counts toward the every-5 batch trigger; misrouted feedback must be surfaceable/correctable. |
| **Deterministic Hard-Filter (system actor)** | Code, not AI. Reads hard constraints from the DB (never the taste profile) at every food-touching step; injects them at prompt-assembly time, labelled and separate. |
| **Meal Planner / Recipe Optimiser / Recipe Discovery (consumers)** | Read slices of the model as constraints/weights/scoring inputs; the planner is the primary consumer and the reason lifestyle config exists. |
| **Household Model (consumer)** | Reads Tier 1 + Tier 2 across multiple users; computes the union (most restrictive) of hard constraints for shared meals; reads `household_context` / `suitable_for`. |
| **Scheduler / system clock (system actor)** | Drives the weekly taste-profile update fallback, the 4-week experiment lifecycle, the 3-month stale-stable-preference re-evaluation flag, and the 2–3-month lifestyle "is this still accurate?" review prompts. |

## 3. Action Space (frontend-spec backbone)

Flat, exhaustive list of every distinct user (or user-facing system) action the HLD permits across the three tiers. Each: verb-phrase + one-line description + HLD ref. Downstream pathways draw from this.

### Onboarding / bootstrap
1. **Collect hard constraints at onboarding** — capture allergies + dietary identity upfront (safety-critical, required to function). §Bootstrapping (Hard constraints).
2. **Take the quick preference quiz** — swipe like/dislike on 10–15 dishes/ingredients → AI seeds an initial taste profile (low `evidence_count`, flagged low-confidence). §Bootstrapping (Taste profile).
3. **Import preference signal from an external source** — seed initial preferences from MyFitnessPal history or a favourite-recipes list. §Bootstrapping (Alternative sources).
4. **Configure essential lifestyle settings (Day 1)** — meal structure, weeknight cooking-time tolerance, dietary identity. §Bootstrapping (Lifestyle config, progressive disclosure).
5. **Configure prompted lifestyle settings (Week 1 / Week 2+)** — eating context, batch-cooking interest, then reheating / seasonal / novelty tolerance as progressively disclosed. §Bootstrapping (progressive disclosure).

### Hard Constraints (Tier 1 — user-only, deterministic)
6. **Add / edit an allergy** — add or change an entry in the allergy list via the dedicated labelled UI. §Tier 1; §Guardrails (hard constraints).
7. **Remove an allergy** — delete an allergy entry (user-only). §Tier 1.
8. **Set / edit dietary identity** — structured `base` + conditional `exceptions` (frequency/context) + display label. §Tier 1 (Dietary identity).
9. **Add / edit a hard (severe) intolerance** — record a severity-promoted intolerance (e.g. coeliac) enforced like an allergy. §Tier 1 (Intolerances).
10. **Add / edit a medical diet** — record a medical-diet entry in hard constraints. §Tier 1 (shape `medical_diets`).
11. **View / manage auto-populated age restrictions** — see restrictions auto-added for a child profile (e.g. no whole nuts under-5). §Tier 1 (Age restrictions); §Profile Metadata.

### Taste Profile (Tier 2 — AI-maintained, user-viewable/correctable)
12. **View the taste profile ("here's what I think you like")** — read the AI's current cheat-sheet, with recent changes highlighted and `source` (feedback/inferred/onboarding) shown. §Versioning (user-facing); §Design notes (source tracking).
13. **Manually override a learned preference** — correct/override any taste-profile item; the override is flagged so the AI won't re-learn the wrong thing. §Guardrails; §Versioning (user-facing).
14. **Add a soft (mild) intolerance** — record a mild intolerance (e.g. lactose, small amounts OK) as a soft constraint in the taste profile. §Tier 1 (Intolerances split by severity).
15. **Request a manual taste-profile update** — trigger the delta update on demand rather than waiting for the batch/weekly trigger. §How It Gets Updated (Trigger — manual).
16. **View taste-profile version history** — list versions with `feedback_range`, `trigger`, `deltas_applied`, `model_tier_used`. §Versioning.
17. **Roll back the taste profile to a prior version** — revert and replay feedback forward from that version's cursor. §Versioning (Rollback).
18. **View the preference archive** — see previously-pruned preferences with evidence count, last signal, and pruning reason. §Tier 2 (Archive).

### Taste-Profile Update Pipeline (system-applied deltas, surfaced via outcomes)
19. **Run a delta update (batch / weekly trigger)** — pipeline fires after 5 new feedbacks or weekly, loads profile + archive + new feedback, proposes & applies validated deltas, increments version & cursor. §How It Gets Updated.
20. **Promote / discard an active experiment** — resolve a hypothesis (move to a stable field, or drop) after enough evidence or 4 weeks. §Guardrails (experiments); §How It Gets Updated (PROMOTE/DISCARD).
21. **Archive / re-promote a preference** — prune the lowest-evidence/stalest items under token pressure into the archive, or re-promote a re-emerging archived item carrying forward its evidence. §Tier 2 (Archive); §How It Gets Updated (ARCHIVE/RE-PROMOTE).
22. **Detect an update anomaly** — compute a structural diff after each update and alert on an unusually large diff (e.g. >3 removals in one update). §Versioning (Anomaly detection).

### Lifestyle Config (Tier 3 — user settings, no AI)
23. **Edit meal structure** — weekday/weekend meals, snacks, recurring skips. §Tier 3 (shape).
24. **Edit meal timing** — preferred schedule per meal + flexibility. §Tier 3.
25. **Edit novelty tolerance** — per-slot mode, repeat cooldown weeks, ingredient frequency caps. §Tier 3 (Novelty tolerance per meal slot).
26. **Edit cooking contexts** — per-context max time / complexity / preferred styles / ingredient-count bounds (weeknight, weekend, project, lazy_night). §Tier 3 (Structured cooking contexts).
27. **Edit batch-cooking settings** — prep days, leftover days, leftover strategy, freezer tolerance & exclusions, same-protein rule. §Tier 3 (Batch cooking).
28. **Edit reheating preferences** — available equipment at work/home, preferred method, never-reheat/never-batch exclusions, cold-meal tolerance. §Tier 3 (Reheating preferences).
29. **Edit eating context** — per-slot location/format/constraints (e.g. office/packed/portable). §Tier 3.
30. **Edit seasonal preferences** — lean-toward / avoid lists per season. §Tier 3 (Seasonal preferences).
31. **Edit meal-type preferences** — per-meal variety/complexity tolerance + staples. §Tier 3.
32. **Edit accompaniments** — beverages and sides treated as meal components. §Tier 3 (Accompaniments).
33. **Edit grocery quality preferences** — organic / free-range / branded-vs-own-label rules (what the user *values*, independent of budget). §Tier 3 (Grocery quality preferences).
34. **Respond to a lifestyle review prompt** — confirm/update a setting on the 2–3-month "is this still accurate?" prompt. §Guardrails (Lifestyle config); §Tier 3 (Staleness risk).
35. **Respond to a behavioural-drift prompt** — accept/decline a suggested config change when logged behaviour contradicts config (e.g. home lunches vs office/packed). §Guardrails (behavioural drift).
36. **View the lifestyle-config change audit log** — read the standard timestamped audit of lifestyle changes. §Versioning (lifestyle simpler versioning).

### Profile metadata
37. **View / set profile metadata** — `age`, `age_group`, `portion_scale`, `preference_volatility`, `update_confirmation_threshold`. §Profile Metadata.

### Cross-tier reads (consumer-facing)
38. **Read the hard-constraint set (filter input)** — deterministic hard-filter reads the DB at every food-touching step. §Tier 1; optimisation-loop §Hard-filter function.
39. **Read a taste-profile slice (planner/optimiser/discovery)** — consumer pulls only the fields it needs. §How It Gets Used.
40. **Compute the household hard-constraint union** — Household Model reads Tier 1 across eaters for a shared slot (most restrictive). §How It Gets Used (Household); system-overview §Household / §Constraint resolution.

## 4. State Models

### 4.1 Taste-profile version lifecycle
```
SEEDED (onboarding quiz / import — low evidence_count, flagged low-confidence)
   │  feedback accumulates (counts toward "every 5")
   ▼
ACTIVE vN  ── delta update (batch | weekly | manual) ──► ACTIVE vN+1  (prior version retained)
   │  ├─ user manually overrides an item → flagged-override (AI must not re-learn it from old data)
   │  ├─ token budget approached → low-evidence/stale items ARCHIVED (not deleted)
   │  └─ user rolls back → revert to vK + replay feedback forward from vK's feedback_cursor
   ▼
(retention: ≥ last 10 versions; first year keep all)
```
Each version carries `feedback_range`, `trigger`, `deltas_applied`, `model_tier_used`. Profile is **never regenerated wholesale** — only deltas applied.

**Illegal / disallowed transitions (→ error pathways):**
- AI may NOT alter hard constraints or lifestyle config via a taste-profile delta (the document doesn't contain them).
- A delta not supported by new feedback must not be applied.
- No lossy drop: removing a preference must be an explicit REMOVE/ARCHIVE; ARCHIVE preserves, never deletes.
- An override-flagged item must not be silently re-learned from old feedback.

### 4.2 Active-experiment lifecycle (within the taste profile)
```
CREATED (hypothesis, status: testing, evidence_for/against)
   ├─ enough confirming signals (≥ update_confirmation_threshold) → PROMOTED to a stable field
   ├─ contradicted / insufficient evidence                       → DISCARDED
   └─ 4 weeks elapsed with insufficient evidence                 → DISCARDED (forced resolve)
```
Caps: ≤ 5 active experiments; ≤ 8 learned insights (insights duplicating structured fields are removed). `preference_volatility: high` (children) raises the confirmation threshold (default 3 adults / 5 children).
**Illegal transitions:** an experiment lingering unresolved past 4 weeks (must resolve); exceeding the 5-experiment cap (must archive/resolve before adding).

### 4.3 Preference-archive lifecycle
```
(active item) ── ARCHIVE (pruned: low evidence | staleness | token pressure) ──► ARCHIVED (retained, with reason + evidence_count + last_signal)
ARCHIVED ── new supporting feedback ──► RE-PROMOTED to active (carries forward historical evidence; not a fresh discovery)
```
Archive is unbounded, excluded from regular prompts, loaded alongside the profile during updates. **Illegal:** treating a re-emerging archived item as a brand-new ADD (must RE-PROMOTE to carry evidence and avoid preference cycling).

### 4.4 Hard-constraint lifecycle
```
SET (onboarding, required) ──► user-only edits (add/remove/modify) ──► (each change logged with timestamp)
```
No AI write, no feedback-system write, no optimiser write — ever. The hard-filter always reads the DB, never the taste profile.
**Illegal transitions:** any non-user actor mutating a hard constraint; a hard constraint stored in / read from the taste-profile JSON; relaxing a hard constraint to resolve a constraint conflict (structural change proposed instead).

### 4.5 Lifestyle-config lifecycle
```
DEFAULTED (sensible defaults; system works unconfigured)
   │  progressive disclosure during onboarding fills fields over Day1 → Week1 → Week2+
   ▼
CONFIGURED ── user edits via settings UI ──► CONFIGURED' (standard timestamped audit entry)
   │  ├─ 2–3-month review prompt → user confirms / updates
   │  └─ behavioural-drift detected → user prompted to update
```
Not touched by the AI feedback loop. **Illegal transitions:** AI/feedback mutating lifestyle config; a taste-profile update altering meal structure/timing because of a meal rating.

---

## 5. Pathways

> Categories: **Happy** (default success), **Alternate** (valid non-default), **Error** (validation/not-found/unauthorized/conflict/illegal-transition), **Edge** (empty/huge/boundary/duplicate/concurrent). Cross-module touchpoints (Feedback, AI delta pipeline, Planner/Optimiser/Discovery consumers, Household merge, Nutrition/Provisions boundaries) are noted; they are fully detailed in their own domain files + the cross-journey file.

### Onboarding / bootstrap

#### PREF-01 — Collect hard constraints at onboarding
- **Category:** Happy
- **Actor:** Primary user
- **Preconditions:** New account; no preference model yet.
- **Action:** Provide allergies and dietary identity in the onboarding flow.
- **Expected outcome:** Hard constraints stored in the hard-locked tier; dietary identity stored as structured `base` + optional `exceptions` + display label; available to the deterministic hard-filter immediately; the system can now plan safely.
- **Variations:** allergies only; dietary identity only; both; a base-with-exception identity ("vegetarian but fish 2–3x/week" → label pescatarian); no allergies at all (empty list is valid). `[HLD-GAP]` — whether onboarding *blocks* progression until hard constraints are confirmed (they're "always collected") vs allows skipping is not stated.
- **HLD ref:** preference-model.md §Bootstrapping (Hard constraints), §Tier 1; system-overview.md §Data Model 1.
- **Notes:** Safety-critical seed for every downstream food-touching step. Self-contained (fresh user).

#### PREF-02 — Take the quick preference quiz
- **Category:** Happy
- **Actor:** Primary user (+ Taste-Profile Update Pipeline as system actor)
- **Preconditions:** Onboarding; taste profile near-empty.
- **Action:** Swipe like/dislike across 10–15 dishes/ingredients.
- **Expected outcome:** AI generates an initial taste profile; seeded preferences carry low `evidence_count` and `source: onboarding`; the whole profile is flagged low-confidence so the planner explores rather than over-fits.
- **Variations:** all likes; all dislikes; mixed; user skips the quiz (empty profile — system still works via defaults/exploration); very few swipes (sparse seed).
- **HLD ref:** preference-model.md §Bootstrapping (Taste profile); §Design notes (source tracking, evidence).
- **Notes:** Cross-module: AI generation. Low-confidence flag is the key assertion; ties to planner exploration behaviour.

#### PREF-03 — Import preference signal from an external source
- **Category:** Alternate
- **Actor:** Primary user
- **Preconditions:** Onboarding or later; an external source available (MyFitnessPal history, favourite-recipes list).
- **Action:** Import history/favourites to infer initial preferences.
- **Expected outcome:** Inferred preferences seeded with appropriate `source`/low evidence; profile remains low-confidence.
- **Variations:** MFP history; favourite-recipes list (overlaps recipe batch quick-start, RCP-07); import yields nothing usable (no-op, empty seed); import conflicts with quiz answers `[HLD-GAP]` (reconciliation between quiz seed and import seed unspecified).
- **HLD ref:** preference-model.md §Bootstrapping (Alternative sources).
- **Notes:** Cross-module: Recipe import (favourites) shares the URL pipeline. `[HLD-GAP]` — `source` value for imported signal not enumerated.

#### PREF-04 — Configure essential lifestyle settings (Day 1)
- **Category:** Happy
- **Actor:** Primary user
- **Preconditions:** Onboarding.
- **Action:** Set meal structure (which meals eaten), weeknight cooking-time tolerance, and dietary identity.
- **Expected outcome:** Lifestyle config populated for the essentials; unconfigured fields fall back to sensible defaults; the system functions immediately.
- **Variations:** minimal (only required Day-1 fields); user changes defaults vs accepts them; dietary identity captured here also feeds Tier 1 `[HLD-GAP]` (the same dietary-identity field appears in both Bootstrapping/lifestyle and Tier 1 hard constraints — which store is canonical at onboarding is ambiguous).
- **HLD ref:** preference-model.md §Bootstrapping (Lifestyle config, progressive disclosure).
- **Notes:** Defaults are "documented once all three data model designs are complete" — concrete default values are a deferred GAP (see appendix).

#### PREF-05 — Progressive disclosure of further lifestyle settings (Week 1 / Week 2+)
- **Category:** Alternate
- **Actor:** Primary user / Scheduler (prompting)
- **Preconditions:** Day-1 essentials set; later in onboarding lifecycle.
- **Action:** Respond to prompted settings — Week 1: eating context, batch-cooking yes/no; Week 2+: reheating (only if batch cooking on), seasonal, novelty tolerance.
- **Expected outcome:** Each prompted field stored when answered; skipped fields keep defaults; reheating prompt only appears if batch cooking is enabled.
- **Variations:** batch cooking disabled (reheating prompt suppressed); user defers a prompt; user answers out of order `[HLD-GAP]` (whether settings can be set ahead of their disclosure step is unstated).
- **HLD ref:** preference-model.md §Bootstrapping (progressive disclosure).
- **Notes:** Conditional disclosure (reheating⇐batch-cooking) is the key assertion.

### Hard Constraints (Tier 1)

#### PREF-06 — Add or edit an allergy
- **Category:** Happy
- **Actor:** Primary user
- **Preconditions:** Authenticated; existing preference model.
- **Action:** Add a new allergy (or edit an existing entry) via the dedicated, clearly-labelled hard-constraints UI.
- **Expected outcome:** Allergy persisted in the hard-locked tier; change logged with a timestamp; the deterministic hard-filter picks it up on the next food-touching step; nothing written to the taste profile.
- **Variations:** add first allergy; add to an existing list; edit wording of an existing entry; add an allergy that overlaps an existing soft intolerance (e.g. promoting lactose) — see PREF-10.
- **HLD ref:** preference-model.md §Tier 1; §Guardrails (hard constraints, changes logged).
- **Notes:** Cross-module: every downstream consumer's hard-filter. Audit-log entry is a self-scoped assertion.

#### PREF-07 — Remove an allergy
- **Category:** Alternate
- **Actor:** Primary user
- **Preconditions:** An allergy exists.
- **Action:** Remove the allergy entry.
- **Expected outcome:** Entry removed (user-only action); change logged with timestamp; hard-filter no longer blocks that allergen.
- **Variations:** remove one of several; remove the last allergy (empty list valid); remove then re-add. `[HLD-GAP]` — no confirmation/safety-interstitial behaviour is specified for *removing* a safety-critical constraint (only that changes are logged).
- **HLD ref:** preference-model.md §Tier 1; §Guardrails.
- **Notes:** Loosening a safety constraint — the missing confirmation step is a finding.

#### PREF-08 — Set or edit a structured dietary identity
- **Category:** Happy
- **Actor:** Primary user
- **Preconditions:** Authenticated.
- **Action:** Set `base` identity plus optional conditional `exceptions` (frequency/context) and a display label.
- **Expected outcome:** Stored structured; the hard-filter uses `base` as the safe default and widens only when an exception condition is met; display label shown in UI.
- **Variations:** base only (e.g. vegan, no exceptions); base + frequency exception ("fish 2–3x/week"); base + context exception ("meat only when dining out"); change base from vegetarian → vegan (narrowing); add an exception (widening). `[HLD-GAP]` — how the hard-filter evaluates "frequency" exceptions at a single meal (it can't know weekly frequency from one slot) is unspecified.
- **HLD ref:** preference-model.md §Tier 1 (Dietary identity); optimisation-loop.md §Hard-filter function.
- **Notes:** The base-default-then-widen rule is the key assertion; frequency-exception evaluation is a meaningful GAP.

#### PREF-09 — Non-user actor attempts to modify a hard constraint
- **Category:** Error
- **Actor:** Taste-Profile Update Pipeline / Feedback System / Optimiser (any non-user)
- **Preconditions:** A hard constraint exists; an AI/feedback/optimiser path attempts to change it (e.g. a delta proposing to drop an allergy).
- **Action:** Non-user write to the hard-locked tier.
- **Expected outcome:** Rejected — hard constraints are user-only; the AI document doesn't even contain them, so a delta cannot reference them; no mutation; (ideally) logged.
- **Variations:** delta attempts to ADD/REMOVE an allergy; feedback "I think I'm not allergic anymore" routes to preference but must NOT auto-edit Tier 1; optimiser tries to relax dietary identity to satisfy a conflict (must propose a structural change instead, PREF-37).
- **HLD ref:** preference-model.md §Tier 1; §Guardrails (never modified by AI); §How the Taste Profile Gets Updated (Rules: do not alter hard constraints).
- **Notes:** Core safety invariant. Assert "no change to Tier 1" rather than any partial write.

#### PREF-10 — Promote a mild intolerance to a hard (severe) intolerance
- **Category:** Alternate
- **Actor:** Primary user
- **Preconditions:** A mild intolerance exists as a soft constraint in the taste profile (e.g. lactose), or a new severe diagnosis.
- **Action:** Record a severe intolerance (e.g. coeliac) in hard constraints with severity/notes.
- **Expected outcome:** Stored in Tier 1 and enforced identically to an allergy by the deterministic filter; if it was previously a soft intolerance, behaviour now flips from "avoid dairy-heavy dishes" to zero-tolerance hard-block.
- **Variations:** brand-new severe intolerance; promotion of an existing mild one; the same substance existing in both tiers simultaneously `[HLD-GAP]` (whether the soft-tier entry must be removed when promoted, or both coexist, is unspecified).
- **HLD ref:** preference-model.md §Tier 1 (Intolerances split by severity).
- **Notes:** Tier-crossing; the soft/hard coexistence question is a GAP.

#### PREF-11 — Manage auto-populated age restrictions for a child profile
- **Category:** Edge
- **Actor:** Managing adult (Primary user) / system (auto-population)
- **Preconditions:** A profile with `age_group ∈ {young_child, child}`.
- **Action:** View the age restrictions the system auto-populated (e.g. no whole nuts under-5, no raw shellfish).
- **Expected outcome:** Restrictions present and enforced deterministically like allergies; tied to `age_group` in profile metadata.
- **Variations:** young_child (most restrictions); older child (fewer); restriction that should lapse as the child ages `[HLD-GAP]` (whether age restrictions auto-update on a birthday / age_group change is unspecified); adult profile (no auto-restrictions).
- **HLD ref:** preference-model.md §Tier 1 (Age restrictions); §Profile Metadata.
- **Notes:** Cross-module: Household (child profiles). Lapse-on-ageing is a GAP. Editing the child profile itself is also a GAP (see actors table).

### Taste Profile (Tier 2)

#### PREF-12 — View the taste profile ("here's what I think you like")
- **Category:** Happy
- **Actor:** Primary user
- **Preconditions:** A taste profile exists (seeded or evolved).
- **Action:** Open the taste-profile view.
- **Expected outcome:** Structured preferences shown (flavour/texture/ingredient/cuisine/cooking/portion etc.), each ingredient item showing `source` (feedback/inferred/onboarding) and evidence; recent changes highlighted so the user can catch errors quickly.
- **Variations:** near-empty seeded profile; rich evolved profile; profile with active experiments and trending items shown distinctly; inferred-vs-explicit visibly distinguished.
- **HLD ref:** preference-model.md §Versioning (user-facing); §Design notes (source tracking).
- **Notes:** Pure read. Source distinction is a key assertion (drives override ease, PREF-13).

#### PREF-13 — Manually override a learned preference
- **Category:** Alternate
- **Actor:** Primary user
- **Preconditions:** A taste-profile item exists (especially an `inferred` one).
- **Action:** Correct or override the item ("no, I actually like coriander").
- **Expected outcome:** Item updated to the user's value; the override is **flagged** so the AI does not re-learn the old value from stale feedback; inferred items are easier to override than explicit ones.
- **Variations:** override an inferred item; override an explicit feedback-sourced item (should be harder/slower `[HLD-GAP]` — "easier to override" is asserted for inferred but the exact friction difference is unspecified); override that contradicts strong evidence (high evidence_count) — override still wins but flag persists; override then later real feedback re-confirms the AI's original `[HLD-GAP]` (how long the override flag suppresses re-learning is unstated).
- **HLD ref:** preference-model.md §Guardrails (overrides flagged); §Versioning (user can override).
- **Notes:** The override flag is the load-bearing assertion. Two GAPs around override friction and flag lifetime.

#### PREF-14 — Add a mild (soft) intolerance to the taste profile
- **Category:** Alternate
- **Actor:** Primary user
- **Preconditions:** Authenticated.
- **Action:** Record a mild intolerance (e.g. lactose — small amounts OK) as a soft constraint.
- **Expected outcome:** Stored in the taste profile's `soft_constraints.intolerances`; the planner/optimiser treat it as a soft constraint (avoid dairy-heavy), NOT a hard block.
- **Variations:** add lactose-mild; add a second soft intolerance; a substance that should actually be hard (user under-reports severity) — see PREF-10 for promotion.
- **HLD ref:** preference-model.md §Tier 1 (Intolerances split by severity); §Tier 2 shape (`soft_constraints`).
- **Notes:** Boundary between hard-block and soft-avoid is the key assertion.

#### PREF-15 — Request a manual taste-profile update
- **Category:** Alternate
- **Actor:** Primary user (+ Update Pipeline)
- **Preconditions:** Some feedback exists since the last `feedback_cursor` (or none).
- **Action:** Request an immediate taste-profile update rather than waiting for batch/weekly.
- **Expected outcome:** Delta update runs on demand (`trigger: manual`); new version with `feedback_range`, `deltas_applied`, etc.; cursor advances.
- **Variations:** with new feedback (real deltas); with no new feedback since the cursor (NO_CHANGE — no new version, or an empty-delta version `[HLD-GAP]`); rapid repeated manual requests (throttling unspecified `[HLD-GAP]`).
- **HLD ref:** preference-model.md §How It Gets Updated (Trigger — manual).
- **Notes:** Cross-module: AI delta pipeline. Empty-feedback behaviour is a GAP.

#### PREF-16 — Run a delta update on the batch/weekly trigger
- **Category:** Happy
- **Actor:** Taste-Profile Update Pipeline (on Feedback batch / Scheduler weekly)
- **Preconditions:** 5 new feedback entries accumulated, or a week elapsed.
- **Action:** Pipeline loads current profile + archive + feedback since cursor; proposes deltas (ADD/REMOVE/UPDATE/PROMOTE/DISCARD/ARCHIVE/RE-PROMOTE); each is schema-validated; valid ones applied.
- **Expected outcome:** New profile version; only feedback-supported changes applied; hard constraints and lifestyle config untouched; insights deduped against structured fields; version + cursor + `last_updated` advance; previous version retained.
- **Variations:** `trigger: batch` (5 feedbacks) vs `trigger: weekly` (whichever first); a delta the AI proposes that isn't feedback-supported (must be rejected at validation); a delta that tries to touch hard constraints / lifestyle (rejected, PREF-09); update producing only UPDATE (evidence_count bumps) and no structural change.
- **HLD ref:** preference-model.md §How It Gets Updated; §Why deltas.
- **Notes:** Cross-module: Feedback (counts toward trigger), AI. Assert "only supported deltas applied" + tier isolation.

#### PREF-17 — Promote or discard an active experiment
- **Category:** Happy
- **Actor:** Taste-Profile Update Pipeline
- **Preconditions:** An `active_experiment` (status testing) exists.
- **Action:** During an update, resolve it: PROMOTE to a stable field if confirming signals ≥ `update_confirmation_threshold`, else DISCARD if contradicted or stale.
- **Expected outcome:** Promoted hypothesis becomes a structured preference (e.g. tahini → favourites); discarded one removed; experiment slot freed.
- **Variations:** promote at exactly the threshold (3 adult / 5 child — boundary); promote with conflicting evidence_for/against (e.g. 1/1 — should NOT promote); child profile `preference_volatility: high` requiring more confirmations; experiment hitting the 4-week limit with insufficient evidence (forced DISCARD).
- **HLD ref:** preference-model.md §Guardrails (experiment lifecycle); §How It Gets Updated (PROMOTE/DISCARD); §Profile Metadata.
- **Notes:** Threshold boundary + volatility-driven threshold are key tests.

#### PREF-18 — Experiment forced to resolve at the 4-week limit
- **Category:** Edge
- **Actor:** Scheduler / Update Pipeline
- **Preconditions:** An experiment older than 4 weeks with insufficient evidence.
- **Action:** Lifecycle limit reached.
- **Expected outcome:** Experiment DISCARDED (can't linger unresolved); slot freed.
- **Variations:** exactly at 4 weeks (boundary); experiment that gained enough evidence at week 3 (PROMOTED, not discarded); experiment at 4 weeks with evidence_for==evidence_against `[HLD-GAP]` (tie at the deadline — promote or discard? unspecified).
- **HLD ref:** preference-model.md §Guardrails (after 4 weeks or N points, must resolve).
- **Notes:** Time-boundary test; the tie-at-deadline case is a GAP.

#### PREF-19 — Token-budget pressure forces archival
- **Category:** Edge
- **Actor:** Taste-Profile Update Pipeline
- **Preconditions:** Profile approaching ~2500 tokens; an update wants to ADD items.
- **Action:** Pipeline proposes ARCHIVE for the lowest-evidence/stalest items before proposing additions.
- **Expected outcome:** Archived items moved to the unbounded preference archive with evidence_count, last_signal, and pruning reason (low evidence | staleness | token pressure) — never deleted; profile stays within budget; learned_insights capped at 8, active_experiments at 5.
- **Variations:** archive for token pressure; archive a stale-stable item (no signal 3+ months — flagged for re-evaluation); hitting the 8-insight cap (oldest/duplicate removed); hitting the 5-experiment cap; profile that can't get under budget even after archiving `[HLD-GAP]` (no described fallback if a single update can't fit budget).
- **HLD ref:** preference-model.md §Tier 2 (Archive); §Guardrails (token budget, caps); §Design notes (evidence/staleness).
- **Notes:** Cross-module: AI. Assert "archived not deleted" + caps. Un-fittable-budget case is a GAP.

#### PREF-20 — Re-promote a re-emerging archived preference
- **Category:** Edge
- **Actor:** Taste-Profile Update Pipeline
- **Preconditions:** A previously-archived preference; new feedback now supports it.
- **Action:** Update loads the archive alongside the profile; detects the re-emerging item.
- **Expected outcome:** Uses RE-PROMOTE (carries forward the historical evidence_count), NOT a fresh ADD — preventing preference cycling and treating it as higher-confidence.
- **Variations:** re-promote with carried evidence; an archived item that re-emerges but only weakly (insufficient new support — stays archived); the same item archived and re-promoted repeatedly (cycling guard).
- **HLD ref:** preference-model.md §Tier 2 (Archive); §How It Gets Updated (RE-PROMOTE rule).
- **Notes:** The "carry evidence, don't re-discover" rule is the load-bearing assertion.

#### PREF-21 — Delta proposes a change not supported by feedback
- **Category:** Error
- **Actor:** Taste-Profile Update Pipeline
- **Preconditions:** AI returns a delta with no supporting new feedback.
- **Action:** Validation runs against the schema + the "only feedback-supported" rule.
- **Expected outcome:** Unsupported delta rejected; not applied; the rest of the valid batch still applies.
- **Variations:** unsupported ADD; unsupported REMOVE; a delta that renames a key / changes nesting (structural drift — rejected because the app owns the schema); malformed delta response `[HLD-GAP]` (no described fallback when the whole delta response is unparseable — unlike the optimisation-loop's Stage-C retry-once, the preference pipeline doesn't specify retry/fallback).
- **HLD ref:** preference-model.md §How It Gets Updated (Parse + validate); §Why deltas (no structural drift).
- **Notes:** Schema-ownership guard. The unparseable-response fallback is a notable GAP vs the optimisation-loop doc.

#### PREF-22 — Anomalously large update detected
- **Category:** Edge / Error
- **Actor:** Update Pipeline / system
- **Preconditions:** An update whose structural diff is unusually large (e.g. >3 removals in one update).
- **Action:** Post-update structural diff computed.
- **Expected outcome:** Alert raised (likely a bad update); `[HLD-GAP]` — whether the update is auto-rolled-back, blocked, or merely alerted is not stated (only "Alert if…").
- **Variations:** exactly 3 removals (no alert) vs 4 (alert — boundary); large diff from a genuine bulk correction (false positive); large diff that is actually a bad update.
- **HLD ref:** preference-model.md §Versioning (Anomaly detection).
- **Notes:** The action-on-anomaly (alert-only vs block/rollback) is a GAP.

#### PREF-23 — View taste-profile version history
- **Category:** Happy
- **Actor:** Primary user
- **Preconditions:** ≥1 profile version.
- **Action:** Open version history.
- **Expected outcome:** Ordered versions each with `version`, `generated_at`, `feedback_range`, `trigger`, `deltas_applied`, `model_tier_used`.
- **Variations:** single seeded version; many versions (retention ≥10; first-year-keep-all); a version generated by a manual trigger vs batch vs weekly.
- **HLD ref:** preference-model.md §Versioning.
- **Notes:** Pure read. Retention boundary (≥10) is testable.

#### PREF-24 — Roll back the taste profile to a prior version
- **Category:** Alternate
- **Actor:** Primary user
- **Preconditions:** ≥2 versions; a bad/erroneous recent update.
- **Action:** Roll back to a chosen earlier version.
- **Expected outcome:** Profile reverts to that version; feedback is replayed forward from that version's `feedback_cursor` (deterministic — the cursor says exactly which feedback to reprocess); a new current version results.
- **Variations:** roll back one version; roll back several; roll back then the replay reproduces a similar profile (expected); roll back across an archival event `[HLD-GAP]` (whether replay restores archive state / re-archives is unspecified); roll back to a version older than retention keeps `[HLD-GAP]` (if only last 10 kept, rolling back to an evicted version).
- **HLD ref:** preference-model.md §Versioning (Rollback, feedback_cursor).
- **Notes:** Cross-module: Feedback (replay). Replay-determinism is the assertion; archive interplay + retention-vs-rollback are GAPs.

#### PREF-25 — View the preference archive
- **Category:** Alternate
- **Actor:** Primary user
- **Preconditions:** ≥1 archived preference exists.
- **Action:** Open the preference archive view.
- **Expected outcome:** Archived items shown with evidence_count, last_signal, and pruning reason; archive is unbounded and full-history.
- **Variations:** empty archive (nothing pruned yet); archive with items pruned for each reason (low evidence / staleness / token pressure); a re-promoted item that left the archive (no longer listed). `[HLD-GAP]` — whether the user can manually re-promote an archived item (vs only the AI on re-emerging feedback) is unspecified.
- **HLD ref:** preference-model.md §Tier 2 (Archive).
- **Notes:** Manual re-promote affordance is a GAP.

### Lifestyle Config (Tier 3)

#### PREF-26 — Edit a lifestyle-config setting
- **Category:** Happy
- **Actor:** Primary user
- **Preconditions:** Authenticated; lifestyle config exists (possibly defaulted).
- **Action:** Edit any lifestyle field via the settings UI (meal structure, timing, eating context, etc.).
- **Expected outcome:** Setting persisted immediately (manual edits take effect immediately); a timestamped audit entry recorded; the AI feedback loop does not touch this; the planner reads the new value directly as a query parameter.
- **Variations:** edit each major section (meal structure / timing / eating context / seasonal / meal-type / accompaniments); change a default to an explicit value; clear a value back to default `[HLD-GAP]` (no described "reset to default" affordance).
- **HLD ref:** preference-model.md §Tier 3 (Update mechanism); §Versioning (lifestyle audit log); system-overview.md §Manual direct edits.
- **Notes:** Cross-module: Planner (consumes directly). Audit entry is the self-scoped assertion.

#### PREF-27 — Edit novelty tolerance (caps and cooldowns)
- **Category:** Happy
- **Actor:** Primary user
- **Preconditions:** Authenticated.
- **Action:** Set per-slot novelty mode, `recipe_repeat_cooldown_weeks`, and `ingredient_frequency_caps`.
- **Expected outcome:** Stored; the planner respects caps (won't over-index a favourite) and cooldowns (a recipe can't reappear too soon).
- **Variations:** breakfast rotation vs dinner high-variety; a 0-week cooldown override (breakfast can repeat daily); a cap of "3x/week" on a favourite (planner must honour even if it's top-rated); contradictory caps (cap lower than what nutrition needs) `[HLD-GAP]` (how a novelty cap vs a nutrition requirement is reconciled isn't owned here — feeds constraint resolution, PREF-37).
- **HLD ref:** preference-model.md §Tier 3 (Novelty tolerance); §Design notes.
- **Notes:** Cross-module: Planner scoring (multiplicative variety gate per optimisation-loop §Scoring). Cap-vs-nutrition tension routes to constraint resolution.

#### PREF-28 — Edit reheating preferences with exclusions
- **Category:** Alternate
- **Actor:** Primary user
- **Preconditions:** Batch cooking enabled (reheating only surfaces then).
- **Action:** Set available equipment (work/home), preferred method, and never-reheat / never-batch exclusions + cold-meal tolerance.
- **Expected outcome:** Stored; the planner won't schedule, e.g., reheated fish for an office lunch (never_reheat fish), nor batch leafy greens beyond day 1.
- **Variations:** add a never_reheat category; add a never_batch category; cold-meal tolerance list; set preferences while batch cooking is OFF `[HLD-GAP]` (reheating "only if batch cooking enabled" — whether the fields are editable/ignored when batch cooking is off is unstated).
- **HLD ref:** preference-model.md §Tier 3 (Reheating preferences); §Bootstrapping (reheating only if batch cooking).
- **Notes:** Cross-module: Planner (batch-cook scheduling). The off-batch-cooking editability is a GAP.

#### PREF-29 — Edit grocery quality preferences
- **Category:** Alternate
- **Actor:** Primary user
- **Preconditions:** Authenticated.
- **Action:** Set organic / free-range / branded-vs-own-label rules.
- **Expected outcome:** Stored in the Preference Model (what the user *values*), kept independent of the Provisions budget; the grocery module consults Preference for quality and Provisions for affordability; a tight budget + "always free-range eggs" expresses a priority that the budget enforces the trade-off on.
- **Variations:** "organic when price comparable"; "free-range always" under a tight budget (priority vs affordability tension — resolved by budget, not here); own-label-preferred.
- **HLD ref:** preference-model.md §Tier 3 (Grocery quality preferences); §Boundaries with Other Models.
- **Notes:** Cross-module: Grocery (reads), Provisions (budget). The preference/budget independence is the key assertion.

#### PREF-30 — Respond to a 2–3-month lifestyle review prompt
- **Category:** Alternate
- **Actor:** Scheduler (prompts) / Primary user (responds)
- **Preconditions:** A lifestyle setting unchanged for 2–3 months.
- **Action:** System prompts "is this still accurate?"; user confirms or updates.
- **Expected outcome:** Confirm → setting unchanged, review timestamp resets; update → setting changed + audit entry.
- **Variations:** confirm; update; dismiss/ignore the prompt `[HLD-GAP]` (re-prompt cadence after dismissal unstated); prompt on a never-configured (defaulted) field.
- **HLD ref:** preference-model.md §Guardrails (periodic review); §Tier 3 (Staleness risk).
- **Notes:** Time-driven prompt; dismissal behaviour is a GAP.

#### PREF-31 — Respond to a behavioural-drift prompt
- **Category:** Alternate
- **Actor:** system (detects drift) / Primary user (responds)
- **Preconditions:** Logged behaviour consistently contradicts a config value (e.g. logs home lunches but config says office/packed).
- **Action:** System surfaces the contradiction and suggests an update; user accepts or declines.
- **Expected outcome:** Accept → config updated + audit; decline → config unchanged.
- **Variations:** drift on eating context; drift on meal structure (skipping a "planned" meal repeatedly); decline (config kept despite drift). `[HLD-GAP]` — what signal/threshold counts as "consistently contradicts" (how many logs over what window) is unspecified; also which subsystem owns drift detection (Preference vs Feedback vs Planner) is ambiguous.
- **HLD ref:** preference-model.md §Guardrails (behavioural drift detection).
- **Notes:** Cross-module: relies on logged behaviour (Nutrition logger / Feedback). Detection threshold + ownership are GAPs.

#### PREF-32 — View the lifestyle-config change audit log
- **Category:** Happy
- **Actor:** Primary user
- **Preconditions:** ≥1 lifestyle change made.
- **Action:** Open the lifestyle audit log.
- **Expected outcome:** Standard timestamped list of changes (simpler than taste-profile versioning since changes are infrequent and user-initiated).
- **Variations:** empty (no changes since defaults); several changes; a change made via a review/drift prompt (PREF-30/31) appearing in the log.
- **HLD ref:** preference-model.md §Versioning (lifestyle simpler versioning).
- **Notes:** Self-scoped read.

### Profile metadata

#### PREF-33 — View / set profile metadata
- **Category:** Alternate
- **Actor:** Primary user / system (auto-derives some)
- **Preconditions:** A profile exists.
- **Action:** View or set `age`, `age_group`, `portion_scale`, `preference_volatility`, `update_confirmation_threshold`.
- **Expected outcome:** Stored; `age_group` drives age-restriction auto-population and threshold defaults (3 adult / 5 child); `preference_volatility: high` (children) raises the confirmation bar; `portion_scale` is a presentation preference (calorie targets stay owned by Nutrition).
- **Variations:** adult defaults; child profile (high volatility, threshold 5, restrictions auto-populated); a portion_scale for a 5-year-old (0.33). `[HLD-GAP]` — whether `age`/`age_group` are user-set or derived, and whether they auto-advance over time, is unspecified (ties to PREF-11).
- **HLD ref:** preference-model.md §Profile Metadata.
- **Notes:** Boundary with Nutrition (portion_scale vs calorie targets). Auto-advance is a GAP.

### Cross-tier reads (consumer-facing)

#### PREF-34 — Deterministic hard-filter reads constraints at a food-touching step
- **Category:** Happy
- **Actor:** Deterministic Hard-Filter (system) on behalf of any consumer
- **Preconditions:** A food-touching operation about to surface output (plan composition, recipe optimisation, creative augmentation, grocery substitution).
- **Action:** The hard-filter reads hard constraints from the DB and screens the candidate/output.
- **Expected outcome:** Anything violating an allergy/dietary identity/severe intolerance/age restriction is filtered *before* being shown; the filter reads the DB, never the taste profile; hard constraints are injected at prompt-assembly time, clearly labelled and separate.
- **Variations:** allergen-bearing candidate dropped; dietary-identity violation dropped; severe-intolerance violation dropped; a base-with-exception identity where the exception condition is/ isn't met; everything filtered out (→ constraint resolution, PREF-37).
- **HLD ref:** preference-model.md §Tier 1 (deterministic enforcement); optimisation-loop.md §Hard-filter function; system-overview.md §Data Model 1.
- **Notes:** Cross-module: all consumers. The "reads DB not taste profile" invariant is the load-bearing assertion. Detailed per-consumer in their domain files.

#### PREF-35 — Consumer reads only its taste-profile slice
- **Category:** Happy
- **Actor:** Meal Planner / Recipe Optimiser / Recipe Discovery
- **Preconditions:** A taste profile exists.
- **Action:** The consumer pulls the fields it needs (planner: all three tiers; optimiser: Tier1+Tier2 + selective cooking contexts; discovery: mostly Tier2 cuisine/trends + selective contexts).
- **Expected outcome:** Each consumer receives its slice; evidence counts weight preferences (strong steers harder than weak); trending-positive items drive discovery exploration; no consumer needs all fields.
- **Variations:** planner full read; optimiser evidence-weighted read; discovery trend-driven read (e.g. tahini trending → lean Middle Eastern); empty/low-confidence profile (planner explores rather than over-fits).
- **HLD ref:** preference-model.md §How It Gets Used (all four consumers).
- **Notes:** Cross-module: Planner/Optimiser/Discovery — detailed in their domain files. Evidence-weighting + trend-driven exploration are the assertions.

#### PREF-36 — Compute the household hard-constraint union for a shared slot
- **Category:** Alternate
- **Actor:** Household Model (reads) → Meal Planner
- **Preconditions:** A shared meal slot with ≥2 eaters, each with their own hard constraints.
- **Action:** Compute the union (most restrictive) of all eaters' hard constraints for that slot.
- **Expected outcome:** The shared-slot hard-filter uses the union; `household_context` / `suitable_for` tags distinguish individual-only from family-suitable options.
- **Variations:** compatible constraints (clean union); one eater's allergy restricts the whole slot; `individual_only_preferences` (e.g. blue cheese) kept out of shared slots; the union being irreconcilable (→ PREF-37). `[HLD-GAP]` — full merge weighting/conflict resolution is explicitly deferred to the Household Model design (not specified here).
- **HLD ref:** preference-model.md §How It Gets Used (Household); §Design notes (household context); system-overview.md §Household Model.
- **Notes:** Cross-module: Household (owns merge logic), Planner. Most-restrictive union is the assertion; weighting is a deferred GAP.

#### PREF-37 — Constraint set too restrictive → resolution, hard constraints never relaxed
- **Category:** Error / Edge
- **Actor:** Meal Planner (constraint feasibility check) ← Preference inputs
- **Preconditions:** The active constraint set (preferences + nutrition + provisions + household union) is too restrictive to produce a viable plan.
- **Action:** Planner runs the feasibility check before composition.
- **Expected outcome:** Conflict surfaced with proposed resolutions; **hard constraints (allergies, dietary identity) are never relaxed** — instead structural changes are proposed (e.g. split a shared slot into individual meals for a vegan+keto collision); soft preferences *can* be relaxed and are ranked by quality recovered per unit loosened (e.g. "relax 3x/week chicken cap to 4x → 6 more options"); the user always chooses; never silently degraded.
- **Variations:** household hard-constraint collision (→ split meal); over-specified soft preferences (novelty + cuisine + caps too tight → suggest widening the most restrictive); preference cap vs nutrition floor tension; everything filtered out by hard constraints (no candidate → resolution, not silent failure).
- **HLD ref:** system-overview.md §Constraint resolution; preference-model.md §How It Gets Used (Household, irreconcilable); optimisation-loop.md §Failure Modes (no candidate passes hard-filter).
- **Notes:** Cross-module: Planner (owns resolution), Household, Nutrition, Provisions. The hard-never-relaxed / soft-rankable-relaxation split is the load-bearing assertion. Preference is the *input*; resolution mechanics belong to the Planner domain.

### Flagship cross-module journey

#### PREF-38 — Feedback → preference learning loop → updated taste profile → planner & optimiser steer differently, hard constraints untouched
- **Category:** Happy (flagship end-to-end)
- **Actor:** Primary user (+ Feedback System, Taste-Profile Update Pipeline, Meal Planner / Recipe Optimiser as system actors)
- **Preconditions:** A user with hard constraints set, a seeded/evolved taste profile, lifestyle config, and a `feedback_cursor` at the last-processed feedback.
- **Action (sequence):**
  1. The user gives preference-relevant feedback across several meals ("too much coriander", "loved the tahini dressing", "rate this batch curry highly") — routed by the Feedback System to the preference update pipeline *(cross-module: Feedback)*.
  2. On the 5th new feedback (or the weekly tick), the update pipeline loads the current taste profile + the preference archive + all feedback since the cursor *(cross-module: AI delta pipeline)*.
  3. The AI proposes deltas — ADD tahini to favourites (or RE-PROMOTE if previously archived, carrying its evidence), increment coriander's disliked evidence_count, PROMOTE the "likes tahini dressings" experiment if it crossed the confirmation threshold — strictly NOT touching hard constraints or lifestyle config.
  4. The application validates each delta against its owned schema and applies the valid ones; if the profile nears ~2500 tokens it ARCHIVES the lowest-evidence/stalest items first (preserved, not deleted); version, `feedback_cursor`, and `last_updated` advance; the prior version is retained.
  5. A post-update structural diff is computed; an unusually large diff would raise an anomaly alert.
  6. On the next plan, the Meal Planner and Recipe Optimiser read the updated taste-profile slice — discovery leans into the now-trending cuisine, the optimiser steers harder on the higher-evidence favourite, the planner respects unchanged novelty caps *(cross-module: Planner / Optimiser / Discovery)*.
  7. Throughout, the deterministic hard-filter keeps reading hard constraints from the DB (never the evolved JSON) and the user's allergies/dietary identity remain exactly as set.
- **Expected outcome:** A new taste-profile version that captures only feedback-supported deltas, with an auditable `deltas_applied` trail and an advanced cursor; hard constraints and lifestyle config provably unchanged by the AI; downstream planning observably steered by the new preferences; nothing about the learning loop able to compromise safety-critical data.
- **Variations:** feedback that re-emerges an archived preference (RE-PROMOTE vs ADD); feedback that contradicts an override-flagged item (must NOT re-learn the old value); a manual trigger instead of the 5-feedback batch; feedback that *also* mentions cost/portions (multi-destination split — only the preference slice lands here, the rest routes to Provisions/Nutrition); a child profile (`high` volatility → more confirmations before promotion); an anomalously large diff raising an alert.
- **HLD ref:** preference-model.md §How It Gets Updated, §Tier 2 (Archive), §Versioning, §Guardrails, §How It Gets Used; system-overview.md §Feedback System (four destinations), §Trigger 2; optimisation-loop.md §Decision Log (trace).
- **Notes:** CROSS-MODULE backbone — step 1 (routing) is owned by **Feedback**, steps 6 (consumption) by **Planner / Recipe Optimiser / Discovery**, and the multi-destination split touches **Nutrition** and **Provisions**; all detailed in those domain files + the cross-journey file. The load-bearing invariant spanning the whole journey: the AI learning loop **never** touches hard constraints or lifestyle config — assertions span taste-profile version state + archive state + an unchanged Tier 1 + observable downstream steering.

---

## Appendix — `[HLD-GAP]` findings (consolidated)

| # | Gap | Pathway |
|---|---|---|
| GP1 | Whether onboarding *blocks* progression until hard constraints are confirmed (vs allows skipping). | PREF-01 |
| GP2 | Reconciliation between the onboarding quiz seed and an external-import seed when they conflict; `source` value for imported signal not enumerated. | PREF-03 |
| GP3 | Concrete default values for lifestyle config (explicitly deferred until all three data-model designs are complete). | PREF-04, PREF-05 |
| GP4 | Which store is canonical for dietary identity at onboarding — the Tier-1 hard-constraint table or the Day-1 lifestyle/bootstrap step (it appears in both). | PREF-04, PREF-08 |
| GP5 | Whether lifestyle settings can be set ahead of their progressive-disclosure step. | PREF-05 |
| GP6 | No confirmation / safety-interstitial behaviour specified for *removing* a safety-critical hard constraint (only that changes are logged). | PREF-07 |
| GP7 | How the deterministic hard-filter evaluates a *frequency*-conditioned dietary-identity exception at a single meal slot (it can't know weekly frequency from one slot). | PREF-08 |
| GP8 | Whether a substance can coexist in both the soft (taste-profile) and hard (Tier-1) intolerance tiers, or the soft entry must be removed on promotion. | PREF-10 |
| GP9 | Whether auto-populated age restrictions auto-update when a child's age / `age_group` changes; and who edits a child profile. | PREF-11, PREF-33 |
| GP10 | Exact friction difference between overriding an `inferred` vs an `explicit` preference ("easier" is asserted but unquantified). | PREF-13 |
| GP11 | How long a manual-override flag suppresses AI re-learning from old feedback. | PREF-13 |
| GP12 | Behaviour of a manual taste-profile update when there is no new feedback since the cursor (NO_CHANGE vs empty-delta version); and any throttling on rapid repeated manual updates. | PREF-15 |
| GP13 | Fallback when the AI's whole delta response is malformed/unparseable (the preference pipeline specifies no retry/fallback, unlike optimisation-loop Stage C). | PREF-21 |
| GP14 | Action taken on an anomalously large update — alert-only vs block vs auto-rollback (doc only says "Alert if…"). | PREF-22 |
| GP15 | Whether profile-version rollback restores/re-archives prior archive state; and rollback to a version older than the retention window keeps (≥10 / first-year-all). | PREF-24 |
| GP16 | Whether a user can manually re-promote an archived preference (vs only the AI on re-emerging feedback). | PREF-25 |
| GP17 | No described "reset to default" affordance for a lifestyle setting. | PREF-26 |
| GP18 | How a novelty/ingredient-frequency cap is reconciled against a conflicting nutrition requirement (ownership is constraint-resolution, not Preference). | PREF-27 |
| GP19 | Whether reheating-preference fields are editable/ignored when batch cooking is disabled (they only "surface" when it's on). | PREF-28 |
| GP20 | Re-prompt cadence after a user dismisses (rather than answers) a 2–3-month lifestyle review prompt. | PREF-30 |
| GP21 | What signal/threshold counts as "consistently contradicts" for behavioural-drift detection, and which subsystem owns drift detection (Preference vs Feedback vs Planner). | PREF-31 |
| GP22 | Whether `age` / `age_group` are user-set or system-derived, and whether they auto-advance over time. | PREF-33 |
| GP23 | Full household hard-constraint merge weighting / conflict-resolution rules (explicitly deferred to the Household Model design). | PREF-36 |
| GP24 | Tie-at-deadline experiment resolution (evidence_for == evidence_against at the 4-week limit — promote or discard?). | PREF-18 |
| GP25 | Fallback when a single update cannot bring the profile under the ~2500-token budget even after archiving. | PREF-19 |
