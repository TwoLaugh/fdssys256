# End-to-End Testing — Strategy & Decisions Log

> **Purpose.** This is the canonical reference for how we are building the E2E test suite, and *why*. It exists so we can periodically check that the work (the pathway catalogue, the Gherkin features, the test harness) is still following the decisions we agreed. If something in the catalogue or tests contradicts this doc, one of them is wrong — reconcile before proceeding.
>
> **Status:** Stage 1 (pathway catalogue) COMPLETE — awaiting gap triage, then Stage 2. Last updated 2026-05-24.

---

## 1. Approach — Specification by Example (BDD)

We define behaviour from the **HLDs (the contract)**, *before and independently of* the implementation. This is deliberate:

- Tests written from the code tend to just mirror the code's bugs. Tests written from the spec catch the gap between intended and actual behaviour.
- The act of enumerating every pathway **surfaces HLD gaps and contradictions** (already happening — see §7).
- The behavioural catalogue **doubles as the frontend spec** — the frontend will be built from the same action/pathway definitions, so the catalogue must be exhaustive on the *action space*, not just on tested scenarios.

### Three stages
1. **Stage 1 — Pathway catalogue (code-blind).** Read every HLD, emit an exhaustive, code-agnostic catalogue of user pathways. No endpoints, no class names, no DB tables. *(This folder: `e2e/pathways/`.)*
2. **Stage 2 — Gherkin + endpoint mapping.** Distil a risk-prioritised subset of the catalogue into `.feature` files (the *what*) and write step definitions that call the real API (the *how*).
3. **Stage 3 — Run.** Execute against a prod-parity stack, wire into CI.

### Catalogue vs test set (important distinction)
- The **catalogue** is *exhaustive* — every action and combination the HLDs allow (this is the frontend contract).
- The **executable test set** is a *risk-prioritised subset* (equivalence partitioning + the high-value happy/error/edge cases). We never test every combination; that way lies thousands of brittle tests.

---

## 2. Tooling

**Cucumber-JVM (Gherkin `.feature` files) + REST-assured**, run under JUnit.

- `.feature` files = the code-agnostic Stage-1 scenarios — readable by non-engineers, double as frontend spec.
- Step definitions (REST-assured) = the only layer that knows HTTP/JSON (Stage 2).
- `Scenario Outline` + data tables fold combinations compactly; tags (`@smoke`, `@critical`, `@error`, `@pending`, `@soak`) select subsets.

**Why not Karate:** Karate embeds the HTTP calls inside the scenario, coupling the behavioural definition to the API — which breaks the "define without referencing code" property and weakens the catalogue as a frontend spec.

---

## 3. Environment

- **Prod-parity `docker-compose` stack**: same Postgres 16 + pgvector, the real application, real external dependencies. As close to production as practical.
- **CI is the source of truth** for the gating run (the local Docker VM has historically been memory-constrained). Local execution is opt-in if Docker memory is raised.
- **Only the AI is faked.** A deterministic test double returns canned, valid-shaped AI responses. USDA, OpenFoodFacts, live web fetch, and (later) Tesco are **real**.
  - *Why:* E2E tests the plumbing (does the flow route/persist/recalc), not the AI's answer quality. A live model makes the suite non-deterministic → flaky → untrustworthy as a gate. AI quality is a separate **eval** activity. Prod uses OpenAI, so the suite must stay provider-agnostic anyway.
  - *Live-AI confidence:* a separate, **opt-in, non-gating** smoke run may hit a real model; it never blocks CI.

---

## 4. Suite design — one test set, two run modes

The **same** feature files run two ways; we choose per-run:

| Mode | Cleanup | Purpose |
|---|---|---|
| **Clean** | teardown per scenario | Isolated regression gate — parallelisable, failures localise cleanly |
| **Soak** (`@soak`) | none — state accumulates | Emergent-bug discovery — uniqueness collisions, pagination-at-scale, lock contention, ordering, "real-world messiness" |

This works only if scenarios obey three rules (enforced in the catalogue and step defs):

1. **Self-contained data** — each scenario creates its own data (fresh user with a random handle, its own recipes). No scenario assumes a globally clean DB.
2. **Self-scoped assertions** — assert on *this scenario's* data ("*this user's* audit log has 1 entry"), never on global counts ("the table has 1 row"). *(Global-count assertions have repeatedly caused false failures.)*
3. **Toggleable cleanup hook** — a Cucumber `@After` gated by a flag; clean mode runs it, soak mode skips it.

> Caveat for soak mode: a mid-chain failure cascades and can't always be localised — that is expected and is the point of the mode.

---

## 5. Catalogue structure & conventions (Stage 1)

One file per domain in `e2e/pathways/`. Every file follows the structure of `recipe.md` (the template):

