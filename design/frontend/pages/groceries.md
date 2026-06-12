# Page spec — Groceries (`/groceries`)

The contract-complete specification: every endpoint this page consumes, and the UI
that each request field and response field demands. A control exists for every
writable field; a display home exists for every returned field (or an explicit
"not on this page" entry). Companion docs: [../ia.md](../ia.md),
[../design-language.md](../design-language.md). Template: [nutrition.md](nutrition.md)
(the pilot). Siblings: [plan.md](plan.md), [pantry.md](pantry.md).

Groceries is the grocery module's whole user surface — all four HLD tiers land
here: the shopping list (Tier 1), mark-bought manual fulfilment (Tier 2), the
provider order lifecycle (Tier 3, opt-in), and price history (Tier 4). Provider
*connection management* lives on /settings (§7).

---

## 1. Intent (HLD)

From `design/grocery.md` + `lld/grocery.md` (user-facing semantics only):

- **Four cooperating tiers, each independently useful.** "A user with no provider
  configured gets full value from tiers 1, 2, 4." The page must never gate the
  list or mark-bought behind a provider — Tier 3 is a side panel, not the spine.
- **The list is derived state** — "regenerated when the underlying plan or
  provisions change… never edited as the source of truth." There is no
  add/edit/remove-line UI; edits flow through plan/pantry and the list re-derives.
  History is kept "so the user can see 'what was the list last week?' — useful for
  retroactively marking bought."
- **Mark-bought is the default path**: "Tap to mark bought at the suggested pack
  size" (one-tap), "adjust quantity," "enter actual paid price (optional but
  encouraged — this is what feeds Tier 4)," "set the store (optional — enables
  cross-store comparison)." Plus bulk: "'mark all bought' + 'set total spend'…
  per-item prices estimated by distributing the total proportionally."
- **Providers never auto-confirm purchases.** `placeOrder` "drives a basket up to
  checkout and stops. The user confirms in the provider's UI." The page renders
  the checkout link and an explicit "I've confirmed" action — never a pay button.
- **Substitutions are proposals, never auto-accepted** — "substitutions can change
  a meal materially." Accept → substitute enters the pantry; reject → "logged as
  wasted-on-arrival; planner notified original is unmet." All proposals must be
  resolved before the order reconciles.
- **Cost is always shown with confidence and freshness** — `(estimated_total,
  confidence)` plus "stale data: 12 of 47 ingredients last priced >3 months ago"
  and a "Refresh prices?" affordance. Cold start (first 4–8 weeks): "the UI
  nudges price entry."
- **Graceful degrade is a contract**: "the user can always complete the order
  manually. Browser automation is convenience; never a hard dependency." Every
  provider failure surfaces with the printable-list / manual-entry fallback.
- **Quotes run without intent to place** — "refresh prices" on a draft is cheap,
  explicit, user-initiated, and feeds the price cache.

## 2. Endpoint inventory

The grocery module exposes 27 endpoints; 26 are consumed by this page across
three zones (**List · Orders · Prices**). 1 is not for this page (§7).

