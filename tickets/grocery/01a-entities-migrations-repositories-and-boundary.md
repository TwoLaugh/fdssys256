# Ticket: grocery — 01a Entities + Migrations + Repositories + Module Boundary + Config

## Summary

Lay the **foundation** for the entire grocery module: package skeleton, the six
Flyway migrations + the repeatable pack-size seed, all JPA entities + enums + JSONB
inner records, the package-private repositories, the four public service interfaces
(declared only — impl bodies land in 01b..01g), the `GroceryModule` facade, the
`GroceryConfig` property bundle, the module exception root + handler, and the
`GroceryBoundaryTest` ArchUnit rules. Per [LLD §Package Layout lines 24-66](../../lld/grocery.md),
[LLD §Database lines 70-349](../../lld/grocery.md), [LLD §Entities lines 353-378](../../lld/grocery.md),
[LLD §Repositories lines 489-545](../../lld/grocery.md), [LLD §Service Interfaces lines 549-643](../../lld/grocery.md),
[LLD §Configuration lines 988-1001](../../lld/grocery.md). Ships:

- **Six migrations + one repeatable seed** (LLD lines 75-82) — `shopping_lists`(+lines),
  `grocery_orders`(+lines), `grocery_provider_state`, `grocery_substitution_proposals`,
  `grocery_price_history`, `grocery_pack_size_heuristics`, `R__grocery_seed_pack_size_heuristics.sql`.
- **All entities** (LLD lines 357-378): `ShoppingList` / `ShoppingListLine`, `GroceryOrder` /
  `GroceryOrderLine`, `GroceryProviderState`, `GrocerySubstitutionProposal`, `PriceObservation`,
  `PackSizeHeuristic` + the eight module-local enums + `AutomationFailureRecord` /
  `ProviderSessionState` JSONB inner records.
- **All repositories** (LLD lines 493-543), package-private, with the `findAllByIdIn` /
  `findByUserIdIn` batch siblings per the style guide.
- **Four public service interfaces** (LLD lines 556-641) — `ShoppingListService`,
  `ManualFulfilmentService`, `GroceryOrderService`, `PriceHistoryService` — **declarations
  only**; `GroceryServiceImpl` is created as an empty `@Service` skeleton implementing all
  four, with every method `throw new UnsupportedOperationException("grocery-01b..01g")`. Each
  later ticket fills in its tier's bodies.
- **All DTOs + request records** (LLD lines 386-479) — needed by the interface signatures.
  Records only; no logic.
- **`GroceryModule` facade** (LLD line 28) re-exporting the four interfaces.
- **`GroceryConfig`** `@ConfigurationProperties("mealprep.grocery")` (LLD lines 990-1001) — six
  nested records with the LLD's default values.
- **`GroceryException` root + `GroceryExceptionHandler`** (LLD lines 744-766) — the
  `@RestControllerAdvice` with every module exception mapped, `@Order(HIGHEST_PRECEDENCE)`.
- **`GroceryBoundaryTest`** — ArchUnit: repos package-private, impls in `internal`,
  `GroceryProvider` impls only in `internal.providers`, Spring-Web only in `api`/`config`.

**This ticket ships NO behaviour** — every service method throws. The migrations apply,
the schema validates against the JPA mappings (`ddl-auto=validate`), the context boots, and
the boundary test passes. 01b..01g fill the bodies tier by tier.

**Unblocks (E2E):** foundation for **GROC-01** (01b), **GROC-03 / GROC-30** (01c),
**GROC-15 / GROC-16 / GROC-17 / GROC-18** (01e), **GROC-19** (01f), and **XJ-05** (the
grocery-loop flagship). No `@pending` scenario flips green on 01a alone.

## LLD-divergence notes

### `plan_revision` vs the planner's `generation` (CRITICAL — confirm before build)

