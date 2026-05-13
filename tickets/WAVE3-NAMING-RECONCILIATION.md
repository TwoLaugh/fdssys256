# Wave 3 — Naming Reconciliation Note

**Status**: Pinned for implementer agents. Read this BEFORE implementing any wave-3 ticket that references the adaptation pipeline's cross-module surface.

## Context

During wave-3 ticket writing, the parent's brief told the planner/discovery/feedback ticket-writing agents that the optimiser module would expose `OptimiserService` in package `com.example.mealprep.optimiser`. The adaptation-pipeline ticket-writing agent — following LLD-as-truth — shipped tickets that name the service **`AdaptationService`** in package **`com.example.mealprep.adaptation`**. The LLD names win; the parent's brief was wrong.

Affected tickets:
- `tickets/planner/01g-stage-c-invoker.md` — references `OptimiserService` in passing comments only (no calls)
- `tickets/planner/01h-phase2-augmenter.md` — references `OptimiserService.adapt(RefineDirectiveDto)` for Stage D
- `tickets/planner/01j` (yet-to-be-written composer) — will call the optimiser to apply refine-directives
- `tickets/feedback/01d-router-and-destination-dispatchers.md` — references `OptimiserService.handleRecipeFeedback(...)`

## The mapping

When an implementer agent reads a ticket that says **"call `OptimiserService.X(...)`"**, substitute the real `AdaptationService` call per this table:

| Ticket says | Real call (use this) | Inputs assembled by caller |
|---|---|---|
| `OptimiserService.adapt(RefineDirectiveDto)` | `adaptationService.runPlanTimeRefineJob(PlanTimeRefineDirectiveRequest)` | `PlanTimeRefineDirectiveRequest(recipeId, userId, planId, slotId, RefineDirectiveDto directive, PlanConstraintsSnapshotDto constraints, parentDecisionId, traceId)` — see `tickets/adaptation-pipeline/01b-public-service-contracts-dtos-mappers.md` §Request DTOs |
| `OptimiserService.handleRecipeFeedback(...)` | `adaptationService.enqueueFeedbackJob(FeedbackJobRequest)` | `FeedbackJobRequest(recipeId, userId, feedbackId, feedbackText, RatingDeltaDto ratingDelta, traceId, parentDecisionId)` |
| `RefineDirectiveDto` (as a top-level type) | Still `RefineDirectiveDto` — but it's a **nested record** inside `PlanTimeRefineDirectiveRequest` (`record RefineDirectiveDto(DirectiveKind kind, String description, JsonNode targetDelta)`) |
| `OptimiserService.handleNutritionDivergence(...)` | **No direct method.** The nutrition module emits `NutritionTargetsChangedEvent`; the adaptation pipeline's Trigger-3 listener consumes it via `@TransactionalEventListener(AFTER_COMMIT) + @Transactional(REQUIRES_NEW)` and calls `enqueueDataModelChangeJobs` internally. Caller does nothing. |
| `OptimiserService.handleMidWeekTrigger(...)` | **Not a thing in adaptation-pipeline.** Mid-week re-opt is owned by the planner module (see `planner-01i`). |
| `InfeasibleDirective` signal/exception | **Not a thing.** Read `AdaptationResultDto.classification() == NO_CHANGE` instead. |

## Result type

`AdaptationService.runPlanTimeRefineJob` and `enqueueFeedbackJob` both return:

```java
AdaptationResultDto(
    UUID jobId,
    UUID recipeId,
    AdaptationClassification classification,  // VERSION | BRANCH | SUBSTITUTION | NO_CHANGE
    Optional<UUID> versionIdCreated,
    Optional<UUID> branchIdCreated,
    Optional<UUID> substitutionIdCreated,
    Optional<UUID> pendingChangeIdCreated,
    JsonNode proposedDiff,
    String reasoning,
    String nutritionalNotes,
    boolean requiresApproval,
    List<PlannerHintDto> plannerHints,
    UUID traceId,
    BigDecimal confidence)
```

`NO_CHANGE` = infeasibility OR adaptation rejected by gates. Caller treats both the same: the refine-directive was honoured, no recipe change happened.

## Events to listen to

The adaptation pipeline publishes these AFTER_COMMIT (see `tickets/adaptation-pipeline/01b` for the full list):

- `AdaptationJobCompletedEvent(jobId, recipeId, outcomeKind, outcomeTargetId, classification, confidence, traceId, occurredAt)`
- `AdaptationJobFailedEvent(jobId, recipeId, JobFailureReason reason, excerpt, traceId, occurredAt)` — filter `reason == AI_UNAVAILABLE` for block-and-prompt notifications.
- `PendingChangeCreatedEvent` / `Accepted` / `Rejected` / `Superseded`
- `PlannerHintEmittedEvent`

Listeners in planner / feedback / notification MUST use round-7's propagation rule if they touch JPA: `@TransactionalEventListener(AFTER_COMMIT) + @Transactional(propagation = REQUIRES_NEW)`.

## Action when implementing affected tickets

1. Implement the ticket's logic as specified, treating the `OptimiserService` references as logical placeholders.
2. At the call site, import `com.example.mealprep.adaptation.api.dto.*` for the request DTOs and `com.example.mealprep.adaptation.domain.service.AdaptationService` for the service.
3. Assemble the request DTO from the ticket's local context (the planner already has `RefineDirectiveProposal` from the LLM; the feedback router already has the structured rating + extracted text).
4. Call the real method.
5. Map `AdaptationResultDto.classification == NO_CHANGE` to whatever the ticket calls "infeasibility" / "no-op routing".

If you're stuck on a shape mismatch the LLDs don't resolve, stop and surface it — don't guess.
