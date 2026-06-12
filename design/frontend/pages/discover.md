# Page spec — Discover (`/discover`)

The contract-complete specification: every endpoint this page consumes, and the UI
that each request field and response field demands. A control exists for every
writable field; a display home exists for every returned field (or an explicit
"not on this page" entry). Companion docs: [../ia.md](../ia.md),
[../design-language.md](../design-language.md). Template: [nutrition.md](nutrition.md)
(the pilot). Siblings: [recipes.md](recipes.md), [recipe-detail.md](recipe-detail.md).

Discover runs **autonomous recipe discovery jobs**: pick constraints, start a
job, watch it work (poll + scrape-log transparency), then triage the ingested
results into your library. Discovered recipes land in the **system catalogue**
automatically — "Keep" is a *promote*, not an import (§5, the traced handoff).

---

## 1. Intent (HLD)

From `design/recipe-system.md` + `design/system-overview.md` + `lld/discovery.md`
+ `lld/recipe-extraction-pipeline.md`:

- **Discovery feeds the system catalogue** — "Online discovery — search the web,
  hard-filter against constraints, score against preferences. Goes into the
  system catalogue; user can promote to their catalogue." Every successful
  fetch is already a recipe (`dataQuality = WEB_DISCOVERED`) before the user
  sees it.
- **The job is the unit of work** — async lifecycle `QUEUED → RUNNING →
  SUCCEEDED | FAILED | PARTIAL`; "one source down → partial; all sources down →
  failed with surfaced error." POST returns 202 + the queued DTO; the caller
  polls.
- **Constraints are frozen at enqueue** — `DiscoveryConstraints` is "the
  snapshot of what the planner / user wanted at job-enqueue time… a constraint
  change mid-job does not retroactively alter the search."
- **Hard constraints are deterministic, the AI filter is advisory** —
  `mustExcludeIngredientMappingKeys` is applied "as a second hard-filter pass
  after extraction — the deterministic safety net — and never trusts the AI
  filter to enforce it." The AI candidate filter drops only on explicit model
  rejection; an AI outage "never silently shrinks the candidate set"
  (skip-and-flag).
- **Every fetch is audited** — the scrape log is "an append-only audit of every
  fetch attempt… powers debugging, rate-limit diagnostics, robots.txt audit,
  and content-fingerprint dedup." The `AI_FILTER_REJECTED` rows exist precisely
  "so the audit log explains the `candidatesSeen → candidatesAfterFilter`
  drop." This page is where that transparency surfaces.
- **Cancellation keeps the harvest** — "already-ingested recipes are kept —
  they are valid system-catalogue entries."
- **Sources are registry rows** — ~25–30 curated sites + a search-API source;
  per-source rate limits, robots.txt policy, circuit-breaker
  (`failure_streak ≥ 5` → skipped an hour). "User can disable any source via
  Settings" (LLD intent — no shipped user endpoint, §9 Q4); admin
  enable/disable is a separate surface.

## 2. Endpoint inventory

The discovery module exposes 11 operations; **7** are consumed by this page,
plus two recipe-module support calls for the results triage. 4 are admin-only
(§8).

| # | Endpoint | Where | When called |
|---|----------|-------|-------------|
| 1 | `POST /api/v1/discovery/jobs` | Start panel | "Start discovery" (202 + `Location` + QUEUED `DiscoveryJobDto`) |
| 2 | `GET /api/v1/discovery/jobs/{jobId}` | Job card | Poll while QUEUED/RUNNING (client cadence ~2 s; no push channel in v1) + once on terminal |
| 3 | `POST /api/v1/discovery/jobs/{jobId}/cancel` | Job card | "Cancel" while QUEUED/RUNNING |
| 4 | `GET /api/v1/discovery/jobs/{jobId}/scrape-log?page&size` | Transparency drawer + results | Drawer open; re-fetched on terminal to build the results list (page ≤100, default 20) |
| 5 | `GET /api/v1/discovery/jobs?page&size` | History panel | On load + after every job terminal (queued-at descending) |
| 6 | `GET /api/v1/discovery/sources` | Sources panel | Panel open (full registry list) |
| 7 | `GET /api/v1/discovery/sources/{sourceKey}` | Sources panel | Row expand (same DTO — optional refresh) |
| s1 | `GET /api/v1/recipes/{recipeId}` | Result cards | Name/time/cuisine join per ingested row (scrape log carries ids only; cached) |
| s2 | `POST /api/v1/recipes/{recipeId}/promote` | Result cards | **Keep** (§5 — the traced handoff) |

