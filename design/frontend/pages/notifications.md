# Page spec — Notifications (`/notifications`)

The contract-complete specification: every endpoint this page consumes, and the UI
that each request field and response field demands. Companion docs:
[../ia.md](../ia.md), [../design-language.md](../design-language.md). Template:
[nutrition.md](nutrition.md) (the pilot).

This page is also the **backing store for a global shell element**: the
notification bell (badge + digest dropdown) lives on every page and consumes the
same endpoints (§4). The page and the bell share one client cache.

---

## 1. Intent (HLD)

- **The user is always in the loop** (`design/meal-planner.md` §triggers): events
  fire notifications, never automatic changes. Every row is therefore a *pointer*
  (deep link via `actionTargetUri`) to the page that owns the decision — this page
  itself applies nothing.
- **Minimal preferences v1 — GAP-86 ruling** (`e2e/pathways/hld-gaps.md`):
  "per-category mute + a single quiet-hours window (default 22:00–07:00);
  per-channel granularity deferred." A 2am defrost reminder is a real harm — quiet
  hours are a safety feature, not a nicety.
- **Debounced and bundled, not spammy** (`design/technical-architecture.md`
  §Event debouncing + `lld/notification.md` F9): bursts within the user's
  `debounceWindowMinutes` (default 30) are absorbed into one row with
  `bundleCount > 1`. The UI renders the bundle count; it never sees the absorbed
  siblings as rows.
- **Quiet hours defer, never drop** (`lld/notification.md`): a notification
  arriving inside quiet hours is persisted with a `DEFERRED` delivery-log row; the
  row still appears in the inbox. The delivery log (§3d) is the transparency
  surface for "why didn't I get pinged".
- **In-app channel only in v1** — `PUSH`/`EMAIL` exist in the `DeliveryChannel`
  enum but no v1 producer; SSE live push is a v1.5 backlog item. The bell badge is
  poll-driven.
- **`PLANNER_PLAN_GENERATED` is default-OFF** (`lld/notification.md` §Kinds): it
  doubles up with the plan page's natural "your plan is ready" state; the
  preferences panel must render it as an opt-in toggle that starts off.

## 2. Endpoint inventory

One module, 9 paths / 10 operations (4 reads, 6 writes):

| # | Endpoint | Surface | When called |
|---|----------|---------|-------------|
| 1 | `GET /api/v1/notifications?status&kind&since&page&size` | Main list | On load + filter/page change + after every mutation |
| 2 | `GET /api/v1/notifications/summary` | Badge counts (bell + header chips) | On load + bell-poll cadence (shared with shell, §4) |
| 3 | `GET /api/v1/notifications/{id}` | Detail expansion (when deep-linked by id) | On `/notifications/{id}` deep link only — list rows already carry the full DTO |
| 4 | `POST /api/v1/notifications/{id}/read` | Row | Row click / explicit "mark read" |
| 5 | `POST /api/v1/notifications/{id}/dismiss` | Row | Dismiss (✕) action |
| 6 | `POST /api/v1/notifications/{id}/action` | Row | Following the deep link (`actionTargetUri`) |
| 7 | `POST /api/v1/notifications/bulk/read` | Toolbar | "Mark all read" (optionally scoped to kinds) |
| 8 | `GET /api/v1/notifications/{id}/delivery-log?page&size` | Delivery drawer | On drawer open (lazy) |
| 9 | `GET /api/v1/notifications/preferences` | Preferences panel | On panel open (auto-seeds defaults server-side) |
| 10 | `PUT /api/v1/notifications/preferences` | Preferences panel | Save |

All cookie-auth. No support joins: rows are self-contained (`title`/`body` are
denormalised server-side precisely so the inbox never re-renders copy from
payload — `lld/notification.md` §Database).

## 3. Anatomy & field mapping

### 3a. Filter bar — request params of #1 (exact)

| Control | Param | Constraints |
|---|---|---|
| Status tabs (All · Unread · Read · Dismissed · Actioned) | `status` | optional, **single** `NotificationStatus`; "All" omits it |
| Kind dropdown | `kind` | optional, **single** `NotificationKind`; no multi-select exists (§7 Q3) |
| "Since" — not a v1 control | `since` | `date-time`; reserved for the bell's incremental poll, not a page filter |
| Pager | `page` | int ≥ 0, default 0 |
| Page size | `size` | int 1–100, default 20 |

