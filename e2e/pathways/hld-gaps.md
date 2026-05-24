# Aggregated HLD Gaps — Batch Triage List

> **Purpose.** Single deduplicated triage list of every `[HLD-GAP]` flagged during Stage-1 catalogue generation across all 10 domain pathway files. Per README §6/§8 (decision D8), gaps are aggregated here for **one batch review** rather than decided piecemeal. Each row needs a single user ruling; resolutions feed back into the HLDs and/or the frontend spec.
>
> **How to read sources.** Source ids are the originating per-domain gap ids, prefixed with the domain so the (colliding) per-file numbering is unambiguous: `recipe Gn`, `auth AGn`, `feedback FBn`, `nutrition Nn`, `notif Nn`, `pref GPn`, `groc GGn`, `prov GPn`, `household HHn`, `planner Pn`. (Preference and Provisions both numbered their appendices `GP1–GP25`; they are disambiguated here as `pref`/`prov`.)
>
> **Status:** Drafted 2026-05-24 from the consolidated appendices of recipe.md, auth.md, feedback.md, nutrition.md, notification.md, preference.md, grocery.md, provisions.md, household.md, planner.md.

---

## Summary

- **Raw flags across 10 domains:** ~217 (recipe 23, auth 23, feedback 18, nutrition 24, notification 24, preference 25, grocery 16, provisions 25, household 22, planner 17).
- **Unique gaps after dedup:** **88**
- **Merged cross-domain clusters:** 13 (collapsing ~40 raw flags into 13 entries; the rest are 1:1 carry-overs of domain-local gaps).

### Counts per type

| Type | Count |
|---|---|
| Safety | 6 |
| Cross-doc contradiction | 9 |
| Missing spec | 48 |
| Ambiguity | 16 |
| Missing feature | 9 |
| **Total** | **88** |

### Top 8 highest-impact (decide first)

1. **GAP-01** — Soft- *and* hard-preference household merge algorithm is entirely undefined (deferred to a non-existent Household Model). Blocks every shared-plan path.
2. **GAP-07** — Shopping-list calculation ownership contradiction (grocery vs planner) — two docs claim the logic; the frontend can't be specced against either.
3. **GAP-08** — No GDPR export **and** no erasure flow; erasure directly contradicts "no hard delete" + unbounded caches + undefined `user_id` cascade.
4. **GAP-02** — Slot wall-clock serve-time is unmodelled, yet defrost lead-times and prep reminders presuppose it.
5. **GAP-09** — Single-flight / concurrent-invocation rule unreconciled across recipe-loop, planner, and feedback (competing pending changes).
6. **GAP-10** — Supplier cost: scraped-from-feeds vs learned-from-actual-paid-prices contradiction (provisions vs system-overview).
7. **GAP-03** — Nutrition alerts are a named deliverable with no wired event source and no thresholds/timing.
8. **GAP-04** — Removing a safety-critical hard constraint has no confirmation/safety interstitial (only logged).

---

## Safety

