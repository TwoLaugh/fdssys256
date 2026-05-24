# Auth & Account Domain — User-Pathway Catalogue

> Code-agnostic behavioural catalogue derived purely from the HLD design docs. Double duty: (a) source for E2E test scenarios; (b) behavioural spec for the frontend. No endpoints, HTTP verbs, class names, or DB tables — pure user/behaviour language. Where the HLD is silent on something a user would obviously need, it is flagged `[HLD-GAP]` rather than invented.
>
> **Caveat for this domain specifically:** there is **no dedicated Auth HLD**. The system overview explicitly frames auth as a "thin auth layer — not a domain module, just infrastructure" (system-overview §User Accounts). Auth behaviour is therefore extracted from three places only — system-overview §User Accounts, technical-architecture §Authentication + §Frontend-Backend Contract, and the shared `user_id` ownership note — and is *mostly implied*. As a result this catalogue is unusually `[HLD-GAP]`-heavy: the action space below is the frontend contract we *need*, but a large fraction of its rules are unspecified in the HLD and recorded in the appendix for batch triage. **Auth is the precondition for nearly every other domain's pathways** — almost every `RCP-`, `PREF-`, `NUT-`, `PROV-`, `PLAN-`, `FEED-`, `GROC-`, `HH-`, `NOTIF-` pathway lists "Authenticated" as a precondition, and the backend resolves `userId` from the auth/session context server-side (never from a URL path). This file therefore defines the *gate* the rest of the suite assumes.

---

## 1. Domain Summary

Auth & Account is the **infrastructure gate**, not a constraint loop. It serves **none of the three optimisation loops** (preference / nutrition / provisions) directly — it owns identity and the session boundary that every loop runs *inside*. Its job is narrow and deliberate: establish *who the user is* (username + hashed password, "no OAuth initially"), maintain a session across requests (session cookie or JWT-in-httpOnly-cookie — the HLD leaves the choice open), and own the canonical `user_id` that every other module stores as a plain identifier and resolves through query services. It is "multi-user from v1 (family members)," so each account links to its own Preference Model, own Nutrition Model, and a household membership — making Auth the natural seam between an individual identity and the shared Household. It contains no food logic, no AI, and no optimisation: it is purely the trust boundary that the `HardConstraintFilterService` and every food-output module implicitly rely on (the filter reads constraints *by `userId`*, so a wrong/spoofed identity is a safety concern, not just a privacy one). Because the HLD treats it as "just infrastructure," most of its behavioural surface is implied — this catalogue makes that surface explicit so the frontend has a contract and the rest of the suite has a precondition it can trust.

## 2. Actors

