# Page spec — Recipe detail (`/recipes/{recipeId}`)

The contract-complete specification: every endpoint this page consumes, and the UI
that each request field and response field demands. A control exists for every
writable field; a display home exists for every returned field (or an explicit
"not on this page" entry). Companion docs: [../ia.md](../ia.md),
[../design-language.md](../design-language.md). Template: [nutrition.md](nutrition.md)
(the pilot). Siblings: [recipes.md](recipes.md), [discover.md](discover.md).

The deepest page in the app: one recipe's full body, **versions** (linear,
per-branch), **branches** (creative forks), **substitutions** (constraint
overlays with their own lifecycle), **multi-dimensional ratings**, import
provenance, image upload, nutrition recalculation, and the per-recipe slice of
the adaptation pending-change queue.

---

## 1. Intent (HLD)

From `design/recipe-system.md` + `lld/recipe.md`:

- **Versions are the system's best current guess** — "every recipe change on a
  branch creates a new version… usually strictly better, but a user can revert
  to any prior version." Revert writes a *new* version cloning the target
  (`trigger = REVERT`) — history is never rewritten.
- **Structured diffs, not free text** — `changes` is a structured diff "so any
  two versions compare cleanly"; the diff endpoint is a stored-key lookup
  between **consecutive same-branch** versions only (422 otherwise).
- **Branches are forks with independent merit** — protein swaps, flavour-direction
  forks, method changes. "When NOT to branch: seasoning tweaks… use a version."
  Branches carry their own character fingerprint; divergence > threshold
  surfaces "this branch has become its own dish — promote to a standalone
  recipe?" (no endpoint yet — §11 Q4).
- **Substitutions are overlays, never edits** — "the base recipe is unchanged";
  not ideal ("if the constraint lifts, the original should return"). Shipped
  state machine: `PROPOSED → ACCEPTED | REJECTED`; `ACCEPTED → SUPERSEDED` on
  promote-to-version. After N applications (default 3) without complaint:
  "make this permanent?" — one tap promotes the overlay to a new version
  (`trigger = SUBSTITUTION_PROMOTION`).
- **Ratings are multi-dimensional** — four 0–100 axes: **taste** (required,
  the one-tap default), **effort_worth_it**, **portion_fit**, **repeat_value**;
  "the default rating path asks only for taste; the user can open 'rate in
  detail'." Ratings are **per version**.
- **Proposed, never auto-applied** — adaptation pending changes wait for the
  user: side-by-side diff, accept / reject / modify-then-accept; superseded and
  expired proposals move to history, visible on request.
- **Nutrition is never imported** — per-serving numbers come from the nutrition
  module; recipes show `nutritionStatus` CALCULATED / PENDING / PARTIAL and a
  needs-review repair path for sub-confidence ingredient mappings.
- **Manual edits on SYSTEM recipes are forbidden** — promote to USER first
  (422 `recipe-catalogue-violation` otherwise).

## 2. Endpoint inventory

23 recipe-module operations + 1 nutrition + 5 adaptation are consumed here,
across five zones (**Hero · Body/Edit · Versions & branches · Substitutions ·
Ratings**) plus two side panels (provenance, suggestions).

