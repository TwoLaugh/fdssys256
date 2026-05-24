# Household Domain — User-Pathway Catalogue

> Code-agnostic behavioural catalogue derived purely from the HLD design docs. Double duty: (a) source for E2E test scenarios; (b) behavioural spec for the frontend. No endpoints, HTTP verbs, class names, or DB tables — pure user/behaviour language. Where the HLD is silent on something a user would obviously need, it is flagged `[HLD-GAP]` rather than invented. **There is no dedicated Household HLD** — this domain is extracted from `system-overview.md §Household Model`, `technical-architecture.md` (events, `checkForHousehold`, who-injects-what), `preference-model.md §How It Gets Used (Household Model)` + `household_context`, and `nutrition-model.md` (per-member targets). Consequently this domain is unusually gap-heavy — almost every behaviour beyond the six bullet points in the system overview is implied, not specified.

---

## 1. Domain Summary

The Household domain is the system's **multi-user composition layer**. It is not a data model in its own right — it owns very little state (membership, roles, shared-vs-individual meal settings, and the shared Provisions link) and instead *coordinates* the per-user data models that already exist. Its defining responsibilities per the HLD:

- **Each member is a full user**: own account, own Preference Model (3 tiers), own Nutrition Model. The household does **not** hold per-member preference/nutrition state — it references the members' own models.
- **Provisions are shared per household** (pantry, equipment, environment) — one physical kitchen, one inventory, one budget. (Contrast: preference and nutrition are per-member.)
- **Household settings define which meal slots are shared vs individual.** Shared slots are cooked once for multiple eaters; individual slots are planned per person.
- **The soft-preference merge across members**: for a shared meal the planner must reconcile multiple taste profiles. The HLD locates the *hard-constraint union* deterministically (`HardConstraintFilterService.checkForHousehold`, "union = most restrictive") but explicitly **defers the soft-preference merge** ("weighting, conflict resolution") to "the Household Model design" which does not exist — so the merge algorithm is almost entirely `[HLD-GAP]`.
- **Headcount-based portion scaling** for shared slots.
- **A role/permission boundary**: the *primary user* manages shared Provisions and the shared plan; *household members* can give feedback only on their own meals.

In the three-loop architecture the Household domain sits **above** all three constraint loops: it widens the constraint set for shared slots (union of hard constraints + merged soft preferences feed the **Planner**), shares one Provisions loop across members, and keeps the Preference/Nutrition loops per-member. It is a **caller/coordinator**, not an optimiser. The merge it produces is consumed by the Planner's constraint-feasibility check and scoring; when the merged hard-constraint set is irreconcilable, control passes to the Planner's **constraint resolution** (split the slot into individual meals).

## 2. Actors

