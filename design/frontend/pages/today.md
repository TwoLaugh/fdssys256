# Page spec — Today (`/`)

The contract-complete specification: every endpoint this page consumes, and the UI
that each request field and response field demands. Companion docs:
[../ia.md](../ia.md), [../design-language.md](../design-language.md). Template:
[nutrition.md](nutrition.md) (the pilot). Sibling: [plan.md](plan.md).

Today is a **cross-module composite** — the home page reads five modules and owns
none of them. Its discipline is *glanceable subset*: each card shows the minimum
that answers "what now?", and everything deeper deep-links to the owning page.
The "Not on this page" table (§7) is therefore the load-bearing section.

---

## 1. Intent (HLD)

- **One tap per meal** (`design/nutrition-model.md`): "logging a normal day is
  one tap per meal" — Today is where that tap lives. Slot rows surface the next
  lifecycle action only, never a form.
- **Pinned slots are immutable** (`design/meal-planner.md`): eaten/cooked/cooking
  meals never regenerate; Today's actions only ever move a slot *forward*.
- **The user is always in the loop** (`design/meal-planner.md` §triggers):
  events fire notifications, not automatic re-opts — Today surfaces the digest
  ("needs attention") and routes to the owning page; it never applies changes.
- **Proposed, never auto-applied** (`design/recipe-system.md` adaptation):
  recipe changes wait in a pending queue; Today teases the top one with
  accept/review, mirroring the Activity page's semantics.
- **Cost always with confidence** — the budget snapshot keeps the weekly target
  visible daily (`design/provision-model.md` budget tier).
- **Cook event is a direct call in v1** (`design/technical-architecture.md`
  Flow 4 note): "the cook flow ships as a direct REST/service call, not an
  event… the Nutrition-Logger auto-confirm-on-cook leg is deferred." The client
  therefore coordinates planner slot state and nutrition intake itself (§3b,
  §8 Q1).

## 2. Endpoint inventory

Six modules, 12 endpoints (9 reads on load, 3 writes from slot/snack actions);
support joins marked s.

| # | Endpoint | Card | When called |
|---|----------|------|-------------|
| 1 | `GET /api/v1/plans/active?householdId&weekStartDate` | Header + meal timeline | On load (current Monday week) + after slot actions |
| 2 | `GET /api/v1/nutrition/intake/{date}` | Meal timeline (intake join) + snacks | On load + after every slot/snack action |
| 3 | `GET /api/v1/nutrition/intake/{date}/aggregate` | Stat band | On load + after every action |
| 4 | `GET /api/v1/nutrition/targets` | Stat band (targets) | On load (shared cache with /nutrition; 404 → band hidden + setup link) |
| 5 | `GET /api/v1/notifications/summary` | Needs attention (badge counts) | On load + bell-poll cadence (shared with shell) |
| 6 | `GET /api/v1/notifications?status=UNREAD&size=3` | Needs attention (rows) | On load |
| 7 | `GET /api/v1/provisions/budget` | Week budget | On load |
| 8 | `GET /api/v1/adaptation/pending-changes` | Suggestion teaser | On load (top-3 ranked; Today shows row 1) |
| 9 | `PATCH /api/v1/plans/{planId}/slots/{slotId}/state` | Meal timeline | Start cooking / mark cooked / mark eaten / skip |
| 10 | `POST /api/v1/nutrition/intake/{date}/slots/{mealSlot}/confirm` | Meal timeline | Paired with #9 on "Mark eaten" (§3b) |
| 11 | `POST /api/v1/nutrition/intake/{date}/slots/{mealSlot}/skip` | Meal timeline | Paired with #9 on "Skip" |
| 12 | `POST /api/v1/nutrition/intake/{date}/snacks` | Quick snack log | Snack chip/form submit |
| s1 | `GET /api/v1/recipes/{recipeId}` | Meal timeline | Recipe-name/time join (cached) |
| s2 | `GET /api/v1/households/current/slot-configuration/planner-view` | Meal timeline | Once per session — headcount/shared context |

