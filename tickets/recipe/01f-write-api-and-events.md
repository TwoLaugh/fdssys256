# Ticket: recipe — 01f `RecipeWriteApi` SPI + `RecipeAdaptedEvent` + `RecipeEvolvedEvent` + Cross-Module `RecipeNutritionWriter` Impl

## Summary

Layer the **`RecipeWriteApi`** internal SPI for the (future) Adaptation Pipeline + the two new events `RecipeAdaptedEvent` (pipeline-only with trace ID) + `RecipeEvolvedEvent` (umbrella event for derived-data back-fills — nutrition / fingerprint / embedding) on top of the 01a/01b/01c/01d/01e recipe module. Per [LLD §`RecipeWriteApi` lines 583-624](../../lld/recipe.md), [LLD §Events lines 691-713](../../lld/recipe.md), [LLD §Flow 5 / Adaptation pipeline lines 751-755](../../lld/recipe.md). Ships:

- **`RecipeWriteApi`** SPI interface in `recipe.spi.RecipeWriteApi` (LLD line 586) with the six methods: `saveAdaptedVersion`, `saveAdaptedBranch`, `saveAdaptedSubstitution`, `updateNutritionStatus`, `updateCharacterFingerprint`, `updateBranchDivergence`, `storeEmbedding` (LLD lines 589-599). Plus the three `Save*Command` records (LLD lines 602-622).
- **Default impl `RecipeWriteApiImpl`** in `recipe/domain/service/internal/` (per LLD line 513 "implemented by a single `RecipeServiceImpl`"; 01f keeps the SPI's impl as a class on top of `RecipeServiceImpl` — same bean, additional interface).
- **`RecipeAdaptedEvent`** record per LLD lines 704-706.
- **`RecipeEvolvedEvent`** record (NEW — see "LLD divergence: RecipeEvolvedEvent shape" below) — umbrella event published when derived-data back-fills (nutrition, fingerprint, embedding) change a version's state.
- **Cross-module impl of `nutrition.spi.RecipeNutritionWriter`** (the SPI defined in nutrition-01f) — a `@Component` in `recipe/spi/internal/` that delegates `writeNutritionPerServing(versionId, result)` → `RecipeWriteApi.updateNutritionStatus(versionId, NutritionStatus.fromString(result.nutritionStatus()), result.toJsonNode())`. **Annotated `@ConditionalOnClass(name = "com.example.mealprep.nutrition.spi.RecipeNutritionWriter")`** so recipe-01f compiles + runs even if nutrition-01f isn't on the classpath at compile time — once both merge, the `@ConditionalOnMissingBean` on nutrition-01f's Noop steps aside and this real impl wires.

## Critical: recipe-01f ↔ nutrition-01f cross-SPI coupling

**Per parent's per-module guidance, Option B**. Nutrition-01f owns the OUTBOUND SPI interface `nutrition.spi.RecipeNutritionWriter`. **Recipe-01f does NOT define a `RecipeNutritionWriter` interface** — recipe defines its own internal `RecipeWriteApi` (this ticket's main artefact) AND a `@Component` impl of nutrition's `RecipeNutritionWriter` interface.

**Parallel-safety**:

- If nutrition-01f merged first → its SPI interface is on the classpath, recipe-01f's `@Component` impl wires, the Noop in nutrition-01f's config defers via `@ConditionalOnMissingBean(RecipeNutritionWriter.class)`. Recalc end-to-end works.
- If recipe-01f merges first → nutrition.spi.RecipeNutritionWriter isn't on the classpath, recipe-01f's `@ConditionalOnClass` keeps the impl dormant, the calc service doesn't exist yet so no one calls it. Recipe-01f's other deliverables (RecipeWriteApi, events) ship cleanly.
- When both merge → the impl wires; the Noop defers; recalc end-to-end works.

**Round-5 SPI Noop pattern reminders** (these apply to nutrition-01f's Noop, not recipe-01f's real impl, but the agent should know the pattern):

```java
@Configuration                                                // class name → bean "..."
public class NoopMyServiceConfiguration {
  @Bean
  @ConditionalOnMissingBean(MyService.class)
  MyService defaultMyService() { return new NoopImpl(); }     // method name DIFFERENT from class name (round-5 bug 2 fix)
}
```

Recipe-01f's `RecipeNutritionWriter` impl is a **`@Component`** (NOT a Noop fallback — it's the real implementation, supersedes Noop via `@ConditionalOnMissingBean(RecipeNutritionWriter.class)`):

```java
@Component
@ConditionalOnClass(name = "com.example.mealprep.nutrition.spi.RecipeNutritionWriter")
public class RecipeNutritionWriterImpl implements RecipeNutritionWriter {
  // delegates to RecipeWriteApi.updateNutritionStatus
}
```

**Why `@Component` + `@ConditionalOnClass` here is OK (not the round-5 bug 1 pattern)**: round-5's bug-1 was `@Component @ConditionalOnMissingBean` — the conditional fires at component-scan, before other beans register. **Recipe-01f uses `@ConditionalOnClass`** which fires on classpath, not bean-presence — `@ConditionalOnClass` is fully composable with component-scan and doesn't suffer the ordering bug. **Worth user review** — alternative is to put the impl behind `@Configuration class + @Bean @ConditionalOnClass` for symmetry; rejected because `@ConditionalOnClass` semantics are correct for the bug-1 fix. **Document explicitly in the impl Javadoc.**

**LLD divergence note** — **`RecipeEvolvedEvent` shape**:

LLD recipe.md §Events (lines 691-707) declares `RecipeCreatedEvent`, `RecipeUpdatedEvent`, `RecipeArchivedEvent`, `RecipePromotedEvent`, `RecipeAdaptedEvent`. **There's no `RecipeEvolvedEvent`**. LLD line 711 says: "The HLD describes a single `RecipeEvolvedEvent`. This LLD splits it into `RecipeUpdatedEvent` (any version write — manual or otherwise) and `RecipeAdaptedEvent` (pipeline-only with trace ID) so subscribers can target precisely." The parent's per-module guidance for 01f explicitly lists `RecipeEvolvedEvent` as a deliverable separate from `RecipeAdaptedEvent`. **01f reconciles**:

- `RecipeUpdatedEvent` (already shipped from recipe-01a/01c): emitted on every new version write — manual or via the catalogue's edit flow.
- `RecipeAdaptedEvent` (NEW in 01f, per LLD line 704): pipeline-only, carries `adapterTraceId`.
- `RecipeEvolvedEvent` (NEW in 01f, **not in the LLD**): umbrella event published when a version's **derived data** (nutrition, fingerprint, embedding) changes WITHOUT a new version being created. The three operations that emit `RecipeEvolvedEvent`: `updateNutritionStatus`, `updateCharacterFingerprint`, `storeEmbedding`. **Worth user review** — the LLD splits version-creation events; the parent guidance asks for an umbrella for back-fills. 01f ships the umbrella with a `EvolvedReason` enum:

```java
public record RecipeEvolvedEvent(UUID recipeId, UUID versionId, EvolvedReason reason,
                                 UUID traceId, Instant occurredAt) {
  public enum EvolvedReason { NUTRITION_RECALCULATED, FINGERPRINT_REFRESHED, EMBEDDING_STORED }
}
```

Reasons:
- Per the LLD §Consumed line 717 ("Nutrition recalculation listener subscribes to both events" — `RecipeUpdatedEvent` + `RecipeAdaptedEvent`) — **`updateNutritionStatus` is itself called by the nutrition listener**, so the back-fill is recursive if it republishes `RecipeUpdatedEvent`. **`RecipeEvolvedEvent` is the safe non-recursive signal** the recipe module emits to say "derived data changed; downstream caches may refresh."
- Future planner module's "feed change" subscription can target `RecipeEvolvedEvent` specifically without re-triggering nutrition recalc on every embedding back-fill.

**Worth user review**: alternative is to fold all three into `RecipeUpdatedEvent` with a discriminator. Rejected because the LLD already locks `RecipeUpdatedEvent.newVersionNumber` / `.newVersionId` semantics — back-fills don't create new versions, so the field would be nullable, polluting the contract. Cleaner to add the umbrella.

**LLD divergence note** — **`RecipeWriteApi` race-check shipping vs deferred**:

LLD line 786 + line 588 spec `saveAdaptedVersion` as race-checked: "`findById ... FOR UPDATE` on the parent `Recipe` row, asserts `recipe.currentVersion == command.expectedParentVersionNumber` and the version-row id matches. Mismatch → `RecipeVersionConflictException`. Pipeline rebases up to 3 times." **01f ships the race check fully** — the version-conflict exception (LLD line 668 — `RecipeVersionConflictException` mapped to 409) is already declared in the recipe LLD. The Adaptation Pipeline itself is not part of recipe-01f scope — but the SPI it consumes is.

**Scope limits** for the WriteApi methods (LLD line 583 — "internal SPI for the Adaptation Pipeline"):

