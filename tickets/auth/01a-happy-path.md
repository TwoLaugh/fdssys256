# Ticket: auth — 01a Happy Path

## Summary

Implement the auth module's "happy path": register, login, /me, /logout, session cookie, `SessionAuthenticationFilter`, and `AuthSecurityConfig` (replaces the permissive baseline with deny-by-default + a small whitelist). Per [`lld/auth.md`](../../lld/auth.md). Defensive layer (throttle, lockout, breached-password list, timing parity) is **deferred to 01b**; password change is **deferred to 01c**.

This is the first of three tickets that split the original `auth-01` (now archived under `archive/01-monolithic-original-split.md`). 01a is the foundation; b and c layer on top without changing 01a's public API.

The migrations, entities, and repos are already in place — see commit `2b80aba` ("batch A — migrations + entities + repos for auth-01").

## Behavioural spec (write this BEFORE implementation)

### Registration

1. `POST /api/v1/auth/register` with valid `RegisterRequest { username, password }` returns 201 + `UserDto` body + `Set-Cookie: AUTH_SESSION=<token>; HttpOnly; SameSite=Lax; Path=/; Max-Age=2592000` (30-day absolute TTL).
2. Cookie `Secure=true` in prod profile, `false` in dev (verified per profile).
3. Registration auto-logs the user in.
4. Username uniqueness enforced via the `idx_auth_users_username_normalised` unique index — second register with the same normalised username returns 409 ProblemDetail (no Set-Cookie).
5. Username validation (`@ValidUsername`): length 3–32, ASCII alphanumerics + `_-` only.
6. Password validation in 01a is **basic only**: length 12–128, no leading/trailing whitespace, not equal to username (case-insensitive). The breached-password block list is added in 01b.
7. Password hashed with BCrypt cost 12 before storage. Raw password never logged.
8. `UserRegisteredEvent` published `AFTER_COMMIT` carrying `userId, username, registeredAt, traceId`.
9. No `LoginAttempt` row for register (registration is not a login).

### Login

10. `POST /api/v1/auth/login` with valid credentials returns 200 + `LoginResponse { userId, username }` + `Set-Cookie: AUTH_SESSION=...`.
11. Login response **never** includes the raw token in the JSON body.
12. Bad password: 401 with generic ProblemDetail (`"Invalid credentials"`).
13. Unknown username: same 401, same generic message. (Dummy BCrypt verify for timing parity is **deferred to 01b**.)
14. `UserLoggedInEvent` published `AFTER_COMMIT` with `userId, sessionId, ipAddress, userAgent, loggedInAt, traceId`. Suppressed for failed logins.
15. **No `LoginAttempt` audit row in 01a** — that's added in 01b alongside the throttle service. (LoginAttempt entity exists from Batch A but stays unused until 01b.)

### Session lifecycle

16. Token: 32 bytes from `SecureRandom`, base64url-encoded. Raw token is the cookie value; only its SHA-256 hex hash is persisted.
17. `expiresAt = issuedAt + 30 days` absolute. `lastSeenAt` set at creation only and **not** updated per request (locked decision).
18. `SessionAuthenticationFilter` reads the cookie, looks up by `tokenHash`, attaches `userId` to `SecurityContext`. Missing/invalid/expired/revoked cookie → context stays anonymous → controller returns 401 if endpoint requires auth.
19. `GET /api/v1/auth/me` with valid cookie returns 200 + `UserDto`. Without cookie or with expired/revoked session returns 401.
20. `POST /api/v1/auth/logout` returns 204 + `Set-Cookie: AUTH_SESSION=; Max-Age=0`. Sets `revokedAt` on the session row. Idempotent — second call also returns 204.
21. Soft-deleted user's still-valid session is rejected (the lookup honours `deletedAt`).

### Spring Security baseline

22. `AuthSecurityConfig` replaces the permissive `SecurityConfig` from project-setup-00. Whitelisted paths (anonymous-allowed): `/api/v1/auth/register`, `/api/v1/auth/login`, `/v3/api-docs/**`, `/swagger-ui/**`, `/error`. **Everything else** requires a valid session — including the admin endpoints from core-01, which means the `@PreAuthorize("hasRole('ADMIN')")` TODOs from `AdminDecisionLogController` are **partially** activated: anonymous access is rejected, but role check is not enforced (still flat user model — every authenticated user counts as ROLE_USER, not ROLE_ADMIN).

   The promotion to true admin gating is itself a follow-up; the comment in `AdminDecisionLogController` should be updated from `auth-01-followup` to `auth-roles-followup`.
