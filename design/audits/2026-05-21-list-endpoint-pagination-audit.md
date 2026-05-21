# List-Endpoint Pagination Audit (infra/01b)

Date: 2026-05-21
Owner: infra-01b
Status: complete (PR pending)

## Method

Enumerated every `@GetMapping` controller method in `src/main/java/.../api/controller/` that
returns a `Collection`, `List`, `Set`, `Iterable`, or `Page<>` of a DTO type. Each is categorised
as:

- **Already paginated** — returns `Page<>` + accepts `Pageable`. No change required.
- **Bounded by design** — returns `List<>` but cardinality is bounded by domain semantics
  (date range, parent aggregate, explicit cap, etc.). Annotated `@BoundedCollection` with a
  free-text justification.
- **Needs pagination** — returns `List<>` without a domain-imposed bound. Pagination added in
  this ticket.

The `@BoundedCollection` marker annotation (`com.example.mealprep.core.api.markers`) is gated by
a new ArchUnit rule (`listReturningGetMappingsMustBeAnnotatedBoundedCollection`) so future drift
fails the build.

## Endpoint inventory

### Module: ai

| Endpoint | Status | Notes |
|---|---|---|
| `GET /api/v1/admin/ai/call-log` | Already paginated | `Page<AiCallLogDto>` |
| `GET /api/v1/admin/ai/prompt-templates` | Already paginated | `Page<PromptTemplateDto>` |
| `GET /api/v1/admin/ai/cost-summary` | N/A | Returns single rollup DTO |

### Module: recipe

| Endpoint | Status | Notes |
|---|---|---|
| `GET /api/v1/recipes/{id}` | N/A | Single resource |
| `GET /api/v1/recipes/{id}/branches` | Bounded | `@BoundedCollection("bounded by recipe; branches per recipe are typically < 10")` |
| `GET /api/v1/recipes/{id}/substitutions/active` | Bounded | `@BoundedCollection("bounded by recipe")` |
| `GET /api/v1/recipes/{id}/substitutions?versionId=` | Bounded | `@BoundedCollection("bounded by version")` |
| `GET /api/v1/recipes/{id}/versions/{fromId}/diff/{toId}` | N/A | Single diff DTO |
| `GET /api/v1/recipes/{id}/versions/{versionId}/with-substitutions` | N/A | Single version DTO |
| `GET /api/v1/recipes/imports/{id}` | N/A | Single resource |

Note: `GET /api/v1/recipes` (catalogue browse) is NOT yet implemented at the controller level
(deferred to `recipe-01i`). This ticket's Phase-4 verification confirms there is no list endpoint
to retrofit; the search controller will land paginated by default per the LLD.

### Module: nutrition

| Endpoint | Status | Notes |
|---|---|---|
| `GET /api/v1/nutrition/intake/{date}` | N/A | Single day DTO |
| `GET /api/v1/nutrition/intake?from=&to=` | Bounded | `@BoundedCollection("bounded by date range; service enforces max 35-day window")` |
| `GET /api/v1/nutrition/intake/week/{weekStart}/aggregate` | N/A | Single aggregate |
| `GET /api/v1/nutrition/intake/{date}/audit-log` | Already paginated | `Page<IntakeAuditEntryDto>` |
| **`GET /api/v1/nutrition/intake/search`** | **NEW (paginated)** | C-B-048 — added in this ticket; `Page<IntakeSlotSearchResultDto>` |
| `GET /api/v1/nutrition/journal/{date}` | Bounded | `@BoundedCollection("bounded-by-date; journal entries per day are typically < 20")` |
| `GET /api/v1/nutrition/journal` | Already paginated | `Page<FoodMoodEntryDto>` |
| `GET /api/v1/nutrition/targets/audit-log` | Already paginated | `Page<NutritionTargetsAuditEntryDto>` |
| `GET /api/v1/nutrition/targets/activity?from=&to=` | Bounded | `@BoundedCollection("bounded by date range; one row per day")` |
| `GET /api/v1/nutrition/health-directives` | Already paginated | `Page<HealthDirectiveDto>` |
| `GET /api/v1/nutrition/ingredients/needs-review` | Already paginated | `Page<IngredientNutritionDto>` |
| `GET /api/v1/nutrition/ingredients/lookup?term=` | N/A | Single resource |

