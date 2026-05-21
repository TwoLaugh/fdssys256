# Ticket: infra — 01c OpenAPI Spec Audit + TypeScript Type Generation Sanity Check

## Summary

Verify the OpenAPI spec at `src/main/resources/openapi/openapi.yaml` (and its modular path/schema files under `paths/` and `schemas/`) accurately reflects the controllers as-shipped. Per roadmap A5 in [`design/audits/2026-05-21-frontend-readiness-roadmap.md`](../../design/audits/2026-05-21-frontend-readiness-roadmap.md): the frontend will generate TypeScript types from this spec, and the audit's headline finding ("83 of 83 planned tickets shipped but the OpenAPI auto-update was per-ticket and never sanity-checked end-to-end") means there are likely small omissions and drift.

This ticket is **verification + spec-only changes** — pure additive corrections to the YAML. **No controller changes.** If the audit surfaces a controller-side gap (e.g. an undocumented endpoint, a controller method returning a shape that doesn't match its declared schema), the gap is recorded in a follow-up ticket; this ticket fixes only spec-side errors.

Closes: Tier-A frontend unblock (roadmap A5). Audit-tier work; no capability ID closes by itself but unblocks every other ticket that has been generating types from the spec.

## Behavioural spec

### Phase 1: Static lint pass

1. Run `npx -y @apidevtools/swagger-cli validate src/main/resources/openapi/openapi.yaml`. Any error → fix as a spec-side correction.
2. Run `npx -y @redocly/cli lint src/main/resources/openapi/openapi.yaml --extends recommended`. Severity-warning items are advisory; severity-error items are fixed.
3. The current state of CI: `openapi-lint` step in `.github/workflows/ci.yml` (or equivalent) already runs swagger-cli per the implementation playbook. This ticket strengthens that to Redocly's recommended rule set.

### Phase 2: Controller-vs-spec reconciliation

4. The agent enumerates every `@RestController` method across the 14 modules. For each:
   - Read the controller signature (verb, path, parameters, return type).
   - Locate the corresponding `paths/<module>.yaml` entry.
   - Compare: HTTP verb, path template, request body shape, response shape, status codes, security scheme, query parameters.
5. **Three classes of mismatch** are handled differently:
   - **Spec missing an endpoint that exists in code**: add the path entry. This is the most common drift.
   - **Spec has an endpoint that doesn't exist in code**: remove the path entry. (Or flag for a follow-up if the missing impl is intentional / TODO.)
   - **Spec's shape disagrees with code**: fix the spec to match code (this ticket's scope). If the *code* is wrong, flag a follow-up ticket — fixing controllers requires a separate change.
6. **Reconciliation table** committed at `design/audits/2026-05-21-openapi-reconciliation.md` listing every endpoint examined with verdict: MATCH / SPEC_FIXED / FOLLOWUP_NEEDED. This is the audit evidence.

### Phase 3: Schema shape verification