LLD lines 96 / 108 / 150 / 869 model the shopping list keyed on `(plan_id, plan_revision)`
with `plan_revision` described as "matches the planner's revision counter." **The planner has
NO `plan_revision`.** Per [`lld/planner.md` line 170](../../lld/planner.md) and the shipped
`planner_plans` table, the monotonic counter is **`generation`** (`integer NOT NULL`, "1 for
first plan; +1 per regeneration"), and `PlanDto` carries `int generation` (planner-01a). There
is no separate "revision" concept.

**01a's decision:** name the column **`plan_generation`** (not `plan_revision`) and the entity
field `planGeneration`, mapping 1:1 to `PlanDto.generation`. The `UNIQUE (plan_id, plan_revision)`
constraint becomes `UNIQUE (plan_id, plan_generation)`. All LLD references to `plan_revision`
read as `plan_generation`. The DTO field follows (`int planGeneration`).

**Worth user review** — this is a rename to reconcile the LLD with shipped planner code. The
alternative (keep `plan_revision` as a grocery-local alias of `generation`) adds a confusing
synonym. 01a picks the rename. The `RecalculateShoppingListRequest.planRevision` field
(LLD line 472) likewise becomes `planGeneration` (nullable → latest).

### `grocery_order_lines` migration folds into V20260601120100

LLD lines 75-82 list six migration files but the `grocery_order_lines` table (LLD lines 193-213)
has no dedicated file in that list — it's described in the same section as `grocery_orders`
(V20260601120100). **01a ships `grocery_order_lines` inside `V20260601120100`** (one migration,
the order aggregate = parent + child lines, one concern). Same for `shopping_list_lines` inside
`V20260601120000`. This matches the LLD's section grouping (both tables documented under one
heading) and the "one concern per migration" rule reads the aggregate as the concern.

### `EncryptedJsonConverter` for `session_state` is a stub in 01a

LLD lines 246 / 363 / 1083 specify app-level encryption-at-rest for `grocery_provider_state.session_state`
via `@Convert(converter = EncryptedJsonConverter.class)` but flag the mechanism (pgcrypto vs
app-side AES via a not-yet-existent `core.crypto`) as **deferred / worth user review**. **01a
ships `session_state` as plain JSONB** mapped to `ProviderSessionState` via `@Type(JsonType.class)`
with a `// TODO(grocery-crypto-followup): wrap in EncryptedJsonConverter once core.crypto lands`
marker. v1 has no provider session anyway (FakeGroceryProvider holds no real cookies). **Worth
user review** — shipping plaintext JSONB for a column the LLD wants encrypted is acceptable only
because no real credentials/cookies ever land there in v1; the converter is a hard requirement
before real Tesco automation (the deferred post-v1 ticket).

### Service interfaces declared but unimplemented

01a ships `GroceryServiceImpl` as a single `@Service` implementing all four interfaces with
every method body `throw new UnsupportedOperationException(...)`. This compiles, the bean
registers (so the controllers in later tickets can `@MockBean` or inject it), and the boundary
test sees the multi-interface impl. **Worth user review** — alternative is to not ship the impl
until 01b; rejected because the facade + DTOs + interface set is the contract every later ticket
and every sibling-module reader (planner-02a, the dormant provisions listener) compiles against.

## Database

```
src/main/resources/db/migration/V20260601120000__grocery_create_shopping_lists.sql           new
src/main/resources/db/migration/V20260601120100__grocery_create_grocery_orders.sql            new
src/main/resources/db/migration/V20260601120200__grocery_create_grocery_provider_state.sql    new
src/main/resources/db/migration/V20260601120300__grocery_create_grocery_substitution_proposals.sql  new
src/main/resources/db/migration/V20260601120400__grocery_create_grocery_price_history.sql      new
src/main/resources/db/migration/V20260601120500__grocery_create_pack_size_heuristics.sql       new
src/main/resources/db/migration/R__grocery_seed_pack_size_heuristics.sql                       new (repeatable)
```

Ship the SQL **verbatim from [LLD lines 90-345](../../lld/grocery.md)** with the one rename:
`plan_revision` → `plan_generation` (column + the `UNIQUE` constraint + the index predicate).
Reproduced (shopping_lists, the one with the rename):

```sql
CREATE TABLE shopping_lists (
    id                       uuid PRIMARY KEY,
    user_id                  uuid NOT NULL,
    household_id             uuid,
    plan_id                  uuid NOT NULL,                       -- soft FK to planner_plans
    plan_generation          integer NOT NULL,                   -- == planner_plans.generation (see divergence note)
    generated_at             timestamptz NOT NULL,
    superseded_at            timestamptz,
    estimated_total_pence    integer,
    estimated_total_currency varchar(3) NOT NULL DEFAULT 'GBP',
    cost_confidence          numeric(4,3),
    stale_ingredient_count   integer NOT NULL DEFAULT 0,
    pantry_tracking_enabled  boolean NOT NULL,
    notes                    varchar(255),
    version                  bigint NOT NULL DEFAULT 0,
    created_at               timestamptz NOT NULL,
    updated_at               timestamptz NOT NULL,
    UNIQUE (plan_id, plan_generation)
);
CREATE INDEX idx_shop_lists_user_plan_active
    ON shopping_lists (user_id, plan_id) WHERE superseded_at IS NULL;
CREATE INDEX idx_shop_lists_user_generated
    ON shopping_lists (user_id, generated_at DESC);
-- shopping_list_lines: verbatim LLD lines 117-147 (one migration with the parent aggregate)
```

