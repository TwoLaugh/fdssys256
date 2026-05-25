# Ticket: grocery — 01f Substitution Review + Order Reconciliation + Provisions Event Seam

## Summary

Complete **Tier 3**: persist provider substitution proposals, the user-approves-each resolution
flow, the reconcile gate (blocked while any proposal is pending), and the reconciliation write
(paid-price observations + inventory + `GroceryOrderReconciledEvent`). Per
[LLD §Flow 4 steps 5-7 lines 911-913](../../lld/grocery.md),
[LLD §`resolveSubstitution` lines 608-609](../../lld/grocery.md),
[LLD §`grocery_substitution_proposals` lines 248-277](../../lld/grocery.md),
[LLD §Events §substitution decisions lines 829, 835-837](../../lld/grocery.md),
[LLD §Substitution REST lines 720-721](../../lld/grocery.md),
[LLD §`OrderHasOutstandingProposalsException` line 762](../../lld/grocery.md). Ships:

- **`SubstitutionPersister`** (LLD line 49) — maps provider `SubstitutionProposal`s to
  `grocery_substitution_proposals` rows (`pending_user_review`, or `unparsed` for opaque payloads).
  Invoked from 01e's `markDelivered`.
- **`resolveSubstitution`** + **`getOutstandingProposals`** — `GroceryOrderService` bodies.
- **The reconcile gate + `tryReconcile`** — internal; runs when no proposal remains
  `pending_user_review`; writes paid-price observations (`PAID`, weight 1.0), sets `paid_total_pence`,
  `status = RECONCILED`.
- **Substitution-decision events** — `SubstitutionProposedEvent` (published in 01e at delivery),
  `SubstitutionAcceptedEvent`, `SubstitutionRejectedEvent` (published here).
- **The substitution-resolve endpoints** (LLD lines 720-721) on the existing `GroceryOrderController`.

**Unblocks (E2E):** **GROC-19** ("A user resolves a substitution proposal at delivery" — `@pending`
[`grocery.feature` lines 87-93](../../src/e2e/resources/features/grocery/grocery.feature)). Completes
the **reconcile leg of GROC-18** ("delivered → reconciled") and the substitution + reconcile legs of
**XJ-05 / GROC-36** (against `FakeGroceryProvider`).

## Decision baked in — provider proposes → user approves EACH → reconcile blocked while pending

**Product-owner decision (settled):** substitution is **user-approves-each** — the provider proposes,
the user accepts or rejects each proposal individually, and **reconciliation is blocked while any
proposal is `pending_user_review`** (or `unparsed`). **Auto-accept is structurally forbidden** (LLD
line 912, pathway state-model 4.3 line 142 — "auto-accepting a proposal" is an illegal transition).
This matches the HLD's safety contract: a substitution can change a meal materially, so a human
decides.

**Event ownership (the GG-resolving seam):** **grocery owns the user-decision event
`SubstitutionAcceptedEvent`**; **provisions republishes its own inventory state-change.** Per
[LLD line 835](../../lld/grocery.md): the directionality of `SubstitutionAcceptedEvent` is ambiguous
across the design docs (the technical-architecture catalogue lists it as a sub-kind of provisions'
`ProvisionChangedEvent`, but the *decision* is owned by grocery). **This LLD's settled stance, baked
in here:** grocery publishes `SubstitutionAcceptedEvent` (the user-decision); provisions consumes it,
adds the substitute to inventory, and publishes its own `SubstitutionAcceptedEvent` /
`ItemAddedFromGroceryEvent` from its table update. **Worth user review (HIGH)** — this is a genuine
cross-doc ambiguity; surfacing it: there are now potentially **two events named
`SubstitutionAcceptedEvent`** (one grocery-owned user-decision, one provisions-owned state-change).
The planner's listener ([`lld/planner.md` line 985](../../lld/planner.md)) already pattern-matches on
`ProvisionChangedEvent.SubstitutionAcceptedEvent`. **The clean fix is distinct names** — e.g. grocery
publishes `SubstitutionResolvedEvent` (accepted/rejected) and provisions keeps its
`ProvisionChangedEvent.SubstitutionAcceptedEvent`. **01f's recommendation: rename grocery's event to
`SubstitutionAcceptedEvent` → keep, but qualify the package** (`grocery.event.SubstitutionAcceptedEvent`
vs `provisions.event.ProvisionChangedEvent.SubstitutionAcceptedEvent`) so they're distinct types, and
document the seam loudly. The owner should confirm the naming before build.

