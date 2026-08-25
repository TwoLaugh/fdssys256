# Ticket: plan-vs-target surface — "did my plan / my week hit my targets, per nutrient"

Spec ticket (docs only). Implementation is a follow-up task gated on the FORCED CHOICES
below being decided by the product owner. Structure follows `tickets/frontend-gaps/*`;
acceptance criteria are EARS-style so a verifier can execute them against the live stack
(`docs/RUNBOOK-LIVE-STACK.md`) or the mock fixtures.

Every capability claim below is cited file:line against branch `docs/t5-plan-vs-target-spec`
(base: main `b466d73`) or labelled UNVERIFIED / [DISCOVER].

## Summary — and a premise correction

`state.md` (workstream B) says the plan-vs-target surface is "not yet built". Half of it is
built: the **projection** lens ("will this plan hit my targets") already renders on the Plan
page as the `PlanNutritionPanel` — landed inside the experiment-branch WIP snapshot
`3a91595`, never specced, never ticketed, typed through an `as unknown as` cast
(`frontend/src/pages/Plan.tsx:911-915`), and invisible in mock/design mode because the plan
fixtures carry no coverage data (`frontend/src/mock/store.ts:1170-1200`,
`rollupSummary` has `daily`+`weekly` only).

What this ticket therefore specs:

1. **Promote the existing projection panel from WIP to specified surface** — pin its
   behaviour with acceptance criteria, give it real types and mock fixtures, and add the
   missing page-spec section to `design/frontend/pages/plan.md` (which currently has zero
   coverage mentions).
