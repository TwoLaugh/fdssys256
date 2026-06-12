# Page spec — Settings (`/settings`)

The contract-complete specification: every endpoint this page consumes, and the UI
that each request field and response field demands. Companion docs:
[../ia.md](../ia.md), [../design-language.md](../design-language.md). Template:
[nutrition.md](nutrition.md) (the pilot).

Settings is the **household & account** surface: members + roles + invites, the
slot-configuration document, the grocery provider connection (delegated here by
[groceries.md](groceries.md) §7), and the account section (password, logout).
Everything preference-flavoured (taste, hard constraints, lifestyle) lives on
/preferences; notification preferences live on /notifications.

---

## 1. Intent (HLD)

- **Handshake invites, not direct attach — GAP-84 ruling**
  (`e2e/pathways/hld-gaps.md`): "primary generates an invite code/link, invitee
  accepts — the safer model for cross-account joining." The direct
  `POST /households/current/members` exists in the contract but its own summary
  says "Prefer the invite flow for user-facing onboarding" — this page does not
  render it (§7).
- **One household per user (v1)** (`lld/household.md`): create 409s if the caller
  is already in one; invite accept 409s if the accepter is already in one. The
  page never has to model multi-household.
- **Primary/member role split**: settings PUT, invite create/revoke, member
  PATCH/role-change are PRIMARY-only (403 otherwise); members may self-remove.
  Last-primary invariants are server-enforced (409).
- **Slot configuration is what the planner eats**
  (`design/meal-planner.md` §Slot configuration): per-kind defaults — "time
  budget per slot, in minutes, defaulted by slot kind (breakfast 15, lunch 20,
  dinner 45, snack 5) and overrideable per slot"; "shared slots use
  household-union constraints; per-person slots use that person's individual
  constraints"; custom slots ("post-workout shake") are user-defined and backed
  by a built-in kind.
- **Session-cookie auth — GAP-22 ruling**; **logout ships in v1 — GAP-80
  ruling**; password change "rotates the hash, bulk-revokes the user's OTHER
  sessions, and re-issues the calling session" (`lld/auth.md` Flow 5) — the UI
  must explain that other devices get signed out, and must store nothing (the
  fresh cookie arrives via `Set-Cookie`).

## 2. Endpoint inventory

Three modules, 16 on-page operations (8 reads, 8 writes); two more are
deliberately off-page (§7) and one is its own deep-link surface (§3d).

