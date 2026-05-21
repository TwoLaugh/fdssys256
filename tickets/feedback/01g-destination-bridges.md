# Ticket: feedback — 01g Real Destination Bridges (replace four Noop configurations)

## Summary

Replace the four placeholder destination configurations (`NoopFeedbackBridgesConfiguration`, `NoopRecipeFeedbackHandlerConfiguration`, `NoopFeedbackRevertersConfiguration`, the no-op preference/nutrition/provisions handlers) with **real** implementations that apply classifier output to downstream module state. Per [`design/feedback-system.md`](../../design/feedback-system.md) and roadmap §B1.4 in [`design/audits/2026-05-21-frontend-readiness-roadmap.md`](../../design/audits/2026-05-21-frontend-readiness-roadmap.md). This is the work that closes the AI-learning loop — until this lands, the system classifies feedback correctly but acts on nothing (the audit's "the system can't currently learn" finding).

**Hard dependencies — both must merge first**:
- **`tickets/preference/01c-taste-profile-entity.md`** (the `TasteProfileUpdateService.applyDeltas` interface must exist for the preference bridge to compile)
- **`tickets/core/02b-origin-tracking-foundation.md`** (every bridge call uses `X-Origin: AI_FEEDBACK` per `design/origin-tracking-pattern.md`)

Four bridges replace the four Noop implementations. Each follows the same pattern:
- `@TransactionalEventListener(phase = AFTER_COMMIT)` on `FeedbackProcessedEvent` (already published by `feedback-01d`'s router).
- Idempotency key = `feedback_id` — re-processing the same event within 5 minutes is a no-op.
- Confidence floor check — reject when below 0.5; surface as a pending-suggestion for user review instead (deferred to a follow-up; this ticket just logs the rejection).
- Per-destination dispatch — call the destination's `applyFeedback` / `applyDeltas` / equivalent via the **origin-tracking pattern** (`X-Origin: AI_FEEDBACK`, `X-Origin-Trace: feedback-<feedback_id>`, `X-Origin-Depth: 1`).
- Audit attribution: every audit row written by the destination carries `actor_type = AI`, `origin_trace = feedback-<feedback_id>`.

The four bridges:

1. **`PreferenceFeedbackBridge`** — classifier extracts a `taste_profile_delta`; bridge calls `TasteProfileUpdateService.applyDeltas(userId, request)`. Once 01c's stub becomes the real applier, this writes to the taste profile.
2. **`NutritionFeedbackBridge`** — classifier extracts a target adjustment; bridge calls `NutritionTargetsUpdateService.applyFeedbackAdjustment(userId, request)`. Origin-aware so the audit-log row carries `AI` attribution.
3. **`ProvisionsFeedbackBridge`** — handles two cases: equipment removal (`"I don't have a food processor"` → call `EquipmentUpdateService.removeByName(userId, "food processor")`) and inventory adjustment (`"I'm out of soy sauce"` → call `InventoryUpdateService.markDepleted(userId, ingredientKey)`).
4. **`RecipeFeedbackBridge`** — routes recipe-specific feedback to the existing adaptation pipeline via `OptimiserService.handleRecipeFeedback(...)`. The adaptation pipeline is already wired (per the audit's "real, end-to-end, no stubs" claim about the planner-adaptation path) — the bridge is just the routing edge.

Closes: capability "feedback bridges" (not directly inventoried; closes the loop on **C-A-003** application, **C-IMP-007** application, **C-C-034**, **C-C-039**, partial close on **C-G-037**). Tier-B keystone work.

## Behavioural spec

### Replace the Noop configurations

1. **Delete** the following classes (or rewrite them as the real impls):
   - `NoopFeedbackBridgesConfiguration` (wherever it lives — verify location at agent start)
   - `NoopRecipeFeedbackHandlerConfiguration`
   - `NoopFeedbackRevertersConfiguration`
   - Any Noop `FeedbackDestinationDispatcher` beans
2. Each Noop is replaced by its real counterpart described below.

### Common bridge infrastructure

3. **`FeedbackBridgeSupport`** at `feedback.bridge.internal.FeedbackBridgeSupport` — abstract base class for the four bridges. Provides:
   - **Idempotency check**: looks up a `feedback_bridge_idempotency` table; if a row with the same `(feedback_id, destination)` exists less than 5 minutes old, skip.
   - **Confidence-floor check**: rejects when `classification.confidence < 0.5`; logs a structured WARN with `feedback_id`, destination, confidence; **does NOT throw** (per `design/feedback-system.md` — rejection should not poison the event loop). Future enhancement: write a `pending_suggestions` row for user review (deferred per `design/origin-tracking-pattern.md` Open Questions).
   - **Origin-tracking call helper**: a `dispatchWithOrigin(...)` method that constructs the headers + body + Spring `RestTemplate` (or `WebClient` if the project uses it) and invokes the destination's endpoint. **Worth implementer review** — choose between in-process service injection and over-the-wire HTTP call:
     - **In-process** (faster, simpler): inject `TasteProfileUpdateService` directly and call `applyDeltas(...)`. **Cost**: the `OriginContext` request-scoped bean isn't populated for in-process calls; the bridge must construct an `OriginContext` programmatically and pass it explicitly.
     - **Over-the-wire HTTP** (matches the `design/origin-tracking-pattern.md` ideal): construct a real HTTP request with the headers; Spring's filter chain populates `OriginContext` naturally. **Cost**: serialization round-trip, extra latency, lateral coupling to the controller paths.
     - **Decision for v1**: **in-process** with explicit `OriginContext` construction. The pattern-doc's "ideal" is over-the-wire, but the in-process path preserves correct audit attribution while avoiding the latency. The pattern is preserved at the **convention level** (every bridge uses the same `OriginContext`-aware service surface) even when not the literal HTTP-roundtrip mechanism. **Worth user review.**
4. **`feedback_bridge_idempotency`** table — migration `V20260615190000__feedback_create_bridge_idempotency.sql`:
   ```sql
   CREATE TABLE feedback_bridge_idempotency (
       id                       uuid PRIMARY KEY,
       feedback_id              uuid NOT NULL,
       destination              varchar(16) NOT NULL,
       status                   varchar(16) NOT NULL,        -- DISPATCHED | REJECTED_LOW_CONFIDENCE | FAILED
       dispatched_at            timestamptz NOT NULL,
       UNIQUE (feedback_id, destination)
   );
   CREATE INDEX idx_feedback_bridge_idempotency_recent
       ON feedback_bridge_idempotency (dispatched_at DESC);
   ```
   The `UNIQUE` constraint enforces idempotency at the DB level (insert-or-skip via `ON CONFLICT DO NOTHING` in the bridge code).

### `PreferenceFeedbackBridge`

5. **`PreferenceFeedbackBridge`** at `feedback.bridge.PreferenceFeedbackBridge`. `@Component`, extends `FeedbackBridgeSupport`. `@TransactionalEventListener(phase = AFTER_COMMIT, condition = "#event.destination().name() == 'PREFERENCE'")` — only fires for PREFERENCE-destined feedback (the router publishes one `FeedbackProcessedEvent` per destination per `feedback-01d`).
6. Behaviour on event:
   - Confidence-floor check (skip if < 0.5).
   - Idempotency check.
   - Parse `event.structuredPayload()` into `ApplyTasteProfileDeltasRequest`. The classifier emits a JSON shape like:
     ```json
     { "deltas": [
         { "op": "Add", "fieldPath": "ingredientPreferences.disliked",
           "item": { "item": "coriander", "evidenceCount": 1, "lastSignal": "2026-05-20", "source": "FEEDBACK" } }
       ],
       "trigger": "BATCH",
       "feedbackRangeStart": "feedback-<feedback_id>",
       "feedbackRangeEnd": "feedback-<feedback_id>",
       "modelTierUsed": "mid"
     }
     ```
   - Construct `OriginContext { origin: AI_FEEDBACK, originTrace: "feedback-<feedback_id>", originDepth: 1, confidence: <event.confidence> }`. Pass to the service call.
   - Call `tasteProfileUpdateService.applyDeltas(event.userId(), request)`. **Stubbed today** — the call throws `UnsupportedOperationException` per `tickets/preference/01c`'s stub note. Catch the exception, record a `feedback_bridge_idempotency` row with `status = FAILED`, log a structured error referencing the deferred-applier ticket. **This is the expected v1 behaviour**: the bridge is wired and ready; the applier implementation lands in the deferred-applier ticket.
   - On success: record an idempotency row with `status = DISPATCHED`.
7. **Audit attribution**: when the future applier writes to `preference_taste_profile_audit`, the row's `actor_type = AI` and `origin_trace = feedback-<feedback_id>`. The applier reads these from `OriginContext`.

### `NutritionFeedbackBridge`

8. **`NutritionFeedbackBridge`** at `feedback.bridge.NutritionFeedbackBridge`. Same shape as the preference bridge, listening for `NUTRITION` destination.
9. Classifier payload shape:
   ```json
   { "target": "sodium_mg_max",
     "direction": "decrease",
     "magnitude": "moderate",
     "absoluteValue": 2000,
     "reason": "user feedback: cutting sodium" }
   ```
10. Bridge translates to `NutritionTargetsUpdateService.applyFeedbackAdjustment(userId, NutritionFeedbackAdjustment)` per the existing nutrition service API. **If `applyFeedbackAdjustment` doesn't exist** in the nutrition service interface (verify at agent start), the bridge calls the **next-closest method** (probably `updateMicroTarget(userId, key, value)`) and the nutrition LLD gets a follow-up note. **Worth implementer review.**
11. Audit: nutrition's audit-log row carries `actor_type = AI`, `origin_trace`.

### `ProvisionsFeedbackBridge`

12. **`ProvisionsFeedbackBridge`** at `feedback.bridge.ProvisionsFeedbackBridge`. Listens for `PROVISIONS` destination.
13. Two payload sub-shapes (the classifier disambiguates via the `provisionsAction` field):
    - **Equipment removal**:
      ```json
      { "provisionsAction": "REMOVE_EQUIPMENT",
        "equipmentName": "food processor",
        "reason": "user feedback: don't have a food processor" }
      ```
      Bridge calls `EquipmentUpdateService.removeByName(userId, equipmentName)`. If the equipment doesn't exist, no-op (idempotent removal).
    - **Inventory depletion**:
      ```json
      { "provisionsAction": "MARK_DEPLETED",
        "ingredientMappingKey": "soy_sauce",
        "reason": "user feedback: out of soy sauce" }
      ```
      Bridge calls `InventoryUpdateService.markDepleted(userId, ingredientMappingKey)`. Idempotent.
14. If the classifier emits a `provisionsAction` value the bridge doesn't handle (`ADJUST_BUDGET`, etc., reserved for future) → record `status = FAILED` with reason `unsupported-provisions-action`; the feedback module's quality-monitoring (per `lld/feedback.md`) surfaces these.

### `RecipeFeedbackBridge`

15. **`RecipeFeedbackBridge`** at `feedback.bridge.RecipeFeedbackBridge`. Listens for `RECIPE` destination.
16. Classifier payload includes a `recipeId` (from the feedback's UI context per `feedback-01a` — the user is rating/commenting on a specific recipe):
    ```json
    { "recipeId": "<uuid>",
      "feedbackType": "FLAVOUR | METHOD | PORTION | OTHER",
      "extractedFeedback": "needed more salt",
      "affectsPlan": true }
    ```
17. Bridge calls `OptimiserService.handleRecipeFeedback(recipeId, RecipeFeedbackInput)` per `lld/feedback.md:772`. **Assumed signature**:
    ```java
    public interface OptimiserService {
      AdaptationResult handleRecipeFeedback(
          UUID recipeId, UUID userId,
          String extractedFeedback,
          UUID traceId, JsonNode structuredPayload);
    }
    ```
    If the adaptation-pipeline LLD specifies a different shape, the bridge adapts via a one-liner mapping function. **Worth implementer review.**
18. Origin context: the bridge passes `OriginContext { AI_FEEDBACK, "feedback-<feedback_id>", depth 1, confidence }`. The adaptation pipeline already has `traceId` propagation per its LLD; the origin metadata extends this.

### Removing the old `NoopFeedbackRevertersConfiguration`

19. The `NoopFeedbackRevertersConfiguration` provides Noop `FeedbackReverter` beans used by `tickets/feedback/01f-misclassification-correction.md` (the correction-replay path). **Replacing the Noop reverters is OUT OF SCOPE for this ticket**. Reverters are activated when a user submits a misclassification correction; the same per-destination call surface (`applyFeedback` / `applyDeltas`) is used in the reverse direction. **Recommendation**: ship the four real **dispatchers** here (this ticket) and ship four real **reverters** in a sibling ticket once correction-replay surfaces a real need. For v1, keep the Noop reverters in place — feedback corrections will replay-without-effect until reverters are implemented (the data is captured; the playback is silent).
20. **Worth user review**: alternative is to ship reverters alongside dispatchers here. Reverter logic is more complex than dispatch (knowing how to "undo" an AI delta requires either a reverse-delta or a snapshot-rollback). Defer to a follow-up.

### Idempotency cleanup

21. **`@Scheduled(cron = "0 0 4 * * ?")`** scanner `FeedbackBridgeIdempotencyCleanupScheduler` deletes `feedback_bridge_idempotency` rows older than 7 days (configurable via `mealprep.feedback.bridge.idempotency-retention-days`, default 7). The idempotency window is 5 minutes; rows persist longer for forensic / audit visibility. **Worth user review** — the 7-day retention vs idempotency-window-only argument.

### Cross-cutting

22. New module-local exceptions:
    - `FeedbackBridgeDispatchFailedException(Destination, UUID feedbackId, Throwable cause) extends FeedbackException` (500). The event listener catches this and records `status = FAILED` in the idempotency table; does NOT propagate — the post-AFTER_COMMIT listener must not affect the original feedback transaction.
23. The existing `FeedbackProcessedEvent` (from `feedback-01d`) is the **input** for all four bridges. **The event must already implement `OriginAwareEvent`** (per `tickets/core/02b`) — verify; if not, this ticket modifies the event class to add `Origin origin()` and `String originTrace()` defaulting to USER + null for the event-source side (the bridges always set AI_FEEDBACK when they call downstream).
24. ArchUnit rule (added to `FeedbackBoundaryTest` per `feedback-01a`): every class in `feedback.bridge..` must be a `@Component` and must implement either `@TransactionalEventListener` directly or extend `FeedbackBridgeSupport`. Prevents drift toward custom bridge patterns.

### Events

25. **Published**: none — bridges are terminal listeners.
26. **Consumed**: `FeedbackProcessedEvent` (from `feedback-01d`).

## Database

```
NEW   src/main/resources/db/migration/V20260615190000__feedback_create_bridge_idempotency.sql
```

Schema per §4.

## OpenAPI updates

**No OpenAPI changes.** Bridges are internal event listeners — they call existing destination service APIs but expose no new HTTP surface themselves.

## Edge-case checklist

- [ ] Migration applies cleanly; `FlywayMigrationIT` passes.
- [ ] **Noop classes deleted**: `NoopFeedbackBridgesConfiguration` and friends no longer compile/exist (verify with grep).
- [ ] **All four real bridges are `@Component`s** registered in the Spring context (verified by counting `@Component` beans implementing `FeedbackBridgeSupport`).
- [ ] **PreferenceFeedbackBridge happy path**: a `FeedbackProcessedEvent(destination=PREFERENCE, confidence=0.7)` is published `AFTER_COMMIT` → bridge fires → constructs `ApplyTasteProfileDeltasRequest` from the payload → calls `TasteProfileUpdateService.applyDeltas(...)`. **Today**: the stub throws `UnsupportedOperationException` → bridge catches → idempotency row written with `status=FAILED` and a structured log line referencing the deferred applier. **This is the expected v1 outcome** — the bridge is wired, the applier is its own follow-up.
- [ ] **PreferenceFeedbackBridge low confidence**: same event with `confidence=0.4` → bridge skips, idempotency row `status=REJECTED_LOW_CONFIDENCE`, WARN log.
- [ ] **PreferenceFeedbackBridge idempotency**: two events with the same `feedback_id` within 5 minutes → second is a no-op (existing idempotency row found).
- [ ] **PreferenceFeedbackBridge idempotency window expiry**: re-firing the same `feedback_id` event 6 minutes later → bridge processes again (new idempotency row).
- [ ] **NutritionFeedbackBridge happy path**: `FeedbackProcessedEvent(destination=NUTRITION, payload={target: sodium_mg_max, direction: decrease, ...})` → bridge calls `NutritionTargetsUpdateService.applyFeedbackAdjustment(...)`. The nutrition audit-log row carries `actor_type=AI`, `origin_trace=feedback-<id>`.
- [ ] **ProvisionsFeedbackBridge equipment removal**: payload `{provisionsAction: REMOVE_EQUIPMENT, equipmentName: "food processor"}` → bridge calls `EquipmentUpdateService.removeByName(userId, "food processor")`. If equipment exists, removed; if not, no-op (idempotent).
- [ ] **ProvisionsFeedbackBridge inventory depletion**: payload `{provisionsAction: MARK_DEPLETED, ingredientMappingKey: "soy_sauce"}` → bridge calls `InventoryUpdateService.markDepleted(...)`.
- [ ] **ProvisionsFeedbackBridge unsupported action**: payload `{provisionsAction: ADJUST_BUDGET, ...}` → bridge records `status=FAILED`, reason `unsupported-provisions-action`, ERROR log.
- [ ] **RecipeFeedbackBridge happy path**: `FeedbackProcessedEvent(destination=RECIPE, payload={recipeId: <uuid>, extractedFeedback: "needed more salt"})` → bridge calls `OptimiserService.handleRecipeFeedback(...)`. Adaptation pipeline runs end-to-end.
- [ ] **All bridges**: an exception in the destination call is caught → idempotency row `status=FAILED` → no exception escapes the AFTER_COMMIT listener (the original feedback transaction is unaffected).
- [ ] **Origin context construction**: bridges programmatically construct `OriginContext { Origin.AI_FEEDBACK, ... }` and the audit row produced by the destination correctly reads it (verified by IT querying the audit table).
- [ ] **Cross-bridge isolation**: only one bridge fires per event (the SpEL condition `destination().name() == 'X'` partitions cleanly).
- [ ] **Cleanup scheduler**: rows older than 7 days are deleted on the daily cron; mocked-Clock IT verifies.
- [ ] **`FeedbackProcessedEvent` already exists** — this ticket does not redefine it; ArchUnit checks no shadow class is created.
- [ ] **ArchUnit**: every `feedback.bridge..` class extends `FeedbackBridgeSupport` OR is annotated `@Component` (no rogue bridges).
- [ ] **OriginAwareEvent compatibility**: `FeedbackProcessedEvent` carries `origin()` returning `USER` (the originating feedback is user-driven) — verified by reflection in `FeedbackProcessedEventTest`.

## Files this ticket touches

```
NEW   src/main/resources/db/migration/V20260615190000__feedback_create_bridge_idempotency.sql

DEL   (or rewrite) src/main/java/.../NoopFeedbackBridgesConfiguration.java
DEL   src/main/java/.../NoopRecipeFeedbackHandlerConfiguration.java

NEW   src/main/java/com/example/mealprep/feedback/bridge/internal/FeedbackBridgeSupport.java
NEW   src/main/java/com/example/mealprep/feedback/bridge/PreferenceFeedbackBridge.java
NEW   src/main/java/com/example/mealprep/feedback/bridge/NutritionFeedbackBridge.java
NEW   src/main/java/com/example/mealprep/feedback/bridge/ProvisionsFeedbackBridge.java
NEW   src/main/java/com/example/mealprep/feedback/bridge/RecipeFeedbackBridge.java
NEW   src/main/java/com/example/mealprep/feedback/bridge/internal/FeedbackBridgeIdempotencyCleanupScheduler.java
NEW   src/main/java/com/example/mealprep/feedback/bridge/internal/FeedbackBridgeIdempotencyRepository.java
NEW   src/main/java/com/example/mealprep/feedback/bridge/internal/FeedbackBridgeIdempotency.java         (entity)

NEW   src/main/java/com/example/mealprep/feedback/exception/FeedbackBridgeDispatchFailedException.java

MOD   src/main/java/com/example/mealprep/feedback/event/FeedbackProcessedEvent.java                      (implement OriginAwareEvent if not already)
MOD   src/main/java/com/example/mealprep/config/GlobalExceptionHandler.java                              (1 new mapping)
MOD   src/test/java/com/example/mealprep/feedback/FeedbackBoundaryTest.java                              (bridge ArchUnit rule)

NEW   src/test/java/com/example/mealprep/feedback/PreferenceFeedbackBridgeTest.java
NEW   src/test/java/com/example/mealprep/feedback/NutritionFeedbackBridgeTest.java
NEW   src/test/java/com/example/mealprep/feedback/ProvisionsFeedbackBridgeTest.java
NEW   src/test/java/com/example/mealprep/feedback/RecipeFeedbackBridgeTest.java
NEW   src/test/java/com/example/mealprep/feedback/FeedbackBridgeIdempotencyIT.java
NEW   src/test/java/com/example/mealprep/feedback/FeedbackBridgeEndToEndIT.java                           (Testcontainers — all 4 bridges round-trip)
```

Total: ~15 new + 3 mods + 2 deletes. Estimated agent runtime 5-7 hours.

## Dependencies

- **Hard dependency**: `tickets/preference/01c-taste-profile-entity.md` — `TasteProfileUpdateService.applyDeltas` interface must exist.
- **Hard dependency**: `tickets/core/02b-origin-tracking-foundation.md` — `OriginContext`, `Origin.AI_FEEDBACK`, `AuditMetadata`, `OriginAwareEvent`.
- **Hard dependency**: `feedback-01d` (merged) — `FeedbackProcessedEvent` publisher.
- **Hard dependency**: `nutrition-XX` (merged) — `NutritionTargetsUpdateService` exists.
- **Hard dependency**: `provisions-XX` (merged) — `EquipmentUpdateService`, `InventoryUpdateService` exist.
- **Hard dependency**: `adaptation-pipeline-XX` (merged) — `OptimiserService.handleRecipeFeedback(...)` exists.

## Acceptance / DoD

- [ ] `./mvnw -Dspotless.check.skip=true verify` passes
- [ ] `./mvnw spotless:apply` then `./mvnw spotless:check` clean
- [ ] CI green (build + spotless + ArchUnit + contract tests)
- [ ] All edge-case items above ticked
- [ ] `FeedbackBridgeEndToEndIT` verifies all four bridges fire end-to-end against real downstream modules
- [ ] PR description includes a workflow trace: feedback submitted → classified → routed → bridge fires → destination audit row written with `AI` attribution
- [ ] **The "system can't learn" audit finding is closed** — the loop is wired, even though the preference applier is still stubbed (that's the next ticket)

## What's NOT in scope

- **`TasteProfileDeltaApplier` real implementation** — deferred from `preference/01c`. The bridge is wired against the (today-stubbed) interface.
- **Reverters for misclassification correction** — deferred per §19-20.
- **`pending_suggestions` table for low-confidence AI suggestions** — deferred per the design doc.
- **Distributed idempotency cache** (Redis) — single-instance in-DB suffices.
- **Frontend UX** showing AI-driven changes — frontend concern.
- **Quality-monitoring dashboard** showing bridge success/failure rates — `lld/feedback.md` §Quality Monitoring is its own future ticket.
- **Cross-tenant safety for service-token-driven bridges** — none of the four bridges use service tokens; they all use Pattern A (inherited session).

Squash-merge with: `feat(feedback): 01g — real destination bridges (replaces 4 Noop configurations; closes the learning loop)`
