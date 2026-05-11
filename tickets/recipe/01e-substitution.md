# Ticket: recipe — 01e Recipe Substitution Aggregate + State Machine + Overlay Applier + Endpoints

## Summary

Layer the **`RecipeSubstitution`** aggregate + lifecycle endpoints on top of the 01a/01b/01c/01d recipe module per [LLD V20260601120300 (lines 161-181)](../../lld/recipe.md), [LLD §`RecipeSubstitution` entity (line 280)](../../lld/recipe.md), [LLD §`RecipeSubstitutionDto` (lines 326-335)](../../lld/recipe.md), [LLD §`CreateSubstitutionRequest` (lines 413-419)](../../lld/recipe.md), [LLD §`RecipeSubstitutionRepository` (lines 495-498)](../../lld/recipe.md), [LLD §Service Interfaces (`getActiveSubstitutions`, `getSubstitutionsForVersion`, `createSubstitution`, `deactivateSubstitution`, `promoteSubstitutionToVersion` lines 531-574)](../../lld/recipe.md), [LLD §Flow 5 (line 751)](../../lld/recipe.md). Ships the `recipe_substitution` table + entity with the **state machine PROPOSED → ACCEPTED → REJECTED → SUPERSEDED** (LLD line 176 uses `active | inactive | promoted`; **01e renames** per the parent's per-module guidance to `PROPOSED → ACCEPTED / REJECTED / SUPERSEDED` — see "LLD divergence note: state machine rename" below). The `RecipeSubstitutionsController` endpoints under `/api/v1/recipes/{recipeId}/substitutions` per LLD lines 652-654 + the **expanded set** per the parent's per-module guidance: `POST /` propose, `GET /substitutions/active` (LLD `getActiveSubstitutions`), `GET /substitutions?versionId=...` (LLD `getSubstitutionsForVersion`), `POST /{subId}/accept`, `POST /{subId}/reject`, `POST /{subId}/promote-to-version` (creates a new `RecipeVersion` with the substitution applied; state → `SUPERSEDED`; LLD line 574 `promoteSubstitutionToVersion`). The **`SubstitutionOverlayApplier`** `@Component` (LLD line 37, line 749) that overlays a substitution onto a recipe's ingredients list when reading the recipe (active substitutions modify the "as displayed" view per LLD line 749 — "the base version is not mutated; the substitution stays an overlay"). The planner-facing cross-module helper `recordSubstitution` (LLD line 572 `recordSubstitutionApplication`) **SPI lands but no impl wired here** per the parent's guidance — planner-01a will implement.

**LLD divergence note** — **state machine rename `active|inactive|promoted` → `PROPOSED|ACCEPTED|REJECTED|SUPERSEDED`**:

LLD line 176 declares the DB column `state varchar(16) NOT NULL DEFAULT 'active' -- active | inactive | promoted` with the implicit state machine "create → active → (inactive on deactivate | promoted on promote-to-version)." **The parent's per-module guidance for 01e explicitly upgrades this** to a four-state machine: **PROPOSED → ACCEPTED / REJECTED / SUPERSEDED**.

Mapping:
- LLD `active` → 01e `ACCEPTED` (the substitution is in force; the planner applies it). **Note**: the LLD's `active` conflates "newly created" with "user-accepted" — 01e splits them.
- LLD `inactive` → 01e `REJECTED` (the user explicitly rejected; the substitution is on record but never applied).
- LLD `promoted` → 01e `SUPERSEDED` (a new `RecipeVersion` was created with the substitution baked in; the substitution is historical).
- NEW `PROPOSED` — a substitution exists but the user hasn't decided. The planner does NOT apply `PROPOSED` substitutions; only `ACCEPTED`.

Reasons:
- Matches **nutrition-01e's `HealthDirective` propose/accept model** (PENDING_REVIEW → ACCEPTED / REJECTED) — same propose/decide pattern.
- Lets the planner ask "what substitutions ARE currently being applied?" via `state = ACCEPTED` instead of `state = active` (clearer semantic).
- LLD line 656 says "the base version is not mutated — the substitution stays an overlay"; the four-state model preserves that while adding the user-decision step.

**Worth user review**: alternative is to keep LLD's three-state machine and add a separate `accepted` flag. Rejected because:
- A flag adds a second decision axis that callers have to remember to check.
- The DB column already permits a state-machine rename without a structural migration (varchar(16) accommodates `PROPOSED`/`ACCEPTED`/`REJECTED`/`SUPERSEDED` as lowercase literals).
- LLD line 162's table comments are updated in 01e's migration (the migration adds the new column comment in the leading SQL comment).

**LLD impact**: 01e renames the `SubstitutionState` enum values to `PROPOSED`, `ACCEPTED`, `REJECTED`, `SUPERSEDED`. The DB stores lowercase. **Worth user review** — document the rename clearly at the top of the impl file's Javadoc; if the parent prefers to keep LLD's labels for cross-doc consistency, the rename is a single search-replace.

**LLD divergence note** — **`createSubstitution` invariants relaxed**: LLD §Flow 5 line 751 says "Validates `original.ingredientMappingKey` exists in the version's ingredient list — substitution must reference a real ingredient." 01e enforces this (422 `SubstitutionOriginalNotInVersionException`). LLD also says "the planner attaches it to a plan slot and calls `recordSubstitutionApplication(...)`" — **01e ships `recordSubstitutionApplication` on the service interface but does NOT wire a controller for it** (planner-only path; no HTTP exposure needed). The **cross-module SPI** `RecipeSubstitutionRecorder` is the planner's hook:

```java
public interface RecipeSubstitutionRecorder {
  /** Append a plan id to a substitution's appliedInPlanIds + bump applicationCount + lastAppliedAt. */
  void recordSubstitution(UUID substitutionId, UUID planId);
}
```

`RecipeServiceImpl` provides the default impl (single in-module impl); the SPI lives in `recipe/spi/` so planner-01a can depend on the interface without importing the impl. **No `NoopRecipeSubstitutionRecorder`** — the recipe module's own impl is always wired. (Different from household-01e and nutrition-01e where the SPI is an outbound contract to other modules; here it's an inbound contract from planner to recipe, and recipe owns the impl.)

**LLD divergence note** — **state-machine guards**:

| Current state | Action | Allowed | Result state |
|---|---|---|---|
| PROPOSED | accept | yes | ACCEPTED |
| PROPOSED | reject | yes | REJECTED |
| PROPOSED | promote-to-version | **no** — must accept first | (422 `SubstitutionPromotionPreconditionException`) |
| ACCEPTED | accept | no-op | ACCEPTED (200, no change, no event) |
| ACCEPTED | reject | yes | REJECTED |
| ACCEPTED | promote-to-version | yes | SUPERSEDED + new RecipeVersion |
| REJECTED | accept | yes (re-activate) | ACCEPTED |
| REJECTED | reject | no-op | REJECTED (200) |
| REJECTED | promote-to-version | **no** — rejected substitutions don't promote | (422) |
| SUPERSEDED | any | **no** — terminal state | (422 `SubstitutionTerminalStateException`) |

**Worth user review** — the LLD doesn't enumerate state-transition rules. 01e locks the above table; the LLD's "promotion requires `state == ACTIVE`" maps to "promotion requires `state == ACCEPTED`" after the rename. The re-activate path (REJECTED → ACCEPTED) is **new** — the LLD doesn't address whether rejected substitutions are revivable. 01e allows it because the more conservative path (REJECTED is terminal) loses the ability for a user to change their mind without re-creating the substitution; the LLD line 280 says `state` is mutable, which permits this.

**Defers** (still out of scope after 01e):

- **`SubstitutionOverlayApplier` integration with `getById` / `getVersion` hot reads** — 01e ships the applier as a `@Component` with the overlay logic, and ships an explicit endpoint `GET /api/v1/recipes/{recipeId}/versions/{versionId}/with-substitutions` that returns the overlaid version. **The applier is NOT wired into the existing `getById` / `getVersion` flow** because that's a hot read path used by the planner with a per-call decision about whether to apply overlays. Wiring it everywhere would change every existing test's expected output. **Worth user review** — alternative is a `boolean withSubstitutions` query param on the existing `GET /recipes/{recipeId}`; 01e picks the dedicated endpoint to avoid risk to 01a/01b/01c/01d's existing tests.
- **Planner-facing `recordSubstitution` impl wired into a `RecipeSubstitutionRecorder` bean** — the SPI lands but the planner module that calls it isn't built yet. The recipe module ships the bean; no external caller in 01e.
- **`saveAdaptedSubstitution`** (pipeline-facing path, LLD line 593) → **recipe-01f** (lands with the rest of `RecipeWriteApi`)
- **Substitution overlay applied automatically by `getById`** — see first bullet
- AI-driven substitution suggestion → out of scope (pipeline concern)
- **Method-overlay applier** — the LLD §`MethodOverlayLineDto` (line 334) declares an overlay shape but 01e's overlay applier only handles ingredient swaps. Method-overlay application is a sub-feature deferred to `recipe-01f` (the pipeline path needs it; manual flow can ship without).

