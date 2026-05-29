# AUDIT planner/planner-5 — Revert is copy-forward of the current ACTIVE plan, not revert-to a chosen historical plan; missing ownership check, hard-constraint re-filter, and Phase-2 refill

| field | value |
|---|---|
| Severity | **HIGH** |
| Module | planner |
| Dimension | DESIGN_DIVERGENCE |
| Verification | verified-confirmed |
| **Triage (edit me)** | **FIX-OR-ACCEPT** |
| Source | design/audits/2026-05-29-v1-backend-conformance-audit.md |

## Where

src/main/java/com/example/mealprep/planner/domain/service/internal/lifecycle/PlanWriteServiceImpl.java:184-227 (revertPlan); api/controller/PlansController.java:152-165

## What's wrong

LLD §Service Interfaces (line 600) and §Flow 4 specify revertToPlan(RevertToPlanRequest{targetHistoricalPlanId}) — content COPIED FROM an arbitrary historical plan the user picks, with: step 2 household-ownership guard throwing RevertTargetNotInHistoryException (422), step 4 score/rollup recompute, step 5 HardConstraintFilterService re-check stripping now-banned recipes, step 6 Phase2Augmenter refill of emptied slots. The shipped revertPlan(UUID planId) takes the plan to revert FROM, requires it be ACTIVE, and copyForward()s that same plan's graph verbatim onto generation+1 (lines 193, 441-501) — there is no target-plan selection, no RevertToPlanRequest DTO (it does not exist in api/dto), no household-ownership check, no HardConstraintFilterService re-filter, and no Phase-2 refill. RevertTargetNotInHistoryException is defined and mapped to 422 but is never thrown. This 'revert' is effectively 'clone current active', which cannot satisfy the HLD use-case 'browse historical plans ... revert to plan version N'.

## Recommendation

Implement target-plan-based revert per LLD §Flow 4 (accept targetHistoricalPlanId, validate it belongs to the caller's household → 422, copy its content, re-run the hard-constraint filter + Phase-2 refill), or document the reduced 'clone-active' semantics as an accepted v1 scope cut and remove the dead RevertTargetNotInHistoryException/422 mapping.
