# Ticket: provisions + feedback — 01i `MARK_DEPLETED` by-mapping-key Inventory Depletion

## Summary

Wire `ProvisionsFeedbackBridgeImpl.markDepleted` ([`src/main/java/com/example/mealprep/feedback/bridge/ProvisionsFeedbackBridgeImpl.java:131-156`](../../src/main/java/com/example/mealprep/feedback/bridge/ProvisionsFeedbackBridgeImpl.java), currently throws `provisions-inventory-lookup-by-key-not-exposed`) to the existing depletion path by adding the **one missing public read surface**: a by-`ingredientMappingKey` inventory lookup on `ProvisionQueryService`. Everything else already exists — this is the smallest of the deferred-gap tickets.

The bridge is fully wired: the `PROVISIONS` feedback path disambiguates on `provisionsAction`, and `REMOVE_EQUIPMENT` is already real ([`ProvisionsFeedbackBridgeImpl.java:92-129`](../../src/main/java/com/example/mealprep/feedback/bridge/ProvisionsFeedbackBridgeImpl.java)). `MARK_DEPLETED` ("I'm out of soy sauce") books a deferred `FAILED` with a structured reason because — per the bridge's own javadoc ([`ProvisionsFeedbackBridgeImpl.java:131-137`](../../src/main/java/com/example/mealprep/feedback/bridge/ProvisionsFeedbackBridgeImpl.java)) — the provisions **write** surface `markExhausted(itemId, actorUserId)` is keyed by item id, and the public provisions **read** surface exposes no lookup by `ingredientMappingKey` ([`ProvisionQueryService`](../../src/main/java/com/example/mealprep/provisions/domain/service/ProvisionQueryService.java) carries only id / search-criteria / scanner reads). The repository query already exists — [`InventoryItemRepository.findActiveByMappingKeyOrderByExpiryAsc(userId, ingredientMappingKey)`, `InventoryItemRepository.java:124-131`](../../src/main/java/com/example/mealprep/provisions/domain/repository/InventoryItemRepository.java) (FIFO-by-expiry, ACTIVE only). Only the **public** service method that surfaces it cross-module is missing.

Ships:
- **`ProvisionQueryService.getActiveInventoryByMappingKey(UUID userId, String ingredientMappingKey)`** (+ impl) — returns the ACTIVE inventory rows for `(userId, key)`, oldest-expiry first.
- **Flip `ProvisionsFeedbackBridgeImpl.markDepleted`** off the deferred-FAILED throw to: look up by key → `markExhausted` the resolved item(s) → record `DISPATCHED`.

Closes: **C5** — the `MARK_DEPLETED` provisions feedback action. **In-scope and NOT the grocery vertical** (audit E4) — this is plain inventory lifecycle, fully owned by the merged provisions module.

**Dependency / ordering**: standalone, smallest. Provisions inventory (`provisions-01a`/`01g`) merged; `feedback-01g` (the bridge) merged.

## Behavioural spec

### The write + repo surfaces already exist

- **Write**: `ProvisionUpdateService.markExhausted(UUID itemId, UUID actorUserId)` — interface [`ProvisionUpdateService.java:70-74`](../../src/main/java/com/example/mealprep/provisions/domain/service/ProvisionUpdateService.java), impl [`ProvisionServiceImpl.java:652-688`](../../src/main/java/com/example/mealprep/provisions/domain/service/internal/ProvisionServiceImpl.java). It flips `itemStatus → EXHAUSTED`, is **idempotent** (already-exhausted rows return unchanged with no audit row / no event), writes an `InventoryAuditLog` (actor `USER`), and publishes `ItemRanOutEvent`.
- **Read (repo)**: `InventoryItemRepository.findActiveByMappingKeyOrderByExpiryAsc(UUID userId, String ingredientMappingKey)` — [`InventoryItemRepository.java:124-131`](../../src/main/java/com/example/mealprep/provisions/domain/repository/InventoryItemRepository.java). Returns ACTIVE rows for the key, `ORDER BY expiry NULLS LAST, expiry ASC`. Used today only by the cook-event/standalone-consumption deduction engine.

