# Page spec — Activity (`/activity`)

The contract-complete specification: every endpoint this page consumes, and the UI
that each request field and response field demands. A control exists for every
writable field; a display home exists for every returned field (or an explicit
"not on this page" entry). Companion docs: [../ia.md](../ia.md),
[../design-language.md](../design-language.md). Template: [nutrition.md](nutrition.md)
(the pilot).

---

## 1. Intent (HLD)

From `design/feedback-system.md`, adaptation sections of `design/system-overview.md`
(+ `lld/feedback.md`, `lld/adaptation-pipeline.md`):

- **Propose, not apply.** "The optimiser never silently mutates [the user
  catalogue]. Every change is a proposed new version or branch that the user can
  accept, reject, or modify" — presented "as a diff … with an accept/reject control
  per change". This page is the inbox for those proposals.
- **Budgeted suggestions:** top-3 pending changes, ranked by `impact × confidence`
  (rank-at-read; "the cap is a ceiling, not a floor"); proposals expire after 14
  days; a newer same-dimension proposal supersedes the old one.
- **Confidence-tiered routing** is the feedback system's confirmation contract:
  ≥ 0.8 "route automatically"; 0.5–0.8 "route automatically but flag … 'I think you
  meant X — correct me if wrong'"; < 0.5 "ask the user to clarify" — a service call
  back to the user with options, not an AI conversation.
- **Misclassification is correctable, not fire-and-forget.** "The user sees the
  routing and can correct it"; corrections are "logged as ground truth"; "most
  corrections are simple re-routes, not complex undo chains" (undo is best-effort).
- **The routing log is the audit trail** — "every route is traceable from the
  original text through classification to the action taken".
- **Partial success is acceptable** — each destination write is its own
  transaction; failures are "logged, and surfaced to the user".

## 2. Endpoint inventory

12 endpoints across three sections (**Pending changes · Feedback history ·
Clarifications inbox**). `POST /feedback` itself is global-modal, not this page (§7).

| # | Endpoint | Section | When called |
|---|----------|---------|-------------|
| 1 | `GET /adaptation/pending-changes` | Pending | On load + after #3/#4 (top-3 ranked) |
| 2 | `GET /adaptation/pending-changes/{id}` | Pending | Card expand — **mandatory before accept** (the list row carries no `optimisticVersion` or diff; §8 Q1) |
| 3 | `POST …/pending-changes/{id}/accept` | Pending | **Accept** / **Accept with edits** |
| 4 | `POST …/pending-changes/{id}/reject` | Pending | **Dismiss** (optional reason) |
| 5 | `GET /adaptation/recipes/{recipeId}/pending-history?page&size` | Pending | "History for this recipe" drawer (lazy; shared with recipe-detail) |
| 6 | `GET /feedback?page&size` | Feedback | On load + pagination + poll while any visible entry is non-terminal |
| 7 | `GET /feedback/{feedbackId}` | Feedback | Row refresh (the `Location` target after a global-modal submit) + clarification/correction follow-up |
| 8 | `POST /feedback/{feedbackId}/routes/{routingId}/correct` | Feedback | Correction picker submit |
| 9 | `GET /feedback/corrections?page&size` | Feedback | "Corrections log" expander (lazy) |
| 10 | `GET /feedback/clarifications?status&page&size` | Inbox | On load (`status=PENDING`) + "answered/expired" filter chips |
| 11 | `GET /feedback/clarifications/{queryId}` | Inbox | Deep-link / single-card refresh |
| 12 | `POST …/clarifications/{queryId}/answer` | Inbox | Answer submit |

## 3. Pending changes — anatomy & field mapping

### 3a. Top-3 cards — reads `PendingChangeListItemDto[]` (#1)

A bare array (max 3), not a page — there is deliberately no "see all" (§8 Q2).

| Display element | Source field |
|---|---|
| Recipe name + link | `recipeId` → recipes cache join (name lookup; deep-link to recipe detail) |
| Dimension chip | `changeDimension` — SALT_LEVEL · PROTEIN · METHOD_SIMPLIFICATION · PORTION_SIZE · FLAVOUR_BALANCE · ACID_BALANCE · TEXTURE · COOKING_TIME · SUBSTITUTION_PROMOTION · GENERAL (humanised labels) |
| One-line reason | `reasoningPreview` (≤200, nullable → omit) |
| Confidence pill | `confidence` (0–1, two decimals) |
| Impact meter | `impactScore` (0–1; small bar — drives the ranking, caption "ranked by impact × confidence") |
| "expires in N days" countdown | `expiresAt` (amber when < 48 h) |
| Proposed date | `createdAt` (relative) |

