# Ticket: preference — 01a Hard-Constraints Aggregate

## Summary

Implement the `preference` module's hard-constraints aggregate (root + 4 children + audit log) plus the GET / PUT / audit-log REST endpoints. Per [`lld/preference.md`](../../lld/preference.md). Does NOT include `HardConstraintFilterService` (deferred to 01b), the `@ValidDietaryIdentity` custom validator (deferred to 01c), or the seeded `allergen_derivatives` reference table (deferred to 01b alongside the filter that consumes it).

This is the first preference ticket; the module is currently empty. Builds on the conventions established by `auth` and `core`.

**Trial purpose**: this is the first ticket using the validated agent self-verify loop (see `lld/implementation-playbook.md §Verification model`). Agent runs `./mvnw test` end-to-end and iterates until green before reporting back.

## Behavioural spec

### Aggregate shape

1. `HardConstraints` is the aggregate root, one row per user (`user_id` UNIQUE). Fields: `id, userId, allergies (text[]), dietaryIdentityBase (varchar 32), dietaryIdentityLabel (varchar 64, nullable), medicalDiets (text[]), version (@Version), createdAt, updatedAt`.
2. `DietaryIdentityException` (allows / frequency / context) — many per parent. `@ManyToOne(fetch = LAZY)` back to `HardConstraints`. Cascade ALL + orphanRemoval.
3. `HardIntolerance` (substance / severity / notes) — many per parent. Same cascade.
4. `AgeRestriction` (ruleKey / autoPopulated) — many per parent. Same cascade.
5. `HardConstraintsAuditLog` — append-only. One row per *field change* on update. Carries `actorUserId, fieldChanged (varchar 64), previousValueJson (jsonb), newValueJson (jsonb), occurredAt`. No `@Version`, no `@LastModifiedDate`, no setters for fields after construction.
6. The Postgres `text[]` columns map to Java `List<String>` via Hibernate's array support; if that's awkward, fall back to `varchar(255)` JSON-encoded strings — but use the native `text[]` if it works cleanly.

### Endpoints

7. `GET /api/v1/preferences/hard-constraints` — returns the calling user's `HardConstraintsDto` (200) or 404 if no row exists yet. Authentication required (cookie via `SessionAuthenticationFilter` from auth-01a). The `userId` is resolved server-side via `CurrentUserResolver`; never accepted from a query parameter.
8. `PUT /api/v1/preferences/hard-constraints` — accepts `UpdateHardConstraintsRequest`. Must include `expectedVersion` matching the row's current `@Version` value; mismatch → 409 `OptimisticLockException` mapped to ProblemDetail. On success: replaces the aggregate's children (cascade + orphanRemoval handles delete + insert), bumps version, returns the updated DTO. **Writes one audit-log row per field that actually changed** (compare old vs. new values; skip no-op fields). 
9. `GET /api/v1/preferences/hard-constraints/audit-log?page=&size=` — paginated audit log for the calling user, `Page<HardConstraintsAuditEntryDto>` newest-first. Pagination uses Spring's standard `Pageable`.
10. **No POST.** The aggregate is initialised by `auth` at user-creation via a future `initialiseHardConstraints(userId)` method on the update service — out of scope for 01a; expose the method but call it from a unit test only.

### Validation

11. `UpdateHardConstraintsRequest`: `@NotNull` on every field; `@NotBlank` on each list element; `@Size(max=64)` on string fields; `@Min(0)` on `expectedVersion`. **No** `@ValidDietaryIdentity` custom validator yet — that's 01c. For now `dietaryIdentityBase` is just a `String` validated as `@NotBlank`, length-bounded.
12. Validation failures → 400 `MethodArgumentNotValidException` mapped to ProblemDetail with `errors[]` (already wired in `GlobalExceptionHandler` from auth-01a).

### Cross-cutting

13. New `PreferenceException` module-root exception; `HardConstraintsNotFoundException` extends it (404). Add handler in `GlobalExceptionHandler` for both.
14. `OptimisticLockException` already has a 409 handler in `GlobalExceptionHandler` (added in auth-01c) — no change needed.
15. ArchUnit per-module repo-private rule: add a `preferenceReposAreInternalToPreference` rule alongside the existing `core` and `auth` rules in `ModuleBoundaryTest`. Same shape, different package prefix.
16. `PreferenceModule` facade re-exports the public service interfaces (mirror `auth/AuthModule.java` + `core/CoreModule.java`).

### Events

17. `HardConstraintsUpdatedEvent` published `AFTER_COMMIT` carrying `(UUID userId, Set<String> fieldsChanged, UUID traceId, Instant occurredAt)`. Implements `core.events.ScopeChangedEvent` with `scopeKind="hard-constraints"`, `scopeId=userId`. No listeners in 01a — emitted for downstream consumers (recipe filter cache invalidation in 01b, planner re-opt trigger in a later ticket).

## Database

```
src/main/resources/db/migration/V20260601300000__preference_create_hard_constraints.sql
```

