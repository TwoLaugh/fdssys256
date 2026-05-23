# Ticket: preference — 01f Taste-Profile Delta Applier + Budget Guard + Archive Integration

## Summary

Replace the `TasteProfileDeltaApplier.NoopStub` (the `UnsupportedOperationException` thrower at [`src/main/java/com/example/mealprep/preference/domain/service/internal/TasteProfileDeltaApplier.java:38-45`](../../src/main/java/com/example/mealprep/preference/domain/service/internal/TasteProfileDeltaApplier.java)) with the **real deterministic applier** and turn `TasteProfileServiceImpl.applyDeltas` ([`TasteProfileServiceImpl.java:299-315`](../../src/main/java/com/example/mealprep/preference/domain/service/internal/TasteProfileServiceImpl.java)) from a lookup-then-throw stub into the full delta-application path of [`lld/preference.md` Flow 3 lines 717-729](../../lld/preference.md) and [`design/preference-model.md` lines 65-71, 165-176, 417-419](../../design/preference-model.md). This is the keystone that unblocks the feedback→preference mutation: the wired `PreferenceFeedbackBridgeImpl` ([`src/main/java/com/example/mealprep/feedback/bridge/PreferenceFeedbackBridgeImpl.java:91`](../../src/main/java/com/example/mealprep/feedback/bridge/PreferenceFeedbackBridgeImpl.java)) already calls `applyDeltas` and today books a `FAILED` row + records `AI_UNAVAILABLE`; this ticket makes that call succeed.

Ships:
- **`TasteProfileDeltaApplier` real impl** — eight per-op handlers mutating the immutable `TasteProfileDocument` record-tree (copy-and-replace per op), the `validate` pass (service-layer, 422 on failure), and archive integration (Archive op → `PreferenceArchiveUpdateService.archiveItem`; RePromote → `markRePromoted` + re-emergence check against the archive — closes **C-IMP-007**).
- **`TasteProfileBudgetGuard`** — token estimation against the ~2500-token budget ([`design/preference-model.md:69`](../../design/preference-model.md)); throws `TasteProfileBudgetExceededException` (422) when the post-apply document exceeds it.
- **Anomaly detection** — WARN log when a batch removes/archives >3 items ([`design/preference-model.md:418`](../../design/preference-model.md), [`lld/preference.md:728`](../../lld/preference.md)). Folded into this ticket (it is a 5-line guard on the already-counted ops; a separate ticket would be over-fragmented).
- **`TasteProfileServiceImpl.applyDeltas` body** — full Flow 3: load profile, validate, apply, budget-check, write document + bump `documentVersion`/`feedbackCursor`/`basedOnFeedbackCount`/`lastDeltaAppliedAt`/`lastTokenEstimate`, write version snapshot (real `deltasApplied` JSON, `trigger`, `modelTierUsed`, feedback range), write audit row (`actor_type=AI`), publish `TasteProfileChangedEvent` AFTER_COMMIT.

Closes: **C-C-034** (AI delta updates — the deterministic application half; AI generation is `01g`), **C-IMP-007** (re-emerging preference detection from archive). Anomaly detection (**C-C-039**) is partially closed here (the >3-removal WARN); the structural-diff alerting surface is `01h` if richer than a log line is wanted — see What's NOT in scope.

**Dependency**: `preference-01c` (merged — entity, DTOs, sealed `TasteProfileDelta`, `ApplyTasteProfileDeltasRequest`, the stub interface, the three tables) and `preference-01e` (merged — `preference_taste_profile_archive` + `PreferenceArchiveUpdateService`). No new tables. `feedback-01g` (merged) is the caller and needs no change.

## Behavioural spec

### `TasteProfileDeltaApplier.apply` — replace the NoopStub

