# Module: Notification

## Purpose
Generates and serves in-app alerts and reminders. Listens to events from other modules and creates notifications. Runs scheduled checks for time-based triggers.

## Dependencies
- **→ Pantry.getExpiringWithinDays()** — items expiring soon
- **→ Pantry.getFreezerItemsNeedingDefrost()** — defrost reminders
- **→ Planner.getCurrentPlan()** — prep reminders
- **→ NutritionTracker (event: significant_deviation)** — nutrition alerts

## Data Model

### notification
```sql
CREATE TABLE notification (
    id              BIGSERIAL PRIMARY KEY,
    type            VARCHAR(30) NOT NULL,     -- expiry_warning/defrost_reminder/prep_reminder/nutrition_alert/review_ready
    title           VARCHAR(200) NOT NULL,
    body            TEXT NOT NULL,
    source_type     VARCHAR(30),              -- pantry_item/meal_slot/nutrition_log
    source_id       BIGINT,
    read            BOOLEAN DEFAULT FALSE,
    dismissed       BOOLEAN DEFAULT FALSE,
    trigger_at      TIMESTAMP NOT NULL,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_notif_trigger ON notification(trigger_at);
CREATE INDEX idx_notif_unread ON notification(read) WHERE NOT read;
```

## API

### GET /api/v1/notifications
Get notifications (unread first, then recent read).

**Query params:** `unreadOnly` (boolean, default false), `limit` (default 20)

**Response 200:**
```json
[
  {
    "id": 1,
    "type": "expiry_warning",
    "title": "Chicken breast expiring tomorrow",
    "body": "500g chicken breast in the fridge expires March 22. You're using it in Thursday's stir fry.",
    "read": false,
    "triggerAt": "2026-03-21T08:00:00Z"
  }
]
```

### GET /api/v1/notifications/count
Unread count (for badge).

**Response 200:** `{ "unread": 3 }`

### PUT /api/v1/notifications/{id}/read
Mark as read.

### PUT /api/v1/notifications/{id}/dismiss
Dismiss.

## Service Interface

```java
public interface NotificationService {
    List<NotificationDto> getNotifications(boolean unreadOnly, int limit);
    int getUnreadCount();
    void markAsRead(Long id);
    void dismiss(Long id);

    // Called by other modules to create notifications
    void create(CreateNotificationRequest request);

    // Scheduled checks (run by a cron/scheduler)
    void checkExpiringPantryItems();    // daily
    void checkDefrostReminders();       // daily
    void checkPrepReminders();          // hourly
}
```

## Scheduled Jobs

| Job | Frequency | Logic |
|-----|-----------|-------|
| Expiry check | Daily 8am | → Pantry.getExpiringWithinDays(2) → create expiry_warning for each |
| Defrost check | Daily 8am | Look at tomorrow's plan → any frozen ingredients? → defrost_reminder |
| Prep reminder | Hourly | Look at today's remaining meals → any needing advance prep? → prep_reminder |

## Event Listeners

| Event | Action |
|-------|--------|
| `nutrition.significant_deviation` | Create nutrition_alert |
| `health.review_ready` | Create review_ready notification |
| `grocery.substitution_detected` | Create substitution alert |
| `pantry.item_expiring_soon` | Create expiry_warning |

## Consumed By
- **Frontend** — displays notifications + badge count

## Events Emitted
None. Terminal module.
