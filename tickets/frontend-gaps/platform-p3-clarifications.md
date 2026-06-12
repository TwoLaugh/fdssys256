# Ticket: notification/household/auth — P3 platform clarifications (combined)

Low-priority items from [`design/frontend/pages/notifications.md` §8](../../design/frontend/pages/notifications.md),
[`settings.md` §8](../../design/frontend/pages/settings.md),
[`login.md` §7](../../design/frontend/pages/login.md), and
[`admin.md` §5/§7](../../design/frontend/pages/admin.md). Resolve item-by-item; tick + annotate.

## Notification

1. **`actionTargetUri` namespace mismatch** (notifications §8 Q2). Server emits `/app/provisions/inventory`,
   `/app/plans/{id}`, `/app/feedback/{id}`; the IA routes are `/pantry`, `/plan`, `/activity`.
   **Proposed:** update the server resolver copy to IA routes (one map, server-side — keeps every
   client dumb); alternative is a client-side `/app/*` → route map. Decide once, before live wiring.
2. **Single-valued `status`/`kind` filters** (notifications §8 Q3). The "all except dismissed"
   inbox default needs client-side filtering or a multi-status param. **Proposed:** accept
   client-side filtering for v1 (page sizes are small); param only if usage hurts.
3. **Preferences PUT required-fields mismatch** (notifications §8 Q5). OpenAPI requires
   `[enabledKinds, timezone]`; Java binds 3 more as primitives and `expectedVersion` is
   effectively mandatory. **Proposed:** align the contract (doc-level: mark all server-required
   fields required) — pure schema fix, fold into the next notification touch.
4. **Bell poll cadence unspecified** (notifications §8 Q6). **Proposed:** product nod for 60 s +
   on-focus refetch until SSE (task #172); record in the page spec.

## Household / settings

5. **No household rename** (settings §8 Q1). `name` is set at create; no `PUT /households/{id}`.
   **Proposed:** render read-only in v1; ship rename only if product asks.
6. **Role-change verb is `POST .../role`** (settings §8 Q3) — verb-over-resource; callers must not
   assume idempotent retry (the `expectedVersion` guards). **Proposed:** doc note in the OpenAPI
   description; no rename of the operation.
7. **`expiresAt` silent truncation** on invite create (settings §8 Q5, cap now+30d). **Proposed:**
   doc the cap in the contract; UI echoes the returned `expiresAt` (already specced).

## Auth / admin

8. **`isAdmin` on `GET /auth/me`** (admin §5). The client probes `admin/status` once per session
   to decide nav visibility — works, but a boolean on `/me` removes the probe + hidden-nav flash.
   **Proposed:** small additive field; schedule with the next auth touch.
9. **No session-expiry signal** (login §7 Q1). `LoginResponse` omits `sessionExpiresAt`; cookie
   Max-Age renews invisibly. **Proposed:** accept for v1 (no UX needs it yet).
10. **`Retry-After` CORS exposure** (login §7 Q2). The 423/429 countdowns need the header in
    `Access-Control-Expose-Headers`. **Proposed:** verify the CORS config (core-02a) exposes it;
    one-line fix if not — do the verification now, it is free.

## Acceptance / DoD

- [ ] Each item: decision recorded inline; doc/schema items landed; item 10 verified
- [ ] Items promoted to build (1, 3, 8, 10) spun out or folded into adjacent work

Squash-merge with: `docs(platform): P3 clarifications from notifications/settings/login/admin page specs`