01e unblocks the **substitution UX**: users can propose "use chickpeas instead of beans in this lasagne version," accept, reject, or promote the substitution to a new version that bakes the swap in permanently. Without 01e, recipe variants require a full new branch + new version (heavy); substitutions are the lightweight overlay path.

## Behavioural spec

### Aggregate shape — `RecipeSubstitution`

1. `RecipeSubstitution` is a **standalone aggregate root** per [LLD §Entities line 280](../../lld/recipe.md): "Mutable on `applicationCount`, `lastAppliedAt`, `appliedInPlanIds`, `state`, `promotedToVersionId`. `@Version`. `methodOverlay` mapped as `List<MethodOverlayLine>` via JSONB."
2. Fields per [LLD V20260601120300 lines 161-178](../../lld/recipe.md):
   - `id (UUID, application-set)`
   - `recipeId (UUID NOT NULL, FK → recipe_recipes ON DELETE CASCADE)`
   - `versionId (UUID NOT NULL, FK → recipe_versions ON DELETE CASCADE)` — the base version the substitution overlays.
   - `branchId (UUID NOT NULL, FK → recipe_branches ON DELETE CASCADE)`
   - `originalMappingKey (varchar 160 NOT NULL)`, `originalQuantity (BigDecimal(10,3) NOT NULL)`, `originalUnit (varchar 16 NOT NULL)` — the ingredient being substituted.
   - `substituteMappingKey (varchar 160 NOT NULL)`, `substituteQuantity (BigDecimal(10,3) NOT NULL)`, `substituteUnit (varchar 16 NOT NULL)` — what replaces it.
   - `reason (SubstitutionReason enum: BUDGET | AVAILABILITY | DIETARY_TEMP | EQUIPMENT; varchar 32; lowercase in DB)`
   - `constraintRef (varchar 160 nullable)` — e.g. `"budget-cap-2026-w15"`.
   - `methodOverlay (jsonb nullable — `List<MethodOverlayLine>`)` — overlaid method-step instructions.
   - `notes (text nullable)`
   - `temporary (boolean NOT NULL DEFAULT true)`
   - `appliedInPlanIds (uuid[] NOT NULL DEFAULT '{}')` — Postgres array of plan UUIDs the planner has applied this substitution in. **LLD divergence**: per the agent-prompt-template gotcha "Postgres `text[]` columns are brittle in Hibernate; use `jsonb List<String>`," 01e considers `jsonb` here too. **Verdict: keep as `uuid[]`** because UUID is a first-class Postgres type with `gin (uuid_ops)` indexing potential and the planner queries by `array_position(...)`; document with a TODO if Hibernate friction arises.
   - `applicationCount (integer NOT NULL DEFAULT 0)`
   - `lastAppliedAt (Instant nullable)`
   - `state (SubstitutionState enum: PROPOSED | ACCEPTED | REJECTED | SUPERSEDED; varchar 16; default 'proposed' — see rename divergence note)`
   - `promotedToVersionId (UUID nullable, FK → recipe_versions(id))` — populated when state == SUPERSEDED.
   - `createdAt (@CreatedDate)`, `createdByActor (varchar 32 NOT NULL — "user:<uuid>" pattern per LLD line 154-coded width concerns; 01a's column-width adjustment to 64 applies if `created_by_actor varchar(32)` was widened in 01a — **verify**; if 01a left it at 32, 01e widens to 64 in its migration as a small ALTER)`
   - `adapterTraceId (UUID nullable)` — pipeline-only; null for manual flow.
   - `optimisticVersion (@Version Long, column `version` per LLD pattern)` — **NEW addition NOT in the LLD migration columns** but **mandated by the entity comment line 280 (`@Version`)**; 01e adds the column to the migration. LLD line 178 omits the column but the entity needs it. **Worth user review.**
3. **DB constraints / indexes** per LLD lines 180-181:
   - `CREATE INDEX idx_recipe_substitutions_version ON recipe_substitutions (version_id) WHERE state = 'active'` — **01e renames to** `WHERE state = 'accepted'` per the rename. The agent must propagate.
   - `CREATE INDEX idx_recipe_substitutions_promotion ON recipe_substitutions (recipe_id, application_count DESC) WHERE state = 'active'` — same rename to `'accepted'`.
4. **`MethodOverlayLine` JSONB inner shape** per LLD line 334:
   ```java
   public record MethodOverlayLine(int step, String instruction) {}
   ```

### `createSubstitution` flow (propose) — LLD line 571 + Flow 5

5. `POST /api/v1/recipes/{recipeId}/substitutions`. Authenticated. Server resolves `actorUserId`. Body: `CreateSubstitutionRequest` verbatim from [LLD lines 413-419](../../lld/recipe.md):
   ```java
   public record CreateSubstitutionRequest(
       @NotNull UUID versionId,
       @NotNull @Valid SubstitutionItemRequest original,
       @NotNull @Valid SubstitutionItemRequest substitute,
       @NotNull SubstitutionReason reason, @Size(max = 160) String constraintRef,
       @Valid List<MethodOverlayLineRequest> methodOverlay,
       @Size(max = 1000) String notes, boolean temporary) {}

   public record SubstitutionItemRequest(@NotBlank @Size(max = 160) String ingredientMappingKey,
                                          @NotNull @DecimalMin("0") BigDecimal quantity,
                                          @NotBlank @Size(max = 16) String unit) {}

   public record MethodOverlayLineRequest(@Min(1) int step, @NotBlank @Size(max = 2000) String instruction) {}
   ```
6. **Validation cascade**:
   1. Jakarta `@Valid` → 400 on shape failures.
   2. Recipe exists / not soft-deleted → 404 `RecipeNotFoundException`.
   3. Recipe catalogue check: `recipe.catalogue == USER` AND `recipe.userId == actorUserId` else 404 (USER recipe owned by someone else) or 422 `RecipeCatalogueViolationException` (SYSTEM recipe — promote first). Same gate as 01c's manual-edit and 01d's branch-create flows.
   4. Version exists with `versionId == request.versionId()` AND `version.recipeId == recipe.id` → 404 `RecipeVersionNotFoundException` (existing from 01c).
   5. **Original ingredient must exist in the version's ingredient list** (LLD line 751): `recipeIngredientRepository.findMappingKeysByVersionId(versionId).contains(request.original().ingredientMappingKey())` else 422 `SubstitutionOriginalNotInVersionException` (NEW).
7. **Single `@Transactional` write**:
   - Insert `RecipeSubstitution` row: `id = UUID.randomUUID()`, `state = PROPOSED`, `applicationCount = 0`, `appliedInPlanIds = empty`, `lastAppliedAt = null`, `promotedToVersionId = null`, `createdByActor = "user:" + actorUserId`, `adapterTraceId = null`.
8. **Event**: publish `RecipeSubstitutionCreatedEvent(UUID substitutionId, UUID recipeId, UUID versionId, UUID branchId, SubstitutionReason reason, UUID traceId, Instant occurredAt)` `AFTER_COMMIT`. **NEW event** — LLD §Events doesn't declare it; 01e adds it because the planner's per-recipe cache wants to know when a new substitution proposal lands. Cost is one record class; no listeners in 01e.
9. Return 201 + `RecipeSubstitutionDto`. `Location: /api/v1/recipes/{recipeId}/substitutions/{subId}`.

### `acceptSubstitution` flow

10. `POST /api/v1/recipes/{recipeId}/substitutions/{subId}/accept`. Authenticated. Body: `AcceptSubstitutionRequest { long expectedVersion }`.
11. **404 ladder**: substitution not found OR `sub.recipeId != recipeId` → 404 `RecipeSubstitutionNotFoundException` (LLD line 666 names it).
12. **Authorisation**: `recipe.userId == actorUserId` else 404.
13. **Stale `expectedVersion`** → 409 via `OptimisticLockingFailureException`.
14. **State guard** (per the state table above): `SUPERSEDED` → 422 `SubstitutionTerminalStateException`. `ACCEPTED` → no-op (200, no change, no event). `PROPOSED` or `REJECTED` → proceed.
15. Single `@Transactional` write: `state = ACCEPTED`. JPA bumps `@Version`. Return 200 + `RecipeSubstitutionDto`.
16. **Event**: publish `RecipeSubstitutionStateChangedEvent(subId, recipeId, versionId, previousState, newState = ACCEPTED, traceId, occurredAt)` `AFTER_COMMIT`. **NEW event** — same rationale as the created event.

### `rejectSubstitution` flow

