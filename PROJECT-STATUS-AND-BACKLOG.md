# MealPrep AI — Project Status & Backlog

**As of:** main `b466d73` (2026-08-25) · 15 feature modules · 1,653 main Java files · 625 test files (+20 e2e) · 117 Flyway migrations · 85 `@RestController` classes · React/Vite frontend under `frontend/`.

This is a living doc. It answers: *does what's in the backend let us do what the HLD wants?* + tracks the testing work + logs what's deferred/probable. Regenerate the metrics sections from `target/site/jacoco/jacoco.csv` + the Pitest report on each main run.

**Revision note (2026-08-25):** re-anchored from `17e726d` to `b466d73` after D-0006 merged the experiment branch into main (see §4, now resolved history). Mechanical check at write time: `git log b466d73..main --oneline` is empty. The previous revision (2026-08-24) had itself re-anchored from a silently expired `4d0958d`; the lesson stands — always run the delta log before trusting this file.

---

## 0. Confidence / methodology note

An unprimed reviewer agent produced a polished but **wrong-by-10×** survey (claimed 4 modules / 119 files / "planner & recipe have zero code"). Disproven against the tree. **Lesson, and a real course finding:** confident agent output is not evidence — every claim here is verified against code or labelled `UNVERIFIED`. A second lesson from this doc's own history: an anchor that is not mechanically re-checked rots — the 2026-08-24 truth-up found 3 of 5 P0 items already fixed on main months earlier. Always name the anchor and run the delta log before trusting this file.

The codebase is **substantially and competently implemented** — not a skeleton. Core meal-plan generation, recipe import (user-initiated *and* discovery-fed), grocery, and notifications work end-to-end. The gaps are specific, and most of the previous revision's gaps have since closed.

---

## 1. Does the backend do what the HLD wants? (capability → status)

Legend: ✅ works · ◐ wired-but-dormant (by milestone design) · ✗ broken/stub · ⚠ data-integrity/security gap

