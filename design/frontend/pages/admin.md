# Page spec — Admin (`/admin`)

Contract-complete but short: an allowlist-gated operator console — status, AI
cost, call log, decision-log explorer. Read-only in v1 (every on-page endpoint is
a GET). Companion docs: [../ia.md](../ia.md),
[../design-language.md](../design-language.md).

---

## 1. Intent

- **Allowlist-gated, fail-closed** (`auth/api/AdminAccessGuard`): every admin
  handler calls `requireAdmin()` against the config allowlist
  (`mealprep.admin.user-ids`) — anonymous 401, authenticated-but-not-listed 403.
  With the default empty allowlist *everyone* is denied. There is no
  role/claim on the session: admin-ness is config, invisible to the client (§5).
- **Operational snapshot** (capability C-G-032, `paths/core.yaml#adminStatus`):
  "status=UP when the database is reachable, DEGRADED otherwise … lastUsdaCallAt
  is a process-local liveness signal that resets on restart."
- **Cost in micro-pence** (`schemas/ai.yaml`): all AI spend figures are integer
  micro-pence (`costMicroPence`, `totalMicroPence`) except the status card's
  `aiMonthToDatePence` (pence, number). Display in £: micro-pence ÷ 100 000 000;
  pence ÷ 100.

## 2. Endpoint inventory

| # | Endpoint | Card | When called |
|---|----------|------|-------------|
| 1 | `GET /api/v1/admin/status` | Status card (also the page's access probe, §5) | On load + manual refresh |
| 2 | `GET /api/v1/admin/ai/cost-summary?windowHours` | Cost card | On load + window change (1–720h, default 24) |
| 3 | `GET /api/v1/admin/ai/call-log?page&size&taskType&userId` | Call-log table | On tab open + filter/page change |
| 4 | `GET /api/v1/admin/ai/prompt-templates?page&size` (+ `GET .../{name}/{version}`) | Prompt drawer | Lazy, from a call-log row's prompt ref |
| 5 | `GET /api/v1/admin/decision-log/{decisionId}` | Explorer | Lookup by id |
| 6 | `GET /api/v1/admin/decision-log/trace/{traceId}` | Explorer | Lookup by trace (also deep-linked from a notification/call-log `traceId`) |
| 7 | `GET /api/v1/admin/decision-log/{decisionId}/ancestry?maxDepth` | Explorer | "Walk ancestry" (1–32, default 32) |
| 8 | `GET /api/v1/admin/planner/decisions/{planId}?traceId` | Planner-chain panel | Lookup by planId |

Other modules' admin verbs (discovery source enable/disable + orphan sweep,
adaptation sweep/retry + prompt-version traces, recipe archive scan) are ops
*actions* — deliberately not on this v1 read-only console (§6).

## 3. Anatomy & field mapping

### 3a. Status card — `AdminStatusDto` (#1)

| Display element | Source field |
|---|---|
| Health badge | `status` — UP olive ✓ · DEGRADED red ✕ (still a 200; degraded ≠ error state) |
| DB row | `dbConnected` boolean mark |
| Last AI / USDA call | `lastAiCallAt` / `lastUsdaCallAt` (relative; null → "none yet"; USDA caption: "since last restart") |
| Month-to-date AI spend | `aiMonthToDatePence` ÷ 100 → "£12.34 this month (UTC)" |
| Checked-at caption | `checkedAt` |

### 3b. Cost card — `CostSummaryDto` (#2)

Window select (24h / 72h / 7d / 30d → `windowHours`). Stat band: `totalCalls`,
`totalMicroPence` (→ £). `topUsers[]` (`CostSummaryUserEntry`: `userId`,
`calls`, `costMicroPence`) → top-20 spender table; userId renders raw (no
username join — same gap as [settings.md](settings.md) §8 Q2), click filters #3
by that user.

### 3c. Call-log table — `AiCallLogDto` page (#3)

Filters: `taskType` (9-value enum select: PREFERENCE_DELTA_UPDATE …
PLANNER_PHASE2_AUGMENTATION), `userId` (from §3b click); pager (size ≤100,
newest-first).

