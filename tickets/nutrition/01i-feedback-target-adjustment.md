# Ticket: nutrition — 01i Single-Field Feedback Target Adjustment (`applyFeedbackAdjustment`)

## Summary

Add a **single-field, relative target adjustment** write surface to the nutrition module so the already-wired `NutritionFeedbackBridgeImpl` can stop recording `FAILED` and actually nudge one macro/micro target. Today the bridge is fully wired (confidence floor, idempotency, AI origin attribution, payload parse) but books a deferred `FAILED` because `NutritionUpdateService` exposes only `updateTargets(userId, UpdateTargetsRequest, actorUserId)` — a **full-document-replacement** write requiring the complete targets payload + an optimistic `expectedVersion`, which is unsafe for a fire-and-forget AFTER-routing bridge ([`src/main/java/com/example/mealprep/feedback/bridge/NutritionFeedbackBridgeImpl.java:24-37, 91-114`](../../src/main/java/com/example/mealprep/feedback/bridge/NutritionFeedbackBridgeImpl.java)). The classifier emits `{target, direction, magnitude, absoluteValue, reason}` ([`tickets/feedback/01g-destination-bridges.md:88-95`](../feedback/01g-destination-bridges.md)).

Per [`design/feedback-system.md` lines 102-113, 344-346, 436](../../design/feedback-system.md) ("The Nutrition Model decides how to interpret 'portions too small'... portion complaints adjust per-meal distribution, protein complaints adjust protein floors") and [`design/nutrition-model.md` line 572](../../design/nutrition-model.md). LLD: [`lld/nutrition.md` lines 327-340 (targets audit, `actor_kind = feedback`), 356-359](../../lld/nutrition.md).