Response is a Spring `NotificationDtoPage` (`content`, `totalElements`,
`totalPages`, `number`, `size`, `first`/`last`/`empty`) — standard pager wiring.
Header chips render `NotificationSummaryDto`: `unreadCount` (bell badge),
`attentionCount`, `urgentCount` (red); `generatedAt` is cache metadata, not shown.

### 3b. Row anatomy — `NotificationDto`

| Display element | Source field |
|---|---|
| Kind icon + accent | `kind` → §3c mapping table |
| Severity treatment | `severity` — INFO ink/muted · ATTENTION amber · URGENT red (D6: red = danger only; URGENT is the *only* red in this page) |
| Title / body | `title` (≤200) / `body` (≤1000) — verbatim, server-rendered copy |
| Bundle pill | `bundleCount > 1` → "×4 bundled"; `bundleKeys` not displayed (debug) |
| Timestamp | `createdAt` (relative, "2h ago") |
| Unread treatment | `status = UNREAD` → bold + dot; READ plain; DISMISSED collapsed/greyed; ACTIONED olive ✓ |
| Primary action | `actionTargetUri` non-null → "View" deep link, fires #6 then navigates; null → row expands in place |
| Dismiss (ghost ✕) | fires #5 |
| Delivery-log affordance | small "delivery" caption opens §3d drawer |
| Not displayed | `id`/`userId`/`householdId` (plumbing), `payload` (kind-specific record — server already rendered it into title/body; §7 Q4), `traceId` (admin: /admin decision-log lookup), `readAt`/`actionedAt`/`dismissedAt` (tooltip-only), `version` (no client use — transitions are verb POSTs without a version precondition) |

**Status state machine** (server-enforced; 409 on anything else):

```
UNREAD ──read──▶ READ ──action──▶ ACTIONED ──dismiss──▶ DISMISSED (terminal)
   │                │                                        ▲
   ├──action──────▶ ACTIONED                                 │
   └──dismiss────────┴──────────dismiss──────────────────────┘
```

- UNREAD → READ | ACTIONED | DISMISSED
- READ → ACTIONED | DISMISSED
- ACTIONED → DISMISSED
- DISMISSED → nothing (terminal)

