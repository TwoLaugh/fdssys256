# Page spec — Onboarding (`/onboarding`)

Contract-complete but short: the wizard is five steps over endpoints that all
have richer homes elsewhere — each step links its owning page spec rather than
re-specifying fields. Companion docs: [../ia.md](../ia.md) (the 5-step IA),
[../design-language.md](../design-language.md).

**Entry condition** (shell-routed, [login.md](login.md) §5): authenticated user
with `GET /households/current` → 404. **Exit**: redirect `/` (Today).

---

## 1. Intent (HLD / IA)

- IA route 2: "5 steps: household → invite members → allergies & dietary
  identity → lifestyle/slot config → nutrition targets (auto-seed)."
- **GAP-84 ruling**: member onboarding is the invite handshake — so step 1 must
  branch **create vs join** (an invitee should never create a duplicate
  household).
- Targets bootstrap (`paths/nutrition.yaml#targetsInitialise`): "creates the
  targets aggregate from the onboarding-computed request and DRI-seeds any
  micronutrient the request omits." The *micros* auto-seed; the macros are the
  wizard's job (§3 step 5).
- Wizard state is **derived, not stored**: there is no onboarding-progress
  resource. Each step probes its own GET and skips if already satisfied (§4).

## 2. Step → endpoint mapping

| Step | Title | Endpoint(s) | Advances when | Skippable? |
|---|---|---|---|---|
| 1a | Create your household | `POST /api/v1/households` (`{ name }`) | 201 | No (1a **or** 1b required) |
| 1b | …or join one | `POST /api/v1/invites/accept` (`{ inviteCode }`) — surface shared with `/invite` ([settings.md](settings.md) §3d) | 200 | — |
| 2 | Invite members | `POST /api/v1/households/current/invites` (+ list `GET`) | user clicks Next | **Yes** ("just me" is the default) |
| 3 | Allergies & dietary identity | `PUT /api/v1/preferences/hard-constraints` (`UpdateHardConstraintsRequest`, `expectedVersion: 0`) | 200 | Yes — but see §5 G1: the PUT 404s until the aggregate exists |
| 4 | Lifestyle & slots | `PUT /api/v1/preferences/lifestyle-config` (`UpdateLifestyleConfigRequest`, `expectedVersion: 0`) + optionally `PUT /households/{id}/settings` (slot defaults — created with sane defaults at step 1, so usually skipped) | 200 | Yes (defaults are fine) |
| 5 | Nutrition targets | `POST /api/v1/nutrition/targets/initialise` (`UpdateTargetsRequest`, `expectedVersion: 0`) | 201 | Yes — Today/Nutrition render "set targets" links until done ([today.md](today.md) §4) |

Step 1b joiners skip straight to step 3 (household + slots already exist; a
joining member is not primary and cannot edit them anyway).

Field-level detail: step 3 fields → /preferences spec (incl. the GAP-04
interstitial — additions never trigger it, so onboarding usually won't see the
409); step 4 lifestyle fields (meal-timing window, novelty %, batch-cooking) →
/preferences spec; step 4 slot editor → [settings.md](settings.md) §3c; step 5
targets form → [nutrition.md](nutrition.md) §4. Onboarding renders *reduced*
forms of each (the IA's promise is a 5-minute setup, not the full editors).

## 3. Per-step behaviour notes

- **Step 1a**: single `name` field (1–128). 409 ("already a member") → the user
  half-finished earlier: silently advance to step 2.
- **Step 2**: create invite (role select + expiry ≤30d) → show the one-time code
  + copy/share link (code is null in every later response —
  [settings.md](settings.md) §3b). Repeatable; "Next" advances.
- **Step 3**: reduced form — allergy chips, dietary-identity select, severe
  intolerances. Submits the **full replace** shape with empty arrays for what
  the user didn't touch.
- **Step 5**: the wizard computes goal-based macro suggestions client-side
  (goal select → suggested calories/protein/carbs/fat/fibre/satFat +
  perMealDistribution), user confirms/tweaks, then POSTs. Micros are omitted —
  the server DRI-seeds them. 409 ("targets row already exists") → silently
  advance (re-run case).

## 4. Resume / skip rules (derived state)

On wizard mount, probe in order and jump to the first unsatisfied step:
`households/current` (404 → step 1) → `preferences/hard-constraints` GET (404 →
step 3) → `preferences/lifestyle-config` GET (404 → step 4) →
`nutrition/targets` GET (404 → step 5) → all present → redirect `/`. Steps 2 and
4's household-settings half are never blockers (defaults exist from creation).

## 5. Open questions / backend gaps

1. **G1 — blocker: steps 3 & 4 cannot complete against the current contract.**
   Both `PUT /preferences/hard-constraints` and `PUT /preferences/lifestyle-config`
   404 until an internal `initialise` runs — and neither initialise is exposed
   over REST. `LifestyleConfigController`'s own javadoc: "the initialise flow is
   intentionally NOT exposed on the REST surface here — the onboarding wizard
   ticket is responsible for calling `LifestyleConfigUpdateService#initialise`
   during the wizard's submit step" — i.e. a server-side onboarding endpoint was
   assumed and never built (`initialiseHardConstraints` is currently reachable
   only via the health-directive SPI and the test-profile e2e seeder).
   **Backend ticket needed**: either expose `POST .../initialise` for both, make
   the PUTs upsert on first write, or seed both rows on `UserRegisteredEvent`
   (the event already exists with no listener).
2. **G2 — `targets/initialise` reuses `UpdateTargetsRequest`**, so the create
   call must send a meaningless `expectedVersion` (use 0) and the *full*
   aggregate including `perMealDistribution`/`activityAdjustments` — heavier
   than a bootstrap needs. Cosmetic; the IA's "(auto-seed)" is true only of
   micros.
3. **G3 — no resume marker**: the §4 probe chain costs 3–4 GETs on every
   `/onboarding` mount. Acceptable; noting in case product wants a stored
   wizard-state later.
4. Invite-code delivery is copy-paste only in v1 ([settings.md](settings.md)
   §8 Q4).

## 6. Not on this page

Full editors for everything the wizard touches: /settings (household, members,
slots), /preferences (constraints, lifestyle), /nutrition (targets). Plan
generation is **not** an onboarding step — the Today empty state owns the
"Generate your first plan" CTA ([today.md](today.md) §3a).

## 7. Mock deltas

1. Build the wizard shell (5 steps + 1b join branch) with the §4 probe-derived
   resume logic; currently the mock seeds a household and skips onboarding
   entirely.
2. Wire step forms to the real request shapes (full-replace semantics,
   `expectedVersion: 0`, one-time invite code reveal, client-computed macro
   suggestions for step 5).
3. Mock G1's missing initialise as an upsert-on-first-PUT so the wizard is
   demonstrable; tag it `data-gap="G1"` so the live wiring fails loudly.
