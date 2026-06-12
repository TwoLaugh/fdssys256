# Page spec — Preferences (`/preferences`)

The contract-complete specification: every endpoint this page consumes, and the UI
that each request field and response field demands. A control exists for every
writable field; a display home exists for every returned field (or an explicit
"not on this page" entry). Companion docs: [../ia.md](../ia.md),
[../design-language.md](../design-language.md). Template: [nutrition.md](nutrition.md)
(the pilot).

---

## 1. Intent (HLD)

From `design/preference-model.md` + `lld/preference.md`:

- **Three tiers, three update patterns.** Hard constraints are "safety-critical and
  never touched by AI"; the taste profile "evolves organically from feedback — the
  AI writes itself a cheat sheet about you" (~2500-token budget); lifestyle config
  "is essentially user settings — set during onboarding, stable for months". The
  page renders all three as visibly different surfaces, not one settings form.
- **"Here's what I think you like — correct anything that's wrong."** The taste
  profile is viewable and editable; manual overrides are flagged so the AI doesn't
  re-learn the wrong thing. Source tracking (`FEEDBACK` / `INFERRED` / `ONBOARDING`)
  lets users "see which preferences are their own words vs AI guesses".
- **Evidence is the confidence currency** — `evidenceCount` + `lastSignal` per
  ingredient preference; "a favourite with 23 data points steers harder than one
  from onboarding with 2".
- **Pruning is never lossy.** Token-budget pruning moves items to the unbounded
  archive (reason: low evidence / stale / token pressure); re-emerging preferences
  are re-promoted by the pipeline, not the user.
- **Versioned with rollback.** Every update snapshots a version; rollback "reverts
  to a previous version and replays feedback from the rolled-back version's
  `feedback_cursor` forward" — restored as a **new** monotonic version, never a
  decrement.
- **The three-event rule** (delta prompt, `lld/prompts/01-taste-profile-delta.md`):
  the AI only adds a like/dislike when 2–3 events agree or a single statement is
  explicit. The page must set this expectation — a manual refresh can legitimately
  change nothing.
- **GAP-04:** "Removing a Tier-1 hard constraint requires a confirmation
  interstitial … dropping a safety-critical constraint cannot be a silent one-step
  edit." Additions, reorderings, and non-Tier-1 edits stay one-step.
- **Lifestyle staleness:** "Periodic review prompts every 2–3 months: 'Is this
  still accurate?'" — driven by `lastReviewPromptAt` + the mark-reviewed endpoint.

## 2. Endpoint inventory

The preference module exposes 16 user endpoints; all 16 are consumed by this page
across four sections (**Taste profile · Hard constraints · Lifestyle · Archive**).
In-process surfaces are §7.

| # | Endpoint | Section | When called |
|---|----------|---------|-------------|
| 1 | `GET /preferences/taste-profile` | Taste | On load; re-fetch after #2/#3/#4 and while a refresh is pending (poll) |
| 2 | `PUT /preferences/taste-profile` | Taste | "Edit profile" save (manual override) |
| 3 | `POST /preferences/taste-profile/refresh-now` | Taste | Header **Refresh now** button (202) |
| 4 | `POST /preferences/taste-profile/rollback` | Taste | Versions drawer **Restore** button |
| 5 | `GET /preferences/taste-profile/versions?page&size` | Taste | Versions drawer open (lazy) + pagination |
| 6 | `GET …/taste-profile/versions/{documentVersion}` | Taste | Version row expand (snapshot preview) |
| 7 | `GET /preferences/taste-profile/audit-log?page&size` | Taste | "Change history" expander (lazy) |
| 8 | `GET /preferences/hard-constraints` | Constraints | On load + after #9 |
| 9 | `PUT /preferences/hard-constraints` | Constraints | Editor save; re-submitted with `confirmTier1Removals=true` after the GAP-04 interstitial |
| 10 | `GET …/hard-constraints/audit-log?page&size` | Constraints | "Change history" expander (lazy) |
| 11 | `GET /preferences/lifestyle-config` | Lifestyle | On load + after #12/#13 |
| 12 | `PUT /preferences/lifestyle-config` | Lifestyle | Lifestyle form save |
| 13 | `POST …/lifestyle-config/mark-reviewed` | Lifestyle | Review-nudge banner "Still accurate" |
| 14 | `GET …/lifestyle-config/audit-log?page&size&section` | Lifestyle | "Change history" expander (lazy; section filter chips) |
| 15 | `GET /preferences/archive?page&size&fieldPathPrefix` | Archive | Archive panel open (lazy) + filter |
| 16 | `GET /preferences/archive/active-count` | Archive | On load (panel badge count) |

