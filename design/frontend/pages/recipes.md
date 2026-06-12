# Page spec — Recipes (`/recipes`)

The contract-complete specification: every endpoint this page consumes, and the UI
that each request field and response field demands. A control exists for every
writable field; a display home exists for every returned field (or an explicit
"not on this page" entry). Companion docs: [../ia.md](../ia.md),
[../design-language.md](../design-language.md). Template: [nutrition.md](nutrition.md)
(the pilot). Siblings: [recipe-detail.md](recipe-detail.md),
[discover.md](discover.md).

This page is the **library**: browse/search the user + system catalogues, get
recipes *into* the library (manual create, one-shot URL import, preview→confirm
import), and perform card-level catalogue moves (promote / demote / archive /
unarchive). Everything per-recipe-deep (versions, branches, ratings,
substitutions, edit) is recipe-detail's.

---

## 1. Intent (HLD)

From `design/recipe-system.md` + `lld/recipe.md` + `lld/recipe-extraction-pipeline.md`:

- **Two catalogues, one schema** — "user (curated, approval required) and system
  (AI-managed, direct write)… The only difference is the approval model." The
  library shows both; the user can **promote** any system recipe "with one tap"
  and **demote** a user recipe back ("soft delete; data preserved" — shipped as
  flip-in-place, recipe stays pool-accessible).
- **Data-quality tiers are visible** — `data_quality` reflects trust in the
  ingredient list: `USER_VERIFIED > IMPORTED ≈ AI_GENERATED > WEB_DISCOVERED`.
  "Low-trust recipes get a visual indicator."
- **Import is preview-then-confirm (Paprika-style)** — extraction runs, "the user
  reviews and edits before final save". Two preview modes: server-fetch
  (`preview-url`) and frontend-supplied HTML (`preview-html`, for JS-rendered /
  paywalled pages). The one-shot `/imports/url` "is retained for backward
  compatibility and persists directly without the dedup gate" (lld/recipe.md
  §REST).
- **Dedup is a dialog, not a rejection** — ≥80 % ingredient-overlap collision
  surfaces *"This looks similar to 'Chicken Stir Fry' in your library. Merge,
  import as a variant branch, or import anyway?"* Shipped as 422
  `recipe-import-duplicate` carrying `candidateRecipeId` + `ingredientOverlap`.
- **Imported nutrition is discarded** — "external nutrition data from imports is
  DISCARDED — recalculated internally"; freshly imported recipes carry
  `nutritionStatus = PENDING` until the nutrition module computes.
- **Needs-review is a badge, not a blocker** — sub-0.7 USDA mapping confidence
  flags ingredients `needs_review` and sets `nutritionStatus = PARTIAL`; "the UI
  surfaces a badge: '3 ingredients need review'".
- **Archival is soft** — system recipes unused 3 months auto-archive (excluded
  from planner index, retained in storage); users can archive/unarchive manually.
- **Images are optional cosmetics** — "a recipe without one is fully functional";
  the image GET is anonymous-accessible (public asset, unguessable URL).

## 2. Endpoint inventory

The recipe module exposes 33 operations; **10** are consumed by this page (plus
two per-card support reads). The page's core read — a paginated list/search of the
library — **has no endpoint in the shipped contract** (§8 Q1, the headline gap);
the grid below is specified against `RecipeDto` so it wires up the moment the
listing lands.

