@notification
Feature: Notification — inbox reads, preference read/update, mark-read/dismiss lifecycle, errors
  The Notification System is a cross-cutting, read-only delivery surface (see
  e2e/pathways/notification.md): an in-app inbox plus per-user preferences. This
  Batch-3 feature exercises the user-facing CRUD surface that is buildable GREEN
  over HTTP from a fresh user and @pendings the scanner/event-driven emission paths
  (which need time-travel or cross-module commits) with a precise reason each.

  The load-bearing findings that shape this feature (from reading NotificationsController,
  NotificationPreferencesController, and NotificationServiceImpl):
  - PREFERENCES auto-seed on read. GET /api/v1/notifications/preferences ensures a
    defaults row first, so a fresh user always gets 200 (never 404). PUT replaces the
    document with an optimistic expectedVersion (stale -> 409) and requires enabledKinds
    to cover EXACTLY the NotificationKind enum.
  - The INBOX is reactive — there is NO public HTTP path to create a notification
    (NotificationServiceImpl.create is listener-facing only). So a fresh user's list is
    an empty Page, and mark-read/dismiss/action/transition + ownership tests need a real
    row first: an e2e seeder (E2eNotificationSeedController, @Profile e2e) persists ONE
    UNREAD HEALTH_DIRECTIVE_RECEIVED notification via the public create command.
  - Mark-read is NOT idempotent. The status state machine allows UNREAD->READ but
    READ->READ is illegal -> 409 (NotificationStateTransition). UNREAD->DISMISSED is
    legal; DISMISSED is terminal.
  - Unknown / other-user notification id -> 404 (the read is scoped to the caller).

  Each scenario registers its OWN fresh user (D5 self-contained data) and asserts only
  on THIS user's notifications/preferences (self-scoped) — never global counts — so the
  feature runs in both clean and soak mode.

  # ----- Preferences (green: auto-seed read + update + lock) -----

  @smoke
  # NOTIF-08-adjacent: a fresh user's preferences auto-seed on first read (200, defaults).
  Scenario: A fresh user reads auto-seeded notification preferences
    Given a fresh registered and logged-in user
    When they read their notification preferences
    Then the notification preferences are returned with defaults for this user

  # NOTIF-08: the user enables quiet hours via a full-replacement PUT; the change persists.
  Scenario: A user updates their notification preferences
    Given a fresh registered and logged-in user
    And the user has read their notification preferences
    When they enable quiet hours in their notification preferences
    Then the quiet-hours change is reflected on a read for this user

  # NOTIF-08 (optimistic lock): a preferences PUT with a stale expectedVersion is a 409.
  Scenario: A preferences update with a stale version is rejected as a conflict
    Given a fresh registered and logged-in user
    And the user has read their notification preferences
    When they update preferences with a stale expected version
    Then the preferences update is rejected as a conflict

  # ----- Inbox reads (green: empty-state + summary) -----

  @smoke
  # NOTIF-02: a brand-new user's notification list is an empty page, not an error.
  Scenario: The notification list is empty for a fresh user
    Given a fresh registered and logged-in user
    When they list their notifications
    Then the notification list is empty for this user

  # NOTIF-01-adjacent: the badge summary is all-zero for a fresh user.
  Scenario: The notification summary is zero for a fresh user
    Given a fresh registered and logged-in user
    When they read their notification summary
    Then the notification summary counts are zero for this user

  # NOTIF-05: marking a non-existent notification read is a clean not-found.
  Scenario: Marking a non-existent notification read returns not found
    Given a fresh registered and logged-in user
    When they mark a random non-existent notification read
    Then the notification mark-read is rejected as not found

  # NOTIF cross-cutting: listing notifications with no session is denied.
  Scenario: Listing notifications with no session is denied
    Given a fresh registered and logged-in user
    When an anonymous client lists notifications with no session
    Then the request is rejected as unauthenticated

  # ----- Inbox lifecycle (green via the e2e seeder) -----

  @smoke
  # NOTIF-01 + NOTIF-03: a seeded UNREAD notification is listed, readable by id, and
  # marks read (UNREAD -> READ); the read state persists on a re-read.
  Scenario: A user lists and marks a seeded notification read
    Given a fresh registered and logged-in user
    And the user has a seeded unread notification
    When they list their notifications
    Then the seeded notification is listed as unread for this user
    When they mark that notification read
    Then the notification is read for this user

  # NOTIF-04: re-marking an already-READ notification is an illegal transition (409).
  Scenario: Re-marking a read notification is rejected as an illegal transition
    Given a fresh registered and logged-in user
    And the user has a seeded unread notification
    When they mark that notification read
    Then the notification is read for this user
    When they mark that notification read again
    Then the notification transition is rejected as a conflict

  # NOTIF-03 (dismiss leg): a seeded notification can be dismissed (UNREAD -> DISMISSED).
  Scenario: A user dismisses a seeded notification
    Given a fresh registered and logged-in user
    And the user has a seeded unread notification
    When they dismiss that notification
    Then the notification is dismissed for this user

  # NOTIF-01: a seeded notification's delivery-log is readable (empty for a seeded row,
  # which never went through the delivery channel).
  Scenario: A user reads a seeded notification's delivery log
    Given a fresh registered and logged-in user
    And the user has a seeded unread notification
    When they read that notification's delivery log
    Then the notification delivery log is empty for this notification

  # ----- Scheduled scanner (green via an e2e on-demand scan trigger) -----

  # NOTIF-09 (expiry-warning scan). The expiry scanner is @Scheduled (daily 06:00) and reads
  # Provisions inventory at a wall-clock threshold, so the black-box suite cannot wait for the
  # cron. An e2e-profile-only trigger (E2eNotificationScanController, POST
  # /test-support/notification/run-expiry-scan) invokes the SAME scan() the @Scheduled method
  # delegates to. The scenario sets up the REAL precondition over HTTP — a fridge inventory item
  # expiring within the 2-day fridge threshold via POST /api/v1/provisions/inventory — fires the
  # scan, then POLLS the inbox: the scan publishes ItemNearingExpiryEvent which the notification
  # listener turns into a PROVISION_ITEM_NEAR_EXPIRY notification AFTER_COMMIT (async).
  Scenario: An expiry-warning notification appears after the scan
    Given a fresh registered and logged-in user
    And the user has a fridge inventory item expiring within the expiry-warning window
    When the expiry-warning scan is triggered
    Then an expiry-warning notification eventually appears for this user

  # ----- @pending: event-driven feedback-confirmation has no notification consumer in the product -----

  @pending
  # NOTIF-16 (feedback-confirmation notification). PENDING — and CANNOT be un-pended with a test
  # hook: the product has NO consumer for FeedbackProcessedEvent in the notification module.
  # NotificationKindResolver maps 9 producer events (provisions/nutrition/health/planner/staple)
  # but has no resolve(FeedbackProcessedEvent), there is no FEEDBACK_CONFIRMATION NotificationKind,
  # and the only AFTER_COMMIT listener of that event (feedback's PreferenceDeltaBatchTrigger) drives
  # the preference batch-delta pipeline, NOT a notification. So no feedback-confirmation notification
  # is ever produced — the HLD's NOTIF-16 is an unimplemented design-gap (e2e/pathways: notification.md
  # action #13 / appendix), not a timing/orchestration limitation. Un-pend ONLY when the product wires
  # a FeedbackProcessedEvent->notification listener. The feedback routing itself is covered by the
  # feedback suite's routed-state assertions.
  Scenario: A feedback-confirmation notification is produced after routing
    Given a fresh registered and logged-in user
    When they read their notification summary
    Then the notification summary counts are zero for this user
