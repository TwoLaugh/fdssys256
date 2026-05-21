# Ticket: core — 02a CORS Config (dev profile only)

## Summary

Add a Spring `WebMvcConfigurer` bean active **only under the `dev` Spring profile** that permits the Vite dev server origin (`http://localhost:5173`) to call the REST API. Per [`design/technical-architecture.md` §Frontend Topology lines 940-942](../../design/technical-architecture.md) and roadmap item A1 in [`design/audits/2026-05-21-frontend-readiness-roadmap.md`](../../design/audits/2026-05-21-frontend-readiness-roadmap.md). Without this, the frontend cannot make a single request from `vite dev` without a CORS preflight failure.

**Critical safety invariant**: the bean MUST be gated to the `dev` profile via `@Profile("dev")` (or `ConditionalOnProperty`). Production absolutely must NOT permit `http://localhost:5173` or any wildcard CORS origin — that would defeat the httpOnly cookie + CSRF posture from `auth-01a`. ArchUnit + an explicit test asserts the bean is **absent** in `prod` and `test` profiles.

Closes: C-G-XXX (Tier-A frontend unblock — capability not explicitly inventoried; see roadmap A1).

This ticket is **infrastructure-only** — no entity, no migration, no OpenAPI surface change. Single-package scope under `com.example.mealprep.core.config` (cross-cutting infra lives in `core`).

## Behavioural spec

### Configuration

