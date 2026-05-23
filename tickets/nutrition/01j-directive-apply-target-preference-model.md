# Ticket: nutrition + preference — 01j Wire `DirectiveApplyTarget` for `preference_model` Health Directives

## Summary

Provide a **real `DirectiveApplyTarget` `@Component`** (on the preference side) to replace the throwing Noop at [`src/main/java/com/example/mealprep/nutrition/spi/internal/NoopDirectiveApplyTarget.java:46-62`](../../src/main/java/com/example/mealprep/nutrition/spi/internal/NoopDirectiveApplyTarget.java). The directive-accept flow is fully wired: `NutritionServiceImpl` calls `directiveApplier.apply(directive, effective, actorUserId)` ([`NutritionServiceImpl.java:1759-1760`](../../src/main/java/com/example/mealprep/nutrition/domain/service/internal/NutritionServiceImpl.java)), which routes `mapsToModel = "preference_model"` to the SPI ([`DirectiveApplier.java:76, 195-205`](../../src/main/java/com/example/mealprep/nutrition/domain/service/internal/DirectiveApplier.java)). Today the only `DirectiveApplyTarget` bean is the Noop, which throws `DirectiveApplyTargetUnavailableException` (422) — so a health directive routed to the preference model fails the accept with "preference module not wired for this yet." This ticket makes that route apply real preference state.

Per [`lld/nutrition.md:1014-1016`](../../lld/nutrition.md): *"`preference_model` → `PreferenceUpdateService.updateHardConstraints` joining this transaction. `temporary` and `autoExpiresAt` persisted with the constraint so preference can expire it."* And [`lld/nutrition.md:1022`](../../lld/nutrition.md) (auto-expiry sweep): *"instructs the source module to revert (`PreferenceUpdateService.removeTemporaryConstraint(...)`)."*

Ships:
- A real `DirectiveApplyTarget` `@Component` (preference module) implementing `applyPreferenceDirective(...)`, translating the directive instruction into a `PreferenceUpdateService.updateHardConstraints` call, recording `temporary`/`autoExpiresAt` so the constraint can later be expired.
- **`PreferenceUpdateService.removeTemporaryConstraint(...)`** — this method does **NOT exist today** (verified). It must be added so the deferred directive auto-expiry sweep ([`HealthDirectiveRepository.findByStatusAndAutoExpiresAtBefore`, `HealthDirectiveRepository.java:31`](../../src/main/java/com/example/mealprep/nutrition/domain/repository/HealthDirectiveRepository.java), no caller yet) has its preference-side reversal surface.

Closes: **C4** — the `preference_model` health-directive apply path. The wiring target `updateHardConstraints` exists ([`PreferenceServiceImpl.java:138`](../../src/main/java/com/example/mealprep/preference/domain/service/internal/PreferenceServiceImpl.java), interface at [`PreferenceUpdateService.java:26`](../../src/main/java/com/example/mealprep/preference/domain/service/PreferenceUpdateService.java)); only the SPI bean + the expiry surface are missing.

**Dependency / ordering**: standalone. `updateHardConstraints` is merged (preference-01a). `nutrition-01e` (health directives queue, the `DirectiveApplier` + accept flow) is merged.

## Behavioural spec

### The SPI contract (fixed)

[`DirectiveApplyTarget.java:28-34`](../../src/main/java/com/example/mealprep/nutrition/spi/DirectiveApplyTarget.java):

```java
void applyPreferenceDirective(
    UUID userId,
    DirectiveInstructionDocument instruction,  // effective instruction, past the safety gate
    boolean temporary,
    Instant autoExpiresAt,                      // null when temporary == false
    UUID directiveId,                           // for audit-log linkage
    UUID actorUserId);                          // who pressed accept
```

[`DirectiveInstructionDocument.java:17-22`](../../src/main/java/com/example/mealprep/nutrition/api/dto/DirectiveInstructionDocument.java) carries `action`, `target`, `scope`, `duration`, `extras` (a `Map<String, JsonNode>`).

The SPI javadoc ([`DirectiveApplyTarget.java:18-23`](../../src/main/java/com/example/mealprep/nutrition/spi/DirectiveApplyTarget.java)) mandates: **implementations MUST join the caller's transaction** (no `@Transactional(REQUIRES_NEW)`) so a downstream failure rolls back the directive's status update too. `DirectiveApplier.applyToPreferenceTarget` ([`DirectiveApplier.java:195-205`](../../src/main/java/com/example/mealprep/nutrition/domain/service/internal/DirectiveApplier.java)) resolves the bean via `ObjectProvider.getObject()` and the whole call runs inside `NutritionServiceImpl`'s accept tx.