### Add the public read surface

1. Add to `ProvisionQueryService` ([`ProvisionQueryService.java`](../../src/main/java/com/example/mealprep/provisions/domain/service/ProvisionQueryService.java)):
   ```java
   /**
    * ACTIVE inventory rows for {@code (userId, ingredientMappingKey)}, oldest-expiry first
    * (NULLS LAST). Read-only cross-module helper for the feedback module's MARK_DEPLETED bridge
    * ("I'm out of soy sauce"). Empty list when the user has no active rows for that key. No HTTP
    * exposure.
    */
   List<InventoryItemDto> getActiveInventoryByMappingKey(UUID userId, String ingredientMappingKey);
   ```
   Implement on `ProvisionServiceImpl` (the single impl of both query + update interfaces — verify; mirror the existing read methods like `getDefrostCandidates`): delegate to `findActiveByMappingKeyOrderByExpiryAsc`, map via the existing `mapper.toDto`, return the list. `@Transactional(readOnly = true)`.
   **Naming**: keep it consistent with the existing scanner reads (`getDefrostCandidates`, `getStaplesNeedingReplenishment` — [`ProvisionQueryService.java:55-68`](../../src/main/java/com/example/mealprep/provisions/domain/service/ProvisionQueryService.java)). `getActiveInventoryByMappingKey` matches that house style. **Worth implementer review**: a `List` vs an `Optional<InventoryItemDto>` "single best row" — see §3.

### Flip the bridge

2. In `ProvisionsFeedbackBridgeImpl.markDepleted` ([`ProvisionsFeedbackBridgeImpl.java:138-156`](../../src/main/java/com/example/mealprep/feedback/bridge/ProvisionsFeedbackBridgeImpl.java)): remove the deferred-FAILED throw; inject `ProvisionQueryService` alongside the existing `ProvisionUpdateService` ([`ProvisionsFeedbackBridgeImpl.java:47-57`](../../src/main/java/com/example/mealprep/feedback/bridge/ProvisionsFeedbackBridgeImpl.java)); implement:
   - `String ingredientKey = textOrNull(input.structuredPayload(), "ingredientMappingKey")` (already read at [`ProvisionsFeedbackBridgeImpl.java:139`](../../src/main/java/com/example/mealprep/feedback/bridge/ProvisionsFeedbackBridgeImpl.java)). If null/blank → `throw failed(... new IllegalArgumentException("missing-ingredient-mapping-key"))` (mirror the equipment-name guard at [`ProvisionsFeedbackBridgeImpl.java:94-102`](../../src/main/java/com/example/mealprep/feedback/bridge/ProvisionsFeedbackBridgeImpl.java)).
   - `List<InventoryItemDto> rows = provisionQueryService.getActiveInventoryByMappingKey(input.userId(), ingredientKey)`.
   - **If empty** → idempotent no-op success (mirror the `EquipmentNotFoundException` "already absent → DISPATCHED" branch at [`ProvisionsFeedbackBridgeImpl.java:117-128`](../../src/main/java/com/example/mealprep/feedback/bridge/ProvisionsFeedbackBridgeImpl.java)): the user being "out" of something they don't track is not a failure. `recordOutcome(..., DISPATCHED)`, return a "nothing-to-deplete" `Result`.
   - **If present** → call `markExhausted` (see §3 for how many rows), `recordOutcome(..., DISPATCHED)`, return a DISPATCHED `Result` with the origin trace (mirror the equipment-removed `Result` at [`ProvisionsFeedbackBridgeImpl.java:114-116`](../../src/main/java/com/example/mealprep/feedback/bridge/ProvisionsFeedbackBridgeImpl.java)).
