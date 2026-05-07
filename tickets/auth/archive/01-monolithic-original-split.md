# Ticket: auth — 01 User Entity and Registration

## Summary

Implement the auth module's first slice: User entity + Session entity + LoginAttempt entity + register/login/logout/me/password endpoints + Spring Security 6 baseline + ProblemDetail error mapping. Per [`lld/auth.md`](../../lld/auth.md). Security-sensitive — additional review focus on hashing, token handling, throttling.

This is **Pilot 2** of the implementation playbook. Validates:
- Spring Security 6 wiring under our conventions
- Auth-specific edge cases (timing parity, throttling, lockout) testable
- OpenAPI authoring for security-flavoured endpoints
- Mutation testing on security-critical code (BCrypt verify, token generation, throttle counters)

## Behavioural spec (write this BEFORE implementation)

The implementation must guarantee:

### Registration

1. `POST /api/v1/auth/register` with valid `RegisterRequest { username, password }` returns 201 + `UserDto` body + `Set-Cookie: AUTH_SESSION=<token>; HttpOnly; Secure (prod only); SameSite=Lax; Path=/; Max-Age=2592000` (30-day absolute TTL).
2. Registration auto-logs the user in (locked decision per [`lld/auth.md`](../../lld/auth.md)).
3. Username uniqueness enforced via DB unique constraint; second register with same username returns 409 ProblemDetail (no Set-Cookie).
4. Password rules per `@ValidPassword`: length 12-128, no leading/trailing whitespace, not equal to username (case-insensitive), not in the static block list of top-1000 breached passwords. Failures return 400 ProblemDetail with field-level `errors[]` listing each violated rule.
5. Password is hashed with BCrypt cost factor 12 before storage; the raw password is never logged (verify by greppable absence + LoggingConfigTest).
6. `UserRegisteredEvent` published `AFTER_COMMIT` carrying `userId, username, registeredAt, traceId`.
7. Audit row in `auth_login_attempts` not created for register.

### Login

8. `POST /api/v1/auth/login` with valid credentials returns 200 + `LoginResponse { userId, username }` + `Set-Cookie: AUTH_SESSION=...` (same attributes).
9. Login response **never** includes the raw token in the JSON body — only via `Set-Cookie`.
10. Bad password: 401 with generic ProblemDetail (`"Invalid credentials"`); `Retry-After` header NOT set (only set for throttle/lockout).
11. Unknown username: same 401, same generic message. **Dummy BCrypt verify** runs against a dummy hash to keep timing parity with real verify (within ±10ms verified by IT).
12. `UserLoggedInEvent` published `AFTER_COMMIT` with `userId, sessionId, ipAddress, userAgent, loggedInAt, traceId`. Suppressed for failed logins.
13. Each login attempt (success or failure) inserts a row in `auth_login_attempts` with `username, ipAddress, occurredAt, outcome (SUCCESS | INVALID_CREDENTIALS | LOCKED | THROTTLED)`.

### Throttling and lockout

14. **Per-username throttle**: 10 failed attempts in 15-min rolling window → 429 with `Retry-After` until the window expires for the next attempt. Counts only `INVALID_CREDENTIALS` (not LOCKED).
15. **Per-IP throttle**: 30 failed attempts in 15-min rolling window from the same IP → 429.
16. **Lockout**: 5 consecutive `INVALID_CREDENTIALS` for one user → user row's `lockedUntil` set to `now + 15 min`. Login attempts during lockout return 423 Locked with `Retry-After`. Successful login during the 5-attempt window resets the counter.
17. Lockout, throttle, and dummy-verify all log via SLF4J at INFO with structured fields (no PII beyond username); no leakage of password attempts to logs.

### Session lifecycle

18. Session token: 256 bits of entropy (32 bytes from `SecureRandom`, base64url-encoded). Raw token is the cookie value; only its SHA-256 hash is persisted in `auth_sessions.token_hash`.
19. Session `expiresAt = createdAt + 30 days` absolute. Per-request `lastSeenAt` is **NOT** updated (locked decision); set at creation only.
20. `SessionAuthenticationFilter` reads the cookie, looks up the session by `tokenHash`, attaches the authenticated `userId` to `SecurityContext`. Missing/invalid cookie → SecurityContext stays anonymous; controller returns 401 if endpoint requires auth.
21. `GET /api/v1/auth/me` with valid cookie returns 200 + `UserDto`. Without cookie or with expired/revoked session returns 401.
22. `POST /api/v1/auth/logout` with valid session → 204 + `Set-Cookie: AUTH_SESSION=; Max-Age=0` (clears). Session row's `revokedAt` set to now. Idempotent: subsequent logout calls also return 204.