The other five tables + the repeatable seed: **verbatim** from LLD lines 154-349. Honour the
column widths AS WRITTEN but **do not trust them blindly** — `ingredient_mapping_key varchar(128)`
matches the cross-module `text`/`varchar(128)` convention used elsewhere; `store varchar(64)`,
`provider_key varchar(32)`, `source varchar(24)`. Verify against the shipped provisions /
nutrition column widths for `ingredient_mapping_key` before committing (they must match for the
soft-FK cross-module joins to be apples-to-apples).

`R__grocery_seed_pack_size_heuristics.sql` ships a **small v1 starter set** (LLD line 349: flour
500g/1kg/1.5kg; eggs 6/12; milk 1pt/2pt/4pt; plus ~10 more common categories). The full data is
reference data filled in later — 01a ships enough rows that `PackSizeOptimiserTest` (01b) has
fixtures. Repeatable so additions don't pollute the version sequence.

**Migration self-test:** `FlywayMigrationIT` boots Postgres, runs all grocery migrations, and
asserts `ddl-auto=validate` passes against the JPA mappings.

## Entities + enums

Ship **all entities verbatim from [LLD lines 357-378](../../lld/grocery.md)**. Style-guide
shape: UUID `@Id` set application-side, `@Version Long version` on every mutable aggregate root
(`ShoppingList`, `GroceryOrder`, `GroceryProviderState`, `GrocerySubstitutionProposal`),
`@CreatedDate`/`@LastModifiedDate`, Lombok `@Getter @Setter @Builder @NoArgsConstructor(access =
PROTECTED) @AllArgsConstructor`. JSONB via `@Type(JsonType.class)` (hypersistence-utils).

`PriceObservation` and `PackSizeHeuristic` are **append-only / reference data — no `@Version`,
no `@LastModifiedDate`** (LLD lines 365-366). `ShoppingListLine` / `GroceryOrderLine` are children
(`@ManyToOne(fetch = LAZY)` to parent, no own `@Version` — parent covers the aggregate).

Eight module-local enums (LLD lines 369-376):

```java
public enum ShoppingListLineType { PLANNED_DEMAND, STAPLE_REPLENISHMENT }
public enum LineFulfilmentStatus { UNFILLED, PARTIAL, BOUGHT, SUBSTITUTED, DROPPED }
public enum BoughtVia { MANUAL, ORDER, BULK_TOTAL }
public enum GroceryOrderStatus {
  DRAFT, QUOTED, PLACED, PLACED_PARTIAL, AWAITING_USER_CONFIRMATION, CONFIRMED,
  DELIVERED, RECONCILED, CANCELLED, ARCHIVED, PROVIDER_UNAVAILABLE }
public enum OrderLineStatus { QUEUED, ADDED, ADDED_PARTIAL, UNAVAILABLE, SUBSTITUTED, DELIVERED, REJECTED }
public enum SubstitutionProposalStatus { PENDING_USER_REVIEW, ACCEPTED, REJECTED, UNPARSED }
public enum PriceSource { PAID, QUOTE, MANUAL, MANUAL_ESTIMATED, INFLATION_INDEXED }
// ProviderKey is an open string at the entity level (LLD line 376) — validated by the in-app registry, not an enum.
```

JSONB inner records (LLD line 378):

```java
public record AutomationFailureRecord(String step, String message, Instant occurredAt) {}
public record ProviderSessionState(
    Map<String, String> cookies, String navigationCursor, Map<String, Object> providerExtras) {}
```

**Worth user review** — `ProviderSessionState.providerExtras` is `Map<String, Object>`; the
style guide warns against `Object`-typed JSONB. It's acceptable here because each provider carries
its own opaque typed extras and v1 has no real provider; flag for the deferred Tesco ticket to pin
a typed shape.

## DTOs + request records

