# Ticket: recipe/adaptation — P3 semantic clarifications (combined)

Low-priority items from [`design/frontend/pages/recipes.md` §8](../../design/frontend/pages/recipes.md),
[`recipe-detail.md` §11](../../design/frontend/pages/recipe-detail.md), and
[`activity.md` §8](../../design/frontend/pages/activity.md). Resolve item-by-item; tick + annotate.

## Items

1. **`previewToken` semantics drift** (recipes §8 Q5). LLD Flow 2 describes a signed 15-minute
   token validated on confirm; the shipped contract is a nullable opaque echo ("v1 keeps the flow
   stateless"). **Proposed:** pin the LLD text to the shipped stateless behaviour (doc-only).
2. **REJECTED → re-accept is legal in the shipped service** but `design/recipe-system.md` calls
   REJECTED terminal (recipe-detail §11 Q3). The page spec follows the code (re-accept offered).
   **Proposed:** amend the design doc (only SUPERSEDED is hard-terminal) — or add the guard if the
   owner prefers the HLD; default: amend the doc.
3. **Branch promote-to-standalone has no endpoint** (recipe-detail §11 Q4). The divergence > 0.7
   nudge is HLD-mandated ("promote copies the branch out as a new recipe with `forked_from`");
   `forkedFromRecipeId` exists but nothing writes it from a user action. v1 renders the nudge
   informational-only. **Proposed:** defer to v1.1 as its own feature ticket
   (`POST /recipes/{id}/branches/{branchId}/promote-to-recipe`); record the deferral in the HLD.
4. **`proposedDiff` / `userEdits` are contractually opaque JSON** (recipe-detail §11 Q6,
   activity §8 Q3). The red/green diff renderer is convention-coupled to the pipeline's shape; if
   it matches `RecipeDiffDto` the contract should say so. **Proposed:** publish the diff JSON
   schema (or a `$ref` to `RecipeDiffDto` with the pipeline's extensions) in
   `schemas/adaptation.yaml` — doc/schema-only.
5. **`destinationResult` is an untyped shell** (activity §8 Q4). v1 renders `actionTaken` only.
   **Proposed:** accept for v1; type it per-destination (oneOf) if/when the UI wants richer rows.
6. **No user-facing "all my pending changes" list** (activity §8 Q2). The top-3 budget hides a
   4th+ PENDING change until rank/expiry surfaces it; history is per-recipe only. Per the HLD
   budget this is intended. **Proposed:** accept; revisit only if users report "lost" suggestions.
7. **Character fingerprint invisible** (recipe-detail §11 Q7). `fingerprintOverride` is accepted
   on branch create but nothing reads the current fingerprint. **Proposed:** accept (server
   derives); note for future branch UX.

## Acceptance / DoD

- [ ] Each item: decision/pin recorded inline (option + date); doc/schema items landed
- [ ] Item 3 spun out as a feature ticket if/when scheduled

Squash-merge with: `docs(recipe,adaptation): P3 clarifications from recipe/activity page specs`
