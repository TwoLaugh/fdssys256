# MealPrep AI — Project Status & Backlog

**As of:** main `4d0958d` · 13 modules · 1,087 main Java files · 345 test files · 74 Flyway migrations · 44 REST controllers.

This is a living doc. It answers: *does what's in the backend let us do what the HLD wants?* + tracks the testing work + logs what's deferred/probable. Regenerate the metrics sections from `target/site/jacoco/jacoco.csv` + the Pitest report on each main run.

---

## 0. Confidence / methodology note

An unprimed reviewer agent produced a polished but **wrong-by-10×** survey (claimed 4 modules / 119 files / "planner & recipe have zero code"). Disproven against the tree (13 modules, 1,087 files, 23 event-listener classes). **Lesson, and a real course finding:** confident agent output is not evidence — every claim here is verified against code or labelled `UNVERIFIED`. A second, hardened review (mandatory ground-truth + file:line evidence) produced the corroborated findings below.

The codebase is **substantially and competently implemented** — not a skeleton. Core meal-plan generation and user-initiated recipe import genuinely work end-to-end. The gaps are specific and mostly deferred-by-design, not pervasive.

---

## 1. Does the backend do what the HLD wants? (capability → status)

Legend: ✅ works · ◐ wired-but-dormant (by milestone design) · ✗ broken/stub · ⚠ data-integrity/security gap

| HLD capability | Status | Evidence | MVP-critical? | Blocking dependency |
|---|---|---|---|---|
| Auth: register/login/logout/session/password | ✅ | `auth/domain/service/internal/AuthServiceImpl` — timing-parity, throttle/lockout, hashed sessions, audit | yes | — |
| Meal-plan generation (Stage A beam → B rollup → C LLM pick) | ✅ | `PlanComposer.compose` (`:153-375`), `BeamSearchEngineImpl`, `StageCInvokerImpl` | yes | — |
| Stage D plan-time AI refinement (refine-directives) | ◐ | `Phase2AugmenterImpl` always emits empty directive list → `PlanComposer:275-279` routing loop never runs in prod | no (deferred) | cross-module `RefineDirectiveDto` is a planner-local placeholder |
| User-initiated recipe import (URL) | ✅ | `RecipeServiceImpl.importFromUrl` (`:317-339`) — fetch→parse→map→persist→provenance | yes | — |
| **Automated recipe discovery → ingestion** | ✗ | `DiscoveryJobRunner:621-651` permanent **skeleton mode**: scraped+deduped recipes write `EXTRACTION_FAILED` (`saveImportedRecipeNotYetImplemented`) instead of persisting; `DiscoveryRecipeIngestedEvent` never published | **yes IF discovery in MVP** | `RecipeWriteApi.saveImportedRecipe` SPI **does not exist** (deferred `recipe-01l`) |
| Feedback submit → classify → route (4 destinations) | ✅ (core) | `FeedbackServiceImpl`, `FeedbackClassificationListener` (`@TransactionalEventListener AFTER_COMMIT`) | yes | — |
| Feedback: retry stuck classifications | ✗ (latent) | `FeedbackServiceImpl:392` throws `UnsupportedOperationException` ("feedback-01g pending"); **no scheduler/controller calls it** → stuck classifications never retried, silently | no (but silent) | feedback-01g + a scheduler |
| Nutrition: targets/intake/journal/floor-gate/directives | ✅ (mostly) | nutrition module + ITs | yes | — |
| Nutrition: manual intake override macros | ⚠ | `NutritionServiceImpl:1043-1049` persists **zero macros** for overridden slots until async AI parse (`nutrition-01k`) → daily/weekly rollups under-count in the window | data-integrity | nutrition-01k listener timing |
| Nutrition: `logSnack(deductFromPantry=true)` | ⚠ | `NutritionServiceImpl:1140` logs and **no-ops** the pantry deduction silently | data-integrity | provisions deduction wiring |
| Nutrition: GET intake audit-log | ✗ | endpoint returns **HTTP 500** — bad Spring Data derived query over a lazy assoc with `OrderBy` (spawned fix task; regression test pins the 500) | yes (broken endpoint) | the spawned audit-log fix |
| Provisions / household / preference / adaptation core | ✅ | implemented + IT-covered | yes | — |
| `PlanGeneratedEvent` / `PlanAcceptedEvent` consumers | ✗ | published (`PlanComposer:480`, `PlanWriteServiceImpl:127,213,349`) but **no production consumer** — anything meant to react (notifications, grocery provisioning, household sync) is silently absent | depends on MVP scope | the intended downstream listeners |
| Household-scoped read authorization | ⚠ (security) | `PlansController:55-57` — any authenticated caller can read **any** household's plans/history/suggestions (deferred-by-comment) | **security** | household authz follow-up |
| Admin endpoints role auth | ⚠ (security) | `AdminDecisionLogController` had `TODO(auth-roles-followup)`; no `ROLE_ADMIN` (all users `ROLE_USER`) — verify exposure | security | role model |
| AI £10/mo budget cap enforcement | ◐ UNVERIFIED | spend is recorded; a hard pre-call reject guard was not located — needs a deeper trace | medium | — |

---

## 2. Testing state & roadmap