Schema mirrors [`lld/preference.md` §V…__hard_constraints](../../lld/preference.md#L66) — exclude the `allergen_derivatives` table (01b) and the `R__preference_seed_allergen_derivatives.sql` repeatable migration (01b). The five tables for 01a:

- `preference_hard_constraints` (root)
- `preference_dietary_identity_exceptions`
- `preference_hard_intolerances`
- `preference_age_restrictions`
- `preference_hard_constraints_audit`

Use timestamp **V20260601300000** (after `core` and `auth` which are V20260601100000+ and V20260601200000+).

## OpenAPI updates

Add 3 paths and 5 schemas to `src/main/resources/openapi/openapi.yaml`. Use `cookieAuth` security on every path (already defined in auth-01a). Schemas:

- `HardConstraintsDto`, `DietaryIdentityDto`, `DietaryIdentityExceptionDto`, `HardIntoleranceDto`, `AgeRestrictionDto`, `UpdateHardConstraintsRequest`, `HardConstraintsAuditEntryDto`

Preserve all existing paths/schemas — append, don't replace.

## Edge-case checklist

- [ ] GET on a user with no aggregate row → 404 `HardConstraintsNotFoundException`
- [ ] PUT with stale `expectedVersion` → 409 (concurrent-update ProblemDetail)
- [ ] PUT replacing all 3 child collections — cascade+orphanRemoval cleanly deletes old and inserts new
- [ ] Audit log only records *changed* fields (no row written for no-op PUT)
- [ ] Audit log captures the full old/new JSON for the changed field, not just diff
- [ ] Anonymous request → 401 (filter chain rejects before controller)
- [ ] Authenticated request for user A cannot read/update user B's aggregate (the resolver returns A's userId; never accepts userId from request)
- [ ] OpenAPI request/response shapes match (contract test)
- [ ] ArchUnit: no cross-module repo imports
- [ ] Initial `initialiseHardConstraints(userId)` test confirms the row is created with sensible defaults (`omnivore` base, empty collections)

## Files this ticket touches

```
src/main/resources/db/migration/V20260601300000__preference_create_hard_constraints.sql   new
src/main/java/com/example/mealprep/preference/PreferenceModule.java                       new (facade)
src/main/java/com/example/mealprep/preference/api/controller/HardConstraintsController.java   new
src/main/java/com/example/mealprep/preference/api/dto/HardConstraintsDto.java             new (record)
src/main/java/com/example/mealprep/preference/api/dto/DietaryIdentityDto.java             new (record)
src/main/java/com/example/mealprep/preference/api/dto/DietaryIdentityExceptionDto.java    new (record)
src/main/java/com/example/mealprep/preference/api/dto/HardIntoleranceDto.java             new (record)
src/main/java/com/example/mealprep/preference/api/dto/AgeRestrictionDto.java              new (record)
src/main/java/com/example/mealprep/preference/api/dto/UpdateHardConstraintsRequest.java   new (record)
src/main/java/com/example/mealprep/preference/api/dto/HardConstraintsAuditEntryDto.java   new (record)
src/main/java/com/example/mealprep/preference/api/mapper/HardConstraintsMapper.java       new (MapStruct)
src/main/java/com/example/mealprep/preference/domain/entity/HardConstraints.java          new
src/main/java/com/example/mealprep/preference/domain/entity/DietaryIdentityException.java new
src/main/java/com/example/mealprep/preference/domain/entity/HardIntolerance.java          new
src/main/java/com/example/mealprep/preference/domain/entity/AgeRestriction.java           new
src/main/java/com/example/mealprep/preference/domain/entity/HardConstraintsAuditLog.java  new
src/main/java/com/example/mealprep/preference/domain/repository/HardConstraintsRepository.java   new
src/main/java/com/example/mealprep/preference/domain/repository/HardConstraintsAuditLogRepository.java   new
src/main/java/com/example/mealprep/preference/domain/service/PreferenceQueryService.java          new (interface, partial — only hard-constraints methods)
src/main/java/com/example/mealprep/preference/domain/service/PreferenceUpdateService.java         new (interface, partial)
src/main/java/com/example/mealprep/preference/domain/service/internal/PreferenceServiceImpl.java  new
src/main/java/com/example/mealprep/preference/event/HardConstraintsUpdatedEvent.java      new
src/main/java/com/example/mealprep/preference/exception/PreferenceException.java          new
src/main/java/com/example/mealprep/preference/exception/HardConstraintsNotFoundException.java   new
src/main/resources/openapi/openapi.yaml                                                   modified
src/main/java/com/example/mealprep/config/GlobalExceptionHandler.java                     modified (1 new handler)
src/test/java/com/example/mealprep/archunit/ModuleBoundaryTest.java                       modified (add preferenceReposAreInternalToPreference rule)
src/test/java/com/example/mealprep/preference/HardConstraintsServiceImplTest.java         new (unit)
src/test/java/com/example/mealprep/preference/HardConstraintsFlowIT.java                  new (full IT — register user → seed aggregate → GET → PUT → audit log)
src/test/java/com/example/mealprep/preference/testdata/HardConstraintsTestData.java       new (Test Data Builder)
```

## Dependencies

- **Hard dependency**: `core-01-decision-log` (merged) — uses `core.events.ScopeChangedEvent` for the auth event base.
- **Hard dependency**: `auth-01a` (merged) — uses `CurrentUserResolver` for `userId` and `SessionAuthenticationFilter` for endpoint authentication.

## Acceptance / DoD

- [ ] `./mvnw -DskipITs -Dspotless.check.skip=true test` passes locally on the agent's worktree
- [ ] `./mvnw spotless:apply` then `./mvnw spotless:check` passes
- [ ] If Docker is available on the host, `./mvnw verify` passes (else CI catches the IT layer)
- [ ] CI green on the PR (build + spotless + OpenAPI lint + pitest)
- [ ] All edge-case items above ticked
- [ ] No raw passwords in any log (no auth interaction here, but the convention persists)

Squash-merge with: `feat(preference): 01a — hard-constraints aggregate + GET/PUT/audit-log endpoints`