- `saveAdaptedVersion` — full impl. Race-checks `expectedParentVersionId` + `expectedParentVersionNumber` against `recipe.currentVersion` under `SELECT ... FOR UPDATE`. Persists `RecipeVersion`. Bumps `recipe.currentVersion`. Publishes `RecipeUpdatedEvent` + `RecipeAdaptedEvent`.
- `saveAdaptedBranch` — full impl. Creates `RecipeBranch` + v1 `RecipeVersion`. Sets `branch_point_version_id`. Publishes `RecipeAdaptedEvent`.
- `saveAdaptedSubstitution` — full impl. Delegates to existing 01e `RecipeSubstitution` aggregate; persists with `state = ACCEPTED` (per 01e's state-machine rename) and `adapterTraceId` populated. Publishes `RecipeAdaptedEvent` with `outcomeType = SUBSTITUTION`.
- `updateNutritionStatus` — writes `nutritionPerServing` JSONB + `nutritionStatus` enum on the version row. Publishes `RecipeEvolvedEvent(reason = NUTRITION_RECALCULATED)`.
- `updateCharacterFingerprint` — writes `characterFingerprint` JSONB on the version row. Publishes `RecipeEvolvedEvent(reason = FINGERPRINT_REFRESHED)`.
- `updateBranchDivergence` — writes `divergence_score` on the branch row. No event.
- `storeEmbedding` — writes `embedding` (float[]) + `embedding_status = embedded` + `embedding_model_id` on the version row. Publishes `RecipeEvolvedEvent(reason = EMBEDDING_STORED)`. **LLD line 599 signature is `storeEmbedding(UUID versionId, float[] embedding)`**; 01f extends to `storeEmbedding(UUID versionId, float[] embedding, String modelId)` because the schema has `embedding_model_id` per LLD line 117 — caller must supply. **LLD divergence**: signature widened by one param.

**Defers** (still out of scope after 01f):

- The Adaptation Pipeline itself — separate module, not in recipe scope.
- The `EmbeddingTask` producer (LLD line 115 — "producer is recipe module via `EmbeddingTask`, async defer-and-pending"). 01f exposes the write hook; the producer ticket is **recipe-01h** per the round-5 retro plan.
- AI tag inference — **recipe-01k**.
- Promotion / archive — **recipe-01g**.
- Search — **recipe-01i**.
- Helpers — **recipe-01j**.
- ArchUnit rule asserting "no module outside `recipe.*` imports `RecipeWriteApi`" (LLD line 824). 01f ships the rule as `@ArchTest` in the existing `RecipeBoundaryTest` — **NOTE**: the LLD line 824 says "asserts the isolation in place once the pipeline module exists" — the pipeline module doesn't exist yet, so the rule today is vacuously true. **01f ships the rule with the assertion** — it'll catch any accidental import attempt. Document in the test's comment.

01f unblocks **two downstream callers**:
- The Adaptation Pipeline (future module) — has a typed SPI to write through.
- Nutrition-01f — its `RecipeNutritionWriter` SPI is implemented in recipe-01f's `recipe/spi/internal/` package.

## Behavioural spec

### `RecipeWriteApi` SPI

1. New public interface `com.example.mealprep.recipe.spi.RecipeWriteApi` verbatim from [LLD lines 586-600](../../lld/recipe.md), with the `storeEmbedding` signature widened to `(UUID versionId, float[] embedding, String modelId)` per LLD divergence above.
2. New public records `com.example.mealprep.recipe.spi.SaveAdaptedVersionCommand`, `SaveAdaptedBranchCommand`, `SaveAdaptedSubstitutionCommand` verbatim from [LLD lines 602-622](../../lld/recipe.md).
3. Lives in NEW `recipe/spi/` sub-package (matches household-01e and nutrition-01f).

### `saveAdaptedVersion` flow

4. `@Transactional` (write, REQUIRED).
5. `recipeRepository.findById(command.recipeId())` with `LockModeType.PESSIMISTIC_WRITE` (SELECT ... FOR UPDATE per LLD line 786). Missing → 404 `RecipeNotFoundException`.
6. **Race check**: `recipe.currentVersion != command.expectedParentVersionNumber` OR `recipe.currentBranch.headVersionId != command.expectedParentVersionId` → throw `RecipeVersionConflictException` (existing from recipe-01a or recipe-01c; LLD line 667 maps to 409). The pipeline is responsible for rebasing.
7. **Persist `RecipeVersion`**:
   - `id = UUID.randomUUID()` (application-set per style guide).
   - `recipe = recipe`, `branch = recipe.currentBranch (resolved via recipeBranchRepository.findById(command.branchId()))`.
   - `versionNumber = recipe.currentVersion + 1`.
   - `parentVersionId = command.expectedParentVersionId`.
   - `trigger = VersionTrigger.ADAPTATION_PIPELINE` (existing enum from recipe-01a/01c; if missing, **append** the enum value — single-line additive change).
   - `changeDiff` / `changeReason` / `adapterTraceId` from command.
   - `characterFingerprint` from command.
   - `nutritionStatus = pending` initially (the nutrition listener back-fills later via `updateNutritionStatus`).
   - `createdBy` = system actor (e.g. `"pipeline:<adapterTraceId>"` = 44 chars). `created_by_actor` column is already `varchar(64)` on main (parent-side patch during 01a verify already addressed the round-4 gotcha — see Migrations section below). No widening needed in 01f.
8. **Persist children**: `RecipeIngredient`, `RecipeMethodStep`, `RecipeMetadata`, `RecipeTags` — same shape as recipe-01a's create flow.
9. **Update parent recipe**: `recipe.currentVersion = newVersionNumber`. JPA dirty-flag triggers UPDATE. `@Version` bump captured via OptimisticLockingFailureException (defensive — the FOR UPDATE lock should prevent concurrent bumps; the OL check is belt-and-braces).
10. **Publish two events `AFTER_COMMIT`**:
    - `RecipeUpdatedEvent(recipeId, branchId, newVersionId, newVersionNumber, VersionTrigger.ADAPTATION_PIPELINE, traceId, occurredAt)` per LLD line 695. Existing event record; reused.
    - `RecipeAdaptedEvent(recipeId, branchId, newVersionId, AdaptationOutcomeType.NEW_VERSION, command.adapterTraceId(), traceId, occurredAt)` per LLD line 704. **`AdaptationOutcomeType` enum** is NEW in 01f — values: `NEW_VERSION`, `BRANCH`, `SUBSTITUTION`.
11. Returns `RecipeVersionDto` (via `RecipeVersionMapper.toDto(saved)`).

### `saveAdaptedBranch` flow

12. `@Transactional`. Loads recipe (404 if missing). Resolves `parentBranchId` (404 if missing).
13. **Duplicate-name guard**: `recipeBranchRepository.findByRecipeIdAndName(recipeId, command.name())` → present → 409 `RecipeBranchNameConflictException` (NEW or pre-existing — verify recipe-01d).
14. **Persist `RecipeBranch`** with `name`, `label`, `reason`, `parentBranchId`, `branchPointVersionId = command.branchPointVersionId`.
15. **Persist v1 `RecipeVersion`** on the new branch with `versionNumber = 1`, `parentVersionId = command.branchPointVersionId`, `trigger = ADAPTATION_PIPELINE`, full ingredient/method/metadata/tags child set. `characterFingerprint` from command.
16. **Do NOT** bump `recipe.currentVersion` or `currentBranchId` — branch creation doesn't move the head.
17. **Publish** `RecipeAdaptedEvent(recipeId, newBranchId, newVersionId, AdaptationOutcomeType.BRANCH, command.adapterTraceId(), ...)` `AFTER_COMMIT`.
18. Returns `RecipeBranchDto`.

### `saveAdaptedSubstitution` flow

19. `@Transactional`. Delegates to existing recipe-01e's `createSubstitution` impl with `state = ACCEPTED` (per 01e's state-machine rename) + `adapterTraceId` populated. **The 01e flow handles validation** (original ingredient present in version, etc).
20. **Publish** `RecipeAdaptedEvent(recipeId, branchId, versionId, AdaptationOutcomeType.SUBSTITUTION, command.adapterTraceId(), ...)` `AFTER_COMMIT`. This is in **addition** to any 01e-published events — 01f's path is the pipeline path.
21. Returns `RecipeSubstitutionDto`.

