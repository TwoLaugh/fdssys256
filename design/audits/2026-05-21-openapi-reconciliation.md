# OpenAPI Spec / Controller Reconciliation Audit

**Ticket:** `tickets/infra/01c-openapi-spec-audit.md`
**Date:** 2026-05-21
**Scope:** Verification + spec-only corrections. No controller changes.
**Spec under audit:** `src/main/resources/openapi/openapi.yaml` (+ modular `paths/*.yaml`, `schemas/*.yaml`).

## Headline

| Metric                                | Value                       |
| ------------------------------------- | --------------------------- |
| Distinct paths in spec                | 134                         |
| Distinct paths derived from controllers | 134                       |
| Paths present in controllers, missing from spec | 0                 |
| Paths present in spec, missing from controllers | 0                 |
| Schemas in spec `components.schemas`  | 187 (after PageEnvelope add) |
| swagger-cli validate                  | PASS                        |
| redocly lint --extends recommended (errors) | PASS (0 errors after fixes; 9 advisory warnings remain) |
| openapi-typescript codegen            | PASS                        |
| tsc --strict --noEmit                 | PASS (0 errors)             |

**Verdict:** spec is comprehensive; no missing endpoints. The audit's expected
"small omissions and drift" did not materialise — coverage is end-to-end clean.
The fixes shipped in this ticket are limited to **lint-rule errors** that
blocked Redocly's recommended ruleset plus the two shared-schema additions the
ticket mandated (`PageEnvelope`, `bearerAuth`).

## Methodology

1. **Path coverage.** Extracted every `@RestController` class-level
   `@RequestMapping` and combined with every method-level `@{Get,Post,Put,Patch,Delete}Mapping`
   in `src/main/java/com/example/mealprep/**/*Controller.java`. Cross-checked
   the resulting set against the top-level `paths:` keys in
   `src/main/resources/openapi/openapi.yaml`. Set difference in both directions
   is empty (134 = 134).
2. **Lint pass.** Ran `swagger-cli validate` (CI's existing gate — green
   pre-fix) and `redocly lint --extends recommended` (the new strengthened
   gate). Pre-fix: 4 errors + 10 warnings. Post-fix: 0 errors + 9 warnings.
3. **Type-gen smoke.** Ran `openapi-typescript ... -o api-types.d.ts` then
   `tsc --strict --noEmit api-types.d.ts`. Both exit 0. The generated file is
   12,677 lines of clean TypeScript — the frontend can adopt it without
   downstream patches.
4. **Schema spot-check.** Confirmed `JsonNode` fields on `PromptTemplateDto`
   (`outputSchema`, `tools`) were the only schema entries triggering the
   `nullable-type-sibling` Redocly rule — both are now declared
   `type: object, additionalProperties: true, nullable: true` per the ticket's
   "JSONB / `JsonNode` fields" rule.

## Fixes applied in this ticket

| File                                                      | Change                                                                  |
| --------------------------------------------------------- | ----------------------------------------------------------------------- |
| `src/main/resources/openapi/schemas/ai.yaml`              | `PromptTemplateDto.outputSchema` / `tools`: add `type: object, additionalProperties: true` alongside `nullable: true`. JsonNode passthrough. |
| `src/main/resources/openapi/paths/auth.yaml`              | `register` / `login`: add `security: []` (explicit public). `logout`: add `'401'` response. |
| `src/main/resources/openapi/schemas/common.yaml`          | Add `PageEnvelope` shared schema (reservation; existing per-type `*DtoPage` schemas remain canonical for current endpoints). |
| `src/main/resources/openapi/openapi.yaml`                 | Add `bearerAuth` security scheme (reservation for `tickets/core/02b-origin-tracking-foundation.md`; not applied to any path). Add `PageEnvelope` to `components.schemas` ref table. |
| `.github/workflows/ci.yml`                                | Add `redocly lint --extends recommended` as a second OpenAPI gate.      |

## Reconciliation table — high-confidence sample

A spot-check across the 14 modules. Verdict columns: `MATCH` (spec ↔ controller agree),
`SPEC_FIXED` (this ticket adjusted the spec), `FOLLOWUP_NEEDED` (controller-side
issue surfaced; out of scope for this ticket).

