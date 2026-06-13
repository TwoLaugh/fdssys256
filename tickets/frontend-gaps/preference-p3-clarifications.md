# Ticket: preference — P3 semantic clarifications (combined)

Low-priority items from [`design/frontend/pages/preferences.md` §8](../../design/frontend/pages/preferences.md)
and [`onboarding.md` §5](../../design/frontend/pages/onboarding.md). Resolve item-by-item; tick +
annotate.

## Items

1. ✅ **Server re-stamp of document scalars on manual override** (preferences §8 Q1).
   `UpdateTasteProfileRequest.document` includes server-managed scalars (`version`,
   `basedOnFeedbackCount`, `feedbackCursor`, `lastUpdated`); the contract doesn't say whether the
   server re-stamps or trusts them. **Proposed:** enforce server-side re-stamp on
   `applyManualOverride` (ignore client values) + document it — small code + doc.
   **DONE (2026-06-13, small code + doc):** `version`/`lastUpdated` were already re-stamped;
   `basedOnFeedbackCount`/`feedbackCursor` were trusted from the client — now copied from the
   entity (the server-side truth) in `TasteProfileServiceImpl.applyManualOverride`, so the JSONB
   copy can no longer drift from the delta-pipeline bookkeeping. Documented on the
   `updateTasteProfile` operation; unit-tested
   (`applyManualOverride_reStampsServerManagedScalars_fromEntityNotClient`).
2. ✅ **`refresh-now` has no completion signal** (preferences §8 Q2). No job id/status endpoint; a
   legitimate "no change" outcome (three-event rule) is indistinguishable from "still running".
   v1 ships poll-with-timeout. **Proposed:** accept for v1; the status surface rides the SSE/push
   channel (backlog task #172) rather than a bespoke endpoint.
   **DONE (2026-06-13, decision):** accepted as proposed — poll-with-timeout for v1; completion
   signal rides SSE (task #172), no bespoke endpoint.
3. ✅ **Manual-override PUT documents no 422 for the token budget** (preferences §8 Q3). The
   2500-token `TasteProfileBudgetGuard` is specified on the delta path only; a user-pasted
   oversized document is unspecified. **Proposed:** run the guard on `applyManualOverride`, return
   422, document it — small code + contract.
   **DONE (2026-06-13, small code + contract):** `budgetGuard.enforce` now runs on the stamped
   document before any write (throws the existing 422-mapped
   `TasteProfileBudgetExceededException`) and stamps `lastTokenEstimate` like the delta path; 422
   response added to the `updateTasteProfile` operation. Unit-tested
   (`applyManualOverride_oversizedDocument_throwsBudgetExceeded_andWritesNothing`).
4. ✅ **Profile-metadata REST never shipped** (preferences §8 Q4). LLD lists
   `GET/PUT /preferences/profile-metadata` (age group, portion scale) + `/soft-bundle`; the page
   spec dropped the portion-scale control. **Proposed:** confirm the drop for v1 (reconcile the
   LLD's REST table); ship the endpoints only if product wants the control back.
   **DONE (2026-06-13, doc-only):** drop confirmed (verified: no such endpoints in the codebase);
   `lld/preference.md` REST table reconciled — rows struck with a dated note; soft-bundle stays
   in-process only.
5. ✅ **Onboarding G2 — `targets/initialise` reuses `UpdateTargetsRequest`** (onboarding §5 G2): the
   create call sends a meaningless `expectedVersion` and the full aggregate. Cosmetic.
   **Proposed:** accept; optionally relax `expectedVersion` to nullable on the initialise
   operation's description (doc-only).
   **DONE (2026-06-13, doc-only):** accepted; the `initialiseNutritionTargets` operation
   description now states `expectedVersion` is ignored on this create path (send 0 — the shared
   schema keeps the field required, so true nullability was not relaxed).
6. ✅ **Onboarding G3 — no resume marker** (onboarding §5 G3): the wizard probes 3–4 GETs per mount.
   **Proposed:** accept (probe-derived state is the design); revisit only if product wants a
   stored wizard state.
   **DONE (2026-06-13, decision):** accepted as proposed — probe-derived state is the design.

## Acceptance / DoD

- [x] Each item: decision recorded inline; items 1 and 3 (small code) landed or spun out — both
      landed in this PR (`TasteProfileServiceImpl` + tests + OpenAPI)
- [x] LLD REST table reconciled for item 4

Squash-merge with: `docs(preference): P3 clarifications from preferences/onboarding page specs`