3. **How many rows to exhaust (worth user review)** — "I'm out of soy sauce" means the user has none left:
   - **Recommendation: exhaust ALL active rows for the key.** "Out of soy sauce" means zero remaining — if there are two open bottles tracked, both are now empty. Loop `markExhausted(row.id(), input.userId())` over every returned row; each is idempotent. This matches the user's real-world claim better than exhausting only the oldest.
   - The alternative (exhaust only the oldest-expiry row, since the query is FIFO-ordered) would leave phantom stock. Avoid it.
   - **`actorUserId`**: pass `input.userId()` to `markExhausted` (the feedback originated from the user). The bridge already records `actorType()` / `originTrace()` in its structured log + `Result` map ([`ProvisionsFeedbackBridgeImpl.java:106-116`](../../src/main/java/com/example/mealprep/feedback/bridge/ProvisionsFeedbackBridgeImpl.java)) — keep that for origin attribution. **Note**: `markExhausted` writes its audit row with `AuditActor.USER` ([`ProvisionServiceImpl.java:671`](../../src/main/java/com/example/mealprep/provisions/domain/service/internal/ProvisionServiceImpl.java)); the AI-origin provenance lives in the bridge's idempotency/log layer, not the inventory audit. If the inventory audit must carry the AI origin trace, that is a larger `markExhausted` signature change — **out of scope; flag if the user wants AI attribution on the inventory audit row** (the equipment-removal path has the same property today, so this is consistent, not a regression).

### Transaction phase (GOTCHA, decision-log 0010)

4. The bridge fires from an AFTER_COMMIT listener and runs under `@Qualifier(REQUIRES_NEW_TX_TEMPLATE) TransactionTemplate` ([`ProvisionsFeedbackBridgeImpl.java:52-55`](../../src/main/java/com/example/mealprep/feedback/bridge/ProvisionsFeedbackBridgeImpl.java) via `FeedbackBridgeSupport`). `markExhausted` keeps its own `@Transactional` (REQUIRED, [`ProvisionServiceImpl.java:653`](../../src/main/java/com/example/mealprep/provisions/domain/service/internal/ProvisionServiceImpl.java)) so it **joins** the bridge's REQUIRES_NEW template tx — the inventory flip + audit + `ItemRanOutEvent` and the bridge's `DISPATCHED` idempotency row commit together. Do NOT add `REQUIRES_NEW` anywhere. The read (`getActiveInventoryByMappingKey`) runs read-only in the same tx.

### Cross-cutting

5. **No new exception** — empty-result is success (no-op), missing-key reuses the existing `failed(...)` path; unsupported actions are already handled at [`ProvisionsFeedbackBridgeImpl.java:158-171`](../../src/main/java/com/example/mealprep/feedback/bridge/ProvisionsFeedbackBridgeImpl.java).
6. **No REST surface, no OpenAPI** — `getActiveInventoryByMappingKey` is an internal cross-module read (like the other scanner reads); the bridge is an event listener.
7. **ArchUnit**: the new query method stays on `ProvisionQueryService` (public read API); the bridge's cross-module call goes through the public interface, satisfying `ProvisionsBoundaryTest` (the bridge already injects `ProvisionUpdateService` legitimately at [`ProvisionsFeedbackBridgeImpl.java:47`](../../src/main/java/com/example/mealprep/feedback/bridge/ProvisionsFeedbackBridgeImpl.java)). No repository reach-through.
8. **Update the bridge javadoc** ([`ProvisionsFeedbackBridgeImpl.java:18-38`](../../src/main/java/com/example/mealprep/feedback/bridge/ProvisionsFeedbackBridgeImpl.java)) — remove the "wired-but-deferred / by-key lookup not exposed" note; document the real depletion path.

### Events

9. **Published**: `ItemRanOutEvent` (one per exhausted row, from `markExhausted` — [`ProvisionServiceImpl.java:678-685`](../../src/main/java/com/example/mealprep/provisions/domain/service/internal/ProvisionServiceImpl.java)). **Consumed**: none new (the bridge consumes `FeedbackProcessedEvent` already).