23. CSRF disabled (cookie + `SameSite=Lax` is the mitigation — JSON-only POST is enforced by Spring's content-type matching).

## OpenAPI spec excerpt

4 paths added; 4 schemas added; `cookieAuth` security scheme added.

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

paths:
  /api/v1/auth/register: { post: ... }
  /api/v1/auth/login:    { post: ... }
  /api/v1/auth/logout:   { post: ..., security: [cookieAuth] }
  /api/v1/auth/me:       { get: ..., security: [cookieAuth] }
```

The full `paths:` shape mirrors the version in the archived monolithic ticket — same shape, minus `/password` (deferred to 01c) and minus `423` / `429` responses (deferred to 01b).

## Edge-case checklist

- [ ] BCrypt cost factor exactly 12
- [ ] Token entropy ≥256 bits (32 bytes verified)
- [ ] Token hash != raw token (raw never persisted)
- [ ] Cookie `HttpOnly` always on, `SameSite=Lax` always, `Domain` not set
- [ ] Cookie `Secure` true in prod profile, false in dev
- [ ] Session past `expiresAt` rejected on next request (returns 401)
- [ ] Soft-deleted user's still-valid session rejected
- [ ] Concurrent register with same username — DB unique violation surfaces as 409, not 500
- [ ] Concurrent login (same user) — both succeed (independent session rows)
- [ ] Generic 401 message used for both unknown-user and wrong-password
- [ ] Logout idempotent
- [ ] Spring Security baseline rejects anonymous access to non-whitelist endpoints (verified by hitting `/api/v1/admin/decision-log/*` from core-01 without a cookie → 401)
- [ ] Whitelisted endpoints (`/auth/register`, `/auth/login`, OpenAPI/Swagger) reachable without cookie
- [ ] OpenAPI request/response schemas match (contract test in every controller IT)
- [ ] No raw passwords in any log

## Files this ticket touches

```
src/main/java/com/example/mealprep/auth/AuthModule.java                                            new
src/main/java/com/example/mealprep/auth/api/controller/AuthController.java                         new
src/main/java/com/example/mealprep/auth/api/dto/RegisterRequest.java                               new
src/main/java/com/example/mealprep/auth/api/dto/LoginRequest.java                                  new
src/main/java/com/example/mealprep/auth/api/dto/LoginResponse.java                                 new
src/main/java/com/example/mealprep/auth/api/dto/UserDto.java                                       new
src/main/java/com/example/mealprep/auth/api/mapper/UserMapper.java                                 new (MapStruct)
src/main/java/com/example/mealprep/auth/domain/service/AuthQueryService.java                       new (interface)
src/main/java/com/example/mealprep/auth/domain/service/AuthUpdateService.java                      new (interface)
src/main/java/com/example/mealprep/auth/domain/service/CurrentUserResolver.java                    new (interface)
src/main/java/com/example/mealprep/auth/domain/service/internal/AuthServiceImpl.java               new
src/main/java/com/example/mealprep/auth/domain/service/internal/CurrentUserResolverImpl.java       new
src/main/java/com/example/mealprep/auth/domain/service/internal/PasswordHasher.java                new
src/main/java/com/example/mealprep/auth/domain/service/internal/PasswordStrengthValidator.java     new (basic — no breach list)
src/main/java/com/example/mealprep/auth/domain/service/internal/SessionTokenGenerator.java         new
src/main/java/com/example/mealprep/auth/domain/service/internal/SessionAuthenticationFilter.java   new
src/main/java/com/example/mealprep/auth/event/UserRegisteredEvent.java                             new
src/main/java/com/example/mealprep/auth/event/UserLoggedInEvent.java                               new
src/main/java/com/example/mealprep/auth/exception/UsernameAlreadyExistsException.java              new
src/main/java/com/example/mealprep/auth/exception/InvalidCredentialsException.java                 new
src/main/java/com/example/mealprep/auth/validation/ValidPassword.java                              new
src/main/java/com/example/mealprep/auth/validation/ValidPasswordValidator.java                     new
src/main/java/com/example/mealprep/auth/validation/ValidUsername.java                              new
src/main/java/com/example/mealprep/auth/validation/ValidUsernameValidator.java                     new
src/main/java/com/example/mealprep/auth/config/AuthSecurityConfig.java                             new (replaces SecurityConfig)
src/main/java/com/example/mealprep/auth/config/AuthProperties.java                                 new (@ConfigurationProperties)
src/main/java/com/example/mealprep/config/SecurityConfig.java                                      delete (superseded)
src/main/java/com/example/mealprep/config/GlobalExceptionHandler.java                              modified (add handlers for 2 new exceptions)
src/main/java/com/example/mealprep/core/audit/api/controller/AdminDecisionLogController.java       modified (TODO marker text update)
src/main/resources/openapi/openapi.yaml                                                            modified (4 paths + 4 schemas + cookieAuth)
src/main/resources/application-dev.properties                                                      modified (cookie-secure=false)
src/main/resources/application-prod.properties                                                     modified (cookie-secure=true)
src/test/java/com/example/mealprep/auth/PasswordHasherTest.java                                    new (unit)
src/test/java/com/example/mealprep/auth/SessionTokenGeneratorTest.java                             new (unit)
src/test/java/com/example/mealprep/auth/PasswordStrengthValidatorTest.java                         new (unit; basic rules only)
src/test/java/com/example/mealprep/auth/AuthServiceImplTest.java                                   new (unit; happy paths)
src/test/java/com/example/mealprep/auth/RegisterFlowIT.java                                        new
src/test/java/com/example/mealprep/auth/LoginFlowIT.java                                           new (basic; timing parity in 01b)
src/test/java/com/example/mealprep/auth/SessionLifecycleIT.java                                    new
src/test/java/com/example/mealprep/auth/SecurityChainTest.java                                     new
src/test/java/com/example/mealprep/auth/testdata/AuthTestData.java                                 new (Test Data Builder)
```

## Dependencies

- `core-01-decision-log` (merged): uses `core.events.MealPrepEvent` sealed base for auth events.
- Batch A of original auth-01 (already on this branch as commit `2b80aba`): migrations + entities + repos.

## Acceptance / DoD

- [ ] `mvn verify` passes locally + on CI
- [ ] Mutation score ≥70% on `PasswordHasher`, `SessionTokenGenerator`, `SessionAuthenticationFilter`
- [ ] All edge-case items above ticked
- [ ] OpenAPI spec lints
- [ ] Spotless clean
- [ ] No raw passwords in any log

Squash-merge with: `feat(auth): 01a — register/login/logout/me + session filter + security baseline`
