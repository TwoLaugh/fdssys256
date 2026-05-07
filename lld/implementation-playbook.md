# Implementation Playbook — Backend

*The contract for how every backend ticket gets specified, implemented, tested, reviewed, merged. Designed for agent-driven implementation with strong-enough quality gates that hollow tests, over-mocking, and silent regressions don't slip through.*

This doc is the **shared agreement** between the user (you), the agents that implement tickets, and the CI that gates merges. Every ticket in the implementation phase follows this template. Every agent reads this before starting work.

The architectural specs (the 14 module LLDs + style guide + 9 prompts + extraction pipeline) are inputs. This doc says how to turn them into running code.

## Scope

**In scope:** turning the LLD specs into a buildable Spring Boot 3.2 + PostgreSQL backend. Migrations, entities, repositories, services, controllers, validators, mappers, events, prompt template files, REST endpoints, the full test ladder (unit / integration / contract / performance / e2e), CI gates, git workflow, ticket breakdown, agent dispatch.

**Out of scope:** frontend, mobile, browser-automation specifics for grocery (Tesco), production hosting + deployment automation. The OpenAPI spec is the frontend contract — frontend can begin from it once the corresponding tickets ship.

## Success criteria for a finished ticket

A ticket is **DONE** when **all** of these hold. The agent self-verifies (per the [Verification model](#verification-model--agent-self-verify--harness-prepost-flight) below) and the harness double-checks before user review.

1. **Behavioural spec was written first** — committed before the implementation; tests verify against it, not against the impl.
2. **OpenAPI spec is updated** for any endpoint change — handcrafted in `src/main/resources/openapi/openapi.yaml`. Provider-side contract test asserts the controller matches it.
3. **`./mvnw clean verify` passes locally** in the agent's working environment. This is the foundational gate — without it, none of the others can be trusted.
4. **Unit tests pass.** Coverage ≥80% line, ≥70% branch (JaCoCo). Mutation score ≥70% via Pitest on production code in this ticket's packages.
5. **Integration tests pass.** No mocking of any in-module collaborator; only external services (AI API, Tesco automation) mocked.
6. **Contract test passes.** Every controller endpoint validates request + response against the OpenAPI spec.
7. **Performance tests pass per-endpoint budget.** Median latency ≤ budget; p95 ≤ 2× budget. Budget defined in the ticket.
8. **ArchUnit checks pass.** No cross-module repository imports. No service in module X importing from module Y's `domain.repository`.
9. **Lint passes.** `./mvnw spotless:check` clean.
10. **Decision-log smoke** — if the ticket touches an optimisation-loop scope, decision-log row written; smoke test asserts.
11. **Edge-case checklist ticked off in the PR description** — the explicit list of edge cases the ticket required must each have a corresponding test.
12. **Harness post-flight ran clean** — `./mvnw clean verify` re-run by you/me before tagging the user; confirms the agent didn't have a stale cache or skipped a step.
13. **Code reviewed by you.** Agent-produced PR; one-pass review minimum. Approve, request changes, or reject.

If any gate fails, ticket goes back to the agent (via `SendMessage` to preserve context) for follow-up. CI doesn't merge red builds.

---

## Per-ticket specification template

Every ticket — whether agent-written or hand-written — has this structure. Tickets live as markdown files in `tickets/<module>/<NN>-<feature>.md` until they're picked up; once picked up, the ticket file is referenced from the PR description.

```
# Ticket: <module> — <feature>

## Summary
One-sentence statement of what this ticket delivers.

## Behavioural spec (write this BEFORE implementation)
Numbered list of behaviours the implementation must guarantee. Each behaviour must
be testable. The agent writes tests against THIS list, not against whatever the
implementation happens to do.

Example:
1. POST /api/v1/preferences/hard-constraints with valid body returns 200 with the
   stored aggregate.
2. POST with allergen string longer than 64 chars returns 400 with a ProblemDetail
   listing the offending field.
3. POST with both `dietary_identity_base` and a contradictory `medical_diets` entry
   (e.g. base=vegan + medical_diets=["lactose_intolerant"]) returns 400.
4. Concurrent updates to the same user's hard-constraints (same expectedVersion)
   resolve via @Version: first wins, second returns 409 with current version.
5. Hard-constraint update fires HardConstraintsChangedEvent after commit, NOT
   before. Verified by transactional listener test.
6. Audit log row written per changed field, with the actor user id.
7. Retrieving hard-constraints for a non-existent user returns 404.

## OpenAPI spec excerpt
The endpoints this ticket adds or modifies, expressed as the YAML that will land
in src/main/resources/openapi/openapi.yaml. Inline in the ticket so reviewer can
verify the contract before implementation.

## Edge-case checklist
Tickable list. Each item must have a corresponding test. Reviewer verifies each
test exists and is meaningful (not just "happy path with mocks").

- [ ] Empty list inputs handled
- [ ] Maximum-size inputs handled (boundary)
- [ ] Concurrent-update conflicts (@Version) tested
- [ ] All ProblemDetail status codes (400/404/409 etc.) tested
- [ ] Database constraint violations surface as 4xx not 500
- [ ] Audit log writes for each mutation
- [ ] Events fire AFTER commit, not before
- [ ] (more, ticket-specific)

## Files this ticket touches
List explicitly so reviewer can spot scope creep.

- src/main/java/.../preference/domain/entity/HardConstraints.java         (new)
- src/main/java/.../preference/domain/repository/HardConstraintsRepository.java (new)
- src/main/java/.../preference/domain/service/internal/HardConstraintsServiceImpl.java (new)
- src/main/java/.../preference/api/controller/HardConstraintsController.java (new)
- src/main/resources/db/migration/V20260601100000__preference_create_hard_constraints.sql (new)
- src/main/resources/openapi/openapi.yaml (modified — adds 5 endpoints)
- src/test/java/.../preference/HardConstraintsServiceImplTest.java (new — unit)
- src/test/java/.../preference/HardConstraintsControllerIT.java (new — integration)

## Performance budget
Per endpoint, median latency under load with realistic data:

- POST /api/v1/preferences/hard-constraints: 50ms median, 100ms p95
- GET /api/v1/preferences/hard-constraints: 20ms median, 50ms p95

k6 script asserts these on each PR.

## Dependencies on other tickets
List of tickets that must merge before this one. None for foundational tickets.

## Time estimate
1 day for this ticket. If it grows, split.
```

The ticket is the single source of truth during implementation. Agents read it; reviewer reads the PR diff against it.

---

## Test strategy — 5 layers, what each catches

The test ladder is **layered**, not redundant. Each layer catches different failure modes.

### Layer 1 — Unit tests (Mockito; no Spring context)

**What they catch:** business-logic bugs in a single class. Pure-function-flavoured.

**Tooling:** JUnit 5 + Mockito + AssertJ. `@ExtendWith(MockitoExtension.class)`.

**Coverage targets:** ≥80% line, ≥70% branch (JaCoCo). Hard gate.

**Mocking discipline (the killer rule):**

> **Mock only at module boundaries.** Within a module, classes that collaborate use real instances. Across modules, the dependency's QueryService/UpdateService is mocked.

Concrete:
- ✅ `HardConstraintsServiceImplTest` mocks `HardConstraintsRepository` (module-internal data layer; mocking it is OK because it's a Spring-Data-generated proxy with no logic of its own).
- ✅ `BeamSearchEngineTest` uses real `ScoringEngine`, real `SubScoreCalculator` instances — they're in the same module, all production code. Tests the actual algorithm.
- ❌ `BeamSearchEngineTest` mocks `ScoringEngine` and asserts it was called — proves nothing about the search behaviour.
- ✅ `PlannerServiceImplTest` mocks `RecipeQueryService` (cross-module dependency) — that's a real boundary.
- ❌ `PlannerServiceImplTest` mocks `BeamSearchEngine` — same module; not a real boundary.

**Test naming:** `<methodName>_<scenario>_<expected>`. Examples:
- `applyCookEvent_unitConversionFails_floorsAtZeroAndEmitsUnderflowFlag`
- `evaluateFloorGate_proteinFloorMissed_returnsPassedFalseWithViolation`

If you can't name the test in this shape, the test is probably testing the wrong thing.

**Anti-patterns hard-gated by review:**
- Tests that only verify mock interactions (`verify(mockRepo).save(any())`) without asserting state or output.
- Tests named `testFoo` or `shouldWork`.
- Tests with no `assert*` calls.
- Tests that mock a final/sealed class for no reason.
- Tests that exercise the happy path only — every PR's edge-case checklist must list ≥3 negative cases per happy-path case.

### Layer 2 — Integration tests (Testcontainers; full Spring slice)

**What they catch:** wiring bugs, transaction boundary bugs, JPA mapping bugs, real-DB-vs-in-memory divergence, event listener registration, Spring Security filter chain.

**Tooling:** `@SpringBootTest` + `@AutoConfigureMockMvc` + Testcontainers (Postgres 16-alpine). Per-module slice or full context — pick per ticket. Real DB always.

**Mocking discipline (different from unit):**

> **No mocking of in-process Spring beans.** Only external services are mocked: AI API (`TestAiService` bean from `lld/ai.md`), grocery automation (`TestGroceryProvider` for v1).

Concrete:
- ✅ `HardConstraintsControllerIT` posts an HTTP request via MockMvc, lets the request flow through the real Spring stack, asserts the row landed in the real Postgres, asserts the event fired.
- ❌ `HardConstraintsControllerIT` mocks `HardConstraintsServiceImpl` — defeats the purpose; that's a unit test in disguise.

**Test class naming:** `<Subject>IT.java` (Failsafe runs `*IT.java`; Surefire runs `*Test.java`). Keeps fast unit tests on every build, slow ITs on the integration phase.

**Database state:** each test class gets a fresh container OR shared container with `@Sql` cleanup. Pick per module; document in the IT class header. Default: shared container, transactional rollback.

### Layer 3 — Contract tests (OpenAPI ↔ controller)

**What they catch:** drift between the spec and the implementation. The spec is the frontend contract; if the controller diverges silently, the frontend breaks.

**Tooling:** [`atlassian/swagger-request-validator`](https://bitbucket.org/atlassian/swagger-request-validator/) — Spring integration validates every request and response against the OpenAPI spec at runtime in tests. Easy: a single `@TestConfiguration` adds the validator interceptor; ITs that already exercise the endpoint also exercise the contract.

**What it asserts:**
- Request body schema matches what the controller accepts
- Response body schema matches what the controller returns
- Status codes listed in the spec are the only ones the controller can produce
- Required fields are validated
- Enum values match

**No separate contract tests file** — the validator runs as part of every controller IT. One toggle on the IT base class.

### Layer 4 — Performance tests (k6)

**What they catch:** regressions in per-endpoint latency under load. Catches accidental N+1 queries, missing indexes, slow new code paths.

**Tooling:** [k6](https://k6.io/) — JS scripts, runs in Docker, integrates with GitHub Actions. Each endpoint gets a k6 script under `perf/<module>/<endpoint>.js`.

**Per-endpoint budget:** defined in the ticket (e.g. `POST /api/v1/preferences/hard-constraints: 50ms median, 100ms p95`). Set conservatively at first; tighten as we learn real performance.

**CI behaviour:** runs on every PR against a known dataset. PR fails if median > budget OR p95 > 2× budget. Doesn't run mutation testing on perf budgets — they're sanity checks, not exhaustive.

**Realistic data:** k6 scripts use a seeded test database with ~100 users, ~500 recipes, ~50 plans of history. Seeded once per CI run via Flyway repeatable migration `R__perf_seed.sql`.

### Layer 5 — E2E flow tests (REST Assured)

**What they catch:** cross-module flow bugs. Examples:
- "Mark meal cooked → pantry deducted → nutrition logged → events fire in correct order"
- "Submit feedback → classified → routed to preference → taste profile updated → embedding re-queued"
- "Generate plan → grocery list → mark items bought → pantry updated → re-plan reads new state"

**Tooling:** [REST Assured](https://rest-assured.io/) — real HTTP calls (not MockMvc). Runs against a Testcontainers Postgres + the full Spring app on a random port. Real serialisation, real headers, real status codes.

**Class naming:** `<Flow>E2ETest.java` — same Failsafe handling as ITs but slower; runs on the integration phase only, not per PR. Per merge to main.

**Coverage:** ~10-15 e2e flows total. Not exhaustive — they're the "does the whole system hang together" check. Per-module ITs catch most bugs; e2e catches the integration-between-modules class.

### Layer 6 — Mutation testing (Pitest)

**What it catches:** **the hollow-tests problem.** Pitest mutates production code (changes operators, swaps return values, removes statements) and runs the test suite. If the test suite still passes, the mutation went uncaught — which means the tests don't actually exercise that line.

**This is the single most effective gate against agent-written tests that look thorough but aren't.**

**Tooling:** [Pitest](https://pitest.org/) + `pitest-junit5-plugin`. Runs as a Maven goal `mvn test-compile pitest:mutationCoverage`.

**Threshold:** ≥70% mutation score per module. Hard CI gate. Below = ticket fails.

**What 70% means:** Pitest applies ~10-30 mutations per method; 70% of those mutations must be caught (the test suite fails on a mutated version of the code). 100% is rarely achievable; 70% catches almost all hollow tests.

**Cost:** Pitest is slower than the regular test run (~3-5×). Runs in CI on PRs touching production code; locally devs can run on changed files only.

**Anti-patterns Pitest catches:**
- Tests that only call methods without asserting outputs
- Tests that mock everything and verify nothing
- Tests that assert "method was called" but not "method produced X"

---

## Verification model — agent self-verify + harness pre/post flight

Locked 2026-05-07 after the project-setup pilot exposed the gap.

**Agents must run `./mvnw verify` (and the other CI gates) themselves and iterate until green.** A ticket is not "done" from the agent's side until the build is green locally. Two pom artefact-resolution bugs slipped past the project-setup agent because it couldn't run Maven; this is the single biggest workflow risk and we close it here.

### Required tools available to every implementer agent

- `./mvnw` (Maven wrapper) — run `compile`, `verify`, `pitest:mutationCoverage`, `spotless:check`, etc.
- `git` — branch, commit, push.
- `docker` — Testcontainers needs the Docker daemon for the IT layer.
- `npx` — for `swagger-cli validate src/main/resources/openapi/openapi.yaml`.

If the agent's sandbox blocks any of these, the ticket cannot ship per this playbook. The agent must report the blocker explicitly; the harness either escalates permissions or runs the verification on the agent's behalf as fallback.

### The agent's verify-and-iterate loop

After writing implementation + tests:

1. `./mvnw clean compile` — must succeed before going further (catches pom-level issues fast)
2. `./mvnw test` — unit tests must pass
3. `./mvnw verify` — full suite including ITs (Testcontainers spins Postgres)
4. `./mvnw pitest:mutationCoverage` — mutation score ≥70% on the ticket's production code
5. `./mvnw spotless:check` — formatting clean
6. `npx -y @apidevtools/swagger-cli validate src/main/resources/openapi/openapi.yaml` — spec valid

If any step fails: read the error, fix, re-run. **Maximum 3 iteration attempts** per ticket. After that, the agent reports the failing state with diagnosis and stops.

### Harness-side pre-flight check (5 minutes before launching)

Before launching an implementer agent, the harness (or you / me running Bash) verifies the **starting state is clean**:

```
./mvnw dependency:resolve -q   # catches artefact-name mistakes left by previous tickets
./mvnw compile -q              # confirms the codebase compiles
./mvnw test -q                 # confirms existing tests pass
```

If pre-flight fails, fix the prior state before launching the next agent. Don't pile bugs on bugs.

### Harness-side post-flight check (after agent reports done)

After the agent reports "done," before user review:

```
./mvnw clean verify            # full re-run; catches anything the agent missed
./mvnw pitest:mutationCoverage # confirms mutation score
```

If post-flight fails: feed the failure back to the same agent (via `SendMessage` to its agent ID) for follow-up. If the agent has timed out, spawn a "follow-up fix" agent with the failure log.

### When agents truly cannot run the gates

Sandbox limitations are real; not every environment grants `mvn` / `docker` / `git`. **The agent's correct behaviour when blocked is to STOP IMMEDIATELY and report**, NOT to ship speculative code. Validated by the core-01 pilot: the agent hit `Permission to use Bash has been denied` on `git checkout -b`, recognised it as the sandbox limitation the playbook calls out, and stopped before writing a single source file. That's the desired behaviour.

Three fallbacks when the implementer agent is blocked:

- **Grant permissions and resume.** If the harness operator can elevate permissions (allow-list `git`, `./mvnw`, `mvn`, `npx`, `docker`), do so and resume the same agent via `SendMessage` — preserves context. Cheapest if the limitation is artificial.
- **Pair-mode:** Implementer agent writes code; harness operator (or a separate Verifier agent with broader permissions) runs the gates and feeds failures back via `SendMessage`. Mechanical; doubles round-trips per ticket but works in any sandbox.
- **Manual verification:** Harness operator runs the gates between every agent step. Slowest. Only acceptable for the first ~5-10 tickets while the pattern matures, then re-evaluate.

The default model is **agent self-verify**. Fallbacks exist for environments that can't support it. **Never** silently ship unverified code. The cost of a few hours of harness operator time to verify on the agent's behalf is enormously less than the compounding cost of bugs piling on bugs across 75 tickets.

### Common compatibility traps caught in pilots

These bugs all surface compile-time. None surface in the LLD design. **Future tickets must avoid; the style guide's [verified pom block](style-guide.md#verified-deps-2026-05-07-spring-boot-325--java-17) and anti-list catalogue them all.**

| Caught in | Trap | Lesson |
|---|---|---|
| project-setup pilot | `flyway-database-postgresql` artefact added to pom; doesn't exist for Spring Boot 3.2's Flyway 9 | When choosing artefacts, check the Spring Boot version's BOM — many "obvious" artefacts only exist in newer Spring Boot versions |
| project-setup pilot | `swagger-request-validator-spring` (no `mvc` suffix) — guessed artefact name; doesn't exist | Don't guess Maven artefact names from intuition; verify against `mvnrepository.com` or the project's official docs |
| project-setup pilot | `OpenApiValidationInterceptor` extends Spring 5's deprecated `HandlerInterceptorAdapter`, removed in Spring 6 / Spring Boot 3.x | Check the deprecation status of any Spring class an external lib references; prefer Filter-based (servlet-spec) over Interceptor-based wherever possible |
| project-setup pilot | `@ServiceConnection` from `org.springframework.boot.testcontainers.service.connection` requires `spring-boot-testcontainers` test-scoped artefact (not transitive via `spring-boot-starter-test`) | If you're using Spring Boot 3.1+ Testcontainers conveniences, add `spring-boot-testcontainers` explicitly |

If you encounter a NEW compatibility trap, add a row here AND to the style guide's anti-list. These two docs are the institutional memory; they save the next ticket from rediscovering the same gotcha.

---

## Quality gates per ticket — CI checks

Every PR runs:

```
mvn clean verify           # compile + unit tests + integration tests + JaCoCo
mvn pitest:mutationCoverage # mutation score ≥70% per touched module
mvn spotless:check         # lint
mvn archunit:test          # module boundary checks
k6 run perf/<changed>.js   # perf budget per touched endpoint
swagger-cli validate openapi.yaml  # OpenAPI spec well-formed
```

If any fail, PR is red, can't merge. Branch protection enforces.

Per-merge to `main`:
- E2E suite (REST Assured)
- Redocly static-site rebuild + deploy
- Tag the build artefact

---

## Tooling stack — locked

| Concern | Tool | Notes |
|---|---|---|
| Unit framework | JUnit 5 + AssertJ + Mockito | Spring Boot default |
| Integration framework | `@SpringBootTest` + MockMvc + Testcontainers (postgres:16-alpine) | MockMvc faster than REST Assured for ITs |
| Contract testing | `swagger-request-validator` Spring integration | Spec is source of truth; validator runs in every IT |
| Performance | k6 | JS scripts; runs in Docker; CI-integrated |
| E2E | REST Assured | Real HTTP; runs on merge, not per PR |
| Mutation testing | Pitest + pitest-junit5-plugin | 70% threshold; per-module |
| Coverage | JaCoCo | 80% line, 70% branch |
| Lint | Spotless + google-java-format | Auto-formats; CI fails on violations |
| Architecture | ArchUnit | Module boundary rules; runs as test |
| OpenAPI source | Hand-authored YAML in `src/main/resources/openapi/openapi.yaml` | Springdoc generates the runtime spec from controllers as a verification step |
| API docs | Redocly OSS CLI | Static HTML rebuilt on each main merge; served as `/docs/api/` |
| CI | GitHub Actions | Per-PR + per-merge workflows |
| Test data | Test Data Builders pattern (`<Entity>TestData.builder()...build()`) | Avoids fragile fixtures; per-module |
| Commit format | Conventional Commits (`feat:`, `fix:`, `test:`, `docs:`, `refactor:`, `chore:`) | Standard tooling |
| Branch model | `main` + `feature/<module>-<feature>` | Squash-merge to main |
| PR review | One-pass by user | Until pattern is trusted (~5-10 PRs); revisit then |

The full pom.xml additions are in [`style-guide.md §pom.xml additions`](style-guide.md#quick-reference--pomxml-additions). Plus add Pitest, k6 isn't a Maven thing (Docker), and `swagger-request-validator` for contract testing.

---

## Git workflow

### Branching

- `main` — protected. Squash-merge only. Branch protection: passing CI + 1 approving review.
- `feature/<module>-<feature>` — per ticket. Naming matches the ticket file's path. Examples:
  - `feature/auth-register-login`
  - `feature/preference-hard-constraints`
  - `feature/planner-stage-c-prompt-template`
- `chore/<description>` — for non-feature work (CI tweaks, dependency bumps, lint fixes).
- No long-lived branches beyond `main`. Feature branches live ≤ 5 days; older = stale, rebase or close.

### Commit messages — Conventional Commits

```
<type>(<scope>): <subject>

<body, optional>

<footer, optional>
```

Types: `feat | fix | test | docs | refactor | chore | perf`. Scope: module name. Subject: imperative, ≤72 chars. Examples:

- `feat(preference): add hard-constraints CRUD endpoints`
- `fix(planner): correct cost-confidence regression in stage-c rollup`
- `test(provisions): add edge cases for cook-event idempotency`
- `refactor(auth): extract token-hashing helper`

### PR template

Every PR description follows this template (configured in `.github/pull_request_template.md`):

```
## Ticket
Link to tickets/<module>/<NN>-<feature>.md.

## Summary
One paragraph.

## Edge-case checklist (copy from ticket)
- [x] (each item ticked or explicitly skipped with justification)

## Test additions
- Unit: <list>
- Integration: <list>
- Performance: <list, if applicable>

## OpenAPI changes
- (none / lists endpoints added or modified)

## Risk / Rollback
What could break? How do you roll this back?
```

### Review protocol

1. Agent opens PR. CI runs; agent waits for green build.
2. If red, agent fixes; re-pushes. Loop until green.
3. Once green, agent posts the PR summary to the user (no auto-merge).
4. User reviews:
   - Read the ticket.
   - Read the PR diff.
   - Spot-check 1-2 tests for hollowness (mutation score is the safety net but eyeballing matters).
   - Eyeball the OpenAPI diff if there is one.
   - Approve / request changes / reject.
5. On approve: squash-merge.

If the user approves with comments, the agent applies them in a follow-up PR. The original PR has merged; the comments become a small chore ticket.

---

## Branching + conflict minimisation

The 14 modules each own a prefix of database tables and a Java sub-package. **Conflicts arise primarily from:**

1. Cross-module dependencies — module B depends on module A's QueryService. Land A first.
2. Shared infrastructure — `core` module, `application.properties`, top-level `pom.xml`, OpenAPI base file.
3. Cross-cutting changes — `style-guide.md` updates, conventions changes.

**Conflict-minimisation rules:**

- **One ticket touches one module's package** in `src/main/java/com/example/mealprep/<module>/`. If a ticket needs to change two modules, it must be split or rejected.
- **Cross-module interface changes (a service interface)** land in their own ticket, with no implementation changes — purely interface evolution. Then dependents update in follow-up tickets.
- **OpenAPI changes** are isolated per-controller block; the YAML is structured so each module's endpoints are in a contiguous block (anchor comments `# ===== preference =====` / `# ===== nutrition =====`), reducing merge conflict surface.
- **Migrations are uniquely-timestamped per the locked V<YYYYMMDDhhmmss>__ convention.** No conflicts possible.
- **Top-level pom.xml** changes (new dependencies, plugin tweaks) are their own `chore:` tickets, not bundled with feature work. Keeps the pom diff readable in PRs.
- **Style-guide / convention updates** are `docs:` tickets affecting only `lld/` files, never code. Land separately.

---

## Module dependency graph + wave ordering

Three waves. Within each wave, modules are independent and tickets can land in parallel.

### Wave 1 — Foundation (must land first; ~1-1.5 weeks single-dev)

Cross-cutting, depended on by all other modules. Land in this order:

1. **Project setup** (1 ticket): pom updates, package rename, profile config, Spring Security 6 baseline, OpenAPI base YAML, ArchUnit base rules, Pitest config. Single ticket; gates everything else.
2. **`core` module** (3-4 tickets): types/enums, sealed event base interfaces, `decision_log` table + service, `LockService`. Depended on by every other module.
3. **`auth` module** (4-5 tickets): User entity, register/login/logout endpoints, session management, CurrentUserResolver. Depended on by every other module for `userId` resolution.
4. **`ai` module** (4-5 tickets): `AiService` interface + Anthropic provider, `AiTask` SPI, `EmbeddingTask` SPI + OpenAI provider, prompt template loading, cost-cap state machine, call log. Depended on by all AI-using modules.

### Wave 2 — Data models (depend on Wave 1; mostly independent of each other; ~3-4 weeks parallel)

5. **`preference`** (5-6 tickets): hard-constraints aggregate, taste-profile + versions + archive, lifestyle-config, profile-metadata, HardConstraintFilterService, embedding pipeline integration.
6. **`nutrition`** (6-7 tickets): targets aggregate (with goal + enforcement_direction), per-meal distribution, micro targets, intake day + slot + snack, food/mood journal, ingredient mapping cache + IngredientMappingPipeline, NutritionFloorGateService, health directives.
7. **`provisions`** (5-6 tickets): inventory + tracking_mode + cook-event idempotency, equipment, budget, supplier products, waste log, staples, expiry sweep job.
8. **`recipe`** (catalogue half, 6-7 tickets): recipes + versions + branches, substitutions, ingredients + method steps, metadata + tags, imports, embedding column + pipeline, RecipeWriteApi for the adaptation pipeline, archive scan job.
9. **`household`** (3-4 tickets): household + members + settings, invites, HouseholdMergeService.

### Wave 3 — Orchestrators (depend on data models; ~3-4 weeks parallel)

10. **`recipe-extraction-pipeline`** (3-4 tickets): RecipeExtractionService, 5-layer stack, cache, `FromUrl` + `FromHtml` modes. Used by recipe and discovery.
11. **`discovery`** (3-4 tickets): jobs, sources (curated + Google CSE), scrape log, RecipeSiteExtractor SPI, AI candidate filter via prompt #7.
12. **`adaptation-pipeline`** (4-5 tickets): four trigger flows, pending-changes, fingerprints, planner-hints, NutritionalKnowledgeService SPI, prompt #5 wiring.
13. **`feedback`** (4-5 tickets): submission, classification (prompt #4), routing to 4 destinations (3 + adaptation pipeline), misclassification correction, clarification queries.
14. **`planner`** (6-8 tickets): plan + day + slot + scheduled-recipe entities (Option B normalised), beam-search engine, scoring engine + 7 sub-score calculators, rollup builder, Stage C invoker (prompt #8), Phase 2 augmenter (prompt #9), mid-week re-opt coordinator, lifecycle state machine.
15. **`grocery`** (5-6 tickets): shopping list (Tier 1), manual fulfilment (Tier 2), grocery order via GroceryProvider (Tier 3), price history (Tier 4), Tesco automation impl, substitution flow.
16. **`notification`** (3-4 tickets): notification + preferences + delivery log, DeliveryChannel SPI + InAppDeliveryChannel, event listeners across all modules, debouncer.

### Wave 4 — Polish & cross-cutting (~1 week)

17. **Decision-log integration verification** (1 ticket): assert all loops write to decision_log; trace IDs propagate; view endpoint works.
18. **E2E flow suite** (1-2 tickets): the ~10-15 cross-module flows.
19. **Performance test infrastructure** (1 ticket): k6 base scripts, seeded perf DB.
20. **Production readiness** (1 ticket): logging config, observability beyond decision log, SBOM, basic ops dashboard if any.

**Total: ~75-90 tickets across 4 waves. Linear single-dev: ~10-12 weeks. With agents working multiple modules in parallel within a wave: ~5-7 weeks compressed.**

---

## Ticket breakdown — concrete examples

For each module, tickets split per-feature. Examples (not exhaustive):

### `auth` module tickets

1. `auth-01-user-entity-and-registration` — User entity + migration + `POST /register` + tests
2. `auth-02-login-session-token` — Session entity + migration + `POST /login` (Set-Cookie) + tests
3. `auth-03-logout-and-me-endpoints` — `POST /logout` + `GET /me` + filter chain + tests
4. `auth-04-password-change` — `PUT /password` + bulk session revoke + tests
5. `auth-05-throttling-and-lockout` — login attempt tracking + 5/15 min lockout + 423/429 + tests

### `preference` module tickets

1. `preference-01-hard-constraints` — hard_constraints aggregate + 5 endpoints + HardConstraintFilterService + tests
2. `preference-02-taste-profile-storage` — taste_profile + versions + archive entities + migrations
3. `preference-03-taste-profile-delta-application` — `applyTasteProfileDeltas` + delta validator + `TasteProfileDeltaApplier` + tests (calls AI is in adaptation-pipeline ticket; this one only applies deltas)
4. `preference-04-lifestyle-config` — lifestyle config aggregate + 4 endpoints + tests
5. `preference-05-profile-metadata` — metadata + endpoints + tests
6. `preference-06-soft-preference-bundle` — `getForPlanner` aggregate query + tests
7. `preference-07-taste-vector-pipeline` — embedding column + async re-embed listener + integration with ai module

### `planner` module tickets

1. `planner-01-plan-data-model` — Plan + Day + MealSlot + ScheduledRecipe entities + migrations + repositories
2. `planner-02-plan-lifecycle-state-machine` — state transitions + supersession + revert + tests
3. `planner-03-plan-query-service` — read API + history endpoints + tests
4. `planner-04-beam-search-engine` — Stage A composition + tests against canonical plan candidates
5. `planner-05-scoring-engine-and-subscores` — 7 sub-score calculators + composition + tests (math verification)
6. `planner-06-rollup-builder` — Stage B aggregation + tests
7. `planner-07-stage-c-invoker` — Stage C LLM call via prompt #8 + tests with TestAiService
8. `planner-08-phase2-augmenter` — Phase 2 LLM call via prompt #9 + tests
9. `planner-09-mid-week-reopt` — re-opt triggers + state-based pinning + tests
10. `planner-10-rest-controllers` — `POST /plans/generate` + accept + reject + revert + abandon + slot-state + tests
11. `planner-11-event-listeners` — ProvisionChanged/Nutrition/Preference/HouseholdConfig listeners + materiality filters + tests
12. `planner-12-decision-log-integration` — every loop iteration writes a decision-log row; trace IDs propagate

(Other modules similar shape.)

---

## Pilot tickets

Before scaling to ~80 tickets via agents, run **two pilot tickets** end-to-end manually (or with a single agent under your direct oversight). These exercise the full vertical slice and prove the playbook works.

**Pilot 1: `core-01-decision-log`**
- Smallest cross-cutting module
- New tables, new service, new test patterns
- Establishes: migration template, entity template, service template, unit + IT pattern, mutation testing, OpenAPI for admin endpoint, contract test, k6 baseline
- ~1 day of work

**Pilot 2: `auth-01-user-entity-and-registration`**
- Single endpoint, full ladder
- Exercises auth-flavoured code (security-sensitive)
- Validates the security review pass works
- ~1 day of work

After pilots:
- Review what worked / didn't
- Update this playbook if needed
- Refine the agent dispatch prompt
- Then spawn agents for waves 1.3-1.4 (auth-02 through ai-04 etc.) in parallel where possible

---

## Agent dispatch model

### Per-ticket agent spawn

Agent prompt includes:
1. The ticket file (`tickets/<module>/<NN>-<feature>.md`) verbatim
2. The relevant LLD(s) — e.g. `lld/preference.md` for preference tickets
3. This playbook (`lld/implementation-playbook.md`) — the contract
4. The style guide (`lld/style-guide.md`)
5. Path to recent merged PRs in the same module (as concrete examples of the patterns)

Agent task:
1. Read the ticket and the behavioural spec.
2. Create a feature branch.
3. Write the OpenAPI changes first, if any.
4. Write the tests (unit + IT) per the behavioural spec, BEFORE the implementation.
5. Write the implementation.
6. Run the full CI locally; fix any reds.
7. Push and open the PR with the template filled in.
8. Tag the user for review.

### Ticking the edge-case checklist

The agent fills in the PR template's checklist. **Every item must have a corresponding test, OR a justification for skipping.** Reviewer (you) verifies each test exists and looks meaningful.

### Hollow-test enforcement — the safety net

Layered defence:

1. **Behavioural spec written first** in the ticket — agent writes tests against the spec, not the impl.
2. **Mutation testing as CI gate** — Pitest fails the build if mutation score < 70%. Hollow tests don't catch mutations; this is the strongest signal.
3. **No-mocking-within-module rule** in the playbook — agents instructed; reviewer eyeballs the test imports.
4. **Edge-case checklist** in the PR template — every checkbox must trace to a real test.
5. **Reviewer spot-check** — for ~10 random tests per PR, can you describe what bug would slip through if the test were deleted? If "none," the test is hollow.

### Failure modes during dispatch

| Failure | Response |
|---|---|
| Agent's PR fails CI | Agent fixes and re-pushes. Up to 3 retries before bailing. |
| Agent's mutation score is 65% (just below threshold) | PR rejected. Agent must improve test quality. |
| Agent introduces cross-module repository import (ArchUnit fails) | Hard reject. Architecture violation. |
| Agent's tests look okay but reviewer spots hollowness | Reviewer flags specific tests; agent rewrites. |
| Agent's PR scope exceeds the ticket | Reviewer requests split. Agent splits into ≥2 PRs. |
| Agent's PR conflicts with main during review | Agent rebases. If conflict is non-trivial, ticket stays in flight while another agent's work lands first. |

### Agent context budget

A single agent's context handles: ticket (~200 lines) + relevant LLD (~800 lines) + playbook (~1000 lines) + style guide (~600 lines) + 1-2 example PRs (~500 lines) + the actual code generation. ~6-8k tokens fixed context + the work itself. Comfortably within budget.

---

## Frontend coordination

The OpenAPI spec at `src/main/resources/openapi/openapi.yaml` is **the contract**. Frontend can begin implementation against this spec without waiting for backend; the contract test layer guarantees the impl matches the spec.

**Decisions bound to the contract (locked early):**

- Auth: cookie-based session, `Set-Cookie: AUTH_SESSION=...` per `lld/auth.md`.
- Errors: RFC 9457 ProblemDetail, `application/problem+json`, fields per `lld/style-guide.md`.
- Pagination: `?page=&size=`, `Page<T>` payload shape per style guide.
- Filtering: per-endpoint specific query params, not a generic DSL.
- IDs: UUIDs throughout.
- Dates: ISO 8601.
- Currency: minor units in DB (`pence`); rendered as decimal in API.

**What's intentionally fluid (not in the contract; safe for frontend to ignore):**

- Internal service interface signatures (Java-side; not visible)
- Decision-log internals (audit-only; not consumed by frontend)
- Performance budgets (CI concern)
- Migration sequence numbers (DB concern)

**Frontend-driven feedback:**

When frontend hits a backend gap (missing endpoint, missing field, wrong shape), it gets logged as a `chore:` ticket against the relevant module. These are expected; the backend planning minimises them but doesn't eliminate them. Budget: ~10-20% of backend tickets will be frontend-feedback-driven.

**The OpenAPI spec is the user-readable artefact.** Redocly publishes it as `/docs/api/`. Frontend devs read it directly. No "API documentation" task lives separately — generating the docs is part of every backend ticket.

---

## Out of scope

- **Frontend implementation** — separate phase; this playbook only covers backend
- **Browser automation specifics for Tesco** — gray area; lives in the grocery module but the actual Selenium / playwright code is its own track
- **Production deployment automation** — local / self-hosted per system-overview; Docker compose suffices for v1
- **Multi-tenant / scaling strategy beyond a single household** — not v1
- **Localisation** — English only in v1
- **Mobile** — web-first; PWA later
- **SBOM / supply-chain scanning** — v2 concern
- **Cost-monitoring beyond AI cost cap** — v2 concern
- **Disaster recovery / backups** — basic Postgres dump suffices for v1

---

## Open items / first edits to this playbook

Things this playbook leaves under-specified; refine after pilot tickets land:

1. **k6 budget defaults** — set per-endpoint conservatively in the first ~10 tickets; tighten based on real measurements.
2. **Mutation score threshold** — 70% as starting point; might need to drop to 60% for some classes (e.g. ones dominated by mappers / DTOs).
3. **Test data builders** — first builder lands as part of pilot 2; pattern crystallises after 2-3 modules.
4. **Performance seeded DB content** — defined in a single ticket; first version may be sparse and grow.
5. **Cross-module e2e flow list** — sketched at ~10-15 flows but the actual list depends on what bugs show up; revisit after Wave 2 lands.
