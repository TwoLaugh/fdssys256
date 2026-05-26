# Ticket: adaptation — 02b Trigger-1 cost discipline (STUB — design decision, needs product input)

> **STATUS: DECIDED — GATE IT (deferred build).** Owner approved the "Candidate design" below:
> adapt-on-create gets a deterministic pre-filter before the LLM call (only adapt a USER recipe when
> it plausibly conflicts with the owner's hard constraints / soft prefs / budget), a quality gate for
> SYSTEM/discovery recipes, and bulk-origin creates route through BATCH. **Implementation is deferred**
> until the cross-module affected-set query helpers exist (the same ones Trigger 3's filters need —
> currently stubbed `emptySet`); build this together with that work. 02a already removed the
> operational harm (terminal-state leak, unbounded threads, wrong userId, supersession races). The
> three open questions below are answered: (1) a clean manual `USER_VERIFIED` create should NOT auto-
> fire adaptation — only on a later data-model change or a low-quality import; (2) bulk discovery
> seeding must NOT fire per-recipe LLM calls — gate/BATCH it; (3) silent pending-change for v1 (no
> dedicated "we adapted your import" surface yet).
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
