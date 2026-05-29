# AUDIT planner/planner-2 — No single-flight generation lock; ConcurrentGenerationInProgressException is dead code

| field | value |
|---|---|
| Severity | **HIGH** |
| Module | planner |
| Dimension | MISSING_CAPABILITY |
| Verification | verified-confirmed |
| **Triage (edit me)** | **DESCOPE?** |
| Source | design/audits/2026-05-29-v1-backend-conformance-audit.md |

## Where

src/main/java/com/example/mealprep/planner/domain/service/internal/composer/PlanComposer.java:174-195 (compose); exception at exception/ConcurrentGenerationInProgressException.java

## What's wrong

LLD §Flow 1 step 1 (line 1213) and §Concurrency (line 1323) require 'Acquire single-flight lock: core.LockService.tryLock("planner:generate", "%s:%s".formatted(householdId, weekStartDate)). If lock unavailable -> ConcurrentGenerationInProgressException (409)', and §Failure Modes (line 1343) reaffirms it. The implementation injects no LockService anywhere in the planner package (grep for LockService/tryLock finds only the unused exception class and a javadoc mention) and instead relies solely on a 5-minute in-memory Caffeine Idempotency-Key cache (PlanComposer lines 107-153) — which requires the client to send a header and does not prevent two concurrent header-less 'regenerate' clicks from both running the full A→D pipeline against the same (household, week). ConcurrentGenerationInProgressException is mapped to 409 in PlannerExceptionHandler but is never thrown.

## Recommendation

Either wire core.LockService.tryLock around compose() per the LLD (and throw ConcurrentGenerationInProgressException on contention), or formally record the idempotency-cache substitution as an accepted v1 design change and delete the dead exception + handler. As-is the design's stated concurrency guarantee is absent.

## Scope decision needed

MISSING_CAPABILITY: the design calls for it but it is absent/stubbed. Decide **FIX** for v1, or **DESCOPE** and document the deferral in the LLD/HLD so the v1 test pass does not assert it.
