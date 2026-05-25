# Ticket: grocery — 01e GroceryProvider SPI + FakeGroceryProvider + Order Lifecycle State Machine

## Summary

Implement **Tier 3 core**: the `GroceryProvider` SPI, the deterministic `FakeGroceryProvider`, the
order state machine, and the order-lifecycle service methods (draft → quoted → placed →
awaiting-confirmation → confirmed → delivered) plus cancel and provider-connection management. Per
[LLD §`GroceryProvider` lines 645-674](../../lld/grocery.md),
[LLD §`GroceryOrderService` lines 591-617](../../lld/grocery.md),
[LLD §Flow 4 (order lifecycle) lines 901-924](../../lld/grocery.md),
[LLD §Flow 8 (provider connection) lines 959-966](../../lld/grocery.md),
[LLD §Order state machine lines 780-796](../../lld/grocery.md),
[LLD §Order REST lines 707-725](../../lld/grocery.md). Ships:

- **`GroceryProvider` SPI** + supporting records (`BasketDraft`, `QuoteResult`, `PlaceOrderResult`,
  `OrderStatus`, `SubstitutionProposal`, exceptions) — LLD lines 650-666.
- **`FakeGroceryProvider`** — deterministic, test-scoped, with an injectable failure-mode toggle;
  **its quote prices DERIVE from 01c's `ReferencePriceSource`** (realistic, not arbitrary).
- **`OrderStateMachine`** — the legal-edges table (LLD lines 783-794), `assertCanTransition`.
- **`BasketDraftAssembler`** — builds `BasketDraft` from a shopping list + lifestyle preferences.
- **`GroceryOrderService` lifecycle methods** — `createDraft`, `quote`, `placeOrder`,
  `markUserConfirmed`, `refreshStatus`, `markDelivered`, `cancel`, plus
  `getProviderState`/`upsertProviderConnection`. (Substitution resolution + reconciliation → 01f.)
