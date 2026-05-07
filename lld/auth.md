# Auth Module — LLD

*Implementation specification for the thin auth layer: username + password registration, session-based login, password change, and the `CurrentUserResolver` boundary that every other module reads `userId` through. Translates the "User Accounts" section of [system-overview.md](../design/system-overview.md#user-accounts) into a buildable Spring Boot module.*

## Scope

This document specifies the `auth` module — package layout, entities, migrations, repositories, services, DTOs, controllers, validation, events, business-logic flows (with security details called out), transactions, and tests. Conventions defer to [lld/style-guide.md](style-guide.md).

The HLD frames the layer as deliberately minimal: *"Username + hashed password (simple, no OAuth initially) ... links to the user's Preference Model, Nutrition Model, and household membership. Multi-user from v1."* The module owns user identity, credentials, and session lifecycle, and exposes a single resolver bean that lets every other module read `userId` without depending on Spring Security types. It does **not** own household membership, per-route authorisation (each controller LLD documents its own), or any AI-touched data.

"Simple" means *narrow scope*, not *insecure*. Current secure defaults are applied throughout — BCrypt cost 12, 256-bit session tokens, HttpOnly + Secure + SameSite cookies, login-attempt throttling, generic auth-failure messages, hash-only token storage. Every security-implicated choice is flagged inline and summarised at the end.

**Implementation deps note.** Pulls in `spring-boot-starter-security` (Spring Security 6, lambda-style configuration). All other dependencies are covered by the style guide's tech stack.

---

## Package Layout

```
com.example.mealprep.auth/
├── AuthModule.java                         facade re-exporting public service interfaces
├── api/
│   ├── controller/                         AuthController (register/login/logout/me/password)
│   ├── dto/                                records (see DTOs section)
│   └── mapper/                             UserMapper, SessionMapper
├── domain/
│   ├── entity/                             User, Session, LoginAttempt
│   ├── repository/                         Spring Data interfaces — package-private
│   └── service/
│       ├── AuthQueryService.java           public interface
│       ├── AuthUpdateService.java          public interface
│       ├── CurrentUserResolver.java        public interface — cross-module userId resolution
│       ├── AuthServiceImpl.java            single impl of AuthQueryService + AuthUpdateService
│       ├── CurrentUserResolverImpl.java    Spring Security-backed impl of CurrentUserResolver
│       └── internal/
│           ├── PasswordHasher              BCrypt wrapper (cost factor configurable)
│           ├── PasswordStrengthValidator   policy enforcement (length, character classes, breach list)
│           ├── SessionTokenGenerator       SecureRandom-backed 256-bit token + lookup hash
│           ├── LoginThrottleService        per-username + per-IP attempt accounting
│           └── SessionAuthenticationFilter Spring Security filter — reads cookie, loads session, sets context
├── event/                                  UserRegisteredEvent, UserLoggedInEvent, UserDeletedEvent
├── exception/                              module-root + per-failure subclasses (see Errors)
├── validation/                             @ValidUsername, @ValidPassword + validators
└── config/
    ├── AuthSecurityConfig.java             SecurityFilterChain bean, password-encoder bean, CORS
    └── AuthProperties.java                 @ConfigurationProperties (bcrypt cost, session TTL, throttle thresholds, cookie flags)
```

`AuthModule.java` re-exports `AuthQueryService`, `AuthUpdateService`, and `CurrentUserResolver` so cross-module code never touches `SecurityContextHolder` — see [Cross-Module Integration](#cross-module-integration). `SessionAuthenticationFilter` is a Spring Security plumbing detail; it is registered on the `SecurityFilterChain` in `AuthSecurityConfig`.

---

## Database

Migrations live under `src/main/resources/db/migration/` with the project-wide timestamp scheme from [technical-architecture.md §Migrations](../design/technical-architecture.md#migrations):

```
V20260501100000__auth_create_users.sql
V20260501100100__auth_create_sessions.sql
V20260501100200__auth_create_login_attempts.sql
```

### V20260501100000 — Users

```sql
CREATE TABLE auth_users (
    id                          uuid PRIMARY KEY,
    username                    varchar(64) NOT NULL,
    username_normalised         varchar(64) NOT NULL,            -- lowercase + trim, used for uniqueness and lookup
    password_hash               varchar(72) NOT NULL,            -- BCrypt $2a/$2b output, 60 chars + headroom
    password_updated_at         timestamptz NOT NULL,
    failed_login_count          integer NOT NULL DEFAULT 0,      -- consecutive failures since last success
    locked_until                timestamptz,                     -- null when not locked
    last_login_at               timestamptz,
    last_login_ip               inet,
    deleted_at                  timestamptz,                     -- soft-delete; preserves userId references
    version                     bigint NOT NULL DEFAULT 0,       -- @Version
    created_at                  timestamptz NOT NULL,
    updated_at                  timestamptz NOT NULL
);
-- Hot read: every login lookup, every CurrentUserResolver call.
CREATE UNIQUE INDEX idx_auth_users_username_normalised ON auth_users (username_normalised);
-- Operational query: list locked accounts.
CREATE INDEX idx_auth_users_locked_until ON auth_users (locked_until) WHERE locked_until IS NOT NULL;
```

The HLD's cross-module table lists `user_id` as `bigint`, but the style guide rules **all entity IDs are UUIDs**. Choosing `uuid` to satisfy the style guide; the cross-module table should be amended to match. **Worth user review.**

Soft-delete on the user row is deliberate. Every other module stores `userId` as a plain column with no FK (per [technical-architecture.md §Cross-module references](../design/technical-architecture.md#cross-module-references)); hard-deleting would orphan references silently. Soft-delete preserves referential meaning while preventing further authentication. GDPR-correct hard-delete with cross-module cascade is deferred (see Out of Scope). **Worth user review.**

`username_normalised` is a separate column, not a function index, so JPA queries hit the index without `LOWER(...)` rewrites.

### V20260501100100 — Sessions

```sql
CREATE TABLE auth_sessions (
    id                          uuid PRIMARY KEY,
    user_id                     uuid NOT NULL REFERENCES auth_users(id) ON DELETE CASCADE,
    token_hash                  varchar(64) NOT NULL,            -- SHA-256 hex of the raw token; raw token never stored
    issued_at                   timestamptz NOT NULL,
    expires_at                  timestamptz NOT NULL,
    last_seen_at                timestamptz NOT NULL,            -- updated on each authenticated request (sliding expiry)
    revoked_at                  timestamptz,                     -- explicit logout / password change
    issuing_ip                  inet,
    user_agent                  varchar(255),
    version                     bigint NOT NULL DEFAULT 0
);
-- Hot read: every authenticated request hits this index.
CREATE UNIQUE INDEX idx_auth_sessions_token_hash ON auth_sessions (token_hash);
-- Logout-everywhere and admin views.
CREATE INDEX idx_auth_sessions_user_active ON auth_sessions (user_id) WHERE revoked_at IS NULL;
-- Reaper job: delete expired/revoked rows.
CREATE INDEX idx_auth_sessions_expires_at ON auth_sessions (expires_at);
```

`token_hash` is SHA-256 of the raw token. The raw token leaves the server exactly once (in the login response's `Set-Cookie`) and is never persisted, so a DB read yields no active credentials. **The HLD is silent on token storage; choosing hash-only because plaintext storage turns a DB leak into session takeover with no offsetting benefit. Worth user review.**

`ON DELETE CASCADE` on `user_id` is safe here — soft-delete sets `deleted_at` and revokes sessions in the same transaction; the cascade only matters for the eventual hard-delete path.

### V20260501100200 — Login attempts

```sql
CREATE TABLE auth_login_attempts (
    id                          uuid PRIMARY KEY,
    username_normalised         varchar(64) NOT NULL,            -- recorded even when no user matches (prevents enumeration)
    user_id                     uuid,                            -- null when username didn't match
    source_ip                   inet NOT NULL,
    succeeded                   boolean NOT NULL,
    failure_reason              varchar(32),                     -- BAD_PASSWORD | UNKNOWN_USER | ACCOUNT_LOCKED | INVALID_REQUEST
    attempted_at                timestamptz NOT NULL
);
-- Per-username throttle window query.
CREATE INDEX idx_auth_login_attempts_username_time ON auth_login_attempts (username_normalised, attempted_at DESC);
-- Per-IP throttle window query.
CREATE INDEX idx_auth_login_attempts_ip_time ON auth_login_attempts (source_ip, attempted_at DESC);
```

Recording attempts for unknown usernames lets the throttle service apply identical rules whether or not the username exists, preventing the attempt log from becoming a username-enumeration oracle.

---

## Entities

All entities follow the style guide: UUID `@Id` set application-side, `@Version` on mutable aggregate roots, `@CreatedDate`/`@LastModifiedDate` audit columns, Lombok `@Getter @Setter @Builder @NoArgsConstructor(access = PROTECTED) @AllArgsConstructor`.

| Entity | Notes |
|---|---|
| `User` | Aggregate root. `username`, `usernameNormalised`, `passwordHash`, `passwordUpdatedAt`, `failedLoginCount`, `lockedUntil`, `lastLoginAt`, `lastLoginIp`, `deletedAt`. `@Version`. |
| `Session` | `@ManyToOne(fetch = LAZY) User`. `tokenHash`, `issuedAt`, `expiresAt`, `lastSeenAt`, `revokedAt`, `issuingIp`, `userAgent`. `@Version`. |
| `LoginAttempt` | Append-only. `usernameNormalised`, optional `userId`, `sourceIp`, `succeeded`, `failureReason`, `attemptedAt`. No `@Version`. |

Local enums: `LoginFailureReason` (`BAD_PASSWORD`, `UNKNOWN_USER`, `ACCOUNT_LOCKED`, `INVALID_REQUEST`).

`User` does **not** model roles or authorities — single role per user in v1; per-route authorisation rules belong to each controller LLD.

---

## DTOs

All DTOs are Java records per the style guide.

```java
public record UserDto(
    UUID id,
    String username,
    Instant createdAt,
    Instant passwordUpdatedAt,
    Instant lastLoginAt
) {}

public record SessionDto(
    UUID id,
    UUID userId,
    Instant issuedAt,
    Instant expiresAt,
    Instant lastSeenAt
) {}

public record RegisterRequest(
    @NotBlank @ValidUsername String username,
    @NotBlank @ValidPassword String password
) {}

public record LoginRequest(
    @NotBlank String username,
    @NotBlank String password
) {}

public record LoginResponse(
    UserDto user,
    Instant sessionExpiresAt          // raw token is delivered via Set-Cookie, never in JSON
) {}

public record ChangePasswordRequest(
    @NotBlank String currentPassword,
    @NotBlank @ValidPassword String newPassword
) {}
```

The raw session token is **never** in a JSON response body — only in `Set-Cookie` on the login response, so it is unreachable from JavaScript and not captured by body-logging middleware. **The HLD is silent on cookie-vs-body; choosing cookie-only because it eliminates the most common credential-leak path and matches the cookie-based contract in [technical-architecture.md §Authentication](../design/technical-architecture.md#authentication). Worth user review.**

`UserDto` deliberately excludes `passwordHash`, `failedLoginCount`, `lockedUntil`, `deletedAt`, `lastLoginIp` — operational fields stay server-side.

---

## Mappers

```java
@Mapper(componentModel = "spring")
public interface UserMapper {
    UserDto toDto(User entity);
    List<UserDto> toDtos(List<User> entities);
}

@Mapper(componentModel = "spring")
public interface SessionMapper { SessionDto toDto(Session entity); }
```

No custom mappings — all field names align.

---

## Repositories

Package-private; cross-module access goes through service interfaces only.

```java
interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByUsernameNormalised(String usernameNormalised);
    Optional<User> findByUsernameNormalisedAndDeletedAtIsNull(String usernameNormalised);
    boolean existsByUsernameNormalised(String usernameNormalised);
    List<User> findByIdIn(List<UUID> ids);
}

interface SessionRepository extends JpaRepository<Session, UUID> {
    Optional<Session> findByTokenHash(String tokenHash);
    List<Session> findByUserIdAndRevokedAtIsNull(UUID userId);

    @Modifying
    @Query("update Session s set s.revokedAt = :now where s.userId = :userId and s.revokedAt is null")
    int revokeAllActiveForUser(@Param("userId") UUID userId, @Param("now") Instant now);

    @Modifying
    @Query("delete from Session s where s.expiresAt < :cutoff or (s.revokedAt is not null and s.revokedAt < :cutoff)")
    int deleteExpiredAndRevokedBefore(@Param("cutoff") Instant cutoff);
}

interface LoginAttemptRepository extends JpaRepository<LoginAttempt, UUID> {
    long countByUsernameNormalisedAndAttemptedAtAfterAndSucceededFalse(String usernameNormalised, Instant since);
    long countBySourceIpAndAttemptedAtAfterAndSucceededFalse(InetAddress ip, Instant since);
}
```

The bulk JPQL `revokeAllActiveForUser` and `deleteExpiredAndRevokedBefore` queries keep password-change revocation and the nightly reaper to one statement each.

---

## Service Interfaces

Per the style guide, both module interfaces are implemented by a single `AuthServiceImpl`. `CurrentUserResolver` is a separate interface so cross-module callers inject only what they need and so the test-time impl can be a pure stub without booting Spring Security.

### `AuthQueryService`

```java
public interface AuthQueryService {
    Optional<UserDto> getUser(UUID userId);
    List<UserDto> getUsersByIds(List<UUID> userIds);
    Optional<UserDto> getUserByUsername(String username);

    // Session lookup — primarily used by SessionAuthenticationFilter and the /me endpoint.
    Optional<SessionDto> getActiveSession(UUID sessionId);
    List<SessionDto> getActiveSessionsForUser(UUID userId);
}
```

### `AuthUpdateService`

```java
public interface AuthUpdateService {
    UserDto register(RegisterRequest request);

    // Returns the raw session token alongside the persisted SessionDto.
    // Caller (controller) is responsible for placing the token in a Set-Cookie header
    // and discarding it from any other response surface.
    LoginOutcome login(LoginRequest request, LoginContext loginContext);

    void logout(UUID sessionId);
    void logoutAllSessionsForUser(UUID userId);

    UserDto changePassword(UUID userId, ChangePasswordRequest request);

    // Soft-delete a user. Revokes all active sessions in the same transaction.
    // Hard-delete and downstream data deletion are out of scope here — see Out of Scope.
    void deleteUser(UUID userId);
}

public record LoginContext(InetAddress sourceIp, String userAgent) {}

public record LoginOutcome(UserDto user, SessionDto session, String rawSessionToken) {}
```

`LoginOutcome` is the only place the raw token is exposed. It is not a client-facing DTO — the controller converts the token into a `Set-Cookie` and discards it. Encoding this in a dedicated type makes the boundary auditable.

### `CurrentUserResolver`

```java
public interface CurrentUserResolver {
    // Returns the userId of the currently authenticated principal, or empty for anonymous requests.
    Optional<UUID> currentUserId();

    // Convenience: throws AuthenticationRequiredException (mapped to 401) when missing.
    UUID requireCurrentUserId();

    // Returns the active session associated with the current request, or empty if none.
    Optional<SessionDto> currentSession();
}
```

`CurrentUserResolverImpl` reads from `SecurityContextHolder` and unwraps the principal placed there by `SessionAuthenticationFilter`. **The architectural rule of this LLD: other modules read `userId` exclusively through `CurrentUserResolver`** — never `SecurityContextHolder`, never any Spring Security type. `userId` then flows downward as an explicit method argument. This contains the Spring Security dependency to the auth module.

---

## REST Controllers

All endpoints under `/api/v1/auth/...`. OpenAPI: `@Tag(name = "Auth")` on the controller, `@Operation` on each handler.

| Method | Path | Request | Response | Status |
|---|---|---|---|---|
| POST | `/api/v1/auth/register` | `RegisterRequest` | `UserDto` | 201 / 400 / 409 |
| POST | `/api/v1/auth/login` | `LoginRequest` | `LoginResponse` (+ `Set-Cookie`) | 200 / 400 / 401 / 423 / 429 |
| POST | `/api/v1/auth/logout` | — | — | 204 |
| GET | `/api/v1/auth/me` | — | `UserDto` | 200 / 401 |
| PUT | `/api/v1/auth/password` | `ChangePasswordRequest` | `UserDto` | 200 / 400 / 401 |

Notes:

- **`POST /register` → 201** with the new `UserDto` **plus a `Set-Cookie: AUTH_SESSION=...`** — registration auto-logs the user in. **Locked decision (2026-05-07)**, overriding the agent's separated-flows preference: friction matters for first-impression UX. Password-strength feedback happens within the register POST itself via the `@ValidPassword` validator's structured error response. If email verification is added later it can re-introduce a separation; that work is bounded.
- **`POST /login` → 200** with `LoginResponse` plus `Set-Cookie: AUTH_SESSION=...` (attributes in [Flow 3](#flow-3-session-lifecycle)). Bad credentials → **401** with a generic message that never distinguishes unknown user from wrong password. Locked account → **423 Locked** with `Retry-After`. Throttled → **429** with `Retry-After`. **HLD silent on status differentiation; choosing 423 vs 429 because the states require different UI handling. Worth user review.**
- **`POST /logout` → 204** and clears the cookie via `Set-Cookie: AUTH_SESSION=; Max-Age=0`. Idempotent.
- **`GET /me`** is the canonical "am I logged in" probe. 200 with cookie, 401 without.
- **`PUT /password`** revokes all other sessions on success; the calling session is re-issued so the user is not bounced (see Flow 5).

### Error responses

All error responses use RFC 9457 `ProblemDetail`. Module-specific exceptions and their mappings (handled in the project-wide `GlobalExceptionHandler`):

| Exception | Status | `type` URI |
|---|---|---|
| `UsernameAlreadyTakenException` | 409 | `https://mealprep.example.com/problems/username-taken` |
| `InvalidCredentialsException` | 401 | `https://mealprep.example.com/problems/invalid-credentials` |
| `AccountLockedException` | 423 | `https://mealprep.example.com/problems/account-locked` |
| `LoginThrottledException` | 429 | `https://mealprep.example.com/problems/login-throttled` |
| `AuthenticationRequiredException` | 401 | `https://mealprep.example.com/problems/authentication-required` |
| `WeakPasswordException` | 400 | `https://mealprep.example.com/problems/weak-password` |
| `MethodArgumentNotValidException` | 400 | `errors[]` extension on ProblemDetail |

`InvalidCredentialsException` carries no detail beyond a generic message — the controller and handler must not expose the underlying reason.

Module root: `AuthException extends MealPrepException`.

---

## Validation

### `@ValidUsername` (custom)

- 3-64 characters
- ASCII letters, digits, dot, underscore, hyphen — `^[A-Za-z0-9._-]{3,64}$`
- Must not start or end with a separator
- Reserved-name list (e.g. `admin`, `root`, `system`, `support`) — configurable via `AuthProperties`

### `@ValidPassword` (custom)

HLD silent. Choosing NIST SP 800-63B-aligned defaults, configurable via `AuthProperties`:

- Minimum length **12** (`auth.password.min-length=12`)
- Maximum length **128** (DoS guard on BCrypt input)
- Must not equal the username (case-insensitive)
- Must not appear in a shipped block-list (top-1000 public breach corpora)
- No composition rules ("must contain uppercase + digit + symbol")

**Worth user review:** length floor (12 vs 10 vs 14), block-list size, deferred HaveIBeenPwned k-anonymity integration.

### Standard Jakarta annotations

`@NotBlank` on every credential field. `@Email` is **not** used (HLD says username, not email). Validation failures bubble up as `MethodArgumentNotValidException` → 400. `@ValidPassword` failures throw `WeakPasswordException` mapped to a ProblemDetail with a `reasons[]` extension (`TOO_SHORT`, `MATCHES_USERNAME`, `IN_BLOCKLIST`) so the UI can show actionable feedback without leaking block-list contents.

---

## Events

### Published

```java
public record UserRegisteredEvent(
    UUID userId, String username,
    UUID traceId, Instant occurredAt
) {}

public record UserLoggedInEvent(
    UUID userId, UUID sessionId, InetAddress sourceIp,
    UUID traceId, Instant occurredAt
) {}

public record UserDeletedEvent(
    UUID userId,
    UUID traceId, Instant occurredAt
) {}
```

`UserRegisteredEvent` is consumed by the preference and nutrition modules (seed empty per-user records) and household module (create default single-person household). Those listeners belong to those modules.

`UserLoggedInEvent` exists primarily for observability — keep if a listener materialises, drop on first cleanup otherwise. **Worth user review.**

`UserDeletedEvent` triggers downstream per-user data purge; the actual deletion is each module's concern.

Published via `ApplicationEventPublisher` after commit. Listeners use `@TransactionalEventListener(phase = AFTER_COMMIT)` per the style guide.

### Consumed

None. Auth is upstream of every other module's data.

---

## Business Logic Flows

### Flow 1: Registration

`POST /api/v1/auth/register` → `register(request)`. `@Transactional`.

1. Normalise username (`trim().toLowerCase(Locale.ROOT)`).
2. Jakarta validation already passed; reserved-name check is in `@ValidUsername`.
3. `existsByUsernameNormalised(...)` → `UsernameAlreadyTakenException` (409) on hit.
4. `passwordHasher.hash(rawPassword)` — BCrypt at `auth.bcrypt.cost-factor` (default **12**, ~250-400ms on modern hardware). HLD says "hashed password (simple)"; applying OWASP floor of 10 with headroom at 12. **Worth user review.**
5. Create `User` with `id = UUID.randomUUID()`, `passwordHash`, `passwordUpdatedAt = now`. Persist.
6. Publish `UserRegisteredEvent` after commit. Return `UserDto`. No session is created.

### Flow 2: Login (with throttling and lockout)

`POST /api/v1/auth/login` → `login(request, loginContext)`. `@Transactional`. The most security-sensitive path. Requirements: no leak of username existence, no leak of which credential was wrong, near-constant time across paths, bounded attempt rate per username and per IP, every attempt recorded.

1. Normalise username.
2. **Throttle pre-check.** `LoginThrottleService` counts failures in the last `auth.throttle.window-seconds` (default **900s / 15 min**):
   - per-username ≥ `auth.throttle.username-max-failures` (default **10**) → `LoginThrottledException` (429, `Retry-After`)
   - per-IP ≥ `auth.throttle.ip-max-failures` (default **30**) → `LoginThrottledException` (429)
3. Lookup `User` by `usernameNormalised`. If absent → run `passwordHasher.dummyVerify(rawPassword)` (constant-time BCrypt against a fixed dummy hash) so the unknown-user path costs the same as a real verify, record `LoginAttempt(succeeded=false, reason=UNKNOWN_USER)`, throw `InvalidCredentialsException` (401, generic message). **Worth user review** — the dummy-verify is the standard timing-enumeration mitigation; the hasher must expose a constant-cost stub.
4. **Lockout check.** If `user.lockedUntil > now` → record `LoginAttempt(reason=ACCOUNT_LOCKED)`, throw `AccountLockedException` (423) with unlock time in `Retry-After`.
5. `passwordHasher.verify(rawPassword, user.passwordHash)`.
6. **On failure:** record `LoginAttempt(reason=BAD_PASSWORD)`, `failedLoginCount += 1`. When `≥ auth.lockout.threshold` (default **5**), set `lockedUntil = now + auth.lockout.duration-seconds` (default **900s**) and reset count to 0. Persist. Throw `InvalidCredentialsException` (401, generic).
7. **On success:** `failedLoginCount = 0`, `lastLoginAt = now`, `lastLoginIp = sourceIp`. Generate **256-bit** token via `SessionTokenGenerator` (`SecureRandom` → 32 bytes → base64url). Compute `tokenHash = sha256(rawToken)`. Persist `Session` with `tokenHash`, `expiresAt = now + auth.session.absolute-ttl` (default **30 days**), `lastSeenAt = now`, `issuingIp`, `userAgent`. Record `LoginAttempt(succeeded=true)`. Publish `UserLoggedInEvent` after commit. Return `LoginOutcome(userDto, sessionDto, rawSessionToken)`.

Controller writes `Set-Cookie: AUTH_SESSION=<rawToken>; HttpOnly; Secure; SameSite=Lax; Path=/; Max-Age=<absolute-ttl-seconds>`. HLD silent on cookie flags; choosing the modern secure default:

- `HttpOnly` — keeps the token out of JavaScript / XSS reach
- `Secure` — HTTPS-only. `auth.cookie.secure=true` in prod, `false` in `application-dev.yml`
- `SameSite=Lax` — rejects cross-origin POST CSRF while allowing normal top-level navigation (`Strict` blocks cross-site auth links; `None` defeats protection). **Worth user review.**
- `Path=/` — every protected endpoint needs it
- No `Domain` attribute — cookie binds to the issuing host

#### Throttling design

Per-username throttling defends against single-account brute force; per-IP throttling defends against spraying across accounts. Both checks must pass. Window is rolling (`count(failures) where attempted_at > now - window`) so attackers cannot abuse fixed-bucket boundaries.

Lockout is distinct from throttling: throttling resets by time alone; lockout writes to the user row and persists past the throttle window. Both exist because they cover different attack shapes (high-rate brute force vs slow-and-low). **Worth user review:** threshold (5), duration (15 min), and whether to add an admin-unlock endpoint (deferred).

### Flow 3: Session Lifecycle

`SessionAuthenticationFilter` runs early in the Spring Security chain on every request:

1. Read `AUTH_SESSION` cookie. Absent → context stays anonymous.
2. Compute `tokenHash = sha256(cookieValue)`. Lookup via `findByTokenHash`. Use a constant-time equality helper on the post-lookup check — cheap defence in depth.
3. Reject (anonymous context) when row missing, `expiresAt < now`, `revokedAt != null`, or `user.deletedAt != null` (also revokes the session in the latter case).
4. (No-op — `lastSeenAt` is populated at session creation only; not updated per-request per the locked decision above.)
5. Set the Spring Security `Authentication`: principal carries `userId` and `sessionId`; authorities are `[ROLE_USER]` (single role until household-admin lands).

**Absolute TTL, no sliding extension.** `expiresAt` is the ceiling and never moves; `lastSeenAt` is observability only. **Locked decision (2026-05-07)** — rolling sessions extend stolen-cookie lifetime indefinitely.

**`lastSeenAt` is populated on login only, not per-request.** **Locked decision (2026-05-07)**, overriding the agent's per-request-write proposal: the value is only consumed by a future "active sessions on other devices" UI, which is out of scope for v1. The per-request write was paying observability cost for a feature not built. When that UI lands, "last seen" can be computed lazily from the most recent decision-log entry for the session's user — same data, no write amplification.

### Flow 4: Logout

`POST /api/v1/auth/logout`. `@Transactional`. Resolves current session via `CurrentUserResolver.currentSession()`; sets `revokedAt = now`. Controller emits `Set-Cookie: AUTH_SESSION=; Max-Age=0`. Idempotent — 204 even when there is no active session. `logoutAllSessionsForUser` is the bulk variant used by Flow 5.

### Flow 5: Password Change

`PUT /api/v1/auth/password` → `changePassword(userId, request)`. `@Transactional`.

1. Controller resolves `userId` via `CurrentUserResolver.requireCurrentUserId()`.
2. Verify `currentPassword`. Mismatch → `InvalidCredentialsException` (401). The attempt is recorded as `LoginAttempt(reason=BAD_PASSWORD)` so it counts toward the throttle window — same brute-force surface, same defence.
3. `@ValidPassword` already passed; re-check `password != username` and not in block-list.
4. Hash with `passwordHasher.hash`. Update `passwordHash`, `passwordUpdatedAt`.
5. **Bulk-revoke all other active sessions** via `revokeAllActiveForUser`, then revoke and re-issue the current session so the calling user is not bounced. **HLD silent; choosing global revoke + current re-issue because password change is the canonical "compromise" trigger. Worth user review.**

### Flow 6: Session reaper

`@Scheduled(cron = "0 15 3 * * *")` runs nightly; `deleteExpiredAndRevokedBefore(now - auth.session.retain-revoked-for)` (default retention **7 days**) hard-deletes stale session rows. `LoginAttempt` retains the longer-term audit trail.

---

## Cross-Module Integration

The most-touched seam in the codebase. Done well, it costs nothing; done badly, it pollutes every module with Spring Security imports.

### `CurrentUserResolver` is the boundary

Other modules inject `CurrentUserResolver` (re-exported by `AuthModule`). They never inject Spring Security types, never read `SecurityContextHolder`, never touch the `Authentication` principal directly.

```java
@RequiredArgsConstructor
@RestController
public class HardConstraintsController {
    private final CurrentUserResolver currentUserResolver;
    private final PreferenceQueryService preferenceQueryService;

    @GetMapping("/api/v1/preferences/hard-constraints")
    public HardConstraintsDto get() {
        UUID userId = currentUserResolver.requireCurrentUserId();
        return preferenceQueryService.getHardConstraints(userId)
            .orElseThrow(HardConstraintsNotFoundException::new);
    }
}
```

`userId` flows downward as an explicit method argument. No thread-locals.

Per-route authorisation rules belong in each module's controller LLD (e.g. "audit-log endpoint requires the requester's userId to match the row"). The auth module exposes the resolver; the consumer enforces the rule. Household admin authorities arrive with the household module — until then, every authenticated request carries `[ROLE_USER]` and acts as the user it owns. **Worth user review.**

---

## Concurrency and Transactions

| Concern | Decision |
|---|---|
| `@Transactional` placement | All service-impl methods. Repositories never. |
| Read-method propagation | `@Transactional(readOnly = true)`. |
| Write-method propagation | Default REQUIRED. |
| Optimistic locking | `@Version` on `User` and `Session`. Append-only `LoginAttempt` — no `@Version`. |
| Pessimistic locking | None. |
| Concurrent same-user logins | Each login produces a new `Session` row. No conflict. |
| Concurrent password-change vs login | The login flow updates `lastLoginAt` while password-change updates `passwordHash`/`passwordUpdatedAt`. `@Version` on `User` causes the loser to retry; the loser's outcome (login or change) is naturally idempotent on retry. |
| `lastSeenAt` semantics | Set on session creation only; never updated by the per-request authentication path. No write amplification. The "active sessions" UI when built computes recency from decision-log entries, not from this column. |
| Session reaper concurrency with active reads | `deleteExpiredAndRevokedBefore` operates on rows already filtered by `expiresAt < now` or `revokedAt < cutoff`. The filter chain rejects expired/revoked sessions before any business logic, so deleting them mid-request is safe. |

---

## Observability

- `INFO` on every successful registration, login, logout, password change — `userId`, `sessionId`, `traceId`. No username at INFO (PII).
- `WARN` on every locked-account hit, throttle rejection, unknown-user attempt that overlaps an IP burst.
- `ERROR` only for infrastructure failures (DB unavailable, hasher misconfigured).
- `DEBUG` may carry the username for dev troubleshooting only.
- Session reaper logs the per-run prune count at INFO.

Auth is the system's gatekeeper; this audit-trail discipline parallels [technical-architecture.md §Observability](../design/technical-architecture.md#observability).

---

## Test Plan

Unit tests use `@ExtendWith(MockitoExtension.class)`. Integration tests are `*IT.java` with Testcontainers Postgres. Names follow `methodName_scenario_expected`.

### Unit

| Class | Verifies |
|---|---|
| `AuthServiceImplTest` | All `AuthQueryService` and `AuthUpdateService` happy paths and error mappings, with mocked repositories, `PasswordHasher`, `SessionTokenGenerator`, `LoginThrottleService`. |
| `PasswordHasherTest` | `hash` produces a verifiable BCrypt string at the configured cost. `verify` accepts correct password, rejects wrong. `dummyVerify` returns false but takes comparable wall-clock time to a real `verify` (asserted with a wide tolerance — guards against accidentally short-circuiting the unknown-user path). |
| `PasswordStrengthValidatorTest` | Each rejection reason fires as expected: too short, too long, equals username (case-insensitive), in block-list. Accepts a 12-char non-block-listed password. |
| `SessionTokenGeneratorTest` | Tokens are 256 bits of entropy (32 bytes pre-encoding). Two consecutive tokens never collide in 10 000 generations. `tokenHash` is deterministic SHA-256 of the raw token. |
| `LoginThrottleServiceTest` | Below-threshold attempts pass. At-threshold username and at-threshold IP both reject. Window expiry reopens (clock-mocked). |
| `CurrentUserResolverImplTest` | Anonymous context → `currentUserId()` empty, `requireCurrentUserId()` throws. Authenticated context → both return the principal's `userId`. |
| `UsernameValidatorTest` | Valid characters, length floor/ceiling, separator-edge rejection, reserved-name rejection. |
| `UserMapperTest`, `SessionMapperTest` | MapStruct round-trips preserve all DTO-visible fields and mask the secret ones. |

### Integration

| Class | Verifies |
|---|---|
| `AuthControllerIT` | Full HTTP cycle over MockMvc: `POST /register` (201, 400 on weak password, 409 on duplicate), `POST /login` (200 with `Set-Cookie`, 401 generic on bad password and unknown user, 423 on locked, 429 on throttled), `POST /logout` (204 + cookie cleared), `GET /me` (200 with cookie, 401 without), `PUT /password` (200, revokes other sessions, current session re-issued). Verifies the raw token never appears in any JSON response body. |
| `AuthSecurityConfigIT` | The security filter chain wires `SessionAuthenticationFilter` correctly. Cookie attributes on the login response: `HttpOnly`, `Secure` (in prod-like profile), `SameSite=Lax`, `Path=/`, `Max-Age` matches absolute TTL. Anonymous request to a protected route returns 401, not 403. |
| `LoginFlowIT` | Lockout: 5 failures → 6th returns 423 with `Retry-After`; after window, login succeeds and `failed_login_count` resets. Throttle: 10 IP failures across distinct usernames → 11th returns 429. Unknown-user timing: response time within ±20% of a bad-password real-user attempt over 50 trials. |
| `SessionLifecycleIT` | Login issues a session row with hashed token. Subsequent authenticated request finds it via `tokenHash` (and a request with a tampered cookie does not). Session past `expiresAt` rejects. Soft-deleted user's still-valid session rejects. `lastSeenAt` is populated at creation and **does not change** on subsequent authenticated requests (locked decision). |
| `PasswordChangeIT` | Successful change revokes all other active sessions for the user. Wrong current-password counts toward throttle. New password fails `@ValidPassword` checks return 400 with `reasons[]`. |
| `SessionReaperIT` | Schedules a manual run; rows past retention are deleted; rows still in retention remain. |
| `FlywayMigrationIT` | Boots Postgres, runs all auth migrations, validates schema against the JPA mapping (`spring.jpa.hibernate.ddl-auto=validate`). |
| `EventPublicationIT` | Registration, login, deletion publish their respective events only after commit. A failing test-scoped listener does not roll back the underlying state. |

---

## Configuration

`AuthProperties` (`@ConfigurationProperties(prefix = "auth")`):

```properties
auth.bcrypt.cost-factor=12
auth.password.min-length=12
auth.password.max-length=128
auth.password.blocklist-resource=classpath:auth/password-blocklist.txt
auth.username.reserved-names=admin,root,system,support
auth.session.absolute-ttl=30d
auth.session.retain-revoked-for=7d
auth.cookie.name=AUTH_SESSION
auth.cookie.secure=true
auth.cookie.same-site=Lax
auth.throttle.window-seconds=900
auth.throttle.username-max-failures=10
auth.throttle.ip-max-failures=30
auth.lockout.threshold=5
auth.lockout.duration-seconds=900
```

Defaults documented in this LLD; per-environment overrides via `application-<profile>.properties`. `auth.cookie.secure=false` in `application-dev.yml` only.

---

## Out of Scope

Deferred deliberately:

- **OAuth / SSO / federated identity / SAML.** HLD: "no OAuth initially".
- **Multi-factor authentication (MFA / TOTP / WebAuthn).** Future module addition.
- **Email-based password reset.** No email infrastructure is committed yet; in-app password change while logged in is the only v1 recovery path.
- **Account recovery flows** beyond in-app password change (email reset, recovery codes, security questions).
- **Per-route authorisation rules.** Each module's controller LLD documents its own.
- **Household-admin role and authority model.** Awaits the household module LLD.
- **Admin-unlock endpoint.** Lockouts auto-clear after the configured window in v1.
- **HaveIBeenPwned integration** beyond the static block-list.
- **Hard-delete with cross-module cascade.** Soft-delete is v1. GDPR-correct deletion needs every module to honour `UserDeletedEvent` with its own purge flow — a project-wide deliverable.
- **Session-list UI** ("see and revoke active sessions on other devices"). Data is captured (`userAgent`, `issuingIp`, `lastSeenAt`); endpoints/UI deferred.
- **Gateway / WAF rate limiting.** Application-level throttling is here; infra rate limiting is a hosting concern.
- **CSRF tokens.** With `SameSite=Lax` and JSON-only POSTs (rejecting `application/x-www-form-urlencoded`), explicit CSRF tokens are not required for v1. **Worth user review** — adding `spring-security-csrf` is trivial if the threat model tightens.
