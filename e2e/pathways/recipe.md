# Recipe Domain — User-Pathway Catalogue

> Code-agnostic behavioural catalogue derived purely from the HLD design docs. Double duty: (a) source for E2E test scenarios; (b) behavioural spec for the frontend. No endpoints, HTTP verbs, class names, or DB tables — pure user/behaviour language. Where the HLD is silent on something a user would obviously need, it is flagged `[HLD-GAP]` rather than invented.

---

## 1. Domain Summary

The Recipe domain is the system's only food-as-food reasoner. It is one subsystem with two cleanly separated concerns: the **Catalogue** (stores recipes, versions, branches, substitutions; pure data) and the **Adaptation Pipeline** (proposes/applies culinary + nutritional + constraint-driven changes, classifying each as version, branch, or substitution). It maintains two catalogues — the user's curated library (approval required) and an AI-managed system pool (direct write). In the three-loop architecture it is the **Recipe-scale optimisation loop** (enumerate candidate adaptations → rollup → choose → optional refine) and is the candidate pool the week-scale Meal Planner draws from. It serves all three constraint loops (preference, nutrition, provisions) by adapting recipes against them, but owns none of them — it reads them as inputs.

## 2. Actors

| Actor | Role in this domain (per HLD) |
|---|---|
| **Primary user** | Owns the user catalogue. Imports/enters/promotes recipes, manually edits, rates, reviews & accepts/rejects/modifies pending changes, reverts versions, promotes branches/substitutions, demotes recipes, runs re-optimise. |
| **Household member** | Has their own account/preference/nutrition models; can give feedback on their own meals. Recipe-write authority beyond own-meal feedback is unspecified `[HLD-GAP]`. |
| **Adaptation Pipeline (AI, system actor)** | Proposes changes to user recipes; applies directly to system recipes; extracts fingerprints; classifies changes; emits planner hints; writes traces. |
| **Feedback System (caller)** | Calls the pipeline synchronously with routed recipe-specific feedback; needs the trace ID + resulting version ID back. |
| **Meal Planner (caller)** | Invokes plan-time adaptation synchronously as a pre-step; consumes branches, substitutions, and planner hints. |
| **Nutrition Engine (downstream)** | Recalculates nutrition on every recipe-evolved signal; provides USDA mapping + per-ingredient confidence on import; receives mapping corrections. |
| **Scheduler / system clock (system actor)** | Drives weekly discovery, 3-month system-catalogue archival, 14-day pending-change expiry, weekly optimisation-budget surfacing. |

## 3. Action Space (frontend-spec backbone)

Flat, exhaustive list of every distinct user (or user-facing system) action the HLD permits. Each: verb-phrase + one-line description + HLD ref. Downstream pathways draw from this.

### Create / Import
1. **Create recipe manually** — fill a form (ingredients, method, metadata) → user catalogue, `user_verified`. §Import Pipeline (manual entry), §Service Interfaces (create).
2. **Import recipe from URL** — AI extracts a web page into internal format → user catalogue, `imported`. §Import Pipeline (URL import).
3. **Batch-import URLs (quick-start)** — onboarding "paste your 5 favourite recipe URLs" seeding the user catalogue. §Bootstrapping.
4. **Upload a recipe image** — attach an image file to a manual entry (optional). §Recipe images.
5. **Trigger AI recipe generation** — generate a recipe against a constraint brief → system catalogue. §Import Pipeline (AI generation), §Service Interfaces (generate).
6. **Trigger / consume online discovery** — weekly or gap-triggered web search, constraint-filtered, top results run through URL import → system catalogue, `web_discovered`. §Import Pipeline (online discovery).

### Read / Query
7. **View a recipe by ID** — open full recipe detail. §Service Interfaces (getById).
8. **View the recipe index/list** — filtered library listing (compact, uses aggregate rating, low-trust indicator). §Service Interfaces (getRecipeIndex); §Data quality.
9. **Search recipes** — query catalogue by criteria. §Service Interfaces (search).
10. **View version history (per branch)** — list of versions with diffs and reasons. §Versioning; §Service Interfaces (getVersionHistory).
11. **View branches of a recipe** — list branches with labels/reasons. §Branching; §Service Interfaces (getBranches).
12. **View substitutions of a recipe** — list active/historic overlays. §Substitutions; §Service Interfaces (getSubstitutions).
13. **View character fingerprint** — see defining ingredients/techniques per branch. §Character fingerprint; §Service Interfaces (getFingerprint).
14. **View nutrition for a recipe** — the internally calculated per-serving nutrition + status. §Recipe Data Model; §Service Interfaces (getNutrition).
15. **Compare two versions side-by-side** — structured diff view. §Versioning (changes diff).
16. **View adaptation trace / quality dashboard** — accept/reject/modify rates, rating delta, revert rate, per-prompt aggregates. §Observability (quality dashboard).
17. **View ingredient review badge** — "N ingredients need review" with one-tap correct flow. §USDA mapping confidence.

### Manual edit (no AI in loop)
18. **Manually edit a recipe** — change ingredients/method/metadata directly; always permitted on user recipes; creates a new version. §Two Catalogues (manual edits always permitted); §system-overview (manual direct edits).
19. **Correct an ingredient USDA mapping** — one-tap fix a flagged ingredient; feeds back to the Nutrition Model cache. §USDA mapping confidence.
20. **Manually override a nutrition value** — preserve a user-set nutrition number against recalculation. §system-overview (nutrition override exception).

### Versioning
21. **Create a new version** — linear improvement on a branch (manual or accepted proposal). §Versioning; §Service Interfaces (createVersion).
22. **Revert to a prior version** — roll the active recipe back to an earlier version. §Versioning; §Service Interfaces (revertToVersion).

### Branching
23. **Create a branch** — fork a creative variant from a version (own fingerprint, own version history). §Branching; §Service Interfaces (createBranch).
24. **Promote a divergent branch to standalone recipe** — accept the "this branch has become its own dish" prompt; copies out with `forked_from`. §Branch divergence.

### Substitutions
25. **Create a substitution overlay** — constraint-driven plan-level swap on a version. §Substitutions; §Service Interfaces (createSubstitution).
26. **Revert a substitution / let it lapse** — constraint lifts → substitute returns to original. §Substitutions; §State Lifecycles (substitution).
27. **Promote a substitution to a version** — accept "make this permanent?" after N applications. §Substitution → version promotion; §Service Interfaces (promoteSubstitutionToVersion).

### Adaptation pipeline interaction
28. **Trigger import-time adaptation** — the IMPORT job fires automatically on store; extracts fingerprint, usually no change. §Adaptation Pipeline (job sources), §Job flow.
29. **Trigger feedback-driven adaptation** — recipe-specific feedback synchronously enqueues a FEEDBACK job → version or branch proposal. §Job sources (FEEDBACK).
30. **Run re-optimise (data-model change)** — user hits "re-optimise" on one recipe or batches the catalogue after constraints change. §system-overview (Trigger 3); §Service Interfaces (enqueueDataModelChangeJobs).
31. **Run plan-time adaptation** — planner invokes a sync adaptation pre-step; yields substitution (or version if genuinely better) + hints. §Job sources (PLAN_TIME); §Service Interfaces (runPlanTimeJob).

### Approval (pending changes)
32. **View pending changes** — list capped per optimisation budget, with diff + reasoning. §Approval Model; §Service Interfaces (getPendingChanges).
33. **Accept a pending change** — applies the proposed diff → new version (`trigger: adaptation_pipeline`). §Approval Model; §Service Interfaces (acceptPendingChange).
34. **Reject a pending change** — proposal → history. §Approval Model; §Service Interfaces (rejectPendingChange).
35. **Modify-then-accept a pending change** — edit the diff, then accept → new version with user edits. §Approval Model; §Service Interfaces (modifyPendingChange).
36. **Discuss/refine a proposal via the suggestion box** — conversational AI box alongside the diff to refine before accepting. §Pending changes (suggestion box).
37. **View dismissed/expired proposals** — "show dismissed" history including superseded/expired. §Expiry; §Pending change supersession.