| # | Endpoint | Where | When called |
|---|----------|-------|-------------|
| 1 | *(missing)* `GET /api/v1/recipes?…` | Library grid | On load + filter/search/page change — **no contract** (§8 Q1) |
| 2 | `POST /api/v1/recipes` | "New recipe" form | Manual-create save (runs dedup) |
| 3 | `POST /api/v1/recipes/imports/preview-url` | Import flow §4 | "Fetch & preview" with a pasted URL |
| 4 | `POST /api/v1/recipes/imports/preview-html` | Import flow §4 | In-app-browser "Save recipe" (frontend supplies HTML) |
| 5 | `POST /api/v1/recipes/imports/confirm` | Import flow §4 | "Save to library" on the reviewed/edited preview (runs dedup) |
| 6 | `POST /api/v1/recipes/imports/url` | Import flow §4 | One-shot import (no preview, **no dedup gate**) — §8 Q3 |
| 7 | `POST /api/v1/recipes/{recipeId}/promote` | Card overflow (SYSTEM rows) | "Add to my library" |
| 8 | `POST /api/v1/recipes/{recipeId}/demote` | Card overflow (USER rows) | "Remove from my library" confirm |
| 9 | `POST /api/v1/recipes/{recipeId}/archive` | Card overflow | "Archive" (idempotent) |
| 10 | `POST /api/v1/recipes/{recipeId}/unarchive` | Card overflow (archived rows) | "Unarchive" (idempotent) |
| 11 | `GET /api/v1/recipes/{recipeId}/image` | Card thumbnail | `<img src>` per card — **anonymous**, `Cache-Control: public, max-age=86400, immutable` |
| s1 | `GET /api/v1/recipes/{recipeId}` | Card click prefetch | Navigating to `/recipes/{id}` (recipe-detail's load; shared cache) |
| s2 | `GET /api/v1/recipes/{recipeId}/ratings/summary` | Card taste score | Per visible card (no `versionId` → recipe-level aggregate) — N+1, §8 Q4 |

## 3. Library grid — anatomy & field mapping

### 3a. Card — reads `RecipeDto` (#1 rows, once the listing exists)

| Display element | Source field |
|---|---|
| Photo | `imageUrl` (server-relative; null → no-photo placeholder, card fully functional) — actual bytes via #11 |
| Name | `name` (≤160) |
| Meta line | `currentVersionBody.metadata.totalTimeMins` ("25 min") · `metadata.servings` ("serves 2") · `metadata.cuisine` |
| Quality badge | `dataQuality` — USER_VERIFIED tint-chip · IMPORTED / AI_GENERATED / WEB_DISCOVERED muted badge (HLD low-trust indicator) |
| Catalogue mark | `catalogue` — USER (default, unmarked) · SYSTEM → "from the pool" caption + Promote action (§5) |
| Nutrition state | `nutritionStatus` — CALCULATED (nothing) · PENDING "nutrition pending" caption · PARTIAL amber "n ingredients need review" (count from `currentVersionBody.ingredients[].needsReview`) |
| Taste numeral | `RecipeRatingSummaryDto.avgTaste` (s2; null → "—" unrated) + `count` tooltip |
| Archived dimming | `archivedAt` non-null → muted card + "archived" chip (visible only with the archived filter on) |
| Fork provenance | `forkedFromRecipeId` non-null → "forked" caption, link to parent |
| Version tag | `currentVersion` ("v3") — detail page owns history |

Not displayed: `userId`, `optimisticVersion`, `deletedAt` (soft-deleted rows
never reach the UI), `currentBranchId`, `branches[]`, `lastUsedInPlanAt`
(planner bookkeeping), `currentVersionBody` ingredient/method bodies (detail
page), `description` (detail page hero).

### 3b. Search & filters — request mapping (target shape, §8 Q1)

The mock filters client-side. The LLD's `RecipeSearchCriteriaDto`
(lld/recipe.md §Search) is the intended wire shape; controls map onto it so the
page is contract-ready:

| Control | Criteria field | Notes |
|---|---|---|
| Search box | `namePattern` | debounced |
| Cuisine chips | `cuisine` | values harvested from loaded rows |
| Max-time chips (≤20/≤30/≤45) | `maxTotalTimeMins` | |
| Quality chips | `minDataQuality` | **ordinal floor**, not equality — the mock's per-tier equality filter is a delta (§9.2) |
| Catalogue toggle (mine / pool / all) | `catalogue` | absent in mock |
| "Show archived" toggle | `includeArchived` | default false |
| Pagination | `page` / `size` | Spring page conventions elsewhere: size default 20, max 100 |

Diet/meal-type/equipment/protein facets exist on the criteria record but are
deferred to a filter drawer (v1.1) — listed for completeness, no v1 control.

## 4. Import flows — anatomy & field mapping

Entry: a single **Import** button → sheet with two tabs (**Paste a URL** ·
**Browse & save**) + a "start from scratch" link to the manual form (§4d).

### 4a. Preview — `POST /imports/preview-url` (#3) / `POST /imports/preview-html` (#4)

| Control | Request field | Constraints |
|---|---|---|
| URL input* | `url` | 1–2048, valid URI (both endpoints) |
| (in-app browser, hidden) | `html` | #4 only; 1–4,000,000 chars — `document.documentElement.outerHTML` captured on "Save recipe" |
| — | `catalogue` | nullable no-op: "imported recipes are always created in the caller's USER catalogue regardless" — **no control** |

Response `RecipeImportPreview` → review screen:

| Display element | Source field |
|---|---|
| Editable candidate form | `parsedRecipe` (a full `CreateRecipeRequest` — same form as manual create §4d, pre-filled) |
| Source line | `sourceUrl` ("from seriouseats.com") |
| Extraction chip | `extractionMethod` (json_ld / microdata / common_selectors; open vocabulary) — "read automatically" tooltip |
| Warning rows | `validationWarnings[]` (strings — unparseable ingredient lines, time inconsistency) → amber list above the form |
| **Dedup pre-warning** | `dedupCandidate` non-null → inline card "looks ≈{ingredientOverlap·100}% similar to a recipe you have" + link to `recipeId` — shown *before* save, same options as the 422 dialog (§4c) |
| (carried, hidden) | `previewToken` (nullable ≤200) — echoed on confirm; v1 flow is stateless, the confirm body is authoritative |

While in flight: skeleton + "reading the page…" (extraction can take seconds;
the preview endpoints are deliberately non-transactional reads). 422 →
`RecipeImportFailure` with machine-readable `failureReason` → copy map:

| `failureReason` | UI copy |
|---|---|
| `no_extractor_matched` | "Couldn't find a recipe on that page — enter it manually?" → manual form pre-filled with the URL as description |
| `fetch_timeout` / `fetch_io_error` | "Couldn't reach that page" + retry |
| `fetch_4xx_<status>` | "The site refused (HTTP n)" — suggest the Browse & save tab (in-app browser path beats bot-blocking) |
| `fetch_5xx_<status>` | "The site had an error" + retry |
| `oversize` / `schema_mismatch` | "Page too large / malformed" → manual entry offer |
| unknown token | open vocabulary — degrade to `detail` text |

### 4b. Confirm — `POST /imports/confirm` (#5)

| Control | Request field | Constraints |
|---|---|---|
| (hidden) | `previewToken` | nullable; echo from preview |
| (hidden) | `sourceUrl`* | 1–2048 — provenance row |
| (hidden) | `extractionMethod` | nullable ≤64 — provenance row |
| The reviewed form | `recipe`* | full `CreateRecipeRequest` (§4d constraints) — **authoritative**, edits included |

201 + `Location` + `RecipeDto` → toast "added to your library — nutrition
calculating" (`nutritionStatus = PENDING`), grid refresh, deep link to detail.
The recipe lands `catalogue = USER`, `dataQuality = IMPORTED`.

### 4c. Dedup 422 — `RecipeImportDuplicate` (on #2 create and #5 confirm)

Body: ProblemDetail + `candidateRecipeId` (uuid) + `ingredientOverlap` (0–1).
Dialog (HLD wording): **"Merge, import as a variant, or import anyway?"** with
the candidate card rendered via s1 (`GET /recipes/{candidateRecipeId}`):

| Dialog action | Contract backing |
|---|---|
| **Open existing** (default) | navigate `/recipes/{candidateRecipeId}` |
| **Import as variant** | `POST /recipes/{candidateRecipeId}/branches` with the parsed body as `CreateBranchRequest.body`, `branchPointVersionId` = candidate's current version, name slugified from source domain — see recipe-detail §5 |
| **Merge** | **no endpoint** — v1 renders as "open existing and edit" (§8 Q2) |
| **Import anyway** | **no override flag** on `ConfirmImportRequest` — re-POST deterministically 422s again (§8 Q2, backend gap) |

### 4d. Manual create — `POST /recipes` (#2), `CreateRecipeRequest` ⇄ form

| Control | Request field | Constraints |
|---|---|---|
| Name* | `name` | 1–160 |
| Description | `description` | ≤2000, nullable |
| Ingredient rows* (≥1) | `ingredients[]` | per row: `lineOrder`* ≥0 · `ingredientMappingKey`* 1–160 (auto-derived from display name, advanced-editable) · `displayName`* 1–160 · `quantity` nullable ("to taste") · `unit` ≤16 nullable · `preparation` ≤80 · `optional` toggle. No duplicate (key, preparation) pairs (400) |
| Method steps* (≥1) | `method[]` | `stepNumber`* contiguous from 1 · `instruction`* · `durationMinutes` nullable |
| Metadata* | `metadata` | `servings`* ≥1 · `prepTimeMins`* / `cookTimeMins`* / `totalTimeMins`* ≥0 (total ≈ prep+cook ±5, 400 otherwise) · `equipmentRequired[]` ≤64 each · `fridgeDays` / `freezerWeeks` (freezer only when fridge set) · `packable` · `cuisine` ≤64 · `mealTypes[]` ≤32 each |
| Tags expander | `tags` | nullable — "AI inference fills if absent" caption; `protein` ≤64 · `cookingMethod` ≤64 · `complexity` MINIMAL/MODERATE/INVOLVED · `flavourProfile[]` / `dietaryFlags[]` ≤32 each |

201 → `dataQuality = USER_VERIFIED`, `nutritionStatus = PENDING`. 422 → dedup
dialog (§4c). The same form component is reused by the preview review (§4a) and
recipe-detail's edit.

## 5. Card actions — catalogue state machine (#7–#10)

| Recipe state | Card actions |
|---|---|
| `catalogue = SYSTEM` | **Add to my library** (primary → #7 promote; 200 returns the flipped `RecipeDto`) |
| `catalogue = USER`, active | **Archive** (ghost → #9) · **Remove from my library** (overflow, confirm copy: "stays in the recipe pool — your versions are preserved" → #8, 204) |
| `archivedAt` non-null | **Unarchive** (→ #10) |

Semantics the UI must surface:

- **Promote is flip-in-place** — versions/branches/ratings survive; plans
  referencing the id keep working. 422 = already USER / deleted / archived →
  toast with server detail + row refresh.
- **Demote keeps the data** — flip to SYSTEM, `userId` retained for provenance;
  *not* a delete. 422 = already SYSTEM. 404 = not owned by caller.
- **Archive/unarchive are idempotent** (re-archive → 204 no-op); archive of a
  deleted recipe → 422.
- System rows auto-archive after 90 days unused (server scan) — no UI trigger;
  the archived filter is where they reappear.

## 6. (state machines folded into §5; the import flow's only state is the §4a→§4b stepper)

## 7. Not on this page

| Contract item | Home |
|---|---|
| `GET /recipes/{id}` full body, versions, branches, diffs, revert | recipe-detail ([recipe-detail.md](recipe-detail.md)) |
| Substitutions (list/accept/reject/promote), with-substitutions view | recipe-detail §6 |
| Ratings list/mine/POST/PUT/DELETE (beyond the card summary read s2) | recipe-detail §7 |
| `PUT /recipes/{id}` manual edit | recipe-detail §4 |
| `POST /recipes/{id}/image` upload (multipart) | recipe-detail §8 |
| `GET /recipes/{id}/import-provenance` | recipe-detail §9 |
| `POST /recipes/admin/run-archive-scan` | Admin page (manual scan trigger) |
| Discovery jobs / "find me more recipes" | Discover ([discover.md](discover.md)) |
| Adaptation pending changes | recipe-detail §10 + Activity page |
| `POST /nutrition/recipes/{id}/versions/{vid}/recalculate` | recipe-detail (nutrition.md §7 cross-ref) |
| AI recipe generation | no user-facing endpoint (planner-internal gap fill) |

## 8. Status-code → UI map

| Code | Where | UI behaviour |
|---|---|---|
| 422 `recipe-import-duplicate` | #2, #5 | dedup dialog (§4c) — an offer, not an error |
| 422 `recipe-import-failure` | #3, #4, #6 | `failureReason` copy map (§4a) + manual-entry fallback |
| 422 catalogue violation | #7/#8/#9 | toast with server detail; refresh row (state moved elsewhere) |
| 404 | #7–#10, s1 | "recipe no longer exists" toast → grid refresh |
| 400 | #2, #5 forms | inline field errors (`errors[]` extension) |
| 404 image | #11 | hide `<img>`, placeholder (already mock behaviour) |
| 401 | all | global session-expired redirect |

**Open questions (flagged, not resolved here):**
1. **No library read.** The contract has no `GET /recipes` list/search — the
   page's core read is unbacked. `RecipesController` javadoc: "user-private
   filtering belongs in search/list endpoints later"; the LLD specifies
   `GET /`, `/user-catalogue`, `/system-catalogue` + `RecipeSearchCriteriaDto`
   + `Page<RecipeDto>` but none shipped (internal `findPlannableCandidates` is
   planner-only). **Headline backend gap candidate**: paginated
   `GET /api/v1/recipes` with at minimum `catalogue`, `namePattern`, `cuisine`,
   `maxTotalTimeMins`, `minDataQuality`, `includeArchived`, `page`, `size`.
2. **Dedup dialog half-backed.** "Merge" has no endpoint; "import anyway" has
   no override flag, so the HLD's three-way dialog can only honestly offer
   open-existing + variant-branch. Backend gap candidate: `forceImport`
   (or `ignoreDuplicateOfRecipeId`) on `ConfirmImportRequest` /
   `CreateRecipeRequest`; merge stays v2.
3. **One-shot `/imports/url` bypasses dedup** (LLD: backward-compat, persists
   directly). Inconsistent safety: the same URL 422s on confirm but slips
   through one-shot. UI decision here: the page uses preview→confirm
   exclusively and does **not** expose one-shot (#6 listed for completeness);
   propose deprecating it or adding the dedup gate.
4. **Card taste score is N+1.** No rating aggregate on `RecipeDto`;
   `ratings/summary` per visible card. Tolerable behind a cache at mock scale;
   fold an `avgTaste`/`ratingCount` into the future list DTO (§Q1) instead.
5. **`previewToken` semantics drift.** LLD Flow 2 describes a signed 15-minute
   token validated on confirm; the shipped contract says nullable opaque echo,
   "v1 keeps the flow stateless — the confirm body carries the authoritative
   recipe". Spec follows the contract (token optional, never blocks); pin the
   LLD text.

## 9. Mock deltas (to make the mock match this spec)

1. Retype the recipes slice on `RecipeDto` (generated types): `tier` →
   `dataQuality` enum, `timeMin/serves/cuisine` → `currentVersionBody.metadata`,
   `taste` → ratings-summary join, `img` → `imageUrl` (+404 placeholder),
   drop bespoke `Recipe` shape.
2. Quality filter becomes a `minDataQuality` ordinal floor (currently
   per-tier equality); add catalogue toggle (mine/pool/all) and "show archived".
3. Add card states: SYSTEM badge + Promote, archived dimming + Unarchive,
   `nutritionStatus` PENDING/PARTIAL captions with needs-review counts,
   forked-from caption.
4. Add the Import sheet: URL preview → editable review form (validation
   warnings, extraction chip, dedup pre-warning) → confirm; seed one preview
   fixture with warnings + one dedup hit exercising the §4c dialog (variant
   branch wired, merge/import-anyway disabled with gap tooltips).
5. Add the manual-create form (shared component with the review form) with the
   §4d constraint set; on save run a mock dedup against ingredient overlap.
6. Card overflow menu: promote/demote/archive/unarchive with confirm copy and
   idempotent re-tap behaviour; remove from grid only on demote (it moves to
   the pool view), not on archive (dim instead).
7. Grid pagination ("load more", page size 20) in front of the seeded array —
   keeps the store shape ready for the future list endpoint.
