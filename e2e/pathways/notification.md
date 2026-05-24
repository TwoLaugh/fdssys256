# Notification Domain — User-Pathway Catalogue

> Code-agnostic behavioural catalogue derived purely from the HLD design docs. Double duty: (a) source for E2E test scenarios; (b) behavioural spec for the frontend. No endpoints, HTTP verbs, class names, or DB tables — pure user/behaviour language. Where the HLD is silent on something a user would obviously need, it is flagged `[HLD-GAP]` rather than invented. **Caveat for this domain:** there is no dedicated notification HLD. The entire surface is reconstructed from five passing mentions across `system-overview.md`, `technical-architecture.md`, and `feedback-system.md`. As a result this domain is *unusually* gap-dense — almost every operational detail (quiet hours, preferences, dedup, scanner cadence, payload shape, read-state model) is implied-only or absent. Every such gap is flagged inline and consolidated in the appendix; none are resolved here.

---

## 1. Domain Summary

The Notification System is a **cross-cutting, read-only delivery surface**, not one of the three constraint loops and not a data model. Its job: surface alerts and reminders to the user **in-app**. It is purely reactive — it owns no domain state of its own beyond the notification record, and it **never writes to any data model** (per the wiring matrix it injects only the *query* services for Preference, Nutrition, and Provisions — no update services). It has two input channels:

1. **Event-driven** — it listens to `ApplicationEvent`s published by other modules after their transactions commit (`FeedbackProcessedEvent`, `ItemNearingExpiryEvent`, `HealthDirectiveReceivedEvent`, the `HouseholdMember*`/`HouseholdSettingsChanged` family) and turns each into a user-facing notification.
2. **Scheduled scanners** — periodic `@Scheduled` sweeps that read other modules' state (Provisions for expiry/defrost/staples, Planner for prep lead-times) and emit reminders when something becomes due.

In the three-loop architecture it serves **none** of the loops directly; it is one of the two cross-cutting services (alongside the AI Service). It is a *consumer* of the loops' outputs, not a participant. Its value is making the system feel proactive — "the system tells me when to defrost / what's expiring / when to start marinating / that I'm under protein / that a health directive is waiting."

## 2. Actors

| Actor | Role in this domain (per HLD) |
|---|---|
| **Primary user** | Recipient of notifications. Views the recent-notifications list, marks individual notifications read. Manages provisions/plan/staples elsewhere; the notification system reads that state. Whether the user can configure notification preferences/quiet hours/channels is unspecified `[HLD-GAP]`. |
| **Household member** | Has their own account; is alerted on household membership/settings changes (`HouseholdMember*` events listened by Notification). Whether household members receive the shared-provisions scanners (expiry/defrost/staple on the shared pantry) or only the primary user does is unspecified `[HLD-GAP]`. |
| **Scheduler / system clock (`@Scheduled`, "system-scheduled" actor)** | Drives the scanners: expiry check on Provisions, defrost reminder at `meal_time − defrost_lead_time_hours`, prep reminder from the plan, staple-replenishment reminder. Cadence/cron for each is not stated in the allowed HLDs `[HLD-GAP]`. |
| **Provisions module (event source + scanner target)** | Publishes `ItemNearingExpiryEvent` (itself fired by a scheduled check on Provisions); is read by the defrost and staple scanners. Cross-module touchpoint. |
| **Meal Planner (scanner target)** | Read by the prep-reminder scanner ("start marinating at 6pm") and by the defrost scanner (it owns the meal schedule that defrost lead-time is computed against). Cross-module touchpoint. |
| **Nutrition Model (event/source for alerts)** | Source of "nutrition alerts" ("way under protein today"). The triggering mechanism (event vs scanner) is not specified in the allowed HLDs `[HLD-GAP]`. Cross-module touchpoint. |
| **Feedback System (event source)** | Publishes one `FeedbackProcessedEvent` per feedback entry → Notification confirms to the user what was updated; also the source of low-confidence clarification call-backs surfaced to the user. Cross-module touchpoint. |
| **Health Platform integration (event source)** | Publishes `HealthDirectiveReceivedEvent` → Notification alerts the user that a proposed directive is pending review. Cross-module touchpoint. |
| **Household module (event source)** | Publishes `HouseholdMemberAdded/Removed/SettingsChanged/RoleChanged/Created` events → Notification alerts household members. Cross-module touchpoint. |

## 3. Action Space (frontend-spec backbone)

Flat, exhaustive list of every distinct user (or user-facing system) action the HLD permits or clearly implies. Each: verb-phrase + one-line description + HLD ref. The user-facing read surface is tiny (two explicit operations); the bulk of the action space is system-actor *emission* of notifications, which is the real frontend contract (the frontend must render each notification *type*). Anything beyond list + mark-read is flagged as a gap because the HLD does not expose it.