Deliberately **not** called from Today (owning pages instead): override/edit
intake, journal, weekly aggregate, activity level, plan generate/accept,
suggestion accept (plan re-opt), pantry mutations, order endpoints. See §7.

## 3. Anatomy & field mapping

### 3a. Header

| Display element | Source |
|---|---|
| Date label | client clock ("Wednesday 10 June") |
| Week-progress label | derived: `PlanDto.weekStartDate` → "week plan day n of 7" |
| Plan status chip | `PlanDto.status` — ACTIVE "Plan active" · none (404 #1) → "No plan this week" chip + **Generate** deep-link `/plan/generate` |
| Greeting | client time-of-day + session display name |

No-plan state: the meal timeline collapses to the same CTA; stat band, budget,
attention and teaser cards still render (they don't depend on the plan).

### 3b. Meal timeline — joins `PlanDto.days[date=today].slots[]` (#1) × `IntakeDayDto.slots[]` (#2)

Join key: planner `MealSlotDto.kind` ↔ nutrition `IntakeSlotDto.mealSlot` —
BREAKFAST↔BREAKFAST, LUNCH↔LUNCH, DINNER↔DINNER. Planner `SNACK` ↔ nutrition
`SNACKS` (enum names differ); planner `CUSTOM` slots have **no** intake slot
(§8 Q3). One row per planner slot, ordered by `slotIndex`.

| Display element | Source field |
|---|---|
| Serve time | `MealSlotDto.mealTime` when set; null → no time shown (resolution gap, see plan.md §8 Q3) |
| Recipe name | `scheduledRecipe.recipeId` → s1 join; null → slot `label` ("— eating out") |
| Meta line | `shared` + `eaters[]` count ("Shared · 4 eating" / "Just you") · `scheduledRecipe.servings` · planned kcal from `IntakeSlotDto.planned.calories` |
| BATCH context | `scheduledRecipe.batchCookSessionId` non-null → "batch-cooked" tag (the mock's "portion 3 of 5" is not derivable — §8 Q4) |
| Lead-time hint | derived: `mealTime − timeBudgetMin` → "start cooking 18:35" on the next upcoming slot |
| State chip | planner `state` (PLANNED ○ / COOKING ◐ / COOKED ● / EATEN ✓ / SKIPPED —) |
| Logged check | intake `actual.status` ≠ PENDING → "logged" tick; OVERRIDDEN/EDITED on /nutrition show through unchanged |
| Action button | next-action per the table below |

**Two state machines, one row.** Each row reflects the *planner* slot state
(cooking lifecycle) and the *nutrition* intake status (logging lifecycle).
Which API each button hits:

| Button (shown when) | Planner call (#9) | Nutrition call | Notes |
|---|---|---|---|
| **Start cooking** (state=PLANNED) | `newState: COOKING` | — | |
| **Mark cooked** (state=COOKING) | `newState: COOKED` | — | pantry deduction via `POST /provisions/cook-event` is **not** fired from Today in v1 — §8 Q1 |
| **Mark eaten** (state=COOKED) | `newState: EATEN` | `POST …/confirm` (#10) — idempotent, credits planned values | two calls, no transaction — §8 Q1 ordering |
| **Skip** (ghost, state=PLANNED/COOKING) | `newState: SKIPPED` | `POST …/skip` (#11) | also dual-write |
| (state=EATEN/SKIPPED) | — | — | terminal; row pinned |

Intake statuses CONFIRMED/OVERRIDDEN/EDITED/SKIPPED are decided states — if the
user already logged the meal on /nutrition (e.g. overrode with free text), the
"Mark eaten" planner call still fires but the nutrition confirm is **skipped**
(it would 422 "already decided"; the join shows the existing logged tick).
Rows never offer override/edit here — that's /nutrition (§7).

### 3c. Stat band (glanceable subset) — reads `DailyAggregateDto` (#3) + `TargetsDto` (#4)

Four cells — Calories · Protein · Carbs · Fat (the six-cell band with remaining
sub-lines and micros belongs to /nutrition):

| Display element | Source field |
|---|---|
| Big numeral | `caloriesActualSoFar` / `{macro}.actualSoFarG` |
| "/ target" suffix | `TargetsDto.calories.dailyTarget` / `{macro}.targetG` |
| Bar fill | actualSoFar ÷ target |
| Behind/over colouring | `TargetsDto.{macro}.direction` (LOWER_FLOOR amber-behind · UPPER_LIMIT amber-over · BOTH_BOUNDED either) |
| Band → /nutrition | whole band is a deep link |

Not shown here (on /nutrition): `caloriesPlanned`/`plannedG`, `*Remaining`,
fibre cell, sat-fat cell, `microsActualSoFar`, week strip, divergence banner.

### 3d. Needs attention — reads #5 + #6

| Display element | Source field |
|---|---|
| Section count badge | `NotificationSummaryDto.attentionCount` (+ `urgentCount` styled red); `unreadCount` feeds the shell bell, not this card |
| Row text | `NotificationDto.title` (top 3 unread, newest first) |
| Row icon | `NotificationDto.kind` → icon map; `severity` → colour |
| Row action | `actionTargetUri` → in-app navigation; null → opens /notifications |
| "View all" | deep link /notifications (full list, read/dismiss live there) |

Today renders rows read-only — no read/dismiss/actioned mutations here (§7).
`generatedAt` is cache metadata, not displayed.

### 3e. Week budget snapshot — reads `BudgetDto` (#7)

| Display element | Source field |
|---|---|
| Target figure | `weeklyTarget` + `currency` ("£60 weekly target") |
| Tolerance note | `toleranceOver` ("soft ceiling +£5") |
| Disabled state | `enabled=false` → card renders "budget tracking off" + /pantry link |
| Spent-so-far + bar | `spendTracking` — **always null in v1** (contract: "populated by 01f/01h once order history is wired"); v1 renders target-only and the mock's "£38.40 of £52" bar cannot be wired — §8 Q5. When non-null (v1.5): `currentWeekActual` / `currentWeekTarget` bar, `currentWeekRemaining` sub-line |
| 404 (not initialised) | card → "Set a weekly budget" deep link /pantry |

`id`, `userId`, `priceSensitivity`, `version` not on this page (Pantry owns the
editor; PUT budget lives there).

### 3f. Suggestion teaser — reads `PendingChangeListItemDto[]` (#8, top row only)

| Display element | Source field |
|---|---|
| Label | static "Suggestion · from your feedback" + `changeDimension` chip (SALT_LEVEL, PORTION_SIZE, …) |
| Title/body | `reasoningPreview` (≤200, nullable → fall back to dimension label) |
| Confidence pill | `confidence` (0–1) |
| Expiry hint | `expiresAt` ("expires in 3 days") |
| Recipe context | `recipeId` → s1 name join |
| **Review** (ghost) | deep link `/recipes/{recipeId}` (pending-change panel) or `/activity` |
| **Accept** (primary) | requires `expectedOptimisticVersion` — the list item doesn't carry it, so accept first fetches `GET /adaptation/pending-changes/{id}` then `POST …/{id}/accept` (two calls; 409 → re-fetch + "changed elsewhere") — §8 Q6 |
| Hidden when | list empty, or top item `impactScore` below display threshold (UI choice) |

`impactScore`, `createdAt` not displayed (ranking is server-side; Activity page
shows the full top-3 with before/after diffs).

### 3g. Quick snack log — writes #12 (`LogSnackRequest`)

Chips prefill the same request the Nutrition page's full form sends; Today keeps
chips + a minimal inline row only (full lookup-assisted form is /nutrition §3e):

| Control | Request field | Constraints |
|---|---|---|
| Chip / free-text | `freeText`* | 1–255 |
| Quantity | `quantityG`* | g, > 0 |
| Macros (prefilled by chip) | `calories`*, `proteinG`*, `carbsG`*, `fatG`* (+ optional `fibreG`, `micros`) | ≥ 0 |
| (not offered here) | `ingredientMappingKey`, `source`, `deductFromPantry` | lookup assist + source attribution live on /nutrition |

Success → re-fire #2 + #3 (band updates). 404 intake day → "generate a plan to
start logging" (same rule as the pilot §8).

## 4. Composite degradation

Each card fetches independently; one failure never blanks the page:

| Failing call | Card behaviour |
|---|---|
| #1 active plan 404 | no-plan CTA (§3a) — not an error |
| #2/#3 intake 404 | timeline shows planner states only, band hidden ("no intake day — generate a plan") |
| #4 targets 404 | band hidden + "set nutrition targets" link |
| #5/#6 | attention card hidden (bell still in shell) |
| #7 404 | "Set a weekly budget" link |
| #8 empty | teaser hidden |
| any 5xx | per-card retry chip, others unaffected |

## 5. Status-code → UI map

| Code | Where | UI behaviour |
|---|---|---|
| 404 | #1, #2/#3, #4, #7 | empty-state per §4 (not errors) |
| 409 invalid slot transition | #9 | toast w/ server detail; re-fetch #1 (another device advanced it) |
| 409 optimistic lock | #9 | silent re-fetch + one retry |
| 422 already decided | #10/#11 | swallow when the planner call succeeded (intake was logged earlier); otherwise toast + re-fetch #2 |
| 409 stale version | adaptation accept (§3f) | re-fetch detail + "suggestion changed — review again" |
| 400 | #12 | inline field errors on the snack row |
| 401 | all | global session-expired redirect |

## 6. (state machines specified in §3b — planner slot + intake status coupling)

## 7. Not on this page

The composite discipline: surface the glanceable figure, deep-link the rest.

| Contract item / capability | Home (deep link) |
|---|---|
| Intake override ("log what I ate"), structured edit, `needsAiParse` repair | /nutrition §3d |
| Snacks list + remove, lookup assist, `source` badges | /nutrition §3e |
| Remaining sub-lines, fibre/sat-fat cells, micros panel, divergence banner | /nutrition §3b/§3g |
| `GET intake/week/{weekStart}/aggregate` (week strip, floor violations) | /nutrition §3c |
| Journal (read/add/edit) | /nutrition §3f |
| Activity-level quick control (`PUT targets/activity/{date}`) | /nutrition §3a |
| Targets viewing/editing | /nutrition §4 |
| Plan grid, generate/accept/reject/abandon/revert, feasibility, history | /plan (plan.md) |
| Re-opt suggestions accept/reject + diff (`plans/suggestions`, …/accept) | /plan §3e — Today only mirrors the notification row |
| Notification read/dismiss/actioned, preferences, quiet hours | /notifications |
| Budget editing (`PUT provisions/budget`), pantry inventory, expiry detail, waste log | /pantry |
| `POST /provisions/cook-event` (pantry deduction) | Pantry/cook flow — not wired from Today in v1 (§8 Q1) |
| Pending-changes full top-3, before/after diff, reject | /activity |
| Grocery list, orders, substitutions | /groceries |
| Recipe detail (ratings, versions, substitutions) | /recipes/{id} |

## 8. Open questions (flagged, not resolved here)

1. **Plan-slot ↔ intake coupling is client-side dual-write.** The Flow-4
   auto-confirm leg is deferred (technical-architecture v1 note), so "Mark
   eaten" = `PATCH slot state EATEN` + `POST intake confirm` with no
   transaction. Order chosen: planner first (authoritative lifecycle), confirm
   second; a failure between them leaves EATEN+PENDING, which /nutrition can
   repair (confirm is idempotent). Also unresolved: should "Mark cooked" fire
   `POST /provisions/cook-event` (pantry deduction + underflow handling) from
   Today, or is cooking logged only from the recipe/cook surface? Backend gap
   candidate: a composed "slot eaten" operation or the event fan-out leg.
   **Resolved (2026-06-13, frontend-gaps P3):** dual-write accepted for v1 as
   documented (planner first; /nutrition repairs the gap); the composed
   operation / event fan-out leg is the v1.5 item. "Mark cooked" stays a
   planner-only state change from Today — `POST /provisions/cook-event` wiring
   is deferred to the cook-mode surface (v1.5) so pantry deduction has a single
   deliberate trigger.
2. **Skip semantics across the two machines.** Planner SKIPPED is terminal;
   intake skip zeroes the slot's contribution. If the user skips on /nutrition
   only, the planner slot stays PLANNED and is *pinned to original* in past
   days (re-opt rules) — divergent representations of "didn't happen." Needs a
   product ruling on whether Today's Skip is the only sanctioned path.
   **Resolved (2026-06-13, frontend-gaps P3):** ruled — Today's paired Skip is
   the sanctioned path; the /nutrition-only skip intentionally leaves the
   planner slot PLANNED. Divergence pinned in `lld/planner.md` (§enums) and
   `lld/nutrition.md` (Flow 5).
3. **Planner CUSTOM/SNACK slots have no intake row.** Nutrition pre-fill covers
   BREAKFAST/LUNCH/DINNER/SNACKS; a CUSTOM planner slot ("post-gym shake") gets
   planner-state buttons but no confirm target — and nutrition SNACKS is a
   day-level bucket, not slot-shaped. Confirm the join rule for non-core kinds.
   **Resolved (2026-06-13, frontend-gaps P3):** join rule pinned in
   `lld/nutrition.md` (Flow 5): CUSTOM → planner actions only, no intake
   target; planner SNACK ↔ the day's cumulative SNACKS bucket.
4. **Batch portion progress ("portion 3 of 5") not derivable.** The contract
   links slots by `batchCookSessionId` but exposes no cooked/consumed counter
   per session. Mock copy must degrade to a "batch-cooked" tag, or backend gap.
   **Resolved (2026-06-13, frontend-gaps P3):** "batch-cooked" tag accepted for
   v1; a per-session portion counter (provisions portions row is the natural
   source) is a v1.5 enrichment.
5. **Budget spent-so-far is null in v1.** `BudgetDto.spendTracking` ships null
   until grocery order history wires it (01f/01h); the card is target-only.
   Mock's spent bar stays behind a flag. (Alternative interim source: grocery
   cost projection — rejected here; it's a projection, not spend.)
6. **One-tap accept from the teaser needs a second fetch.**
   `PendingChangeListItemDto` lacks `optimisticVersion`; accept requires
   `expectedOptimisticVersion` from the detail GET. Either accept-from-Today
   does GET-then-POST, or the list DTO grows the version field (backend gap
   candidate), or Today's Accept becomes a deep link to /activity.

## 9. Mock deltas (to make the mock match this spec)

1. Replace `TodayState.slotMeta` hardcodes (time/meta/kcal/alert strings) with
   the §3b join: planner slot fields + intake `planned.calories` + recipe
   cache; serve time from `mealTime` (null-safe), lead hint derived.
2. `setSlotState` mock action: add the dual-write pairing — EATEN also fires
   intake confirm (already present), SKIPPED also fires intake skip (missing);
   add the Skip button to rows; surface 422-already-decided as a no-op.
3. Add the no-plan empty state (timeline CTA → /plan/generate) and per-card
   degradation (§4) — currently the page assumes every slice is seeded.
4. Needs-attention: source rows from the notifications slice (top-3 unread)
   with kind icons + `actionTargetUri` navigation, replacing the static
   `attention[]` seed; show `attentionCount` badge from the summary shape.
5. Budget card: retype on `BudgetDto`; render target + tolerance; put the
   spent/total bar behind a `spendTracking != null` guard (v1.5), seeding it
   null by default.
6. Suggestion teaser: retype on `PendingChangeListItemDto` (changeDimension
   chip, reasoningPreview, confidence pill, expiresAt); Accept performs
   detail-fetch-then-accept with `expectedOptimisticVersion`; Review deep-links
   /recipes/{recipeId}; drop the bespoke `TodaySuggestion` shape.
7. Stat band: keep 4 cells but add direction-aware colouring from
   `TargetsDto.{macro}.direction` (mock already does) and make the whole band a
   /nutrition link; remove any remaining-line creep (owned by /nutrition).
8. Snack chips: submit full `LogSnackRequest` (chips already carry req bodies)
   and refresh the aggregate; remove the bespoke quick-snack inline list in
   favour of a confirmation toast + band update.