### Catalogue lifecycle
38. **Promote a system recipe to user catalogue** — one-tap copy a system recipe into the curated library. §System catalogue; §Service Interfaces (promoteToUserCatalogue).
39. **Demote a user recipe to system catalogue** — soft delete; data preserved. §User catalogue (demote).
40. **Archive a system recipe (auto)** — system recipe unused 3 months → excluded from planner index, retained. §System catalogue; §Service Interfaces (archiveSystemRecipe).
41. **Promote (restore) an archived recipe back to user catalogue** — one tap from ARCHIVED back to ACTIVE/user. §State Lifecycles (recipe).
42. **Resolve a deduplication conflict on import** — choose merge / import-as-variant-branch / import-anyway. §Recipe deduplication.

### Rating
43. **Rate a recipe (quick)** — default one-tap `taste` rating on the current version. §Multi-dimensional rating.
44. **Rate in detail** — supply taste / effort_worth_it / portion_fit / repeat_value. §Multi-dimensional rating.

## 4. State Models

### 4.1 Recipe lifecycle
```
CREATED (import | manual | generation | discovery)
   │  import-time adaptation runs; fingerprint extracted
   ▼
ACTIVE  ── linear versions on current branch (manual edits, accepted proposals, system writes, feedback)
   │  ├─ user demotes a user recipe → (soft) lands in system catalogue, data preserved
   │  └─ system recipe unused 3 months (no feedback, no promotion, not in any plan)
   ▼
ARCHIVED (excluded from planner index, retained in storage; NO hard delete)
   │
   └─ user promotes back to user catalogue (one tap) → ACTIVE
```
Cross-cutting: a recipe may carry `nutrition_status` ∈ {calculated, pending, partial} and `data_quality` ∈ {user_verified, imported, ai_generated, web_discovered} — these are attributes, not lifecycle states.

**Illegal / disallowed transitions (→ error pathways):**
- No hard delete of any recipe (only demote/archive). 
- Pipeline may NOT auto-apply to a user-catalogue recipe (always pending change). 
- A version must never change the character fingerprint (if it would, it must be a branch).

### 4.2 Pending change lifecycle
```
PENDING (created by pipeline)
   ├─ user accepts            → ACCEPTED  → new version
   ├─ user rejects            → REJECTED  → history
   ├─ user modifies+accepts   → MODIFIED  → new version with user edits
   ├─ superseded (newer proposal, same (recipe, dimension)) → SUPERSEDED → history
   └─ 14 days elapsed         → EXPIRED   → history
```
**Illegal transitions:** acting on an already-resolved (accepted/rejected/superseded/expired) proposal; two PENDING proposals in the same `(recipe, dimension)` simultaneously (unique constraint serialises — second supersedes).

### 4.3 Substitution lifecycle
```
ACTIVE (applied in one or more plans)
   ├─ constraint lifted / user reverts        → INACTIVE (retained in history)
   └─ applied N times (default 3) w/o revert → prompt surfaced → (user confirms) → PROMOTED (overlay → version)
```
**Illegal transitions:** promoting a substitution never applied; double-promoting an already-PROMOTED overlay.

### 4.4 Adaptation job lifecycle (system-internal, surfaced to user only via outcomes/traces)
```
PENDING → RUNNING → DONE | FAILED
```
Outcomes per catalogue/source: user→pending change; system→direct write+version; plan-time→substitution overlay. FAILED leaves the recipe unchanged.

---

## 5. Pathways

> Categories: **Happy** (default success), **Alternate** (valid non-default), **Error** (validation/not-found/unauthorized/conflict/illegal-transition), **Edge** (empty/huge/boundary/duplicate/concurrent). Cross-module touchpoints (web fetch, USDA/Nutrition, AI steps, Feedback, Planner) are noted; they will be fully detailed in their own domain files + the cross-journey file.

### Create / Import

#### RCP-01 — Create a recipe manually
- **Category:** Happy
- **Actor:** Primary user
- **Preconditions:** Authenticated; user catalogue may be empty or populated.
- **Action:** Fill the recipe form (name, structured ingredients with units/prep/optional flag, ordered method steps, metadata: servings, prep/cook/total time, equipment, stores-well, packable, cuisine, meal types) and save.
- **Expected outcome:** Recipe stored in user catalogue with `data_quality: user_verified`; ingredients mapped to USDA (nutrition derived); AI infers metadata tags; import-time adaptation job runs and extracts the character fingerprint; recipe enters ACTIVE.
- **Variations:** with vs without image; metric vs imperial units; with vs without optional-flagged ingredients; minimal vs rich metadata; ingredient that maps cleanly vs one that the mapper can't place (→ partial nutrition).
- **HLD ref:** design/recipe-system.md §Import Pipeline (manual entry), §Recipe Data Model, §Service Interfaces.
- **Notes:** Cross-module: Nutrition (USDA mapping) and AI tag inference. Self-contained data + self-scoped assertions straightforward (single new recipe id).

#### RCP-02 — Manual create with missing required fields
- **Category:** Error
- **Actor:** Primary user
- **Preconditions:** Authenticated.
- **Action:** Submit a manual recipe missing a required field (no name, empty ingredient list, or no method steps).
- **Expected outcome:** Validation rejects; nothing stored; clear field-level error.
- **Variations:** missing name; zero ingredients; zero method steps; ingredient with quantity but no unit; negative/zero quantity; non-positive servings. `[HLD-GAP]` — the HLD never enumerates which recipe fields are mandatory or their validation rules; flagged as a finding.
- **HLD ref:** design/recipe-system.md §Recipe Data Model (implied required structure).
- **Notes:** No external deps. The exact required-field set is unspecified — test asserts "rejected" but the precise rule list is a GAP.

#### RCP-03 — Import a recipe from a URL
- **Category:** Happy
- **Actor:** Primary user
- **Preconditions:** Authenticated; reachable recipe web page.
- **Action:** Provide a recipe URL to import.
- **Expected outcome:** AI extracts the page into internal structured format; external nutrition data discarded; ingredients USDA-mapped (per-ingredient confidence); tags inferred; hard-constraint filter runs (violations flagged, not rejected); stored in user catalogue with `data_quality: imported`; `source.type=imported`, `source.url` retained; IMPORT job runs → fingerprint extracted; nutrition recalculated internally.
- **Variations:** page with vs without image (image stored as URL); page with embedded nutrition (must be discarded); all ingredients map cleanly vs some below 0.7 confidence (→ `needs_review` + `nutrition_status: partial`); page that flags an allergy/dietary violation for a household member (flagged, still stored).
- **HLD ref:** design/recipe-system.md §Import Pipeline (URL import), §USDA mapping confidence.
- **Notes:** Cross-module: web fetch (external), Nutrition (USDA), AI extraction + tagging, Hard Constraint Filter. Flagship journey starts here (see RCP-60).

#### RCP-04 — URL import: unreachable URL
- **Category:** Error
- **Actor:** Primary user
- **Preconditions:** Authenticated; the URL cannot be fetched (404, timeout, DNS).
- **Action:** Submit an unreachable URL.
- **Expected outcome:** Fail fast with a clear error; NO stored partial recipe.
- **Variations:** 404; connection timeout; invalid/malformed URL string; non-recipe page (e.g. a login wall). `[HLD-GAP]` — HLD distinguishes "unreachable" (fail fast) from "garbage extraction" (store with needs_review); a reachable-but-non-recipe page sits between and isn't explicitly classified.
- **HLD ref:** design/recipe-system.md §Failure handling (URL unreachable).
- **Notes:** External web-fetch dependency; assert no catalogue write occurred.