**Current (main, merged-IT-counted after the jacoco-merge fix #89):**

- Line **89.1%** (17,204/19,312) · Branch **70.8%** (4,240/5,988) · Mutation **62% killed** (4,799/7,795), test-strength **87%**, **~780 SURVIVED + ~2,250 NO-COVERAGE** unkilled.
- Goal: line → ~95%; branch is the weak axis; mutation is the big remaining quality gap.

**Systemic issues found (and their state):**

1. **Coverage/mutation gates are silently OFF** — `pom.xml`: JaCoCo `haltOnFailure=false`, Pitest `mutationThreshold=0`, never re-enabled after code landed. *Decision: hold gating until the number is up (user). Must enable before "done".*
2. **JaCoCo report excluded ALL IT coverage** until fixed in #89 (merged) — report read only `jacoco.exec`, never `jacoco-it.exec`. Real coverage was always ~89%, just invisible.
3. **Contract validation was opt-in**; audit found 7 of 49 MockMvc ITs never asserted `openApi().isValid()` → latent unflagged drift. Adding it (#90) surfaced a real one (catalogue, below). *Residual:* endpoints with **no** contract-asserting IT at all were not yet inventoried — TODO §3.
4. **Catalogue contract**: spec was fine (optional/nullable); ITs send explicit `catalogue:null` which the atlassian validator rejects vs `enum` despite `nullable:true`. #92 aligned spec↔LLD (correct, harmless); **#90 still red — needs the test-side fix (omit the field) before merge.**
5. Pitest runs **unit-only** — ITs raise JaCoCo line but never mutation. The ~2,250 NO-COVERAGE mutants are largely IT-covered code Pitest can't see; closing mutation needs *unit* tests (or a heavier Pitest-with-IT reconfig).

**Mutation campaign (in progress, module-by-module, survivor-targeted, test-only):** nutrition #91 (hot classes 75→99%), recipe #93 (48→66%, +114) merged. Remaining: adaptation, planner, provisions, feedback, discovery, household, ai, auth, core, preference. Equivalent mutants documented, never gamed.

**Exclusions (#94, approved):** generated `*MapperImpl` + `Noop*` SPI stubs excluded from JaCoCo+Pitest (measurement only; not gating).

---

## 3. Backlog (prioritised)

### P0 — MVP-blocking / broken in production
- [ ] **`RecipeWriteApi.saveImportedRecipe` SPI** (deferred `recipe-01l`) → unblocks `DiscoveryJobRunner` skeleton mode. *Decide first: is automated discovery in the MVP?* If yes, this is a no-go blocker; if no, document discovery as explicitly out-of-scope.
- [ ] **Nutrition GET intake audit-log → HTTP 500** — spawned fix task; flip the pinned regression test once fixed.
- [ ] **#90 test-side fix** — request builders must omit `catalogue` (not send explicit null) so the contract net lands; then merge #90.
- [ ] **Household-scoped read authz** (`PlansController:55-57`) — cross-tenant read exposure; close before any real deploy.
- [ ] Verify **admin endpoint authorization** (no `ROLE_ADMIN`) — confirm what `AdminDecisionLogController` exposes.

### P1 — data integrity / correctness / quality
- [ ] Nutrition manual-override macros under-count window (`NutritionServiceImpl:1043-1049`) — confirm `nutrition-01k` closes it; add an assertion.
- [ ] `logSnack` silent pantry no-op (`:1140`) — wire deduction or surface a clear "not supported" error.
- [ ] Confirm whether `PlanGeneratedEvent`/`PlanAcceptedEvent` are *supposed* to have consumers (notifications/grocery/household). If yes — implement; if no — document as fire-and-forget by design.
- [ ] Continue mutation campaign to all modules; target killed ≥80% / strength ≥90% where non-equivalent.
- [ ] Branch-coverage push (weakest axis at 70.8%) — focused round on uncovered conditionals.
- [ ] Inventory **every endpoint vs contract-asserting IT**; close gaps (true contract assurance).
- [ ] Enable the gates (`haltOnFailure=true`, raise `mutationThreshold`) once numbers are up — likely 85% line / 70% branch / staged mutation.

### P2 — deferred-by-design / hardening
- [ ] Stage-D plan-time refinement: real cross-module `RefineDirectiveDto` + activate the routing loop.
- [ ] `retryStuckClassifications` (feedback-01g) + its scheduler.
- [ ] Import-time recipe dedupe (`RecipeServiceImpl:321`).
- [ ] AI budget-cap hard pre-call guard — verify/implement.
- [ ] Login throttle/lockout under concurrency (read-modify-write without row lock) — harden if auth ships standalone.
- [ ] `UserPasswordChangedEvent` comment claims AFTER_COMMIT but publishes in-tx — fix before any consumer assumes durability.

### Process / decisions open
- [ ] **Gating timing decision** (held by user until coverage up).
- [ ] **MVP scope decision**: is automated discovery + Stage-D refinement in this release? This single decision flips several P0/P2 items.
- [ ] Persona-review verification discipline — never relay unverified agent analysis (this doc's §0).

---

## 4. Net assessment

Conditional-go for the **planner + manual-import** slice if automated discovery and Stage-D refinement are explicitly deferred (the code's own ticket references say they are), **contingent on** closing the P0 list (audit-log 500, household read authz, admin authz) and confirming the P1 event-consumer questions. If automated discovery is in-scope, it is a **no-go** until `saveImportedRecipe` lands. The engineering quality of what exists is high; the risk is treating green CI / high coverage as "done" while these specific seams are stubbed.
