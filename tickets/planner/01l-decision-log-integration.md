# Ticket: planner — 01l Decision-log integration + trace propagation + smoke test

## Summary

Wires the planner module into the project-wide `DecisionLog` (per `lld/core.md`). Every loop iteration — Stage A start, Stage A done, Stage B rollup, Stage C choice, Phase 2 augmentations, Stage D refines, mid-week reopt, suggestion accept/reject — writes a `decision_log` row. Trace IDs propagate from request → composer → adaptation-pipeline → events. A smoke test asserts the cross-stage decision-log chain reads back coherently for a single plan generation. Per `lld/planner.md §Decision-log integration`.

This ticket is **the integration layer** — it provides the `DecisionLogWriter` helper that 01i, 01j, 01k were authored to call null-tolerantly. After 01l ships, those nulls flip to real writes.

## Behavioural spec

1. New class `DecisionLogWriter` at `com.example.mealprep.planner.domain.service.internal.decisionlog.DecisionLogWriter`. `@Component`, package-private. Constructor-injects: `DecisionLogService` (from core module — the cross-cutting service from `core-01-decision-log`), `ObjectMapper`, `Clock`.

2. **Public method** `UUID write(DecisionLogEntry entry)`. Builds a `DecisionLog` row via `decisionLogService.append(...)` and returns the new row id. The `DecisionLogEntry` record carries: `scope_kind = "PLANNER"`, `scope_id = planId`, `actor_user_id`, `kind` (enum below), `parent_decision_id` (nullable), `trace_id`, `inputs` (JsonNode), `outputs` (JsonNode).

3. **Decision kinds** (new enum `PlannerDecisionKind` in `internal/decisionlog/`):
   - `PLAN_GENERATION_START` — entry to `PlanComposer.compose`
   - `STAGE_A_DONE` — after `BeamSearchEngine.search` returns top-N
   - `STAGE_B_DONE` — after `RollupBuilder.build`
   - `STAGE_C_DONE` — after `StageCInvoker.invoke` returns the chosen index
   - `PHASE_2_DONE` — after `Phase2Augmenter.augment` returns augmentations + directives
   - `STAGE_D_OUTCOME` — once per directive routed to `AdaptationService.runPlanTimeRefineJob` (records the `AdaptationResultDto.classification`)
   - `PLAN_GENERATION_COMPLETE` — composer exit
   - `PLAN_LIFECYCLE_TRANSITION` — every state machine transition (GENERATED→ACCEPTED, etc.)
   - `MID_WEEK_REOPT_REQUEST` — entry to `MidWeekReoptCoordinator.requestReopt`
   - `MID_WEEK_REOPT_RESULT` — exit (suggestion id or skipped)
   - `REOPT_SUGGESTION_ACCEPTED` — user accepted via 01j endpoint
   - `REOPT_SUGGESTION_REJECTED` — user rejected
   - `LISTENER_TRIGGER` — each materiality-positive listener invocation (links external event → planner reopt-request)