### User-facing reads & actions
1. **View the recent-notifications list** — open the in-app list of recent notifications. §system-overview Notification System; §technical-architecture (Notifications API: list recent).
2. **Mark a notification as read** — mark a single notification read. §technical-architecture (Notifications API: mark as read).
3. **Dismiss a notification** — remove/clear a notification from the list. `[HLD-GAP]` — only "mark as read" is in the HLD; an explicit dismiss/delete action is not exposed (read ≠ dismiss is unspecified).
4. **Configure notification preferences** — opt in/out of categories, set channels, mute. `[HLD-GAP]` — no preferences surface exists anywhere in the allowed HLDs.
5. **Set quiet hours / do-not-disturb** — suppress delivery during a window. `[HLD-GAP]` — quiet hours are named nowhere in the allowed HLDs; entirely implied-need.
6. **Act on a notification (deep-link)** — tap a notification to jump to the originating screen (the expiring item, the pending recipe change, the directive review). `[HLD-GAP]` — deep-link/action semantics are not specified, though the alert content implies them.

### System-actor emission — scheduled scanners
7. **Emit an expiry warning** — scheduled check on Provisions fires `ItemNearingExpiryEvent`; Notification alerts the user about expiring items ("Mince expires tomorrow"). §system-overview (expiry warnings); §technical-architecture (`ItemNearingExpiryEvent`).
8. **Emit a defrost reminder** — at `meal_time − defrost_lead_time_hours`, remind the user to move a frozen item to the fridge. §system-overview (defrost warnings); Provisions cross-module touchpoint (defrost lead time).
9. **Emit a prep reminder** — from the Meal Planner's schedule, remind the user of an advance-prep action ("start marinating at 6pm"). §system-overview (prep reminders); Planner cross-module touchpoint.
10. **Emit a staple-replenishment reminder** — when a staple hits `low`/`out`, surface "Running low on paprika — added to next shop." §system-overview (Notification listens across modules); Provisions cross-module touchpoint. `[HLD-GAP]` — staple-replenishment is named as a Provisions/grocery behaviour; whether it is *also* a discrete notification type is implied-only in the allowed HLDs.

### System-actor emission — event-driven
11. **Emit a nutrition alert** — surface a nutrition shortfall/observation ("way under protein today"). §system-overview (nutrition alerts). `[HLD-GAP]` — no event for this exists in the event catalogue; trigger mechanism unspecified.
12. **Emit a health-directive notification** — on `HealthDirectiveReceivedEvent`, alert the user that a proposed directive is pending review. §system-overview (health review available); §technical-architecture (`HealthDirectiveReceivedEvent`).
13. **Emit a feedback-confirmation notification** — on `FeedbackProcessedEvent`, confirm what was updated and where (payload includes the destinations). §technical-architecture (`FeedbackProcessedEvent` → Notification); §feedback-system (Confirmation).
14. **Surface a feedback clarification request** — when classification confidence < 0.5, present the user with routing options to pick from (service call-back, not an AI chat). §feedback-system (Confidence handling, < 0.5 path).
15. **Emit a household-change notification** — on `HouseholdMemberAdded/Removed/SettingsChanged/RoleChanged/Created`, alert household members. §technical-architecture (`HouseholdMember*` → Notification).

## 4. State Models

### 4.1 Notification lifecycle
```
CREATED (event-driven emission | scanner emission)
   │
   ▼
UNREAD  ── appears in the recent-notifications list
   │
   └─ user marks read → READ (still listed; "recent" window unspecified)
```
The only two transitions the HLD names are *create* (implicit, via emission) and *mark read*. There is **no** documented expiry/retention of notifications, no dismiss/delete, no "all read," and no archival sweep. `notification_log` is the only storage named.

**Illegal / disallowed transitions (→ error pathways):**
- Marking a non-existent / already-read notification (idempotency unspecified `[HLD-GAP]`).
- Marking another user's notification read (ownership scoping unspecified `[HLD-GAP]`).
- The notification system writing to any data model — **forbidden by the wiring matrix** (Notification injects query services only). Any "notification" that mutates state is illegal.

### 4.2 Delivery/suppression model (implied-only)
```
emission requested
   ├─ within quiet hours?        → suppress / defer / drop?   [HLD-GAP — quiet hours undefined]
   ├─ duplicate of a live one?   → debounce / dedup / coalesce? [HLD-GAP — no dedup rule stated]
   ├─ category muted by prefs?   → suppress?                  [HLD-GAP — no preferences surface]
   └─ otherwise                  → DELIVERED (in-app, UNREAD)
```
Only the final "delivered in-app" branch is actually documented. Every gate above it is an implied need with no HLD rule — each is flagged. The HLD *does* establish an event-debouncing principle at the publisher side (a grocery delivery updating 15 items publishes **one** `ProvisionChangedEvent`, not 15; one `FeedbackProcessedEvent` per multi-destination feedback entry), but this is debouncing *upstream of* the notification system, not within it. Whether the notification system performs any dedup of its own is unstated `[HLD-GAP]`.

