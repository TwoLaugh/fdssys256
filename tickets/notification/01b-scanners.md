# Ticket: notification — 01b Scheduled Scanners (expiry, defrost, prep, nutrition, replenishment)

## Summary

Add the **scheduled scanners** that produce notifications from time-based triggers, complementing the event-driven listeners shipped in `tickets/notification/01a-core.md`. Per roadmap §B2 part 2 in [`design/audits/2026-05-21-frontend-readiness-roadmap.md`](../../design/audits/2026-05-21-frontend-readiness-roadmap.md). The LLD (`lld/notification.md`) doesn't dedicate a section to scanners directly — the scanners are the **producers** of the time-based events the listeners consume — but the roadmap line item is explicit about which scanners to build.

**Hard dependency**: `tickets/notification/01a-core.md` (this ticket consumes the dispatcher and `NotificationCreatedEvent` plumbing).

Five scanners:

1. **`ExpiryWarningScanner`** — queries `provisions_inventory` for items whose `expiry_date` falls within a threshold (default 2 days for fridge, 14 days for freezer); fires `ItemNearingExpiryEvent` for each. The notification listener (already shipped in 01a) consumes the event.
2. **`DefrostReminderScanner`** — queries the meal plan for slots with `defrost_lead_time_hours` set; for each upcoming meal, computes the defrost target time (`meal_time - defrost_lead_time_hours`); fires `DefrostReminderEvent` when the current time is within 1 hour of the target.
3. **`PrepReminderScanner`** — queries the meal plan for slots with a `prep_step_at_time` (e.g. "start marinating at 6pm"); fires `PrepReminderEvent` when current time is within 15 minutes of the prep moment.
4. **`NutritionAlertScanner`** — runs daily at 21:00; for each user, compares intake-so-far against the daily target; if a macro (or selected micros) is meaningfully under or over (configurable threshold per `lld/notification.md:508`), fires `NutritionIntakeDivergedEvent`.
5. **`StapleReplenishmentScanner`** — runs weekly (e.g. Sundays at 10:00); for each user with staple inventory, identifies items that have dropped below their `restock_threshold` AND haven't appeared in recent purchases; fires `ItemNearingExpiryEvent` with a different payload classifier (or a new `StapleReplenishmentEvent` — see §10).