| Column | Source field |
|---|---|
| When / latency | `createdAt`, `latencyMs` (`completedAt` tooltip) |
| Task / tier / model | `taskType`, `modelTier` (CHEAP/MID/HIGH chip), `modelId` |
| Status | `status` — PENDING amber · SUCCEEDED olive · FAILED red + `errorKind` (AI_UNAVAILABLE / INVALID_REQUEST / INVALID_RESPONSE) |
| Tokens / cost | `requestTokens`/`responseTokens` (nullable), `costMicroPence` → £ |
| Prompt ref | `promptRefName`@`promptRefVersion` (nullable) → opens #4 drawer (`PromptTemplateDto`: systemPrompt/userPromptTemplate read-only viewers, outputSchema/tools JSON, sourceHash) |
| Trace link | `traceId` (nullable) → explorer #6 |

### 3d. Decision-log explorer — #5/#6/#7 (`DecisionLogDto`)

One input (uuid) + mode toggle (decision / trace). Row anatomy: `scopeKind` +
`scale` (WEEK/RECIPE/OTHER) chips, `triggeredBy`, `actorUserId` (nullable),
`iteration`, `durationMs`, `createdAt`; collapsible JSON viewers for `inputs`,
`candidates`, `chosen`, `emittedDirective`; `reasoning` in serif (it is AI
voice). `parentDecisionId` → "Walk ancestry" (#7) renders the chain root-first;
`AncestryResponse.cycleDetected = true` → red warning "depth cap hit — parent
chain may be cyclic". Trace mode (#6) lists creation-ordered rows; empty list is
a valid result ("no decisions for this trace"), not an error.

### 3e. Planner-chain panel — `PlannerDecisionChainDto` (#8)

`planId` input (+ optional `traceId` narrowing). `rows[]`
(`PlannerDecisionRowDto`: `kind` e.g. "STAGE_C_DONE", `inputs`/`outputs` JSON,
`reasoning`, `parentDecisionId`) rendered as a vertical DAG/timeline,
`createdAt` ascending. Contract note: "plans generated before planner-01l have
no rows (empty list; no retroactive backfill)" — empty state copy says exactly
that.

## 4. Status-code → UI map

| Code | Where | UI behaviour |
|---|---|---|
| 401 | all | global session-expired redirect |
| 403 | all | the §5 not-an-admin state (full-page), never per-card |
| 404 | #4 detail, #5, #7 | "no such decision/template" inline on the lookup |
| 400 | #2/#3/#7 params | unreachable via UI controls (clamped); dev error |

## 5. Allowlist 403 UX (decision)

**Route hidden + lazy probe; 403 renders a quiet dead-end, not an error.**
`/auth/me` carries no admin flag, so the client cannot know membership without
asking. Decision:

- The nav rail does **not** show /admin by default. On first shell boot of a
  session, fire #1 (`admin/status`) once, silently: 200 → reveal the nav entry
  and cache `isAdmin=true`; 403 → never show it again this session.
- Direct navigation to `/admin` while non-admin: full-page "This area is
  restricted." (no detail, no retry — fail-closed UX matching the guard).
- Backend gap candidate (low): an `isAdmin` boolean on `GET /auth/me` would
  remove the probe request and the flash-of-hidden-nav.

## 6. Not on this page

| Capability | Home |
|---|---|
| Discovery source enable/disable, orphan sweep, job sync | ops/curl (v1) — mutating admin verbs deliberately excluded from the read-only console |
| Adaptation sweep-expired / retry-failed-job / prompt-version traces | ops/curl (v1) |
| Recipe `run-archive-scan` | ops/curl (v1) |
| Allowlist editing | server config `mealprep.admin.user-ids` — no API exists |
| Per-user AI budget caps / cost gating rules | server config (ai module properties) |
| Actuator/health (infra) | not proxied through the SPA |

## 7. Open questions

1. **Money units are inconsistent by design** (`aiMonthToDatePence` pence vs
   `*MicroPence` micro-pence): centralise conversion in one formatter; a unit
   mix-up here is a 10⁶ display error.
2. **Mutating admin verbs**: if the console grows action buttons (v1.5), the
   §2-excluded endpoints are the inventory — needs a product call on whether the
   UI should be able to disable a discovery source.
3. **userId columns** render raw UUIDs (no username join anywhere admin-side) —
   same backend gap family as settings §8 Q2.

## 8. Mock deltas

1. Add the lazy admin probe + hidden-nav behaviour (§5); mock store gets an
   `adminAllowlisted` flag to demo both outcomes.
2. Build the four cards against the real DTOs — status badge pair, window-select
   cost band with top-spender table → call-log filter link, call-log pager with
   tier/status chips and prompt drawer, explorer with JSON viewers + ancestry
   walk + `cycleDetected` warning, planner DAG timeline.
3. Implement the £ formatter with both unit families and a unit test pinning
   the 10⁶/10² conversions (§7 Q1).
