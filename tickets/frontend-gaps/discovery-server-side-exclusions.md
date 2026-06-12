# Ticket: discovery — SAFETY: server-side injection of hard-constraint exclusions (P1)

## Summary

⚠️ **SAFETY GAP — the deterministic allergy filter on user discovery jobs is client-trusted.**

`DiscoveryConstraints.mustExcludeIngredientMappingKeys` is the hard-constraint snapshot applied as
the **deterministic second hard-filter pass** after extraction — the safety net the AI filter is
never trusted to enforce. But the LLD assigns *populating* it to the **caller**
([`DiscoveryConstraints.java` line 10](../../src/main/java/com/example/mealprep/discovery/api/dto/DiscoveryConstraints.java):
"carries the hard-constraint snapshot computed by the caller"). For `USER_INITIATED` jobs the
caller is the **frontend** — it must read `GET /preferences/hard-constraints` and translate to
mapping keys client-side. A buggy, stale, or malicious client sending an **empty list ingests
allergy-violating recipes into the system catalogue**, where the planner can schedule them.
Flagged by [`design/frontend/pages/discover.md` §3 + §9 Q3](../../design/frontend/pages/discover.md)
("a client-trust hole for a safety filter").

**Fix:** the server computes the caller's hard-constraint exclusion snapshot at enqueue time for
`USER_INITIATED` jobs and **unions** it with whatever the client sent. The client list becomes an
additive extra ("also exclude mushrooms this time"), never the safety baseline.

## Behavioural spec

- On `POST /api/v1/discovery/jobs` with `trigger = USER_INITIATED`:
  1. Resolve the caller's hard constraints via the preference module's in-process read seam (the
     same `HardConstraintFilterService` / hard-constraints snapshot the planner and adaptation
     callers use — reuse, don't re-derive).
  2. Translate to pre-normalised mapping keys (`core.IngredientMappingKeys.normalise`, core-03 —
     the same normalisation the validator already asserts on inbound keys).
  3. `effective = serverSnapshot ∪ clientProvidedKeys` (deduplicated, normalised) — frozen into
     the persisted `DiscoveryConstraints` snapshot. Constraint changes mid-job still do not
     retroactively alter the search (unchanged invariant).
- `COLD_START` / `SCHEDULED` triggers: **verify** these callers (planner cold-start, weekly sweep)
  already pass a server-computed snapshot; if they route through the same enqueue path, the union
  is a no-op-safe hardening for them too — apply uniformly unless a caller owns a different user
  context.
- The job DTO's constraints recap (`constraints.*` re-rendered read-only on the job card) now
  shows the effective union — the user sees their allergy keys were applied.
- Log (INFO) when the server snapshot adds keys the client omitted — this is the trust hole
  closing; it should be observable.

## Edge-case checklist

- [ ] Client sends empty/null `mustExcludeIngredientMappingKeys` → job still carries the user's full hard-constraint snapshot
- [ ] Client sends extra keys → union (client keys kept, server keys added), no duplicates
- [ ] User with no hard constraints → empty server snapshot; client keys pass through unchanged
- [ ] Keys normalised before union ("Chicken Breast" client key merges with "chicken breast" server key)
- [ ] Snapshot frozen at enqueue: editing hard constraints mid-job does not change the running job's filter
- [ ] The persisted job's constraints (and the GET job DTO recap) reflect the union
- [ ] HARD_CONSTRAINT_VIOLATION scrape-log rows still produced against the union (deterministic pass unchanged)
- [ ] No module-boundary violation: discovery reads preference via its public API only (ArchUnit/ModuleBoundaryTest)

## Files this ticket touches

```
MOD   src/main/java/com/example/mealprep/discovery/domain/service/internal/DiscoveryServiceImpl.java   (enqueue path: snapshot + union)
NEW   src/main/java/com/example/mealprep/discovery/domain/service/internal/HardConstraintSnapshotAssembler.java   (or fold into the impl — preference read + key translation)
MOD   src/main/java/com/example/mealprep/discovery/api/dto/DiscoveryConstraints.java                   (javadoc: server-unioned for user jobs)
MOD   src/main/resources/openapi/paths/discovery.yaml + schemas/discovery.yaml                         (field description: client keys are additive; server injects the caller's snapshot)
MOD   src/test/java/com/example/mealprep/discovery/...                                                 (union matrix ITs incl. the empty-client-list attack case)
```

## Dependencies

- **Hard:** preference module's hard-constraints read seam (shipped); `core.IngredientMappingKeys`
  (core-03, shipped).
- Coordinate with the existing hard-constraint→mapping-key translation used by
  planner/adaptation — if none exists as a reusable unit, extracting one is in scope here (it is
  the safety-critical translation and should live once).

## Acceptance / DoD

- [ ] `verify` + `spotless` clean; CI green; all edge cases ticked
- [ ] **The attack case is a test:** user with a peanut allergy, client sends `[]`, a seeded
      peanut recipe page → scrape log shows HARD_CONSTRAINT_VIOLATION, nothing ingested
- [ ] Frontend no longer required to compute the snapshot (discover.md §3 row updated by the
      frontend follow-up; client translation code becomes optional sugar)

Squash-merge with: `feat(discovery): server-side hard-constraint exclusion snapshot on user discovery jobs (safety)`

## What's NOT in scope

- The user source-disable endpoint → [`discovery-user-source-disable.md`](discovery-user-source-disable.md).
- CANCELLED status → [`discovery-cancelled-status.md`](discovery-cancelled-status.md).
- Retro-scanning already-ingested recipes against constraints (catalogue hygiene — flag for the
  owner if wanted; the planner's own hard filter still protects plans).
