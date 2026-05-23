# Ticket: notification — 01c Wire Prep/Defrost Scanners to Real Slot Meal Times

## Summary

Replace the slot-kind-default time approximations in the `PrepReminderScanner` and `DefrostReminderScanner` with the **real wall-clock meal time** now resolved on `UpcomingSlotView` (shipped by `planner-01m`). Today both scanners approximate:
- `PrepReminderScanner` hard-codes `defaultMealTime()` per slot kind ([`src/main/java/com/example/mealprep/notification/scanner/PrepReminderScanner.java:135-150`](../../src/main/java/com/example/mealprep/notification/scanner/PrepReminderScanner.java)) and derives `prepStepAtTime = mealTime − timeBudgetMin`.
- `DefrostReminderScanner` anchors the defrost target on the provisions `expiryDate` start-of-day ([`src/main/java/com/example/mealprep/notification/scanner/DefrostReminderScanner.java:89`](../../src/main/java/com/example/mealprep/notification/scanner/DefrostReminderScanner.java)) because it has no planner slot meal time to anchor on — the `slotId` it logs is actually the inventory item id ([`DefrostReminderScanner.java:34-37, 94`](../../src/main/java/com/example/mealprep/notification/scanner/DefrostReminderScanner.java)).

Per [`tickets/notification/01b-scanners.md` §Scanner #2-#3](01b-scanners.md), [`design/meal-planner.md` lines 150-189](../../design/meal-planner.md), [`design/system-overview.md`](../../design/system-overview.md) (prep reminders). This ticket is the **consumer half** of the Leaf 3 work; the **source half** (resolving the time) is `planner-01m`.

Numbered `01c` (notification is at 01a–01b; this is the next slot in the same phase).

Closes: the scanner time-approximation gap — prep reminders now fire relative to the user's actual schedule, and (where a frozen item is linked to a planned slot) defrost reminders anchor on the slot's real meal time instead of the inventory expiry date.

## Behavioural spec

### `PrepReminderScanner` — consume `UpcomingSlotView.mealTime`

1. Delete `PrepReminderScanner.defaultMealTime()` ([`PrepReminderScanner.java:141-150`](../../src/main/java/com/example/mealprep/notification/scanner/PrepReminderScanner.java)) — the slot-kind default table now has one home in the planner (`SlotKindDefaultTimes`, `planner-01m`), reachable via the already-resolved `UpcomingSlotView.mealTime`.
2. In `prepMomentFor` ([`PrepReminderScanner.java:135-139`](../../src/main/java/com/example/mealprep/notification/scanner/PrepReminderScanner.java)): use `slot.mealTime()` (the resolved, never-null wall-clock time from `planner-01m`) instead of `defaultMealTime(slot.kind())`. The prep moment stays `mealTime@dayDate − timeBudgetMin`, but `mealTime` is now the user's real schedule.
3. If `planner-01m` also populates `UpcomingSlotView.prepStepAtTime` (the per-slot override, currently always null), prefer it when non-null: `prepMoment = prepStepAtTime ?? (mealTime − timeBudgetMin)`. Until the pre-cook-actions feature populates it, this is always the derived value (no behaviour change beyond the real meal time).
4. Everything else unchanged: every-5-min cron, ±`leadMinutes` fire window, `(slotId, prepStepAtTime)` idempotency, `Clock` injection (reads `clock().getZone()` / `now()`, never `Instant.now()`).

### `DefrostReminderScanner` — anchor on slot meal time when slot-linked

5. **The honest current state**: `DefrostReminderScanner` reads frozen-item candidates from **provisions** ([`DefrostReminderScanner.java:84`](../../src/main/java/com/example/mealprep/notification/scanner/DefrostReminderScanner.java)) and has no planner slot reference — the defrost lead-time + use-by anchor "live in the provisions module" per its javadoc ([`DefrostReminderScanner.java:27-32`](../../src/main/java/com/example/mealprep/notification/scanner/DefrostReminderScanner.java)). So switching its anchor to a slot meal time requires a **link from a frozen inventory item to the planned slot that will consume it**.
6. **DECISION (worth user review)**: does provisions/planner expose a frozen-item → consuming-slot link?
   - **If YES** (a `scheduledRecipe`/`batchCookSession` → inventory link exists): anchor `defrostTarget = consumingSlot.mealTime − defrostLeadTimeHours` and log the real `slotId`. This is the correct behaviour.
   - **If NO** (no such link today — likely, given the provisions-driven candidate query): **keep the `expiryDate` anchor** but document it clearly as the v1 approximation, and scope the slot-linked anchoring as a **follow-up that needs the inventory→slot link first**. Do NOT invent a link in this ticket.
   - **Recommendation**: **NO-path for v1** — keep the provisions `expiryDate` anchor for `DefrostReminderScanner`, and limit THIS ticket's defrost change to (a) documenting the approximation accurately and (b) IF a frozen item is consumed by a planned slot reachable via `getUpcomingSlots`, opportunistically anchoring on that slot's `mealTime`. The prep scanner (which IS slot-driven) gets the full real-time treatment; the defrost scanner's full fix is gated on the inventory→slot link (flag as out of scope + a spawnable follow-up). **This keeps the ticket honest** — don't claim a defrost fix the data model can't yet support.
7. If the recommendation's opportunistic path is taken: for each defrost candidate, attempt to find an upcoming slot consuming it (via `getUpcomingSlots` + the scheduled-recipe ingredient/batch link, IF reachable); when found, anchor on `slot.mealTime`; else fall back to `expiryDate`. **Only implement the opportunistic path if the link is genuinely reachable** — otherwise skip it and keep `expiryDate`.

### Cross-cutting

8. **Cross-module read placement**: `planner-01m` resolves the meal time either in the planner projection OR in the scanner (its §5 design call). `PrepReminderScanner` already injects `PlanQueryService` and `HouseholdQueryService` ([`PrepReminderScanner.java:50-53`](../../src/main/java/com/example/mealprep/notification/scanner/PrepReminderScanner.java)); if `planner-01m` decided to resolve in the scanner, this ticket injects `PreferenceQueryService` (lifestyle config) into the scanner and does the coalesce here instead of reading a pre-resolved `mealTime`. **Verify at agent start which side `planner-01m` resolved on** — the recommendation in 01m is planner-side resolution, so the scanner just reads `slot.mealTime()`.
9. **GOTCHA (read-only scanners)**: per [`tickets/notification/01b-scanners.md:20, 165`](01b-scanners.md), scanners run **read-only** queries against source modules and never write to them — only publish events. The meal-time read is read-only; preserve this (the IT counts writes via Hibernate statistics and asserts zero writes to planner/preference).
10. **No AFTER_COMMIT concern here**: scanners are `@Scheduled` (not event listeners). The `@Transactional` boundary commits the dispatch-log row + publishes the event together ([`PrepReminderScanner.java:73-77`](../../src/main/java/com/example/mealprep/notification/scanner/PrepReminderScanner.java)) — decision-log 0010 does not apply (no AFTER_COMMIT listener in the write path).
11. ArchUnit: scanners stay in `notification.scanner..` extending `ScannerSupport` ([`tickets/notification/01b-scanners.md:121`](01b-scanners.md)); no new repos.

### Events

12. **Published**: `PrepReminderEvent` (unchanged shape, but `prepStepAtTime` now reflects the real meal time), `DefrostReminderEvent` (unchanged). **Consumed**: none.

## Database

```
(none — consumes planner-01m's columns; no new tables. Dispatch-log tables ship in notification-01b.)
```

## OpenAPI updates

**No OpenAPI changes.** Scanners are internal `@Scheduled` components ([`tickets/notification/01b-scanners.md:149`](01b-scanners.md)).

## Edge-case checklist

- [ ] **PrepReminderScanner uses real meal time**: owner lifestyle config `dinner: 19:00-20:00`, DINNER slot with `timeBudgetMin=45` on day D → prep moment = D@19:00 − 45min = 18:15. With mock Clock at 18:10 (within default 15-min lead) → fires; at 17:55 → no fire.
- [ ] **No regression without config**: a user with no lifestyle config → `UpcomingSlotView.mealTime` is the slot-kind default (DINNER 18:00 from `planner-01m`) → prep scanner fires exactly as it did before this ticket.
- [ ] **`defaultMealTime` deleted** from `PrepReminderScanner` (grep confirms gone; the one-home table lives in the planner).
- [ ] **`prepStepAtTime` override**: if a slot has a non-null `prepStepAtTime`, the scanner fires relative to it (not the derived mealTime−budget). Until populated, always derived (no behaviour change beyond real meal time).
- [ ] **Idempotency preserved**: `(slotId, prepStepAtTime)` unique — re-running the scan in the same window is a no-op ([`PrepReminderScanner.java:103`](../../src/main/java/com/example/mealprep/notification/scanner/PrepReminderScanner.java)); the recomputed `prepStepAtTime` (now off the real meal time) is stable across runs so idempotency still holds.
- [ ] **Clock injection**: scanner still reads `now()`/`clock().getZone()`, never `Instant.now()` (grep-verified).
- [ ] **Read-only**: IT asserts zero writes to planner/preference tables during a scan (Hibernate statistics).
- [ ] **DefrostReminderScanner documented**: javadoc accurately states the v1 anchor (provisions `expiryDate`, OR slot mealTime when a reachable link exists) — no false claim of slot-time anchoring if the link doesn't exist.
- [ ] **DefrostReminderScanner opportunistic anchor** (only if the inventory→slot link is reachable): a frozen item consumed by a DINNER slot at 19:00 with `defrostLeadTimeHours=4` → defrost target 15:00; else `expiryDate` fallback unchanged.
- [ ] **DefrostReminderScanner no-link fallback**: a frozen item with no consuming slot → `expiryDate` anchor, identical to today (no regression).
- [ ] **Cross-tenant**: scanners only emit events scoped to the owning user; the lifestyle-config read is for the slot's household owner only.
- [ ] **Empty plan window**: `getUpcomingSlots` returns empty → no prep events, no errors.
- [ ] **Failure isolation**: an exception resolving one user's meal time is caught + logged + the scan continues with the next user ([`PrepReminderScanner.java:126-128`](../../src/main/java/com/example/mealprep/notification/scanner/PrepReminderScanner.java)).
- [ ] **`PrepReminderScannerTest` / `DefrostReminderScannerTest`** updated with `Clock.fixed` + real-meal-time fixtures.

## Files this ticket touches

```
MOD   src/main/java/com/example/mealprep/notification/scanner/PrepReminderScanner.java                          (use slot.mealTime(); delete defaultMealTime)
MOD   src/main/java/com/example/mealprep/notification/scanner/DefrostReminderScanner.java                       (document anchor; opportunistic slot-time anchor if link reachable)

MOD   src/test/java/com/example/mealprep/notification/PrepReminderScannerTest.java                              (real-meal-time fixtures)
MOD   src/test/java/com/example/mealprep/notification/DefrostReminderScannerTest.java
MOD   src/test/java/com/example/mealprep/notification/ScannerIdempotencyIT.java                                 (assert read-only + stable prepStepAtTime)
```

Total: 0 new + 5 mods. Estimated agent runtime 2-3 hours (small — the source resolution lands in `planner-01m`; this ticket just consumes it + updates tests + honestly scopes the defrost case).

## Dependencies

- **Hard dependency**: `planner-01m` (the resolved `UpcomingSlotView.mealTime`). MUST merge first — this ticket is the consumer.
- **Hard dependency**: `notification-01b` (merged) — the scanners, dispatch-log tables, `ScannerSupport`, events.
- **Conditional**: a frozen-inventory → consuming-slot link (for the defrost opportunistic anchor) — if absent, the defrost full-fix is a follow-up.

## Acceptance / DoD

- [ ] `./mvnw -Dspotless.check.skip=true verify` passes
- [ ] `./mvnw spotless:apply` then `./mvnw spotless:check` clean
- [ ] **Run the full notification module IT suite locally with Docker** + spotless before pushing. Docker IS available locally. Run ITs in ~3-class batches (Hikari pool-exhaustion flake on big sweeps).
- [ ] CI green (build + spotless + ArchUnit)
- [ ] All edge-case items above ticked
- [ ] PR description traces: DINNER slot, owner config `dinner: 19:00`, budget 45 → prep reminder fires at 18:15 (vs the old hard-coded 18:00−45=17:15); a no-config user still fires at the 18:00-default-derived moment (no regression).

## What's NOT in scope

- **Resolving the meal time** (the source) — `planner-01m`.
- **The frozen-inventory → consuming-slot link** needed for full defrost slot-anchoring — a follow-up if the link doesn't exist today.
- **`NutritionAlertScanner` / `ExpiryWarningScanner` / `StapleReplenishmentScanner`** — they don't use meal times; untouched.
- **Per-day meal-time overrides** — depends on `planner-01m`'s reserved-but-unused override column being populated, which is its own future feature.

Squash-merge with: `feat(notification): 01c — wire prep/defrost scanners to real slot meal times (replaces slot-kind-default approximations)`