## 3. Taste profile — anatomy & field mapping

### 3a. Header & profile envelope — reads `TasteProfileDto` (#1)

| Display element | Source field |
|---|---|
| "Here's what I think you like — built from N feedback signals" headline | `basedOnFeedbackCount` |
| Version pill ("v14") | `documentVersion` |
| "last learned" caption | `lastDeltaAppliedAt` (relative time; null → "nothing learned yet") |
| Taste-matching status dot | `tasteVectorStatus` — EMBEDDED (no dot) · PENDING (subtle spinner, tooltip "updating taste matching") · FAILED (amber dot, tooltip "taste matching degraded — retries on next update") |
| Held for writes, not displayed | `optimisticVersion` (→ `expectedVersion` on #2/#4), `id`, `userId` |
| Not displayed | `feedbackCursor`, `lastTokenEstimate` (internal), `createdAt`/`updatedAt` |

**Header buttons:** **Refresh now** (primary → #3) · **Roll back** (ghost → opens
versions drawer §3c) · **Edit profile** (ghost → §3d).

### 3b. Document body — reads `TasteProfileDto.document` (`TasteProfileDocument`)

One card per populated section; empty sections collapse to a single "nothing
learned yet" line. Server-managed scalars inside the document (`lastUpdated`,
`version`, `basedOnFeedbackCount`, `feedbackCursor`) are never rendered as
editable content (envelope copies in §3a win).

| Card | Source | Display |
|---|---|---|
| Mild intolerances | `softConstraints.intolerances[]` | rows: `substance` · `severity` chip · `notes`; caption "soft — severe intolerances live under hard constraints" |
| Flavours | `flavourPreferences.likes[]`/`dislikes[]` (≤30 ea) + `notes` | like chips (tint) / dislike chips (muted); notes as advisor-voice caption |
| Textures | `texturePreferences.likes[]`/`dislikes[]` | same chip treatment, no notes |
| Ingredients | `ingredientPreferences.favourites[]`/`disliked[]` (≤50 ea) | row per item: `item` · evidence dot-scale from `evidenceCount` ("×23") · `lastSignal` relative date · **source badge**: FEEDBACK "you said" / INFERRED "advisor guess" / ONBOARDING "from your quiz" |
| Trending | `ingredientPreferences.trendingPositive[]`/`trendingNegative[]` | ↑/↓ chips: `item` · `evidenceCount` · `firstSignal` ("since Mar") |
| Cuisines | `cuisinePreferences.favourites[]`/`enjoys[]`/`lessPreferred[]` + `notes` | three bands (favourite / enjoys / less preferred) |
| Cooking | `cookingPreferences.skillLevel` (BEGINNER/INTERMEDIATE/ADVANCED badge) + `preferredMethods[]`/`dislikedMethods[]` | badge + chips |
| Portions | `portionStyle.preference` / `.saladMeals` | two sentences |
| Household | `householdContext.individualOnlyPreferences[]` + `householdSuitableNotes` | "just for you" chips + note |
| Repeat / avoid | `recipesToRepeat[]` / `recipesToAvoid[]` (≤50 ea) | rows: `name` · `suitableFor` chip · `reason` (italic) |
| Experiments | `activeExperiments[]` (≤20) | rows: `hypothesis` · status chip TESTING/PROMOTED/DISCARDED · "for/against" tally from `evidenceFor`/`evidenceAgainst` · `created` |
| Insights | `learnedInsights[]` (≤20 strings) | bulleted advisor-voice list |

### 3c. Versions drawer & rollback — #5, #6, #4

- Row per `TasteProfileVersionDto`: `documentVersion` ("v13") · `generatedAt` ·
  `trigger` badge (BATCH / WEEKLY / MANUAL) · `modelTierUsed` (muted) · feedback
  range caption from `feedbackRangeStart`–`feedbackRangeEnd` (nullable → omit) ·
  deltas count from `deltasApplied` (opaque JSON; render count + raw expander).
  Current version row (== envelope `documentVersion`) marked "current", no Restore.