#### RCP-05 — URL import: AI extraction produces garbage
- **Category:** Error / Edge
- **Actor:** Primary user
- **Preconditions:** Authenticated; page reachable but extraction yields missing ingredients / nonsensical method.
- **Action:** Submit such a URL.
- **Expected outcome:** Recipe IS stored with a `needs_review` flag and `data_quality: imported` — never silently discarded; user can correct.
- **Variations:** missing ingredients; nonsensical method ordering; foreign-language page (extract what's possible, untranslated steps flagged for review).
- **HLD ref:** design/recipe-system.md §Failure handling (garbage, foreign-language).
- **Notes:** Distinct from RCP-04 — here a partial IS persisted. Cross-module: AI extraction.

#### RCP-06 — URL import: USDA mapping fails for all ingredients
- **Category:** Edge
- **Actor:** Primary user
- **Preconditions:** Authenticated; page extracts fine but no ingredient maps to USDA.
- **Action:** Import the URL.
- **Expected outcome:** Stored with `nutrition_status: pending`; planner may still use it but flags "nutrition incomplete."
- **Variations:** all unmapped (pending) vs some unmapped (partial, RCP-03 variation) — boundary between the two states.
- **HLD ref:** design/recipe-system.md §Failure handling (USDA mapping fails for all).
- **Notes:** Cross-module: Nutrition. Assert nutrition_status state, not numbers.

#### RCP-07 — Batch quick-start import of favourite URLs
- **Category:** Alternate
- **Actor:** Primary user
- **Preconditions:** Onboarding flow; user catalogue empty.
- **Action:** Paste up to ~5 recipe URLs to seed the library.
- **Expected outcome:** Each URL runs through the URL-import pipeline; successful ones seed the user catalogue; this also bootstraps taste-profile signal (preference cross-module).
- **Variations:** all 5 succeed; mix of success + unreachable + garbage (per-URL outcome, batch not aborted); fewer than 5; duplicate URLs in the batch; two URLs that are near-duplicate recipes (dedup, RCP-30).
- **HLD ref:** design/recipe-system.md §Bootstrapping (quick-start). `[HLD-GAP]` — partial-batch semantics (does one bad URL abort the batch?) are not stated; assumed per-URL independent but flagged.
- **Notes:** Cross-module: web fetch, Nutrition, Preference bootstrap. Multiple new recipe ids to assert.

#### RCP-08 — Upload an image to a manual recipe
- **Category:** Alternate
- **Actor:** Primary user
- **Preconditions:** Authenticated; creating or editing a manual recipe.
- **Action:** Upload an image file with the recipe.
- **Expected outcome:** Image attached and stored (file for manual entry; URL for imports); recipe fully functional with or without it.
- **Variations:** no image (still valid); valid image; oversized/unsupported file `[HLD-GAP]` (no size/format constraints stated); AI-generated recipe expecting no image (v1 generates none).
- **HLD ref:** design/recipe-system.md §Recipe images.
- **Notes:** Image constraints unspecified — flagged. Self-contained.

#### RCP-09 — Trigger AI recipe generation against a brief
- **Category:** Happy
- **Actor:** Adaptation Pipeline (on planner gap) / Primary user (if a user-initiated generate is exposed)
- **Preconditions:** A constraint brief exists (e.g. "high-protein weeknight meal under 30 mins").
- **Action:** Request generation against the brief.
- **Expected outcome:** Recipe created in system catalogue with `data_quality: ai_generated`; ingredients USDA-mapped; tags inferred; hard-constraint filter run; no image in v1; IMPORT job runs → fingerprint extracted.
- **Variations:** tight brief (few candidates) vs loose; brief that conflicts with a hard constraint (generation must respect/flag it); user explicitly saves a generated recipe into the user catalogue (→ promote, RCP-31).
- **HLD ref:** design/recipe-system.md §Import Pipeline (AI generation); system-overview.md §Recipe Engine (Three sources). `[HLD-GAP]` — whether the user can directly invoke generation (vs only the planner) is not explicit.
- **Notes:** Cross-module: AI generation, Nutrition. System catalogue → direct write, no approval.

#### RCP-10 — Online discovery run (weekly or gap-triggered)
- **Category:** Happy
- **Actor:** Scheduler / Adaptation Pipeline
- **Preconditions:** Discovery cadence reached, or planner gap triggers it.
- **Action:** AI searches the web, hard-filters by constraints, scores against preferences, runs top results through URL import.
- **Expected outcome:** Surviving recipes stored in system catalogue with `data_quality: web_discovered` (lowest trust → visual indicator in plan); nutrition recalculated; dedup runs against existing catalogue.
- **Variations:** weekly scheduled vs gap-triggered; zero results survive the filter (empty — no writes); discovery finds near-duplicates of existing recipes (dedup, RCP-30); a result fails URL import (per-result, doesn't abort the run).
- **HLD ref:** design/recipe-system.md §Import Pipeline (online discovery); system-overview.md §Recipe Engine.
- **Notes:** Cross-module: web search/fetch, Nutrition, Preference (scoring). System actor; observable via new system-catalogue rows.

### Read / Query

#### RCP-11 — View a recipe by ID
- **Category:** Happy
- **Actor:** Primary user
- **Preconditions:** Recipe exists.
- **Action:** Open the recipe detail.
- **Expected outcome:** Full recipe returned (ingredients, method, metadata, fingerprint, nutrition, rating, data_quality, source, timestamps), reflecting the current version on the current branch.
- **Variations:** user vs system vs archived recipe; recipe with partial/pending nutrition (status surfaced); low-trust recipe (indicator).
- **HLD ref:** design/recipe-system.md §Service Interfaces (getById).
- **Notes:** Self-contained read.

#### RCP-12 — View a recipe that does not exist
- **Category:** Error
- **Actor:** Primary user
- **Preconditions:** No recipe with the given id.
- **Action:** Request a non-existent recipe id.
- **Expected outcome:** Not-found error; nothing returned.
- **Variations:** never-existed id; an id that was demoted (still readable, just in system catalogue) — distinguish "moved" from "missing". `[HLD-GAP]` — visibility of system-catalogue recipes to a normal user query is not spelled out.
- **HLD ref:** design/recipe-system.md §Service Interfaces (getById).
- **Notes:** No hard delete means "missing" should be genuinely unknown ids only.

#### RCP-13 — View the recipe index / list with filters
- **Category:** Happy
- **Actor:** Primary user
- **Preconditions:** Catalogue has recipes.
- **Action:** Open the library list applying a filter.
- **Expected outcome:** Compact list using `aggregate` rating; low-trust recipes carry a visual indicator; archived/system recipes excluded from the planner index (but list visibility per filter).
- **Variations:** empty library (cold start — nothing to show); filter by cuisine / meal type / data_quality / packable / stores-well; huge library (~200 user + ~1000 system) pagination/boundary; filter matching zero recipes.
- **HLD ref:** design/recipe-system.md §Service Interfaces (getRecipeIndex); §Data quality; §Data Volumes.
- **Notes:** `[HLD-GAP]` — exact filterable fields & pagination not enumerated. Self-scoped if test seeds its own recipes.

#### RCP-14 — Search recipes by criteria
- **Category:** Happy
- **Actor:** Primary user
- **Preconditions:** Catalogue populated.
- **Action:** Search with criteria.
- **Expected outcome:** Matching recipes returned.
- **Variations:** match-many; match-one; match-none (empty result, not error); search spanning both catalogues vs user-only. `[HLD-GAP]` — search criteria fields and scope unspecified.
- **HLD ref:** design/recipe-system.md §Service Interfaces (search).
- **Notes:** Self-scoped.

#### RCP-15 — View version history for a branch
- **Category:** Happy
- **Actor:** Primary user
- **Preconditions:** Recipe with ≥1 version.
- **Action:** Open version history for a branch.
- **Expected outcome:** Ordered list of versions, each with structured diff, change_reason, trigger, created_by, trace_id.
- **Variations:** single-version recipe; many versions (~5 avg, boundary at large counts); request history for a non-existent branch (error); request for the non-default branch.
- **HLD ref:** design/recipe-system.md §Versioning; §Service Interfaces (getVersionHistory).
- **Notes:** All versions retained — history never truncates.

#### RCP-16 — Compare two versions
- **Category:** Happy
- **Actor:** Primary user
- **Preconditions:** Recipe with ≥2 versions.
- **Action:** Select two versions to compare.
- **Expected outcome:** Clean structured diff (ingredients/method/metadata adds/mods/removes).
- **Variations:** adjacent versions; non-adjacent; compare a version with its parent; compare across branches `[HLD-GAP]` (cross-branch diff semantics unstated); compare a version to itself (degenerate — empty diff).
- **HLD ref:** design/recipe-system.md §Versioning (changes diff).
- **Notes:** Pure read.

#### RCP-17 — View branches / substitutions / fingerprint / nutrition
- **Category:** Happy
- **Actor:** Primary user
- **Preconditions:** Recipe exists.
- **Action:** Open the branches list, substitutions list, character fingerprint, or nutrition view.
- **Expected outcome:** Branches with label/reason/branch-point; substitutions (active + history) with reason/constraint_ref; fingerprint per branch; nutrition per serving with status.
- **Variations:** recipe with no branches (just main); recipe with no substitutions; fingerprint requested for a branch that doesn't exist (error); nutrition still `pending`/`partial`.
- **HLD ref:** design/recipe-system.md §Service Interfaces (getBranches, getSubstitutions, getFingerprint, getNutrition).
- **Notes:** Reads; nutrition is Nutrition-owned but stored here.

#### RCP-18 — View the adaptation quality dashboard
- **Category:** Happy
- **Actor:** Primary user
- **Preconditions:** Some adaptation history exists.
- **Action:** Open the quality dashboard.
- **Expected outcome:** Accept/reject/modify rates, rating delta per version, revert rate, per-prompt-version aggregates, adaptation volume by source — surfaced, no automatic action in v1.
- **Variations:** no history yet (empty dashboard); rich history; traces older than 6 months (raw rows gone, only aggregates remain).
- **HLD ref:** design/recipe-system.md §Observability (quality dashboard, retention).
- **Notes:** Cross-cutting observability. `[HLD-GAP]` — whether the full trace per job is user-visible vs dev-only is partly ambiguous ("Surfaced to the user (and dev)").

### Manual edit

#### RCP-19 — Manually edit a recipe
- **Category:** Happy
- **Actor:** Primary user
- **Preconditions:** Recipe in user catalogue, ACTIVE.
- **Action:** Edit ingredient(s)/method/metadata directly and save.
- **Expected outcome:** Edit always permitted (no approval); creates a new version on the current branch; nutrition recalculated (cross-module); if the edit touches ingredients a recipe-evolved signal fires.
- **Variations:** edit an ingredient quantity; add/remove an ingredient; reorder/edit a method step; change metadata only (e.g. servings — see RCP-20 recalc); edit that would contradict the fingerprint `[HLD-GAP]` (manual edits aren't gated by the version-vs-branch classifier — is a character-breaking manual edit allowed to stay a version? unstated).
- **HLD ref:** design/recipe-system.md §Two Catalogues; §Guardrails (silently change nutrition → recalculation); system-overview.md §Manual direct edits.
- **Notes:** Cross-module: Nutrition recalculation. Core of the flagship journey (RCP-60).

#### RCP-20 — Manual edit triggers nutrition recalculation + log
- **Category:** Happy
- **Actor:** Primary user
- **Preconditions:** Recipe with calculated nutrition.
- **Action:** Edit an ingredient (quantity or swap) on a user recipe.
- **Expected outcome:** New version created; recipe-evolved signal published → Nutrition Engine recalculates the new version's nutrition; the change is recorded (version diff + change_reason + recalculation is observable on the new version).
- **Variations:** quantity change; ingredient swap; adding an ingredient that needs USDA mapping (may go partial); editing an ingredient the user previously nutrition-overrode (override preserved, RCP-22).
- **HLD ref:** design/recipe-system.md §Guardrails; §Events (RecipeEvolvedEvent).
- **Notes:** Cross-module: Nutrition (recalc) — detailed in nutrition domain + cross-journey file. The "recorded in a log" leg of the flagship journey.

#### RCP-21 — Correct a flagged ingredient mapping
- **Category:** Alternate
- **Actor:** Primary user
- **Preconditions:** Recipe with ≥1 ingredient flagged `needs_review` (confidence < 0.7).
- **Action:** Use the one-tap correct flow to fix the ingredient's USDA mapping.
- **Expected outcome:** Mapping corrected; the correction feeds the Nutrition Model cache (similar recipes benefit); recipe's `nutrition_status` updates (partial → calculated when all resolved); nutrition recalculated.
- **Variations:** correct one of several flagged; correct the last flag (status flips to calculated); correct a mapping to another low-confidence value `[HLD-GAP]` (re-flag behaviour unstated).
- **HLD ref:** design/recipe-system.md §USDA mapping confidence.
- **Notes:** Cross-module: Nutrition cache write. Self-scoped assertion on nutrition_status.

#### RCP-22 — Manually override a nutrition value
- **Category:** Alternate
- **Actor:** Primary user
- **Preconditions:** Recipe with calculated nutrition.
- **Action:** Override a specific nutrition value manually.
- **Expected outcome:** Override preserved against future recalculations (the documented exception to "always recalculated internally").
- **Variations:** override one macro; subsequent ingredient edit must NOT clobber the override; later remove the override (revert to calculated) `[HLD-GAP]` (no described un-override flow).
- **HLD ref:** system-overview.md §Data quality and nutrition ownership (override exception).
- **Notes:** Cross-module: Nutrition. The override-vs-recalc precedence is a key assertion; un-override path is a GAP.

### Versioning

#### RCP-23 — Create a new version (linear improvement)
- **Category:** Happy
- **Actor:** Primary user (manual) / Adaptation Pipeline (accepted proposal/system write)
- **Preconditions:** Recipe ACTIVE on a branch.
- **Action:** Apply a linear improvement (seasoning tweak, portion scaling, method fix) on the current branch.
- **Expected outcome:** New version with `parent_version_id` = prior current; structured diff + change_reason + trigger; fingerprint UNCHANGED; recipe-evolved signal fires.
- **Variations:** trigger = manual edit / feedback / adaptation_pipeline / substitution_promotion / data_model_change; change that should be a branch but is attempted as a version (must reclassify/reject — RCP-26 territory).
- **HLD ref:** design/recipe-system.md §Versioning; §Service Interfaces (createVersion).
- **Notes:** Fingerprint-stability assertion is important here.

#### RCP-24 — Revert to a prior version
- **Category:** Happy
- **Actor:** Primary user
- **Preconditions:** Recipe with ≥2 versions; a newer version underperformed.
- **Action:** Revert the active recipe to an earlier version.
- **Expected outcome:** The chosen prior version becomes active again; history retained (all versions kept); pipeline quality dashboard picks up the revert (revert-rate metric).
- **Variations:** revert one step back; revert several steps; revert then re-version forward; revert on a non-main branch; revert to the already-current version (degenerate / no-op or error). 
- **HLD ref:** design/recipe-system.md §Versioning; §Service Interfaces (revertToVersion); §Failure modes (feedback for a reverted version).
- **Notes:** `[HLD-GAP]` — whether revert creates a new version pointer or moves a "current" marker is unspecified; assert active version, not mechanism.

#### RCP-25 — Revert to a non-existent / foreign version
- **Category:** Error
- **Actor:** Primary user
- **Preconditions:** Recipe exists.
- **Action:** Attempt to revert to a version id that doesn't exist or belongs to another recipe/branch.
- **Expected outcome:** Rejected with a clear error; active version unchanged.
- **Variations:** unknown version id; version id from a different recipe; version id from a sibling branch `[HLD-GAP]` (cross-branch revert legality unstated).
- **HLD ref:** design/recipe-system.md §Service Interfaces (revertToVersion).
- **Notes:** Validation pathway.

### Branching

#### RCP-26 — Create a branch (creative fork)
- **Category:** Happy
- **Actor:** Adaptation Pipeline (typical) / Primary user (if user-initiated branching exposed)
- **Preconditions:** Recipe with a version to fork from.
- **Action:** Fork a variant whose character genuinely differs (protein swap that changes the dish, flavour-direction fork, cooking-method change).
- **Expected outcome:** New branch with `parent_branch`, `branch_point_version`, label, reason; the branch extracts its OWN character fingerprint; it gets its own version history starting at version 1.
- **Variations:** protein swap (chicken→beef); flavour fork (coconut vs tomato); cooking-method change; attempting a branch for a non-character change (seasoning/garnish/portion) — should be a version instead (classification error, RCP-27).
- **HLD ref:** design/recipe-system.md §Branching; §Service Interfaces (createBranch).
- **Notes:** Cross-module: AI fingerprint extraction. `[HLD-GAP]` — whether a user can manually create a branch (vs only the pipeline) is not explicit.

#### RCP-27 — Misclassified change: branch requested for a trivial tweak
- **Category:** Error / Edge
- **Actor:** Adaptation Pipeline / Primary user
- **Preconditions:** A change that does not shift character (seasoning, garnish, portion scaling, method-error fix).
- **Action:** Attempt to record it as a branch.
- **Expected outcome:** Per the classification logic it should be a VERSION, not a branch; pipeline reclassifies. `[HLD-GAP]` — if a *user* manually forces a branch on a trivial change, whether the system blocks or allows it is unspecified.
- **Variations:** garnish addition; portion scaling; method-error fix; seasoning tweak.
- **HLD ref:** design/recipe-system.md §Branching (when NOT to branch); §Classification logic.
- **Notes:** Tests the version-vs-branch boundary as an error/guard.

#### RCP-28 — Promote a divergent branch to a standalone recipe
- **Category:** Alternate
- **Actor:** Primary user
- **Preconditions:** A branch whose divergence (shared-ingredient proportion + method similarity) has crossed the threshold; system surfaces the prompt.
- **Action:** Accept "promote to a standalone recipe?"
- **Expected outcome:** Branch copied out as a new recipe with a `forked_from` reference preserved for history.
- **Variations:** accept the prompt; dismiss the prompt (branch stays linked); divergence exactly at threshold (boundary); promote a branch that's still tightly linked (should not have surfaced — error if forced) `[HLD-GAP]` (manual-trigger of promotion without crossing threshold unstated).
- **HLD ref:** design/recipe-system.md §Branch divergence.
- **Notes:** Cross-module: divergence metric computation. Assert new recipe + forked_from link.

### Substitutions

#### RCP-29 — Create a substitution overlay
- **Category:** Happy
- **Actor:** Adaptation Pipeline (plan-time) / Primary user (if user-initiated substitution exposed)
- **Preconditions:** A recipe version is in/being placed in a plan; a constraint forces a swap (budget, availability, temporary dietary, equipment).
- **Action:** Record a substitution overlay on the version (original → substitute, reason, constraint_ref, temporary flag, applied_in_plans, optional method_overlay).
- **Expected outcome:** Overlay created; base recipe UNCHANGED (overlay applied at plan-time only); substitution enters ACTIVE; no library pollution.
- **Variations:** budget (fillet→rump, method_overlay for longer cook); availability (coriander→parsley); temporary dietary (elimination protocol); equipment (food processor→knife prep); substitution that itself violates a hard constraint (must be rejected by the filter, RCP-43).
- **HLD ref:** design/recipe-system.md §Substitutions; §Service Interfaces (createSubstitution).
- **Notes:** Cross-module: Provisions/budget constraint, Planner (applies overlay). `[HLD-GAP]` — whether a user can author a substitution directly is not explicit (HLD frames it as plan-driven).

#### RCP-30 — Substitution lapses / user reverts
- **Category:** Alternate
- **Actor:** Primary user / system (constraint lifted)
- **Preconditions:** An ACTIVE substitution.
- **Action:** Constraint lifts, or user reverts the substitution.
- **Expected outcome:** Substitution → INACTIVE (retained in history); the original ingredient returns in future plans.
- **Variations:** constraint auto-lifts; explicit user revert; revert a substitution already INACTIVE (idempotent/no-op or error).
- **HLD ref:** design/recipe-system.md §Substitutions; §State Lifecycles (substitution).
- **Notes:** Self-scoped on substitution state.

#### RCP-31 — Promote a substitution to a permanent version
- **Category:** Alternate
- **Actor:** Primary user
- **Preconditions:** A substitution applied N times (default 3 plan weeks) without revert/complaint; system surfaces "make this permanent?"
- **Action:** Accept the prompt (one tap).
- **Expected outcome:** Overlay promoted to a new version on main with `trigger: substitution_promotion`; recipe-evolved signal fires; substitution → PROMOTED.
- **Variations:** accept at exactly N=3 (boundary); dismiss the prompt (substitution stays ACTIVE); promote a substitution that hasn't reached N (should not have surfaced — error if forced); promote an already-PROMOTED overlay (illegal, RCP-33-style).
- **HLD ref:** design/recipe-system.md §Substitution → version promotion; §Service Interfaces (promoteSubstitutionToVersion).
- **Notes:** Boundary on the N=3 threshold is a key test.

### Adaptation pipeline & approval

#### RCP-32 — Feedback-driven adaptation produces a pending change (user catalogue)
- **Category:** Happy
- **Actor:** Feedback System (caller) → Adaptation Pipeline; reviewed by Primary user
- **Preconditions:** A user-catalogue recipe; recipe-specific feedback routed in (e.g. "needs more flavour").
- **Action:** Feedback enqueues a synchronous FEEDBACK job; pipeline reasons (culinary + nutritional + constraint), checks against fingerprint, classifies, validates (hard-constraint filter, character self-check, confidence floor).
- **Expected outcome:** Because it's a user recipe, a PENDING change is written (not a version): diff + reasoning + change_dimension + 14-day expiry; user notified; caller receives trace id (+ pending-change id). No silent mutation.
- **Variations:** classified VERSION vs BRANCH proposal; confidence < 0.5 (flagged for review); feedback that the pipeline decides is NO_CHANGE (nothing queued); feedback on a recipe currently in a batch (FEEDBACK pre-empts the batch — RCP-50).
- **HLD ref:** design/recipe-system.md §Job sources (FEEDBACK), §Job flow, §Approval Model; §Guardrails (never auto-apply to user catalogue).
- **Notes:** Cross-module: Feedback System (sync call), AI reasoning, Hard Constraint Filter. Pipeline interaction is asserted via the resulting pending change.

#### RCP-33 — System-catalogue adaptation applies directly
- **Category:** Happy
- **Actor:** Adaptation Pipeline
- **Preconditions:** A system-catalogue recipe; any adaptation source.
- **Action:** Pipeline proposes a change and validates it.
- **Expected outcome:** Direct write → new version (or branch/substitution per classification), no approval, bypasses the optimisation budget; recipe-evolved signal fires.
- **Variations:** version vs branch vs substitution outcome; confidence < 0.5 (must STILL flag for user review even on system catalogue — exception to direct-write); character self-check fail (never auto-apply).
- **HLD ref:** design/recipe-system.md §System catalogue; §Job flow (apply per policy); §Failure Modes (low confidence flags even system catalogue).
- **Notes:** The confidence-floor exception is a notable non-happy branch of a happy path.

#### RCP-34 — Import-time adaptation job (usually no change)
- **Category:** Happy
- **Actor:** Adaptation Pipeline
- **Preconditions:** A recipe just stored (any of the four entry points).
- **Action:** IMPORT job runs asynchronously.
- **Expected outcome:** Character fingerprint extracted; usually NO_CHANGE; occasionally a version (system) or pending change (user); trace written.
- **Variations:** no-change outcome; small version improvement; user-catalogue import (pending) vs system (direct); import-time adaptation aggressiveness setting `[HLD-GAP]` (Open Question — conservative vs aggressive default unresolved).
- **HLD ref:** design/recipe-system.md §Job sources (IMPORT); §Open Questions (adaptation aggressiveness).
- **Notes:** Cross-module: AI. Aggressiveness default is an explicit open question.

#### RCP-35 — Run re-optimise on one recipe after a data-model change
- **Category:** Happy
- **Actor:** Primary user
- **Preconditions:** A constraint changed (preference/nutrition/budget) and a target recipe exists.
- **Action:** Hit "re-optimise" on the recipe.
- **Expected outcome:** A DATA_MODEL_CHANGE job runs (version to fix violations); user recipe → pending change, system recipe → direct write.
- **Variations:** preference change vs nutrition change vs budget change; recipe already compliant (NO_CHANGE); user vs system catalogue.
- **HLD ref:** design/recipe-system.md §Job sources (DATA_MODEL_CHANGE); system-overview.md §Trigger 3.
- **Notes:** Cross-module: Preference/Nutrition/Provisions (constraint inputs).

#### RCP-36 — Batch re-optimise across the catalogue (data-model change)
- **Category:** Alternate / Edge
- **Actor:** Primary user / system (on DataModelChangedEvent)
- **Preconditions:** A data-model change affecting many recipes.
- **Action:** Trigger a batch re-optimise across the catalogue.
- **Expected outcome:** N jobs enqueued (Batches API, 50% cost, ~24h); adapted recipes written individually; user-catalogue results become pending changes (subject to the 3/week budget), system results apply directly.
- **Variations:** small batch vs large burst (50-200 jobs); partial batch failure (failed jobs retry with exponential backoff next run); a FEEDBACK job arriving mid-batch for a recipe in the batch (batch defers that recipe — RCP-50).
- **HLD ref:** design/recipe-system.md §Job sources, §Failure Modes (batch partial failure), §Concurrency.
- **Notes:** Async/long-running; assert per-recipe outcomes, not batch timing.

#### RCP-37 — Run plan-time adaptation (planner pre-step)
- **Category:** Happy
- **Actor:** Meal Planner (caller) → Adaptation Pipeline
- **Preconditions:** Weekly composition under way; planner needs a recipe adapted to fit the week.
- **Action:** Planner invokes a synchronous plan-time adaptation with plan constraints.
- **Expected outcome:** Returns an AdaptationResult: usually a SUBSTITUTION overlay attached to the plan (or a VERSION if genuinely better), plus reasoning, nutritional notes, planner hints, confidence, trace id; base recipe unchanged for the substitution case.
- **Variations:** substitution outcome (budget swap); version outcome (genuine improvement); NO_CHANGE (planner uses unadapted recipe); planner hints emitted (PREP_LEAD_TIME / ABSORPTION_CONFLICT / NUTRITION_TRADEOFF); user-recipe surfaced change in plan ("I swapped turkey for chicken…").
- **HLD ref:** design/recipe-system.md §Job sources (PLAN_TIME), §Planner hints; system-overview.md §Trigger 4; optimisation-loop.md §Recipe level.
- **Notes:** Cross-module: Planner (sync), Provisions/budget. Pipeline never modifies the plan — emits hints only.

#### RCP-38 — Adaptation job fails entirely
- **Category:** Error
- **Actor:** Adaptation Pipeline
- **Preconditions:** A job running; AI times out / rate-limits / returns malformed tool response.
- **Action:** Job execution fails.
- **Expected outcome:** Recipe UNCHANGED; trace records the failure; for PLAN_TIME, the planner falls back to the unadapted recipe.
- **Variations:** timeout; rate limit; malformed tool response; failure on FEEDBACK (user waiting — error surfaced) vs PLAN_TIME (silent fallback) vs IMPORT (recipe stays as stored).
- **HLD ref:** design/recipe-system.md §Failure Modes (AI task fails entirely).
- **Notes:** Cross-module: AI Service. Assert "no change + trace" rather than any partial write.

#### RCP-39 — Proposed change violates a hard constraint
- **Category:** Error
- **Actor:** Adaptation Pipeline
- **Preconditions:** A job produces a diff that would violate an allergy / dietary identity.
- **Action:** Validation runs the deterministic hard-constraint filter.
- **Expected outcome:** Rejected at validation; logged; retried ONCE with a stronger constraint directive; if it fails again the recipe is flagged for manual review. Never stored.
- **Variations:** retry succeeds; retry also violates (→ manual-review flag); violation on system catalogue (still rejected — AI never trusted for allergy safety).
- **HLD ref:** design/recipe-system.md §Failure Modes; §Guardrails (never violate hard constraints).
- **Notes:** Cross-module: Hard Constraint Filter (deterministic gate). Critical safety pathway.

#### RCP-40 — AI fails the character-preservation self-check
- **Category:** Error / Alternate
- **Actor:** Adaptation Pipeline
- **Preconditions:** A proposed change; the AI's `preserves_character` self-check fails.
- **Action:** Character self-check runs at validation.
- **Expected outcome:** Never auto-applied; if the result is a coherent different dish → propose as a BRANCH; otherwise discard.
- **Variations:** coherent different dish (→ branch proposal); incoherent result (→ discard); on user vs system catalogue.
- **HLD ref:** design/recipe-system.md §Failure Modes; §Guardrails (produce inedible results).
- **Notes:** Cross-module: AI self-check. Boundary between "rejected" and "rerouted to branch."

#### RCP-41 — View pending changes (within the optimisation budget)
- **Category:** Happy
- **Actor:** Primary user
- **Preconditions:** Pipeline has produced ≥1 pending change.
- **Action:** Open the pending-changes list.
- **Expected outcome:** At most 3 surfaced this week (configurable default 3); each with side-by-side diff + reasoning + change_dimension; extra proposals sit in a `confidence × impact` ranking pool.
- **Variations:** 0 pending; exactly 3; >3 (only top 3 by confidence×impact surface); proposals across different dimensions coexisting; system-catalogue proposals (don't count against budget).
- **HLD ref:** design/recipe-system.md §Approval Model (optimisation budget); §Service Interfaces (getPendingChanges).
- **Notes:** Budget cap is a boundary test (3/week).

#### RCP-42 — Accept a pending change
- **Category:** Happy
- **Actor:** Primary user
- **Preconditions:** A PENDING change exists.
- **Action:** Accept it.
- **Expected outcome:** Status → ACCEPTED; new version created (`trigger: adaptation_pipeline`); fingerprint unchanged (unless branch); recipe-evolved signal → nutrition recalculated; the proposal's slot frees in the weekly budget.
- **Variations:** accept a version proposal; accept a branch proposal; accept on a recipe a concurrent edit also touched (optimistic-lock rebase, RCP-49).
- **HLD ref:** design/recipe-system.md §Approval Model; §Service Interfaces (acceptPendingChange).
- **Notes:** Cross-module: Nutrition. New version is the observable outcome.

#### RCP-43 — Reject a pending change
- **Category:** Alternate
- **Actor:** Primary user
- **Preconditions:** A PENDING change exists.
- **Action:** Reject it.
- **Expected outcome:** Status → REJECTED → history; no version created; visible under "show dismissed."
- **Variations:** reject a version vs branch proposal; reject then later a fresh proposal arrives in the same dimension.
- **HLD ref:** design/recipe-system.md §Approval Model; §State Lifecycles (pending change).
- **Notes:** Self-scoped.

#### RCP-44 — Modify-then-accept a pending change
- **Category:** Alternate
- **Actor:** Primary user
- **Preconditions:** A PENDING change exists.
- **Action:** Edit the proposed diff, then accept.
- **Expected outcome:** Status → MODIFIED; new version created with the user's edits; nutrition recalculated.
- **Variations:** small tweak to the diff; substantial rewrite; modify into something that would violate a hard constraint (must be re-validated by the filter `[HLD-GAP]` — whether user-modified diffs re-pass the hard-constraint gate is not explicitly stated, though guardrails imply EVERY adaptation passes the filter).
- **HLD ref:** design/recipe-system.md §Approval Model; §Service Interfaces (modifyPendingChange).
- **Notes:** The re-validation question is a meaningful GAP/safety finding.

#### RCP-45 — Discuss/refine a proposal via the suggestion box
- **Category:** Alternate
- **Actor:** Primary user
- **Preconditions:** A PENDING change open.
- **Action:** Use the conversational AI suggestion box alongside the diff to discuss/refine before accepting.
- **Expected outcome:** Refined proposal the user can then accept/modify/reject.
- **Variations:** refine then accept; refine then reject; refine into a hard-constraint violation (filter still applies). `[HLD-GAP]` — the suggestion-box conversation's persistence/audit is unspecified.
- **HLD ref:** design/recipe-system.md §Pending changes (suggestion box); system-overview.md §Approval UX.
- **Notes:** Cross-module: AI conversational.

#### RCP-46 — Act on an already-resolved pending change
- **Category:** Error
- **Actor:** Primary user
- **Preconditions:** A pending change already ACCEPTED/REJECTED/SUPERSEDED/EXPIRED.
- **Action:** Attempt to accept/reject/modify it.
- **Expected outcome:** Rejected — illegal state transition; clear error; underlying recipe unchanged.
- **Variations:** act on accepted; on rejected; on superseded; on expired.
- **HLD ref:** design/recipe-system.md §State Lifecycles (pending change).
- **Notes:** Illegal-transition pathway.

#### RCP-47 — Pending change supersession (same dimension)
- **Category:** Edge
- **Actor:** Adaptation Pipeline
- **Preconditions:** A PENDING change in dimension D (e.g. salt_level); a newer proposal arrives in dimension D.
- **Action:** New proposal stored.
- **Expected outcome:** Newer supersedes the unreviewed prior (status SUPERSEDED → history, retained); user sees "this replaced an earlier one from <date>"; proposals in different dimensions coexist; the `(recipe, dimension, status=pending)` uniqueness serialises concurrent writers.
- **Variations:** supersession in salt_level while protein coexists; rapid succession of 3 salt proposals (only the latest pending); concurrent same-dimension writers (second wins atomically — RCP-49).
- **HLD ref:** design/recipe-system.md §Pending change supersession; §Concurrency.
- **Notes:** Prevents stacking/staleness. Concurrency-flavoured edge.

#### RCP-48 — Pending change expires after 14 days
- **Category:** Edge
- **Actor:** Scheduler / system clock
- **Preconditions:** A PENDING change created 14 days ago, never reviewed.
- **Action:** 14-day window elapses.
- **Expected outcome:** Status → EXPIRED → history; no longer surfaced; visible under "show dismissed."
- **Variations:** exactly at 14 days (boundary); reviewed at day 13 (no expiry); expired proposal cannot then be accepted (RCP-46).
- **HLD ref:** design/recipe-system.md §Expiry; §State Lifecycles.
- **Notes:** Time-boundary test.

### Concurrency

#### RCP-49 — Concurrent writes to the same recipe (optimistic lock)
- **Category:** Edge
- **Actor:** Adaptation Pipeline + Primary user (or two jobs)
- **Preconditions:** Two writes target the same recipe's current version.
- **Action:** Both attempt to write a new version with the same `parent_version_id`.
- **Expected outcome:** First wins; second is race-rejected, rebases its diff onto the new current version, retries (up to 3 times); after 3 failures the job fails and logs (signal of a loop/stuck edit, not honest contention).
- **Variations:** rebase succeeds on retry 1; succeeds on retry 3 (boundary); fails after 3 (job-fail + log); manual edit in flight holds a 30s advisory lock → pipeline write waits/retries after expiry.
- **HLD ref:** design/recipe-system.md §Concurrency; §Failure Modes (concurrent writes).
- **Notes:** Concurrency core; hard to make fully deterministic in E2E but the rebase-retry contract is assertable.

#### RCP-50 — FEEDBACK job pre-empts a running batch on the same recipe
- **Category:** Edge
- **Actor:** Feedback System + batch (DATA_MODEL_CHANGE)
- **Preconditions:** A recipe is being processed in a batch; a FEEDBACK job arrives for it.
- **Action:** Both contend for the recipe.
- **Expected outcome:** Batch DEFERS that recipe; FEEDBACK processes first (user waiting); the batch re-evaluates fit for that recipe after the feedback job completes.
- **Variations:** feedback arrives mid-batch; feedback arrives just as the batch picks up the recipe (boundary).
- **HLD ref:** design/recipe-system.md §Concurrency (batch vs feedback).
- **Notes:** Ordering guarantee; cross-module Feedback.

#### RCP-51 — Single-flight per recipe adaptation
- **Category:** Edge / Error
- **Actor:** Adaptation Pipeline / caller
- **Preconditions:** An adaptation is already running for a recipe.
- **Action:** A second adaptation invocation for the same recipe scope arrives.
- **Expected outcome:** Second invocation rejected — the recipe-scale loop is single-flight per scope (one adaptation per recipe at a time).
- **Variations:** same-source duplicate; different-source overlap (defer vs reject interplay with RCP-50). `[HLD-GAP]` — interaction between "single-flight reject" (optimisation-loop) and "batch defer / feedback pre-empt" (recipe-system) isn't fully reconciled across the two docs.
- **HLD ref:** optimisation-loop.md §Failure Modes (concurrent invocations); design/recipe-system.md §Concurrency.
- **Notes:** Notable cross-doc consistency GAP worth surfacing.

### Deduplication

#### RCP-52 — Deduplication conflict on import
- **Category:** Edge
- **Actor:** Primary user (resolving) / Adaptation pipeline (detecting on discovery)
- **Preconditions:** Importing/discovering a recipe whose normalised ingredient-set hash collides above threshold (default 80% overlap + method length within ±20%) with an existing recipe.
- **Action:** System surfaces the merge/variant/import-anyway dialog; user chooses.
- **Expected outcome:** "Merge" consolidates; "import as variant branch" creates a branch; "import anyway" stores a separate recipe.
- **Variations:** exactly at the 80% threshold (boundary); discovery near-duplicate (auto-handled vs surfaced `[HLD-GAP]` — who resolves a discovery-time dedup, user or system, is unstated); merge vs branch vs import-anyway each as a distinct outcome; duplicate URL re-import.
- **HLD ref:** design/recipe-system.md §Recipe deduplication; §Open Questions (dedup threshold).
- **Notes:** Threshold is an acknowledged open question; resolver actor for discovery-time dedup is a GAP.

### Catalogue lifecycle

#### RCP-53 — Promote a system recipe to the user catalogue
- **Category:** Happy
- **Actor:** Primary user
- **Preconditions:** A system-catalogue recipe exists.
- **Action:** One-tap promote it to the user library.
- **Expected outcome:** Recipe now in the user catalogue (curated library); future pipeline changes to it require approval (approval model flips from direct-write to pending).
- **Variations:** promote a discovered recipe; promote a generated recipe; promote an archived recipe (RCP-56); promote one that's a near-duplicate of an existing user recipe (dedup interplay). `[HLD-GAP]` — whether promotion copies or moves the recipe (and what happens to system-catalogue use after) is not fully specified.
- **HLD ref:** design/recipe-system.md §System catalogue; §Service Interfaces (promoteToUserCatalogue).
- **Notes:** Approval-model flip is the key assertion.

#### RCP-54 — Demote a user recipe to the system catalogue
- **Category:** Alternate
- **Actor:** Primary user
- **Preconditions:** A user-catalogue recipe exists.
- **Action:** Demote it (soft delete).
- **Expected outcome:** Recipe moves to the system catalogue; data preserved; no hard delete; pipeline may now adapt it freely (direct write).
- **Variations:** demote a recipe with versions/branches/substitutions (all preserved); demote then re-promote (RCP-53); demote a recipe currently in an active plan `[HLD-GAP]` (effect on the live plan unstated).
- **HLD ref:** design/recipe-system.md §User catalogue (demote).
- **Notes:** Soft-delete semantics; assert preservation.

#### RCP-55 — System recipe auto-archived after 3 months unused
- **Category:** Edge
- **Actor:** Scheduler / system clock
- **Preconditions:** A system recipe with no feedback, no promotion, not in any plan for 3 months.
- **Action:** Archival sweep runs.
- **Expected outcome:** Recipe → ARCHIVED: excluded from the planner index, retained in storage, no hard delete.
- **Variations:** exactly at 3 months (boundary); a recipe used once 2 months ago (NOT archived); a recipe rated long ago (retained indefinitely); user-catalogue recipe (never auto-archived — only system).
- **HLD ref:** design/recipe-system.md §System catalogue; §State Lifecycles; system-overview.md §System catalogue pruning.
- **Notes:** Time-boundary; assert exclusion-from-index + retention.

#### RCP-56 — Promote (restore) an archived recipe
- **Category:** Alternate
- **Actor:** Primary user
- **Preconditions:** An ARCHIVED recipe.
- **Action:** Promote it back to the user catalogue (one tap).
- **Expected outcome:** Recipe → ACTIVE in the user catalogue; re-included in the planner index.
- **Variations:** restore a long-archived recipe; restore one that was archived then would re-qualify for archival (cycle).
- **HLD ref:** design/recipe-system.md §State Lifecycles (recipe, promote back).
- **Notes:** Reverse of RCP-55.

#### RCP-57 — Attempt to hard-delete a recipe
- **Category:** Error
- **Actor:** Primary user
- **Preconditions:** Any recipe.
- **Action:** Attempt a permanent delete.
- **Expected outcome:** Not supported — only demote (user) or archive (system); data always preserved. `[HLD-GAP]` — the HLD asserts "no hard delete" but never says whether the UI exposes a delete affordance that maps to demote/archive, or rejects outright.
- **Variations:** delete a user recipe (→ demote semantics?); delete a system recipe (→ archive semantics?).
- **HLD ref:** design/recipe-system.md §State Lifecycles ("no hard delete"); §Versioning (keep all versions).
- **Notes:** Confirms the no-destruction invariant.

### Rating

#### RCP-58 — Rate a recipe (quick, taste only)
- **Category:** Happy
- **Actor:** Primary user / Household member (own meal)
- **Preconditions:** A recipe version the user has cooked/eaten.
- **Action:** One-tap `taste` rating.
- **Expected outcome:** Taste score recorded against the current version (ratings are per version); count increments; aggregate recomputed; `last_rated` updated.
- **Variations:** first rating (count 1); subsequent rating (running blend); rating after revert (which version is "current"? — ties to RCP-24); household member rating their own meal.
- **HLD ref:** design/recipe-system.md §Multi-dimensional rating; §Versioning (ratings per version).
- **Notes:** `[HLD-GAP]` — how multiple ratings combine into score/count (mean? recency-weighted?) is not specified.

#### RCP-59 — Rate in detail (four dimensions)
- **Category:** Alternate
- **Actor:** Primary user
- **Preconditions:** A recipe version eaten.
- **Action:** Open "rate in detail" and supply taste / effort_worth_it / portion_fit / repeat_value.
- **Expected outcome:** All supplied dimensions recorded per version; aggregate (weighted blend) recomputed; downstream signals enabled (low effort_worth_it+high taste → method-simplification proposal, etc.).
- **Variations:** all four supplied; partial detail (only some dimensions); v1 may ship only taste + effort_worth_it `[HLD-GAP]` (Open Question — which dimensions are live in v1 is undecided); out-of-range score (>100 or <0 → validation error).
- **HLD ref:** design/recipe-system.md §Multi-dimensional rating; §Open Questions (rating dimensions).
- **Notes:** Drives later adaptation; dimension set for v1 is an open question.

### Flagship cross-module journey

#### RCP-60 — Web-found recipe → internal format → nutrition derived → user edits an ingredient → recalculation → recorded in a log
- **Category:** Happy (flagship end-to-end)
- **Actor:** Primary user (+ Adaptation Pipeline, Nutrition Engine as system actors)
- **Preconditions:** Authenticated; a reachable recipe web page (found via discovery or supplied directly).
- **Action (sequence):**
  1. Import the recipe from the web (URL import or a discovery result run through URL import).
  2. System converts the page to the internal structured format; external nutrition is discarded.
  3. Nutrition is derived internally by mapping ingredients to USDA *(cross-module: Nutrition)*.
  4. Import-time adaptation job extracts the character fingerprint.
  5. User manually edits an ingredient (e.g. changes a quantity or swaps an ingredient).
  6. The edit creates a new version and triggers a nutrition recalculation *(cross-module: Nutrition, via recipe-evolved signal)*.
  7. The change is recorded in a log (version diff + change_reason + adaptation/decision trace).
- **Expected outcome:** A user-catalogue recipe with `data_quality: imported`, internally calculated nutrition (recalculated after the edit), a new version capturing the manual edit, and an auditable record of the recalculation/decision.
- **Variations:** discovery-sourced (`web_discovered`) vs direct URL (`imported`); edit a quantity vs swap an ingredient; edited ingredient maps cleanly vs goes `partial`/`pending`; recipe with vs without an image; the imported recipe flags a household allergy (flagged, not rejected) before the edit.
- **HLD ref:** design/recipe-system.md §Import Pipeline, §USDA mapping confidence, §Versioning, §Guardrails (recalculation), §Events; system-overview.md §Recipe Engine.
- **Notes:** CROSS-MODULE touchpoints — step 3 (nutrition derivation) and step 6 (recalculation) are owned by the **Nutrition** domain and detailed there + in the cross-journey file. The "recorded in a log" leg spans the recipe version diff AND the adaptation/decision trace. This is the integration backbone; assertions span recipe state + nutrition state + a log entry.

---

## Appendix — `[HLD-GAP]` findings (consolidated)

| # | Gap | Pathway |
|---|---|---|
| G1 | Mandatory recipe fields & validation rules never enumerated. | RCP-02 |
| G2 | Reachable-but-non-recipe page not classified (between unreachable and garbage). | RCP-04 |
| G3 | Partial-batch semantics for quick-start import (does one bad URL abort?). | RCP-07 |
| G4 | Image file size/format constraints unspecified. | RCP-08 |
| G5 | Whether the user can directly invoke AI generation (vs only the planner). | RCP-09 |
| G6 | Visibility of system-catalogue recipes to a normal user read/query. | RCP-12, RCP-14 |
| G7 | Exact filterable fields & pagination for the index/search. | RCP-13, RCP-14 |
| G8 | Cross-branch version diff/revert legality. | RCP-16, RCP-25 |
| G9 | Whether a character-breaking *manual* edit is allowed to remain a version. | RCP-19, RCP-27 |
| G10 | Un-override flow for a manual nutrition override. | RCP-22 |
| G11 | Revert mechanism (new version vs moving a "current" marker). | RCP-24 |
| G12 | Whether a user can manually create a branch / force-promote a non-divergent branch. | RCP-26, RCP-28 |
| G13 | Whether a user can author a substitution directly (HLD frames it as plan-driven). | RCP-29 |
| G14 | Whether user-modified pending-change diffs re-pass the hard-constraint filter. | RCP-44 |
| G15 | Suggestion-box conversation persistence/audit. | RCP-45 |
| G16 | Single-flight reject vs batch-defer/feedback-pre-empt reconciliation across docs. | RCP-51 |
| G17 | Who resolves a discovery-time dedup (user vs system, auto). | RCP-52 |
| G18 | Promotion copy-vs-move semantics; effect on system-catalogue use after promote. | RCP-53 |
| G19 | Effect on a live plan of demoting a recipe currently in it. | RCP-54 |
| G20 | Whether a delete affordance exists and maps to demote/archive. | RCP-57 |
| G21 | How multiple ratings combine (mean? recency-weighted?). | RCP-58 |
| G22 | Which rating dimensions are live in v1 (Open Question). | RCP-59 |
| G23 | Import-time adaptation aggressiveness default (Open Question). | RCP-34 |
