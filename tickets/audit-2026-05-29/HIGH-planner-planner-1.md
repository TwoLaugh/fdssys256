# AUDIT planner/planner-1 — AI Stage C / Phase 2 / Stage D run INSIDE the persistence transaction, violating the locked Tier-1 rule

| field | value |
|---|---|
| Severity | **HIGH** |
| Module | planner |
| Dimension | DESIGN_DIVERGENCE |
| Verification | verified-confirmed |
| **Triage (edit me)** | **FIX** |
| Source | design/audits/2026-05-29-v1-backend-conformance-audit.md |

## Where

src/main/java/com/example/mealprep/planner/domain/service/internal/composer/PlanComposer.java:205-439 (composeTransactional)

## What's wrong

LLD §Flow 1 (line 1211) and §Concurrency (line 1322) lock the rule: 'generatePlan ... is not @Transactional at the outermost — the AI calls in Stages C and D must run outside a transaction per style-guide §AI Service (Tier 1 decision: AI calls in-transaction = no).' But composeTransactional is annotated @Transactional(propagation = REQUIRED) (line 205) and calls stageCInvoker.pickOne(...) (line 292), phase2Augmenter.augment(...) (line 313) and adaptationService.runPlanTimeRefineJob(...) (line 373) all inside that open transaction. StageCInvokerImpl's own javadoc (lines 24-29) and Phase2AugmenterImpl's (lines 31-35) both assert 'the composer opens its persistence transaction only AFTER Stage C returns' / 'the composer owns the surrounding transaction boundary and calls this method outside it' — which is false. A slow/blocked LLM (up to the 20s Stage-C timeout plus Phase-2 plus N Stage-D adaptation jobs) now holds a DB connection and row locks open for the entire AI latency window.

## Recommendation

Restructure compose() so beam search + Stage C + Phase 2 + Stage D run with no active transaction, and only the final PlanPersister.persist + PlanGeneratedEvent publish run inside a short @Transactional block (the LLD's 'steps 12-13 are the only DB write boundary'). Then correct or remove the now-false 'no @Transactional / outside the tx' javadoc on StageCInvokerImpl and Phase2AugmenterImpl.
