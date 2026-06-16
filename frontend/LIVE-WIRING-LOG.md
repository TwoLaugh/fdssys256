# Live-API wiring — progress, findings & TODOs

Running log for wiring the mock frontend to the real backend. The full mock set
is frozen at [`../frontend-mock-snapshot/`](../frontend-mock-snapshot); this app
(`frontend/`) is the evolving wired one.

## How it works (architecture)

- **Hydrate-the-store.** Page components read the in-memory store via `useStore`.
  In live mode (`VITE_LIVE=1`) `src/live/hydrate.ts` `hydrateLive()` fetches the
  real DTOs on boot (one fault-isolated fan-out — a 404/error on any slice → empty
  slice, never a blank app) and writes them into the store slices each page reads.
  Mutation functions in `src/mock/store.ts` get an `if (LIVE) { liveMutation(...) }`
  branch that fires the real API then re-hydrates.
- **Dates.** `src/live/dates.ts` re-exports the date anchors (`MOCK_TODAY_ISO`,
  `CURRENT_WEEK_START`, `WEEK_DATES`, `WEEK_DAY_LABELS`, `TODAY_INDEX`,
  `MOCK_NOW_MS`) — real clock in live mode, fixed fixtures in mock mode. Pages that
  gate data on dates import from here instead of the mock seeds.
- **Switch:** `VITE_LIVE=1`; Vite proxies `/api` + `/test-support` → `:8080`
  (same-origin ⇒ cookies, no CORS). Dev auto-login (`src/live/session.ts`) until
  the real login page is wired. Run via the `mealprep-frontend-live` launch config
  (port 5176); backend per memory `reference_mealprep_live_stack`.

## Page status

**READS (hydration): ✅ live for every page** — the whole app navigates on real
data (verified: Today, Plan, Recipes, Nutrition, Settings). Pages with no seeded
data render correct empty states.