- **`GroceryOrderController`** + the order endpoints (LLD lines 709-723; the substitution-resolve +
  provider-state subset that 01f doesn't own).
- **Order-lifecycle events** — the sealed `GroceryOrderLifecycleEvent` family + per-event records +
  `GroceryProviderUnavailableEvent` (LLD lines 808-830).
- **Single-flight** via `core.LockService` on `quote`/`place`/`refreshStatus` (LLD line 905, 978).

**Unblocks (E2E):** **GROC-15** (quote), **GROC-16** (place → awaiting confirmation), **GROC-17**
(confirm), **GROC-18** (delivered → reconcile leg — reconcile itself completes in 01f) — the
`@pending` scenario "A user places, confirms, and reconciles a provider order"
([`grocery.feature` lines 77-85](../../src/e2e/resources/features/grocery/grocery.feature)). Also the
provider legs of **XJ-05** / GROC-36 (driven against the `FakeGroceryProvider`, since real Tesco
automation is deferred).

## Decision baked in — GroceryProvider SPI + deterministic Fake; real Tesco DEFERRED

**Product-owner decision (settled):** Tier 3 ships the **`GroceryProvider` SPI + a deterministic
`FakeGroceryProvider`**. **Real Tesco browser automation is a DEFERRED post-v1 ticket** — this
ticket does NOT write `TescoGroceryProvider`. Per [LLD lines 674, 1075-1076](../../lld/grocery.md):
DOM selectors, login flow, retry policy, anti-bot measures, and the AI-navigator prompts all live in
implementation-phase technical guidance / the AI prompt registry, NOT here. The LLD specifies only
the contract + the state-machine integration.

The `FakeGroceryProvider`'s quote prices **DERIVE from the `ReferencePriceSource`** (01c) — so a
quote against the fake returns realistic numbers (the reference per-mapping-key estimate × pack
count) rather than arbitrary constants. This makes GROC-15's "prices refresh, quote rows feed Tier 4"
assertion meaningful and keeps the E2E deterministic.

**Worth user review** — the fake is the ONLY provider in v1. There is no real fulfilment; the
flagship XJ-05 provider legs are exercised against the fake. The owner should confirm v1 ships with
no real supermarket integration (the manual path remains the real-world arm).

## LLD-divergence notes

### `EncryptedJsonConverter` on `session_state` — still a stub (from 01a)

The fake provider holds no real cookies; `grocery_provider_state.session_state` stays plaintext JSONB
with the 01a `// TODO(grocery-crypto-followup)` marker. The encryption converter is a **hard
prerequisite for the deferred Tesco ticket**, not v1. **Worth user review** (carried from 01a).

### `ProviderUnavailableException` / `ProviderPartialFailureException` are CHECKED exceptions

LLD line 672 makes both checked (the calling service catches + surfaces, never retries blindly). Ship
them as `extends Exception` (checked) so the compiler forces the catch at every provider call site.
`ProviderPartialFailureException` maps to a **200 body** (LLD line 755/764), not an error — the
"fail-forward" contract: a partial place is a successful-but-flagged outcome.

### `AiUnavailableException` reverts to DRAFT (not a new state)

LLD line 908/924: when the AI navigator hits the cost cap during `quote`/`place`, the order reverts
to `DRAFT` with reason "AI cost cap reached" and returns 503. There is no dedicated state — the
graceful-degrade contract is "fall back to the printable list." For v1 the fake's failure-mode toggle
can raise `AiUnavailableException` to exercise this path. The real cost-cap wiring (the `ai` module's
`AiCostBudgetService`) is consulted in 01g's scheduled path; here the on-demand path just catches and
reverts.

## Behavioural spec

### `GroceryProvider` SPI (LLD lines 650-666)

```java
public interface GroceryProvider {
  String providerKey();
  QuoteResult quote(BasketDraft draft) throws ProviderUnavailableException;
  PlaceOrderResult placeOrder(BasketDraft draft) throws ProviderUnavailableException, ProviderPartialFailureException;
  OrderStatus checkStatus(String providerOrderId) throws ProviderUnavailableException;
  void cancel(String providerOrderId) throws ProviderUnavailableException;
}
```

Supporting records **verbatim from LLD lines 660-666** (`BasketDraft`, `BasketDraftLine`,
`BasketDraftPreferences`, `QuoteResult`, `QuoteLineResult`, `PlaceOrderResult`, `OrderStatus`,
`SubstitutionProposal`). Lives in `domain/service/internal/providers`; the **interface is public**
(re-exported via the facade) but **impls are package-private** and the `GroceryBoundaryTest` rule
(01a) restricts impls to that pocket.

**Three structural rules (LLD lines 668-672):** (1) `placeOrder` drives to checkout and STOPS —
never confirms; returns the `confirmLink`. (2) session state round-trips through
`grocery_provider_state` on every call. (3) the two checked exceptions are caught + surfaced, never
blindly retried.

### `FakeGroceryProvider` (LLD line 1045)

Test-scoped `@Component` (or registered via a test `@Configuration`). Deterministic:
`providerKey() = "fake"`; `quote` returns per-line `QuoteLineResult` priced from
`ReferencePriceSource.referencePrice(key) × packCount` (an injectable seam so tests can override);
`placeOrder` returns a `confirmLink` + per-line `ADDED` statuses; `checkStatus` returns a canned
`OrderStatus`. An **injectable failure-mode toggle** raises `ProviderUnavailableException` /
`ProviderPartialFailureException` / `AiUnavailableException` for negative-path tests (GROC-22/25/27/28).
Lives in the providers pocket per the ArchUnit rule.

### `OrderStateMachine` (LLD lines 783-794)

`assertCanTransition(GroceryOrderStatus current, GroceryOrderStatus target)` throws
`IllegalOrderTransitionException` (409) on an illegal edge. The legal-edges table **verbatim**:

```
draft           → quoted, cancelled
quoted          → placed, placed_partial, draft, cancelled
placed          → awaiting_user_confirmation, cancelled, provider_unavailable
placed_partial  → awaiting_user_confirmation, cancelled
awaiting_user_confirmation → confirmed, cancelled
confirmed       → delivered, cancelled
delivered       → reconciled, cancelled
reconciled      → archived
provider_unavailable → draft, cancelled
cancelled       → (terminal)
archived        → (terminal)
```

Test (LLD line 1016): every legal transition succeeds; a sample of illegal ones throws; every
non-terminal status has ≥ 1 outgoing edge (graph-completeness assertion).

### Lifecycle methods (LLD lines 901-924) — each `@Transactional`, sequential

**Single-flight:** `quote`/`place`/`refreshStatus` acquire `core.LockService.tryAcquire(scope, ttl)`
keyed on `hash(userId, shoppingListId)`, TTL = `GroceryConfig.order.singleFlightLockTtlSeconds`
(300). Failed acquire → `OrderConcurrencyConflictException` (409). The lock holds across the provider
call AND the transaction (LLD line 980 — AI calls happen outside the tx; the lock spans both).

1. **`createDraft`** (`DRAFT`) — validate the shopping list + provider config
   (`ProviderNotConfiguredException` 422 if no/disabled provider state); deep-copy lines from the
   list (the order is the snapshot; the list may regenerate).
2. **`quote`** (`DRAFT → QUOTED`) — lock; load `GroceryProviderState` (404 if missing); build
   `BasketDraft` via `BasketDraftAssembler`; `provider.quote(draft)`. On `ProviderUnavailableException`
   → `PROVIDER_UNAVAILABLE` + `GroceryProviderUnavailableEvent` + 503. On `AiUnavailableException`
   → revert to `DRAFT` + reason + 503. On success: write `provider_order_id`, per-line quoted
   prices, **one `QUOTE` `PriceObservation` per line** (weight 0.85) via 01c's writer; publish
   `GroceryOrderQuotedEvent` + per-line `PriceObservedEvent`.
3. **`placeOrder`** (`QUOTED → PLACED | PLACED_PARTIAL`) — lock; `provider.placeOrder(draft)`;
   persist `confirm_link`, per-line `OrderLineStatus`, `automation_failure_log`. **Auto-advance to
   `AWAITING_USER_CONFIRMATION`.** On `ProviderPartialFailureException` → `PLACED_PARTIAL` + persist
   the added lines + `confirm_link` (200 body, not error). The user always confirms in the provider
   UI — automation never auto-confirms. Publish `GroceryOrderPlacedEvent`.
4. **`markUserConfirmed`** (`AWAITING_USER_CONFIRMATION → CONFIRMED`) — optionally fetch the
   confirmed total via `provider.checkStatus`; publish **`GroceryOrderConfirmedEvent`** — **the event
   provisions consumes** (the dormant `GroceryOrderConfirmedListener` shipped in provisions-01h,
   `@ConditionalOnClass` — see the contract note below).
5. **`refreshStatus` / `markDelivered`** (`CONFIRMED → DELIVERED`) — `refreshStatus` wraps
   `provider.checkStatus` and applies lifecycle implications; `markDelivered` advances to `DELIVERED`,
   persists substitution proposals as `pending_user_review` (the persistence is 01f's
   `SubstitutionPersister`, invoked here), publishes one `SubstitutionProposedEvent` per +
   `GroceryOrderDeliveredEvent`. Both manual ("it arrived") and the hourly scheduled poll (01g) call
   `markDelivered`.
6. **`cancel`** (any state → `CANCELLED`, until `reconciled`) — `provider.cancel` if a
   `provider_order_id` exists; publish `GroceryOrderCancelledEvent`. Cancel after `reconciled`/
   `archived` → `IllegalOrderTransitionException` (409, GROC-24).

### Failure-forward matrix (LLD lines 917-924)

Login expired → stays `DRAFT`, `consecutive_failures++`, user re-auths (GROC-25). Partial place →
`PLACED_PARTIAL` + confirm_link (GROC-22). Provider down → `PROVIDER_UNAVAILABLE`; the 24-hour
hourly-retry-then-auto-cancel is **01g's scheduled concern** (01e sets the state + event; 01g runs
the retry sweep). AI cost cap → revert `DRAFT` + printable-list fallback (GROC-28).