## 3. Start panel — `StartDiscoveryJobRequest` (#1)

| Control | Request field | Constraints |
|---|---|---|
| (fixed) | `trigger`* | `USER_INITIATED` — this page's only value (COLD_START is planner-internal, SCHEDULED is the weekly sweep) |
| "How many" stepper | `requestedCount`* | 1–50, default 10 |
| Free-text mood box | `constraints.preferenceHints[]` | free-form hints consumed by the AI candidate filter ("lighter dishes", "high-protein") — the mock's query string maps **here**, not to a search-API query |
| Cuisine chips | `constraints.requiredCuisines[]` | nullable list |
| Meal-type chips | `constraints.requiredMealTypes[]` | members of the canonical meal-type set (400 otherwise) |
| Max-time chip | `constraints.maxTotalTimeMins` | ≥0 |
| Dietary chips | `constraints.dietaryFlags[]` | e.g. "vegetarian", "gluten_free" |
| (client-populated, hidden) | `constraints.mustExcludeIngredientMappingKeys[]` | the user's hard-constraint snapshot — pre-normalised mapping keys; **the caller computes this** (LLD), so the page must read `GET /preferences/hard-constraints` and translate — §9 Q3 (safety gap candidate) |
| (fixed) | `constraints.schemaVersion`* | 1 |
| Per-source cap (advanced) | `constraints.maxRecipesPerSource` | ≥1, must be ≤ `requestedCount` (400) |
| Source picker (advanced) | `sourceKeys[]` | nullable = all enabled; named subset — any unknown **or disabled** key → 422; populate from #6 `enabled` rows |
| — | `traceId` | nullable; planner/pipeline plumbing — no control |