| Actor | Role in this domain (per HLD) |
|---|---|
| **Primary user** | Creates/owns the household. Manages shared Provisions and the shared plan. Adds/removes members, sets roles, defines shared-vs-individual meal settings, sets headcount per slot. Has their own Preference + Nutrition models like any member. The only actor the HLD grants shared-resource management authority. |
| **Household member** | A full user with own account + own Preference Model + own Nutrition Model. Per the HLD can **give feedback on their own meals**. Any authority beyond that (managing shared settings, editing shared Provisions, removing others, viewing others' models) is unspecified `[HLD-GAP]`. |
| **Child / dependent member** | A member whose Preference Model carries `profile_metadata.age_group ∈ {young_child, child, teen}`, `portion_scale < 1.0`, `preference_volatility: high`, auto-populated `age_restrictions` (hard constraints). Whether a child has an independent login or is a managed profile under the primary is unspecified `[HLD-GAP]`. |
| **Meal Planner (caller / downstream)** | Consumes the household's per-slot eater set, the hard-constraint union (`checkForHousehold`), the merged soft preferences, and headcount; runs constraint-feasibility and constraint-resolution (split slot) on irreconcilable unions; emits the shared plan. Listens to `HouseholdMember*`/`HouseholdSettingsChanged`/`HouseholdRoleChanged`/`HouseholdCreated` events for slot reconfiguration. |
| **Hard Constraint Filter (system actor, deterministic)** | `HardConstraintFilterService.checkForHousehold(userIds, ingredients)` — computes the union (most restrictive) of all eaters' allergies + dietary identities for a shared slot. Code, never AI. |
| **Notification System (downstream)** | Listens to the same household events to alert affected members ("you were added to a household", settings changed, etc.). |
| **Provisions (shared, downstream)** | One shared inventory/equipment/environment/budget per household. The Household module injects `ProvisionQueryService` (per the who-injects-what matrix) and `PreferenceQueryService` (for the union/merge). |

## 3. Action Space (frontend-spec backbone)

Flat, exhaustive list of every distinct user (or user-facing system) action the HLD permits or clearly implies. Each: verb-phrase + one-line description + HLD ref. Almost all of these are *implied* — the HLD enumerates the household's responsibilities but not its operations — so most carry an inline gap. Downstream pathways draw from this.

### Create / membership
1. **Create a household** — the primary user establishes a household, becoming its first member + manager (emits `HouseholdCreatedEvent`). §Household Model; tech-arch §Event catalogue (`HouseholdCreatedEvent`).
2. **Add a member to the household** — invite/attach another user account as an eater (emits `HouseholdMemberAddedEvent`). §Household Model ("household members"); tech-arch §Event catalogue.
3. **Add a child / dependent profile** — add a member whose age_group drives auto-populated age restrictions + portion_scale. §Household Model; preference-model §Profile Metadata, §Hard Constraints (age restrictions).
4. **Remove a member from the household** — detach a member (emits `HouseholdMemberRemovedEvent`). §Household Model; tech-arch §Event catalogue.
5. **Accept / decline a household invitation** — the invited user's side of joining `[HLD-GAP]` (invite/accept handshake is never described — membership may be primary-driven only). §Household Model (implied by "household members").

### Roles / permissions
6. **Assign / change a member's role** — set who is primary vs ordinary member (emits `HouseholdRoleChangedEvent`). §Household Model ("primary user manages…"); tech-arch §Event catalogue (`HouseholdRoleChangedEvent`).
7. **Transfer the primary role** — hand shared-resource management to another member. §Household Model (implied by role existence); `[HLD-GAP]` no transfer flow described.
8. **View household roster + roles** — list members, their roles, and which models they own. §Household Model; `[HLD-GAP]` no read interface specified.

### Shared-vs-individual meal configuration
9. **Define which meal slots are shared vs individual** — household-level setting per meal slot (emits `HouseholdSettingsChangedEvent`). §Household Model ("Household settings define which meals are shared vs individual"); tech-arch §Event catalogue.
10. **Set the eater set for a shared slot** — choose which members eat a given shared slot. §Household Model (implied — union is "all eaters'"); `[HLD-GAP]` per-slot eater assignment mechanism unspecified.
11. **Set / adjust headcount for a shared slot** — portions scale per meal based on headcount. §Household Model ("Portions scale per meal based on headcount").
12. **Mark a member as not eating a slot (guest-out / eating-out)** — exclude a member from a shared slot occasionally `[HLD-GAP]` (preference-model defers "guest/occasion overrides" to the Household Model, which doesn't exist). §Household Model; preference-model §Boundaries ("Guest/occasion overrides").

### Provisions sharing (shared resource)
13. **View shared Provisions** — any member views the shared pantry/equipment/environment/budget `[HLD-GAP]` (member read access to shared Provisions not stated). §Household Model ("Provisions … shared per household").
14. **Manage shared Provisions** — the primary user edits shared inventory/equipment/budget. §Household Model ("Primary user manages provisions").
15. **Set shared kitchen environment** — environment is shared per household (own table `household_environments`). §Household Model; tech-arch §Database (`household_environments`).

### Per-member models (referenced, not owned by household)
16. **Configure a member's own Preference Model** — each member sets their own hard constraints / taste profile / lifestyle config. §Household Model ("each user has their own … Preference Model"); preference-model (whole doc).
17. **Configure a member's own Nutrition Model** — each member sets their own targets / eating window / activity. §Household Model ("their own … Nutrition Model"); nutrition-model (whole doc).
18. **Tag a preference as individual-only vs household-suitable** — `individual_only_preferences` + `suitable_for` on recipe prefs distinguish "foods I enjoy alone" from "family-suitable". preference-model §household_context, §Design notes (Household context tags).

### The merge (shared meal reconciliation)
19. **Compute the hard-constraint union for a shared slot** — deterministic union (most restrictive) of all eaters' allergies + dietary identities. preference-model §How It Gets Used (Household Model); tech-arch §Hard Constraint Filter (`checkForHousehold`).
20. **Merge soft preferences across eaters for a shared slot** — reconcile multiple taste profiles into one scoring input for the planner. preference-model §How It Gets Used (Household Model) — **"weighting, conflict resolution" explicitly deferred to a non-existent Household Model design** → `[HLD-GAP]`.
21. **Surface an irreconcilable shared slot → propose split** — when the union is near-empty, hand off to planner constraint resolution (split into individual meals). §Constraint resolution (Household hard constraint collision); §Household Model.

### Feedback (role-scoped)
22. **Give feedback on one's own meal (any member)** — a member submits feedback on a meal they ate. §Household Model ("household members can give feedback on their own meals"); feedback-system (routing).
23. **Attempt feedback on another member's individual meal** — boundary case the role rule implies is disallowed `[HLD-GAP]` (the rule says "their own meals"; enforcement is implied, not specified). §Household Model.

### System-driven
24. **React to a household event (slot reconfiguration / alerts)** — Planner reconfigures plan slots and Notification alerts members when membership/settings/roles change. tech-arch §Event catalogue (`HouseholdMember*`, `HouseholdSettingsChanged`, `HouseholdRoleChanged`, `HouseholdCreated`).

## 4. State Models

> The HLD defines **no** explicit household/membership lifecycle. The models below are reconstructed from the event catalogue (which proves create/add/remove/role-change/settings-change exist as transitions) and the six system-overview bullets. Every transition not directly evidenced is flagged.

### 4.1 Household lifecycle
```
(no household)
   │  primary user creates household → HouseholdCreatedEvent
   ▼
ACTIVE household (≥1 member, exactly one primary)
   │  add member → HouseholdMemberAddedEvent
   │  remove member → HouseholdMemberRemovedEvent
   │  change settings (shared/individual slots, headcount) → HouseholdSettingsChangedEvent
   │  change role / transfer primary → HouseholdRoleChangedEvent
   ▼
(dissolve / delete household?)  [HLD-GAP] — no teardown/dissolve transition is described.
```
**Illegal / disallowed transitions (→ error pathways):**
- A household with **zero primary** members (removing or demoting the last primary). `[HLD-GAP]` — the HLD says the primary manages shared resources but never states the "exactly one primary, always" invariant; it is *implied* and recorded as a gap, not resolved.
- A household member belonging to **two households at once** `[HLD-GAP]` (multi-household membership unspecified; auth links "household membership" singular-sounding but not stated).
- Removing a member who is the **sole eater of an in-flight shared slot** — effect on the active plan unspecified `[HLD-GAP]`.

### 4.2 Membership state (per member)
```
INVITED?  [HLD-GAP — no invite/accept handshake described]
   │
   ▼
MEMBER (role: primary | ordinary)
   ├─ role changed (ordinary ⇄ primary) → HouseholdRoleChangedEvent
   └─ removed → HouseholdMemberRemovedEvent → (former member; own Preference/Nutrition models retained as a standalone user?)  [HLD-GAP]
```
**Illegal transitions:** demoting the only primary (see 4.1); an ordinary member performing primary-only actions (manage shared Provisions, change settings, remove others) — disallowed by the role rule, enforcement implied `[HLD-GAP]`.

### 4.3 Meal-slot sharing state (household setting, per slot)
```
INDIVIDUAL  (planned per person; each eater's own constraints only)
   ⇅  household settings change → HouseholdSettingsChangedEvent → planner reconfigures slot
SHARED      (one cook for the eater set; union of hard constraints + merged soft prefs; headcount-scaled portions)
   │  union irreconcilable (e.g. vegan + keto eaters)
   ▼
SPLIT  (planner constraint resolution splits the SHARED slot back into per-person INDIVIDUAL meals — hard constraints never relaxed)
```
**Illegal transitions:** relaxing a hard constraint to keep a slot SHARED (the design forbids this — structural split instead); a SHARED slot with an empty eater set `[HLD-GAP]`.

### 4.4 Per-member model ownership (invariant, not a lifecycle)
- Preference Model: **per member** (3 tiers each).
- Nutrition Model: **per member**.
- Provisions (pantry/equipment/environment/budget): **shared, one per household**.
- A member with **no Nutrition Model configured** is valid — "if the user skips nutrition setup… the planner operates without nutrition constraints" (nutrition-model §Bootstrapping). A member with a **near-empty taste profile** is valid (cold start). These feed degenerate-merge edge pathways.

---

## 5. Pathways

> Categories: **Happy** (default success), **Alternate** (valid non-default), **Error** (validation/not-found/unauthorized/conflict/illegal-transition), **Edge** (empty/huge/boundary/duplicate/concurrent). Cross-module touchpoints (Planner merge consumption, Hard Constraint Filter, Preference/Nutrition per-member, shared Provisions, Notification) are noted; they are fully detailed in their own domain files + the cross-journey file.
>
> **Self-contained data / self-scoped assertions:** every scenario creates its own household with freshly-registered member accounts (random handles) and asserts only on *this household's* roster, slots, and merge outcomes — never on global member/household counts (per `README §4`).

### Create / membership

#### HH-01 — Create a household
- **Category:** Happy
- **Actor:** Primary user
- **Preconditions:** Authenticated user with no existing household (or `[HLD-GAP]` — whether a user may belong to/own more than one is unspecified).
- **Action:** Create a household.
- **Expected outcome:** Household exists with the creator as its sole member and primary; shared Provisions context (pantry/equipment/environment/budget) is established for it; `HouseholdCreatedEvent` published → Planner + Notification react.
- **Variations:** create with a name vs unnamed `[HLD-GAP]` (no household attributes defined beyond membership); creator already owns a household (reject vs second household — unspecified); brand-new account creating a household immediately at onboarding.
- **HLD ref:** system-overview §Household Model; tech-arch §Event catalogue (`HouseholdCreatedEvent`), §Database (`household_members`, `household_environments`).
- **Notes:** Cross-module: Provisions (shared context init), Planner/Notification (event listeners). Assert on the new household id + the creator's primary role.

#### HH-02 — Add a member to the household
- **Category:** Happy
- **Actor:** Primary user
- **Preconditions:** An ACTIVE household; a second registered user account.
- **Action:** Add the second account as a household member.
- **Expected outcome:** Member attached as an ordinary member; their own Preference + Nutrition models are referenced (not copied into the household); `HouseholdMemberAddedEvent` published → Planner may reconfigure shared slots, Notification alerts the member.
- **Variations:** add a member who already has a populated taste profile vs a brand-new empty one; add a member with conflicting hard constraints (e.g. existing member vegetarian, new member none — union widens); add a member with a stricter dietary identity (union tightens for shared slots); add a member with an allergy nobody else has (now constrains every shared slot they eat).
- **HLD ref:** system-overview §Household Model; tech-arch §Event catalogue (`HouseholdMemberAddedEvent`); preference-model §How It Gets Used (Household Model).
- **Notes:** Cross-module: Preference (union recompute), Planner (slot reconfig). `[HLD-GAP]` — the *add* mechanism (direct attach by primary vs invite/accept) is never described; see HH-05.

#### HH-03 — Add a child / dependent profile
- **Category:** Alternate
- **Actor:** Primary user
- **Preconditions:** An ACTIVE household.
- **Action:** Add a member whose `profile_metadata.age_group` is a child band.
- **Expected outcome:** Member created with `age_group ∈ {young_child, child, teen}`, `portion_scale < 1.0`, `preference_volatility: high`, `update_confirmation_threshold` defaulting to 5; **age restrictions auto-populated as hard constraints** (e.g. no whole nuts under 5, no raw shellfish) and enforced deterministically like allergies in every shared slot the child eats.
- **Variations:** young_child (portion_scale ~0.33) vs teen (~0.9+); age restriction that collides with a recipe an adult eats in a shared slot (slot must satisfy the union → child restriction wins); child's high preference_volatility requiring more confirming signals before a taste-profile experiment promotes.
- **HLD ref:** preference-model §Profile Metadata, §Hard Constraints (age restrictions), §Tier 1; tech-arch §Hard Constraint Filter (age restrictions auto-populated).
- **Notes:** Cross-module: Preference (hard constraints), Hard Constraint Filter, Planner (portion scaling). `[HLD-GAP]` — whether a child is a separate login or a managed sub-profile under the primary is unstated; portion_scale-vs-Nutrition-calorie-target interaction noted (preference owns presentation scale, nutrition owns calories).

#### HH-04 — Remove a member from the household
- **Category:** Alternate
- **Actor:** Primary user
- **Preconditions:** An ACTIVE household with ≥2 members.
- **Action:** Remove an ordinary member.
- **Expected outcome:** Member detached; `HouseholdMemberRemovedEvent` published → Planner reconfigures shared slots (that eater drops out of the union; headcount falls), Notification alerts; the removed member's own Preference/Nutrition models persist with their account `[HLD-GAP]` (fate of the ex-member's account/models unstated).
- **Variations:** remove a member who was the *only* one imposing a particular allergy on shared slots (union relaxes — previously-excluded recipes re-open); remove a member mid-plan-week (effect on the in-flight shared plan unspecified — see HH-22); remove a member who ate only individual slots (no union change).
- **HLD ref:** system-overview §Household Model; tech-arch §Event catalogue (`HouseholdMemberRemovedEvent`).
- **Notes:** Cross-module: Planner (slot/union/headcount recompute). Assert the roster shrank and the union recomputed for *this* household.

#### HH-05 — Join via invitation handshake
- **Category:** Alternate
- **Actor:** Primary user (invites) + invited user (accepts/declines)
- **Preconditions:** An ACTIVE household; a target user account.
- **Action:** Primary invites; invited user accepts or declines.
- **Expected outcome:** On accept → member added (as HH-02); on decline → no membership change. `[HLD-GAP]` — **the entire invite/accept handshake is undescribed**; the HLD only states members exist and the primary manages the household. Pathway is tagged for the frontend contract but the flow is a gap.
- **Variations:** accept; decline; invite an account already in another household; invite a non-existent account; invitation to an account that later registers.
- **HLD ref:** system-overview §Household Model (implied); system-overview §User Accounts ("Links to … household membership").
- **Notes:** Pure gap-surfacing pathway. No event named for invite/decline (only Added/Removed exist), reinforcing that membership may be primary-driven attach with no handshake.

### Roles / permissions

#### HH-06 — Assign / change a member's role
- **Category:** Happy
- **Actor:** Primary user
- **Preconditions:** An ACTIVE household with ≥2 members.
- **Action:** Change a member's role (e.g. promote an ordinary member toward primary, or adjust permissions).
- **Expected outcome:** Role updated; `HouseholdRoleChangedEvent` published → Planner/Notification react. The promoted member can now manage shared Provisions and the shared plan (per the primary's authority definition).
- **Variations:** promote ordinary → primary (now two primaries? — see HH-08); a defined intermediate role `[HLD-GAP]` (only "primary" vs "member" is named — no role taxonomy beyond that); demote a primary to ordinary.
- **HLD ref:** system-overview §Household Model; tech-arch §Event catalogue (`HouseholdRoleChangedEvent`).
- **Notes:** `[HLD-GAP]` — the role set is binary-and-vague (primary "manages provisions and shared plan"; members "give feedback on own meals"); intermediate permissions are undefined.

#### HH-07 — Transfer the primary role to another member
- **Category:** Alternate
- **Actor:** Primary user
- **Preconditions:** An ACTIVE household with ≥2 members.
- **Action:** Hand the primary role to another member.
- **Expected outcome:** Target becomes primary; original primary becomes ordinary (or co-primary?); shared-resource management authority moves. `[HLD-GAP]` — no transfer flow, and whether the household allows **multiple** primaries or exactly one is unstated.
- **Variations:** transfer with original stepping down; transfer creating two primaries; transfer to a child member (should be disallowed? — unspecified).
- **HLD ref:** system-overview §Household Model (role existence implies transfer); tech-arch §Event catalogue (`HouseholdRoleChangedEvent`).
- **Notes:** Whole behaviour is a gap; pathway exists for the frontend contract.

#### HH-08 — Ordinary member attempts a primary-only action
- **Category:** Error
- **Actor:** Household member (ordinary)
- **Preconditions:** An ACTIVE household; the actor is not primary.
- **Action:** Attempt to edit shared Provisions, change household settings (shared/individual slots, headcount), remove another member, or change a role.
- **Expected outcome:** Rejected — unauthorized; the role rule grants ordinary members only own-meal feedback. `[HLD-GAP]` — the rule is stated as a responsibility split ("primary user manages …; members can give feedback on own meals") but the **enforcement boundary and the full set of primary-only actions are never enumerated**.
- **Variations:** member edits shared pantry; member flips a slot from individual→shared; member removes the primary; member changes own role to primary.
- **HLD ref:** system-overview §Household Model ("Primary user manages provisions and the shared plan; household members can give feedback on their own meals").
- **Notes:** Permission-boundary pathway. The precise list of gated actions is the key gap; test asserts "rejected for non-primary" against the implied boundary.

#### HH-09 — Remove or demote the last primary
- **Category:** Error / Edge
- **Actor:** Primary user (acting on self or the only other primary)
- **Preconditions:** Household has exactly one primary.
- **Action:** Remove that primary, or demote them to ordinary, leaving the household with no manager.
- **Expected outcome:** Should be rejected — a household with no primary cannot manage shared Provisions or the shared plan. `[HLD-GAP]` — the "≥1 primary always" invariant is **implied** by the management-authority model but never stated; whether the system blocks it, auto-promotes someone, or orphans the household is unspecified. **Recorded, not resolved.**
- **Variations:** demote self as sole primary; remove self as sole primary; remove the only other primary in a two-primary household (degrades to one — legal).
- **HLD ref:** system-overview §Household Model (implied invariant).
- **Notes:** Illegal-transition pathway from §4.1. Directly tied to gap on primary cardinality.

#### HH-10 — View the household roster + roles
- **Category:** Happy
- **Actor:** Primary user / Household member
- **Preconditions:** An ACTIVE household.
- **Action:** Open the household view.
- **Expected outcome:** Members listed with roles and an indication of which models they own; shared-vs-individual slot settings and headcount visible.
- **Variations:** single-member household (just the primary); large household; ordinary member's view (may be restricted — `[HLD-GAP]` whether members can see others' roster/models/preferences). 
- **HLD ref:** system-overview §Household Model.
- **Notes:** `[HLD-GAP]` — no read interface or visibility rules specified. Self-scoped read on *this* household's roster.

### Shared-vs-individual meal configuration

#### HH-11 — Define which meal slots are shared vs individual
- **Category:** Happy
- **Actor:** Primary user
- **Preconditions:** An ACTIVE household with ≥2 members.
- **Action:** Mark specific meal slots (e.g. weekday dinners) as shared and others (e.g. weekday lunches at separate workplaces) as individual.
- **Expected outcome:** Setting stored; `HouseholdSettingsChangedEvent` published → Planner reconfigures: shared slots planned once against the union+merge with headcount scaling; individual slots planned per person against each member's own models.
- **Variations:** all slots shared; all slots individual (degenerate household = parallel single-user planning); mixed (dinners shared, lunches individual); change an existing slot's sharing mid-plan (reconfiguration of remaining days).
- **HLD ref:** system-overview §Household Model ("Household settings define which meals are shared vs individual"); tech-arch §Event catalogue (`HouseholdSettingsChangedEvent`).
- **Notes:** Cross-module: Planner (slot reconfiguration). Core of the flagship journey (HH-24).

#### HH-12 — Set the eater set for a shared slot
- **Category:** Alternate
- **Actor:** Primary user
- **Preconditions:** An ACTIVE household; a slot marked shared.
- **Action:** Choose which members eat that shared slot.
- **Expected outcome:** The union (hard constraints) and the merge (soft prefs) for that slot are computed over exactly the chosen eaters; headcount derives from the eater set.
- **Variations:** all members eat the slot; a subset eats it; one member opts out (eating out — HH-13); eater set of one (degenerates to an individual meal for that person).
- **HLD ref:** system-overview §Household Model (union is over "all eaters'"); preference-model §How It Gets Used (Household Model).
- **Notes:** `[HLD-GAP]` — per-slot eater-assignment mechanism is implied (the union must be over *some* eater set) but never described. Cross-module: Hard Constraint Filter (`checkForHousehold(userIds, …)`).

#### HH-13 — Mark a member as eating out / guest occasion for a slot
- **Category:** Alternate
- **Actor:** Primary user / Household member (for own slot?)
- **Preconditions:** A shared slot with a defined eater set.
- **Action:** Temporarily exclude a member from a shared slot (eating out), or add a guest.
- **Expected outcome:** That occurrence's union/merge/headcount recompute without the excluded member (or with the guest's constraints). `[HLD-GAP]` — preference-model explicitly defers "Guest/occasion overrides" and "marks a day as eating out" to **the Household Model design**, which does not exist; the mechanism is undefined.
- **Variations:** member eats out for one dinner; recurring eat-out (overlaps lifestyle `recurring_skips`, which is *per-member* not household); a guest with an unknown allergy (how is a guest's hard-constraint set captured? — unspecified).
- **HLD ref:** system-overview §Constraint resolution ("marks a day as eating out"); preference-model §Boundaries (Guest/occasion overrides → Household Model).
- **Notes:** Gap-heavy. Whether eating-out is a household setting or a per-member lifestyle field is itself ambiguous (preference-model puts `recurring_skips` per member). Cross-module: Planner (slot reconfig).

#### HH-14 — Set / adjust headcount for a shared slot
- **Category:** Happy
- **Actor:** Primary user
- **Preconditions:** A shared slot.
- **Action:** Set the number of portions for the slot (or let it derive from the eater set).
- **Expected outcome:** Portions scale per headcount; the planner schedules a single cook producing that many servings (interacts with recipe `servings` / batch-cooking).
- **Variations:** headcount = eater count; headcount > eaters (cook extra for leftovers/batch); headcount changes after a member is added/removed; per-member `portion_scale` (children) factored into total portions `[HLD-GAP]` (whether headcount is raw bodies or sum of portion_scales is unspecified).
- **HLD ref:** system-overview §Household Model ("Portions scale per meal based on headcount"); recipe-system §Recipe properties (servings/batch).
- **Notes:** Cross-module: Planner (portion math), Recipe (servings/stores-well/packable). `[HLD-GAP]` — child portion_scale vs integer headcount interaction.

### Provisions sharing

#### HH-15 — Manage shared Provisions (primary)
- **Category:** Happy
- **Actor:** Primary user
- **Preconditions:** An ACTIVE household.
- **Action:** Edit the shared pantry/equipment/budget/environment.
- **Expected outcome:** One shared Provisions state for the whole household; changes affect every member's shared-slot planning. (Provisions events — `ProvisionChangedEvent` etc. — are owned by the Provisions domain.)
- **Variations:** edit shared pantry; set shared budget (one budget for the household — `[HLD-GAP]` per-member vs household budget split unspecified); set shared equipment; set kitchen environment (`household_environments`).
- **HLD ref:** system-overview §Household Model ("Provisions … shared per household"; "Primary user manages provisions"); tech-arch §Database (`household_environments`), §Who-injects-what (Household reads Provisions).
- **Notes:** Cross-module: Provisions (shared inventory/budget). Provisions pathways live in `provisions.md`; here the assertion is "shared scope + primary-only write."

#### HH-16 — Member views shared Provisions
- **Category:** Alternate
- **Actor:** Household member (ordinary)
- **Preconditions:** An ACTIVE household with shared Provisions.
- **Action:** Open the shared pantry/inventory view.
- **Expected outcome:** Member can see the shared state (read). `[HLD-GAP]` — member *read* access to shared Provisions is not stated (only that the primary *manages* it). Whether ordinary members can read but not write is implied-but-unspecified.
- **Variations:** member reads; member attempts a write (→ HH-08 unauthorized); member reads while primary is concurrently editing (stale read).
- **HLD ref:** system-overview §Household Model.
- **Notes:** Read-visibility gap. Cross-module: Provisions.

### Per-member models (referenced)

#### HH-17 — Each member configures their own Preference + Nutrition models
- **Category:** Happy
- **Actor:** Household member (each, on their own account)
- **Preconditions:** Member of an ACTIVE household.
- **Action:** A member sets their own hard constraints, taste profile, lifestyle config, nutrition targets, eating window.
- **Expected outcome:** The model is owned by that member alone; it feeds *individual* slots directly and *shared* slots via the union/merge. No member's model is editable by another member (hard constraints are user-only even against AI). 
- **Variations:** member sets a strict dietary identity (tightens every shared slot they eat); member sets an eating window that differs from another member's (per-member; only matters for that member's slots); member with **no nutrition model** configured (valid — planner ignores nutrition for them); member overrides an AI-inferred preference.
- **HLD ref:** system-overview §Household Model ("each user has their own … Preference Model, and Nutrition Model"); preference-model §Tiers; nutrition-model §Bootstrapping (skip nutrition is valid).
- **Notes:** Cross-module: Preference, Nutrition (both per-member, detailed in their own domain files). The Household domain only *references* these.

