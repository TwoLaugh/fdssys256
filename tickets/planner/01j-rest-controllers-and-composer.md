# Ticket: planner — 01j REST controllers + composer (Stage A→D wiring)

## Summary

Lands the planner's HTTP face (`PlansController`) and the **composer** that orchestrates Stage A → Stage B (rollup) → Stage C (LLM pick) → Phase 2 (augment) → Stage D (refine-directives to adaptation pipeline) → persist `Plan`. Per `lld/planner.md §Composer`, §REST controllers.

The composer is the entry point that `POST /plans/generate` calls. It assembles `PlanCompositionContext` (the read-only bundle that aggregates every wave-2 `<Module>ForPlannerBundleDto`), feeds it through the existing pipeline (01d-01h), routes emitted `RefineDirective`s to **`AdaptationService.runPlanTimeRefineJob`** (NOT `OptimiserService.adapt` — see `tickets/WAVE3-NAMING-RECONCILIATION.md`), and writes the final `Plan` aggregate via the state machine in 01b.

## Behavioural spec

1. New class `PlanComposer` at `com.example.mealprep.planner.domain.service.internal.composer.PlanComposer`. `@Component`, package-private. Constructor-injects: `PlanCompositionContextBuilder`, `BeamSearchEngine` (01d), `RollupBuilder` (01f), `StageCInvoker` (01g), `Phase2Augmenter` (01h), `PlanPersister` (new), `PlanStateMachine` (01b), `AdaptationService` (cross-module — from adaptation-pipeline-01b), `ApplicationEventPublisher`, `DecisionLogWriter` (01l, null-tolerant), `Clock`.

2. New class `PlanCompositionContextBuilder` at `internal/composer/PlanCompositionContextBuilder.java`. `@Component`. Reads every wave-2 `<Module>ForPlannerBundleDto`: `PreferenceForPlannerBundleDto`, `NutritionForPlannerBundleDto`, `ProvisionsForPlannerBundleDto`, `HouseholdForPlannerBundleDto`, `RecipeForPlannerBundleDto`. Wires them into a fully-populated `PlanCompositionContext` (the 13-field full version per LLD; 01d shipped the partial).