| # | Endpoint | Card | When called |
|---|----------|------|-------------|
| 1 | `GET /api/v1/households/current` | Whole page scaffold | On load — 404 ⇒ no household: page collapses to a create/join empty state |
| 2 | `POST /api/v1/households` | Empty state | "Create household" (also onboarding step 1 — [onboarding.md](onboarding.md)) |
| 3 | `GET /api/v1/households/{householdId}/settings` | Slot config editor | On load (id from #1) |
| 4 | `PUT /api/v1/households/{householdId}/settings` | Slot config editor | Save (primary-only) |
| 5 | `GET /api/v1/households/{householdId}/settings/audit-log?page&size` | Audit drawer | On drawer open (lazy) |
| 6 | `GET /api/v1/households/{householdId}/slot-configuration` | Slot config — resolved preview | On load + after #4 (read-back of what the planner will see) |
| 7 | `GET /api/v1/households/current/invites` | Invites panel | On load + after create/revoke |
| 8 | `POST /api/v1/households/current/invites` | Invites panel | "Invite member" submit (primary-only) |
| 9 | `DELETE /api/v1/households/current/invites/{inviteId}` | Invites panel | Revoke (primary-only) |
| 10 | `PATCH /api/v1/households/current/members/{memberId}` | Members table | Edit displayName / priority (primary-only) |
| 11 | `DELETE /api/v1/households/current/members/{memberId}` | Members table | Remove member (primary) / "Leave household" (self) |
| 12 | `POST /api/v1/households/current/members/{memberId}/role` | Members table | Promote/demote (primary-only) — note: **POST**, not PUT |
| 13 | `PUT /api/v1/auth/password` | Account card | Change-password submit |
| 14 | `POST /api/v1/auth/logout` | Account card | Sign out |
| 15 | `GET /api/v1/grocery/orders/providers/{providerKey}` | Provider card | On load (`providerKey` = "tesco" in v1) — 404 ⇒ "not connected" |
| 16 | `PUT /api/v1/grocery/orders/providers/{providerKey}` | Provider card | Connect / pause / refresh settings save |
| d | `POST /api/v1/invites/accept` | **Own deep-link surface** — §3d | From an invite link/code, not from this page's chrome |

Off-page: `POST /households/current/members` (direct add — §7),
`POST /households/current/merge` (read-only planner seam — §7),
`GET /households/current/slot-configuration/planner-view` (planner-facing — §7).

## 3. Anatomy & field mapping

### 3a. Household card + members table — `HouseholdDto` (#1)

| Display element | Source field |
|---|---|
| Household name header | `name` (≤128) — **read-only: no rename endpoint exists** (§8 Q1) |
| Created caption | `createdAt`; `createdByUserId` not displayed |
| Member rows | `members[]` (`HouseholdMemberDto`) |

Per member row:

| Display element | Source field |
|---|---|
| Name | `displayName` (nullable ≤64); null → fallback `userId` short-form — **there is no username join** (§8 Q2) |
| "You" tag | `userId == session userId` (from `/auth/me`) |
| Role chip | `role` — `primary` terracotta chip · `member` plain |
| Priority | `priority` (0–1000) — numeric stepper; HLD: weights the soft-preference merge for shared slots |
| Joined | `joinedAt` |
| Edit (pencil) | opens inline edit → #10 `UpdateMemberRequest { priority?, displayName?, expectedVersion }` — PATCH semantics, null = no change; `expectedVersion` from the row's `version` |
| Role action | #12 `ChangeRoleRequest { newRole, expectedVersion }` |
| Remove / Leave | #11 — primary sees "Remove" on every row; a non-primary sees only "Leave household" on their own row |

**Role/action state machine** (who can press what):

| Caller role | Edit member | Change role | Remove other | Remove self | Invite/revoke | Settings PUT |
|---|---|---|---|---|---|---|
| `primary` | ✓ | ✓ (but demoting the **last** primary → 409) | ✓ (removing last primary while others remain → 409) | ✓ | ✓ | ✓ |
| `member` | — (403) | — (403) | — (403) | ✓ ("Leave") | — (403) | — (403) |

Render-gate on the caller's own `role` from #1; the 403s remain the backstop.
After self-removal: client clears household-scoped caches and routes to the
empty state (create/join).

### 3b. Invites panel — #7/#8/#9

Create form (`CreateInviteRequest`):

| Control | Field | Constraints |
|---|---|---|
| Role select | `intendedRole`* | `primary` \| `member` (default member) |
| Expiry | `expiresAt`* | date-time, future; **server caps at now+30d and silently truncates** — show "max 30 days" helper, default 7d |
| Restrict to user (advanced, collapsed) | `issuedForUserId` | optional uuid — when set, only that account can accept (403 otherwise). No username lookup exists, so this is paste-a-uuid only (§8 Q2) |

**The invite code is returned exactly once** — on the 201 response
(`HouseholdInviteDto.inviteCode` non-null only there; list responses redact it to
null). The success state must render the code + a copy-to-clipboard / share-link
affordance (`/invite?code=...`) with "you won't see this code again".

List rows (`HouseholdInviteDto[]` — pending only):

| Display element | Source field |
|---|---|
| Status chip | `status` (derived enum) — PENDING amber · ACCEPTED olive · REVOKED muted · EXPIRED muted (list returns pending; others appear only transiently) |
| Role + expiry | `intendedRole`, `expiresAt` countdown |
| Issued by/for | `issuedByUserId`, `issuedForUserId` (uuid short-form, §8 Q2) |
| Revoke (ghost) | #9 → 204; 409 = already accepted/revoked → re-fetch list |
| Not displayed | `inviteCode` (always null here), `acceptedAt`/`revokedAt` (tooltip), `householdId` |

### 3c. Slot configuration editor — #3/#4 (+ #6 read-back)

Editor binds the raw `HouseholdSettingsDocument` (what PUT replaces); the
resolved `SlotConfigurationDto` (#6) renders a read-only "what the planner sees"
preview underneath.

`slotDefaults` — one row per built-in kind (`breakfast`/`lunch`/`dinner`/`snack`):

| Control | Field | Constraints |
|---|---|---|
| Shared toggle | `SlotDefault.shared`* | boolean — shared = household-union constraints; off = per-person |
| Headcount | `headcount` | 1–16, nullable → falls back to `defaultHeadcount` at resolve time |
| Time budget | `timeBudgetMin` | 0–480 min, nullable → per-kind default (15/20/45/5) — show the default as placeholder |

`customSlots[]` — add/remove rows (`CustomSlotDefinition`):

| Control | Field | Constraints |
|---|---|---|
| Key | `key`* | 1–48, `^[a-z0-9-]+$` (slugified from label client-side); collision with built-ins rejected server-side |
| Label | `label`* | 1–64 ("Post-workout shake") |
| Backed by | `backedByKind`* | SlotKind select — nutrition/planner treat it as this kind |
| Shared / headcount / time budget | as per slotDefaults | same bounds |

Document-level: `defaultHeadcount` (1–16, nullable); `scheduling` is an empty
object reserved for v2 per-day overrides — do not render.

Save sends `UpdateHouseholdSettingsRequest { document, expectedVersion }` with
`expectedVersion` = `HouseholdSettingsDto.version`; 409 → re-fetch + merge
banner. **Primary-only** (403): render read-only for members with a "only the
household primary can edit" caption.

Resolved preview (#6, `SlotConfigEntryDto[]`): slotKey, kind, shared mark,
headcount, timeBudgetMin, and `eaterUserIdsIfPerPerson` (null when shared) —
plus `allEaterUserIds`. Display as a compact table; this is also where a
per-person slot shows *who* it covers.

Audit drawer (#5, `HouseholdSettingsAuditEntryDto` page): `occurredAt`,
`actorUserId`, `fieldPath` (e.g. `slotDefaults.dinner.shared`),
`previousValue` → `newValue` (strikethrough → bold, D6 advisor-card diff
treatment). Lazy-load with pager (page/size, max 100).

### 3d. Invite accept — its own deep-link surface (decision)

**Decision: accept is not rendered inside /settings.** It is a standalone
route, `/invite` (query `?code=`), reachable while logged in — the natural
landing for a shared link, and onboarding's "join instead of create" branch
([onboarding.md](onboarding.md) step 1b). Rationale: the accepter by definition
has no household yet, so /settings (which scaffolds from
`households/current`) is the wrong host; and GAP-84's handshake model wants the
code redeemable from a bare link.

`POST /api/v1/invites/accept` — `AcceptInviteRequest { inviteCode (1–32) }` →
200 `HouseholdMemberDto` (the accepter's new membership; note: **not** the
household — follow with #1 to render it).

| Code | UI |
|---|---|
| 400 | inline "enter the code you were sent" |
| 401 | redirect /login with `?next=/invite?code=…` |
| 403 | "this invite was issued for a different account" |
| 404 | "code not recognised — check for typos" |
| 409 | "already used" / "you're already in a household" (leave first — §3a) |
| 410 | "this invite expired or was revoked — ask for a new one" |

### 3e. Account card — #13/#14

Change password (`PasswordChangeRequest`):

| Control | Field | Constraints |
|---|---|---|
| Current password | `currentPassword`* | any — wrong value returns **401 generic** "invalid credentials" (never "wrong password"; it also counts toward the login throttle, `lld/auth.md` Flow 5) |
| New password (+confirm, client-side) | `newPassword`* | 12–128 |

200 → fresh `Set-Cookie` re-issues the calling session automatically (no client
token handling) and **all other sessions are revoked** — success toast: "Password
changed. Other devices have been signed out." 409 = conflict (e.g. new ==
current) → inline message.

Logout: #14 → 204 + cleared cookie (idempotent, per-device — other sessions
survive, GAP-71 rules). Client clears all caches and routes to /login.

### 3f. Grocery provider card — #15/#16 (delegated here by groceries.md §7)

| Display element / control | Field |
|---|---|
| Status line | `enabled`, `sessionExpiresAt` (past → amber "session needs attention"), `lastFailureReason` + `consecutiveFailures` |
| Connect / pause toggle | PUT `ProviderConnectionRequest { providerKey*, enabled }` |
| Scheduled refresh toggle + top-N | `scheduledRefreshEnabled`, `refreshTopNIngredients` (0–200, nullable) |
| 404 on GET | "No provider connected — connect Tesco" empty state |

Field-level detail and the order lifecycle live in
[groceries.md](groceries.md); this card is the *management* end of it.

## 4. Page degradation

| Failing call | Behaviour |
|---|---|
| #1 404 | whole page becomes the create/join empty state (#2 + link to `/invite`) — not an error |
| #3/#6 404 | slot config card → "settings not initialised" (shouldn't happen post-create; treat as error chip) |
| #7 404 | invites panel hidden (caller not in household — consistent with #1) |
| #15 404 | provider card "not connected" empty state |
| any 5xx | per-card retry chip |

## 5. Status-code → UI map

| Code | Where | UI behaviour |
|---|---|---|
| 400 | #2/#4/#8/#10/#12/#13/#16 | inline field errors (name length, slot-key pattern, headcount/time bounds, password 12–128, top-N 0–200) |
| 401 | all | global session-expired redirect (after #13 it's a *wrong current password* — inline, not redirect: distinguish by request) |
| 403 | #4/#8/#9/#10/#11/#12 | "primary only" — should be unreachable (controls render-gated by role, §3a); toast + role re-fetch if hit |
| 404 | #1 (empty state), #9–#12 (row gone → re-fetch), #15 (not connected) | per §4 |
| 409 | #2 ("already in a household"), #4/#10/#12 (stale `expectedVersion` → re-fetch + retry banner), #9 (already accepted/revoked), #11 (last-primary rule → explain: "promote someone else first"), #12 (demoting last primary — same copy), #13 (conflict → inline) | |
| 410 | accept (§3d) | expired/revoked copy |
| 423/429 | #13 (shares the login throttle) | "too many attempts — try again in {Retry-After}s" |

## 6. (role/action matrix specified in §3a)

## 7. Not on this page

| Contract item / capability | Home |
|---|---|
| `POST /households/current/members` (direct add by userId) | **nowhere in v1 UI** — contract keeps it as an admin/ops seam; GAP-84 ruled the handshake flow. Render-never. |
| `POST /households/current/merge` | not a settings control — it *takes* `MergeSoftPreferencesRequest { eaterUserIds? }` (null/empty = all members) and *returns* a read-only, non-persisted `MergedSoftPreferencesDto` (merged taste/lifestyle projection, `userIdsByPriority`, strategy `MEAN_WEIGHTED_BY_PRIORITY`). `lld/household.md`: shipped as "a planner-reachable / debug seam". If a "preview household taste merge" card is ever wanted it belongs on /preferences. |
| `GET /households/current/slot-configuration/planner-view` | planner-internal flattened view (adds `eaterUserIdsByPriority`, meal-timing window, per-slot cuisine weight); /plan reads it — [today.md](today.md) s2 |
| Hard constraints, dietary identity, lifestyle config, taste profile | /preferences |
| Notification preferences (mute/quiet hours) | /notifications |
| Nutrition targets | /nutrition |
| Weekly budget, equipment | /pantry |
| Orders, shopping list (provider *usage*) | /groceries |
| Login/register/me | /login + app shell ([login.md](login.md)) |
| Admin allowlist management | server config (`mealprep.admin.user-ids`) — no API |

## 8. Open questions (flagged, not resolved here)

1. **No household rename.** `name` is set at create and no `PUT /households/{id}`
   exists. Render read-only; backend gap candidate if product wants rename.
2. **Members are UUIDs without names.** `HouseholdMemberDto` has nullable
   `displayName` and no username; there is no user-lookup endpoint (auth exposes
   only `/me`). A fresh member who never got a `displayName` renders as a UUID
   stub, and invite "issued for" targeting requires pasting a UUID. Backend gap
   candidate: include `username` in `HouseholdMemberDto`, or auto-populate
   `displayName` from the accepter's username on invite accept.
3. **Role-change verb.** Contract is `POST .../role` (verb-over-resource style),
   not PUT — codegen callers should not assume idempotent retry semantics
   (`expectedVersion` provides the guard).
4. **Invite-code delivery is manual.** v1 has no email send; the share-link copy
   affordance *is* the delivery mechanism. Fine for self-hosted; note for v1.5.
5. **`expiresAt` silent truncation** (cap now+30d) means the UI's confirmation
   should echo the server's returned `expiresAt`, not the requested one.
6. **Settings audit `actorUserId`** has the same UUID-display problem as Q2.

## 9. Mock deltas (to make the mock match this spec)

1. Scaffold the page from `households/current` (404 → create/join empty state)
   instead of seeding a household unconditionally.
2. Retype members on `HouseholdMemberDto`; add role chips, priority stepper,
   PATCH-with-`expectedVersion` edit, role-change POST, self-remove "Leave"
   variant; enforce the §3a render-gate from the caller's role.
3. Invites: create form with role/expiry (+30d cap helper), one-time code reveal
   with copy affordance, pending list with redacted codes, revoke with
   409-already-used handling. Add the standalone `/invite` accept route (§3d
   status ladder).
4. Slot config: bind the editor to `HouseholdSettingsDocument` (slotDefaults map
   + customSlots array + defaultHeadcount), validate key slug/bounds inline,
   save with `expectedVersion`, then re-fetch the resolved preview (#6) and
   render eater lists for per-person slots. Add the audit drawer.
5. Account card: password form with generic-401 inline handling and the "other
   devices signed out" success toast; logout clears the mock session.
6. Add the provider connection card (state read + enable/pause + refresh
   settings) wired to the grocery slice shared with /groceries.
