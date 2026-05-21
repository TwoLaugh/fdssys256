# Ticket: infra — 01a Repo Hygiene (actuator + .env.example + README refresh)

## Summary

Three bundled-but-discrete deliverables, all in one PR because each is small enough that splitting them adds more PR churn than it saves. Per roadmap A3 in [`design/audits/2026-05-21-frontend-readiness-roadmap.md`](../../design/audits/2026-05-21-frontend-readiness-roadmap.md) and the "Ops/hygiene rough edges" cluster in [`design/audits/2026-05-21-backend-state-audit.md`](../../design/audits/2026-05-21-backend-state-audit.md).

The three deliverables:

- **(a)** Add `spring-boot-starter-actuator` to `pom.xml` and expose **only** `/actuator/health` and `/actuator/info`. Explicitly do NOT expose `/actuator/env`, `/actuator/configprops`, `/actuator/beans`, `/actuator/mappings`, `/actuator/threaddump`, or any other endpoint — those leak configuration, env vars, and internal structure.
- **(b)** Add `.env.example` at repo root documenting the required environment variables (`DB_PASSWORD`, `ANTHROPIC_API_KEY`, `USDA_API_KEY`, `OPENAI_API_KEY`). Update `.gitignore` if `.env` isn't already excluded.
- **(c)** Update `README.md` from its stale state. The current `Status` section claims "Bootstrap (project-setup ticket) only — no user-facing endpoints yet. Wave 1 (`core`, `auth`, `ai`) lands next." Reality: 14 modules with full HTTP surface, ~289 capabilities inventoried, AI infrastructure live. The new README reflects the current architecture, how to run, and references the design docs.

This is **infra-tier**: no entity, no migration, no service code. The only Java change is `application.properties` keys.

Closes: tier-A frontend unblock (not directly inventoried in the capability matrix; per roadmap A3).

## Behavioural spec

### (a) Actuator with minimal exposure

1. Add to `pom.xml` under `<dependencies>`:
   ```xml
   <dependency>
     <groupId>org.springframework.boot</groupId>
     <artifactId>spring-boot-starter-actuator</artifactId>
   </dependency>
   ```
   Version inherited from the Spring Boot parent. No transitive surprises — actuator is a single-purpose starter.

2. Add to `src/main/resources/application.properties`:
   ```properties
   # Actuator: expose ONLY health + info. Never enable env/configprops/etc.
   management.endpoints.web.exposure.include=health,info
   management.endpoints.web.base-path=/actuator
   management.endpoint.health.show-details=when-authorized
   management.endpoint.health.show-components=when-authorized
   management.info.env.enabled=false
   management.info.java.enabled=true
   management.info.os.enabled=false
   management.info.build.enabled=true
   ```
   - `show-details=when-authorized` — unauthenticated callers see `{"status":"UP"}` only. Authenticated callers (the future admin role) see component-level breakdown. This is the standard Spring Boot posture; ensures the public health endpoint doesn't leak DB / Redis / external-API names.
   - `management.info.env.enabled=false` — explicitly disables env var dumping in `/actuator/info`. Defense-in-depth even though `include=health,info` controls exposure at a higher level.

3. **Security posture**: `/actuator/**` is accessible without authentication for the v1 frontend health-check page. The auth module's `SecurityConfig` already permits-all on a few public paths (`/api/v1/auth/login`, `/api/v1/auth/register`); add `"/actuator/health", "/actuator/info"` to that allowlist. **Do NOT** use `permitAll()` on the broader `/actuator/**` pattern — that would auto-expose any endpoint a future contributor accidentally adds.

4. The `/actuator/info` payload should include build metadata. Add the Spring Boot `build-info` goal to the `maven-plugin` config in `pom.xml`:
   ```xml
   <plugin>
     <groupId>org.springframework.boot</groupId>
     <artifactId>spring-boot-maven-plugin</artifactId>
     <executions>
       <execution>
         <goals>
           <goal>build-info</goal>
         </goals>
       </execution>
     </executions>
   </plugin>
   ```
   This generates `META-INF/build-info.properties` containing version/timestamp/git-commit, which `/actuator/info` surfaces.