7. For every DTO referenced in the spec, the agent verifies the YAML schema matches the Java record shape by spot-checking:
   - **Field names** (camelCase consistency — Java records use camelCase, Spring's default Jackson naming is camelCase, OpenAPI properties should be camelCase).
   - **Field types**: `UUID` → `format: uuid`, `Instant` → `format: date-time`, `LocalDate` → `format: date`, `BigDecimal` → `type: number` (no `format`, document precision in `description`).
   - **Required vs optional**: a field is `required` in the schema iff the Java record has `@NotNull` or is a primitive (which can never be null on the wire — but be careful: `int` is required, `Integer` is optional).
   - **Enums**: every Java enum referenced in a DTO has a corresponding `enum: [VAL_A, VAL_B, ...]` schema in the OpenAPI YAML. Drift here is the most common bug.
   - **JSONB / `JsonNode` fields**: these surface as `type: object, additionalProperties: true`. Schemas with `additionalProperties: false` are wrong if the corresponding code field is `JsonNode`.
8. **Pagination envelope**: per `tickets/infra/01b-list-endpoint-pagination-audit.md`, every `Page<X>`-returning endpoint references a shared `PageEnvelope` schema. Verify this schema exists in `openapi.yaml` `components.schemas` and is referenced consistently. If missing, ship it now:
   ```yaml
   PageEnvelope:
     type: object
     required: [content, totalElements, totalPages, number, size, empty, first, last]
     properties:
       content:
         type: array
         description: Override per-endpoint with the concrete item type
         items: { type: object }
       totalElements: { type: integer, format: int64, minimum: 0 }
       totalPages: { type: integer, minimum: 0 }
       number: { type: integer, minimum: 0 }
       size: { type: integer, minimum: 1, maximum: 100 }
       empty: { type: boolean }
       first: { type: boolean }
       last: { type: boolean }
       numberOfElements: { type: integer, minimum: 0 }
       sort:
         type: object
         additionalProperties: true
   ```

### Phase 4: Common shared schemas

9. Verify the existence and correctness of these shared schemas in `openapi.yaml`:
   - `ProblemDetail` (RFC 9457) — already referenced by every error response per `lld/style-guide.md`. If missing fields (e.g. `errors[]` extension for validation errors), add them.
   - `PageEnvelope` (per §8).
   - Common response references: `#/components/responses/NotFound`, `#/components/responses/Unauthorized`, `#/components/responses/Forbidden`, `#/components/responses/Conflict`, `#/components/responses/BadRequest`, `#/components/responses/UnprocessableEntity`.
10. Common security schemes:
    - `cookieAuth` — session cookie (already exists per `auth-01a`). Verify.
    - **Reserve `bearerAuth`** for the service-token authentication landing in `tickets/core/02b-origin-tracking-foundation.md`. Add the scheme definition here (with `type: http, scheme: bearer, bearerFormat: opaque`) so 02b doesn't have to revisit. **Do NOT** apply `bearerAuth` to any path in this ticket — purely a reservation.

### Phase 5: TypeScript type generation smoke

11. Document in the PR description (not committed code): the agent runs `npx -y openapi-typescript src/main/resources/openapi/openapi.yaml -o /tmp/api-types.d.ts` and inspects the output. Any TypeScript compile error in the generated file indicates a spec-side issue that must be fixed.
12. **Acceptance signal**: the generated TS file compiles cleanly under strict mode. The agent runs `npx -y typescript --strict --noEmit /tmp/api-types.d.ts` and reports zero errors.
13. **The generated `.d.ts` is NOT committed** — the frontend repo owns its generation pipeline. This ticket's deliverable is the audit confirming the spec generates clean types.

### Phase 6: Documentation polish

14. Each path entry has a `summary` (≤ 60 chars) and `description` (sentence). Operations missing either get a one-sentence fill from the controller's Javadoc.
15. Each schema has a `description` if the field's semantics are non-obvious. `UUID` fields whose meaning isn't clear from the name (e.g. `actorUserId` vs `userId`) get a one-line description.
16. **No code comment changes** — Javadoc stays as-is; the OpenAPI doc strings are the additive deliverable.

### Cross-cutting

17. **No controller changes**, **no entity changes**, **no migration**. This is a YAML-only ticket plus the audit doc.
18. **No new ArchUnit rules** — the OpenAPI lint step in CI is the enforcement.
19. **Strengthen the CI gate**: if `openapi-lint` in CI uses only `swagger-cli validate`, add `redocly lint --extends recommended` as a second step. The `.github/workflows/ci.yml` (or equivalent) gains 5 lines.

### Events

20. **None.** Spec-side ticket.

## Database

**No migration.**

## OpenAPI updates

The ticket IS the OpenAPI update. The expected scope:
- Addition of `PageEnvelope` shared schema (if absent).
- Addition of `bearerAuth` security scheme (reservation; no usage).
- Per-module spec fixes — exact list emerges from the audit. Plausible candidates based on the audit's "spec was auto-updated through PRs but worth one sanity check":
  - Missing `Cache-Control` / `Location` response header documentation on relevant endpoints.
  - Missing `cookieAuth` security entries on a handful of paths.
  - Enum drift between code and spec (a recently-added enum value not propagated to the YAML).
  - Pagination query params (`page`, `size`, `sort`) missing on List endpoints — overlap with `tickets/infra/01b`; coordinate to avoid double-touching.

## Edge-case checklist

- [ ] `npx -y @apidevtools/swagger-cli validate src/main/resources/openapi/openapi.yaml` exits 0.
- [ ] `npx -y @redocly/cli lint src/main/resources/openapi/openapi.yaml --extends recommended` exits 0 (warnings ok, no errors).
- [ ] `npx -y openapi-typescript src/main/resources/openapi/openapi.yaml -o /tmp/api-types.d.ts` exits 0.
- [ ] `npx -y typescript --strict --noEmit /tmp/api-types.d.ts` exits 0.
- [ ] **Reconciliation doc** at `design/audits/2026-05-21-openapi-reconciliation.md` lists every endpoint with verdict.
- [ ] **Every controller method** has a corresponding OpenAPI path entry (or is flagged as a follow-up).
- [ ] **Every OpenAPI path entry** has a corresponding controller method (or is flagged for removal).
- [ ] **Every Java DTO record** referenced in a path has a matching schema in `openapi.yaml` or a module-local `schemas/<module>.yaml`.
- [ ] **Every Java enum** referenced in a DTO has a matching `enum: [...]` in the spec.
- [ ] **ProblemDetail** schema present in `components.schemas`, includes `type`, `title`, `status`, `detail`, `instance`, `errors[]` (optional extension for validation responses).
- [ ] **PageEnvelope** schema present.
- [ ] **bearerAuth** security scheme reserved.
- [ ] Every `Page<X>` response references `PageEnvelope` (or inlines an equivalent shape — both are valid).
- [ ] Every error response references a shared response (`#/components/responses/NotFound`, etc.) — no inline duplicates.
- [ ] **No `additionalProperties: true` on schemas that should be closed** — only on JSONB / `JsonNode` payload fields.
- [ ] **Camel-case consistency**: no `snake_case` property names in any schema (Jackson serialises as camelCase by project convention).
- [ ] **Date format consistency**: `Instant` → `format: date-time`; `LocalDate` → `format: date`; `LocalTime` → `format: time`; never bare strings.
- [ ] **UUID format consistency**: every UUID field has `format: uuid`.
- [ ] **`cookieAuth`** applied to every authenticated path (visual scan + count: roughly the number of `@RestController` methods minus public auth endpoints minus actuator).
- [ ] **PR description** includes the TS-type-gen smoke output (zero errors) as the acceptance signal.
- [ ] **CI `.github/workflows/ci.yml`** strengthened to run both `swagger-cli validate` and `redocly lint`.

## Files this ticket touches

```
NEW   design/audits/2026-05-21-openapi-reconciliation.md                       (audit doc)

MOD   src/main/resources/openapi/openapi.yaml                                  (PageEnvelope schema, bearerAuth scheme, ProblemDetail fixes)
MOD   src/main/resources/openapi/paths/*.yaml                                  (per-endpoint fixes — exact list emerges from audit)
MOD   src/main/resources/openapi/schemas/*.yaml                                (per-DTO fixes)

MOD   .github/workflows/ci.yml                                                 (add redocly lint step)
```

Estimated touch: ~10-30 YAML files modified, depending on audit findings. Estimated agent runtime 120-180 min (most of which is the reconciliation pass).

## Dependencies

- **Hard dependency**: every shipped controller across the 14 modules — the audit reads them all.
- **Soft dependency**: `tickets/infra/01b-list-endpoint-pagination-audit.md` — if 01b lands first, its pagination spec changes are already in place and 01c verifies them; if 01c lands first, 01b's spec changes overlap (review for conflicts).
- **No code dependencies** — spec-only changes.

## Acceptance / DoD

- [ ] `./mvnw -Dspotless.check.skip=true verify` passes (no test changes, but the contract tests run against the updated spec)
- [ ] `./mvnw spotless:apply` then `./mvnw spotless:check` clean
- [ ] CI green (build + spotless + OpenAPI lint with strengthened gate)
- [ ] All edge-case items above ticked
- [ ] Audit doc committed
- [ ] PR description includes the TypeScript type-gen smoke output

## What's NOT in scope

- **Controller changes**: any code-side gap surfaced by the audit is flagged for a follow-up ticket, not fixed here.
- **OpenAPI 3.1 upgrade**: if the spec is OpenAPI 3.0, leave it; the upgrade is a separate piece of work.
- **API versioning strategy**: `/api/v1` is the current convention; how `/api/v2` will be introduced is out of scope.
- **Code generation pipeline for the backend**: this project hand-authors the spec by convention. That choice is not revisited here.
- **Doc-comments inside Java DTO records**: per `lld/style-guide.md` the spec is the source of truth, not Javadoc. No Javadoc changes.

Squash-merge with: `chore(infra): 01c — OpenAPI spec audit + TS type-gen smoke + redocly lint`
