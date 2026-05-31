# Notification Module — LLD

*Implementation specification for the cross-cutting notification subsystem: event-driven subscriber that translates module events into user-visible notifications, persists them, applies quiet hours and debouncing, and dispatches through a pluggable channel layer (in-app DB only in v1). Translates the Notification System section of [system-overview.md](../design/system-overview.md#notification-system) and the cross-module event catalogue in [technical-architecture.md](../design/technical-architecture.md#event-catalogue) into a buildable Spring Boot module.*

## Scope

This document specifies the `notification` module — package layout, JPA entities, Flyway migrations, repositories, service interfaces, DTOs, mappers, REST controllers, validation, events, business-logic flows, transaction boundaries, and the test plan. Conventions defer to [lld/style-guide.md](style-guide.md); this LLD restates a rule only when the module-specific application matters.

The module is a cross-cutting subscriber: other modules publish domain events; this module transforms a subset of those into `Notification` rows, optionally bundles them, persists them, and routes through a channel. The only outbound event is `NotificationCreatedEvent`, the future hook for live in-app delivery (WebSocket / SSE) — deferred per [Out of Scope](#out-of-scope). Where the HLD is silent, this LLD chooses and flags — see **Worth user review** notes inline.

---

## Package Layout

```
com.example.mealprep.notification/
├── NotificationModule.java                  facade re-exporting query + update services
├── api/
│   ├── controller/                          NotificationsController, NotificationPreferencesController
│   ├── dto/                                 records (see DTOs)
│   └── mapper/                              NotificationMapper, NotificationPreferenceMapper, DeliveryLogMapper
├── domain/
│   ├── entity/                              Notification, NotificationPreference, DeliveryLog,
│   │                                        NotificationKind, NotificationPayload (+ status/severity/outcome enums)
│   ├── repository/                          package-private Spring Data interfaces
│   │                                        (Notification/Preference/DeliveryLog + the five scanner dispatch-log repos)
│   └── service/
│       ├── NotificationQueryService.java    public interface
│       ├── NotificationUpdateService.java   public interface
│       ├── NotificationServiceImpl.java     single impl of both
│       └── internal/                        package-private — see internal helpers below
│           ├── NotificationDispatcher (+ NotificationDispatcherImpl)
│           ├── NotificationDispatcherFacade   public listener-facing bridge (see Service Interfaces)
│           ├── NotificationKindResolver       event → NotificationDraft (single source of truth for §Consumed)
│           ├── QuietHoursEvaluator
│           ├── NotificationDebouncer
│           ├── NotificationDefaults / NotificationDraft
│           └── delivery/                    DeliveryChannel SPI + InAppDeliveryChannel + DeliveryChannelRegistry
├── scanner/                                 scheduled producers — see §Scanners
│   ├── ExpiryWarningScanner, DefrostReminderScanner, PrepReminderScanner,
│   │   NutritionAlertScanner, StapleReplenishmentScanner
│   ├── config/                              ScannerProperties (@ConfigurationProperties)
│   └── internal/                            ScannerSupport (base), DispatchLogCleanupScheduler,
│                                            entity/ (five *DispatchLog idempotency-log entities)
├── event/                                   ProvisionEventListener, NutritionEventListener, PlannerEventListener,
│                                            FeedbackEventListener, NotificationCreatedEvent (published),
│                                            StapleReplenishmentNeededEvent (defined here — see §Scanners)
├── exception/                               module-root + per-failure subclasses
├── validation/                              @ValidQuietHours validator
├── config/                                  NotificationProperties (@ConfigurationProperties)
└── testing/                                 E2e seed/scan controllers (test-profile only)
```

`NotificationModule.java` re-exports `NotificationQueryService` and `NotificationUpdateService`. The internal `NotificationDispatcher` is package-private — listeners reach it through the public `NotificationDispatcherFacade` only.

---

## Database

Migrations live under `src/main/resources/db/migration/` with the project-wide timestamp scheme from [technical-architecture.md §Migrations](../design/technical-architecture.md#migrations):

```
V20260507100000__notification_create_notifications.sql
V20260507100100__notification_create_notification_preferences.sql
V20260507100200__notification_create_delivery_log.sql
R__notification_seed_default_preferences.sql            (repeatable — default kind toggles for new users; see flow F8)
```

### V20260507100000 — Notifications

```sql
CREATE TABLE notifications (
    id                    uuid PRIMARY KEY,
    user_id               uuid NOT NULL,
    household_id          uuid,                         -- populated for household-scoped kinds, null for per-user
    kind                  varchar(64) NOT NULL,         -- NotificationKind enum
    severity              varchar(16) NOT NULL,         -- INFO | ATTENTION | URGENT
    title                 varchar(200) NOT NULL,
    body                  varchar(1000) NOT NULL,
    payload               jsonb NOT NULL,               -- kind-specific NotificationPayload
    status                varchar(16) NOT NULL,         -- UNREAD | READ | DISMISSED | ACTIONED
    action_target_uri     varchar(512),                 -- deep link
    bundle_count          integer NOT NULL DEFAULT 1,   -- > 1 if this row absorbed siblings
    bundle_keys           jsonb,                        -- ids of bundled origins
    source_event_id       uuid,
    trace_id              uuid,
    created_at            timestamptz NOT NULL,
    read_at               timestamptz,
    actioned_at           timestamptz,
    dismissed_at          timestamptz,
    optimistic_version    bigint NOT NULL DEFAULT 0     -- @Version
);
-- Inbox listing.
CREATE INDEX idx_notifications_user_status_created ON notifications (user_id, status, created_at DESC);
-- Debouncer lookup of open rows for (user, kind) within the dedup window.
CREATE INDEX idx_notifications_user_kind_created ON notifications (user_id, kind, created_at DESC);
-- Cheap unread badge count.
CREATE INDEX idx_notifications_unread ON notifications (user_id) WHERE status = 'UNREAD';
```

`payload` mapped to a sealed-record tree via `@Type(JsonType.class)` — see [Notification Kinds](#notification-kinds). `title`/`body` denormalised so the inbox endpoint never re-renders copy from payload. `household_id` populated for kinds shared across a household (e.g. `PROVISION_ITEM_NEAR_EXPIRY`), null for per-user kinds (e.g. `NUTRITION_INTAKE_DIVERGED`) — not specified by the HLD, **worth user review**.

### V20260507100100 — Notification preferences

```sql
CREATE TABLE notification_preferences (
    id                       uuid PRIMARY KEY,
    user_id                  uuid NOT NULL UNIQUE,
    enabled_kinds            jsonb NOT NULL,                          -- map<NotificationKind, boolean>
    quiet_hours_enabled      boolean NOT NULL DEFAULT false,
    quiet_hours_start        time,                                    -- local time in user's tz
    quiet_hours_end          time,                                    -- may wrap midnight
    timezone                 varchar(64) NOT NULL DEFAULT 'Europe/London',
    debounce_window_minutes  integer NOT NULL DEFAULT 30,
    optimistic_version       bigint NOT NULL DEFAULT 0,
    created_at               timestamptz NOT NULL,
    updated_at               timestamptz NOT NULL
);
CREATE UNIQUE INDEX idx_notification_preferences_user ON notification_preferences (user_id);
```

`enabled_kinds` is JSONB rather than a child table because the kind set is a closed enum and the read pattern is "all toggles for a user" — a child table would force an extra join on every dispatch. **Worth user review.**

### V20260507100200 — Delivery log

```sql
CREATE TABLE notification_delivery_log (
    id              uuid PRIMARY KEY,
    notification_id uuid NOT NULL REFERENCES notifications(id) ON DELETE CASCADE,
    channel         varchar(32) NOT NULL,    -- IN_APP | PUSH (future) | EMAIL (future)
    outcome         varchar(16) NOT NULL,    -- DELIVERED | SKIPPED | DEFERRED | FAILED
    skip_reason     varchar(64),             -- DISABLED_BY_PREF | QUIET_HOURS | DEDUPED_INTO_BUNDLE | CHANNEL_UNAVAILABLE
    detail          jsonb,                   -- channel-specific
    attempted_at    timestamptz NOT NULL
);
CREATE INDEX idx_delivery_log_notification ON notification_delivery_log (notification_id);
CREATE INDEX idx_delivery_log_channel_attempted ON notification_delivery_log (channel, attempted_at DESC);
```

Append-only audit. Per-channel reliability concerns are out of scope for v1; each delivery attempt — including structured skips — is one row.

### R__notification_seed_default_preferences.sql

Repeatable migration providing the default `enabled_kinds` map (every kind ON by default, except `PLANNER_PLAN_GENERATED` — see below). Used by `ensurePreferencesForUser` when a user has no row yet. Repeatable because the kind set evolves.

---

## Notification Kinds

Closed enum, one value per source event:

```java
public enum NotificationKind {
    PROVISION_ITEM_NEAR_EXPIRY,    // ItemNearingExpiryEvent
    PROVISION_ITEM_SPOILED,        // ItemSpoiledEvent
    PROVISION_DEFROST_REMINDER,    // DefrostReminderEvent
    NUTRITION_INTAKE_DIVERGED,     // NutritionIntakeDivergedEvent
    HEALTH_DIRECTIVE_RECEIVED,     // HealthDirectiveReceivedEvent
    PLANNER_PREP_REMINDER,         // PrepReminderEvent
    PLANNER_REOPT_SUGGESTED,       // ReoptSuggestedEvent
    PLANNER_PLAN_GENERATED,        // PlanGeneratedEvent (optional, default OFF)
    STAPLE_REPLENISHMENT_NEEDED,   // StapleReplenishmentNeededEvent (notification/01b — staple scanner)
    FEEDBACK_CONFIRMATION          // FeedbackProcessedEvent (NOTIF-16 — positive-outcome only)
}
```

`STAPLE_REPLENISHMENT_NEEDED` is raised by the weekly `StapleReplenishmentScanner` (see [Scanners](#scanners)); its producer `StapleReplenishmentNeededEvent` is defined inside this module rather than a producer module (the scanner is the sole producer and the listener the sole consumer). `FEEDBACK_CONFIRMATION` is a positive-outcome confirmation that the user's feedback was applied — it fires **only** when ≥1 destination actually applied a change, gated in `FeedbackEventListener` (see [Consumed](#consumed)).

`PLANNER_PLAN_GENERATED` is optional per the brief — `PlanGeneratedEvent` is mostly an analytics signal per [meal-planner.md](../design/meal-planner.md#observability) and surfacing it as a notification doubles up with the UI's natural "your plan is ready" state. **Default OFF**, toggleable via preferences. **Worth user review.** The recipe module does not publish notifications directly — its pending-change UI per [recipe-system.md](../design/recipe-system.md) is client-rendered from query data.

Each kind carries a kind-specific payload record via a sealed interface (Jackson polymorphic deserialisation via `@JsonTypeInfo(use = NAME, property = "kind")`):

```java
public sealed interface NotificationPayload permits
    ItemNearExpiryPayload, ItemSpoiledPayload, DefrostReminderPayload,
    NutritionDivergedPayload, HealthDirectivePayload,
    PrepReminderPayload, ReoptSuggestedPayload, PlanGeneratedPayload,
    StapleReplenishmentPayload, FeedbackConfirmationPayload {

    NotificationKind kind();

    record ItemNearExpiryPayload(NotificationKind kind, List<UUID> inventoryItemIds,
                                 LocalDate earliestExpiry, int itemCount)
        implements NotificationPayload {}

    record ItemSpoiledPayload(NotificationKind kind, List<UUID> inventoryItemIds,
                              List<String> ingredientMappingKeys)
        implements NotificationPayload {}

    record DefrostReminderPayload(NotificationKind kind, UUID inventoryItemId,
                                  UUID plannedMealSlotId, Instant defrostBy)
        implements NotificationPayload {}

    record NutritionDivergedPayload(NotificationKind kind, LocalDate date,
                                    String nutrientKey, BigDecimal targetValue,
                                    BigDecimal actualValue, BigDecimal divergencePct)
        implements NotificationPayload {}

    record HealthDirectivePayload(NotificationKind kind, UUID directiveId,
                                  String summary)
        implements NotificationPayload {}

    record PrepReminderPayload(NotificationKind kind, UUID plannedMealSlotId,
                               UUID recipeId, String prepStep, Instant prepBy)
        implements NotificationPayload {}

    record ReoptSuggestedPayload(NotificationKind kind, UUID planId,
                                 String triggerSummary, List<UUID> affectedSlotIds)
        implements NotificationPayload {}

    record PlanGeneratedPayload(NotificationKind kind, UUID planId, int generation)
        implements NotificationPayload {}

    record StapleReplenishmentPayload(NotificationKind kind, List<UUID> inventoryItemIds,
                                      List<String> ingredientMappingKeys, BigDecimal lowestStockRatio)
        implements NotificationPayload {}

    // appliedDestinations are Destination names as plain strings — keeps the notification
    // module off a hard dependency on the feedback SPI enum.
    record FeedbackConfirmationPayload(NotificationKind kind, UUID feedbackId,
                                       List<String> appliedDestinations)
        implements NotificationPayload {}
}
```

---

## Entities

Standard style-guide shape: UUID `@Id` set application-side, `@Version` on mutable roots, audit columns via `@CreatedDate`/`@LastModifiedDate`, Lombok `@Getter @Setter @Builder @NoArgsConstructor(access = PROTECTED) @AllArgsConstructor`, JSONB via `@Type(JsonType.class)`.

| Entity | Notes |
|---|---|
| `Notification` | Aggregate root. `kind` `@Enumerated(STRING)`. `payload` is sealed `NotificationPayload` via JSONB. Enums for `status` (`UNREAD`/`READ`/`DISMISSED`/`ACTIONED`) and `severity` (`INFO`/`ATTENTION`/`URGENT`). `@Version Long optimisticVersion`. |
| `NotificationPreference` | Aggregate root. `userId` unique. `enabledKinds` is `Map<NotificationKind, Boolean>` via JSONB. `quietHoursStart`/`End` as `LocalTime`. `timezone` resolved to `ZoneId` at evaluation time. `@Version Long optimisticVersion`. |
| `DeliveryLog` | Append-only. `outcome` and nullable `skipReason` enums. No `@Version`, no `@LastModifiedDate`. `@ManyToOne` lazy back to `Notification` for cascade-delete. |

Module-local enums: `NotificationStatus`, `NotificationSeverity`, `DeliveryChannel.Channel`, `DeliveryOutcome`, `DeliverySkipReason`.

---

## DTOs

All DTOs are Java records per the style guide.

```java
public record NotificationDto(
    UUID id, UUID userId, UUID householdId,
    NotificationKind kind, NotificationSeverity severity,
    String title, String body, NotificationPayload payload,
    NotificationStatus status, String actionTargetUri,
    int bundleCount, List<String> bundleKeys, UUID traceId,
    Instant createdAt, Instant readAt, Instant actionedAt, Instant dismissedAt,
    long version
) {}

public record NotificationSummaryDto(int unreadCount, int attentionCount, int urgentCount, Instant generatedAt) {}

public record NotificationPreferenceDto(
    UUID id, UUID userId,
    Map<NotificationKind, Boolean> enabledKinds,
    boolean quietHoursEnabled, LocalTime quietHoursStart, LocalTime quietHoursEnd,
    String timezone, int debounceWindowMinutes, long version
) {}

public record UpdateNotificationPreferenceRequest(
    @NotNull Map<@NotNull NotificationKind, @NotNull Boolean> enabledKinds,
    boolean quietHoursEnabled,
    @ValidQuietHours LocalTime quietHoursStart,
    LocalTime quietHoursEnd,
    @NotBlank String timezone,
    @Min(0) @Max(360) int debounceWindowMinutes,
    long expectedVersion
) {}

public record DeliveryLogEntryDto(
    UUID id, UUID notificationId, DeliveryChannel.Channel channel,
    DeliveryOutcome outcome, DeliverySkipReason skipReason, Instant attemptedAt
) {}

// Internal — listener → NotificationUpdateService.create(). Never exposed via REST.
public record CreateNotificationRequest(
    UUID userId, UUID householdId,
    NotificationKind kind, NotificationSeverity severity,
    String title, String body, NotificationPayload payload,
    String actionTargetUri, UUID sourceEventId, UUID traceId
) {}

public record NotificationListFilter(Set<NotificationStatus> statuses, Set<NotificationKind> kinds, Instant since) {}
```

---

## Mappers

MapStruct interfaces, `@Mapper(componentModel = "spring")`. `NotificationMapper`, `NotificationPreferenceMapper`, `DeliveryLogMapper` — one per entity-DTO pair, default field-name mapping. `NotificationPayload` records pass through unchanged between entity and DTO; the JSONB type handler does the persistence-side conversion.

---

## Repositories

Package-private. Cross-module access goes through service interfaces only.

```java
interface NotificationRepository extends JpaRepository<Notification, UUID> {

    Optional<Notification> findByIdAndUserId(UUID id, UUID userId);

    Page<Notification> findByUserIdAndStatusInOrderByCreatedAtDesc(
        UUID userId, Collection<NotificationStatus> statuses, Pageable pageable);

    Page<Notification> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    long countByUserIdAndStatus(UUID userId, NotificationStatus status);

    // Debouncer: open (UNREAD) notifications of a given (user, kind) within the dedup
    // window, newest-first. Aggregate kinds page (0,1) — the newest row is the target.
    // Per-key kinds page a bounded window and scan for the row whose bundle_keys carries
    // the draft's key, so a newer different-key row never hides an older same-key row (F9).
    @Query("""
        select n from Notification n
        where n.userId = :userId
          and n.kind = :kind
          and n.status = 'UNREAD'
          and n.createdAt >= :since
        order by n.createdAt desc
        """)
    List<Notification> findOpenForBundling(
        @Param("userId") UUID userId,
        @Param("kind") NotificationKind kind,
        @Param("since") Instant since,
        Pageable pageable);
}

interface NotificationPreferenceRepository
        extends JpaRepository<NotificationPreference, UUID> {
    Optional<NotificationPreference> findByUserId(UUID userId);
}

interface DeliveryLogRepository extends JpaRepository<DeliveryLog, UUID> {
    Page<DeliveryLog> findByNotificationIdOrderByAttemptedAtDesc(UUID id, Pageable p);
}
```

The unread-count partial index supports `countByUserIdAndStatus(userId, UNREAD)` cheaply.

---

## Service Interfaces

Per the style guide, both module interfaces are implemented by a single `NotificationServiceImpl`. The internal `NotificationDispatcher` is package-private and **not** part of the public surface.

### `NotificationQueryService`

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

`getById` takes `userId` to enforce row-level access — every read is scoped to the caller per [technical-architecture.md §Frontend-Backend Contract](../design/technical-architecture.md#frontend-backend-contract). Household routing for v1 sends household-scoped notifications to the primary user only (see [Household routing](#household-routing)).

### `NotificationUpdateService`

```java
public interface NotificationUpdateService {
    // Listener-facing (via NotificationDispatcher); never bound to REST.
    NotificationDto create(CreateNotificationRequest request);

    NotificationDto markRead(UUID userId, UUID notificationId);
    NotificationDto markDismissed(UUID userId, UUID notificationId);
    NotificationDto markActioned(UUID userId, UUID notificationId);
    int markAllRead(UUID userId, Set<NotificationKind> kinds);     // returns rows updated; empty kinds == all

    NotificationPreferenceDto updatePreferences(UUID userId, UpdateNotificationPreferenceRequest request);

    // Idempotent — invoked by auth module on user creation and by dispatch as a guard.
    NotificationPreferenceDto ensurePreferencesForUser(UUID userId);
}
```

`create(...)` lives on the public interface (rather than hidden behind the dispatcher) so it's directly seam-testable.

### Internal `NotificationDispatcher`

Package-private bus inside `internal/`. Listeners route through it. Owns: preference + quiet-hours filtering, debouncing / bundling, persistence (via `NotificationUpdateService.create`), fan-out to `DeliveryChannel` impls, `NotificationCreatedEvent` publication.

```java
interface NotificationDispatcher {
    // Returns Optional.empty() when suppressed (preference OFF, quiet-hours, or absorbed into a bundle).
    Optional<UUID> dispatch(NotificationDraft draft);
}
```

`NotificationDraft` is a package-private working type — same shape as `CreateNotificationRequest` plus carry-through of the originating event (`Origin`, originating-event id and trace) for metric tagging and the published `NotificationCreatedEvent`.

#### `NotificationDispatcherFacade` (listener seam)

`NotificationDispatcher` and `NotificationKindResolver` are both package-private in `internal/`, but the event listeners live in the public `event/` package and must reach them. `NotificationDispatcherFacade` is the thin **public** bridge that closes that gap: one `dispatch<Event>(event)` method per producer event, each resolving the event to a `NotificationDraft` via the resolver and handing it to the dispatcher. Listeners inject only the facade — the dispatcher and resolver stay internal (listeners-only), preserving the single-producer assumption that the debouncer relies on. The facade is the only public type in `internal/`.

---

## REST Controllers

All endpoints under `/api/v1/notifications/...`. `userId` resolved server-side from auth context per [technical-architecture.md §Frontend-Backend Contract](../design/technical-architecture.md#frontend-backend-contract). OpenAPI: `@Tag(name = "Notifications")` on each controller, `@Operation` on each handler.

| Method | Path | Request | Response | Status |
|---|---|---|---|---|
| GET    | `/api/v1/notifications` | query: `status`, `kind`, `since`, `page`, `size` | `Page<NotificationDto>` | 200 |
| GET    | `/api/v1/notifications/summary` | — | `NotificationSummaryDto` | 200 |
| GET    | `/api/v1/notifications/{id}` | — | `NotificationDto` | 200 / 404 |
| POST   | `/api/v1/notifications/{id}/read` | — | `NotificationDto` | 200 / 404 / 409 |
| POST   | `/api/v1/notifications/{id}/dismiss` | — | `NotificationDto` | 200 / 404 / 409 |
| POST   | `/api/v1/notifications/{id}/action` | — | `NotificationDto` | 200 / 404 / 409 |
| POST   | `/api/v1/notifications/bulk/read` | `{ kinds: [...] }` | `{ updated: <n> }` | 200 |
| GET    | `/api/v1/notifications/preferences` | — | `NotificationPreferenceDto` | 200 |
| PUT    | `/api/v1/notifications/preferences` | `UpdateNotificationPreferenceRequest` | `NotificationPreferenceDto` | 200 / 400 / 409 |
| GET    | `/api/v1/notifications/{id}/delivery-log?page=&size=` | — | `Page<DeliveryLogEntryDto>` | 200 / 404 |

State transitions (`/read`, `/dismiss`, `/action`) are **POST**, not PUT — verbs over a resource per the style guide ("Verbs for non-CRUD actions"). The technical-architecture sketch uses `PUT /notifications/{id}/read`; this LLD picks POST for consistency with the planner's `/cook` and `/consume` verbs. **Worth user review.** `create` is not exposed via REST — exposing it invites fake-data and breaks the dispatcher's single-producer assumption for debouncing.

### Error responses

All error responses use RFC 9457 `ProblemDetail`. Module-specific exceptions and their mappings (handled in the project-wide `GlobalExceptionHandler`):

| Exception | Status | `type` URI |
|---|---|---|
| `NotificationNotFoundException` | 404 | `https://mealprep.example.com/problems/notification-not-found` |
| `NotificationStateTransitionException` | 409 | `https://mealprep.example.com/problems/notification-illegal-state` |
| `NotificationPreferenceNotFoundException` | 404 | `https://mealprep.example.com/problems/notification-preference-not-found` |
| `OptimisticLockException` (JPA) | 409 | `https://mealprep.example.com/problems/optimistic-lock` |
| `MethodArgumentNotValidException` | 400 | `errors[]` extension on ProblemDetail |

Module root: `NotificationException extends MealPrepException`.

`NotificationStateTransitionException` covers the legal state machine (see [Status state machine](#status-state-machine)).

---

## Validation

Standard Jakarta annotations on request records (`@NotNull`, `@NotBlank`, `@Min`/`@Max`, `@Valid`). One custom validator:

- **`@ValidQuietHours`** (class-level on `UpdateNotificationPreferenceRequest`) — asserts `quietHoursStart` and `quietHoursEnd` are either both null (and `quietHoursEnabled = false`) or both non-null (and `quietHoursEnabled = true`); rejects equal values (zero-length window); validates `timezone` via `ZoneId.of(...)`. Wrap-around windows (22:00 → 06:00) are allowed and handled by the evaluator.

Validation failures bubble up as `MethodArgumentNotValidException` → 400 ProblemDetails via global advice.

---

## Events

### Consumed

Most producer event classes are imported from their owner modules' `event/` packages — this module does not redefine them. The exception is `StapleReplenishmentNeededEvent`, defined inside this module because its sole producer (the staple scanner) and sole consumer (this listener) both live here (see [Scanners](#scanners)). Listeners live in four classes — `ProvisionEventListener` (incl. `onStapleReplenishmentNeeded`), `NutritionEventListener`, `PlannerEventListener`, and `FeedbackEventListener` — each injected with the public `NotificationDispatcherFacade`. Every handler is the same shape:

```java
@TransactionalEventListener(phase = AFTER_COMMIT)
public void onItemNearingExpiry(ItemNearingExpiryEvent event) {
    try { dispatcher.dispatchItemNearingExpiry(event); }   // facade resolves → dispatches
    catch (Exception e) { /* log + metric-count; never rethrow */ }
}
```

Listeners never throw — failures are logged and metric-counted; the publishing transaction has already committed and must not be affected.

**Event-to-notification mapping (single source of truth for handlers F1–F7):**

| Producer event | Listener handler | NotificationKind | Severity | Householded | Bundling key | Flow |
|---|---|---|---|---|---|---|
| `ItemNearingExpiryEvent` | `ProvisionEventListener.onItemNearingExpiry` | `PROVISION_ITEM_NEAR_EXPIRY` | `ATTENTION` | yes | `(userId, kind)` | F1 |
| `ItemSpoiledEvent` | `ProvisionEventListener.onItemSpoiled` | `PROVISION_ITEM_SPOILED` | `ATTENTION` | yes | `(userId, kind)` | F2 |
| `DefrostReminderEvent` | `ProvisionEventListener.onDefrostReminder` | `PROVISION_DEFROST_REMINDER` | `ATTENTION` | yes | per-`mealSlotId` | F3 |
| `NutritionIntakeDivergedEvent` | `NutritionEventListener.onNutritionIntakeDiverged` | `NUTRITION_INTAKE_DIVERGED` | `INFO`/`ATTENTION` | no | `(userId, kind, date)` | F4 |
| `HealthDirectiveReceivedEvent` | `NutritionEventListener.onHealthDirectiveReceived` | `HEALTH_DIRECTIVE_RECEIVED` | `URGENT` | no | per-`directiveId` | F5 |
| `PrepReminderEvent` | `PlannerEventListener.onPrepReminder` | `PLANNER_PREP_REMINDER` | `ATTENTION` | no | per-`mealSlotId` | F6 |
| `ReoptSuggestedEvent` | `PlannerEventListener.onReoptSuggested` | `PLANNER_REOPT_SUGGESTED` | `ATTENTION` | yes | `(userId, planId)` (replacement) | F7 |
| `PlanGeneratedEvent` | `PlannerEventListener.onPlanGenerated` | `PLANNER_PLAN_GENERATED` | `INFO` | yes | per-`planId` | F7 |
| `StapleReplenishmentNeededEvent` | `ProvisionEventListener.onStapleReplenishmentNeeded` | `STAPLE_REPLENISHMENT_NEEDED` | `INFO` | no | `(userId, kind)` | F12 |
| `FeedbackProcessedEvent` | `FeedbackEventListener.onFeedbackProcessed` | `FEEDBACK_CONFIRMATION` | `INFO` | no | per-`feedbackId` | F13 |

The technical-architecture catalogue lists `ExpiryApproachingEvent`; the brief uses `ItemNearingExpiryEvent`. Treating them as the same event — the brief's name takes precedence here; the listener binds to whichever class the provisions LLD ships. **Worth user review.**

`StapleReplenishmentNeededEvent` is emitted by the in-module `StapleReplenishmentScanner` (per [Scanners](#scanners)); per-batch deduplication happens upstream in the scanner's idempotency log, so the listener-side bundling key is the simple `(userId, kind)` aggregate.

**`FeedbackEventListener` fire-condition gate (NOTIF-16, F13).** Unlike the other listeners, `FeedbackEventListener` does **not** dispatch unconditionally — `FEEDBACK_CONFIRMATION` is a *positive-outcome confirmation* and must fire only when the user's feedback actually changed something. The gate lives in the listener (so the resolver maps unconditionally and the dispatcher never runs for a non-firing outcome):

```java
event.appliedDestinations() != null && !event.appliedDestinations().isEmpty()
    && !event.clarificationPending()
```

Gating on `appliedDestinations` (the *succeeded* subset) rather than `destinationsTouched` (every destination *attempted*) is the correctness point: a routed-but-all-FAILED feedback has a non-empty `destinationsTouched` yet applied nothing. So all-success and partial-success fire (the payload lists exactly the succeeded destinations); non-actionable/empty, clarification-pending and total-failure outcomes are skipped (each carries an empty `appliedDestinations` or sets `clarificationPending`). Per-destination failure detail on partial success (HLD-GAP G15) is deferred — v1 fires plainly when anything applied.

### Published

```java
public record NotificationCreatedEvent(
    UUID notificationId, UUID userId, UUID householdId,
    NotificationKind kind, NotificationSeverity severity,
    boolean wasBundled,                     // true when this dispatch absorbed a sibling
    UUID traceId, Instant occurredAt
) {}
```

Published via `ApplicationEventPublisher` from the dispatcher's transaction. No v1 listeners. The event is the **frontend WebSocket / SSE hook** — a future realtime delivery channel listens here and pushes to connected clients. The realtime channel is deferred (see [Out of Scope](#out-of-scope)); the event ships now so the contract is stable.

---

## Delivery Channels

```java
public interface DeliveryChannel {

    enum Channel { IN_APP, PUSH, EMAIL }     // PUSH/EMAIL not implemented in v1

    Channel channel();

    /**
     * Returns true if this channel is responsible for the given notification.
     * Lets us drive routing from kind/severity rather than a config map.
     */
    boolean accepts(Notification notification);

    DeliveryOutcome deliver(Notification notification);
}
```

Future `PushDeliveryChannel` / `EmailDeliveryChannel` impls plug in as Spring beans implementing `DeliveryChannel`; `DeliveryChannelRegistry` auto-wires the full `List<DeliveryChannel>` and resolves channels per notification.

`InAppDeliveryChannel.deliver(...)` writes one `DeliveryLog(IN_APP, DELIVERED)` row and returns — no network I/O. The in-app feed is a database query against `notifications`; "delivery" is just the marker that the row was committed.

The dispatcher iterates `registry.channelsFor(notification)` and records one delivery-log row per channel. Per-channel reliability (retries, dead letter) is out of scope for v1 — the in-app channel can't meaningfully fail. Future channels own their own Resilience4j policy.

---

## Business Logic Flows

### F1–F7: Event-to-notification flows

Every consumed-event flow shares the same shape (per-event differences are captured in the [event mapping table](#consumed)):

1. Listener fires `AFTER_COMMIT`. Calls `resolver.resolve(event) → NotificationDraft`.
2. Listener calls `dispatcher.dispatch(draft)`. Inside the dispatcher (one transaction):
3. **F8 — Preference + quiet-hours filter.** If suppressed → `DeliveryLog(SKIPPED, <reason>)`, return `Optional.empty()`.
4. **F9 — Debounce / bundle.** Recent open notification for `(userId, kind, bundlingKey)` within `debounce_window_minutes` → mutate it (`bundle_count++`, append `bundle_keys`, regenerate `body` from a kind template), write `DeliveryLog(SKIPPED, DEDUPED_INTO_BUNDLE)`, return that id.
5. Otherwise persist a new `Notification`, fan out across `DeliveryChannelRegistry.channelsFor(...)` (one `DeliveryLog` per channel), publish `NotificationCreatedEvent`, return the new id.

Per-flow specifics:

- **F4 `NutritionIntakeDivergedEvent`** — severity tiered by `divergencePct`: `≥ 0.40 → ATTENTION` else `INFO`. Threshold not specified by HLD — **worth user review**.
- **F5 `HealthDirectiveReceivedEvent`** — `URGENT`; deep-links to the directive review page owned by the nutrition module per [nutrition-model.md](../design/nutrition-model.md).
- **F7 `ReoptSuggestedEvent`** — bundling uses **replacement** semantics: a later re-opt overwrites `payload`/`body` (latest wins); `bundle_count` stays at 1. `PlanGeneratedEvent` is one-shot per `planId` when enabled.

The other flows (F1–F3, F6) follow the shared shape with the bundling key from the mapping table — no further specifics.

### F8: Preference + quiet-hours filter

`QuietHoursEvaluator.shouldDeliver(kind, preference, now)`:

1. Load `NotificationPreference` (call `ensurePreferencesForUser` on miss — seeds defaults from the repeatable migration).
2. `enabled_kinds[kind] == false` → `SKIP(DISABLED_BY_PREF)`.
3. `severity == URGENT` → `DELIVER` (urgent bypasses quiet hours; **worth user review**).
4. `quiet_hours_enabled == false` → `DELIVER`.
5. Resolve `now` to `LocalTime` in the preference's `ZoneId`. Inside `[quietHoursStart, quietHoursEnd)` with wrap-around semantics → `SKIP(QUIET_HOURS)`. Else → `DELIVER`.

When suppressed, the dispatcher still writes a `DeliveryLog` row with the structured `skipReason` so the user can see (via the delivery-log endpoint) why an expected notification didn't fire — see [Open questions](#open-questions) for the FK shape.

### F9: Debouncer

`NotificationDebouncer.findBundleTarget(userId, kind, bundlingKey, debounceWindowMinutes, now)`:

1. `since = now - debounce_window_minutes` (default 30, per-user-overridable).
2. **Aggregate kinds** (null `bundlingKey`, e.g. `PROVISION_ITEM_NEAR_EXPIRY`): `findOpenForBundling(userId, kind, since, PageRequest.of(0, 1))` — the single newest open row is the target. Empty → no target.
3. **Per-key kinds** (non-null `bundlingKey`, e.g. `PROVISION_DEFROST_REMINDER` per `mealSlotId`): fetch a **bounded page** of open rows newest-first and scan for the row whose `bundle_keys` contains the draft's key. A `LIMIT 1` lookup would only ever inspect the single newest row, so a newer open row of the same kind but a *different* key (e.g. another `mealSlotId`) would silently hide an older same-key row and split the bundle — the scan prevents that. No key-matching row in the page → no target (open a fresh notification).
4. On a target, mutate the existing notification (`bundle_count++`, append `bundle_keys`, regenerate `body` from a kind-specific template; `PLANNER_REOPT_SUGGESTED` replaces instead — latest wins, count stays 1). JPA `@Version` increments via the bundle target's optimistic-lock check.

Bundling key is kind-specific — see the [event mapping table](#consumed).

### F10–F11: User actions

`markRead` / `markDismissed` / `markActioned` each: `findByIdAndUserId` (404), validate the state transition (illegal → `NotificationStateTransitionException` 409), mutate `status` and the matching timestamp, return the DTO. No event published — these are user-local UI state.

`updatePreferences` is `@Transactional`, optimistic-lock-checked via `expectedVersion`, asserts the `enabled_kinds` map's key set matches the `NotificationKind` enum exactly, persists. No event published.

### F12: Staple replenishment

`StapleReplenishmentNeededEvent` (in-module producer — see [Scanners](#scanners)) follows the shared F1–F7 shape with an aggregate `(userId, kind)` bundling key and `INFO` severity. Per-batch dedup is the scanner's responsibility (its `(userId, scanDate)` idempotency log), so the listener path is otherwise unremarkable.

### F13: Feedback confirmation (NOTIF-16)

`FeedbackEventListener.onFeedbackProcessed` applies the positive-outcome gate (see [Consumed](#consumed)) **before** dispatching. When it fires, the flow is the shared shape with a per-`feedbackId` bundling key (a re-delivered event for the same entry collapses onto the one row — the "no storm" guarantee) and `INFO` severity. The payload's applied-destinations list is built from the event's `appliedDestinations` (the succeeded subset), so a partial success lists only the destinations that took.

---

## Scanners

Five `@Scheduled` scanners under `scanner/` are the in-module producers for kinds the source modules do not push directly (per `tickets/notification/01b-scanners.md` and `01c-scanners-real-times.md`). Each extends `ScannerSupport` (base supplying the injected `Clock` — the single time seam tests pin via `Clock.fixed` — plus the `ApplicationEventPublisher`), is gated by `@ConditionalOnProperty(mealprep.notification.scanners.<name>.enabled, matchIfMissing=true)`, reads its threshold/cron knobs from `ScannerProperties`, and is **idempotent** via a per-scanner dispatch-log table (so a re-run within the same window is a no-op). Each scanner exposes a `@Transactional scan()` (or `sweep()`) the unit/IT tests call directly; the production `@Scheduled` cron is far-future in the test profile so it never auto-fires under test. Scanners read collaborator data through other modules' **public** query services only (provisions/nutrition/planner/household), never their internals, and isolate per-user failures (one user's exception is logged + counted, the scan continues).

| Scanner | Default cadence | Emits | Source query | Idempotency key |
|---|---|---|---|---|
| `ExpiryWarningScanner` | daily 06:00 | `ItemNearingExpiryEvent` | `ProvisionQueryService` (per-location expiry threshold) | `(userId, scanDate)` |
| `DefrostReminderScanner` | every 15 min | `DefrostReminderEvent` | `ProvisionQueryService` (frozen candidates; v1 anchors on `expiryDate`, not slot meal time — see 01c) | `(inventoryItemId, defrostTargetTime)` |
| `PrepReminderScanner` | every 5 min | `PrepReminderEvent` | `PlanQueryService.getUpcomingSlots` (anchors on `UpcomingSlotView.mealTime()`) | `(slotId, prepStepAtTime)` |
| `NutritionAlertScanner` | daily 21:00 | `NutritionIntakeDivergedEvent` (per diverged nutrient) | `NutritionQueryService` (`divergencePct ≥ threshold`, default 0.30) | `(userId, alertDate, nutrientKey)` |
| `StapleReplenishmentScanner` | weekly Sun 10:00 | `StapleReplenishmentNeededEvent` (in-module event) | `ProvisionQueryService` (staples `LOW`/`OUT`) | `(userId, scanDate)` |

The scanner severity tiering is delegated downstream: the scanner emits the producer event and the `NotificationKindResolver` decides severity (e.g. `NUTRITION_INTAKE_DIVERGED` tiers `≥ 0.40 → ATTENTION`, the scanner only fires above its lower 0.30 alert threshold).

`DispatchLogCleanupScheduler` (in `scanner/internal/`, daily 02:00) is a maintenance sweep — not a producer, so it does **not** extend `ScannerSupport` — that hard-deletes dispatch-log rows older than `dispatchLogRetentionDays` (default 30) across all five idempotency-log tables to keep them bounded.

`StapleReplenishmentNeededEvent` is the one consumed event defined in this module (`event/`): the scanner is its only producer and the listener its only consumer, so there is no producer-module contract to publish from (decision §10 Option A in `01b-scanners.md`). It implements `core.events.ScopeChangedEvent` with `scopeKind = "staple-replenishment"`, `scopeId = userId`.

---

## Status state machine

```
                    ┌──────────────────────────────► DISMISSED
                    │                                    │
                  UNREAD ─────► READ ─────► ACTIONED     │ (terminal)
                    │             │                      │
                    └─────────────┴──────────────────────┘
```

Legal transitions:

| From | To | Triggered by |
|---|---|---|
| `UNREAD` | `READ`, `DISMISSED`, `ACTIONED` | controller |
| `READ` | `DISMISSED`, `ACTIONED` | controller |
| `ACTIONED` | `DISMISSED` | controller |
| `DISMISSED` | — (terminal) | — |

Anything else throws `NotificationStateTransitionException`. Enforced in the service impl. Bundling does not change status — a notification stays `UNREAD` regardless of how many drafts merge in.

---

## Household routing

HLD is silent. **Primary-user-only for v1.** When an event carries a `householdId`, the listener resolves to the household's primary user via `HouseholdQueryService.getPrimary(householdId)` and dispatches to that user only. `householdId` is stored on the row so a future "all members see household notifications" mode is a query change, not a schema change. **Worth user review.**

---

## Concurrency and Transactions

| Concern | Decision |
|---|---|
| `@Transactional` placement | All `NotificationServiceImpl` methods. Repositories never. |
| Read-method propagation | `@Transactional(readOnly = true)`. |
| Write-method propagation | Default REQUIRED. Listeners call `dispatcher.dispatch(...)` after the publisher's transaction has committed (`AFTER_COMMIT`); the dispatcher opens its own transaction. |
| Optimistic locking | `@Version` on `Notification` and `NotificationPreference`. `DeliveryLog` is append-only — no `@Version`. |
| Pessimistic locking | None for v1. |
| Debouncer race | Two concurrent dispatches for the same `(userId, kind)` could both find no bundle target and both insert a fresh notification. The race is bounded — at most two (since the third would find one of them inside the window). Acceptable for v1; if it bites in practice, switch the bundle-target lookup to a `pg_advisory_xact_lock(hash(userId, kind))`. **Worth user review.** |
| Event publication | `NotificationCreatedEvent` published from the dispatcher's transaction. Listeners (none in v1) use `@TransactionalEventListener(AFTER_COMMIT)`. |
| Cross-module read calls | The listeners do **not** call back into other modules' query services. The event payload carries everything needed to compose the notification — this preserves the AFTER_COMMIT contract and avoids a circular-event risk. |

---

## Test Plan

Unit tests use `@ExtendWith(MockitoExtension.class)`. Integration tests are `*IT.java` with Testcontainers Postgres. Names follow `methodName_scenario_expected`.

### Unit

| Class | Verifies |
|---|---|
| `NotificationServiceImplTest` | `create` happy path; `markRead`/`markDismissed`/`markActioned` legal and illegal transitions; `getById` enforces `userId` scoping; `markAllRead` kind filter; `updatePreferences` rejects stale `expectedVersion`. |
| `NotificationDispatcherTest` | Disabled-kind suppression (no row, `DeliveryLog(SKIPPED, DISABLED_BY_PREF)`); quiet-hours suppression; URGENT bypass; bundling increments `bundle_count`; per-id bundling kinds never bundle; `NotificationCreatedEvent` published once on persist, never on suppression. |
| `QuietHoursEvaluatorTest` | Wrap-around windows (22:00–06:00); timezone resolution (same `Instant` in `Europe/London` vs `America/Los_Angeles`). |
| `NotificationDebouncerTest` | Window math: events at T, T+10, T+25 (window=30) all bundle; T+31 opens a fresh notification. Cross-kind isolation. Replacement semantics for `PLANNER_REOPT_SUGGESTED`. Per-key match/mismatch, and an older same-key open row hidden behind a newer different-key row still bundles (not LIMIT 1). |
| `NotificationKindResolverTest` | One test per producer event → expected (kind, severity, payload, action URI, householdId). Severity tiering for `NUTRITION_INTAKE_DIVERGED`. |
| `*EventListenerTest` | Each listener calls resolver → dispatcher; dispatcher exceptions are caught and logged, never escape (Mockito `doThrow` + assert no throw). |
| `InAppDeliveryChannelTest` | `deliver` writes one `DeliveryLog(IN_APP, DELIVERED)`; `accepts` returns true for every kind in v1. |
| `QuietHoursValidatorTest` | Combinations of null/non-null start/end vs enabled flag; equal start/end rejected; unknown timezone rejected. |
| `*MapperTest` | MapStruct round-trips preserve all fields, including JSONB `payload` and `enabled_kinds` map. |

### Integration

| Class | Verifies |
|---|---|
| `NotificationsControllerIT` | Full HTTP cycle over MockMvc: list with filters, summary, GET by id (200/404 with `userId` scoping), state-transition POSTs (200/409), bulk `/read`, ProblemDetail shape. |
| `NotificationPreferencesControllerIT` | GET auto-seeds defaults; PUT happy/400/409; `@ValidQuietHours` end-to-end. |
| `EventListenerIT` | Each consumed event published via `ApplicationEventPublisher` produces the expected `Notification` row per the mapping table; `NotificationCreatedEvent` published exactly once; AFTER_COMMIT semantics — rolled-back publisher tx produces no notification. |
| `DispatcherDebounceIT` | Real DB. Three `ItemNearingExpiryEvent`s within the window collapse to one row with `bundle_count = 3`; a fourth past the window opens a second row. |
| `DispatcherQuietHoursIT` | Real DB. Quiet hours 22:00–06:00 (`Europe/London`): event at 23:30 local → suppressed (per Open Questions resolution); 07:00 local → delivered; URGENT at 23:30 → delivered. |
| `DeliveryChannelInterfaceIT` | A test-only second `DeliveryChannel` bean registers and receives delivery alongside the in-app channel — proves the SPI seam. |
| `FlywayMigrationIT` | Notification migrations boot cleanly; `spring.jpa.hibernate.ddl-auto=validate` passes; repeatable migration is idempotent. |
| `NotificationCreatedEventIT` | A test listener captures `NotificationCreatedEvent` and asserts the `notificationId` resolves via `NotificationQueryService.getById`. |

---

## Out of Scope

Deferred deliberately — these belong elsewhere or to a later phase:

- **Push (mobile) channel** — no mobile app yet; `DeliveryChannel` is the plug point.
- **Email channel** — no email infrastructure; same plug point.
- **WebSocket / SSE delivery for live in-app feed** — deferred to the frontend phase. A future `RealtimeDeliveryChannel` listens to `NotificationCreatedEvent`.
- **Specific notification copy / wording** — UX-writing phase. The `title` / `body` strings in this LLD are placeholders; the kind enum and payload schemas are stable.
- **Per-channel reliability** (retries, dead letter, bounce handling, push token refresh) — irrelevant for the in-app channel; future channels add their own Resilience4j policy.
- **Frontend / UI / API consumer concerns** — inbox layout, badge UI, in-context toasts.
- **Notification digest / weekly summary** — a possible future kind.
- **Per-household shared inbox** — v1 routes to primary user only; see [Household routing](#household-routing).
- **Retention / archival / aging of user-facing `notification` rows (GAP-39)** — **DEFERRED to v2** (decision recorded 2026-05-31; audit finding `xcut-5` / `xcut-completeness-5`, rated LOW). v1 ships **no** aging/archival sweep for the `notification` table and **no** `is_archived` flag or age cutoff: rows accumulate indefinitely. This is an accepted v1 position — notification rows grow slowly (one row per delivered notification, bundled where possible) and unbounded growth is a gradual *operational* concern, not a correctness or safety issue, so it does not gate the v1 test pass. NOTE: `DispatchLogCleanupScheduler` (in `notification.scanner.internal`) sweeps the **scanner dispatch-log** tables only — it does **not** touch the user-facing `notification` rows; do not mistake it for a notification-row retention policy. v2 plan (not committed): hard-delete or archive `READ`/`DISMISSED`/`ACTIONED` rows past a ~90-day cutoff via a `@Scheduled` job once volume estimates land. **Worth user review** for the exact cutoff + delete-vs-archive choice.
- **Admin / support visibility** of delivery logs — v1 scopes to the notification's owner only.

## Open Questions

- **Skip-log FK when no notification is created.** When quiet hours / disabled-kind suppresses a draft, should `DeliveryLog.notification_id` reference a synthetic persisted row (FK holds), be nullable, or should the skip not be logged? Tentative: persist the notification with `status = DISMISSED` and `dismissed_at = createdAt` — audit trail complete, never user-visible. **Worth user review.**
- **Re-emergence after read.** If a user reads a bundled notification and a new event of the same kind fires, open a new notification or re-open the read one? Tentative: always a new notification — `findOpenForBundling` already filters on `status = UNREAD`. **Worth user review.**
- **Event class naming.** `ExpiryApproachingEvent` (technical-architecture) vs `ItemNearingExpiryEvent` (brief). Listener binds to whichever the provisions LLD ships.