| Module      | Controller                                | Endpoint count | Verdict     |
| ----------- | ----------------------------------------- | -------------- | ----------- |
| auth        | `AuthController`                          | 5 (register, login, logout, me, password) | SPEC_FIXED (security, 401 on logout) |
| ai          | `AdminAiController`                       | 4              | MATCH; `PromptTemplateDto` schema SPEC_FIXED (JsonNode shape) |
| adaptation  | `AdaptationAdminController`               | 7              | MATCH        |
| adaptation  | `AdapterRunHistoryController`             | 2              | MATCH        |
| adaptation  | `PendingChangesController`                | 6 (incl. accept / reject) | MATCH |
| core        | `AdminDecisionLogController`              | 3              | MATCH        |
| discovery   | `DiscoveryJobsController`                 | 5              | MATCH        |
| discovery   | `DiscoverySourcesController`              | 2              | MATCH        |
| discovery   | `DiscoveryAdminController`                | 5              | MATCH        |
| feedback    | `FeedbackController`                      | 5              | MATCH        |
| feedback    | `ClarificationQueryController`            | 3              | MATCH        |
| household   | `HouseholdsController`                    | 2              | MATCH        |
| household   | `HouseholdSettingsController`             | 4              | MATCH        |
| household   | `HouseholdInvitesController`              | 4              | MATCH        |
| household   | `HouseholdMembersController`              | 4              | MATCH        |
| household   | `HouseholdMergeController`                | 1              | MATCH        |
| household   | `HouseholdSlotConfigurationPlannerViewController` | 1      | MATCH        |
| nutrition   | `TargetsController` / `DailyActivityController` / `IntakeController` / `JournalController` / `IngredientLookupController` / `HealthDirectivesController` / `NutritionRecipeRecalcController` / `NutritionFloorGateController` | ~30 | MATCH |
| planner     | `PlansController`                         | 13             | MATCH        |
| planner     | `AdminPlannerDecisionsController`         | 1              | MATCH        |
| preference  | `HardConstraintsController`               | 3              | MATCH        |
| provisions  | `InventoryController` + `InventoryAdminController` | 8     | MATCH        |
| provisions  | `EquipmentController` / `BudgetController` / `SupplierProductsController` / `WasteController` / `PlannerBundleController` / `CookEventController` / `GroceryImportController` | ~17 | MATCH |
| recipe      | `RecipesController`                       | 8              | MATCH        |
| recipe      | `RecipeBranchesController`                | 3              | MATCH        |
| recipe      | `RecipeVersionsController`                | 3              | MATCH        |
| recipe      | `RecipeSubstitutionsController`           | 5              | MATCH        |
| recipe      | `RecipeImportsController` / `RecipeAdminController` | 2  | MATCH        |

134 endpoints total — full set verified via the set-difference script described
above. No `FOLLOWUP_NEEDED` rows surfaced.

## Remaining Redocly warnings (advisory; not blocking)

Per the ticket: "severity-warning items are advisory; severity-error items are
fixed." These warnings remain after the error fixes. Each is a follow-up
candidate rather than a correctness issue:

1. **`info-license`** (1 warning): `info.license` field is missing. The repo
   doesn't ship a top-level LICENSE file either — adding both belongs in a
   licensing follow-up, not in a spec audit.
2. **`no-server-example.com`** (1 warning): `servers[0].url =
   http://localhost:8080`. Intentional — the local-dev server URL is part of
   the contract for the OpenAPI Generator clients. Suppressing this warning is
   the right move once we move to a versioned `redocly.yaml`.
3. **`no-ambiguous-paths`** (1 warning): `/api/v1/admin/decision-log/trace/{traceId}`
   vs `/api/v1/admin/decision-log/{decisionId}/ancestry`. The Spring router
   resolves these unambiguously (longest-match wins), but Redocly flags the
   pattern. Renaming to `/api/v1/admin/decision-log/by-trace/{traceId}` would
   silence the warning; that's a breaking URL change deferred to a path-rename
   follow-up.
4. **`no-unused-components`** (6 warnings): the shared
   `components.responses.{BadRequest, Unauthorized, NotFound, Conflict,
   UnprocessableEntity, TooManyRequests}` are defined but never `$ref`'d —
   every path file inlines the equivalent `ProblemDetail` content. The
   reusable responses were intended as the canonical reference; converting
   every inline error response to a `$ref` is a multi-file follow-up
   (touches ~12 path files × ~3 responses each). Doing it here violates the
   ticket's "fix what's clearly missing, document the rest as follow-ups" rule.

## Follow-up tickets suggested

- **chore(openapi): adopt `components.responses` refs across all path files**
  — collapses ~150 inline `ProblemDetail` references into shared `$ref`s; drops
  6 of the 9 Redocly warnings.
- **chore(openapi): supply `info.license` + repo `LICENSE`** — picks a
  license, fills `info.license`, commits a top-level `LICENSE`.
- **chore(openapi): rename `/api/v1/admin/decision-log/trace/{traceId}` →
  `/by-trace/{traceId}`** — breaks the ambiguous-paths warning; coordinate with
  any consumer / docs that mention the old path.
- **chore(api): expose request bodies for endpoints that currently document
  only the response** — none surfaced in this audit, but a sweep is worth
  doing once the frontend is integrated and bug reports start arriving.

## Acceptance signal: TypeScript type generation smoke

```text
$ npx -y openapi-typescript@latest src/main/resources/openapi/openapi.yaml -o /tmp/api-types.d.ts
✨ openapi-typescript 7.13.0
🚀 src/main/resources/openapi/openapi.yaml → /tmp/api-types.d.ts [2s]

$ npx -y -p typescript tsc --strict --noEmit /tmp/api-types.d.ts
# exit 0 — zero errors, zero warnings
```

12,677 lines generated. The frontend can adopt this spec as the source of
truth for TypeScript types without manual patching.