5. **No custom `HealthIndicator` beans** in this ticket. Spring Boot's auto-configured DB + disk + ping indicators are enough for v1. A future ticket can add `AnthropicHealthIndicator`, `UsdaHealthIndicator` if monitoring needs grow (per roadmap C3 `/api/v1/admin/status`).

### (b) `.env.example` and `.gitignore`

6. New file `.env.example` at repo root (not in `src/`). Format: KEY=value-or-placeholder, one per line, with section comments. Content:
   ```dotenv
   # MealPrep AI — environment variables
   # Copy this file to .env (do not commit) and fill in the values.
   # See README.md and lld/style-guide.md for which modules read which key.

   # ── Database ──────────────────────────────────────────────────────────────
   # The local Postgres password (docker-compose seeds 'mealprep' by default).
   DB_PASSWORD=mealprep

   # ── AI providers ──────────────────────────────────────────────────────────
   # Anthropic Claude (the primary LLM tier — recipe extraction, feedback
   # classification, taste profile delta generation). Required.
   ANTHROPIC_API_KEY=sk-ant-replace-me

   # OpenAI (used ONLY for text-embedding-3-small embeddings — recipe vectors,
   # taste-profile vectors). Required if pgvector indexes are populated.
   OPENAI_API_KEY=sk-replace-me

   # ── External data ────────────────────────────────────────────────────────
   # USDA FoodData Central — ingredient nutrient lookups (free, but rate-limited).
   # Get one at https://fdc.nal.usda.gov/api-key-signup.html
   USDA_API_KEY=DEMO_KEY

   # ── Optional ─────────────────────────────────────────────────────────────
   # Google Custom Search (discovery module — web-recipe search). Optional; the
   # discovery module skips Google-CSE adapters when unset.
   # GOOGLE_CSE_API_KEY=
   # GOOGLE_CSE_ENGINE_ID=
   ```
   Each key includes a brief explanation of which module consumes it. The placeholders are intentionally obvious (no real-looking key strings) so a developer accidentally committing `.env.example` doesn't ship a leaked secret.

7. Ensure `.gitignore` contains:
   ```
   .env
   .env.local
   .env.*.local
   ```
   The first line is the critical one. Verify against the current `.gitignore`; if `.env` is absent, add it. If already present, no change.

8. **Do NOT add `.env.example` to the codebase if it already exists** — verify first via `ls .env.example`. If a prior agent already shipped one, this ticket reconciles content to the spec above rather than overwriting.

### (c) README refresh

9. Update `README.md` `Status` section. Current text (line 53-54): `Bootstrap (project-setup ticket) only — no user-facing endpoints yet. Wave 1 (`core`, `auth`, `ai`) lands next.`

   Replace with a current-state status block. The new README structure:

   ```markdown
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
   ```

10. **Do NOT add a "Roadmap" or "Future work" section to the README** — that lives in `design/audits/2026-05-21-frontend-readiness-roadmap.md`. Don't duplicate.

11. Do NOT change anything else in the existing README structure beyond the `Status` section + the addition of the Frontend integration section + the Where-to-start update (adding `design/audits/`).

### Cross-cutting

12. **No new code beyond `application.properties`, `pom.xml`, `.env.example`, `.gitignore`, `README.md`, and a `SecurityConfig` permit-all addition for the two actuator paths.**
13. **No ArchUnit rules added** — the `management.endpoints.web.exposure.include` property is the only enforcement needed. Future contributors who add an exposure here will be visible in the diff.

### Events

14. **None.** Pure infra.

## Database

**No migration.**

## OpenAPI updates

**No changes to `openapi.yaml`.** Actuator endpoints are intentionally NOT documented in the project OpenAPI spec — they are a Spring Boot platform concern, not part of the application's API contract. Frontend uses the actuator endpoints directly via well-known paths.

## Edge-case checklist

