# Ticket: recipe/adaptation — P3 semantic clarifications (combined)

Low-priority items from [`design/frontend/pages/recipes.md` §8](../../design/frontend/pages/recipes.md),
[`recipe-detail.md` §11](../../design/frontend/pages/recipe-detail.md), and
[`activity.md` §8](../../design/frontend/pages/activity.md). Resolve item-by-item; tick + annotate.

## Items

1. ✅ **`previewToken` semantics drift** (recipes §8 Q5). LLD Flow 2 describes a signed 15-minute
   token validated on confirm; the shipped contract is a nullable opaque echo ("v1 keeps the flow
   stateless"). **Proposed:** pin the LLD text to the shipped stateless behaviour (doc-only).
   **DONE (2026-06-13, doc-only):** `lld/recipe.md` Flow 2 pinned to the shipped stateless echo
   (preview + confirm paragraphs); the signed-cache token is explicitly reserved for a future
   server-side preview cache.
2. ✅ **REJECTED → re-accept is legal in the shipped service** but `design/recipe-system.md` calls
   REJECTED terminal (recipe-detail §11 Q3). The page spec follows the code (re-accept offered).
   **Proposed:** amend the design doc (only SUPERSEDED is hard-terminal) — or add the guard if the
   owner prefers the HLD; default: amend the doc.
   **DONE (2026-06-13, doc-only):** default taken — `design/recipe-system.md` §Substitution
   amended (only SUPERSEDED hard-terminal; `REJECTED → ACCEPTED` legal). Verified against
   `RecipeServiceImpl.acceptSubstitution` (guards 422 on SUPERSEDED alone).
3. ✅ **Branch promote-to-standalone has no endpoint** (recipe-detail §11 Q4). The divergence > 0.7
   nudge is HLD-mandated ("promote copies the branch out as a new recipe with `forked_from`");
   `forkedFromRecipeId` exists but nothing writes it from a user action. v1 renders the nudge
   informational-only. **Proposed:** defer to v1.1 as its own feature ticket
   (`POST /recipes/{id}/branches/{branchId}/promote-to-recipe`); record the deferral in the HLD.
   **DEFERRED (v1.1): a real feature, bigger than P3** — endpoint + copy semantics + provenance.
   Deferral recorded 2026-06-13 in `design/recipe-system.md` §Branch divergence; spin the feature
   ticket when v1.1 is scoped.
4. ✅ **`proposedDiff` / `userEdits` are contractually opaque JSON** (recipe-detail §11 Q6,
   activity §8 Q3). The red/green diff renderer is convention-coupled to the pipeline's shape; if
   it matches `RecipeDiffDto` the contract should say so. **Proposed:** publish the diff JSON
   schema (or a `$ref` to `RecipeDiffDto` with the pipeline's extensions) in
   `schemas/adaptation.yaml` — doc/schema-only.
   **DONE (2026-06-13, schema-doc):** `schemas/adaptation.yaml` now names the canonical shape —
   the `RecipeDiffDto` change-array family (`ingredientChanges[]` etc., per `schemas/recipe.yaml`)
   with LLM annotation extensions tolerated; pins `ingredientChanges[].to.ingredientMappingKey`
   as the stable load-bearing path (it feeds the server's safety filter). A hard `$ref` was
   deliberately NOT used: the LLM leg's `refinedDiff` may extend entries, and a strict schema
   would false-negative valid rows.
5. ✅ **`destinationResult` is an untyped shell** (activity §8 Q4). v1 renders `actionTaken` only.
   **Proposed:** accept for v1; type it per-destination (oneOf) if/when the UI wants richer rows.
   **DONE (2026-06-13, decision):** accepted as proposed — no contract change; oneOf typing only
   when a page asks for richer rows.
6. ✅ **No user-facing "all my pending changes" list** (activity §8 Q2). The top-3 budget hides a
   4th+ PENDING change until rank/expiry surfaces it; history is per-recipe only. Per the HLD
   budget this is intended. **Proposed:** accept; revisit only if users report "lost" suggestions.
   **DONE (2026-06-13, decision):** accepted as proposed — intended HLD behaviour, no change.
7. ✅ **Character fingerprint invisible** (recipe-detail §11 Q7). `fingerprintOverride` is accepted
   on branch create but nothing reads the current fingerprint. **Proposed:** accept (server
   derives); note for future branch UX.
   **DONE (2026-06-13, decision):** accepted as proposed — server-derived; surface it only with a
   future branch-management UX.

## Acceptance / DoD

- [x] Each item: decision/pin recorded inline (option + date); doc/schema items landed
- [x] Item 3 spun out as a feature ticket if/when scheduled — deferral recorded in the HLD;
      ticket to be cut at v1.1 scoping

Squash-merge with: `docs(recipe,adaptation): P3 clarifications from recipe/activity page specs`
