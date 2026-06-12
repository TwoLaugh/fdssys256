# Ticket: planner/today — P3 semantic clarifications (combined)

Low-priority contract clarifications and product calls from
[`design/frontend/pages/plan.md` §8](../../design/frontend/pages/plan.md) and
[`today.md` §8](../../design/frontend/pages/today.md). None blocks wiring; each needs either a
doc pin, a product nod, or a small deferred change. Resolve item-by-item; tick + annotate inline.

## Items

1. **Resolution options have no apply endpoint** (plan §8 Q4). `ResolutionOptionDto.key` is opaque
   ("drop_protein_floor_to_160"); the HLD implies one-tap application, v1 deep-links to the owning
   settings page. **Proposed:** accept deep-links for v1; pin in `lld/planner.md` that `key` is
   display-only until a v2 apply verb exists.
2. **Stage C reasoning not user-visible** (plan §8 Q5). The mock's "Why this plan" card has no
   contract source (reasoning is decision-log/admin only). **Proposed:** product call — either
   drop the card (cheapest) or add a nullable user-grade `reasoningSummary` to `PlanDto` written
   at compose time. Default: drop for v1.
3. **`POST /generate` semantics against an ACTIVE week with `forceRegenerateIfActive=false`**
   (plan §8 Q6) are not pinned in the contract docs (409? parallel GENERATED gen?). The UI assumes
   a new GENERATED generation that supersedes only on accept. **Proposed:** verify shipped
   behaviour, then pin it in `paths/planner.yaml` description — doc-only unless behaviour is
   surprising.
4. **Slot-eaten dual-write** (today §8 Q1). "Mark eaten" = planner PATCH + nutrition confirm, no
   transaction (Flow-4 auto-confirm leg deferred by `design/technical-architecture.md`). Failure
   between calls leaves EATEN+PENDING, repairable on /nutrition (confirm is idempotent).
   **Proposed:** accept for v1 (already the documented order: planner first); the composed
   operation or event fan-out leg is the v1.5 item. Also decide whether "Mark cooked" should fire
   `POST /provisions/cook-event` from Today (pantry deduction) — currently unwired from any page.
5. **Skip semantics across the two machines** (today §8 Q2). Planner SKIPPED is terminal; intake
   skip zeroes contribution; skipping on /nutrition only leaves the planner slot PLANNED.
   **Proposed:** product ruling that Today's paired Skip is the sanctioned path; document the
   divergence in both page specs + LLDs.
6. **Planner CUSTOM/SNACK slots have no intake row** (today §8 Q3). Nutrition pre-fill covers
   BREAKFAST/LUNCH/DINNER/SNACKS; CUSTOM slots get planner buttons but no confirm target.
   **Proposed:** pin the join rule (CUSTOM → no intake action; planner SNACK ↔ nutrition SNACKS
   day-bucket) in the nutrition LLD; revisit if CUSTOM slots become nutrition-relevant.
7. **Batch portion progress not derivable** (today §8 Q4). Slots link by `batchCookSessionId` but
   no cooked/consumed counter exists; the mock's "portion 3 of 5" degrades to a "batch-cooked"
   tag. **Proposed:** accept the tag for v1; a per-session counter (provisions portions row is the
   natural source) is a v1.5 enrichment.

## Acceptance / DoD

- [ ] Each item: decision/pin recorded inline above (option + date); doc-only items landed as doc PRs
- [ ] Items needing code spun out as their own tickets once accepted

Squash-merge with: `docs(planner): P3 clarifications from plan/today page specs`
