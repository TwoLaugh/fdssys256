# Ticket: planner — candidate plans & user pick — PRODUCT DECISION (P1)

## Summary

**HLD/contract contradiction; needs a product-owner ruling before any code.**

`design/meal-planner.md` Stage C says: *"the UI presents all 5 candidates with the LLM's
recommendation highlighted; override is logged as `chosen.source = 'user'`."* The shipped
contract disagrees: `POST /api/v1/plans/generate` returns **one** composed `PlanDto` — Stage C's
LLM picks from the internal top-5 server-side, the candidates are discarded, and nothing exposes
the top-N rollups or a pick verb. The mock's five-candidate grid is contract-divergent and now
sits behind a flag — [`design/frontend/pages/plan.md` §4c + §8 Q1](../../design/frontend/pages/plan.md).

This ticket is the decision record. **Pick one option below; the implementation (if any) is a
follow-up ticket sized after the ruling.**

## Options

### Option A — expose the candidates (honour the HLD)

- Stage B persists the top-5 candidate summaries (`scoreBreakdown` + `rollupSummary` + dinner
  line-up recipe ids per candidate — a compact projection, not 5 full plans) keyed to the
  generation attempt.
- `GET /api/v1/plans/{planId}/candidates` → the 5 summaries with the LLM's pick marked.
- `POST /api/v1/plans/{planId}/candidates/{candidateIndex}/pick` → re-composes the chosen
  candidate as the GENERATED plan (supersedes the LLM-picked one within the same generation, or
  re-writes the plan in place pre-accept — needs design), logs `chosen.source = 'user'` to the
  decision log.
- **Cost:** new table or candidate-blob column, retention sweep, a re-compose path that today
  doesn't exist, and lifecycle subtleties (what happens if the user picks after accepting?).
  Largest option by far.

### Option B — embed candidates in the generate response (middle path)

- `POST /generate` 201 carries `candidateSummaries[]` (same compact projection) alongside the
  composed `PlanDto`; a `POST /plans/{planId}/repick` with the candidate index swaps the
  GENERATED plan's content before accept. No persistence beyond the GENERATED window (repick
  only legal while GENERATED; candidates dropped on accept/reject).
- **Cost:** moderate — still needs candidate content retained server-side for the GENERATED
  window (the summaries alone can't rebuild a plan), so in practice Option B ≈ Option A with
  shorter retention.

### Option C — amend the HLD (bless the shipped behaviour)

- Single-result review stays; "Regenerate all" (fresh Idempotency-Key) is the alternative-seeking
  control. The HLD Stage C paragraph is rewritten: the LLM picks; the user's lever is
  accept / reject / regenerate. `chosen.source = 'user'` is logged only via regenerate-then-accept.
- **Cost:** zero backend; one HLD paragraph + the mock's flagged grid removed. Loses the
  five-up comparison UX the HLD promised.

## Recommendation (for the owner to confirm or override)

**Option C for v1, Option A as a v1.5 candidate.** Rationale: the comparison UX is unproven, the
single-result flow already shipped end-to-end (idempotent generate, review card, accept/reject),
and Option A's persistence + re-compose work is a multi-ticket feature competing with safety and
wiring gaps. The decision log already records the Stage C pick, so the audit story survives.

## Acceptance / DoD

- [ ] Product-owner decision recorded here (edit this file: chosen option + date + rationale)
- [ ] If A or B: follow-up implementation ticket(s) written with full behavioural spec
- [ ] If C: `design/meal-planner.md` Stage C paragraph amended; `plan.md` §8 Q1 resolved; the
      mock's flagged candidate grid deleted
- [ ] Decision-log entry (tickets/core/01 pattern) cross-referenced

Squash-merge with: `docs(planner): candidate-pick product decision (frontend-gaps)`

## What's NOT in scope

- Any implementation in this ticket.
- The re-opt suggestion diff preview — separate, already actionable:
  [`planner-reopt-suggestion-detail.md`](planner-reopt-suggestion-detail.md).
- Stage C reasoning exposure on `PlanDto` (plan.md §8 Q5) — P3, see
  [`planner-today-p3-clarifications.md`](planner-today-p3-clarifications.md).


---

## DECISION (2026-06-12)

**Ruling: amend the HLD for v1 (Option C / no-build).** Product owner chose to keep the shipped
contract: Stage C auto-picks server-side; candidates stay internal. Rationale: the generate →
review → accept/reject loop already gives the user a veto, candidate exposure adds a re-compose
path plus candidate persistence for marginal v1 value, and full-candidate generation would
multiply per-generation AI cost. `design/meal-planner.md` Stage C amended accordingly; candidate
exposure + user pick deferred to v2 (revisit if accept-rate telemetry shows users fighting the
auto-pick). Frontend: the mock's five-candidate grid is removed/flag-off permanently for v1
(`design/frontend/pages/plan.md` §8 Q1 resolved).