2. **Spec the retrospective lens** ("how did my day/week actually do against my targets,
   per nutrient") on the Nutrition page's Overview tab, and its live wiring — today the
   Overview is mock-complete (`design/frontend/pages/nutrition.md` §3, built in
   `frontend/src/pages/nutrition/OverviewTab.tsx`) but live hydration fills only today's
   intake day and no aggregates (`frontend/src/live/hydrate.ts:171,332`).
3. **Name the backend gaps** that bound what the retrospective lens can honestly show
   (G1 below: intake-side NO_DATA is unrepresentable).

## What the backend + contract actually expose (verified inventory)

### Projection: `rollupSummary.nutritionCoverage` (per plan)

- Declared in the contract at
  `src/main/resources/openapi/schemas/planner.yaml:137-163` (T8 commit `2a4e982`):
  nullable object on `RollupSummaryDocument` with `macros[]`, `micros[]` (both
  `NutritionTargetCoverageDocument`), counts `macrosMet/macrosTotal/microsMet/microsTotal/
  microsNoData`, and a display-only nullable `fatBreakdown` (sat/mono/poly g/day).
  Null on plans generated before coverage shipped and when the user has no targets
  (comment at `planner.yaml:138-139`).
- Per-target row: `NutritionTargetCoverageDocument`, `planner.yaml:103-127` —
  `key`, `unit`, `target`, `projectedDailyAvg` (null when NO_DATA), `direction`
  (LOWER_FLOOR | UPPER_LIMIT | BOTH_BOUNDED), `met`, `status` (MET | SHORT | NO_DATA),
  `source` (measured | derived | estimated; null when NO_DATA).
- Semantics (introduced by `bd149c3`, current code):
  `src/main/java/com/example/mealprep/planner/domain/service/internal/rollup/RollupBuilderImpl.java`
  - Weekly-average projection: per-day plan totals averaged over the plan's days
    (`:167-188`). There is **no per-day projected coverage** — `DailyRollupDocument`
    carries kcal/macros/cost/time/violation strings only (`planner.yaml:60-74`).
  - Macros always scored: calories, protein, carbs, fat, fibre; `saturated_fat` only when
    a satFat target is set (`:206-222`). Macro rows are always "measured"
    (`NutritionTargetCoverageDocument.java:28`).
  - Micros scored only for targets that exist and carry a floor or cap (`:230-234`).
  - **NO_DATA rule**: a micro absent from every plan recipe is UNKNOWN, not zero —
    `status=NO_DATA`, `projectedDailyAvg=null`, excluded from the short count
    (`:235-244`, `:260`).
  - Provenance: worst source across the week wins — any AI-estimated contribution makes
    the row "estimated" (`:190-204`, `:245`).
  - Coverage is computed for the **primary eater only** (first eater with a targets row,
    `:162-163`, `:280-295`); null coverage when that user has no targets or the plan has
    no days (`:164-166`).
- Returned by every endpoint serving `PlanDto` (`planner.yaml:343`, `rollupSummary`
  required at `:358`, `:384`): `getPlan`, `getActivePlan`
  (`src/main/resources/openapi/paths/planner.yaml:4,38`), history/range variants.

### Targets: cal + protein + macros + 28 micros, DRI-seeded

- `GET /api/v1/nutrition/targets` → `TargetsDto`; 404 until initialised
  (`src/main/resources/openapi/paths/nutrition.yaml:1-24`).
- `POST /api/v1/nutrition/targets/initialise` → creates the aggregate and DRI-seeds any
  micro the request omits, from `nutrition_dri_defaults` (`paths/nutrition.yaml:61-73`).
- `TargetsDto` (`schemas/nutrition.yaml:77-127`): calories + per-macro
  `MacroTargetDto` rows (protein/carbs/fat/fibre/satFat, each with `direction`,
  enforcement, `isHardFloor` — `:25-45`) + `microTargets[]` (`MicroTargetDto`:
  `nutrientKey`, `targetValue`, `upperLimit`, `isHardFloor`, `notes` — `:46-76`).
- DRI seed: **28 tracked micronutrients** × 3 adult age groups × 2 sexes, NIH ODS values,
  plus pregnancy/lactation life-stage floors
  (`src/main/resources/db/migration/R__nutrition_seed_dri_defaults.sql:6-12`; keys:
  vitamins A/C/D/E/K, B-complex incl. B6/B12/folate/thiamin/riboflavin/niacin/biotin/
  pantothenic acid, choline, calcium, iron, magnesium, zinc, phosphorus, potassium,
  sodium, chloride, copper, manganese, selenium, iodine, chromium, molybdenum).

### Retrospective: intake aggregates (actuals)

- `GET /nutrition/intake/{date}/aggregate` → `DailyAggregateDto` (zero-valued when no
  intake row; `paths/nutrition.yaml:1053-1070`), and
  `GET /nutrition/intake/week/{weekStart}/aggregate` → `WeeklyAggregateDto`
  (Monday-anchored; `:1079-1091`).
- `DailyAggregateDto` (`schemas/nutrition.yaml:873-887`): calories planned/actual/
  remaining, `MacroAggregateDto` for protein/carbs/fat/fibre/satFat, and
  `microsActualSoFar` as a **plain string→number map**.
- `WeeklyAggregateDto` (`:857-872`): `perDay[]`, `weeklyTotal`, `floorViolations[]` as
  `FloorViolationDto` (`:845-856`) — daily-enforcement floors arrive dated, weekly-average
  floors arrive with `date: null` (`design/frontend/pages/nutrition.md` §10 (b), resolved).

### Frontend today (the pattern to follow)

- Live mode: `VITE_LIVE=1` hydrates the mock store from the backend once at boot; pages
  read the store unchanged (`frontend/src/live/flag.ts:1-9`,
  `frontend/src/live/hydrate.ts:1-10`). Today page is the live-wired reference
  (state.md workstream B; `frontend/src/pages/Today.tsx:187-216` renders the daily
  actual-vs-target band from the hydrated intake day + targets).
- Routes (`frontend/src/App.tsx:30-38`): `/` Today, `/plan`, `/nutrition` (4 tabs).
- Plan page renders the projection panel when coverage is present
  (`Plan.tsx:52-276` panel, `:1031` mount).
- Nutrition Overview (mock-complete per `design/frontend/pages/nutrition.md` §3): six-cell
  stat band, week strip + floor-violation chips, slot rows, collapsed micros panel
  (`OverviewTab.tsx:189-242,1166-1235`).

## Scope

The surface a trial user sees for "how did my day/week do against my targets, per
nutrient", split across the two questions they actually ask:

- **Plan page (`/plan`) — projection**: "will this plan hit my targets?" One row per
  scored macro + micro target: projected daily average vs target, MET/SHORT/NO_DATA,
  provenance badge, fat spread. Promote the existing panel; no new backend.
- **Nutrition Overview (`/nutrition`) — retrospective**: "how is my day/week actually
  going?" Existing band + micros panel + week strip, live-wired for the whole current
  week, with floor-violation chips from the weekly aggregate. Honest NO_DATA on the
  actuals side is bounded by gap G1.
- **Today (`/`)**: unchanged (its band already answers "today so far"); gains nothing but
  a link to the Nutrition Overview. Not a new surface.

No new routes. No per-day projected coverage (backend has none — see inventory).

## Acceptance criteria (EARS)

"Coverage" = `rollupSummary.nutritionCoverage` of the viewed plan. A verifier executes
these against the live stack seeded per the runbook, or against mock fixtures that
include coverage data (in-scope work item W3).

### Projection panel (Plan page)

- **A1** When the viewed plan's coverage is non-null, the Plan page shall render a
  "Plan vs your targets" panel with one row per entry of `coverage.macros` and a summary
  line reading `{macrosMet}/{macrosTotal} macros · {microsMet}/{microsTotal −
  microsNoData} micros met`, appending `· N no data` when `microsNoData > 0`.
- **A2** When the viewed plan's coverage is null, the Plan page shall render no panel and
  no error or placeholder.
- **A3** When a row has `status=MET`, the row shall show `projectedDailyAvg` with unit and
  target and no warning treatment.
- **A4** When a row has `status=SHORT`, the row shall show the value in the warning
  (amber) treatment with a short-of-target marker; the count of rows so treated shall
  equal the number of non-NO_DATA rows with `met=false`.
- **A5** When a row has `status=NO_DATA` (or `projectedDailyAvg=null`), the row shall
  render a muted "no data" with the target still shown, an empty bar, and shall never
  render a zero value or the SHORT treatment.
- **A6** When a row's `direction=UPPER_LIMIT`, the target shall render as an upper bound
  ("≤ N") and met shall mean at-or-under.
- **A7** When a row's `source` is `derived` or `estimated`, a provenance badge with an
  explanatory tooltip shall render; `measured` rows shall carry no badge.
- **A8** When `fatBreakdown` is non-null with any unsaturated value, the panel shall
  render the saturated/mono/poly line as informational text (no target treatment).
- **A9** When micro rows exist, the default visible set shall follow FC2's decision, with
  a control to reach all rows.
- **A10** When the seeded dev user generates a plan on the live stack, the panel shall
  show the five always-scored macro rows (calories, protein, carbs, fat, fibre;
  saturated_fat additionally iff a satFat target is set) and one row per DRI-seeded micro
  target that carries a floor or cap.

### Retrospective (Nutrition Overview, live mode)

- **B1** When the app boots with `VITE_LIVE=1` and an active plan exists, the Overview
  day band, slot rows, and micros panel shall render backend data for **every** day of
  the current week (not just today), and stepping days shall not fabricate empty data
  where the backend has intake rows. (Wiring change W4.)
- **B2** When a tracked micro has both a target and an actual value for the viewed day,
  its row shall show actual vs target, with the amber treatment when an `upperLimit` is
  exceeded and the hard-floor marker when `isHardFloor=true`.
- **B3** When the weekly aggregate returns `floorViolations`, the week strip shall show
  one chip per violation — named day for dated entries, "this week" phrasing for
  `date: null` entries.
- **B4** When the user has no targets (404), Overview and Targets tab shall show the
  initialise CTA as an empty state, never an error (per `nutrition.md` §8).
- **B5** When a tracked micro appears in no decided slot or snack of the day, its row
  shall present as "no data", not as 0 of target. **BLOCKED on gap G1** — until G1 is
  resolved this criterion is waived and the row shall render the current `0` with the
  caveat noted in the page spec.

### NO_DATA handling (explicit, both lenses)

NO_DATA means *unmeasured*, never *zero* and never *failed*:

- Never contributes to short counts (backend guarantees this for projection:
  `RollupBuilderImpl.java:244,260`).
- Never renders as a numeric 0 or a filled/zero-progress amber bar.
- Always keeps the target visible so the user sees what was supposed to be measured.
- The summary arithmetic uses `assessed = microsTotal − microsNoData` as the denominator.
- Placement of NO_DATA rows follows FC5.

## FORCED CHOICES (product owner decides; implementation blocked on these)

- **FC1 — Where does the retrospective surface live?**
  - (a) Upgrade the existing Nutrition Overview (micros panel + week strip) — no new
    navigation, but the page is already dense. **Recommended.**
  - (b) New "Report" tab on the Nutrition page — clean weekly read, one more tab to learn.
  - (c) Actuals column added to the Plan page's projection panel — one place for both
    lenses, but conflates "what the plan promised" with "what you logged".
- **FC2 — Which micros show by default in the Plan projection panel?**
  - (a) Only SHORT rows, with a "show all N" toggle (current WIP behaviour) — least
    noise, surfaces problems first. **Recommended.**
  - (b) All ~28 always — nothing hidden, but a long list dominates the page.
  - (c) Summary counts only, rows behind an expander — quietest, hides the centerpiece.
- **FC3 — Default period for the retrospective view?**
  - (a) Day view defaulting to today, week strip alongside (matches the shipped page
    spec §3 and the built Overview). **Recommended.**
  - (b) Week-first report with day drill-in — matches "how did my week do" verbatim but
    diverges from the built page and needs new layout.
- **FC4 — SHORT colouring: binary or banded?**
  - (a) Binary: the backend's `met`/`status` drives amber, no client-side thresholds —
    no authored numbers (habits.md #1). **Recommended.**
  - (b) Near-miss band (e.g. ≥90 % of floor gets a softer tone) — friendlier, but the
    90 % is authored scaffolding and must be labelled as such.
  - (c) Red for hard-floor misses (`isHardFloor`), amber otherwise — uses data the
    targets already carry; only differentiates where users set hard floors.
- **FC5 — Where do NO_DATA rows sit?**
  - (a) Inline in nutrient order, muted (current WIP) — visible honesty. **Recommended.**
  - (b) Grouped at the bottom under a "no data (N)" divider — cleaner scan, reorders keys.
  - (c) Count-only in the summary, rows hidden — quiet, but the trial cares *which*
    micros are unmeasured.
- **FC6 — Multi-eater households: whose targets?** (Projection is primary-eater only,
  `RollupBuilderImpl.java:280-295`.)
  - (a) Label the panel with the primary eater's name and ship — honest, zero backend
    work. **Recommended.**
  - (b) Hide the panel for multi-adult households until per-user coverage exists.
  - (c) Build per-user coverage now — real backend work, outside this appetite.

## Backend / wiring gaps discovered

- **G1 — retrospective NO_DATA is unrepresentable.** `DailyAggregateDto.microsActualSoFar`
  is a plain map (`schemas/nutrition.yaml:885-887`) built by merging slot + snack micros
  JSONB (`IntakeAggregator.java:111-137`): a micro no decided source carried simply never
  appears, indistinguishable from a measured zero — the same conflation `bd149c3` fixed on
  the planner side. Blocks B5 only. Fix candidates: an aggregate-side per-micro status
  (mirror `NutritionTargetCoverageDocument`), or a documented absent-key-means-no-data
  rule. [DISCOVER] whether a genuine measured 0 is ever emitted as a present `0` entry —
  if absence already implies "no data" reliably, the documented-rule fix suffices.
- **G2 — generated API types are stale.** `frontend/src/api/types.gen.ts` predates the T8
  spec fix: no `nutritionCoverage`. Regen via `npm run codegen`
  (`frontend/package.json:10`). Trivial.
- **G3 — the panel is untyped and invisible in design review.** Mock `PlanDto` lacks
  `rollupSummary.nutritionCoverage` (cast at `Plan.tsx:911-915`); mock plan fixtures carry
  no coverage (`store.ts:1170-1200`), so mock mode never renders the panel.
- **G4 — live hydration under-fetches the retrospective data.** Only today's intake day is
  hydrated (`hydrate.ts:171`) and `aggregates` is left empty (`hydrate.ts:332`); the
  weekly aggregate endpoint exists and is unused (`paths/nutrition.yaml:1079`). Blocks B1
  until fixed (in-scope wiring, not a backend change).

## Work items (implementation task, after FORCED CHOICES land)

```
W1  MOD  design/frontend/pages/plan.md          new § for the projection panel (A1-A10)
W2  MOD  design/frontend/pages/nutrition.md     amendment: live-wiring + NO_DATA rules (B1-B5)
W3  MOD  frontend/src/mock/types.ts + store.ts  type rollupSummary.nutritionCoverage; fixture
                                                coverage incl. SHORT + NO_DATA rows; drop the cast
W4  MOD  frontend/src/live/hydrate.ts           hydrate the week's intake days + weekly aggregate
W5  MOD  frontend/src/pages/Plan.tsx            apply FC2/FC5/FC4 decisions to the panel
W6  MOD  frontend/src/pages/nutrition/*.tsx     apply FC1/FC3 decisions
W7  NEW  ticket for G1                          backend, only if B5 is wanted for the trial
G2  RUN  npm run codegen                        regenerate api/types.gen.ts
```

## Acceptance / DoD (for the implementation task)

- [ ] A1-A10 pass against mock fixtures; A1-A8 + A10 pass against the live stack
- [ ] B1-B4 pass against the live stack (B5 per G1's resolution)
- [ ] Frontend checks green (`npm run build`, lint); no `as unknown as` on coverage
- [ ] Page specs amended (W1/W2) — the panel stops being spec-less

**Not in scope:** per-day projected coverage (no backend), per-user coverage for
households (FC6c), any change to `RollupBuilderImpl` semantics, intake-history range view
(v1.1 per `nutrition.md` §7).
