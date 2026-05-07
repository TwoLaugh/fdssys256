# Ticket: project-setup — 00 Bootstrap

## Summary

Land the foundational tooling and conventions all other tickets depend on: pom dependencies, package rename, Spring profiles, OpenAPI base, ArchUnit boundary rules, JaCoCo + Pitest configs, Spotless, Testcontainers wiring, GitHub Actions CI, PR template, swagger-request-validator wiring. After this ticket merges, every subsequent ticket has a working test ladder to land into.

This is **not** a feature ticket — it has no user-visible behaviour. The "behavioural spec" below is the set of CI / build / tooling outcomes that must hold. Subsequent tickets break if any of these regress.

## Behavioural spec (write this BEFORE implementation)

The implementation must guarantee:

1. `mvn clean compile` succeeds against an empty `com.example.mealprep` package skeleton (only `MealPrepApplication.java` + the bootstrap test class remain).
2. `mvn clean verify` runs unit tests and integration tests; ITs use Testcontainers Postgres 16-alpine; H2 dependency removed from the project entirely.
3. `mvn pitest:mutationCoverage` runs (even with no production code beyond the application class) and writes a report under `target/pit-reports/`. Empty-codebase score is N/A; the gate is that the plugin runs.
4. `mvn spotless:check` passes; `mvn spotless:apply` formats Java files using google-java-format.
5. `swagger-cli validate src/main/resources/openapi/openapi.yaml` succeeds. The base YAML has a valid schema with `info`, `servers`, `paths: {}` (empty), `components.schemas: {}` (empty), and `components.responses` containing the standard ProblemDetail response shape.
6. ArchUnit base rules pass on the empty layout: no class outside `com.example.mealprep.<module>.api` may depend on Spring Web; no class outside `<module>.domain.repository` may import `JpaRepository`. (No-op in an empty codebase but the rules must compile and the test class must run.)
7. Three Spring profiles are active and tested: `dev`, `test`, `prod`. Profile-specific properties files exist with appropriate overrides (Postgres connection per profile, debug logging in dev, validate-only Hibernate ddl-auto in prod).
8. Application starts in `test` profile against a Testcontainers Postgres in `MealPrepApplicationTests`.
9. `@TestConfiguration OpenApiValidatorConfig` exists in `src/test/java/.../testsupport/`. When imported by an IT class, it adds `swagger-request-validator` interceptors that validate every controller request and response against the OpenAPI spec. (No-op for the empty codebase but the wiring exists.)
10. GitHub Actions workflow at `.github/workflows/ci.yml` runs on every push and pull request. Steps: checkout, set up Java 17 + Maven cache, `mvn verify`, `mvn pitest:mutationCoverage`, `mvn spotless:check`, OpenAPI lint, post coverage summary. Workflow runs green on the empty codebase (no production code → mutation gate trivially passes).
11. PR template at `.github/pull_request_template.md` exists; matches the template defined in [`lld/implementation-playbook.md §Git workflow`](../../lld/implementation-playbook.md#git-workflow).
12. `.editorconfig` at the repo root standardises line endings (LF), final newline, charset (UTF-8), 4-space Java indentation.
13. Conventional Commits are linted via a CI check on PR titles. Invalid PR title (e.g. `Update stuff`) blocks the merge.
14. `pom.xml` does NOT include H2; includes Flyway (core + database-postgresql), Lombok, MapStruct + annotation processor, springdoc-openapi-starter-webmvc-ui, hypersistence-utils-hibernate-63, pgvector + openai-java, spring-boot-starter-validation, spring-boot-starter-security, Testcontainers (junit-jupiter + postgresql), swagger-request-validator (Spring integration), Pitest core + JUnit5 plugin. Plugin section configures Lombok then MapStruct annotation processors in correct order.
15. `MealPrepApplication.java` is in package `com.example.mealprep` (no longer `com.example.claudetest`). Test class likewise. The journal scaffold is gone; only `MealPrepApplication` and `MealPrepApplicationTests` exist as Java code.
16. Root-level `README.md` exists at the project root (separate from the existing `design/README.md`); briefly states what the project is, points to `lld/README.md` and `design/README.md`.
17. `application-prod.properties` uses `${ENV_VAR}` references for secrets (`spring.datasource.url`, `spring.datasource.username`, `spring.datasource.password`, `mealprep.ai.anthropic-api-key`, `mealprep.ai.openai-api-key`); never literal values.
18. `@EnableJpaAuditing` is configured globally so future entities can use `@CreatedDate` / `@LastModifiedDate` without per-class wiring.

## OpenAPI spec excerpt

The base YAML at `src/main/resources/openapi/openapi.yaml`:

```yaml
openapi: 3.0.3
info:
  title: MealPrep AI
  description: |
    Backend API for the MealPrep AI personal meal-planning system.

    See the architectural docs in `design/` and the LLDs in `lld/`.
  version: 0.1.0
  contact:
    name: MealPrep AI
servers:
  - url: http://localhost:8080
    description: Local development
  - url: /
    description: Same-origin (production deployment)
paths: {}
components:
  schemas:
    ProblemDetail:
      type: object
      description: RFC 9457 ProblemDetail; the canonical error shape across all endpoints.
      required: [type, title, status]
      properties:
        type:
          type: string
          format: uri
          description: A URI identifying the problem type.
          example: https://mealprep.example.com/problems/validation-error
        title:
          type: string
          description: Short human-readable summary.
          example: Validation failed
        status:
          type: integer
          description: HTTP status code.
          example: 400
        detail:
          type: string
          description: Human-readable explanation specific to this occurrence.
        instance:
          type: string
          format: uri
          description: A URI identifying the specific occurrence.
        errors:
          type: array
          description: Optional field-level validation errors.
          items:
            type: object
            properties:
              field: { type: string }
              message: { type: string }
              rejectedValue: {}
  responses:
    BadRequest:
      description: Validation or syntactic error.
      content:
        application/problem+json:
          schema: { $ref: '#/components/schemas/ProblemDetail' }
    Unauthorized:
      description: Missing or invalid authentication.
      content:
        application/problem+json:
          schema: { $ref: '#/components/schemas/ProblemDetail' }
    NotFound:
      description: Resource not found.
      content:
        application/problem+json:
          schema: { $ref: '#/components/schemas/ProblemDetail' }
    Conflict:
      description: Conflict with current state (optimistic-lock, duplicate, etc.).
      content:
        application/problem+json:
          schema: { $ref: '#/components/schemas/ProblemDetail' }
    UnprocessableEntity:
      description: Semantic error (constraint feasibility, etc.).
      content:
        application/problem+json:
          schema: { $ref: '#/components/schemas/ProblemDetail' }
    TooManyRequests:
      description: Rate limit exceeded.
      headers:
        Retry-After:
          schema: { type: integer }
      content:
        application/problem+json:
          schema: { $ref: '#/components/schemas/ProblemDetail' }
```

Subsequent tickets add paths and schemas.

## Edge-case checklist

- [ ] H2 removed from pom (verify dependency tree)
- [ ] Spotless and google-java-format installed; check fails on a deliberately-malformed file
- [ ] Pitest plugin runs on empty codebase without erroring
- [ ] Testcontainers Postgres starts in CI (verify via integration test that hits a `SELECT 1`)
- [ ] OpenAPI YAML validates; deliberately-broken edit fails the lint
- [ ] ArchUnit test runs and passes; deliberately-introduced cross-module import fails it
- [ ] PR template renders correctly on a test PR
- [ ] Conventional Commit linter rejects an ill-formed PR title in CI
- [ ] All three profiles (`dev`, `test`, `prod`) start the application
- [ ] `prod` profile fails fast if any env var is unset (don't ship with default secrets)
- [ ] `application-test.properties` uses `spring.datasource.url=tc:postgresql:16-alpine:///mealprep` (Testcontainers JDBC URL convention) so `@SpringBootTest` slices auto-spin a container
- [ ] Coverage report published as a CI artifact
- [ ] Mutation report published as a CI artifact
- [ ] Hibernate `ddl-auto=validate` in prod (catches entity/schema drift)
- [ ] Hibernate `ddl-auto=none` in test (Flyway runs first; validate would be redundant)

## Files this ticket touches

```
pom.xml                                                              modified
src/main/java/com/example/mealprep/MealPrepApplication.java          renamed (was claudetest/ClaudeTestApplication)
src/main/resources/application.properties                            modified (defaults + profile activation)
src/main/resources/application-dev.properties                        new
src/main/resources/application-test.properties                       new
src/main/resources/application-prod.properties                       new
src/main/resources/openapi/openapi.yaml                              new
src/test/java/com/example/mealprep/MealPrepApplicationTests.java     renamed
src/test/java/com/example/mealprep/testsupport/TestContainersConfig.java     new
src/test/java/com/example/mealprep/testsupport/OpenApiValidatorConfig.java   new
src/test/java/com/example/mealprep/archunit/ModuleBoundaryTest.java          new
src/test/resources/application-test.properties                       (already exists; modify)
.github/workflows/ci.yml                                             new
.github/pull_request_template.md                                     new
.editorconfig                                                        new
.gitignore                                                           modified (target/, .idea/, etc.)
README.md                                                            new (project root)
spotless config (in pom.xml)                                         new
pitest config (in pom.xml)                                           new
src/main/java/com/example/mealprep/config/JpaAuditingConfig.java     new
```

The journal scaffold's `ClaudeTestApplication.java` and `ClaudeTestApplicationTests.java` are deleted (renamed-to-mealprep); journal-specific files were already removed in the design phase.

## Performance budget

N/A — no endpoints in this ticket. CI workflow run-time itself: target < 5 minutes for the full PR pipeline on a clean cache; < 2 minutes on a warm cache.

## Dependencies on other tickets

None. This is the foundation; nothing precedes it.

## Time estimate

**1.5-2 days.** Substantial because of the surface area (build config, three profiles, CI workflow, multiple tooling integrations) but each piece is well-understood — no design risk.

## Test plan

This ticket's "tests" are the CI workflow itself plus a few sanity tests:

| Test | Layer | What it verifies |
|---|---|---|
| `MealPrepApplicationTests.contextLoads()` | IT | Spring context starts in test profile against Testcontainers Postgres |
| `ModuleBoundaryTest.layeredArchitectureRulesEnforced()` | unit (ArchUnit-as-test) | The base ArchUnit rules compile and run; cross-module repository imports rejected |
| `OpenApiSpecValidatesAtBuildTime` | build | swagger-cli lint runs at `mvn verify` and fails the build on malformed spec |
| `SpotlessChecksFormat` | build | spotless:check runs and rejects unformatted code |
| `PitestRunsOnEmptyCodebase` | build | mutation plugin executes (no mutations to score yet) |

Mutation testing threshold doesn't apply yet (no production code). It will activate as soon as the first feature ticket lands.

## Decisions left to the implementor (small)

1. **Spotless config exact rules** — start with google-java-format defaults; tighten if needed.
2. **ArchUnit rule wording** — the rules express the style guide's module-boundary intent; minor phrasing variation is fine.
3. **PR-title-linter exact tool** — GitHub Action like `commitlint` or `amannn/action-semantic-pull-request`; both work; pick one and document.
4. **Coverage report viewer** — JaCoCo HTML output suffices; no need for Codecov/Coveralls in v1 unless trivial to wire.
5. **`pgvector` extension installation** — install via Flyway migration `V20260601000000__core_install_pgvector.sql` (just `CREATE EXTENSION IF NOT EXISTS vector;`). Belongs to this ticket since `core` is the first module with a vector dependency, but happens to be SQL-side rather than Maven-side.

## Acceptance / DoD

A reviewer (you) opens the PR, sees:

- [ ] CI is green (all gates pass)
- [ ] pom diff is reasonable; no surprise dependencies
- [ ] Three profiles defined; `prod` doesn't accidentally embed secrets
- [ ] OpenAPI YAML lints and is well-structured
- [ ] PR template renders on the PR itself
- [ ] No leftover `claudetest` references anywhere
- [ ] README at root explains the project briefly
- [ ] Edge-case checklist above all ticked

Squash-merge with commit message:

```
chore(setup): bootstrap project — pom, profiles, CI, OpenAPI base, ArchUnit, Pitest, Testcontainers

Lays the foundation for all subsequent feature tickets per
lld/implementation-playbook.md.
```
