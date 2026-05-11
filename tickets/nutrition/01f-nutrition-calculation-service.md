# Ticket: nutrition — 01f NutritionCalculationService + RecipeNutritionWriter SPI + Recipe-Event Listeners

## Summary

Layer the **`NutritionCalculationService`** + the two `@TransactionalEventListener(AFTER_COMMIT)` listeners (`RecipeVersionCreatedEvent`-style + `RecipeUpdatedEvent` per [LLD §Consumed line 931](../../lld/nutrition.md)) + a recipe-bound **outbound SPI `RecipeNutritionWriter`** + the manual-recalc REST endpoint `POST /api/v1/nutrition/recipes/{recipeId}/versions/{versionId}/recalculate` on top of the 01a/01b/01c/01d/01e nutrition module. Per [LLD §`NutritionCalculationService` lines 741-753](../../lld/nutrition.md), [LLD §Consumed lines 926-933](../../lld/nutrition.md), [LLD §`RecipeNutritionResultDto` + `CalculateRecipeNutritionRequest` lines 492-508](../../lld/nutrition.md), [LLD §`UnmappedIngredientDto` line 508](../../lld/nutrition.md), [LLD §Flow 6 lines 970-982](../../lld/nutrition.md) (cache-hit / cache-miss arithmetic). Ships the calc service over `IngredientMappingRepository` (from 01d), computes nutrition-per-serving as `sum(ingredientMapping.nutritionPer100g × gramsEstimate / 100) / servings`, picks `nutritionStatus ∈ {calculated, partial, pending}` per the LLD rules (all ingredients mapped + non-pending = `calculated`; any missing = `partial`; no ingredients resolved = `pending`), and **writes back to the recipe via the new outbound SPI `RecipeNutritionWriter`** (per parent's per-module guidance "Option B" — see SPI section below). Two event listeners trigger recalc on recipe writes; manual `POST .../recalculate` endpoint lets an admin or feedback-driven path force recalculation.

## Critical: recipe-01f ↔ nutrition-01f cross-SPI coupling

**Per parent's per-module guidance, 01f defines an OUTBOUND SPI `RecipeNutritionWriter` in `nutrition.spi`** (not in `recipe.spi`). The calc service calls it after computing per-serving nutrition. **Recipe-01f (running in parallel)** ships a `@Component` impl of `nutrition.spi.RecipeNutritionWriter` that delegates to recipe's own internal `RecipeWriteApi.updateNutritionStatus` (LLD recipe.md line 596). For parallel safety, recipe-01f's `@Component` impl is **`@ConditionalOnClass(name = "com.example.mealprep.nutrition.spi.RecipeNutritionWriter")`** so recipe-01f compiles + runs even if nutrition-01f hasn't merged yet (the interface isn't on its classpath).

**01f ships its OWN `NoopRecipeNutritionWriter` `@Configuration` + `@Bean @ConditionalOnMissingBean` fallback** so until recipe-01f merges + wires its impl, the calc service runs cleanly and logs WARN ("nutrition computed but not persisted to recipe — recipe-01f impl not yet wired"). Recipe-01f's `@Component` impl takes precedence the moment both merge.

**Round-5 SPI Noop pattern — apply verbatim**:

```java
@Configuration
public class NoopRecipeNutritionWriterConfiguration {
  @Bean
  @ConditionalOnMissingBean(RecipeNutritionWriter.class)
  RecipeNutritionWriter defaultRecipeNutritionWriter() {                                 // method name DIFFERENT from class name
    return new NoopRecipeNutritionWriterImpl();
  }

  static class NoopRecipeNutritionWriterImpl implements RecipeNutritionWriter {
    private static final Logger log = LoggerFactory.getLogger(NoopRecipeNutritionWriterImpl.class);
    @Override
    public void writeNutritionPerServing(UUID versionId, RecipeNutritionResultDto result) {
      log.warn("NoopRecipeNutritionWriter: skipping write for version {} — recipe-01f impl not yet wired", versionId);
    }
  }
}
```

**Do NOT use** `@Component @ConditionalOnMissingBean` on the Noop class itself (round-5 bug 1: registers unconditionally, then a real impl appears → `NoUniqueBeanDefinitionException`). **Do NOT** name the `@Bean` method the same as the `@Configuration` class (round-5 bug 2: `BeanDefinitionOverrideException`).

**Test-side override**: tests that inject a fake `RecipeNutritionWriter` via `@TestConfiguration` MUST annotate the bean `@Primary` (round-5 bug 3 fix).

**LLD divergence note** — **two events instead of one**: LLD line 931 says "**`RecipeEvolvedEvent`** → triggers `NutritionCalculationService.recalculateForEvolvedRecipe`." Recipe-01f's LLD (line 711) splits `RecipeEvolvedEvent` into `RecipeUpdatedEvent` (manual writes) + `RecipeAdaptedEvent` (pipeline writes). 01f listens to **both** events. **Both call the same internal method** `recalculateForEvolvedRecipe(CalculateRecipeNutritionRequest)`. The LLD's intent ("recalc on any version write that changes ingredients") is preserved.

**LLD divergence note** — **manual recalc endpoint**: LLD §`IngredientLookupController` line 824 says "`NutritionCalculationService` is in-process only — no REST — because the recipe module injects it directly." **01f adds a single REST endpoint** `POST /api/v1/nutrition/recipes/{recipeId}/versions/{versionId}/recalculate` for **manual recompute** (admin / feedback / debugging). Reasons:

- The save-time call from recipe is in-process (LLD respected).
- Manual recalc is a different use case — an operator wants to force recalculation without producing a new recipe version. Without REST, this is unreachable in v1.
- The endpoint is **not** invoked by recipe; it's a side-door for the operator.

**Worth user review**: the endpoint could be admin-gated. v1 ships it open to any authenticated caller; an admin gate can be added when the user role enum gains an `ADMIN` value.

**LLD divergence note** — **`@Transactional` semantics on the listener path**: the listener is `@TransactionalEventListener(phase = AFTER_COMMIT)` (LLD line 911). The recalc method itself is `@Transactional(readOnly = true)` for the cache lookups + a separate write-tx initiated by `RecipeNutritionWriter` (which is a recipe-module concern). 01f's calc service does NOT open a write tx — it computes, then delegates the write through the SPI. The SPI impl (in recipe-01f) opens its own write tx.

**Defers** (still out of scope after 01f):

- `NutritionFloorGateService` → **nutrition-01g**
- Weekly aggregates + `DivergenceDetector` → **nutrition-01h**
- AI-rich ingredient parsing (free-text → structured form) — already deferred in 01d; 01f's calc takes pre-structured `RecipeIngredientLineDto` (LLD line 498) verbatim and skips AI parse
- Embedding-model integration on the recipe — recipe-01h
- Cache-warming sweep for partial recipes — operations-only concern, no ticket
- Per-user nutrition target overrides feeding the calc — calc is target-independent; nutrition floor gate handles target comparison

01f unblocks **recipe-01f's adapted-version + manual-edit flow**: every new `RecipeVersion` triggers an automatic recalc via the event listener, and recipe persists the result via the SPI. Without 01f, every recipe version carries `nutritionStatus = pending` forever (which 01a/01b/01c/01d/01e tickets already establish as the default).

## Behavioural spec

### `NutritionCalculationService` — public interface

1. New public interface `com.example.mealprep.nutrition.domain.service.NutritionCalculationService` verbatim from [LLD lines 746-752](../../lld/nutrition.md):
   ```java
   public interface NutritionCalculationService {
     RecipeNutritionResultDto calculateRecipeNutrition(CalculateRecipeNutritionRequest request);
     RecipeNutritionResultDto recalculateForEvolvedRecipe(CalculateRecipeNutritionRequest request);
   }
   ```
2. **Implemented by `NutritionServiceImpl`** (existing class from 01a/01b/01c/01d/01e). The new methods join the existing impl per the LLD's single-impl convention.

### `calculateRecipeNutrition` flow (save-time)

3. **Read-only**: `@Transactional(readOnly = true)`. The method only reads ingredient mappings; **the write-back happens via the SPI** which opens its own tx (recipe-01f's concern).
4. **Input**: `CalculateRecipeNutritionRequest(@NotNull UUID recipeId, @NotNull @Size(min=1) List<RecipeIngredientLineDto> ingredients, @NotNull @Min(1) Integer servings)`. **Existing DTO from 01d** (LLD line 492). No new request DTO.
5. **For each `RecipeIngredientLineDto`**:
   - If `ingredientMappingKey` non-null → look up via `ingredientMappingRepository.findBySearchTerm(ingredientMappingKey)` (existing repo from 01d).
   - If `ingredientMappingKey` null AND `name` non-blank → look up by normalised `name` via existing `IntakeKeyNormaliser` (from 01d) → `findBySearchTerm`. (01d's `IngredientMappingPipeline` is **NOT** invoked here — 01f assumes pre-populated cache; cache-miss paths produce `unmapped` entries.)
   - **Missing mapping** → append `new UnmappedIngredientDto(line.name(), "not-in-cache", BigDecimal.ZERO)` to the result's `unmapped` list. Contributes zero nutrition. **Does NOT** trigger the live USDA/OFF pipeline (Flow 6) — 01f is read-only over the cache.
6. **Per-ingredient arithmetic** (LLD line 975): `gramsForLine = line.gramsEstimate()` (BigDecimal; if null and `quantity + unit` present, use existing 01d gram-conversion if available — else treat as unmapped). Multiply `mapping.nutritionPer100g.<field>` by `gramsForLine / 100` for every macro + every micro key. `BigDecimal.ROUND_HALF_UP` to 2 decimal places per LLD §`IngredientNutritionDocument` precision.
7. **Sum across ingredients** → totals. **Divide by `servings`** → per-serving values. Calories: integer (rounded half-up). Macros (`proteinG`, `carbsG`, `fatG`, `fibreG`): `BigDecimal` to 2 d.p. Micros: `Map<String, BigDecimal>` to 2 d.p.; keys present only if at least one ingredient contributed (no zero-filled entries).
8. **`nutritionStatus`** (LLD line 511):
   - All ingredients resolved AND no `IngredientMapping.needsReview = true` AND no `IngredientMapping.source = MANUAL` overrides flagged unverified → `calculated`.
   - Some ingredients resolved, some unmapped OR any mapping `needsReview = true` → `partial`.
   - Zero ingredients resolved (every line missing) → `pending`.
   - **Worth user review** — LLD line 511 only enumerates the three statuses; the boundary "needsReview makes it partial" is a 01f decision aligned with the calc's "trust the cache only when it's verified" intent.
9. **Build the result** `RecipeNutritionResultDto(recipeId, caloriesPerServing, proteinPerServingG, carbsPerServingG, fatPerServingG, fibrePerServingG, microsPerServing, nutritionStatus, unmapped)` per LLD line 503.
10. **No write from the calc service itself** — caller (or the listener — see below) invokes `RecipeNutritionWriter.writeNutritionPerServing` separately. The save-time direct call from recipe takes the returned DTO and writes via the SPI in the same in-process call (recipe-01f's concern).

### `recalculateForEvolvedRecipe` flow

11. **Identical body** to `calculateRecipeNutrition` — separate method name **for log/observability clarity per LLD line 750**. Single shared private helper. **Worth user review** — the LLD's separation feels redundant for the runtime; preserved as written.
12. Returns `RecipeNutritionResultDto`. The event listener that calls this method **then invokes `RecipeNutritionWriter.writeNutritionPerServing(versionId, result)`** (the listener does the SPI call, NOT the recalc method itself — keeps the calc method pure).

### Event listener — `RecipeUpdatedEvent`

13. New `@Component` `RecipeUpdatedEventListener` in `nutrition/domain/service/internal/` (existing sub-package convention). Has one method `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT) public void onRecipeUpdated(RecipeUpdatedEvent event)`.
14. **Event shape** (recipe-01f's record): `RecipeUpdatedEvent(UUID recipeId, UUID branchId, UUID newVersionId, int newVersionNumber, VersionTrigger trigger, UUID traceId, Instant occurredAt)`. **01f reads `recipeId` + `newVersionId`** only.
15. **Cross-module read** to fetch the version's ingredient list: uses `RecipeQueryService.getVersion(recipeId, branchId, versionNumber)` from recipe-01a (already public). Returns `RecipeVersionDto` which carries `List<RecipeIngredientDto>`. **Map** each `RecipeIngredientDto` → `RecipeIngredientLineDto` (LLD line 498) — fields `name`, `ingredientMappingKey`, `quantity`, `unit`, `gramsEstimate`, `isCooked` map 1:1.
16. **Build `CalculateRecipeNutritionRequest(event.recipeId(), lines, version.servings())`** → invoke `recalculateForEvolvedRecipe(request)` → write back via `RecipeNutritionWriter.writeNutritionPerServing(event.newVersionId(), result)`.
17. **Idempotency**: re-delivery of the same event (same `newVersionId`) → re-runs the calc → re-writes the same result. The recipe-01f SPI impl is idempotent (writing the same JSONB twice is a no-op). **Do NOT** add a "seen-versions" cache — Spring's event delivery is at-least-once but the calc is deterministic + the write is idempotent.
18. **Failure-mode**: if `RecipeQueryService.getVersion` returns empty (race — version deleted between event publish and listener handle) → log WARN + return. If the calc throws → log ERROR + return (listener does NOT re-throw — it's `AFTER_COMMIT`, no upstream to roll back). If the SPI throws → log ERROR + return.

### Event listener — `RecipeAdaptedEvent`

19. Second method on the same listener class: `@TransactionalEventListener(phase = AFTER_COMMIT) public void onRecipeAdapted(RecipeAdaptedEvent event)`.
20. **Same body** as the `RecipeUpdatedEvent` listener — extracts `recipeId` + `newVersionId`, fetches the version, builds the request, calls `recalculateForEvolvedRecipe`, writes via SPI. **Trace log includes `event.adapterTraceId()`** so observability can join the recalc to the pipeline trace (LLD line 709).
21. **Both listeners may fire for the same write** when the pipeline writes a new version (pipeline publishes BOTH `RecipeUpdatedEvent` and `RecipeAdaptedEvent` per LLD recipe.md line 755 + 711). The calc runs twice, the SPI write happens twice — both writes produce the same JSONB so it's a benign no-op on the second pass. **Worth user review** — alternative is a "fire one, suppress the other" rule on the recipe side; punted because both listeners are independent + the second write is idempotent. **Document on the listener Javadoc.**

### `POST /api/v1/nutrition/recipes/{recipeId}/versions/{versionId}/recalculate`

22. New endpoint on a new controller `NutritionRecipeRecalcController` (under `nutrition/api/controller/`). Authenticated. Server resolves `actorUserId` via `CurrentUserResolver`.
23. **Path-variable values**: both UUIDs (`recipeId`, `versionId`). **No URL-encoding concerns** — UUIDs are safe in path segments (round-4 gotcha doesn't apply).
24. **Authorisation**: any authenticated caller (v1 — no admin role). **Worth user review** — alternative is to restrict to the recipe's owner via `RecipeQueryService.getById(recipeId).userId == actorUserId`; v1 keeps it open because the recalc is read-from-cache + write-via-SPI (no user-data exposure).
25. **Flow**: fetch the version via `recipeQueryService.getVersion(recipeId, /* branchId resolution */, /* versionNumber */)`. **Lookup issue**: the recipe LLD's `getVersion(recipeId, branchId, versionNumber)` takes a `(recipeId, branchId, versionNumber)` triple; 01f's path carries `(recipeId, versionId)` as a UUID directly. **01f calls `recipeQueryService.getVersionById(versionId)` if such a method exists; else falls back to listing all branches + finding the version**. **Verify the recipe service interface**; if `getVersionById` is missing, **add it** to the recipe `RecipeQueryService` SPI signature — **NO**: that crosses sibling-ticket scope. Instead, **01f's controller looks up via a new query method on `NutritionQueryService` that delegates internally**: NO — simplest is to require the caller to provide branch + versionNumber. **Final shape**: endpoint becomes `POST /api/v1/nutrition/recipes/{recipeId}/versions/{versionId}/recalculate` carrying a request body `RecalculateRecipeNutritionRequest { @NotNull UUID branchId, @Min(1) int versionNumber }`. **Worth user review** — alternative is to require recipe-01f to add a `getVersionById` query; rejected to keep 01f decoupled from recipe-01f's surface.
26. **Response**: 200 with `RecipeNutritionResultDto` (the calc result). **The SPI write happens before returning** so the caller knows the recipe has been updated.
27. **404 ladder**: version not found → 404 `RecipeVersionLookupFailedException` (NEW 01f exception — recipe-01f's `RecipeVersionNotFoundException` lives in recipe; 01f maps the empty `Optional` to its own 404 with a clear cross-module-trace message).
28. **422**: recipe-01f's `RecipeNutritionWriter` impl throws (e.g. version is immutable, or branch mismatch) → 422 `RecipeNutritionWriteFailedException` (NEW). With `NoopRecipeNutritionWriter` wired → the write logs WARN and returns silently; the endpoint returns 200 with the calc result and a `Warning` header `100 - "recipe-01f impl not yet wired; nutrition computed but not persisted"`. **Worth user review** — the `Warning` header is a clearer signal than silently returning success; alternatives include 503 (Service Unavailable). 200 + Warning header keeps the contract simple.

### `RecipeNutritionWriter` SPI

29. New public interface `com.example.mealprep.nutrition.spi.RecipeNutritionWriter`:
   ```java
   public interface RecipeNutritionWriter {
     /**
      * Persist computed per-serving nutrition to a recipe version.
      * Implementations: recipe-01f wires the real impl that delegates to RecipeWriteApi.updateNutritionStatus.
      * Until then, NoopRecipeNutritionWriter logs WARN and returns.
      */
     void writeNutritionPerServing(UUID versionId, RecipeNutritionResultDto result);
   }
   ```
   Lives in a NEW `nutrition/spi/` sub-package (matches household-01e's `household/spi/`).
30. `NoopRecipeNutritionWriterConfiguration` `@Configuration` class — see the round-5 Noop pattern in the Summary. Lives in `nutrition/spi/internal/` (package-private).
31. **Wire-up**: `NutritionRecipeRecalcController` (and the listener) takes `RecipeNutritionWriter` via constructor injection — Spring picks the real impl if recipe-01f is on the classpath, else the Noop. **Field injection or `ObjectProvider` not needed** — at startup time exactly one bean exists (Noop OR real impl), the `@ConditionalOnMissingBean` ensures no collision.

### Errors

32. New module exception subclasses extending the existing `NutritionException` from 01a:
    - `RecipeVersionLookupFailedException` (404, `type = .../recipe-version-lookup-failed`) — when `RecipeQueryService.getVersion` returns empty.
    - `RecipeNutritionWriteFailedException` (422, `type = .../recipe-nutrition-write-failed`) — when the SPI impl throws.
33. **Append two new `@ExceptionHandler` methods** to the existing `NutritionExceptionHandler` `@RestControllerAdvice` from 01a/01b/01c/01d/01e (already `@Order(Ordered.HIGHEST_PRECEDENCE)`). Do **NOT** create a second handler class. Do **NOT** modify `config/GlobalExceptionHandler.java`.

### Cross-module facade

34. **Append** `NutritionCalculationService` re-export to `NutritionModule.java` if 01a follows the nested-class facade pattern; else `@Autowired NutritionCalculationService` works directly. **Optional.**

## Database

**Zero migrations.** 01f is a pure code-path change. The calc reads `IngredientMapping` (cache from 01d); the SPI handles the recipe-side write (recipe-01f's concern).

## OpenAPI updates

### Append to `src/main/resources/openapi/paths/nutrition.yaml`

(File created by 01a, extended by 01b/01c/01d/01e — append one new path-item below 01e's most recent block. Do NOT touch existing path-items.)

```yaml
nutritionRecipeRecalc:
  post:
    tags: [Nutrition]
    operationId: recalculateRecipeNutrition
    summary: 'Manually recalculate per-serving nutrition for a recipe version; result written back via the recipe-side SPI.'
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
    requestBody:
      required: true
      content:
        application/json:
          schema: { $ref: '../schemas/nutrition.yaml#/RecalculateRecipeNutritionRequest' }
    responses:
      '200':
        description: 'Computed per-serving nutrition. May include a Warning header if the recipe-side SPI is not yet wired.'
        content:
          application/json:
            schema: { $ref: '../schemas/nutrition.yaml#/RecipeNutritionResultDto' }
      '400': { description: 'Validation error', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '401': { description: 'Unauthenticated', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '404': { description: 'Recipe version not found', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '422': { description: 'Recipe-side write failed (e.g. version immutable)', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
```

### Append to `src/main/resources/openapi/schemas/nutrition.yaml`

```yaml
RecalculateRecipeNutritionRequest:
  type: object
  required: [branchId, versionNumber]
  properties:
    branchId: { type: string, format: uuid }
    versionNumber: { type: integer, minimum: 1 }
RecipeNutritionResultDto:
  type: object
  required: [recipeId, caloriesPerServing, proteinPerServingG, carbsPerServingG, fatPerServingG, fibrePerServingG, microsPerServing, nutritionStatus, unmapped]
  properties:
    recipeId: { type: string, format: uuid }
    caloriesPerServing: { type: integer, minimum: 0 }
    proteinPerServingG: { type: number, format: double, minimum: 0 }
    carbsPerServingG: { type: number, format: double, minimum: 0 }
    fatPerServingG: { type: number, format: double, minimum: 0 }
    fibrePerServingG: { type: number, format: double, minimum: 0 }
    microsPerServing:
      type: object
      additionalProperties: { type: number, format: double, minimum: 0 }
    nutritionStatus:
      type: string
      enum: [calculated, partial, pending]
    unmapped:
      type: array
      items: { $ref: '#/UnmappedIngredientDto' }
UnmappedIngredientDto:
  type: object
  required: [name, reason]
  properties:
    name: { type: string, maxLength: 255 }
    reason: { type: string, maxLength: 64 }
    confidence:
      type: number
      format: double
      minimum: 0
      maximum: 1
      nullable: true
```

**Gotcha applied**: `confidence` on `UnmappedIngredientDto` uses **inline** `nullable: true`. `nutritionStatus` inlines the enum (no `$ref + nullable` trap).

**Gotcha applied**: every description string containing `,` / `:` / `'` is single-quoted per round-4.

### Append to entry `src/main/resources/openapi/openapi.yaml`

```yaml
  /api/v1/nutrition/recipes/{recipeId}/versions/{versionId}/recalculate:
    $ref: 'paths/nutrition.yaml#/nutritionRecipeRecalc'
```

Under `components.schemas:` in the `# nutrition` block:

```yaml
    RecalculateRecipeNutritionRequest: { $ref: 'schemas/nutrition.yaml#/RecalculateRecipeNutritionRequest' }
    RecipeNutritionResultDto: { $ref: 'schemas/nutrition.yaml#/RecipeNutritionResultDto' }
    UnmappedIngredientDto: { $ref: 'schemas/nutrition.yaml#/UnmappedIngredientDto' }
```

(If 01d already shipped `RecipeNutritionResultDto` + `UnmappedIngredientDto` schemas — **verify before duplicating**. The schemas are LLD-declared in 01d's territory; 01f only appends if 01d didn't.)

## Verbatim shape snippets

### SPI interface

```java
package com.example.mealprep.nutrition.spi;

public interface RecipeNutritionWriter {
  void writeNutritionPerServing(UUID versionId, RecipeNutritionResultDto result);
}
```

### Noop config (round-5 pattern)

```java
package com.example.mealprep.nutrition.spi.internal;

@Configuration
public class NoopRecipeNutritionWriterConfiguration {

  @Bean
  @ConditionalOnMissingBean(RecipeNutritionWriter.class)
  RecipeNutritionWriter defaultRecipeNutritionWriter() {
    return new NoopRecipeNutritionWriterImpl();
  }

  static class NoopRecipeNutritionWriterImpl implements RecipeNutritionWriter {
    private static final Logger log = LoggerFactory.getLogger(NoopRecipeNutritionWriterImpl.class);

    @Override
    public void writeNutritionPerServing(UUID versionId, RecipeNutritionResultDto result) {
      log.warn("NoopRecipeNutritionWriter: skipping write for version {} (status {}, calories {}/serving) "
          + "— recipe-01f impl not yet wired", versionId, result.nutritionStatus(), result.caloriesPerServing());
    }
  }
}
```

### Calc service skeleton

```java
@Transactional(readOnly = true)
public RecipeNutritionResultDto calculateRecipeNutrition(CalculateRecipeNutritionRequest req) {
  List<UnmappedIngredientDto> unmapped = new ArrayList<>();
  BigDecimal totalCalories = BigDecimal.ZERO;
  BigDecimal totalProtein = BigDecimal.ZERO, totalCarbs = BigDecimal.ZERO,
      totalFat = BigDecimal.ZERO, totalFibre = BigDecimal.ZERO;
  Map<String, BigDecimal> totalMicros = new HashMap<>();
  boolean anyNeedsReview = false;
  int resolvedCount = 0;

  for (RecipeIngredientLineDto line : req.ingredients()) {
    String key = line.ingredientMappingKey() != null
        ? line.ingredientMappingKey()
        : intakeKeyNormaliser.normalise(line.name());
    Optional<IngredientMapping> mapping = key == null ? Optional.empty()
        : ingredientMappingRepository.findBySearchTerm(key);
    if (mapping.isEmpty()) {
      unmapped.add(new UnmappedIngredientDto(line.name(), "not-in-cache", BigDecimal.ZERO));
      continue;
    }
    resolvedCount++;
    if (mapping.get().getNeedsReview()) anyNeedsReview = true;
    BigDecimal grams = line.gramsEstimate() != null
        ? line.gramsEstimate() : BigDecimal.ZERO;
    BigDecimal factor = grams.divide(BD_100, 6, RoundingMode.HALF_UP);
    IngredientNutritionDocument n = mapping.get().getNutritionPer100g();
    totalCalories = totalCalories.add(BigDecimal.valueOf(n.calories()).multiply(factor));
    totalProtein = totalProtein.add(n.proteinG().multiply(factor));
    totalCarbs   = totalCarbs.add(n.carbsG().multiply(factor));
    totalFat     = totalFat.add(n.fatG().multiply(factor));
    totalFibre   = totalFibre.add(n.fibreG().multiply(factor));
    n.micros().forEach((k, v) -> totalMicros.merge(k, v.multiply(factor), BigDecimal::add));
  }

  BigDecimal servings = BigDecimal.valueOf(req.servings());
  int caloriesPerServing = totalCalories.divide(servings, 0, RoundingMode.HALF_UP).intValueExact();
  String status = resolvedCount == 0 ? "pending"
      : (unmapped.isEmpty() && !anyNeedsReview ? "calculated" : "partial");

  return new RecipeNutritionResultDto(req.recipeId(), caloriesPerServing,
      totalProtein.divide(servings, 2, RoundingMode.HALF_UP),
      totalCarbs.divide(servings, 2, RoundingMode.HALF_UP),
      totalFat.divide(servings, 2, RoundingMode.HALF_UP),
      totalFibre.divide(servings, 2, RoundingMode.HALF_UP),
      totalMicros.entrySet().stream().collect(Collectors.toMap(
          Map.Entry::getKey, e -> e.getValue().divide(servings, 2, RoundingMode.HALF_UP))),
      status, List.copyOf(unmapped));
}
```

### Listener skeleton

```java
@Component
class RecipeEventListener {
  private final NutritionCalculationService calc;
  private final RecipeNutritionWriter writer;
  private final RecipeQueryService recipeQuery;

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onRecipeUpdated(RecipeUpdatedEvent event) { recalc(event.recipeId(), event.newVersionId(), event.branchId(), event.newVersionNumber()); }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onRecipeAdapted(RecipeAdaptedEvent event) {
    log.debug("RecipeAdapted: recipe={} version={} adapterTrace={}", event.recipeId(), event.newVersionId(), event.adapterTraceId());
    recalcByVersionId(event.recipeId(), event.newVersionId());
  }

  private void recalc(UUID recipeId, UUID versionId, UUID branchId, int versionNumber) {
    // Resolve version → ingredients → request → calc → writer (catch + log throughout).
  }
}
```

## Edge-case checklist

- [ ] Every ingredient mapped + verified → `nutritionStatus = "calculated"`
- [ ] Some ingredients unmapped → `nutritionStatus = "partial"` + `unmapped` non-empty
- [ ] Zero ingredients resolved → `nutritionStatus = "pending"`, all macros = 0
- [ ] Any mapping `needsReview = true` → status `"partial"` (even if all mapped)
- [ ] `ingredientMappingKey` explicit → look up by key, skip `name` normalisation
- [ ] `ingredientMappingKey` null + `name` non-blank → normalise + look up by `searchTerm`
- [ ] `gramsEstimate` null → treats as 0g (contributes nothing) — does NOT throw
- [ ] Servings = 1 → totals == per-serving values
- [ ] Servings = 4 → per-serving = totals / 4 (rounded half-up)
- [ ] Micros key present in only some ingredients → final map contains the key with summed-then-divided value
- [ ] `RecipeUpdatedEvent` listener fires after commit; calc + SPI write run; recipe receives the result
- [ ] `RecipeAdaptedEvent` listener fires after commit; `adapterTraceId` logged
- [ ] Both events fire for one write → both listeners run; idempotent (same JSONB written twice)
- [ ] `RecipeQueryService.getVersion` returns empty (race) → listener logs WARN + returns (does NOT throw)
- [ ] `NoopRecipeNutritionWriter` wired when no recipe-01f impl present → listener completes, logs WARN, no exception
- [ ] Test-scoped `@TestConfiguration @Primary RecipeNutritionWriter` wins over the Noop (round-5 bug 3 fix verified)
- [ ] `POST .../recalculate` by authenticated caller → 200 with `RecipeNutritionResultDto`
- [ ] `POST .../recalculate` anonymous → 401
- [ ] `POST .../recalculate` with bad versionId → 404 `recipe-version-lookup-failed`
- [ ] `POST .../recalculate` with body missing `branchId` → 400 (Jakarta validation)
- [ ] `POST .../recalculate` with Noop wired → 200 + `Warning` header `100 - "recipe-01f impl not yet wired..."`
- [ ] `POST .../recalculate` path-variable values are UUIDs (no URL-encoding needed — round-4 gotcha doesn't apply)
- [ ] `@TransactionalEventListener(phase = AFTER_COMMIT)` annotation present on both listener methods
- [ ] `@ConditionalOnMissingBean(RecipeNutritionWriter.class)` on the `@Bean` method (NOT the class)
- [ ] `@Bean` method name (`defaultRecipeNutritionWriter`) differs from the enclosing `@Configuration` class (`NoopRecipeNutritionWriterConfiguration`) — round-5 bug 2 fix
- [ ] `NutritionExceptionHandler` retains `@Order(Ordered.HIGHEST_PRECEDENCE)` after appending 2 handler methods
- [ ] OpenAPI shapes match (swagger-request-validator in IT)
- [ ] `NutritionBoundaryTest` (from 01a) still passes — new `spi/` + `spi/internal/` sub-packages added; verify the boundary rule permits them. **If the rule whitelists known sub-packages**, **append `spi` and `spi.internal`** — this is the ONE shared file the agent may touch on the nutrition side.
- [ ] Determinism — same ingredient list + servings → byte-identical result (modulo result object identity)
- [ ] No N+1 — per-ingredient lookup uses `findBySearchTerm`; consider `findBySearchTermIn` (existing in 01d) for batch — **01f uses the batch form** to amortise round-trips
- [ ] No regression on 01a/01b/01c/01d/01e tests
- [ ] No `pom.xml` dependency adds
- [ ] No recipe / household / provisions / auth / preference module file touched

## Files this ticket touches

```
NEW   src/main/java/com/example/mealprep/nutrition/api/controller/NutritionRecipeRecalcController.java
NEW   src/main/java/com/example/mealprep/nutrition/api/dto/RecalculateRecipeNutritionRequest.java
NEW   src/main/java/com/example/mealprep/nutrition/domain/service/NutritionCalculationService.java
NEW   src/main/java/com/example/mealprep/nutrition/domain/service/internal/RecipeEventListener.java
NEW   src/main/java/com/example/mealprep/nutrition/spi/RecipeNutritionWriter.java
NEW   src/main/java/com/example/mealprep/nutrition/spi/internal/NoopRecipeNutritionWriterConfiguration.java
NEW   src/main/java/com/example/mealprep/nutrition/exception/RecipeVersionLookupFailedException.java
NEW   src/main/java/com/example/mealprep/nutrition/exception/RecipeNutritionWriteFailedException.java

MOD   src/main/java/com/example/mealprep/nutrition/domain/service/internal/NutritionServiceImpl.java    (implements NutritionCalculationService; constructor takes IngredientMappingRepository + IntakeKeyNormaliser already)
MOD   src/main/java/com/example/mealprep/nutrition/api/NutritionExceptionHandler.java                    (append 2 @ExceptionHandler methods; KEEP @Order(Ordered.HIGHEST_PRECEDENCE))
MOD   src/main/java/com/example/mealprep/nutrition/NutritionModule.java                                  (optional — re-export NutritionCalculationService)

MOD   src/main/resources/openapi/paths/nutrition.yaml      (append 1 new path-item; do NOT touch existing)
MOD   src/main/resources/openapi/schemas/nutrition.yaml    (append `RecalculateRecipeNutritionRequest`; append `RecipeNutritionResultDto` + `UnmappedIngredientDto` ONLY IF 01d didn't already)
MOD   src/main/resources/openapi/openapi.yaml              (1 line under paths in `# nutrition` block; 1-3 schema refs under components.schemas in `# nutrition` block)

NEW   src/test/java/com/example/mealprep/nutrition/NutritionCalculationServiceTest.java          (cache-hit / cache-miss / partial / pending / needs-review branches; arithmetic over a fixture per LLD line 1069)
NEW   src/test/java/com/example/mealprep/nutrition/RecipeEventListenerTest.java                  (mock RecipeQueryService + RecipeNutritionWriter; both events; idempotency; failure-mode logs)
NEW   src/test/java/com/example/mealprep/nutrition/NutritionRecipeRecalcFlowIT.java              (HTTP: happy path; anonymous → 401; missing version → 404; Noop wired → 200 + Warning header)
NEW   src/test/java/com/example/mealprep/nutrition/NoopRecipeNutritionWriterIT.java              (Spring context-load test: @ConditionalOnMissingBean fires when no other bean present; @TestConfiguration @Primary wins)
MOD   src/test/java/com/example/mealprep/nutrition/testdata/NutritionTestData.java               (append builders for RecipeNutritionResultDto, UnmappedIngredientDto, RecipeIngredientLineDto, IngredientMapping fixture)
```

**Files this ticket does NOT modify** (cross-cutting; sibling round-6 tickets running in parallel must not collide):

- `src/main/java/com/example/mealprep/config/GlobalExceptionHandler.java` — module exception goes in the existing `NutritionExceptionHandler`.
- `src/test/java/com/example/mealprep/archunit/ModuleBoundaryTest.java` — module boundary rule lives in the existing `NutritionBoundaryTest`.
- Other modules' `paths/*.yaml`, `schemas/*.yaml`, `<module>ExceptionHandler.java`, migrations, entities — none touched.
- Recipe module (any file) — **explicitly not modified**. Recipe-01f wires `RecipeNutritionWriter` impl on its side.
- 01a/01b/01c/01d/01e's existing tests — none modified; only `NutritionTestData.java` gets new builder methods.
- `NutritionBoundaryTest` — verify it permits new sub-packages `spi/` and `spi/internal/`; if it whitelists only `api/`, `domain/`, `event/`, `exception/`, `validation/`, `config/`, then **append the two new sub-packages to the rule** (this is the ONE shared file the agent may touch on the nutrition side).

## Dependencies

- **Hard dependency**: `nutrition-01a` (merged) — `NutritionException`, `NutritionExceptionHandler`, `NutritionBoundaryTest`, `NutritionServiceImpl`.
- **Hard dependency**: `nutrition-01b` (merged) — pattern reuse only.
- **Hard dependency**: `nutrition-01c` (merged) — pattern reuse only.
- **Hard dependency**: `nutrition-01d` (merged) — `IngredientMapping` aggregate, `IngredientMappingRepository`, `IngredientNutritionDocument`, `IntakeKeyNormaliser`, `RecipeIngredientLineDto`, `CalculateRecipeNutritionRequest`, `RecipeNutritionResultDto`, `UnmappedIngredientDto` (LLD lines 492-508). 01f reads the cache; does NOT trigger the live pipeline.
- **Hard dependency**: `nutrition-01e` (merged) — pattern reuse only.
- **Hard dependency**: `recipe-01a` (merged) — `RecipeQueryService.getVersion`, `RecipeVersionDto`, `RecipeIngredientDto`.
- **Hard dependency**: `recipe-01c` (merged) — `RecipeUpdatedEvent` (LLD recipe.md line 695).
- **Soft dependency on recipe-01f (running in parallel)** — **see "Cross-SPI coupling" below**. `RecipeAdaptedEvent` (recipe-01f's new event) drives one of the listener methods. If recipe-01f hasn't merged yet, the second listener compiles against the event record (which lives in `recipe.event`); 01f imports `com.example.mealprep.recipe.event.RecipeAdaptedEvent`. If recipe-01f's event record doesn't exist at 01f's implementation time, **the listener for `RecipeAdaptedEvent` is `@ConditionalOnClass(name = "com.example.mealprep.recipe.event.RecipeAdaptedEvent")`** OR the agent splits the listener class into two `@Component`s (one per event), each `@ConditionalOnClass(name = ...)`. **Worth user review** — alternative: 01f waits for recipe-01f. Rejected because parallel safety is the parent's stated goal. **The agent SHOULD verify whether `RecipeAdaptedEvent` already exists from a prior recipe ticket** (recipe-01e or earlier — none in the round 1-5 splits) and report.
- **Hard dependency**: `auth-01a` (merged) — `CurrentUserResolver`, `SessionAuthenticationFilter`.
- **Hard dependency**: `refactor-01-split-merge-zones` (merged) — per-module YAML / advice / boundary-test layout.

### Cross-SPI coupling (recipe-01f ↔ nutrition-01f)

**Parent's per-module guidance, Option B**. Nutrition-01f owns the outbound SPI `nutrition.spi.RecipeNutritionWriter`. Recipe-01f ships a `@Component` impl in the recipe module that delegates to recipe's internal `RecipeWriteApi.updateNutritionStatus`. **Both tickets compile + run independently**:

- nutrition-01f: ships `RecipeNutritionWriter` interface + `NoopRecipeNutritionWriterConfiguration`. Compiles standalone. With Noop wired, recalc runs but the recipe doesn't get updated (logs WARN + returns 200 + `Warning` header).
- recipe-01f: ships a `@Component RecipeNutritionWriterImpl` annotated `@ConditionalOnClass(name = "com.example.mealprep.nutrition.spi.RecipeNutritionWriter")` so recipe-01f compiles + runs even without nutrition-01f. When both merge, the real impl takes precedence (`@ConditionalOnMissingBean` on the Noop bean defers).

**Merge order**: either ticket can merge first. With nutrition-01f alone, calc works but writes are noop. With recipe-01f alone, the impl class is on the classpath but the SPI interface isn't, so the `@ConditionalOnClass` keeps it dormant. When both are merged, the impl wires and the noop steps aside.

- **Sibling tickets running in parallel** (Wave 2 round 6): `household-01f`, `provisions-01f`, `recipe-01f`. None should touch any nutrition file or any of the cross-cutting files listed above. Only collision point is the entry `openapi.yaml`; this ticket appends in the `# nutrition` block, sibling tickets append in their own module's block.

## Acceptance / DoD

- [ ] `./mvnw -Dspotless.check.skip=true verify` passes locally on the agent's worktree
- [ ] `./mvnw spotless:apply` then `./mvnw spotless:check` clean
- [ ] CI green on the PR (build + spotless + OpenAPI lint + ArchUnit gate)
- [ ] All edge-case items above ticked
- [ ] `NutritionExceptionHandler` retains `@Order(Ordered.HIGHEST_PRECEDENCE)` after appending 2 handler methods
- [ ] OpenAPI 3.0 nullable fields use **inline** `nullable: true` on `confidence`
- [ ] All YAML description strings containing `,` `:` `'` are single-quoted (round-4 lesson)
- [ ] `NoopRecipeNutritionWriter` is a `@Bean @ConditionalOnMissingBean` factory inside a `@Configuration` class (NOT `@Component`) — round-5 bug 1 avoidance
- [ ] `@Bean` method name differs from `@Configuration` class name — round-5 bug 2 avoidance
- [ ] Test-side `@TestConfiguration` `RecipeNutritionWriter` bean uses `@Primary` to win over Noop — round-5 bug 3 fix
- [ ] Recalc service method writes audit/state when needed AND throws 4xx? **No** — calc is read-only; no `@Transactional(noRollbackFor)` needed
- [ ] `@TransactionalEventListener(phase = AFTER_COMMIT)` on listener methods
- [ ] Idempotent recalc — same input twice → identical result
- [ ] No regression on 01a-01e tests
- [ ] No `pom.xml` dependency adds
- [ ] No recipe / household / provisions / auth / preference module file touched

## What's NOT in scope

- **Live USDA / OFF pipeline invocation during recalc** — calc is read-from-cache only. 01d's `IngredientMappingPipeline` handles cache population; 01f's calc reads.
- AI ingredient parsing — already deferred in 01d.
- `NutritionFloorGateService` → **nutrition-01g**
- Weekly aggregates + `DivergenceDetector` → **nutrition-01h**
- Recipe-side persistence — recipe-01f's concern (via the SPI impl).
- Cache-warming sweep for partial recipes — operations concern.
- Per-user calc parameters (e.g. user-specific gram-conversion overrides) — calc is per-recipe-pure.
- Async / batch recalc across many recipes — single-version-at-a-time only in v1.
- Admin-role gate on the `/recalculate` endpoint — open to authenticated callers; admin gate when role enum gains `ADMIN`.

Squash-merge with: `feat(nutrition): 01f — NutritionCalculationService + RecipeNutritionWriter SPI (outbound) + Noop fallback + RecipeUpdated/Adapted listeners + manual recalc endpoint`