### 4.3 Scanner run lifecycle (system-internal)
```
SCHEDULED tick → SCAN (read source module) → {eligible items found → EMIT one notification per item (or coalesced?)} | {none eligible → NO-OP}
```
**Illegal/undefined:** per-item vs coalesced emission when a scan finds many eligible items is unspecified `[HLD-GAP]`; re-emission on the next tick for an item still eligible (re-nag vs suppress-once) is unspecified `[HLD-GAP]`.

---

## 5. Pathways

> Categories: **Happy** (default success), **Alternate** (valid non-default), **Error** (validation/not-found/unauthorized/conflict/illegal-transition), **Edge** (empty/huge/boundary/duplicate/concurrent). Cross-module touchpoints (Provisions, Planner, Nutrition, Feedback, Health Platform, Household) are noted; they are owned by those domains and fully detailed in their own files + the cross-journey file. Given the thin HLD, many pathways assert only "a notification of type X is produced / suppressed / listed" — the precise payload, copy, and channel are gaps.

### User-facing reads & actions

#### NOTIF-01 — View the recent-notifications list
- **Category:** Happy
- **Actor:** Primary user
- **Preconditions:** Authenticated; the user has ≥1 notification.
- **Action:** Open the recent-notifications list.
- **Expected outcome:** A list of this user's recent notifications is returned, each carrying at least a type, a read/unread state, and a timestamp; ordered most-recent-first (ordering implied, not stated).
- **Variations:** mix of read + unread; only unread; notifications of several different types coexisting (expiry + prep + feedback-confirmation); ordering by recency. `[HLD-GAP]` — what defines the "recent" window (count cap? age cutoff?), the sort order, and the exact notification fields are all unspecified.
- **HLD ref:** system-overview.md §Notification System; technical-architecture.md §API (list recent notifications).
- **Notes:** Self-scoped read — assert on *this user's* notifications, never a global count. Seed this user's own notifications first.

#### NOTIF-02 — View the list when there are no notifications
- **Category:** Edge
- **Actor:** Primary user
- **Preconditions:** Authenticated; brand-new user, nothing has been emitted.
- **Action:** Open the list.
- **Expected outcome:** Empty list (not an error); the UI shows an empty state.
- **Variations:** never had any; all previously read and (if retention exists) aged out. `[HLD-GAP]` — no retention/aging rule is defined, so "they aged out" is unspecified.
- **HLD ref:** system-overview.md §Notification System.
- **Notes:** Cold-start empty state. Self-scoped (a fresh user).

#### NOTIF-03 — Mark a notification as read
- **Category:** Happy
- **Actor:** Primary user
- **Preconditions:** Authenticated; an UNREAD notification owned by the user.
- **Action:** Mark that notification read.
- **Expected outcome:** Its state flips UNREAD → READ; it remains in the list (read does not remove it — dismiss is undefined).
- **Variations:** mark one of several; mark the only one; re-open the list and confirm state persisted.
- **HLD ref:** technical-architecture.md §API (mark as read).
- **Notes:** Self-scoped assertion on that notification's state.

#### NOTIF-04 — Mark an already-read notification read (idempotency)
- **Category:** Edge / Error
- **Actor:** Primary user
- **Preconditions:** A notification already READ.
- **Action:** Mark it read again.
- **Expected outcome:** `[HLD-GAP]` — idempotency is unspecified; either a no-op success or an illegal-transition error. Test asserts "no adverse effect / state stays READ" but the precise contract is a finding.
- **Variations:** mark-read twice in succession; concurrent double mark-read (both should converge to READ).
- **HLD ref:** technical-architecture.md §API (mark as read).
- **Notes:** Illegal/degenerate-transition probe.

#### NOTIF-05 — Mark a non-existent notification read
- **Category:** Error
- **Actor:** Primary user
- **Preconditions:** No notification with the given id.
- **Action:** Mark a non-existent notification read.
- **Expected outcome:** Not-found error; nothing changes.
- **Variations:** never-existed id; malformed id.
- **HLD ref:** technical-architecture.md §API (mark as read).
- **Notes:** Validation/not-found pathway.

#### NOTIF-06 — Mark another user's notification read (ownership)
- **Category:** Error
- **Actor:** Primary user / household member
- **Preconditions:** Two users; user A targets a notification belonging to user B.
- **Action:** User A marks user B's notification read.
- **Expected outcome:** Rejected — not-found or unauthorized; B's notification unchanged.
- **Variations:** another household member's notification; a different (unrelated) account's notification. `[HLD-GAP]` — per-user notification ownership/scoping is never stated; the test asserts isolation but the rule is a finding.
- **HLD ref:** system-overview.md §User Accounts (multi-user); technical-architecture.md §API.
- **Notes:** Multi-user isolation; critical self-scoped-assertion case.