Ship **all DTOs verbatim from [LLD lines 386-467](../../lld/grocery.md)** and the request records
from [LLD lines 472-479](../../lld/grocery.md), **with the one rename**: `ShoppingListDto.planRevision`
→ `planGeneration` (int), `RecalculateShoppingListRequest.planRevision` → `planGeneration` (Integer,
nullable = latest). All records, no logic. The `@ValidQuantityUnit` / `@ValidObservedPrice`
validation annotations on request records reference the custom validators shipped in 01d (Tier 2's
ticket owns the validator impls); 01a ships the annotation **interfaces** (empty `ConstraintValidator`
wired in 01d) OR defers them to 01d and ships the request records with plain `@NotNull`/`@Positive`
until then. **Decision:** ship the annotation interfaces in 01a (so the records compile with the
final shape) but a permissive `ConstraintValidator` that 01d tightens. **Worth user review.**

## Repositories

Ship **verbatim from [LLD lines 493-543](../../lld/grocery.md)**, package-private:
`ShoppingListRepository`, `GroceryOrderRepository`, `PriceObservationRepository` (with the four
`@Query` aggregation reads), plus the standard-shape `ShoppingListLineRepository`,
`GroceryOrderLineRepository`, `GroceryProviderStateRepository`, `GrocerySubstitutionProposalRepository`
(incl. `countByGroceryOrderIdAndProposalStatus` — the reconcile gate), `PackSizeHeuristicRepository`.
`@EntityGraph(attributePaths = {"lines"})` on the hot-read parent loads (no N+1). Apply the rename:
`findByPlanIdAndPlanRevision` → `findByPlanIdAndPlanGeneration`,
`findFirst...OrderByPlanRevisionDesc` → `...OrderByPlanGenerationDesc`.

## Service interfaces (declared) + skeleton impl

Ship the four interfaces **verbatim from [LLD lines 556-641](../../lld/grocery.md)** in
`domain/service/`. `GroceryModule.java` re-exports them (LLD line 28 / 66). `GroceryServiceImpl`
in `domain/service/internal/` is a single `@Service` implementing all four; every method body:

```java
@Override
public ShoppingListDto recalculate(UUID userId, RecalculateShoppingListRequest request) {
  throw new UnsupportedOperationException("implemented in grocery-01b");
}
```

(tag each with the ticket that fills it). Read methods get `@Transactional(readOnly = true)`,
writes get `@Transactional`, even on the throwing skeleton — so 01b..01g only fill bodies, never
re-annotate.

## Config

`GroceryConfig` `@ConfigurationProperties("mealprep.grocery") @Validated` — six nested records
**verbatim from [LLD lines 992-999](../../lld/grocery.md)** with the LLD defaults. **v1 note:**
`AggregatorConfig.halfLifeDays` / `priorStrength` and `InflationConfig.monthlyFactor` are shipped
(the config surface is stable) but **unused by 01c's V1-SIMPLE aggregate** — 01c's aggregate is
weighted-mean only; the decay/Bayesian/inflation fields wire up in the deferred-v2 work. Ship them
so the config doesn't churn, but document "v1 reads only `confidenceWeights` + `staleThresholdDays`."

## Exceptions

`GroceryException extends MealPrepException` (root). Per-failure subclasses
**verbatim from [LLD lines 748-766](../../lld/grocery.md)** with their HTTP statuses + `type` URIs.
Single `GroceryExceptionHandler` `@RestControllerAdvice`, `@Order(Ordered.HIGHEST_PRECEDENCE)`,
one `@ExceptionHandler` per type. **Do NOT modify `config/GlobalExceptionHandler.java`.**
`ProviderPartialFailureException` is **NOT mapped to an error** (LLD line 755 / 764) — it's caught
service-side and returned as a 200 body; no handler entry.

## ArchUnit — `GroceryBoundaryTest`

```java
@AnalyzeClasses(packages = "com.example.mealprep.grocery")
class GroceryBoundaryTest {
  @ArchTest static final ArchRule reposArePackagePrivate = ...;            // domain.repository interfaces package-private
  @ArchTest static final ArchRule implsLiveInInternal = ...;              // *ServiceImpl + helpers in domain.service.internal
  @ArchTest static final ArchRule providerImplsLiveInProvidersPocket =    // GroceryProvider impls only in
      classes().that().implement(GroceryProvider.class)
          .should().resideInAPackage("com.example.mealprep.grocery.domain.service.internal.providers..");
  @ArchTest static final ArchRule springWebStaysInApi = ...;             // controllers/RestClient only in api/config
}
```