| # | Endpoint | Zone | When called |
|---|----------|------|-------------|
| 1 | `GET /api/v1/recipes/{recipeId}` | All | On load + after every successful write (returns `RecipeDto` + hydrated `currentVersionBody` + `branches[]`) |
| 2 | `PUT /api/v1/recipes/{recipeId}` | Edit | Edit-form save (full replacement + `changeReason` + `expectedOptimisticVersion`) |
| 3 | `GET …/branches` | Versions strip | Branch list refresh (also embedded in #1 `branches[]`) |
| 4 | `GET …/branches/{branchId}` | Versions strip | Branch row expand |
| 5 | `POST …/branches` | Versions strip | "Fork as variant" save |
| 6 | `GET …/versions?branchId&page&size` | History drawer | Drawer open + branch switch (newest first; size ≤100, default 20) |
| 7 | `GET …/versions/{versionNumber}?branchId` | History drawer | Old-version row open (read-only body view) |
| 8 | `POST …/versions/revert` | History drawer | "Revert to this version" confirm |
| 9 | `GET …/versions/{fromVersionId}/diff/{toVersionId}` | History drawer | "What changed" expander between consecutive rows |
| 10 | `GET …/substitutions?versionId` | Substitutions | ACCEPTED subs on the viewed version |
| 11 | `GET …/substitutions/active` | Substitutions | All ACCEPTED subs on the recipe (panel header count) |
| 12 | `POST …/substitutions` | Substitutions | "Propose a swap" save (lands PROPOSED) |
| 13 | `POST …/substitutions/{subId}/{action}` (`accept` \| `reject` \| `promote-to-version`) | Substitutions | Lifecycle buttons (§6) |
| 14 | `GET …/versions/{versionId}/with-substitutions` | Body | "View with substitutions" toggle ON |
| 15 | `GET …/import-provenance` | Provenance panel | Panel expand (404 `recipe-import-not-found` for manual recipes → hide) |
| 16 | `POST …/image` (multipart) | Hero | Photo upload/replace |
| 17 | `GET …/image` | Hero | `<img src>` (anonymous, immutable-cached) |
| 18 | `POST …/ratings` | Ratings | First rating submit (one-tap or detailed) |
| 19 | `GET …/ratings?versionId&page&size` | Ratings | "All ratings" expander (newest first) |
| 20 | `GET …/ratings/mine?versionId` | Ratings | On load (404 = not rated → rate CTA) |
| 21 | `GET …/ratings/summary?versionId` | Ratings | On load per viewed version; omit `versionId` → recipe-level header aggregate |
| 22 | `PUT …/ratings/{ratingId}` | Ratings | Revise own rating (with `expectedVersion`) |
| 23 | `DELETE …/ratings/{ratingId}` | Ratings | Remove own rating |
| n1 | `POST /api/v1/nutrition/recipes/{recipeId}/versions/{versionId}/recalculate` | Hero | "Recalculate nutrition" action (cross-module — pre-assigned by nutrition.md §7) |
| a1 | `GET /api/v1/adaptation/pending-changes` | Suggestion card | On load (top-3 for the user; filter rows to `recipeId == this`) |
| a2 | `GET /api/v1/adaptation/pending-changes/{id}` | Suggestion card | Card expand + pre-accept fetch (carries `optimisticVersion`) |
| a3 | `POST /api/v1/adaptation/pending-changes/{id}/accept` | Suggestion card | Accept (optionally with `userEdits`) |
| a4 | `POST /api/v1/adaptation/pending-changes/{id}/reject` | Suggestion card | Reject (+optional `reasonNote`) |
| a5 | `GET /api/v1/adaptation/recipes/{recipeId}/pending-history?page&size` | Suggestion history | "Earlier suggestions" expander |

## 3. Hero — reads `RecipeDto` (#1)

| Display element | Source field |
|---|---|
| Photo | `imageUrl` → #17 (null → upload dropzone, owner only) |
| Name + description | `name`, `description` |
| Chips | `currentVersionBody.metadata`: `totalTimeMins` ("25 min", tooltip prep `prepTimeMins` + cook `cookTimeMins`) · `servings` · `cuisine` · `packable` ("packable") · `fridgeDays`/`freezerWeeks` ("keeps 3 d fridge / 8 w freezer") · `equipmentRequired[]` ("wok · hob") · `mealTypes[]` |
| Tag chips | `currentVersionBody.tags`: `protein` · `cookingMethod` · `complexity` (MINIMAL/MODERATE/INVOLVED) · `flavourProfile[]` · `dietaryFlags[]` |
| Quality badge | `dataQuality` (tier badge, same mapping as recipes.md §3a) |
| Catalogue banner | `catalogue = SYSTEM` → "pool recipe — add it to your library to edit" + **Promote** (recipes.md #7); editing/rating-version controls disabled until USER |
| Nutrition status | `nutritionStatus` — CALCULATED renders the per-serving band (Q1: **no source field**, §11) · PENDING "calculating…" · PARTIAL amber "n ingredients need review" linking the flagged rows |
| Version tag | `currentVersion` + current branch name (from `branches[]` row matching `currentBranchId`) |
| Archived banner | `archivedAt` → "archived" + Unarchive |
| Header actions | **Edit** (→ §4; hidden for SYSTEM) · **Rate** (→ §7) · **Recalculate nutrition** (→ n1) · overflow: archive/demote (recipes.md §5) |

**Recalculate nutrition (n1)** — body `{branchId*, versionNumber*}` (both from
#1). 200 → `RecipeNutritionResultDto`: `caloriesPerServing`,
`proteinPerServingG`, `carbsPerServingG`, `fatPerServingG`, `fibrePerServingG`,
`microsPerServing` map, `nutritionStatus`, `unmapped[]` (rows that failed USDA
mapping → "fix in Data quality" deep link to /nutrition §6). Render the result
as the per-serving band; a `Warning` header may flag the recipe-side write-back
as unwired. 422 = recipe-side write failed → show numbers anyway + toast.

Not displayed: `userId`, `optimisticVersion` (request plumbing — held for #2/#8),
`deletedAt`, `lastUsedInPlanAt`, `currentVersionBody.embeddingStatus` /
`adapterTraceId` / `createdByActor` (admin/debug), `forkedFromRecipeId`
(provenance caption only).

## 4. Body & edit — `currentVersionBody` ⇄ `UpdateRecipeManualEditRequest` (#2)

### 4a. Read — ingredients & method

| Display element | Source field |
|---|---|
| Ingredient row | `ingredients[]` ordered by `lineOrder`: `displayName` · `quantity`+`unit` ("400 g") · `preparation` italic ("sliced") · `optional` → "(optional)" |
| Needs-review badge | `needsReview = true` → amber dot + `mappingConfidence` tooltip ("USDA match 0.62 — nutrition may be off") |
| Swap chip | ingredient key ∈ an ACCEPTED substitution's `original.ingredientMappingKey` (#10) → tint chip "swap: {substitute display}" linking §6 |
| Method step | `methodSteps[]` by `stepNumber`: `instruction` + `durationMinutes` ("~10 min") |

**"View with substitutions" toggle** → #14: same `RecipeVersionDto` shape with
the overlay applied; `appliedSubstitutionIds[]` drives "n swaps applied"
caption; overlay rows have `id = null` (computed projection, not persisted —
no row-level actions in this mode).

### 4b. Edit form — full replacement

Same form component as recipes.md §4d (same constraints), plus:

| Control | Request field | Constraints |
|---|---|---|
| (whole form) | `name`*, `description`, `ingredients[]`*, `method[]`*, `metadata`*, `tags` | recipes.md §4d table |
| Change note* | `changeReason` | 1–2000 — "what did you change and why" (lands on the version row) |
| (hidden) | `expectedOptimisticVersion`* | from #1 `optimisticVersion`; 409 → conflict card "changed since you opened it (another tab or an accepted suggestion)" → reload + re-apply |

200 returns the new current `RecipeDto` (version bumped, `trigger =
MANUAL_EDIT`). 400 = validation or **no-op edit** (nothing changed) → inline.
422 = SYSTEM catalogue → promote-first interstitial.

## 5. Versions & branches — #3–#9

### 5a. Branch strip

Tabs from `branches[]` (`RecipeBranchDto`), 'main' first (createdAt ASC):

| Display element | Source field |
|---|---|
| Tab label | `label` ?? `name` ("Beef Stir Fry" / `beef-variant`) |
| Current marker | branch `id == RecipeDto.currentBranchId` |
| Tooltip | `reason` · `createdByActor` ("adaptation_pipeline" → "AI-created") · `createdAt` · `branchPointVersionId` → "forked at v{n}" · `currentVersion` |
| Divergence nudge | `divergenceScore` > 0.7 → "this branch has become its own dish" caption (promote-to-standalone has **no endpoint** — §11 Q4; render as informational only) |

Not displayed: `parentBranchId` (drawing the tree is v1.1), `adapterTraceId`,
`version` (optimistic plumbing).

**Fork as variant** (ghost button) → `CreateBranchRequest`:

| Control | Request field | Constraints |
|---|---|---|
| Slug name* | `name` | 1–64, `^[a-z0-9-]+$`; 409 = name taken; "main" reserved (422) |
| Display label | `label` | ≤120 |
| Why* | `reason` | 1–2000 |
| Fork-point picker | `branchPointVersionId`* | version rows from #6; must belong to this recipe (422) |
| Body editor* | `body` | pre-filled from the fork-point version; full `CreateRecipeBodyRequest` (ingredients/method/metadata/tags, §4d constraints) |
| — | `fingerprintOverride` | nullable; pipeline-only concept — **no control** (server derives) |

422 on SYSTEM recipes (promote first). 201 → refresh #1 (new branch becomes
visible; note: creating a branch does *not* switch `currentBranchId`).

### 5b. Version history drawer — `RecipeVersionDtoPage` (#6)

| Display element | Source field |
|---|---|
| Row title | `versionNumber` ("v3") + current marker (`== RecipeDto.currentVersion` on the current branch) |
| Trigger chip | `trigger` — MANUAL_CREATE "created" · MANUAL_EDIT "edited by you" · IMPORT "imported" · ADAPTATION_PIPELINE "AI adaptation" · SUBSTITUTION_PROMOTION "swap made permanent" · BRANCH_CREATION "branch start" · REVERT "revert" |
| Change note | `changeReason` (italic; null on v1 rows) |
| When / by | `createdAt` · `createdByActor` ("user:<uuid>" → "you"; "adaptation_pipeline" → "AI") |
| Lineage | `parentVersionId` (cross-branch on branch-start rows → "from main v2") |
| Open | row click → #7 read-only body (same §4a mapping; banner "viewing v2 of 3") |
| **What changed** | #9 between this row and its parent → `RecipeDiffDto` (§5c) — **consecutive same-branch pairs only**; the expander is hidden on branch-start rows (cross-branch parent → 422) |
| **Revert** | on every non-current row → confirm "writes a new version copying v{n} — nothing is deleted" → #8 |

**Revert** body `RevertToVersionRequest`: `branchId`* (viewed branch),
`versionNumber`* (target row), `expectedRecipeOptimisticVersion`* (#1
`optimisticVersion`). 200 → new `RecipeVersionDto` (`trigger = REVERT`) →
refresh #1 + history. 400 = no-op (reverting to the current version). 409 =
stale → reload. 422 = SYSTEM recipe.

### 5c. Diff rendering — `RecipeDiffDto` (#9)

| Display element | Source field |
|---|---|
| Ingredient rows | `ingredientChanges[]`: `action` ADDED (+green) / REMOVED (−red) / MODIFIED (~) · `from`/`to` objects (null on ADDED/REMOVED respectively) · `fieldChanged` on MODIFIED ("quantity: 1 tbsp → 2 tbsp" — render the named scalar only) |
| Method rows | `methodChanges[]`: `action` + `step` + `from`/`to` instruction strings |
| Metadata rows | `metadataChanges[]`: `field` + `from`/`to` (untyped JSON — render stringified) |
| Tag rows | `tagChanges[]`: `dimension` + `from`/`to` |
| (plumbing) | `fromVersionId`, `toVersionId` — not displayed |

## 6. Substitutions — #10–#14

Panel lists the recipe's substitutions. v1 read endpoints filter to **ACCEPTED**
only (#10 per-version, #11 recipe-wide); PROPOSED rows therefore surface only
via the 201 response of #12 and the adaptation flow — flagged §11 Q2.

| Display element | Source field (`RecipeSubstitutionDto`) |
|---|---|
| Swap line | `original.{ingredientMappingKey, quantity, unit}` → `substitute.{…}` ("300 g fillet steak → 300 g rump steak") |
| Reason chip | `reason` — BUDGET / AVAILABILITY / DIETARY_TEMP / EQUIPMENT |
| Constraint ref | `constraintRef` muted ("budget-cap-2026-w15") |
| Method overlay | `methodOverlay[]` rows ("step 3: cook 6–8 min instead") |
| Notes | `notes` italic |
| Temporary badge | `temporary = true` → ⏱ "until the constraint lifts" |
| Usage line | `applicationCount` ("used in 3 plans") + `lastAppliedAt` |
| State chip | `state` — PROPOSED amber · ACCEPTED olive "active overlay" · REJECTED muted · SUPERSEDED muted "made permanent" + `promotedToVersionId` → version link |
| Provenance | `createdByActor` ("AI" vs "you") + `createdAt` |
| (plumbing) | `id`, `recipeId`, `versionId`, `branchId`, `adapterTraceId`, `version` (held for #13 `expectedVersion`) |

**Lifecycle buttons — each fires #13 with the action in the path and
`SubstitutionLifecycleRequest` body (`expectedVersion`* always; `reason`
≤255 consumed by reject only; `changeReason` 1–2000 required by
promote-to-version):**

| State | Actions |
|---|---|
| PROPOSED | **Accept** (primary → ACCEPTED; overlay goes live) · **Reject** (ghost + optional reason popover) |
| ACCEPTED | **Revert to original** (= reject; the "reset" affordance — overlay removed, original ingredient returns) · **Make permanent** (promote-to-version: requires a change note*; returns a new `RecipeVersionDto`, substitution → SUPERSEDED) · promotion nudge banner when `applicationCount ≥ 3`: "you've used this swap in 3 plans — make it permanent?" (HLD rule) |
| REJECTED | **Re-accept** (accept is legal from REJECTED — only SUPERSEDED is hard-terminal in the shipped service; §11 Q3 flags the HLD drift) |
| SUPERSEDED | none — terminal; link to the promoted version |

Accept/reject return the updated substitution; promote returns the new
**version** (refresh #1 + history). Re-accepting an already-ACCEPTED row is an
idempotent no-op (200, no version bump). 409 = stale `expectedVersion` →
re-fetch row. 422 = SUPERSEDED, or promote on a non-ACCEPTED row.

**Propose a swap** (ghost) → #12 `CreateSubstitutionRequest`:

| Control | Request field | Constraints |
|---|---|---|
| (implicit) | `versionId`* | the viewed version |
| Original picker* | `original` | dropdown of the version's ingredients → `{ingredientMappingKey* 1–160, quantity* ≥0, unit* 1–16}`; key must exist on the version (422) |
| Substitute* | `substitute` | same shape, free entry |
| Reason* | `reason` | 4-value enum select |
| Constraint ref | `constraintRef` | ≤160 |
| Method overlay rows | `methodOverlay[]` | `{step ≥1, instruction 1–2000}` |
| Notes | `notes` | ≤1000 |
| Temporary | `temporary` | toggle, default true |

201 → row appears as PROPOSED with accept/reject. 422 on SYSTEM recipes.

## 7. Ratings — #18–#23

### 7a. Summary band — `RecipeRatingSummaryDto` (#21)

Recipe-level call (no `versionId`) feeds the hero numeral; per-version call
feeds the band under the body. `avg*` fields are null when `count = 0` → "not
rated yet" + CTA.

| Display element | Source field |
|---|---|
| Four axis cells | `avgTaste` · `avgEffortWorthIt` · `avgPortionFit` · `avgRepeatValue` (0–100, one decimal) + segment bars |
| Headline | `avgAggregate` + `count` ("78 · 5 ratings") |
| Version scope note | `versionId` (null → "all versions") |

### 7b. Rate flow — `CreateRatingRequest` (#18) / `UpdateRatingRequest` (#22)

On load, #20 (`ratings/mine?versionId`) decides the CTA: 404 → **Rate this
version** (POST path); 200 → **Update your rating** (PUT path, pre-filled,
carries `RecipeRatingDto.optimisticVersion` → `expectedVersion`).

| Control | Request field | Constraints |
|---|---|---|
| (implicit) | `versionId`* | the viewed version; must belong to the path recipe (400) |
| **Taste slider*** | `taste` | 0–100 — the one-tap path submits taste alone |
| "Rate in detail" expander | `effortWorthIt`, `portionFit`, `repeatValue` | each 0–100, nullable — absent axes coalesce to taste in the aggregate (server-side) |
| Notes | `notes` | ≤1000 |
| Slot link | `slotId` | nullable uuid — pre-filled only when arriving from a plan/Today deep link ("rate tonight's dinner"); no manual control |
| (update only, hidden) | `expectedVersion`* | 409 → reload own rating |

POST 409 = "already rated this version" → switch to PUT path silently.
Response `RecipeRatingDto` also carries the computed `aggregate` → optimistic
band update. **Delete** (#23, ghost in the edit popover) → 204 → re-fetch #20/#21.

### 7c. All-ratings list — `RecipeRatingDtoPage` (#19)

Rows: `taste`/`effortWorthIt`/`portionFit`/`repeatValue` mini-bars ·
`aggregate` numeral · `notes` · `createdAt`/`updatedAt` ("edited") · own row
marked (id == mine). `userId`, `householdId`, `slotId`, `traceId`,
`optimisticVersion` not displayed (single-user v1; household attribution is a
v1.5 concern).

## 8. Image upload — #16

Owner-only dropzone on the hero (hidden on SYSTEM recipes — 403 server-side):

| Constraint | UI behaviour |
|---|---|
| multipart field `file`* | file picker / drop target |
| ≤5 MB | client pre-check + 413 → "image too large (max 5 MB)" |
| MIME allow-list JPEG/PNG/WebP — **magic-byte probed** (Tika), browser type is a hint | accept attr `image/jpeg,image/png,image/webp`; 415 → "JPEG, PNG or WebP only" |
| 403 | not owner / SYSTEM catalogue → dropzone never rendered; toast if raced |

200 → `RecipeImageDto { imageUrl, sizeBytes, contentType }` → swap `<img src>`
(bust cache with a query param — the GET is served `immutable`).

## 9. Import provenance panel — `RecipeImportDto` (#15)

Collapsed "Where this came from" panel; hidden when 404
(`recipe-import-not-found` = manually created, no provenance row).

| Display element | Source field |
|---|---|
| Source chip | `sourceType` — MANUAL / URL / AI_GENERATED / WEB_DISCOVERED |
| Source link | `sourceUrl` (nullable) — external link, rel=noopener |
| Extraction method | `extractionMethod` ("json_ld" → "read from the page's recipe data") |
| Imported line | `importedAt` + `importedByUserId` (== self in v1 → "by you") |
| Duplicate note | `duplicateOfRecipeId` non-null → "imported as a duplicate of …" link |
| Not displayed | `sourcePayload` (raw HTML excerpt — audit blob, admin/debug), `id`, `recipeId` |

## 10. Suggested changes (adaptation) — a1–a5

The advisor card the mock already renders, wired to the adaptation module. The
**global** review queue (top-3 across recipes, before/after diffs, weekly
budget) lives on /activity; this page shows **this recipe's** pending change +
its history, with the same accept/reject semantics (be consistent — §12 table).

| Display element | Source field |
|---|---|
| Card (collapsed) | `PendingChangeListItemDto` from a1 filtered to `recipeId`: `changeDimension` chip (SALT_LEVEL · PROTEIN · METHOD_SIMPLIFICATION · PORTION_SIZE · FLAVOUR_BALANCE · ACID_BALANCE · TEXTURE · COOKING_TIME · SUBSTITUTION_PROMOTION · GENERAL) · `reasoningPreview` (≤200, nullable → dimension label) · `confidence` pill · `expiresAt` ("expires in 3 days") |
| Card (expanded, a2) | `PendingChangeDto`: `reasoning` (full) · `nutritionalNotes` · `proposedClassification` (VERSION "new version" / BRANCH "as a variant" / SUBSTITUTION "as a temporary swap") · `proposedDiff` (opaque JSON — render with the §5c diff component on a best-effort key match) · `baseVersionId` ("against v3") · `status` chip (PENDING / ACCEPTED / MODIFIED / REJECTED / EXPIRED / SUPERSEDED) · `supersededBy` link ("replaced by a newer proposal") |
| Not displayed | `jobId`, `traceId`, `promptTemplateVersion`, `impactScore`, `userId` (admin/ranking internals) |

**Accept** (primary) — requires `expectedOptimisticVersion` from a2 (the list
row doesn't carry it: expand-then-accept, two calls — same gap as today.md §8
Q6). Optional **modify before accepting** expander → edited copy of the diff
sent as `userEdits` (opaque JSON overlay; null = as-proposed). 200 → status
ACCEPTED → refresh #1 + version history (a new version/branch/sub was written).
409 = stale or superseded → re-fetch a2, "this suggestion changed". 422 = not
pending / expired → re-fetch, card flips to its history state.

**Reject** (ghost) — optional `reasonNote` (≤200) popover. 200 → REJECTED.
422 = not pending.

**History expander** (a5, paginated) — past proposals for this recipe as muted
rows (dimension + status + createdAt); supports the HLD's "show dismissed".

## 11. Status-code → UI map & open questions

| Code | Where | UI behaviour |
|---|---|---|
| 404 | #1 | "Recipe not found" page state (mock already has it) |
| 404 | #15 | hide provenance panel (manual recipe — not an error) |
| 404 | #20 | rate CTA (not rated — not an error) |
| 409 | #2 edit, #8 revert, #13 lifecycle, #22 rating, a3 accept | conflict card / row re-fetch + "changed elsewhere" |
| 409 | #5 branch name, #18 rating exists | rename inline / switch to update path |
| 422 catalogue violation | #2, #5, #8, #12 on SYSTEM | promote-first interstitial |
| 422 | #9 non-consecutive or cross-branch diff | hide the expander (guard client-side; toast if raced) |
| 422 | #13 terminal / promote precondition | re-fetch row, explain ("already made permanent") |
| 422 | n1 | show computed numbers + "couldn't save back" toast |
| 422 | a3/a4 not pending / expired | card → history state |
| 400 | #2/#5/#12/#18 forms, #8 no-op | inline field errors / "nothing to revert" |
| 413 / 415 / 403 | #16 image | §8 copy |
| 401 | all | global session-expired redirect |

**Open questions (flagged, not resolved here):**
1. **Per-serving nutrition has no read field.** `RecipeDto` /
   `RecipeVersionDto` carry `nutritionStatus` but **not** `nutritionPerServing`
   (the LLD DTO had it; the shipped contract dropped it). The mock's "520 kcal
   · 38 g protein" pills are unwireable; the only contract source is the
   *recalculate* POST (n1) — a write op as a read workaround. Backend gap
   candidate (headline): expose `nutritionPerServing` on `RecipeVersionDto`
   (or `GET /recipes/{id}/nutrition` per the LLD REST table).
2. **PROPOSED substitutions are unlistable.** #10/#11 filter to ACCEPTED; a
   user-proposed (#12) or pipeline-proposed substitution in PROPOSED state has
   no read endpoint — the accept/reject UI only works on rows the client
   remembers from the 201. Backend gap candidate: `state` query param on the
   substitution list.
3. **REJECTED → re-accept is legal in the shipped service** (only SUPERSEDED
   hard-guards; accept from REJECTED transitions to ACCEPTED), but
   `design/recipe-system.md` calls REJECTED terminal. Spec follows the code
   (re-accept offered); pin the design doc or add the guard.
4. **Branch promote-to-standalone has no endpoint.** Divergence > 0.7 nudge is
   HLD-mandated ("promote copies the branch out as a new recipe with
   `forked_from`"); `forkedFromRecipeId` exists on the DTO but nothing writes
   it from a user action. Render informational-only; backend gap candidate.
5. **Adaptation accept is expand-then-accept** (list DTO lacks
   `optimisticVersion`) — shared with today.md §8 Q6; one backend ticket
   covers both.
6. **`proposedDiff` is opaque** ("shape governed by the adaptation pipeline")
   — the side-by-side diff renders best-effort; if the pipeline's shape matches
   `RecipeDiffDto` it should say so in the contract. Minor gap candidate.
7. **Character fingerprint is invisible.** `CreateBranchRequest` accepts a
   `fingerprintOverride` no user can meaningfully author, and no read exposes
   the current fingerprint. v1 ships no control (server derives); note for the
   future branch UX.

## 12. Not on this page

| Contract item | Home |
|---|---|
| Library browse/search, import flows, manual create, dedup dialog | /recipes ([recipes.md](recipes.md) §3–§4) |
| Promote/demote/archive/unarchive (the buttons render here too, but semantics + state machine are specced once) | recipes.md §5 |
| **Global** pending-changes review queue (top-3 across recipes, optimisation budget, weekly cap) | /activity — this page consumes only the per-recipe slice (a1 filtered + a5) |
| `GET /adaptation/jobs/{id}`, job traces, run-history, prompt-version traces | Admin (ROLE_ADMIN debug surface) |
| Feedback entry ("too salty" free text) | /activity feedback composer — feedback routes to adaptation, which lands back here as a pending change |
| Ingredient mapping corrections (`needsReview` repair) | /nutrition Data quality tab (nutrition.md §6) — this page only badges + deep-links |
| `POST /nutrition/.../recalculate` ownership | consumed here (n1) per nutrition.md §7's explicit assignment |
| Plan slot scheduling of this recipe, `recordSubstitutionApplication` | planner-internal (no UI) |
| Rating one-tap from a meal slot | Today page deep-links here with `slotId` pre-filled |
| `POST /recipes/admin/run-archive-scan` | Admin |

## 13. Mock deltas (to make the mock match this spec)

1. Retype on `RecipeDto`/`RecipeVersionDto`: body from
   `currentVersionBody.ingredients[]`/`methodSteps[]` (drop bespoke `n`/`q`
   strings), chips from `metadata` + `tags`, hero ratings from
   ratings-summary, photo from `imageUrl`.
2. Versions strip → branch tabs (from `branches[]`) + history drawer (#6
   pagination, trigger chips, change reasons) + per-row diff expander (§5c
   component) + revert flow with confirm copy; seed a 3-version main + one
   branch with a cross-branch start row (hidden diff).
3. `pendingChange` → adaptation DTO pair (list item + detail), expand-then-
   accept with `expectedOptimisticVersion`, modify-before-accept editing
   `userEdits`, reject reason popover, expiry countdown, history expander;
   accept must append a version to the history (currently mutates the
   ingredient in place — keep the mutation but route it through a new version).
4. Substitutions panel (§6): seed one ACCEPTED (swap chip on the ingredient +
   "view with substitutions" toggle), one PROPOSED (accept/reject), one at
   `applicationCount = 3` showing the promotion nudge; promote writes a
   version with `trigger = SUBSTITUTION_PROMOTION`.
5. Rate flow: replace the static rating bars with mine/summary reads + the
   one-tap taste slider + rate-in-detail expander (4 axes, exact names) +
   update/delete paths.
6. Add: edit form (PUT semantics — changeReason required, optimistic-version
   conflict state), image dropzone with 5 MB/MIME/owner rules, provenance
   panel, needs-review ingredient badges, SYSTEM-catalogue read-only banner +
   promote interstitial, archived banner.
7. Nutrition pills: source from the recalc result shape behind a "Q1 gap" flag
   (seeded constant until the read field lands); add the `unmapped[]` →
   /nutrition deep link.
