# Ticket: preference — 01g AI Delta-Generation Pipeline (PreferenceTasteProfileDeltaTask + triggers)

## Summary

Build the **AI delta-generation pipeline** that reads a user's recent feedback + current taste profile + recent archive and proposes a batch of `TasteProfileDelta` operations, then feeds them into the deterministic applier (`preference-01f`). This is the **only AI path that can write to the taste profile** ([`lld/prompts/01-taste-profile-delta.md:24, 119`](../../lld/prompts/01-taste-profile-delta.md)). The prompt is fully specified at [`lld/prompts/01-taste-profile-delta.md`](../../lld/prompts/01-taste-profile-delta.md); this ticket implements its `AiTask`, the trigger wiring, and the orchestration that turns the AI response into an `ApplyTasteProfileDeltasRequest`.

Per [`design/preference-model.md` lines 67-71, 336-396](../../design/preference-model.md) and [`lld/preference.md` lines 717-729, 795](../../lld/preference.md) (which scopes the prompt itself OUT of the preference LLD and INTO the feedback module's `TasteProfileDeltaTask`). The task lives in the **feedback module** ([`lld/prompts/01-taste-profile-delta.md:13-15`](../../lld/prompts/01-taste-profile-delta.md): `Module: feedback (calls into preference via applyTasteProfileDeltas)`).

Ships:
- **`PreferenceTasteProfileDeltaTask`** — `AiTask<TasteProfileDeltaResponse>`, `TaskType.PREFERENCE_DELTA_UPDATE`, mid-tier (Sonnet) per the skeleton at [`lld/prompts/01-taste-profile-delta.md:358-386`](../../lld/prompts/01-taste-profile-delta.md). System prompt + user-prompt-ref + tool schema + context (`current_taste_profile`, `feedback_batch`, `recent_archive_ids`).
- **The orchestrator** that: gathers the feedback batch + current profile (via `PreferenceQueryService.getTasteProfile` + `getFullPreferenceArchive` for `recent_archive_ids` — [`lld/preference.md:535`](../../lld/preference.md)), runs the task via the mid-tier `AiService`, maps the AI `TasteProfileDeltaResponse` (whose delta shapes differ slightly from the wire DTO — see §4) into the canonical `ApplyTasteProfileDeltasRequest`, and calls `TasteProfileUpdateService.applyDeltas`.
- **The trigger**: every-5-feedbacks (batch), weekly, and manual via the existing `triggerRefresh` path. `triggerRefresh` ([`TasteProfileServiceImpl.java:317-356`](../../src/main/java/com/example/mealprep/preference/domain/service/internal/TasteProfileServiceImpl.java)) already publishes `TasteProfileRefreshRequestedEvent` AFTER_COMMIT with NO listener today — this ticket adds the listener.

Closes: **C-C-034** (AI delta updates — the generation half; the deterministic-apply half is `preference-01f`). Folds in the prompt's eval-set acceptance gate (15/18, [`lld/prompts/01-taste-profile-delta.md:329`](../../lld/prompts/01-taste-profile-delta.md)).

**Hard dependencies**: `preference-01f` (the real applier — this pipeline produces deltas it consumes), the mid-tier `AiService` infrastructure + `AiTask`/`TaskType`/`PromptRef`/`ToolDefinitionBuilder` (assumed merged — verify at agent start; if `TaskType.PREFERENCE_DELTA_UPDATE` doesn't exist, add the enum value), and the feedback module's classification→routing (`feedback-01d`, merged) which decides feedback is `Destination.PREFERENCE`.

## Behavioural spec

### `PreferenceTasteProfileDeltaTask` (the AiTask)

1. Implement exactly the skeleton at [`lld/prompts/01-taste-profile-delta.md:358-386`](../../lld/prompts/01-taste-profile-delta.md) in `feedback.ai.task.PreferenceTasteProfileDeltaTask` (or the module's existing `AiTask` package — verify at agent start). `getTaskType()` → `PREFERENCE_DELTA_UPDATE`; `getSystemPrompt()` → the verbatim system prompt at [`lld/prompts/01-taste-profile-delta.md:100-147`](../../lld/prompts/01-taste-profile-delta.md); `getUserPromptRef()` → `PromptRef("preference/taste-profile-delta-user", ...)`; tool schema from `TasteProfileDeltaResponse`; timeout override 15s.
2. Context map carries `current_taste_profile` (the `TasteProfileDocument`), `feedback_batch` (`List<ClassifiedFeedbackEvent>`, the record at [`lld/prompts/01-taste-profile-delta.md:43-49`](../../lld/prompts/01-taste-profile-delta.md)), `recent_archive_ids` (last 30 archived item keys — for re-emergence detection), `user_id`.
3. **`TasteProfileDeltaResponse`** record + the **prompt-side** sealed `TasteProfileDelta` (8 permits, but with the prompt's field set: `evidenceFeedbackId`, `reasoning`, `Confidence` on Add — [`lld/prompts/01-taste-profile-delta.md:57-93`](../../lld/prompts/01-taste-profile-delta.md)). **NOTE — there are TWO `TasteProfileDelta` shapes**: the prompt/AI-response shape (this ticket) and the canonical wire/applier shape (`preference.api.dto.TasteProfileDelta`, from 01c). Keep the AI-response type in the feedback module (e.g. `feedback.ai.dto.AiTasteProfileDelta`) to avoid colliding with the preference DTO; §4 maps between them. **Worth implementer review** — do not reuse `preference.api.dto.TasteProfileDelta` as the tool schema; its field set (`JsonNode item`, `itemKey`, no `confidence`/`reasoning`) is the apply shape, not the generate shape.

### Orchestration (AI response → applyDeltas)

4. **`TasteProfileDeltaOrchestrator`** (`@Component`, feedback module). On a trigger (§6):
   - Load current profile via `PreferenceQueryService.getTasteProfile(userId)`; if absent, skip with a structured log (lazily-initialised profile may not exist — mirrors the bridge's missing-profile handling at [`PreferenceFeedbackBridgeImpl.java:96`](../../src/main/java/com/example/mealprep/feedback/bridge/PreferenceFeedbackBridgeImpl.java)).
   - Load `recent_archive_ids` via `PreferenceQueryService.getFullPreferenceArchive(userId)` (last 30 unpromoted keys).
   - Gather the feedback batch (the 1-10 PREFERENCE-routed `ClassifiedFeedbackEvent`s since the last cursor — the feedback module owns this query).
   - Run the task via the mid-tier `AiService`. On `AiUnavailable` → feedback entries sit `pending_delta`, retried when the AI cap resets ([`lld/prompts/01-taste-profile-delta.md:16`](../../lld/prompts/01-taste-profile-delta.md)). Do NOT throw past the orchestrator edge.
   - **Map** `TasteProfileDeltaResponse.deltas` (AI shape) → `List<preference.api.dto.TasteProfileDelta>` (apply shape): e.g. AI `Add(fieldPath, item, notes, evidenceFeedbackId, reasoning, confidence)` → apply `Add(fieldPath, item: JsonNode-of{item, notes, evidenceCount:1, lastSignal:today, source:FEEDBACK})`. The `reasoning`/`evidenceFeedbackId`/`confidence` are dropped from the apply payload but recorded in `overallReasoning` → version snapshot context. **Worth implementer review** — this mapping is the load-bearing seam; keep it in one well-tested function.
   - Build `ApplyTasteProfileDeltasRequest(deltas, trigger, feedbackRangeStart=feedback-<firstId>, feedbackRangeEnd=feedback-<lastId>, modelTierUsed="mid")`.
   - Call `tasteProfileUpdateService.applyDeltas(userId, request)` — the 01f applier validates + applies.
5. **Malformed-delta / batch-rejection handling**: if the applier throws `InvalidTasteProfileDeltaException`, retry once with a corrective re-prompt ([`lld/prompts/01-taste-profile-delta.md:349`](../../lld/prompts/01-taste-profile-delta.md)); if still bad, surface as `delta_invalid` for the feedback module to log + skip (no partial application — whole batch rejected per [`lld/preference.md:723`](../../lld/preference.md)). If `TasteProfileBudgetExceededException`, retry once with a corrective prompt instructing the model to propose Archive ops first ([`lld/preference.md:725`](../../lld/preference.md)).

### Triggers

6. Three trigger modes per [`design/preference-model.md:67`](../../design/preference-model.md) ("every 5 feedbacks / weekly / manual"):
   - **BATCH (every 5 feedbacks)**: the feedback module increments a per-user PREFERENCE-routed counter; on the 5th, schedule a delta-update run (trigger=`BATCH`). The counter lives in the feedback module (the preference module does NOT consume `FeedbackProcessedEvent` — [`lld/preference.md:692`, locked decision](../../lld/preference.md)). **Worth implementer review** — verify the feedback module already tracks a per-destination processed count; if so reuse it; if not, add a small `preference_delta_trigger_cursor` table (see Database) keyed by userId tracking `(last_processed_feedback_id, pending_count)`.
   - **WEEKLY**: `@Scheduled(cron = "${mealprep.feedback.preference-delta.weekly-cron:0 0 3 * * SUN}")` sweeps users with `pending_count > 0` since their last run (trigger=`WEEKLY`). `@ConditionalOnProperty` to disable in tests.
   - **MANUAL**: add a `@TransactionalEventListener(phase = AFTER_COMMIT)` on `TasteProfileRefreshRequestedEvent` (published by `triggerRefresh` at [`TasteProfileServiceImpl.java:338`](../../src/main/java/com/example/mealprep/preference/domain/service/internal/TasteProfileServiceImpl.java) — **currently has no listener**, [`tickets/preference/01c-taste-profile-entity.md:297`](01c-taste-profile-entity.md) notes "the event is published but has no listener — that's fine"). This ticket adds the listener (trigger=`MANUAL`); the event carries `feedbackRangeStart`/`feedbackRangeEnd` for an explicit range override.
7. **GOTCHA (AFTER_COMMIT, decision-log 0010)**: the MANUAL trigger's listener fires AFTER_COMMIT of `triggerRefresh` and then calls `applyDeltas` (which writes). The listener MUST run its write under `@Transactional(propagation = REQUIRES_NEW)` — plain `@Transactional` does NOT commit in the AFTER_COMMIT phase. Mirror the `FeedbackTxTemplateConfig.REQUIRES_NEW_TX_TEMPLATE` pattern the bridges use ([`PreferenceFeedbackBridgeImpl.java:58`](../../src/main/java/com/example/mealprep/feedback/bridge/PreferenceFeedbackBridgeImpl.java)). The WEEKLY/BATCH triggers run from a scheduler/scheduled context (not AFTER_COMMIT) so a normal `@Transactional` is fine there.

### Cross-cutting

8. **Trigger enum reuse**: `TasteProfileTrigger` (`BATCH`/`WEEKLY`/`MANUAL`) already exists ([`lld/preference.md:264`](../../lld/preference.md)) — the orchestrator sets it on the request; the applier stamps it on the version snapshot.
9. **Config**: `@ConfigurationProperties(prefix="mealprep.feedback.preference-delta")` record holding `weeklyCron`, `batchThreshold` (default 5), `correctiveRetryEnabled`. **GOTCHA**: a `@ConfigurationProperties` record needs `@EnableConfigurationProperties` registered in any `@WebMvcTest` slice that touches it (none here, but the `@SpringBootTest` IT needs the bean present).
10. **Origin attribution**: the orchestrator/listener passes the origin trace (`feedback-<batch-cursor>` or the manual `traceId`) so the 01f applier writes `actor_type=AI` + the trace per [`design/origin-tracking-pattern.md`](../../design/origin-tracking-pattern.md). For MANUAL, the trigger was user-initiated but the *application* is AI — record `actor=AI` on the delta apply (the user only requested the refresh; the audit row from `triggerRefresh` already recorded `REFRESH_TRIGGERED`/`actor=USER`).

### Events

11. **Published**: none new (the apply path's `TasteProfileChangedEvent` is published by 01f's service). **Consumed**: `TasteProfileRefreshRequestedEvent` (manual trigger). The feedback module's own `FeedbackProcessedEvent` may drive the BATCH counter — reuse the existing feedback-side listener rather than adding a new one if present.

## Database

```
NEW   src/main/resources/db/migration/V20260615240000__feedback_create_preference_delta_trigger_cursor.sql   (ONLY IF the feedback module does not already track per-user PREFERENCE processed counts — verify at agent start)
```

If needed:
```sql
CREATE TABLE feedback_preference_delta_cursor (
    id                        uuid PRIMARY KEY,
    user_id                   uuid NOT NULL UNIQUE,
    last_processed_feedback_id uuid,
    pending_count             integer NOT NULL DEFAULT 0,
    last_run_at               timestamptz,
    last_run_trigger          varchar(16),
    optimistic_version        bigint NOT NULL DEFAULT 0,
    created_at                timestamptz NOT NULL,
    updated_at                timestamptz NOT NULL
);
CREATE UNIQUE INDEX idx_feedback_pref_delta_cursor_user ON feedback_preference_delta_cursor (user_id);
```
(Timestamp `V20260615240000` continues the scheme; latest in-tree is `V20260615230000`. Pick the next free slot if 01f/01i/01m land first — coordinate at merge.)

## OpenAPI updates

**No OpenAPI changes.** The pipeline is internal (AiTask + scheduler + event listener). The MANUAL trigger's HTTP surface (`POST /refresh-now`) already ships from 01c.

## Edge-case checklist

- [ ] **Migration applies cleanly** (if added); `FlywayMigrationIT` passes; `ddl-auto=validate` accepts the cursor entity.
- [ ] **AiTask schema**: `TasteProfileDeltaResponse` tool schema generates; the 8 AI-shape delta permits serialise/deserialise round-trip.
- [ ] **Two-delta-shape isolation**: the AI-response `TasteProfileDelta` and the preference wire `TasteProfileDelta` do not collide (different packages); the mapper converts AI→wire correctly for all 8 ops.
- [ ] **Mapper**: AI `Add` → wire `Add` with a synthesised `IngredientPreference` JSON (`evidenceCount:1`, `source:FEEDBACK`, `lastSignal:today`); AI `RePromote(archivedItemKey)` → wire `RePromote(fieldPath, itemKey)`.
- [ ] **BATCH trigger fires on the 5th** PREFERENCE-routed feedback (counter increments; resets after run).
- [ ] **WEEKLY trigger** sweeps users with pending feedback (mocked Clock); `@ConditionalOnProperty` lets the test profile disable it.
- [ ] **MANUAL trigger**: `POST /refresh-now` → `TasteProfileRefreshRequestedEvent` → listener → `applyDeltas`. The listener runs `REQUIRES_NEW` (decision-log 0010) so its write commits despite firing AFTER_COMMIT.
- [ ] **AiUnavailable**: AI call fails → feedback entries left `pending_delta`, no exception past the orchestrator, retried later.
- [ ] **InvalidTasteProfileDelta**: applier rejects the batch → orchestrator retries once with a corrective prompt → still bad → logged `delta_invalid`, skipped, no partial application.
- [ ] **Budget exceeded**: applier throws → orchestrator retries once instructing the model to Archive first.
- [ ] **Empty AI response** (`deltas:[]`, the conservative no-delta path) → no `applyDeltas` call, no version bump, cursor still advanced (the feedback was processed).
- [ ] **>3 archive ops** in the AI response → passes through to the applier, which logs the anomaly WARN (01f); the AI response's `warnings` are logged too ([`lld/prompts/01-taste-profile-delta.md:351`](../../lld/prompts/01-taste-profile-delta.md)).
- [ ] **Hard-constraint signal** in feedback (e.g. "I've gone vegetarian") → the prompt returns empty deltas + warning ([`lld/prompts/01-taste-profile-delta.md:312, 354`](../../lld/prompts/01-taste-profile-delta.md)); the orchestrator logs the warning, applies nothing.
- [ ] **Origin attribution**: the resulting `preference_taste_profile_audit` row carries `actor_type=AI` and the origin trace.
- [ ] **MANUAL with explicit range**: `TriggerTasteProfileRefreshRequest{feedbackRangeStart, feedbackRangeEnd}` is honoured by the listener (overrides the since-cursor default).
- [ ] **Eval-set regression**: the 18-case eval set at [`lld/prompts/01-taste-profile-delta.md:308-327`](../../lld/prompts/01-taste-profile-delta.md) runs; gate ≥ 15/18 (the prompt's ship threshold). Wire it as a tagged/optional test (AI calls are not part of the default CI sweep — gate it behind a profile/property).
- [ ] **Per-user isolation**: a BATCH run for user A never reads user B's feedback or profile.

## Files this ticket touches

```
NEW   src/main/resources/db/migration/V20260615240000__feedback_create_preference_delta_trigger_cursor.sql      (conditional — see Database)

NEW   src/main/java/com/example/mealprep/feedback/ai/task/PreferenceTasteProfileDeltaTask.java
NEW   src/main/java/com/example/mealprep/feedback/ai/dto/TasteProfileDeltaResponse.java
NEW   src/main/java/com/example/mealprep/feedback/ai/dto/AiTasteProfileDelta.java                                 (AI-response sealed type — 8 permits, prompt field set)
NEW   src/main/java/com/example/mealprep/feedback/ai/internal/TasteProfileDeltaOrchestrator.java
NEW   src/main/java/com/example/mealprep/feedback/ai/internal/AiToApplyDeltaMapper.java                           (AI-shape → wire-shape mapping)
NEW   src/main/java/com/example/mealprep/feedback/ai/internal/PreferenceDeltaTriggerScheduler.java               (WEEKLY @Scheduled)
NEW   src/main/java/com/example/mealprep/feedback/ai/internal/PreferenceRefreshRequestedListener.java            (MANUAL — @TransactionalEventListener AFTER_COMMIT, REQUIRES_NEW write)
NEW   src/main/java/com/example/mealprep/feedback/ai/config/PreferenceDeltaProperties.java                       (@ConfigurationProperties record)

NEW   src/main/java/com/example/mealprep/feedback/domain/entity/PreferenceDeltaCursor.java                       (conditional)
NEW   src/main/java/com/example/mealprep/feedback/domain/repository/PreferenceDeltaCursorRepository.java         (conditional — MUST live in domain.repository per ArchUnit)

MOD   src/main/resources/openapi/... (none)
MOD   src/main/resources/application.properties / application-test.properties                                    (weekly-cron default; test profile disables the scheduler + AI calls)
MOD   (verify) TaskType enum                                                                                      (add PREFERENCE_DELTA_UPDATE if absent)

NEW   src/test/java/com/example/mealprep/feedback/PreferenceTasteProfileDeltaTaskTest.java                        (unit — task wiring + schema)
NEW   src/test/java/com/example/mealprep/feedback/AiToApplyDeltaMapperTest.java                                  (unit — 8-op mapping)
NEW   src/test/java/com/example/mealprep/feedback/TasteProfileDeltaOrchestratorTest.java                         (unit — triggers, AiUnavailable, corrective retry)
NEW   src/test/java/com/example/mealprep/feedback/PreferenceDeltaPipelineIT.java                                 (Testcontainers — manual trigger AFTER_COMMIT → applyDeltas, REQUIRES_NEW commit)
NEW   src/test/java/com/example/mealprep/feedback/PreferenceTasteProfileDeltaEvalTest.java                       (the 18-case eval set; profile-gated)
```

Total: ~13 new + 2-3 mods. Estimated agent runtime 6-8 hours (the AI-shape↔wire-shape mapper + the three trigger modes + the eval set dominate).

## Dependencies

- **Hard dependency**: `preference-01f` (the real applier — without it `applyDeltas` still throws).
- **Hard dependency**: mid-tier `AiService` + `AiTask`/`TaskType`/`PromptRef`/`ToolDefinitionBuilder` infra (verify merged at agent start).
- **Hard dependency**: `feedback-01d` (merged) — classification→`Destination.PREFERENCE` routing produces the `ClassifiedFeedbackEvent`s.
- **Reads**: `PreferenceQueryService.getTasteProfile` + `getFullPreferenceArchive` (01c/01e, merged).

## Acceptance / DoD

- [ ] `./mvnw -Dspotless.check.skip=true verify` passes
- [ ] `./mvnw spotless:apply` then `./mvnw spotless:check` clean
- [ ] **Run the full feedback module IT suite locally with Docker** + `redocly lint` (no-op for this ticket) + spotless before pushing. Docker IS available locally. Run ITs in ~3-class batches (Hikari pool-exhaustion flake on big sweeps).
- [ ] CI green
- [ ] All edge-case items above ticked; eval-set ≥ 15/18 (documented in PR)
- [ ] PR description traces: 5th PREFERENCE feedback → BATCH trigger → AiService → mapped deltas → `applyDeltas` → profile version bump; AND `POST /refresh-now` → MANUAL trigger AFTER_COMMIT → apply.

## What's NOT in scope

- **The deterministic applier** — `preference-01f`.
- **Rollback / replay** — `preference-01h`.
- **Classifier prompt** (deciding feedback IS preference-flavoured) — `feedback-01d`, merged.
- **Hard-constraint editing** — user-only, never AI ([`lld/prompts/01-taste-profile-delta.md:29`](../../lld/prompts/01-taste-profile-delta.md)).
- **pending_suggestions / low-confidence user-review queue** — deferred per the feedback design.

Squash-merge with: `feat(preference): 01g — AI delta-generation pipeline (PreferenceTasteProfileDeltaTask + batch/weekly/manual triggers; closes C-C-034 generate half)`