The `providerImplsLiveInProvidersPocket` rule is **vacuously true in 01a** (no impls yet);
`FakeGroceryProvider` (01e, test-scoped) and the deferred `TescoGroceryProvider` must land in the
pocket. Mirrors discovery-01c's "impls in the source pocket" pattern.

## Verbatim shape snippets

### `GroceryModule` facade

```java
@Component
@RequiredArgsConstructor
public class GroceryModule {
  private final ShoppingListService shoppingListService;
  private final ManualFulfilmentService manualFulfilmentService;
  private final GroceryOrderService groceryOrderService;
  private final PriceHistoryService priceHistoryService;
  public ShoppingListService shoppingList() { return shoppingListService; }
  public ManualFulfilmentService manualFulfilment() { return manualFulfilmentService; }
  public GroceryOrderService groceryOrder() { return groceryOrderService; }
  public PriceHistoryService priceHistory() { return priceHistoryService; }
}
```

(or follow whatever re-export shape the other modules' `*Module.java` facades use — verify
`HouseholdModule` / `ProvisionsModule` and match.)

### `GroceryConfig`

```java
@ConfigurationProperties("mealprep.grocery")
@Validated
public record GroceryConfig(
    @NotNull AggregatorConfig aggregator,
    @NotNull ConfidenceWeightsConfig confidenceWeights,
    @NotNull InflationConfig inflation,
    @NotNull FreshnessConfig freshness,
    @NotNull SchedulerConfig scheduler,
    @NotNull OrderConfig order) {
  public record AggregatorConfig(@Min(1) int halfLifeDays, @DecimalMin("0.0") double priorStrength,
      @Min(1) int staleThresholdDays) {}
  public record ConfidenceWeightsConfig(@DecimalMin("0.0") @DecimalMax("1.0") double paid,
      double quote, double manual, double manualEstimated, double inflationIndexed) {}
  public record InflationConfig(@DecimalMin("0.0") double monthlyFactor) {}
  public record FreshnessConfig(@Min(0) int rampUpWeeks, @Min(1) int defaultRefreshTopN) {}
  public record SchedulerConfig(@NotBlank String refreshCron, @NotBlank String orderStatusCron,
      @NotBlank String archiveCron) {}
  public record OrderConfig(@Min(1) int singleFlightLockTtlSeconds, @Min(1) int providerUnavailableRetryHours) {}
}
```

Defaults in `application.yml` under `mealprep.grocery.*` per LLD lines 994-999.

## Edge-case checklist

- [ ] All six migrations + the repeatable seed apply cleanly on a fresh Postgres
- [ ] `ddl-auto=validate` passes — every entity field maps to a column; no drift
- [ ] `plan_generation` (NOT `plan_revision`) in the table, the `UNIQUE` constraint, the index predicate, the entity, and `ShoppingListDto`
- [ ] `UNIQUE (plan_id, plan_generation)` present (the idempotency guard 01b relies on)
- [ ] `grocery_price_history` is append-only — `PriceObservation` has no `@Version`, no `@LastModifiedDate`, no setter that an UPDATE could use
- [ ] `grocery_pack_size_heuristics` has both partial indexes + the two CHECK constraints (`chk_packsize_or_count`, `chk_match_target`)
- [ ] `automation_failure_log` defaults `'[]'::jsonb`; `session_state` nullable JSONB
- [ ] `ingredient_mapping_key` column width matches provisions / nutrition exactly (cross-module soft-FK parity)
- [ ] All eight enums present with the LLD's exact members + order
- [ ] `GroceryServiceImpl` implements all four interfaces; every method throws `UnsupportedOperationException` with its owning-ticket tag
- [ ] Read methods `@Transactional(readOnly = true)`, writes `@Transactional` — even on the skeleton
- [ ] `GroceryConfig` binds with the LLD defaults; context boots
- [ ] `GroceryExceptionHandler` is `@Order(Ordered.HIGHEST_PRECEDENCE)`; `ProviderPartialFailureException` NOT mapped (200-in-body)
- [ ] `GroceryBoundaryTest`: repos package-private; impls in `internal`; provider-impl pocket rule (vacuous today); Spring-Web in `api`/`config`
- [ ] `R__` seed has enough rows for `PackSizeOptimiserTest` fixtures (flour, eggs, milk + ~10 categories)
- [ ] Context-load IT: all four interfaces resolvable; `GroceryModule` bean present

## Files this ticket touches