#### HH-18 — Tag a preference as individual-only vs household-suitable
- **Category:** Alternate
- **Actor:** Household member / Primary user (own profile) / AI (inferred)
- **Preconditions:** A member with a taste profile.
- **Action:** Mark a preference `individual_only` (e.g. very spicy dishes, blue cheese) or tag a recipe `suitable_for: household` vs `individual`.
- **Expected outcome:** The planner uses these tags to keep individual-only foods out of shared slots and steer them into that member's individual slots; household-suitable items are eligible for the merge.
- **Variations:** `individual_only_preferences` (spicy, blue cheese) excluded from shared slots; a recipe tagged `suitable_for: individual` not offered to the household; the `household_suitable_notes` ("keep spice mild and broadly accessible") steering shared-slot scoring.
- **HLD ref:** preference-model §Tier 2 (`household_context`), §Design notes (Household context tags: "distinguish foods I enjoy alone from foods that work for the family").
- **Notes:** Cross-module: Preference (taste profile tags), Planner (uses tags for shared vs individual fills). Feeds the soft-preference merge (HH-19).

### The merge

#### HH-19 — Compute the hard-constraint union for a shared slot
- **Category:** Happy
- **Actor:** Hard Constraint Filter (deterministic) ← invoked by Planner
- **Preconditions:** A shared slot with ≥2 eaters, each with hard constraints.
- **Action:** Compute `checkForHousehold(eaterIds, ingredients)` — the union (most restrictive) of all eaters' allergies + dietary identities + severe intolerances + age restrictions.
- **Expected outcome:** Every shared-slot output is filtered against the combined set deterministically; an ingredient any single eater is allergic to is excluded for the whole slot; the strictest dietary identity applies (e.g. one vegetarian → no meat in the shared dish). Code, never AI.
- **Variations:** disjoint allergies (union = sum); overlapping allergies (union = set merge, no double-count); one vegetarian + others omnivore (shared dish vegetarian); a child age-restriction folding in; an ambiguous item (lactose-free exception) → flag-and-ask, never silently pass.
- **HLD ref:** preference-model §How It Gets Used (Household Model: "computes the union (most restrictive) across all eaters"); tech-arch §Hard Constraint Filter (`checkForHousehold`, enforcement rules, the open question on ambiguous items).
- **Notes:** Cross-module: Hard Constraint Filter (preference module), Planner (caller). Safety-critical. The union is the **one fully-specified** piece of the household merge.

