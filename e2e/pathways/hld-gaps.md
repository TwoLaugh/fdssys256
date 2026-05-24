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

---

## Recommended dispositions (for review)

> **Counts:** `HLD-clarify` 68 · `Fix-contradiction` 7 · `Product-decision` 8 · `Defer-to-backlog` 5 (total 88). Owner split: 80 `eng`, 8 `you`.
>
> **The 8 genuine product decisions (`Owner=you`) to focus on:** **GAP-04** (allergy-removal confirmation interstitial), **GAP-08** (GDPR export + erasure — legal), **GAP-22** (session-cookie vs JWT credential mechanism), **GAP-37** (health-platform export consent), **GAP-80** (ship a logout flow in v1?), **GAP-82** (account recovery, or explicitly none?), **GAP-84** (household member-onboarding flow), **GAP-86** (notification preferences + quiet hours). Everything else is `eng` (write-the-rule-down, pick-the-authoritative-doc, or park-it). The recommendations below give a sensible default for each `you` item — you can batch-accept and only stop on the ones you want to overrule.

| GAP | Disposition | Recommendation | Owner |
|---|---|---|---|
| GAP-01 | HLD-clarify | Ship v1 with shared slots respecting only the **union of hard constraints** (already specified in meal-planner §Household Integration) plus per-person priority-weighted mean of soft taste vectors (also already stated); defer the richer cross-member weighting/child-down-weighting algorithm. The "non-existent Household Model" is the gap — write the minimal merge rule into preference-model/meal-planner rather than waiting for a full Household HLD. | eng |
| GAP-02 | HLD-clarify | Add a `serve_time` (wall-clock) field to `MealSlot`, defaulted from Preference `meal_timing` within the Nutrition eating window; when a defrost/prep lead-time can't fit before `serve_time`, surface a notification + skip the auto-reminder rather than silently scheduling. Grounded in provision-model's `defrost_lead_time_hours` + preference `meal_timing`, which already presuppose a serve-time. | eng |
| GAP-03 | HLD-clarify | Wire nutrition alerts off the Nutrition Logger via a new `NutritionAlertEvent` to Notification (the existing `NutritionIntakeDivergedEvent` correctly targets the Planner — keep both); threshold = same ≥15% macro-variance the planner already uses for re-opt, plus "floor unmet with ≤1 meal left." Reuses the divergence threshold already in meal-planner. | eng |
| GAP-04 | Product-decision | Recommend **YES** — require a confirmation/safety interstitial when removing a Tier-1 hard constraint (allergy/dietary-identity/severe-intolerance), since the deterministic filter is the system's only allergy guardrail. Default to interstitial-on; your call whether to also add a re-confirm-after-24h. | you |
| GAP-05 | HLD-clarify | When feedback mixes a hard-constraint (allergy) claim with routable feedback: route the routable slice normally, and for the allergy slice **do not let AI write it** — surface an escalation prompt directing the user to the user-only hard-constraint edit flow and require explicit confirmation. This is already the rule in feedback-system §Boundaries ("'I'm now allergic to nuts' must go through the manual hard-constraint flow"); just make the split-handling explicit. | eng |
| GAP-06 | HLD-clarify | Mirror the recipe engine's garbage path: an unparseable logged-meal override is **not** silently logged — flag it `needs_review`, log zero-intake-pending, and prompt the user to confirm/manually enter, never guess macros. Grounds in nutrition-model's existing manual-override + needs-review patterns. | eng |
| GAP-07 | Fix-contradiction | **Planner owns the calculation logic; Grocery exposes/renders the result.** provision-model §"Shopping List Calculation" and technical-architecture Flow 1 both place the deterministic calc in the Planner; grocery.md's "first-class output exposed by the grocery module" is the rendering/surfacing role. Authoritative doc: provision-model + technical-architecture. Reword grocery.md to say "exposes," not "owns." | eng |
| GAP-08 | Product-decision | **Legal call — yours.** Recommended default for a local/self-hosted single-household app: ship a full **JSON/CSV export** (cheap, no contradictions) in v1; treat **erasure** as a documented manual DB-level operation for now (reconcile with "no hard delete" by mapping erasure → anonymise `user_id` + purge PII columns, keeping recipe/plan history). Revisit if ever multi-tenant/hosted. Confirm this satisfies your obligations. | you |
| GAP-09 | Fix-contradiction | Adopt the **Planner's single-flight rule as canonical**: one in-flight invocation per scope, second rejected, event-triggered re-opts enqueued behind a user-initiated run (meal-planner §Failure Modes states this explicitly). Recipe-loop's "single-flight reject" and feedback's per-(recipe,dimension) supersession (RCP-47) are consistent sub-cases of it. Authoritative doc: meal-planner.md. | eng |
| GAP-10 | Fix-contradiction | **Learned from actual paid prices, not scraped from feeds.** Both system-overview §"Data Model 3" and grocery.md §Tier 4 state costs are confidence-weighted aggregates of paid/quoted/manual prices; "scraped from supplier feeds" is explicitly disavowed (grocery.md §Out of Scope: "does not try to scrape supermarket deal feeds"). Authoritative doc: grocery.md + system-overview. The provisions "searched/scraped" wording is the loser — reword to "quote-refreshed." | eng |
| GAP-11 | Fix-contradiction | No real contradiction once staged: `nutrition_floor_gate × 0` kills a candidate **inside Stage-A scoring/beam search**; the floor-missing partial plan is produced **outside** scoring by the Constraint Feasibility Check's "user declined all resolutions" path, flagged `quality_warning: true`. meal-planner.md already separates these. Write the rule: zero-gate suppresses ranking, not the explicit quality-warned fallback. Authoritative doc: meal-planner.md. | eng |
| GAP-12 | Fix-contradiction | **Per-`userId`** is canonical (matches the `ClassificationMetrics getClassificationMetrics(Long userId, …)` interface in feedback-system). The "90% to one destination" framing is a per-user health signal too, just described loosely. Reword the quality-monitoring prose to scope metrics per-user (system-wide rollups are an optional aggregate). Authoritative doc: feedback-system.md. | eng |
| GAP-13 | Fix-contradiction | **Preference Model's Tier-1 hard-constraints table is the single canonical store** for dietary identity. nutrition-model and preference-model both state Nutrition only *consumes* it as input. The Day-1 onboarding step *collects* it but writes to the hard-constraints tier — collection point ≠ store. Authoritative doc: preference-model.md. | eng |
| GAP-14 | Fix-contradiction | Both compose, not compete: the **3-consecutive-weeks-over-(target+tolerance)** rule is the *prompt/alert trigger* (provision-model §Guardrails); the **rolling-4-week average** is the *displayed metric/context* feeding that prompt. Keep both with that division of labour. Authoritative doc: provision-model.md (both already live there). | eng |
| GAP-15 | HLD-clarify | A cost complaint **proposes**, never directly mutates the budget — consistent with the system-wide "propose, not apply" principle (Recipe Optimiser user recipes, health directives). feedback-system already says cost feedback "may prompt a budget review." Make it an explicit propose/accept. | eng |
| GAP-16 | HLD-clarify | Enumerate required recipe fields (name, ≥1 ingredient with quantity+unit, ≥1 method step, servings) + validation; everything else optional. recipe-system already implies the structure — just write the mandatory subset down. | eng |
| GAP-17 | HLD-clarify | Define accepted image formats (JPEG/PNG/WebP) and a size cap (e.g. ≤5MB, server-resized); recipe/02a image storage already exists in the build, so codify what it accepts. | eng |
| GAP-18 | HLD-clarify | Per-URL independence — one bad URL does **not** abort the quick-start batch; failed URLs are reported individually and the rest import. Matches the system's pervasive partial-failure-forward stance (grocery, feedback). | eng |
| GAP-19 | HLD-clarify | Enumerate filterable fields (cuisine, maxTime, catalogue, tags, rating) + standard pagination envelope (`page/size/totalElements/sort`) per technical-architecture §"Paginated lists" — the infra/01b pagination audit already standardised this. | eng |
| GAP-20 | HLD-clarify | Define registration validation: username 3–32 chars `[a-z0-9_]` (post-normalise), password ≥10 chars with a basic strength check; reject otherwise with 400. Pure write-it-down. | eng |
| GAP-21 | HLD-clarify | Add a `normaliseKey()`-analogue for usernames: lowercase + trim + collapse internal whitespace, uniqueness enforced on the normalised form. Mirrors the existing ingredient-key normalisation rule in technical-architecture. | eng |
| GAP-22 | Product-decision | Recommend **session-cookie auth** (Spring Security default) — technical-architecture calls it "simpler for a web app," TanStack Query needs no token handling, and there's no mobile/3rd-party-API consumer in v1. Your call if you anticipate native mobile soon (then JWT-in-httpOnly-cookie). | you |
| GAP-23 | HLD-clarify | Define lockout: throttle after 5 failed logins/user/15min (Resilience4j `@RateLimiter`, already the chosen resilience lib), exponential backoff, no permanent lockout for a self-hosted family app. | eng |
| GAP-24 | HLD-clarify | Define: 30-day absolute + 7-day idle session lifetime, rotate session id on login, "remember me" extends idle to 30 days. Sensible self-hosted defaults; write them down. | eng |
| GAP-25 | HLD-clarify | Enumerate the public routes (register, login, health) — everything under `/api/v1/**` else is protected. Trivial to state from the existing API surface in technical-architecture. | eng |
| GAP-26 | HLD-clarify | State the rule: every query service resolves `userId` server-side and denies cross-user reads; intra-household read scope = shared Provisions + shared plan slots only, never another member's Preference/Nutrition model. Grounded in system-overview §Household + technical-architecture §Frontend-Backend Contract. | eng |
| GAP-27 | HLD-clarify | Define `GET /api/v1/account` returning `{username, householdId, role, createdAt}` (no secrets). Fills the stated model linkage; pure spec. | eng |
| GAP-28 | HLD-clarify | Confidence gate is **per-classification** (each routed slice carries its own confidence; bands 0.5/0.8 already defined in feedback-system); endpoint ownership: ≥0.8 auto, [0.5,0.8) auto+flag, <0.5 clarify — make 0.8 and 0.5 inclusive-lower. A mixed-confidence multi-destination response routes each slice independently by its own band. Authoritative: feedback-system §Confidence handling. | eng |
| GAP-29 | HLD-clarify | Define feedback input validation: reject empty/whitespace (400), max 2,000 chars pre-classifier, **truncate** (not reject) at the 5,000-token classifier cap with a logged warning, context object optional (screen context is "a signal not a constraint"). | eng |
| GAP-30 | HLD-clarify | `FeedbackProcessedEvent` payload carries only succeeded destinations + a `partialFailure` flag; a `clarification_needed` outcome does **not** emit the event (nothing was routed yet). Consistent with the one-event-per-entry rule in technical-architecture §Event debouncing. | eng |
| GAP-31 | HLD-clarify | Enumerate target bounds (e.g. calories 800–6000, protein 0–400g, sane micro ceilings) + required fields (calorie target or explicit skip); reject out-of-range with 400/422. Write-it-down from nutrition-model's existing shapes. | eng |
| GAP-32 | HLD-clarify | Define legality: a `daily_floor` on a `weekly_average` macro is legal (floor still enforced daily); floor > target is illegal (422); tolerance wider than target clamps to target. Grounded in nutrition-model's `enforcement` semantics. | eng |
| GAP-33 | HLD-clarify | Per-meal distribution need **not** sum exactly to the daily total — nutrition-model already calls them "guidelines, not hard constraints"; the planner reconciles at day level. State that the per-meal sum is advisory and the daily total/floor is authoritative. | eng |
| GAP-34 | HLD-clarify | Align the USDA needs-review threshold to **0.7**, matching recipe-system. One number, one place; no reason to diverge. | eng |
| GAP-35 | HLD-clarify | Define the API-unreachable path = the documented one: flag ingredient `nutrition_status = pending`, save recipe, retry via the existing `@Scheduled` job (technical-architecture §USDA already specifies this). Just cross-reference it into nutrition-model. | eng |
| GAP-36 | HLD-clarify | Add a manual-macro-entry fallback for a standalone food with no USDA/OFF match (store as `source = manual`); mirrors the user-override path nutrition-model already has for recipes. | eng |
| GAP-37 | Product-decision | Recommend **explicit user consent + manual/on-connect trigger** for health-platform export (no automatic cadence): export only fires when the user connects the platform and opts in, given the data is intake/journal PII. Your call on whether to also offer a scheduled push once connected. | you |
| GAP-38 | HLD-clarify | Define the notifications list contract: default window last 30 days, newest-first, fields `{id, type, body, createdAt, read, deepLink}`, standard pagination. Notification/01a core is already built — codify its contract. | eng |
| GAP-39 | HLD-clarify | Define retention: auto-archive notifications >90 days old (excluded from default list, kept in `notification_log`); nothing hard-deletes. Mirrors grocery order archival (12mo) and recipe pruning patterns. | eng |
| GAP-40 | HLD-clarify | State per-user notification scoping: `notification_log.user_id` owned, every read filtered server-side by auth context. Same isolation rule as GAP-26. | eng |
| GAP-41 | HLD-clarify | Define scanner cadences (expiry daily 06:00, defrost hourly, prep hourly, staple daily) + default thresholds (fridge 2d, freezer 14d) — provision-model already gives the thresholds; notification/01c scanners are built, so write the cadences down. | eng |
| GAP-42 | HLD-clarify | **Coalesce** per scan run: one notification listing N eligible items, not N notifications — matches the event-debouncing principle in technical-architecture (one event per operation). | eng |
| GAP-43 | HLD-clarify | Suppress-once per item per eligibility window (don't re-nag a still-eligible item every tick); re-emit only if it leaves and re-enters eligibility. Prevents notification spam; consistent with quiet-hours intent. | eng |
| GAP-44 | HLD-clarify | In-app notifications are persisted rows, so "delivery" is a DB write — no retry/drop question for the in-app path; if Spring Modulith event replay is enabled, the listener replays on failure (technical-architecture §Module boundary enforcement). State this. | eng |
| GAP-45 | HLD-clarify | Provide the lifestyle-config defaults now (the "deferred until all three data models complete" blocker is resolved — all three are complete): use the example values already in preference-model Tier-3 as the shipped defaults. | eng |
| GAP-46 | HLD-clarify | Define the manual-override suppression window: an overridden preference is immune to AI re-learning for 90 days (or until the user clears the override), then re-eligible. preference-model already flags overrides "so the AI doesn't re-learn"; just bound it. | eng |
| GAP-47 | HLD-clarify | Define malformed-delta fallback: with tool-use structured output a malformed delta is near-impossible, but on semantic-validation failure, retry once then **skip the update** (keep current profile, log, surface "couldn't update preferences this cycle"). Mirrors the AI-service retry-then-degrade pattern. | eng |
| GAP-48 | HLD-clarify | Define token-budget-exceeded fallback: if even after archiving the profile can't fit ~2500 tokens, archive most-stale/lowest-evidence items first (already the rule), and if still over, hard-cap the lowest-priority sections and log an anomaly. Extends the existing archive guardrail. | eng |
| GAP-49 | HLD-clarify | Enumerate manual-add provisions fields (name, category, quantity+unit OR staple-status, storage_location; expiry optional) + validation. provision-model has the shapes — write the required subset. | eng |
| GAP-50 | HLD-clarify | When an ordered item has no category expiry default: leave `expiry_date` unset (informational, no expiry-driven scheduling) and prompt the user to set one. Consistent with "Everything Is Optional." | eng |
| GAP-51 | HLD-clarify | Define staple transitions (`stocked → low → out` and any reverse via shop/user; no illegal jumps — all transitions legal since user-driven) and auto-promotion threshold (an item ordered in ≥3 of last 5 shops auto-suggests staple status, user confirms). | eng |
| GAP-52 | HLD-clarify | Define `tolerance_over` validation: must be ≥0, ≤ weekly_target (a tolerance larger than the budget is nonsensical); reject otherwise. Trivial bound. | eng |
| GAP-53 | HLD-clarify | Apply the standard pagination envelope to inventory and all Provisions lists (page/size/sort) — infra/01b audit already standardised this project-wide; just declare it for Provisions. | eng |
| GAP-54 | HLD-clarify | Define money handling: `BigDecimal`, GBP, round half-up to 2dp for totals, display estimates as ranges (provision-model already says "show ranges not point values"). Pull the deferred-to-LLD note up into a stated v1 rule since it touches every money assertion. | eng |
| GAP-55 | HLD-clarify | Define the `awaiting_user_confirmation` timeout: auto-expire to `cancelled` after 24h with a notification; the user re-runs. Bounds the order lifecycle's open state. | eng |
| GAP-56 | HLD-clarify | State a per-failure-class retry policy at HLD level: `provider_unavailable` → scheduled retry (3 attempts, exponential backoff via Resilience4j); partial-basket → no auto-retry, user completes manually (grocery.md already says "never retries blindly"). Codify the classes; constants to LLD. | eng |
| GAP-57 | HLD-clarify | Define the draft-basket-vs-automation concurrency contract: the draft basket is a Zustand working-draft (per technical-architecture §Frontend state); an automation run snapshots the draft at start and rejects concurrent user edits with a "basket locked while ordering" state. Mirrors the planner's single-flight snapshot approach. | eng |
| GAP-58 | HLD-clarify | Define the config surface: planning cadence + `max_repeat`/scoring params live in lifestyle config (meal-planner already says slot structure is "owned by the household's lifestyle config"); expose cadence as a settings field, scoring weights as admin-only (Initial Weights v1). | eng |
| GAP-59 | HLD-clarify | Enumerate the `MealSlot` transition set: `planned → cooking → cooked → eaten`, `planned → skipped`, `cooking → cooked`; illegal = any backward/un-mark transition and any skip after `cooking`. meal-planner's pinning table implies these — write the illegal set explicitly to seed error pathways. | eng |
| GAP-60 | HLD-clarify | Define the Nutrition concurrency model = optimistic locking (version column) on `nutrition_targets`, last-write-wins on intake-log appends (they're additive), 409 on stale target edits — same optimistic-lock approach recipe-system already uses. | eng |
| GAP-61 | HLD-clarify | Define price-history + shared-inventory concurrency: price-history rows are append-only (no concurrency conflict — aggregation handles dupes); shared inventory uses last-write-wins + a "partner updated X" notification (the resolution provision-model's Open Question already proposes). Promote that proposal to the rule. | eng |
| GAP-62 | HLD-clarify | Define guest hard-constraint capture: a lightweight per-occasion guest record (allergies/dietary-identity only) folded into the shared-slot `checkForHousehold` union for that slot; not a full account. Grounds in the existing `checkForHousehold(List<UUID>)` filter signature. | eng |
| GAP-63 | HLD-clarify | Enumerate primary-only gated actions: edit/accept the shared plan, manage shared Provisions, manage household membership, change household settings (system-overview §Household: "Primary user manages provisions and the shared plan"). Write the full list + enforcement boundary. | eng |
| GAP-64 | HLD-clarify | Classify reachable-but-non-recipe imports as **garbage → store with `needs_review`** (not fail-fast), letting the user discard — same low-trust handling as `web_discovered`. Fills the gap between "unreachable" and "garbage." | eng |
| GAP-65 | HLD-clarify | System-catalogue recipes **are** visible to normal users (system-overview: "user can promote any system recipe"); state that read/query exposes both catalogues with a `catalogue` filter (the API already has `?catalogue=user`). | eng |
| GAP-66 | HLD-clarify | Allow cross-branch diff (read-only comparison) but **disallow cross-branch revert** (revert is linear within a branch's version history; branches diverge by design). Grounds in recipe-system's version-vs-branch taxonomy. | eng |
| GAP-67 | HLD-clarify | Allow a character-breaking manual edit to remain a version (manual edits are user authority and bypass the version-vs-branch classifier, which only governs AI/optimiser changes). State that the classifier gates automated changes, not manual ones. | eng |
| GAP-68 | HLD-clarify | Define revert as **new version pointer** (create a new version copying old content), consistent with recipe-system's immutable-version model and the planner's copy-forward revert. No moving "current" marker. | eng |
| GAP-69 | HLD-clarify | Define rating aggregation = recency-weighted mean with a visible count; recipe/02b multi-dimensional rating is built, so codify the aggregation it uses (per-dimension recency-weighted mean). | eng |
| GAP-70 | HLD-clarify | Use **identical** error messages for wrong-password vs unknown-user (anti-enumeration), even on a self-hosted app — cheap, standard, no downside. | eng |
| GAP-71 | HLD-clarify | Define logout edge cases: logout-with-no-session is idempotent (200), logout is per-device (invalidates the current session only), concurrent sessions allowed. Pairs with GAP-80's logout-flow decision. | eng |
| GAP-72 | HLD-clarify | Define the context-vs-text tie-break: **text wins** when a strong text signal contradicts screen context (feedback-system already calls context "a signal not a constraint"); if both strong and contradictory, drop to the <0.5 clarify path. | eng |
| GAP-73 | HLD-clarify | Allow two classifications to target the **same** destination (two distinct slices → same module) — each is its own routing-log row + independent transaction; the destination module dedupes if needed. Consistent with feedback-system's independent-transaction model. | eng |
| GAP-74 | HLD-clarify | Define the experiment tie-at-deadline (`evidence_for == evidence_against` at 4 weeks): **discard** (insufficient evidence to promote — the safe default for a learning system); archived so it can re-emerge. Extends the existing experiment lifecycle. | eng |
| GAP-75 | HLD-clarify | Quantify the inferred-vs-explicit override friction: overriding an `inferred` preference is a one-tap correction; overriding an `explicit` one shows a "you told us X — sure?" confirm. preference-model already says inferred should be "easier to override"; this is the concrete rule. | eng |
| GAP-76 | HLD-clarify | Define behavioural-drift: owned by **Preference** (it owns the taste profile + lifestyle staleness); threshold = logged behaviour contradicts config/profile in ≥3 of last 5 relevant events, then prompt the user. preference-model already names drift detection a Preference guardrail. | eng |
| GAP-77 | HLD-clarify | Define "if material" for under-marked-bought re-opt = same ≥15% threshold the planner uses elsewhere: re-opt only if the unbought items affect ≥15% of an unconsumed slot's ingredients or break a hard constraint. Reuses the established materiality threshold. | eng |
| GAP-78 | HLD-clarify | Define headcount = sum of per-member `portion_scale`s (not raw body count — children eat less; `portion_scale` already exists in profile metadata); budget = one household budget (provision-model: "one household budget," shared Provisions). State both. | eng |
| GAP-79 | HLD-clarify | At week-end, auto-skip non-terminal past slots (`planned`/`cooking` that never completed) so the plan can reach `completed` (which requires all slots terminal); surface a summary, don't block. Grounds in the plan lifecycle's `completed` definition + pinning table. | eng |
| GAP-80 | Product-decision | Recommend **YES — ship logout in v1.** It's trivial with session auth (invalidate the session) and expected on any multi-user app. Pairs with GAP-71's edge-case rules. Your call only if you want to defer to keep v1 minimal (not recommended). | you |
| GAP-81 | Defer-to-backlog | Defer password-change to backlog (self-hosted family app, low urgency); when built, it must invalidate all other sessions. Matters once you have non-trusted users or remote hosting. | eng |
| GAP-82 | Product-decision | Recommend **explicitly none in v1** — there's no email infrastructure ("local/self-hosted"), so account recovery is a manual admin/DB reset documented in the runbook. Your call if you plan to add email infra; then a token-based reset. | you |
| GAP-83 | Defer-to-backlog | Defer the full account lifecycle/states beyond implicit ACTIVE to backlog; pairs with GDPR erasure (GAP-08). Matters when you need deactivation or multi-tenant hosting. | eng |
| GAP-84 | Product-decision | Recommend a **handshake invite flow** (primary generates an invite code/link, invitee accepts) over direct-attach — it's the safer model for cross-account joining and matches the `HouseholdMemberAddedEvent` already in the catalogue. Your call on invite-by-code vs invite-by-username; either is fine for self-hosted. | you |
| GAP-85 | Defer-to-backlog | Defer primary-role transfer to backlog, BUT ship one invariant now: **≥1 primary always** (block removing/demoting the last primary). v1 = single primary; multi-primary + transfer is post-v1. The invariant matters immediately to avoid orphaned households. | eng |
| GAP-86 | Product-decision | Recommend shipping a **minimal** notification-preferences surface in v1 (per-category mute + quiet-hours), because it's acute for time-bound defrost/prep alerts (a 2am defrost reminder is a real harm). Default quiet hours 22:00–07:00. Your call on how granular (per-channel can wait). | you |
| GAP-87 | Defer-to-backlog | Ship **dismiss + mark-read-idempotent** now (cheap, expected); defer "clear all" + delete to backlog. Mark-read must be idempotent (re-marking a read notification is a no-op 200). Partial-defer: the read/dismiss split matters for v1 UX, bulk-clear doesn't. | eng |
| GAP-88 | Defer-to-backlog | Defer rich deep-link/act-on semantics, but include a simple `deepLink` string field on notifications now (GAP-38 already lists it) so the frontend can route; the richer "act inline" affordances are backlog. Matters when notifications grow action types. | eng |