Button semantics: a row click marks read (#4) *and* expands; following the deep
link marks actioned (#6 — fire-and-forget before navigation; a 409 because the row
was already ACTIONED is swallowed). Dismiss is always available except on
DISMISSED rows. There is no "un-dismiss" / "mark unread" — don't render one.

### 3c. Kind → icon / colour mapping (D6 semantic colours)

The contract enum (`schemas/notification.yaml#NotificationKind`, 8 values) plus
the two backend-only values (§7 Q1). Colour = D6 token; per principle 2, red only
where the thing itself is a harm.

| Kind | Icon (suggested) | D6 colour | Default deep link (server `actionTargetUri`) |
|---|---|---|---|
| `PROVISION_ITEM_NEAR_EXPIRY` | ⏳ | `amber` (time-sensitive) | `/app/provisions/inventory` |
| `PROVISION_ITEM_SPOILED` | ⚠ | `red` (spoiled = danger semantics) | `/app/provisions/inventory` |
| `PROVISION_DEFROST_REMINDER` | ❄ | `amber` | `/app/provisions/inventory` |
| `NUTRITION_INTAKE_DIVERGED` | ◔ | `amber` (severity INFO when divergence < threshold → muted) | `/app/nutrition/intake/{date}` |
| `HEALTH_DIRECTIVE_RECEIVED` | ✚ | `red` accent via URGENT severity | `/app/nutrition/health-directives/{id}` |
| `PLANNER_PREP_REMINDER` | 🔪 | `amber` | `/app/planner/slots/{slotId}` |
| `PLANNER_REOPT_SUGGESTED` | ✎ | `terra` (the system suggests / you act) | `/app/plans/{planId}` |
| `PLANNER_PLAN_GENERATED` | ▦ | `olive` (done/confirmed) | `/app/plans/{planId}` |
| `STAPLE_REPLENISHMENT_NEEDED` *(enum-gap, §7 Q1)* | ▤ | `terra` | `/app/provisions/inventory` |
| `FEEDBACK_CONFIRMATION` *(enum-gap, §7 Q1)* | ✓ | `olive` | `/app/feedback/{feedbackId}` |

**The server's `actionTargetUri` values are `/app/...` paths that do not match the
IA's routes** (`/pantry`, `/nutrition`, `/plan`, `/activity`). The client needs a
URI→route mapping layer, or the backend copy needs updating — §7 Q2.

### 3d. Delivery-log drawer — `DeliveryLogEntryDto` page (#8)

Per-notification transparency: one row per delivery attempt, newest-first.

| Display element | Source field |
|---|---|
| Channel chip | `channel` — IN_APP (only v1 producer) · PUSH · EMAIL |
| Outcome mark | `outcome` — DELIVERED ✓ olive · SKIPPED — muted · DEFERRED ⏲ amber · FAILED ✕ red |
| Skip reason caption | `skipReason` (nullable) — `DISABLED_BY_PREF` "muted in preferences" · `QUIET_HOURS` "held for quiet hours" · `DEDUPED_INTO_BUNDLE` "bundled into an earlier alert" · `CHANNEL_UNAVAILABLE` |
| Timestamp | `attemptedAt` |
| Not displayed | `id`, `notificationId` (plumbing) |

### 3e. Preferences panel — #9 / #10 (exact shape)

GET auto-seeds a defaults row on first open (idempotent), so the panel never has
an empty state. The PUT is a **full replace**:

| Control | Field | Constraints / notes |
|---|---|---|
| Per-kind mute toggles | `enabledKinds` | **map of `NotificationKind` → boolean** — yes, per-kind mute is the v1 granularity (GAP-86 "per-category"). Render one toggle per §3c kind; `PLANNER_PLAN_GENERATED` seeds OFF, everything else ON |
| Quiet-hours master switch | `quietHoursEnabled` | boolean |
| Quiet-hours window | `quietHoursStart` / `quietHoursEnd` | `time` (HH:mm), nullable; **may wrap midnight** (22:00–07:00 is the seeded default); class-level `@ValidQuietHours` rejects enabled-with-null-times → 400 |
| Timezone | `timezone` | required, ≤64, IANA id (validated server-side); seed default `Europe/London` |
| Debounce window | `debounceWindowMinutes` | int 0–360, seed 30 — advanced/collapsed control ("bundle repeats within N minutes") |
| (hidden) | `expectedVersion` | echo `NotificationPreferenceDto.version`; stale → 409 re-fetch + "preferences changed elsewhere" |

Contract nit: the OpenAPI marks only `enabledKinds` + `timezone` as required, but
the Java record binds `quietHoursEnabled`/`debounceWindowMinutes`/`expectedVersion`
as primitives — omitting them silently defaults false/0/0, and an omitted
`expectedVersion` will 409 against any edited row. Always send the full document
(§7 Q5). Response `NotificationPreferenceDto` adds `id`/`userId` (plumbing, not
shown).

### 3f. Toolbar — bulk read (#7)

`BulkReadRequest { kinds: NotificationKind[] }` — empty/absent list = all kinds.
Wire "Mark all read" to the **current kind filter**: filter active → `kinds:
[thatKind]`, else `kinds: []`. Response `{ updated: n }` → toast "n marked read"
+ re-fetch #1/#2. Note the asymmetry: bulk read ignores the status filter (it only
ever targets UNREAD rows server-side).

## 4. Shared surface — the bell dropdown (app shell)

The bell is **not** a separate API surface; it is this page's endpoints on a poll:

- Badge: #2 `unreadCount` (red-dot variant when `urgentCount > 0`), polled at the
  shell cadence (v1 polls; SSE deferred).
- Dropdown rows: #1 with `status=UNREAD&size=5` (Today's needs-attention card uses
  `size=3` — same cache key family).
- Row click in the dropdown: #4 (+#6 when deep-linked), then navigate; "View all"
  → this page.
- The dropdown must not offer dismiss/preferences — those live here.

One TanStack Query cache for #1/#2 keyed on filters; every mutation invalidates
both, which keeps page, dropdown and Today's digest consistent for free.

## 5. Status-code → UI map

| Code | Where | UI behaviour |
|---|---|---|
| 401 | all | global session-expired redirect |
| 404 | #3, #4–#6, #8 | "notification no longer exists" toast + remove row + re-fetch #1 (another device dismissed it) |
| 409 illegal transition | #4/#5/#6 | silent re-fetch of the row (#3) — the row was already past that state; only toast if the refreshed state still allows nothing |
| 409 optimistic lock | #10 | re-fetch #9, re-apply form, "preferences changed elsewhere — review and save again" |
| 400 | #10 | inline field errors (quiet-hours window incomplete, bad timezone, debounce out of 0–360) |
| 400 | #1 params | unreachable via UI controls (clamped); treat as dev error |

Empty states: #1 empty page → "You're all caught up" illustration; filtered-empty
→ "no {kind} notifications". No 404 exists on list/summary/preferences (seeded).

