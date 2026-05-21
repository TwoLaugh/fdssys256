# Backend State Audit — 2026-05-21

A point-in-time snapshot of where the MealPrep AI backend stands relative to its own design intent. Composed of three layered audits, conducted after the 12-module mutation campaign and quality consolidation closed (PRs #91–#114).

The point of this document is to answer three questions:

1. **What was the system designed to do?** (HLD capability extraction)
2. **What does it actually do?** (Backend coverage)
3. **Did the LLDs guide the implementation?** (Drift audit)

## Method

- **Source corpus**: 11 HLDs in `design/` (≈6,000 lines) + 18 LLDs in `lld/` (≈14,000 lines).
- **Capability inventory**: 289 capabilities catalogued. Each tagged EXPLICIT / IMPLICIT / DEFERRED / OUT-OF-SCOPE / MISSING-FROM-HLD, with doc:line citation. Both literal-text capabilities and capabilities IMPLIED by other claims (e.g. "the system maps free-text food to nutrients" implies a USDA-style lookup adapter) were captured.
- **Backend coverage**: Each capability searched in code with file:line evidence. Verdicts: IMPLEMENTED / PARTIAL / MISSING / DEFERRED-MATCHES-HLD / OUT-OF-SCOPE-MATCHES-HLD / OVER-IMPLEMENTED.
- **LLD drift audit**: 3 module triples (nutrition, planner, notification) + cross-cutting style-guide check. Per-claim FOLLOWED / DRIFTED / NOT-FOUND.

Full capability inventory is in [`2026-05-21-capability-inventory.md`](2026-05-21-capability-inventory.md).

## Headline numbers

| Verdict | Count | % of 222 should-be-built |
|---|---|---|
| IMPLEMENTED | 133 | 60% |
| PARTIAL | 83 | 37% |
| MISSING | 76 | 34% |
| DEFERRED-MATCHES-HLD | ~56 | (correct) |
| OUT-OF-SCOPE-MATCHES-HLD | ~11 | (correct) |
| OVER-IMPLEMENTED | 0 | — |

(Sums slightly exceed 289 due to overlap between Section H deferreds and other-section verdicts.)

**The headline framing:** the recently-completed ticket campaign shipped 83/83 tickets — every planned ticket merged. But the planned tickets covered only ~60% of the HLD's capability surface. Either tickets were systematically descoped from the HLD during planning, or the HLD grew during implementation. Either way, this slippage is invisible from inside a ticket-driven workflow.

## Section 1: Capability inventory

The 289 capabilities span 8 sections:

| Section | Capabilities | Theme |
|---|---|---|
| A. Foundational data models | 53 | Entities, relationships, persistence |
| B. User input surfaces | 48 | Every action a user can take |
| C. AI capabilities | 43 | LLM tasks, prompt management, cost |
| D. Optimization / planning | 39 | Constraint loops, scoring, adaptation |
| E. Recipe management | 22 | Catalogue, branches, versions |
| F. External integrations | 30 | USDA, OFF, Tesco, health platform |
| G. Cross-cutting | 63 | Auth, multi-user, observability, events |
| H. Deferred / out-of-scope | 41 | Explicitly future or rejected |
| IMP. Implicit add-ons | 22 | High-stakes implicit capabilities |

**Four capabilities were added during user review** (tagged MISSING-FROM-HLD because not in the design but required regardless): user account deletion / GDPR data export (C-G-062), image storage backend (C-A-053), household role / permission model (C-G-063), and intake history search (C-B-048).

**Implicit-capability extraction was the highest-value part of this stage.** The HLDs say "the system maps free-text food to nutrients" but the *how* is implicit — a USDA / Open Food Facts adapter, an ingredient-resolution cache, an AI parser. Capturing those implicit needs makes them auditable. The 22 IMP entries are the cluster where this paid off most.

## Section 2: Backend coverage

### What's implemented

- **Auth + sessions**: fully built. Spring Security with session-based httpOnly cookies, registration, login, logout, password change, lockout, breached-list defence, timing-parity (PR #107 brought this to 98% mutation coverage).
- **Week-planner critical path**: real, end-to-end, no stubs. Beam search → rollup → Stage C LLM pick → persistence. All 7 sub-scores + 2 gates wired with real implementations.
- **Adaptation pipeline**: 4 triggers (IMPORT / FEEDBACK / DATA_MODEL_CHANGE / PLAN_TIME) wired. Fingerprint extraction + rebase + optimistic concurrency.
- **Mid-week re-optimisation**: scheduler + materiality filters + pinning rules.
- **Nutrition foundation**: targets (macros + micros + activity + eating windows), intake logging, food/mood journal, USDA + Open Food Facts integration, ingredient mapping cache, USDA retry job.
- **Provisions**: inventory, equipment, budget (target), waste log, supplier products, cook-events, batch cook split (entity-level).
- **Recipes**: full catalogue, versions, branches, substitutions, character fingerprint, optimistic concurrency, advisory lock.
- **Feedback classification**: real LLM-driven classifier with confidence gates and misclassification corrections.
- **AI infrastructure**: dispatcher, three model tiers (cheap/mid/frontier), prompt template loader with versioning, cost tracking per call, daily spend cap.
- **Decision log + trace IDs**: trace_id propagation through service args and MDC.
- **Auditing**: hard constraints audit log, lifestyle config audit log, allergy filter audit trail.

This is the substrate. It's complete, validated, mutation-tested. The gaps are vertical slices missing on top, not foundation problems.

### The six MISSING clusters

These explain ~80% of the 76 MISSING capabilities:

**1. Preference Model Tier 2/3/4 — the "learning" layer is absent.**

The HLD's central concept is three-tier preferences: Hard Constraints (DB-locked, user-only), Taste Profile (AI-maintained JSON), Lifestyle Config (settings UI). **Only Tier 1 ships.** The Taste Profile entity doesn't exist. The Lifestyle Config entity doesn't exist. The preference archive doesn't exist. Profile metadata doesn't exist.

Consequence: the system can't currently learn from feedback. There's nowhere to apply AI-derived preference deltas. Feedback classification works, classifies into "this should update preferences", and then has nothing to write to.

Blocks: C-B-004, C-B-005, C-B-038, C-B-046, C-C-034, C-C-039, C-C-040, C-G-037, C-G-049, C-G-059, C-IMP-007, C-IMP-008.

**Effort to close:** ~10-15 days (entity + service + AI delta + audit + planner integration).

**2. Entire grocery / shopping-list / Tesco vertical absent.**

No shopping list entity, derivation, or persistence. No grocery orders, no provider state, no price history. No `GroceryProvider` abstraction, no Tesco integration. The `provisions` module has supporting structure (supplier products cache, budget, waste log) but the *consumer* — the shopping list and ordering subsystem — doesn't exist.

Blocks ~20 capabilities (C-B-039 through C-B-043, all of F-005 through F-014, F-022/F-023, plus retention, partial-success surfaces, batch-cook aggregation).

**Effort:** ~3-4 weeks for MVP shape. Tesco computer-use automation is a separate post-MVP phase.

**3. Notifications subsystem missing.**

No `NotificationService`, no `notification_log` table, no scheduled scanners. Events publish; nothing listens. `NutritionIntakeDivergedEvent` is detected and emitted; no consumer.

**Effort:** ~3-5 days.

**4. Feedback destination wiring is all Noop.**

`NoopFeedbackBridgesConfiguration`, `NoopRecipeFeedbackHandlerConfiguration`, `NoopFeedbackRevertersConfiguration`. Classification + routing work; downstream apply is log-only across all four destinations (preference, nutrition, provisions, recipe). The feedback loop is half-built: feedback is collected and classified but acts on nothing.

**Effort:** ~5-7 days for real impls.

**5. Discovery is deliberate skeleton.**

`DiscoveryJobRunner:621-651` writes `EXTRACTION_FAILED` rows because `RecipeWriteApi.saveImportedRecipe` SPI doesn't exist (`recipe-01l` deferred). `NoopCandidateAiFilterConfiguration` confirms AI filter has no real impl. Whole module exists; none of it functions end-to-end.

**Effort:** ~2-3 days to close.

**6. Ops/hygiene rough edges.**

No `spring-boot-starter-actuator` dependency. No `pg_dump` backup script. No CORS config. No `.env.example`. No GDPR delete / data-export endpoints. Each tiny; sum noticeable.

**Effort:** ~3-5 days total.

### What's notable about the absence of over-implementation

Zero capabilities are flagged OVER-IMPLEMENTED. Section H (deferred/out-of-scope) was spot-checked and confirmed: the team didn't accidentally build things the HLDs ruled out. That's discipline.

## Section 3: LLD drift

### Per-triple results

| Triple | Followed | Drifted | Not-found | Verdict |
|---|---|---|---|---|
| Nutrition | 7 | 2 | 0 | LLD load-bearing; drifts cosmetic |
| Planner | 7 | 2 | 0 | LLD load-bearing; one substantive drift |
| Notification | 0 | 0 | ~30 | **LLD-IGNORED** |
| Style-guide / playbook | 6 | 3 | 1 N/A | Cross-cutting rules drift silently |

### Four drift patterns

**1. Wholesale absence — notification module.** The 640-line `lld/notification.md` specifies ~30 concrete artefacts (3 entities, 4 migrations, 2 services + 5 helpers, 3 listeners, 1 published event, 1 enum + sealed payload, 10 REST endpoints, 7 DTOs). The LLD is highly specific and clearly implementation-ready. **Zero of it exists in code.** Either consciously deprioritised and forgotten, or written speculatively and abandoned.

The silver lining: this LLD is concrete enough that handing it to an agent would produce a working notification module on the next pass.

**2. Pattern erosion in cross-cutting rules.** Three drifts verified, all universal across modules:

- **All repositories are `public`.** Style guide says package-private. Universal violation across all 14 modules.
- **Per-module `*ExceptionHandler`s, not global.** Style guide mandates a single global handler. Reality: 8 separate handlers, one per module. (The playbook itself references a `GlobalExceptionHandler`, suggesting one existed briefly and got split.)
- **Planner service split.** Style guide says `<Module>QueryService + <Module>UpdateService + single <Module>ServiceImpl`. Planner has two impls: `PlannerServiceImpl` (read) + `PlanWriteServiceImpl` (write).

Common feature: nobody checks these per ticket. Per-module LLDs defer to the style guide rather than restating it, so when the rule changes in practice no LLD notices.

**3. Substantive structural drift — planner service split.** Another agent or contributor reading the LLD would build the single-impl pattern and clash with what exists. This is real divergence, not cosmetic.

**4. Cosmetic renames and relocations (harmless).** `PinningRules` → `PinningSetCalculator`. `ReoptScopeBuilder` → `ReoptContextBuilder`. `PlannerService` → `PlanWriteService`. `UsdaApiClient` moved from `domain/service/internal/` to `config/`. Migration timestamps globally rebased. Translatable for a reader who knows the renames.

### What this says about the process

The team's discipline is **real where practiced**. The `IngredientMappingPipeline` javadoc literally cites its LLD line numbers and explicitly flags admitted divergences. That's mature engineering — drift is acknowledged, not silent.

Three failure modes leaked through:

1. **Cross-cutting rules drift silently.** No automated check, no LLD update. New rules need automated enforcement at the point they're written.
2. **Abandoned LLDs aren't reclaimed.** The notification LLD sits orphaned. It should either be implemented (queue as ticket), or archived (move to `lld/archive/`), or status-flagged. None of those happened.
3. **Late structural decisions don't update the LLD.** Planner clearly went two-impl during implementation (probably for good reasons — concurrency, transaction-boundary clarity). The LLD wasn't updated. Future readers will trust the LLD and be wrong.

### Verdict

**PARTIALLY FOLLOWED.** Not "decorative" (the worst case — LLDs read convincingly but don't match code at all). Not "fully followed" (the best case — LLDs are 1:1 maps). The realistic middle: module LLDs were load-bearing for the modules that got built; cross-cutting style rules drifted because no one re-checked them per ticket; notification was abandoned wholesale.

## Section 4: Cross-cutting themes

Three observations that emerge only when reading the three audits together.

### The 83/83 ticket count was true but partial

The recently-completed ticket campaign shipped every planned ticket. That's a meaningful achievement of execution discipline. But it covered ~60% of the HLD's capability surface, not 100%. The slippage happened during the ticket-planning phase — capabilities were dropped or deferred without that being visible at ticket-execution time.

The implication for future planning: any new ticket campaign should be audited against a capability inventory at planning time, not just at execution time. The capability inventory in this document is a reusable artefact for that purpose.

### The system can't currently learn

This is the most consequential single finding. The project is designed around three constraint-optimisation loops (preference, nutrition, provisions) all orchestrated by a meal planner with AI-driven feedback closing the loop. **The preference loop's learning surface — the Taste Profile entity — doesn't exist.**

The feedback module classifies free-text correctly. The destination bridges are all Noop. The Taste Profile has no entity to apply deltas to. The result is that the system today is a "static-rules planner" rather than an "AI meal-prep that learns" — which is the whole point of the project.

Closing Taste Profile + feedback bridges turns the codebase from "the substrate is built but the loop is open" to "the loop is closed and the system actually does what the HLD promises." That's the single highest-leverage piece of work this report can recommend.

### Discipline is real where practiced, but unobservable rules drift

The mutation campaign hit 78% project-wide. ArchUnit boundary tests guard module-to-module imports. Optimistic concurrency is universal (112 `@Version` occurrences across 80 files). The decision-log + trace-id pattern propagates everywhere. The team writes good code by code-craft standards.

But three style-guide claims that *aren't* automatically checked have drifted: repository visibility, exception-handler topology, single-impl-per-module. These are exactly the rules that need either (a) automated enforcement, or (b) per-PR explicit review. Neither happened, and they drifted.

The implication: any new convention introduced in the style guide should ship with an enforcing test (ArchUnit, custom check, lint rule) in the same PR. Otherwise it will erode within a few months.

## Section 5: Recommended sequence

Closing the gap between design intent and implementation reality, in priority order.

### Phase 1 — Close the foundation hole (~2-3 weeks)

The two pieces that unlock the AI-learning loop the system was designed around.

- **Taste Profile + Lifestyle Config entities** (10-15 days). Build per the HLD: JSONB taste profile with structured fields, archive on prune, lifestyle config with full settings shape. Add Spring Data repos, services, MapStruct mappers, REST endpoints, audit log. Migration timestamps continue the current scheme.
- **Real feedback destination bridges** (5-7 days). Replace the four Noop configurations with real impls in preference, nutrition, provisions, recipe modules. Each module gains a small service that applies the classifier's `extracted_feedback` payload.

After Phase 1, the system *learns* from feedback. That's the central design promise made real.

### Phase 2 — Make it shippable (~1 week)

- **Household RBAC** (3-5 days). Define role-to-action matrix. Apply at controller level. Close the cross-tenant read exposure flagged in PROJECT-STATUS-AND-BACKLOG.
- **Ops hygiene** (1-2 days). Add `spring-boot-starter-actuator`. Write `scripts/backup.sh`. Add CORS config. Add `.env.example`.
- **GDPR endpoints** (3-5 days). Delete-account scrubs PII. Data-export emits machine-readable bundle.
- **Recipe images + recipe ratings** (3-4 days combined). Small UX gaps.

Phase 2 closes the security + operability gaps that block any real production deployment.

### Phase 3 — Pick a vertical (~3-4 weeks)

Two natural verticals, one to pick depending on what the frontend most needs:

**3A. Notifications + Discovery wiring** — makes the system *react* and surface things. Notification service + log table + scheduled scanners (expiry, defrost, prep). Activate Discovery via the `saveImportedRecipe` SPI. Wire the recipe write target through to ingestion. About 5-7 days for notifications, 2-3 days for discovery wiring.

**3B. Grocery + Shopping list** — closes the loop to actual cooking. Shopping list entity + deterministic derivation, grocery orders, provider state, price history. Stop short of Tesco automation (separate future phase). ~3-4 weeks.

These are largely independent. If the frontend needs "the system tells me when to defrost", Phase 3A. If it needs "the system tells me what to buy", Phase 3B.

### Phase 4 — Stretch (timing TBD)

- AI cost controls — prompt caching, circuit breaker, token-counting, Anthropic Batches API. Only matters once you have real prompt traffic.
- Tesco computer-use automation.
- Stage D refine-directive loop (currently dormant per design).

## Section 6: Cheap fixes for LLD drift (1-3 days total)

Independent of the phase plan above:

- **ArchUnit rule for repository visibility** — or update the style guide to acknowledge `public` as the accepted reality. Pick one and stop the drift.
- **Decide on exception-handler topology** — either centralise behind a global advice (matches the style guide) or update the style guide to say per-module is correct.
- **Update planner LLD** to reflect the actual two-impl shape. Otherwise future readers will be wrong.
- **Archive `lld/notification.md`** to `lld/archive/` if notifications is deprioritised. Or capture it as a real ticket if it's queued. Either way, stop leaving it in pristine condition next to live LLDs.

## Caveats and limitations

- This audit is a snapshot at commit `a1857b0`. The codebase will move; the audit will date.
- Some capabilities marked PARTIAL might be IMPLEMENTED if I dug deeper — agent budget was ~5 minutes per capability search. Marked PARTIAL when in doubt.
- The LLD drift audit checked 3 triples + cross-cutting. There are 18 LLDs total. The other 15 may have their own drift patterns not surfaced here.
- The "60% implemented" headline depends on how implicit capabilities are counted. The methodology is consistent within this audit; comparing to other projects' numbers would require their own implicit-capability extraction.

## Provenance

Three working artefacts feeding this audit are in `C:/Users/irenv/ai-workflow/` (local, not in repo):

- `capability-inventory.md` — full 289-capability list with citations
- `backend-gap-report.md` — full Stage 3 agent output (~14k words, per-capability verdicts)
- `lld-drift-audit.md` — full Stage 3.5 agent output

The capability inventory is mirrored into this audits directory as [`2026-05-21-capability-inventory.md`](2026-05-21-capability-inventory.md) for the parts of this report that reference specific capability IDs.

This audit was conducted in three stages: HLD extraction (agent reads all 11 design docs), user review with spot-check additions, backend coverage check (agent verifies each capability against code), and finally a separate LLD-vs-code drift audit on top.