#### NOTIF-07 — Dismiss / delete a notification
- **Category:** Error / Alternate
- **Actor:** Primary user
- **Preconditions:** A notification exists.
- **Action:** Attempt to dismiss/delete it (distinct from mark-read).
- **Expected outcome:** `[HLD-GAP]` — only "mark as read" exists; a dismiss/delete affordance is not in the HLD. Either unsupported (error) or aliased to mark-read — unspecified.
- **Variations:** dismiss an unread; dismiss a read; "clear all."
- **HLD ref:** technical-architecture.md §API (only list + mark-read present).
- **Notes:** Confirms the action surface is just two operations; the gap is the point.

#### NOTIF-08 — Configure notification preferences / quiet hours
- **Category:** Error / Edge
- **Actor:** Primary user
- **Preconditions:** Authenticated.
- **Action:** Attempt to open/set notification preferences, mute a category, or set a quiet-hours window.
- **Expected outcome:** `[HLD-GAP]` — there is **no** preferences or quiet-hours surface anywhere in the allowed HLDs. No expected behaviour can be derived; flagged as a major gap.
- **Variations:** mute expiry alerts; mute all; set 22:00–07:00 quiet hours; per-channel (in-app only is the sole named channel).
- **HLD ref:** (none — implied need only; system-overview names only "in-app" delivery).
- **Notes:** Heavy non-happy requirement (preferences not set / quiet hours) cannot be exercised against a real contract — recorded as a gap so the frontend spec and HLD can add it.

### Scheduled scanners

#### NOTIF-09 — Expiry-warning scan finds expiring items (happy)
- **Category:** Happy
- **Actor:** Scheduler / system clock → Provisions
- **Preconditions:** The user's Provisions inventory has ≥1 item nearing its expiry threshold.
- **Action:** The scheduled check on Provisions runs and fires `ItemNearingExpiryEvent`; Notification consumes it.
- **Expected outcome:** An expiry-warning notification is produced for the user ("X expires tomorrow"); it appears UNREAD in the list.
- **Variations:** one expiring item; several expiring at once (one notification per item vs one coalesced — `[HLD-GAP]`); item expiring exactly at the threshold boundary (default expiry windows are not stated in the allowed HLDs — `[HLD-GAP]`).
- **HLD ref:** system-overview.md §Notification System (expiry warnings); technical-architecture.md §Events (`ItemNearingExpiryEvent`, scheduled check on Provisions).
- **Notes:** Cross-module: Provisions (owns inventory + the scheduled check). Assert via *this user's* resulting notification, not global rows.

#### NOTIF-10 — Expiry scan with no eligible items (no consumer / no-op)
- **Category:** Edge
- **Actor:** Scheduler / system clock → Provisions
- **Preconditions:** No inventory item is near expiry (empty pantry, or everything fresh).
- **Action:** The scheduled expiry check runs.
- **Expected outcome:** No event fired / no notification produced; the run is a clean no-op.
- **Variations:** empty inventory; inventory present but all well within shelf life; an item just *past* the threshold yesterday already notified (re-nag vs suppress-once — `[HLD-GAP]`).
- **HLD ref:** technical-architecture.md §Events (`ItemNearingExpiryEvent`).
- **Notes:** "Scanner with no eligible items" required-coverage case. Assert *absence* of a new notification for this user.

#### NOTIF-11 — Defrost reminder fires at lead-time
- **Category:** Happy
- **Actor:** Scheduler / system clock → Provisions + Planner
- **Preconditions:** A frozen item is scheduled in the plan for a future meal; current time reaches `meal_time − defrost_lead_time_hours`.
- **Action:** The defrost scan computes the lead-time window and emits a reminder.
- **Expected outcome:** A defrost reminder ("move X to the fridge") is produced ahead of the meal.
- **Variations:** overnight-fridge defrost (long lead) vs quick method (short lead); a frozen meal whose defrost window has *already* passed when the plan was made late (boundary — does it still fire? `[HLD-GAP]`); user's defrost-tolerance lifestyle preference vs the item's actual defrost requirement (the preference is read elsewhere; the notification only fires off the computed window).
- **HLD ref:** system-overview.md §Notification System (defrost reminders); Provisions cross-module (defrost lead time → "the notification system triggers a 'move to fridge' reminder"); Planner (owns the meal schedule).
- **Notes:** Cross-module: Provisions (defrost data) + Planner (meal time). The defrost scanner's *cadence* (how often it ticks to catch the window) is not in the allowed HLDs — `[HLD-GAP]`.

#### NOTIF-12 — Defrost scan with no frozen items due
- **Category:** Edge
- **Actor:** Scheduler / system clock → Provisions + Planner
- **Preconditions:** No frozen item is due within its lead-time window (or the freezer is empty, or the plan has no frozen meals upcoming).
- **Action:** The defrost scan runs.
- **Expected outcome:** No reminder produced; clean no-op.
- **Variations:** empty freezer; freezer items but none scheduled soon; an item scheduled but outside the lead-time window still.
- **HLD ref:** system-overview.md §Notification System; Provisions/Planner cross-module.
- **Notes:** "Scanner with no eligible items" coverage. Assert no defrost notification for this user.

