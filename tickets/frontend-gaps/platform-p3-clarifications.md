# Ticket: notification/household/auth — P3 platform clarifications (combined)

Low-priority items from [`design/frontend/pages/notifications.md` §8](../../design/frontend/pages/notifications.md),
[`settings.md` §8](../../design/frontend/pages/settings.md),
[`login.md` §7](../../design/frontend/pages/login.md), and
[`admin.md` §5/§7](../../design/frontend/pages/admin.md). Resolve item-by-item; tick + annotate.

## Notification

1. ✅ **`actionTargetUri` namespace mismatch** (notifications §8 Q2). Server emits `/app/provisions/inventory`,
   `/app/plans/{id}`, `/app/feedback/{id}`; the IA routes are `/pantry`, `/plan`, `/activity`.
   **Proposed:** update the server resolver copy to IA routes (one map, server-side — keeps every
   client dumb); alternative is a client-side `/app/*` → route map. Decide once, before live wiring.
   **DONE (2026-06-13, small code):** decided server-side and built — `NotificationKindResolver`
   now emits IA routes (`/pantry` ×4 kinds, `/nutrition` ×2, `/plan` ×3, `/activity` ×1); entity
   context rides the typed payload, never the URI. OpenAPI `actionTargetUri` description updated;
   resolver tests assert every kind's route. Pre-change rows keep `/app/*` (audit history —
   nothing live-wired consumed them).
2. ✅ **Single-valued `status`/`kind` filters** (notifications §8 Q3). The "all except dismissed"
   inbox default needs client-side filtering or a multi-status param. **Proposed:** accept
   client-side filtering for v1 (page sizes are small); param only if usage hurts.
   **DONE (2026-06-13, decision):** accepted as proposed; recorded in `notifications.md` §8 Q3.
3. ✅ **Preferences PUT required-fields mismatch** (notifications §8 Q5). OpenAPI requires
   `[enabledKinds, timezone]`; Java binds 3 more as primitives and `expectedVersion` is
   effectively mandatory. **Proposed:** align the contract (doc-level: mark all server-required
   fields required) — pure schema fix, fold into the next notification touch.
   **DONE (2026-06-13, schema fix):** `UpdateNotificationPreferenceRequest` required list aligned
   to the Java binding (`enabledKinds, quietHoursEnabled, timezone, debounceWindowMinutes,
   expectedVersion`) + full-replace description; folded into this PR's notification touch.
4. ✅ **Bell poll cadence unspecified** (notifications §8 Q6). **Proposed:** product nod for 60 s +
   on-focus refetch until SSE (task #172); record in the page spec.
   **DONE (2026-06-13, decision):** nod recorded in `notifications.md` §8 Q6 — 60 s + on-focus
   refetch until SSE.

## Household / settings

5. ✅ **No household rename** (settings §8 Q1). `name` is set at create; no `PUT /households/{id}`.
   **Proposed:** render read-only in v1; ship rename only if product asks.
   **DEFERRED (v1.5): household rename endpoint** — read-only render accepted for v1
   (2026-06-13); ship only on product ask.
6. ✅ **Role-change verb is `POST .../role`** (settings §8 Q3) — verb-over-resource; callers must not
   assume idempotent retry (the `expectedVersion` guards). **Proposed:** doc note in the OpenAPI
   description; no rename of the operation.
   **DONE (2026-06-13, doc-only):** note added to the `changeHouseholdMemberRole` operation
   description in `paths/household.yaml` (no blind retries; `expectedVersion` guards — verified
   required in `ChangeRoleRequest`).
7. ✅ **`expiresAt` silent truncation** on invite create (settings §8 Q5, cap now+30d). **Proposed:**
   doc the cap in the contract; UI echoes the returned `expiresAt` (already specced).
   **DONE (2026-06-13, doc-only):** cap documented on the `createHouseholdInvite` operation in
   `paths/household.yaml` (verified against `HouseholdServiceImpl.MAX_INVITE_LIFETIME` = 30d;
   response `expiresAt` is authoritative).

## Auth / admin

8. ✅ **`isAdmin` on `GET /auth/me`** (admin §5). The client probes `admin/status` once per session
   to decide nav visibility — works, but a boolean on `/me` removes the probe + hidden-nav flash.
   **Proposed:** small additive field; schedule with the next auth touch.
   **DONE (2026-06-13, small code):** built as this queue's auth touch — `/me` now returns
   `CurrentUserDto` (UserDto projection + required `isAdmin` read from the project-wide
   `AdminAccessProperties` allowlist; display-only, admin endpoints still 403 server-side).
   `UserDto` itself untouched (cross-module consumers unaffected). OpenAPI schema added; covered
   by `AuthControllerMeTest` (allowlisted/non-allowlisted/anonymous) + `SessionLifecycleIT`.
9. ✅ **No session-expiry signal** (login §7 Q1). `LoginResponse` omits `sessionExpiresAt`; cookie
   Max-Age renews invisibly. **Proposed:** accept for v1 (no UX needs it yet).
   **DONE (2026-06-13, decision):** accepted as proposed — no contract change until a UX needs it.
10. ✅ **`Retry-After` CORS exposure** (login §7 Q2). The 423/429 countdowns need the header in
    `Access-Control-Expose-Headers`. **Proposed:** verify the CORS config (core-02a) exposes it;
    one-line fix if not — do the verification now, it is free.
    **DONE (2026-06-13, one-line fix):** verified NOT exposed (`DevCorsConfiguration.
    EXPOSED_HEADERS` carried only X-Trace-Id/Location/Content-Disposition) — `Retry-After` added;
    `DevCorsConfigurationTest` updated.

## Acceptance / DoD

- [x] Each item: decision recorded inline; doc/schema items landed; item 10 verified (fix needed
      and applied)
- [x] Items promoted to build (1, 3, 8, 10) spun out or folded into adjacent work — all four
      folded into this PR

Squash-merge with: `docs(platform): P3 clarifications from notifications/settings/login/admin page specs`