This is **ONE ticket**. Numbered `01i` (nutrition is at 01a–01h; this is an additive method on an existing aggregate, not a new phase — matches the module's single-phase convention).

Ships:
- **`NutritionUpdateService.applyFeedbackAdjustment(userId, target, direction, magnitude)`** + impl — nudge one macro/micro target by a **relative magnitude**, audited (`actor_kind = feedback`), version-bumped.
- **Flip the bridge** ([`NutritionFeedbackBridgeImpl.java:91-114`](../../src/main/java/com/example/mealprep/feedback/bridge/NutritionFeedbackBridgeImpl.java)) from the `DEFERRED_REASON` FAILED path to calling `applyFeedbackAdjustment` via the origin-tracking pattern (`actor_type = AI` / `origin_trace`).

Closes: the nutrition half of the feedback learning loop (the bridge's deferred FAILED). Capability: closes the `NutritionFeedbackBridge` real-dispatch deferral noted in [`tickets/feedback/01g-destination-bridges.md:96`](../feedback/01g-destination-bridges.md) ("If `applyFeedbackAdjustment` doesn't exist... the nutrition LLD gets a follow-up note" — this IS that follow-up).

**Dependency**: `nutrition-01a` (merged — `nutrition_targets` aggregate, `nutrition_targets_audit` with the `feedback` actor_kind, `MacroTargetDto`/`MicroTargetDto`/`CalorieTargetDto`, `NutritionTargetsChangedEvent`), `feedback-01g` (merged — the bridge this ticket flips).

## Behavioural spec

### Service method

1. Add to `NutritionUpdateService`:
   ```java
   /**
    * Apply a single-field, relative feedback-driven adjustment to one nutrition target. Unlike
    * {@link #updateTargets}, this is NOT a full-document replacement: it reads the current value of
    * the named target, nudges it by a relative magnitude in the given direction, and writes only
    * that field — safe to call fire-and-forget from the feedback bridge (no client-supplied
    * expectedVersion; the @Version bump is internal). Writes one nutrition_targets_audit row with
    * actor_kind = feedback. Publishes NutritionTargetsChangedEvent.
    */
   TargetsDto applyFeedbackAdjustment(UUID userId, FeedbackTargetAdjustment adjustment);
   ```
   where `FeedbackTargetAdjustment` is a record carrying the classifier's shape (see §2). (The prompt's free signature `applyFeedbackAdjustment(userId, target, direction, magnitude)` is honoured via the record — keeping a single param object is cleaner than 4 positional args and lets `reason`/`absoluteValue` ride along for the audit.)
2. **`FeedbackTargetAdjustment`** record (`nutrition.api.dto`):
   ```java
   public record FeedbackTargetAdjustment(
       @NotBlank @Size(max = 96) String target,        // "calorie_target" | "protein_target_g" | "micro.sodium_mg" | "per_meal.lunch.calorie_target"
       @NotNull AdjustmentDirection direction,          // INCREASE | DECREASE
       @NotNull AdjustmentMagnitude magnitude,          // SMALL | MODERATE | LARGE  (relative steps)
       @Nullable BigDecimal absoluteValue,              // optional explicit target the classifier extracted; overrides relative nudge when present
       @Size(max = 256) String reason) {}
   ```
   New enums `AdjustmentDirection {INCREASE, DECREASE}`, `AdjustmentMagnitude {SMALL, MODERATE, LARGE}` in `nutrition.domain.entity` (module-local per the style guide).
3. **Relative magnitude → percentage** (deterministic, config-overridable): `SMALL = 5%`, `MODERATE = 10%`, `LARGE = 20%` of the current value, applied in `direction`. Held in a `@ConfigurationProperties(prefix="mealprep.nutrition.feedback-adjustment")` record (`smallPct`/`moderatePct`/`largePct`). **GOTCHA**: this `@ConfigurationProperties` record needs `@EnableConfigurationProperties` in any `@WebMvcTest` slice that loads it; the `@SpringBootTest` IT just needs the bean present.
4. **`absoluteValue` precedence**: if present, set the target to it directly (the classifier extracted an explicit "increase my calorie target to 2200"); else compute `current ± pct·current`.
5. **Target path resolution**: a small allow-set + resolver mapping `target` strings to the aggregate's fields:
   - Macro/calorie: `calorie_target` → `CalorieTargetDto.dailyTarget`; `protein_target_g`/`carbs_target_g`/`fat_target_g`/`fibre_target_g`/`sat_fat_target_g` → the matching `MacroTargetDto.targetG`.
   - Micro: `micro.<nutrient_key>` → the `MicroTarget` child row for `nutrient_key` (`targetValue`). If the micro target row doesn't exist, **no-op with WARN** (the user hasn't opted into that micro — do not create it from feedback). **Worth implementer review.**
   - Per-meal: `per_meal.<slot>.<field>` → the `PerMealDistributionEntry` for that slot. (Portion-complaint adjustments per [`design/feedback-system.md:113`](../../design/feedback-system.md).)
   - Unknown `target` → `InvalidFeedbackAdjustmentException` (422). The bridge catches it (booking FAILED, reason `unsupported-adjustment-target`).
