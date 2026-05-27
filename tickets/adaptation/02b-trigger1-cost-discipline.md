# Ticket: adaptation — 02b Trigger-1 cost discipline

> **STATUS: IMPLEMENTED.** The Trigger-1 adapt-on-create gate now lives in
> `AdaptationImportListener.decideAndEnqueue(...)`, decided by `RecipeCreatedEvent.dataQuality()`
> (which already encodes the recipe's origin — so no separate bulk-count / origin signal was needed):
> - **USER_VERIFIED** (clean manual create) → a deterministic single-recipe pre-filter runs first:
>   it enqueues an **ASYNC** job ONLY when the recipe plausibly conflicts with the owner's hard
>   constraints (`HardConstraintFilterService.checkRecipe`) or — if its nutrition is already
>   computed — their nutrition targets (`NutritionQueryService.findRecipeIdsViolatingTargets`).
>   Otherwise it **SKIPs** entirely (no job, no LLM spend) — the common "nothing to adapt" case.
> - **IMPORTED / WEB_DISCOVERED / AI_GENERATED** (bulk / messy origin) → enqueue at **BATCH**
>   priority. No `JobReadyEvent` is published; the daily `BatchJobOrchestrator` cron processes them,
>   so bulk discovery/import seeding no longer fans out per-recipe immediate LLM calls.
>
> Plumbing: `AdaptationService.enqueueImportJob` gained a priority-aware overload
> `enqueueImportJob(ImportJobRequest, JobPriority)`; it publishes `JobReadyEvent` only for ASYNC
> (BATCH mirrors `enqueueDataModelChangeJobs`). The `USER` catalogue → `PENDING_CHANGE` / `SYSTEM` →
> `DIRECT` approval-policy rule is unchanged. v1 stays a silent pending-change (no new user-facing
> surface). The reusable cross-module affected-set query helpers this depended on landed separately
> (the same ones Trigger 3's filters use, and the budget/Trigger-3 affected-set work); this ticket
> reuses the per-recipe single-check variants of them. 02a already removed the operational harm
> (terminal-state leak, unbounded threads, wrong userId, supersession races).
>
> The three open questions are answered & implemented: (1) a clean manual `USER_VERIFIED` create does
> NOT auto-fire adaptation unless it actually conflicts; (2) bulk discovery/import seeding routes to
> BATCH (no per-recipe LLM fan-out); (3) silent pending-change for v1.
>
> Original framing (kept for context): captured during E2E stabilisation; mechanics fixed in 02a; XJ-02
> later confirmed the eager firing's cost — its per-create pending change collided with feedback's,
> forcing the supersession hardening.

## The observation

Trigger 1 ([adaptation-pipeline.md:774-778](../../lld/adaptation-pipeline.md)) enqueues a
`RECIPE_ADAPTATION` LLM job on **every** `RecipeCreatedEvent` — manual create, URL import, AI
generation, online discovery — with "outcome bias: usually NO_CHANGE." Three concerns:

1. **Cost / yield.** Most calls do nothing, but each costs an LLM round-trip (tokens, latency, $).
   Tellingly, **Trigger 3** (data-model change,
   [adaptation-pipeline.md:790](../../lld/adaptation-pipeline.md)) does the disciplined thing: a *cheap
   deterministic pre-filter* (recipes containing the new allergen / over budget / violating targets)
   selects the affected set **before** spending AI. Trigger 1 has no such pre-filter — shotgun where
   Trigger 3 is a scalpel.

2. **Bulk thundering-herd.** Discovery seeding, bulk URL import, and (future) household-merge catalogue
   import create many recipes fast → immediate per-recipe ASYNC fan-out. Trigger 3 routes bulk through
   `BATCH` priority (the daily `BatchJobOrchestrator`, batches of 50). Trigger 1 fires immediately per
   recipe. The BATCH machinery already exists; Trigger 1 just doesn't use it for bulk-origin creates.

3. **Appropriateness varies by source.** A clean `USER_VERIFIED` manual create (the user typed it
   deliberately) is a weak adaptation candidate; a messy `IMPORTED`/`WEB_DISCOVERED` recipe is a strong
   one. `dataQuality` (now on the event after 02a) is the natural gate. NOTE: SYSTEM/discovery
   adaptation is *legitimate* (quality cleanup of scraped recipes, not user-tailoring) — do **not**
   simply drop it; gate it on quality, not catalogue alone.

## Candidate design (for discussion — not locked)

- **Pre-filter before AI (USER recipes):** only enqueue when the recipe plausibly conflicts with the
  owner's hard constraints / soft preferences / budget (reuse the Trigger-3 affected-set helpers once
  they exist — they're currently stubbed to `emptySet`). Skip the LLM call entirely for the common
  "nothing to adapt" case.
- **Quality gate:** for SYSTEM/discovery, gate on `dataQuality` (only clean up low-quality imports).
- **BATCH for bulk:** route bulk-origin creates (discovery seeding, bulk import, merge) through the
  BATCH path rather than immediate per-recipe ASYNC.

## Why deferred

- It's a product/HLD decision (AI-spend vs adaptation coverage), not a correctness fix.
- It depends on the cross-module affected-recipe query helpers (preference/nutrition/provisions
  `QueryService` methods) that Trigger 3's filters also need — those are stubbed `emptySet` today and
  are their own work.
- 02a already removes the operational harm (terminal-state leak, unbounded threads, wrong userId).

## Open questions for the owner

1. Should a clean manual `USER_VERIFIED` create trigger adaptation at all, or only flag on a later
   data-model change?
2. Acceptable per-recipe LLM spend on bulk discovery seeding, or must that go BATCH/skip?
3. Is a user-facing "we adapted your imported recipe" surface wanted, or silent pending-change only?