### Module: provisions

| Endpoint | Status | Notes |
|---|---|---|
| `GET /api/v1/provisions/inventory` | Already paginated | `Page<InventoryItemDto>` |
| `GET /api/v1/provisions/inventory/{itemId}` | N/A | Single resource |
| `GET /api/v1/provisions/inventory/admin/{itemId}/audit-log` | Already paginated | `Page<InventoryAuditEntryDto>` |
| `GET /api/v1/provisions/equipment` | Bounded | `@BoundedCollection("bounded by user kitchen inventory; typically < 50 items")` |
| `GET /api/v1/provisions/budget` | N/A | Single resource |
| `GET /api/v1/provisions/waste` | Already paginated | `Page<WasteEntryDto>` |
| `GET /api/v1/provisions/supplier-products` | Already paginated | `Page<SupplierProductDto>` |
| `GET /api/v1/provisions/for-planner` | N/A | Single bundle DTO |

### Module: feedback

| Endpoint | Status | Notes |
|---|---|---|
| `GET /api/v1/feedback` | Already paginated | `Page<FeedbackEntryDto>` |
| `GET /api/v1/feedback/{id}` | N/A | Single resource |
| `GET /api/v1/feedback/corrections` | Already paginated | `Page<MisclassificationCorrectionDto>` |
| `GET /api/v1/clarification-queries` | Already paginated | `Page<ClarificationQueryDto>` |
| `GET /api/v1/clarification-queries/{id}` | N/A | Single resource |

### Module: household

| Endpoint | Status | Notes |
|---|---|---|
| `GET /api/v1/households/current` | N/A | Single resource |
| `GET /api/v1/households/current/invites` | Bounded | `@BoundedCollection("bounded by household pending-invite count; typically < 10")` |
| `GET /api/v1/households/current/settings` | N/A | Single resource |
| `GET /api/v1/households/current/settings/audit-log` | Already paginated | `Page<HouseholdSettingsAuditEntryDto>` |
| `GET /api/v1/households/current/slot-configuration` | N/A | Single resource |
| `GET /api/v1/households/{id}/slot-configuration/planner-view` | N/A | Single resource |

### Module: planner

| Endpoint | Status | Notes |
|---|---|---|
| `GET /api/v1/plans/active?householdId=&weekStartDate=` | N/A | Single plan |
| `GET /api/v1/plans/history?householdId=&weekStartDate=` | Bounded | `@BoundedCollection("bounded by service-level cap of 100 generations per (household, week)")` |
| `GET /api/v1/plans?householdId=&from=&to=` | Already paginated | `Page<PlanDto>` |
| `GET /api/v1/plans/suggestions?householdId=` | Already paginated | `Page<ReoptSuggestionDto>` |
| `GET /api/v1/admin/planner/decisions/{planId}` | N/A | Returns `PlannerDecisionChainDto` (DAG aggregate) |

### Module: preference

| Endpoint | Status | Notes |
|---|---|---|
| `GET /api/v1/preferences/hard-constraints` | N/A | Single resource |
| `GET /api/v1/preferences/hard-constraints/audit-log` | Already paginated | `Page<HardConstraintsAuditEntryDto>` |

### Module: discovery

| Endpoint | Status | Notes |
|---|---|---|
| `GET /api/v1/discovery/sources` | Bounded | `@BoundedCollection("static registry; bounded by configured source count")` |
| `GET /api/v1/discovery/sources/{key}` | N/A | Single resource |
| `GET /api/v1/discovery/jobs` | Already paginated | `Page<DiscoveryJobDto>` |
| `GET /api/v1/discovery/jobs/{id}` | N/A | Single resource |
| `GET /api/v1/discovery/jobs/{id}/scrape-log` | Already paginated | `Page<DiscoveryScrapeLogEntryDto>` |