#### NOTIF-13 — Prep reminder fires from the plan
- **Category:** Happy
- **Actor:** Scheduler / system clock → Meal Planner
- **Preconditions:** Today's (or an upcoming) meal in the plan carries an advance-prep action ("marinate the chicken") with a lead time.
- **Action:** The prep scan reads the plan and emits the reminder at the right moment.
- **Expected outcome:** A prep reminder is produced ("start marinating at 6pm").
- **Variations:** a meal with prep notes vs one without; multiple prep actions on the same day; a prep action whose time has already passed (boundary). `[HLD-GAP]` — the plan's "pre-cook action / lead time" concept is acknowledged as a *planner* design concern that may not be fully modelled yet; the notification depends on data the planner may or may not expose.
- **HLD ref:** system-overview.md §Notification System (prep reminders, "start marinating at 6pm"); Planner cross-module (pre-cook actions with lead times).
- **Notes:** Cross-module: Planner. Tightly coupled to a planner capability that is itself flagged as not-yet-fully-designed.

#### NOTIF-14 — Prep scan with no prep-bearing meals
- **Category:** Edge
- **Actor:** Scheduler / system clock → Meal Planner
- **Preconditions:** No upcoming meal carries an advance-prep action (or there is no active plan).
- **Action:** The prep scan runs.
- **Expected outcome:** No reminder produced; clean no-op.
- **Variations:** no active plan at all; plan exists but no meal needs prep; all prep already past.
- **HLD ref:** system-overview.md §Notification System; Planner cross-module.
- **Notes:** "Scanner with no eligible items" coverage.

#### NOTIF-15 — Staple-replenishment reminder
- **Category:** Alternate
- **Actor:** Scheduler / system clock → Provisions
- **Preconditions:** A staple item has dropped to `low` or `out`.
- **Action:** The staple scan (or the replenishment flow) surfaces a reminder.
- **Expected outcome:** A reminder is produced ("Running low on paprika — added to next shop").
- **Variations:** staple hits `low`; staple hits `out`; multiple staples low at once. `[HLD-GAP]` — staple replenishment is documented as a Provisions/grocery behaviour ("added to next shop"); whether it surfaces as a *Notification-domain* notification (vs only a grocery-list effect) is implied-only in the allowed HLDs.
- **HLD ref:** system-overview.md §Notification System (listens across modules); Provisions cross-module (staple status low/out → next shop).
- **Notes:** Cross-module: Provisions/Grocery. Classed Alternate because its existence as a notification is inferred.

### Event-driven notifications

#### NOTIF-16 — Feedback-confirmation notification
- **Category:** Happy
- **Actor:** Feedback System (event source) → Notification
- **Preconditions:** The user has submitted feedback; the Feedback System has classified, routed, and published exactly one `FeedbackProcessedEvent` for that entry (one per entry, even for multi-destination feedback — publisher-side debounce).
- **Action:** Notification consumes the event.
- **Expected outcome:** A confirmation notification is produced summarising what was updated and where ("Proposed recipe change for X; noted cost concern"), reflecting all destinations in the payload.
- **Variations:** single-destination feedback (one update confirmed); multi-destination feedback (one event, multiple destinations summarised in one notification); a destination whose write *failed* (partial success — confirmation should reflect applied vs failed `[HLD-GAP]` whether the notification distinguishes them); a destination whose result is `pending_approval` (recipe change) vs `applied` (provisions).
- **HLD ref:** technical-architecture.md §Events (`FeedbackProcessedEvent` → Notification, "one event per feedback entry"); feedback-system.md §Confirmation and Misclassification Correction.
- **Notes:** Cross-module: Feedback System. The single-event-per-entry guarantee is the key assertion (no notification storm for multi-destination feedback).

#### NOTIF-17 — Feedback clarification request (low-confidence routing)
- **Category:** Alternate
- **Actor:** Feedback System → user (service call-back) → Notification surface
- **Preconditions:** The classifier returns confidence < 0.5 for a feedback entry.
- **Action:** The system calls back to the user with routing options ("(a) the recipe needs changing, (b) your preferences need updating, (c) something about the cost?").
- **Expected outcome:** The user is presented with the clarification choices (a service call-back surfaced to the user, NOT an AI multi-turn chat); after the user picks, classification re-runs with the added context.
- **Variations:** user picks one of the offered options; user types a free clarification; confidence in the 0.5–0.8 band (auto-routed but *flagged* in the confirmation — "I think you meant X" — rather than a clarification request). `[HLD-GAP]` — whether the clarification is delivered through the *notification* surface specifically, or a synchronous feedback-screen prompt, is not pinned down; the allowed HLDs place it in the Feedback flow, not explicitly in Notifications.
- **HLD ref:** feedback-system.md §Classification (Confidence handling, < 0.5 path; 0.5–0.8 flag-in-confirmation).
- **Notes:** Cross-module: Feedback System. The confidence-band thresholds are the boundary cases.

