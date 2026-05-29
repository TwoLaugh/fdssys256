# AUDIT discovery/discovery-2 — DiscoveryJobRunner class javadoc still describes the removed 'skeleton mode' saveImportedRecipe stub

| field | value |
|---|---|
| Severity | **HIGH** |
| Module | discovery |
| Dimension | STALE_DOC |
| Verification | verified-confirmed |
| **Triage (edit me)** | **DOC-ONLY** |
| Source | design/audits/2026-05-29-v1-backend-conformance-audit.md |

## Where

src/main/java/com/example/mealprep/discovery/domain/service/internal/DiscoveryJobRunner.java:64-71

## What's wrong

The class javadoc states: "This implementation ships in skeleton mode: post-hard-constraint, post-fingerprint-dedup candidates write an EXTRACTION_FAILED row with error_class = 'saveImportedRecipeNotYetImplemented' and the job continues. The 5-minute follow-up discovery-01d-real-handoff flips the stub once recipe-01l merges." The shipped fetchPhase (lines 628-698) actually calls recipeWriteApi.saveImportedRecipe(importData), handles newlyCreated vs dedup, writes SUCCESS rows with recipe_id, and publishes DiscoveryRecipeIngestedEvent — the real hand-off is wired. The javadoc is dangerously misleading to a reader debugging the live ingest path.

## Recommendation

Rewrite the javadoc to describe the real saveImportedRecipe hand-off (newlyCreated → SUCCESS + event; !newlyCreated → DUPLICATE; RuntimeException → EXTRACTION_FAILED). Remove the 'skeleton mode' / 'saveImportedRecipeNotYetImplemented' paragraph entirely.