| # | Endpoint | Zone | When called |
|---|----------|------|-------------|
| 1 | `GET /api/v1/grocery/shopping-lists/current?planId` | List | On load (active plan's id) + after every mark/undo/recalculate + after order reconcile |
| 2 | `GET …/shopping-lists/{shoppingListId}` | List/history | Opening a history row; resolving an order's source list |
| 3 | `GET …/shopping-lists/history?page&size` | History drawer | Drawer open (newest first, page ≤ 100) |
| 4 | `POST …/shopping-lists/recalculate` | List | "Recalculate" button (`planGeneration` null → latest) |
| 5 | `GET …/shopping-lists/{id}/export?format` | Export menu | Per-format action (default PRINTABLE_HTML) |
| 6 | `POST …/{listId}/lines/{lineId}/mark-bought` | List | Line checkbox (one-tap) / "more info" popover save |
| 7 | `POST …/{listId}/bulk-mark-bought` | List | Select-mode "Mark all bought" confirm |
| 8 | `POST …/{listId}/lines/{lineId}/undo-mark-bought` | List | "Undo" on a Tier-2-bought line |
| 9 | `GET /api/v1/grocery/orders?page&size` | Orders | On load + after every order action |
| 10 | `POST /api/v1/grocery/orders` | Orders | "Order via {provider}" CTA (creates DRAFT) |
| 11 | `GET …/orders/{orderId}` | Orders | Order expand / deep link (every action also returns the order) |
| 12 | `POST …/orders/{orderId}/quote` | Orders | "Get quote" (DRAFT → QUOTED) |
| 13 | `POST …/orders/{orderId}/place` | Orders | "Place order" (QUOTED → PLACED/PLACED_PARTIAL) |
| 14 | `POST …/orders/{orderId}/mark-user-confirmed` | Orders | "I've confirmed on {provider}" |
| 15 | `POST …/orders/{orderId}/refresh-status` | Orders | "Refresh status" (pulls provider status) |
| 16 | `POST …/orders/{orderId}/mark-delivered` | Orders | "It arrived" (CONFIRMED → DELIVERED) |
| 17 | `POST …/orders/{orderId}/cancel` | Orders | "Cancel order" + reason popover |
| 18 | `GET …/orders/{orderId}/substitutions` | Orders | DELIVERED order expand + after each resolve |
| 19 | `POST …/orders/{orderId}/substitutions/{proposalId}/resolve` | Orders | Accept / Reject on a proposal card |
| 20 | `GET …/orders/providers/{providerKey}` | Orders | On load — gates the whole Tier-3 panel |
| 21 | `GET /api/v1/grocery/price-history/aggregates?ingredientKey&store` | Prices | Line price-popover open |
| 22 | `GET …/price-history/aggregates/cross-store?ingredientKey` | Prices | Popover "compare stores" tab |
| 23 | `GET …/price-history/observations?page&size` | Prices | "Price activity" drawer (audit) |
| 24 | `GET …/price-history/observations/by-key?ingredientKey&page&size` | Prices | Popover "history" tab |
| 25 | `POST …/price-history/observations/manual` | Prices | "Record a price" form save |
| 26 | `POST …/price-history/refresh` | Prices | "Refresh prices" header action |
| s1 | `GET /api/v1/plans/active?householdId&weekStartDate` | — | Resolve the current `planId` (shared shell/plan cache, not re-fetched here) |

`userId` is server-resolved from the session on every call; no request carries it.

## 3. Shopping list (Tier 1) — anatomy & field mapping

### 3a. Header & stat strip — reads `ShoppingListDto` (#1)

| Display element | Source field |
|---|---|
| Context line | `planId` → plan join (s1: week range) + `planGeneration` ("for plan week 8–14 June · generation 3") |
| Generated/superseded | `generatedAt` ("calculated Sunday 18:02"); `supersededAt` non-null → history-row badge "superseded" (never shown on the current list — #1 returns non-superseded only) |
| **Projected total cell** | `estimatedTotalPence` ÷ 100 + `estimatedTotalCurrency` ("£52.40"); null → "no price data yet" (cold start, not an error) |
| Confidence sub-line | `costConfidence` (0–1 → "83% confidence"); < 0.5 renders amber; null → omitted |
| **Stale prices cell** | `staleIngredientCount` ("4", warn-styled when > 0; sub-line "last priced > 3 months ago") → scrolls to STALE-tagged lines; cell also hosts the **Refresh prices** action (#26, §6c) |
| Items-bought cell | derived: count of `lines[].fulfilmentStatus ∈ {BOUGHT, SUBSTITUTED}` ÷ `lines.length` |
| Pantry-tracking caption | `pantryTrackingEnabled=false` → "pantry stock not subtracted — tracking is off" + /settings link |
| List notes | `notes` (muted line under the header) |
| Header actions | **Recalculate** (#4) · **Export** menu (#5) · **History** drawer (#3) · select-mode toggle (§4b) |
| Cold-start nudge | `costConfidence` null or < 0.3 → advisor caption "Enter prices as you shop — estimates improve after a few weeks" (HLD cold-start rule) |

Not displayed: `id` (request plumbing), `userId`/`householdId` (session), `version`
(optimistic-lock plumbing — mark-bought collisions surface as 409, §8).

The mock's "budget headroom" cell is **not** derivable from this module —
`BudgetDto.spendTracking` is null in v1 (see pantry.md §6); the cell drops or
renders the /pantry budget target as a link-out (mock delta §9.1).

### 3b. Line rows — reads `lines[]` (`ShoppingListLineDto`)

Two sections by `lineType`: **Planned demand** (PLANNED_DEMAND) and **Staples to
replenish** (STAPLE_REPLENISHMENT, caption "added because it ran low — not from a
recipe"). The mock's category groups (Produce / Protein / …) have no contract
source — lines carry no category field (mock delta §9.2).

| Display element | Source field |
|---|---|
| Checkbox | `fulfilmentStatus` — UNFILLED ○ · BOUGHT ✓ (struck name) · SUBSTITUTED ⇄ chip "substituted" · DROPPED — muted strikethrough "dropped" · PARTIAL ◐ (reserved — no v1 write path sets it, render as half-filled) |
| Name | `displayName` |
| Quantity | `requestedQuantity` + `requestedUnit` ("600 g") |
| Pack suggestion | `suggestedPackCount` × `suggestedPackSizeG` + `suggestedPackUnit` ("1 × 1 kg pack"); all-null → omitted |
| Quality chip | `qualityNotes` ("free-range", terra tint) |
| Price | `estimatedLinePence` ÷ 100 ("£3.20"); `estimatedUnitPence` in the popover ("32p / 100 g"); null → "—" |
| Confidence dot | `estimatedConfidence` (< 0.5 → amber dot, tooltip "%") |
| STALE tag | `isStaleEstimate=true` → amber "STALE" tag (estimate > 3 months old; the mock's "2 weeks" copy is wrong — delta §9.3) |
| Bought line (decided rows) | `boughtQuantity` + `boughtUnit` + `boughtPricePence` ÷ 100 + `boughtAt` ("✓ 1 kg · £3.45 · Mon 14:20") |
| Via badge | `boughtVia` — MANUAL "marked by you" · BULK_TOTAL "bulk" · ORDER "from order" + `groceryOrderId` → order deep link (§5) |
| Price popover trigger | `ingredientMappingKey` keys #21/#22/#24/#25 (§6a) |
| Row actions | per fulfilment state, §4 |

### 3c. Recalculate, history, export

- **Recalculate** (#4) — body `RecalculateShoppingListRequest{ planId*,
  planGeneration }`; the page always sends `planGeneration: null` (= latest).
  Confirm copy: "Re-derives the list from the plan and your pantry — bought marks
  on this generation are kept." 200 returns the (possibly identical) list.
  **Contract caveat:** recalculate is idempotent per `(planId, planGeneration)` —
  within one plan generation it returns the existing list, so it does *not* pick
  up pantry drift (§8 open question 2). 404 → "no such plan / no active
  generation" toast.
- **History drawer** (#3) — paginated `ShoppingListDtoPage`. Row: `generatedAt` ·
  `planGeneration` · `estimatedTotalPence` · bought-count · `supersededAt` badge.
  Row click → #2 read-only list view with mark-bought **still enabled**
  (HLD: "retroactively marking bought" is the point of history).
- **Export menu** (#5) — one entry per `ExportFormat`:

| Menu entry | `format` | Frontend behaviour with `ShoppingListExportDto.content` |
|---|---|---|
| Print / PDF | `PRINTABLE_HTML` (server default) | open print dialog (print-to-PDF is browser-side per the LLD) |
| Copy to clipboard | `PLAIN_TEXT` | clipboard write + toast |
| Markdown | `MARKDOWN` | download `.md` |
| CSV | `CSV` | download `.csv` |
| Email / share | `PLAIN_TEXT` | `mailto:` / OS share sheet (frontend concern per LLD) |

`ShoppingListExportDto.shoppingListId` / `format` echo the request (not displayed).

## 4. Mark-bought (Tier 2) — the price-observation capture

### 4a. Single line — `MarkBoughtRequest` (#6)

Two paths on every UNFILLED line:

**One-tap** (the checkbox): sends the suggested values, **no price** — a fast mark
with no price observation written. Pre-filling the *estimate* as if paid would
feed the estimate back into the learning loop, so the one-tap path deliberately
omits `boughtPricePence` (display rule; diverges from the HLD's "at last-known
price" phrasing — rationale: observations must be real encounters).

**"More info" popover** (the entry chip with units — every request field mapped):

| Control | Request field | Constraints |
|---|---|---|
| (path-bound) | `shoppingListLineId`* | body field exists but the path `lineId` is authoritative — the controller rebinds it; client sends the path id in both |
| Quantity input* | `boughtQuantity` | number > 0, scale ≤ 3, ≤ 1,000,000; prefilled `suggestedPackCount × suggestedPackSizeG` (fallback `requestedQuantity`) |
| Unit select* | `boughtUnit` | enum: `g · kg · ml · l · items · pt · tsp · tbsp · cup`; prefilled `suggestedPackUnit` (fallback `requestedUnit`) |
| Price input (£→pence) | `boughtPricePence` | optional, ≥ 0, ≤ 1,000,000 pence; placeholder shows `estimatedLinePence` as a hint, **never auto-submitted**; caption "feeds your price history" |
| Store input | `store` | optional ≤ 64; datalist of stores seen in recent observations (#23) + "manual" default server-side; caption "enables cross-store comparison" |
| When | `boughtAt` | optional date-time, default now (server) |

**Result** (`MarkBoughtResultDto`): `newStatus` → row flips; `priceObservationId`
non-null → toast "price recorded"; `inventoryItemId` non-null → toast "added to
your pantry" with /pantry link; `note` non-null → over-mark warning toast (HLD:
"warn it wasn't on the list" when bought > requested — buying more is allowed).

### 4b. Bulk — `BulkMarkBoughtRequest` (#7)

Select-mode toggle → checkboxes become multi-select + a sticky footer bar:

| Control | Request field | Constraints |
|---|---|---|
| (implicit) | `shoppingListId`* | the rendered list |
| Selected rows | `shoppingListLineIds`* | ≥ 1 uuid |
| Total spend input (£) | `totalSpendPence` | optional; when set, server distributes proportionally to estimated line costs (uniform share for unpriced lines) and writes per-line MANUAL_ESTIMATED observations (lower confidence — HLD's discount); caption explains this |
| Store input | `store` | optional ≤ 64 (one store for the batch) |
| When | `boughtAt` | optional, default now |

Returns `MarkBoughtResultDto[]` — same per-row handling as 4a; one toast
summarises ("12 marked bought · total £41.20 distributed").

### 4c. Undo (#8)

Shown only on rows with `fulfilmentStatus=BOUGHT` **and** `boughtVia ∈ {MANUAL,
BULK_TOTAL}` (order-fulfilled rows are not undoable here). No body; 204 → row back
to UNFILLED. Confirm copy must carry the contract's caveat: "Removes the mark and
writes a compensating price note — **the pantry item is not removed
automatically**; correct it in /pantry if needed" (§8 open question 4).
409 → "not currently bought" → silent re-fetch.

## 5. Orders & substitutions (Tier 3) — anatomy, state machine

### 5a. Panel gate — reads `GroceryProviderStateDto` (#20)

| State | Panel behaviour |
|---|---|
| 404 (no provider state) | Empty state: "No provider connected — connect Tesco in Settings" → /settings deep link. List/mark-bought unaffected (HLD: tiers 1/2/4 are full value) |
| `enabled=false` | "Provider paused" + /settings link |
| `enabled=true` | "Order via {providerKey}" CTA (#10) + order list (#9) |
| `sessionExpiresAt` past / `lastFailureReason` non-null | Amber banner: "{providerKey} session needs attention — {lastFailureReason}" + `consecutiveFailures` count + /settings link |
| `lastLoginAt`, `lastFailureAt` | tooltip detail on the banner |
| Not displayed | `id`, `userId` (plumbing); `scheduledRefreshEnabled`, `refreshTopNIngredients` (editor lives on /settings, §7) |

### 5b. Order card — reads `GroceryOrderDto` (#9/#11 and every action response)

| Display element | Source field |
|---|---|
| Title | `providerKey` + `providerOrderId` (when set) |
| Status chip + timeline | `status` (11 values) → §5c machine; timeline marks from `placedAt` / `confirmedAt` / `deliveredAt` / `reconciledAt` / `cancelledAt` |
| Status reason | `statusReason` (e.g. "delivery_slot_required" → human copy "pick a delivery slot in the {provider} basket") |
| Totals row | `quotedTotalPence` → "quoted £48.20" · `confirmedTotalPence` → "confirmed £47.90" · `paidTotalPence` → "paid £47.35" (each ÷ 100, `currency`) — show the most advanced non-null, earlier ones in the expand |
| Delivery slot | `deliverySlotStart`–`deliverySlotEnd` ("Tue 18:00–19:00") |
| Checkout link | `confirmLink` → "Open {provider} basket" external-link button (PLACED/PLACED_PARTIAL/AWAITING states) |
| Cancel metadata | `cancelReason` + `cancelledAt` (terminal read-only line) |
| Freshness | `lastStatusCheckAt` ("status checked 5 min ago") next to the Refresh-status button |
| Source list | `shoppingListId` → #2 join ("from the list of 8 June") |
| Substitution badge | `outstandingProposals[]` length > 0 → "n substitutions to review" amber chip → §5d |
| Lines table (expand) | `lines[]` per the next table |
| Not displayed | `id`, `userId`, `householdId`, `version` (plumbing; 409 handling §8) |

**Order lines** (`GroceryOrderLineDto`):

| Display element | Source field |
|---|---|
| Name + SKU | `displayName`; `providerProductId` in tooltip |
| Requested | `quantityRequested` + `quantityUnit`; `packCountRequested` × `packSizeG` sub-line |
| Delivered | `packCountDelivered` (differs from requested → amber) |
| Price per stage | `quotedUnitPence` / `confirmedUnitPence` / `paidUnitPence` (÷ 100; show the most advanced non-null) |
| Line status | `lineStatus` — QUEUED ○ · ADDED ✓ · ADDED_PARTIAL ◐ amber · UNAVAILABLE ✗ muted · SUBSTITUTED ⇄ · DELIVERED ✓ olive · REJECTED — struck |
| Note | `note` (muted) |
| List link | `shoppingListLineId` → highlights the source line in §3b |
| Key | `ingredientMappingKey` (price-popover trigger, §6a) |

### 5c. Order state machine — buttons per status

Legal edges per the backend `OrderStateMachine`; the UI offers exactly these.
**Cancel** (#17, ghost + reason popover `CancelOrderRequest{ groceryOrderId =
path id, reason ≤ 64 }`) is legal from every state until RECONCILED.

| Status | Buttons / behaviour |
|---|---|
| DRAFT | **Get quote** (primary → #12) · Cancel. Banner when reverted here with `statusReason` "AI cost cap reached": "AI paused — use the printable list and mark bought manually" (export CTA) |
| QUOTED | **Place order** (primary → #13) · Cancel. Shows quoted total + per-line quotes. (QUOTED → DRAFT "re-edit" is a legal machine edge with **no endpoint** — §8 open question 3) |
| PLACED | paused state — "Basket built; pick a delivery slot in the {provider} basket" (`statusReason=delivery_slot_required`) · **Open basket** (`confirmLink`) · **Refresh status** (#15 — the only advance path) · Cancel |
| PLACED_PARTIAL | amber "n of m items added — complete the basket manually" (from `lines[].lineStatus`) · **Open basket** · **Refresh status** · Cancel. Fail-forward: this is a 200 outcome, not an error |
| AWAITING_USER_CONFIRMATION | "Confirm the order in {provider} — we never confirm for you" · **Open basket** · **I've confirmed** (primary → #14) · Cancel |
| CONFIRMED | delivery slot countdown · **Refresh status** (#15) · **It arrived** (→ #16) · Cancel. (An hourly server job also advances to DELIVERED) |
| DELIVERED | substitution review gate (§5d): "resolve n substitutions to finish" · **Refresh status** · Cancel. Reconciliation runs automatically when the last proposal resolves — no button |
| RECONCILED | terminal read-only: paid total, "pantry updated, prices recorded" caption |
| CANCELLED | terminal read-only: `cancelReason` |
| PROVIDER_UNAVAILABLE | red banner "{provider} unreachable — retrying hourly for 24 h, then auto-cancel" · **Try quote again** (#12) · Cancel · manual-fallback copy (export CTA) |
| ARCHIVED | excluded from the default list (12-month sweep); reachable only via old pagination — render as RECONCILED, fully inert |

Transitions the UI must never offer: place on non-QUOTED, confirm on
non-AWAITING, deliver on non-CONFIRMED, anything on RECONCILED/CANCELLED/ARCHIVED
— guarded client-side and still handled via 409 (§8) if raced.

### 5d. Substitution review — #18, #19 (`GrocerySubstitutionProposalDto`)

Card per proposal under a DELIVERED order (plus the `outstandingProposals` badge):

| Display element | Source field |
|---|---|
| Swap line | `originalDisplayName` → `substituteDisplayName` (SwapLine component); product ids in tooltip (`originalProductId`, `substituteProductId`) |
| Quantity/price | `substituteQuantity` + `substituteUnit` + `substituteUnitPence` ÷ 100 |
| Reason chip | `reason` (provider-supplied: "out of stock") |
| Mapping keys | `originalIngredientMappingKey` / `substituteIngredientMappingKey` → price-popover triggers |
| State | `proposalStatus` — PENDING_USER_REVIEW (actionable) · UNPARSED (actionable, amber "we couldn't read this one — judge it yourself") · ACCEPTED / REJECTED read-only with `resolvedAt` + `resolvedByUserId` (member-name join) |
| Order-line link | `groceryOrderLineId` → highlights the line in §5b |

**Buttons** (PENDING_USER_REVIEW and UNPARSED only):
**Accept** (primary) / **Reject** (ghost) → #19 body
`ResolveSubstitutionRequest{ proposalId = path id, decision: ACCEPTED | REJECTED }`
(other enum values are 400). The card must state the consequences (HLD):

- Accept → "the substitute goes into your pantry"
- Reject → "logged as unmet — the planner may suggest re-optimising affected meals"
- Footer: "All substitutions must be resolved before the order completes" —
  resolving the last one triggers reconciliation server-side; re-fetch #11.

409 → "already resolved elsewhere" → re-fetch #18.

## 6. Price history (Tier 4) — popover, activity, refresh

### 6a. Per-line price popover — #21/#22/#24, keyed by `ingredientMappingKey`

**Aggregate tab** (`PriceAggregateDto`, #21 — store param omitted = cross-store
blend; 404 = "no price data for this ingredient yet", not an error):

| Display element | Source field |
|---|---|
| Headline | `pointEstimatePence` ÷ 100 ("~£3.20") |
| Confidence | `confidence` (0–1 → %; < 0.5 amber) |
| Range | `minPence`–`maxPence` ("£2.90–£3.60") with `minObservedAt`/`maxObservedAt` tooltips |
| Freshness | `lastSeenAt` ("last seen 5 weeks ago") + `sampleCount` ("from 7 observations"); `sampleCount=0` → "reference price" caption (cold-start fallback source) |
| Stale flag | `isStale=true` → amber "stale — refresh or record a price" |
| Echo | `ingredientMappingKey`, `store` (header context) |

**Compare-stores tab** (#22): list of the same rows, one per `store` (empty →
"only one store seen"). **History tab** (#24, paginated
`PriceObservationDtoPage`): rows mapping every `PriceObservationDto` field —
`observedAt` · `store` · `paidTotalPence`÷100 (+ `paidUnitPence` unit price) ·
`quantity`+`quantityUnit` · `packCount`×`packSizeG` · `source` badge (PAID /
QUOTE / MANUAL / MANUAL_ESTIMATED / INFLATION_INDEXED) · `confidenceWeight` dot ·
`note` · `groceryOrderId`/`shoppingListLineId` → deep links · `currency`.
(`id`, `userId`, `householdId`, `providerProductId` — tooltip/plumbing only.)

### 6b. Record a price — `RecordManualPriceRequest` (#25)

"Record a price" in the popover (and a bare form in the activity drawer):

| Control | Request field | Constraints |
|---|---|---|
| (prefilled) | `ingredientMappingKey`* | 1–128, from the line; editable free text in the drawer variant |
| Store input* | `store`* | 1–64 — **required here** (unlike mark-bought where it defaults) |
| Price (£→pence) | `paidTotalPence` | optional ≥ 0 |
| Quantity + unit | `quantity`, `quantityUnit` | optional (unit ≤ 16) |
| When | `observedAt` | optional, default now |

201 → toast "recorded (source MANUAL, weight 0.7)"; popover aggregate re-fetches.

### 6c. Refresh prices — `RefreshPricesRequest` (#26)

Header action on the stale-prices cell:

| Control | Request field | Constraints |
|---|---|---|
| (implicit) | `ingredientMappingKeys` | the current list's UNFILLED line keys (≤ 200); null = server default |
| Provider toggle | `useProviderQuote` | pre-set true when #20 shows an enabled provider, false otherwise (false = re-read aggregates only, no tokens spent) |

`RefreshPricesResultDto`: toast "`ingredientsRefreshed` ingredients ·
`observationsWritten` new observations"; `aiUnavailableFallbackUsed=true` →
amber toast with `fallbackMessage` ("AI features paused — enter prices manually
via mark-bought"). 503 → same fallback copy (§8). Then re-fetch #1 (line
estimates may move).

### 6d. Cost ± confidence + staleness — page-wide display rules

- Money renders from integer pence ÷ 100 in `currency` (GBP v1).
- **No ± band anywhere** — the contract carries `(estimate, confidence)` only; the
  HLD's "£47 ± £8" band has no field (same finding as plan.md §4c; §8 open
  question 1). Render "£52.40 · 83% confidence".
- Confidence < 0.5 → amber, ≥ 0.5 → neutral; null → "no price data yet".
- Stale = aggregate `isStale` / line `isStaleEstimate` / list
  `staleIngredientCount` — all mean "freshest observation > 3 months" (aggregator
  `staleThresholdDays=90`), not the mock's "2 weeks" (that threshold belongs to
  the provisions supplier cache, pantry.md §7).
- Estimates are approximations — prefix "~" on line estimates (HLD display
  strategy: no false precision).

## 7. Not on this page

| Contract item | Home |
|---|---|
| `PUT /grocery/orders/providers/{providerKey}` (`ProviderConnectionRequest`: enable/disable, scheduled refresh toggle, top-N) | /settings — provider connection management ("configure Tesco in Settings" per HLD) |
| `GroceryProviderStateDto.scheduledRefreshEnabled` / `refreshTopNIngredients` editing | /settings (read-only gate info here, §5a) |
| Provider login / cookie establishment | Provider's own UI (out of band; never in-app) |
| Scheduled background refresh, hourly status polling, archive sweep | Server `@Scheduled` jobs — no UI; their effects arrive via re-fetch |
| `ShoppingListDto.version`, order `version`, ids | Optimistic-lock/request plumbing |
| Budget target & headroom (`provisions/budget`) | /pantry (pantry.md §6) — this page links out |
| Pantry contents the list subtracted | /pantry |
| Plan generation that produces the list | /plan (plan.md) |
| Per-line "which recipe demanded this" enrichment | Not in the v1 DTO (LLD out-of-scope: discovery-style enrichment, v2) |

## 8. Status-code → UI map

| Code | Where | UI behaviour |
|---|---|---|
| 404 | #1 current list | Empty state (not an error): "No shopping list for this week" → **Recalculate** CTA; no active plan at all → "Generate a plan first" → /plan/generate |
| 404 | #2/#5 list, #6–#8 line, #11–#19 order/proposal | "No longer exists" toast → re-fetch #1/#9 |
| 404 | #20 provider state | Connect-provider empty state (§5a, not an error) |
| 404 | #21 aggregate | "No price data yet" popover state (not an error) |
| 409 already bought | #6 | Silent re-fetch (another device marked it); checkbox settles |
| 409 not bought | #8 | Silent re-fetch |
| 409 optimistic lock | #6/#7 | Re-fetch list + one auto-retry, then surface |
| 409 illegal transition | #12–#17, #19 | Re-fetch order: "this order changed state elsewhere" |
| 409 concurrent operation | #10/#12/#13/#15 (advisory lock) | "Another grocery operation is in flight — try again shortly" toast |
| 422 provider not configured | #10/#12/#13 | "Connect a provider in Settings" CTA (should not occur — §5a gates) |
| 503 provider unavailable | #12/#13/#15 | Order banner per §5c PROVIDER_UNAVAILABLE + manual-fallback copy |
| 503 AI unavailable (distinct ProblemDetail `type`) | #12/#13/#26 | "AI features paused — use the printable list / enter prices manually" + Export CTA; order reverts to DRAFT |
| 400 | #4/#6/#7/#17/#19/#25/#26 | Inline field errors; path/body id mismatch is a client bug — log + generic toast |
| 401 | all | Global session-expired redirect |

**Open questions (flagged, not resolved here):**
1. **No cost-variance field.** HLD mandates "£47 ± £8 (17% uncertainty)"; the
   contract has `(estimate, confidence)` and per-key min/max only — no list-level
   range. Same gap as plan.md §8; backend gap candidate: range or variance on
   `ShoppingListDto` (compose from line aggregates server-side).
2. **Recalculate can't refresh within a generation.** `recalculate` is idempotent
   on `(planId, planGeneration)` — after pantry drift (e.g. items spoiled) the
   button returns the cached list. The HLD says lists "regenerate when the
   underlying plan or provisions change"; the server-side provisions listener
   "may prompt regeneration" but no force path exists. Backend gap candidate:
   `force` flag or listener-driven supersede within a generation.
3. **QUOTED → DRAFT re-edit edge has no endpoint.** The state machine allows it;
   no REST method performs it, and re-quoting from QUOTED is an illegal
   transition. UI consequence: a stale quote can only be re-priced by cancelling
   and creating a new draft. Backend gap candidate: re-quote or back-to-draft.
4. **Undo-mark-bought leaves the pantry add in place** ("inventory corrected
   manually" per the contract). The UI says so (§4c), but the natural expectation
   is a compensating removal. Backend gap candidate: reverse the inventory add in
   `undoMarkBought`.
5. **No provider catalogue endpoint.** The page hardcodes `tesco` as the v1
   provider key for #10/#20; a `GET providers` list is needed when a second
   provider lands.
6. **PLACED advance is implicit.** After the user picks a delivery slot in the
   provider UI, the only path to AWAITING_USER_CONFIRMATION is `refresh-status`
   detecting it (or the hourly job). Acceptable for v1? An explicit "slot chosen"
   action may be friendlier.

## 9. Mock deltas (to make the mock match this spec)

1. Stat strip: retype on `ShoppingListDto` — projected total + confidence from
   `estimatedTotalPence`/`costConfidence`, stale cell from `staleIngredientCount`;
   drop the budget-headroom cell (or make it a /pantry link); add the
   pantry-tracking-off caption and the cold-start nudge.
2. Line list: regroup by `lineType` (drop bespoke category groups); retype rows on
   `ShoppingListLineDto` (pack suggestion, confidence dot, bought-line detail,
   `boughtVia` badge, SUBSTITUTED/DROPPED states beyond the binary open/bought).
3. Stale copy: "2+ weeks" → "> 3 months" (`isStaleEstimate` semantics); footnote
   re-worded per §6d.
4. Mark-bought: replace the bare checkbox toggle with one-tap (no price) + the
   §4a popover (quantity/unit/price/store/when); add the result toasts
   (price-recorded, pantry link, over-mark note). Add select-mode bulk with
   total-spend distribution (§4b) and Undo with the no-inventory-reversal caveat.
5. Enable Export (per-format menu over #5) and Recalculate (#4) header actions;
   add the history drawer (#3) with retro-marking.
6. Orders: replace the single mock order + `advanceOrder` stepper with the full
   §5c machine (11 statuses, per-state buttons, `confirmLink` out-links,
   PLACED_PARTIAL and PROVIDER_UNAVAILABLE banners, cancel-with-reason); gate the
   panel on provider state (#20) incl. the 404 connect CTA; seed one order at
   AWAITING_USER_CONFIRMATION and one DELIVERED with 2 proposals (one UNPARSED).
7. Substitutions: retype on `GrocerySubstitutionProposalDto`; render the
   accept/reject consequence copy + the resolve-gates-reconciliation footer;
   resolve via #19 semantics (proposal leaves the outstanding set; order
   re-fetches and may flip RECONCILED).
8. Add the price popover (§6a: aggregate / compare-stores / history tabs), the
   record-a-price form (§6b), the refresh-prices action with AI-fallback toast
   (§6c), and a price-activity drawer over #23.