4. **Parent-decision chain**:
   - `PLAN_GENERATION_START` has `parent_decision_id = null` (it's a root) or the trigger's decision id if the request came from a listener.
   - Every subsequent stage of the SAME plan generation references the START row's id.
   - `STAGE_D_OUTCOME` records BOTH the planner stage's decision id (parent) AND the adaptation's `parentDecisionId` from `PlanTimeRefineDirectiveRequest` (cross-module chain). The adaptation pipeline's own decision rows reference that parent.
   - `MID_WEEK_REOPT_REQUEST.parent_decision_id` = the listener's `LISTENER_TRIGGER` decision id, which in turn references the source event's `traceId` (NOT a decision id — events don't have decision ids; the `trace_id` field carries the cross-event correlation).

5. **Trace ID propagation rules**:
   - Each `POST /plans/generate` request without an `X-Trace-Id` header generates a new UUID.
   - The composer threads it through: `context.traceId → BeamSearchEngine reads → StageCInvoker passes to AiTask → Phase2Augmenter passes to AiTask → adaptationService.runPlanTimeRefineJob(traceId=...)` → adaptation publishes events with that same trace id → planner's `AdaptationCallbackHandler` (01k) reads it back.
   - Listeners READ the source event's traceId and propagate it into the reopt request. A reopt's trace id matches the trigger event's trace id (so a divergence event and its resulting reopt suggestion are connectable).

6. **`@Transactional` propagation**: `DecisionLogWriter.write` uses `Propagation.REQUIRES_NEW` so the decision row commits independently of the calling stage's transaction. If the composer rolls back, the decision-log rows of stages that already succeeded REMAIN — they record what was attempted. This is the same pattern as `noRollbackFor` but cleaner.

7. **Update existing call sites** (the null-tolerant calls land in 01i, 01j, 01k):
   - In `PlanComposer` (01j): replace each `if (decisionLogWriter != null) decisionLogWriter.write(...)` with direct calls.
   - In `MidWeekReoptCoordinator` (01i): same.
   - In `PlannerEventListener` (01k): emit `LISTENER_TRIGGER` rows.
   - In `PlanStateMachine` consumers (controllers in 01j, listeners in 01k): emit `PLAN_LIFECYCLE_TRANSITION` rows on every transition.

8. **`inputs` / `outputs` payload contract** — JsonNode shapes per kind:
   - `STAGE_A_DONE.outputs`: `{topNRecipeIds: [...], topNScores: [...], poolSizes: {slotId: count}}`
   - `STAGE_C_DONE.inputs`: `{rollupCount: N, promptVersion: "..."}`, `.outputs`: `{chosenIndex: i, reasoning: "...", qualityWarnings: [...]}`
   - `PHASE_2_DONE.outputs`: `{augmentations: [...], refineDirectiveCount: N}`
   - `STAGE_D_OUTCOME.outputs`: `{adaptationJobId: ..., classification: "VERSION|BRANCH|SUBSTITUTION|NO_CHANGE", versionIdCreated: ...}`
   - `MID_WEEK_REOPT_RESULT.outputs`: `{suggestionId: ... | null, skippedReason: "no_degrees_of_freedom|no_material_change|budget_exhausted" | null}`

9. **Smoke test** `PlannerDecisionLogChainIT`: generates one plan end-to-end (full Stage A→D), then queries `decision_log` for `scope_kind=PLANNER AND scope_id=planId`. Asserts:
   - Exactly one `PLAN_GENERATION_START` row.
   - Subsequent rows are reachable from START via `parent_decision_id` chain (single connected DAG).
   - `STAGE_C_DONE.inputs.rollupCount` matches Stage A's `topNRecipeIds.length` (the count of candidates fed to Stage C).
   - Every row has a non-null `trace_id`, all matching.

10. **Admin read endpoint** `GET /api/v1/admin/planner/decisions/{planId}` (`@PreAuthorize("hasRole('ROLE_ADMIN')")`) — returns the chain as a flat list ordered by `created_at`. Supports `?traceId=` filter alternative. Schema: `PlannerDecisionChainDto(planId, rows: List<PlannerDecisionRowDto>)`.

11. **Performance** — decision-log rows are small (<1KB each), one per stage = ~7-10 rows per plan. No paging needed at v1. Index on `(scope_kind, scope_id)` from core's migration is sufficient.

12. **No retroactive backfill** — plans generated BEFORE 01l ships have no decision-log rows; the admin endpoint returns an empty list. Note in the OpenAPI description.

## Files this ticket touches

```
src/main/java/com/example/mealprep/planner/domain/service/internal/decisionlog/DecisionLogWriter.java        new
src/main/java/com/example/mealprep/planner/domain/service/internal/decisionlog/PlannerDecisionKind.java        new
src/main/java/com/example/mealprep/planner/domain/service/internal/decisionlog/DecisionLogEntry.java        new
src/main/java/com/example/mealprep/planner/api/controller/AdminPlannerDecisionsController.java        new
src/main/java/com/example/mealprep/planner/api/dto/PlannerDecisionRowDto.java        new
src/main/java/com/example/mealprep/planner/api/dto/PlannerDecisionChainDto.java        new
src/main/java/com/example/mealprep/planner/api/mapper/PlannerDecisionMapper.java        new
src/main/java/com/example/mealprep/planner/domain/service/internal/composer/PlanComposer.java        modified  (flip null-tolerant calls to direct)
src/main/java/com/example/mealprep/planner/domain/service/internal/reopt/MidWeekReoptCoordinator.java        modified  (same)
src/main/java/com/example/mealprep/planner/domain/service/internal/listeners/PlannerEventListener.java        modified  (emit LISTENER_TRIGGER)
src/main/java/com/example/mealprep/planner/api/controller/PlansController.java        modified  (emit PLAN_LIFECYCLE_TRANSITION on state transitions)
src/main/resources/openapi/paths/planner.yaml        modified  (admin endpoint)
src/test/java/com/example/mealprep/planner/DecisionLogWriterTest.java        new  unit
src/test/java/com/example/mealprep/planner/PlannerDecisionLogChainIT.java        new  full E2E smoke
src/test/java/com/example/mealprep/planner/AdminPlannerDecisionsControllerIT.java        new  auth + read shape
```

## Dependencies

- **Hard dependency**: `planner-01i, 01j, 01k` (merged) — this ticket replaces their null-tolerant decision-log calls with real ones. Cannot ship before them.
- **Hard dependency**: `core-01-decision-log` (merged) — `DecisionLogService.append`.
- **Hard dependency**: `adaptation-pipeline-01c` — emits its own decision-log rows; this ticket's `STAGE_D_OUTCOME` row links to them via shared `parent_decision_id`. Verify the adaptation module uses the same `decision_log` table.

## Acceptance / DoD

- [ ] `./mvnw -Dspotless.check.skip=true verify` passes
- [ ] All 12 invariants ticked
- [ ] Smoke test asserts the chain is a single connected DAG
- [ ] `STAGE_D_OUTCOME` rows correctly chain to adaptation-pipeline rows in `PlannerDecisionLogChainIT`
- [ ] Admin endpoint test: anon 401, non-admin 403, admin 200 with ordered list
- [ ] `DecisionLogWriter.write` uses `REQUIRES_NEW` (grep before commit)
- [ ] No remaining `if (decisionLogWriter != null)` guards in `PlanComposer` / `MidWeekReoptCoordinator` / `PlannerEventListener`
- [ ] Trace id is non-null on every row written
- [ ] `inputs`/`outputs` JsonNode shapes match the contract in invariant 8

Squash-merge with: `feat(planner): 01l — DecisionLogWriter + cross-stage chain + admin endpoint + smoke test`

## What's NOT in scope

- Cross-module decision-log analytics (e.g., "which plans had AI_UNAVAILABLE in Stage D?") — admin query support, not v1
- Decision-log retention sweep — owned by core module
- UI for the admin endpoint — frontend phase
- Cross-trace causality analysis tools — separate observability ticket

## Gotchas to bake in

- **`DecisionLogWriter.write` MUST use `@Transactional(propagation = Propagation.REQUIRES_NEW)`** — composer rollbacks must NOT erase the audit of what was attempted. This is the same pattern as `noRollbackFor` for write-then-throw flows.
- **JsonNode payloads serialise via Jackson** — ensure all fields are JSON-safe (no Spring entities, no proxies). Use the existing `ObjectMapper` bean.
- **`parent_decision_id` is a UUID, not a fk constraint** — core's `decision_log` table doesn't enforce referential integrity (cross-module rows would create circular deps). Cross-module chains are by convention, not enforcement.
- **Don't log secrets** — the `inputs.userId` is fine (UUID), but never include preference free-text or feedback bodies. Phase 2 augmentation payloads may contain LLM-generated text — truncate to 500 chars before logging.
- **Listener-source trace_id propagation**: events from other modules carry their own trace_id. The listener READS it (do NOT generate a new one) and threads it into the reopt request. A single source event's effects across modules share one trace.
- **Adaptation pipeline writes its own decision rows** (per adaptation-pipeline-01c) — the planner's `STAGE_D_OUTCOME` references the adaptation's `jobId` AND uses the adaptation's `parentDecisionId` field. The cross-module DAG resolves via shared parent_decision_id values.
