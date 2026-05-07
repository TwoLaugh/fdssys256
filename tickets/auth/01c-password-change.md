# Ticket: auth — 01c Password Change

## Summary

Add `PUT /api/v1/auth/password` for authenticated users to rotate their password. Verifies current password via real BCrypt verify, hashes the new password, bulk-revokes all *other* sessions for the user, and re-issues the calling session with a fresh token. Per the password-change items in [`lld/auth.md`](../../lld/auth.md). Independent of 01b — can ship concurrently with 01b once 01a is merged.

## Behavioural spec

1. `PUT /api/v1/auth/password` with `PasswordChangeRequest { currentPassword, newPassword }` requires an authenticated session.
2. Without a session: 401 (handled by `SessionAuthenticationFilter` from 01a).
3. With a session but wrong `currentPassword`: 401 (NOT 400). Generic `"Invalid credentials"` ProblemDetail. **No** lockout / throttle counter increment from this path (those are login-only — see 01b).
4. With a valid session and correct `currentPassword` but `newPassword` failing validation: 400 with field-level `errors[]`.
5. Happy path: update the user's `passwordHash` to BCrypt(newPassword) (cost 12), set `passwordUpdatedAt = now`, return 200 + `UserDto` + `Set-Cookie: AUTH_SESSION=<NEW-TOKEN>` (re-issued session).
6. Bulk-revoke side effect: every *other* active session for this user (i.e. every session row with `user_id = me AND id <> currentSession.id AND revoked_at IS NULL`) gets `revokedAt = now` set. The calling session's row is itself replaced — old token's `tokenHash` row is set `revokedAt = now`, a new row with a new `tokenHash` is inserted.
7. The user is not bounced — the new cookie immediately authenticates the next request.
8. `UserPasswordChangedEvent` published `AFTER_COMMIT` with `userId, sessionsRevokedCount, traceId`.
9. Idempotence isn't relevant — same currentPassword + same newPassword on retry just rotates again with another fresh token.

## OpenAPI excerpt

```yaml
components:
  schemas:
    PasswordChangeRequest:
      type: object
      required: [currentPassword, newPassword]
      properties:
        currentPassword: { type: string }
        newPassword: { type: string, minLength: 12, maxLength: 128 }

paths:
  /api/v1/auth/password:
    put:
      tags: [Auth]
      operationId: changePassword
      summary: Rotate password; revoke other sessions; re-issue current.
      security: [{ cookieAuth: [] }]
      requestBody:
        required: true
        content:
          application/json:
            schema: { $ref: '#/components/schemas/PasswordChangeRequest' }
      responses:
        '200':
          description: Password changed; new session cookie issued; other sessions revoked.
          headers: { Set-Cookie: { schema: { type: string } } }
          content:
            application/json:
              schema: { $ref: '#/components/schemas/UserDto' }
        '400': { $ref: '#/components/responses/BadRequest' }
        '401': { $ref: '#/components/responses/Unauthorized' }
```

## Edge-case checklist

- [ ] Wrong currentPassword returns 401 (NOT 400)
- [ ] New password validation runs only after currentPassword is verified — order matters (don't surface "your new password is weak" before confirming the user actually knows the current one)
- [ ] Bulk-revoke leaves the calling session untouched in the SQL — but the impl then revokes-and-reissues the calling session as a separate step, so the old token from the calling cookie is unusable for the next request
- [ ] After rotation: old cookie value rejected on next request (verified in IT)
- [ ] After rotation: new cookie value succeeds on next request (verified in IT)
- [ ] Other browsers' sessions for the same user all show `revokedAt` set (verified in IT)
- [ ] `UserPasswordChangedEvent` carries the right `sessionsRevokedCount` (count of other sessions; the calling session is re-issue, not revoke)
- [ ] Concurrent password change races: two simultaneous `PUT /password` from different sessions of the same user — `@Version` on User catches the second; second returns 409
- [ ] OpenAPI request/response schemas match (contract test)

## Files this ticket touches

```
src/main/java/com/example/mealprep/auth/api/dto/PasswordChangeRequest.java                         new
src/main/java/com/example/mealprep/auth/api/controller/AuthController.java                         modified (add PUT /password)
src/main/java/com/example/mealprep/auth/domain/service/AuthUpdateService.java                      modified (add changePassword method to interface)
src/main/java/com/example/mealprep/auth/domain/service/internal/AuthServiceImpl.java               modified (add changePassword)
src/main/java/com/example/mealprep/auth/event/UserPasswordChangedEvent.java                        new
src/main/resources/openapi/openapi.yaml                                                            modified (1 path + 1 schema)
src/test/java/com/example/mealprep/auth/PasswordChangeIT.java                                      new
src/test/java/com/example/mealprep/auth/AuthServiceImplTest.java                                   modified (add changePassword unit cases)
```

## Dependencies

- **Hard dependency**: `auth-01a` (this ticket adds a method to `AuthServiceImpl` and a new endpoint to `AuthController` from 01a). Must merge after 01a.
- **No dependency on 01b**: 01c can ship concurrently with 01b on a separate branch; merge order between b and c is interchangeable.

## Acceptance / DoD

- [ ] All edge-case items above ticked
- [ ] OpenAPI spec lints
- [ ] `mvn verify` passes locally + CI
- [ ] Spotless clean

Squash-merge with: `feat(auth): 01c — password change with bulk-revoke + session re-issue`