#### HH-20 — Merge soft preferences across eaters for a shared slot
- **Category:** Happy (but spec-incomplete)
- **Actor:** Planner (consumer) over multiple members' taste profiles
- **Preconditions:** A shared slot with ≥2 eaters whose hard-constraint union is satisfiable.
- **Action:** Reconcile multiple taste profiles (likes/dislikes, cuisines, evidence-weighted favourites, individual-only tags) into a single scoring input for the shared dish.
- **Expected outcome:** A merged soft-preference signal the planner scores against — favouring broadly-liked, household-suitable options and avoiding any eater's strong dislikes. **`[HLD-GAP]` — the merge algorithm (weighting across members, how to resolve A-likes-X / B-dislikes-X, whether evidence_count weights between people, whether children weight less) is explicitly deferred to "the Household Model design" which does not exist.** Only the *direction* ("most restrictive for hard, distinguish individual-vs-household for soft") is given.
- **Variations:** all eaters like the same cuisine (easy merge); one eater dislikes an ingredient another favours (conflict — resolution unspecified); evidence-weighted favourite of one vs weak preference of another; child high-volatility prefs vs adult stable prefs (relative weight unspecified); a dislike that is individual-only vs household-wide.
- **HLD ref:** preference-model §How It Gets Used (Household Model: "Full merge logic (weighting, conflict resolution) is defined in the Household Model design"); preference-model §Design notes (Household context tags).
- **Notes:** **The central gap of this domain.** Cross-module: Preference (per-member profiles), Planner (scoring). Test can only assert weak invariants (no eater's hard constraint violated; an item every eater dislikes is not chosen); the conflict-weighting is untestable until specified.

#### HH-21 — Irreconcilable shared slot → planner proposes a split
- **Category:** Error / Alternate
- **Actor:** Planner constraint resolution ← Household union
- **Preconditions:** A shared slot whose hard-constraint union has a near-zero viable recipe intersection (e.g. one member vegan, another keto).
- **Action:** Planner runs the constraint-feasibility check; detects a household hard-constraint collision.
- **Expected outcome:** Hard constraints are **never relaxed**; instead the planner proposes **splitting the shared slot into individual meals** and re-plans each affected person independently for that slot (state SHARED → SPLIT). The user chooses; the planner never silently degrades.
- **Variations:** vegan + keto (classic collision → split); allergy that eliminates the only shared protein source (split or substitute); collision resolvable by a creative augmentation vs requiring a full split; a 3-member household where 2 are compatible and 1 collides (split off only the one).
- **HLD ref:** system-overview §Constraint resolution (Household hard constraint collision → "splitting the meal slot into individual meals, not relaxing either person's dietary identity"); §Household Model.
- **Notes:** Cross-module: Planner (constraint resolution — detailed in `planner.md`). The trigger (irreconcilable union) is owned here; the resolution mechanics in the planner. This is where the merge hands off.

### Feedback (role-scoped)

#### HH-22 — Member gives feedback on their own meal
- **Category:** Happy
- **Actor:** Household member (ordinary)
- **Preconditions:** A member who ate a meal (shared or individual).
- **Action:** Submit feedback on that meal.
- **Expected outcome:** Feedback accepted and routed through the normal Feedback System (to preference/nutrition/provisions/recipe) — scoped to **that member's** own models (their taste profile, their nutrition). For a *shared* meal, feedback affecting shared resources (e.g. "too expensive" → shared Provisions) vs personal taste (their own preference) `[HLD-GAP]` (how one eater's feedback on a shared dish propagates — does it update only their profile, or the shared-slot merge? — unspecified).
- **Variations:** feedback on an individual meal (own preference/nutrition); feedback on a shared meal routing to shared Provisions (cost/availability — affects everyone); feedback on a shared meal routing to own taste profile (affects only that member's future weighting); a child's feedback (high volatility → weaker signal).
- **HLD ref:** system-overview §Household Model ("household members can give feedback on their own meals"); feedback-system (routing).
- **Notes:** Cross-module: Feedback System (routing), Preference/Nutrition/Provisions. `[HLD-GAP]` — shared-meal feedback propagation across members is undefined.

#### HH-23 — Member attempts feedback on another member's individual meal
- **Category:** Error
- **Actor:** Household member
- **Preconditions:** Two members; the actor did not eat the target individual meal.
- **Action:** Attempt to give feedback on someone else's individual meal.
- **Expected outcome:** Disallowed — the role rule scopes members to "their own meals". `[HLD-GAP]` — the rule is stated but enforcement (and what "own meal" means for a shared dish two people ate) is not specified.
- **Variations:** feedback on another's individual lunch (disallowed); feedback on a shared dinner both ate (allowed — both are eaters); primary giving feedback on a member's individual meal (does management authority extend to this? — unspecified).
- **HLD ref:** system-overview §Household Model.
- **Notes:** Permission-boundary pathway; ties to the "own meals" scope gap.

### Flagship cross-module journey

#### HH-24 — Two-member household → shared dinner with conflicting constraints → union + merge feed the planner → irreconcilable slot splits → each member re-planned and rated independently
- **Category:** Happy (flagship end-to-end, spanning the household merge → planner handoff)
- **Actor:** Primary user (+ a second member; Hard Constraint Filter, Planner, Notification as system/downstream actors)
- **Preconditions:** Authenticated primary user; a second registered account; shared Provisions established.
- **Action (sequence):**
  1. Primary **creates a household** (`HouseholdCreatedEvent`) and **adds the second member** (`HouseholdMemberAddedEvent`).
  2. Each member configures **their own Preference + Nutrition models** — member A vegetarian (dietary identity hard constraint), member B with a peanut allergy; A tags "very spicy" as `individual_only`.
  3. Primary **marks weekday dinner as a shared slot** with both as eaters, headcount 2 (`HouseholdSettingsChangedEvent`).
  4. At plan time the **Hard Constraint Filter computes the union** for the shared dinner — vegetarian ∧ peanut-free *(cross-module: Preference / Hard Constraint Filter)* — and the **soft-preference merge** runs over both taste profiles, excluding A's individual-only spicy preference *(cross-module: Preference; merge weighting is `[HLD-GAP]`)*.
  5. The planner composes a shared, vegetarian, peanut-free, mild dinner scaled to headcount 2 *(cross-module: Planner scoring + portion scaling)*.
  6. The primary then **tightens member B's constraints** so the union becomes irreconcilable (e.g. B adopts strict keto while A stays vegetarian) → the planner's **constraint resolution detects the collision and proposes splitting** the slot into two individual meals (SHARED → SPLIT); hard constraints are never relaxed *(cross-module: Planner constraint resolution)*.
  7. Each member is **re-planned independently** for that slot against their own models; later each **gives feedback only on their own meal**, routing to their own preference/nutrition (and any cost feedback to the shared Provisions) *(cross-module: Feedback, Preference/Nutrition, shared Provisions)*.
- **Expected outcome:** A household with two members and per-member models; a shared dinner correctly filtered by the union and shaped by the merge while satisfiable; on conflict, a clean split into individual meals (no hard constraint ever relaxed); per-member re-plans and per-member feedback scoping. Assertions span household roster + per-slot sharing state + the union result + the split outcome + per-member feedback routing.
- **Variations:** disjoint vs overlapping allergies at step 4; satisfiable union (no split) vs irreconcilable (split) at step 6; add a third (child) member with age restrictions mid-journey; member removed at step 6 instead of constrained (union relaxes rather than splits); shared-meal cost feedback hitting shared Provisions vs taste feedback hitting one profile.
- **HLD ref:** system-overview §Household Model, §Constraint resolution; preference-model §How It Gets Used (Household Model), §household_context; tech-arch §Hard Constraint Filter (`checkForHousehold`), §Event catalogue (household events); nutrition-model §Bootstrapping (per-member, skippable).
- **Notes:** CROSS-MODULE backbone of this domain. The **union (step 4a)** is fully specified and deterministic; the **soft-merge weighting (step 4b)** is the domain's central `[HLD-GAP]`; the **split (step 6)** is owned by the Planner's constraint resolution and detailed in `planner.md` + the cross-journey file. This journey is the primary candidate for the cross-journeys synthesis (Household ↔ Preference ↔ Planner ↔ Feedback ↔ Provisions). Assert per-member scoping and the no-relaxation invariant, not the (unspecified) merge weights.

---

## Appendix — `[HLD-GAP]` findings (consolidated)

| # | Gap | Pathway |
|---|---|---|
| G1 | Whether a user may own/belong to more than one household; household attributes (name, etc.) beyond membership undefined. | HH-01, HH-04 |
| G2 | The member-add mechanism: direct primary-attach vs an invite/accept handshake (no invite/decline events exist — only Added/Removed). | HH-02, HH-05 |
| G3 | Whether a child member is a separate login or a managed sub-profile under the primary. | HH-03 |
| G4 | Fate of an ex-member's account + Preference/Nutrition models after removal. | HH-04 |
| G5 | The entire invitation/accept/decline flow (cross-account joining) is undescribed. | HH-05 |
| G6 | Role taxonomy beyond binary primary/ordinary; intermediate permissions undefined. | HH-06 |
| G7 | Primary-role transfer flow; whether a household allows multiple primaries or exactly one. | HH-07, HH-09 |
| G8 | Full set of primary-only (gated) actions and the enforcement boundary are never enumerated. | HH-08 |
| G9 | The "≥1 primary always" invariant is implied, not stated; behaviour on removing/demoting the last primary unspecified (block? auto-promote? orphan?). | HH-09 |
| G10 | No household/roster read interface or member-visibility rules (can a member see others' roster/models/preferences?). | HH-10, HH-16 |
| G11 | Per-slot eater-assignment mechanism (how the eater set for a shared slot is chosen) is implied but undescribed. | HH-12 |
| G12 | Guest/occasion + eating-out overrides explicitly deferred to a non-existent Household Model design; also ambiguous whether eating-out is household-level or per-member (`recurring_skips` is per-member). | HH-13 |
| G13 | How a guest's hard-constraint set is captured for a shared slot. | HH-13 |
| G14 | Whether headcount is raw body count or the sum of per-member `portion_scale`s (children). | HH-14 |
| G15 | Whether budget is one household budget or split per member. | HH-15 |
| G16 | Ordinary-member read access to shared Provisions (read-yes/write-no implied but unspecified). | HH-16 |
| G17 | **The soft-preference merge algorithm** (cross-member weighting, like/dislike conflict resolution, evidence-count weighting between people, child down-weighting) — explicitly deferred to a Household Model design that does not exist. **Central gap.** | HH-20, HH-24 |
| G18 | How one eater's feedback on a *shared* meal propagates — only their own profile, or the shared-slot merge / others' models. | HH-22 |
| G19 | Enforcement of the "own meals" feedback scope; what "own meal" means for a shared dish multiple members ate; whether the primary may feed back on a member's individual meal. | HH-23 |
| G20 | Effect on an in-flight shared plan of removing a member (or the sole eater of a slot) mid-week. | HH-04, HH-22 |
| G21 | Whether a single member may belong to two households simultaneously (auth links "household membership" — cardinality unstated). | §4.1 |
| G22 | No household dissolve/teardown transition described. | §4.1 |