1. New `@Configuration @Profile("dev")` class `DevCorsConfiguration` at `com.example.mealprep.core.config.DevCorsConfiguration` registering a `WebMvcConfigurer` bean. The bean overrides `addCorsMappings(CorsRegistry registry)`.
2. Allowed origin: **exactly** `http://localhost:5173` (no wildcard, no `localhost:*`). The Vite default port. Configurable via `mealprep.cors.allowed-origin` property with default `http://localhost:5173` for explicit override during local debugging (e.g. when Vite picks a different port).
3. Allowed methods: `GET, POST, PUT, PATCH, DELETE, OPTIONS, HEAD`. Listed explicitly; do NOT use `*` even in dev — explicit lists are still the LLD-style convention.
4. Allowed headers (explicit allowlist, not `*`):
   - `Content-Type`
   - `Accept`
   - `Authorization` (forward-compat: not used today since auth is session-cookie based, but reserve)
   - `X-Origin` (reserved for the origin-tracking pattern landing in `core/02b` — even though no consumer exists in this ticket, including the header here means `02b` doesn't have to revisit CORS)
   - `X-Origin-Trace`
   - `X-Origin-Depth`
   - `X-Trace-Id` (decision-log trace propagation from `core-01`)
   - `X-CSRF-TOKEN` (Spring Security default CSRF header name — Vite-side CSRF flow lands later, but the header must be allowlisted now)
5. **`allowCredentials(true)`** — required because authentication uses session httpOnly cookies (per `auth-01a`). The browser will not send the `JSESSIONID` cookie cross-origin without this flag set AND the server returning `Access-Control-Allow-Credentials: true`. Pairs with the explicit origin (no wildcard allowed with `allowCredentials=true`).
6. Exposed headers (response): `X-Trace-Id`, `Location`, `Content-Disposition`. The frontend's API client reads `X-Trace-Id` for support-link telemetry and `Location` for create-then-redirect flows.
7. `maxAge(3600)` — caches preflight for one hour, reducing OPTIONS chatter during dev sessions.

### Property binding

8. New `@ConfigurationProperties(prefix = "mealprep.cors")` record `CorsProperties` with `String allowedOrigin` (default `http://localhost:5173`) and `Duration preflightMaxAge` (default `PT1H`). `@Validated` with `@NotBlank` on `allowedOrigin`. Bound only under `@Profile("dev")` via `@EnableConfigurationProperties(CorsProperties.class)` on `DevCorsConfiguration`. **Do NOT** make the property visible in `prod`/`test` profiles — a stray prod-yaml entry should be inert, not weaponised.

### `application-dev.properties` updates

9. Append to `src/main/resources/application-dev.properties`:
   ```properties
   mealprep.cors.allowed-origin=http://localhost:5173
   mealprep.cors.preflight-max-age=PT1H
   ```
   These are explicit so a developer reading the file sees the convention without having to look at code.

### Prod safety

10. **No CORS bean is registered in `application.properties` (the prod default) or `application-test.properties`.** Spring's default behaviour with no CORS config + no `@CrossOrigin` is to reject cross-origin requests — that is the correct prod posture.
11. **Test gate**: a new test class `DevCorsConfigurationTest` under `src/test/java/com/example/mealprep/core/config/` asserts:
    - With profile `dev` active: the `WebMvcConfigurer` bean is present in the context and configured for the expected origin.
    - With profile `prod` active: the `DevCorsConfiguration` bean is **absent** from the context (verified via `ApplicationContext#getBeansOfType(WebMvcConfigurer.class)` not containing `DevCorsConfiguration`).
    - With profile `test` active: same as `prod` — bean absent.

### Cross-cutting

12. **No `@CrossOrigin` on any controller.** Per LLD style: cross-cutting concerns live in `core/config/`, not scattered across controllers. The roadmap mentions `@CrossOrigin` as "less preferred"; we are taking that prescription literally.
13. **No `addAllowedOriginPattern("*")`, no `CorsConfiguration.applyPermitDefaultValues()`.** Both are tempting in dev but pull dangerous defaults; the explicit per-list configuration above is the entire scope.
14. ArchUnit rule (added to `ModuleBoundaryTest`): classes outside `com.example.mealprep.core.config..` must not annotate methods with `@CrossOrigin`. This is the automated guard against future drift.

### Events

15. **None.** Pure infra. No events published or consumed.

## Database

**No migration.** Pure config change.

## OpenAPI updates

**No OpenAPI changes.** CORS is a transport-layer concern; the API surface is unchanged. Document the dev-profile-only allowed origin in `openapi.yaml`'s top-level `info.description` block as a one-line note: "CORS for `http://localhost:5173` is active under the `dev` Spring profile only; production requires same-origin requests."

## Edge-case checklist

- [ ] `DevCorsConfigurationTest` (profile `dev`): the `CorsRegistry` is configured with origin `http://localhost:5173`, credentials true, max-age 3600, the explicit method list, and the explicit header allowlist (each header checked).
- [ ] `DevCorsConfigurationTest` (profile `prod`): no `DevCorsConfiguration` bean in the context; any preflight OPTIONS to `/api/v1/auth/login` returns the Spring-default rejection (verified via MockMvc with `Origin: http://localhost:5173` → 403 or similar).
- [ ] `DevCorsConfigurationTest` (profile `test`): same as prod.
- [ ] **Manual curl smoke**: with `-Dspring.profiles.active=dev`, `curl -i -X OPTIONS -H 'Origin: http://localhost:5173' -H 'Access-Control-Request-Method: POST' http://localhost:8080/api/v1/auth/login` returns `Access-Control-Allow-Origin: http://localhost:5173`, `Access-Control-Allow-Credentials: true`, `Access-Control-Allow-Methods` lists POST, `Access-Control-Allow-Headers` includes `Content-Type, X-Origin, X-CSRF-TOKEN`.
- [ ] **Manual curl negative**: same command without `-Dspring.profiles.active=dev` (default profile in test build) → no `Access-Control-Allow-*` response headers.
- [ ] `Access-Control-Allow-Origin: *` never appears in any response — `allowCredentials=true` makes wildcard literally illegal per the CORS spec; the test asserts the specific origin string.
- [ ] Origin header mismatch (e.g. `Origin: http://localhost:5174`) under `dev` profile → not allowed (server returns no `Access-Control-Allow-Origin`, browser rejects).
- [ ] CSRF flow (when it lands): the `X-CSRF-TOKEN` request header passes preflight.
- [ ] `X-Origin: ai-feedback` header (from `core/02b`) passes preflight — verified by including the header in a test request and asserting no preflight rejection.
- [ ] `Authorization: Bearer <token>` header passes preflight even though session-cookie is the current auth scheme (forward-compat).
- [ ] **`ArchUnit` rule**: no class outside `com.example.mealprep.core.config` uses `@CrossOrigin` — a deliberately-mis-placed `@CrossOrigin` in a sibling controller fails the test (verify by deleting it again).
- [ ] **No deprecated `addAllowedOriginPattern` or `applyPermitDefaultValues`** in the configuration — grep at PR time.
- [ ] `application-dev.properties` contains both new keys and they bind to the `CorsProperties` record at boot.
- [ ] Property override: setting `mealprep.cors.allowed-origin=http://localhost:5174` on the command line under `dev` results in the new origin being authoritative (proves property binding works).

## Files this ticket touches

```
NEW   src/main/java/com/example/mealprep/core/config/DevCorsConfiguration.java
NEW   src/main/java/com/example/mealprep/core/config/CorsProperties.java
MOD   src/main/resources/application-dev.properties                          (add 2 keys)
MOD   src/main/resources/openapi/openapi.yaml                                 (1-line info.description note)
NEW   src/test/java/com/example/mealprep/core/config/DevCorsConfigurationTest.java
MOD   src/test/java/com/example/mealprep/archunit/ModuleBoundaryTest.java     (add @CrossOrigin-disallowed rule)
```

Total: 4 new files + 2 modified. Estimated agent runtime 20-30 min.

## Dependencies

- **Hard dependency**: `core-01-decision-log` (merged) — the `core.config` package conventionally hosts cross-cutting infra alongside the decision-log infrastructure.
- **Hard dependency**: `auth-01a` (merged) — the `allowCredentials=true` flag is meaningful only because session cookies are the auth mechanism.
- **Soft sibling**: `core/02b-origin-tracking-foundation` will add `X-Origin*` header consumers; this ticket pre-allows the headers so `02b` doesn't have to touch CORS.

## Acceptance / DoD

- [ ] `./mvnw -Dspotless.check.skip=true test` passes (the new test class included)
- [ ] `./mvnw spotless:apply` then `./mvnw spotless:check` clean
- [ ] All edge-case items above ticked
- [ ] Manual `curl` smoke documented in the PR description (the dev-profile OPTIONS + the no-dev-profile OPTIONS, with response headers)
- [ ] Reviewer eyeball: confirm the `@Profile("dev")` annotation is present on `DevCorsConfiguration` AND the production properties file is unchanged
- [ ] No regression on existing tests; no new endpoints exposed

Squash-merge with: `feat(core): 02a — CORS config for dev profile (Vite localhost:5173)`