| GAP | Description | Source(s) | Domains affected | Type | Decision needed |
|---|---|---|---|---|---|
| GAP-01 | Household soft- **and** hard-preference merge algorithm undefined (cross-member weighting, like/dislike & hard-constraint conflict resolution, evidence-count & child down-weighting) — deferred to a non-existent Household Model. | household HH17, household HH18, pref GP23 | Household, Preference, Planner | Safety | Define the merge/weighting algorithm (or scope it out of v1) — including how conflicting hard constraints across members combine for a shared slot. |
| GAP-02 | Slot wall-clock serve-time unmodelled (slots carry only a `time_budget_min` duration); defrost lead-times and prep reminders ("start marinating at 6pm") presuppose a scheduled meal-time that does not exist. | planner P16, notif N12, notif N13, prov GP23 | Planner, Notification, Provisions | Safety | Add a scheduled serve-time field + scheduling rule, and define behaviour when a defrost/prep lead-time window can't fit before the slot. |
| GAP-03 | Nutrition alerts are a named deliverable but have no wired event source (`NutritionIntakeDivergedEvent` targets the Planner, not Notification) and no thresholds/timing for how far under-floor / over-limit before alerting. | notif N17, nutrition N23 | Notification, Nutrition | Safety | Wire an event source for nutrition alerts and define the alert thresholds/timing. |
| GAP-04 | Removing a safety-critical hard constraint (e.g. an allergy) has no confirmation / safety-interstitial behaviour — only that the change is logged. | pref GP6 | Preference | Safety | Decide whether removing a Tier-1 hard constraint requires a confirmation/safety interstitial. |
| GAP-05 | Compound feedback mixing a **hard-constraint** (allergy) claim with routable feedback — how the split is handled (route the routable part, redirect/escalate the allergy part?). | feedback FB9 | Feedback, Preference | Safety | Define how a message containing a hard-constraint claim is split, escalated, and confirmed. |
| GAP-06 | Free-text nutrition logger override that is unparseable/unmappable has no garbage-handling rule (unlike the recipe engine), risking silent mis-logging of intake. | nutrition N11 | Nutrition | Safety | Define the failure/garbage path for an unparseable logged-meal override. |

---

## Cross-doc contradiction

| GAP | Description | Source(s) | Domains affected | Type | Decision needed |
|---|---|---|---|---|---|
| GAP-07 | Shopping-list calculation ownership contradiction: grocery.md says the planner produces it and grocery merely *exposes* it (yet details the 6-step calc); provision-model.md says it is "owned by the Planner module" with the implementation living there. Who owns the logic vs exposes the result is contradictory. | groc GG1, planner P2 | Grocery, Provisions, Planner | Cross-doc contradiction | Rule which module owns shopping-list calculation logic vs merely exposes the derived result. |
| GAP-08 | No GDPR data-export mechanism **and** no erasure flow; erasure directly contradicts Recipe Engine "no hard delete", the unbounded mapping cache + preference archive, and an undefined `user_id` cascade across every module (and the recipe "delete affordance maps to demote/archive" stance). | auth AG20, auth AG21, recipe G20 | Auth, Recipe, all modules | Cross-doc contradiction | Decide the export + erasure contract and reconcile it with "no hard delete" / cascade across modules. |
| GAP-09 | Single-flight / concurrent-invocation rule unreconciled: recipe-loop "single-flight reject" vs recipe-system "batch defer / feedback pre-empt" vs planner concurrent invocation; feedback adds competing recipe-feedback routes creating rival pending changes vs Recipe per-(recipe,dimension) supersession (RCP-47). | recipe G16, planner (recipe-loop vs planner), feedback FB17 | Recipe, Planner, Feedback | Cross-doc contradiction | Define one canonical single-flight/supersession rule covering recipe-loop, planner, and feedback concurrent invocations. |
| GAP-10 | Price-sourcing contradiction: primary provisions doc says the supplier cache is searched/scraped from products; system-overview says costs are learned from the user's actual paid prices, not scraped from feeds. | prov GP20 | Provisions, Grocery | Cross-doc contradiction | Rule whether supplier cost is scraped from feeds or learned from actual paid prices (or both, with precedence). |
| GAP-11 | Scoring contradiction: `nutrition_floor_gate` multiplies a floor-missing plan by 0, yet the "decline all resolutions" path surfaces a floor-missing partial plan — how a zero-scored plan is still produced/ranked is unreconciled. | planner P4 | Planner, Nutrition | Cross-doc contradiction | Reconcile the zero-gate vs surfaced-partial-plan paths. |
| GAP-12 | Classification-metrics scope contradiction: the interface is per-`userId` but the quality-monitoring framing reads system-wide ("90% of feedback to one destination"). | feedback FB10 | Feedback | Cross-doc contradiction | Decide whether classification metrics are per-user or system-wide. |
| GAP-13 | Canonical store for dietary identity at onboarding is ambiguous across docs — it appears in both the Tier-1 hard-constraint table and the Day-1 lifestyle/bootstrap step. | pref GP4 | Preference | Cross-doc contradiction | Name the single canonical store for dietary identity. |
| GAP-14 | Two distinct over-budget triggers — 3 consecutive weeks vs a rolling-4-week average — are not reconciled. | prov GP18 | Provisions | Cross-doc contradiction | Pick the canonical over-budget trigger (or define how both compose). |
| GAP-15 | AI-mediated cost complaint: tension between directly mutating budget vs proposing a change (propose/accept vs no-approval-override) is unresolved. | prov GP19 | Provisions | Cross-doc contradiction | Decide whether a cost complaint mutates budget directly or proposes a change for approval. |