Empty state: "No pending changes — the advisor will raise suggestions here."

### 3b. Card expand — reads `PendingChangeDto` (#2)

| Display element | Source field |
|---|---|
| Full reasoning | `reasoning` (advisor voice) |
| Nutrition note | `nutritionalNotes` (nullable → omit) |
| Kind chip | `proposedClassification` — VERSION "updates the recipe" · BRANCH "new variant alongside" · SUBSTITUTION "ingredient swap" (NO_CHANGE never reaches a pending card) |
| **Diff panel** | `proposedDiff` (opaque JSON, pipeline-owned) — render original-red / replacement-green per the HLD approval UX; unknown keys fall back to a raw expander |
| Base pointer | `baseVersionId` / `baseBranchId` → "proposed against v{n}" via recipe cache (stale-base hint if the recipe has moved) |
| Status chip | `status` (§3e machine) + `resolvedAt` on decided cards |
| Superseded link | `supersededBy` (nullable) → "replaced by a newer suggestion" → loads that id via #2 |
| Accepted pointer | `acceptedVersionId` (nullable) → "view the new version" deep-link (recipe detail) |
| Applied edits | `userEdits` (nullable) → "you modified this before accepting" + raw expander |
| Held for accept | `optimisticVersion` → `expectedOptimisticVersion` |
| Not displayed | `userId`, `jobId`, `traceId`, `promptTemplateVersion` (debug/admin) |

### 3c. Accept / reject — #3, #4

**Accept** sends `AcceptPendingChangeRequest`:

| Control | Request field | Constraints |
|---|---|---|
| **Accept** (primary) | `userEdits: null` | accept the proposal as-is → status ACCEPTED |
| **Modify before accepting** expander → editable copy of the diff | `userEdits` (diff overlay) | must still reference the same `baseVersionId`; ingredient keys must exist (server 400 otherwise) → status MODIFIED |
| (hidden) | `expectedOptimisticVersion`* | from #2 — never from the list row (it has no version; §8 Q1) |

**Reject** (ghost) sends `RejectPendingChangeRequest`: optional `reasonNote`
(≤200) in a popover ("helps the advisor learn"). Not idempotent — re-rejecting
returns 422.

Both return the updated `PendingChangeDto`; the card flips to its decided state
and #1 refreshes (a new proposal may enter the top-3).

### 3d. Per-recipe history drawer — #5