| Page | Route | Reads | Writes | Notes |
|---|---|---|---|---|
| Today | `/` | ✅ | ✅ | cook/eat/skip/snack — verified round-trip |
| Plan | `/plan` | ✅ | ✅ | lifecycle/re-opt/revert/slots; generation flow deferred (#200) |
| Nutrition | `/nutrition` | ✅ | ✅ | targets/snack/override/edit/journal CRUD/directives accept+reject/ingredient correction |
| Groceries | `/groceries` | ✅ (empty: no list) | ✅ | mark-bought/bulk/recalculate/orders lifecycle/substitutions |
| Pantry | `/pantry` | ✅ (empty: no inventory) | ✅ | inventory CRUD/quantity/status/consume/spoiled/waste/equipment |
| Recipes | `/recipes` | ✅ (8 pool recipes) | ✅ | promote/demote/archive/branch — promote round-trip verified |
| Recipe detail | `/recipes/:id` | ✅ lazy | ✅ | per-id lazy hydrate on mount (versions/subs/ratings); edit/revert/substitution lifecycle/rating CRUD/recalc — rating POST verified 201 |
| Discover | `/discover` | ✅ (sources; no jobs) | ✅ start/cancel | async job poll still TODO |
| Preferences | `/preferences` | ✅ | ✅ | taste save/rollback/refresh, lifestyle; **GAP-04** hard-constraints 409 interstitial deferred |
| Activity | `/activity` | ✅ | ✅ | accept/reject pending-change, submit feedback, answer clarification |
| Settings | `/settings` | ✅ | ✅ | invites (create reveals code), members, settings, provider; **password change deferred by design** |
| Notifications | `/notifications` | ✅ | ✅ | read/dismiss/action/bulk/prefs |
| Admin | `/admin` | n/a (403) | n/a | seed user not allowlisted → 403 dead-end (correct); read-only console |
| Onboarding | `/onboarding` | n/a | n/a | seed user done → redirects `/`; wizard untested live |
| Login | `/login` | n/a | n/a | bypassed by dev auto-login |

**Every page's primary reads AND writes are now live.** The remaining gaps are
specific flows, not whole pages — see the deferred list below.

## Mutation-wiring plan — DONE except 2 own-task flows

All per-page mutations are wired (`if (LIVE) liveMutation(apiSend(...))`), across
Nutrition, Notifications, Preferences, Activity, Settings, Pantry, Groceries,
Recipes, Discover (start/cancel), and Recipe-detail (lazy hydrate + 8 mutations).
Remaining ordered work: **Plan generation (#200)** and **Discover async job
polling** — both need their own async/polling machinery (see deferred list).

## Findings & cross-cutting TODOs

- **Generation flow (Plan)** guarded/deferred — task #200.
- **Real-AI** — e2e profile stubs AI; generated plans/recipes are the
  deterministic `e2e_curated` pool, not real cooking. Real AI = dev profile +
  valid OpenAI key + fix placeholder model ids.
- **Login/onboarding** — dev auto-login stopgap; real auth flow unwired.
- **Plan week-nav fragility** — defaults to live week only because today sits in
  the mock's fixed `KNOWN_WEEKS`; should derive real weeks.
- **types.gen.ts** — regen chip spun off; tsc green so likely already current.
- **Recipe detail lazy per-id hydration — DONE.** `hydrateRecipeDetail(id)` runs
  on the page's mount effect: chained recipe → `currentBranchId` → versions
  (`branchId` required) → head versionId → ratings (`versionId` required) +
  `ratings/mine`. Provenance is not fetched (manual recipes 404; left empty).
  Branch switching still fetches only the current branch (see deferred).
- **Discover is async** — job lifecycle needs polling (or SSE, v1.5 #172);
  boot only hydrates sources + job history.
- **Admin** — seed user isn't in `mealprep.admin.user-ids` → all admin endpoints
  403; page shows access-denied (correct). To exercise it, add the user to the
  allowlist (server config) or seed an admin.
- **Seeding for non-empty pages** — Groceries (recalculate to build a list),
  Pantry (create inventory/equipment/budget), Activity (submit feedback),
  Discover (run a job) all need data seeded to show populated (vs empty) states.

## Backend contract gaps surfaced (frontend can't fully wire until fixed)

- **recipes** §8: no merge / "import anyway" override on the dedup dialog (v2).
- **recipe-detail** §11: PROPOSED substitutions have no list endpoint (client-
  remembered only); `nutritionPerServing` only via recalc POST.
- **discover** §9: no `CANCELLED` status (cancel = FAILED+string); skip is
  client-only; per-job "kept" count not derivable; user source-disable endpoint
  exists (`/sources/{key}/user-disable`) — DTO `userDisabled` to surface.
- **household** §8: member display names — DTO has `username` now (#250) but the
  page still renders the userId in places; verify the join.
- **admin** §7: allowlist editable only via server config (no REST).

## Per-page notes

### Nutrition / Preferences — mutations (partial, 2026-06-15)
Wired (live API + re-hydrate): `saveTargets` (PUT /nutrition/targets), `removeSnack`
(DELETE …/snacks/{id}), `rejectDirective` (POST …/health-directives/{id}/reject),
`markLifestyleReviewed` (POST …/lifestyle-config/mark-reviewed), `refreshTasteProfile`
(POST …/taste-profile/refresh-now). Plus confirm/skip/snack already live (shared
with Today).
**Deferred (need body/verb verification or special handling):**
- `overrideSlot` / `editSlot` — POST …/slots/{mealSlot}/override|/edit, body shape
  unverified.
- `addJournalEntry`/`updateJournalEntry`/`deleteJournalEntry` — journal endpoints
  need date+entryId path + request shape.
- `correctIngredient` — POST …/ingredients/{searchTerm}/correction, body wrapper
  unverified.
- `acceptDirective` — POST …/accept needs AcceptDirectiveRequest (userModification +
  expectedVersion).
- `saveHardConstraints` — **GAP-04**: returns the Tier-1 removal interstitial on
  409; `liveMutation` (fire+toast) can't surface the problem body. Needs an async
  path that returns the 409 `Tier1RemovalConfirmationProblem` to the page.
- `saveTasteProfile` / `rollbackTasteProfile` / `saveLifestyleConfig` — return
  "ok"|"conflict"; wire with optimistic-return + fire + reconcile (verify the
  Update*Request body wrappers / optimisticVersion field names first).

**Pattern note:** mutations that return a value the page branches on are wired
*without* an early return — the mock optimistic update + sync return stay, and the
API fires + re-hydrates to reconcile (same as `changeSlotState`). Pure
fire-and-forget mutations early-return in the LIVE branch.

### Mutation wiring — all pages (2026-06-15, ~44 wired)
Every page's **primary** write mutations now fire the real API + re-hydrate:
- **Notifications:** markRead, action, dismiss, bulkMarkRead, savePrefs.
- **Recipes:** promote, demote, archive, unarchive, createVariantBranch.
- **Settings:** revokeInvite, updateMember, changeMemberRole, removeMember,
  saveHouseholdSettings, logout.
- **Pantry:** createInventoryItem, updateInventoryItem, markItemExhausted,
  removeInventoryItem, upsertEquipment, removeEquipment, logWaste.
- **Groceries:** markBoughtLine, undoMarkBought, recalculate, refreshPrices,
  createOrder, quote, place, refreshStatus, markUserConfirmed, markDelivered,
  cancelOrder.
- **Nutrition:** saveTargets, removeSnack, rejectDirective (+ confirm/skip/snack).
- **Preferences:** saveLifestyleConfig, markLifestyleReviewed, refreshTasteProfile.
- **Activity:** rejectPendingChange, answerClarification.
- **Discovery:** startJob, cancelJob (early-return; async polling still TODO).
- **Plan/Today:** (prior) slot states, lifecycle, re-opt, revert.

**Verification bug caught + fixed:** `recalculate` needed a
`{planId, force}` body (empty `{}` → 400) and GET `…/shopping-lists/current`
needs a `?planId=` query param (hydrator was relying on `/history` as fallback).
Both fixed + curl-verified 200. tsc clean throughout.

### Tail + recipe-detail wiring (2026-06-15, ~72 wired total)
The deferred tail and the whole recipe-detail surface are now wired. All bodies
were extracted from `api/types.gen.ts` and **verified against source** before
wiring (an agent mis-reported three: `quantityAdjustment` vs the real
`newQuantity`, a non-existent `meal-consumption` endpoint, and missing required
query params — caught by reading the spec, see below).

- **Nutrition:** `overrideSlot`, `editSlot`, `addJournalEntry`,
  `updateJournalEntry`/`deleteJournalEntry` (look up `onDate` from the entry),
  `acceptDirective` (+`expectedVersion`), `correctIngredient`
  (PUT `…/{searchTerm}/correction`, `encodeURIComponent`).
- **Pantry:** `adjustItemQuantity` (PATCH `…/quantity {newQuantity,expectedVersion}`),
  `cycleStapleStatus` (PATCH `…/status`, StapleStatus `STOCKED|LOW|OUT`),
  `consumePortions` (POST `/provisions/meal-consumption {inventoryItemId,portions}`),
  `markSpoiled`.
- **Groceries:** `bulkMarkBought`, `resolveSubstitution` (+ `markBoughtOneTap`
  delegates to the already-live `markBoughtLine`).
- **Preferences/Activity/Settings:** `saveTasteProfile`, `rollbackTasteProfile`,
  `submitFeedback`, `acceptPendingChange`, `acceptInvite`, `saveProviderConnection`,
  `createInvite` (captures the 201 `inviteCode` and stashes it under the real id
  after rehydrate — list rows redact it).
- **Recipe detail (per-id lazy hydration + 8 mutations):**
  `hydrate.ts` gained `hydrateRecipeDetail(id)` — chained fetch
  (recipe → `currentBranchId` → versions `?branchId=` → head versionId →
  ratings `?versionId=` + `ratings/mine`), called from the page's mount effect.
  `store.ts` gained `liveRecipeMutation` (reconciles catalogue + per-id detail).
  Wired: `editRecipe`, `revertRecipe`, `proposeSubstitution`, `actOnSubstitution`
  (accept/reject/promote-to-version), `submitRating`, `updateRating`, `deleteRating`,
  `recalculateNutrition`.

**Bug fixed in already-wired code:** `rejectDirective` was sending `{reason}` —
the contract is `RejectDirectiveRequest {rejectionReason, expectedVersion}`. It
would have silently mis-behaved; corrected.

**Live verifications this batch:** recipe `promote` round-trip (library 0→1,
catalogue→USER); recipe-detail lazy hydrate renders real versions; rating POST
`201` with the exact wired body (server aggregate 78). tsc clean throughout.

### Still deferred (genuine blockers / own tasks)
- **Plan generation** (#200) and **Discover async job polling** — own tasks
  (need a polling/long-poll loop; boot only hydrates job history).
- **saveHardConstraints** (Preferences, GAP-04) — the Tier-1 allergy-removal 409
  returns a `Tier1RemovalConfirmationProblem` body the page must show in an
  interstitial; `liveMutation` (fire+toast) can't surface it. Needs an async
  path that returns the 409 body. Mock-only until then.
- **changePassword** — **deliberately mock-only.** Wiring it to the real
  `PUT /auth/password` would change the dev user's password server-side and
  break the hardcoded dev auto-login (`iren-demo`/`demo-password-123`) on the
  next boot. Leave until real login replaces auto-login.
- **createRecipeManual** dedup dialog + recipe **import** flows (preview/confirm)
  — backend dedup/preview response shapes not fully nailed; needs the 409
  "import anyway" override (a v2 contract gap, see below).
- **markSpoiled waste leg** — the spoiled call fires; logging the waste entry as
  a second leg is still mock-only.
- **Recipe-detail branch switching** — lazy hydrate fetches the *current*
  branch's versions only (versions are per-branch, `branchId` required); viewing
  a non-current branch needs an on-switch fetch.
- **recalculateNutrition display** — fires the real POST, but the page consumes a
  *synchronous* return so it shows the deterministic mock numbers optimistically;
  the real per-serving numbers need an async page refactor to surface.
