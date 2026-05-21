# Ticket: notification — 01a Core (entities, migrations, dispatcher, listeners, REST)

## Summary

Build the notification module per [`lld/notification.md`](../../lld/notification.md). Per roadmap §B2 in [`design/audits/2026-05-21-frontend-readiness-roadmap.md`](../../design/audits/2026-05-21-frontend-readiness-roadmap.md). The audit's drift finding: this LLD is 640 lines, specifies ~30 concrete artefacts (3 entities, 4 migrations, 2 services + 5 helpers, 3 listeners, 1 published event, 1 enum + sealed payload, 10 REST endpoints, 7 DTOs), and **zero of it exists in code**. This ticket brings it to life.

The notification module is a **cross-cutting subscriber**: other modules publish domain events; this module transforms a subset into `Notification` rows, optionally bundles them, persists them, and routes through a channel layer (in-app DB only in v1).

Ships:

- **3 entities**: `Notification` (aggregate root), `NotificationPreference`, `DeliveryLog` (append-only).
- **4 migrations**: notifications table, preferences, delivery log, repeatable seed for defaults.
- **2 service interfaces** (`NotificationQueryService`, `NotificationUpdateService`) + impl + 5 internal helpers (`NotificationDispatcher`, `NotificationKindResolver`, `QuietHoursEvaluator`, `NotificationDebouncer`, `DeliveryChannel` SPI + `InAppDeliveryChannel` + `DeliveryChannelRegistry`).
- **3 event listeners** (`ProvisionEventListener`, `NutritionEventListener`, `PlannerEventListener`) listening to the 8 producer events per `lld/notification.md` §Consumed.
- **1 enum** `NotificationKind` (8 values) + sealed `NotificationPayload` with 8 record permits.
- **10 REST endpoints** under `/api/v1/notifications`.
- **7 DTOs** + 1 internal request shape (`CreateNotificationRequest`).
- **Module facade** `NotificationModule` + ArchUnit `NotificationBoundaryTest`.
- **`@ValidQuietHours` class-level validator**.
- **Module-root exception + 3 subclasses**.

**Deferred to follow-up `tickets/notification/01b-scanners.md`**:
- Scheduled scanners (expiry warnings, defrost reminders, prep reminders, nutrition alerts, staple replenishment) — they produce notifications from time-based triggers rather than events. Sibling ticket.

**`PLANNER_PLAN_GENERATED` is default-OFF** per `lld/notification.md:149,153` — the kind enum value ships but the repeatable seed default has it disabled. **Worth user review.**

**Realtime delivery (WebSocket/SSE) is deferred** per `lld/notification.md` §Out of Scope. The `NotificationCreatedEvent` ships now so the contract is stable; the realtime channel listens to it later.