| Actor | Role in this domain (per HLD) |
|---|---|
| **Anonymous / unauthenticated visitor** | Has no session. Can (must be able to) register and log in. Every other action is denied until authenticated. `[HLD-GAP]` — the HLD never enumerates which routes are public vs protected; only "the backend knows who the user is from the session/token" (technical-architecture §Frontend-Backend Contract). |
| **Primary user** | Owns an account created at registration; "manages provisions and the shared plan" (system-overview §Household). Account links to their own Preference + Nutrition models and a household membership. The default authenticated actor for nearly every other domain's pathways. |
| **Household member** | Has their **own account, Preference Model, and Nutrition Model** (system-overview §Household). Authenticates the same way as the primary user. Authority differs (can give feedback on own meals; provisions/plan management is the primary user's) — but the *auth mechanism* is identical. Cross-domain: see HH- pathways. |
| **Child profile** | Implied by the Hard Constraint Filter note "Age restrictions: auto-populated for child profiles" (technical-architecture §Hard Constraint Filter). Whether a child profile is a full account, a sub-profile, or auth-less is unspecified `[HLD-GAP]`. |
| **Auth subsystem (server, system actor)** | Hashes/verifies passwords, issues/validates the session credential, resolves `userId` from the auth context on every request, owns the canonical user identifier consumed by every module. |
| **Session/credential store + system clock (system actor)** | Holds session/token state; expires sessions (session timeout / token expiry). Drives any expiry-based pathways. `[HLD-GAP]` — no timeout duration, rotation, or "remember me" policy is stated. |
| **Health Platform integration (external, adjacent)** | Not an auth actor itself, but pushes directives "to the Nutrition Model via a propose/accept flow" — i.e. an external system acting *on behalf of* an authenticated user. How that integration authenticates is entirely unspecified `[HLD-GAP]` (out of this domain's core scope; noted for completeness). |

## 3. Action Space (frontend-spec backbone)

Flat, exhaustive list of every distinct user (or user-facing system) action the auth surface must support for the frontend. Each: verb-phrase + one-line description + HLD ref. **Heavy `[HLD-GAP]` density is expected here** — the HLD names the *primitives* (username, hashed password, session cookie, multi-user) but not the *flows*. Downstream pathways draw from this list; gaps are catalogued, not invented.

### Registration / Account creation
1. **Register a new account** — create an account with a username and a password (which is hashed, never stored plaintext). System overview: "Username + hashed password (simple, no OAuth initially)"; new account links to a fresh Preference Model, Nutrition Model, and household membership. §User Accounts.
2. **Register the first household member (account → new household)** — implied first-run: registering creates an account that is "multi-user from v1," so the first user must establish or join a household. §User Accounts; §Household. `[HLD-GAP]` — whether registration creates a household, joins one by invite, or is independent is unspecified.
3. **Register an additional household member** — a family member gets their own account/Preference/Nutrition models. §Household ("Each user has their own account…"). `[HLD-GAP]` — invite vs self-signup vs primary-user-creates is unspecified (cross-domain with HH-).

### Session establishment / teardown
4. **Log in (establish a session)** — supply username + password; on success the server issues a session credential (session cookie, or JWT in an httpOnly cookie) that the frontend sends automatically with every request. §Authentication.
5. **Log out (end a session)** — invalidate the current session credential so subsequent requests are unauthenticated. `[HLD-GAP]` — the HLD never describes a logout flow, only that credentials ride on cookies.
6. **Resume an existing session** — a returning request carries a valid session credential; the backend resolves `userId` from it server-side without re-login. §Authentication; §Frontend-Backend Contract ("the backend knows who the user is from the session/token").
7. **Switch the acting household member** — act as another member within the session, anticipated by the HLD ("If household member switching is needed later, use a header or query param `?actingAs=memberId`"). §Frontend-Backend Contract. `[HLD-GAP]` — explicitly deferred ("if needed later"); authorisation rules for who may act-as whom are unspecified.

### Account read / profile
8. **View own account / identity** — see the authenticated identity (username, household membership, linked models). `[HLD-GAP]` — no account-read endpoint or shape is defined anywhere in the HLD; entirely implied.
9. **View linked models for the account** — the account links to its Preference Model, Nutrition Model, household membership (system-overview §User Accounts) — surfacing these is implied. `[HLD-GAP]` — read surface unspecified.

### Account lifecycle / credential management
10. **Change own password** — set a new password (re-hashed). `[HLD-GAP]` — never mentioned in the HLD; required for any real account system.
11. **Change own username** — `[HLD-GAP]` — never mentioned; username uniqueness/mutability unspecified.
12. **Reset a forgotten password** — `[HLD-GAP]` — no email/recovery mechanism described; "no OAuth initially" implies a local credential but no recovery path is given.
13. **Deactivate / delete own account** — `[HLD-GAP]` — account deletion/closure is never described.
14. **Remove a household member's account** — implied by `HouseholdMemberRemovedEvent` (technical-architecture §Event catalogue). The *event* exists; the *account-lifecycle* consequence (does the member's account/data get deleted, orphaned, or retained?) is unspecified `[HLD-GAP]` (cross-domain with HH-).

### GDPR / data rights
15. **Export own data (GDPR portability)** — `[HLD-GAP]` — **the HLD contains no mention of data export.** Recorded as a required-for-the-frontend action whose entire behaviour is unspecified.
16. **Request account + data deletion (GDPR erasure)** — `[HLD-GAP]` — **no mention of GDPR deletion.** Note the tension with the Recipe Engine's "no hard delete" invariant and with `user_id` being referenced across every module (technical-architecture §Cross-module references) — erasure semantics are entirely unspecified and potentially contradictory.

## 4. State Models

### 4.1 Account lifecycle
```
(none)
   │  register (username + hashed password)
   ▼
ACTIVE  ── linked to own Preference Model + Nutrition Model + household membership
   │       can authenticate, hold sessions, act across all domains
   │
   ├─ change password / username        → ACTIVE (credential updated)   [HLD-GAP — flows undefined]
   ├─ deactivate / delete own account    → ??? (CLOSED / DELETED)        [HLD-GAP — no lifecycle defined]
   └─ removed as a household member       → ??? (HouseholdMemberRemovedEvent fires; account fate unspecified) [HLD-GAP]
```
`[HLD-GAP]` — **the HLD defines no account states at all.** ACTIVE is the only state actually implied (an account that can authenticate). Every transition out of ACTIVE (close, delete, suspend, lock-after-failed-logins) is unspecified. There is no stated relationship between deleting an account and the system-wide "no hard delete" / cross-module `user_id` references.

### 4.2 Session / credential lifecycle
```
NO SESSION (anonymous)
   │  log in (valid username + password)
   ▼
AUTHENTICATED (session credential issued; rides on cookie; userId resolved server-side per request)
   │
   ├─ log out                      → NO SESSION (credential invalidated)        [HLD-GAP — logout flow undefined]
   ├─ session timeout / expiry      → NO SESSION (must re-authenticate)          [HLD-GAP — no timeout policy stated]
   └─ credential invalid/tampered   → treated as NO SESSION (request unauthenticated)
```
`[HLD-GAP]` — session duration, idle vs absolute timeout, rotation/refresh, concurrent sessions per user, and "remember me" are all unspecified. The HLD offers two *mechanisms* (session-based vs JWT) without choosing one, so even the credential's nature is ambiguous.

**Illegal / disallowed transitions (→ error pathways):**
- Performing any protected (non-auth) action while in NO SESSION → unauthorized; request rejected before any domain logic runs.
- Reaching another user's data while AUTHENTICATED as a different user → unauthorized (the backend resolves `userId` from the session, so cross-user access via a manipulated identifier must be denied). `[HLD-GAP]` — the HLD asserts server-side `userId` resolution but never states the cross-user denial rule explicitly; it is implied by "the backend knows who the user is."
- Registering a username that already exists → conflict (must be rejected). `[HLD-GAP]` — username uniqueness is implied by "username + password" login but never stated.
- Logging in with a wrong password or unknown username → rejected; no session issued.

## 5. Pathways

> Categories: **Happy** (default success), **Alternate** (valid non-default), **Error** (validation/not-found/unauthorized/conflict/illegal-transition), **Edge** (empty/huge/boundary/duplicate/concurrent). Because auth is the precondition for every other domain, several pathways here are explicitly the *setup* those domains assume (a fresh registered+logged-in user with a random handle, per the README's self-contained-data rule). Cross-module touchpoints (model provisioning on registration, household membership, hard-constraint-filter trust) are noted; they are fully detailed in their own domain files + the cross-journey file.

### Registration

#### AUTH-01 — Register a new account
- **Category:** Happy
- **Actor:** Anonymous visitor
- **Preconditions:** No session; username not already taken.
- **Action:** Submit a unique username and a password to create an account.
- **Expected outcome:** Account created in ACTIVE; password stored hashed (never plaintext); a canonical `user_id` is minted for the account; the account is linked to a fresh Preference Model, Nutrition Model, and a household membership; the user can now log in.
- **Variations:** registration that immediately establishes a session (auto-login) vs registration that requires a subsequent explicit login `[HLD-GAP]`; first-ever user (bootstraps a new household) vs an additional household member (AUTH-03); minimal vs rich initial profile.
- **HLD ref:** system-overview §User Accounts; §Household; technical-architecture §Cross-module references (`user_id` owned by Auth).
- **Notes:** Cross-module: provisioning of the linked Preference/Nutrition models and household membership is implied but not specified (`[HLD-GAP]` — does registration eagerly create empty models, or are they created lazily on first use?). This is the canonical "fresh user" setup the rest of the suite's self-contained-data rule depends on. Self-scoped: assert on *this* new account's identity, never global user counts.

#### AUTH-02 — Register with missing / invalid required fields
- **Category:** Error
- **Actor:** Anonymous visitor
- **Preconditions:** No session.
- **Action:** Submit a registration missing a required field or with an invalid value.
- **Expected outcome:** Validation rejects; no account created; clear field-level error.
- **Variations:** no username; no password; empty/whitespace username; password below any complexity/length floor; username with disallowed characters. `[HLD-GAP]` — **the HLD enumerates no registration validation rules at all** (no password policy, no username format/length). Test asserts "rejected" for an obviously-empty field but the precise rule set is a GAP.
- **HLD ref:** system-overview §User Accounts (implied required structure: username + password).
- **Notes:** No external deps. Mirror of RCP-02's "required-field set is unspecified" finding, but worse — auth has *zero* stated validation.

#### AUTH-03 — Register an additional household member
- **Category:** Alternate
- **Actor:** Anonymous visitor / Primary user (depending on the unspecified invite model)
- **Preconditions:** A household already exists.
- **Action:** Create a second account that becomes a member of the existing household.
- **Expected outcome:** New account ACTIVE with its **own** Preference Model and Nutrition Model; member added to the shared household; `HouseholdMemberAddedEvent` fires (consumed by Planner + Notification).
- **Variations:** self-signup that joins by some household identifier; primary-user-creates-the-account; invite-link flow. `[HLD-GAP]` — **which of these is the actual mechanism is entirely unspecified**; the HLD only states each user has their own account/models and that an add-member event exists.
- **HLD ref:** system-overview §Household; technical-architecture §Event catalogue (`HouseholdMemberAddedEvent`).
- **Notes:** Cross-domain with HH-. The auth leg (a new authenticable identity) is in scope here; household membership semantics are detailed in household.md.

#### AUTH-04 — Register a duplicate username
- **Category:** Error
- **Actor:** Anonymous visitor
- **Preconditions:** An account with username X already exists.
- **Action:** Attempt to register again with username X.
- **Expected outcome:** Rejected with a conflict; no second account created; existing account untouched.
- **Variations:** exact duplicate; case-variant duplicate ("Alice" vs "alice") — `[HLD-GAP]` whether usernames are case-folded for uniqueness (mirrors the `normaliseKey()` lowercase rule for ingredient keys, but no such rule is stated for usernames); duplicate with surrounding whitespace.
- **HLD ref:** system-overview §User Accounts (login by username implies uniqueness). `[HLD-GAP]` — uniqueness and case-folding never stated.
- **Notes:** Illegal-transition / conflict pathway. Self-scoped: seed username X in the same scenario, then attempt the duplicate.

### Login / Logout / Session

#### AUTH-05 — Log in with correct credentials
- **Category:** Happy
- **Actor:** Registered user (primary or household member)
- **Preconditions:** Account ACTIVE; correct username + password.
- **Action:** Submit username + password.
- **Expected outcome:** Session credential issued (session cookie, or JWT in httpOnly cookie); frontend automatically attaches credentials on subsequent requests; the backend resolves `userId` from the session server-side; user can now reach protected actions.
- **Variations:** session-cookie mode vs JWT-in-cookie mode (HLD offers both, chooses neither — `[HLD-GAP]`); login as primary user vs household member (same mechanism); login immediately after registration vs returning visit.
- **HLD ref:** technical-architecture §Authentication; §Frontend-Backend Contract (credentials via cookies, no `Authorization` header management).
- **Notes:** This is the precondition step nearly every other pathway folds into "Authenticated." Assert "session established + a protected read now succeeds," not the credential's internal shape.

#### AUTH-06 — Log in with wrong password
- **Category:** Error
- **Actor:** Registered user
- **Preconditions:** Account ACTIVE; password supplied is incorrect.
- **Action:** Submit a valid username with the wrong password.
- **Expected outcome:** Login rejected; **no session issued**; subsequent requests remain unauthenticated.
- **Variations:** wrong password once; repeated wrong passwords (lockout? rate-limit?) — `[HLD-GAP]` no failed-attempt lockout or throttling is specified (the AI Service has rate limits, but nothing analogous is stated for auth); error message that does NOT reveal whether the username exists (anti-enumeration) — `[HLD-GAP]` unspecified.
- **HLD ref:** technical-architecture §Authentication (password verification implied by "hashed password").
- **Notes:** Core non-happy auth path. Assert no protected action succeeds afterward.

#### AUTH-07 — Log in with unknown username
- **Category:** Error
- **Actor:** Anonymous visitor
- **Preconditions:** No account with the given username.
- **Action:** Submit an unknown username (with any password).
- **Expected outcome:** Login rejected; no session issued.
- **Variations:** never-registered username; username of a deleted/deactivated account (`[HLD-GAP]` — no deletion lifecycle, so "deactivated login" behaviour is undefined); identical error to wrong-password (anti-enumeration) vs distinct error — `[HLD-GAP]`.
- **HLD ref:** technical-architecture §Authentication.
- **Notes:** Distinguish from AUTH-06 only if the HLD ever specifies enumeration policy (it does not — GAP).

#### AUTH-08 — Log in with missing credentials
- **Category:** Error
- **Actor:** Anonymous visitor
- **Preconditions:** No session.
- **Action:** Submit a login with no username, no password, or both blank.
- **Expected outcome:** Validation rejects; no session; clear error.
- **Variations:** blank username; blank password; both blank; whitespace-only.
- **HLD ref:** technical-architecture §Authentication (implied input requirement).
- **Notes:** Validation pathway; no external deps.

#### AUTH-09 — Resume a session on a returning request
- **Category:** Happy
- **Actor:** Authenticated user
- **Preconditions:** A valid, unexpired session credential exists.
- **Action:** Issue a request carrying the existing session credential (no re-login).
- **Expected outcome:** Backend resolves `userId` from the session server-side; the request proceeds as that user without re-authentication.
- **Variations:** read action; write action; act across multiple domains in one session.
- **HLD ref:** technical-architecture §Authentication; §Frontend-Backend Contract ("the backend knows who the user is from the session/token").
- **Notes:** This is the property every "Authenticated" precondition elsewhere relies on. Self-scoped.

#### AUTH-10 — Log out
- **Category:** Happy
- **Actor:** Authenticated user
- **Preconditions:** An active session.
- **Action:** Log out.
- **Expected outcome:** Session credential invalidated; subsequent requests are unauthenticated and protected actions are denied until re-login.
- **Variations:** log out then attempt a protected action (must fail — see AUTH-12); log out with no active session (idempotent no-op or error — `[HLD-GAP]`); log out on one device while another session for the same user persists (`[HLD-GAP]` — concurrent-session/global-logout behaviour unspecified).
- **HLD ref:** `[HLD-GAP]` — **no logout flow is described in any HLD doc**; this action is required by the frontend but entirely unspecified.
- **Notes:** Entirely gap-driven; assert "session ended, protected action now denied."

#### AUTH-11 — Session expires / times out
- **Category:** Edge
- **Actor:** Session store / system clock
- **Preconditions:** An authenticated session whose lifetime has elapsed.
- **Action:** The session expiry window passes; the user issues a request with the now-expired credential.
- **Expected outcome:** Request treated as unauthenticated; user must re-authenticate; in-flight protected action denied.
- **Variations:** idle timeout vs absolute timeout; exactly-at-the-boundary expiry; request at expiry−1 (still valid); JWT-expiry vs server-session-expiry (different mechanisms, both unspecified). `[HLD-GAP]` — **no timeout duration, idle-vs-absolute policy, or refresh/rotation is stated.**
- **HLD ref:** `[HLD-GAP]` — session lifetime unspecified across all three docs.
- **Notes:** Time-boundary test; hard to make deterministic without a configurable/forced expiry. The *contract* (expired ⇒ unauthenticated) is assertable; the *duration* is a GAP.

#### AUTH-12 — Access a protected action with no / invalid session
- **Category:** Error
- **Actor:** Anonymous visitor (or a user with an invalid/tampered credential)
- **Preconditions:** No valid session.
- **Action:** Attempt any protected (non-auth) action — e.g. view recipes, view a plan, give feedback.
- **Expected outcome:** Unauthorized; request rejected **before** any domain logic runs; no data returned, no state changed.
- **Variations:** no credential at all; malformed/garbage credential; expired credential (overlaps AUTH-11); a once-valid credential for a logged-out session; tampered JWT (signature invalid). `[HLD-GAP]` — the public-vs-protected route boundary is never enumerated; this asserts the general "must be authenticated" rule the whole suite leans on.
- **HLD ref:** technical-architecture §Frontend-Backend Contract ("the backend knows who the user is from the session/token" — implies a gate).
- **Notes:** This is the negative of every other domain's "Authenticated" precondition. Critical gate pathway.

#### AUTH-13 — Cross-user access attempt (act as / read another user's data)
- **Category:** Error
- **Actor:** Authenticated user A
- **Preconditions:** User A and user B both exist; A is logged in; B owns some resource.
- **Action:** A attempts to read or modify B's data — directly, or by supplying B's identifier where the API would otherwise infer the user.
- **Expected outcome:** Denied — the backend resolves the acting `userId` from A's session, **not** from a client-supplied identifier, so A cannot reach B's data; no data leaked, no state changed.
- **Variations:** A passes B's `user_id` in a body/param (must be ignored — "don't put it in URL paths"); A within the same household vs A in a different household (`[HLD-GAP]` — intra-household read scope is undefined: the HLD shares Provisions per household and each user owns their own Preference/Nutrition, but who-can-read-whose is unstated); A attempts `?actingAs=B` without authorisation (overlaps AUTH-14).
- **HLD ref:** technical-architecture §Frontend-Backend Contract ("Resolve `userId` from auth context server-side — don't put it in URL paths"). `[HLD-GAP]` — the *denial* rule is implied, never stated; intra-household authorisation undefined.
- **Notes:** Tied to the Hard Constraint Filter's safety model — the filter checks constraints *by `userId`*, so identity integrity here is a safety concern, not only privacy. Self-scoped: seed A and B in the same scenario.

#### AUTH-14 — Switch the acting household member (`actingAs`)
- **Category:** Alternate
- **Actor:** Authenticated user (likely the primary user)
- **Preconditions:** An authenticated session; a household with ≥2 members.
- **Action:** Act as another household member within the session (anticipated `?actingAs=memberId` header/param).
- **Expected outcome:** Subsequent reads/writes resolve to the target member's models — IF the actor is authorised to act as them.
- **Variations:** primary user acts as a member (allowed?); a member tries to act as the primary user (allowed?); act-as a member in a different household (must be denied). `[HLD-GAP]` — the HLD explicitly defers this ("if household member switching is needed later") and gives **no authorisation rules**: who may act as whom is entirely unspecified.
- **HLD ref:** technical-architecture §Frontend-Backend Contract (`?actingAs=memberId`, "if needed later").
- **Notes:** Tagged `@pending` material — explicitly a future capability. Cross-domain with HH-. Included so the frontend contract is complete.

### Account read / profile

#### AUTH-15 — View own account / linked models
- **Category:** Happy
- **Actor:** Authenticated user
- **Preconditions:** An active session.
- **Action:** Open the account/profile view.
- **Expected outcome:** Returns the authenticated identity (username, household membership) and references to the linked Preference Model and Nutrition Model.
- **Variations:** primary user vs household member view; freshly-registered account with empty linked models vs a populated one.
- **HLD ref:** system-overview §User Accounts ("Links to the user's Preference Model, Nutrition Model, and household membership"). `[HLD-GAP]` — **no account-read endpoint or response shape is defined anywhere**; the linkage is stated but the read surface is implied.
- **Notes:** Pure read; self-scoped on this user's identity.

### Account lifecycle / credentials

#### AUTH-16 — Change own password
- **Category:** Alternate
- **Actor:** Authenticated user
- **Preconditions:** An active session; knows current password.
- **Action:** Supply current password + a new password.
- **Expected outcome:** Password re-hashed and stored; old password no longer authenticates; new password does; existing sessions either persist or are invalidated (`[HLD-GAP]` — unspecified).
- **Variations:** correct current password (success); wrong current password (rejected); new password failing an unspecified policy (rejected — `[HLD-GAP]` no policy); reusing the same password (`[HLD-GAP]`).
- **HLD ref:** `[HLD-GAP]` — **password change is never mentioned** in any HLD doc; required for any real account system.
- **Notes:** Entirely gap-driven. Assert old credential fails + new credential succeeds.

#### AUTH-17 — Reset a forgotten password
- **Category:** Alternate
- **Actor:** Anonymous visitor (locked out)
- **Preconditions:** Has an account but cannot supply the password.
- **Action:** Initiate a password recovery flow.
- **Expected outcome:** `[HLD-GAP]` — **no recovery mechanism is described.** "No OAuth initially" implies a local credential, but no email-based reset, security question, or admin-reset path exists in the HLD.
- **Variations:** N/A — entire flow unspecified.
- **HLD ref:** `[HLD-GAP]` — absent from all docs.
- **Notes:** Flagged as a required-but-undefined frontend action. Likely `@pending` until the HLD defines a recovery channel (the system is "local / self-hosted" with no stated email infra).

#### AUTH-18 — Deactivate / delete own account
- **Category:** Error / Edge
- **Actor:** Authenticated user
- **Preconditions:** An active session.
- **Action:** Request closure/deletion of the own account.
- **Expected outcome:** `[HLD-GAP]` — **no account-deletion lifecycle is defined.** Open tension: every module stores this account's `user_id`; the Recipe Engine asserts "no hard delete"; the Nutrition mapping cache and preference archive are "unbounded." Whether deleting an account cascades, soft-closes, anonymises, or is forbidden is entirely unspecified — and potentially self-contradictory with the no-hard-delete invariant.
- **Variations:** delete the primary user of a multi-member household (what happens to the shared household/provisions?); delete a sole-member account; delete an account with active plan/feedback history.
- **HLD ref:** `[HLD-GAP]` — absent; flagged tension with technical-architecture §Cross-module references (`user_id` used by every module) and recipe-system "no hard delete."
- **Notes:** High-value gap; recorded, not resolved. Cross-domain with HH- (member removal) and every data-owning module.

#### AUTH-19 — Remove a household member's account
- **Category:** Error / Edge
- **Actor:** Primary user
- **Preconditions:** A household with ≥2 members.
- **Action:** Remove another member from the household.
- **Expected outcome:** `HouseholdMemberRemovedEvent` fires (consumed by Planner — slot reconfiguration; Notification — alert members). The *event* is specified; the **account-and-data fate** of the removed member (account deleted? retained but unlinked? their Preference/Nutrition models orphaned?) is `[HLD-GAP]`.
- **Variations:** remove a member with their own feedback/plan history; remove the last non-primary member; a member removing themselves (leave household). `[HLD-GAP]` — authority (who may remove whom) and data consequences both unspecified.
- **HLD ref:** technical-architecture §Event catalogue (`HouseholdMemberRemovedEvent`); system-overview §Household.
- **Notes:** Auth-relevant leg only (the account lifecycle consequence); membership mechanics detailed in household.md. Recorded gap.

### GDPR / data rights

#### AUTH-20 — Export own data (GDPR portability)
- **Category:** Alternate
- **Actor:** Authenticated user
- **Preconditions:** An active session.
- **Action:** Request an export of all data associated with the account.
- **Expected outcome:** `[HLD-GAP]` — **the HLD contains no data-export mechanism whatsoever.** Scope (which models/logs are included), format, and delivery are all undefined. Recorded as a required-for-the-frontend action with zero HLD coverage.
- **Variations:** N/A — entirely unspecified.
- **HLD ref:** `[HLD-GAP]` — absent from all three docs (and the wider design set as far as auth-relevant docs go).
- **Notes:** The task explicitly scoped GDPR "if the HLD mentions them" — it does **not**, so this is captured purely as a flagged gap, not a behavioural spec. Likely `@pending`.

#### AUTH-21 — Request account + data deletion (GDPR erasure)
- **Category:** Alternate / Error
- **Actor:** Authenticated user
- **Preconditions:** An active session.
- **Action:** Request erasure of the account and its personal data.
- **Expected outcome:** `[HLD-GAP]` — **no GDPR erasure flow exists in the HLD**, and it directly collides with: (a) Recipe Engine "no hard delete," (b) the unbounded Nutrition ingredient-mapping cache and `preference_taste_profile_archive`, and (c) `user_id` being referenced by every module without a defined cascade. The right-to-erasure vs no-hard-delete contradiction is the headline finding.
- **Variations:** N/A — undefined; overlaps AUTH-18.
- **HLD ref:** `[HLD-GAP]` — absent; contradiction flagged against recipe-system + technical-architecture.
- **Notes:** Captured as a flagged gap/contradiction only, per task scope. Not resolved here.

### Flagship cross-module journey

#### AUTH-22 — Register → log in → provision identity → act across a domain → log out → denied
- **Category:** Happy (flagship end-to-end; the auth gate the whole suite depends on)
- **Actor:** Primary user (+ Auth subsystem, Preference/Nutrition models, Household as system actors)
- **Preconditions:** No session; username available.
- **Action (sequence):**
  1. Register a new account with a unique username + password (password hashed) — a canonical `user_id` is minted *(AUTH-01)*.
  2. The account is linked to a fresh Preference Model, Nutrition Model, and household membership *(cross-module: Preference, Nutrition, Household — provisioning implied)*.
  3. Log in with the credentials; a session credential is issued and rides on the cookie *(AUTH-05)*.
  4. Perform a protected action in another domain (e.g. create a recipe / set a hard constraint / view the plan) — the backend resolves `userId` from the session, **not** from any client-supplied id, and the action is scoped to this user *(cross-module: any of RCP-/PREF-/PLAN-)*.
  5. Confirm a *second* user B cannot reach this user's data within their own session *(AUTH-13 — identity isolation)*.
  6. Log out; the session is invalidated *(AUTH-10)*.
  7. Re-attempt the protected action — now denied as unauthenticated *(AUTH-12)*.
- **Expected outcome:** A working account with a stable `user_id`, an established-then-torn-down session, exactly one user's data touched throughout (no cross-user leakage), and protected actions gated on the live session at both ends.
- **Variations:** session-cookie vs JWT-in-cookie credential; primary user vs household member as the actor; the cross-domain action being a read vs a write; auto-login-on-register vs explicit login (`[HLD-GAP]`).
- **HLD ref:** system-overview §User Accounts; technical-architecture §Authentication, §Frontend-Backend Contract, §Cross-module references.
- **Notes:** CROSS-MODULE backbone — this journey is the **precondition template** every other domain's pathways assume: step 1–3 are the "fresh user with a random handle" the README's self-contained-data rule mandates, and step 4 is the generic "Authenticated" precondition. Steps 2 (model provisioning) and 5 (identity isolation / filter-by-`userId` safety) are the integration touchpoints detailed in preference.md / nutrition.md / household.md and the cross-journey file. Assertions span auth state (session up/down) + identity (`user_id` stable, isolation holds) + a single downstream domain action succeeding then being denied.

---

## Appendix — `[HLD-GAP]` findings (consolidated)

> Auth is "infrastructure, not a domain" in the HLD, so this appendix is unusually large: the HLD specifies the *primitives* (username, hashed password, cookie-borne session, server-side `userId` resolution, multi-user) but almost none of the *flows, policies, or lifecycles*. None resolved here — triaged in one batch later.

| # | Gap | Pathway |
|---|---|---|
| AG1 | No registration validation rules at all (password policy, username format/length, allowed chars). | AUTH-02 |
| AG2 | Whether registration auto-logs-in or requires a separate login step. | AUTH-01, AUTH-22 |
| AG3 | Whether registration eagerly provisions the linked Preference/Nutrition models + household, or they're created lazily. | AUTH-01 |
| AG4 | Household onboarding model for an additional member: invite vs self-signup vs primary-user-creates. | AUTH-03 |
| AG5 | Username uniqueness + case-folding/whitespace normalisation rule (no `normaliseKey()` analogue stated for usernames). | AUTH-04 |
| AG6 | Credential mechanism left unchosen — session-cookie vs JWT-in-httpOnly-cookie (HLD offers both, picks neither). | AUTH-05, AUTH-11 |
| AG7 | No failed-login lockout / throttling policy. | AUTH-06 |
| AG8 | Username-enumeration policy on login errors (identical vs distinct messages for wrong-password vs unknown-user). | AUTH-06, AUTH-07 |
| AG9 | No logout flow is described anywhere. | AUTH-10 |
| AG10 | Logout-with-no-session behaviour (idempotent vs error); global vs per-device logout; concurrent sessions per user. | AUTH-10 |
| AG11 | No session lifetime / timeout policy (idle vs absolute), no refresh/rotation, no "remember me". | AUTH-11 |
| AG12 | Public-vs-protected route boundary never enumerated (which actions require auth). | AUTH-12 |
| AG13 | Cross-user denial rule only implied by server-side `userId` resolution, never stated; intra-household read scope undefined. | AUTH-13 |
| AG14 | `actingAs` household-switching is explicitly deferred and has no authorisation rules (who may act as whom). | AUTH-14 |
| AG15 | No account-read endpoint or response shape defined despite the stated model linkage. | AUTH-15 |
| AG16 | Password-change flow entirely absent; effect on existing sessions unspecified. | AUTH-16 |
| AG17 | No forgotten-password / account-recovery mechanism (no email infra; "local/self-hosted"). | AUTH-17 |
| AG18 | No account deactivation/deletion lifecycle; no account states defined beyond implicit ACTIVE. | AUTH-18, §4.1 |
| AG19 | Fate of a removed household member's account + their Preference/Nutrition models (delete? orphan? retain?); removal authority undefined. | AUTH-19 |
| AG20 | **No GDPR data-export mechanism** (scope/format/delivery all absent). | AUTH-20 |
| AG21 | **No GDPR erasure flow — and it contradicts** Recipe Engine "no hard delete", the unbounded mapping cache + preference archive, and undefined `user_id` cascade across every module. | AUTH-21, AUTH-18 |
| AG22 | Child profiles referenced ("age restrictions auto-populated for child profiles") but their auth status (full account / sub-profile / auth-less) is undefined. | §2 Actors |
| AG23 | How the external Health Platform integration authenticates / acts on behalf of a user is unspecified. | §2 Actors |