### Password change

23. `PUT /api/v1/auth/password` with `PasswordChangeRequest { currentPassword, newPassword }` requires authenticated session. Verifies `currentPassword` via BCrypt (real verify, not dummy). Updates user's `passwordHash` to new BCrypt'd value.
24. Password change bulk-revokes ALL OTHER sessions for the user (sets `revokedAt = now` on every session row except the calling one). Calling session is **re-issued** (new token, new cookie) so the user is not bounced.
25. Password change returns 200 + `Set-Cookie: AUTH_SESSION=<new-token>` (re-issued session).
26. `UserPasswordChangedEvent` published with `userId, sessionsRevokedCount, traceId`.

### Cookie attributes per profile

27. Dev profile: `Secure=false` (allows http://localhost). Prod profile: `Secure=true` (enforced via Spring Security cookie config). `HttpOnly` and `SameSite=Lax` always.

## OpenAPI spec excerpt

5 paths added; 4 schemas added. Cookie security scheme defined.

```yaml
components:
  securitySchemes:
    cookieAuth:
      type: apiKey
      in: cookie
      name: AUTH_SESSION
      description: Session cookie issued by /auth/login or /auth/register.

  schemas:
    RegisterRequest:
      type: object
      required: [username, password]
      properties:
        username: { type: string, minLength: 3, maxLength: 32, pattern: '^[a-zA-Z0-9_-]+$' }
        password: { type: string, minLength: 12, maxLength: 128 }

    LoginRequest:
      type: object
      required: [username, password]
      properties:
        username: { type: string }
        password: { type: string }

    LoginResponse:
      type: object
      required: [userId, username]
      properties:
        userId: { type: string, format: uuid }
        username: { type: string }

    UserDto:
      type: object
      required: [userId, username, createdAt]
      properties:
        userId: { type: string, format: uuid }
        username: { type: string }
        createdAt: { type: string, format: date-time }

    PasswordChangeRequest:
      type: object
      required: [currentPassword, newPassword]
      properties:
        currentPassword: { type: string }
        newPassword: { type: string, minLength: 12, maxLength: 128 }

paths:
  /api/v1/auth/register:
    post:
      tags: [Auth]
      operationId: register
      summary: Create a new user account and auto-log in.
      requestBody:
        required: true
        content:
          application/json:
            schema: { $ref: '#/components/schemas/RegisterRequest' }
      responses:
        '201':
          description: User created; session cookie issued.
          headers:
            Set-Cookie:
              schema: { type: string }
              description: AUTH_SESSION cookie.
          content:
            application/json:
              schema: { $ref: '#/components/schemas/UserDto' }
        '400': { $ref: '#/components/responses/BadRequest' }
        '409': { $ref: '#/components/responses/Conflict' }

  /api/v1/auth/login:
    post:
      tags: [Auth]
      operationId: login
      summary: Authenticate; receive session cookie.
      requestBody:
        required: true
        content:
          application/json:
            schema: { $ref: '#/components/schemas/LoginRequest' }
      responses:
        '200':
          description: Logged in; session cookie issued.
          headers:
            Set-Cookie:
              schema: { type: string }
          content:
            application/json:
              schema: { $ref: '#/components/schemas/LoginResponse' }
        '401':
          description: Invalid credentials (generic; never distinguishes unknown user from wrong password).
          content:
            application/problem+json:
              schema: { $ref: '#/components/schemas/ProblemDetail' }
        '423':
          description: User locked due to consecutive failed attempts.
          headers:
            Retry-After:
              schema: { type: integer }
          content:
            application/problem+json:
              schema: { $ref: '#/components/schemas/ProblemDetail' }
        '429': { $ref: '#/components/responses/TooManyRequests' }

  /api/v1/auth/logout:
    post:
      tags: [Auth]
      operationId: logout
      summary: Revoke the current session.
      security:
        - cookieAuth: []
      responses:
        '204':
          description: Logged out; cookie cleared.
          headers:
            Set-Cookie:
              schema: { type: string }
              description: Cleared AUTH_SESSION cookie.

  /api/v1/auth/me:
    get:
      tags: [Auth]
      operationId: getCurrentUser
      summary: Probe authentication state.
      security:
        - cookieAuth: []
      responses:
        '200':
          description: Authenticated user details.
          content:
            application/json:
              schema: { $ref: '#/components/schemas/UserDto' }
        '401': { $ref: '#/components/responses/Unauthorized' }

  /api/v1/auth/password:
    put:
      tags: [Auth]
      operationId: changePassword
      summary: Rotate password; revoke other sessions; re-issue current.
      security:
        - cookieAuth: []
      requestBody:
        required: true
        content:
          application/json:
            schema: { $ref: '#/components/schemas/PasswordChangeRequest' }
      responses:
        '200':
          description: Password changed; new session cookie issued; other sessions revoked.
          headers:
            Set-Cookie:
              schema: { type: string }
          content:
            application/json:
              schema: { $ref: '#/components/schemas/UserDto' }
        '400': { $ref: '#/components/responses/BadRequest' }
        '401': { $ref: '#/components/responses/Unauthorized' }
```

## Edge-case checklist

Security-flavoured tickets carry a heavier checklist. Each item has a corresponding test.

- [ ] BCrypt cost factor exactly 12 (verified via `BCryptPasswordEncoder` config inspection)
- [ ] Token entropy ≥256 bits (32 bytes verified)
- [ ] Token hash != raw token (raw never persisted; verified by reading `auth_sessions` post-register)
- [ ] Cookie `HttpOnly` always on
- [ ] Cookie `Secure` true in prod profile, false in dev — verified per profile
- [ ] Cookie `SameSite=Lax` always
- [ ] Cookie `Domain` not set (no Domain attribute leaks subdomain)
- [ ] Session past `expiresAt` rejected on next request (returns 401)
- [ ] Soft-deleted user's still-valid session rejected
- [ ] Concurrent register with same username — DB unique violation surfaces as 409, not 500
- [ ] Concurrent login (same user) — both succeed (independent session rows)
- [ ] Throttle counts only INVALID_CREDENTIALS (not LOCKED, not SUCCESS)
- [ ] Per-username and per-IP throttles work simultaneously (a single attacker can't bypass IP throttle by switching usernames)
- [ ] Lockout reset by successful login within the 5-attempt counter
- [ ] Dummy BCrypt verify on unknown user has timing within 10ms of real verify
- [ ] Generic 401 message used for both unknown-user and wrong-password
- [ ] Password change with wrong currentPassword returns 401 (NOT 400)
- [ ] Password change bulk-revokes other sessions but keeps current
- [ ] Password change re-issues current session with fresh token
- [ ] All endpoints emit appropriate events `AFTER_COMMIT`
- [ ] Login attempt audit row written for every attempt (success and failure)
- [ ] Audit row never contains the password attempt
- [ ] CSRF mitigation: `SameSite=Lax` cookie + JSON-only POST verified by `application/x-www-form-urlencoded` rejection on register/login (via Spring Security default)
- [ ] Spring Security filter chain doesn't accidentally allow anonymous access to non-whitelist endpoints (verified by `SecurityChainTest`)
- [ ] Logout idempotent: second logout call returns 204
- [ ] Block list of breached passwords loaded at startup; verified one entry rejects
- [ ] OpenAPI request/response schemas match (contract test in every controller IT)
- [ ] Mutation score on `PasswordHasher`, `SessionTokenGenerator`, `LoginThrottleService`, `SessionAuthenticationFilter` ≥70%
- [ ] No raw passwords in any log (verified by tail-grep on `target/test.log` after running the IT suite)

## Files this ticket touches

```
src/main/java/com/example/mealprep/auth/AuthModule.java                                              new
src/main/java/com/example/mealprep/auth/api/controller/AuthController.java                           new
src/main/java/com/example/mealprep/auth/api/dto/RegisterRequest.java                                 new
src/main/java/com/example/mealprep/auth/api/dto/LoginRequest.java                                    new
src/main/java/com/example/mealprep/auth/api/dto/LoginResponse.java                                   new
src/main/java/com/example/mealprep/auth/api/dto/UserDto.java                                         new
src/main/java/com/example/mealprep/auth/api/dto/PasswordChangeRequest.java                           new
src/main/java/com/example/mealprep/auth/api/mapper/UserMapper.java                                   new (MapStruct)
src/main/java/com/example/mealprep/auth/api/mapper/SessionMapper.java                                new (MapStruct)
src/main/java/com/example/mealprep/auth/domain/entity/User.java                                      new
src/main/java/com/example/mealprep/auth/domain/entity/Session.java                                   new
src/main/java/com/example/mealprep/auth/domain/entity/LoginAttempt.java                              new
src/main/java/com/example/mealprep/auth/domain/repository/UserRepository.java                        new
src/main/java/com/example/mealprep/auth/domain/repository/SessionRepository.java                     new
src/main/java/com/example/mealprep/auth/domain/repository/LoginAttemptRepository.java                new
src/main/java/com/example/mealprep/auth/domain/service/AuthQueryService.java                         new (interface)
src/main/java/com/example/mealprep/auth/domain/service/AuthUpdateService.java                        new (interface)
src/main/java/com/example/mealprep/auth/domain/service/CurrentUserResolver.java                      new (interface; cross-module use)
src/main/java/com/example/mealprep/auth/domain/service/internal/AuthServiceImpl.java                 new
src/main/java/com/example/mealprep/auth/domain/service/internal/PasswordHasher.java                  new
src/main/java/com/example/mealprep/auth/domain/service/internal/PasswordStrengthValidator.java       new
src/main/java/com/example/mealprep/auth/domain/service/internal/SessionTokenGenerator.java           new
src/main/java/com/example/mealprep/auth/domain/service/internal/LoginThrottleService.java            new
src/main/java/com/example/mealprep/auth/domain/service/internal/SessionAuthenticationFilter.java     new
src/main/java/com/example/mealprep/auth/domain/service/internal/CurrentUserResolverImpl.java         new
src/main/java/com/example/mealprep/auth/event/UserRegisteredEvent.java                               new
src/main/java/com/example/mealprep/auth/event/UserLoggedInEvent.java                                 new
src/main/java/com/example/mealprep/auth/event/UserPasswordChangedEvent.java                          new
src/main/java/com/example/mealprep/auth/event/UserDeletedEvent.java                                  new (record only; no logic in this ticket)
src/main/java/com/example/mealprep/auth/exception/UsernameAlreadyExistsException.java                new
src/main/java/com/example/mealprep/auth/exception/InvalidCredentialsException.java                   new
src/main/java/com/example/mealprep/auth/exception/AccountLockedException.java                       new
src/main/java/com/example/mealprep/auth/exception/LoginThrottledException.java                      new
src/main/java/com/example/mealprep/auth/exception/PasswordRuleViolationException.java               new
src/main/java/com/example/mealprep/auth/validation/ValidPassword.java                               new (annotation)
src/main/java/com/example/mealprep/auth/validation/ValidPasswordValidator.java                      new
src/main/java/com/example/mealprep/auth/validation/ValidUsername.java                               new
src/main/java/com/example/mealprep/auth/validation/ValidUsernameValidator.java                      new
src/main/java/com/example/mealprep/auth/config/AuthSecurityConfig.java                              new (Spring Security filter chain)
src/main/java/com/example/mealprep/auth/config/AuthProperties.java                                  new (@ConfigurationProperties)
src/main/java/com/example/mealprep/config/GlobalExceptionHandler.java                               new (cross-cutting RestControllerAdvice — first ticket to land it)
src/main/resources/db/migration/V20260601200000__auth_create_users.sql                              new
src/main/resources/db/migration/V20260601200100__auth_create_sessions.sql                           new
src/main/resources/db/migration/V20260601200200__auth_create_login_attempts.sql                     new
src/main/resources/auth/breached-passwords.txt                                                      new (top-1000 list)
src/main/resources/openapi/openapi.yaml                                                             modified (5 paths + 4 schemas + cookieAuth)
src/test/java/com/example/mealprep/auth/PasswordHasherTest.java                                     new (unit)
src/test/java/com/example/mealprep/auth/PasswordStrengthValidatorTest.java                          new (unit)
src/test/java/com/example/mealprep/auth/SessionTokenGeneratorTest.java                              new (unit; entropy + uniqueness)
src/test/java/com/example/mealprep/auth/LoginThrottleServiceTest.java                               new (unit; counter logic)
src/test/java/com/example/mealprep/auth/AuthServiceImplTest.java                                    new (unit; happy paths with real collaborators except the repos)
src/test/java/com/example/mealprep/auth/RegisterFlowIT.java                                         new (full HTTP flow + contract test)
src/test/java/com/example/mealprep/auth/LoginFlowIT.java                                            new (success + bad password + unknown user; timing-parity assertion)
src/test/java/com/example/mealprep/auth/SessionLifecycleIT.java                                     new (cookie + filter chain + revocation)
src/test/java/com/example/mealprep/auth/ThrottlingAndLockoutIT.java                                 new (per-username + per-IP throttles; lockout)
src/test/java/com/example/mealprep/auth/PasswordChangeIT.java                                       new (rotation + bulk-revoke + re-issue)
src/test/java/com/example/mealprep/auth/SecurityChainTest.java                                      new (filter chain; anonymous access whitelist)
src/test/java/com/example/mealprep/auth/testdata/AuthTestData.java                                  new (Test Data Builder)
perf/auth/register.js                                                                               new (k6)
perf/auth/login.js                                                                                  new (k6)
```

## Performance budget

| Endpoint | Median | p95 |
|---|---|---|
| `POST /api/v1/auth/register` | 250ms | 500ms (BCrypt-dominated) |
| `POST /api/v1/auth/login` (success) | 250ms | 500ms (BCrypt-dominated) |
| `POST /api/v1/auth/login` (bad password) | 250ms | 500ms (must match success — timing parity) |
| `POST /api/v1/auth/login` (unknown user) | 250ms | 500ms (dummy verify; must match success) |
| `GET /api/v1/auth/me` | 10ms | 30ms |
| `POST /api/v1/auth/logout` | 10ms | 30ms |
| `PUT /api/v1/auth/password` | 500ms | 1000ms (BCrypt verify + BCrypt hash + bulk update) |

Timing-parity assertion: median variance between success / bad-password / unknown-user logins ≤ 25ms. Asserted in `LoginFlowIT`.

## Test plan

### Unit tests

| Class | Verifies |
|---|---|
| `PasswordHasherTest` | BCrypt cost 12 enforced; verify true on match; verify false on mismatch; verify deterministically takes ≥150ms (rough cost-12 indicator on test machine) |
| `PasswordStrengthValidatorTest` | All 4 rules: length, no whitespace, not == username, not in block list. Edge: 12-char minimum, 128-char maximum, exactly-on-block-list password rejected, near-miss not rejected |
| `SessionTokenGeneratorTest` | Token is 256 bits (32 bytes pre-encoding); 1000 generated tokens have zero collisions; tokens are URL-safe |
| `LoginThrottleServiceTest` | Counter advances on INVALID_CREDENTIALS only; resets on SUCCESS; window math correct; per-username and per-IP buckets independent |
| `AuthServiceImplTest` | Happy paths for register/login/logout/me/password — collaborators are real except `*Repository` (mocked). Each method's transaction propagation verified |

### Integration tests (Testcontainers Postgres)

| Class | Verifies |
|---|---|
| `RegisterFlowIT` | POST /register → 201 + cookie + DB row + event published. Duplicate username → 409. Weak password → 400 with field-level errors. OpenApi contract validated. |
| `LoginFlowIT` | Success + cookie + event. Bad password → 401 with generic message + audit row + counter incremented. Unknown user → same generic 401 + audit row + dummy verify timing matches real verify within ±10ms. Cookie attributes correct per profile. |
| `SessionLifecycleIT` | Authenticated request finds session by tokenHash. Tampered cookie rejected. Expired session rejected. Revoked session rejected. Soft-deleted user's still-valid session rejected. `lastSeenAt` does NOT change between requests (locked decision verified). |
| `ThrottlingAndLockoutIT` | 11th failed attempt in window → 429 with Retry-After. 31st from same IP → 429. 5 consecutive failures → user locked → next attempt 423 with Retry-After. Successful login resets counter (verified by interleaving attempts). |
| `PasswordChangeIT` | Rotation works. Other sessions revoked (verified by querying `revokedAt`). Calling session re-issued (cookie value changes; old cookie rejected on next request). Wrong currentPassword → 401. Event published. |
| `SecurityChainTest` | `/api/v1/auth/register`, `/api/v1/auth/login`, `/v3/api-docs/**`, `/swagger-ui/**` are anonymous-allowed; everything else requires auth. Verified by hitting representative non-auth endpoints (decision-log endpoints from Pilot 1) without cookie → 401. |

### Contract test

Every controller IT imports `OpenApiValidatorConfig` and asserts request/response shapes match the spec.

### Mutation testing

≥70% mutation score on `PasswordHasher`, `SessionTokenGenerator`, `LoginThrottleService`, `SessionAuthenticationFilter`, and the `AuthServiceImpl` security-flavoured methods. Lower threshold (≥60%) acceptable on DTO mappers and pure data classes.

### Performance test

`perf/auth/register.js` and `perf/auth/login.js` k6 scripts. 10 VUs, 30-second runs. Assert budget thresholds.

## Dependencies on other tickets

- **Hard dependency**: `project-setup-00-bootstrap` (foundation)
- **Hard dependency**: `core-01-decision-log` (this ticket uses `core.lock.LockService` for the throttle counter? No — throttling is per-row `auth_login_attempts` query, not a lock. But `core.events.MealPrepEvent` sealed base is used for the auth events. Hard dep on core types/events.)

## Time estimate

**2 days.** Security-sensitive; lots of edge cases; the timing-parity assertion + throttle/lockout state machine is finicky.

## Decisions left to the implementor

1. **Block-list source** — Have I Been Pwned's top-1k or NIST's reference list. Either is fine; load at startup from `src/main/resources/auth/breached-passwords.txt`. Performance: a `Set<String>` lookup is O(1).
2. **Dummy BCrypt hash for unknown-user verify** — pre-computed BCrypt hash of a random string, stored as a constant. Verify against this when user doesn't exist. The BCrypt cost is the dominant timing factor; this matches naturally.
3. **Throttle counter storage** — query `auth_login_attempts` directly with a windowed COUNT (`WHERE username = ? AND outcome = 'INVALID_CREDENTIALS' AND occurred_at > now() - INTERVAL '15 minutes'`). No separate counter table; the audit table doubles as the source. Index `(username, occurred_at DESC) WHERE outcome = 'INVALID_CREDENTIALS'` makes this fast.
4. **Login audit retention** — keep all `auth_login_attempts` indefinitely for v1; sweep for >12-month-old records via a scheduled job in a follow-up ticket.
5. **Spring Security filter ordering** — `SessionAuthenticationFilter` runs before `UsernamePasswordAuthenticationFilter`; documented in `AuthSecurityConfig`.
6. **Test data builder** — `AuthTestData.user().withUsername(...).withPassword(...).build()` returns a User entity; `.persistedUser(...)` variant inserts via TestEntityManager.
7. **Timing-parity assertion threshold** — ±10ms in unit-test environment; ±25ms in IT. Asserted via repeated calls (50+) and median comparison; not single-sample.

## Acceptance / DoD

When the PR is opened:

- [ ] All CI gates green
- [ ] Mutation score ≥70% on the security-critical classes (specifically asserted in `pitest.properties` mutator targets)
- [ ] Edge-case checklist all ticked
- [ ] Reviewer (you) eyeballs ≥5 random tests for hollowness; pass
- [ ] No raw password in any log (`grep -ri "password.*=.*[a-zA-Z]" target/logs/` returns nothing relevant)
- [ ] Spring Security baseline reasonable (no obvious mistakes; whitelisted paths intentional)
- [ ] OpenAPI spec well-formed and accurately models the 5 endpoints
- [ ] k6 perf budget met for all 5 endpoints
- [ ] Timing-parity assertion verified

Squash-merge with commit message:

```
feat(auth): add User entity + register/login/logout/me/password endpoints

Pilot 2 of the implementation playbook. Spring Security 6 baseline,
BCrypt cost 12, hash-only session storage, 256-bit tokens, dummy
BCrypt verify on unknown-user paths for timing parity, distinct 423
(locked) vs 429 (throttled), 30-day absolute session TTL with no
per-request lastSeenAt write, password-change bulk-revoke other
sessions + re-issue current.

See lld/auth.md for design; tickets/auth/01-user-entity-and-registration.md
for spec.
```

## Notes for the implementor

This ticket is security-critical. Specific failure modes to guard against:

- **Timing oracle on unknown-user**: dummy verify must run; tested via timing parity assertion.
- **Token storage**: only the hash. The raw must never reach the database, never reach a log, never reach the response body (only the cookie).
- **Lockout DoS**: an attacker spamming wrong passwords could lock a real user out, denying them service. Per-IP throttle limits this; logged for ops review.
- **Password rotation race**: if a user changes password while another session is mid-request, the in-flight request should still succeed (it has a valid session); after that request commits, the session is revoked by the password-change tx. Don't add cross-request locking — let `@Version` and the event ordering handle it.
- **Cookie domain leakage**: explicitly do NOT set the `Domain` attribute. Cookies are bound to the exact host they were issued from.