### `updateNutritionStatus` flow

22. `@Transactional`. `recipeVersionRepository.findById(versionId)` → 404 `RecipeVersionNotFoundException` if missing.
23. Write `nutritionStatus = status` (enum) + `nutritionPerServing = jsonNode` (raw JSONB). JPA UPDATE. **Append-only constraint** (LLD line 130 — "Versions are append-only — never updated after initial save with three well-defined exceptions: nutrition recalculation, ...") — nutrition is one of the three permitted mutations; the entity's `@Version` does NOT bump (LLD line 278 — `RecipeVersion` has no `@Version`).
24. **Publish** `RecipeEvolvedEvent(recipeId, versionId, EvolvedReason.NUTRITION_RECALCULATED, traceId, occurredAt)` `AFTER_COMMIT`.

### `updateCharacterFingerprint` flow

25. `@Transactional`. `recipeVersionRepository.findById(versionId)` → 404.
26. Write `characterFingerprint = fingerprint` JSONB.
27. **Publish** `RecipeEvolvedEvent(recipeId, versionId, EvolvedReason.FINGERPRINT_REFRESHED, ...)` `AFTER_COMMIT`.

### `updateBranchDivergence` flow

28. `@Transactional`. `recipeBranchRepository.findById(branchId)` → 404 `RecipeBranchNotFoundException`.
29. Write `divergence_score = score`. **No event** (LLD doesn't specify one).

### `storeEmbedding` flow

30. `@Transactional`. `recipeVersionRepository.findById(versionId)` → 404.
31. Write `embedding = vector` + `embedding_status = "embedded"` + `embedding_model_id = modelId` (per LLD line 117).
32. **Publish** `RecipeEvolvedEvent(recipeId, versionId, EvolvedReason.EMBEDDING_STORED, ...)` `AFTER_COMMIT`.

### `RecipeAdaptedEvent` shape (NEW)

33. Verbatim per LLD line 704:
   ```java
   public record RecipeAdaptedEvent(UUID recipeId, UUID branchId, UUID newVersionId,
                                    AdaptationOutcomeType outcomeType, UUID adapterTraceId,
                                    UUID traceId, Instant occurredAt) {}
   public enum AdaptationOutcomeType { NEW_VERSION, BRANCH, SUBSTITUTION }
   ```
34. Lives in `recipe/event/`.

### `RecipeEvolvedEvent` shape (NEW)

35. Per the LLD divergence note above:
   ```java
   public record RecipeEvolvedEvent(UUID recipeId, UUID versionId, EvolvedReason reason,
                                    UUID traceId, Instant occurredAt) {
     public enum EvolvedReason { NUTRITION_RECALCULATED, FINGERPRINT_REFRESHED, EMBEDDING_STORED }
   }
   ```
36. Lives in `recipe/event/`.

### Cross-module `RecipeNutritionWriter` impl

37. New class `com.example.mealprep.recipe.spi.internal.RecipeNutritionWriterImpl` (package-private):
   ```java
   @Component
   @ConditionalOnClass(name = "com.example.mealprep.nutrition.spi.RecipeNutritionWriter")
   class RecipeNutritionWriterImpl implements com.example.mealprep.nutrition.spi.RecipeNutritionWriter {
     private final RecipeWriteApi writeApi;
     private final ObjectMapper objectMapper;

     @Override
     public void writeNutritionPerServing(UUID versionId, RecipeNutritionResultDto result) {
       NutritionStatus status = NutritionStatus.valueOf(result.nutritionStatus().toUpperCase());
       writeApi.updateNutritionStatus(versionId, status, objectMapper.valueToTree(result));
     }
   }
   ```
38. The `@ConditionalOnClass` ensures recipe-01f compiles + runs when nutrition-01f's interface isn't on the classpath. **Spring Boot's class-presence check uses `name = "..."` form** (string) to avoid a `NoClassDefFoundError` at class-load time.
39. **Test coverage**: when nutrition-01f's interface IS on the classpath, this bean wires; the nutrition Noop's `@ConditionalOnMissingBean` defers. Verified by an IT that loads the full Spring context with both modules and asserts `RecipeNutritionWriter` resolves to `RecipeNutritionWriterImpl` (not the Noop).

### Internal facade re-export

40. `RecipeServiceImpl` implements `RecipeWriteApi` directly (LLD line 513 — single impl). Constructor unchanged from 01e (already takes repos + mappers).
41. **Optional**: append `RecipeWriteApi` re-export to `RecipeModule.java` facade if 01a follows the nested-class pattern. **Verify and skip if not.**

### `NutritionStatus` enum

42. New enum `com.example.mealprep.recipe.domain.entity.NutritionStatus` — values `CALCULATED, PARTIAL, PENDING` (uppercase Java, lowercase in DB to match LLD line 511). **Verify whether 01a/01b/01c/01d/01e shipped this enum** — if yes, reuse; if no, ship in 01f.

### Migrations

43. **Zero new migrations.** The `recipe_versions.created_by_actor` column is already `varchar(64)` (parent-side patch during recipe-01a verify already covered the round-4 gotcha). `"pipeline:<uuid>"` = 44 chars fits comfortably. Verified: `V20260601800100__recipe_create_recipe_versions.sql` line for the column reads `varchar(64) NOT NULL`.

### Errors

44. **New module exceptions** (extending existing `RecipeException` from 01a):
    - `RecipeNutritionWriteFailedException`? NO — that's nutrition-01f's exception. Recipe-01f keeps existing exception types.
    - `RecipeBranchNameConflictException` (409) — IF not already shipped by recipe-01d (verify; LLD §`RecipeBranchRepository.findByRecipeIdAndName` line 493 makes this a natural fit; 01d may have shipped it).
    - Otherwise: re-uses `RecipeNotFoundException`, `RecipeVersionNotFoundException`, `RecipeBranchNotFoundException`, `RecipeVersionConflictException`.
45. **Append `@ExceptionHandler` methods** to the existing `RecipeExceptionHandler` IF new exceptions ship; else no change.
46. **DO NOT** modify `config/GlobalExceptionHandler.java`.

### ArchUnit boundary

47. Append `@ArchTest` to existing `RecipeBoundaryTest`:
   ```java
   @ArchTest
   static final ArchRule recipeWriteApiNotImportedByOtherModules = noClasses()
       .that().resideOutsideOfPackages("com.example.mealprep.recipe..", "..test..")
       .should().dependOnClassesThat().resideInAPackage("com.example.mealprep.recipe.spi..");
   ```
   Per LLD line 824. **Today** (no pipeline module exists), the rule is vacuously true; the assertion catches any future violation.
48. **Append** `spi` and `spi.internal` to the existing `RecipeBoundaryTest`'s allowed sub-package list if it enumerates them (verify).

## OpenAPI updates

**Zero OpenAPI changes**. 01f ships an internal SPI + events — no HTTP endpoints. Do NOT touch `paths/recipe.yaml`, `schemas/recipe.yaml`, or the entry `openapi.yaml`.

## Verbatim shape snippets

### SPI interface

```java
package com.example.mealprep.recipe.spi;

public interface RecipeWriteApi {
  RecipeVersionDto      saveAdaptedVersion     (SaveAdaptedVersionCommand command);
  RecipeBranchDto       saveAdaptedBranch      (SaveAdaptedBranchCommand command);
  RecipeSubstitutionDto saveAdaptedSubstitution(SaveAdaptedSubstitutionCommand command);

  void updateNutritionStatus(UUID versionId, NutritionStatus status, JsonNode nutritionPerServing);
  void updateCharacterFingerprint(UUID versionId, CharacterFingerprintDto fingerprint);
  void updateBranchDivergence(UUID branchId, BigDecimal divergenceScore);
  void storeEmbedding(UUID versionId, float[] embedding, String modelId);
}
```

### Save-version command record

```java
package com.example.mealprep.recipe.spi;

public record SaveAdaptedVersionCommand(
    UUID recipeId, UUID branchId, int expectedParentVersionNumber, UUID expectedParentVersionId,
    List<CreateIngredientRequest> ingredients, List<CreateMethodStepRequest> method,
    CreateRecipeMetadataRequest metadata, CreateRecipeTagsRequest tags,
    CharacterFingerprintDto characterFingerprint,
    JsonNode changeDiff, String changeReason, UUID adapterTraceId) {}
```

### Events

```java
public record RecipeAdaptedEvent(UUID recipeId, UUID branchId, UUID newVersionId,
                                 AdaptationOutcomeType outcomeType, UUID adapterTraceId,
                                 UUID traceId, Instant occurredAt) {}

public enum AdaptationOutcomeType { NEW_VERSION, BRANCH, SUBSTITUTION }

public record RecipeEvolvedEvent(UUID recipeId, UUID versionId, EvolvedReason reason,
                                 UUID traceId, Instant occurredAt) {
  public enum EvolvedReason { NUTRITION_RECALCULATED, FINGERPRINT_REFRESHED, EMBEDDING_STORED }
}
```

### `RecipeNutritionWriterImpl` (cross-module SPI consumer)

```java
package com.example.mealprep.recipe.spi.internal;

@Component
@ConditionalOnClass(name = "com.example.mealprep.nutrition.spi.RecipeNutritionWriter")
class RecipeNutritionWriterImpl implements com.example.mealprep.nutrition.spi.RecipeNutritionWriter {
  private static final Logger log = LoggerFactory.getLogger(RecipeNutritionWriterImpl.class);
  private final RecipeWriteApi writeApi;
  private final ObjectMapper objectMapper;

  RecipeNutritionWriterImpl(RecipeWriteApi writeApi, ObjectMapper objectMapper) {
    this.writeApi = writeApi;
    this.objectMapper = objectMapper;
  }

  @Override
  public void writeNutritionPerServing(UUID versionId, RecipeNutritionResultDto result) {
    log.debug("Writing nutrition for version {} (status {}, calories {}/serving)", versionId,
        result.nutritionStatus(), result.caloriesPerServing());
    NutritionStatus status = NutritionStatus.valueOf(result.nutritionStatus().toUpperCase());
    writeApi.updateNutritionStatus(versionId, status, objectMapper.valueToTree(result));
  }
}
```

### `saveAdaptedVersion` skeleton (impl-side)

```java
@Override
@Transactional
public RecipeVersionDto saveAdaptedVersion(SaveAdaptedVersionCommand cmd) {
  Recipe recipe = recipeRepository.findByIdForUpdate(cmd.recipeId())
      .orElseThrow(RecipeNotFoundException::new);
  if (recipe.getCurrentVersion() != cmd.expectedParentVersionNumber()) {
    throw new RecipeVersionConflictException("expected v" + cmd.expectedParentVersionNumber()
        + " but current is v" + recipe.getCurrentVersion());
  }
  RecipeBranch branch = recipeBranchRepository.findById(cmd.branchId())
      .orElseThrow(RecipeBranchNotFoundException::new);
  // ... build RecipeVersion, persist children, bump recipe.currentVersion, return DTO ...
  eventPublisher.publishEvent(new RecipeUpdatedEvent(...));
  eventPublisher.publishEvent(new RecipeAdaptedEvent(..., AdaptationOutcomeType.NEW_VERSION, cmd.adapterTraceId(), ...));
  return recipeVersionMapper.toDto(saved);
}
```

## Files this ticket touches

```
NEW   src/main/java/com/example/mealprep/recipe/spi/RecipeWriteApi.java
NEW   src/main/java/com/example/mealprep/recipe/spi/SaveAdaptedVersionCommand.java
NEW   src/main/java/com/example/mealprep/recipe/spi/SaveAdaptedBranchCommand.java
NEW   src/main/java/com/example/mealprep/recipe/spi/SaveAdaptedSubstitutionCommand.java
NEW   src/main/java/com/example/mealprep/recipe/spi/internal/RecipeNutritionWriterImpl.java
NEW   src/main/java/com/example/mealprep/recipe/event/RecipeAdaptedEvent.java
NEW   src/main/java/com/example/mealprep/recipe/event/AdaptationOutcomeType.java
NEW   src/main/java/com/example/mealprep/recipe/event/RecipeEvolvedEvent.java
NEW   src/main/java/com/example/mealprep/recipe/domain/entity/NutritionStatus.java          (verify; reuse if 01a/01b/01c/01d/01e shipped)

# (zero new migrations — created_by_actor is already varchar(64) on main)

MOD   src/main/java/com/example/mealprep/recipe/domain/service/internal/RecipeServiceImpl.java     (implements RecipeWriteApi; constructor unchanged)
MOD   src/main/java/com/example/mealprep/recipe/api/RecipeExceptionHandler.java                     (append @ExceptionHandler for any NEW exceptions; KEEP @Order(Ordered.HIGHEST_PRECEDENCE))
MOD   src/main/java/com/example/mealprep/recipe/RecipeModule.java                                   (optional re-export of RecipeWriteApi if facade pattern)

NEW   src/test/java/com/example/mealprep/recipe/RecipeWriteApiTest.java                            (saveAdaptedVersion race-check; saveAdaptedBranch duplicate-name; saveAdaptedSubstitution delegates; updateNutritionStatus writes JSONB; updateCharacterFingerprint writes; storeEmbedding writes vector + modelId)
NEW   src/test/java/com/example/mealprep/recipe/RecipeWriteApiIT.java                              (full DB: saveAdaptedVersion succeeds when parent matches, throws on concurrent bump; saveAdaptedBranch creates branch + v1 + publishes RecipeAdaptedEvent; saveAdaptedSubstitution publishes outcomeType=SUBSTITUTION)
NEW   src/test/java/com/example/mealprep/recipe/RecipeEventPublicationTest.java                    (assert RecipeAdaptedEvent + RecipeEvolvedEvent emitted at the right moments)
NEW   src/test/java/com/example/mealprep/recipe/RecipeNutritionWriterImplIT.java                   (when nutrition.spi.RecipeNutritionWriter is on classpath, the @Component wires; delegates to RecipeWriteApi.updateNutritionStatus)
MOD   src/test/java/com/example/mealprep/recipe/testdata/RecipeTestData.java                       (append builders: SaveAdaptedVersionCommand, SaveAdaptedBranchCommand, SaveAdaptedSubstitutionCommand, RecipeAdaptedEvent, RecipeEvolvedEvent)
MOD   src/test/java/com/example/mealprep/recipe/RecipeBoundaryTest.java                            (append `recipeWriteApiNotImportedByOtherModules` @ArchTest; append `spi` / `spi.internal` to allow-list if enumerated)
```

**Files this ticket does NOT modify** (cross-cutting; sibling round-6 tickets running in parallel must not collide):

- `src/main/java/com/example/mealprep/config/GlobalExceptionHandler.java` — no new cross-cutting exception.
- `src/test/java/com/example/mealprep/archunit/ModuleBoundaryTest.java` — module-specific rule lives in `RecipeBoundaryTest`.
- Other modules' `paths/*.yaml`, `schemas/*.yaml`, `<module>ExceptionHandler.java`, migrations, entities — none touched.
- The nutrition module (any file) — **explicitly not modified**. Recipe-01f's `RecipeNutritionWriterImpl` imports `com.example.mealprep.nutrition.spi.RecipeNutritionWriter` but the conditional `@ConditionalOnClass` keeps recipe-01f decoupled at compile time + classpath time.
- The household, provisions, auth, preference modules — none touched.
- `openapi.yaml`, `paths/recipe.yaml`, `schemas/recipe.yaml` — **zero changes** (this ticket ships no HTTP surface).
- 01a/01b/01c/01d/01e's existing tests — none modified; only `RecipeTestData.java` gets new builder methods, `RecipeBoundaryTest.java` gets new ArchTest.

## Edge-case checklist

- [ ] `saveAdaptedVersion` race-check: parent at v3, command claims `expectedParentVersionNumber = 2` → 409 `RecipeVersionConflictException`
- [ ] `saveAdaptedVersion` race-check: parent at v3, command claims `expectedParentVersionId` mismatches → 409
- [ ] `saveAdaptedVersion` happy: parent at v3, command's expected matches → v4 persisted, `recipe.currentVersion = 4`
- [ ] `saveAdaptedVersion` SELECT...FOR UPDATE acquired (verified via `@SpyBean` on EntityManager or via concurrent-call IT)
- [ ] `saveAdaptedVersion` publishes both `RecipeUpdatedEvent` AND `RecipeAdaptedEvent` `AFTER_COMMIT`
- [ ] `saveAdaptedVersion` writes `created_by_actor = "pipeline:<adapterTraceId>"` (44 chars — verified via JdbcTemplate; widened column accommodates)
- [ ] `saveAdaptedVersion` child entities (ingredient/method/metadata/tags) all persisted
- [ ] `saveAdaptedBranch` happy: creates branch + v1 + publishes `RecipeAdaptedEvent(BRANCH)`
- [ ] `saveAdaptedBranch` duplicate-name: 409 `RecipeBranchNameConflictException`
- [ ] `saveAdaptedBranch` does NOT bump `recipe.currentVersion` (head stays on main branch)
- [ ] `saveAdaptedSubstitution` happy: delegates to 01e flow + publishes `RecipeAdaptedEvent(SUBSTITUTION)`
- [ ] `saveAdaptedSubstitution` original-ingredient-not-in-version: 422 via 01e's existing validation
- [ ] `updateNutritionStatus(versionId, CALCULATED, jsonNode)`: row's `nutrition_status = 'calculated'`, `nutrition_per_serving = jsonNode`, `RecipeEvolvedEvent(NUTRITION_RECALCULATED)` published
- [ ] `updateNutritionStatus` on missing version → 404 `RecipeVersionNotFoundException`
- [ ] `updateCharacterFingerprint` writes JSONB + publishes `RecipeEvolvedEvent(FINGERPRINT_REFRESHED)`
- [ ] `updateBranchDivergence` writes `divergence_score`; NO event published
- [ ] `storeEmbedding(versionId, vector, "openai:text-embedding-3-small")`: writes `embedding`, `embedding_status = 'embedded'`, `embedding_model_id = "openai:text-embedding-3-small"`, publishes `RecipeEvolvedEvent(EMBEDDING_STORED)`
- [ ] `RecipeAdaptedEvent` payload includes `adapterTraceId` for downstream join
- [ ] `RecipeEvolvedEvent` published `AFTER_COMMIT` (verified via `TransactionTemplate` test that asserts the event handler runs only after the write commits)
- [ ] `RecipeNutritionWriterImpl` `@ConditionalOnClass` wires when `nutrition.spi.RecipeNutritionWriter` is on the classpath (IT)
- [ ] `RecipeNutritionWriterImpl` delegates correctly: status string `"calculated"` → enum `CALCULATED` → `updateNutritionStatus` call → `RecipeEvolvedEvent(NUTRITION_RECALCULATED)`
- [ ] `RecipeNutritionWriterImpl` `@ConditionalOnClass` does NOT register when nutrition module's SPI absent (verify via a context-load test in a profile that excludes the nutrition jar — **may not be testable in a monolithic build**; document as "trust the conditional + cover the wiring branch in the merged-context IT")
- [ ] Migration `V<timestamp>__recipe_widen_created_by_actor.sql` applies cleanly; existing rows preserved; new inserts up to 64 chars succeed
- [ ] `RecipeBoundaryTest`'s `recipeWriteApiNotImportedByOtherModules` ArchTest passes (vacuously true today)
- [ ] `RecipeBoundaryTest` allow-list extended to include `spi` + `spi.internal` if it enumerates
- [ ] `RecipeExceptionHandler` retains `@Order(Ordered.HIGHEST_PRECEDENCE)` after appending any new handler methods
- [ ] **No** OpenAPI changes — recipe-01f ships zero HTTP surface
- [ ] No regression on 01a/01b/01c/01d/01e tests (especially recipe-01e's substitution flow IT and recipe-01a's create flow IT)
- [ ] No `pom.xml` dependency adds
- [ ] No nutrition / household / provisions / auth / preference module file touched (recipe imports the nutrition SPI interface via `@ConditionalOnClass` — this is one-way and decouples through Spring's classpath conditional, not module modification)

## Dependencies

- **Hard dependency**: `recipe-01a` (merged) — `Recipe`, `RecipeBranch`, `RecipeVersion`, all repos, `RecipeException`, `RecipeNotFoundException`, `RecipeVersionNotFoundException`, `RecipeBranchNotFoundException`, `RecipeVersionConflictException`, `RecipeUpdatedEvent`, `VersionTrigger` enum (add `ADAPTATION_PIPELINE` if missing), `CharacterFingerprintDto`, `RecipeServiceImpl`.
- **Hard dependency**: `recipe-01b` (merged) — URL-import patterns; reuse only.
- **Hard dependency**: `recipe-01c` (merged) — manual-edit patterns; reuse only.
- **Hard dependency**: `recipe-01d` (merged) — branch-creation flow; `RecipeBranchRepository.findByRecipeIdAndName`.
- **Hard dependency**: `recipe-01e` (merged) — `RecipeSubstitution`, `SubstitutionState` (PROPOSED/ACCEPTED/REJECTED/SUPERSEDED), `createSubstitution` flow.
- **Hard dependency**: `auth-01a` (merged) — no direct usage but `RecipeBoundaryTest` rules.
- **Hard dependency**: `refactor-01-split-merge-zones` (merged) — per-module YAML / advice / boundary-test layout.
- **Soft dependency on nutrition-01f (running in parallel)** — **see "Cross-SPI coupling" below**.

### Cross-SPI coupling (recipe-01f ↔ nutrition-01f)

**Parent's per-module guidance, Option B**. Recipe-01f imports `com.example.mealprep.nutrition.spi.RecipeNutritionWriter` and ships `RecipeNutritionWriterImpl` as a `@Component @ConditionalOnClass(name = "...")`. **The `@ConditionalOnClass` form (NOT `@ConditionalOnMissingBean` on the component) is the parallel-safety mechanism**:

- If nutrition-01f hasn't merged: nutrition's SPI interface isn't on the classpath. The `@ConditionalOnClass` evaluates to `false` (class absent). The `@Component` is NOT registered. Recipe-01f compiles + runs cleanly. The `RecipeNutritionWriterImpl.java` source file compiles only if the nutrition-01f jar is present — **so the agent SHOULD verify nutrition-01f's SPI interface is on the build classpath**. **If not on the build classpath at recipe-01f's implementation time**, the agent SHOULD:
  - **Option A**: Ship `RecipeNutritionWriterImpl.java` with a fully-qualified reference and trust the build to fail-fast if the interface vanishes. **Rejected** — the class wouldn't compile.
  - **Option B (recommended)**: Ship `RecipeNutritionWriterImpl.java` only AFTER nutrition-01f's interface lands. If implementing recipe-01f BEFORE nutrition-01f merges, **ship everything else** (RecipeWriteApi, events, migration) and **leave a TODO** to add `RecipeNutritionWriterImpl.java` in a follow-up commit / sibling-merge. Report this clearly.
  - **Option C**: Ship a placeholder interface `nutrition.spi.RecipeNutritionWriter` IN recipe-01f's module under a clearly-marked "TEMPORARY SHIM — DELETE WHEN NUTRITION-01F MERGES" comment. **Rejected** — pollutes recipe's package; the SPI is nutrition's.
- If both merge: recipe-01f's `@Component` wires, nutrition-01f's Noop `@ConditionalOnMissingBean` defers.

**Worth user review**: Option B is the "merge nutrition-01f first" path. If parallel implementation can't guarantee ordering, the agent SHOULD report which scenario they're in.

### Merge order considerations

- **Both can merge first** — neither blocks the other architecturally.
- **Recommended**: nutrition-01f first (it defines the SPI interface); recipe-01f's `RecipeNutritionWriterImpl` then compiles cleanly. **Worth user review** — if parallel, the recipe-01f agent should verify nutrition-01f's branch and report whether the interface is on its build path.

- **Sibling tickets running in parallel** (Wave 2 round 6): `household-01f`, `nutrition-01f`, `provisions-01f`. None should touch any recipe file or the cross-cutting files listed above. **Only** recipe-01f imports nutrition-01f's SPI interface (one-way coupling). Only collision point is the entry `openapi.yaml` — **recipe-01f makes zero changes to `openapi.yaml`** (no HTTP surface), so no collision risk on the entry file.

## Acceptance / DoD

- [ ] `./mvnw -Dspotless.check.skip=true verify` passes locally on the agent's worktree
- [ ] `./mvnw spotless:apply` then `./mvnw spotless:check` clean
- [ ] CI green on the PR (build + spotless + ArchUnit gate — OpenAPI lint trivially passes since no YAML changes)
- [ ] All edge-case items above ticked
- [ ] `RecipeExceptionHandler` retains `@Order(Ordered.HIGHEST_PRECEDENCE)` after any append
- [ ] No new migrations added (created_by_actor already varchar(64) from 01a)
- [ ] `RecipeBoundaryTest`'s `recipeWriteApiNotImportedByOtherModules` ArchTest present + passing
- [ ] `RecipeNutritionWriterImpl` uses `@ConditionalOnClass(name = "...")` form (NOT class-literal — string-form avoids `NoClassDefFoundError` at class-load if nutrition jar is absent)
- [ ] `@Transactional` on every WriteApi method — no `@Transactional(noRollbackFor = ...)` needed (no 4xx-from-write-then-throw pattern in this scope)
- [ ] No `pom.xml` dependency adds
- [ ] No nutrition / household / provisions / auth / preference module file touched (recipe imports nutrition.spi only via `@ConditionalOnClass`)
- [ ] No `openapi.yaml` / `paths/recipe.yaml` / `schemas/recipe.yaml` changes — verify zero diff on these files

## What's NOT in scope

- **The Adaptation Pipeline module** — separate module; not built; not in recipe-01f scope.
- **`EmbeddingTask` producer** — recipe-01h or later. 01f only ships the write hook.
- AI tag inference — **recipe-01k**.
- Promotion / archive flows — **recipe-01g**.
- Recipe search — **recipe-01i**.
- Cross-module helpers (`getIngredientMappingKeys` etc) — **recipe-01j**.
- HTTP-facing endpoints — 01f is an internal-SPI ticket; zero HTTP surface.
- Cross-module ArchUnit rule asserting the SPI's isolation **enforces** isolation today (vacuously true; rule is in place for future pipeline-module enforcement).
- Async / batch-mode WriteApi methods — synchronous-only in v1.

Squash-merge with: `feat(recipe): 01f — RecipeWriteApi SPI + RecipeAdaptedEvent + RecipeEvolvedEvent + cross-module RecipeNutritionWriter impl (@ConditionalOnClass)`
