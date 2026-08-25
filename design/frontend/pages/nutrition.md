# Page spec — Nutrition (`/nutrition`)

The contract-complete specification: every endpoint this page consumes, and the UI
that each request field and response field demands. A control exists for every
writable field; a display home exists for every returned field (or an explicit
"not on this page" entry). Companion docs: [../ia.md](../ia.md),
[../design-language.md](../design-language.md).

**Pilot note:** this is the first page spec; its structure is the template for the
other 14.

---

## 1. Intent (HLD)

From `design/nutrition-model.md` + `lld/nutrition.md`:

- **Targets are absolute grams, not ratios** — "180 g protein" is a floor, not a %.
  Each macro carries `direction` (UPPER_LIMIT / LOWER_FLOOR / BOTH_BOUNDED),
  `enforcement` (daily floor vs weekly average) and `isHardFloor`. Protein is
  typically a hard lower floor; calories on a cut are a soft upper limit; saturated
  fat is always an upper limit.
- **Intake is pre-filled from the plan** — when a plan generates, each day's slots
  arrive PENDING with the planned recipe's full computed nutrition. Logging a normal
  day is one tap per meal ("confirm").
- **Planned vs actual side-by-side**, divergence visible. ≥15 % variance on any
  macro (with pending slots remaining) triggers the planner's mid-week re-opt — the
  page must make that state visible, not silent.
- **Free-text reality** — "I had a cheese sandwich instead" is a first-class path
  (override → AI parse → structured actuals), distinct from structured manual edit.
- **Journal is a diary, not a tracker** — free text tied (optionally) to a meal
  slot; consumed by the feedback classifier for context. No mood scales, no
  biomarkers.
- **Health directives are proposed, never auto-applied** — accept/reject/modify
  with a safety-gate verdict shown.
- **Micros are tracked from day one.** The HLD defers micro *display* to v2;
  **product owner has overridden this for v1** — micros render in a collapsed
  panel (deviation flagged in §8).

## 2. Endpoint inventory

The nutrition module exposes 31 endpoints; 26 are consumed by this page across
four tabs (**Overview · Targets · Directives · Data quality**). 5 are not for this
page (§7).

| # | Endpoint | Tab | When called |
|---|----------|-----|-------------|
| 1 | `GET /nutrition/intake/{date}` | Overview | On load + after every slot/snack action |
| 2 | `GET /nutrition/intake/{date}/aggregate` | Overview | On load + after every action |
| 3 | `GET /nutrition/intake/week/{weekStart}/aggregate` | Overview | On load (Monday-anchored) + after actions |
| 4 | `POST …/slots/{mealSlot}/confirm` | Overview | "Confirm" button |
| 5 | `POST …/slots/{mealSlot}/override` | Overview | "Log what I ate" free-text submit |
| 6 | `POST …/slots/{mealSlot}/edit` | Overview | Structured edit form save |
| 7 | `POST …/slots/{mealSlot}/skip` | Overview | "Skip" button |
| 8 | `POST /nutrition/intake/{date}/snacks` | Overview | Snack form submit |
| 9 | `DELETE …/snacks/{snackId}` | Overview | Snack row remove |
| 10 | `GET /nutrition/journal/{date}` | Overview | On load |
| 11 | `POST /nutrition/journal/{date}` | Overview | Journal add |
| 12 | `PUT …/journal/{date}/entries/{entryId}` | Overview | Journal inline edit |
| 13 | `DELETE …/journal/{date}/entries/{entryId}` | Overview | Journal remove |
| 14 | `GET /nutrition/journal/recent?page&size` | Overview | "Earlier entries" expander (lazy) |
| 15 | `PUT /nutrition/targets/activity/{date}` | Overview | Activity-level quick control |
| 16 | `GET /nutrition/targets/activity?from&to` | Overview | With week strip (shows day badges) |
| 17 | `GET /nutrition/ingredients/lookup?term` | Overview | Snack-form autofill assist (debounced) |
| 18 | `GET /nutrition/targets` | Targets (+ Overview reads cached copy) | On load; 404 → initialise CTA |
| 19 | `POST /nutrition/targets/initialise` | Targets | Empty-state CTA |
| 20 | `PUT /nutrition/targets` | Targets | Save (with `expectedVersion`) |
| 21 | `GET /nutrition/health-directives?status&page&size` | Directives | Tab open + filter change |
| 22 | `GET /nutrition/health-directives/{id}` | Directives | Row expand |
| 23 | `POST …/health-directives/{id}/accept` | Directives | Accept button |
| 24 | `POST …/health-directives/{id}/reject` | Directives | Reject button |
| 25 | `POST /nutrition/ingredients/search` | Data quality | Search submit |
| 26 | `GET /nutrition/ingredients/needs-review?page&size` | Data quality | Tab open |
| 27 | `PUT /nutrition/ingredients/{searchTerm}/correction` | Data quality | Correction form save |
| — | `GET /nutrition/intake?from&to` | (range view — v1.1 history page) | not in v1 page |

