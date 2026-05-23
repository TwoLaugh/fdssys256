# Ticket: feedback — 01h Misclassification Reverters (implement the four `*FeedbackReverter` SPIs)

## Summary

Replace the four log-only Noop reverters in [`src/main/java/com/example/mealprep/feedback/config/NoopFeedbackRevertersConfiguration.java:32-70`](../../src/main/java/com/example/mealprep/feedback/config/NoopFeedbackRevertersConfiguration.java) with **real `revert(RevertContext)` implementations** per destination. The misclassification-correction flow (`feedback-01f`) is already wired: `FeedbackServiceImpl.correctMisclassification` ([`FeedbackServiceImpl.java:171-252`](../../src/main/java/com/example/mealprep/feedback/domain/service/FeedbackServiceImpl.java)) calls `bestEffortRevert(entry, original)` ([`FeedbackServiceImpl.java:283-305`](../../src/main/java/com/example/mealprep/feedback/domain/service/FeedbackServiceImpl.java)), which switches on `original.getDestination()` and invokes the matching SPI. Today every SPI is the Noop default — the correction records the ground-truth `MisclassificationCorrection` row and fires the synthetic replay, but the original destination's write is never undone. This ticket makes the undo real (best-effort) per [`lld/feedback.md` §Flow 4 step 3 (lines 796-801)](../../lld/feedback.md) and [`design/feedback-system.md` lines 257-264](../../design/feedback-system.md).

Ships four `@Component` adapters (one per destination module), each satisfying `@ConditionalOnMissingBean` so the matching Noop in `NoopFeedbackRevertersConfiguration` steps aside:
- **`RecipeFeedbackReverter`** — cancel a pending recipe adaptation if still awaiting approval; log-only if already applied.
- **`PreferenceFeedbackReverter`** — best-effort taste-profile delta rollback (depends on `preference/01h` rollback/replay).
- **`ProvisionsFeedbackReverter`** — revert cost-concern logs / supplier-cache writes; equipment + waste changes stay immutable (log-only).
- **`NutritionFeedbackReverter`** — cancel an un-accepted proposal; log-only / best-effort otherwise.