17. `POST /api/v1/recipes/{recipeId}/substitutions/{subId}/reject`. Authenticated. Body: `RejectSubstitutionRequest { long expectedVersion, @Size(max = 255) String reason /* optional */ }`.
18. **404 / 409 / authorisation** same as accept.
19. **State guard**: `SUPERSEDED` → 422. `REJECTED` → no-op (200). `PROPOSED` or `ACCEPTED` → proceed.
20. Single `@Transactional` write: `state = REJECTED`. (The `reason` field on the request is **not** persisted on the substitution row — no `rejection_reason` column on `recipe_substitution` per LLD. 01e logs the reason at INFO if non-blank for audit; **worth user review** — alternative is to add a `rejection_reason varchar(255)` column; 01e defers to keep the migration minimal.)
21. **Event**: publish `RecipeSubstitutionStateChangedEvent(...)` AFTER_COMMIT.

### `promoteSubstitutionToVersion` flow (LLD line 574 + Flow 5 line 751)

22. `POST /api/v1/recipes/{recipeId}/substitutions/{subId}/promote-to-version`. Authenticated. Body: `PromoteSubstitutionRequest { long expectedVersion, @Size(max = 2000) String changeReason /* required */ }`.
23. **404 / 409 / authorisation** same as accept.
24. **State guard**: precondition `state == ACCEPTED` (per state table; PROPOSED / REJECTED / SUPERSEDED all 422 `SubstitutionPromotionPreconditionException`).
25. **Single `@Transactional` write** — reuses 01c's `manualEdit` insert-new-version path:
    1. Load base version (`sub.versionId`) with body (ingredients + method + metadata + tags).
    2. Apply the substitution to a fresh `NewVersionInput`: replace the matching ingredient line(s) with `(substituteMappingKey, substituteQuantity, substituteUnit)` and overlay the method-step instructions per `methodOverlay`. The overlay logic is delegated to `SubstitutionOverlayApplier.apply(baseBody, sub)`.
    3. Insert new `RecipeVersion` row on the same branch (`branchId = sub.branchId`) with `versionNumber = branch.currentVersion + 1`, `parentVersionId = sub.versionId`, `trigger = SUBSTITUTION_PROMOTION` (existing enum value from 01a per LLD line 112), `changeDiff = VersionDiffer.diff(baseVersion, appliedBody)`, `changeReason = request.changeReason()`, `characterFingerprint = baseVersion.characterFingerprint` (substitution promotion doesn't refresh fingerprint per LLD line 113 — fingerprint refreshes only on branch creation), `nutritionPerServing = null` (recalc later), `embeddingStatus = "pending"`, `createdByActor = "user:" + actorUserId`.
    4. Insert child rows for the new version (`RecipeIngredient`, `RecipeMethodStep`, `RecipeMetadata`, `RecipeTags`) — reuse 01a/01c's child-row builder helpers.
    5. Bump `Recipe.currentVersion`, `RecipeBranch.currentVersion` to the new version number (the promotion lands on the current branch by definition).
    6. Update substitution: `state = SUPERSEDED`, `promotedToVersionId = newVersionId`. JPA bumps `@Version`.
26. **Events** (both `AFTER_COMMIT`):
    - `RecipeVersionCreatedEvent(versionId = newVersionId, recipeId, branchId, versionNumber, traceId, occurredAt)` (existing from 01a).
    - `RecipeUpdatedEvent(recipeId, branchId, newVersionId, newVersionNumber, trigger = SUBSTITUTION_PROMOTION, traceId, occurredAt)` (existing from 01c).
    - `RecipeSubstitutionStateChangedEvent(...)` with `newState = SUPERSEDED`.
27. Return 200 + the new `RecipeVersionDto` (hydrated).
28. **Note**: LLD line 574 `promoteSubstitutionToVersion` returns `RecipeVersionDto`; 01e follows verbatim. The substitution itself (now SUPERSEDED) is **not** returned in the response body; the caller can re-fetch via `GET /substitutions/{subId}` if needed.

### `getActiveSubstitutions` (LLD line 531) — `GET /api/v1/recipes/{recipeId}/substitutions/active`

29. Authenticated. Returns `List<RecipeSubstitutionDto>` for the recipe where `state == ACCEPTED`, sorted `last_applied_at DESC NULLS LAST`. Repository: `findAllByRecipeIdAndStateOrderByLastAppliedAtDesc(UUID recipeId, SubstitutionState state)` per [LLD line 496](../../lld/recipe.md) — invoked with `state = ACCEPTED`. **LLD verbatim except the rename.**

### `getSubstitutionsForVersion` (LLD line 532) — `GET /api/v1/recipes/{recipeId}/substitutions?versionId=...`

30. Authenticated. Returns `List<RecipeSubstitutionDto>` filtered by `versionId` AND `state == ACCEPTED`, sorted `last_applied_at DESC NULLS LAST`. Repository: `findAllByVersionIdAndStateOrderByLastAppliedAtDesc(UUID versionId, SubstitutionState state)` per [LLD line 497](../../lld/recipe.md). When `versionId` query param is **absent** → 400 (the endpoint is version-scoped).
31. **Worth user review**: this overlaps semantically with `getActiveSubstitutions` (the active set is the union of per-version active sets). 01e keeps both per the LLD's explicit declaration of both methods.

### `SubstitutionOverlayApplier` — `domain/service/internal/`

32. `SubstitutionOverlayApplier` is a `@Component` in `recipe/domain/service/internal/`. Implements LLD line 749 "the substitution stays an overlay." Single public method:
    ```java
    NewVersionInput apply(NewVersionInput baseBody, List<RecipeSubstitution> activeSubstitutions);
    ```
33. **Logic**:
    - For each substitution (in `created_at ASC` order — earliest first):
      - Find every ingredient in `baseBody.ingredients()` whose `ingredientMappingKey == sub.originalMappingKey`. If multiple match (rare; same ingredient appearing twice with different preparations), substitute ALL.
      - Replace `(ingredientMappingKey, quantity, unit)` with the substitute's values. Preserve `lineOrder`, `displayName` (set to a reasonable derived value — `sub.substituteMappingKey` or a builder), `preparation`, `optional`, `needsReview` flags.
      - If `sub.methodOverlay != null && !sub.methodOverlay.isEmpty()`: for each `MethodOverlayLine(step, instruction)`, replace the matching `step_number` in `baseBody.method()` with the overlaid instruction. **Note**: 01e applies ingredient overlays fully; method overlays are applied if present but the LLD's `MethodOverlayLineDto.step` is 1-indexed (verify against 01a's `RecipeMethodStep.stepNumber` convention — should match).
    - Return the modified `NewVersionInput`. **Immutable**: the input is not mutated; the applier returns a new record.
34. **Unit-test coverage**: identical overlay (same key) → no change; single swap → swapped; two non-overlapping swaps → both applied; method overlay → instruction replaced at the named step; method overlay for non-existent step → ignored with WARN log. **Pure logic** — no DB access.

### `GET /api/v1/recipes/{recipeId}/versions/{versionId}/with-substitutions`

35. Authenticated. Returns `RecipeVersionDto` with the version's body overlaid by active substitutions. Internally:
    - Load `RecipeVersion` with body (existing `findWithBodyById` from 01b/01c).
    - Load active substitutions for the version (`getSubstitutionsForVersion`).
    - Run `SubstitutionOverlayApplier.apply(baseBody, activeSubs)`.
    - Map the overlaid `NewVersionInput` back to a `RecipeVersionDto` (the mapper takes the original version metadata + the overlaid body).
36. **Note**: the returned DTO carries the **base version's id** (not a new id) so the caller knows it's a view over an existing version. The substitution ids that contributed are listed in a NEW field `appliedSubstitutionIds: List<UUID>` on `RecipeVersionDto` — **LLD divergence**: 01a's `RecipeVersionDto` doesn't carry this field. 01e adds a nullable `appliedSubstitutionIds` field to `RecipeVersionDto`. **Verify 01a's actual shape** — if the field doesn't exist, the agent adds it; document in the impl Javadoc that the field is null on non-overlay reads (`getById`, `getVersion`).

### `RecipeSubstitutionRecorder` SPI (planner-facing inbound)

37. New public interface `com.example.mealprep.recipe.spi.RecipeSubstitutionRecorder`:
    ```java
    public interface RecipeSubstitutionRecorder {
      /** Append a plan id to the substitution's appliedInPlanIds + bump applicationCount + lastAppliedAt = now. */
      void recordSubstitution(UUID substitutionId, UUID planId);
    }
    ```
