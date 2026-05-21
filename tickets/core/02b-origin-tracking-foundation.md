# Ticket: core — 02b Origin-Tracking Foundation (filter, context, service tokens, audit hooks)

## Summary

Build the **foundation** of the origin-tracking pattern specified in [`design/origin-tracking-pattern.md`](../../design/origin-tracking-pattern.md). This ticket ships the cross-cutting machinery — the `OriginContext` request-scoped bean, the `OriginFilter` servlet filter, the origin-aware `AuditLogWriter` extension, the `auth_service_tokens` table for Pattern B authentication, the `@OriginAware` controller annotation, and the `OriginAwareEvent` base interface — **without wiring any consumers**. Individual consumers (feedback bridges, scheduled jobs, adaptation pipeline) ship in their own tickets and reuse this foundation.

The pattern's purpose, per the design doc: when AI / system code needs to mutate user-scoped state on the user's behalf, it calls the **same REST endpoints a human would call**, with origin-identifying headers. The endpoint applies origin-specific policies (confidence floor, rate limit, audit attribution, recursion guard) via this filter. The alternative — building a parallel "internal-only" API surface for system-driven mutations — was explicitly rejected (per the design doc's "Alternative considered" section) because of behavioural divergence over time.

**Strict ordering**: this ticket must merge **before** `tickets/feedback/01g-destination-bridges.md`, which is the first real consumer.

Ships:

- **`OriginContext`** — request-scoped bean carrying origin kind, trace, depth, confidence. Read by services when writing audit rows.
- **`OriginFilter`** — Spring `OncePerRequestFilter` that reads `X-Origin*` headers, validates against a permitted-origins allowlist, applies confidence-floor + rate-limit + depth-check, and populates the `OriginContext` request attribute.
- **`@OriginAware`** annotation — controller-level marker indicating "this endpoint accepts non-user origin"; absence on a controller method means user-only (defense-in-depth).
- **`AuditMetadata`** record + extension to existing audit-log writers — every module's audit writer accepts `(actorType, originTrace)` so the per-module audit row carries the attribution.
- **`auth_service_tokens` table + entity + repository + `ServiceTokenAuthenticationProvider`** — Pattern B authentication for scheduled jobs that don't have a session.
- **`OriginAwareEvent` interface** — extension of `core.events.MealPrepEvent` adding `Origin getOrigin()` and `String getOriginTrace()`. Existing events implement default-to-null bodies for backward compat (USER-origin events have no AI/SYSTEM origin); new events ship with non-null values.
- **`X-Origin*` header constants** in `core.api` for cross-module reuse.

**Crucially**, this ticket does **not**:
- Modify any existing controller (the filter populates `OriginContext` but no service yet reads it).
- Modify any existing audit-log table (the new `actor_type` and `origin_trace` columns add via migration but stay nullable; existing inserts remain valid).
- Activate Pattern A inheritance — that wiring is per-consumer.

Closes: **C-G-XXX** (origin-tracking pattern foundation — not directly in the capability inventory; unblocks C-IMP-007, C-A-003 application, the feedback-bridge work).

## Behavioural spec

### Database — service token table

1. Migration `V20260615180000__auth_create_service_tokens.sql`:
   ```sql
   CREATE TABLE auth_service_tokens (
       id                       uuid PRIMARY KEY,
       token_hash               varchar(96) NOT NULL UNIQUE,         -- bcrypt or SHA-256 of the opaque token
       name                     varchar(128) NOT NULL,                -- human-readable e.g. "scheduled-price-refresh"
       permitted_origins        text[] NOT NULL,                      -- e.g. {system-scheduled}
       enabled                  boolean NOT NULL DEFAULT true,
       last_used_at             timestamptz,
       created_at               timestamptz NOT NULL,
       updated_at               timestamptz NOT NULL,
       revoked_at               timestamptz,                          -- non-null if revoked
       optimistic_version       bigint NOT NULL DEFAULT 0
   );
   CREATE INDEX idx_auth_service_tokens_enabled ON auth_service_tokens (enabled, revoked_at) WHERE enabled = true AND revoked_at IS NULL;
   ```
   - Token **hash only** stored, never plaintext. Tokens are minted via an admin CLI command (out of scope here; covered by a future ops ticket) and shown once.
   - `permitted_origins` is the **whitelist** of `X-Origin` values this token can claim. A token tagged `{system-scheduled}` cannot make `ai-feedback` calls. Per `design/origin-tracking-pattern.md` §Authentication Pattern B.

### Migration — audit log columns on existing tables

2. Migration `V20260615180100__core_add_audit_origin_columns.sql` — adds two columns to **each** existing audit-log table:
   ```sql
   ALTER TABLE preference_hard_constraints_audit
     ADD COLUMN actor_type varchar(16),
     ADD COLUMN origin_trace varchar(128);
   ALTER TABLE preference_lifestyle_config_audit
     ADD COLUMN actor_type varchar(16),
     ADD COLUMN origin_trace varchar(128);
   ALTER TABLE preference_taste_profile_audit
     ADD COLUMN actor_type varchar(16),
     ADD COLUMN origin_trace varchar(128);
   -- nutrition / provisions / recipe / feedback audit logs covered analogously
   -- per the per-module enumeration below
   ```
   - Both columns **nullable** — backward-compatible. Existing rows have `NULL` `actor_type` (treated as `USER` by reads).
   - `actor_type`: `USER | AI | SYSTEM` — string enum, no DB check constraint (Java enum is the whitelist).
   - `origin_trace`: matches the `X-Origin-Trace` header value (typically `feedback-<uuid>`, `scheduled-<cron>-<instance>`, etc.).
3. **Enumeration of audit tables to alter** (verify against the codebase at agent start; this is the as-of-2026-05-21 list):
   - `preference_hard_constraints_audit` (from `preference-01a`)
   - `preference_taste_profile_audit` (from `preference-01c`)
   - `preference_lifestyle_config_audit` (from `preference-01d`)
   - `nutrition_targets_audit` (verify existence per nutrition LLD)
   - `nutrition_intake_audit` (verify)
   - `provisions_inventory_audit` (verify)
   - `recipe_versions` (uses `created_by_actor` already — verify whether this counts as the audit-attribution column; if so, no change needed)
   - Other module audit logs as the agent finds them.
   - **If an audit table does not exist yet**, no change required — the module's own ticket includes `actor_type` and `origin_trace` from the start.

### `OriginContext` request-scoped bean

4. **`OriginContext`** at `com.example.mealprep.core.origin.OriginContext`. `@Component @RequestScope`. Holds:
   ```java
   public class OriginContext {
     private Origin origin;                    // enum, see below; defaults to USER
     private String originTrace;               // e.g. "feedback-<uuid>"; nullable for USER
     private int originDepth;                  // 0 for direct user calls; >0 for cascading system calls
     private BigDecimal confidence;            // populated for AI origins; nullable otherwise
     private UUID actingAsUserId;              // populated for Pattern B (service-token + X-Acting-As)
     // getters; package-private setters used by OriginFilter only
   }
   ```
5. **`Origin`** enum at `com.example.mealprep.core.origin.Origin`. Values (matching the design doc's initial set):
   - `USER` — direct user. Default when no `X-Origin` header.
   - `AI_FEEDBACK` — feedback classifier's downstream apply.
   - `AI_ADAPTATION` — recipe adaptation pipeline writing pending changes.
   - `SYSTEM_SCHEDULED` — scheduled scanners (expiry, defrost, price refresh).
   - `SYSTEM_REOPT` — mid-week re-optimisation.
   - `SYSTEM_DISCOVERY` — discovery pipeline saving an imported recipe.
6. **`ActorType`** enum at `com.example.mealprep.core.origin.ActorType`. Values: `USER, AI, SYSTEM`. Derived from `Origin` via `Origin.toActorType()`:
   - `USER → USER`
   - `AI_FEEDBACK | AI_ADAPTATION → AI`
   - `SYSTEM_SCHEDULED | SYSTEM_REOPT | SYSTEM_DISCOVERY → SYSTEM`

### `OriginFilter`

7. **`OriginFilter`** at `com.example.mealprep.core.origin.OriginFilter`. Extends `OncePerRequestFilter`. Registered with `@Order(SecurityProperties.IGNORED_ORDER + 100)` so it runs AFTER Spring Security but BEFORE controllers. Reads request headers:
   - `X-Origin` (optional; absence → `Origin.USER`)
   - `X-Origin-Trace` (optional but required when `X-Origin` is present and non-USER)
   - `X-Origin-Depth` (optional; default 0; type integer)
   - `X-Acting-As` (optional but required when authentication is service-token-based per Pattern B)
8. **Validation steps inside the filter** (fail-closed per design doc Open Questions):
   - If `X-Origin` parses to an unknown value → 400 ProblemDetail (`unknown-origin`).
   - If `X-Origin` is non-USER and `X-Origin-Trace` is absent → 400 (`origin-trace-required-for-non-user-origin`).
   - If `X-Origin-Depth > 3` → 422 (`origin-depth-exceeded`) per `design/origin-tracking-pattern.md` §Recursion guard.
   - If origin is `AI_FEEDBACK` or `AI_ADAPTATION` and the request body's `confidence` field is < `mealprep.origin.ai-confidence-floor` (default 0.5) → 422 (`confidence-below-threshold`). **Implementation note**: parsing the body requires the request to be buffered (`ContentCachingRequestWrapper`); the filter wraps and re-reads. This adds a small overhead per request — acceptable for v1.
9. **Authentication mode dispatch** inside the filter:
   - If `Authorization: Bearer <token>` is present AND `X-Origin` is non-USER → **Pattern B** (service token). Filter delegates to `ServiceTokenAuthenticationProvider` to validate the token, check `permitted_origins` includes the claimed `X-Origin`, and set the security context to act as `X-Acting-As` user.
   - Else → **Pattern A** (inherited session). Filter trusts the existing security context (set by `SessionAuthenticationFilter` from `auth-01a`).
   - If neither path resolves a user (no session, no service token) AND the path requires authentication → 401 (existing behaviour).
10. **Rate limiting** — per-origin token-bucket. Stub implementation in 01g uses an in-memory `Map<(userId, Origin), TokenBucket>`. Configured via:
    - `mealprep.origin.rate-limit.ai-feedback.per-user-per-day` (default 20)
    - `mealprep.origin.rate-limit.system-scheduled.per-minute-global` (default 100)
    - `mealprep.origin.rate-limit.ai-adaptation.per-user-per-day` (default 50)
    Exceeded → 429 (`rate-limit-exceeded`). **Worth user review** — in-memory bucket is process-local; distributed rate-limiting requires Redis (out of scope, single-instance deploy is the v1 target).

### `@OriginAware` annotation

11. **`@OriginAware`** at `com.example.mealprep.core.origin.OriginAware`. `@Target({ElementType.TYPE, ElementType.METHOD}) @Retention(RUNTIME)`. Marker only; no value attributes. Controller methods (or whole controllers) marked with it indicate "this endpoint accepts non-user origin"; absence means user-only.
12. **Defense-in-depth check** in the filter: if the resolved request handler (the controller method) is **not** `@OriginAware` and `X-Origin` is non-USER → 403 (`origin-not-permitted-on-endpoint`). Resolved via `RequestMappingHandlerMapping.getHandler(request)` inside the filter. **Important**: the filter looks up the handler **once** to apply this check; the rest of the filter chain proceeds normally.
13. **No controllers are annotated in this ticket.** The annotation lands; future tickets (feedback bridges, adaptation pipeline) annotate the relevant controllers.

### Service token authentication

14. **`ServiceToken`** entity at `com.example.mealprep.auth.domain.entity.ServiceToken`. Fields per §1's schema. Lombok per style guide.
15. **`ServiceTokenRepository`** at `auth.domain.repository`:
    ```java
    interface ServiceTokenRepository extends JpaRepository<ServiceToken, UUID> {
      Optional<ServiceToken> findByTokenHashAndEnabledTrueAndRevokedAtIsNull(String tokenHash);
    }
    ```
16. **`ServiceTokenAuthenticationProvider`** at `auth.security.ServiceTokenAuthenticationProvider`. Implements `AuthenticationProvider`. Authenticates `BearerTokenAuthentication` requests:
    - SHA-256 the supplied token, look up by hash.
    - If found, enabled, not revoked: build a Spring `Authentication` with authorities `ROLE_SERVICE_<origin>` (one per permitted origin); set the principal to the **`X-Acting-As`** user (the filter passed it through).
    - If not found / revoked: throw `BadCredentialsException`.
    - Update `last_used_at` (async, fire-and-forget — don't block request).
17. **`SecurityConfig`** (existing) gains the `ServiceTokenAuthenticationProvider` and the `OriginFilter` registration via `HttpSecurity.addFilterAfter(...)`. The session-cookie auth continues to work; the bearer-token path is parallel.

### `OriginAwareEvent` base interface

18. **`OriginAwareEvent`** at `core.events.OriginAwareEvent`. Extends `MealPrepEvent`. Adds:
    ```java
    public interface OriginAwareEvent extends MealPrepEvent {
      Origin origin();        // never null; defaults to USER for legacy events
      String originTrace();   // nullable; populated for non-USER events
    }
    ```
19. **Default implementations** on `MealPrepEvent` aren't possible in a Java interface without breaking the sealed type. Instead, the new events (from `tickets/feedback/01g`, `tickets/notification/01a`, etc.) implement both `MealPrepEvent` and `OriginAwareEvent` directly. Existing events stay unchanged (USER-origin by default; no listener relies on origin metadata yet).

### `AuditMetadata`

20. **`AuditMetadata`** record at `core.origin.AuditMetadata`:
    ```java
    public record AuditMetadata(ActorType actorType, String originTrace) {
      public static AuditMetadata fromContext(OriginContext context) {
        return new AuditMetadata(context.getOrigin().toActorType(), context.getOriginTrace());
      }
      public static AuditMetadata user() {
        return new AuditMetadata(ActorType.USER, null);
      }
    }
    ```
21. **Existing audit-log writers** in `preference`, `nutrition`, `provisions`, `recipe` are **not modified** in this ticket. They continue to insert without `actor_type` / `origin_trace`. Their per-module follow-ups will switch to using `AuditMetadata.fromContext(originContext)` when those modules' bridges land.
22. **New audit writes** in `tickets/feedback/01g`, `tickets/preference/01c`, `tickets/notification/01a` etc. should use `AuditMetadata.fromContext(...)`. The pattern: inject `OriginContext` (request-scoped) into the service, call `auditLog.write(..., AuditMetadata.fromContext(originContext))`.

### Header constants

23. **`OriginHeaders`** final class at `core.api.OriginHeaders`. Public string constants:
    ```java
    public static final String X_ORIGIN = "X-Origin";
    public static final String X_ORIGIN_TRACE = "X-Origin-Trace";
    public static final String X_ORIGIN_DEPTH = "X-Origin-Depth";
    public static final String X_ACTING_AS = "X-Acting-As";
    ```
    Constants — never re-typed across the codebase.

### Configuration

24. **`OriginProperties`** record at `core.origin.OriginProperties`. `@ConfigurationProperties(prefix = "mealprep.origin") @Validated`:
    ```java
    public record OriginProperties(
        @NotNull BigDecimal aiConfidenceFloor,                 // default 0.5
        @NotNull Duration rateLimitWindow,                     // default PT1H
        @NotNull Map<Origin, RateLimitConfig> rateLimits,       // per-origin overrides
        boolean rejectOriginOnNonAnnotatedController            // default true
    ) {
      public record RateLimitConfig(int limit, Scope scope) {}  // scope: PER_USER | GLOBAL
      public enum Scope { PER_USER, GLOBAL }
    }
    ```
25. Properties in `application.properties` (or yaml):
    ```properties
    mealprep.origin.ai-confidence-floor=0.5
    mealprep.origin.rate-limit-window=PT1H
    mealprep.origin.rate-limits.AI_FEEDBACK.limit=20
    mealprep.origin.rate-limits.AI_FEEDBACK.scope=PER_USER
    mealprep.origin.rate-limits.SYSTEM_SCHEDULED.limit=100
    mealprep.origin.rate-limits.SYSTEM_SCHEDULED.scope=GLOBAL
    mealprep.origin.reject-origin-on-non-annotated-controller=true
    ```

### Cross-cutting

26. New exceptions in `core.origin.exception`:
    - `OriginValidationException` (400) — unknown origin, missing trace
    - `OriginDepthExceededException` (422)
    - `ConfidenceBelowThresholdException` (422)
    - `OriginNotPermittedOnEndpointException` (403)
    - `OriginRateLimitExceededException` (429)
27. `GlobalExceptionHandler` handlers added for all five.
28. **ArchUnit rule** added to `ModuleBoundaryTest`: classes outside `core.origin..` should not declare bean components implementing `Filter` or `OncePerRequestFilter` that read the `X-Origin` header. The single OriginFilter is the only place this logic lives. Verified by string-search on `X-Origin` references in non-`core.origin` classes.

### Events

29. **None published in 01g** beyond the existing event surface. The interface (`OriginAwareEvent`) is shipped; consumers (future events) implement it.

## Database

```
NEW   src/main/resources/db/migration/V20260615180000__auth_create_service_tokens.sql
NEW   src/main/resources/db/migration/V20260615180100__core_add_audit_origin_columns.sql
```

## OpenAPI updates

Add the `bearerAuth` security scheme to `openapi.yaml` (if not already added by `tickets/infra/01c`):
```yaml
components:
  securitySchemes:
    bearerAuth:
      type: http
      scheme: bearer
      bearerFormat: opaque
```

Document the `X-Origin*` headers as **reusable parameter components**:
```yaml
components:
  parameters:
    OriginHeader:
      name: X-Origin
      in: header
      required: false
      schema: { type: string, enum: [USER, AI_FEEDBACK, AI_ADAPTATION, SYSTEM_SCHEDULED, SYSTEM_REOPT, SYSTEM_DISCOVERY] }
      description: |
        Origin of the request. Absence means USER (direct user call).
        Non-USER values require X-Origin-Trace and a permitted service token (Pattern B)
        or an inherited user session (Pattern A).
    OriginTraceHeader:
      name: X-Origin-Trace
      in: header
      required: false
      schema: { type: string, maxLength: 128 }
    OriginDepthHeader:
      name: X-Origin-Depth
      in: header
      required: false
      schema: { type: integer, minimum: 0, maximum: 3, default: 0 }
    ActingAsHeader:
      name: X-Acting-As
      in: header
      required: false
      schema: { type: string, format: uuid }
```

**No paths are modified in this ticket** — the headers are referenced by future tickets' paths.

Add a top-level documentation block to `openapi.yaml`'s `info.description` referencing the pattern doc.

## Edge-case checklist

- [ ] Two migrations apply cleanly; `FlywayMigrationIT` passes.
- [ ] `ddl-auto=validate` accepts the new `ServiceToken` entity and the additive columns on existing audit tables.
- [ ] Filter behaviour: request with no `X-Origin` → `OriginContext.origin = USER`, `originTrace = null`, `originDepth = 0`, no filter rejection.
- [ ] Filter behaviour: request with `X-Origin: AI_FEEDBACK` + `X-Origin-Trace: feedback-abc` + body `{"confidence": 0.7, ...}` → populated context, no rejection.
- [ ] Filter rejection: `X-Origin: UNKNOWN_VALUE` → 400 `unknown-origin`.
- [ ] Filter rejection: `X-Origin: AI_FEEDBACK` with no `X-Origin-Trace` → 400 `origin-trace-required-for-non-user-origin`.
- [ ] Filter rejection: `X-Origin-Depth: 4` → 422 `origin-depth-exceeded`.
- [ ] Filter rejection: `X-Origin: AI_FEEDBACK` + body `{"confidence": 0.3}` → 422 `confidence-below-threshold` (threshold default 0.5).
- [ ] Filter rejection: `X-Origin: AI_FEEDBACK` to a controller method not annotated `@OriginAware` → 403 `origin-not-permitted-on-endpoint`.
- [ ] `@OriginAware` annotation present on a stub test controller permits the same request to pass through to the controller method.
- [ ] **Pattern B**: `Authorization: Bearer <valid-token>` + `X-Origin: SYSTEM_SCHEDULED` + `X-Acting-As: <userid>` + valid token in `auth_service_tokens` with `permitted_origins={system-scheduled}` → authenticated as `<userid>` with `ROLE_SERVICE_SYSTEM_SCHEDULED`.
- [ ] **Pattern B rejection**: same with `X-Origin: AI_FEEDBACK` but token's `permitted_origins={system-scheduled}` → 403 `origin-not-permitted-by-token`.
- [ ] **Pattern B rejection**: invalid bearer token → 401.
- [ ] **Pattern B rejection**: revoked token (`revoked_at IS NOT NULL`) → 401.
- [ ] **Pattern B rejection**: disabled token (`enabled = false`) → 401.
- [ ] **Pattern A** (no bearer, session cookie present, `X-Origin: AI_FEEDBACK`): filter trusts existing session; if session resolved user A, the call acts as user A.
- [ ] **Rate limit**: 21 `AI_FEEDBACK` calls for the same user in one hour → 21st returns 429.
- [ ] **Rate limit reset**: after the configured window expires, the bucket refills.
- [ ] **Audit columns**: existing audit-log rows (pre-migration) have `actor_type=NULL`, `origin_trace=NULL`. Application code reading these treats `NULL actor_type` as `USER`.
- [ ] **`AuditMetadata.fromContext`**: in a unit test, an `OriginContext` with `Origin.AI_FEEDBACK` produces `ActorType.AI`.
- [ ] **`AuditMetadata.user`**: produces `ActorType.USER` with null trace.
- [ ] **`OriginAwareEvent`**: a new event implementing it serialises `origin` and `originTrace`; Jackson handles the enum.
- [ ] **No production code change to existing audit writers** — they still work; their behaviour just doesn't populate the new columns (verified by spot-checking that `preference_hard_constraints_audit` writes from `preference-01a` still pass).
- [ ] **Header constants**: 4 string constants present; `OriginFilter` uses them; no hard-coded `"X-Origin"` strings elsewhere (grep).
- [ ] **`@RequestScope` lifecycle**: `OriginContext` is recreated per request; verified by an IT that asserts two parallel requests have isolated contexts.
- [ ] **Filter ordering**: `OriginFilter` runs after `SessionAuthenticationFilter` (verified via the filter chain dump).
- [ ] OpenAPI lints clean with the new security scheme + parameter components.
- [ ] Manual smoke documented in PR: curl with `X-Origin: SYSTEM_SCHEDULED` + a valid bearer token + `X-Acting-As: <userid>` to a `@OriginAware`-annotated test endpoint → 200; same curl without the token → 401.
- [ ] ArchUnit: no `OncePerRequestFilter` outside `core.origin..` reads `X-Origin*` headers.

## Files this ticket touches

```
NEW   src/main/resources/db/migration/V20260615180000__auth_create_service_tokens.sql
NEW   src/main/resources/db/migration/V20260615180100__core_add_audit_origin_columns.sql

NEW   src/main/java/com/example/mealprep/core/origin/Origin.java
NEW   src/main/java/com/example/mealprep/core/origin/ActorType.java
NEW   src/main/java/com/example/mealprep/core/origin/OriginContext.java
NEW   src/main/java/com/example/mealprep/core/origin/OriginFilter.java
NEW   src/main/java/com/example/mealprep/core/origin/OriginAware.java
NEW   src/main/java/com/example/mealprep/core/origin/OriginProperties.java
NEW   src/main/java/com/example/mealprep/core/origin/AuditMetadata.java
NEW   src/main/java/com/example/mealprep/core/origin/internal/InMemoryTokenBucketRateLimiter.java
NEW   src/main/java/com/example/mealprep/core/origin/exception/OriginValidationException.java
NEW   src/main/java/com/example/mealprep/core/origin/exception/OriginDepthExceededException.java
NEW   src/main/java/com/example/mealprep/core/origin/exception/ConfidenceBelowThresholdException.java
NEW   src/main/java/com/example/mealprep/core/origin/exception/OriginNotPermittedOnEndpointException.java
NEW   src/main/java/com/example/mealprep/core/origin/exception/OriginRateLimitExceededException.java

NEW   src/main/java/com/example/mealprep/core/api/OriginHeaders.java

NEW   src/main/java/com/example/mealprep/core/events/OriginAwareEvent.java

NEW   src/main/java/com/example/mealprep/auth/domain/entity/ServiceToken.java
NEW   src/main/java/com/example/mealprep/auth/domain/repository/ServiceTokenRepository.java
NEW   src/main/java/com/example/mealprep/auth/security/ServiceTokenAuthenticationProvider.java

MOD   src/main/java/com/example/mealprep/auth/config/SecurityConfig.java                 (register OriginFilter + ServiceTokenAuthenticationProvider)
MOD   src/main/java/com/example/mealprep/config/GlobalExceptionHandler.java              (5 new mappings)
MOD   src/main/resources/application.properties                                          (origin.* properties)
MOD   src/main/resources/openapi/openapi.yaml                                            (bearerAuth scheme + 4 header parameter components)
MOD   src/test/java/com/example/mealprep/archunit/ModuleBoundaryTest.java                (OriginFilter uniqueness rule)

NEW   src/test/java/com/example/mealprep/core/origin/OriginFilterTest.java               (mockmvc unit-ish)
NEW   src/test/java/com/example/mealprep/core/origin/OriginFilterIT.java                 (full filter chain + Pattern A + Pattern B)
NEW   src/test/java/com/example/mealprep/core/origin/ServiceTokenAuthenticationProviderTest.java
NEW   src/test/java/com/example/mealprep/core/origin/InMemoryTokenBucketRateLimiterTest.java
NEW   src/test/java/com/example/mealprep/core/origin/AuditMetadataTest.java
NEW   src/test/java/com/example/mealprep/core/origin/testdata/ServiceTokenTestData.java
```

Total: ~24 new + 5 mods. Estimated agent runtime 5-7 hours (filter logic + rate limiter + 2 auth providers + cross-module migration).

## Dependencies

- **Hard dependency**: `core-01-decision-log` (merged) — `MealPrepEvent` base.
- **Hard dependency**: `auth-01a` (merged) — `SecurityConfig`, `SessionAuthenticationFilter`.
- **Hard dependency**: all wave-2 module audit tables (preference, nutrition, provisions, recipe) — the migration alters them all. **Coordinate**: ensure no sibling ticket is mid-flight modifying those tables.
- **Downstream consumer**: `tickets/feedback/01g-destination-bridges.md` is the FIRST real consumer of this foundation.

## Acceptance / DoD

- [ ] `./mvnw -Dspotless.check.skip=true verify` passes
- [ ] `./mvnw spotless:apply` then `./mvnw spotless:check` clean
- [ ] CI green (build + spotless + OpenAPI lint + ArchUnit + contract tests)
- [ ] All edge-case items above ticked
- [ ] **No existing tests break** — the filter is non-intrusive when `X-Origin` is absent
- [ ] PR description includes manual curl: Pattern A request (cookie + `X-Origin: AI_FEEDBACK`), Pattern B request (bearer + `X-Origin: SYSTEM_SCHEDULED` + `X-Acting-As`), Plain user request (no headers).

## What's NOT in scope

- **Wiring any feedback bridge / scheduled job / SPI** — that's per-consumer tickets.
- **Annotating any existing controller with `@OriginAware`** — that's per-consumer.
- **JWT-based service tokens** — opaque tokens with DB lookup chosen for simplicity per the design doc Open Questions.
- **Distributed (Redis) rate limiting** — in-memory token bucket suffices for single-instance v1.
- **Token-minting CLI / admin endpoint** — covered by a future ops ticket.
- **Per-tenant token scoping** beyond `permitted_origins` — token impersonates whatever `X-Acting-As` is.
- **`pending_suggestions` table** for the "AI suggests but user must confirm" UX (per design doc Open Questions) — separate ticket if/when needed.

Squash-merge with: `feat(core): 02b — origin-tracking foundation (filter, context, service tokens, audit hooks)`
