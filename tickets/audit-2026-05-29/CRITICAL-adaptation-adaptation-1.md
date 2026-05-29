# AUDIT adaptation/adaptation-1 — Worker never applies DIRECT (system-catalogue) or PLAN_OVERLAY (plan-time substitution) outcomes — both record NO_OP

| field | value |
|---|---|
| Severity | **CRITICAL** |
| Module | adaptation |
| Dimension | MISSING_CAPABILITY |
| Verification | verified-confirmed |
| **Triage (edit me)** | **FIX** |
| Source | design/audits/2026-05-29-v1-backend-conformance-audit.md |

## Where

src/main/java/com/example/mealprep/adaptation/domain/service/AdaptationServiceImpl.java:682-710 (processJob Step 7)

## What's wrong

Step 7 only handles `effective == ApprovalPolicy.PENDING_CHANGE` (calls PendingChangeStore.create). The `else` branch — covering DIRECT (SYSTEM catalogue) and PLAN_OVERLAY (Trigger 4) — sets `outcome = OutcomeKind.NO_OP` with the comment "DIRECT / PLAN_OVERLAY apply paths land in 01d / 01e. For 01c the trace records NO_OP." The LLD §Shared worker pipeline step 7 (lines 758-764) requires: DIRECT → RecipeWriteApi.saveAdaptedVersion/saveAdaptedBranch with RebaseOrchestrator retries; PLAN_OVERLAY → RecipeWriteApi.saveAdaptedSubstitution. RecipeWriteApi exposes all three methods (recipe/spi/RecipeWriteApi.java:33,40,47) so the paths were fully buildable. Consequence: a SYSTEM-catalogue import/feedback job never creates a version or branch, and Trigger 4 (runPlanTimeRefineJob) never writes a substitution overlay — runPlanTimeRefineJob returns NO_CHANGE/NO_OP for every call. The LLD calls plan-time refine output 'always a substitution' (line 810, Decisions §9); the planner's Stage D refine loop is therefore non-functional end-to-end. No test exercises a DIRECT version write or a Trigger-4 substitution (AdaptationServiceLifecycleIT mocks saveAdaptedSubstitution but never verifies it; the LLD test-plan ITs Trigger1ImportFlowIT/Trigger4PlanTimeFlowIT/AdaptationServiceIT do not exist).

## Recommendation

Implement the DIRECT and PLAN_OVERLAY apply branches in processJob Step 7: route DIRECT through RebaseOrchestrator.saveAdaptedVersionWithRebase / saveAdaptedBranchWithRebase (setting VERSION_CREATED/BRANCH_CREATED + outcomeTargetId), and PLAN_OVERLAY through RecipeWriteApi.saveAdaptedSubstitution (SUBSTITUTION_CREATED). Add the LLD's Trigger1 (SYSTEM) and Trigger4 flow ITs to lock the behaviour.
