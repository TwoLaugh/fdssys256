# Ticket: provisions — inventory query + status-tap surface gaps (P2)

## Summary

Three Pantry-page gaps on the same controller, shipped together
([`design/frontend/pages/pantry.md` §9 Q1–Q3](../../design/frontend/pages/pantry.md)):

1. **Staple status tap rides the full PUT.** The HLD promises "single-tap update … no friction",
   but the only write path for `status` (STOCKED → LOW → OUT) is the full-replacement
   `PUT /inventory/{itemId}` with the whole document — heavyweight for a chip tap and racy against
   concurrent system writes. Mirror of the existing `PATCH …/quantity`:
   **`PATCH /inventory/{itemId}/status` `{ newStatus, expectedVersion }`**.
2. **No non-ACTIVE inventory view.** `GET /inventory` returns ACTIVE only, no `itemStatus` filter
   — a spoiled/exhausted/wasted item disappears irrecoverably from the UI (mark-spoiled has no
   undo; the repair path needs to *find* the row). Add **`itemStatus` filter** (default ACTIVE).
3. **No server `expiringSoon` filter.** The stat cell derives "expiring ≤ 7 days" client-side from
   loaded pages — wrong past page 1. Add **`expiringWithinDays` param** (items with
   `expiryDate ≤ today + N`, null expiry excluded).

### OpenAPI excerpt

```yaml
# paths/provisions.yaml — /inventory
parameters:
  - { name: itemStatus, in: query, schema: { type: string, enum: [ACTIVE, EXHAUSTED, SPOILED, WASTED], default: ACTIVE } }
  - { name: expiringWithinDays, in: query, schema: { type: integer, minimum: 0 } }
# /inventory/{itemId}/status
patch:
  operationId: adjustInventoryStatus
  requestBody: { newStatus: { enum: [STOCKED, LOW, OUT] }, expectedVersion: integer }
  responses: { '200': InventoryItemDto, '400': 'QUANTITY-mode item', '409': 'stale version' }
```

## Behavioural spec notes

- `PATCH /status`: STATUS-tracking-mode items only — 400 on a QUANTITY item (same split as the
  quantity PATCH's 400 on STATUS items). Transition to OUT on a staple fires `ItemRanOutEvent`
  exactly as the PUT path does today (the replenishment promise — single source for that rule, do
  not duplicate the event logic). Audit row written (`fieldChanged: status`).
- `itemStatus` filter composes with `storageLocation`/`isStaple`/pagination; default keeps today's
  behaviour byte-identical for existing callers.
- `expiringWithinDays=7` + `itemStatus=ACTIVE` is the stat-cell query; count comes from
  `totalElements` — no new endpoint needed.

## Edge-case checklist

- [ ] Status PATCH on QUANTITY item → 400; on STATUS item → 200, audit row, version bump
- [ ] Staple → OUT via PATCH fires ItemRanOutEvent once (parity with PUT path, asserted)
- [ ] 409 stale version → re-fetch-and-retry works (chip-tap UX)
- [ ] `itemStatus=SPOILED` lists spoiled rows (the mis-tap recovery view); default ACTIVE unchanged
- [ ] `expiringWithinDays=0` → expiring today; null-expiry items never match
- [ ] Filters compose; pagination correct on filtered sets

## Files this ticket touches

```
MOD   src/main/java/com/example/mealprep/provisions/api/controller/InventoryController.java   (PATCH status + two params)
MOD   src/main/java/com/example/mealprep/provisions/domain/service/...                        (status transition method + repo filters)
MOD   src/main/resources/openapi/paths/provisions.yaml + schemas/provisions.yaml              (operation + request shape + params)
MOD   src/test/java/com/example/mealprep/provisions/...                                       (matrix above)
```

## Acceptance / DoD

- [ ] `verify` + `spotless` clean; CI green
- [ ] Pantry chip tap = one PATCH; stat cell = one filtered count query; spoiled rows findable

Squash-merge with: `feat(provisions): status PATCH + itemStatus/expiringWithinDays inventory filters`

**Not in scope:** mark-spoiled+waste composition and consumption-endpoint placement — P3, see
[`grocery-provisions-p3-clarifications.md`](grocery-provisions-p3-clarifications.md); undo for
mark-spoiled (the itemStatus filter + edit-mode lifecycle select is the recovery path).