### Module: adaptation

| Endpoint | Status | Notes |
|---|---|---|
| `GET /api/v1/pending-changes` | Bounded | `@BoundedCollection("explicit top-3 cap per ranking algorithm")` |
| `GET /api/v1/pending-changes/{id}` | N/A | Single resource |
| `GET /api/v1/recipes/{id}/pending-history` | Already paginated | `Page<PendingChangeListItemDto>` |
| `GET /api/v1/admin/adaptation/recipes/{id}/jobs` | Already paginated | `Page<AdaptationJobDto>` |
| `GET /api/v1/admin/adaptation/recipes/{id}/traces` | Already paginated | `Page<AdaptationTraceDto>` |
| `GET /api/v1/admin/prompt-versions/{name}/{version}/traces` | Already paginated | `Page<AdaptationTraceDto>` |
| `GET /api/v1/admin/adaptation/run-history` | Already paginated | `Page<AdaptationJobDto>` |
| `GET /api/v1/admin/adaptation/run-history/by-prompt-version` | Already paginated | `Page<AdaptationTraceDto>` |

### Module: core

| Endpoint | Status | Notes |
|---|---|---|
| `GET /api/v1/admin/decision-log/{id}` | N/A | Single resource |
| `GET /api/v1/admin/decision-log/trace/{traceId}` | Bounded | `@BoundedCollection("bounded-by-trace; a trace is a single request's decision chain")` |
| `GET /api/v1/admin/decision-log/{id}/ancestry` | N/A | Single response with bounded depth |

### Module: auth

| Endpoint | Status | Notes |
|---|---|---|
| `GET /api/v1/auth/me` | N/A | Single resource |

## Summary

- **Total list-shaped endpoints surveyed**: 23 (`Page<>` returns) + 11 (`List<>` returns) = 34.
- **Already paginated**: 23.
- **Bounded by design, annotated `@BoundedCollection` in this ticket**: 11.
- **Needs pagination, retrofitted in this ticket**: 0 — every previously-unpaginated endpoint was
  judged bounded by date / aggregate / explicit cap.
- **New endpoint added (paginated)**: 1 — `GET /api/v1/nutrition/intake/search` (C-B-048).

## Judgement calls worth flagging

- **`GET /api/v1/discovery/sources`** is a static registry today; if the source set ever grows
  past ~100, swap to `Page<>`. Not bounded by user input.
- **`GET /api/v1/provisions/equipment`** could grow unbounded if a user goes wild logging
  equipment. The `< 50` heuristic is a soft expectation; tighten to `Page<>` if perf testing
  shows realistic users with hundreds of rows.
- **`GET /api/v1/pending-changes`** top-3 cap is enforced inside the service — the controller
  doesn't expose a `limit` param. Per design (the UI shows 3 cards); if the UX changes, this
  must follow.
- **`GET /api/v1/nutrition/intake/search`** does NOT JOIN on `recipe.name` (the ticket's
  original spec called for it). The nutrition module would have to import the recipe entity,
  which violates module-boundary rules. Search is restricted to `IntakeSlot.overrideFreeText`
  (the only free-text field on the nutrition side). True full-text + cross-module name search
  is a follow-up via a denormalised projection table or read replica.

## ArchUnit rule

`ModuleBoundaryTest.listReturningGetMappingsMustBeAnnotatedBoundedCollection`. Removing
`@BoundedCollection` from any of the 11 annotated endpoints (e.g. `RecipeBranchesController.list`)
will fail the rule. The rule does NOT cover `ResponseEntity<List<>>` returns (the raw type is
`ResponseEntity`); that's a recognised limitation and the manual audit caught the one such case
(`PlansController.getHistory`).