| HLD capability | Status | Evidence (at `17e726d`) | MVP-critical? | Blocking dependency |
|---|---|---|---|---|
| Auth: register/login/logout/session/password | ✅ | `auth/domain/service/internal/` — `AuthServiceImpl`, `LoginThrottleService`, `SessionReaper`, `PasswordStrengthValidator`; hardening sweep in #197 (`4f3170e`: lockout reset, password-change throttle, reserved usernames, soft-deleted-user revoke) | yes | — |
| Meal-plan generation (Stage A beam → B rollup → C LLM pick) | ✅ | `PlanComposer` (events published inside `persistAndPublish`, `PlanComposer.java:92,675`); catalogue-backed recipe pool + cold-start gate (#153/#154) | yes | — |
| Stage D plan-time AI refinement (refine-directives) | ✅ | Was ◐ (augmenter always emitted an empty list). #224 (`72c1d35`) activated it: `Phase2AugmenterImpl.java:126-147` parses `refineDirectives` from the Phase-2 response; `PlanComposer.java:426-476` builds `PlanTimeRefineDirectiveRequest` and invokes `adaptationService.runPlanTimeRefineJob` | no | — |
| User-initiated recipe import (URL) | ✅ | `RecipeServiceImpl.importFromUrl` (`:550`); 5-layer extraction pipeline + real BBC Good Food import test (#198); preview-then-confirm + import dedup + version history (#212) | yes | — |
| Automated recipe discovery → ingestion | ✅ code / **OUT of trial scope** | Was ✗ skeleton. `RecipeWriteApi.saveImportedRecipe` landed in #122 (`781c5a3`): `RecipeWriteApi.java:114`, impl `RecipeServiceImpl.java:1213`; `DiscoveryJobRunner.java:874` publishes `DiscoveryRecipeIngestedEvent`; `EXTRACTION_FAILED` rows only on genuine scrape/extraction failures (`DiscoveryJobRunner.java:77-81`). Since hardened: ingredient-key ingest fix (CRITICAL, #191 `3f82e87`), AI-filter keep-on-outage (#203), parallel source fan-out default-off (#223), server-side hard-constraint exclusions (#245), cancelled-job status + source disable (#253). **D-0004 (resolved 2026-08-24): discovery is NOT in the real-user trial scope** — see §3 process notes | no (per D-0004) | — |
| Feedback submit → classify → route (4 destinations) | ✅ | `FeedbackServiceImpl`, `FeedbackClassificationListener`; real destination bridges (#130), misclassification reverters (#145) | yes | — |
| Feedback: retry stuck classifications | ✅ | Was ✗ (threw `UnsupportedOperationException`, nothing called it). #134 landed the real sweep: `FeedbackServiceImpl.java:433` + `StuckClassificationRetrier` on an `@Scheduled` cadence (`FeedbackRetrySweepProperties.java:17`) | no | — |
| Nutrition: targets/intake/journal/floor-gate/directives | ✅ | nutrition module + ITs; upsert targets on PUT (#156), directive-expiry sweep + daily aggregate + DRI initialise (#205) | yes | — |
| Nutrition: manual intake override macros | ◐ | Was ⚠ (zero macros persisted until async AI parse). The parse is now wired end-to-end (nutrition-01k, #221 `66471e6`), and #244 (`be3d65b`) added a structured-edit repair path for failed parses (`NutritionServiceImpl.java:1557-1590`). An overridden slot still carries zeroed actuals in the pre-parse window by design — narrowed, no longer silent-dead-end | no | — |
| Nutrition: `logSnack(deductFromPantry=true)` | ✅ | Was ⚠ silent no-op. Wired in the same PR as this revision (fix/trial-readiness): `NutritionServiceImpl.logSnack` hands off to `ProvisionUpdateService.applyStandaloneConsumption` in the same tx; `deductFromPantry` without an `ingredientMappingKey` is now a 400 (`SnackDeductWithoutMappingKeyException`), so the silent path is gone | yes | — |
| Nutrition: GET intake audit-log | ✅ | Was ✗ (HTTP 500, bad derived query over a lazy assoc). Fixed in #100 (`bbd5c64`, pinning regression test flipped): `NutritionServiceImpl.java:1372-1381` resolves the day row first, then queries `findByIntakeDay_IdOrderByOccurredAtDesc` by id | yes | — |
| Provisions / household / preference / adaptation core | ✅ | implemented + IT-covered; since extended: taste-vector embeddings via pgvector (#216), AI taste-profile delta pipeline (#142), adaptation triggers 1/3 wired (#170/#172), worker apply paths fixed (CRITICAL, #190) | yes | — |
| Grocery module (shopping list → provider orders) | ✅ | **New since previous anchor.** 01a-01g complete (#174-#186): Tier-1 shopping-list calc + recalc listeners, manual fulfilment, price history, provider SPI + order state machine, substitution review + reconciliation, scheduled jobs + cost-cap guardrails; mutation-hardened (#181) | yes | — |
| Notification module | ✅ | **New since previous anchor.** Core entity/service/REST/listeners (#128) + scheduled scanners (expiry, defrost, prep, nutrition, staples) (#131); feedback-confirmation NOTIF-16 (#165) | yes | — |
| `PlanGeneratedEvent` / `PlanAcceptedEvent` consumers | ✅ | Was ✗ (published, no consumer). Now consumed in production by notification (`PlannerEventListener.java:54`) and grocery recalc (`ShoppingListRecalcListener.java:89`) | yes | — |
| Household-scoped read authorization | ✅ | **Closed.** PR #263 (`397d383`, merged 2026-08-25): household-membership check on all plan read endpoints (`2230c6e`), plus the T8 review fixes that rode the same PR — notification dispatch only when a target user resolves (`460cc81`), non-throwing soft-prefs merge seam for the planner (`0f89da3`), drifted plan/shopping-list response fields declared in the contract (`2a4e982`) | yes | — |
| Admin endpoints role auth | ✅ (imperative) | Was ⚠. Enforced project-wide in #215 (`b785f1b`) via `AdminAccessGuard.requireAdmin()` against the `mealprep.admin.user-ids` allowlist — fail-closed: anonymous → 401, non-allowlisted → 403, default empty allowlist denies everyone (`AdminAccessGuard.java:30-34`). Nuance: the `@PreAuthorize("hasRole('ADMIN')")` annotations on admin controllers (e.g. `AdaptationAdminController.java:65`, from #213 `fa282dd`) are the *published contract* but **inert** — no `@EnableMethodSecurity`, no `ROLE_ADMIN` in the flat v1 user model (`AdminAccessGuard.java:21-28`). Deploy note: prod config must populate the allowlist or no one can use admin endpoints. The e2e/dev dogfood stacks now allowlist the seed user by username instead (`mealprep.admin.usernames`, same PR as this revision) because its UUID is minted at registration; prod stays id-keyed and fail-closed | security | — |
| AI cost budget enforcement | ✅ | Was ◐ UNVERIFIED. #213 (`fa282dd`) landed the two-scope pre-call gate: `CostBudgetGuard` ("Pre-call gate", `:22`; hard breach rejects before dispatch, `:109`, `AiCostBudgetExceededException`), plus per-task token caps (`TokenCapGuard`) and retry backoff | medium | — |
| Frontend | ✅ (against backend contract) | **New since previous anchor.** Vite/React app under `frontend/` built page-by-page to contract specs (#225-#240), responsive degradation pass (#230), backend gap tickets driven from the page-spec programme all resolved (#241 → #260 ledger close) | yes | — |

---

## 2. Testing state & roadmap

**Metrics — STALE, carried not regenerated.** No build was run for this revision either; refresh from the post-merge CI run's report. Last measured numbers, anchored to the post-12-module-mutation-campaign CI run on main at `52d094b` (#112, GH Actions run 26191394397 — the run the pom gate comments cite, `pom.xml:470-474,542-545`):

- Line **93.4%** (17,691/18,951) · Branch **79.5%** (4,747/5,968) · Mutation **78% killed** (5,960/7,661), test-strength **95%**.
- That run predates ~40 commits to `17e726d` plus the 63-commit experiment-branch merge and the #263 wave to `b466d73` — a much larger drift than at the previous revision. The first CI run on merged main regenerates JaCoCo + Pitest; take the numbers from there.
- (The previous revision's 89.1/70.8/62 figures were an even older anchor and are superseded.)

**Systemic issues from the previous revision — all closed:**

1. **Gates are ON** (was: silently OFF). `pom.xml:475` `haltOnFailure=true` with line ≥ 0.88 / branch ≥ 0.74 (`:483,:488`); `pom.xml:546` `mutationThreshold=73`. Enabled in #99 (`c19f9e9`) at 80/70/60, tightened in #113 (`248f31b`) after the campaign. Recorded in decision D-0005 (supersedes D-0003).
2. JaCoCo merged-exec fix (#89) — holds; the check runs on `jacoco-merged.exec` (`pom.xml:459,469`).
3. Contract net #90 — **merged** (`8fd0442`): openapi contract asserted in the 7 previously-unchecked ITs (the catalogue test-side issue was resolved in the merge). Residual: a full endpoint-vs-contract-asserting-IT inventory was never done — still open in §3.
4. Mutation campaign — **complete across the 12 then-existing modules** (#91-#113: nutrition, recipe, planner, adaptation-pipeline, provisions, feedback, discovery, auth, ai, household, core, preference), plus grocery hardened after build-out (#181, `GroceryServiceImpl` un-excluded per `pom.xml:526-532`).
5. Pitest still runs **unit-only** — ITs raise JaCoCo but not mutation. Unchanged trade-off, now enforced at the 73% level that unit tests alone clear.

**New since previous anchor:** a full Stage-2 E2E harness (Cucumber-JVM + REST-assured, own maven profile `pom.xml:553+`) — scaffold #147, five batches of suites (#148-#152), cross-domain journeys XJ-01..06, and a long tail of un-pended scenarios as features landed (#155-#166, #183-#185).

---

## 3. Backlog (prioritised)

### P0 — MVP-blocking / broken in production

None open.

- [x] **Household-scoped read authz — CLOSED.** Merged in PR #263 (`397d383`): membership check on all plan read endpoints (`2230c6e`) plus the T8 review fixes riding the same PR (notification null-target dispatch guard `460cc81`, planner soft-prefs merge seam `0f89da3`, contract-drift alignment `2a4e982`).

Struck on earlier re-verification (all previously listed here):
- ~~`saveImportedRecipe` SPI~~ — landed #122; and D-0004 resolved discovery OUT of trial scope anyway.
- ~~Nutrition audit-log 500~~ — fixed #100.
- ~~#90 test-side fix + merge~~ — merged `8fd0442`.
- ~~Admin endpoint authorization~~ — enforced imperatively #215 (see §1 nuance; populate the prod allowlist at deploy time).

### P1 — data integrity / correctness / quality
- [x] ~~`logSnack` silent pantry no-op~~ — wired in the same PR as this revision; missing mapping key now 400s (see §1 row).
- [ ] Manual-override pre-parse zero-macro window — narrowed (#221 parse, #244 repair), decide whether the residual window needs a UI signal; add a rollup assertion.
- [ ] Endpoint-vs-contract-asserting-IT inventory — never done; #90 covered the 7 known stragglers only (#263's `2a4e982` closed two more drift instances found by hand).
- [ ] Refresh coverage/mutation numbers from the first post-merge CI run on `b466d73`-or-later and re-check headroom against the 88/74/73 gates.

### P2 — deferred-by-design / hardening
- [ ] Login throttle/lockout under concurrency (read-modify-write without row lock) — UNVERIFIED whether the #197 hardening sweep addressed the row-lock specifically; re-check `LoginThrottleService` before shipping auth standalone.
- ~~Stage-D refinement activation~~ — done #224.
- ~~`retryStuckClassifications` + scheduler~~ — done #134.
- ~~Import-time recipe dedupe~~ — done #212 (`RecipeDeduplicationService`), override added #252.
- ~~AI budget-cap hard pre-call guard~~ — done #213 (`CostBudgetGuard`).
- ~~`UserPasswordChangedEvent` misleading AFTER_COMMIT comment~~ — comment now states the in-tx publish and listener-side binding explicitly (`AuthServiceImpl.java:393-394`).

### Process / decisions
- [x] **D-0004 resolved (2026-08-24): automated discovery is OUT of the real-user trial scope.** The user-initiated import path (working, §1) carries the trial; the culinary graph is the intended long-term candidate supplier (D-0002), which is why hardening discovery further is not trial-critical. The discovery code that exists stays in but is not a supported trial feature — related backlog items are demoted accordingly (none remain at P0/P1).
- [x] **D-0005: gates are ON** — supersedes D-0003 ("hold gating until the number is up"), whose trigger fired at #99 before the record was written.
- [ ] Persona-review verification discipline — never relay unverified agent analysis (this doc's §0), and mechanically re-check this doc's anchor (`git log <anchor>..main --oneline | wc -l`) before trusting it.

---

## 4. The experiment branch (resolved history)

**Resolved by D-0006 (2026-08-24, executed 2026-08-24/25): merged.** `experiment/dataset-recipe-pool` (HEAD `1bf366d`, 63 commits ahead / 0 behind) was fast-forwarded into main — no merge commit needed, `17e726d` was a clean ancestor. The docs/fix wave that followed (T1 truth-up `e513403`, T6 runbook `61f58bf`, T2 read-authz PR #263) brings main to `b466d73`. **Main is the live line and the trial baseline again**; the "experiment" label was outgrown, exactly as the D-0006 record argued. What the branch delivered, one line each (branch-level evidence, verified at commit-log level):

- **Nutrition-driven planning:** coverage reports met/short/NO_DATA instead of scoring unknown micros as 0 (`bd149c3`); per-micro provenance (measured/derived/estimated) through to coverage; real NUTRIENT_ESTIMATION AI task for residual micros.
- **Portion optimisation + in-meal additions:** portion-scaling toward per-meal calorie targets, deterministic addition gap-fill + LLM addition-pairing gate, SIDE_RECIPE additions, per-day portion optimiser with per-macro weights, additions spread/rotated across the week.
- **Guideline targets from demographics:** DRI-based target calculator, pregnancy/lactation life-stage floors, body-size-scaled B vitamins, compute-defaults endpoint + "compute from my details" frontend flow.
- **Planner performance/UX:** incremental beam scoring + memos, 90s beam budget, async plan generation with frontend progress UI, recipe-pool cap tuning.
- **Live frontend wiring:** Today page live against the real backend (`3a91595`), live targets save, Plan page derives weeks from live plans + route error boundary (`1bf366d`).
- **Culinary-graph Phase 0 integration (G04-G11):** graph-batch ingest entrypoint (admin path), spike-canon IngredientMapping seed, engine-side nutrient-key contract pin, additive dataQuality + provenance chips, batch withdraw/restore by jobId, spike-vs-engine nutrition comparison harness.
- **Dogfood support:** dev profile with seeded recipe pool + graceful no-key AI degrade; zero-kcal "calculated" recalc bug fixed.
- Caveat: the history carries one explicit WIP snapshot commit (`e7a0476`, batch-cooking scoring / cost-reuse / pack-size heuristics) — reviewed at D-0006 execution time as a descriptive snapshot and kept in history; not everything in it is finished work.

---

## 5. Net assessment

The trial slice — **planner + user-initiated import + grocery + notifications + live frontend** — is a go on the P0/P1 ledger: the last P0 (household read authz) closed in #263, the logSnack silent no-op is wired, the dogfood stack can reach its admin surfaces, and D-0006 put the trial baseline back on main. Automated discovery stays explicitly out of scope per D-0004 (implemented but unsupported for the trial). Remaining conditions are operational, not code: populate the prod admin allowlist at deploy time, and refresh the STALE §2 metrics from the first post-merge CI run before leaning on them. The prior revision's headline risk (an unmerged 63-commit de-facto live line) is resolved history — the residual risk worth watching is anchor rot in this very file; run the §0 delta check.
