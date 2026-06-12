# Page spec — Pantry (`/pantry`)

The contract-complete specification: every endpoint this page consumes, and the UI
that each request field and response field demands. A control exists for every
writable field; a display home exists for every returned field (or an explicit
"not on this page" entry). Companion docs: [../ia.md](../ia.md),
[../design-language.md](../design-language.md). Template: [nutrition.md](nutrition.md)
(the pilot). Siblings: [groceries.md](groceries.md), [plan.md](plan.md).

Pantry is the provisions module's user surface — inventory (with expiry and
lifecycle), waste log + summary, equipment, and the weekly budget. The
cross-module *write* seams (cook-event, standalone consumption, grocery import,
planner bundle) are deliberately not user surfaces here (§8).

---

## 1. Intent (HLD)

From `design/provision-model.md` + `lld/provisions.md` (user-facing only):

- **Everything is optional, everything is additive.** "Each feature reads from its
  own data and gracefully ignores missing data from other features." The page must
  work with any subset populated — empty inventory, no budget, no expiry dates —
  and never present a feature as a prerequisite.
- **Two tracking modes.** Fridge/freezer/cupboard items are quantity-tracked
  (`quantity` + `unit`); spice-rack/staple items are status-tracked
  (`STOCKED → LOW → OUT`) because "gram-level tracking is pointless for spices,
  but running out of cumin mid-recipe is a real problem." Status updates must be
  "single tap … no friction."
- **Staples auto-replenish** — `isStaple` items at LOW/OUT "are automatically
  included in the next shopping list." The UI tells the user that's what the
  status tap did.
- **Expiry is honest, not precise.** Dates come from category defaults at import;
  "user corrections take precedence." Freezer expiry derives from
  `frozenAt + maxFreezeWeeks`. Display quantities as approximate ("~400 g") —
  "when the system is uncertain, say so."
- **The user is the authority on their pantry.** "All overrides are logged with
  timestamps for auditability but do not require approval flows."
- **Mark-spoiled has consequences** — `ItemSpoiledEvent` → "the planner reacts by
  offering re-optimisation of any meal slot scheduled to use the spoiled
  ingredient." Exhausting a staple fires `ItemRanOutEvent` → next shopping list.
  The UI must say what will happen, since nothing is auto-applied.
- **Waste entries are immutable** — "corrections create a new entry, not an edit";
  waste ≤ remaining inventory when tracking is active; reasons feed the planner
  (`EXPIRED` → schedule earlier / buy less; `DIDNT_LIKE` → routed to feedback).
- **Equipment is a hard filter** on recipe feasibility; when the list is empty the
  planner assumes common equipment — so an empty state must not read as "you own
  nothing".
- **Budget is one weekly figure** + tolerance + price sensitivity; "the system
  doesn't judge spending." Spend tracking against orders is **v1.5** —
  `spendTracking` is contractually null in v1.

## 2. Endpoint inventory

The provisions module exposes 25 endpoints; 19 are consumed by this page across
four sections (**Inventory · Waste · Equipment · Budget**, plus a price-book
read). 6 are not for this page (§8).