Paginated `PendingChangeListItemDto` rows (same mapping as §3a), newest first,
"every change ever proposed for {recipe}". **Known gap (design around):** history
rows carry **no `status`** — the row cannot say accepted/rejected/expired. Render
rows neutrally ("proposed {createdAt} · expired {expiresAt} if past"); status
appears only on row expand (#2 by id). Do not N+1-hydrate the whole page. Backend
ticket candidate: add `status` (+ `resolvedAt`) to the list DTO — §8 Q1.

### 3e. Pending-change state machine

```
PENDING ──accept(as-is)──────→ ACCEPTED   (acceptedVersionId set)
PENDING ──accept(userEdits)──→ MODIFIED   (acceptedVersionId set)
PENDING ──reject─────────────→ REJECTED
PENDING ──14-day sweep───────→ EXPIRED
PENDING ──newer same-dimension proposal─→ SUPERSEDED (supersededBy set)
```

All non-PENDING states are terminal; decided cards show no actions. Buttons exist
only on PENDING cards: **Accept** (primary) · **Modify before accepting** (ghost
expander) · **Dismiss** (ghost).

## 4. Feedback history — anatomy & field mapping

### 4a. Entry card — reads `FeedbackEntryDto` (#6 rows / #7)

| Display element | Source field |
|---|---|
| Quoted text | `text` |
| When | `createdAt` (relative); `updatedAt` not shown |
| Context chip | `context.screen` (RECIPE_DETAIL · PLAN_MEAL_DETAIL · PLAN_VIEW · GROCERY · NUTRITION_DASHBOARD · SETTINGS · GENERAL) + recipe/plan link when `context.recipeId`/`planId` present (`recipeVersion`, `mealSlotId`, `referenceDate` feed the link, not displayed) |
| Status chip | `submissionStatus` (§4d machine) |
| Attempts caption | `classificationAttempts` — shown only > 1 ("3rd attempt"); after ~3 clarification rounds nudge "consider submitting fresh feedback" |
| Route rows | `routes[]` (§4b) |
| "Needs you" link | `pendingClarificationQueryId` (nullable) → anchors to the matching inbox card (§5) |
| Not displayed | `userId`, `traceId` (debug), `lastClassifiedAt` |

**Polling:** entries in RECEIVED / CLASSIFYING are non-terminal — poll #6 (or #7
per entry) every ~2 s with backoff until terminal. No push channel in v1
(SSE is task #172).

### 4b. Route row — reads `RoutingDecisionDto`, confidence-tier display rules

Tier is **server-decided** — render from `decision`, show `confidence` as the
number; never re-derive the tier client-side except as a fallback:

| Band (HLD) | Contract signal | UI treatment |
|---|---|---|
| ≥ 0.8 | `decision = AUTO_ROUTED` | quiet olive ✓ row; destination + action; escape hatch "This isn't right" (low-key) |
| 0.5 – 0.8 | `decision = ROUTED_WITH_FLAG` | amber ? mark + caption "I think you meant {destination} — correct me if wrong"; correction control prominent |
| < 0.5 | **no route row exists** — entry `submissionStatus = CLARIFICATION_PENDING` + `pendingClarificationQueryId` set (`decision = CLARIFICATION_QUEUED` only appears on later inspection) | "… needed you" state on the entry; the *whole* entry pauses (no partial routing); links to the inbox card |

| Display element | Source field |
|---|---|
| Destination chip | `destination` — RECIPE / PREFERENCE / NUTRITION / PROVISIONS |
| Confidence | `confidence` ("confidence 0.92") |
| Extracted fragment | `extractedFeedback` (the slice of the text this route covers — quoted small) |
| Action line | `actionTaken` (≤512, nullable; "Proposed adaptation to Chicken Stir Fry…") |
| Route status chip | `status` (§4d) — AWAITING_USER_APPROVAL links to the pending-changes section (recipe destination produces a §3 card) |
| Failure line | `failureMessage` (≤512, nullable; shown red on FAILED rows) |
| Not rendered deeply | `destinationResult` (untyped shell, shape per destination — raw expander only; §8 Q4) |
| Correction key | `id` (→ `{routingId}` in #8) |

### 4c. Correction flow — #8 + corrections log #9

"This isn't right" on a route row → picker listing the three *other* destinations
(same-destination correction is a server 422 no-op) + optional note:

| Control | Request field | Constraints |
|---|---|---|
| Destination picker* | `newDestination` | RECIPE / PREFERENCE / NUTRITION / PROVISIONS, ≠ original; correcting **to** RECIPE needs a recipe attached to the entry (422 otherwise — grey the option with tooltip when `context.recipeId` is null) |
| "What did you mean?" note | `userCorrectionNote` | ≤512, optional |

Response is a fresh `SubmitFeedbackResponse` — the replay runs synchronously:
original row flips CORRECTED_AWAY, a new route row appears for the corrected
destination, entry status becomes CORRECTED. Refresh the entry via #7. One
correction per route — corrections are not chained (422); the only path after is
new feedback. Undo of the original write is **best-effort** (HLD correction
limitations) — show "previous action kept; routing corrected" when the original
was already applied.

**Corrections log** (expander, #9) — rows from `MisclassificationCorrectionDto`:
`originalDestination` → `correctedDestination` (arrow chips) · `originalConfidence`
("was 0.72 confident") · `userCorrectionNote` (italic) · `replayStatus` chip —
PENDING_REPLAY (spinner) / APPLIED ✓ / FAILED / DESTINATION_REJECTED ("the new
destination couldn't use it") · `occurredAt`. `feedbackEntryId` links back to the
entry card; `originalRoutingId`/`replayRoutingId`/`actorUserId`/`id`/`createdAt`
not displayed (§8 Q5 — no feedback text on the row).

### 4d. State machines

**Submission status** (`submissionStatus`):

```
RECEIVED → CLASSIFYING → CLASSIFIED → ROUTED            (all routes ok)
                                    → PARTIALLY_FAILED   (some routes FAILED)
                                    → FAILED              (all failed / classifier gave up)
         CLASSIFYING → CLARIFICATION_PENDING ──answer──→ RECEIVED (re-classification loop)
ROUTED / PARTIALLY_FAILED ──route corrected──→ CORRECTED
```

Chips: RECEIVED/CLASSIFYING "working…" (spinner) · ROUTED ✓ olive ·
CLARIFICATION_PENDING "needs you" terra · PARTIALLY_FAILED amber "partly applied" ·
FAILED red · CORRECTED ✎ "correction recorded". CLASSIFIED is transient
(internal hand-off) — render as "working…".

**Route status** (`status`): PENDING → APPLIED ✓ / AWAITING_USER_APPROVAL ⧖ /
FAILED ✕; APPLIED|AWAITING → CORRECTED_AWAY (struck-through, "re-routed") ·
REPLAYED (re-fired by the system). FAILED+TRANSIENT rows may self-heal (5-min
backend sweep) — keep polling failed entries for a while.

## 5. Clarifications inbox — anatomy & field mapping

### 5a. Cards — reads `ClarificationQueryDto` (#10 rows / #11)

Default filter `status=PENDING`; header count from page `totalElements`. Filter
chips PENDING / ANSWERED / EXPIRED re-fire #10.

| Display element | Source field |
|---|---|
| Question (serif, advisor voice) | `questionText` (≤512) |
| Option buttons | `options[]` — per option: `destination` (label) + `snippet` (the text fragment it would cover) + `classifierJustification` (nullable tooltip "why I think this") |
| Context quote "from: …" | via `feedbackEntryId` → #7 (`text`) — the DTO carries no excerpt (§8 Q5) |
| Expiry countdown | `expiresAt` ("expires in 2 days"; amber < 24 h) |
| When asked | `createdAt` |
| Status chip (non-pending filters) | `status` — ANSWERED ✓ / EXPIRED muted |

### 5b. Answer — #12, `AnswerClarificationRequest`

| Control | Request field | Constraints |
|---|---|---|
| Tap an option button | `selectedDestination` | RECIPE / PREFERENCE / NUTRITION / PROVISIONS (nullable) |
| "…or tell me more" free-text | `userClarificationText` | ≤4000 (nullable) |

**At least one of the two must be present** — 400 otherwise (disable submit until
one is set; tapping an option submits immediately, free-text has its own send).
200 returns a `SubmitFeedbackResponse` *receipt* — `submissionStatus = RECEIVED`,
`routes = []`: re-classification is queued, not done. Card resolves; the parent
entry (#7) re-enters the §4a polling loop. If the re-classification dips < 0.5
again, a fresh query appears (no hard round cap; §4a attempts nudge).

**410 Gone** (expired): card flips to expired state with CTA "This conversation
expired — re-submit your feedback" → opens the global feedback modal pre-filled
with the original `text` (via #7). **422** (already answered): refresh the card.

## 6. (state machines folded into §3e/§4d; the inbox's machine is PENDING → ANSWERED | EXPIRED)

## 7. Not on this page

| Contract item | Home |
|---|---|
| `POST /api/v1/feedback` (`SubmitFeedbackRequest`: `text` 1–4000 + `context` `UiContextDto`) | **Global feedback modal** (app shell, every page — ia.md "feedback-from-anywhere"). The modal owns the request contract incl. `@ValidUiContext` (RECIPE_DETAIL needs `recipeId`; PLAN_MEAL_DETAIL needs `planId`+`mealSlotId`) and receives 202 + `Location`; this page is where the *result* lands (#6/#7) |
| `GET /adaptation/jobs/{id}`, `…/jobs/{id}/trace`, `…/recipes/{id}/jobs`, `…/recipes/{id}/traces`, `/adaptation/admin/*`, `/adaptation/run-history*` | Admin / quality dashboard (ROLE_ADMIN), not user UI |
| Accepted-version browsing (`acceptedVersionId` target), version history, diffs between recipe versions | Recipe-detail page |
| Plan re-optimisation suggestions (accept/reject of *plan* changes) | Plan page (`plan.md` §3e) — planner surface, not adaptation pending changes |
| Feedback-confirmation toasts / "advisor learned X" notices | Notifications page |
| Preference deltas produced by routed feedback | Preferences page (taste-profile audit log) |

## 8. Status-code → UI map

| Code | Where | UI behaviour |
|---|---|---|
| 409 | #3 accept | Two flavours by problem `type`: `…/optimistic-lock` → "changed in another tab" conflict card → re-fetch #2 + re-confirm; `…/pending-change-superseded` → "a newer suggestion replaced this" → refresh #1 |
| 422 | #3/#4 | `…/pending-change-not-pending` ("already decided elsewhere") / `…/pending-change-expired` → flip card to its decided/expired state |
| 400 | #3 | invalid `userEdits` (stale base / unknown ingredient key) → inline error in the modify expander |
| 404 | #2/#3/#4, #7, #11, #12 | row vanished (or another user's) → remove card + toast |
| 410 | #12 | expired-clarification state + re-submit CTA (§5b) |
| 422 | #8 | same-destination no-op / already corrected / structural mismatch (e.g. RECIPE without a recipe) → message from ProblemDetail, picker stays open |
| 422 | #12 | already answered → refresh card |
| 202 | global modal POST | receipt only — poll the `Location` (#7) |
| 401 | any | global re-auth flow |

**Open questions (flagged, not resolved here):**
1. **Known list-DTO gaps (design around, ticket both):**
   `PendingChangeListItemDto` carries **no `optimisticVersion` and no `status`** —
   (a) accept can never be one-tap from a list row (the page always expands via #2
   first; acceptable since the diff lives there anyway); (b) pending-history rows
   (#5) cannot show outcomes (§3d). Backend ticket candidate: add
   `status`/`resolvedAt` (+ optionally `optimisticVersion`) to the list projection.
2. The top-3 cap means a 4th+ PENDING change is invisible until rank or expiry
   surfaces it — per the HLD budget, accepted; but there is no user-facing "all my
   pending changes" endpoint at all (history is per-recipe only). Flag if users
   report "lost" suggestions.
3. `proposedDiff` / `userEdits` are contractually opaque JSON — the red/green diff
   renderer is convention-coupled to the pipeline's shape. Backend ticket
   candidate: publish the diff JSON schema.
4. `destinationResult` is an untyped shell; v1 renders `actionTaken` only.
5. Neither `ClarificationQueryDto` nor `MisclassificationCorrectionDto` carries
   the original feedback text — the inbox needs one #7 call per visible card for
   the "from: …" quote (bounded by page size, but still N+1). Backend ticket
   candidate: add a `textExcerpt` to both DTOs.

## 9. Mock deltas (to make the mock match this spec)

1. Pending changes: replace the `recipe.pendingChange` + planner-suggestion hybrid
   with real `PendingChangeListItemDto` cards (dimension chip, reasoning preview,
   confidence + impact, expiry countdown) and a detail expand that fetches #2
   before any accept; wire `expectedOptimisticVersion`, the "modify before
   accepting" `userEdits` expander, and reject's `reasonNote`.
2. Move the plan re-opt suggestion card out of the top-3 (it belongs to the plan
   page); keep at most a cross-link.
3. Add the per-recipe pending-history drawer (#5) with status-less rows (§3d).
4. Feedback cards: derive tier marks from `decision` (the mock recomputes
   `tierFor(conf)` client-side), add submission-status chips + polling for
   RECEIVED/CLASSIFYING, route-status chips (incl. AWAITING_USER_APPROVAL linking
   to §3, FAILED with `failureMessage`), and `extractedFeedback` fragments.
5. Correction: replace the one-shot `markFeedbackCorrected` flag with the per-route
   destination picker → #8, CORRECTED_AWAY strike-through + replay row, and the
   corrections-log expander (#9).
6. Clarifications: options from `ClarificationOptionDto` (destination + snippet +
   justification tooltip) plus the free-text answer path (the mock has plain
   string buttons only); add `expiresAt` countdowns, the 410 expired/re-submit
   state, and the answered receipt → poll loop.
7. Add pagination ("earlier feedback"), the PENDING/ANSWERED/EXPIRED inbox
   filter, and per-section empty states.