### Provider connection (Flow 8, LLD lines 959-966)

`upsertProviderConnection` creates/updates `grocery_provider_state` (enable/disable, toggle
scheduled refresh + curated-set size, reset the failure counter). `getProviderState` reads it. The
actual login/cookie-establishing flow is **out of scope** (LLD line 966) — it lives in the deferred
provider impl; v1's fake needs no real session.

### `GroceryOrderConfirmedEvent` contract with the dormant provisions listener (Decision-4 seam)

Provisions-01h shipped a **dormant** `GroceryOrderConfirmedListener`
(`@ConditionalOnClass(name = "com.example.mealprep.grocery.event.GroceryOrderConfirmedEvent")`) whose
body throws `UnsupportedOperationException` until the grocery module ships, and which calls a
**future grocery-side query service** to fetch the order detail and build a
`GroceryOrderImportCommand`. **01e ships the `GroceryOrderConfirmedEvent` record** (the publisher owns
the record), which activates that listener's `@ConditionalOnClass`. **Worth user review — the
listener's order-fetch contract:** the provisions listener (01h step 16) expects to fetch the order
via a grocery query service to build the import command. 01e must either (a) expose
`GroceryOrderService.getById` (already in the interface) for the listener to read the order +
reconcile lines into a `GroceryOrderImportCommand`, or (b) carry enough detail on
`GroceryOrderConfirmedEvent` for the listener to build the command without a callback. **The cleaner
seam is (a)** — but the inventory-add actually happens at **reconciliation** (01f), not at
confirmation, per the LLD's Flow 4/Flow 7 (paid-price observations + inventory writes happen during
reconcile). **This is the load-bearing ambiguity to settle in 01f** (see 01f's "confirmation vs
reconciliation" note). 01e's job is just to publish `GroceryOrderConfirmedEvent`; whether provisions
adds inventory on confirm or on reconcile is decided in 01f.

## Edge-case checklist

- [ ] `createDraft` with no provider state → 422 `provider-not-configured` (GROC-14)
- [ ] `createDraft` clones lines (deep copy) — regenerating the list doesn't mutate the order
- [ ] `quote` happy → `QUOTED`, `provider_order_id` set, one `QUOTE` observation per line (weight 0.85), `GroceryOrderQuotedEvent` + per-line `PriceObservedEvent` (GROC-15)
- [ ] Fake quote prices derive from `ReferencePriceSource` (not arbitrary)
- [ ] `quote` + `ProviderUnavailableException` → `PROVIDER_UNAVAILABLE` + event + 503 (GROC-27)
- [ ] `quote` + `AiUnavailableException` → revert `DRAFT` + 503 (GROC-28)
- [ ] `placeOrder` happy → `PLACED` → auto-advance `AWAITING_USER_CONFIRMATION`, `confirm_link` set, `GroceryOrderPlacedEvent` (GROC-16); never auto-confirms
- [ ] `placeOrder` + `ProviderPartialFailureException` → `PLACED_PARTIAL`, added lines persisted, confirm_link, **200 body** (GROC-22)
- [ ] `markUserConfirmed` → `CONFIRMED`, `GroceryOrderConfirmedEvent` published (GROC-17)
- [ ] `markDelivered` → `DELIVERED`, proposals persisted `pending_user_review`, `SubstitutionProposedEvent` per + `GroceryOrderDeliveredEvent` (GROC-18 leg)
- [ ] `cancel` from each state up to `delivered` → `CANCELLED` + event (GROC-23)
- [ ] `cancel` after `reconciled`/`archived` → 409 illegal transition (GROC-24)
- [ ] `OrderStateMachine`: every legal edge passes; illegal sample throws 409; every non-terminal has an outgoing edge
- [ ] Single-flight: two concurrent `quote` on same `(userId, shoppingListId)` → one wins, other 409 `order-concurrency-conflict`; after release the loser can re-attempt
- [ ] `BasketDraftAssembler`: lines 1:1; preferences from lifestyle config; preferred SKU from last-paid `(householdId, key)`
- [ ] `GroceryOrderConfirmedEvent` record present → the provisions dormant listener's `@ConditionalOnClass` activates (provisions ContextLoad IT now registers the listener — note the cross-module impact)
- [ ] Sealed `GroceryOrderLifecycleEvent` — listeners receive subtype-specific records
- [ ] Controller: 200/201/404/409/422/503; OpenAPI shapes match

## Files this ticket touches

```
NEW   src/main/java/com/example/mealprep/grocery/domain/service/internal/providers/GroceryProvider.java   (public SPI, re-exported)
NEW   src/main/java/com/example/mealprep/grocery/domain/service/internal/providers/*.java                 (BasketDraft/QuoteResult/PlaceOrderResult/OrderStatus/SubstitutionProposal records + 2 checked exceptions)
NEW   src/main/java/com/example/mealprep/grocery/domain/service/internal/OrderStateMachine.java
NEW   src/main/java/com/example/mealprep/grocery/domain/service/internal/BasketDraftAssembler.java
NEW   src/main/java/com/example/mealprep/grocery/api/controller/GroceryOrderController.java
NEW   src/main/java/com/example/mealprep/grocery/api/mapper/GroceryOrderMapper.java + GroceryOrderLineMapper.java
NEW   src/main/java/com/example/mealprep/grocery/event/GroceryOrderLifecycleEvent.java (sealed) + 6 records + GroceryOrderConfirmedEvent + GroceryProviderUnavailableEvent
MOD   src/main/java/com/example/mealprep/grocery/domain/service/internal/GroceryServiceImpl.java           (fill GroceryOrderService lifecycle bodies; substitution-resolve/reconcile → 01f)
MOD   src/main/resources/openapi/paths/grocery.yaml + schemas/grocery.yaml + openapi.yaml
NEW   src/test/java/com/example/mealprep/grocery/internal/FakeGroceryProvider.java                         (test-scoped, in the providers pocket)
NEW   src/test/java/com/example/mealprep/grocery/OrderStateMachineTest.java
NEW   src/test/java/com/example/mealprep/grocery/BasketDraftAssemblerTest.java
NEW   src/test/java/com/example/mealprep/grocery/GroceryOrderControllerIT.java                             (lifecycle through markDelivered; reconcile leg → 01f)
NEW   src/test/java/com/example/mealprep/grocery/OrderConcurrencyIT.java
MOD   src/test/java/com/example/mealprep/grocery/testdata/GroceryTestData.java
```

**Does NOT touch:** `TescoGroceryProvider` (deferred); provisions production code (the dormant
listener is provisions-owned and activates via `@ConditionalOnClass` simply because this ticket adds
the event record to the classpath — no provisions edit). **Cross-module impact to flag:** adding
`GroceryOrderConfirmedEvent` activates the provisions dormant listener — provisions' ContextLoad IT
that asserts "listener NOT registered" (provisions-01h edge-case) will now register it; that test
needs updating in a **provisions follow-up**, OR it's already guarded against the grocery module's
arrival. Verify + report.

## Dependencies

- **Hard:** grocery-01a (entities, interfaces, `GroceryProviderState`, exceptions, boundary rule);
  grocery-01b (shopping lists to draft from); grocery-01c (`ReferencePriceSource` for fake quotes +
  `PriceObservationWriter` for quote observations); `core.LockService` (single-flight).
- **Soft:** grocery-01f — substitution persistence + reconciliation (01e invokes
  `SubstitutionPersister` from `markDelivered`; sequence 01f's persister or stub it).
- **Cross-module seam:** provisions-01h dormant `GroceryOrderConfirmedListener` activates on this
  event record.
- **Deferred (NOT a dependency):** real `TescoGroceryProvider`, `ai` cost-cap wiring (01g).

## Acceptance / DoD

- [ ] `verify` + `spotless` clean; CI green; all edge cases ticked
- [ ] GROC-15/16/17 + the GROC-18 delivered leg exercisable against `FakeGroceryProvider`
- [ ] `FakeGroceryProvider` lives in the providers pocket (`GroceryBoundaryTest` rule no longer vacuous)
- [ ] Single-flight + state-machine ITs green; checked exceptions force catches at every provider call
- [ ] `GroceryOrderConfirmedEvent` published on confirm; cross-module listener-activation impact reported

Squash-merge with: `feat(grocery): 01e — GroceryProvider SPI + FakeGroceryProvider + order state machine + lifecycle (draft→delivered)`

## What's NOT in scope

- **Real Tesco browser automation** (`TescoGroceryProvider`, DOM selectors, login flow, retry/anti-bot,
  AI-navigator prompts) → **DEFERRED post-v1 ticket**.
- Substitution resolution + reconciliation (`resolveSubstitution`, `tryReconcile`, `SubstitutionPersister`,
  paid-price observations) → **grocery-01f**.
- The 24-hour `provider_unavailable` retry sweep + hourly status poll + 12-month archival → **grocery-01g**.
- AI cost-cap pre-flight (`AiCostBudgetService`) → **grocery-01g** (on-demand path here just catches `AiUnavailableException`).
- `EncryptedJsonConverter` for `session_state` → deferred (prerequisite for the real Tesco ticket).