| # | Endpoint | Section | When called |
|---|----------|---------|-------------|
| 1 | `GET /api/v1/provisions/inventory?storageLocation&isStaple&page&size` | Inventory | On load + after every item mutation; filter-chip changes |
| 2 | `POST /api/v1/provisions/inventory` | Inventory | "Add item" form save |
| 3 | `GET …/inventory/{itemId}` | Inventory | Row expand / detail drawer |
| 4 | `PUT …/inventory/{itemId}` | Inventory | Edit-item form save (full replacement) + staple status tap (§3e) |
| 5 | `PATCH …/inventory/{itemId}/quantity` | Inventory | Quantity stepper / direct entry commit |
| 6 | `DELETE …/inventory/{itemId}` | Inventory | "Remove" (soft delete → WASTED, no waste entry) |
| 7 | `POST …/inventory/{itemId}/mark-spoiled` | Inventory | "Mark spoiled" button |
| 8 | `POST …/inventory/{itemId}/mark-exhausted` | Inventory | "Used up" button |
| 9 | `GET …/inventory/{itemId}/audit-log?page&size` | Inventory | Detail drawer "history" tab (lazy) |
| 10 | `POST /api/v1/provisions/meal-consumption` | Inventory | "Ate a portion" on prepared-meal rows (§3g) |
| 11 | `POST /api/v1/provisions/waste` | Waste | Log-waste form save (also reachable from a row action) |
| 12 | `GET /api/v1/provisions/waste?from&to&page&size` | Waste | Waste card open + range change (default last 90 days) |
| 13 | `GET /api/v1/provisions/waste/summary?from&to` | Waste | On load (30-day window for the stat cell) + range change |
| 14 | `GET /api/v1/provisions/equipment` | Equipment | On load |
| 15 | `PUT /api/v1/provisions/equipment/{name}` | Equipment | Toggle / add / details save (upsert) |
| 16 | `DELETE /api/v1/provisions/equipment/{name}` | Equipment | Row remove |
| 17 | `GET /api/v1/provisions/budget` | Budget | On load; 404 → set-budget CTA |
| 18 | `PUT /api/v1/provisions/budget` | Budget | Budget form save (upsert — insert and update both 200) |
| 19 | `GET /api/v1/provisions/supplier-products?mappingKey&supplier&page&size` | Price book | Detail-drawer "known products" tab + price-book expander (lazy) |
| s1 | `GET /api/v1/nutrition/ingredients/lookup?term` | Inventory | Mapping-key assist on the add/edit forms (debounced; same join the nutrition page uses) |

`userId` is server-resolved from the session on every call; the user can only act
on their own pantry.

## 3. Inventory — anatomy & field mapping

### 3a. Stat strip

| Cell | Source |
|---|---|
| Items tracked | #1 `totalElements` (unfiltered query) |
| Expiring soon | derived client-side: count of items with `expiryDate ≤ today + 7` (no server `expiringSoon` filter shipped — §9 open question 3); warn-styled when > 0 |
| Waste (30 days) | #13 `WasteSummaryDto.totalCostEstimate` ("£4.30") + `totalEntries` sub-line |
| Budget target | #17 `weeklyTarget` + `currency` ("£60 weekly"); the mock's spent/headroom figures are **not derivable** in v1 (`spendTracking` null — §6) |

### 3b. Item list — reads `InventoryItemDto` (#1)