---

## Missing spec

| GAP | Description | Source(s) | Domains affected | Type | Decision needed |
|---|---|---|---|---|---|
| GAP-16 | Mandatory recipe fields & validation rules never enumerated. | recipe G1 | Recipe | Missing spec | Enumerate required recipe fields and their validation rules. |
| GAP-17 | Image file size/format constraints unspecified. | recipe G4 | Recipe | Missing spec | Define accepted image sizes/formats (or none). |
| GAP-18 | Partial-batch semantics for quick-start import (does one bad URL abort the batch?) unstated. | recipe G3 | Recipe | Missing spec | Define per-URL independence vs batch abort for quick-start import. |
| GAP-19 | Exact filterable fields & pagination for the recipe index/search unspecified. | recipe G7 | Recipe | Missing spec | Enumerate filterable fields and pagination contract. |
| GAP-20 | No registration validation rules at all (password policy, username format/length, allowed chars). | auth AG1 | Auth | Missing spec | Define registration validation rules. |
| GAP-21 | Username uniqueness + case-folding/whitespace normalisation rule (no `normaliseKey()` analogue for usernames). | auth AG5 | Auth | Missing spec | Define username uniqueness & normalisation. |
| GAP-22 | Credential mechanism left unchosen — session-cookie vs JWT-in-httpOnly-cookie (HLD offers both, picks neither). | auth AG6 | Auth | Missing spec | Choose the credential mechanism. |
| GAP-23 | No failed-login lockout / throttling policy. | auth AG7 | Auth | Missing spec | Define lockout/throttling policy. |
| GAP-24 | No session lifetime/timeout policy (idle vs absolute), no refresh/rotation, no "remember me". | auth AG11 | Auth | Missing spec | Define session lifetime, rotation, and remember-me. |
| GAP-25 | Public-vs-protected route boundary never enumerated (which actions require auth). | auth AG12 | Auth | Missing spec | Enumerate the public/protected route boundary. |
| GAP-26 | Cross-user denial rule only implied by server-side `userId` resolution; intra-household read scope undefined. | auth AG13 | Auth, Household | Missing spec | State the cross-user denial rule and intra-household read scope. |
| GAP-27 | No account-read endpoint or response shape defined despite the stated model linkage. | auth AG15 | Auth | Missing spec | Define the account-read interface/shape. |
| GAP-28 | Confidence-gate boundary semantics for feedback: per-classification vs whole-response; which band owns the exact endpoints (0.5, 0.8); how a mixed-confidence multi-destination response is handled. | feedback FB5 | Feedback | Missing spec | Pin down confidence-gate scope, endpoint ownership, and mixed-confidence handling. |
| GAP-29 | User-facing feedback **input validation** entirely unspecified: empty/whitespace rejection, min/max length, behaviour at the 5,000-token classifier cap (truncate vs reject), whether the context object is mandatory. | feedback FB8 | Feedback | Missing spec | Define feedback input validation and token-cap handling. |
| GAP-30 | `FeedbackProcessedEvent` payload semantics on partial failure (only succeeded destinations?) and whether a `clarification_needed` outcome emits an event at all. | feedback FB16 | Feedback, Notification | Missing spec | Define event payload on partial failure and clarification outcomes. |
| GAP-31 | Target validation bounds (min/max plausible values) and required-vs-optional target fields never enumerated. | nutrition N2 | Nutrition | Missing spec | Enumerate nutrition target bounds and required fields. |
| GAP-32 | Behaviour when a `daily_floor` is attached to a `weekly_average` macro, or floor > target, or tolerance wider than the target. | nutrition N3 | Nutrition | Missing spec | Define legality/handling of these target combinations. |
| GAP-33 | Whether per-meal distribution must reconcile (sum) to the daily total. | nutrition N4 | Nutrition | Missing spec | Decide whether per-meal distribution must sum to the daily total. |
| GAP-34 | Exact USDA-match confidence threshold for needs-review unnamed in nutrition-model.md (recipe-system uses 0.7). | nutrition N14 | Nutrition, Recipe | Missing spec | Name the USDA needs-review threshold (align with recipe's 0.7?). |
| GAP-35 | Behaviour when the USDA/OFF API is unreachable mid-mapping (caching reduces calls but the failure path is absent). | nutrition N15 | Nutrition | Missing spec | Define the API-unreachable failure path. |
| GAP-36 | No fallback (manual macro entry) for a standalone food with no USDA/OFF match. | nutrition N12 | Nutrition | Missing spec | Define a manual-entry fallback for unmatched foods. |
| GAP-37 | Health-platform export trigger/cadence/consent mechanics ("what" is stated, "when/how" absent). | nutrition N20 | Nutrition, Auth | Missing spec | Define export trigger, cadence, and consent. |
| GAP-38 | "Recent" notifications: list window (count cap / age cutoff), sort order, exact fields, and pagination never defined. | notif N1, notif N22 | Notification | Missing spec | Define the notifications list contract (window, order, fields, pagination). |
| GAP-39 | No retention / aging / archival rule for notifications (nothing ever removes one). | notif N2 | Notification | Missing spec | Define notification retention/aging. |
| GAP-40 | Per-user notification ownership/scoping never stated (multi-user isolation rule). | notif N4 | Notification, Auth | Missing spec | State per-user notification scoping. |
| GAP-41 | Scanner cadence/cron for each scanner (expiry, defrost, prep, staple) not stated; default expiry thresholds not stated. | notif N8, notif N9 | Notification, Provisions | Missing spec | Define scanner cadences and expiry thresholds. |
| GAP-42 | Per-item vs coalesced emission when a scan finds many eligible items — unspecified. | notif N10 | Notification | Missing spec | Decide per-item vs coalesced emission. |
| GAP-43 | Re-emission policy across ticks (re-nag a still-eligible item vs suppress-once) — unspecified. | notif N11 | Notification | Missing spec | Define re-emission/suppression across ticks. |
| GAP-44 | Whether failed notifications are retried (Modulith replay) or dropped — not stated for the notification path. | notif N21 | Notification | Missing spec | Define notification delivery retry vs drop. |
| GAP-45 | Concrete default values for lifestyle config (deferred until all three data-model designs complete). | pref GP3 | Preference | Missing spec | Provide lifestyle config defaults. |
| GAP-46 | How long a manual-override flag suppresses AI re-learning from old feedback. | pref GP11 | Preference | Missing spec | Define the manual-override suppression window. |
| GAP-47 | Fallback when the AI's whole preference-delta response is malformed/unparseable (no retry/fallback unlike optimisation-loop Stage C). | pref GP13 | Preference | Missing spec | Define the malformed-delta fallback. |
| GAP-48 | Fallback when a single update cannot bring the profile under the ~2500-token budget even after archiving. | pref GP25 | Preference | Missing spec | Define the token-budget-exceeded fallback. |
| GAP-49 | Manual-add provisions required/optional fields and validation rules never enumerated. | prov GP1 | Provisions | Missing spec | Enumerate manual-add fields and validation. |
| GAP-50 | Behaviour when an ordered item has no category expiry default (expiry unset?). | prov GP3 | Provisions | Missing spec | Define expiry when no category default exists. |
| GAP-51 | Staple status transitions (e.g. stocked→out direct jumps) have no illegal-transition rules; the staples auto-promotion trigger/threshold from grocery orders is undefined. | prov GP11, prov GP12 | Provisions | Missing spec | Define staple state transitions and auto-promotion threshold. |
| GAP-52 | `tolerance_over` validation (negative? bounded?) not specified. | prov GP17 | Provisions | Missing spec | Define tolerance_over validation. |
| GAP-53 | Data volumes / pagination for inventory and other Provisions lists unspecified. | prov GP14 | Provisions | Missing spec | Define provisions list pagination. |
| GAP-54 | Currency / rounding / precision of cost projections deferred to LLD — affects every money assertion. | groc GG16 | Grocery, Provisions | Missing spec | Define currency/rounding/precision for money values. |
| GAP-55 | No stated confirmation-window timeout for an order left in `awaiting_user_confirmation`. | groc GG8 | Grocery | Missing spec | Define the order confirmation-window timeout. |
| GAP-56 | Provider retry-policy specifics (cadence, max attempts, backoff) for `provider_unavailable` deferred to LLD and not reconciled per-failure-class. | groc GG11 | Grocery | Missing spec | Define provider retry policy. |
| GAP-57 | Concurrency between user manual edits to a draft basket and automation runs explicitly deferred to LLD — no behavioural contract for the frontend yet. | groc GG15 | Grocery | Missing spec | Define draft-basket-vs-automation concurrency contract. |
| GAP-58 | Planning-cadence configuration surface/granularity ("defaults to weekly but configurable") never specified; `max_repeat`/scoring params have defaults but no config surface. | planner P1, planner P15 | Planner | Missing spec | Define the planning-cadence + scoring-param configuration surface. |
| GAP-59 | The legal/illegal `MealSlot` state-transition set (and any un-marking/backward transition) is never enumerated — only forward order + immutability prose. | planner P9 | Planner | Missing spec | Enumerate the MealSlot state-transition set. |
| GAP-60 | No concurrency/locking model for the Nutrition domain (multi-device target edits, directive-vs-manual collisions, racing logger writes) — unlike Recipe's optimistic-lock + rebase. | nutrition N24 | Nutrition | Missing spec | Define the Nutrition concurrency/locking model. |
| GAP-61 | Price-history concurrency for concurrent multi-user writes not addressed (shared-inventory concurrency is an Open Question; price history isn't covered at all). | groc GG14, prov GP25 | Grocery, Provisions, Household | Missing spec | Define price-history and shared-inventory concurrency. |
| GAP-62 | How a guest's hard-constraint set is captured for a shared slot. | household HH13 | Household | Missing spec | Define guest hard-constraint capture. |
| GAP-63 | Full set of primary-only (gated) actions and the enforcement boundary never enumerated. | household HH8 | Household | Missing spec | Enumerate primary-only gated actions. |

---

## Ambiguity

| GAP | Description | Source(s) | Domains affected | Type | Decision needed |
|---|---|---|---|---|---|
| GAP-64 | Reachable-but-non-recipe page not classified (sits between "unreachable" fail-fast and "garbage" store-with-needs_review). | recipe G2 | Recipe | Ambiguity | Classify the reachable-but-non-recipe import case. |
| GAP-65 | Visibility of system-catalogue recipes to a normal user read/query unspecified. | recipe G6 | Recipe | Ambiguity | Define system-catalogue visibility to users. |
| GAP-66 | Cross-branch version diff/revert legality unstated. | recipe G8 | Recipe | Ambiguity | Define cross-branch diff/revert legality. |
| GAP-67 | Whether a character-breaking *manual* edit is allowed to remain a version (not gated by the version-vs-branch classifier). | recipe G9 | Recipe | Ambiguity | Decide whether manual edits can stay versions. |
| GAP-68 | Revert mechanism ambiguous: new version pointer vs moving a "current" marker. | recipe G11 | Recipe | Ambiguity | Define the revert mechanism. |
| GAP-69 | How multiple ratings combine into score/count (mean? recency-weighted?). | recipe G21 | Recipe | Ambiguity | Define rating aggregation. |
| GAP-70 | Username-enumeration policy on login errors (identical vs distinct messages for wrong-password vs unknown-user). | auth AG8 | Auth | Ambiguity | Decide login error message uniformity. |
| GAP-71 | Logout edge behaviour: logout-with-no-session (idempotent vs error); global vs per-device; concurrent sessions per user. | auth AG10 | Auth | Ambiguity | Define logout edge cases and session model. |
| GAP-72 | Tie-break when a strong screen-context signal contradicts a strong text signal (context is "a signal not a constraint," no weighting rule). | feedback FB2 | Feedback | Ambiguity | Define context-vs-text tie-break weighting. |
| GAP-73 | Whether two classifications in one feedback response may target the **same** destination (two slices → same module). | feedback FB3 | Feedback | Ambiguity | Decide whether same-destination duplicate routes are allowed. |
| GAP-74 | Tie-at-deadline experiment resolution (evidence_for == evidence_against at the 4-week limit — promote or discard?). | pref GP24 | Preference | Ambiguity | Define the experiment tie-break at deadline. |
| GAP-75 | Exact friction difference between overriding an `inferred` vs an `explicit` preference ("easier" asserted but unquantified). | pref GP10 | Preference | Ambiguity | Quantify the inferred-vs-explicit override friction. |
| GAP-76 | What signal/threshold counts as "consistently contradicts" for behavioural-drift detection, and which subsystem owns drift detection (Preference vs Feedback vs Planner). | pref GP21 | Preference, Feedback, Planner | Ambiguity | Define drift threshold and owning subsystem. |
| GAP-77 | The "if material" threshold deciding whether under-marking triggers a planner re-optimisation is undefined. | groc GG5 | Grocery, Planner | Ambiguity | Define the "if material" re-opt threshold. |
| GAP-78 | Whether headcount is a raw body count or the sum of per-member `portion_scale`s; whether budget is one household budget or split per member. | household HH14, household HH15 | Household, Planner, Provisions | Ambiguity | Define headcount and budget semantics. |
| GAP-79 | Week-end completion when slots are still non-terminal (`planned`/`cooking`) — auto-skip, stay active, or flag? | planner P8 | Planner | Ambiguity | Define week-end handling of non-terminal slots. |

---

## Missing feature

| GAP | Description | Source(s) | Domains affected | Type | Decision needed |
|---|---|---|---|---|---|
| GAP-80 | No logout flow described anywhere. | auth AG9 | Auth | Missing feature | Decide whether v1 ships a logout flow. |
| GAP-81 | Password-change flow entirely absent; effect on existing sessions unspecified. | auth AG16 | Auth | Missing feature | Decide on password-change flow + session effect. |
| GAP-82 | No forgotten-password / account-recovery mechanism (no email infra; "local/self-hosted"). | auth AG17 | Auth | Missing feature | Decide on account recovery (or explicitly none). |
| GAP-83 | No account deactivation/deletion lifecycle; no account states beyond implicit ACTIVE. | auth AG18 | Auth | Missing feature | Define the account lifecycle/states. |
| GAP-84 | The entire household invitation/accept/decline flow (cross-account joining) is undescribed; member-add mechanism (direct attach vs handshake) undefined. | household HH2, household HH5 | Household, Auth | Missing feature | Decide the member-onboarding flow. |
| GAP-85 | Primary-role transfer flow; whether a household allows one or multiple primaries; behaviour on removing/demoting the last primary (block? auto-promote? orphan?). | household HH7, household HH9 | Household | Missing feature | Define primary-role transfer and the ≥1-primary invariant. |
| GAP-86 | No notification-preferences surface (mute categories, channels, opt-out) and no quiet-hours / do-not-disturb concept — acute for time-bound defrost/prep alerts. | notif N6, notif N7 | Notification | Missing feature | Decide on notification preferences + quiet hours. |
| GAP-87 | No dismiss/delete/"clear all" notification action — only "mark as read" exists (read ≠ removed); mark-read idempotency also unspecified. | notif N3, notif N5 | Notification | Missing feature | Decide on dismiss/clear actions and mark-read idempotency. |
| GAP-88 | Deep-link / "act on a notification" semantics (tapping to jump to the source screen) implied by copy but not specified. | notif N23 | Notification | Missing feature | Define notification deep-link/act-on semantics. |