### Replace the Noop with a real preference-side impl

1. New `@Component PreferenceDirectiveApplyTarget` in the **preference module** (`preference.spi.internal`, mirroring how `NoopDirectiveApplyTarget` describes the in-module bean it expects at [`NoopDirectiveApplyTarget.java:19-20`](../../src/main/java/com/example/mealprep/nutrition/spi/internal/NoopDirectiveApplyTarget.java)) implementing `com.example.mealprep.nutrition.spi.DirectiveApplyTarget`. As a plain `@Component`, it out-ranks the Noop `@Bean @ConditionalOnMissingBean(DirectiveApplyTarget.class)` ([`NoopDirectiveApplyTarget.java:40-44`](../../src/main/java/com/example/mealprep/nutrition/spi/internal/NoopDirectiveApplyTarget.java)) so the Noop steps aside (same SPI-with-Noop pattern as `SoftPreferencesReader`). Keep the Noop in place (do not delete) for test slices that don't load preference.
2. **No `@Transactional` annotation** on `applyPreferenceDirective` (or `@Transactional` REQUIRED — never `REQUIRES_NEW`) so it joins `NutritionServiceImpl`'s directive-accept tx per the SPI contract.

### Map the directive to a hard-constraint update

3. The `preference_model` route per [`lld/nutrition.md:1009, 1016`](../../lld/nutrition.md) handles directives like `INGREDIENT_RESTRICTION` / `ELIMINATION_TRIAL` (e.g. "6-week egg elimination") — these become **hard constraints** (intolerances/exceptions), NOT taste-profile soft prefs. Translate `instruction` to an `UpdateHardConstraintsRequest`:
   - Read the current aggregate first. `updateHardConstraints` is a **full-document replacement** with an `expectedVersion` ([`PreferenceServiceImpl.java:138-191`](../../src/main/java/com/example/mealprep/preference/domain/service/internal/PreferenceServiceImpl.java)) — it diffs and writes one audit row per changed field. So the impl must: load the user's current `HardConstraintsDto` (via the in-module aggregate / `PreferenceQueryService.getHardConstraints`), build a new `UpdateHardConstraintsRequest` that is the current constraints **plus** the directive's restriction (e.g. add the `target` ingredient to `intolerances`/the medical-diet exceptions), and pass the current `version` as `expectedVersion`.
   - **GOTCHA — actor**: `updateHardConstraints(userId, request, actorUserId)` writes the audit row with `actorUserId`. Pass `actorUserId` (who pressed accept) — the directive provenance (`directiveId`) needs a home too (see §5).
4. **`action` → field mapping** (allow-set; unknown → throw a mapped 422 so the accept surfaces a clear error rather than silently no-op'ing — preserve the Noop's "clear error, not silent no-op" intent at [`NoopDirectiveApplyTarget.java:16-17`](../../src/main/java/com/example/mealprep/nutrition/spi/internal/NoopDirectiveApplyTarget.java)):
   - `restrict_ingredient` / `INGREDIENT_RESTRICTION` → add `instruction.target()` to the appropriate hard-constraint list (intolerance or dietary-identity exception — pick per the directive `scope`).
   - `ELIMINATION_TRIAL` (a time-boxed restriction) → same add, but `temporary == true` (see §6).
   - Any directive whose `action` does not map to a hard-constraint mutation → `InvalidDirectivePreferenceRouteException` (new, 422). **Worth implementer review**: the LLD only enumerates ingredient-restriction-style directives for `preference_model`; a `target_adjustment` should never reach here (it routes `nutrition_model`), so an unmapped action is a routing bug worth surfacing loudly.

### Temporary constraints + auto-expiry surface

