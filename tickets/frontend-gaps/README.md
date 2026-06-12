# Frontend-gap tickets — backend contract gaps from the page-spec programme

Consolidated from the 15 page specs under `design/frontend/pages/` (the "Open questions" sections,
inline "backend gap/ticket pending" notes, and nutrition's Amendments). Deduplicated across specs
— e.g. the pending-change `optimisticVersion` gap was flagged by three pages and is one ticket.

**Priorities:** **P1** blocks live wiring · **P2** DTO/field/endpoint gaps that degrade UX ·
**P3** semantic clarifications / product calls (grouped into combined tickets per module).

## P1 — blocks live wiring (6)

| Ticket | Gap | Source spec |
|---|---|---|
| [recipe-list-search-endpoint](recipe-list-search-endpoint.md) | `GET /api/v1/recipes` list/search MISSING — the library page's core read is unbacked (controller javadoc defers it; LLD specifies it) | recipes §8 Q1 (+Q4 N+1 folded in) |
| [preference-onboarding-initialise](preference-onboarding-initialise.md) | Onboarding steps 3–4 cannot complete: hard-constraints + lifestyle-config PUTs 404 until an internal initialise that has no REST path (recommend upsert-on-first-PUT) | onboarding §5 G1 |
| [nutrition-intake-override-repair](nutrition-intake-override-repair.md) | OVERRIDDEN + `needsAiParse` has no legal repair transition (edit requires PENDING) — user is stuck after a failed parse | nutrition §8 Q1 |
| [planner-candidate-pick-decision](planner-candidate-pick-decision.md) | **PRODUCT DECISION**: HLD says user picks among 5 candidates; API auto-picks via Stage C — expose candidates+pick vs amend HLD | plan §8 Q1 |
| [discovery-server-side-exclusions](discovery-server-side-exclusions.md) | ⚠️ **SAFETY**: `mustExcludeIngredientMappingKeys` is client-trusted on user jobs — empty list ingests allergy-violating recipes; server must inject+union the snapshot | discover §3 / §9 Q3 |
| [planner-reopt-suggestion-detail](planner-reopt-suggestion-detail.md) | No GET-single re-opt suggestion with `proposedAssignments` — the HLD-mandated diff preview *before* accept cannot render | plan §3e / §8 Q2 |

## P2 — DTO/field/endpoint gaps, degrade UX (16)

### nutrition
| Ticket | Gap | Source spec |
|---|---|---|
| [nutrition-daily-aggregate-satfat](nutrition-daily-aggregate-satfat.md) | `DailyAggregateDto` missing a `satFat` macro aggregate (rides the micros map by key convention) | nutrition §10 (a) |
| [nutrition-weekly-floor-violations](nutrition-weekly-floor-violations.md) | `WeeklyAggregateDto.floorViolations` is key-only `string[]` — adopt the already-defined `FloorViolationDto` | nutrition §10 (b) |

### planner / adaptation
| Ticket | Gap | Source spec |
|---|---|---|
| [planner-effective-meal-time](planner-effective-meal-time.md) | Serve-time resolution is server-internal — add resolved `effectiveMealTime` (+source) to `MealSlotDto` | plan §8 Q3, today §3b |
| [adaptation-pending-change-list-dto](adaptation-pending-change-list-dto.md) | `PendingChangeListItemDto` lacks `optimisticVersion` (forced expand-then-accept) + `status`/`resolvedAt` (history rows can't show outcomes) | today §8 Q6, recipe-detail §11 Q5, activity §8 Q1 |

### recipe
| Ticket | Gap | Source spec |
|---|---|---|
| [recipe-version-nutrition-per-serving](recipe-version-nutrition-per-serving.md) | `nutritionPerServing` absent from version DTOs — nutrition pills only obtainable via the recalc POST (write as read) | recipe-detail §11 Q1 |
| [recipe-substitution-state-filter](recipe-substitution-state-filter.md) | Substitution lists filter to ACCEPTED — PROPOSED rows unlistable after reload; add `state` param | recipe-detail §11 Q2 |
| [recipe-import-dedup-consistency](recipe-import-dedup-consistency.md) | Dedup dialog half-backed: no "import anyway" override (`ignoreDuplicateOfRecipeId`); one-shot `/imports/url` bypasses the dedup gate | recipes §8 Q2/Q3 |

### discovery
| Ticket | Gap | Source spec |
|---|---|---|
| [discovery-cancelled-status](discovery-cancelled-status.md) | No `CANCELLED` terminal status — cancel is FAILED + a string contract; OpenAPI cancel text stale | discover §9 Q2 |
| [discovery-user-source-disable](discovery-user-source-disable.md) | HLD-promised user source-disable has no endpoint; `userDisabled` column/entity exist but DTO hides it | discover §9 Q4 |

### grocery
| Ticket | Gap | Source spec |
|---|---|---|
| [grocery-cost-variance](grocery-cost-variance.md) | No cost-variance field — the HLD's "£47 ± £8" band undeliverable; add min/max totals to `ShoppingListDto` (plan card shares the finding) | groceries §8 Q1, plan §3b/§4c |
| [grocery-recalculate-pantry-drift](grocery-recalculate-pantry-drift.md) | Recalculate idempotent per `(planId, planGeneration)` — can't pick up pantry drift; add `force` rebuild preserving bought marks | groceries §8 Q2 |
| [grocery-undo-pantry-reversal](grocery-undo-pantry-reversal.md) | Undo-mark-bought leaves the pantry add in place — add best-effort compensating reversal | groceries §8 Q4 |

### provisions
| Ticket | Gap | Source spec |
|---|---|---|
| [provisions-inventory-surface-gaps](provisions-inventory-surface-gaps.md) | Staple status tap rides the full PUT (add `PATCH …/status`); no `itemStatus` filter (spoiled rows unfindable); no `expiringWithinDays` param | pantry §9 Q1–Q3 |

### notification / household / feedback
| Ticket | Gap | Source spec |
|---|---|---|
| [notification-kind-enum](notification-kind-enum.md) | OpenAPI `NotificationKind` missing 2 shipped values — **codegen-breaking at runtime** for those kinds; add parity test | notifications §8 Q1 |
| [household-member-display-names](household-member-display-names.md) | Members render as UUID stubs — add `username` to `HouseholdMemberDto` + displayName default on invite accept | settings §8 Q2/Q6, admin §7 Q3 |
| [feedback-clarification-text-excerpt](feedback-clarification-text-excerpt.md) | `ClarificationQueryDto` / `MisclassificationCorrectionDto` carry no feedback text — N+1 per inbox card; add `textExcerpt` | activity §8 Q5 |

## P3 — semantic clarifications, grouped per module (6 combined tickets)

| Ticket | Items | Source specs |
|---|---|---|
| [planner-today-p3-clarifications](planner-today-p3-clarifications.md) | resolution-option apply endpoint; Stage C reasoning visibility; `forceRegenerateIfActive=false` semantics pin; slot-eaten dual-write / cook-event wiring; skip semantics across machines; CUSTOM/SNACK intake join rule; batch portion counter | plan §8 Q4–Q6, today §8 Q1–Q4 |
| [recipe-adaptation-p3-clarifications](recipe-adaptation-p3-clarifications.md) | previewToken drift pin; REJECTED→re-accept vs HLD; branch promote-to-standalone (deferred feature); `proposedDiff` schema publication; `destinationResult` typing; no all-pending-changes list; fingerprint invisibility | recipes §8 Q5, recipe-detail §11 Q3/Q4/Q6/Q7, activity §8 Q2–Q4 |
| [preference-p3-clarifications](preference-p3-clarifications.md) | re-stamp server-managed scalars; refresh-now completion signal (→SSE); budget guard 422 on manual override; profile-metadata REST never shipped; onboarding G2/G3 | preferences §8 Q1–Q4, onboarding §5 G2/G3 |
| [grocery-provisions-p3-clarifications](grocery-provisions-p3-clarifications.md) | QUOTED→DRAFT re-quote; provider catalogue endpoint; PLACED implicit advance; composed spoil-with-waste; ate-a-portion placement; mapping-key inference | groceries §8 Q3/Q5/Q6, pantry §9 Q4–Q6 |
| [discovery-p3-clarifications](discovery-p3-clarifications.md) | live progress (→SSE); skip-result product ruling; per-job kept count | discover §9 Q1/Q5/Q6 |
| [platform-p3-clarifications](platform-p3-clarifications.md) | actionTargetUri namespace; multi-status filter; notif-prefs PUT required-fields; bell cadence; household rename; role-verb note; expiresAt truncation doc; `isAdmin` on /auth/me; sessionExpiresAt; Retry-After CORS exposure | notifications §8 Q2/Q3/Q5/Q6, settings §8 Q1/Q3/Q5, admin §5, login §7 Q1/Q2 |

## Noted, deliberately NOT ticketed

- `BudgetDto.spendTracking` null in v1 (today §8 Q5, pantry §6) — **by design**, populated by
  order history in v1.5; the pages render target-only behind a guard.
- "Merge" on the dedup dialog — v2 (needs a merge model; recorded inside
  [recipe-import-dedup-consistency](recipe-import-dedup-consistency.md)).
- SSE/push (job progress, notifications, refresh-now signals) — existing backlog item (task #172);
  the P3 tickets reference it rather than duplicating it.
- Admin money-unit inconsistency (admin §7 Q1) — frontend formatter concern, no contract change.