#### NOTIF-18 — Health-directive notification (pending review)
- **Category:** Happy
- **Actor:** Health Platform integration (event source) → Notification
- **Preconditions:** A connected health platform pushes a directive; `HealthDirectiveReceivedEvent` is published.
- **Action:** Notification consumes the event.
- **Expected outcome:** A notification is produced telling the user a proposed directive is pending their review ("health review available"); it links into the propose/accept review flow (deep-link implied).
- **Variations:** target-adjustment directive; elimination-protocol directive; ingredient-restriction directive; no health platform connected (event never fires — no notification). The event is also listened by Nutrition/Preference for the actual propose/accept handling — Notification only alerts.
- **HLD ref:** system-overview.md §Notification System (health review available); §Nutrition Model (health platform propose/accept); technical-architecture.md §Events (`HealthDirectiveReceivedEvent` → Notification, Nutrition/Preference).
- **Notes:** Cross-module: Health Platform + Nutrition/Preference. Notification is alert-only; it must not itself apply the directive (read-only invariant).

#### NOTIF-19 — Nutrition-alert notification
- **Category:** Happy
- **Actor:** Nutrition Model (source) → Notification
- **Preconditions:** The user is significantly off a nutrition target (e.g. "way under protein today").
- **Action:** A nutrition alert is emitted.
- **Expected outcome:** A nutrition-alert notification is produced summarising the shortfall.
- **Variations:** under a macro floor (protein); over a target; a daily-floor breach vs a weekly-average drift. `[HLD-GAP]` — there is **no** event in the catalogue for nutrition alerts (`NutritionIntakeDivergedEvent` targets the *Planner*, not Notification); the trigger mechanism for a user-facing nutrition alert is unspecified — event? scanner? piggyback on the logger?
- **HLD ref:** system-overview.md §Notification System (nutrition alerts, "way under protein today").
- **Notes:** Cross-module: Nutrition Model. The missing trigger is a significant gap — the alert is named as a deliverable but has no wired source.

#### NOTIF-20 — Household-change notification
- **Category:** Happy
- **Actor:** Household module (event source) → Notification
- **Preconditions:** A household membership or settings change occurs (member added/removed, role changed, settings changed, household created).
- **Action:** The Household module publishes the corresponding event; Notification consumes it.
- **Expected outcome:** Affected household members receive a notification of the change.
- **Variations:** member added; member removed; settings changed (e.g. which meals are shared); role changed; household created. `[HLD-GAP]` — *which* members are notified for *which* change (e.g. is the removed member notified?) is not specified; recipients are unspecified.
- **HLD ref:** technical-architecture.md §Events (`HouseholdMember*`/`HouseholdSettingsChanged` → Planner, **Notification**: "alert household members").
- **Notes:** Cross-module: Household. Recipient set is the gap.

### Error / suppression / edge (cross-cutting delivery)

#### NOTIF-21 — Notification with no consumer (event fired, nothing listening)
- **Category:** Edge / Error
- **Actor:** Any event source
- **Preconditions:** An event is published whose only documented Notification handling is ambiguous or absent (e.g. a nutrition alert with no wired event, or an event type Notification is listed against but for which no user-facing notification copy is defined).
- **Action:** The event fires.
- **Expected outcome:** `[HLD-GAP]` — the HLD does not define what happens when an event has no Notification consumer or no notification template; the safe expectation is "no notification, no error, logged," but this is not stated.
- **Variations:** nutrition alert with no event source (NOTIF-19); an event listed for Notification but with no defined user copy; a scanner that finds an item type it has no template for.
- **HLD ref:** technical-architecture.md §Event System (`@TransactionalEventListener(AFTER_COMMIT)`); system-overview.md §Notification System.
- **Notes:** Required "notification with no consumer" coverage. Mostly a gap-probe — asserts no crash, no rollback (listener runs AFTER_COMMIT so a failed notification must not undo the source transaction).

#### NOTIF-22 — Quiet-hours suppression
- **Category:** Edge
- **Actor:** Scheduler / event source → Notification
- **Preconditions:** A notification would be emitted during the user's quiet-hours window.
- **Action:** Emission is attempted inside quiet hours.
- **Expected outcome:** `[HLD-GAP]` — quiet hours do not exist in the allowed HLDs, so suppress/defer/deliver-anyway cannot be derived. Recorded as a gap; the required non-happy "quiet-hours suppression" case has no contract to test against.
- **Variations:** a defrost reminder due at 03:00 (would it wake the user?); an expiry warning at 02:00; a time-critical prep reminder during quiet hours (does urgency override?).
- **HLD ref:** (none — implied need only).
- **Notes:** Heavy non-happy requirement that the HLD cannot satisfy — surfaced as a gap. Defrost/prep are inherently time-bound, which makes the quiet-hours interaction acute.