6. **Clamping / sanity floor**: never drive a target ≤ 0 (clamp to a configurable floor, default the field's existing minimum, e.g. calorie floor 1000). A `DECREASE LARGE` that would breach the floor clamps + logs WARN rather than throwing.
7. **Direction-field interaction**: adjusting `targetG` does NOT change the macro's `EnforcementDirection`/`isHardFloor` ([`lld/nutrition.md:416`](../../lld/nutrition.md)) — feedback nudges the magnitude only, never the enforcement semantics (those are user/goal-driven). Leave `userOverriddenDirections` untouched.
8. **Audit**: write ONE `nutrition_targets_audit` row with `actor_kind = "feedback"`, `actor_user_id = userId` (the feedback originated from the user; the *agent* is AI but the audit's `actor_kind=feedback` is the designed channel — [`lld/nutrition.md:334`](../../lld/nutrition.md)), `field_path` = the resolved target path, `previous_value_json`/`new_value_json`, `occurred_at = now`. **`source_directive_id` stays null** (that column is for health-directive provenance, not feedback). Record the origin trace (§10) — if the audit table lacks an origin column, put the trace in the `reason`/a new nullable `origin_trace varchar(128)` column (see Database).
9. **Version bump + event**: the `@Version` on `NutritionTargets` bumps internally (no client `expectedVersion` — this is the key difference from `updateTargets`). Publish `NutritionTargetsChangedEvent` carrying the single changed `field_path` ([`lld/nutrition.md:39-40`](../../lld/nutrition.md)). `@Transactional`.

### Origin-tracking attribution

10. The bridge calls `applyFeedbackAdjustment` with `actor_type = AI` + `origin_trace = feedback-<feedback_id>` per [`design/origin-tracking-pattern.md`](../../design/origin-tracking-pattern.md). The audit row's `actor_kind = feedback` is the nutrition-side classification; the `origin_trace` is the cross-cutting provenance. If `nutrition_targets_audit` has no origin columns yet (it predates the origin-tracking foundation), add a nullable `origin_trace varchar(128)` column via a small migration (§Database) — mirrors `V20260615180100__core_add_audit_origin_columns.sql` which added origin columns to other modules' audit tables.

### Flip the bridge

11. In `NutritionFeedbackBridgeImpl.applyAdjustment` ([`NutritionFeedbackBridgeImpl.java:91-114`](../../src/main/java/com/example/mealprep/feedback/bridge/NutritionFeedbackBridgeImpl.java)): remove the `DEFERRED_REASON` throw; parse the payload into `FeedbackTargetAdjustment`; call `nutritionUpdateService.applyFeedbackAdjustment(input.userId(), adjustment)`; on success `recordOutcome(..., DISPATCHED)` and return a DISPATCHED `Result`. On `InvalidFeedbackAdjustmentException` (unknown target) → `recordOutcome(..., FAILED)` + structured log + `throw failed(...)` (so the router records the failure) — mirror the preference bridge's catch shape ([`PreferenceFeedbackBridgeImpl.java:96-129`](../../src/main/java/com/example/mealprep/feedback/bridge/PreferenceFeedbackBridgeImpl.java)). Drop the now-unused `DEFERRED_REASON` constant and the `@SuppressWarnings("unused")` on the injected service ([`NutritionFeedbackBridgeImpl.java:44-49`](../../src/main/java/com/example/mealprep/feedback/bridge/NutritionFeedbackBridgeImpl.java)).
12. **GOTCHA (AFTER_COMMIT, decision-log 0010)**: the bridge fires from an AFTER_COMMIT event listener and already runs under `@Qualifier(REQUIRES_NEW_TX_TEMPLATE) TransactionTemplate` ([`NutritionFeedbackBridgeImpl.java:54-57`](../../src/main/java/com/example/mealprep/feedback/bridge/NutritionFeedbackBridgeImpl.java)). `applyFeedbackAdjustment` keeps plain `@Transactional` (REQUIRED) so it **joins** the bridge's REQUIRES_NEW template tx — do NOT add `REQUIRES_NEW` to `applyFeedbackAdjustment` itself. Verify in the IT that the targets update + audit row commit together with the bridge's `DISPATCHED` idempotency row.

### Cross-cutting

13. New exception `InvalidFeedbackAdjustmentException` (422) — mapped in the **per-module nutrition `@RestControllerAdvice`** (`NutritionExceptionHandler`), never the global one. Note `applyFeedbackAdjustment` is in-process (no REST surface), so the 422 propagates to the bridge which catches it — but the handler mapping is still needed for completeness/consistency.
14. **No REST endpoint** — `applyFeedbackAdjustment` is invoked in-process by the bridge only (mirrors `applyDeltas` on the preference side). No OpenAPI path.
15. ArchUnit: any new repository query stays in `nutrition.domain.repository` (likely none — the existing `NutritionTargetsRepository.findByUserId` suffices).

### Events

16. **Published**: `NutritionTargetsChangedEvent` (single changed field). **Consumed**: none new (the bridge is the in-process caller).

## Database

```
NEW   src/main/resources/db/migration/V20260615240100__nutrition_add_targets_audit_origin_trace.sql   (ONLY IF nutrition_targets_audit lacks an origin column — verify at agent start)
```

If needed:
```sql
ALTER TABLE nutrition_targets_audit ADD COLUMN origin_trace varchar(128);
```
(Timestamp continues the scheme; latest in-tree is `V20260615230000`. `V20260615240000` is reserved by `preference-01g`; this uses `240100`. Coordinate the exact free slot at merge.)

No other schema change — the adjustment writes existing target columns + an existing audit table.

## OpenAPI updates

**No OpenAPI changes.** `applyFeedbackAdjustment` is in-process only (no HTTP surface), exactly like the preference `applyDeltas` path.

## Edge-case checklist

- [ ] **Migration applies cleanly** (if added); `FlywayMigrationIT` passes; `ddl-auto=validate` accepts the column.
- [ ] **Macro INCREASE MODERATE**: protein 150g → 165g (+10%); `field_path = "protein_target_g"`; one audit row `actor_kind=feedback`; `@Version` bumped.
- [ ] **Calorie DECREASE SMALL**: 2000 → 1900 (−5%).
- [ ] **absoluteValue precedence**: `{target: calorie_target, absoluteValue: 2200}` → set to 2200 exactly, ignoring magnitude.
- [ ] **Micro adjustment**: `micro.sodium_mg` DECREASE MODERATE on an existing micro target row → nudged; audit `field_path = "micro.sodium_mg.target"`.
- [ ] **Micro not opted-in**: `micro.iron_mg` adjustment when no iron micro target row exists → no-op WARN, no row created, no audit, no event.
- [ ] **Per-meal adjustment**: `per_meal.lunch.calorie_target` INCREASE → the lunch `PerMealDistributionEntry` nudged.
- [ ] **Unknown target**: `{target: "vibes"}` → `InvalidFeedbackAdjustmentException` (422) → bridge books FAILED, reason `unsupported-adjustment-target`.
- [ ] **Floor clamp**: calorie DECREASE LARGE that would drop below 1000 → clamped to floor + WARN; never ≤ 0.
- [ ] **Enforcement untouched**: adjusting `protein_target_g` does NOT change its `EnforcementDirection`/`isHardFloor`/`userOverriddenDirections`.
- [ ] **AFTER_COMMIT atomicity (decision-log 0010)**: invoked via the bridge's REQUIRES_NEW template, `applyFeedbackAdjustment` (plain `@Transactional`, joins) commits the target update + audit row together with the bridge's `DISPATCHED` row. IT publishes a `FeedbackProcessedEvent(NUTRITION)` and asserts all rows present after commit.
- [ ] **Bridge happy path**: `FeedbackProcessedEvent(NUTRITION, {target: sodium_mg_max, direction: decrease, magnitude: moderate})` → bridge → `applyFeedbackAdjustment` → DISPATCHED; audit `actor_kind=feedback`, `origin_trace=feedback-<id>`, `actor_type=AI` provenance recorded.
- [ ] **Bridge low confidence** (< 0.5): skipped → `REJECTED_LOW_CONFIDENCE` (unchanged existing behaviour at [`NutritionFeedbackBridgeImpl.java:63-74`](../../src/main/java/com/example/mealprep/feedback/bridge/NutritionFeedbackBridgeImpl.java)).
- [ ] **Bridge idempotency**: two `FeedbackProcessedEvent`s with the same `feedback_id` within the window → second is a no-op (unchanged at [`NutritionFeedbackBridgeImpl.java:75-82`](../../src/main/java/com/example/mealprep/feedback/bridge/NutritionFeedbackBridgeImpl.java)).
- [ ] **Deferred path removed**: grep confirms `DEFERRED_REASON` / the `@SuppressWarnings("unused")` are gone from the bridge.
- [ ] **NutritionTargetsChangedEvent** fires exactly once AFTER_COMMIT carrying the single changed field path.
- [ ] **Cross-tenant**: user A's feedback never adjusts user B's targets.
- [ ] **No expectedVersion required**: the call succeeds without a client-supplied version (the distinguishing property vs `updateTargets`); a concurrent `updateTargets` racing it surfaces as an `OptimisticLockingFailureException` inside the adjustment tx → bridge books FAILED (acceptable — feedback retries).

## Files this ticket touches

```
NEW   src/main/resources/db/migration/V20260615240100__nutrition_add_targets_audit_origin_trace.sql              (conditional — see Database)

MOD   src/main/java/com/example/mealprep/nutrition/domain/service/NutritionUpdateService.java                    (add applyFeedbackAdjustment)
MOD   src/main/java/com/example/mealprep/nutrition/domain/service/internal/NutritionUpdateServiceImpl.java        (impl + path resolver + magnitude/clamp logic)
NEW   src/main/java/com/example/mealprep/nutrition/api/dto/FeedbackTargetAdjustment.java
NEW   src/main/java/com/example/mealprep/nutrition/domain/entity/AdjustmentDirection.java
NEW   src/main/java/com/example/mealprep/nutrition/domain/entity/AdjustmentMagnitude.java
NEW   src/main/java/com/example/mealprep/nutrition/domain/service/internal/FeedbackTargetResolver.java            (target string → field; allow-set)
NEW   src/main/java/com/example/mealprep/nutrition/config/FeedbackAdjustmentProperties.java                      (@ConfigurationProperties record)
NEW   src/main/java/com/example/mealprep/nutrition/exception/InvalidFeedbackAdjustmentException.java
MOD   src/main/java/com/example/mealprep/nutrition/exception/NutritionExceptionHandler.java                      (map the 422)

MOD   src/main/java/com/example/mealprep/feedback/bridge/NutritionFeedbackBridgeImpl.java                        (flip from FAILED to applyFeedbackAdjustment; drop DEFERRED_REASON)

MOD   src/main/resources/application.properties                                                                  (magnitude pct defaults)

NEW   src/test/java/com/example/mealprep/nutrition/FeedbackTargetAdjustmentTest.java                             (unit — macro/micro/per-meal/clamp/absolute)
NEW   src/test/java/com/example/mealprep/nutrition/FeedbackTargetResolverTest.java                               (unit — path allow-set)
NEW   src/test/java/com/example/mealprep/nutrition/FeedbackTargetAdjustmentIT.java                               (Testcontainers — service-layer, audit row, version bump, event)
MOD   src/test/java/com/example/mealprep/feedback/NutritionFeedbackBridgeTest.java                               (replace deferred-FAILED assertions with DISPATCHED)
NEW   src/test/java/com/example/mealprep/feedback/NutritionFeedbackBridgeAdjustsIT.java                          (Testcontainers — FeedbackProcessedEvent → bridge → adjustment, AFTER_COMMIT atomicity)
```

Total: ~9 new + 5 mods. Estimated agent runtime 4-5 hours (the target-path resolver + the macro/micro/per-meal cases + the AFTER_COMMIT-atomicity IT dominate).

## Dependencies

- **Hard dependency**: `nutrition-01a` (merged) — `nutrition_targets` aggregate, `nutrition_targets_audit` (`feedback` actor_kind), `MacroTargetDto`/`MicroTargetDto`/`CalorieTargetDto`/`PerMealDistributionDto`, `NutritionTargetsChangedEvent`, `NutritionExceptionHandler`.
- **Hard dependency**: `feedback-01g` (merged) — `NutritionFeedbackBridgeImpl` (this ticket flips it), `BridgeDispatchStatus`, `FeedbackTxTemplateConfig.REQUIRES_NEW_TX_TEMPLATE`.
- **Soft**: `core-02b` origin-tracking (merged — `V20260615180100`) — the audit origin-trace convention.

## Acceptance / DoD

- [ ] `./mvnw -Dspotless.check.skip=true verify` passes
- [ ] `./mvnw spotless:apply` then `./mvnw spotless:check` clean
- [ ] **Run the full nutrition + feedback module IT suites locally with Docker** + `redocly lint` (no-op here) + spotless before pushing. Docker IS available locally. Run ITs in ~3-class batches (Hikari pool-exhaustion flake on big sweeps).
- [ ] CI green (build + spotless + ArchUnit + contract tests)
- [ ] All edge-case items above ticked
- [ ] PR description traces: `FeedbackProcessedEvent(NUTRITION, decrease sodium moderate)` → bridge → `applyFeedbackAdjustment` → sodium micro target −10% + audit row `feedback`/`origin_trace` + `DISPATCHED`.

## What's NOT in scope

- **AI interpretation of vague nutrition feedback** ("portions too small" → which target?) — the classifier (`feedback-01d`) already emits the structured `{target, direction, magnitude}`; this ticket consumes it deterministically.
- **Cascading mid-week re-opt** triggered by the target change — the planner's concern (it listens to `NutritionTargetsChangedEvent`).
- **Undo of a feedback adjustment** (C-IMP-021, "Nutrition: harder if cascaded") — a reverter ticket, deferred.
- **Multi-field adjustment in one feedback event** — the classifier emits one `{target,...}` per event; multi-target is a future enhancement.
- **Macro-rebalance / activity-adjustment feedback** — those route via health directives, not the feedback bridge.

Squash-merge with: `feat(nutrition): 01i — single-field feedback target adjustment + flip nutrition bridge to live`