Closes: the undo half of **C-IMP-007**/**C-G-037** correction (the application half landed in `feedback-01g`/`01f`). The reverter SPI contracts (`PreferenceFeedbackReverter`, `NutritionFeedbackReverter`, `ProvisionsFeedbackReverter`, `RecipeFeedbackReverter`) and `RevertContext` already exist from `feedback-01f` — no SPI authoring here, only the impls.

**Dependency / ordering**: order this **AFTER** `preference/01f` (taste-profile delta applier — A1/A2) and `preference/01h` (version rollback + feedback-cursor replay) merge — the preference reverter calls the rollback surface those tickets ship. The recipe reverter needs an adaptation-cancel surface that does **not exist today** (see §Implementation, Recipe). Standalone from `01i`/`01j`. **This ticket MAY be split per-destination** — see §Splitting.

## Behavioural spec

The contract is fixed by the SPI + the caller. From [`PreferenceFeedbackReverter.java:12-15`](../../src/main/java/com/example/mealprep/feedback/spi/PreferenceFeedbackReverter.java) (all four are identical shape):

```java
public interface PreferenceFeedbackReverter { void revert(RevertContext ctx); }
```

and [`RevertContext.java:15-21`](../../src/main/java/com/example/mealprep/feedback/spi/RevertContext.java):

```java
public record RevertContext(
    UUID originalRoutingId, UUID userId, UUID traceId,
    Destination originalDestination,
    JsonNode structuredPayload, JsonNode destinationResultJson) {}
```

**Invariant (all four impls)**: `revert` is **best-effort and MUST NOT throw** to block the correction. The caller already wraps the call in a `try/catch` that logs WARN and proceeds ([`FeedbackServiceImpl.java:292-304`](../../src/main/java/com/example/mealprep/feedback/domain/service/FeedbackServiceImpl.java)), but each impl should still degrade gracefully (catch its own domain exceptions, log structured WARN, return) so the WARN message names the destination + correlation handle rather than leaking a stack trace. Each impl reads its correlation handle out of `structuredPayload` / `destinationResultJson` (the destination decides its own shape — see [`RevertContext` javadoc](../../src/main/java/com/example/mealprep/feedback/spi/RevertContext.java)).

**Transaction phase (GOTCHA, decision-log 0010)**: `revert` runs **inside** `correctMisclassification`'s default `@Transactional` (REQUIRED) bookkeeping tx ([`FeedbackServiceImpl.java:172`](../../src/main/java/com/example/mealprep/feedback/domain/service/FeedbackServiceImpl.java)) — it is NOT an AFTER_COMMIT listener. A destination write done in `revert` therefore commits atomically with the `CORRECTED_AWAY` flip + the `MisclassificationCorrection` row. Each reverter's downstream service call keeps plain `@Transactional` (REQUIRED) so it **joins** this tx — do NOT add `REQUIRES_NEW` (that would detach the undo from the correction record and break atomicity).

### `RecipeFeedbackReverter` (adaptation pipeline)

Per [`lld/feedback.md:797`](../../lld/feedback.md): *"cancel the pending adaptation if `AWAITING_USER_APPROVAL`; if already approved or applied, the correction is log-only."*

1. New `@Component RecipeFeedbackReverterImpl` in the **adaptation module** (`adaptation.feedback` or `adaptation.spi.internal` — mirror where the recipe-side feedback adapter lives; verify at agent start) implementing `com.example.mealprep.feedback.spi.RecipeFeedbackReverter`.
2. Read the pending-change / adaptation-job handle from `ctx.destinationResultJson()` (the recipe bridge records the `pendingChangeId` / `jobId` it created — verify the recipe bridge writes a result handle; if it does not, that is a prerequisite gap, flag it).
3. **GOTCHA — the cancel surface does not exist yet.** `AdaptationService` ([`src/main/java/com/example/mealprep/adaptation/domain/service/AdaptationService.java`](../../src/main/java/com/example/mealprep/adaptation/domain/service/AdaptationService.java)) exposes `rejectPendingChange(pendingChangeId, RejectPendingChangeRequest, actorUserId)` ([`AdaptationService.java:48-49`](../../src/main/java/com/example/mealprep/adaptation/domain/service/AdaptationService.java)) and `acceptPendingChange`, but **no `cancelAdaptation`** and the recipe-feedback entry is `enqueueFeedbackJob` (the `OptimiserService.handleRecipeFeedback` forward-reference resolves here per [`tickets/WAVE3-NAMING-RECONCILIATION.md`](../WAVE3-NAMING-RECONCILIATION.md)). **Decision (worth user review)**: reuse `rejectPendingChange` to cancel a still-pending change (its "already-rejected → 422" guard is exactly the "already-applied → log-only" branch — catch the 422 and downgrade to a log line), OR add a thin `cancelPendingChange(pendingChangeId, actorUserId)` to `AdaptationService` that no-ops on non-pending. **Recommendation: reuse `rejectPendingChange`** and catch its terminal-state exception → log-only. Do NOT invent a brand-new cancel pipeline.
4. If no pending change is reachable (already applied / no handle) → structured WARN "recipe revert is log-only; previous suggestion kept" and return. The synthetic replay (handled by the caller) re-routes correctly regardless.

### `PreferenceFeedbackReverter`

Per [`lld/feedback.md:798`](../../lld/feedback.md): *"call the preference revert surface; if the delta has already been applied and rolled into a new `documentVersion`, the revert is best-effort (rollback may be partial)."*

5. New `@Component PreferenceFeedbackReverterImpl` in the **preference module** (`preference.spi.internal` — mirror the package the directive `DirectiveApplyTarget` impl lands in for `01j`) implementing `com.example.mealprep.feedback.spi.PreferenceFeedbackReverter`.
6. **Hard dependency on `preference/01h`**: the rollback surface (`PreferenceUpdateService.rollbackTasteProfile(userId, targetDocumentVersion, actorUserId)` per [`lld/preference.md:570-572`](../../lld/preference.md)) and the feedback-cursor replay are owned by `preference/01h`. This reverter calls that surface to roll the taste profile back to the `documentVersion` that preceded the original routing's delta apply.
7. **Locating the target version**: the original routing's delta apply stamped a version snapshot keyed by the feedback range (`feedback-<feedbackId>`). Read the pre-apply version from `ctx.destinationResultJson()` if the preference bridge recorded it; else look up the version snapshot whose `deltasApplied` trace matches `ctx.originalRoutingId()`/`ctx.traceId()`. **If the version is not resolvable** (no snapshot, or the delta apply itself failed/booked FAILED) → log-only WARN, no rollback. The applier from `preference/01f` flips `tasteVectorStatus = PENDING` on apply; the rollback path inherits whatever `preference/01h` does for re-embedding (out of scope here).
8. **Best-effort semantics**: if newer deltas were applied on top (the `documentVersion` advanced past the corrected one), a clean revert is impossible; `preference/01h`'s replay-from-cursor is the designed reconciliation. This reverter triggers the rollback-then-replay and accepts a partial result, logging the divergence per [`design/feedback-system.md` §Correction limitations](../../design/feedback-system.md).

### `ProvisionsFeedbackReverter`

Per [`lld/feedback.md:800`](../../lld/feedback.md): *"revert cost-concern logs and supplier-cache writes; equipment changes and waste log entries are immutable and the correction is log-only."*

9. New `@Component ProvisionsFeedbackReverterImpl` in the **provisions module** implementing `com.example.mealprep.feedback.spi.ProvisionsFeedbackReverter`.
10. Read the `provisionsAction` + the written-row handle from `ctx.structuredPayload()`/`ctx.destinationResultJson()`. Dispatch:
    - **`MARK_DEPLETED`** (lands once `provisions/01i` ships the by-key lookup): if the inventory row was flipped `EXHAUSTED` by the feedback, the reverter has NO clean "un-exhaust" path today — `markExhausted` is one-way and there is no inverse on `ProvisionUpdateService`. **Recommendation: log-only** for inventory-status reverts (an un-exhaust would mis-state real-world stock). Document this clearly.
    - **`REMOVE_EQUIPMENT`** — equipment deletion is immutable per the LLD; **log-only** (re-adding equipment would fabricate a row the user never created).
    - Cost-concern logs / supplier-cache writes (the `ADJUST_BUDGET` family, reserved/future) — when those land, revert the cache write; today **log-only** with `unsupported-provisions-revert`.
11. **Net for v1**: provisions revert is effectively **log-only** because the implemented provisions feedback actions (`REMOVE_EQUIPMENT`, and `MARK_DEPLETED` once wired) are both one-way per the LLD's immutability rule. State this honestly — do NOT fabricate inverse writes. The reverter's value is the structured WARN naming the un-revertable action for quality monitoring.

### `NutritionFeedbackReverter`

Per [`lld/feedback.md:799`](../../lld/feedback.md): *"if the original routing was a proposal the user hadn't accepted, cancel the proposal; if it was a journal append, leave the journal entry."*

12. New `@Component NutritionFeedbackReverterImpl` in the **nutrition module** implementing `com.example.mealprep.feedback.spi.NutritionFeedbackReverter`.
13. The implemented nutrition feedback path (`nutrition/01i` — `applyFeedbackAdjustment`) is a **direct single-field target write** (audited `actor_kind = feedback`, `@Version` bumped), not a propose/accept proposal. There is no proposal to cancel for the 01i path. **Decision**: the nutrition reverter is **log-only / best-effort** in v1 — undoing a feedback target nudge would require a stored before-value + an inverse write, which overlaps the deferred adjustment-undo (`C-IMP-021`, explicitly out of scope per [`tickets/nutrition/01i-feedback-target-adjustment.md` §What's NOT in scope](../nutrition/01i-feedback-target-adjustment.md)). Read the `nutrition_targets_audit` row by `origin_trace = feedback-<feedbackId>` and log-WARN the un-reverted field. If a future propose/accept nutrition feedback flow (Flow 10 proposals, [`lld/nutrition.md:1032-1036`](../../lld/nutrition.md)) lands, this reverter cancels the un-accepted proposal then.

### Splitting

14. **This ticket may be split per-destination** if too large for one PR. Suggested split:
    - **01h-a**: Recipe + Provisions + Nutrition reverters (the three that are largely log-only / cancel-pending — no upstream rollback dependency). Unblocked today.
    - **01h-b**: Preference reverter — **gated on `preference/01h`** (rollback/replay). Ship after.
    The four impls are independent `@Component`s; splitting is mechanical (each lives in its own destination module). Keep the IT (§Test) per-destination so a split doesn't strand a cross-cutting test.

### Cross-cutting

15. No new SPI, no new `RevertContext`, no `FeedbackServiceImpl` change (the switch + `bestEffortRevert` already dispatch). The only feedback-module touch is **deleting the now-shadowed Noop `@Bean` methods** is NOT required — leave `NoopFeedbackRevertersConfiguration` in place; each real `@Component` out-ranks its matching Noop via `@ConditionalOnMissingBean` (the same SPI-with-Noop pattern documented at [`NoopFeedbackRevertersConfiguration.java:20-24`](../../src/main/java/com/example/mealprep/feedback/config/NoopFeedbackRevertersConfiguration.java)). Removing a Noop bean would break any module that hasn't yet shipped its real reverter.
16. ArchUnit: each impl lives in its destination module and implements a `feedback.spi` interface — the cross-module SPI direction (destination depends on feedback's SPI package) is the established pattern; confirm `ProvisionsBoundaryTest` / the preference + adaptation boundary tests allow the `feedback.spi` import.

### Events

17. **Published**: none new (the reverters mutate destination state in-tx; any destination event — e.g. `TasteProfileChangedEvent` on rollback — fires from the destination service it calls). **Consumed**: none (invoked in-process via the SPI).

## Database

```
(none — no schema changes. The preference reverter relies on preference/01h's version-snapshot tables; the others read existing audit/result columns.)
```

## OpenAPI updates

**No OpenAPI changes.** Reverters are in-process SPI implementations with no HTTP surface (mirrors the bridges).

## Edge-case checklist

- [ ] **Recipe — pending change cancelled**: original RECIPE routing left a pending change `AWAITING_USER_APPROVAL` → reverter cancels it (via `rejectPendingChange`); correction proceeds; replay re-routes.
- [ ] **Recipe — already applied**: pending change already accepted/applied → reverter catches the terminal-state 422 → log-only WARN; no throw; correction proceeds.
- [ ] **Recipe — no handle**: `destinationResultJson` carries no pending-change id → log-only WARN; no throw.
- [ ] **Preference — clean rollback**: delta applied at v15, no newer deltas → `rollbackTasteProfile` to v14; `documentVersion` reverts; audit row written by preference.
- [ ] **Preference — partial (newer deltas on top)**: v15 superseded by v16/v17 → best-effort rollback + replay-from-cursor (preference/01h); divergence logged; no throw.
- [ ] **Preference — unresolvable version**: no snapshot for the routing trace → log-only WARN; no rollback; no throw.
- [ ] **Preference — `preference/01h` not merged**: reverter compiles against the `rollbackTasteProfile` interface; if absent, the ticket is blocked (ordering note).
- [ ] **Provisions — equipment removal**: `REMOVE_EQUIPMENT` correction → log-only (immutable); structured WARN names `unsupported-provisions-revert`; no throw.
- [ ] **Provisions — mark-depleted**: `MARK_DEPLETED` correction → log-only (no inverse on `ProvisionUpdateService`); no throw.
- [ ] **Nutrition — feedback adjustment**: original NUTRITION routing nudged a target via `applyFeedbackAdjustment` → reverter logs the un-reverted `origin_trace=feedback-<id>` field; no inverse write; no throw.
- [ ] **All four — never throw**: a forced exception inside any reverter is caught internally → structured WARN; `correctMisclassification` still records the `MisclassificationCorrection` row and fires the synthetic replay (verified by the existing [`MisclassificationCorrectionIT`](../../src/test/java/com/example/mealprep/feedback/MisclassificationCorrectionIT.java) staying green).
- [ ] **`@ConditionalOnMissingBean` win**: each real `@Component` out-ranks its Noop; `CorrectionReverterSpiTest` (currently asserts Noops are resolved) is updated so the resolved bean is the real impl for any destination whose module is on the test classpath.
- [ ] **Transaction join (decision-log 0010)**: a preference rollback done in `revert` commits atomically with the `CORRECTED_AWAY` flip + `MisclassificationCorrection` row (IT asserts all present after commit, none if the bookkeeping rolls back).
- [ ] **Cross-tenant**: a reverter only touches `ctx.userId()`'s rows.

## Files this ticket touches

```
NEW   src/main/java/com/example/mealprep/adaptation/spi/internal/RecipeFeedbackReverterImpl.java          (verify package vs the recipe bridge adapter location)
NEW   src/main/java/com/example/mealprep/preference/spi/internal/PreferenceFeedbackReverterImpl.java       (calls preference/01h rollbackTasteProfile)
NEW   src/main/java/com/example/mealprep/provisions/spi/internal/ProvisionsFeedbackReverterImpl.java       (log-only per immutability rule)
NEW   src/main/java/com/example/mealprep/nutrition/spi/internal/NutritionFeedbackReverterImpl.java         (log-only / cancel-proposal)

MOD   src/main/java/com/example/mealprep/adaptation/domain/service/AdaptationService.java                 (ONLY IF reuse of rejectPendingChange is rejected — add cancelPendingChange; see §Implementation Recipe)

MOD   src/test/java/com/example/mealprep/feedback/CorrectionReverterSpiTest.java                           (assert real impls win over Noops where module on classpath)
NEW   src/test/java/com/example/mealprep/adaptation/RecipeFeedbackReverterTest.java                        (unit — cancel-pending vs log-only)
NEW   src/test/java/com/example/mealprep/preference/PreferenceFeedbackReverterTest.java                    (unit — clean / partial / unresolvable)
NEW   src/test/java/com/example/mealprep/provisions/ProvisionsFeedbackReverterTest.java                    (unit — log-only paths)
NEW   src/test/java/com/example/mealprep/nutrition/NutritionFeedbackReverterTest.java                      (unit — log-only)
NEW   src/test/java/com/example/mealprep/feedback/MisclassificationCorrectionRevertIT.java                 (Testcontainers — correction → real revert → atomic commit; one per destination if split)
```

Total: ~4 new impls + 5 test files (+1 conditional adaptation mod). Estimated agent runtime 5-7 days (dominated by the preference rollback wiring + the four cross-module ITs; the recipe cancel-surface decision and the provisions immutability honesty are the design-heavy parts).

## Dependencies

- **Hard dependency**: `feedback-01f` (merged) — the four `*FeedbackReverter` SPIs, `RevertContext`, `correctMisclassification` + `bestEffortRevert` dispatch, `CorrectionReverterSpiTest`.
- **Hard dependency**: `preference/01h` (rollback + feedback-cursor replay) — **for the preference reverter only**; if not yet merged, ship 01h-b after (see §Splitting). Also needs `preference/01f` (the applier whose deltas are being rolled back) merged.
- **Hard dependency**: `feedback-01g` (merged) — the bridges that wrote the destination state being reverted; their result handles (`destinationResultJson`) are the reverter's correlation source.
- **Soft / informs**: the recipe cancel-surface decision touches `adaptation` (`rejectPendingChange` reuse vs a new `cancelPendingChange`).

## Acceptance / DoD

- [ ] `./mvnw -Dspotless.check.skip=true verify` passes
- [ ] `./mvnw spotless:apply` then `./mvnw spotless:check` clean
- [ ] **Run the full feedback + preference + provisions + nutrition + adaptation module IT suites locally with Docker** + spotless before pushing. Docker IS available locally. Run ITs in ~3-class batches to avoid the Hikari pool-exhaustion flake.
- [ ] CI green (build + spotless + ArchUnit + contract tests)
- [ ] All edge-case items above ticked
- [ ] PR description traces: PREFERENCE feedback applied at v15 → user corrects to PROVISIONS → preference reverter rolls back to v14 (or best-effort replay) → `CORRECTED_AWAY` + `MisclassificationCorrection` row committed atomically → synthetic PROVISIONS replay APPLIED.

## What's NOT in scope

- **The reverter SPIs / `RevertContext`** — already shipped by `feedback-01f`.
- **`preference/01h` rollback + feedback-cursor replay machinery** — its own merged ticket; this ticket only *calls* it.
- **A clean inverse for `applyFeedbackAdjustment`** (storing before-values + inverse write) — `C-IMP-021`, deferred; the nutrition reverter is log-only.
- **An inventory "un-exhaust" surface** on `ProvisionUpdateService` — would mis-state real-world stock; provisions revert stays log-only.
- **Re-embedding** on a preference rollback — owned by the deferred embedding vertical (audit E5).
- **A pending-suggestions / quality-monitoring dashboard** surfacing un-revertable corrections — `lld/feedback.md` §Quality Monitoring, its own future ticket.

Squash-merge with: `feat(feedback): 01h — misclassification reverters (real revertFeedback per destination; best-effort undo)`