While a job is QUEUED/RUNNING the start button disables ("a discovery is
already running" — UI rule; the contract itself allows concurrent jobs).
422 = "no enabled source matched" → re-open the source picker with the error.

## 4. Job card — `DiscoveryJobDto` (#2) — state machine & what each state shows

The contract has **two live states and three terminal states** — the mock's
QUEUED → SEARCHING → FILTERING → DONE timeline is contract-divergent (§10.1);
phase detail during RUNNING comes from the scrape log, not the status enum.

| Job status | Card renders |
|---|---|
| QUEUED | "Queued — waiting for a runner…" · constraints recap · **Cancel** |
| RUNNING | spinner + live counters (below) · scrape-log drawer (rows are written eagerly per fetch — this is the live progress feed) · **Cancel** |
| SUCCEEDED | results grid (§5) + per-source stat strip + counters |
| PARTIAL | same as SUCCEEDED + amber banner "some sources failed" from `errorSummary` + `sourcesFailed[]` chips |
| FAILED | error card: `errorSummary`; the string "cancelled by user" distinguishes a cancel from a genuine failure (§9 Q2 — fragile) · results grid still renders if `recipesIngested > 0` (cancelled jobs keep their harvest) |

Field mapping:

| Display element | Source field |
|---|---|
| Status chip | `status` (enum above) |
| Constraints recap | `constraints.*` (chips re-rendered read-only — frozen snapshot) + `requestedCount` ("asked for 10") |
| Trigger badge | `trigger` — USER_INITIATED (none) · SCHEDULED "weekly sweep" · COLD_START "planner cold-start" (history rows only) |
| Timing line | `queuedAt` → `startedAt` → `completedAt` ("ran 38 s") |
| Funnel counters | `candidatesSeen` → `candidatesAfterFilter` ("AI filter kept 14 of 22") → `recipesIngested` ("8 saved") + `recipesSkippedDuplicate` ("3 you already had") |
| Per-source chips | `sourcesRequested[]` coloured by membership of `sourcesSucceeded[]` (olive) / `sourcesFailed[]` (red) |
| Error text | `errorSummary` (nullable; FAILED/PARTIAL only) |
| (plumbing) | `id` (poll key), `userId`, `traceId` (admin correlation), `optimisticVersion` — not displayed |

**Cancel (#3)** — semantics per the shipped service (the OpenAPI description
lags it, §9 Q2):

| Job state | Result |
|---|---|
| QUEUED | 200 — atomically flipped to FAILED, `errorSummary = "cancelled by user"` |
| RUNNING | 200 — **returns the still-RUNNING DTO**; an in-memory flag stops the runner between candidates; keep polling until FAILED lands. Card copy: "stopping after the current page…" |
| SUCCEEDED / FAILED / PARTIAL | 422 `discovery-job-already-terminal` → re-fetch, hide the button |

Already-ingested recipes survive cancellation (render the partial results grid).

## 5. Results triage & the Keep handoff — #4 + s1 + s2

**Where results come from:** the job DTO carries only counters. The result
*rows* are the scrape-log entries with `status = SUCCESS`, each carrying the
`recipeId` of the **already-persisted system-catalogue recipe** (`dataQuality =
WEB_DISCOVERED`). Fetch #4 (filter client-side to SUCCESS), join s1 for
name/time/cuisine.

| Card element | Source |
|---|---|
| Title / meta | s1 join: `RecipeDto.name` · `currentVersionBody.metadata.totalTimeMins` · `cuisine` |
| Domain | scrape row `canonicalUrl` ?? `candidateUrl` (hostname) |
| Confidence pill | scrape row `extractionConfidence` (0–1) + `extractionMethod` tooltip ("read from the page's recipe data" for json_ld) — note: this is **extraction** confidence; the mock labels it "AI filter" (§10.3) |
| **Keep** (primary) | **`POST /api/v1/recipes/{recipeId}/promote`** (s2) — flips the recipe into the USER catalogue, one tap, 200 `RecipeDto` → "✓ in your library". **Traced:** discovery persists via the internal `RecipeWriteApi.saveImportedRecipe` SPI during the job — there is no recipe-imports HTTP call in the Keep path; the recipe already exists, so the only user action left is the catalogue flip. 422 = already promoted (idempotent-ish — flip card state) |
| **Skip** (ghost) | **no contract call** — the recipe stays in the system catalogue (planner may still draw on it). v1 renders skip as local dismissal; an "and keep it out of my plans" variant would be `POST /recipes/{recipeId}/archive` — product decision, §9 Q5 |
| Kept state | "✓ in your catalogue" + deep link `/recipes/{recipeId}` |

## 6. Transparency drawer & per-source strip — `DiscoveryScrapeLogEntryDto` (#4)

The HLD's audit surface, paginated. Per-source stat strip = client-side
group-by `sourceKey` over the fetched rows ("seriouseats · 9 rows · 4 saved").

| Display element | Source field |
|---|---|
| Row URL | `candidateUrl` (host + path, truncated) · `canonicalUrl` tooltip when different (redirect followed) |
| Source chip | `sourceKey` |
| Outcome icon + copy | `status` (`ScrapeOutcome`) → map below |
| Skip reason | `skipReason` (`ScrapeSkipReason`, nullable) — sub-line |
| HTTP / latency | `httpStatusCode` (nullable) · `latencyMs` ("420 ms") |
| Robots verdict | `robotsTxtOutcome` — ALLOWED (nothing) · DISALLOWED "site asked us not to" · UNAVAILABLE "robots.txt unreachable — skipped politely" · SKIPPED (API source — nothing) |
| Extraction | `extractionMethod` + `extractionConfidence` (SUCCESS rows) |
| Recipe link | `recipeId` non-null → "view recipe" |
| Error detail | `errorClass` + `errorMessage` (collapsed; HTTP_ERROR/EXTRACTION_FAILED rows) |
| When | `occurredAt` |
| (plumbing) | `id`, `jobId`, `contentFingerprint` (dedup internals — not displayed) |

`ScrapeOutcome` → row copy:

| Outcome | Copy |
|---|---|
| SUCCESS | "saved" (olive) |
| DUPLICATE | "you already have this one" |
| HARD_CONSTRAINT_VIOLATION | "contains an ingredient you've excluded" (red — the deterministic safety net at work) |
| SKIPPED + `skipReason = AI_FILTER_REJECTED` | "AI filter: not your taste" |
| SKIPPED + `LOW_CONFIDENCE` | "couldn't read it confidently enough" |
| SKIPPED + `JOB_QUOTA_REACHED` | "quota already met" |
| RATE_LIMITED | "slowed down to be polite" |
| ROBOTS_DISALLOWED | "site disallows scraping" |
| HTTP_ERROR | "page error (HTTP n)" |
| EXTRACTION_FAILED | "no recipe found on the page" |

## 7. History & sources panels

### 7a. Job history — `DiscoveryJobDtoPage` (#5)

Rows (newest first): `constraints.preferenceHints` joined as the "query" line
(fallback: cuisine/dietary chips) · `trigger` badge (SCHEDULED / COLD_START
rows appear here read-only — the weekly sweep and planner cold-starts share
the user's job list) · `status` chip · `queuedAt` ("2 days ago") ·
`candidatesSeen → recipesIngested` counts ("22 found · 8 saved"). Row click →
re-open the job card (#2 + #4). "Kept" counts (mock) are **not derivable** —
promotion isn't tracked per job (§10.6).

### 7b. Sources panel — `DiscoverySourceDto` (#6/#7), read-only

| Display element | Source field |
|---|---|
| Name + kind | `displayName` · `kind` (SITEMAP / RSS_FEED / CATEGORY_INDEX / SEARCH_API → "search API" caption) · `baseUrl` (host) |
| Enabled state | `enabled` — disabled rows muted "unavailable (admin)" |
| Health line | `failureStreak` ≥ 5 → "paused after repeated failures" (circuit breaker) · `lastSuccessAt` ("last worked 2 h ago") · `lastFailureAt` |
| Politeness tooltip | `requestsPerMinute` / `requestsPerDay` · `respectRobotsTxt` ("honours robots.txt") · `userAgent` |
| Notes | `notes` (nullable, muted) |
| (plumbing) | `id`, `sourceKey` (request key for the §3 picker), `optimisticVersion` |

**No user enable/disable control** — see §9 Q4 and §8.

## 8. Not on this page

| Contract item | Home |
|---|---|
| `POST /discovery/admin/sources/{sourceKey}/enable` · `/disable` | Admin page — and note: admin-disable deliberately does **not** set `user_disabled` (distinct concepts) |
| `POST /discovery/admin/run-orphan-sweep` | Admin (resumes heartbeat-stale RUNNING jobs) |
| `POST /discovery/admin/jobs/sync?timeoutSeconds&strictTimeout` (+ its 408/502 semantics) | Planner cold-start internal — never called from a UI |
| User source-disable toggle | **No endpoint** (§9 Q4) — would live on this page's sources panel or /settings once built |
| SCHEDULED weekly-sweep configuration | None in v1 (server cron); sweep jobs merely appear in §7a history |
| Browsing / editing / rating a kept recipe | /recipes ([recipes.md](recipes.md)) + /recipes/{id} ([recipe-detail.md](recipe-detail.md)) |
| Needs-review / nutrition repair of ingested recipes | /nutrition Data quality tab (nutrition.md §6) |
| Hard-constraint editing (the source of `mustExcludeIngredientMappingKeys`) | /preferences |
| `traceId` correlation, decision log | Admin debug surfaces |

## 9. Status-code → UI map & open questions

| Code | Where | UI behaviour |
|---|---|---|
| 202 | #1 | job card mounts in QUEUED; begin polling |
| 400 | #1 | inline field errors (unknown meal type, per-source cap > total, negative time) |
| 422 | #1 | "no enabled source matched" → source picker with error |
| 404 | #2/#3/#4 | "job no longer exists" → refresh history |
| 422 `already-terminal` | #3 | re-fetch job, hide Cancel |
| 200-but-still-RUNNING | #3 on RUNNING | "stopping…" state, keep polling (not an error) |
| 422 | s2 Keep | already in your library → flip card to kept |
| 404 | s1 join | recipe vanished (hard-deleted/raced) → drop the card |
| 401 | all | global session-expired redirect |

**Open questions (flagged, not resolved here):**
1. **No live progress signal.** Status jumps QUEUED → RUNNING → terminal; the
   mock's SEARCHING/FILTERING phases don't exist. v1 derives liveness from
   scrape-log row arrival while polling. SSE/push job progress is the known
   v1.5 item — until then the timeline collapses to 3 visual steps.
2. **Cancel is reported as FAILED.** No CANCELLED status; the UI distinguishes
   a cancel only by `errorSummary == "cancelled by user"` — a string contract.
   The OpenAPI cancel description is also stale ("in-flight 422, 01b
   limitation") versus the shipped behaviour (RUNNING cancel honoured via
   flag, 200). Backend gap candidates: a `CANCELLED` terminal status (or
   structured `cancelled` flag) + refresh the contract text.
3. **Who populates `mustExcludeIngredientMappingKeys`?** The LLD assigns it to
   the caller; for USER_INITIATED jobs that means the *frontend* must fetch
   hard constraints and translate them to mapping keys — a client-trust hole
   for a safety filter (an empty list ingests allergy-violating recipes into
   the pool). Backend gap candidate: server-side injection of the caller's
   hard-constraint snapshot on USER_INITIATED jobs.
4. **User source-disable is missing.** HLD/LLD: "user can disable any source
   via Settings"; the DB has `user_disabled` and admin-disable explicitly does
   NOT set it — but no user endpoint exists and `DiscoverySourceDto` doesn't
   expose the flag. Sources panel ships read-only; backend gap candidate:
   `POST /discovery/sources/{key}/user-disable` (+DTO field).
5. **Skip is semantically empty.** A skipped result remains a live
   system-catalogue recipe the planner can schedule. Product ruling needed:
   local-dismiss only (specced default) vs archive-on-skip.
6. **Per-job "kept" count not derivable** — promotion isn't linked back to the
   discovery job (the scrape row's `recipeId` is, but catalogue state requires
   N× s1 joins). Acceptable v1 cost on the open card; history rows drop the
   stat.

## 10. Mock deltas (to make the mock match this spec)

1. Replace the `DiscoveryStep` enum (QUEUED/SEARCHING/FILTERING/DONE) with the
   contract's `status` (QUEUED/RUNNING/SUCCEEDED/FAILED/PARTIAL); timeline
   becomes Queued · Running · Done(±partial) with live counters; add FAILED and
   PARTIAL card states seeded once each.
2. Start panel: retype `startDiscovery(query, picked)` onto
   `StartDiscoveryJobRequest` — query string → `preferenceHints`, chips →
   `dietaryFlags`/`maxTotalTimeMins`/`requiredCuisines`, add requestedCount
   stepper + advanced source picker; "Nut-free" chip moves to the
   hard-constraint snapshot (`mustExcludeIngredientMappingKeys`), not a
   dietary flag.
3. Results: build cards from scrape-log SUCCESS rows + recipe join (drop the
   bespoke `DiscoveryResult`); relabel the pill extraction-confidence; Keep →
   recipes-store promote (catalogue flip, not append); Skip → local dismissal
   flag only.
4. Add the transparency drawer: seeded scrape-log rows covering at least
   SUCCESS, DUPLICATE, HARD_CONSTRAINT_VIOLATION, AI_FILTER_REJECTED,
   RATE_LIMITED, ROBOTS_DISALLOWED with the §6 copy map; derive the per-source
   stat strip from those rows (drop `job.sources`).
5. Add Cancel with the three-state semantics (QUEUED flip, RUNNING
   "stopping…", terminal hidden) and the kept-harvest partial grid.
6. History: retype on `DiscoveryJobDtoPage` rows (status chip, trigger badge,
   counters from the DTO); drop the underivable "kept" stat; add a SCHEDULED
   seeded row to show non-user jobs.
7. Add the read-only sources panel (#6 mapping) with one circuit-broken and
   one admin-disabled row seeded.
