# MealPrep AI — Backend

Spring Boot 3.2 / Java 17 backend for the MealPrep AI personal meal-planning system.

The application is structured as a single deployable unit with internally-segregated modules
(`auth`, `core`, `preference`, `nutrition`, `provisions`, `recipe`, `planner`, `feedback`,
`discovery`, `adaptation-pipeline`, `household`, `ai`, `grocery`, `notification`) following
the boundary rules in [`lld/style-guide.md`](lld/style-guide.md).

## Status

14 modules with full HTTP surface. ~133 of 222 design-intent capabilities implemented
(see [`design/audits/2026-05-21-backend-state-audit.md`](design/audits/2026-05-21-backend-state-audit.md)
for the gap inventory). Tier A frontend-readiness shipping in the current wave; Tier B
(preference Tier 2/3 + feedback bridges + notifications + ratings + discovery wiring) in flight
per [`design/audits/2026-05-21-frontend-readiness-roadmap.md`](design/audits/2026-05-21-frontend-readiness-roadmap.md).

## Where to start

- [`design/README.md`](design/README.md) — system overview, architectural decisions, optimisation loop.
- [`design/audits/`](design/audits/) — current state, capability inventory, frontend-readiness roadmap.
- [`lld/README.md`](lld/README.md) — per-module low-level designs and the implementation playbook.
- [`lld/implementation-playbook.md`](lld/implementation-playbook.md) — per-ticket workflow, test ladder, CI gates.
- [`tickets/`](tickets/) — implementation tickets, grouped by module.

## Local development

```bash
# Copy the environment template and fill in your secrets
cp .env.example .env
# Edit .env — at minimum set ANTHROPIC_API_KEY and OPENAI_API_KEY

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

# Health check (after starting the app)
curl http://localhost:8080/actuator/health
curl http://localhost:8080/actuator/info
```

The default profile is `dev` (local Postgres + permissive CORS for `http://localhost:5173`).
Integration tests run in the `test` profile (Testcontainers). Production runs in `prod`
(env-var-only secrets, no CORS, no Swagger UI).

### CI gates

Hard-gated on `main`:

| Gate | Threshold |
|---|---|
| JaCoCo line coverage | 88% |
| JaCoCo branch coverage | 74% |
| Pitest mutation coverage | 73% |
| Spotless (google-java-format) | clean |

See the `<rules>` block in `pom.xml` for the merged unit+IT JaCoCo configuration and the
`mutationThreshold` setting for Pitest.

### Operational endpoints

Spring Boot Actuator is configured to expose **only** `/actuator/health` and `/actuator/info` —
nothing else. Other actuator endpoints (`/env`, `/configprops`, `/beans`, `/mappings`,
`/threaddump`, `/heapdump`, `/loggers`, etc.) are deliberately **NOT** exposed because they
leak configuration, environment variables, and internal structure. The exposure allow-list
lives at `management.endpoints.web.exposure.include` in
[`src/main/resources/application.properties`](src/main/resources/application.properties);
the matching Spring-Security permit-list is in
[`src/main/java/com/example/mealprep/auth/config/AuthSecurityConfig.java`](src/main/java/com/example/mealprep/auth/config/AuthSecurityConfig.java).
Both must name a path for it to be publicly reachable.

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
| AI | Anthropic Claude (multi-tier: Haiku / Sonnet / Opus) + OpenAI embeddings |
| External data | USDA FoodData Central, Open Food Facts |

See [`lld/style-guide.md`](lld/style-guide.md) for the full set of conventions.

## Frontend integration

Frontend (Vite + React, separate repo) runs at `http://localhost:5173` in dev. The backend's
dev profile permits this origin via `tickets/core/02a-cors-config-dev-profile.md`. Frontend
generates TypeScript types from `src/main/resources/openapi/openapi.yaml`.
