# Frontend-gap tickets — backend contract gaps from the page-spec programme

Consolidated from the 15 page specs under `design/frontend/pages/` (the "Open questions" sections,
inline "backend gap/ticket pending" notes, and nutrition's Amendments). Deduplicated across specs
— e.g. the pending-change `optimisticVersion` gap was flagged by three pages and is one ticket.

**Priorities:** **P1** blocks live wiring · **P2** DTO/field/endpoint gaps that degrade UX ·
**P3** semantic clarifications / product calls (grouped into combined tickets per module).

> **PROGRAMME LEDGER (closed 2026-06-13).** Every P1 ticket and 13 of 16 P2 tickets are resolved
> by the PR named in their row (#242–#255); every P3 item carries its disposition below and a
> dated annotation inside its grouped ticket file. **Three P2 tickets remain OPEN** (verified
> against the code 2026-06-13, no resolving PR exists): planner-effective-meal-time,
> adaptation-pending-change-list-dto, grocery-recalculate-pantry-drift — they survive the
> programme close as ordinary backlog tickets.

## P1 — blocks live wiring (6) — all resolved

| Ticket | Gap | Source spec | Resolved by |
|---|---|---|---|
| [recipe-list-search-endpoint](recipe-list-search-endpoint.md) | `GET /api/v1/recipes` list/search MISSING — the library page's core read is unbacked (controller javadoc defers it; LLD specifies it) | recipes §8 Q1 (+Q4 N+1 folded in) | **#242** |
| [preference-onboarding-initialise](preference-onboarding-initialise.md) | Onboarding steps 3–4 cannot complete: hard-constraints + lifestyle-config PUTs 404 until an internal initialise that has no REST path (recommend upsert-on-first-PUT) | onboarding §5 G1 | **#243** |
| [nutrition-intake-override-repair](nutrition-intake-override-repair.md) | OVERRIDDEN + `needsAiParse` has no legal repair transition (edit requires PENDING) — user is stuck after a failed parse | nutrition §8 Q1 | **#244** |
| [planner-candidate-pick-decision](planner-candidate-pick-decision.md) | **PRODUCT DECISION**: HLD says user picks among 5 candidates; API auto-picks via Stage C — expose candidates+pick vs amend HLD | plan §8 Q1 | **#249** (ruled: HLD amended, auto-pick is v1) |
| [discovery-server-side-exclusions](discovery-server-side-exclusions.md) | ⚠️ **SAFETY**: `mustExcludeIngredientMappingKeys` is client-trusted on user jobs — empty list ingests allergy-violating recipes; server must inject+union the snapshot | discover §3 / §9 Q3 | **#245** |
| [planner-reopt-suggestion-detail](planner-reopt-suggestion-detail.md) | No GET-single re-opt suggestion with `proposedAssignments` — the HLD-mandated diff preview *before* accept cannot render | plan §3e / §8 Q2 | **#246** |

## P2 — DTO/field/endpoint gaps, degrade UX (16) — 13 resolved, 3 open

### nutrition
| Ticket | Gap | Source spec | Resolved by |
|---|---|---|---|
| [nutrition-daily-aggregate-satfat](nutrition-daily-aggregate-satfat.md) | `DailyAggregateDto` missing a `satFat` macro aggregate (rides the micros map by key convention) | nutrition §10 (a) | **#247** |
| [nutrition-weekly-floor-violations](nutrition-weekly-floor-violations.md) | `WeeklyAggregateDto.floorViolations` is key-only `string[]` — adopt the already-defined `FloorViolationDto` | nutrition §10 (b) | **#247** |

### planner / adaptation
| Ticket | Gap | Source spec | Resolved by |
|---|---|---|---|
| [planner-effective-meal-time](planner-effective-meal-time.md) | Serve-time resolution is server-internal — add resolved `effectiveMealTime` (+source) to `MealSlotDto` | plan §8 Q3, today §3b | **OPEN** — not built (verified 2026-06-13: no `effectiveMealTime` in main) |
| [adaptation-pending-change-list-dto](adaptation-pending-change-list-dto.md) | `PendingChangeListItemDto` lacks `optimisticVersion` (forced expand-then-accept) + `status`/`resolvedAt` (history rows can't show outcomes) | today §8 Q6, recipe-detail §11 Q5, activity §8 Q1 | **OPEN** — not built (verified 2026-06-13: list DTO unchanged) |

### recipe
| Ticket | Gap | Source spec | Resolved by |
|---|---|---|---|
| [recipe-version-nutrition-per-serving](recipe-version-nutrition-per-serving.md) | `nutritionPerServing` absent from version DTOs — nutrition pills only obtainable via the recalc POST (write as read) | recipe-detail §11 Q1 | **#252** |
| [recipe-substitution-state-filter](recipe-substitution-state-filter.md) | Substitution lists filter to ACCEPTED — PROPOSED rows unlistable after reload; add `state` param | recipe-detail §11 Q2 | **#252** |
| [recipe-import-dedup-consistency](recipe-import-dedup-consistency.md) | Dedup dialog half-backed: no "import anyway" override (`ignoreDuplicateOfRecipeId`); one-shot `/imports/url` bypasses the dedup gate | recipes §8 Q2/Q3 | **#252** |

### discovery
| Ticket | Gap | Source spec | Resolved by |
|---|---|---|---|
| [discovery-cancelled-status](discovery-cancelled-status.md) | No `CANCELLED` terminal status — cancel is FAILED + a string contract; OpenAPI cancel text stale | discover §9 Q2 | **#253** |
| [discovery-user-source-disable](discovery-user-source-disable.md) | HLD-promised user source-disable has no endpoint; `userDisabled` column/entity exist but DTO hides it | discover §9 Q4 | **#253** |

### grocery
| Ticket | Gap | Source spec | Resolved by |
|---|---|---|---|
| [grocery-cost-variance](grocery-cost-variance.md) | No cost-variance field — the HLD's "£47 ± £8" band undeliverable; add min/max totals to `ShoppingListDto` (plan card shares the finding) | groceries §8 Q1, plan §3b/§4c | **#254** |
| [grocery-recalculate-pantry-drift](grocery-recalculate-pantry-drift.md) | Recalculate idempotent per `(planId, planGeneration)` — can't pick up pantry drift; add `force` rebuild preserving bought marks | groceries §8 Q2 | **OPEN** — not built (verified 2026-06-13: no `force` param; #254 scoped it out) |
| [grocery-undo-pantry-reversal](grocery-undo-pantry-reversal.md) | Undo-mark-bought leaves the pantry add in place — add best-effort compensating reversal | groceries §8 Q4 | **#254** |

### provisions
| Ticket | Gap | Source spec | Resolved by |
|---|---|---|---|
| [provisions-inventory-surface-gaps](provisions-inventory-surface-gaps.md) | Staple status tap rides the full PUT (add `PATCH …/status`); no `itemStatus` filter (spoiled rows unfindable); no `expiringWithinDays` param | pantry §9 Q1–Q3 | **#251** |

### notification / household / feedback
| Ticket | Gap | Source spec | Resolved by |
|---|---|---|---|
| [notification-kind-enum](notification-kind-enum.md) | OpenAPI `NotificationKind` missing 2 shipped values — **codegen-breaking at runtime** for those kinds; add parity test | notifications §8 Q1 | **#248** |
| [household-member-display-names](household-member-display-names.md) | Members render as UUID stubs — add `username` to `HouseholdMemberDto` + displayName default on invite accept | settings §8 Q2/Q6, admin §7 Q3 | **#250** |
| [feedback-clarification-text-excerpt](feedback-clarification-text-excerpt.md) | `ClarificationQueryDto` / `MisclassificationCorrectionDto` carry no feedback text — N+1 per inbox card; add `textExcerpt` | activity §8 Q5 | **#255** |

## P3 — semantic clarifications, grouped per module (6 combined tickets) — all dispositioned

Per-item dispositions (dated annotations live inside each ticket file). Legend: **done-doc** =
doc/contract pin landed · **done-code** = small code landed (this PR) · **accepted** = current
behaviour ruled correct, no change · **superseded** / **deferred** as marked.

| Ticket | Item dispositions |
|---|---|
| [planner-today-p3-clarifications](planner-today-p3-clarifications.md) | 1 resolution-option key — **done-doc** (lld/planner.md pin) · 2 Stage C reasoning — **accepted** (drop card v1) · 3 generate-vs-ACTIVE semantics — **done-doc** (verified; pinned in planner.yaml; flag is UNREAD) · 4 slot-eaten dual-write — **accepted**; cook-event wiring **DEFERRED (v1.5)** · 5 skip semantics — **done-doc** (ruled paired-Skip; pinned both LLDs) · 6 CUSTOM/SNACK join rule — **done-doc** (lld/nutrition.md Flow 5) · 7 batch portion counter — **DEFERRED (v1.5)** |
| [recipe-adaptation-p3-clarifications](recipe-adaptation-p3-clarifications.md) | 1 previewToken — **done-doc** (lld/recipe.md pinned stateless) · 2 REJECTED re-accept — **done-doc** (HLD amended; only SUPERSEDED terminal) · 3 branch promote — **DEFERRED (v1.1)** (feature; recorded in HLD) · 4 proposedDiff shape — **done-doc** (schemas/adaptation.yaml names the RecipeDiffDto family) · 5 destinationResult — **accepted** · 6 all-pending list — **accepted** (HLD budget intended) · 7 fingerprint — **accepted** |
| [preference-p3-clarifications](preference-p3-clarifications.md) | 1 re-stamp scalars — **done-code** (applyManualOverride copies entity truth) · 2 refresh-now signal — **accepted** (SSE, task #172) · 3 budget guard 422 — **done-code** (guard on override + contract 422) · 4 profile-metadata REST — **done-doc** (drop confirmed; LLD table reconciled) · 5 G2 expectedVersion — **done-doc** (initialise op description) · 6 G3 resume marker — **accepted** |
| [grocery-provisions-p3-clarifications](grocery-provisions-p3-clarifications.md) | 1 QUOTED→DRAFT — **SUPERSEDED by #254** (built there) · 2 provider catalogue — **accepted** (prereq of provider #2) · 3 PLACED advance — **accepted** · 4 spoil-with-waste — **DEFERRED (v1.5)** (composed endpoint, bigger than P3) · 5 ate-a-portion — **done-doc** (sanctioned; lld/provisions.md pin) · 6 mapping-key inference — **done-doc** (nutrition-lookup assist blessed; lld/provisions.md pin) |
| [discovery-p3-clarifications](discovery-p3-clarifications.md) | 1 live progress — **accepted** (SSE, task #172) · 2 skip semantics — **done-doc** (ruled local-dismiss; pinned page spec + HLD) · 3 kept count — **accepted** |
| [platform-p3-clarifications](platform-p3-clarifications.md) | 1 actionTargetUri — **done-code** (resolver emits IA routes) · 2 multi-status filter — **accepted** · 3 prefs PUT required — **done-doc** (schema aligned) · 4 bell cadence — **accepted** (60 s + on-focus) · 5 household rename — **DEFERRED (v1.5)** · 6 role verb — **done-doc** · 7 expiresAt cap — **done-doc** · 8 isAdmin on /me — **done-code** (`CurrentUserDto`) · 9 sessionExpiresAt — **accepted** · 10 Retry-After CORS — **done-code** (verified missing; one-line fix) |

## Noted, deliberately NOT ticketed

- `BudgetDto.spendTracking` null in v1 (today §8 Q5, pantry §6) — **by design**, populated by
  order history in v1.5; the pages render target-only behind a guard.
- "Merge" on the dedup dialog — v2 (needs a merge model; recorded inside
  [recipe-import-dedup-consistency](recipe-import-dedup-consistency.md)).
- SSE/push (job progress, notifications, refresh-now signals) — existing backlog item (task #172);
  the P3 tickets reference it rather than duplicating it.
- Admin money-unit inconsistency (admin §7 Q1) — frontend formatter concern, no contract change.