## LLD-divergence notes

### Inventory add: at confirmation or at reconciliation? (the load-bearing seam from 01e)

01e's note flagged this. Two LLD threads:
- [LLD line 910](../../lld/grocery.md): `markUserConfirmed` publishes `GroceryOrderConfirmedEvent` —
  "**the event Provisions consumes** to add items to inventory."
- [LLD line 913 / 837](../../lld/grocery.md): **reconciliation** "writes paid-price observations" and
  the HLD says reconcile "updates price history with paid prices"; Flow 7 has inventory writes at
  reconcile. The pathway (GROC-18 line 345) says reconcile is where "items added to Provisions
  inventory."

**These can't both be the single inventory-add trigger.** **01f's settled resolution:** **inventory
is added at RECONCILIATION, not at confirmation.** Rationale: at confirmation the basket is only
*confirmed* — what actually *arrives* (with substitutions resolved) is known only at delivery/reconcile.
Adding at confirm would add the wrong items when a substitution is later rejected. So:
- `GroceryOrderConfirmedEvent` (01e) is for **notification + provisions awareness** (the dormant
  provisions listener can pre-stage, but must NOT add stock yet).
- `tryReconcile` (01f) is the **single inventory-add trigger** via
  `ProvisionUpdateService.applyGroceryOrder` (the same canonical path Tier 2 uses), reflecting the
  actually-delivered + substitution-resolved lines.

**Worth user review (HIGH)** — this contradicts the literal LLD line 910 wording
("Provisions consumes to add items to inventory" on confirm). The provisions-01h dormant listener was
written to build a `GroceryOrderImportCommand` *on confirm*. **The provisions listener must be
re-pointed to fire on reconcile (or on a grocery `GroceryOrderReconciledEvent`), not on confirm** —
that is a **provisions follow-up ticket**. 01f publishes both events; the owner decides which the
provisions listener binds to. 01f's recommendation: provisions binds inventory-add to
`GroceryOrderReconciledEvent`.

### `version` on `GrocerySubstitutionProposal` for the resolve race

LLD line 364/983: `@Version` on the proposal — concurrent resolve attempts → 409. Already on the
entity from 01a.

### `unparsed → accepted|rejected` is a legal resolve transition

LLD line 921: the `resolveSubstitution` endpoint accepts an `UNPARSED → ACCEPTED|REJECTED` transition
(the user resolves a DOM-unparseable proposal manually — GROC-20). The proposal-status machine here:
`PENDING_USER_REVIEW → ACCEPTED|REJECTED` and `UNPARSED → ACCEPTED|REJECTED`.

## Behavioural spec

### `SubstitutionPersister` (LLD line 49, 1018) — invoked from 01e's `markDelivered`

Maps each provider `SubstitutionProposal` to a `grocery_substitution_proposals` row. Parseable →
`proposal_status = PENDING_USER_REVIEW` with original/substitute product + mapping keys. Opaque /
DOM-differs → `proposal_status = UNPARSED` with `raw_payload` populated (GROC-20). Each persisted
proposal → one `SubstitutionProposedEvent` (published by 01e after the delivery commit).

### `resolveSubstitution(userId, ResolveSubstitutionRequest)` (LLD lines 608, 912) — `@Transactional`

`ResolveSubstitutionRequest(proposalId, decision)` where `decision ∈ {ACCEPTED, REJECTED}` only.
1. Load the proposal (404 `GrocerySubstitutionProposalNotFoundException`). Legal from
   `PENDING_USER_REVIEW` or `UNPARSED`; else 409.
2. Set `proposal_status`, `resolved_at`, `resolved_by_user_id`. Bump `@Version` (409 on a stale
   resolve race).
