# Ticket: preference — 01h Taste-Profile Version Rollback + Feedback-Cursor Replay

## Summary

Add the **rollback** path that reverts a taste profile to a prior `document_version` and replays feedback from the rolled-back version's `feedback_cursor` forward. Per [`design/preference-model.md` lines 419-421](../../design/preference-model.md) ("Rolling back reverts to a previous version and replays feedback from the rolled-back version's `feedback_cursor` forward — the `feedback_cursor` makes this deterministic") and [`lld/preference.md` lines 570-572, 613, 780](../../lld/preference.md). 01c shipped the `preference_taste_profile_versions` snapshot table + repo but explicitly deferred the rollback endpoint ([`tickets/preference/01c-taste-profile-entity.md:18, 289`](01c-taste-profile-entity.md): "ship the entity + repo for `preference_taste_profile_versions` here, but **defer the rollback endpoint** to a follow-up").

Closes: **C-C-040** (Rollback of taste profile version — revert + replay feedback from the rolled-back version's `feedback_cursor` forward; [`design/audits/2026-05-21-capability-inventory.md:735-736`](../../design/audits/2026-05-21-capability-inventory.md)).

Ships:
- **`rollbackTasteProfile(userId, targetDocumentVersion, actorUserId)`** on `TasteProfileUpdateService` + impl — restores the target version's `document_snapshot` as a **new** monotonic version (no version decrement — [`TasteProfileServiceImpl.java:54-57`](../../src/main/java/com/example/mealprep/preference/domain/service/internal/TasteProfileServiceImpl.java) javadoc: "rollback creates a new version with `change_type = ROLLED_BACK`, not a version decrement"), writes a `ROLLED_BACK` audit row + a version snapshot, publishes `TasteProfileChangedEvent`.
- **`POST /api/v1/preferences/taste-profile/rollback`** endpoint ([`lld/preference.md:613`](../../lld/preference.md): `{ targetDocumentVersion }` → `TasteProfileDto`, 200/404/409).
- **Feedback-cursor replay delegation**: the preference module restores the document + sets `feedbackCursor` back to the target version's cursor, then delegates the actual replay (re-processing feedback from that cursor forward) to the feedback module via a `FeedbackReplayService` ([`lld/preference.md:571`](../../lld/preference.md): "Replaying feedback from that cursor forward is the feedback module's responsibility (delegated via FeedbackReplayService)").

**Dependency**: `preference-01c` (versions table + repo, `TasteProfileVersionRepository.findByTasteProfileIdAndDocumentVersion` at [`TasteProfileServiceImpl.java:136`](../../src/main/java/com/example/mealprep/preference/domain/service/internal/TasteProfileServiceImpl.java)). Soft coupling to `preference-01f`/`01g` (replay re-runs the AI delta pipeline) — but rollback can ship and function (revert-only) before replay is fully wired; see §6.

## Behavioural spec

### Service method

1. Add to `TasteProfileUpdateService`:
   ```java
   // Reverts to a prior document_version. Restores the snapshot as a NEW version (monotonic;
   // change_type=ROLLED_BACK). Resets feedbackCursor to the target version's cursor and delegates
   // forward-replay to the feedback module.
   TasteProfileDto rollbackTasteProfile(UUID userId, int targetDocumentVersion, UUID actorUserId);
   ```
   Matches the LLD signature at [`lld/preference.md:572`](../../lld/preference.md).
2. Impl in `TasteProfileServiceImpl`, `@Transactional` (REQUIRED). Sequence:
   - Load `TasteProfile` by userId (404 `TasteProfileNotFoundException`).
   - Load the target `TasteProfileVersion` via `versionRepository.findByTasteProfileIdAndDocumentVersion(profile.getId(), targetDocumentVersion)` — 404 (a dedicated `TasteProfileVersionNotFoundException` or reuse `TasteProfileNotFoundException` with a version-specific message; **worth implementer review** — prefer a distinct 404 exception for clarity).
   - **Optimistic-lock guard**: the request carries an `expectedVersion` (the entity's current `optimisticVersion`) — mismatch → `ObjectOptimisticLockingFailureException` → 409, mirroring `applyManualOverride` ([`TasteProfileServiceImpl.java:226-228`](../../src/main/java/com/example/mealprep/preference/domain/service/internal/TasteProfileServiceImpl.java)). The rollback-into-a-concurrently-edited-profile case is exactly why the LLD lists 409 on this endpoint.
   - `previousVersion = profile.getDocumentVersion()`; `newVersion = previousVersion + 1` (monotonic — NEVER restore the old integer).
   - Restore document: take `targetVersion.getDocumentSnapshot()`, re-stamp its internal `version = newVersion` and `lastUpdated = today` (lock-step invariant — same as [`TasteProfileServiceImpl.java:241-258`](../../src/main/java/com/example/mealprep/preference/domain/service/internal/TasteProfileServiceImpl.java)).
   - Reset `feedbackCursor` to `targetVersion.getFeedbackRangeStart()` (the cursor as-of that version — this is the deterministic replay anchor per [`design/preference-model.md:420`](../../design/preference-model.md)). Also reset `basedOnFeedbackCount` to the target's value if recoverable from the snapshot's internal `basedOnFeedbackCount` field.
   - Flip `tasteVectorStatus = PENDING` (the restored document needs re-embedding).
   - `saveAndFlush`.
   - `writeVersionSnapshot(saved, restoredDoc, TasteProfileTrigger.MANUAL, targetCursor, currentCursor, "rollback", now)` — the snapshot records the rollback as a new version (its `deltasApplied` can be an empty array or a synthetic `{"op":"ROLLBACK","toVersion":N}` marker — **pick the marker** for forensic clarity).
   - `writeAudit(saved, actorUserId, ActorType.USER, TasteProfileChangeType.ROLLED_BACK, previousVersion, newVersion, "rolled back to version " + targetDocumentVersion, traceId, now)` — `ROLLED_BACK` is already an enum value ([`tickets/preference/01c-taste-profile-entity.md:123, 174`](01c-taste-profile-entity.md)).
   - Publish `TasteProfileChangedEvent(userId, id, newVersion, ROLLED_BACK, ActorType.USER, traceId, now)` AFTER_COMMIT.
   - **Trigger replay** (§6).

### REST endpoint

3. Add `POST /api/v1/preferences/taste-profile/rollback` to `TasteProfileController`. Request body `RollbackTasteProfileRequest(@Min(1) int targetDocumentVersion, @Min(0) long expectedVersion)`. Response `TasteProfileDto`, 200/404/409. `security: [cookieAuth: []]` (every preference endpoint requires session-cookie auth; `userId` from `CurrentUserResolver`, never a query param).
4. `actorUserId` = the resolved current user (self-rollback today; household-admin later).

### Feedback-cursor replay delegation

5. The preference module **does not replay feedback itself** (it does not consume `FeedbackProcessedEvent` — locked decision [`lld/preference.md:692`](../../lld/preference.md)). It delegates: after the document is restored + cursor reset, publish a **replay request** the feedback module consumes. Two implementation options — **worth user review**:
   - **(A) Event**: publish a new `TasteProfileRollbackReplayRequestedEvent(userId, fromFeedbackCursor, toCursorBefore, traceId, occurredAt)` AFTER_COMMIT; the feedback module adds a listener that re-runs the delta pipeline (`preference-01g` orchestrator) over feedback in `[fromCursor, now]`. Loose coupling, matches the module-boundary convention.
   - **(B) Direct service call**: inject a `FeedbackReplayService` interface (named in [`lld/preference.md:571`](../../lld/preference.md)) and call `replayFrom(userId, feedbackCursor)` in-process. Tighter coupling but the LLD names this interface explicitly.
   - **Recommendation: (A) event.** The preference module already publishes `TasteProfileRefreshRequestedEvent` to the feedback module (the same direction, same loose-coupling pattern); reusing that idiom keeps the dependency graph acyclic (preference → publishes → feedback consumes), whereas (B) would have preference depend on a feedback interface. The `FeedbackReplayService` name in the LLD can be honoured as the feedback-side listener's collaborator.
6. **Replay can be deferred-but-wired**: if `preference-01g` (the AI pipeline that replay re-runs) is not yet merged when this ticket lands, ship the event/delegation **wired** and let the feedback-side listener be a no-op-with-log (the document revert + cursor reset is the user-visible behaviour; replay re-derivation is a follow-up enhancement). **Flag this clearly in the PR** — revert works standalone; full replay needs 01g. The capability C-C-040 is "revert + replay"; mark replay as wired-pending-01g if 01g isn't merged.

### Cross-cutting

7. **GOTCHA (AFTER_COMMIT, decision-log 0010)**: rollback is invoked from a REST controller (a normal request thread, NOT an AFTER_COMMIT listener), so plain `@Transactional` REQUIRED commits correctly — no `REQUIRES_NEW` needed on `rollbackTasteProfile` itself. BUT the **feedback-side replay listener** (option A) fires AFTER_COMMIT of the rollback tx, so ITS write path (re-running the delta pipeline) must use `@Transactional(propagation = REQUIRES_NEW)` — flag this in the feedback-side listener (mirrors `preference-01g` §7 and the bridge pattern at [`PreferenceFeedbackBridgeImpl.java:58`](../../src/main/java/com/example/mealprep/feedback/bridge/PreferenceFeedbackBridgeImpl.java)).
8. **GOTCHA (JPQL nullable params)**: if a new repository query filters version history by an optional `trigger` or `generatedAt` (e.g. "list rollback-eligible versions"), any `(:p is null or col = :p)` predicate over a nullable enum/`Instant` triggers Postgres `42P18`. Use `cast(:p as string)` / `cast(:p as timestamp)`. (The existing `findByTasteProfileIdAndDocumentVersion` is a simple equality — no cast needed; only relevant if this ticket adds a filtered listing.)
9. New exception (if chosen) `TasteProfileVersionNotFoundException` (404) — mapped in the **per-module `PreferenceExceptionHandler`** (`@RestControllerAdvice`), never the global handler. The 409 from `ObjectOptimisticLockingFailureException` is already mapped.
10. ArchUnit: no new repos beyond the existing `TasteProfileVersionRepository` (which already lives in `preference.domain.repository`).

### Events

11. **Published**: `TasteProfileChangedEvent(ROLLED_BACK)`; `TasteProfileRollbackReplayRequestedEvent` (new, option A). **Consumed**: none in the preference module.

## Database

```
(none — uses preference_taste_profile + preference_taste_profile_versions + _audit from 01c)
```

No schema change: rollback reads an existing snapshot and writes a new version row via the existing tables.

## OpenAPI updates

Add 1 path + 1 schema to `src/main/resources/openapi/paths/preference.yaml` and `schemas/preference.yaml`:

**Path**: `POST /api/v1/preferences/taste-profile/rollback` — `requestBody` `RollbackTasteProfileRequest`, `200` `TasteProfileDto`, `404`, `409`. **GOTCHA**: explicit `security: [cookieAuth: []]` (new operations need explicit security).

**Schema**: `RollbackTasteProfileRequest` — `{ targetDocumentVersion: integer (min 1), expectedVersion: integer (min 0) }`. `additionalProperties: false`. **GOTCHA (Redocly nullable-type-sibling)**: no nullable props here, but if any field is later marked `nullable: true` it needs a `type` sibling. The `TasteProfileDto` response schema (reused from 01c) is a `Page`-free single object — no `*DtoPage` envelope needed for this endpoint.

## Edge-case checklist

- [ ] **Rollback happy path**: profile at v15 → rollback to v12 → new version is **v16** (monotonic, NOT 12), document equals v12's snapshot, `feedbackCursor` reset to v12's `feedbackRangeStart`.
- [ ] **Audit row** written with `change_type=ROLLED_BACK`, `actor_type=USER`, previous=15, new=16, summary names the target version.
- [ ] **Version snapshot** written for v16 (the rollback IS a new version, listable in the version history).
- [ ] **Monotonicity invariant**: `document_version` never decreases; the versions table never has a gap-then-reuse.
- [ ] **document.version / entity.documentVersion lock-step**: the restored document's internal `version` field equals the new entity `documentVersion` (16), not the target (12).
- [ ] **404 on missing profile**: rollback for a user with no profile → `TasteProfileNotFoundException`.
- [ ] **404 on missing target version**: rollback to v99 (doesn't exist) → 404.
- [ ] **409 on stale expectedVersion**: rollback with `expectedVersion` not matching the current `optimisticVersion` → `ObjectOptimisticLockingFailureException` → 409 ProblemDetail.
- [ ] **Rollback to the current version** (v15 → v15) → either a no-op-200 or a fresh-snapshot-200 (**pick fresh snapshot** for audit consistency — every rollback writes a version row).
- [ ] **Cross-tenant**: user A cannot rollback user B's profile (CurrentUserResolver scopes the read).
- [ ] **`tasteVectorStatus` flipped to PENDING** (restored document needs re-embed).
- [ ] **Replay delegation fires**: `TasteProfileRollbackReplayRequestedEvent` published AFTER_COMMIT with the from-cursor.
- [ ] **Replay listener REQUIRES_NEW** (decision-log 0010): the feedback-side listener's re-derivation write commits despite firing AFTER_COMMIT (IT verifies, when 01g present).
- [ ] **Replay-pending mode**: if 01g not merged, the listener no-ops-with-log; the document revert is still fully applied + persisted (revert works standalone).
- [ ] **TasteProfileChangedEvent(ROLLED_BACK)** fires exactly once AFTER_COMMIT.
- [ ] **OpenAPI contract test**: the rollback request/response shapes validate; redocly lint clean (security present, no nullable-without-type).
- [ ] **`TasteProfileControllerIT`** (extends the 01c IT per [`lld/preference.md:780`](../../lld/preference.md)): rollback success + 409-on-stale.

## Files this ticket touches

```
MOD   src/main/java/com/example/mealprep/preference/domain/service/TasteProfileUpdateService.java                (add rollbackTasteProfile)
MOD   src/main/java/com/example/mealprep/preference/domain/service/internal/TasteProfileServiceImpl.java          (rollback impl + writeVersionSnapshot reuse)
MOD   src/main/java/com/example/mealprep/preference/api/controller/TasteProfileController.java                    (POST /rollback)
NEW   src/main/java/com/example/mealprep/preference/api/dto/RollbackTasteProfileRequest.java
NEW   src/main/java/com/example/mealprep/preference/event/TasteProfileRollbackReplayRequestedEvent.java           (option A)
NEW   src/main/java/com/example/mealprep/preference/exception/TasteProfileVersionNotFoundException.java           (if chosen)
MOD   src/main/java/com/example/mealprep/preference/exception/PreferenceExceptionHandler.java                     (map the new 404)

NEW   src/main/java/com/example/mealprep/feedback/.../TasteProfileRollbackReplayListener.java                     (feedback module — REQUIRES_NEW; no-op-with-log if 01g absent)

MOD   src/main/resources/openapi/paths/preference.yaml                                                            (1 path)
MOD   src/main/resources/openapi/schemas/preference.yaml                                                          (1 schema)

NEW   src/test/java/com/example/mealprep/preference/TasteProfileRollbackTest.java                                 (unit — service, monotonicity, 409)
MOD   src/test/java/com/example/mealprep/preference/TasteProfileControllerIT.java                                 (rollback success + 409)
NEW   src/test/java/com/example/mealprep/preference/TasteProfileRollbackReplayIT.java                             (Testcontainers — event published, listener REQUIRES_NEW)
```

Total: ~5 new + 5 mods. Estimated agent runtime 3-4 hours (smaller than 01f/01g — reuses existing snapshot infra; the replay delegation is the only novel seam).

## Dependencies

- **Hard dependency**: `preference-01c` (merged) — `preference_taste_profile_versions`, `TasteProfileVersionRepository`, `ROLLED_BACK` enum value, `writeVersionSnapshot`/`writeAudit` helpers.
- **Soft dependency**: `preference-01g` (the AI pipeline replay re-runs) — rollback's revert half works without it; the replay-listener no-ops-with-log until 01g lands. Mark in PR.
- **No dependency** on `preference-01f` for the revert path (snapshot restore is pure entity work).

## Acceptance / DoD

- [ ] `./mvnw -Dspotless.check.skip=true verify` passes
- [ ] `./mvnw spotless:apply` then `./mvnw spotless:check` clean
- [ ] **Run the full preference module IT suite locally with Docker** + `redocly lint` + spotless before pushing. Docker IS available locally. Run ITs in ~3-class batches (Hikari pool-exhaustion flake on big sweeps).
- [ ] CI green (build + spotless + OpenAPI lint + ArchUnit + contract tests)
- [ ] All edge-case items above ticked
- [ ] PR description traces: v15 → rollback to v12 → v16 with v12's document; audit shows `ROLLED_BACK`; replay event published (or no-op-logged if 01g absent).

## What's NOT in scope

- **The AI delta pipeline** that replay re-runs — `preference-01g`.
- **A "diff between two versions" view** — version listing already exists (01c); a structural-diff endpoint is a separate UX ticket.
- **Automatic rollback** on anomaly detection — the anomaly WARN (01f) is observational; auto-rollback is explicitly not designed.
- **Bulk/partial rollback** of individual deltas — only whole-document version rollback is in C-C-040.

Squash-merge with: `feat(preference): 01h — taste-profile version rollback + feedback-cursor replay (closes C-C-040)`