- [ ] `curl http://localhost:8080/actuator/health` returns `{"status":"UP"}` (with no further detail) for anonymous callers.
- [ ] `curl http://localhost:8080/actuator/info` returns a JSON object with `build` keys (version, time, group, artifact) and `java.version` — no `env.*` keys.
- [ ] `curl http://localhost:8080/actuator/env` returns 404 (not exposed). This is the security-critical assertion.
- [ ] `curl http://localhost:8080/actuator/beans` returns 404.
- [ ] `curl http://localhost:8080/actuator/configprops` returns 404.
- [ ] `curl http://localhost:8080/actuator/mappings` returns 404.
- [ ] `curl http://localhost:8080/actuator/threaddump` returns 404.
- [ ] `curl http://localhost:8080/actuator/heapdump` returns 404.
- [ ] `curl http://localhost:8080/actuator/loggers` returns 404.
- [ ] **Test** (`ActuatorExposureIT`): asserts via MockMvc that the two paths return 200 and any other actuator path returns 404. Iterate the candidate list explicitly (env, beans, configprops, mappings, threaddump, heapdump, loggers, conditions, scheduledtasks, caches, sessions, auditevents, httptrace) so a future contributor adding an exposure breaks this test.
- [ ] `META-INF/build-info.properties` is generated by `mvn package` — verify by inspecting the JAR contents.
- [ ] `.env.example` exists at repo root with all four required keys + comments + the optional Google-CSE block.
- [ ] `.gitignore` contains `.env` on its own line.
- [ ] `git status` after creating a fresh `.env` file (copied from the example) shows the `.env` file as untracked-and-ignored (not "untracked").
- [ ] `README.md` `Status` section no longer says "no user-facing endpoints yet".
- [ ] `README.md` `Where to start` section references `design/audits/`.
- [ ] `README.md` `Local development` section includes the `.env.example` copy step.
- [ ] `pom.xml` lints clean (no missing dependencies, no version conflicts surfaced by `./mvnw dependency:tree`).
- [ ] `./mvnw clean verify` passes end-to-end — actuator addition doesn't break any existing test.
- [ ] Spring Security log-line at startup confirms `/actuator/health` and `/actuator/info` are in the public path list, nothing else under `/actuator/**`.

## Files this ticket touches

```
MOD   pom.xml                                                                  (actuator dep + build-info goal)
MOD   src/main/resources/application.properties                                (management.endpoints.* keys)
MOD   src/main/java/com/example/mealprep/auth/config/SecurityConfig.java       (permit-all on 2 actuator paths)
NEW   .env.example                                                              (4 required + 2 optional keys + comments)
MOD   .gitignore                                                                (add .env line if absent)
MOD   README.md                                                                  (Status block + Local dev + Frontend integration)

NEW   src/test/java/com/example/mealprep/infra/ActuatorExposureIT.java          (positive + 14 negative assertions)
```

Total: 1 new test file + 1 new env file + 5 mods. Estimated agent runtime 30-45 min.

## Dependencies

- **Hard dependency**: `auth-01a` (merged) — `SecurityConfig` exists and gates the actuator path-permits.
- **Hard dependency**: project-setup (merged) — pom.xml structure, Spring Boot parent.
- **No module-level dependencies** — pure infra.

## Acceptance / DoD

- [ ] `./mvnw -Dspotless.check.skip=true verify` passes
- [ ] `./mvnw spotless:apply` then `./mvnw spotless:check` clean
- [ ] CI green (build + spotless)
- [ ] All edge-case items above ticked
- [ ] `ActuatorExposureIT` enumerates and asserts 404 on the 14 unwanted actuator endpoints
- [ ] Reviewer eyeball: `management.endpoints.web.exposure.include` is exactly `health,info` — no `*`, no other entries
- [ ] Reviewer eyeball: `.env.example` placeholders are obvious-not-real (e.g. `sk-ant-replace-me`)
- [ ] `git status` on a fresh clone with a copied `.env` shows the `.env` as ignored

## What's NOT in scope

- **Custom HealthIndicator beans** — AnthropicHealthIndicator, UsdaHealthIndicator deferred to roadmap C3.
- **Authenticated `/actuator/health/{component}` detail** — `show-details=when-authorized` is set; the actual admin-role gate is roadmap C1.
- **Prometheus / Micrometer scraping** — out of HLD scope per the audit; SLF4J + Actuator is the chosen stack.
- **`/api/v1/admin/status` custom endpoint** — different ticket per roadmap C3.

Squash-merge with: `chore(infra): 01a — actuator + .env.example + README refresh (Tier A repo hygiene)`
