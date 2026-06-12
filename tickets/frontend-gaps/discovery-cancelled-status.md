# Ticket: discovery — `CANCELLED` terminal status (cancel is currently a string contract) (P2)

## Summary

There is no CANCELLED state: cancelling a job flips it to **FAILED** with
`errorSummary = "cancelled by user"` — the UI distinguishes a cancel from a genuine failure only
by string-matching that sentence. The OpenAPI cancel description is additionally stale (still
describes the 01b "in-flight 422" limitation, while the shipped service honours RUNNING cancels
via an in-memory flag and returns 200 with the still-RUNNING DTO). Flagged by
[`design/frontend/pages/discover.md` §4 + §9 Q2](../../design/frontend/pages/discover.md).

**Fix:** add `CANCELLED` to `DiscoveryJobStatus`
([`schemas/discovery.yaml` line 3](../../src/main/resources/openapi/schemas/discovery.yaml)) and
finalise cancels as CANCELLED instead of FAILED. Refresh the cancel operation's contract text to
the shipped semantics.

## Behavioural spec

- QUEUED + cancel → atomically `CANCELLED` (was: FAILED) — 200.
- RUNNING + cancel → 200 with the still-RUNNING DTO (unchanged); when the runner stops between
  candidates it finalises the job as `CANCELLED`, keeping all counters and the ingested harvest
  (cancellation keeps the harvest — unchanged invariant).
- Terminal + cancel → 422 `discovery-job-already-terminal` (unchanged; CANCELLED is terminal too).
- `errorSummary` on cancelled jobs: keep writing "cancelled by user" for one release (belt and
  braces for any consumer still string-matching), but the status is now the contract.
- Genuine failures keep FAILED exclusively — "FAILED means failed" again.
- Existing rows: historical FAILED+"cancelled by user" rows are **not** migrated (audit history;
  note in the PR). Optional follow-up data migration if the owner wants clean stats.
- Check interplay: the orphan sweep and any `status in (terminal)` queries/indexes must include
  CANCELLED (grep for FAILED-set membership — runner finalisation, history queries, e2e steps).

### OpenAPI excerpt

```yaml
# schemas/discovery.yaml
DiscoveryJobStatus:
  enum: [QUEUED, RUNNING, SUCCEEDED, FAILED, PARTIAL, CANCELLED]
# paths/discovery.yaml — cancel: rewrite description to the shipped semantics
# (QUEUED → CANCELLED immediately; RUNNING → 200 still-RUNNING, finalises CANCELLED; terminal → 422)
```

## Edge-case checklist

- [ ] QUEUED cancel → CANCELLED, never picked up by a runner
- [ ] RUNNING cancel → eventual CANCELLED; `recipesIngested` preserved; partial results grid renders
- [ ] Double cancel while RUNNING → still one finalisation (the existing double-finalisation guard covers the new state)
- [ ] Cancel after terminal → 422 unchanged
- [ ] All-sources-down job → FAILED (not CANCELLED) — failure semantics untouched
- [ ] History/list queries return CANCELLED rows; status chip enum-complete in the contract

## Files this ticket touches

```
MOD   src/main/java/com/example/mealprep/discovery/domain/entity/... DiscoveryJobStatus    (enum)
MOD   src/main/java/com/example/mealprep/discovery/domain/service/internal/DiscoveryServiceImpl.java + DiscoveryJobRunner.java  (cancel finalisation)
MOD   src/main/resources/openapi/schemas/discovery.yaml + paths/discovery.yaml             (enum + stale cancel text)
MOD   src/main/resources/db/migration/...                                                  (only if a CHECK constraint enumerates statuses — verify)
MOD   src/test/java/com/example/mealprep/discovery/...                                     (cancel matrix re-asserted on the new status)
```

## Acceptance / DoD

- [ ] `verify` + `spotless` clean; CI green
- [ ] UI can drop the `errorSummary == "cancelled by user"` string match
- [ ] Cancel OpenAPI text matches shipped behaviour (the §9 Q2 staleness gone)

Squash-merge with: `feat(discovery): CANCELLED terminal job status (replaces FAILED+string cancel contract)`

**Not in scope:** the safety exclusions ticket
([`discovery-server-side-exclusions.md`](discovery-server-side-exclusions.md)); live progress
push (P3/v1.5).