5. **Provenance**: record `directiveId` so the constraint can be linked back / expired. `HardConstraints` + its audit table do NOT today carry a `source_directive_id` (verify the schema at agent start). **Decision (worth user review)**: either (a) add a nullable `source_directive_id` + `auto_expires_at` column on the hard-constraint child rows (the cleanest — lets `removeTemporaryConstraint` target exactly the directive's additions), or (b) store the directive linkage in the audit row only and have `removeTemporaryConstraint` reverse by `(userId, target)` value. **Recommendation: option (a)** — a small migration adding `auto_expires_at timestamptz NULL` + `source_directive_id uuid NULL` to the relevant hard-constraint child table, so a temporary constraint is self-describing and the expiry sweep is a clean by-column query. This is the minimal schema work the LLD's "persisted with the constraint so preference can expire it" ([`lld/nutrition.md:1016`](../../lld/nutrition.md)) requires.
6. When `temporary == true`, stamp `autoExpiresAt` (and `source_directive_id`) on the added constraint row(s) so the future expiry sweep can find them.
7. **`PreferenceUpdateService.removeTemporaryConstraint(...)` — NEW METHOD (does not exist today)**. The LLD references it at [`lld/nutrition.md:1022`](../../lld/nutrition.md) and the preference LLD's update interface ([`lld/preference.md:556-581`](../../lld/preference.md)) does NOT list it — confirmed absent in `PreferenceUpdateService` ([`PreferenceUpdateService.java`](../../src/main/java/com/example/mealprep/preference/domain/service/PreferenceUpdateService.java) has only `initialiseHardConstraints` + `updateHardConstraints`). Add:
   ```java
   /**
    * Reverse a temporary, directive-sourced hard constraint when its directive auto-expires.
    * Best-effort: a constraint the user has since edited away is a no-op. Writes an audit row
    * (actor = the system / directive expiry) and bumps @Version. Idempotent.
    */
   void removeTemporaryConstraint(UUID userId, UUID directiveId);
   ```
   Implement it on `PreferenceServiceImpl`: find the child rows with `source_directive_id = directiveId` (and `auto_expires_at` set), remove them, write audit rows, publish `HardConstraintsUpdatedEvent` ([`PreferenceServiceImpl.java:183-184`](../../src/main/java/com/example/mealprep/preference/domain/service/internal/PreferenceServiceImpl.java) shows the existing publish). **Scope note**: this ticket ADDS the method + impl so the surface exists; **wiring the nutrition auto-expiry `@Scheduled` sweep that CALLS it is its own concern** — the repo query exists (`findByStatusAndAutoExpiresAtBefore`) but has no caller (noted at [`HealthDirectiveRepository.java:18-19`](../../src/main/java/com/example/mealprep/nutrition/domain/repository/HealthDirectiveRepository.java)). **Decision (worth user review)**: include the nutrition expiry sweep here (it is small and completes the temporary-constraint story), OR leave it for a follow-up and ship only the reversal surface. **Recommendation: ship the reversal surface (`removeTemporaryConstraint`) here; flag the nutrition `@Scheduled` expiry sweep as a fast follow-up** so this ticket stays focused on closing the C4 apply gap.

### Audit + version

8. The `updateHardConstraints` call already writes per-field audit rows ([`PreferenceServiceImpl.java:195-209`](../../src/main/java/com/example/mealprep/preference/domain/service/internal/PreferenceServiceImpl.java)) and bumps `@Version`. The directive-sourced apply rides that machinery — the only new audit content is the directive provenance (`source_directive_id`, §5). **Origin attribution**: per [`design/origin-tracking-pattern.md`], the change is system/directive-driven; pass `actorUserId` (the accepting user) as the audit actor, but mark the origin as health-directive in whatever origin field the hard-constraint audit carries (verify; if absent, the `source_directive_id` linkage is sufficient provenance for v1).

### Cross-cutting

9. **New exception** `InvalidDirectivePreferenceRouteException` (422) — mapped in the **per-module `PreferenceExceptionHandler`** (`@RestControllerAdvice`), never the global one. Note `applyPreferenceDirective` is in-process (invoked by the nutrition `DirectiveApplier`), so a thrown 422 propagates up through `DirectiveApplier.apply` to `NutritionServiceImpl`'s accept flow. **GOTCHA**: confirm how the nutrition accept surface maps a preference-side exception — today the Noop throws `DirectiveApplyTargetUnavailableException` (nutrition-owned, 422). When the real impl throws a *preference*-owned exception, the nutrition accept controller's handler must map it (or the impl wraps preference failures in the nutrition-side `DirectiveApplyTargetUnavailableException` to keep the 422 contract module-local). **Recommendation: wrap** — catch preference-domain failures inside `applyPreferenceDirective` and rethrow as the nutrition-contract exception, OR (cleaner) keep the SPI contract throwing a nutrition-owned exception type and let the new preference exception stay internal. **Worth implementer review** — the key invariant is the accept endpoint returns a clean 422, never a 500.
10. **ArchUnit**: the new `@Component` lives in `preference.spi.internal` and implements a `nutrition.spi` interface — this is the established cross-module SPI direction (the impl module depends on the SPI-owner's `spi` package). Confirm `PreferenceBoundaryTest` / `NutritionBoundaryTest` allow the `nutrition.spi` import from preference. (Mirror how `SoftPreferencesReader`'s preference-side impl is expected to import `household.spi`.)
11. **`ObjectProvider` resolution**: `DirectiveApplier` injects `ObjectProvider<DirectiveApplyTarget>` and calls `.getObject()` ([`DirectiveApplier.java:47, 197`](../../src/main/java/com/example/mealprep/nutrition/domain/service/internal/DirectiveApplier.java)) — with one real `@Component` present, `getObject()` returns it; with only the Noop, it returns the Noop. No change to `DirectiveApplier`.

### Events

12. **Published**: `HardConstraintsUpdatedEvent` (from the `updateHardConstraints` / `removeTemporaryConstraint` calls — already wired). The nutrition accept flow publishes `HealthDirectiveAcceptedEvent` ([`NutritionServiceImpl.java:1770-1774`](../../src/main/java/com/example/mealprep/nutrition/domain/service/internal/NutritionServiceImpl.java)) after commit, unchanged. **Consumed**: none new.

## Database

```
NEW   src/main/resources/db/migration/V2026..__preference_add_hard_constraint_directive_provenance.sql   (per §5 option a — verify timestamp slot at merge)
```

If taken (recommended):
```sql
ALTER TABLE preference_hard_constraint_intolerances  ADD COLUMN auto_expires_at    timestamptz NULL;
ALTER TABLE preference_hard_constraint_intolerances  ADD COLUMN source_directive_id uuid       NULL;
-- (and/or the dietary-identity-exception child table, depending on the §4 scope mapping — verify
--  the actual hard-constraint child table names at agent start)
```
No nutrition-side schema change (the directive entity already carries `auto_expires_at` — `findByStatusAndAutoExpiresAtBefore` exists).

## OpenAPI updates

**No OpenAPI changes.** `applyPreferenceDirective` is an in-process SPI call; `removeTemporaryConstraint` is invoked in-process by the (future) expiry sweep — neither has an HTTP surface. The directive-accept endpoint's contract is unchanged (it returns 200 on a now-successful apply instead of 422).

## Edge-case checklist

- [ ] **Migration applies cleanly** (if added); `FlywayMigrationIT` (preference) passes; `ddl-auto=validate` accepts the columns.
- [ ] **Real bean wins**: with the preference module on the classpath, `DirectiveApplier`'s `ObjectProvider.getObject()` resolves the real `PreferenceDirectiveApplyTarget`, NOT the Noop (verified by an IT asserting no `DirectiveApplyTargetUnavailableException`).
- [ ] **Noop still present off-classpath**: a nutrition-only test slice (no preference) still gets the Noop → 422 (unchanged; the Noop is not deleted).
- [ ] **Ingredient restriction applied**: accept an `INGREDIENT_RESTRICTION` directive (`target = "egg"`) → "egg" appears in the user's hard constraints; one audit row per changed field; `@Version` bumped; directive status → ACCEPTED.
- [ ] **Temporary constraint**: a 6-week `ELIMINATION_TRIAL` (`temporary = true`, `autoExpiresAt = now+6w`) → the added constraint row carries `auto_expires_at` + `source_directive_id`.
- [ ] **`removeTemporaryConstraint` reverses**: calling `removeTemporaryConstraint(userId, directiveId)` removes exactly the directive's added constraint(s), writes audit rows, bumps `@Version`, publishes `HardConstraintsUpdatedEvent`.
- [ ] **`removeTemporaryConstraint` idempotent**: a second call (or a constraint the user already edited away) is a no-op — no audit row, no event, no throw.
- [ ] **Transaction join (decision-log / SPI contract)**: a forced failure inside `applyPreferenceDirective` rolls back the directive's status update too (the directive stays `PENDING_REVIEW`, no hard-constraint write persists) — IT asserts atomicity.
- [ ] **Unmapped action → clean 422**: a directive whose `action` doesn't map to a hard-constraint mutation → the accept endpoint returns 422 (not 500); directive stays `PENDING_REVIEW`.
- [ ] **Optimistic-version handling**: a concurrent user PUT to hard-constraints racing the directive apply surfaces as `OptimisticLockingFailureException` inside the apply tx → directive accept fails cleanly (acceptable; user retries accept).
- [ ] **Cross-tenant**: a directive for user A never mutates user B's constraints.
- [ ] **No double-add**: re-accepting is blocked upstream (`HealthDirectiveAlreadyDecidedException`, [`lld/nutrition.md:1006`](../../lld/nutrition.md)); the apply itself dedups (adding an already-present constraint is a no-op diff → no audit row).
- [ ] **`HealthDirectivesFlowIT`** extended: the `preference_model` accept path now succeeds end-to-end (was asserting 422).

## Files this ticket touches

```
NEW   src/main/java/com/example/mealprep/preference/spi/internal/PreferenceDirectiveApplyTarget.java               (real DirectiveApplyTarget @Component)
MOD   src/main/java/com/example/mealprep/preference/domain/service/PreferenceUpdateService.java                    (add removeTemporaryConstraint)
MOD   src/main/java/com/example/mealprep/preference/domain/service/internal/PreferenceServiceImpl.java             (removeTemporaryConstraint impl + temporary-constraint stamping helper)
NEW   src/main/java/com/example/mealprep/preference/exception/InvalidDirectivePreferenceRouteException.java        (422)
MOD   src/main/java/com/example/mealprep/preference/exception/PreferenceExceptionHandler.java                      (map the 422)
NEW   src/main/resources/db/migration/V2026..__preference_add_hard_constraint_directive_provenance.sql            (per §5 option a)

NEW   src/test/java/com/example/mealprep/preference/PreferenceDirectiveApplyTargetTest.java                        (unit — action mapping, temporary stamping, unmapped-action 422)
NEW   src/test/java/com/example/mealprep/preference/RemoveTemporaryConstraintTest.java                             (unit — reverse, idempotent)
NEW   src/test/java/com/example/mealprep/nutrition/DirectivePreferenceRouteIT.java                                 (Testcontainers — accept preference_model directive → hard constraint written, atomicity)
MOD   src/test/java/com/example/mealprep/nutrition/HealthDirectivesFlowIT.java                                     (preference_model accept now 200, not 422)
MOD   src/test/java/com/example/mealprep/nutrition/DirectiveApplierTest.java                                       (preference route now invokes the real target, not the throwing Noop)
```

Total: ~5 new + 4 mods. Estimated agent runtime 1-2 days (the full-document `updateHardConstraints` round-trip translation + the temporary-constraint provenance migration + the cross-module atomicity IT dominate).

## Dependencies

- **Hard dependency**: `preference-01a` (merged) — `PreferenceUpdateService.updateHardConstraints`, `HardConstraints` aggregate + audit, `PreferenceExceptionHandler`, `HardConstraintsUpdatedEvent`.
- **Hard dependency**: `nutrition-01e` (merged) — `DirectiveApplier`, `DirectiveApplyTarget` SPI, `DirectiveInstructionDocument`, the directive-accept flow, `NoopDirectiveApplyTarget`.
- **Soft / informs**: `core-02b` origin-tracking (merged) — the audit origin convention for the directive-sourced change.

## Acceptance / DoD

- [ ] `./mvnw -Dspotless.check.skip=true verify` passes
- [ ] `./mvnw spotless:apply` then `./mvnw spotless:check` clean
- [ ] **Run the full preference + nutrition module IT suites locally with Docker** + spotless before pushing. Docker IS available locally. Run ITs in ~3-class batches (Hikari pool-exhaustion flake on big sweeps).
- [ ] CI green (build + spotless + OpenAPI lint + ArchUnit + contract tests)
- [ ] All edge-case items above ticked
- [ ] PR description traces: accept an `INGREDIENT_RESTRICTION(target=egg, temporary, autoExpiresAt=+6w)` directive → `DirectiveApplier` routes `preference_model` → real target adds "egg" to hard constraints with `source_directive_id` → directive ACCEPTED, all atomic; `removeTemporaryConstraint` reverses it.

## What's NOT in scope

- **The nutrition `@Scheduled` directive auto-expiry sweep** that calls `removeTemporaryConstraint` — recommend a fast follow-up; the repo query (`findByStatusAndAutoExpiresAtBefore`) exists but this ticket only ships the *reversal surface*, not the scheduler. (Worth user review — could be folded in.)
- **`nutrition_model` directives** — already implemented (`DirectiveApplier.applyToNutritionTargets`, [`DirectiveApplier.java:81-123`](../../src/main/java/com/example/mealprep/nutrition/domain/service/internal/DirectiveApplier.java)).
- **Taste-profile (soft-pref) directive routing** — `preference_model` directives in the LLD are ingredient-restriction/hard-constraint shaped; soft-pref/taste-vector mutation from directives is not designed and stays out.
- **The single-field feedback target adjustment** (`applyFeedbackAdjustment`) — that is `nutrition/01i`, a different inbound (feedback bridge, not health directive).

Squash-merge with: `feat(nutrition,preference): 01j — wire DirectiveApplyTarget for preference_model directives (real updateHardConstraints + removeTemporaryConstraint)`
