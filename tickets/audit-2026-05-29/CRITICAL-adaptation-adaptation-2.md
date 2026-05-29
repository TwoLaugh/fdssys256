# AUDIT adaptation/adaptation-2 — Hard-constraint (allergy/dietary) safety-net filter is never invoked inside the worker pipeline

| field | value |
|---|---|
| Severity | **CRITICAL** |
| Module | adaptation |
| Dimension | DESIGN_DIVERGENCE |
| Verification | verified-confirmed |
| **Triage (edit me)** | **FIX** |
| Source | design/audits/2026-05-29-v1-backend-conformance-audit.md |

## Where

src/main/java/com/example/mealprep/adaptation/domain/service/AdaptationServiceImpl.java:637-680 (processJob Steps 3 & 6); CandidateGenerator.java:53-63

## What's wrong

LLD §Shared worker pipeline is explicit: Step 3 — 'HardConstraintFilterService.filterRecipes drops infeasible candidates BEFORE scoring — never bypassed; the safety net is invariant per HLD §Guardrails'; Step 6 — 'HardConstraintFilterService.checkRecipe re-runs against the FINAL diff — guards against the LLM stitching a candidate together post-hoc.' This mirrors the optimisation-loop HLD's core invariant (optimisation-loop.md lines 75-81): the hard-filter runs before scoring and the LLM 'never touches hard constraints — it picks from a pre-vetted shortlist.' In the implementation, processJob calls candidateGenerator.generate() then scoringEngine.selectTopN() with NO hard-constraint filtering step, and there is NO final-diff re-check after Stage C. A grep for HardConstraintFilterService across domain/service shows it is used only in the two listeners (AdaptationImportListener, AdaptationDataModelListener) as a pre-enqueue heuristic — never in the worker. CandidateGenerator.generate() just concatenates strategy output with no filter. For a food-safety system this means an adaptation could surface/apply a swap that reintroduces an allergen with no deterministic guard.

## Recommendation

Inject HardConstraintFilterService into the worker; filter candidates before scoring (Step 3/4 boundary) and re-check the final chosen diff before apply (Step 6). On empty-after-filter, fail with HARD_FILTER (already a JobFailureReason).