#### NOTIF-23 — Duplicate / debounce / dedup of notifications
- **Category:** Edge
- **Actor:** Scheduler / event source → Notification
- **Preconditions:** The same condition would generate the same notification repeatedly — a scanner re-ticking on an item still expiring, or two rapid events for the same subject.
- **Action:** The repeated emission is attempted.
- **Expected outcome:** `[HLD-GAP]` — the HLD defines event *debouncing upstream* (one `ProvisionChangedEvent` per grocery operation; one `FeedbackProcessedEvent` per entry) but states **no** dedup rule *within* the notification system. Whether a still-expiring item re-nags every scan tick or is suppressed-once is unspecified.
- **Variations:** expiry scanner re-emitting for the same item across consecutive ticks; a grocery delivery (debounced to one upstream event) producing one vs many notifications; two near-simultaneous events for the same item.
- **HLD ref:** technical-architecture.md §Event debouncing (publisher-side only).
- **Notes:** Required "debounce/dedup" coverage; the upstream-debounce vs in-notification-dedup distinction is the key finding.

#### NOTIF-24 — Notification emission must not roll back its source transaction
- **Category:** Edge / Error
- **Actor:** Any event source → Notification listener
- **Preconditions:** A source module commits a change (e.g. Provisions inventory deduction) that publishes an event Notification listens to; the notification creation then fails (e.g. transient error).
- **Action:** The notification listener throws while handling the committed event.
- **Expected outcome:** The source transaction stays committed — the inventory change is **not** undone, because listeners run `@TransactionalEventListener(AFTER_COMMIT)` in their own transaction. The failed notification is lost/retried but never corrupts upstream state.
- **Variations:** failure on a `FeedbackProcessedEvent` listener (feedback routing already committed — confirmation lost, routing intact); failure on `ItemNearingExpiryEvent`; failure on a household event. `[HLD-GAP]` — whether failed notifications are retried (Spring Modulith offers "replay-on-failure") or simply dropped is not stated for the notification path specifically.
- **HLD ref:** technical-architecture.md §Event System (AFTER_COMMIT rationale: "a failed re-optimisation suggestion should never undo an inventory update"; Modulith replay-on-failure).
- **Notes:** Transaction-isolation invariant — the most important correctness property of the cross-cutting listener. Assert the *source* state survived; the lost notification is acceptable.

#### NOTIF-25 — Notification list at scale / pagination
- **Category:** Edge
- **Actor:** Primary user
- **Preconditions:** A long-running (soak-mode) user has accumulated many notifications across all types over weeks.
- **Action:** Open the recent-notifications list.
- **Expected outcome:** The list returns a bounded, recent set without error.
- **Variations:** hundreds of accumulated notifications; many unread; mixed types. `[HLD-GAP]` — no pagination, count cap, or retention/aging is defined for the list, so "bounded recent set" is an assumption.
- **HLD ref:** technical-architecture.md §API (list recent notifications).
- **Notes:** Soak-mode boundary; self-scoped to this user's notifications (never assert a global table count).

### Flagship cross-module journey

#### NOTIF-26 — Frozen batch-cooked meal scheduled → defrost reminder fires → item expiry warning later → feedback after eating is confirmed back to the user
- **Category:** Happy (flagship end-to-end)
- **Actor:** Primary user (+ Scheduler, Provisions, Planner, Feedback System as system actors)
- **Preconditions:** Authenticated; the user has a frozen batch-cooked portion in Provisions (with `defrost_lead_time_hours`) and an active weekly plan that schedules it for an upcoming dinner.
- **Action (sequence):**
  1. The plan schedules the frozen portion for, say, Wednesday dinner *(cross-module: Planner owns the schedule)*.
  2. At `meal_time − defrost_lead_time_hours` the **defrost scanner** fires a "move X to the fridge" reminder → the user sees it in the notifications list *(cross-module: Provisions defrost data)*.
  3. The user views the list (NOTIF-01) and marks the defrost reminder read (NOTIF-03).
  4. The thawed item now sits in the fridge; a day later the **expiry scanner** (scheduled check on Provisions) fires `ItemNearingExpiryEvent` → an expiry warning notification is produced *(cross-module: Provisions)*.
  5. The user cooks/eats the meal and submits feedback ("this was a bit bland") *(cross-module: Feedback System)*.
  6. The Feedback System classifies, routes, and publishes one `FeedbackProcessedEvent` → Notification produces a **feedback-confirmation** notification summarising what was updated (NOTIF-16).