3. **Public method** `PlanComposer.compose(GeneratePlanRequest request) → Plan`. Steps:
   1. `contextBuilder.build(request)` → `PlanCompositionContext`
   2. `beamSearchEngine.search(context, beamSearchConfig)` → `List<CandidatePlan>` (top-N)
   3. `rollupBuilder.build(context, candidates)` → `List<CandidatePlanRollupDto>` (flat summary for LLM)
   4. `stageCInvoker.invoke(context, rollups)` → `StageCResult(chosenIndex, reasoning, qualityWarnings)`
   5. `phase2Augmenter.augment(context, chosen)` → `Phase2Result(augmentations, refineDirectives)`
   6. Apply augmentations to `chosen` → mutated `CandidatePlan` (add snacks, set augmentation notes)
   7. Route each `RefineDirectiveProposal` → `adaptationService.runPlanTimeRefineJob(PlanTimeRefineDirectiveRequest)` (synchronous; result feeds into the plan persistence — if `classification != NO_CHANGE`, the new version id replaces the original recipe id for that slot)
   8. `planPersister.persist(chosen, request)` → `Plan` entity written via `PlanStateMachine.transition(GENERATING → GENERATED)`
   9. Publish `PlanGeneratedEvent` (01b's event)
   10. Return persisted `Plan`

4. **Stage D routing** — for each `RefineDirectiveProposal` from Phase 2, build `PlanTimeRefineDirectiveRequest(recipeId=chosen.assignments[slotId].recipeId, userId=request.userId, planId=newPlanId, slotId=affectedSlotId, directive=RefineDirectiveDto(kind=mapKind(proposal.kind), description=proposal.description, targetDelta=proposal.targetDelta), constraints=PlanConstraintsSnapshotDto.fromContext(context), parentDecisionId=composerDecisionId, traceId=context.traceId)`. The mapping function lives in `RefineDirectiveMapper.java`.

5. **Adaptation result handling**: `AdaptationResultDto.classification == NO_CHANGE` → log INFO, leave slot's recipe unchanged. `classification ∈ {VERSION, BRANCH}` → replace `chosen.assignments[slotId].recipeId` with `versionIdCreated.orElseThrow()` (or branchIdCreated). `classification == SUBSTITUTION` → attach `substitutionIdCreated` to the slot's `appliedSubstitutions` list. **MUST happen BEFORE persistence** so the final plan reflects refines.

6. **`AdaptationAiUnavailableException`** (block-and-prompt fallback per adaptation-pipeline LLD) → catch at the composer level, log WARN, skip that directive (leave slot unchanged). Plan still persists. Add a `qualityWarning` to the plan's metadata noting "Stage D adaptation unavailable; using original recipe selections".

7. **`PlanComposer.compose` is `@Transactional`** (propagation REQUIRED). The whole composition + persist runs in ONE tx; if anything fails after Stage A, the partial plan rolls back. The `adaptationService.runPlanTimeRefineJob` is itself `@Transactional(REQUIRES_NEW)` (per adaptation-pipeline-01b's contract), so its writes commit independently — the planner's rollback won't undo the adaptation's pending-changes or trace rows. This is correct: adaptation work is auditable independent of planner persistence outcomes.

8. New REST controller `PlansController` at `api.controller.PlansController`. `@RestController @RequestMapping("/api/v1/plans")`. Endpoints:
   - `POST /plans/generate` → `PlanComposer.compose(request)`. Returns 201 + Location + body `PlanDto`. Auth: required (caller user must match request.userId).
   - `POST /plans/{id}/accept` → `PlanStateMachine.transition(GENERATED → ACCEPTED)` + publish `PlanAcceptedEvent`. Returns 200 + `PlanDto`.
   - `POST /plans/{id}/reject` → idempotent (`REJECTED → REJECTED` = 200 no-op per LLD §Flow 3); state machine raises but controller catches and returns existing state. Body: `RejectPlanRequest(reason)`.
   - `POST /plans/{id}/revert` → only allowed from `ACCEPTED`; transitions to `GENERATED`. Bumps `generation`. Publishes `PlanRevertedEvent` (add to 01b retroactively or include here).
   - `POST /plans/{id}/abandon` → from any non-terminal state. Body: `AbandonPlanRequest(reason)`.
   - `PATCH /plans/{id}/slots/{slotId}/state` → transitions an individual `MealPrepSlot.state` (PLANNED → PROVISIONED → COOKED → EATEN / SKIPPED). Body: `SlotStateChangeRequest(newState)`.
   - `POST /plans/{id}/reopt-suggestions/{suggestionId}/accept` → applies a `MealPrepPlanReoptSuggestion` from 01i. Reads the suggestion's `proposedAssignments`, mutates the plan in place (within tx), publishes `PlanSupersededEvent` for the OLD plan + `PlanGeneratedEvent` for the new generation. Marks suggestion `ACCEPTED`.
   - `POST /plans/{id}/reopt-suggestions/{suggestionId}/reject` → marks suggestion `REJECTED`. No plan change.

9. **Authorization** — `@PreAuthorize("@plannerAuth.canAccessPlan(authentication, #id)")` on all `/{id}/*` endpoints. The `plannerAuth` bean (new) checks plan ownership via household membership.

10. **Idempotency keys** on `POST /plans/generate` — accept `Idempotency-Key` header. If present and matches a recent (last 5 min) successful generation, return the cached `PlanDto` with status 200 (not 201). Prevents double-submit on flaky clients.

11. **OpenAPI** — splice the YAML chunks for all 8 endpoints into `src/main/resources/openapi/openapi.yaml`. Use inline schemas for nullable fields (per round-1/4/6 OpenAPI gotcha — never `$ref + nullable: true`). Schema names: `PlanDto`, `GeneratePlanRequest`, `RejectPlanRequest`, `AbandonPlanRequest`, `SlotStateChangeRequest`, `ReoptSuggestionDto`, `PlanGenerationProblemDetail`.

12. **Error mapping** in `PlannerExceptionHandler`: `InvalidPlanStateTransitionException` → 409 + ProblemDetail; `PlanNotFoundException` → 404; `PlanNotReoptableException` → 400; `AdaptationAiUnavailableException` → swallowed (logged, plan still generates with warning); generic 5xx for unmapped.

13. **Decision-log writes** at: composer entry (`kind=plan_generation_start`), Stage A end (`kind=stage_a_done`), Stage C end (`kind=stage_c_done`), Phase 2 end (`kind=phase_2_done`), composer exit (`kind=plan_generation_complete`). Each row's `parent_decision_id` chains to the previous. 01l owns the writer; treat null-tolerantly.

14. **Trace ID** — generated at composer entry if request doesn't supply one. Propagated to context, to every cross-module call (RecipeBundleDto reads carry it via `traceId` param; AdaptationService calls receive it in the request DTO).

## Files this ticket touches

```
src/main/java/com/example/mealprep/planner/api/controller/PlansController.java        new
src/main/java/com/example/mealprep/planner/api/dto/GeneratePlanRequest.java        new
src/main/java/com/example/mealprep/planner/api/dto/RejectPlanRequest.java        new
src/main/java/com/example/mealprep/planner/api/dto/AbandonPlanRequest.java        new
src/main/java/com/example/mealprep/planner/api/dto/SlotStateChangeRequest.java        new
src/main/java/com/example/mealprep/planner/api/dto/ReoptSuggestionDto.java        new
src/main/java/com/example/mealprep/planner/api/mapper/ReoptSuggestionMapper.java        new
src/main/java/com/example/mealprep/planner/domain/service/internal/composer/PlanComposer.java        new
src/main/java/com/example/mealprep/planner/domain/service/internal/composer/PlanCompositionContextBuilder.java        new
src/main/java/com/example/mealprep/planner/domain/service/internal/composer/PlanPersister.java        new
src/main/java/com/example/mealprep/planner/domain/service/internal/composer/RefineDirectiveMapper.java        new
src/main/java/com/example/mealprep/planner/exception/PlannerExceptionHandler.java        modified  (add new mappings)
src/main/java/com/example/mealprep/planner/security/PlannerAuth.java        new
src/main/resources/openapi/paths/planner.yaml        new  (per-module file)
src/main/resources/openapi/openapi.yaml        modified  (include planner paths)
src/test/java/com/example/mealprep/planner/PlansControllerIT.java        new
src/test/java/com/example/mealprep/planner/PlanComposerIT.java        new  end-to-end with TestAiService + Testcontainers
```

## Dependencies

- **Hard dependency**: `planner-01a, 01b, 01d, 01e, 01f, 01g, 01h` (all merged) — composer wires these together.
- **Hard dependency**: `planner-01i` — `MealPrepPlanReoptSuggestion` entity + `MidWeekReoptCoordinator` (used by accept/reject suggestion endpoints).
- **Hard dependency**: `adaptation-pipeline-01b` — `AdaptationService` interface + `PlanTimeRefineDirectiveRequest` + `AdaptationResultDto`. Per `WAVE3-NAMING-RECONCILIATION.md`.
- **Hard dependency**: All wave-2 `<Module>ForPlannerBundleDto`s — `preference-01a`, `nutrition-01a-h`, `provisions-01f`, `household-01f`, `recipe-01a-h`.

## Acceptance / DoD

- [ ] `./mvnw -Dspotless.check.skip=true verify` passes
- [ ] All 14 invariants ticked
- [ ] `PlansControllerIT` covers happy path (generate, accept, reject, revert, abandon, slot transitions, reopt-suggestion accept/reject) + auth (401 anon, 403 cross-household)
- [ ] `PlanComposerIT` covers end-to-end with TestAiService + a real adaptation-pipeline (or `@MockBean AdaptationService` with stubbed `AdaptationResultDto` returns covering VERSION/BRANCH/SUBSTITUTION/NO_CHANGE/AiUnavailable)
- [ ] OpenAPI: `./mvnw verify` lint passes; swagger-parser accepts the planner paths
- [ ] Idempotency-Key test: double-submit returns 200 + cached body, not 201 twice
- [ ] AdaptationAiUnavailableException path test: composer logs WARN, plan still persists, qualityWarning present
- [ ] No tests use `@Transactional` on test methods inadvertently rolling back fixtures

Squash-merge with: `feat(planner): 01j — PlansController (8 endpoints) + PlanComposer (Stage A→D wiring) + adaptation-pipeline routing`

## What's NOT in scope

- Listeners that trigger mid-week re-opt → **planner-01k**
- Full DecisionLogWriter — 01j only CALLS it null-tolerantly → **planner-01l**
- Plan-list / history endpoints (those are in 01c, merged)
- Suggestion expiry sweep — covered in 01k or a follow-up cron ticket

## Gotchas to bake in

- **The naming reconciliation is CRITICAL**. Read `tickets/WAVE3-NAMING-RECONCILIATION.md` before implementing. The composer calls `adaptationService.runPlanTimeRefineJob(...)` — NOT `optimiserService.adapt(...)`. Imports from `com.example.mealprep.adaptation.api.dto.*` and `com.example.mealprep.adaptation.domain.service.AdaptationService`.
- **`@Transactional` on `PlanComposer.compose` is REQUIRED, not REQUIRES_NEW**. The whole composition is one tx. The adaptation calls inside use REQUIRES_NEW THEMSELVES (declared in adaptation-pipeline-01b); planner's rollback won't undo them. That's correct semantics — adaptation traces survive composer failures.
- **OpenAPI nullable+$ref bug** (round 1/4/6 lesson) — never `$ref + nullable: true` for any DTO field. Inline the type. Especially relevant for `PlanDto.augmentationNotes` (nullable string), `MealPrepSlotDto.appliedSubstitutions` (nullable list).
- **HTTP-client adapters live in `..api..` or `..config..`** — not relevant for 01j (no HTTP client) but flag it for follow-up if the composer ever fetches external URLs.
- **MockMvc URL encoding trap** (round 6 lesson) — IT path values must not contain spaces or special chars. Use UUID fixtures throughout.
- **Spring `Page<T>` flat shape** — not used in 01j (no paged endpoints here; history is 01c). Flag for the implementer not to invent paging where the LLD doesn't ask.
- **Idempotency-Key implementation**: use an in-memory `Caffeine` cache keyed by `(userId, idempotencyKey)`, 5-min TTL, value is `PlanDto`. NOT a DB table for v1.
