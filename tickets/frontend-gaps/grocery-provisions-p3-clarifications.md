# Ticket: grocery/provisions — P3 semantic clarifications (combined)

Low-priority items from [`design/frontend/pages/groceries.md` §8](../../design/frontend/pages/groceries.md)
and [`pantry.md` §9](../../design/frontend/pages/pantry.md). Resolve item-by-item; tick + annotate.

## Items

1. ✅ **QUOTED → DRAFT re-edit edge has no endpoint** (groceries §8 Q3). The `OrderStateMachine`
   allows the edge; no REST method performs it, and re-quoting from QUOTED is illegal — a stale
   quote can only be re-priced by cancel + new draft. **Proposed:** add
   `POST /orders/{id}/back-to-draft` (or make `quote` legal from QUOTED) when Tier-3 usage shows
   the papercut matters; cancel+recreate is an acceptable v1 workaround.
   **Decision (built, P2 grocery batch):** accepted for build as part of the groceries-page P2
   batch — `POST /api/v1/grocery/orders/{orderId}/back-to-draft` (200 with the reverted order;
   422 `order-not-revertible` when not currently QUOTED; 404 unknown). Discards the stale quote
   (provider order id, quoted total, per-line quoted prices; line statuses → QUEUED); audited via
   `status_reason = reverted_from_quoted` + `GroceryOrderRevertedToDraftEvent`.
2. ✅ **No provider catalogue endpoint** (groceries §8 Q5). The page hardcodes `tesco` for
   `POST /orders` and the provider-state gate. **Proposed:** accept while exactly one provider
   exists; `GET /grocery/orders/providers` becomes a prerequisite of the second provider's ticket.
   **DONE (2026-06-13, decision):** accepted as proposed — the catalogue endpoint is a hard
   prerequisite of provider #2's ticket, not v1 work.
3. ✅ **PLACED advance is implicit** (groceries §8 Q6). After the user picks a delivery slot in the
   provider UI, only `refresh-status` (or the hourly job) advances to AWAITING_USER_CONFIRMATION.
   **Proposed:** accept for v1 (the Refresh button is the affordance); an explicit "slot chosen"
   verb is a UX nicety to revisit with real usage.
   **DONE (2026-06-13, decision):** accepted as proposed — Refresh is the v1 affordance; revisit
   with real Tier-3 usage.
4. ✅ **Mark-spoiled and waste logging are disjoint calls** (pantry §9 Q4). The HLD treats "the
   chicken's gone off" as one moment; the contract needs `POST mark-spoiled` + `POST waste`
   (UI ships an "also log to waste" checkbox firing two calls, second can fail independently).
   **Proposed:** add an optional `logWaste: { reason, costEstimate }` block to mark-spoiled
   (composed, single transaction) — small endpoint change, schedule when convenient.
   **DEFERRED (v1.5): composed `logWaste` block on mark-spoiled** — a transactional endpoint
   change, bigger than a P3 doc pin; the two-call UI (second call can fail independently, user
   can retry from /pantry) is an acceptable v1 shape. Decision dated 2026-06-13.
5. ✅ **"Ate a portion" placement** (pantry §9 Q5). The LLD frames the consumption REST endpoints as
   operator/test seams; the page spec gives `POST /provisions/meal-consumption` a user surface on
   BATCH_COOK rows per the HLD's single-tap flow. **Proposed:** product confirm that the endpoint
   is a sanctioned user surface (auth/rate posture unchanged); decide whether it should nudge
   nutrition logging (currently a manual cross-ref).
   **DONE (2026-06-13, confirm + doc):** confirmed a sanctioned user surface — pinned in
   `lld/provisions.md` (§Events Consumed); no nutrition nudge in v1 (waits for the
   auto-confirm-on-cook leg).
6. ✅ **Manual-add mapping-key inference** (pantry §9 Q6). HLD: "the system infers the mapping or the
   user confirms it"; no provisions-side inference endpoint exists, so the page borrows the
   nutrition lookup as the assist. **Proposed:** bless the nutrition-lookup assist as the v1
   answer (doc pin in `lld/provisions.md`); a provisions-owned infer endpoint is v2.
   **DONE (2026-06-13, doc-only):** blessed as proposed — pinned in `lld/provisions.md`
   (§Events Consumed); provisions-owned inference stays a v2 candidate.

## Acceptance / DoD

- [x] Each item: decision recorded inline; accepted items pinned in the relevant LLD/OpenAPI text
- [x] Items 1/4 spun out as small tickets if accepted for build — item 1 built in the P2 grocery
      batch (PR #254); item 4 deferred (v1.5), not accepted for build

Squash-merge with: `docs(grocery,provisions): P3 clarifications from groceries/pantry page specs`