```
NEW   7 × src/main/resources/db/migration/V20260601120*.sql + R__grocery_seed_pack_size_heuristics.sql
NEW   src/main/java/com/example/mealprep/grocery/GroceryModule.java
NEW   src/main/java/com/example/mealprep/grocery/config/GroceryConfig.java
NEW   src/main/java/com/example/mealprep/grocery/config/GroceryJsonConfig.java         (JsonType registration if not project-global)
NEW   src/main/java/com/example/mealprep/grocery/domain/entity/*.java                  (8 entities + 8 enums + 2 JSONB records)
NEW   src/main/java/com/example/mealprep/grocery/domain/repository/*.java              (9 repositories, package-private)
NEW   src/main/java/com/example/mealprep/grocery/domain/service/ShoppingListService.java
NEW   src/main/java/com/example/mealprep/grocery/domain/service/ManualFulfilmentService.java
NEW   src/main/java/com/example/mealprep/grocery/domain/service/GroceryOrderService.java
NEW   src/main/java/com/example/mealprep/grocery/domain/service/PriceHistoryService.java
NEW   src/main/java/com/example/mealprep/grocery/domain/service/internal/GroceryServiceImpl.java   (skeleton; all bodies throw)
NEW   src/main/java/com/example/mealprep/grocery/api/dto/*.java                        (all DTOs + request records)
NEW   src/main/java/com/example/mealprep/grocery/validation/*.java                     (annotation interfaces + permissive validators; 01d tightens)
NEW   src/main/java/com/example/mealprep/grocery/exception/GroceryException.java + per-failure subclasses
NEW   src/main/java/com/example/mealprep/grocery/api/GroceryExceptionHandler.java
NEW   src/test/java/com/example/mealprep/grocery/GroceryBoundaryTest.java
NEW   src/test/java/com/example/mealprep/grocery/FlywayMigrationIT.java
NEW   src/test/java/com/example/mealprep/grocery/GroceryContextLoadIT.java
NEW   src/test/java/com/example/mealprep/grocery/testdata/GroceryTestData.java         (entity + DTO builders; later tickets append)
MOD   src/main/resources/application.yml                                               (mealprep.grocery.* defaults)
```

**Does NOT touch:** `config/GlobalExceptionHandler.java`; `archunit/ModuleBoundaryTest.java`
(module rule lives in `GroceryBoundaryTest`); any other module's files; `pom.xml`
(hypersistence-utils / MapStruct / Resilience4j already present — verify; report if any missing).

## Dependencies

- **Hard dependency:** `core` (merged) — `MealPrepException`, `LockService`, `MealPrepEvent`,
  migration-timestamp scheme.
- **Soft prerequisite:** `tickets/core/03` (mapping-key normalisation) — 01a's write boundaries
  carry a `// TODO(core-03)` and adopt `IngredientMappingKeys.normalise()` once it merges; 01a
  does not block on it (no writes happen in 01a — every service method throws).
- **Reads (no code dependency, soft-FK only):** `planner_plans` (`PlanDto.generation`),
  `provision_inventory`, `nutrition_ingredient_mapping`. 01a writes no cross-module code.

## Acceptance / DoD

- [ ] `./mvnw -Dspotless.check.skip=true verify` passes; `spotless:apply` then `spotless:check` clean
- [ ] CI green (build + spotless + OpenAPI lint + ArchUnit gate)
- [ ] All edge-case items above ticked
- [ ] `FlywayMigrationIT` green; `ddl-auto=validate` passes
- [ ] `GroceryBoundaryTest` passes
- [ ] No `config/GlobalExceptionHandler.java` edit; no other-module file touched
- [ ] No `pom.xml` dependency add (report if any missing)

Squash-merge with: `feat(grocery): 01a — entities, migrations, repositories, service-interface skeleton, config + module boundary`

## What's NOT in scope

- Any tier behaviour — every service method throws `UnsupportedOperationException` (01b..01g fill them).
- Controllers (each tier ticket ships its own controller + OpenAPI).
- MapStruct mappers (each tier ships its mapper, or 01a ships empty mapper interfaces — **decision:
  ship the mapper interfaces in 01a so DTOs round-trip, leave custom-mapping methods to the tier
  tickets**; flag in report).
- `EncryptedJsonConverter` impl — deferred (stub marker only).
- `FakeGroceryProvider` — ships in 01e (test-scoped).
- `tickets/core/03` normalisation util — separate ticket; 01a only TODO-marks the boundaries.
