# Page spec — Login (`/login`)

Contract-complete but deliberately short: one page, two modes (sign in /
register), four endpoints total — two of which belong architecturally to the app
shell. Companion docs: [../ia.md](../ia.md),
[../design-language.md](../design-language.md). Template:
[nutrition.md](nutrition.md).

---

## 1. Intent (HLD)

- **Session-cookie auth — GAP-22 ruling**: "server-side session cookie (not
  JWT)." The cookie (`AUTH_SESSION`, HttpOnly/Secure/SameSite) is set by the
  server; the client never sees, stores or attaches a token — TanStack Query
  needs zero auth plumbing beyond `credentials: 'include'`.
- **No enumeration oracle** (`lld/auth.md`): login 401 is generic — "never
  distinguishes unknown user from wrong password." The page must use one error
  copy for both.
- **Register auto-logs-in** (locked decision, `lld/auth.md`): 201 carries the
  session cookie — no separate "now sign in" step.
- **Throttle + lockout are first-class**: per-username/per-IP throttling (429)
  and consecutive-failure lockout (423), both with `Retry-After`.
- No OAuth, no email, no account recovery in v1 (GAP-82: explicitly none) — no
  "forgot password" link to render.

## 2. Endpoint inventory

| # | Endpoint | When called |
|---|----------|-------------|
| 1 | `POST /api/v1/auth/login` | Sign-in submit |
| 2 | `POST /api/v1/auth/register` | Register submit |
| s1 | `GET /api/v1/auth/me` | **App-shell concern, not this page** — session probe on boot (§5) |
| s2 | `POST /api/v1/auth/logout` | **Not on this page** — lives in /settings account card + shell menu ([settings.md](settings.md) §3e) |

## 3. Anatomy & field mapping

Single card, mode toggle ("Sign in" / "Create account"). Same two fields; only
the validation and submit differ.

### 3a. Sign in — `LoginRequest` (#1)

| Control | Field | Notes |
|---|---|---|
| Username | `username`* | no client-side pattern (don't leak the register policy here) |
| Password | `password`* | — |

200 → `LoginResponse { userId, username }` + `Set-Cookie`. Store the body in the
session slice (greeting copy uses `username` — there is no display name in v1),
then route per §5.

### 3b. Register — `RegisterRequest` (#2)

| Control | Field | Constraints (validate inline, pre-submit) |
|---|---|---|
| Username | `username`* | 3–32, `^[a-zA-Z0-9_-]+$` |
| Password (+ confirm, client-only) | `password`* | 12–128 — show a "12 characters minimum" helper, not a complexity meter (length is the only server rule) |

201 → `UserDto { userId, username, createdAt }` + cookie (auto-login) → route to
`/onboarding` (a fresh account by definition has no household —
[onboarding.md](onboarding.md)).

## 4. Status-code → UI map

| Code | Where | UI behaviour |
|---|---|---|
| 400 | both | inline field errors (pattern/length) — should be pre-caught client-side |
| 401 | #1 | **one generic message**: "Username or password is incorrect." Never "no such user" |
| 409 | #2 | "That username is taken" on the username field |
| 423 | #1 | "Account temporarily locked after repeated failures — try again in {Retry-After}s" + disabled submit with countdown |
| 429 | #1 | "Too many attempts — try again in {Retry-After}s" + countdown (contract declares throttling on login only, not register) |
| 5xx | both | card-level retry message |

## 5. Session probe & routing — app-shell concern (noted here, owned there)

`GET /auth/me` is the boot probe: it belongs in the **router guard / shell**, not
this page — every route except `/login` runs it (cached) before rendering.

- me 200 → proceed; a 200 user with no household (`households/current` 404) is
  the shell's signal to redirect `/onboarding`.
- me 401 → redirect `/login?next={path}`; after successful login, honour `next`.
- Any API 401 mid-session → same redirect (global fetch interceptor).
- Visiting `/login` while already authenticated → redirect `/`.

The page itself never calls /me.

## 6. Not on this page

| Capability | Home |
|---|---|
| Logout | /settings account card + shell user menu |
| Password change | /settings (`PUT /auth/password`) |
| Session probe `/auth/me` | app shell (§5) |
| Forgot password / recovery | **does not exist in v1** (GAP-82 "explicitly none") — render nothing, not a dead link |
| Profile (display name, avatar) | v1.5 backlog — greeting uses `username` |

## 7. Open questions

1. **No session-expiry signal in the body.** `LoginResponse` omits
   `sessionExpiresAt`; the cookie's `Max-Age` handles renewal invisibly, but the
   client cannot warn "session expiring". Fine for v1; backend gap candidate
   only if a UX wants it.
2. **`Retry-After` reliance.** The 423/429 countdowns require the header to be
   CORS-exposed (`Access-Control-Expose-Headers`); verify in the CORS config or
   the countdown degrades to static copy.

## 8. Mock deltas

1. Replace the mock's single hardcoded sign-in with the two-mode card; wire
   register → auto-login → `/onboarding`.
2. Implement the generic-401 copy, 409 username-taken, and 423/429 countdown
   states in the mock auth slice.
3. Move the session probe out of the page component into the router guard
   (`/auth/me` + `households/current` chain per §5) so deep links redirect
   correctly with `next`.