1. **Domain summary** — what it is, which of the 3 loops it serves.
2. **Actors** — user + system actors per the HLD.
3. **Action space** — flat, **exhaustive** list of every user-facing action the HLD permits (the frontend backbone). verb-phrase + one-line + HLD ref.
4. **State models** — entity lifecycles with **illegal transitions explicitly listed** (these seed error pathways).
5. **Pathways** — `### <PREFIX>-NN` entries with: Category (Happy / Alternate / Error / Edge), Actor, Preconditions, Action (behaviour only), Expected outcome, **Variations** (combinations folded in, not duplicated), HLD ref, Notes (external deps, cross-module touchpoints). One **flagship cross-module journey** is the last pathway per domain.
6. **`[HLD-GAP]` appendix** — consolidated table of every gap/ambiguity/contradiction found in that domain.

### Conventions
- **ID prefixes:** `RCP-` recipe, `PREF-` preference, `NUT-` nutrition, `PROV-` provisions, `PLAN-` planner, `FEED-` feedback, `GROC-` grocery, `AUTH-` auth, `HH-` household, `NOTIF-` notification.
- **Scope = the full HLD surface**, including capabilities the backend only partially implements today (so the frontend spec is complete); not-yet-buildable scenarios get tagged `@pending` in Stage 2.
- **Code-blind:** Stage 1 reads only `design/*.md`. No `src/`, `lld/`, or `tickets/`.

---

## 6. HLD gaps — handling

Gaps are **flagged, not resolved**, during catalogue generation (inline `[HLD-GAP]` + per-domain appendix). After all domains are catalogued, the full set is **aggregated into one triage list** (`e2e/pathways/hld-gaps.md`) for a single batch review — rather than deciding each one piecemeal mid-generation. Resolutions then feed back into the HLDs and/or the frontend spec.

---

## 7. Decisions log

| # | Date | Decision | Rationale |
|---|---|---|---|
| D1 | 2026-05-24 | BDD / specification-by-example; catalogue defined code-blind from HLDs first | Avoid mirroring impl bugs; surface HLD gaps; catalogue doubles as frontend spec |
| D2 | 2026-05-24 | Cucumber-JVM + REST-assured | Clean what/how split; readable; frontend-spec-able; Karate couples scenario to API |
| D3 | 2026-05-24 | Prod-parity docker-compose; CI = gating source of truth; local opt-in | Realism; local Docker VM has been memory-constrained |
| D4 | 2026-05-24 | Fake **only** the AI (deterministic double); all other external deps real | E2E tests plumbing not AI quality; determinism; provider-agnostic (prod = OpenAI). Live-AI = separate non-gating smoke |
| D5 | 2026-05-24 | One feature set, two run modes (clean gate + soak), via self-contained data + self-scoped assertions + toggleable cleanup | Best-of-both; self-contained scenarios are best practice regardless |
| D6 | 2026-05-24 | Catalogue conventions: per-domain files, ID prefixes, exhaustive action space, state-model illegal transitions seed errors, variations-folding, one flagship cross-module journey/domain, consolidated gap appendix | Consistency + reviewability; keeps entry count manageable |
| D7 | 2026-05-24 | Catalogue exhaustive over full HLD surface; executable test set is a risk-prioritised subset (`@pending` for not-yet-buildable) | Frontend needs the full action space; tests need to stay maintainable |
| D8 | 2026-05-24 | HLD gaps aggregated + triaged in one batch at end of Stage 1, not decided piecemeal | User preference; the full set informs better rulings |

---

## 8. Stage 1 progress tracker

**Stage 1 COMPLETE** (2026-05-24). Totals: ~292 actions, ~358 domain pathways + 6 cross-journeys, 88 unique HLD gaps (from ~217 raw flags).

| Domain | File | Actions | Pathways | Gaps | Status |
|---|---|---|---|---|---|
| Recipe (+ web import / discovery / adaptation) | `pathways/recipe.md` | 44 | 60 | 23 | ✅ |
| Preference | `pathways/preference.md` | 40 | 38 | 25 | ✅ |
| Nutrition | `pathways/nutrition.md` | 33 | 41 | 24 | ✅ |
| Provisions | `pathways/provisions.md` | 37 | 43 | 25 | ✅ |
| Planner | `pathways/planner.md` | 35 | 40 | 17 | ✅ |
| Feedback | `pathways/feedback.md` | 14 | 28 | 18 | ✅ |
| Grocery | `pathways/grocery.md` | 34 | 36 | 16 | ✅ |
| Auth | `pathways/auth.md` | 16 | 22 | 23 | ✅ |
| Household | `pathways/household.md` | 24 | 24 | 22 | ✅ |
| Notification | `pathways/notification.md` | 15 | 26 | 24 | ✅ |
| Cross-domain journeys | `pathways/cross-journeys.md` | — | 6 (XJ-01–06) | 18 | ✅ |
| Aggregated HLD gaps (triage list) | `pathways/hld-gaps.md` | — | — | 88 unique | ✅ |

**Next:** user batch-triages `hld-gaps.md` (Top-8 first) → Stage 2 (Gherkin + endpoint mapping).