38. **Wire-up**: `RecipeServiceImpl` implements `RecipeSubstitutionRecorder` directly (the simplest path; no extra class). When the planner module lands, `@Autowired RecipeSubstitutionRecorder` picks up the recipe-side bean. **No `Noop` impl** — the recipe module always provides the bean.
39. **Implementation**:
    - Load substitution by id → 404 `RecipeSubstitutionNotFoundException` if missing.
    - **State guard**: must be `ACCEPTED` else 422 `SubstitutionRecordPreconditionException` (NEW). Per LLD line 751 "the planner attaches it to a plan slot" — only accepted subs are eligible.
    - Append `planId` to `appliedInPlanIds` (Postgres array union). If `planId` already in the array → no-op (idempotent).
    - Bump `applicationCount += 1`; `lastAppliedAt = Instant.now()`. JPA bumps `@Version`.
    - **No event** — this is high-frequency planner-internal bookkeeping; events would be noisy.
40. **No HTTP exposure** — internal cross-module helper only. The LLD's `recordSubstitutionApplication` (line 572) matches; 01e renames to `recordSubstitution` per the parent's per-module guidance for symmetry with future cross-module SPI naming conventions.

### Service interfaces — append-only

41. Append to `RecipeQueryService` (already has 01d's `getBranches`, `getBranch`, `getFingerprint`):
    ```java
    List<RecipeSubstitutionDto> getActiveSubstitutions(UUID recipeId);
    List<RecipeSubstitutionDto> getSubstitutionsForVersion(UUID versionId);
    Optional<RecipeSubstitutionDto> getSubstitution(UUID substitutionId);
    RecipeVersionDto getVersionWithSubstitutions(UUID recipeId, UUID versionId);
    ```
    First two verbatim from [LLD lines 531-532](../../lld/recipe.md). `getSubstitution` is a single-fetch helper. `getVersionWithSubstitutions` backs the new endpoint.
42. Append to `RecipeUpdateService`:
    ```java
    RecipeSubstitutionDto createSubstitution(UUID recipeId, CreateSubstitutionRequest request, UUID actorUserId);
    RecipeSubstitutionDto acceptSubstitution(UUID substitutionId, UUID actorUserId, long expectedVersion);
    RecipeSubstitutionDto rejectSubstitution(UUID substitutionId, UUID actorUserId, long expectedVersion, String reason);
    RecipeVersionDto promoteSubstitutionToVersion(UUID substitutionId, UUID actorUserId, long expectedVersion, String changeReason);
    ```
    `createSubstitution` and `promoteSubstitutionToVersion` verbatim from LLD lines 571 / 574. Accept and reject are NEW (the rename divergence — LLD's `deactivateSubstitution` (line 573) is **dropped**; reject replaces it). **Document the LLD divergence on the interface Javadoc.**

### Repository — new

43. ```java
    interface RecipeSubstitutionRepository extends JpaRepository<RecipeSubstitution, UUID> {
      List<RecipeSubstitution> findAllByRecipeIdAndStateOrderByLastAppliedAtDesc(UUID recipeId, SubstitutionState s);
      List<RecipeSubstitution> findAllByVersionIdAndStateOrderByLastAppliedAtDesc(UUID versionId, SubstitutionState s);
    }
    ```
    Verbatim from [LLD lines 495-498](../../lld/recipe.md). Package-private.

### Errors

44. New module exception subclasses extending the existing `RecipeException` from 01a:
    - `RecipeSubstitutionNotFoundException` (404, `.../recipe-substitution-not-found`) — LLD line 666.
    - `SubstitutionOriginalNotInVersionException` (422, `.../substitution-original-not-in-version`) — NEW per invariant 6.5.
    - `SubstitutionTerminalStateException` (422, `.../substitution-terminal-state`) — NEW per the state-table.
    - `SubstitutionPromotionPreconditionException` (422, `.../substitution-promotion-precondition`) — NEW per invariant 24.
    - `SubstitutionRecordPreconditionException` (422, `.../substitution-record-precondition`) — NEW per invariant 39.
45. **Append five new `@ExceptionHandler` methods** to the existing `RecipeExceptionHandler` `@RestControllerAdvice` from 01a/01b/01c/01d (already `@Order(Ordered.HIGHEST_PRECEDENCE)`).

## Database

```
src/main/resources/db/migration/V20260601800900__recipe_create_recipe_substitutions.sql   new
```

Schema mirrors [LLD V20260601120300 lines 161-181](../../lld/recipe.md), renumbered to the recipe timestamp range (`V20260601800900` is the next free slot after 01b's `V20260601800800__recipe_create_recipe_imports_table.sql`):

```sql
-- V20260601800900
-- State machine: PROPOSED → ACCEPTED / REJECTED / SUPERSEDED
-- (renamed from LLD's active|inactive|promoted per ticket 01e — see ticket for rationale)
CREATE TABLE recipe_substitutions (
    id                       uuid PRIMARY KEY,
    recipe_id                uuid NOT NULL REFERENCES recipe_recipes(id)  ON DELETE CASCADE,
    version_id               uuid NOT NULL REFERENCES recipe_versions(id) ON DELETE CASCADE,
    branch_id                uuid NOT NULL REFERENCES recipe_branches(id) ON DELETE CASCADE,
    original_mapping_key     varchar(160) NOT NULL,
    original_quantity        numeric(10,3) NOT NULL,
    original_unit            varchar(16) NOT NULL,
    substitute_mapping_key   varchar(160) NOT NULL,
    substitute_quantity      numeric(10,3) NOT NULL,
    substitute_unit          varchar(16) NOT NULL,
    reason                   varchar(32) NOT NULL,
    constraint_ref           varchar(160),
    method_overlay           jsonb,
    notes                    text,
    temporary                boolean NOT NULL DEFAULT true,
    applied_in_plan_ids      uuid[] NOT NULL DEFAULT '{}',
    application_count        integer NOT NULL DEFAULT 0,
    last_applied_at          timestamptz,
    state                    varchar(16) NOT NULL DEFAULT 'proposed',
    promoted_to_version_id   uuid REFERENCES recipe_versions(id),
    created_at               timestamptz NOT NULL,
    created_by_actor         varchar(64) NOT NULL,                            -- 64 wide for "user:<uuid>" pattern
    adapter_trace_id         uuid,
    version                  bigint NOT NULL DEFAULT 0
);
CREATE INDEX idx_recipe_substitutions_version    ON recipe_substitutions (version_id)            WHERE state = 'accepted';
CREATE INDEX idx_recipe_substitutions_promotion  ON recipe_substitutions (recipe_id, application_count DESC) WHERE state = 'accepted';
```

**`version` column** — added per the entity's `@Version` requirement (LLD line 280); LLD line 178 omitted it. Default 0 to satisfy NOT NULL on existing rows (none exist yet).

**`created_by_actor varchar(64)`** — wider than LLD's `varchar(32)` for the `"user:<uuid>"` 41-char pattern per the agent-prompt-template gotcha "Don't trust LLD column widths blindly."

## OpenAPI updates

### Append to `src/main/resources/openapi/paths/recipe.yaml`

(Append four new path-items below 01d's branches blocks. Do NOT touch existing path-items.)

```yaml
recipeSubstitutions:
  get:
    tags: [Recipes]
    operationId: getSubstitutionsForVersion
    summary: 'List substitutions for a specific version (state filter: ACCEPTED).'
    security: [{ cookieAuth: [] }]
    parameters:
      - in: path
        name: recipeId
        required: true
        schema: { type: string, format: uuid }
      - in: query
        name: versionId
        required: true
        schema: { type: string, format: uuid }
    responses:
      '200':
        description: 'Active substitutions on the given version.'
        content:
          application/json:
            schema:
              type: array
              items: { $ref: '../schemas/recipe.yaml#/RecipeSubstitutionDto' }
      '400': { description: 'versionId query param missing', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '401': { description: 'Unauthenticated', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '404': { description: 'Recipe not found', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
  post:
    tags: [Recipes]
    operationId: createRecipeSubstitution
    summary: 'Propose a new substitution; state = PROPOSED.'
    security: [{ cookieAuth: [] }]
    parameters:
      - in: path
        name: recipeId
        required: true
        schema: { type: string, format: uuid }
    requestBody:
      required: true
      content:
        application/json:
          schema: { $ref: '../schemas/recipe.yaml#/CreateSubstitutionRequest' }
    responses:
      '201':
        description: 'Substitution created in PROPOSED state.'
        headers:
          Location:
            schema: { type: string, format: uri }
        content:
          application/json:
            schema: { $ref: '../schemas/recipe.yaml#/RecipeSubstitutionDto' }
      '400': { description: 'Validation error', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '401': { description: 'Unauthenticated', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '404': { description: 'Recipe / version not found / not owned', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '422': { description: 'Original ingredient not in version / SYSTEM-catalogue recipe', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
recipeSubstitutionsActive:
  get:
    tags: [Recipes]
    operationId: getActiveSubstitutions
    summary: 'List all ACCEPTED substitutions for the recipe.'
    security: [{ cookieAuth: [] }]
    parameters:
      - in: path
        name: recipeId
        required: true
        schema: { type: string, format: uuid }
    responses:
      '200':
        description: 'Accepted substitutions for the recipe.'
        content:
          application/json:
            schema:
              type: array
              items: { $ref: '../schemas/recipe.yaml#/RecipeSubstitutionDto' }
      '401': { description: 'Unauthenticated', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '404': { description: 'Recipe not found', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
recipeSubstitutionLifecycle:
  post:
    tags: [Recipes]
    operationId: actOnRecipeSubstitution
    summary: 'Discriminated lifecycle action — single endpoint with action in path: accept | reject | promote-to-version.'
    security: [{ cookieAuth: [] }]
    parameters:
      - in: path
        name: recipeId
        required: true
        schema: { type: string, format: uuid }
      - in: path
        name: subId
        required: true
        schema: { type: string, format: uuid }
      - in: path
        name: action
        required: true
        schema: { type: string, enum: [accept, reject, promote-to-version] }
    requestBody:
      required: true
      content:
        application/json:
          schema:
            oneOf:
              - { $ref: '../schemas/recipe.yaml#/AcceptSubstitutionRequest' }
              - { $ref: '../schemas/recipe.yaml#/RejectSubstitutionRequest' }
              - { $ref: '../schemas/recipe.yaml#/PromoteSubstitutionRequest' }
    responses:
      '200':
        description: 'Substitution acted upon; accept/reject returns the substitution, promote returns the new RecipeVersion.'
        content:
          application/json:
            schema:
              oneOf:
                - { $ref: '../schemas/recipe.yaml#/RecipeSubstitutionDto' }
                - { $ref: '../schemas/recipe.yaml#/RecipeVersionDto' }
      '400': { description: 'Validation error / unknown action', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '401': { description: 'Unauthenticated', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '404': { description: 'Substitution not found / not owned', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '409': { description: 'Stale expectedVersion', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '422': { description: 'Terminal state / promotion precondition (must be ACCEPTED)', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
recipeVersionWithSubstitutions:
  get:
    tags: [Recipes]
    operationId: getRecipeVersionWithSubstitutions
    summary: 'Fetch a version with active substitutions overlaid onto the ingredients / method.'
    security: [{ cookieAuth: [] }]
    parameters:
      - in: path
        name: recipeId
        required: true
        schema: { type: string, format: uuid }
      - in: path
        name: versionId
        required: true
        schema: { type: string, format: uuid }
    responses:
      '200':
        description: 'Version with overlays applied; carries appliedSubstitutionIds list.'
        content:
          application/json:
            schema: { $ref: '../schemas/recipe.yaml#/RecipeVersionDto' }
      '401': { description: 'Unauthenticated', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '404': { description: 'Recipe / version not found', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
```

**Worth user review**: the discriminated `/substitutions/{subId}/{action}` endpoint uses a path-variable enum to fan out to three behaviours. Alternative is three explicit endpoints (`/accept`, `/reject`, `/promote-to-version`); 01e picks the discriminated variant because the three actions share auth + 404/409/422 ladder, and the `oneOf` request/response shape models them cleanly. **The agent may flip to three endpoints if `oneOf` requestBody breaks swagger-request-validator** — verify in IT; document the choice.

### Append to `src/main/resources/openapi/schemas/recipe.yaml`

```yaml
SubstitutionReason:
  type: string
  enum: [BUDGET, AVAILABILITY, DIETARY_TEMP, EQUIPMENT]
SubstitutionState:
  type: string
  enum: [PROPOSED, ACCEPTED, REJECTED, SUPERSEDED]
SubstitutionItemRequest:
  type: object
  required: [ingredientMappingKey, quantity, unit]
  properties:
    ingredientMappingKey: { type: string, minLength: 1, maxLength: 160 }
    quantity: { type: number, format: double, minimum: 0 }
    unit: { type: string, minLength: 1, maxLength: 16 }
MethodOverlayLineRequest:
  type: object
  required: [step, instruction]
  properties:
    step: { type: integer, minimum: 1 }
    instruction: { type: string, minLength: 1, maxLength: 2000 }
MethodOverlayLineDto:
  type: object
  required: [step, instruction]
  properties:
    step: { type: integer, minimum: 1 }
    instruction: { type: string, maxLength: 2000 }
SubstitutedItemDto:
  type: object
  required: [ingredientMappingKey, quantity, unit]
  properties:
    ingredientMappingKey: { type: string, maxLength: 160 }
    quantity: { type: number, format: double, minimum: 0 }
    unit: { type: string, maxLength: 16 }
RecipeSubstitutionDto:
  type: object
  required: [id, recipeId, versionId, branchId, original, substitute, reason, state, applicationCount, temporary, createdAt, createdByActor, version]
  properties:
    id: { type: string, format: uuid }
    recipeId: { type: string, format: uuid }
    versionId: { type: string, format: uuid }
    branchId: { type: string, format: uuid }
    original: { $ref: '#/SubstitutedItemDto' }
    substitute: { $ref: '#/SubstitutedItemDto' }
    reason: { $ref: '#/SubstitutionReason' }
    constraintRef:
      type: string
      maxLength: 160
      nullable: true
    methodOverlay:
      type: array
      nullable: true
      items: { $ref: '#/MethodOverlayLineDto' }
    notes:
      type: string
      maxLength: 2000
      nullable: true
    temporary: { type: boolean }
    applicationCount: { type: integer, minimum: 0 }
    lastAppliedAt:
      type: string
      format: date-time
      nullable: true
    state: { $ref: '#/SubstitutionState' }
    promotedToVersionId:
      type: string
      format: uuid
      nullable: true
    createdAt: { type: string, format: date-time }
    createdByActor: { type: string, maxLength: 64 }
    adapterTraceId:
      type: string
      format: uuid
      nullable: true
    version: { type: integer, format: int64 }
CreateSubstitutionRequest:
  type: object
  required: [versionId, original, substitute, reason]
  properties:
    versionId: { type: string, format: uuid }
    original: { $ref: '#/SubstitutionItemRequest' }
    substitute: { $ref: '#/SubstitutionItemRequest' }
    reason: { $ref: '#/SubstitutionReason' }
    constraintRef:
      type: string
      maxLength: 160
      nullable: true
    methodOverlay:
      type: array
      nullable: true
      items: { $ref: '#/MethodOverlayLineRequest' }
    notes:
      type: string
      maxLength: 1000
      nullable: true
    temporary: { type: boolean, default: true }
AcceptSubstitutionRequest:
  type: object
  required: [expectedVersion]
  properties:
    expectedVersion: { type: integer, format: int64, minimum: 0 }
RejectSubstitutionRequest:
  type: object
  required: [expectedVersion]
  properties:
    expectedVersion: { type: integer, format: int64, minimum: 0 }
    reason:
      type: string
      maxLength: 255
      nullable: true
PromoteSubstitutionRequest:
  type: object
  required: [expectedVersion, changeReason]
  properties:
    expectedVersion: { type: integer, format: int64, minimum: 0 }
    changeReason: { type: string, minLength: 1, maxLength: 2000 }
```

**Gotcha applied**: every nullable scalar uses **inline** `nullable: true`. `methodOverlay` array is inlined `nullable: true` at the array level (the items reference is fine; only the outer property is nullable).

**Gotcha applied**: all YAML descriptions with `,` `:` `'` single-quoted.

**Note on `RecipeVersionDto.appliedSubstitutionIds`**: verify 01a's schema. If missing, the agent appends:

```yaml
# In the existing RecipeVersionDto schema (touch carefully — 01a/01c rely on this):
    appliedSubstitutionIds:
      type: array
      nullable: true
      description: 'Populated only by GET /versions/{versionId}/with-substitutions; null on every other read.'
      items: { type: string, format: uuid }
```

**This is the ONE shared schema file the agent may modify** beyond appending — document in the report.

### Append to entry `src/main/resources/openapi/openapi.yaml`

**Location**: under the existing `# recipe` block in `paths:`. Append four new path-item refs:

```yaml
  /api/v1/recipes/{recipeId}/substitutions:
    $ref: 'paths/recipe.yaml#/recipeSubstitutions'
  /api/v1/recipes/{recipeId}/substitutions/active:
    $ref: 'paths/recipe.yaml#/recipeSubstitutionsActive'
  /api/v1/recipes/{recipeId}/substitutions/{subId}/{action}:
    $ref: 'paths/recipe.yaml#/recipeSubstitutionLifecycle'
  /api/v1/recipes/{recipeId}/versions/{versionId}/with-substitutions:
    $ref: 'paths/recipe.yaml#/recipeVersionWithSubstitutions'
```

**Location**: under `components.schemas:`, append nine new schema refs in the existing `# recipe` block (alphabetical):

```yaml
    AcceptSubstitutionRequest: { $ref: 'schemas/recipe.yaml#/AcceptSubstitutionRequest' }
    CreateSubstitutionRequest: { $ref: 'schemas/recipe.yaml#/CreateSubstitutionRequest' }
    MethodOverlayLineDto: { $ref: 'schemas/recipe.yaml#/MethodOverlayLineDto' }
    MethodOverlayLineRequest: { $ref: 'schemas/recipe.yaml#/MethodOverlayLineRequest' }
    PromoteSubstitutionRequest: { $ref: 'schemas/recipe.yaml#/PromoteSubstitutionRequest' }
    RecipeSubstitutionDto: { $ref: 'schemas/recipe.yaml#/RecipeSubstitutionDto' }
    RejectSubstitutionRequest: { $ref: 'schemas/recipe.yaml#/RejectSubstitutionRequest' }
    SubstitutedItemDto: { $ref: 'schemas/recipe.yaml#/SubstitutedItemDto' }
    SubstitutionItemRequest: { $ref: 'schemas/recipe.yaml#/SubstitutionItemRequest' }
    SubstitutionReason: { $ref: 'schemas/recipe.yaml#/SubstitutionReason' }
    SubstitutionState: { $ref: 'schemas/recipe.yaml#/SubstitutionState' }
```

## Verbatim shape snippets

### Entity

```java
@Entity
@Table(name = "recipe_substitutions")
@Getter @Setter @Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class RecipeSubstitution {

  @Id @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @Column(name = "recipe_id", nullable = false, updatable = false)
  private UUID recipeId;

  @Column(name = "version_id", nullable = false, updatable = false)
  private UUID versionId;

  @Column(name = "branch_id", nullable = false, updatable = false)
  private UUID branchId;

  @Column(name = "original_mapping_key", nullable = false, updatable = false, length = 160)
  private String originalMappingKey;

  @Column(name = "original_quantity", nullable = false, updatable = false, precision = 10, scale = 3)
  private BigDecimal originalQuantity;

  @Column(name = "original_unit", nullable = false, updatable = false, length = 16)
  private String originalUnit;

  @Column(name = "substitute_mapping_key", nullable = false, updatable = false, length = 160)
  private String substituteMappingKey;

  @Column(name = "substitute_quantity", nullable = false, updatable = false, precision = 10, scale = 3)
  private BigDecimal substituteQuantity;

  @Column(name = "substitute_unit", nullable = false, updatable = false, length = 16)
  private String substituteUnit;

  @Enumerated(EnumType.STRING)
  @Column(name = "reason", nullable = false, updatable = false, length = 32)
  private SubstitutionReason reason;

  @Column(name = "constraint_ref", length = 160)
  private String constraintRef;

  @Type(JsonType.class)
  @Column(name = "method_overlay", columnDefinition = "jsonb")
  private List<MethodOverlayLine> methodOverlay;

  @Column(name = "notes", columnDefinition = "text")
  private String notes;

  @Column(name = "temporary", nullable = false)
  private boolean temporary;

  @Column(name = "applied_in_plan_ids", columnDefinition = "uuid[]")
  private UUID[] appliedInPlanIds;       // Postgres array — see LLD line 174; convert via custom UserType if Hibernate friction

  @Column(name = "application_count", nullable = false)
  private int applicationCount;

  @Column(name = "last_applied_at")
  private Instant lastAppliedAt;

  @Enumerated(EnumType.STRING)
  @Column(name = "state", nullable = false, length = 16)
  private SubstitutionState state;

  @Column(name = "promoted_to_version_id")
  private UUID promotedToVersionId;

  @CreationTimestamp
  @Column(name = "created_at", updatable = false, nullable = false)
  private Instant createdAt;

  @Column(name = "created_by_actor", nullable = false, updatable = false, length = 64)
  private String createdByActor;

  @Column(name = "adapter_trace_id", updatable = false)
  private UUID adapterTraceId;

  @Version @Column(name = "version", nullable = false)
  private long version;
}
```

### Service-impl — `promoteSubstitutionToVersion` skeleton

```java
@Transactional
public RecipeVersionDto promoteSubstitutionToVersion(UUID substitutionId, UUID actorUserId,
                                                      long expectedVersion, String changeReason) {
  RecipeSubstitution sub = recipeSubstitutionRepository.findById(substitutionId)
      .orElseThrow(RecipeSubstitutionNotFoundException::new);
  Recipe recipe = recipeRepository.findByIdAndDeletedAtIsNull(sub.getRecipeId())
      .orElseThrow(RecipeNotFoundException::new);
  if (recipe.getCatalogue() == Catalogue.SYSTEM) {
    throw new RecipeCatalogueViolationException("substitution promotion on SYSTEM recipe");
  }
  if (!recipe.getUserId().equals(actorUserId)) throw new RecipeNotFoundException();
  if (sub.getVersion() != expectedVersion) {
    throw new OptimisticLockingFailureException("stale expectedVersion");
  }
  if (sub.getState() == SubstitutionState.SUPERSEDED) {
    throw new SubstitutionTerminalStateException(sub.getState());
  }
  if (sub.getState() != SubstitutionState.ACCEPTED) {
    throw new SubstitutionPromotionPreconditionException(sub.getState());
  }

  RecipeVersion baseVersion = recipeVersionRepository.findWithBodyById(sub.getVersionId())
      .orElseThrow(RecipeVersionNotFoundException::new);
  RecipeBranch branch = recipeBranchRepository.findById(sub.getBranchId())
      .orElseThrow(RecipeBranchNotFoundException::new);

  NewVersionInput baseBody = mapper.fromVersion(baseVersion);
  NewVersionInput appliedBody = overlayApplier.apply(baseBody, List.of(sub));

  int newVersionNumber = branch.getCurrentVersion() + 1;
  RecipeVersion newVersion = RecipeVersion.builder()
      .id(UUID.randomUUID())
      .recipeId(recipe.getId()).branchId(branch.getId())
      .versionNumber(newVersionNumber).parentVersionId(baseVersion.getId())
      .changeDiff(versionDiffer.diff(baseVersion, appliedBody))
      .changeReason(changeReason).trigger(VersionTrigger.SUBSTITUTION_PROMOTION)
      .characterFingerprint(baseVersion.getCharacterFingerprint())
      .nutritionPerServing(null).embeddingStatus("pending")
      .createdByActor("user:" + actorUserId).adapterTraceId(null)
      .createdAt(Instant.now()).build();
  // child rows: build + attach
  newVersion.replaceIngredients(buildIngredients(appliedBody.ingredients(), newVersion));
  newVersion.replaceMethodSteps(buildMethodSteps(appliedBody.method(), newVersion));
  newVersion.setMetadata(buildMetadata(appliedBody.metadata(), newVersion));
  newVersion.setTags(buildTags(appliedBody.tags(), newVersion));
  recipeVersionRepository.saveAndFlush(newVersion);

  branch.setCurrentVersion(newVersionNumber);
  recipe.setCurrentVersion(newVersionNumber);
  // saveAndFlush both so the response sees the bumped values

  sub.setState(SubstitutionState.SUPERSEDED);
  sub.setPromotedToVersionId(newVersion.getId());
  recipeSubstitutionRepository.saveAndFlush(sub);

  publisher.publishEvent(new RecipeVersionCreatedEvent(
      newVersion.getId(), recipe.getId(), branch.getId(), newVersionNumber,
      traceIdFromMdcOrRandom(), Instant.now()));
  publisher.publishEvent(new RecipeUpdatedEvent(
      recipe.getId(), branch.getId(), newVersion.getId(), newVersionNumber,
      VersionTrigger.SUBSTITUTION_PROMOTION, traceIdFromMdcOrRandom(), Instant.now()));
  publisher.publishEvent(new RecipeSubstitutionStateChangedEvent(
      sub.getId(), recipe.getId(), sub.getVersionId(),
      SubstitutionState.ACCEPTED, SubstitutionState.SUPERSEDED,
      traceIdFromMdcOrRandom(), Instant.now()));
  return recipeVersionMapper.toDto(newVersion);
}
```

### `SubstitutionOverlayApplier` skeleton

```java
@Component
public class SubstitutionOverlayApplier {
  private static final Logger log = LoggerFactory.getLogger(SubstitutionOverlayApplier.class);

  public NewVersionInput apply(NewVersionInput baseBody, List<RecipeSubstitution> subs) {
    List<CreateIngredientRequest> ings = new ArrayList<>(baseBody.ingredients());
    List<CreateMethodStepRequest> steps = new ArrayList<>(baseBody.method());
    List<RecipeSubstitution> sorted = subs.stream()
        .sorted(Comparator.comparing(RecipeSubstitution::getCreatedAt))
        .toList();
    for (RecipeSubstitution s : sorted) {
      for (int i = 0; i < ings.size(); i++) {
        CreateIngredientRequest cur = ings.get(i);
        if (s.getOriginalMappingKey().equals(cur.ingredientMappingKey())) {
          ings.set(i, new CreateIngredientRequest(
              cur.lineOrder(), s.getSubstituteMappingKey(),
              cur.displayName(), s.getSubstituteQuantity(), s.getSubstituteUnit(),
              cur.preparation(), cur.optional(), cur.needsReview(), cur.mappingConfidence()));
        }
      }
      if (s.getMethodOverlay() != null) {
        for (MethodOverlayLine ol : s.getMethodOverlay()) {
          boolean replaced = false;
          for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).stepNumber() == ol.step()) {
              steps.set(i, new CreateMethodStepRequest(ol.step(), ol.instruction(),
                  steps.get(i).durationMinutes()));
              replaced = true;
              break;
            }
          }
          if (!replaced) log.warn("Method overlay references non-existent step {} on substitution {}", ol.step(), s.getId());
        }
      }
    }
    return new NewVersionInput(ings, steps, baseBody.metadata(), baseBody.tags());
  }
}
```

## Edge-case checklist

- [ ] `POST /substitutions` happy path (USER recipe, owner, version on the recipe, original ingredient present) → 201; row persisted with `state = PROPOSED`, `applicationCount = 0`, `appliedInPlanIds = empty`, `lastAppliedAt = null`; `RecipeSubstitutionCreatedEvent` published
- [ ] `POST /substitutions` for SYSTEM recipe → 422 `recipe-catalogue-violation`
- [ ] `POST /substitutions` for USER recipe owned by another user → 404
- [ ] `POST /substitutions` with `versionId` on a different recipe → 404 `recipe-version-not-found`
- [ ] `POST /substitutions` with `original.ingredientMappingKey` not in version's ingredient list → 422 `substitution-original-not-in-version`
- [ ] `POST /substitutions` validation: missing `versionId`, missing `original.ingredientMappingKey`, `original.quantity = -1` → 400
- [ ] `POST /substitutions/{subId}/accept` from PROPOSED → 200 + state ACCEPTED + event
- [ ] `POST /substitutions/{subId}/accept` from REJECTED → 200 + state ACCEPTED (re-activation; document divergence)
- [ ] `POST /substitutions/{subId}/accept` from ACCEPTED → 200 no-op (no event, no @Version bump)
- [ ] `POST /substitutions/{subId}/accept` from SUPERSEDED → 422 `substitution-terminal-state`
- [ ] `POST /substitutions/{subId}/accept` stale `expectedVersion` → 409
- [ ] `POST /substitutions/{subId}/reject` happy path (PROPOSED) → 200 + state REJECTED + event
- [ ] `POST /substitutions/{subId}/reject` from ACCEPTED → 200 + REJECTED + event
- [ ] `POST /substitutions/{subId}/reject` from REJECTED → 200 no-op (no event)
- [ ] `POST /substitutions/{subId}/promote-to-version` from ACCEPTED → 200; new RecipeVersion at `versionNumber + 1` with `trigger = SUBSTITUTION_PROMOTION`; substitution state → SUPERSEDED; `promotedToVersionId` populated; Recipe + Branch `currentVersion` bumped; **3 events** published (`RecipeVersionCreatedEvent`, `RecipeUpdatedEvent`, `RecipeSubstitutionStateChangedEvent`)
- [ ] `POST /substitutions/{subId}/promote-to-version` from PROPOSED → 422 `substitution-promotion-precondition`
- [ ] `POST /substitutions/{subId}/promote-to-version` from REJECTED → 422
- [ ] `POST /substitutions/{subId}/promote-to-version` from SUPERSEDED → 422 `substitution-terminal-state`
- [ ] `GET /substitutions/active` for a recipe with 3 ACCEPTED + 2 PROPOSED + 1 SUPERSEDED → returns 3 (only ACCEPTED)
- [ ] `GET /substitutions?versionId=X` returns only substitutions on that version with state ACCEPTED
- [ ] `GET /substitutions?versionId=` missing query param → 400
- [ ] `GET /versions/{versionId}/with-substitutions` → returns version DTO with overlaid ingredients; `appliedSubstitutionIds` lists the active substitutions; method overlay applied at correct step numbers
- [ ] `GET /versions/{versionId}/with-substitutions` with no active substitutions → identical to the base version; `appliedSubstitutionIds = []`
- [ ] `SubstitutionOverlayApplier` unit tests: identical overlay → no change; single swap → swapped; method-step overlay at step 2 → step 2 instruction replaced; overlay at non-existent step 99 → ignored with WARN
- [ ] `RecipeSubstitutionRecorder.recordSubstitution(subId, planId)` from ACCEPTED → `applicationCount += 1`, `lastAppliedAt = now`, `appliedInPlanIds` grew by one (or unchanged if idempotent re-call); from PROPOSED/REJECTED/SUPERSEDED → 422 `substitution-record-precondition`; from non-existent → 404
- [ ] `applied_in_plan_ids` array round-trips: insert with 2 UUIDs → re-read → both present in same order
- [ ] `method_overlay` JSONB round-trips: 3 overlay lines persist + re-read → all 3 present
- [ ] OpenAPI request/response shapes match (swagger-request-validator filter active in IT); the `oneOf` discriminated lifecycle endpoint validates correctly OR the agent splits into three endpoints with the rationale documented
- [ ] `RecipeBoundaryTest` (from 01a) still passes — new repo in `domain/repository/`; new `spi/` and `spi/internal/` subpackages whitelisted if the rule restricts
- [ ] `RecipeExceptionHandler` retains `@Order(Ordered.HIGHEST_PRECEDENCE)` after appending the five new methods
- [ ] State-machine transitions are deterministic — same input → same output (idempotency on no-op branches verified)
- [ ] No N+1 — promote flow: 1 SELECT substitution, 1 SELECT recipe, 1 SELECT version-with-body, 1 SELECT branch, 1 INSERT version, N INSERTs child rows, 1 UPDATE recipe, 1 UPDATE branch, 1 UPDATE substitution
- [ ] No regression on existing tests, including 01d's `RecipeBranchesFlowIT`, 01c's `RecipeManualEditFlowIT`, 01c's `RecipeVersionDiffFlowIT`, 01b's `RecipeImportFlowIT`, 01a's `RecipeCreateFlowIT`

## Files this ticket touches

```
NEW   src/main/resources/db/migration/V20260601800900__recipe_create_recipe_substitutions.sql

NEW   src/main/java/com/example/mealprep/recipe/api/controller/RecipeSubstitutionsController.java
NEW   src/main/java/com/example/mealprep/recipe/api/dto/AcceptSubstitutionRequest.java
NEW   src/main/java/com/example/mealprep/recipe/api/dto/CreateSubstitutionRequest.java
NEW   src/main/java/com/example/mealprep/recipe/api/dto/MethodOverlayLineDto.java
NEW   src/main/java/com/example/mealprep/recipe/api/dto/MethodOverlayLineRequest.java
NEW   src/main/java/com/example/mealprep/recipe/api/dto/PromoteSubstitutionRequest.java
NEW   src/main/java/com/example/mealprep/recipe/api/dto/RecipeSubstitutionDto.java
NEW   src/main/java/com/example/mealprep/recipe/api/dto/RejectSubstitutionRequest.java
NEW   src/main/java/com/example/mealprep/recipe/api/dto/SubstitutedItemDto.java
NEW   src/main/java/com/example/mealprep/recipe/api/dto/SubstitutionItemRequest.java
NEW   src/main/java/com/example/mealprep/recipe/api/dto/SubstitutionReason.java
NEW   src/main/java/com/example/mealprep/recipe/api/dto/SubstitutionState.java
NEW   src/main/java/com/example/mealprep/recipe/api/mapper/RecipeSubstitutionMapper.java
NEW   src/main/java/com/example/mealprep/recipe/domain/entity/RecipeSubstitution.java
NEW   src/main/java/com/example/mealprep/recipe/domain/entity/MethodOverlayLine.java                    (JSONB inner record)
NEW   src/main/java/com/example/mealprep/recipe/domain/repository/RecipeSubstitutionRepository.java
NEW   src/main/java/com/example/mealprep/recipe/domain/service/internal/SubstitutionOverlayApplier.java
NEW   src/main/java/com/example/mealprep/recipe/event/RecipeSubstitutionCreatedEvent.java
NEW   src/main/java/com/example/mealprep/recipe/event/RecipeSubstitutionStateChangedEvent.java
NEW   src/main/java/com/example/mealprep/recipe/exception/RecipeSubstitutionNotFoundException.java
NEW   src/main/java/com/example/mealprep/recipe/exception/SubstitutionOriginalNotInVersionException.java
NEW   src/main/java/com/example/mealprep/recipe/exception/SubstitutionTerminalStateException.java
NEW   src/main/java/com/example/mealprep/recipe/exception/SubstitutionPromotionPreconditionException.java
NEW   src/main/java/com/example/mealprep/recipe/exception/SubstitutionRecordPreconditionException.java
NEW   src/main/java/com/example/mealprep/recipe/spi/RecipeSubstitutionRecorder.java

MOD   src/main/java/com/example/mealprep/recipe/api/RecipeExceptionHandler.java                          (append 5 @ExceptionHandler methods; KEEP @Order(Ordered.HIGHEST_PRECEDENCE))
MOD   src/main/java/com/example/mealprep/recipe/domain/service/RecipeQueryService.java                  (append getActiveSubstitutions, getSubstitutionsForVersion, getSubstitution, getVersionWithSubstitutions)
MOD   src/main/java/com/example/mealprep/recipe/domain/service/RecipeUpdateService.java                 (append createSubstitution, acceptSubstitution, rejectSubstitution, promoteSubstitutionToVersion)
MOD   src/main/java/com/example/mealprep/recipe/domain/service/internal/RecipeServiceImpl.java          (implement the new methods + RecipeSubstitutionRecorder; reuse 01c's VersionDiffer + 01a/01c's child-row builders + 01d's branch-creation patterns)
MOD   src/main/java/com/example/mealprep/recipe/api/dto/RecipeVersionDto.java                            (add nullable appliedSubstitutionIds: List<UUID>; verify 01a's shape first — if added, also update the @JsonInclude config if needed)
MOD   src/main/java/com/example/mealprep/recipe/domain/entity/VersionTrigger.java                       (verify SUBSTITUTION_PROMOTION enum value present from 01a per LLD line 112; if absent, 01e appends — single shared file the agent may modify)

MOD   src/main/resources/openapi/paths/recipe.yaml      (append 4 new path-items below 01d's; do NOT touch existing)
MOD   src/main/resources/openapi/schemas/recipe.yaml    (append 11 new schemas; possibly modify RecipeVersionDto if 01a left appliedSubstitutionIds out)
MOD   src/main/resources/openapi/openapi.yaml           (4 lines under paths: in the `# recipe` block; 11 lines under components.schemas: in the `# recipe` block)

NEW   src/test/java/com/example/mealprep/recipe/RecipeSubstitutionsServiceTest.java                     (state machine; precondition gates; promotion writes new version + 3 events)
NEW   src/test/java/com/example/mealprep/recipe/RecipeSubstitutionsFlowIT.java                          (LLD line 817: full HTTP; create 201; accept/reject/promote-to-version state transitions; active listing; version listing; promotion creates a new RecipeVersion + marks SUPERSEDED)
NEW   src/test/java/com/example/mealprep/recipe/SubstitutionOverlayApplierTest.java                     (LLD line 807: identical, single swap, method overlay, missing step, ordering preserved)
MOD   src/test/java/com/example/mealprep/recipe/testdata/RecipeTestData.java                            (append substitution-request + DTO builders)
```

**Files this ticket does NOT modify**:

- `config/GlobalExceptionHandler.java`; `archunit/ModuleBoundaryTest.java`.
- Other modules' files (household, nutrition, provisions) — none touched.
- 01a's `Recipe`, `RecipeVersion`, `RecipeIngredient`, `RecipeMethodStep`, `RecipeMetadata`, `RecipeTags` entities — used as-is via setters; child rows constructed via existing builder helpers.
- 01c's `VersionDiffer` — reused for the promotion's `change_diff` computation.
- 01a's `RecipeVersionRepository`, `RecipeIngredientRepository` — used as-is (existing `findMappingKeysByVersionId` from 01a per LLD line 504 backs the original-ingredient validator).
- 01d's `RecipeBranchesController`, `FingerprintDeriver`, `DivergenceScoreCalculator` — independent.
- `RecipeBoundaryTest` — **verify**; if the rule whitelists subpackages, append `spi` to the allow-list (single shared file).
- `RecipeVersionDto` — append-only modification (add the new `appliedSubstitutionIds` field); 01a/01c rely on existing fields which stay intact.

## Dependencies

- **Hard dependency**: `recipe-01a` (merged) — `Recipe`, `RecipeBranch`, `RecipeVersion`, all body entities, `RecipeRepository`, `RecipeBranchRepository`, `RecipeVersionRepository`, `RecipeIngredientRepository.findMappingKeysByVersionId`, `RecipeQueryService`, `RecipeUpdateService`, `RecipeExceptionHandler`, `RecipeBoundaryTest`, `RecipeException`, `RecipeNotFoundException`, `RecipeCatalogueViolationException`, `RecipeVersionNotFoundException`, `Catalogue`, `VersionTrigger` (including `SUBSTITUTION_PROMOTION` value per LLD line 112), `RecipeVersionCreatedEvent`, `RecipeMapper`.
- **Hard dependency**: `recipe-01b` (merged) — `RecipeImport` co-exists; per-module YAML / advice append-only convention.
- **Hard dependency**: `recipe-01c` (merged) — `VersionDiffer` (re-used for promotion's diff), `NoChangesException` (not directly used by 01e but the pattern matches), `RecipeUpdatedEvent`, `NewVersionInput` internal record.
- **Hard dependency**: `recipe-01d` (merged) — `RecipeBranch.currentVersion` mutated by promotion; 01d's branch-creation patterns reused; `paths/recipe.yaml` and `schemas/recipe.yaml` already extended; 01e appends below.
- **Hard dependency**: `auth-01a` (merged) — `CurrentUserResolver`.
- **Hard dependency**: `refactor-01-split-merge-zones` (merged).
- **Sibling tickets running in parallel** (Wave 2 round 5): `household-01e`, `nutrition-01e`, `provisions-01e`. Only collision is the entry `openapi.yaml`.

## Acceptance / DoD

- [ ] `./mvnw -Dspotless.check.skip=true verify` passes locally on the agent's worktree
- [ ] `./mvnw spotless:apply` then `./mvnw spotless:check` clean
- [ ] CI green on the PR
- [ ] All edge-case items above ticked
- [ ] `RecipeExceptionHandler` retains `@Order(Ordered.HIGHEST_PRECEDENCE)` after appending the five new methods
- [ ] `saveAndFlush` used in promote-to-version path so the response payload reflects bumped `@Version` + `currentVersion`
- [ ] OpenAPI 3.0 nullable scalars use **inline** `nullable: true` (NOT `$ref + nullable: true`) on `constraintRef`, `methodOverlay`, `notes`, `lastAppliedAt`, `promotedToVersionId`, `adapterTraceId`, `reason`-on-RejectSubstitutionRequest
- [ ] All YAML descriptions with `,` `:` `'` single-quoted (round-4 lesson)
- [ ] The `oneOf` lifecycle endpoint validates correctly with swagger-request-validator; if not, the agent has split into three endpoints with the choice documented
- [ ] `MethodOverlayLine` JSONB round-trip — write 3 overlay lines → re-read → identical
- [ ] `applied_in_plan_ids` Postgres UUID array round-trip — write 2 UUIDs → re-read → same order
- [ ] State machine deterministic — same transitions always produce the same result; no-op branches don't bump `@Version`
- [ ] `RecipeSubstitutionRecorder` SPI bean wired; planner-01a can `@Autowired RecipeSubstitutionRecorder`
- [ ] `SubstitutionOverlayApplier` is pure (no DB access); unit-test coverage at six scenarios
- [ ] `VersionTrigger.SUBSTITUTION_PROMOTION` confirmed present (LLD line 112; if 01a omitted, 01e adds and documents)
- [ ] `RecipeVersionDto.appliedSubstitutionIds` field added; null on all non-overlay reads
- [ ] No regression on existing tests, including 01d's `RecipeBranchesFlowIT`, 01c's `RecipeManualEditFlowIT`, 01c's `RecipeVersionDiffFlowIT`
- [ ] No pom.xml dependency adds
- [ ] No N+1 on the promote-to-version hot path — verified via Hibernate stats

## What's NOT in scope

- **`SubstitutionOverlayApplier` wired into `getById` / `getVersion` hot reads** → deferred; 01e ships a dedicated `/with-substitutions` endpoint
- **`saveAdaptedSubstitution`** (pipeline-facing path) → **recipe-01f** with the rest of `RecipeWriteApi`
- **AI-driven substitution suggestion** — pipeline concern; no plan
- **Method-overlay full application semantics** (e.g. step renumbering after overlay) — 01e's applier replaces in place at named step numbers; step renumbering is a 01f concern
- **`recordSubstitution` (SPI impl) cross-module caller** — planner-01a will call; no caller in 01e
- **Substitution chaining** — applying a substitution to a version that's itself a substitution-promotion of another — semantically sound but not tested in 01e
- **Bulk-substitution endpoint** — none specified in the LLD
- **Substitution-rename endpoint** — none specified
- **Cross-version substitution overlay** (a substitution on v3 applied to v5) — LLD scopes substitutions to a single `versionId`; 01e enforces

Squash-merge with: `feat(recipe): 01e — RecipeSubstitution aggregate + state machine + SubstitutionOverlayApplier + propose/accept/reject/promote endpoints + 5 exceptions`