## Database

```
(none — no schema changes. The repo query and the EXHAUSTED lifecycle status already exist.)
```

## OpenAPI updates

**No OpenAPI changes.** `getActiveInventoryByMappingKey` is an internal cross-module read with no HTTP exposure (mirrors `getDefrostCandidates` / `getStaplesNeedingReplenishment`).

## Edge-case checklist

- [ ] **Happy path single row**: user has one ACTIVE soy_sauce row → `MARK_DEPLETED` feedback → bridge looks up by key → `markExhausted` → row `EXHAUSTED`, `ItemRanOutEvent` published, bridge `DISPATCHED`.
- [ ] **Multiple rows exhausted**: user has two ACTIVE soy_sauce rows → both flipped `EXHAUSTED` (recommendation §3); two `ItemRanOutEvent`s (or per the event's coalescing semantics — verify); bridge `DISPATCHED`.
- [ ] **No active rows (idempotent no-op)**: user has no ACTIVE rows for the key → bridge records `DISPATCHED` with a "nothing-to-deplete" reason; no exception, no audit, no event (mirrors equipment-already-absent).
- [ ] **Already exhausted**: re-firing the same `MARK_DEPLETED` → `markExhausted` is idempotent (already-EXHAUSTED returns unchanged, no audit/event); the bridge's own idempotency window also short-circuits a same-`feedback_id` replay.
- [ ] **Missing key**: payload `{provisionsAction: MARK_DEPLETED}` with no `ingredientMappingKey` → bridge books FAILED, reason `missing-ingredient-mapping-key` (mirrors the equipment-name guard); no throw escapes the AFTER_COMMIT listener.
- [ ] **Bridge low confidence (< 0.5)**: skipped → `REJECTED_LOW_CONFIDENCE` (unchanged, [`ProvisionsFeedbackBridgeImpl.java:61-72`](../../src/main/java/com/example/mealprep/feedback/bridge/ProvisionsFeedbackBridgeImpl.java)).
- [ ] **Bridge idempotency**: two `FeedbackProcessedEvent`s with the same `feedback_id` within the window → second is a no-op (unchanged, [`ProvisionsFeedbackBridgeImpl.java:73-80`](../../src/main/java/com/example/mealprep/feedback/bridge/ProvisionsFeedbackBridgeImpl.java)).
- [ ] **AFTER_COMMIT atomicity (decision-log 0010)**: invoked via the bridge's REQUIRES_NEW template, `markExhausted` (plain `@Transactional`, joins) commits the inventory flip + audit + `ItemRanOutEvent` together with the bridge's `DISPATCHED` row. IT publishes a `FeedbackProcessedEvent(PROVISIONS, {provisionsAction: MARK_DEPLETED, ingredientMappingKey: soy_sauce})` and asserts all present after commit.
- [ ] **Soft-deleted/EXHAUSTED/SPOILED rows ignored**: the query is `itemStatus = ACTIVE` only — a SPOILED soy_sauce row is not re-touched.
- [ ] **Cross-tenant**: the query is scoped to `input.userId()`; user A's `MARK_DEPLETED` never exhausts user B's stock.
- [ ] **Deferred reason removed**: grep confirms `provisions-inventory-lookup-by-key-not-exposed` is gone from `ProvisionsFeedbackBridgeImpl`.
- [ ] **`unsupportedAction` untouched**: a non-MARK_DEPLETED/non-REMOVE_EQUIPMENT action still books FAILED `unsupported-provisions-action` ([`ProvisionsFeedbackBridgeImpl.java:158-171`](../../src/main/java/com/example/mealprep/feedback/bridge/ProvisionsFeedbackBridgeImpl.java)).

## Files this ticket touches

```
MOD   src/main/java/com/example/mealprep/provisions/domain/service/ProvisionQueryService.java                     (add getActiveInventoryByMappingKey)
MOD   src/main/java/com/example/mealprep/provisions/domain/service/internal/ProvisionServiceImpl.java              (impl — delegate to findActiveByMappingKeyOrderByExpiryAsc + mapper)

MOD   src/main/java/com/example/mealprep/feedback/bridge/ProvisionsFeedbackBridgeImpl.java                        (inject ProvisionQueryService; flip markDepleted to real lookup+exhaust; update javadoc; drop deferred reason)

MOD   src/test/java/com/example/mealprep/provisions/InventoryFlowIT.java                                          (or a focused new IT — assert getActiveInventoryByMappingKey returns ACTIVE rows oldest-first)
MOD   src/test/java/com/example/mealprep/feedback/ProvisionsFeedbackBridgeTest.java                               (replace deferred-FAILED assertions with DISPATCHED; multi-row, no-row, missing-key)
NEW   src/test/java/com/example/mealprep/feedback/ProvisionsFeedbackBridgeDepletesIT.java                         (Testcontainers — FeedbackProcessedEvent → bridge → markExhausted, AFTER_COMMIT atomicity)
```

Total: 0 new prod + 3 mods (+ tests). Estimated agent runtime 0.5-1 day (smallest ticket — one read method + a bridge-method flip; the multi-row decision + the AFTER_COMMIT IT are the only judgement calls).

## Dependencies

- **Hard dependency**: `provisions-01a`/`01g` (merged) — `InventoryItem`, `ItemLifecycleStatus.EXHAUSTED`, `markExhausted`, `findActiveByMappingKeyOrderByExpiryAsc`, `ItemRanOutEvent`, `ProvisionQueryService`/`ProvisionServiceImpl`, `InventoryItemDto` + mapper.
- **Hard dependency**: `feedback-01g` (merged) — `ProvisionsFeedbackBridgeImpl`, `FeedbackBridgeSupport`, `BridgeDispatchStatus`, `REQUIRES_NEW_TX_TEMPLATE`.

## Acceptance / DoD

- [ ] `./mvnw -Dspotless.check.skip=true verify` passes
- [ ] `./mvnw spotless:apply` then `./mvnw spotless:check` clean
- [ ] **Run the full provisions + feedback module IT suites locally with Docker** + spotless before pushing. Docker IS available locally. Run ITs in ~3-class batches (Hikari pool-exhaustion flake on big sweeps).
- [ ] CI green (build + spotless + ArchUnit + contract tests)
- [ ] All edge-case items above ticked
- [ ] PR description traces: `FeedbackProcessedEvent(PROVISIONS, MARK_DEPLETED soy_sauce)` → bridge looks up active soy_sauce rows → `markExhausted` each → `EXHAUSTED` + `ItemRanOutEvent` + bridge `DISPATCHED`, all atomic.

## What's NOT in scope

- **AI origin attribution on the inventory audit row** (`markExhausted` writes `AuditActor.USER`) — a larger signature change; consistent with the existing equipment-removal path, flag separately if AI provenance on inventory audits is wanted.
- **The grocery / shopping-list / order-history vertical** (audit E4) — explicitly out; this is plain inventory lifecycle.
- **A quantity-aware partial depletion** ("I used half the soy sauce") — `MARK_DEPLETED` is a full exhaust; partial decrements go through the cook-event/standalone-consumption flows, not feedback.
- **`ADJUST_BUDGET` / other reserved `provisionsAction` values** — still book FAILED `unsupported-provisions-action`.
- **Reverting a feedback-driven depletion** (correction undo) — covered by the provisions reverter in `feedback/01h` (which recommends log-only for inventory status, since there is no clean un-exhaust).

Squash-merge with: `feat(provisions,feedback): 01i — MARK_DEPLETED by-mapping-key inventory depletion (expose by-key lookup; wire bridge to markExhausted)`