Closes: capabilities in roadmap B2 (not directly inventoried per-capability but closes ~10 MISSING entries in the audit's notification cluster).

## Behavioural spec

### Database — 4 migrations

1. **`V20260615200000__notification_create_notifications.sql`** per `lld/notification.md:60-87`:
   ```sql
   CREATE TABLE notifications (
       id                    uuid PRIMARY KEY,
       user_id               uuid NOT NULL,
       household_id          uuid,
       kind                  varchar(64) NOT NULL,
       severity              varchar(16) NOT NULL,
       title                 varchar(200) NOT NULL,
       body                  varchar(1000) NOT NULL,
       payload               jsonb NOT NULL,
       status                varchar(16) NOT NULL,
       action_target_uri     varchar(512),
       bundle_count          integer NOT NULL DEFAULT 1,
       bundle_keys           jsonb,
       source_event_id       uuid,
       trace_id              uuid,
       created_at            timestamptz NOT NULL,
       read_at               timestamptz,
       actioned_at           timestamptz,
       dismissed_at          timestamptz,
       optimistic_version    bigint NOT NULL DEFAULT 0
   );
   CREATE INDEX idx_notifications_user_status_created ON notifications (user_id, status, created_at DESC);
   CREATE INDEX idx_notifications_user_kind_created ON notifications (user_id, kind, created_at DESC);
   CREATE INDEX idx_notifications_unread ON notifications (user_id) WHERE status = 'UNREAD';
   ```
2. **`V20260615200100__notification_create_notification_preferences.sql`** per `lld/notification.md:92-108`:
   ```sql
   CREATE TABLE notification_preferences (
       id                       uuid PRIMARY KEY,
       user_id                  uuid NOT NULL UNIQUE,
       enabled_kinds            jsonb NOT NULL,
       quiet_hours_enabled      boolean NOT NULL DEFAULT false,
       quiet_hours_start        time,
       quiet_hours_end          time,
       timezone                 varchar(64) NOT NULL DEFAULT 'Europe/London',
       debounce_window_minutes  integer NOT NULL DEFAULT 30,
       optimistic_version       bigint NOT NULL DEFAULT 0,
       created_at               timestamptz NOT NULL,
       updated_at               timestamptz NOT NULL
   );
   CREATE UNIQUE INDEX idx_notification_preferences_user ON notification_preferences (user_id);
   ```
3. **`V20260615200200__notification_create_delivery_log.sql`** per `lld/notification.md:113-126`:
   ```sql
   CREATE TABLE notification_delivery_log (
       id              uuid PRIMARY KEY,
       notification_id uuid NOT NULL REFERENCES notifications(id) ON DELETE CASCADE,
       channel         varchar(32) NOT NULL,
       outcome         varchar(16) NOT NULL,
       skip_reason     varchar(64),
       detail          jsonb,
       attempted_at    timestamptz NOT NULL
   );
   CREATE INDEX idx_delivery_log_notification ON notification_delivery_log (notification_id);
   CREATE INDEX idx_delivery_log_channel_attempted ON notification_delivery_log (channel, attempted_at DESC);
   ```
4. **`R__notification_seed_default_preferences.sql`** — repeatable migration per `lld/notification.md:130-132`. Content: a SQL comment header + the default `enabled_kinds` JSON payload used by `ensurePreferencesForUser` when a user has no row. Format:
   ```sql
   -- Repeatable migration: default kind toggles for new users.
   -- The values land here as a single 'default_preferences' row in a side table OR
   -- (decision below) as a static Java constant. Picking the JAVA CONSTANT approach
   -- so the repeatable migration is empty in 01a (placeholder for future opt-in seed data).

   -- (Empty body — defaults live in NotificationDefaults.java.)
   ```
   **Decision**: `enabled_kinds` defaults live in a Java constant (`NotificationDefaults.DEFAULT_ENABLED_KINDS`) rather than a seed table. Reason: the kind set evolves with code (new enum values must default to ON/OFF in code anyway); a seed table would create dual sources of truth. The repeatable migration ships as a placeholder for future opt-in user-side defaults. **Worth user review.**

### Entities

5. **`Notification`** at `notification.domain.entity.Notification`. Aggregate root. Lombok per style guide. Fields per `lld/notification.md:207`:
   - `id (UUID)`, `userId (UUID)`, `householdId (UUID, nullable)`
   - `kind (NotificationKind, @Enumerated(STRING), length=64)`
   - `severity (NotificationSeverity, @Enumerated(STRING), length=16)`
   - `title (String, length=200)`, `body (String, length=1000)`
   - `payload (NotificationPayload, @Type(JsonType.class), columnDefinition="jsonb")`
   - `status (NotificationStatus, @Enumerated(STRING), length=16)`
   - `actionTargetUri (String, length=512, nullable)`
   - `bundleCount (int, NOT NULL DEFAULT 1)`, `bundleKeys (JsonNode, @Type(JsonType.class), nullable)`
   - `sourceEventId (UUID, nullable)`, `traceId (UUID, nullable)`
   - `readAt`, `actionedAt`, `dismissedAt (Instant, nullable)`
   - `optimisticVersion (long, @Version)`, `createdAt (@CreatedDate)`
6. **`NotificationPreference`** at `notification.domain.entity.NotificationPreference`. Aggregate root. Fields per `lld/notification.md:208`:
   - `id`, `userId (UNIQUE)`
   - `enabledKinds (Map<NotificationKind, Boolean>, @Type(JsonType.class), columnDefinition="jsonb")`
   - `quietHoursEnabled (boolean)`, `quietHoursStart (LocalTime, nullable)`, `quietHoursEnd (LocalTime, nullable)`
   - `timezone (String, length=64)` — resolved to `ZoneId` at evaluation time
   - `debounceWindowMinutes (int)`
   - `optimisticVersion (long, @Version)`, `createdAt`, `updatedAt`
7. **`DeliveryLog`** at `notification.domain.entity.DeliveryLog`. Append-only. `@ManyToOne(fetch=LAZY)` back to `Notification`. Fields per `lld/notification.md:209`:
   - `id`, `notification (@JoinColumn name="notification_id")`
   - `channel (DeliveryChannel.Channel enum)`, `outcome (DeliveryOutcome enum)`, `skipReason (DeliverySkipReason enum, nullable)`
   - `detail (JsonNode, nullable)`, `attemptedAt (Instant)`
   - **No `@Version`**.

### Enums

8. **`NotificationKind`** per `lld/notification.md:141`. 8 values:
   ```
   PROVISION_ITEM_NEAR_EXPIRY, PROVISION_ITEM_SPOILED, PROVISION_DEFROST_REMINDER,
   NUTRITION_INTAKE_DIVERGED, HEALTH_DIRECTIVE_RECEIVED,
   PLANNER_PREP_REMINDER, PLANNER_REOPT_SUGGESTED, PLANNER_PLAN_GENERATED
   ```
9. **`NotificationSeverity`** — `INFO, ATTENTION, URGENT`.
10. **`NotificationStatus`** — `UNREAD, READ, DISMISSED, ACTIONED`.
11. **`DeliveryChannel.Channel`** (inner enum) — `IN_APP, PUSH, EMAIL` (PUSH/EMAIL not implemented in v1).
12. **`DeliveryOutcome`** — `DELIVERED, SKIPPED, DEFERRED, FAILED`.
13. **`DeliverySkipReason`** — `DISABLED_BY_PREF, QUIET_HOURS, DEDUPED_INTO_BUNDLE, CHANNEL_UNAVAILABLE`.

### Sealed `NotificationPayload`

14. **`NotificationPayload`** sealed interface at `notification.domain.entity.NotificationPayload` per `lld/notification.md:158-196`. Records:
    - `ItemNearExpiryPayload(NotificationKind kind, List<UUID> inventoryItemIds, LocalDate earliestExpiry, int itemCount)`
    - `ItemSpoiledPayload(NotificationKind kind, List<UUID> inventoryItemIds, List<String> ingredientMappingKeys)`
    - `DefrostReminderPayload(NotificationKind kind, UUID inventoryItemId, UUID plannedMealSlotId, Instant defrostBy)`
    - `NutritionDivergedPayload(NotificationKind kind, LocalDate date, String nutrientKey, BigDecimal targetValue, BigDecimal actualValue, BigDecimal divergencePct)`
    - `HealthDirectivePayload(NotificationKind kind, UUID directiveId, String summary)`
    - `PrepReminderPayload(NotificationKind kind, UUID plannedMealSlotId, UUID recipeId, String prepStep, Instant prepBy)`
    - `ReoptSuggestedPayload(NotificationKind kind, UUID planId, String triggerSummary, List<UUID> affectedSlotIds)`
    - `PlanGeneratedPayload(NotificationKind kind, UUID planId, int generation)`
15. **Jackson polymorphic deserialisation** via `@JsonTypeInfo(use=NAME, property="kind")` on the sealed interface; `@JsonSubTypes` lists all 8 permits.

### Repositories (package-private)

16. Per `lld/notification.md:278-313`:
    - `NotificationRepository` — `findByIdAndUserId`, `findByUserIdAndStatusInOrderByCreatedAtDesc` (Page), `findByUserIdOrderByCreatedAtDesc` (Page), `countByUserIdAndStatus`, `findOpenForBundling` (@Query, the debouncer's single-flight lookup).
    - `NotificationPreferenceRepository` — `findByUserId`.
    - `DeliveryLogRepository` — `findByNotificationIdOrderByAttemptedAtDesc` (Page).

### Service interfaces + impl

17. **`NotificationQueryService`** per `lld/notification.md:326-334`:
    ```java
    public interface NotificationQueryService {
      Optional<NotificationDto> getById(UUID userId, UUID notificationId);
      List<NotificationDto> getByIds(UUID userId, List<UUID> notificationIds);
      Page<NotificationDto> list(UUID userId, NotificationListFilter filter, Pageable pageable);
      NotificationSummaryDto getSummary(UUID userId);
      NotificationPreferenceDto getPreferences(UUID userId);
      Page<DeliveryLogEntryDto> getDeliveryLog(UUID userId, UUID notificationId, Pageable pageable);
    }
    ```
18. **`NotificationUpdateService`** per `lld/notification.md:341-356`:
    ```java
    public interface NotificationUpdateService {
      NotificationDto create(CreateNotificationRequest request);             // listener-facing
      NotificationDto markRead(UUID userId, UUID notificationId);
      NotificationDto markDismissed(UUID userId, UUID notificationId);
      NotificationDto markActioned(UUID userId, UUID notificationId);
      void markAllRead(UUID userId, Set<NotificationKind> kinds);
      NotificationPreferenceDto updatePreferences(UUID userId, UpdateNotificationPreferenceRequest request);
      NotificationPreferenceDto ensurePreferencesForUser(UUID userId);       // idempotent
    }
    ```
19. **`NotificationServiceImpl`** at `notification.domain.service.internal`. Single impl of both interfaces. `@Transactional` on writes; `@Transactional(readOnly=true)` on reads.

### Internal helpers

20. **`NotificationDispatcher`** (package-private interface + impl) per `lld/notification.md:362-368`:
    ```java
    interface NotificationDispatcher {
      Optional<UUID> dispatch(NotificationDraft draft);
    }
    ```
    Orchestrates: preference filter → quiet-hours filter → debounce/bundle → persist via `NotificationUpdateService.create` → fan-out via `DeliveryChannelRegistry` → publish `NotificationCreatedEvent`.
21. **`NotificationKindResolver`** — maps each producer event to a `(NotificationKind, NotificationSeverity, NotificationPayload, actionTargetUri, householdId)` tuple per `lld/notification.md:439` table.
22. **`QuietHoursEvaluator`** per `lld/notification.md:516-523`. Resolves `now` against the user's `ZoneId`, handles wrap-around windows (22:00 → 06:00). URGENT bypasses quiet hours per `lld/notification.md:520`.
23. **`NotificationDebouncer`** per `lld/notification.md:528-535`. `findOpenForBundling(userId, kind, since, PageRequest.of(0, 1))`. Bundle target mutated (`bundle_count++`, append `bundleKeys`, regenerate title/body from kind template).
24. **`DeliveryChannel` SPI** at `notification.domain.service.internal.delivery.DeliveryChannel`. Per `lld/notification.md:470-484`. Methods: `channel()`, `accepts(Notification)`, `deliver(Notification): DeliveryOutcome`.
25. **`InAppDeliveryChannel`** — sole v1 impl. `accepts` returns true for every notification. `deliver` writes one `DeliveryLog(IN_APP, DELIVERED)` row.
26. **`DeliveryChannelRegistry`** — auto-wires `List<DeliveryChannel>`; resolves channels per notification via `accepts(...)`.

### REST endpoints

27. **`NotificationsController`** at `notification.api.controller`. Endpoints per `lld/notification.md:381-390`:

| Method | Path | Request | Response | Status |
|---|---|---|---|---|
| GET | `/api/v1/notifications` | `status`, `kind`, `since`, `page`, `size` | `Page<NotificationDto>` | 200 |
| GET | `/api/v1/notifications/summary` | — | `NotificationSummaryDto` | 200 |
| GET | `/api/v1/notifications/{id}` | — | `NotificationDto` | 200 / 404 |
| POST | `/api/v1/notifications/{id}/read` | — | `NotificationDto` | 200 / 404 / 409 |
| POST | `/api/v1/notifications/{id}/dismiss` | — | `NotificationDto` | 200 / 404 / 409 |
| POST | `/api/v1/notifications/{id}/action` | — | `NotificationDto` | 200 / 404 / 409 |
| POST | `/api/v1/notifications/bulk/read` | `{ kinds: [...] }` | `{ updated: <n> }` | 200 |
| GET | `/api/v1/notifications/{id}/delivery-log` | `page`, `size` | `Page<DeliveryLogEntryDto>` | 200 / 404 |

28. **`NotificationPreferencesController`**:
| GET | `/api/v1/notifications/preferences` | — | `NotificationPreferenceDto` | 200 |
| PUT | `/api/v1/notifications/preferences` | `UpdateNotificationPreferenceRequest` | `NotificationPreferenceDto` | 200 / 400 / 409 |

29. All endpoints require session-cookie auth. `userId` via `CurrentUserResolver`.
30. **State-transition POSTs (`/read`, `/dismiss`, `/action`)** not PUT — per `lld/notification.md:392` style-guide convention.

### Event listeners

31. **`ProvisionEventListener`** at `notification.event.ProvisionEventListener`:
    - `onItemNearingExpiry(ItemNearingExpiryEvent)` → `PROVISION_ITEM_NEAR_EXPIRY` / ATTENTION / householded.
    - `onItemSpoiled(ItemSpoiledEvent)` → `PROVISION_ITEM_SPOILED` / ATTENTION / householded.
    - `onDefrostReminder(DefrostReminderEvent)` → `PROVISION_DEFROST_REMINDER` / ATTENTION / per-`mealSlotId` bundling.
32. **`NutritionEventListener`**:
    - `onNutritionIntakeDiverged(NutritionIntakeDivergedEvent)` → `NUTRITION_INTAKE_DIVERGED` / INFO if divergencePct < 0.40 else ATTENTION.
    - `onHealthDirectiveReceived(HealthDirectiveReceivedEvent)` → `HEALTH_DIRECTIVE_RECEIVED` / URGENT.
33. **`PlannerEventListener`**:
    - `onPrepReminder(PrepReminderEvent)` → `PLANNER_PREP_REMINDER` / ATTENTION.
    - `onReoptSuggested(ReoptSuggestedEvent)` → `PLANNER_REOPT_SUGGESTED` / ATTENTION / replacement bundling.
    - `onPlanGenerated(PlanGeneratedEvent)` → `PLANNER_PLAN_GENERATED` / INFO / default OFF in preferences.
34. **Every listener method** is `@TransactionalEventListener(phase = AFTER_COMMIT)` and the same 2-line shape:
    ```java
    @TransactionalEventListener(phase = AFTER_COMMIT)
    public void onX(XEvent event) {
      try {
        dispatcher.dispatch(resolver.resolve(event));
      } catch (Exception e) {
        log.error("notification dispatch failed for event={}", event, e);
        meterRegistry.counter("notification.dispatch.failure", "kind", "X").increment();
      }
    }
    ```
    **Never throws** — failures are logged and metric-counted per `lld/notification.md:435`.

### Published event

35. **`NotificationCreatedEvent`** per `lld/notification.md:455-460`. Implements `OriginAwareEvent` (per `tickets/core/02b`). No v1 listeners. The realtime delivery channel listens later.

### Validation

36. **`@ValidQuietHours`** class-level validator at `notification.validation`. Asserts:
    - `quietHoursStart` and `quietHoursEnd` are both null (and `quietHoursEnabled=false`) OR both non-null (and `quietHoursEnabled=true`).
    - Rejects equal `start == end` (zero-length window).
    - Validates `timezone` via `ZoneId.of(...)`.
    - Wrap-around windows allowed.

### Cross-cutting

37. New exceptions per `lld/notification.md:399-403`:
    - `NotificationNotFoundException` (404)
    - `NotificationStateTransitionException` (409) — covers illegal state machine moves
    - `NotificationPreferenceNotFoundException` (404)
    - Module root `NotificationException extends MealPrepException`.
38. `GlobalExceptionHandler` mappings for the three.
39. **`NotificationBoundaryTest`** ArchUnit class. Rules: repos package-private, no cross-module repo import, `DeliveryChannel` SPI is public (sibling impls allowed cross-module).
40. **`ModuleBoundaryTest`** gains the notification module's repo-private rule.

### Configuration

41. **`NotificationProperties`** record at `notification.config`. `@ConfigurationProperties(prefix = "mealprep.notification")`:
    - `Duration retentionAfterRead` (default `P90D`) — placeholder, future retention sweep ticket consumes
    - `int defaultDebounceWindowMinutes` (default 30)
    - `boolean planGeneratedDefaultEnabled` (default false)

### Status state machine

42. Per `lld/notification.md:545-562`. Enforced in `NotificationServiceImpl.markX` methods. Legal:
    - UNREAD → READ | DISMISSED | ACTIONED
    - READ → DISMISSED | ACTIONED
    - ACTIONED → DISMISSED
    - DISMISSED → (terminal)
   Anything else → `NotificationStateTransitionException`.

### Household routing

43. Per `lld/notification.md:567-569`. **Primary-user-only for v1.** When an event carries a `householdId`, listener resolves to the primary user via `HouseholdQueryService.getPrimary(householdId)` and dispatches to that user. Stored `householdId` so future "all members" mode is a query change. **Worth user review.**

## Database

```
NEW   src/main/resources/db/migration/V20260615200000__notification_create_notifications.sql
NEW   src/main/resources/db/migration/V20260615200100__notification_create_notification_preferences.sql
NEW   src/main/resources/db/migration/V20260615200200__notification_create_delivery_log.sql
NEW   src/main/resources/db/migration/R__notification_seed_default_preferences.sql              (placeholder body)
```

## OpenAPI updates

Add **10 paths** + **7 schemas** + **1 module tag** (`Notifications`) per `lld/notification.md` §REST Controllers / §DTOs. Files:
- `src/main/resources/openapi/paths/notification.yaml` (NEW)
- `src/main/resources/openapi/schemas/notification.yaml` (NEW)

Schemas: `NotificationDto`, `NotificationSummaryDto`, `NotificationPreferenceDto`, `UpdateNotificationPreferenceRequest`, `DeliveryLogEntryDto`, `NotificationPayload` (with sealed-interface discriminator), `NotificationKind`, `NotificationSeverity`, `NotificationStatus`.

Reference `openapi.yaml`'s top-level paths/schemas via `$ref` so the modular structure mirrors other modules.

## Edge-case checklist

- [ ] Four migrations apply cleanly; `FlywayMigrationIT` passes.
- [ ] `ddl-auto=validate` accepts all three entities.
- [ ] **Sealed payload round-trip**: persist a `Notification` with each of the 8 payload types → re-read → equal. Jackson `@JsonTypeInfo` correctly serialises/deserialises per the `kind` discriminator.
- [ ] **State machine**: UNREAD → READ → ACTIONED legal; READ → UNREAD throws 409; DISMISSED → READ throws 409.
- [ ] **Idempotent `ensurePreferencesForUser`**: first call creates row with defaults; second call finds existing row, no-op.
- [ ] **`@ValidQuietHours`**: `quietHoursEnabled=true` with null start/end → 400; `start=22:00, end=22:00` → 400; `timezone="Mars/Phobos"` → 400; wrap-around `start=22:00, end=06:00` accepted.
- [ ] **`QuietHoursEvaluator`**: in `Europe/London`, event at 23:30 local with window 22:00→06:00 → suppress. Event at 07:00 → deliver. URGENT at 23:30 → deliver.
- [ ] **`NotificationDebouncer`**: 3 `ItemNearingExpiryEvent`s within the 30-min window → 1 row with `bundle_count=3`. A 4th event past the window → 2nd row.
- [ ] **Cross-kind isolation**: `ItemNearingExpiry` + `ItemSpoiled` for the same user do not bundle into each other.
- [ ] **`PLANNER_REOPT_SUGGESTED` replacement bundling**: a 2nd reopt within the window overwrites the 1st row's payload (latest wins); `bundle_count` stays 1.
- [ ] **AFTER_COMMIT semantics**: an `ItemNearingExpiryEvent` published in a rolled-back transaction → no `Notification` row written.
- [ ] **Listener exception isolation**: a dispatcher exception logs + counts but doesn't propagate (verified by Mockito `doThrow` + assertion of no exception).
- [ ] **In-app channel**: every persisted notification produces one `DeliveryLog(IN_APP, DELIVERED)` row.
- [ ] **Suppressed dispatch**: a notification rejected by quiet hours produces a `DeliveryLog(SKIPPED, QUIET_HOURS)` row. The notification itself is persisted with `status=DISMISSED` and `dismissed_at=createdAt` per `lld/notification.md:638` Open Question resolution.
- [ ] **`NotificationCreatedEvent` published once per persisted notification** — never on suppression.
- [ ] **`getById` enforces userId** — user A cannot read user B's notification.
- [ ] **`markAllRead`** with empty kinds set → marks all unread notifications read; with `{PROVISION_ITEM_NEAR_EXPIRY}` → marks only that kind.
- [ ] **Pagination**: default size 20, max 100 (per `tickets/infra/01b`'s convention).
- [ ] **Summary endpoint**: returns `unreadCount`, `attentionCount`, `urgentCount` matching the actual row counts.
- [ ] **Household routing**: an event with `householdId` set is dispatched to the household's primary user; `householdId` stored on the row.
- [ ] **`OriginAwareEvent` on `NotificationCreatedEvent`**: `origin()` returns `USER` for user-action-triggered notifications; future bridge-driven notifications will return `AI_FEEDBACK` etc.
- [ ] **ArchUnit `NotificationBoundaryTest`**: repos package-private; SPI public; no cross-module repo import.
- [ ] **OpenAPI contract test**: all 10 endpoints' shapes match the spec.
- [ ] **`DeliveryChannel` SPI extensibility**: a test-only second channel bean registers and receives delivery alongside the in-app channel (proves the SPI seam).

## Files this ticket touches

```
NEW   src/main/resources/db/migration/V20260615200000__notification_create_notifications.sql
NEW   src/main/resources/db/migration/V20260615200100__notification_create_notification_preferences.sql
NEW   src/main/resources/db/migration/V20260615200200__notification_create_delivery_log.sql
NEW   src/main/resources/db/migration/R__notification_seed_default_preferences.sql

NEW   src/main/java/com/example/mealprep/notification/NotificationModule.java

NEW   src/main/java/com/example/mealprep/notification/domain/entity/Notification.java
NEW   src/main/java/com/example/mealprep/notification/domain/entity/NotificationPreference.java
NEW   src/main/java/com/example/mealprep/notification/domain/entity/DeliveryLog.java
NEW   src/main/java/com/example/mealprep/notification/domain/entity/NotificationKind.java
NEW   src/main/java/com/example/mealprep/notification/domain/entity/NotificationSeverity.java
NEW   src/main/java/com/example/mealprep/notification/domain/entity/NotificationStatus.java
NEW   src/main/java/com/example/mealprep/notification/domain/entity/NotificationPayload.java       (sealed + 8 record permits)
NEW   src/main/java/com/example/mealprep/notification/domain/entity/DeliveryOutcome.java
NEW   src/main/java/com/example/mealprep/notification/domain/entity/DeliverySkipReason.java

NEW   src/main/java/com/example/mealprep/notification/domain/repository/NotificationRepository.java
NEW   src/main/java/com/example/mealprep/notification/domain/repository/NotificationPreferenceRepository.java
NEW   src/main/java/com/example/mealprep/notification/domain/repository/DeliveryLogRepository.java

NEW   src/main/java/com/example/mealprep/notification/domain/service/NotificationQueryService.java
NEW   src/main/java/com/example/mealprep/notification/domain/service/NotificationUpdateService.java
NEW   src/main/java/com/example/mealprep/notification/domain/service/internal/NotificationServiceImpl.java
NEW   src/main/java/com/example/mealprep/notification/domain/service/internal/NotificationDispatcher.java          (interface + impl)
NEW   src/main/java/com/example/mealprep/notification/domain/service/internal/NotificationKindResolver.java
NEW   src/main/java/com/example/mealprep/notification/domain/service/internal/QuietHoursEvaluator.java
NEW   src/main/java/com/example/mealprep/notification/domain/service/internal/NotificationDebouncer.java
NEW   src/main/java/com/example/mealprep/notification/domain/service/internal/NotificationDefaults.java
NEW   src/main/java/com/example/mealprep/notification/domain/service/internal/delivery/DeliveryChannel.java        (SPI — public)
NEW   src/main/java/com/example/mealprep/notification/domain/service/internal/delivery/InAppDeliveryChannel.java
NEW   src/main/java/com/example/mealprep/notification/domain/service/internal/delivery/DeliveryChannelRegistry.java

NEW   src/main/java/com/example/mealprep/notification/api/controller/NotificationsController.java
NEW   src/main/java/com/example/mealprep/notification/api/controller/NotificationPreferencesController.java
NEW   src/main/java/com/example/mealprep/notification/api/dto/NotificationDto.java
NEW   src/main/java/com/example/mealprep/notification/api/dto/NotificationSummaryDto.java
NEW   src/main/java/com/example/mealprep/notification/api/dto/NotificationPreferenceDto.java
NEW   src/main/java/com/example/mealprep/notification/api/dto/UpdateNotificationPreferenceRequest.java
NEW   src/main/java/com/example/mealprep/notification/api/dto/DeliveryLogEntryDto.java
NEW   src/main/java/com/example/mealprep/notification/api/dto/CreateNotificationRequest.java                       (internal)
NEW   src/main/java/com/example/mealprep/notification/api/dto/NotificationListFilter.java
NEW   src/main/java/com/example/mealprep/notification/api/mapper/NotificationMapper.java
NEW   src/main/java/com/example/mealprep/notification/api/mapper/NotificationPreferenceMapper.java
NEW   src/main/java/com/example/mealprep/notification/api/mapper/DeliveryLogMapper.java

NEW   src/main/java/com/example/mealprep/notification/event/ProvisionEventListener.java
NEW   src/main/java/com/example/mealprep/notification/event/NutritionEventListener.java
NEW   src/main/java/com/example/mealprep/notification/event/PlannerEventListener.java
NEW   src/main/java/com/example/mealprep/notification/event/NotificationCreatedEvent.java

NEW   src/main/java/com/example/mealprep/notification/validation/ValidQuietHours.java
NEW   src/main/java/com/example/mealprep/notification/validation/QuietHoursValidator.java

NEW   src/main/java/com/example/mealprep/notification/config/NotificationProperties.java

NEW   src/main/java/com/example/mealprep/notification/exception/NotificationException.java
NEW   src/main/java/com/example/mealprep/notification/exception/NotificationNotFoundException.java
NEW   src/main/java/com/example/mealprep/notification/exception/NotificationStateTransitionException.java
NEW   src/main/java/com/example/mealprep/notification/exception/NotificationPreferenceNotFoundException.java

NEW   src/main/resources/openapi/paths/notification.yaml
NEW   src/main/resources/openapi/schemas/notification.yaml
MOD   src/main/resources/openapi/openapi.yaml                                       (register the notification path/schema includes + the Notifications tag)

MOD   src/main/java/com/example/mealprep/config/GlobalExceptionHandler.java         (3 new handlers)

NEW   src/test/java/com/example/mealprep/notification/NotificationBoundaryTest.java
NEW   src/test/java/com/example/mealprep/notification/NotificationServiceImplTest.java
NEW   src/test/java/com/example/mealprep/notification/NotificationDispatcherTest.java
NEW   src/test/java/com/example/mealprep/notification/QuietHoursEvaluatorTest.java
NEW   src/test/java/com/example/mealprep/notification/NotificationDebouncerTest.java
NEW   src/test/java/com/example/mealprep/notification/NotificationKindResolverTest.java
NEW   src/test/java/com/example/mealprep/notification/QuietHoursValidatorTest.java
NEW   src/test/java/com/example/mealprep/notification/InAppDeliveryChannelTest.java
NEW   src/test/java/com/example/mealprep/notification/NotificationsControllerIT.java
NEW   src/test/java/com/example/mealprep/notification/NotificationPreferencesControllerIT.java
NEW   src/test/java/com/example/mealprep/notification/EventListenerIT.java
NEW   src/test/java/com/example/mealprep/notification/DispatcherDebounceIT.java
NEW   src/test/java/com/example/mealprep/notification/DispatcherQuietHoursIT.java
NEW   src/test/java/com/example/mealprep/notification/DeliveryChannelInterfaceIT.java
NEW   src/test/java/com/example/mealprep/notification/NotificationCreatedEventIT.java
NEW   src/test/java/com/example/mealprep/notification/testdata/NotificationTestData.java

MOD   src/test/java/com/example/mealprep/archunit/ModuleBoundaryTest.java
```

Total: ~55 new + 3 mods. Estimated agent runtime 8-12 hours (largest ticket in this wave — full module from scratch).

## Dependencies

- **Hard dependency**: `core-01-decision-log` (merged) — `MealPrepEvent`, `ScopeChangedEvent` base classes.
- **Hard dependency**: `auth-01a` (merged) — session-cookie auth.
- **Hard dependency**: `tickets/core/02b-origin-tracking-foundation.md` — `OriginAwareEvent` interface for `NotificationCreatedEvent`.
- **Hard dependency**: producer events exist (`ItemNearingExpiryEvent`, `ItemSpoiledEvent`, `DefrostReminderEvent` from provisions; `NutritionIntakeDivergedEvent`, `HealthDirectiveReceivedEvent` from nutrition; `PrepReminderEvent`, `ReoptSuggestedEvent`, `PlanGeneratedEvent` from planner). Verify all 8 exist; if any are missing, that producer module's ticket is the prerequisite.
- **Sibling**: `tickets/notification/01b-scanners.md` (scheduled scanners) — depends on 01a.

## Acceptance / DoD

- [ ] `./mvnw -Dspotless.check.skip=true verify` passes
- [ ] `./mvnw spotless:apply` then `./mvnw spotless:check` clean
- [ ] CI green (build + spotless + OpenAPI lint + ArchUnit + contract tests)
- [ ] All edge-case items above ticked
- [ ] All 8 producer events successfully drive a notification end-to-end in `EventListenerIT`
- [ ] PR description includes the full event-to-notification table validation

## What's NOT in scope

- **Scheduled scanners** (expiry, defrost, prep, intake-divergence, staple replenishment) — `tickets/notification/01b-scanners.md`.
- **Push / Email / WebSocket channels** — deferred per `lld/notification.md` §Out of Scope.
- **Notification copy / wording** — UX phase.
- **Retention sweep** (90-day delete of READ/DISMISSED/ACTIONED) — future.
- **Per-household shared inbox** — v1 routes to primary user only.

Squash-merge with: `feat(notification): 01a — core module (entities, dispatcher, 8 listeners, REST) (Tier B B2)`