Sections by `storageLocation` (FRIDGE · FREEZER · CUPBOARD · SPICE_RACK — the
mock's three locations miss SPICE_RACK); filter chips re-fire #1 with
`storageLocation` / `isStaple`. Paginated ("show more" appends pages).

| Display element | Source field |
|---|---|
| Name | `name`; `category` as a muted suffix or icon |
| Quantity (QUANTITY mode) | `trackingMode=QUANTITY` → "~`quantity` `unit`" (approximate prefix per HLD) + stepper (§3d) |
| Status (STATUS mode) | `trackingMode=STATUS` → `status` chip STOCKED olive · LOW amber · OUT red; tap cycles (§3e) |
| Staple badge | `isStaple=true` → "staple" tag, tooltip "auto-added to the shop when low or out" |
| Expiry | `expiryDate` — date label coloured: ≤ 2 days red, ≤ 7 amber, else muted (HLD default alert window: 2 d fridge / 14 d freezer — freezer rows use the 14-day amber threshold); null → no label (expiry tracking is optional) |
| Source badge | `source` — TESCO_ORDER / OTHER_SHOP / MANUAL_ADD / BATCH_COOK / GIFT; `sourceRef` in tooltip (order ref) |
| Cost | `costPaid` ("£3.50", detail drawer) |
| Lifecycle | `itemStatus` — the list returns ACTIVE only; EXHAUSTED/SPOILED/WASTED rows leave the list on mutation (no history view — §9 open question 2). The mock's inline SPOILED rows are a delta (§10.2) |
| Freezer detail (FREEZER rows) | `freezerExtension`: `frozenAt` ("frozen 10 Apr") · `maxFreezeWeeks` ("keeps 12 weeks") · `defrostMethod` enum chip (OVERNIGHT_FRIDGE / ROOM_TEMP / MICROWAVE / QUICK_DEFROST) · `defrostLeadTimeHours` ("needs 12 h defrost") · `sourceRecipeId` → recipe deep link ("batch-cooked bolognese") |
| Mapping key | `ingredientMappingKey` (detail drawer chip; null → "not matched to nutrition data" hint + edit CTA) |
| Notes | `notes` (italic, detail drawer) |
| Timestamps | `createdAt` ("added 15 Apr"), `updatedAt` (detail drawer) |
| Row actions | per §3e + edit (§3c) + log waste (§4) |

Not displayed: `id` (plumbing), `userId` (session), `version` (sent back as
`expectedVersion` on writes; collisions surface as 409, §9).

### 3c. Add / edit item forms — `CreateInventoryItemRequest` (#2) ⇄ `UpdateInventoryItemRequest` (#4)

One form, two modes (edit prefills and adds `expectedVersion` + `itemStatus`):

| Control | Request field | Constraints |
|---|---|---|
| Name input* | `name` | 1–128 |
| Category input* | `category` | 1–64 (suggest from existing categories) |
| Location select* | `storageLocation` | 4-value enum; drives the tracking-mode rule below |
| Tracking mode* | `trackingMode` | QUANTITY \| STATUS — **validator: SPICE_RACK requires STATUS; fridge/freezer/cupboard require QUANTITY** (400 otherwise); the UI derives it from location and shows it read-only |
| Quantity + unit | `quantity`, `unit` | QUANTITY mode: quantity ≥ 0, scale ≤ 3, ≤ 1,000,000; unit ≤ 16 ("g", "ml", "items", "portions") |
| Status select | `status` | STATUS mode: STOCKED / LOW / OUT |
| Staple toggle | `isStaple` | default false; caption "auto-replenished when low or out" |
| Expiry date | `expiryDate` | optional; caption "your date wins over our estimate" (user correction precedence) |
| Cost paid (£) | `costPaid` | optional ≥ 0 |
| Mapping-key assist | `ingredientMappingKey` | optional ≤ 128; typing the name fires s1 lookup — picking a suggestion fills the key ("links this item to recipes and nutrition"); no suggestion → leave null |
| Notes | `notes` | optional ≤ 255 |
| Source (add mode) | `source` | default MANUAL_ADD; selectable OTHER_SHOP / GIFT (TESCO_ORDER / BATCH_COOK are system-written) |
| — | `sourceRef` | not user-entered (system provenance); edit mode preserves it |
| Freezer panel | `freezerExtension` | **shown iff location = FREEZER** (validator enforces): `frozenAt` date · `maxFreezeWeeks` ≥ 0 · `defrostMethod` select · `defrostLeadTimeHours` ≥ 0 · `sourceRecipeId` (read-only; system-set on batch cooks) |
| Lifecycle (edit mode) | `itemStatus` | ACTIVE / EXHAUSTED / SPOILED / WASTED select — the repair path for a mis-tap (mark-spoiled has no undo) |
| (edit mode) | `expectedVersion`* | loaded `version`; 409 → conflict toast + reload row |

201 (add) returns the item + Location header; 200 (edit) returns the item.

### 3d. Quantity adjust — `AdjustInventoryQuantityRequest` (#5)

The focused edit for the stepper and inline quantity entry. **Exact body:
`{ newQuantity, expectedVersion }` — `newQuantity` is the absolute new value
(≥ 0), not a delta, and there is no unit field** (the unit is fixed on the item;
changing units is a full PUT). The stepper therefore computes
`current ± step → newQuantity` and sends the loaded `version`.

- Quantity-tracked items only — 400 on a status-tracked item (the UI never offers
  the stepper there).
- `newQuantity = 0` is legal (floor; the item stays ACTIVE — "used up" is the
  explicit action, §3e).
- 409 stale version → silent re-fetch + one retry with the fresh version (the
  other writer may have been a cook-event deduction), then surface.

### 3e. Lifecycle actions & staple status — #4/#6/#7/#8

| Action | Call | UI copy (the cross-module promise) |
|---|---|---|
| **Mark spoiled** | #7 (idempotent, no body) | Confirm: "Marks {name} spoiled and removes it from your pantry. The planner will offer to re-plan any meal that uses it — eaten and cooked meals stay pinned. *This doesn't log waste* — log it from the waste card to track the cost." (§9 open question 4) |
| **Used up** | #8 (idempotent, no body) | "Marks {name} finished." On a staple: "It'll be added to your next shopping list." (ItemRanOutEvent → staple replenishment) |
| **Remove** | #6 (soft delete, 204) | "Removes {name} without logging waste." Distinct from mark-spoiled and from waste logging — for entry mistakes |
| **Staple status tap** | #4 (full PUT) | Chip tap cycles STOCKED → LOW → OUT (legal transitions; replenishment back to STOCKED happens via grocery import). **No focused status endpoint exists** — the tap echoes the whole item back through PUT with `expectedVersion` (§9 open question 1) |

Mark-spoiled and used-up return the updated `InventoryItemDto`
(itemStatus SPOILED / EXHAUSTED) — the row animates out of the ACTIVE list.
Both are idempotent: a second tap returns 200 unchanged.

### 3f. Item history — `InventoryAuditEntryDto` (#9)

"History" tab in the detail drawer, paginated newest-first:

| Display element | Source field |
|---|---|
| Actor icon + label | `actor` — USER "you" · COOK_EVENT "cooking" · GROCERY_IMPORT "delivery" · NUTRITION_LOGGER "food log" · SYSTEM |
| Member attribution | `actorUserId` → member-name join (multi-user households); null for system actors |
| Change line | `fieldChanged` + `previousValue` → `newValue` ("quantity: 600 → 200") — values are untyped JSON, render as text |
| When | `occurredAt` |

(`id`, `inventoryItemId` — plumbing.) This is the HLD's "overrides are logged
with timestamps" surface; read-only.

### 3g. Prepared meals — "Ate a portion" (`MealConsumptionCommand`, #10)

Rows with `source=BATCH_COOK` (typically `unit="portions"`) get a one-tap
**Ate a portion** action — the HLD's meal-consumption flow ("auto-deduct one
portion when user confirms eating a pre-made meal… single-tap confirmation"):

| Control | Request field | Constraints |
|---|---|---|
| (implicit) | `inventoryItemId`* | the row |
| Portion count | `portions`* | ≥ 0, default 1 (long-press / popover for more) |
| — | `traceId` | client-generated uuid (optional) |

Returns `InventoryDeductionResultDto`: `updatedItems[]` → refresh rows;
`exhaustedItems[]` → "that was the last portion" toast (row leaves the list);
`underflows[]` → amber toast "`requested` portions logged but only `available`
tracked — pantry floored at zero" (HLD guardrail). 404 → row gone elsewhere,
re-fetch. *Note: nutrition logging is separate — this records the pantry
deduction only; log the meal on /nutrition.* (§9 open question 5.)

## 4. Waste — log, list, summary (#11/#12/#13)

### 4a. Log-waste form — `LogWasteRequest` (#11)

Opened standalone from the waste card, or pre-linked from an item row's
"Log waste" action:

| Control | Request field | Constraints |
|---|---|---|
| Linked item | `inventoryItemId` | optional uuid — set when opened from a row (locks `itemName` to the item; deduction then applies to that row) |
| Item name* | `itemName` | 1–128, free text when unlinked (waste needn't be tracked inventory) |
| Quantity + unit | `quantity`, `unit` | optional ≥ 0 / ≤ 16; **linked + tracking active: must not exceed the row's remaining quantity (422)** |
| Reason select* | `reason` | EXPIRED "didn't use in time" · LEFTOVER_NOT_EATEN · DIDNT_LIKE (caption: "this one also feeds your taste preferences") · SPOILED_EARLY "went off before the date" · MADE_TOO_MUCH |
| Cost (£) | `costEstimate` | optional ≥ 0; prefill from the linked row's `costPaid` |
| Date* | `occurredOn` | date, default today |
| Notes | `notes` | optional ≤ 255 |

201 → entry persisted; caption near the form: "Waste entries can't be edited —
log a correcting entry if you make a mistake" (immutability per HLD). When
linked, the deduction also fires server-side (row quantity drops, floors at
zero, may flip WASTED) → re-fetch #1.

### 4b. Waste list & summary

**List** (#12, default last 90 days, `from`/`to` pickers, paginated): rows map
every `WasteEntryDto` field — `itemName` · `quantity`+`unit` · `reason` chip ·
`costEstimate` ("£0.50") · `occurredOn` · `notes` (italic) · `inventoryItemId` →
item link (when the item still exists) · `createdAt` tooltip. No edit/delete
actions exist (immutable). 400 from > to → swap-dates inline error.

**Summary** (#13, same range): `totalCostEstimate` headline ("£12.40 wasted") ·
`totalEntries` · `countByReason` map → per-reason mini-bars · `topItems[]`
(`TopWastedItemDto`: `itemName` · `entryCount` · `totalCost`) → "most wasted"
list. `from`/`to` echo the range header.

## 5. Equipment — #14/#15/#16

Card of `EquipmentDto` rows + an add row:

| Display element / control | Field | Notes |
|---|---|---|
| Name chip | `name` | canonical snake_case key, 1–64, `^[a-z0-9_]+$` (e.g. `air_fryer`) — render prettified ("Air fryer"); the add input validates the pattern |
| Availability toggle | `available` | toggle fires #15 upsert; unavailable rows render muted, not hidden (own-but-broken ≠ absent) |
| Details | `details` | ≤ 255 ("4L capacity", "stick blender only") — inline editable, saved via #15 |
| Version | `version` | → `expectedVersion` on update (required for update, ignored for insert — 200 update / 201 insert); 409 → reload row |
| Remove ✕ | — | #16 DELETE, 204; 404 → already gone, refresh |

Caption (HLD semantics, both directions): "Equipment filters which recipes the
planner can pick. No list yet? The planner assumes a typical kitchen." Empty
state offers a starter checklist (oven, hob, microwave, …) that fires one upsert
per tick — the onboarding pattern.

## 6. Budget — `BudgetDto` ⇄ `UpdateBudgetRequest` (#17/#18)

There is **no POST** — `PUT /budget` upserts (insert and update both return 200).

**Empty state (GET 404):** "Set a weekly budget" CTA → form with
`expectedVersion = 0` (the insert default). Caption: "Optional — plans work
without it; with it, the planner optimises cost."

| Display element / control | Field | Constraints |
|---|---|---|
| Target input* | `weeklyTarget` | > 0 (exclusive), no upper limit ("the system doesn't judge spending") |
| Currency* | `currency` | 3-letter `^[A-Z]{3}$`; **changing it on an existing budget returns 422** — render read-only after creation with that explanation |
| Tolerance input | `toleranceOver` | ≥ 0, default 0; caption "soft ceiling — a £50 target with £10 tolerance lets a £58 plan through" |
| Sensitivity segmented | `priceSensitivity` | `low` / `moderate` / `high` (lowercase enum) — "how hard to chase cheaper options" |
| Enabled toggle | `enabled` | default true; off → card renders "budget tracking off", planner stops cost-gating |
| Save | `expectedVersion`* | loaded `version` (0 on insert); 409 → conflict toast + reload |
| Spend tracking | `spendTracking` | **always null in v1** (populated by 01f/01h once order history is wired) — render target-only; when non-null (v1.5): `currentWeekActual` / `currentWeekTarget` bar, `currentWeekRemaining`, `rollingFourWeekAverage`, `currentWeekOrders[]` (supplier · orderRef · totalCost · deliveredOn) |

(`id`, `userId` — plumbing.) The mock's "£38.40 of £52" spent bar cannot be wired
in v1 (same finding as today.md §8 Q5) — mock delta §10.6.

## 7. Price book — `SupplierProductDto` reads (#19)

Read-only expander ("Known products & prices") + a "known products" tab on the
item detail drawer (filtered by the item's `ingredientMappingKey`):

| Display element | Source field |
|---|---|
| Product name + supplier | `name`, `supplier` badge; `productId` tooltip |
| Price | `price` ("£4.50") + `pricePerUnit` per `unit` ("45p / 100g") + `clubcardPrice` ("£3.75 with Clubcard") |
| Pack | `packSizeG` + `packSizeUnit` ("1 kg") |
| Category | `category` chip |
| Freshness | `lastChecked` — > 2 weeks old → "estimated" amber tag; > 4 weeks → "too old for cost estimates" muted tag (HLD supplier-cache guardrails; relaxed during the first 8 ramp-up weeks) |
| Substitution history | `substitutionHistory[]` (`SubstitutionRecordDto`): `date` · `substitutedWithProductId` → product-name join when cached · `accepted` ✓/✗ · `notes` |
| Mapping key | `ingredientMappingKey` (joins to inventory rows) |

Search controls: `mappingKey` / `supplier` inputs (both optional), paginated,
sorted `lastChecked` DESC. (`id`, `version` — plumbing; writes are not on this
page, §8.) Per-store *observed* price history lives on /groceries (price
popover) — this card is the supplier catalogue cache, not the learning loop.

## 8. Not on this page

| Contract item | Home |
|---|---|
| `GET /provisions/planner-bundle` | Planner-internal compose read (no UI) — per plan.md §7 |
| `POST /provisions/cook-event` (`CookEventCommand`: recipeId, mealSlotId, servingsCooked, isBatchCook, proportionOfRecipe, strict, dedupeKey, ingredientsUsed[]) | The cook flow (Today / recipe surface) — v1 ships it as an operator/test seam; not wired from any page yet (today.md §8 Q1 owns the wiring question). Its *effects* show here: COOK_EVENT audit rows, deductions, batch-cook portion rows |
| `POST /provisions/standalone-consumption` | Nutrition logger's snack flow (/nutrition §3e `deductFromPantry`, reserved v1.5) — the HLD's canonical unplanned-consumption path; NUTRITION_LOGGER audit rows show here |
| `POST /provisions/grocery-import` (`GroceryOrderImportCommand`) | **Not a user surface.** The grocery module calls `applyGroceryOrder` in-process at order-reconcile time (idempotent on supplier+orderRef; duplicate → 409); the REST endpoint is an operator/test seam. Manual "I shopped elsewhere" entry = mark-bought on /groceries or Add item here (#2) — clarified per lld/provisions.md §REST reconciliation note |
| `POST /provisions/supplier-products` (upsert) + `POST …/{id}/substitutions` | Grocery-module writes (order import / price refresh) — no user editor in v1; the HLD's "supplier price correction" lands as a /groceries manual price observation instead |
| Expiry alerts & defrost reminders | /notifications — the scanner lives in the notification module; this page only colour-codes `expiryDate` |
| Budget spend-vs-target bar | v1.5 (`spendTracking` null in v1, §6); weekly cost *projection* is /groceries |
| Item-level price history & observations | /groceries §6 (price popover) |

## 9. Status-code → UI map

| Code | Where | UI behaviour |
|---|---|---|
| 404 | #17 budget | Set-budget empty state (§6, not an error) |
| 404 | #3–#9 item, #16 equipment | "No longer in your pantry" toast → re-fetch #1 |
| 409 stale `expectedVersion` | #4/#5/#15/#18 | Re-fetch + one silent retry on the stepper (#5); form saves surface a conflict toast ("changed elsewhere — review and re-save") |
| 422 | #11 waste | "That's more than you have tracked (n g left)" inline error on quantity |
| 422 | #18 currency change | "Currency can't change on an existing budget" — field rendered read-only (§6) |
| 400 | #2/#4 validator | Inline: tracking-mode/location mismatch, freezer panel outside FREEZER |
| 400 | #5 on status-tracked item | Never offered; defensive toast + row re-fetch |
| 400 | #12/#13 from > to | Inline date-range error |
| 401 | all | Global session-expired redirect |

**Open questions (flagged, not resolved here):**
1. **Staple status tap rides the full PUT.** The HLD promises "single-tap update
   … no friction", but the only write path for `status` is the full-replacement
   `PUT /inventory/{itemId}` with `expectedVersion` — racy against concurrent
   system writes and heavyweight for a chip tap. Backend gap candidate: focused
   `PATCH /inventory/{itemId}/status` (mirror of adjustQuantity).
2. **No non-ACTIVE inventory view.** `GET /inventory` returns ACTIVE items only,
   with no `itemStatus` filter — once something is spoiled/exhausted/wasted it is
   only visible via the per-item audit log (whose id you no longer have). Backend
   gap candidate: `itemStatus` filter on the list (a "recently removed" view also
   covers mark-spoiled mis-tap recovery, since #7/#8 have no undo).
3. **No server `expiringSoon` filter.** The LLD's query surface mentions it; the
   shipped contract has only `storageLocation`/`isStaple`. The stat cell derives
   client-side from the loaded pages — wrong once the pantry exceeds one page.
   Backend gap candidate: `expiringWithinDays` param.
4. **Mark-spoiled and waste logging are disjoint calls.** The HLD treats "the
   chicken's gone off" as one user moment (waste entry + spoiled status), but the
   contract needs `POST mark-spoiled` + `POST waste` separately. The UI offers a
   "also log to waste" checkbox in the confirm (two sequential calls, second may
   fail independently). Backend gap candidate: composed spoil-with-waste, or a
   `logWaste` flag on mark-spoiled.
5. **"Ate a portion" placement is a product call.** The LLD frames the
   consumption REST endpoints as operator/test seams; this spec gives
   meal-consumption a user surface on prepared-meal rows (§3g) because the HLD
   describes exactly that single-tap flow. Confirm — and decide whether it should
   also nudge nutrition logging (currently a manual cross-ref).
6. **Manual-add mapping-key inference.** The HLD says "the system infers the
   mapping or the user confirms it"; no provisions-side inference endpoint exists.
   This spec borrows the nutrition lookup (s1) as the assist. Acceptable, or
   should provisions own an infer endpoint?

## 10. Mock deltas (to make the mock match this spec)

1. Retype the pantry slice on `InventoryItemDto` (4 locations incl. SPICE_RACK,
   tracking modes, staple status chips, source badges, freezer extension,
   lifecycle states) and drive sections/filters from #1's query params +
   pagination instead of the seeded 3-location array.
2. Remove inline `spoiled` rows — mark-spoiled (and used-up / remove) drop the
   row from the ACTIVE list; fix the footnote ("logs its cost to waste" is wrong
   — waste logging is a separate action, §3e/§9 Q4) and add the staple-replenish
   copy on used-up.
3. Stepper: send absolute `newQuantity` + `expectedVersion` (PATCH semantics, one
   in-flight commit with debounce), not ±1 deltas; render "~" approximate
   quantities; add the 409 silent-retry path.
4. Add the add/edit item forms (§3c, with the location↔tracking-mode validator
   and the freezer panel), the detail drawer (mapping key, notes, costPaid,
   audit-log history tab over #9), and "Ate a portion" on BATCH_COOK rows.
5. Waste card: add the log-waste form (reason enum + linked-item deduction +
   422 path) and the from/to summary view (`countByReason` bars, `topItems`);
   entries become immutable rows with reason chips (replace the bare name/cost
   list).
6. Budget card: retype on `BudgetDto` — target + tolerance + sensitivity +
   enabled, editable via the §6 form; put the spent bar behind a
   `spendTracking != null` guard (null in the seed) and drop the derived
   headroom cell from the strip.
7. Equipment: replace static chips with toggle rows (`available`, `details`,
   upsert/delete), the canonical-name add input, and the empty-state starter
   checklist.
8. Add the price-book expander (§7) seeded with 2 supplier products — one fresh,
   one > 4 weeks stale — and substitution history on one of them.
