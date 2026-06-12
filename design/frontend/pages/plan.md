# Page spec — Plan (`/plan` + `/plan/generate`)

The contract-complete specification: every endpoint this page consumes, and the UI
that each request field and response field demands. A control exists for every
writable field; a display home exists for every returned field (or an explicit
"not on this page" entry). Companion docs: [../ia.md](../ia.md),
[../design-language.md](../design-language.md). Template: [nutrition.md](nutrition.md)
(the pilot).

The generation flow (`/plan/generate`) is in-page state of this surface, not a
separate module — it shares the plan cache and is specified here as §4.

---

## 1. Intent (HLD)

From `design/meal-planner.md` + `lld/planner.md` (user-facing semantics only):

- **Plans are immutable, generations replace** — "every regeneration of the same
  week creates a new Plan with `generation = previous + 1` and `replaces_plan_id`
  pointing at the previous one. Old plans are never mutated." Only one ACTIVE plan
  per (household, week).
- **Plan lifecycle** — DRAFT (hidden) → GENERATED (awaiting approval) → ACTIVE →
  SUPERSEDED / COMPLETED / ABANDONED; GENERATED → REJECTED. Terminal states are
  immutable; the page renders every state except DRAFT.
- **Slot states drive pinning** — `planned → cooking → cooked → eaten | skipped`,
  never backwards. "Pinned slots are immutable — eaten/cooking/cooked meals never
  regenerate" and the pin reason is recorded (`pinnedReason`) for the user-facing
  "why didn't this slot change?".
- **Diff preview before any re-optimisation** — "The user sees a 'regenerate plan
  from [day]?' prompt with a diff preview (what changes, what's preserved) and
  confirms before the new plan replaces the active one." Listeners never
  auto-replace; they materialise a *suggestion* the user accepts or rejects.
