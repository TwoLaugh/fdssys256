# Ticket: auth — 01b Defensive Layer

## Summary

Layer the throttle / lockout / breached-password / timing-parity / audit-row defences on top of the happy path delivered in 01a. Per the security-flavoured items in [`lld/auth.md`](../../lld/auth.md). Does not change the public API of any 01a endpoint; only adds new failure-mode response codes (`423`, `429`).

## Behavioural spec

### Audit rows

1. Every login attempt — successful or failed, known-user or unknown-user — inserts a `LoginAttempt` row with `usernameNormalised, userId (null if unknown), sourceIp, succeeded, failureReason, attemptedAt`. Audit row never contains the password attempt itself.
2. Registration does not create a `LoginAttempt` row (registration is not a login).

### Throttling

3. **Per-username throttle**: 10 failed attempts in a 15-minute rolling window → 429 with `Retry-After`. Counts only `BAD_PASSWORD` and `UNKNOWN_USER` outcomes (not `ACCOUNT_LOCKED`, not `THROTTLED`).
4. **Per-IP throttle**: 30 failed attempts in a 15-minute rolling window from the same source IP → 429.
5. Per-username and per-IP throttles run independently — a single IP cannot bypass per-IP throttle by switching usernames.
6. Throttle hit emits `failureReason = THROTTLED` to the audit log.
7. Throttled responses include `Retry-After` set to the seconds until the oldest counted attempt exits the window.

### Lockout

8. 5 consecutive `BAD_PASSWORD` failures for one user → set `lockedUntil = now + 15 min` on the `auth_users` row. Subsequent login attempts during lockout return 423 Locked with `Retry-After`. `failureReason = ACCOUNT_LOCKED` on those attempts.
9. A successful login during the consecutive-failure counter window resets `failedLoginCount` to 0 (counter reset is implicit; the column is updated on success).
10. Lockout counts `BAD_PASSWORD` only — not `UNKNOWN_USER`. (Otherwise an attacker who guesses non-existent usernames could lock out a real user by cycling.)

### Timing parity

11. On `UNKNOWN_USER`, `AuthServiceImpl.login` runs a dummy BCrypt verify against a pre-computed BCrypt hash of a random string. The cost is the dominant timing factor; this matches naturally.
12. Median latency of `bad-password`, `unknown-user`, and `success` responses are within ±25ms of each other in IT (asserted by repeated calls and median comparison; see `LoginFlowIT`).

### Breached-password block list

13. `src/main/resources/auth/breached-passwords.txt` (one password per line, normalised lowercase) loaded at startup into a `Set<String>` held by `PasswordStrengthValidator`. Any registration / password-change attempt with a password whose lowercase is in the set fails validation with a `field=password` ProblemDetail entry.
14. The block list is **not** consulted for login attempts (only for register and password change — defending against weak choices, not against attackers using known-breached lists).

### Logging

15. Throttle, lockout, dummy-verify all log via SLF4J at INFO with structured fields. No PII beyond username. No password attempt ever logged.

## OpenAPI updates

Add the `423` response on `POST /api/v1/auth/login`:

```yaml
'423':
  description: User locked due to consecutive failed attempts.
  headers: { Retry-After: { schema: { type: integer } } }
  content:
    application/problem+json:
      schema: { $ref: '#/components/schemas/ProblemDetail' }
'429': { $ref: '#/components/responses/TooManyRequests' }
```

## Files this ticket touches

```
src/main/java/com/example/mealprep/auth/domain/service/internal/LoginThrottleService.java          new
src/main/java/com/example/mealprep/auth/domain/service/internal/AuthServiceImpl.java               modified (login: throttle check + dummy verify + audit row + lockout state machine)
src/main/java/com/example/mealprep/auth/domain/service/internal/PasswordStrengthValidator.java     modified (load + check breach list)
src/main/java/com/example/mealprep/auth/exception/AccountLockedException.java                      new
src/main/java/com/example/mealprep/auth/exception/LoginThrottledException.java                     new
src/main/java/com/example/mealprep/config/GlobalExceptionHandler.java                              modified (handlers for 423/429 + Retry-After header)
src/main/resources/auth/breached-passwords.txt                                                     new (top-1000 from HIBP)
src/main/resources/openapi/openapi.yaml                                                            modified (423/429 on /login)
src/test/java/com/example/mealprep/auth/LoginThrottleServiceTest.java                              new (unit)
src/test/java/com/example/mealprep/auth/ThrottlingAndLockoutIT.java                                new (full IT)
src/test/java/com/example/mealprep/auth/LoginFlowIT.java                                           modified (add timing-parity assertion + audit row checks)
src/test/java/com/example/mealprep/auth/PasswordStrengthValidatorTest.java                         modified (add breach-list cases)
```

## Dependencies

- **Hard dependency**: `auth-01a` (this ticket modifies `AuthServiceImpl.login` and `PasswordStrengthValidator` from 01a). Must merge after 01a.

## Acceptance / DoD

- [ ] All edge-case items in [the original ticket](archive/01-monolithic-original-split.md) under "Throttling and lockout" / "Timing parity" / "Block list" ticked
- [ ] Mutation score ≥70% on `LoginThrottleService` and the modified branches of `AuthServiceImpl.login`
- [ ] Timing-parity median variance ≤ 25ms (IT with 50+ samples)
- [ ] No raw passwords in any log
- [ ] `mvn verify` passes locally + CI

Squash-merge with: `feat(auth): 01b — throttle/lockout/breached-list/timing-parity defences`