1. Delete `TasteProfileDeltaApplier.NoopStub`; ship a real `@Component` implementing the same interface (the `@Service`/`@Component` lookup is unchanged so `TasteProfileServiceImpl`'s constructor injection at [`TasteProfileServiceImpl.java:86`](../../src/main/java/com/example/mealprep/preference/domain/service/internal/TasteProfileServiceImpl.java) is untouched). Keep the `DEFERRED_MESSAGE` constant only if any op remains genuinely unimplemented; otherwise remove it.
2. The applier is a **pure document transformer plus an archive side-effect**: signature stays `TasteProfileDocument apply(TasteProfileDocument current, ApplyTasteProfileDeltasRequest request)`. It must NOT load/save the `TasteProfile` entity (the service does that) — but it DOES call `PreferenceArchiveUpdateService` for Archive/RePromote ops (inject it into the applier; both run inside the service's single `@Transactional` boundary per [`lld/preference.md:721`](../../lld/preference.md), so the archive write joins the same tx).
3. **Validate pass first** (per [`lld/preference.md:657-662, 723`](../../lld/preference.md)) — whole-batch rejection, no partial application:
   - `fieldPath` resolves to a known dot-path location in `TasteProfileDocument` (maintain a static allow-set of resolvable paths: `flavourPreferences.likes`, `flavourPreferences.dislikes`, `texturePreferences.likes`, `texturePreferences.dislikes`, `ingredientPreferences.favourites`, `ingredientPreferences.disliked`, `ingredientPreferences.trendingPositive`, `ingredientPreferences.trendingNegative`, `cuisinePreferences.favourites`/`enjoys`/`lessPreferred`, `cookingPreferences.preferredMethods`/`dislikedMethods`, `recipesToRepeat`, `recipesToAvoid`, `activeExperiments`, `learnedInsights`, and the `*.notes` paths for UpdateNotes).
   - `Remove`/`Update`/`Archive` target an **existing** item (`itemKey` resolves within `fieldPath`'s collection). Missing → `InvalidTasteProfileDeltaException` (422).
   - `RePromote` requires a matching **unpromoted** archive entry for `(userId, fieldPath, itemKey)`; **if none found, fall back to `Add` with a WARN log** ([`lld/preference.md:660, 723`](../../lld/preference.md)) — do NOT reject the batch.
   - `PromoteExperiment.hypothesis` matches an active experiment (`activeExperiments[].hypothesis`, `status=TESTING`). No match → 422.
   - `DiscardExperiment.hypothesis` matches an active experiment. No match → 422.
   - Total delta count ≤ 50 (Jakarta `@Size(max=50)` already enforces at the DTO; re-assert defensively).
4. **Apply pass** mutates in op order, each op producing a new immutable `TasteProfileDocument` (copy-and-replace at the targeted path — the document records are immutable per [`lld/preference.md:753`](../../lld/preference.md)). Per-op semantics:
   - **Add** — append `item` (parsed from the delta's `JsonNode item` into the target record type, e.g. `IngredientPreference`) to the `fieldPath` collection. Dedup: if an item with the same key already exists, treat as a no-op merge (do not duplicate).
   - **Remove** — drop the item matching `itemKey` from `fieldPath`. (Prefer Archive; Remove is the rare explicit-delete path.)
   - **Update** — apply the JSON `patch` to the existing item's mutable fields (e.g. `notes`, `evidenceCount`, `lastSignal`) without changing its identity key.
   - **UpdateNotes** — replace the free-text `notes` field at `fieldPath` (e.g. `flavourPreferences.notes`). Max 1 per batch ([`lld/prompts/01-taste-profile-delta.md:146`](../../lld/prompts/01-taste-profile-delta.md)) — assert.
   - **PromoteExperiment** — move the matching `ActiveExperiment` to `status=PROMOTED` (`TasteProfileDelta.defaultPromotedStatus()`) and add `promotedItem` to `targetFieldPath`.
   - **DiscardExperiment** — set the matching experiment's `status=DISCARDED` (retain it in the list as history per the experiment lifecycle, OR drop — pick **retain with DISCARDED** for auditability; **worth user review**).
   - **Archive** — remove the item from `fieldPath` in the document AND call `archiveUpdateService.archiveItem(userId, new ArchiveItemRequest(fieldPath, itemKey, itemPayload, evidenceCount, lastSignal, ArchiveReason.valueOf(reason)))`. The item's full JSON is preserved verbatim in the archive ([`PreferenceArchiveEntry` itemPayload](../../src/main/java/com/example/mealprep/preference/domain/entity/PreferenceArchiveEntry.java)).
   - **RePromote** — call `archiveUpdateService.markRePromoted(userId, fieldPath, itemKey)`, read back the archived `itemPayload`, and insert it into the live `fieldPath` collection (restore verbatim). This is the **re-emergence path** (C-IMP-007) — the archived item returns with its preserved evidence rather than as a fresh Add.
5. **Document version stamp**: the applier bumps the returned document's internal `version` field and `lastUpdated` (per the interface javadoc at [`TasteProfileDeltaApplier.java:26-29`](../../src/main/java/com/example/mealprep/preference/domain/service/internal/TasteProfileDeltaApplier.java)), and sets `basedOnFeedbackCount`/`feedbackCursor` from the request's feedback range. The entity-level `documentVersion` is bumped by the service (§9) and must equal the document's internal `version` in lock-step (the same lock-step invariant `applyManualOverride` enforces at [`TasteProfileServiceImpl.java:236-261`](../../src/main/java/com/example/mealprep/preference/domain/service/internal/TasteProfileServiceImpl.java)).

### `TasteProfileBudgetGuard`

6. **`TasteProfileBudgetGuard`** at `preference.domain.service.internal.TasteProfileBudgetGuard`, `@Component`. Method `int estimate(TasteProfileDocument doc)` — deterministic token estimate (serialise the document to JSON via the injected `ObjectMapper`, divide char-count by ~4, or a field-weighted heuristic; the LLD only requires determinism — [`lld/preference.md:770`](../../lld/preference.md) `TasteProfileBudgetGuardTest` asserts deterministic counts on canonical fixtures). Budget constant `MAX_TOKENS = 2500`.
7. Run `estimate` on the **post-apply** document. If `> 2500` → `TasteProfileBudgetExceededException` (422) ([`lld/preference.md:725`](../../lld/preference.md)). The exception class already exists from 01c; this ticket wires the throw. The whole batch is rejected (transaction rolls back) — no partial state. Set `lastTokenEstimate` on the entity to the computed value when the apply succeeds.

### Anomaly detection (folded in)

8. After the apply pass, count `Remove` + `Archive` ops in the batch. If `> 3`, log a structured WARN (`feedbackId`/range, userId, count, op list) per [`design/preference-model.md:418`](../../design/preference-model.md) and [`lld/preference.md:728`](../../lld/preference.md). **Log and allow** — the preference module's role is observability; the feedback module surfaces it to the user. Do NOT throw.

### `TasteProfileServiceImpl.applyDeltas` — fill the body

9. Replace the stub body ([`TasteProfileServiceImpl.java:299-315`](../../src/main/java/com/example/mealprep/preference/domain/service/internal/TasteProfileServiceImpl.java)) with the full Flow-3 sequence. Keep the existing `@Transactional` (REQUIRED — joins the feedback bridge's caller tx per [`lld/preference.md:721`](../../lld/preference.md)).
   - Load `TasteProfile` by userId (404 `TasteProfileNotFoundException` — already wired).
   - `applier.apply(profile.getDocument(), request)` → new document (validate + budget run inside).
   - `profile.setDocument(newDoc)`; `profile.setDocumentVersion(previousVersion + 1)`; set `feedbackCursor` = `request.feedbackRangeEnd()`; bump `basedOnFeedbackCount`; set `lastDeltaAppliedAt = now`; set `lastTokenEstimate`; set `tasteVectorStatus = PENDING` (AI mutation invalidates the embedding, mirroring the manual-override path at [`TasteProfileServiceImpl.java:263`](../../src/main/java/com/example/mealprep/preference/domain/service/internal/TasteProfileServiceImpl.java)). `saveAndFlush`.
   - `writeVersionSnapshot(...)` — but unlike the existing helper which writes an **empty** `deltasApplied` array ([`TasteProfileServiceImpl.java:368`](../../src/main/java/com/example/mealprep/preference/domain/service/internal/TasteProfileServiceImpl.java)), the AI path must serialise the **real** `request.deltas()` to the `deltasApplied` JSONB column (this is what the `ObjectMapper` dependency at [`TasteProfileServiceImpl.java:414`](../../src/main/java/com/example/mealprep/preference/domain/service/internal/TasteProfileServiceImpl.java) was reserved for). Use `request.trigger()`, `request.modelTierUsed()`, `request.feedbackRangeStart()`/`End()`. Overload or extend `writeVersionSnapshot` to accept the deltas array.
   - `writeAudit(profile, userId, ActorType.AI, TasteProfileChangeType.AI_DELTA_APPLIED, previousVersion, newVersion, summary, traceId, now)` — `actor_type=AI`. The `summary` should read e.g. `"applied N deltas from feedback batch <start>..<end>"`. `traceId`: extract from the origin trace (see §11).
   - Publish `TasteProfileChangedEvent(userId, id, newVersion, AI_DELTA_APPLIED, ActorType.AI, traceId, now)` — fired AFTER_COMMIT by the existing publisher flow.

### Origin-tracking / actor attribution

10. The applier and service must record `actor=AI` + the origin trace per [`design/origin-tracking-pattern.md`](../../design/origin-tracking-pattern.md). The bridge passes `feedbackRangeStart`/`feedbackRangeEnd` = `feedback-<feedback_id>` (see [`PreferenceFeedbackBridgeImpl.java:140-144`](../../src/main/java/com/example/mealprep/feedback/bridge/PreferenceFeedbackBridgeImpl.java)); derive the `traceId` for the audit row + event from this trace (parse the UUID out of `feedback-<uuid>`, or store the raw trace string in the audit `summary` if the UUID parse is lossy — **worth implementer review**; the audit `traceId` column is nullable, so a non-parseable trace → null traceId + trace string in summary is acceptable).
11. **GOTCHA (transaction phase)**: `applyDeltas` is invoked from `PreferenceFeedbackBridgeImpl`, which runs under a `@Qualifier(REQUIRES_NEW_TX_TEMPLATE) TransactionTemplate` ([`PreferenceFeedbackBridgeImpl.java:58-61`](../../src/main/java/com/example/mealprep/feedback/bridge/PreferenceFeedbackBridgeImpl.java)) because the bridge fires from an AFTER_COMMIT event listener. Per **decision-log 0010**, plain `@Transactional` does NOT commit in the AFTER_COMMIT phase. `applyDeltas` keeps `@Transactional` REQUIRED so it **joins** the bridge's already-`REQUIRES_NEW` template tx (the LLD's intentional join, [`lld/preference.md:721`](../../lld/preference.md)) — do NOT add `REQUIRES_NEW` to `applyDeltas` itself (that would detach it from the bridge's tx and break atomicity). Verify in the IT that the version snapshot + audit row + archive row all commit together with the bridge's idempotency `DISPATCHED` row.

### Cross-cutting

12. No new exceptions (both `InvalidTasteProfileDeltaException` and `TasteProfileBudgetExceededException` ship from 01c with their `PreferenceExceptionHandler` 422 mappings). **GOTCHA**: confirm these are mapped in the **per-module `PreferenceExceptionHandler`** (`@RestControllerAdvice`), never the global handler — but note `applyDeltas` is in-process (no REST surface), so a thrown 422 propagates to the bridge, which catches it and books FAILED. Verify the bridge's catch covers `InvalidTasteProfileDeltaException`/`TasteProfileBudgetExceededException` — today it catches `UnsupportedOperationException` ([`PreferenceFeedbackBridgeImpl.java:113`](../../src/main/java/com/example/mealprep/feedback/bridge/PreferenceFeedbackBridgeImpl.java)); once the stub is gone, the catch must be widened to the preference domain exceptions (a one-line bridge change OR a `PreferenceException` base catch — **worth implementer review; this is a feedback-side touch the ticket owns**).
13. ArchUnit: `PreferenceArchiveUpdateService` is already public/re-exported; the applier (in `domain.service.internal`) calling it is allowed. No new repos.

### Events

14. **Published**: `TasteProfileChangedEvent` (AI_DELTA_APPLIED, ActorType.AI). Also `PreferenceArchivedEvent`/`PreferenceRePromotedEvent` fire from `PreferenceArchiveUpdateService` (already wired in 01e). **Consumed**: none (the applier is invoked in-process).

## Database

```
(none — no schema changes; uses preference_taste_profile, _versions, _audit, and _archive from 01c/01e)
```

## OpenAPI updates

**No OpenAPI changes.** `applyDeltas` is in-process only ([`lld/preference.md:623`](../../lld/preference.md)); no HTTP surface.

## Edge-case checklist

- [ ] **Add** appends a parsed `IngredientPreference` to `ingredientPreferences.disliked`; round-trips through JSONB; `documentVersion` increments by 1.
- [ ] **Add dedup**: adding an item whose key already exists is a no-op merge, not a duplicate.
- [ ] **Remove** of a non-existent `itemKey` → `InvalidTasteProfileDeltaException` (422), whole batch rejected, prior version preserved (no partial state).
- [ ] **Update** applies a `notes`/`evidenceCount` patch without changing the item key.
- [ ] **UpdateNotes** replaces `flavourPreferences.notes`; a batch with 2 UpdateNotes ops → 422.
- [ ] **Archive** removes the item from the live document AND writes a `preference_taste_profile_archive` row with the verbatim `itemPayload` and the correct `ArchiveReason`.
- [ ] **RePromote happy path** (re-emergence, C-IMP-007): an item previously archived → `markRePromoted` flips `re_promoted_at`, the item is restored verbatim into the live `fieldPath`, NOT added fresh.
- [ ] **RePromote with no matching archive entry** → falls back to `Add` with a WARN log; batch NOT rejected.
- [ ] **PromoteExperiment** matching an active TESTING experiment → experiment flips to PROMOTED and `promotedItem` lands in `targetFieldPath`. No match → 422.
- [ ] **DiscardExperiment** matching an active experiment → status DISCARDED (retained as history). No match → 422.
- [ ] **fieldPath that does not resolve** → 422; no partial application.
- [ ] **Budget exceeded**: a batch that pushes the document > 2500 tokens → `TasteProfileBudgetExceededException` (422); prior version preserved (mirrors `TasteProfileBudgetIT` at [`lld/preference.md:786`](../../lld/preference.md)).
- [ ] **Budget OK**: `lastTokenEstimate` on the entity is set to the computed value on success.
- [ ] **Anomaly**: a batch with 4 Archive ops → applies successfully AND logs a WARN with the count; does NOT throw.
- [ ] **Version snapshot** written with the **real** `deltasApplied` JSON (not empty), correct `trigger`, `modelTierUsed`, and feedback range.
- [ ] **Audit row** written with `actor_type=AI`, `change_type=AI_DELTA_APPLIED`, correct previous/new version.
- [ ] **AFTER_COMMIT atomicity (decision-log 0010)**: invoked via the bridge's `REQUIRES_NEW` template, `applyDeltas` (plain `@Transactional`, joins) commits the document update + version snapshot + audit + archive row together; the bridge's `DISPATCHED` idempotency row commits in the same unit. Verified by an IT that publishes a `FeedbackProcessedEvent` and asserts all rows present after commit.
- [ ] **document.version / entity.documentVersion lock-step**: both equal the same integer after the apply.
- [ ] **`tasteVectorStatus` flipped to PENDING** on AI delta apply (re-embed trigger).
- [ ] **Bridge catch widened**: `InvalidTasteProfileDeltaException` / `TasteProfileBudgetExceededException` thrown by `applyDeltas` are caught by `PreferenceFeedbackBridgeImpl` → books FAILED → no raw exception past the bridge edge.
- [ ] **`TasteProfileNotFoundException`** (user with no profile) still books FAILED via the existing bridge catch ([`PreferenceFeedbackBridgeImpl.java:96`](../../src/main/java/com/example/mealprep/feedback/bridge/PreferenceFeedbackBridgeImpl.java)).
- [ ] **Empty delta list** (`request.deltas()` empty) → no-op apply, no version bump, no audit row (or a no-change audit — **pick no-op**; matches the prompt's "empty deltas warranted" path at [`lld/prompts/01-taste-profile-delta.md:325`](../../lld/prompts/01-taste-profile-delta.md)).
- [ ] **Op-order sensitivity**: Add-then-Remove of the same item in one batch nets to absent; Remove-then-Add nets to present ([`lld/preference.md:769`](../../lld/preference.md)).
- [ ] `TasteProfileDeltaApplierTest` (unit) covers each op + each validation failure.
- [ ] `TasteProfileBudgetGuardTest` (unit) — deterministic counts on small/medium/near-budget fixtures.

## Files this ticket touches

```
MOD   src/main/java/com/example/mealprep/preference/domain/service/internal/TasteProfileDeltaApplier.java       (NoopStub → real impl; inject ObjectMapper + PreferenceArchiveUpdateService)
NEW   src/main/java/com/example/mealprep/preference/domain/service/internal/TasteProfileBudgetGuard.java
MOD   src/main/java/com/example/mealprep/preference/domain/service/internal/TasteProfileServiceImpl.java          (applyDeltas body + writeVersionSnapshot deltas overload)

MOD   src/main/java/com/example/mealprep/feedback/bridge/PreferenceFeedbackBridgeImpl.java                       (widen catch to preference domain exceptions; remove the stub-only UnsupportedOperationException assumption)

NEW   src/test/java/com/example/mealprep/preference/TasteProfileDeltaApplierTest.java                            (unit — 8 ops + validation)
NEW   src/test/java/com/example/mealprep/preference/TasteProfileBudgetGuardTest.java                             (unit)
NEW   src/test/java/com/example/mealprep/preference/TasteProfileDeltaApplyIT.java                                (Testcontainers — service-layer end-to-end, version + audit + archive rows)
NEW   src/test/java/com/example/mealprep/feedback/PreferenceFeedbackBridgeAppliesIT.java                         (Testcontainers — FeedbackProcessedEvent → bridge → applyDeltas → DISPATCHED + document mutated, AFTER_COMMIT atomicity)
MOD   src/test/java/com/example/mealprep/preference/TasteProfileServiceImplTest.java                             (replace stub-throws assertions with apply assertions)
```

Total: ~3 new + 4 mods. Estimated agent runtime 5-7 hours (dominated by the 8 op handlers + the path-resolution allow-set + the AFTER_COMMIT-atomicity IT).

## Dependencies

- **Hard dependency**: `preference-01c` (merged) — entity, document tree, sealed `TasteProfileDelta`, DTOs, stub interface, three tables, exceptions.
- **Hard dependency**: `preference-01e` (merged) — `preference_taste_profile_archive`, `PreferenceArchiveUpdateService.archiveItem`/`markRePromoted`.
- **Hard dependency**: `feedback-01g` (merged) — `PreferenceFeedbackBridgeImpl` is the caller (this ticket modifies its catch block).
- **Soft / informs**: `preference-01g` (AI delta-generation) produces the deltas this applier consumes — but this ticket needs nothing from it (the bridge already feeds the classifier payload).

## Acceptance / DoD

- [ ] `./mvnw -Dspotless.check.skip=true verify` passes
- [ ] `./mvnw spotless:apply` then `./mvnw spotless:check` clean
- [ ] **Run the full preference + feedback module IT suites locally with Docker** + `redocly lint` + spotless before pushing (ends the blind-CI cycle). Docker IS available locally. Run ITs in ~3-class batches to avoid the Hikari pool-exhaustion flake on big sweeps.
- [ ] CI green (build + spotless + OpenAPI lint + ArchUnit + contract tests)
- [ ] All edge-case items above ticked
- [ ] PR description traces: `FeedbackProcessedEvent(PREFERENCE)` → bridge → `applyDeltas` → document mutated + version 14→15 + audit row `AI`/`AI_DELTA_APPLIED` + (if Archive op) archive row.

## What's NOT in scope

- **AI delta generation** (the `PreferenceTasteProfileDeltaTask` AiTask + prompt wiring + trigger) — `preference-01g`.
- **Version rollback + feedback-cursor replay** (C-C-040) — `preference-01h`.
- **Richer anomaly alerting** (a persisted alert / user-facing surface beyond the WARN log) — out of scope; the HLD only requires the alert log.
- **pgvector embedding** recompute on `tasteVectorStatus=PENDING` — the deferred vector ticket owns the listener.

Squash-merge with: `feat(preference): 01f — taste-profile delta applier + budget guard + archive integration (closes C-C-034 apply half, C-IMP-007)`