- **Two-step re-opt confirmation (v1 default)** — accepting a suggestion writes a
  new GENERATED generation; the user then accepts *that plan* to make it ACTIVE
  (lld/planner.md decision #7).
- **No silent degradation** — infeasible constraint sets surface conflicts +
  ranked relaxations *before* generation; "the user always chooses; the planner
  never silently relaxes a hard constraint." If the user declines all
  resolutions, the plan ships flagged `quality_warning: true` with the unmet
  floor / unfilled slot visible in the plan UI.
- **Cost is always shown with confidence** — `cost_estimate_gbp` +
  `cost_confidence` + `stale_ingredient_count` "so the user sees freshness
  alongside the projected total."
- **Revert is copy-forward** — reverting to plan N creates a brand-new generation
  with content copied from N; recipes that now fail current hard constraints are
  stripped and deterministically refilled; unfillable slots ship empty with
  `qualityWarning = true` ("3 ingredients no longer available" warning UX).
- **AI degradation is visible, not silent** — Stage C fallback flags the plan:
  "UI flags 'AI ranking unavailable'" (`aiAugmented = false`); cold-start plans
  carry `cold_start: true` "signalling that quality may be lower and feedback is
  especially valuable."
- **Wall-clock serve times** — each slot carries a nullable `mealTime` override
  (planner-01m); the *effective* time resolves slot override → lifestyle-config
  meal schedule → slot-kind default. Resolution is server-side only in an
  internal projection (see §8 open questions).

## 2. Endpoint inventory

The planner module exposes 15 endpoints; 14 are consumed by this page. 1 is not
for this page (§7), plus two cross-module support reads.

| # | Endpoint | Where | When called |
|---|----------|-------|-------------|
| 1 | `GET /api/v1/plans/active?householdId&weekStartDate` | Plan view | On load (current week) + after every accept/abandon/suggestion action |
| 2 | `GET /api/v1/plans/{planId}` | Plan view / history | Opening a specific generation (history row, post-generate review, Location follow) |
| 3 | `GET /api/v1/plans/history?householdId&weekStartDate` | History drawer | Drawer open (latest generation first, capped at 100) |
| 4 | `GET /api/v1/plans?householdId&from&to&page&size` | Previous-weeks picker | Week navigation beyond the current week (page size default 20, max 100) |
| 5 | `GET /api/v1/plans/feasibility?householdId&weekStartDate` | Generate flow §4a | Entering `/plan/generate`, before any generation |
| 6 | `POST /api/v1/plans/generate` (+ `Idempotency-Key` header) | Generate flow §4b | "Generate" confirm; retried with the same key on network failure |
| 7 | `POST /api/v1/plans/{planId}/accept` | Generated-plan review | "Accept plan" button (GENERATED → ACTIVE) |
| 8 | `POST /api/v1/plans/{planId}/reject` | Generated-plan review | "Reject" button (GENERATED → REJECTED), optional reason |
| 9 | `POST /api/v1/plans/{planId}/abandon` | Plan view (ACTIVE) | "Abandon week" (ACTIVE → ABANDONED), optional reason |
| 10 | `POST /api/v1/plans/revert` | History drawer | "Revert to this plan" confirm |
| 11 | `PATCH /api/v1/plans/{planId}/slots/{slotId}/state` | Week grid | Slot action buttons (start cooking / cooked / eaten / skip) |
| 12 | `GET /api/v1/plans/suggestions?householdId&page&size` | Re-opt panel | On load + after suggestion actions (PENDING only, newest first) |
| 13 | `POST /api/v1/plans/{planId}/reopt-suggestions/{suggestionId}/accept` | Re-opt panel | "Accept changes" |
| 14 | `POST /api/v1/plans/{planId}/reopt-suggestions/{suggestionId}/reject` | Re-opt panel | "Dismiss" |
| s1 | `GET /api/v1/recipes/{recipeId}` | All sections | Recipe-name/time join (cached client-side; slots carry ids only) |
| s2 | `GET /api/v1/households/current/slot-configuration/planner-view` | Week grid | Once per session — slot labels/headcount context + eating window |

`householdId` comes from the session household (households/current, fetched by
the app shell, not this page).

## 3. Plan view (`/plan`) — anatomy & field mapping

### 3a. Header & plan status — reads `PlanDto` (#1)

| Display element | Source field |
|---|---|
| Title week range | `weekStartDate` (+6 days, formatted "8–14 June") |
| Status chip | `status` — ACTIVE olive · GENERATED amber "awaiting approval" · COMPLETED muted · SUPERSEDED/REJECTED/ABANDONED muted (historical views) |
| Generation tag | `generation` ("generation 3") + `replacesPlanId` → "replaces gen 2" link (#2) |
| Origin line | `triggerKind` — USER_INITIATED "generated by you" · SCHEDULED_WEEKLY "auto-generated Sunday" · MID_WEEK_REOPT "mid-week re-optimisation" + `createdAt` / `acceptedAt` |
| **Cold-start badge** | `coldStart=true` → chip "early-days plan — feedback especially valuable" |
| **AI-fallback badge** | `aiAugmented=false` → muted chip "AI ranking unavailable — top-scored pick" |
| **Quality-warning banner** | `qualityWarning=true` → amber banner "This plan has quality warnings" → drill-in §3c |
| Terminal metadata | `rejectedAt`+`rejectedReason` / `abandonedAt`+`abandonedReason` / `completedAt` (read-only line on historical generations) |
| Header actions | per plan state machine §5 |

Not displayed: `traceId`, `decisionId`, `triggerEventId`, `version` (admin /
internal); `id`, `householdId` (request plumbing).

### 3b. Week stat strip — reads `rollupSummary.weekly` (`WeeklyRollupDocument`)

| Display element | Source field |
|---|---|
| Est. cost cell | `costEstimateGbp` ("£52") |
| Confidence sub-line | `costConfidence` (0–1 → "83% confidence"); < 0.5 renders amber |
| Stale-price flag | `staleIngredientCount` > 0 → "· 4 stale prices" suffix, links Groceries |
| Variety cell | `varietyIndex` (0–1 → "78%") |
| Energy cell | `kcalTotal` ("15,050 kcal week") |
| Macro sub-line | `proteinAvgG` / `carbsAvgG` / `fatAvgG` ("175P · 220C · 68F g/day avg") |
| Batch cell | `batchCookSessions` ("2 cook sessions") |
| Warnings cell | `constraintViolations[].length` (amber when > 0) → drill-in §3c |

The mock's "± £4" band is **not** in the contract — there is no variance field;
the strip shows estimate + confidence % only (mock delta §9.3).

### 3c. Quality warnings drill-in (expandable panel)

Opens from the §3a banner or §3b warnings cell.

| Display element | Source field |
|---|---|
| Weekly violation rows | `rollupSummary.weekly.constraintViolations[]` (string per violation) |
| Per-day violation rows | `rollupSummary.daily[i].violations[]` grouped under `daily[i].date` |
| Gate verdicts | `scoreBreakdown.nutritionFloorGatePassed` / `varietyGatePassed` → ✓/✗ rows ("daily nutrition floors", "max-repeat variety rule") |
| Sub-score bars | `scoreBreakdown.{preference, nutrition, cost, variety, time, batch, provisions}` (0–1 bars) + `composite` headline |
| Per-day footer | `daily[i].{kcal, proteinG, carbsG, fatG, fibreG, costGbp, totalTimeMin}` mini-table |
| Unfilled-slot rows | derived: any slot with `scheduledRecipe = null` → "no feasible recipe found — relax a constraint or pick manually" |

Not displayed: `scoreBreakdown.weightSchemeVersion` (admin/debug).

### 3d. Week grid — reads `days[]` (`DayDto` → `MealSlotDto`)

Rows = `days[]` ordered by `date` (today's row highlighted); columns = slots
ordered by `slotIndex`. Column headers come from the household slot
configuration (s2), not hardcoded breakfast/lunch/dinner — `kind=CUSTOM` slots
render under their `label`.

| Display element | Source field |
|---|---|
| Day label + notes | `DayDto.date`; `DayDto.notes` ("eating out tonight") as a muted line under the day |
| Recipe name | `scheduledRecipe.recipeId` → recipe cache join (s1); `scheduledRecipe = null` → "— no recipe" cell (empty-slot state, amber when plan has `qualityWarning`) |
| Slot label / kind | `label` (display) — `kind` drives the column + icon |
| State mark | `state` — PLANNED ○ · COOKING ◐ amber · COOKED ● amber · EATEN ✓ olive · SKIPPED — muted strikethrough |
| Affected-by-suggestion strike | derived: slot `id` ∈ pending suggestion's `affectedSlotIds[]` (§3e) — red ✕ overlay; **not** a slot state |
| Pin tooltip | `pinnedReason` (EATEN / COOKED / COOKING / SKIPPED / USER_PINNED) → "pinned: already eaten — re-optimisation won't touch this" |
| BATCH tag | `scheduledRecipe.batchCookSessionId` non-null; same id across slots → hover highlights the whole session group |
| Serve time | `mealTime` (HH:mm) when non-null; null → fallback display (see §8 Q3) |
| Lead-time hint | derived: `mealTime − timeBudgetMin` → "start by 18:35" on today's upcoming slots |
| Slot detail popover | `servings` ("serves 2") · `shared` + `eaters[]` (→ member-name join: "Shared · 4 eating" / per-person avatars) · `timeBudgetMin` ("45 min budget") · `scheduledRecipe.recipeVersionId`/`recipeBranchId` → "view recipe version" deep link · `augmentationNotes` (italic) + `augmentationSource` chip (LLM "AI addition" / USER) · `phase2Addition=true` → "added in creative pass" caption · `prepStepAtTime` (reserved — always null in v1, no UI) |

**Slot state machine — buttons per state (each fires #11 with
`SlotStateChangeRequest.newState`; backend force-bumps plan version):**

| Slot state | Actions |
|---|---|
| PLANNED | **Start cooking** (→ COOKING) · **Skip** (ghost → SKIPPED) |
| COOKING | **Mark cooked** (→ COOKED) · **Skip** (ghost → SKIPPED) |
| COOKED | **Mark eaten** (→ EATEN) |
| EATEN | none — terminal, "✓ eaten" |
| SKIPPED | none — terminal, "— skipped" |

No backwards transitions; illegal requests return 409 (§8) and the grid
re-fetches. Slot actions are enabled only while the plan is ACTIVE. Recipe
content is immutable per the lifecycle — there is no per-slot "swap recipe"
control; the only paths are regeneration, a re-opt suggestion, or revert.

### 3e. Re-opt suggestions panel — #12, #13, #14

Renders above the grid when `GET /suggestions` returns rows (PENDING only).

| Display element | Source field (`ReoptSuggestionDto`) |
|---|---|
| Panel title | `summary` (≤255, e.g. "Chicken breast marked spoiled") |
| Trigger chip | `triggerKind` — PROVISIONS / NUTRITION / PREFERENCE / HOUSEHOLD_SETTINGS / USER |
| Affected count + strikes | `affectedSlotIds[]` → "2 future slots affected" + grid ✕ overlays (§3d) |
| Pin reassurance | static copy "eaten and cooked meals stay pinned" (HLD rule) |
| Expiry countdown | `expiresAt` ("expires Sunday") — suggestions auto-expire at weekStart + 7d |
| Raised at | `createdAt` |
| Pagination | `ReoptSuggestionDtoPage.{content, totalElements, …}` — "1 of 2 suggestions" pager when > 1 |

**Buttons:** **Accept changes** (primary → #13) · **Dismiss** (ghost → #14).
Both return `PlanReoptSuggestionDto` (status ACCEPTED / REJECTED). On accept the
backend writes a **new GENERATED generation** and supersedes the current plan —
the page then re-fetches #1 (404 — no active now), fetches #3, and presents the
new generation for the second confirmation (accept-plan, §5). Surface this
two-step explicitly: "Changes applied as a new draft plan — review and accept."

The accept/reject *response* carries the concrete diff
(`proposedAssignments.changes[]`: `slotId`, `oldRecipeId` → from-name join,
`newRecipeId` → to-name join, `newServings`, `reason` note per row) — exactly the
SwapLine rows the mock renders. But the *list* DTO (#12) has no
`proposedAssignments`, and there is no GET-single-suggestion endpoint — so the
HLD-mandated **diff preview before accepting** cannot be rendered from the
contract. Flagged §8 Q2 (backend gap). Until resolved the panel can only show
`summary` + affected strikes pre-accept and the full diff post-accept.

### 3f. History & revert — #3, #4, #10

History drawer (current week, #3) and previous-weeks picker (#4, paginated
`PlanDtoPage`). Row mapping (`PlanDto`, slots not expanded):

| Display element | Source field |
|---|---|
| Generation + status | `generation`, `status` chip |
| When / by what | `createdAt`, `triggerKind` |
| Outcome line | `acceptedAt` / `rejectedAt`+`rejectedReason` / `abandonedAt`+`abandonedReason` / `completedAt` |
| Chain | `replacesPlanId` → indent/arrow to predecessor row |
| Quality flags | `qualityWarning` ⚠ · `coldStart` · `aiAugmented=false` mini-icons |
| Open | row click → #2 full grid in read-only mode |

**Revert** button on every non-active historical row → confirm dialog ("copies
this plan onto a new generation; recipes that now break your constraints are
replaced") → `POST /plans/revert` body `RevertToPlanRequest{
targetHistoricalPlanId }` (the only field; uuid, required). 201 → new GENERATED
plan returned; route to review (§5 GENERATED). If the response has
`qualityWarning=true` or null-recipe slots, show the HLD warning: "n slots could
not be refilled — accept the partial plan or re-optimise."

## 4. Generation flow (`/plan/generate`) — anatomy & field mapping

In-page stepper: **feasibility → generate → review**. Entered from "Generate
next week" (weekStartDate = next Monday) or "Re-optimise" (current Monday;
`forceRegenerateIfActive` consent — see 4b).

### 4a. Feasibility gate — `GET /plans/feasibility` (#5), reads `FeasibilityCheckResultDto`

Called on entry, before the generate button enables.

| Display element | Source field |
|---|---|
| Green band | `feasible=true` → "✓ Constraints look workable — n recipes available per slot" (count not in DTO; copy stays qualitative) |
| Conflict cards | `conflicts[]` (`ConstraintConflictDto`) — one card each |
| · type chip | `type`: HOUSEHOLD_HARD_COLLISION "diets collide" · NUTRITION_VS_BUDGET "targets vs budget" · PROVISIONS_BOTTLENECK "equipment/pantry limit" · OVER_SPECIFIED_PREFERENCES "preferences too narrow" |
| · body | `description` (server-written sentence) |
| · slot chips | `affectedSlotIds[]` → slot label join from the active plan / slot config; unmatched ids render as "n slots" count |
| **Ranked relaxations** | `resolutions[]` (`ResolutionOptionDto`), server-ranked best-first |
| · row label | `description` ("drop protein floor to 160 g opens 12 recipes") |
| · recovery figures | `slotsRecovered` ("+12 slots") + `scoreRecovered` ("+0.18 score") |
| · row key | `key` — opaque ("split_slot", "drop_protein_floor_to_160"); **no apply endpoint exists** — each row renders a deep link to the owning page (split slot → /settings slot config; protein floor → /nutrition targets; budget → /pantry; preference → /preferences), §8 Q4 |
| Decline-all path | "Generate anyway" ghost button when `feasible=false` → proceeds; resulting plan will carry `qualityWarning=true` (HLD: no silent failure) |

### 4b. Generate — `POST /plans/generate` (#6)

Request `GeneratePlanRequest` ⇄ controls:

| Control | Request field | Constraints |
|---|---|---|
| (implicit) | `householdId`* | uuid; session household; 403 if not a member |
| Week selector | `weekStartDate`* | date; **must be a Monday**; not > 8 weeks past nor > 4 weeks future (400 otherwise) |
| Regenerate-consent checkbox | `forceRegenerateIfActive` | boolean, default false; shown pre-checked-off when an ACTIVE plan exists for the week: "Replace the current active plan" — generating without it against an infeasible set returns the early quality-warning draft path |
| — | `Idempotency-Key` header | ≤200 chars; client generates one uuid per user intent (press of Generate), persists it until a 2xx lands, and **reuses it on network retry**; "Regenerate all" mints a *new* key |

Note: the LLD's request record carries `trigger`/`triggerEventId`; the shipped
HTTP contract does not — the server fixes `triggerKind = USER_INITIATED` for this
endpoint. No UI control.

**Response semantics:**

| Status | Meaning | UI behaviour |
|---|---|---|
| 201 + `Location: /api/v1/plans/{id}` + `PlanDto` | new generation composed | render review (4c) |
| 200 + `PlanDto` (no Location) | **Idempotency-Key replay** — cached plan returned, no new generation ran | render review identically; toast "already generated — showing the existing result" |
| 409 | concurrent generation in progress for this (household, week) | "A generation is already running — try again shortly" toast; offer manual retry (no polling endpoint; the call is synchronous, typically ≲ 20 s) |

While the request is in flight: skeleton + advisor wait card (generation is one
blocking call — Stage A→D server-side; no progress endpoint, v1 polls nothing).

### 4c. Review — the result card (candidate mapping)

The contract returns **one** composed `PlanDto` per generate call — Stage C's
LLM picks from the internal top-5 server-side. The mock's five-candidate grid
has no backing endpoint (§8 Q1 — the HLD's "user can override the LLM's pick"
is not reachable through the shipped contract). The review renders a single
proposed-plan card; "Regenerate all" (new Idempotency-Key) is the only
alternative-seeking control. Card mapping — the exact per-candidate shape:

| Card element (mock) | Contract source (`PlanDto`) |
|---|---|
| Fit score "91 / 100" | `scoreBreakdown.composite` × 100 |
| Sub-score rows | `scoreBreakdown.{preference, nutrition, cost, variety, time, batch, provisions}` (0–1 → %) |
| "Nutrition · on target all days" | derived: `rollupSummary.daily[].violations[]` empty → "on target"; else name the day(s) + violation strings |
| "Cost · £53 ± £4 / 83% confidence" | `rollupSummary.weekly.costEstimateGbp` + `costConfidence`; the "±" band is **not in the contract** — omit (render "£53 · 83% confidence") |
| "Variety · 81%" | `rollupSummary.weekly.varietyIndex` |
| "Prep load · 3h 40m" | derived: Σ `rollupSummary.daily[].totalTimeMin` |
| Warn pill | `qualityWarning=true` → "quality warnings (n)" from `constraintViolations[].length`; "over budget" has no dedicated field — it appears inside `constraintViolations[]` |
| RECOMMENDED tag | n/a — single result; drop |
| Reasoning card | **not in `PlanDto`** — Stage C reasoning lives in the decision log (admin-only endpoint); §8 Q5 |
| Dinner line-up chips | `days[].slots[]` where `kind=DINNER` → `scheduledRecipe.recipeId` name joins (Mon→Sun) |
| Cold-start / AI-fallback badges | `coldStart` / `aiAugmented=false` (§3a copy) |

### 4d. Accept / reject the generated plan

Buttons on the review card (and on any GENERATED plan opened from history):

- **Accept plan** (primary) → `POST /plans/{id}/accept` (#7) → 200 ACTIVE
  `PlanDto`; navigate to `/plan`. 409 = state moved (e.g. superseded meanwhile)
  → re-fetch and explain.
- **Reject** (ghost) → optional reason popover (`RejectPlanRequest.reason`,
  ≤255, nullable) → `POST /plans/{id}/reject` (#8) → 200 REJECTED. Idempotent —
  re-rejecting returns 200 no-op. Return to `/plan` (previous active, if any,
  is untouched).

## 5. Plan state machine — header buttons per status

| Plan status | Buttons (header, §3a) |
|---|---|
| GENERATED | **Accept plan** (#7, primary) · **Reject** (#8, ghost + reason) |
| ACTIVE | **Generate next week** (→ §4, next Monday) · **Re-optimise** (→ §4, same week, consent checkbox) · **Abandon week** (overflow menu → reason popover `AbandonPlanRequest.reason` ≤255 → #9) |
| SUPERSEDED / COMPLETED / REJECTED / ABANDONED | read-only; **Revert to this plan** (#10) on superseded/completed/abandoned/rejected historical rows |
| DRAFT | never rendered (internal composing state) |

Transitions the UI must never offer: accept on non-GENERATED, abandon on
non-ACTIVE, slot actions on non-ACTIVE — all guarded client-side and still
handled via 409 (§8) if raced.

## 6. (folded into §3d/§5 — slot and plan machines above)

## 7. Not on this page

| Contract item | Home |
|---|---|
| `GET /api/v1/admin/planner/decisions/{planId}` (decision chain, traceId filter) | Admin decision-log explorer |
| `PlanDto.traceId` / `decisionId` / `version` | request plumbing + admin |
| `GET /api/v1/provisions/planner-bundle` | planner-internal compose read (no UI) |
| `POST /api/v1/nutrition/floor-gate/evaluate` | planner-internal scoring gate |
| Grocery list generation from the plan | Groceries page (`grocery/*`) |
| Slot intake confirm/override/skip (nutrition logging) | Today + Nutrition pages — this page's slot buttons drive *planner* state only |
| `POST /api/v1/provisions/cook-event` (pantry deduction on cook) | Today / Pantry pages |
| `prepStepAtTime` (reserved, always null in v1) | future pre-cook-actions feature |
| Upcoming-slots projection (`UpcomingSlotView`) | internal, no HTTP exposure |

## 8. Status-code → UI map

| Code | Where | UI behaviour |
|---|---|---|
| 404 | #1 active plan | Empty state (not an error): "No plan for this week yet" → **Generate** CTA (§4) |
| 404 | #2/#7/#8/#9/#10/#11/#13/#14 plan/slot/suggestion/target | "Plan no longer exists" toast → re-fetch #1/#3 |
| 403 | #5/#6 | "You're not a member of this household" (should not occur with session household) |
| 409 invalid plan transition | #7/#8/#9 | Re-fetch the plan; explain: "this plan changed state elsewhere" |
| 409 invalid slot transition | #11 | Toast with server detail; re-fetch plan (another device may have advanced the slot) |
| 409 concurrent generation | #6/#10 | "Generation already in progress" toast + manual retry |
| 409 optimistic lock | #11 | Silent re-fetch + one auto-retry, then surface |
| 422 revert target invalid | #10 | "That plan isn't in your household's history" — disable the row |
| 400 | #4 (from > to), #6 (non-Monday, window), #11 (unknown state) | Inline field errors / blocked submit |
| 401 | all | Session-expired redirect to /login (global) |

**Open questions (flagged, not resolved here):**
1. **No candidates endpoint.** HLD Stage C says "the UI presents all 5
   candidates with the LLM's recommendation highlighted; override is logged as
   `chosen.source = 'user'`" — but `POST /generate` returns one `PlanDto` and
   nothing exposes the top-N rollups. The mock's candidate grid is
   contract-divergent. Backend gap candidate: candidates endpoint (or
   candidate rollups embedded in the 201 response) + a pick endpoint.
2. **Re-opt diff not previewable.** `GET /suggestions` returns
   `ReoptSuggestionDto` (no `proposedAssignments`); the diff shape exists only
   on `PlanReoptSuggestionDto`, returned *by* accept/reject. The HLD mandates
   diff preview *before* confirm. Backend gap candidate: `GET
   /plans/{planId}/reopt-suggestions/{suggestionId}` returning
   `PlanReoptSuggestionDto`, or fold `proposedAssignments` into the list DTO.
3. **Serve-time resolution is server-internal.** `MealSlotDto.mealTime` is the
   raw nullable override; the three-level coalesce (override → lifestyle-config
   schedule → slot-kind default) lives in the internal `UpcomingSlotView`
   projection with no HTTP exposure. The grid must either replicate the
   fallback client-side (reading preferences lifestyle-config) or show times
   only when `mealTime` is set. Backend gap candidate: resolved
   `effectiveMealTime` on `MealSlotDto`.
4. **Resolution options are informational.** `ResolutionOptionDto.key` has no
   apply endpoint; v1 deep-links to the owning settings page. Acceptable for
   v1? (HLD wording implies one-tap application.)
5. **Stage C reasoning not user-visible.** The mock's "Why this plan" advisor
   card has no contract source (reasoning is decision-log/admin only). Either
   drop the card or expose a user-grade reasoning string on `PlanDto`.
6. **`POST /generate` against a week with an ACTIVE plan** — semantics of
   `forceRegenerateIfActive=false` + feasible set (does it 409, supersede, or
   create a parallel GENERATED gen?) are not pinned in the contract docs; the
   UI assumes a new GENERATED generation that supersedes only on accept.
   Verify with backend before wiring.

## 9. Mock deltas (to make the mock match this spec)

1. Retype the plan slice on generated contract types (`PlanDto` et al. from
   `types.gen.ts`) instead of bespoke `PlanState`/`PlanDay`/`PlanSlot`; derive
   header strings (`title`, `range`, `meta`) from `weekStartDate`/`status`/
   `generation`/`triggerKind`.
2. Slot states: add SKIPPED (with skip buttons); remove `"affected"` as a state
   — derive the strike overlay from the pending suggestion's
   `affectedSlotIds`; render `pinnedReason` tooltips.
3. Stat strip: compute from `rollupSummary.weekly` + `scoreBreakdown` (drop the
   pre-baked "± £4"; add stale-ingredient count + kcal/macros cells).
4. Add quality-warnings drill-in (§3c) seeded with 2 violations + one
   gate-failed example; add cold-start and aiAugmented=false badge states.
5. Re-opt panel: reshape `fix` to `ReoptSuggestionDto` (+ post-accept
   `PlanReoptSuggestionDto` diff); accept becomes two-step (new GENERATED gen →
   accept-plan), replacing the in-place swap mutation; add dismiss → REJECTED
   and an `expiresAt` countdown.
6. Generate flow: replace the 5-candidate grid with the single-result review
   card mapped per §4c (keep the grid behind a flag pending §8 Q1); feasibility
   band renders `conflicts[]` + ranked `resolutions[]` (seed one infeasible
   week) instead of the always-green line; store action takes an
   Idempotency-Key and serves a cached replay on re-submit.
7. Add history drawer + previous-weeks pager (#3/#4) and revert flow with the
   stripped-slot warning state.
8. Week grid: drive columns from slot configuration (support SNACK/CUSTOM
   kinds); add day `notes`, serve times + "start by" lead hints, slot detail
   popover (servings, eaters, time budget, augmentation provenance,
   phase2Addition), empty-slot (`scheduledRecipe=null`) cell state.
9. Abandon-week action with reason; rejected/abandoned metadata on history
   rows.