- **Expected outcome:** Across one meal's lifecycle the user receives a defrost reminder, then (conditionally) an expiry warning, then a feedback-confirmation — three notifications from three different sources, all listed for this user, each independently markable read; none of them mutated any data model (read-only invariant); the feedback routing committed regardless of notification success (NOTIF-24).
- **Variations:** defrost reminder during quiet hours (NOTIF-22 — gap); the frozen item *not* near expiry after thawing (step 4 is a no-op, NOTIF-10); feedback routed to multiple destinations (one confirmation, NOTIF-16 multi-destination); a household member is the eater (recipient/scoping gap, NOTIF-06/NOTIF-20).
- **HLD ref:** system-overview.md §Notification System (defrost, expiry, feedback confirmation); technical-architecture.md §Events (`ItemNearingExpiryEvent`, `FeedbackProcessedEvent`, AFTER_COMMIT isolation); feedback-system.md §Confirmation.
- **Notes:** CROSS-MODULE touchpoints — step 1 (Planner schedule), steps 2 & 4 (Provisions defrost + expiry), steps 5–6 (Feedback System routing) are owned by those domains and detailed there + in the cross-journey file. This journey is the integration backbone for the Notification domain: it exercises both input channels (scheduled scanners *and* event-driven), the read/mark-read surface, and the AFTER_COMMIT isolation invariant in one pass. Assertions span this user's notification list across three types — never global counts.

---

## Appendix — `[HLD-GAP]` findings (consolidated)

| # | Gap | Pathway |
|---|---|---|
| G1 | "Recent" notifications: list window (count cap / age cutoff), sort order, and exact notification fields are never defined. | NOTIF-01, NOTIF-25 |
| G2 | No retention / aging / archival rule for notifications (nothing ever removes a notification). | NOTIF-02, NOTIF-25 |
| G3 | Mark-read idempotency (re-mark a READ notification): no-op success vs error — unspecified. | NOTIF-04 |
| G4 | Per-user notification ownership / scoping is never stated (multi-user isolation rule). | NOTIF-06 |
| G5 | No dismiss/delete/"clear all" action — only "mark as read" exists; read ≠ removed. | NOTIF-03, NOTIF-07 |
| G6 | No notification-preferences surface at all (mute categories, channels, opt-out). | NOTIF-08 |
| G7 | No quiet-hours / do-not-disturb concept — suppression of off-hours alerts has no contract (acute for time-bound defrost/prep). | NOTIF-08, NOTIF-22 |
| G8 | Scanner cadence/cron for each scanner (expiry, defrost, prep, staple) is not stated in the allowed HLDs. | NOTIF-09, NOTIF-11, NOTIF-13 |
| G9 | Default expiry thresholds (when an item counts as "nearing expiry") not stated in the allowed HLDs. | NOTIF-09 |
| G10 | Per-item vs coalesced emission when a scan finds many eligible items — unspecified. | NOTIF-09, NOTIF-15 |
| G11 | Re-emission policy across ticks (re-nag a still-eligible item vs suppress-once) — unspecified. | NOTIF-10, NOTIF-23 |
| G12 | Defrost reminder when the lead-time window has already passed at plan time — behaviour undefined. | NOTIF-11 |
| G13 | Prep reminders depend on a planner "pre-cook action / lead time" concept the HLD itself flags as not-yet-fully-modelled. | NOTIF-13 |
| G14 | Staple-replenishment as a *Notification-domain* notification (vs only a grocery-list effect) is implied-only. | NOTIF-15 |
| G15 | Feedback-confirmation: whether the notification distinguishes applied vs failed (partial-success) destinations is unspecified. | NOTIF-16 |
| G16 | Whether the low-confidence feedback clarification is delivered via the notification surface vs a synchronous feedback prompt — not pinned down. | NOTIF-17 |
| G17 | Nutrition alerts are named as a deliverable but have **no wired event/source** (`NutritionIntakeDivergedEvent` targets the Planner, not Notification). | NOTIF-19, NOTIF-21 |
| G18 | Household-change notifications: which members are notified for which change (e.g. is a removed member alerted?) — recipient set unspecified. | NOTIF-20 |
| G19 | Behaviour when an event has no Notification consumer / no notification template — undefined (no-op-and-log assumed). | NOTIF-21 |
| G20 | No dedup rule *within* the notification system (upstream event debouncing is defined, but not in-notification dedup). | NOTIF-23 |
| G21 | Whether failed notifications are retried (Modulith replay-on-failure) or dropped — not stated for the notification path. | NOTIF-24 |
| G22 | No pagination / count cap on the notifications list. | NOTIF-25 |
| G23 | Deep-link / "act on a notification" semantics (tapping to jump to the source screen) are implied by the copy but not specified. | NOTIF-18, action #6 |
| G24 | Whether household members (vs only the primary user) receive shared-provisions scanners (expiry/defrost/staple on the shared pantry) — unspecified. | Actors, NOTIF-09/11/15 |
