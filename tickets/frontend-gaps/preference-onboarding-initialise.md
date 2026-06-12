# Ticket: preference — onboarding steps 3–4 have no REST backing (initialise gap, P1)

## Summary

**The onboarding wizard cannot complete steps 3 (allergies & dietary identity) and 4
(lifestyle & slots) against the current contract** —
[`design/frontend/pages/onboarding.md` §5 G1](../../design/frontend/pages/onboarding.md), flagged
as the wizard's blocker. Both `PUT /api/v1/preferences/hard-constraints` and
`PUT /api/v1/preferences/lifestyle-config` return **404 until an internal `initialise` runs**, and
neither initialise is exposed over REST.
[`LifestyleConfigController.java` lines 36–39](../../src/main/java/com/example/mealprep/preference/api/controller/LifestyleConfigController.java)
says so explicitly: *"the initialise flow is intentionally NOT exposed on the REST surface here —
the onboarding wizard ticket is responsible for calling `LifestyleConfigUpdateService#initialise`
during the wizard's submit step"* — i.e. a server-side onboarding endpoint was assumed and never
built. `initialiseHardConstraints` is currently reachable only via the health-directive SPI and
the test-profile e2e seeder.

## Decision required — three options, with a recommendation

| Option | Shape | Trade-offs |
|---|---|---|
| **A. Expose `POST …/initialise`** for both aggregates | Two new endpoints the wizard calls before its first PUT | Honest, but doubles the wizard's write calls and invents a REST verb only onboarding uses |
| **B. Upsert-on-first-PUT** (recommended) | `PUT` with `expectedVersion: 0` creates the aggregate when absent (delegating to the existing `initialise` internals first, then applying the update) | No new endpoint; matches the budget upsert precedent (`PUT /provisions/budget` — insert and update both 200); the onboarding spec already mocks exactly this (`data-gap="G1"`) |
| **C. Seed on `UserRegisteredEvent`** | Listener initialises both rows at registration (the event exists with **no listener**) | Smallest wizard change, **but breaks the wizard's resume logic**: onboarding §4 probes `GET` 404 → "jump to step 3/4", and the preferences page renders "finish onboarding" empty states on 404 — both rely on absent-until-touched |

**Recommendation: Option B.** `expectedVersion: 0` + aggregate absent → initialise-then-apply in
one transaction, return 200 (or 201 + Location if preferred — pin it). `expectedVersion: 0`
against an *existing* aggregate stays a 409 (stale version), so the create path cannot clobber.
404-until-touched survives for GETs, keeping the wizard's §4 probe-chain and the /preferences
empty states intact.

**Unblocks:** onboarding steps 3 & 4 (the wizard's only blocker); the `/preferences` editors for a
fresh user who skipped onboarding.

## Behavioural spec (Option B)

- `PUT /preferences/hard-constraints` with `expectedVersion: 0`, no aggregate →
  `initialiseHardConstraints(userId)` + apply the full-replace document → 200 with version 1.
- `PUT /preferences/lifestyle-config` with `expectedVersion: 0`, no aggregate → same via
  `LifestyleConfigUpdateService#initialise`.
- `expectedVersion: 0`, aggregate exists → 409 (unchanged optimistic-lock semantics).
- `expectedVersion > 0`, no aggregate → 404 (unchanged — a stale client, not a create intent).
- GAP-04 Tier-1-removal interstitial logic: a create-path PUT has no prior constraints, so
  `confirmTier1Removals` never triggers on first write (assert in tests).
- Change-history/audit rows record the create as origin `ONBOARDING`/`USER` consistent with the
  existing origin-tracking pattern (verify the enum the initialise internals already stamp).
- Update both controllers' javadoc + OpenAPI descriptions (the 404 description currently says
  "until initialise has been called" — now "until first write").

## Edge-case checklist

- [ ] First PUT with `expectedVersion: 0` creates + applies atomically (one tx; no window where the aggregate exists with defaults only)
- [ ] Concurrent double-submit of the create PUT → one wins, the loser gets 409 (DB unique on userId; catch + 409)
- [ ] `expectedVersion: 0` on existing aggregate → 409; `expectedVersion: 3` on absent → 404
- [ ] GAP-04 interstitial not triggered on create-path (no removals possible)
- [ ] Wizard resume probes unaffected: GET still 404 before first write
- [ ] Health-directive SPI initialise path still works (no regression for directive-driven creation)
- [ ] e2e seeder unaffected

## Files this ticket touches

```
MOD   src/main/java/com/example/mealprep/preference/api/controller/HardConstraintsController.java     (javadoc + create-path)
MOD   src/main/java/com/example/mealprep/preference/api/controller/LifestyleConfigController.java     (javadoc + create-path)
MOD   src/main/java/com/example/mealprep/preference/domain/service/internal/...                       (update services: initialise-then-apply on absent + expectedVersion 0)
MOD   src/main/resources/openapi/paths/preference.yaml                                                (PUT descriptions + create-path response)
MOD   src/test/java/com/example/mealprep/preference/...                                               (create-path ITs incl. race + GAP-04 non-trigger)
```

## Dependencies

- None. Sibling: onboarding step 5 (`POST /nutrition/targets/initialise`) already exists — G2's
  request-shape grumble is P3 ([`preference-p3-clarifications.md`](preference-p3-clarifications.md)).

## Acceptance / DoD

- [ ] `verify` + `spotless` clean; CI green; all edge cases ticked
- [ ] An onboarding-shaped IT: fresh user → PUT hard-constraints (v0) → PUT lifestyle-config (v0) → both GETs return the documents
- [ ] Frontend `data-gap="G1"` tag removable (mock upsert becomes the real contract)

Squash-merge with: `feat(preference): upsert-on-first-PUT for hard-constraints + lifestyle-config (onboarding G1)`

## What's NOT in scope

- Stored onboarding progress / resume marker (G3 — P3, probes are acceptable).
- `targets/initialise` request-shape cleanup (G2 — P3).
- Any wizard frontend work.
