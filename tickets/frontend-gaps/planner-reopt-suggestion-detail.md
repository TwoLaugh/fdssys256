# Ticket: planner — GET single re-opt suggestion with `proposedAssignments` (pre-accept diff) (P1)

## Summary

The HLD mandates a **diff preview before any re-optimisation is confirmed** (`design/meal-planner.md`:
"the user sees a 'regenerate plan from [day]?' prompt with a diff preview (what changes, what's
preserved) and confirms"). The shipped contract can't render it: the list read
(`GET /api/v1/plans/suggestions`) returns `ReoptSuggestionDto` **without** `proposedAssignments`,
and the diff shape (`PlanReoptSuggestionDto.proposedAssignments.changes[]`) is returned only *by*
the accept/reject calls — after the decision. There is no GET-single-suggestion endpoint. The
re-opt panel can currently show only `summary` + affected-slot strikes pre-accept —
[`design/frontend/pages/plan.md` §3e + §8 Q2](../../design/frontend/pages/plan.md).

**Fix (the spec's preferred shape):** add

```
GET /api/v1/plans/{planId}/reopt-suggestions/{suggestionId}  →  200 PlanReoptSuggestionDto
```

returning the same DTO accept/reject already return (status + `proposedAssignments.changes[]`:
`slotId`, `oldRecipeId`, `newRecipeId`, `newServings`, per-row `reason`). The alternative — folding
`proposedAssignments` into the list DTO — bloats every poll of the panel; rejected.

## Behavioural spec

- Path-scoped: the suggestion must belong to `{planId}` (404 otherwise — same rule as the existing
  accept/reject paths).
- Any status is readable (PENDING for the preview; ACCEPTED/REJECTED/EXPIRED for history/back
  navigation) — the read has no side effects.
- `proposedAssignments` reflects what acceptance *would* write for PENDING rows — i.e. the stored
  proposal, not a recomputation. If a suggestion's proposal can go stale against pinned-slot drift
  (a slot becomes EATEN after the suggestion was raised), the existing accept-time validation
  stays authoritative; the GET returns the stored proposal as-is (document this in the operation
  description).
- Caller authorisation identical to the list read (household membership).

### OpenAPI excerpt

```yaml
# paths/planner.yaml
/api/v1/plans/{planId}/reopt-suggestions/{suggestionId}:
  get:
    operationId: getReoptSuggestion
    summary: 'Single re-opt suggestion with its proposed slot assignments (pre-accept diff preview).'
    responses:
      '200': { $ref: PlanReoptSuggestionDto }
      '404': { description: 'unknown suggestion, or not a suggestion of this plan' }
```

## Edge-case checklist

- [ ] PENDING suggestion → 200 with full `changes[]` rows
- [ ] Decided/expired suggestion → 200 with its terminal status (read-only history)
- [ ] Suggestion of another plan / household → 404
- [ ] Accept-after-preview unchanged (no double-write; GET is side-effect-free)
- [ ] `changes[]` rows for slots that have since been pinned still render (accept-time validation is the guard, not the read)

## Files this ticket touches

```
MOD   src/main/java/com/example/mealprep/planner/api/controller/PlansController.java     (GET single)
MOD   src/main/java/com/example/mealprep/planner/domain/service/...                      (query method on the plan/suggestion query service)
MOD   src/main/resources/openapi/paths/planner.yaml                                      (new operation)
MOD   src/test/java/com/example/mealprep/planner/...                                     (controller IT + scoping cases)
```

## Dependencies

None — `PlanReoptSuggestionDto` and its mapper exist (accept/reject already produce it).

## Acceptance / DoD

- [ ] `verify` + `spotless` clean; CI green; all edge cases ticked
- [ ] Frontend §3e wireable: expand a pending suggestion → SwapLine diff rows render *before* Accept
- [ ] swagger-request-validator passes

Squash-merge with: `feat(planner): GET single re-opt suggestion with proposedAssignments for pre-accept diff preview`

## What's NOT in scope

- Folding `proposedAssignments` into the list DTO (rejected alternative).
- Candidate-pick decision → [`planner-candidate-pick-decision.md`](planner-candidate-pick-decision.md).
- Suggestion expiry semantics (already shipped).