## 3. Overview tab — anatomy & field mapping

### 3a. Day header
- Date + prev/next day navigation (re-fires #1, #2, #10).
- **Activity quick control** — segmented selector with the 4
  `activityLevel` enum values (REST_DAY, LIGHT_ACTIVITY, TRAINING_DAY,
  HEAVY_TRAINING) + optional `notes` (≤255) in a popover → `PUT activity/{date}`
  (#15). Selected level shows as a chip; targets band footnote shows the applied
  `calorieModifier`/`carbModifierG` from `TargetsDto.activityAdjustments` when a
  non-default level is set.
- **Divergence banner** (conditional): when any macro variance ≥15 % and pending
  slots remain — advisor-voice card: "Today has drifted from plan — re-optimise
  the rest of the week?" → links to Plan re-opt. (Signal arrives via
  notifications; the page recomputes the same condition client-side from #2.)

### 3b. Daily stat band — reads `DailyAggregateDto` (#2)
Six cells (extends the current four): **Calories · Protein · Carbs · Fat · Fibre
· Sat fat**.

| Display element | Source field |
|---|---|
| Big numeral | `caloriesActualSoFar` / `{macro}.actualSoFarG` |
| "/ target" suffix | `TargetsDto.calories.dailyTarget` / `{macro}.targetG` |
| Segment bar fill | actualSoFar ÷ target |
| **Remaining** sub-line | `caloriesRemaining` / `{macro}.remainingG` ("740 kcal left") |
| Behind/over colouring | from `TargetsDto.{macro}.direction`: LOWER_FLOOR → amber when behind pace; UPPER_LIMIT → amber when **over**; BOTH_BOUNDED → either side |
| Hard-floor marker | `isHardFloor=true` → small ▪ on the label, tooltip "hard floor" |

### 3c. Week strip — reads `WeeklyAggregateDto` (#3)
- 7 day-columns (Mon-anchored): per-day kcal numeral + mini bar from
  `perDay[i]`; today highlighted; activity badge per day from #16.
- `weeklyTotal` cell at the right.
- **`floorViolations[]`** → red chips under the strip ("protein floor missed ·
  Tue"), each names the macro/micro key. This is the page's only red (danger).

### 3d. Today's slots — reads `IntakeDayDto.slots[]` (#1)
One row per slot (BREAKFAST / LUNCH / DINNER / SNACKS header excluded — snacks are
§3e). Row layout: slot label + time (from household slot config) | planned column |
actual column | state chip + actions.

| Display element | Source field |
|---|---|
| Recipe name | `planned.recipeId` → recipe lookup (joins recipes cache) |
| Planned kcal/macros | `planned.{calories, proteinG, carbsG, fatG, fibreG}` |
| Actual values (once decided) | `actual.{same fields}` |
| Planned-vs-actual delta | computed; shown when |Δ| > 10 % ("+130 kcal vs plan") |
| State chip | `actual.status` — PENDING ○ · CONFIRMED ✓ olive · OVERRIDDEN ✎ olive ("logged: free text") · EDITED ✎ olive · SKIPPED — muted |
| Free text shown | `actual.overrideFreeText` (italic, quoted) on OVERRIDDEN rows |
| Parse-failed banner | `actual.needsAiParse=true` → amber inline banner "Couldn't read that — enter values manually" + Edit CTA |

**Buttons (state machine — no backwards transitions, decided rows show no actions):**

| State | Actions |
|---|---|
| PENDING | **Confirm** (primary, terra — #4, one tap, credits planned values) · **Log what I ate** (ghost — opens free-text popover, 1–512 chars → #5) · **Edit values** (ghost — structured form: `calories`*, `proteinG`*, `carbsG`*, `fatG`*, `fibreG`, advanced: `micros` map → #6) · **Skip** (ghost — #7) |
| CONFIRMED / EDITED | result line only ("✓ 520 kcal logged") |
| OVERRIDDEN | result + quoted free text; if `needsAiParse` → Edit CTA (**resolved — backend now allows `POST /edit` from OVERRIDDEN when `needsAiParse=true`**: repairs to EDITED, clears the flag, keeps the free text; parse-success OVERRIDDEN stays terminal, 422) |
| SKIPPED | "— skipped · 0 kcal" |

### 3e. Snacks — reads `IntakeDayDto.snacks[]`, writes #8/#9
List rows: `freeText` · `quantityG` ("120 g") · `calories` + macro mini-pills ·
`source` badge (USDA / OFF / manual) · `loggedAt` time · remove ✕ (#9).

**Add-snack form** (replaces quick-add chips; chips remain as shortcuts that
pre-fill the form):

| Control | Request field | Notes |
|---|---|---|
| Free-text input* | `freeText` (1–255) | typing fires debounced lookup assist (#17) |
| Lookup suggestion row | `ingredientMappingKey` + per-100 g nutrition | picking one autofills macros = per100g × quantity/100, sets `source` |
| Quantity input + unit hint* | `quantityG` (g) | uses `defaultPieceGrams` from lookup when present ("1 piece ≈ 50 g") |
| Macro inputs* | `calories`, `proteinG`, `carbsG`, `fatG` (+optional `fibreG`) | editable after autofill |
| Advanced expander | `micros` map | key/value rows |
| Source (auto) | `source` enum | set by lookup path; MANUAL when hand-entered |
| Pantry toggle | `deductFromPantry` | **disabled, tooltip "coming in v1.5"** (reserved no-op field) |

### 3f. Journal — #10–#14
- Entry rows: `journalEntry` text · optional `mealSlot` chip · `loggedAt` time ·
  edit (inline, PUT with `optimisticVersion` → `expectedVersion`) · delete.
- Add form: text area (1–4000)* + optional meal-slot select (4 enum values or
  "whole day") + Add. 409 on save → reload entry + "changed elsewhere" toast.
- "Earlier entries" expander → #14 paginated.

### 3g. Micronutrients panel (collapsed by default)
Reads `DailyAggregateDto.microsActualSoFar` (map) joined against
`TargetsDto.microTargets[]`:

| Display element | Source |
|---|---|
| Row per nutrient | union of `microTargets[].nutrientKey` and actual map keys |
| Actual / target | map value / `targetValue` |
| Upper-limit nutrients | `upperLimit` set (e.g. sodium) → bar colours amber when over |
| Hard-floor marker | `isHardFloor` |
| Note tooltip | `notes`, `sourcePreference` |

## 4. Targets tab — `TargetsDto` ⇄ `UpdateTargetsRequest` (#18–#20)

- **Empty state (404):** advisor card "No targets yet" → **Initialise from your
  lifestyle** button (#19; seeds DRI micros). 409 (already exist) → reload.
- **Goal** — segmented control: LOSE_WEIGHT / MAINTAIN / GAIN_WEIGHT.
- **Calories row** — `dailyTarget` stepper+input, `toleranceUnder`/`toleranceOver`
  inputs, `direction` select, enforcement select.
- **Per-macro rows** (protein, carbs, fat, fibre, satFat) — `targetG`, `floorG`,
  `direction` select, `enforcement`, `isHardFloor` toggle (▪). Rows the user has
  changed direction on are listed back via `userOverriddenDirections` → "custom"
  badge.
- **Per-meal distribution** (≤4 rows) — mealSlot enum + `calorieTarget` +
  `proteinTargetG`; caption: "guideline, the planner may redistribute".
- **Eating window** — `enabled` toggle + `windowStart`/`windowEnd` time inputs +
  `notes`.
- **Activity adjustments** (≤4 rows) — activityLevel enum + `calorieModifier` +
  `carbModifierG`.
- **Micro targets** (≤30 rows) — `nutrientKey`, `targetValue`, `upperLimit`,
  `isHardFloor`, `notes`.
- **Save** — full-replacement PUT with `expectedVersion` = loaded `version`;
  `notes` (≤512) optional change note. **409 → conflict card**: "Targets changed
  since you opened this (likely an accepted health directive)" → reload + re-apply
  prompt. Audit-log link omitted in v1 (admin surface).

## 5. Directives tab (#21–#24)

- Status filter chips: PENDING_REVIEW (default) / ACCEPTED / REJECTED /
  SUPERSEDED / EXPIRED. Paginated list.
- Row: `sourcePlatform` + `directiveType` chip + `receivedAt` + status chip +
  `temporary` ⏱ badge (`autoExpiresAt` tooltip).
- Expanded detail: `evidenceSummary` (plain text) + `evidenceConfidence` chip
  (LOW/MODERATE/HIGH); `instruction` rendered as action/target/scope/duration
  (phases table for ELIMINATION_TRIAL / REINTRODUCTION_PROTOCOL); **safety gate**:
  `safetyGateVerdict` (PASSED olive / PASSED_WITH_WARNINGS amber / BLOCKED red) +
  `safetyGateFindings[]` rows (severity-coloured `message`).
- **Accept** (primary; disabled when verdict BLOCKED) → optional "modify before
  accepting" expander editing a copy of `instruction` → `userModification`; sends
  `expectedVersion`. **Reject** (ghost) → optional `rejectionReason` (≤255).
  409 → reload row ("decided elsewhere / superseded").
- Decided rows show `decidedAt` + `userModification`/`rejectionReason` read-only.

## 6. Data quality tab (#25–#27)

- **Needs review** list (paginated): `searchTerm` · `source` badge · `confidence`
  pill (amber < 0.85) · `lastVerifiedAt`.
- Row expand → per-100 g table from `nutritionPer100g` (calories, proteinG,
  carbsG, fatG, fibreG, saturatedFatG, sugarG + micros/vitamins maps) →
  **Correct** form: editable copy of all fields → `PUT correction` with
  `expectedVersion`; on success source flips MANUAL, confidence 1.0, row leaves
  the queue. 409 → reload.
- **Search** box → #25 (`query`*, `maxResults` 1–20): results as the same rows;
  `cacheOnly=true` (v1) → caption "searching your cache — live USDA search lands
  in a later release".

## 7. Not on this page

| Contract item | Home |
|---|---|
| `GET targets/audit-log`, `GET intake/{date}/audit-log`, `GET intake/search` | Admin / future history page |
| `POST health-directives/inbound` | Platform webhook (no UI) |
| `POST recipes/{id}/versions/{vid}/recalculate` | Recipe detail page ("Recalculate nutrition" action) |
| `POST floor-gate/evaluate` | Planner internal (generation feasibility) |
| `GET intake?from&to` (range) | v1.1 intake-history view |

## 8. Status-code → UI map

| Code | Where | UI behaviour |
|---|---|---|
| 404 targets | Targets/Overview | Initialise CTA (empty state, not an error) |
| 404 intake day | Overview | "No plan covered this day" empty state + Log-snack still available? **No — snacks require an intake day; show 'generate a plan to start logging'** |
| 409 stale version | Targets save, journal edit, correction, directive decide | Conflict card → reload + reapply |
| 422 | Slot transition (already decided), snack validation | Toast with server message; refresh slot row |
| 400 | Any form | Inline field errors |

**Open questions (flagged, not resolved here):**
1. OVERRIDDEN + `needsAiParse=true` has no legal repair transition (edit requires
   PENDING). Backend gap candidate: allow `edit` from OVERRIDDEN-unparsed, or the
   UI's only honest remedy is "log a corrective snack". → raise as backend ticket.
   **Resolved (ticket `nutrition-intake-override-repair`)**: `POST /edit` is now
   legal from OVERRIDDEN + `needsAiParse=true` → EDITED, `needsAiParse=false`,
   `overrideFreeText` retained; any other decided state still 422s. The §3d Edit
   CTA wires directly to #6 — drop the corrective-snack workaround.
2. Micros visible in v1 deviates from HLD's "macros only in v1" — **accepted by
   product owner 2026-06-11** (collapsed panel).

## 9. Mock deltas (to make the mock match this spec)

1. Stat band: 4 → 6 cells; add remaining sub-line; direction-aware colouring.
2. Slot rows: add OVERRIDDEN/EDITED states, free-text override popover, structured
   edit form, needsAiParse banner; remove "Edit kcal"-only flow.
3. Snack quick-add chips → full form w/ lookup assist + quantity + source.
4. Week strip: add per-day macros on hover + floor-violation chips + weekly total.
5. Add: activity quick control, divergence banner, micros panel.
6. Targets: replace ± steppers with the full Targets tab (goal, directions,
   floors, per-meal, eating window, activity adjustments, micro targets,
   version-conflict state).
7. Add Directives + Data quality tabs (seed 2 directives — one with warnings, one
   blocked — and 3 needs-review ingredients).
8. Journal: add meal-slot selector + edit/delete + pagination.

## 10. Amendments (2026-06-12)

Dated corrections from the pilot mock rebuild. Each amends the referenced
section; the original text above is left as written.

- **(a) §3b — sat-fat cell has no aggregate field.** The band's sixth cell
  currently reads `DailyAggregateDto.microsActualSoFar["saturated_fat_g"]` —
  `DailyAggregateDto` carries macro aggregates for protein/carbs/fat/fibre only,
  no satFat `MacroAggregateDto` (so no `plannedG`/`remainingG` for the cell,
  and the value rides the micros map by key convention). Backend ticket
  pending: add a `satFat` aggregate to `DailyAggregateDto`. Until then the cell
  renders actual-vs-target only (target from `TargetsDto.satFat`), with no
  remaining sub-line. *Resolved 2026-06-12:* `DailyAggregateDto.satFat`
  (`plannedG`/`actualSoFarG`/`remainingG`) shipped — the cell reads it directly;
  `microsActualSoFar["saturated_fat_g"]` is retained for one release.
- **(b) §3c — `floorViolations` is key-only.** `WeeklyAggregateDto.floorViolations`
  is `string[]` of macro/micro keys ("weekly total fell below 7-day-summed
  floor") with **no day attribution**; the "protein floor missed · Tue" day
  labels are derived client-side by scanning `perDay[i]` against the summed
  floor. The schema already defines an unused `FloorViolationDto`
  (`{date, macroOrMicro, floor, actual}`) — backend ticket pending: have
  `floorViolations` adopt it. Until then chips may name the macro without a
  reliable day ("protein floor missed this week") when per-day derivation is
  ambiguous. *Resolved 2026-06-12:* `floorViolations` now carries
  `FloorViolationDto[]` — daily-enforcement floors arrive dated (one entry per
  violating tracked day), weekly-average floors arrive with `date: null`
  ("missed this week"); no client-side `perDay` scanning needed.
- **(c) §3d — `planned.recipeId` is nullable.** `PlannedIntakeDto.recipeId` is
  null for slots whose plan slot has no scheduled recipe (eating-out, stripped
  or unfilled slots). Display fallback: the active plan-day slot's `label`
  (via `plans/active` join); failing that, the meal-slot enum name. No recipe
  deep-link on such rows.
- **(d) §3a — "default" activity = no row for that date.** Verified against
  `lld/nutrition.md` + the shipped module: `DailyActivityLog` rows exist only
  when the user sets a level (last-write-wins per `(user, date)`); when absent,
  `GET targets/activity` simply returns no entry for that date and **no
  activity adjustment is applied** — targets render unmodified. Absent-row is
  *not* REST_DAY: REST_DAY is an explicit level carrying its own modifiers
  (HLD example: `rest_day.carb_modifier_g = -30`, while `light_activity` is the
  zero-modifier row). UI rule: no chip and no targets-band footnote on dates
  without a row; the segmented control shows no selection until the user picks.
- **(e) 2026-08-25 — retrospective plan-vs-target wiring (t5, D-0007).** The
  Overview is the retrospective lens ("how did my day/week actually do against
  my targets"): day view defaulting to today with the week strip alongside
  (FC1/FC3 — no new tab). EARS criteria, verifier-executable:
  - **B1** When the app boots with `VITE_LIVE=1` and an active plan exists, the
    Overview day band, slot rows, and micros panel shall render backend data
    for **every** day of the current week (not just today), and stepping days
    shall not fabricate empty data where the backend has intake rows. (Wired:
    boot hydration fetches all seven `intake/{date}` days plus
    `intake/week/{weekStart}/aggregate`.)
  - **B2** When a tracked micro has both a target and an actual value for the
    viewed day, its row shall show actual vs target, with the amber treatment
    when an `upperLimit` is exceeded and the hard-floor marker when
    `isHardFloor=true`. Rows render the shared `NutrientRow` grammar
    (`src/components/NutrientRow.tsx`) — one component with the Plan page's
    projection panel, two lenses.
  - **B3** When the weekly aggregate returns `floorViolations`, the week strip
    shall show one chip per violation — named day for dated entries (daily-
    enforcement floors), "this week" phrasing for `date: null` entries
    (weekly-average floors). Chips read the DTO directly; no client-side
    per-day derivation.
  - **B4** When the user has no targets (404), Overview and Targets tab shall
    show the initialise CTA as an empty state, never an error (§8). Live
    hydration stores `targets: null` on 404 — no fixture fallback.
  - **B5** When a tracked micro appears in no decided slot or snack of the day,
    its row shall present as "no data", not 0 of target: muted, inline in
    nutrient order (FC5), target kept visible, empty bar, never the warn
    treatment — the same NO_DATA grammar as the projection lens. The panel
    reads `DailyAggregateDto.micros[]` per-micro status (D-0008 resolved gap
    G1): MEASURED rows carry the summed actual (a measured zero stays 0),
    NO_DATA rows carry a null value. The plain `microsActualSoFar` map remains
    for map-convention consumers only.