3. **Accept** → publish `SubstitutionAcceptedEvent` (carries `substituteIngredientMappingKey`) —
   provisions adds the substitute on its consume side (Decision-4 seam). **Reject** → publish
   `SubstitutionRejectedEvent` (carries `originalIngredientMappingKey`) — provisions skips; the
   planner is notified (it may re-optimise the affected slot, GROC-19 reject variation).
4. **After commit, call `tryReconcile(orderId)`** — if no proposal remains `pending_user_review`/
   `unparsed`, reconciliation runs.

### The reconcile gate + `tryReconcile` (LLD line 913) — internal, `@Transactional`

Runs from `resolveSubstitution`'s commit AND from `markDelivered` (the no-substitution path). Guard:
`countByGroceryOrderIdAndProposalStatusIn(orderId, {PENDING_USER_REVIEW, UNPARSED}) == 0`. If forced
while proposals remain → `OrderHasOutstandingProposalsException` (422, LLD line 762, GROC-21).
On the clear path:
1. `OrderStateMachine.assertCanTransition(DELIVERED, RECONCILED)`.
2. Write **paid-price observations** (`source = PAID`, weight 1.0) for each delivered line via 01c's
   `PriceObservationWriter`; one `PriceObservedEvent` each (LLD line 837 — "the reconciled-event fires
   once after all paid-price rows are written").
3. **Inventory add** via `ProvisionUpdateService.applyGroceryOrder` reflecting the actually-delivered +
   substitution-resolved lines (the single inventory-add trigger per the seam note). `orderRef` =
   the grocery order id (idempotency).
4. Set `paid_total_pence`, `status = RECONCILED`, `reconciled_at`. Publish
   `GroceryOrderReconciledEvent` (once, after all paid rows).

### Substitution-resolve endpoints (LLD lines 720-721)

On the existing `GroceryOrderController` (01e): `GET …/{orderId}/substitutions` →
`List<GrocerySubstitutionProposalDto>` (200/404); `POST …/{orderId}/substitutions/{proposalId}/resolve`
→ `GrocerySubstitutionProposalDto` (200/404/409). The `GroceryOrderMapper` resolves
`outstandingProposals` on `GroceryOrderDto` from a service-supplied list (LLD line 485 — proposals
queried separately, not loaded with the order aggregate).

## Edge-case checklist

- [ ] Provider reports ≥ 1 substitution at delivery → persisted `pending_user_review`, one `SubstitutionProposedEvent` each (GROC-19 precondition)
- [ ] Opaque/DOM-differs payload → `unparsed` + `raw_payload`; user resolves manually (GROC-20)
- [ ] Accept → `ACCEPTED`, `SubstitutionAcceptedEvent` (substitute key) published; provisions adds on its side
- [ ] Reject → `REJECTED`, `SubstitutionRejectedEvent` (original key); planner notified for re-opt
- [ ] `UNPARSED → ACCEPTED|REJECTED` is a legal resolve; `ACCEPTED → anything` is NOT (already resolved → 409)
- [ ] Reconcile blocked while any `pending_user_review`/`unparsed` remains → 422 `order-has-outstanding-proposals` (GROC-21)
- [ ] All proposals resolved → `tryReconcile` runs → `PAID` observations (weight 1.0) + inventory add + `RECONCILED` + `GroceryOrderReconciledEvent` (GROC-18 reconcile leg)
- [ ] No-substitution delivery → `markDelivered` calls `tryReconcile` directly → straight to `RECONCILED`
- [ ] Mixed accept/reject across several proposals → all must resolve before reconcile (GROC-19 mixed variation)
- [ ] `GroceryOrderReconciledEvent` fires exactly ONCE, after all paid rows written
- [ ] Concurrent resolve on the same proposal → `OptimisticLockException` → 409 (proposal `@Version`)
- [ ] Inventory add at reconcile (NOT confirm) — reflects delivered + resolved lines; reject excludes the original
- [ ] Inventory idempotency: re-reconcile (retry) → provisions no-ops on the order-id `orderRef`
- [ ] `GroceryOrderDto.outstandingProposals` populated from a separate query (not the order aggregate load)
- [ ] Controller: 200/404/409/422; OpenAPI shapes match

## Files this ticket touches

```
NEW   src/main/java/com/example/mealprep/grocery/domain/service/internal/SubstitutionPersister.java
NEW   src/main/java/com/example/mealprep/grocery/domain/service/internal/OrderReconciler.java          (tryReconcile + paid observations + inventory add)
NEW   src/main/java/com/example/mealprep/grocery/api/mapper/GrocerySubstitutionProposalMapper.java
NEW   src/main/java/com/example/mealprep/grocery/event/SubstitutionProposedEvent.java + SubstitutionAcceptedEvent.java + SubstitutionRejectedEvent.java
MOD   src/main/java/com/example/mealprep/grocery/domain/service/internal/GroceryServiceImpl.java        (fill resolveSubstitution + getOutstandingProposals; wire SubstitutionPersister into markDelivered; tryReconcile)
MOD   src/main/java/com/example/mealprep/grocery/api/controller/GroceryOrderController.java             (add the two substitution endpoints if 01e didn't)
MOD   src/main/resources/openapi/paths/grocery.yaml + schemas/grocery.yaml + openapi.yaml
NEW   src/test/java/com/example/mealprep/grocery/SubstitutionPersisterTest.java
NEW   src/test/java/com/example/mealprep/grocery/OrderReconciliationIT.java                              (blocked-while-pending; resolve-all → reconcile + PAID + ReconciledEvent)
NEW   src/test/java/com/example/mealprep/grocery/EventPublicationIT.java                                (each tier's events once after commit; sealed lifecycle subtype dispatch)
MOD   src/test/java/com/example/mealprep/grocery/GroceryOrderControllerIT.java                          (extend 01e's lifecycle test through resolve → reconcile)
MOD   src/test/java/com/example/mealprep/grocery/testdata/GroceryTestData.java
```

**Does NOT touch:** provisions production code (the inventory add is via the public
`applyGroceryOrder`; the provisions listener re-point is a **provisions follow-up**). `config/GlobalExceptionHandler.java`.

## Dependencies

- **Hard:** grocery-01a (entities, `GrocerySubstitutionProposal`, `OrderHasOutstandingProposalsException`);
  grocery-01c (`PriceObservationWriter` for PAID observations); grocery-01e (order lifecycle,
  `OrderStateMachine`, `markDelivered` invoking the persister, `GroceryOrderService` interface).
- **Hard (cross-module):** provisions-01h `ProvisionUpdateService.applyGroceryOrder` (inventory add at reconcile).
- **Cross-module follow-up (NOT in this ticket):** re-point the provisions dormant
  `GroceryOrderConfirmedListener` to bind inventory-add to `GroceryOrderReconciledEvent` (not confirm);
  resolve the `SubstitutionAcceptedEvent` naming collision. Both are provisions-side decisions the
  owner signs off.

## Acceptance / DoD

- [ ] `verify` + `spotless` clean; CI green; all edge cases ticked
- [ ] GROC-19 un-pendable (resolve accept/reject applies to inventory via the provisions seam)
- [ ] Reconcile gate enforced (422 while pending); reconcile writes PAID + inventory + ReconciledEvent once
- [ ] Auto-accept structurally impossible (no code path resolves a proposal without a user decision)
- [ ] The two cross-module follow-ups (listener re-point, event naming) reported for owner sign-off
- [ ] `GroceryBoundaryTest` passes; no provisions production file touched

Squash-merge with: `feat(grocery): 01f — substitution review + reconciliation + paid-price observations + provisions event seam`

## What's NOT in scope

- Re-pointing the provisions dormant listener + the `SubstitutionAcceptedEvent` naming fix → **provisions follow-up** (owner sign-off).
- Planner re-opt on reject (GROC-19/GROC-12 "if material" threshold, GG5) → the planner's listener +
  materiality logic (planner-01k); 01f only publishes `SubstitutionRejectedEvent`.
- The 24-hour `provider_unavailable` retry + hourly status poll + archival → **grocery-01g**.
- Real Tesco automation → deferred post-v1 ticket.