## 6. (state machine specified in §3b)

## 7. Not on this page

| Capability | Home |
|---|---|
| Acting on what a notification points at (accept re-opt, review directive, mark spoiled…) | the `actionTargetUri` target page — this page only marks `ACTIONED` and navigates |
| Needs-attention digest (top-3 unread) | / (Today §3d) — read-only mirror |
| Re-opt suggestion diff/accept | /plan |
| Health-directive accept/reject/modify | /nutrition |
| Expiry/defrost source data | /pantry |
| Feedback routing history | /activity |
| Admin trace lookup from `traceId` | /admin decision-log |
| Quiet-hours *enforcement*, debouncing, scanner scheduling | server-side only — the page renders outcomes (§3d), never re-implements the rules |

## 8. Open questions (flagged, not resolved here)

1. **Kind enum contract gap.** The Java `NotificationKind` has 10 values; the
   OpenAPI enum lists 8 — `STAPLE_REPLENISHMENT_NEEDED` and
   `FEEDBACK_CONFIRMATION` are missing from `schemas/notification.yaml`. Codegen
   types will fail to parse real rows of those kinds (and the preferences map will
   carry unknown keys). **Backend gap: add both to the contract enum.**
2. **`actionTargetUri` namespace mismatch.** Server emits `/app/provisions/inventory`,
   `/app/plans/{id}`, `/app/feedback/{id}`, etc.; the IA routes are `/pantry`,
   `/plan`, `/activity`. Either the resolver copy is updated to IA routes
   (backend gap candidate) or the client ships a static `/app/*`→route map.
   **Resolved (2026-06-13, frontend-gaps P3):** server-side — the
   `NotificationKindResolver` now emits IA routes directly (`/pantry`,
   `/nutrition`, `/plan`, `/activity`); entity context rides the typed
   `payload`. No client map needed. Pre-change rows keep legacy `/app/*` URIs
   (client falls back to opening /notifications for unknown URIs).
3. **Single-valued filters.** `status` and `kind` accept one value each; the
   "All except dismissed" inbox default the mocks show needs either client-side
   filtering of a status-less query or a multi-status param (backend gap
   candidate, low).
   **Resolved (2026-06-13, frontend-gaps P3):** client-side filtering accepted
   for v1 (page sizes are small); a multi-status param only if real usage
   hurts.
4. **`payload` is unused by this page.** All copy is denormalised into
   title/body. If richer rows are ever wanted (e.g. divergence % chip from
   `NutritionDivergedPayload`), the polymorphic payload is already on the wire —
   a UI choice, not a contract change.
5. **Preferences PUT required-fields mismatch.** OpenAPI required:
   `[enabledKinds, timezone]`; Java binds 3 more as primitives, and
   `expectedVersion` is effectively mandatory. Align the contract (backend gap
   candidate, doc-level).
   **Resolved (2026-06-13, frontend-gaps P3):** contract aligned —
   `schemas/notification.yaml#UpdateNotificationPreferenceRequest` now requires
   `enabledKinds, quietHoursEnabled, timezone, debounceWindowMinutes,
   expectedVersion` (full-replace document; only the quiet-hours times stay
   nullable).
6. **Bell poll cadence** is unspecified anywhere. Suggest 60s + on-focus refetch;
   needs a product nod (battery/load trade-off until SSE lands).
   **Resolved (2026-06-13, frontend-gaps P3):** product nod recorded — 60 s
   interval + on-focus refetch is the v1 cadence until SSE lands (backlog task
   #172).

## 9. Mock deltas (to make the mock match this spec)

1. Retype the notifications slice on `NotificationDto` (drop any bespoke shape):
   add `bundleCount` pill, `severity` treatment, kind icon map (§3c), relative
   `createdAt`.
2. Implement the §3b state machine in the mock store — including 409-illegal
   transitions as no-ops — and the read-on-click + actioned-on-deep-link pairing.
3. Wire the filter bar to real list params (single status/kind, pager) instead of
   client-side array filters; render pager from page metadata.
4. Add the delivery-log drawer (lazy fetch, outcome marks per §3d) — not present
   in the mock at all.
5. Preferences panel: per-kind toggle list seeded from `enabledKinds` (PLAN_GENERATED
   off), quiet-hours window with midnight wrap, timezone select, debounce slider;
   save sends the full replace document with `expectedVersion`.
6. Bulk-read button scoped to the active kind filter; show `{updated}` toast.
7. Share the store between the shell bell dropdown and this page (one source of
   truth) and drive the badge from `summary`, not a client count.
