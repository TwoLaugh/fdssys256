# MealPrep AI — Backend

Spring Boot 3.2 / Java 17 backend for the MealPrep AI personal meal-planning system.

The application is structured as a single deployable unit with internally-segregated modules (`auth`, `preference`, `nutrition`, `provisions`, `recipe`, `planner`, …) following the boundary rules in [`lld/style-guide.md`](lld/style-guide.md).

## Where to start

- [`design/README.md`](design/README.md) — system overview, architectural decisions, optimisation loop.
- [`lld/README.md`](lld/README.md) — the per-module low-level designs and the implementation playbook.
- [`lld/implementation-playbook.md`](lld/implementation-playbook.md) — the per-ticket workflow, test ladder, and CI gates.
- [`tickets/`](tickets/) — implementation tickets, grouped by module.

## Local development

```bash
# Bring up local Postgres + supporting services
docker compose up -d

# Run unit + integration tests (Testcontainers spins up its own Postgres for ITs)
./mvnw clean verify

# Spotless format check / apply
./mvnw spotless:check
./mvnw spotless:apply

# Mutation testing (Pitest)
./mvnw pitest:mutationCoverage

# Validate the OpenAPI spec
npx -y @apidevtools/swagger-cli validate src/main/resources/openapi/openapi.yaml
```

The default profile is `dev` (local Postgres). Run integration tests in the `test` profile (Testcontainers). Production runs in `prod` (env-var-only secrets).

## Tech stack

| Concern | Tool |
|---|---|
| Language | Java 17 |
| Framework | Spring Boot 3.2 |
| Database | PostgreSQL 16 + pgvector |
| Migrations | Flyway |
| ORM | Spring Data JPA + Hibernate |
| Testing | JUnit 5, Testcontainers, swagger-request-validator, ArchUnit |
| Lint | Spotless + google-java-format |
| Mutation | Pitest |
| API spec | Hand-authored OpenAPI 3 in `src/main/resources/openapi/openapi.yaml` |

See [`lld/style-guide.md`](lld/style-guide.md) for the full set of conventions.

## Status

Bootstrap (project-setup ticket) only — no user-facing endpoints yet. Wave 1 (`core`, `auth`, `ai`) lands next.