The pattern across all five:
- Each is `@Component @ConditionalOnProperty(prefix="mealprep.notification.scanners", name="<scanner-name>.enabled", havingValue="true", matchIfMissing=true)` — enable/disable per scanner via config.
- Each is `@Scheduled` with a configurable cron expression (no hard-coded times).
- Each runs **read-only** queries against the source module (provisions, planner, nutrition) — never writes to those modules; only **publishes events** that the notification module consumes.
- Each accepts a `Clock` bean (Spring's `Clock`) so tests can advance time via a mock.

Closes: roadmap B2 part 2. Capability-inventory entries: closes the "scheduled scanners" half of the notification cluster.

## Behavioural spec

### Common scanner infrastructure

1. **`ScannerSupport`** abstract base at `notification.scanner.internal.ScannerSupport`. Provides:
   - Injected `Clock` (use `Clock.systemDefaultZone()` in prod via a `@Bean` declaration in `NotificationConfig`; tests inject a fixed `Clock`).
   - Injected `ApplicationEventPublisher`.
   - Injected per-scanner enablement check.
   - A structured logger with the scanner name as a key.
2. **Tests use `Clock.fixed(...)`** — every scanner takes the `Clock` via constructor injection and reads `clock.instant()` instead of `Instant.now()`. The `Clock` bean in `NotificationConfig` defaults to `Clock.systemDefaultZone()` but tests override via `@TestConfiguration`.

### Scanner #1: `ExpiryWarningScanner`

3. **`ExpiryWarningScanner`** at `notification.scanner.ExpiryWarningScanner`. `@Scheduled(cron = "${mealprep.notification.scanners.expiry-warning.cron:0 0 6 * * ?}")` — daily at 06:00 by default.
4. Behaviour:
   - For each user with inventory rows: load all `provisions_inventory` items where `expiry_date IS NOT NULL`.
   - For each item, compute `daysUntilExpiry = ChronoUnit.DAYS.between(today, expiryDate)`. Threshold per storage location:
     - `fridge`: warn if `daysUntilExpiry <= mealprep.notification.scanners.expiry-warning.fridge-days` (default 2).
     - `freezer`: warn if `daysUntilExpiry <= mealprep.notification.scanners.expiry-warning.freezer-days` (default 14).
     - `pantry`: warn if `daysUntilExpiry <= mealprep.notification.scanners.expiry-warning.pantry-days` (default 7).
   - Group by user; for each user-batch, fire **one** `ItemNearingExpiryEvent` containing all relevant items (the notification listener's bundling logic per `lld/notification.md` §Bundling key handles further bundling at the notification layer).
5. **Idempotency**: a separate `expiry_warning_dispatch_log` table records `(userId, scan_date)` so the scanner doesn't fire twice in the same day. Migration `V20260615210000__notification_create_expiry_warning_dispatch_log.sql`:
   ```sql
   CREATE TABLE expiry_warning_dispatch_log (
     id          uuid PRIMARY KEY,
     user_id     uuid NOT NULL,
     scan_date   date NOT NULL,
     fired_at    timestamptz NOT NULL,
     item_count  integer NOT NULL,
     UNIQUE (user_id, scan_date)
   );
   ```
6. **Cross-module read**: the scanner injects `InventoryQueryService.getAllExpiringWithinDays(userId, maxDays)` — a new method added in this ticket's `provisions` module touch. **Worth implementer review** — if `provisions` doesn't yet expose a paginated-by-expiry read, this ticket adds a thin wrapper method (no schema change).

### Scanner #2: `DefrostReminderScanner`

7. **`DefrostReminderScanner`**. `@Scheduled(cron = "${mealprep.notification.scanners.defrost-reminder.cron:0 */15 * * * ?}")` — every 15 minutes.
8. Behaviour:
   - Query the meal planner module for upcoming slots within the next 24 hours that have `defrost_lead_time_hours` set on the slot or recipe metadata.
   - For each slot, compute `defrostTargetTime = slot.mealTime - defrost_lead_time_hours`.
   - If `clock.instant()` is within 1 hour of `defrostTargetTime` AND no prior dispatch row exists for `(slotId, defrostTargetTime)` → fire `DefrostReminderEvent(slotId, inventoryItemId, defrostTargetTime, userId)`.
9. **Idempotency**: `defrost_reminder_dispatch_log` table `(slot_id, defrost_target_time) UNIQUE`. Migration `V20260615210100__notification_create_defrost_reminder_dispatch_log.sql`.

### Scanner #5 (note ordering): `StapleReplenishmentScanner`

10. **Decision: re-use `ItemNearingExpiryEvent` vs new event type.** The roadmap mentions "staple replenishment" but the LLD's `NotificationKind` enum has no `STAPLE_REPLENISHMENT_NEEDED` value. **Options**:
    - **A**: Add `STAPLE_REPLENISHMENT_NEEDED` to `NotificationKind` + a new `StapleReplenishmentPayload` record + a new event. Cleanest; matches the design's "one kind per event" pattern.
    - **B**: Re-use `ItemNearingExpiryEvent` with a special tag in the payload.
    - **Decision**: **Option A**. Ship the new enum value + payload + event in this ticket. The notification module's `NotificationKindResolver` (in 01a) gains a new branch. **Worth user review.**
11. **New enum value** `STAPLE_REPLENISHMENT_NEEDED` added to `NotificationKind`. New payload record `StapleReplenishmentPayload(NotificationKind kind, List<UUID> inventoryItemIds, List<String> ingredientMappingKeys, BigDecimal lowestStockRatio)`.
12. **`StapleReplenishmentScanner`** runs `@Scheduled(cron = "${mealprep.notification.scanners.staple-replenishment.cron:0 0 10 * * SUN}")` — Sundays at 10:00.
13. Behaviour: for each user, query `provisions_inventory` for items tagged `is_staple = true` (verify provisions LLD has this flag; if not, add the flag in a sibling provisions ticket OR use a heuristic of "items the user has bought more than 3 times in 30 days"). For items below `restock_threshold`, fire `StapleReplenishmentNeededEvent`.

### Scanner #3: `PrepReminderScanner`

14. **`PrepReminderScanner`**. `@Scheduled(cron = "${mealprep.notification.scanners.prep-reminder.cron:0 */5 * * * ?}")` — every 5 minutes (finer-grained because the prep moment is a precise time).
15. Behaviour: query planner for slots with `prep_step_at_time` set; if `clock.instant()` is within 15 minutes of the prep time AND no prior dispatch → fire `PrepReminderEvent`.
16. **Idempotency**: `prep_reminder_dispatch_log (slot_id, prep_step_at_time) UNIQUE`. Migration `V20260615210200__notification_create_prep_reminder_dispatch_log.sql`.

### Scanner #4: `NutritionAlertScanner`

17. **`NutritionAlertScanner`**. `@Scheduled(cron = "${mealprep.notification.scanners.nutrition-alert.cron:0 0 21 * * ?}")` — daily at 21:00 (after most evening meals).
18. Behaviour:
   - For each user with nutrition targets configured: load today's intake-so-far + the user's targets.
   - For each tracked nutrient (`calories`, `protein_g`, `carbs_g`, `fat_g`, plus user-selected micros): compute `divergencePct = abs(actual - target) / target`.
   - If `divergencePct >= mealprep.notification.scanners.nutrition-alert.threshold` (default 0.30) AND no prior alert dispatched for this `(userId, date, nutrient)` → fire `NutritionIntakeDivergedEvent(userId, date, nutrientKey, target, actual, divergencePct, traceId, occurredAt)`.
19. **Idempotency**: `nutrition_alert_dispatch_log (user_id, alert_date, nutrient_key) UNIQUE`. Migration `V20260615210300__notification_create_nutrition_alert_dispatch_log.sql`.
20. **Severity threshold** in the notification dispatcher (per 01a's listener): `divergencePct < 0.40 → INFO`, `>= 0.40 → ATTENTION`. The scanner itself doesn't classify severity — that's the listener's job.

### Configuration

21. **`ScannerProperties`** record at `notification.scanner.config.ScannerProperties`. `@ConfigurationProperties(prefix = "mealprep.notification.scanners")`:
    ```java
    public record ScannerProperties(
        ExpiryWarning expiryWarning,
        DefrostReminder defrostReminder,
        PrepReminder prepReminder,
        NutritionAlert nutritionAlert,
        StapleReplenishment stapleReplenishment
    ) {
      public record ExpiryWarning(boolean enabled, String cron, int fridgeDays, int freezerDays, int pantryDays) {}
      public record DefrostReminder(boolean enabled, String cron) {}
      public record PrepReminder(boolean enabled, String cron, int leadMinutes) {}
      public record NutritionAlert(boolean enabled, String cron, BigDecimal threshold) {}
      public record StapleReplenishment(boolean enabled, String cron) {}
    }
    ```
22. All scanners can be individually disabled via `mealprep.notification.scanners.<name>.enabled=false`. Used by test profiles to silence the cron-driven scanners during unit-test runs.

### `@EnableScheduling`

23. **Verify**: `@EnableScheduling` is on the main application config (most Spring Boot apps have it from day one). If absent, add it. If present (almost certainly), no change.

### Cross-cutting

24. **No new exceptions** in this ticket — scanners log + count failures; they don't propagate.
25. **ArchUnit**: a new rule in `NotificationBoundaryTest` asserts every `notification.scanner..` class is `@Scheduled` (extends ScannerSupport) — drift prevention.
26. **Idempotency tables** cascade-delete with the user (FK on `user_id` to `auth_users` — **wait**: per `lld/notification.md:69` and the cross-module FK rule, **no FK to other modules' tables**. Use opaque UUID per the module-boundary convention. Cleanup happens via a daily job or via the future GDPR delete cascade.)
27. **Idempotency-log retention**: a separate `@Scheduled` cleanup deletes log rows older than 30 days. Configurable via `mealprep.notification.scanners.dispatch-log-retention-days` (default 30).

### Events

28. **Published** (all consumed by 01a's listeners — no new listeners in this ticket):
    - `ItemNearingExpiryEvent` (re-used from provisions module)
    - `DefrostReminderEvent` (re-used from planner module, or new in this ticket if planner doesn't publish — verify)
    - `PrepReminderEvent` (re-used)
    - `NutritionIntakeDivergedEvent` (re-used from nutrition module)
    - `StapleReplenishmentNeededEvent` (NEW — defined in this ticket)
29. **Consumed**: none.

## Database

```
NEW   src/main/resources/db/migration/V20260615210000__notification_create_expiry_warning_dispatch_log.sql
NEW   src/main/resources/db/migration/V20260615210100__notification_create_defrost_reminder_dispatch_log.sql
NEW   src/main/resources/db/migration/V20260615210200__notification_create_prep_reminder_dispatch_log.sql
NEW   src/main/resources/db/migration/V20260615210300__notification_create_nutrition_alert_dispatch_log.sql
NEW   src/main/resources/db/migration/V20260615210400__notification_create_staple_replenishment_dispatch_log.sql
```

(5 idempotency tables, one per scanner.)

## OpenAPI updates

**No OpenAPI changes.** Scanners are internal scheduled components; they publish events consumed by 01a's listeners. No new HTTP surface.

## Edge-case checklist

- [ ] All 5 dispatch-log migrations apply cleanly.
- [ ] **ExpiryWarningScanner**: with mock Clock at 06:00 on day D, items with `expiry_date = D+2` (fridge) → event fired. Items with `expiry_date = D+15` (freezer) → no event. Items with `expiry_date = D+14` (freezer) → event fired.
- [ ] **ExpiryWarningScanner idempotency**: running the scanner twice on the same day for the same user → only one event fired (idempotency row prevents the second).
- [ ] **DefrostReminderScanner**: with mock Clock at 14:00 and a meal slot at 18:00 with `defrost_lead_time_hours = 4` → fires (defrost target = 14:00). At 13:00 → no fire (defrost target 14:00 is 1 hour away — within the 1-hour fire window). At 12:30 → no fire (> 1 hour).
- [ ] **PrepReminderScanner**: with mock Clock at 17:45 and a slot with `prep_step_at_time = 18:00` → fires (within 15 minutes). At 17:44 → no fire (> 15 minutes).
- [ ] **NutritionAlertScanner**: with mock Clock at 21:00, user with target 2000 kcal and intake 1400 kcal (divergence 30%) → fires INFO event. Intake 1100 kcal (divergence 45%) → fires ATTENTION-eligible event (listener determines severity).
- [ ] **NutritionAlertScanner idempotency**: same user, same nutrient, same day → only one event.
- [ ] **StapleReplenishmentScanner**: Sunday at 10:00, user with a staple-tagged item below threshold → fires.
- [ ] **Scanner disabled via property**: `mealprep.notification.scanners.expiry-warning.enabled=false` → bean conditionally absent; cron doesn't fire.
- [ ] **Disabled in test profile**: the `application-test.properties` ships with all scanners disabled (per `tickets/infra/01a-repo-hygiene.md`-like convention) so test runs don't trigger background scans.
- [ ] **Clock injection**: every scanner reads `clock.instant()`, never `Instant.now()` (grep-verified).
- [ ] **Cron expressions** parse cleanly at startup (Spring's `CronTrigger` parser).
- [ ] **No write to source modules**: scanners only read from provisions/planner/nutrition; no mutation. Verified by IT counting writes via `Hibernate.statistics`.
- [ ] **Idempotency retention**: rows older than 30 days are deleted by the daily cleanup; mocked-Clock IT verifies.
- [ ] **Cross-tenant isolation**: scanner only emits events scoped to the user the data belongs to; no leakage.
- [ ] **Empty inventory**: scanner runs cleanly with no data → no events, no errors.
- [ ] **Failure isolation**: an exception fetching data for one user is caught + logged + counted; the scanner continues with the next user.
- [ ] **`StapleReplenishmentNeededEvent`** new class + `STAPLE_REPLENISHMENT_NEEDED` enum value + payload record successfully serialise.
- [ ] **ArchUnit**: every `notification.scanner..` class extends `ScannerSupport` (or has `@Scheduled`).

## Files this ticket touches

```
NEW   src/main/resources/db/migration/V20260615210000__notification_create_expiry_warning_dispatch_log.sql
NEW   src/main/resources/db/migration/V20260615210100__notification_create_defrost_reminder_dispatch_log.sql
NEW   src/main/resources/db/migration/V20260615210200__notification_create_prep_reminder_dispatch_log.sql
NEW   src/main/resources/db/migration/V20260615210300__notification_create_nutrition_alert_dispatch_log.sql
NEW   src/main/resources/db/migration/V20260615210400__notification_create_staple_replenishment_dispatch_log.sql

NEW   src/main/java/com/example/mealprep/notification/scanner/internal/ScannerSupport.java
NEW   src/main/java/com/example/mealprep/notification/scanner/ExpiryWarningScanner.java
NEW   src/main/java/com/example/mealprep/notification/scanner/DefrostReminderScanner.java
NEW   src/main/java/com/example/mealprep/notification/scanner/PrepReminderScanner.java
NEW   src/main/java/com/example/mealprep/notification/scanner/NutritionAlertScanner.java
NEW   src/main/java/com/example/mealprep/notification/scanner/StapleReplenishmentScanner.java
NEW   src/main/java/com/example/mealprep/notification/scanner/internal/DispatchLogCleanupScheduler.java

NEW   src/main/java/com/example/mealprep/notification/scanner/internal/repository/ExpiryWarningDispatchLogRepository.java
NEW   src/main/java/com/example/mealprep/notification/scanner/internal/repository/DefrostReminderDispatchLogRepository.java
NEW   src/main/java/com/example/mealprep/notification/scanner/internal/repository/PrepReminderDispatchLogRepository.java
NEW   src/main/java/com/example/mealprep/notification/scanner/internal/repository/NutritionAlertDispatchLogRepository.java
NEW   src/main/java/com/example/mealprep/notification/scanner/internal/repository/StapleReplenishmentDispatchLogRepository.java

NEW   src/main/java/com/example/mealprep/notification/scanner/internal/entity/ExpiryWarningDispatchLog.java
NEW   src/main/java/com/example/mealprep/notification/scanner/internal/entity/DefrostReminderDispatchLog.java
NEW   src/main/java/com/example/mealprep/notification/scanner/internal/entity/PrepReminderDispatchLog.java
NEW   src/main/java/com/example/mealprep/notification/scanner/internal/entity/NutritionAlertDispatchLog.java
NEW   src/main/java/com/example/mealprep/notification/scanner/internal/entity/StapleReplenishmentDispatchLog.java

NEW   src/main/java/com/example/mealprep/notification/scanner/config/ScannerProperties.java

NEW   src/main/java/com/example/mealprep/notification/event/StapleReplenishmentNeededEvent.java

MOD   src/main/java/com/example/mealprep/notification/domain/entity/NotificationKind.java          (add STAPLE_REPLENISHMENT_NEEDED)
MOD   src/main/java/com/example/mealprep/notification/domain/entity/NotificationPayload.java       (add StapleReplenishmentPayload permit)
MOD   src/main/java/com/example/mealprep/notification/domain/service/internal/NotificationKindResolver.java  (resolve new event)
MOD   src/main/java/com/example/mealprep/notification/event/ProvisionEventListener.java            (handle StapleReplenishmentNeededEvent)
MOD   src/main/java/com/example/mealprep/notification/domain/service/internal/NotificationDefaults.java     (default-ON for new kind)

MOD   src/main/resources/application.properties                                                    (scanner cron defaults; test profile disables)
MOD   src/main/resources/application-test.properties                                                (disable all scanners)
MOD   src/main/java/com/example/mealprep/notification/NotificationModule.java                       (no change required — scanners aren't part of facade)

NEW   src/test/java/com/example/mealprep/notification/ExpiryWarningScannerTest.java                (Clock.fixed)
NEW   src/test/java/com/example/mealprep/notification/DefrostReminderScannerTest.java
NEW   src/test/java/com/example/mealprep/notification/PrepReminderScannerTest.java
NEW   src/test/java/com/example/mealprep/notification/NutritionAlertScannerTest.java
NEW   src/test/java/com/example/mealprep/notification/StapleReplenishmentScannerTest.java
NEW   src/test/java/com/example/mealprep/notification/ScannerIdempotencyIT.java                    (Testcontainers — runs twice; second is a no-op)
MOD   src/test/java/com/example/mealprep/notification/NotificationBoundaryTest.java                (scanner ArchUnit rule)
```

Total: ~30 new + 8 mods. Estimated agent runtime 5-7 hours.

## Dependencies

- **Hard dependency**: `tickets/notification/01a-core.md` (merged) — dispatcher, listeners, event types.
- **Hard dependency**: `provisions-XX` (merged) — `InventoryQueryService` with expiry/staple methods.
- **Hard dependency**: `planner-XX` (merged) — `PlannerQueryService.getUpcomingSlots(...)`.
- **Hard dependency**: `nutrition-XX` (merged) — daily-intake queries.
- **Producer-event coordination**: if any of the events the scanners publish doesn't yet exist in the producer module (e.g. `DefrostReminderEvent`), this ticket adds it to the producer module's `event/` package. **Worth implementer review** at agent start.

## Acceptance / DoD

- [ ] `./mvnw -Dspotless.check.skip=true verify` passes
- [ ] `./mvnw spotless:apply` then `./mvnw spotless:check` clean
- [ ] CI green
- [ ] All edge-case items above ticked
- [ ] `ScannerIdempotencyIT` proves each scanner is idempotent within its window
- [ ] PR description includes a Clock-driven trace for each of the 5 scanners showing the fire/no-fire boundary

## What's NOT in scope

- **Real-time intake monitoring** (intermittent during-the-day checks) — the daily 21:00 alert is sufficient for v1.
- **Per-user cron overrides** — global cron config only.
- **Backfill of historical data** — scanners only look forward.
- **Distributed cron coordination** (multiple instances) — single-instance v1; if scaled, add Shedlock or similar.
- **`NotificationKind` value `STAPLE_REPLENISHMENT_NEEDED` UX strings** — UX phase.

Squash-merge with: `feat(notification): 01b — scheduled scanners (expiry / defrost / prep / nutrition / replenishment)`