- Row expand → #6 → render `documentSnapshot` with the §3b card mapping (read-only).
- **Restore** → confirm card ("Restores v13 as a new version; feedback given since
  then is re-applied automatically") → `POST /rollback` with
  `{ targetDocumentVersion, expectedVersion: optimisticVersion }`. 200 returns the
  restored DTO at a **new** monotonic version — drawer refreshes, no decrement.
  404 → "that snapshot is gone" toast + refresh drawer. 409 → conflict card (§8).

### 3d. Manual override (edit mode) — #2

Edit toggles the §3b cards into editable copies (chip add/remove, notes textareas,
experiment row delete). Save sends `UpdateTasteProfileRequest`:

| Control | Request field | Constraints |
|---|---|---|
| All §3b card edits | `document` (full replacement) | per-field maxLength/maxItems from the schema (likes/dislikes ≤30×64 chars, ingredients ≤50×128, notes ≤512, insights ≤20×512…) |
| (hidden) | `document.lastUpdated`/`version`/`basedOnFeedbackCount`/`feedbackCursor` | echoed back exactly as loaded — never user-edited (§8 Q1) |
| (hidden) | `expectedVersion` | loaded `optimisticVersion` |

Server flags the write `MANUAL_OVERRIDE` (audit) with a `trigger=MANUAL` version
snapshot — show post-save toast "Saved. The advisor won't re-learn this from old
feedback." 409 → conflict card.

### 3e. Refresh now — #3

`POST /refresh-now` with **no body** (the optional
`{feedbackRangeStart, feedbackRangeEnd}` window is a debug affordance — not in v1
UI). 202 returns the *current* DTO; the refresh is async (fires the feedback
module's delta task; audit `REFRESH_TRIGGERED`). Button enters "Refreshing…";
poll #1 (e.g. every 3 s, ≤60 s) until `documentVersion` bumps, then highlight
changed cards. If no bump by timeout, show "No changes yet — one-off comments may
not move the profile (it waits for repeated signals)" — the three-event rule, not
an error. (No job-status endpoint exists — §8 Q2.)

### 3f. Taste-profile change history — #7

Rows from `TasteProfileAuditEntryDto`: `changeType` badge (INITIALIZED ·
MANUAL_OVERRIDE · AI_DELTA_APPLIED · REFRESH_TRIGGERED · ROLLED_BACK) ·
`actorType` (USER "you" / AI "advisor" / SYSTEM) · `previousDocumentVersion` →
`newDocumentVersion` ("v12 → v13") · `summary` (nullable) · `occurredAt`.
`traceId`, `actorUserId` not displayed.

## 4. Hard constraints — anatomy & field mapping

Visually distinct card: "Safety filtered" label, red accent, caption "never broken
by any plan; never edited by AI".

### 4a. Display & editor — `HardConstraintsDto` (#8) ⇄ `UpdateHardConstraintsRequest` (#9)

| Display / control | Response field | Request field + constraints |
|---|---|---|
| Allergy chips + add input + remove ✕ | `allergies[]` | `allergies[]` (each ≤64) |
| Medical-diet chips + add + remove ✕ | `medicalDiets[]` | `medicalDiets[]` (≤64; e.g. low_sodium, low_fodmap, diabetic — drives the deterministic filter taxonomy) |
| Dietary identity — base select | `dietaryIdentity.base` | `base` (≤32): omnivore / vegetarian / vegan / pescatarian / keto / paleo / other |
| — display label input | `dietaryIdentity.labelForDisplay` | optional ≤64 ("pescatarian") — shown as the identity's headline when set |
| — exceptions rows | `dietaryIdentity.exceptions[]` | per row: `allows`* (≤64; known sub-category — fish, poultry, dairy, eggs, gluten… — or an "X-free" qualifier like `lactose_free`) · `frequency` (≤32, optional, "2-3x/week") · `context`* (≤32: any / social / weekend / weekday) |
| Severe-intolerance rows | `intolerances[]` | per row: `substance`* (≤64) · `severity`* (≤32, e.g. "coeliac") · `notes` (≤255) |
| Age-restriction chips (read-only) | `ageRestrictions[]` (`ruleKey` + `autoPopulated` badge "auto") | echoed back **unchanged** — required in the request but auto-managed for child profiles; the UI offers no add/remove |
| (hidden) | `version` | `expectedVersion` |
| (interstitial only — §4b) | — | `confirmTier1Removals` (nullable boolean; omitted on first submit) |

**Inline validation notes** (server 400s the UI pre-empts):
- A *plain* exception whose `allows` names a declared allergy or intolerance
  substance is rejected (field error at `dietaryIdentity.exceptions[i].allows`).
- An **"X-free" exception is allowed** even when its base substance is an allergy
  (it widens only to the explicitly-safe variant). Show an info caption on such
  rows: "untagged {substance} foods are still flagged for review (AMBIGUOUS) — only
  items marked {x}-free pass" (the filter's ambiguity flagging,
  `lld/preference.md` Flow 2 step 8).
- Context-conditional exceptions only apply on matching occasions; `frequency` is
  planner-scored, not filter-enforced — caption on the frequency input.

### 4b. The GAP-04 removal interstitial — exact contract (traced in code)

**Detection** (`Tier1RemovalDetector`, pure diff stored-vs-request, case-insensitive
+ trimmed):

| Gated removal | Trigger |
|---|---|
| ALLERGY | any stored allergen absent from `allergies[]` |
| MEDICAL_DIET | any stored diet absent from `medicalDiets[]` |
| SEVERE_INTOLERANCE | any stored intolerance **substance** absent (editing a kept substance's severity/notes is NOT a removal) |
| DIETARY_IDENTITY_BASE | base **relaxation** only — the new base's excluded-food set is a strict subset of the stored one (vegan→vegetarian, vegetarian→omnivore). Tightening (omnivore→vegetarian) and lateral switches (vegetarian→keto; keto/paleo/other are incomparable) are NOT gated |

**The 409.** When ≥1 removal is detected and `confirmTier1Removals` ≠ `true`, the
PUT is rejected — no mutation, no audit row, no version bump — with
`application/problem+json` (`Tier1RemovalConfirmationProblem`):

```json
{
  "type": "https://mealprep.example.com/problems/tier1-removal-requires-confirmation",
  "title": "Tier-1 hard-constraint removal requires confirmation",
  "status": 409,
  "detail": "…",
  "reason": "TIER1_REMOVAL_REQUIRES_CONFIRMATION",
  "removedConstraints": [ { "category": "ALLERGY", "value": "peanuts" } ]
}
```

**The interstitial** is built from `removedConstraints[]` — one line per item,
category label + value: ALLERGY "allergy: peanuts" · MEDICAL_DIET "medical diet:
low_sodium" · SEVERE_INTOLERANCE "severe intolerance: gluten" ·
DIETARY_IDENTITY_BASE "dietary identity relaxed (was vegan)". Body copy: "These
protect every plan, recipe and grocery list. Remove anyway?" Destructive confirm
re-submits the **same payload** with `confirmTier1Removals: true` → 200.

**Disambiguating the two 409s on this PUT:** match `reason ==
"TIER1_REMOVAL_REQUIRES_CONFIRMATION"` (or the `type` URI) → interstitial;
any other 409 (`…/problems/optimistic-lock`) → stale-version conflict card (§8).
Never show the interstitial for an optimistic-lock 409.

### 4c. Constraints change history — #10

Rows from `HardConstraintsAuditEntryDto`: `fieldChanged` · `previousValueJson` →
`newValueJson` (compact inline diff, raw JSON expander) · `occurredAt`.
`actorUserId` shown only when ≠ current user ("household admin").

## 5. Lifestyle config — anatomy & field mapping

### 5a. Review nudge — `lastReviewPromptAt` (#11) + mark-reviewed (#13)

`lastReviewPromptAt` non-null → advisor-voice banner atop the section: "It's been a
while — is this still how you eat?" Buttons: **Looks right** → `POST
/mark-reviewed` (200 returns config with `lastReviewPromptAt: null`; banner
dismisses) · **Update** → scrolls into edit mode. Null → no banner.

### 5b. Form — `LifestyleConfigDto.document` (#11) ⇄ `UpdateLifestyleConfigRequest` (#12)

Full-replacement PUT (`document` + `expectedVersion` = `optimisticVersion`). All
sections optional; one collapsible group each:

| Group | Document section | Controls |
|---|---|---|
| Meal structure | `mealStructure.weekday`/`.weekend` (`meals[]`, `snacks{planned*, style, notes}`) + `recurringSkips[]` (`day`, `meal`, `reason`) | meal multi-select per day-type; snacks toggle + style; skip rows |
| Meal timing | `mealTiming.preferredSchedule.times{slot→"HH:MM-HH:MM"}` + `flexibility` + `notes` | time-range input per slot; flexibility text |
| Novelty tolerance | `noveltyTolerance.bySlot{slot→mode…}` + `recipeRepeatCooldownWeeks{…}` + `ingredientFrequencyCaps{…}` | per-slot mode select (rotation / batch_repeat / high_variety / static) + mode-specific numbers (`rotationSize`, `maxConsecutiveSame`, `weeklyUniqueMinimum`, `newPerWeek`); cooldown steppers; cap rows ("chicken → 3x/week") |
| Cooking contexts | `cookingContexts.byContext{name→…}` | rows: `maxTimeMins` · `complexity` · `preferredStyles[]` · ingredient-count `min`/`max` · `notes`/`source`/`frequency` |
| Batch cooking | `batchCooking` (`prepDays[]{day,window,maxSessionHours,maxRecipes}`, `maxLeftoverDays{…}`, `leftoverStrategy`, `freezerTolerance{acceptable,maxFrozenMealsPerWeek,exclusions[]}`, `sameProteinSameDay`, `parallelCookingTolerance`) | prep-day rows; leftover steppers; freezer toggle + exclusion chips |
| Reheating | `reheatingPreferences` (`availableAtWork[]`/`availableAtHome[]`, `preferredMethod`, `exclusions[]{category,rule,reason}`, `coldMealTolerance[]`) | equipment chips; exclusion rules table |
| Eating context | `eatingContext.bySlot{slot→{location,format,constraints[]}}` | per-slot rows |
| Seasonal | `seasonalPreferences.bySeason{season→{leanToward[],avoid[]}}` | chips per season |
| Meal-type | `mealTypePreferences.byType{type→{varietyTolerance,complexityTolerance,staples[],notes}}` | per-type rows |
| Accompaniments | `accompaniments.beverages{withMeals,morning,avoids[]}` + `.sides{notes}` | text inputs + avoid chips |
| Grocery quality | `groceryQualityPreferences{organic,freeRangeEggs,freeRangeMeat,brandedVsOwnLabel,notes}` | select per rule (always / preferred / when_price_comparable…) |
| Pantry tracking | `pantryTracking.enabled`* | single toggle ("pantry deductions on/off" — gates provisions behaviour) |

**Validation:** invalid novelty mode/field combos return 400 with
`offendingMode`/`offendingField` ProblemDetail extensions → inline error on that
slot row. 409 → conflict card.

### 5c. Lifestyle change history — #14

Rows from `LifestyleConfigAuditEntryDto`: `fieldPath` (one row per changed
top-level section) · previous→new JSON expander · `occurredAt`. The `section`
query param backs filter chips (one per §5b group).

## 6. Archive — #15, #16

- Panel header badge: "Archive (N)" from #16 `count` (active = not yet re-promoted).
- Rows from `PreferenceArchiveEntryDto`: humanised `fieldPath` ("ingredient
  favourites") · `itemKey` · `evidenceCount` ("×3") · `lastSignalAt` ·
  `archivedAt` · `archivedReason` badge — LOW_EVIDENCE "not enough signal" /
  STALE "no recent signal" / TOKEN_PRESSURE "made room" · `rePromotedAt` non-null →
  row renders muted with "re-promoted ✓" chip.
- `itemPayload` → raw expander. `fieldPathPrefix` query backs a section filter
  ("ingredients / cuisines / …").
- **Read-only by design** — re-promotion is the delta pipeline's job (it carries
  historical evidence forward); no user action on rows. Caption: "Pruned, not
  deleted — these come back by themselves if your feedback re-supports them."

## 7. Not on this page

| Contract item | Home |
|---|---|
| `POST /api/v1/feedback` (free-text "correct the advisor") | Global feedback modal (app shell) — see [activity.md §7](activity.md) |
| Taste-profile delta application (`applyTasteProfileDeltas`) | In-process only (feedback module bridge) — no REST surface |
| `initialiseHardConstraints` / `initialiseLifestyleConfig` / taste-profile `initialise` | In-process, onboarding wizard + auth user-creation — no REST surface |
| Hard-constraint filter checks (`HardConstraintFilterService`) | In-process (planner/discovery/adaptation callers) |
| Profile metadata (age group, portion scale) + soft-bundle | LLD-listed (`GET/PUT /preferences/profile-metadata`, `/soft-bundle`) but **not in the shipped contract** — §8 Q4 |
| Weekly budget | Provisions page (budget is a provisions concern) |
| Household merged preferences | Household settings page |

## 8. Status-code → UI map

| Code | Where | UI behaviour |
|---|---|---|
| 404 | #1/#8/#11 GET | "Finish onboarding to start here" empty state per section (aggregates are seeded by onboarding/auth, no client-side initialise) |
| 404 | #4 rollback, #6 version by number | "Snapshot no longer available" toast + refresh drawer |
| 409 `reason=TIER1_REMOVAL_REQUIRES_CONFIRMATION` | #9 | GAP-04 interstitial (§4b) — not an error toast |
| 409 (optimistic-lock) | #9, #2, #4, #12 | Conflict card: "Changed since you opened this" → reload + re-apply prompt |
| 400 | #2, #9, #12 | Inline field errors (dietary collision at `exceptions[i].allows`; novelty `offendingMode`/`offendingField`) |
| 202 | #3 | Not an error — enter polling state (§3e) |
| 401 | any | Global re-auth flow |

**Open questions (flagged, not resolved here):**
1. `UpdateTasteProfileRequest.document` includes server-managed scalars
   (`version`, `basedOnFeedbackCount`, `feedbackCursor`, `lastUpdated`). The UI
   echoes loaded values verbatim, but the contract doesn't state whether the
   server re-stamps or trusts them. Backend ticket candidate: document/enforce
   server-side re-stamp on manual override.
2. `refresh-now` has no completion signal — no job id, no status endpoint, and a
   legitimate "no change" outcome (three-event rule) is indistinguishable from
   "still running". v1 ships the §3e poll-with-timeout; backend ticket candidate:
   refresh status surface (or an SSE push, task #172).
3. The manual-override PUT documents no 422: the 2500-token `TasteProfileBudgetGuard`
   is specified on the delta path only. A user-pasted oversized document's
   behaviour is unspecified. Backend ticket candidate: run the guard on
   `applyManualOverride` and document the 422.
4. The mock's portion-scale stepper has no backing endpoint (LLD profile-metadata
   REST never shipped). Either ship `GET/PUT /preferences/profile-metadata` or
   drop the control (v1 spec drops it).

## 9. Mock deltas (to make the mock match this spec)

1. Taste profile: replace the canned likes/dislikes `groups` grid with the full
   §3b section mapping — source badges, evidence counts, trending, experiments,
   insights, repeat/avoid, mild intolerances — plus the §3a envelope (version
   pill, vector-status dot).
2. GAP-04: replace the local type-to-confirm dialog with the server-driven
   interstitial built from the 409 `removedConstraints[]` (cover medical diets,
   severe intolerances, and base-relaxation — the mock only gates allergy/identity
   chips); re-submit carries `confirmTier1Removals=true`.
3. Hard constraints: add the structured dietary-identity editor (base select,
   display label, exceptions w/ context+frequency), medical diets, severe
   intolerances, read-only age restrictions; add the collision/“X-free” inline
   validation states.
4. Versions: replace the cosmetic version strip with the real drawer — paginated
   rows, trigger badges, snapshot preview, restore w/ `expectedVersion`; rollback
   currently just decrements a counter.
5. Refresh now: wire 202-then-poll semantics with the "no change is normal"
   outcome; the mock always bumps the version after 1 s.
6. Lifestyle: replace the three steppers with the §5b grouped form + review-nudge
   banner + mark-reviewed; keep slot times (they map to
   `mealTiming.preferredSchedule`), move weekly budget to provisions, drop portion
   scale (§8 Q4).
7. Add the archive panel (+ active-count badge) and the three change-history
   expanders (taste / constraints / lifestyle w/ section filter).
8. Add per-section 404 onboarding empty states and the two-flavour 409 handling.
