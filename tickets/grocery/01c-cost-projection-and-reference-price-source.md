# Ticket: grocery — 01c Price History Tier 4 (V1-SIMPLE) + ReferencePriceSource + Open Prices Seed

## Summary

Implement **Tier 4 price history at a deliberately MINIMAL v1 scope** plus the
`ReferencePriceSource` cold-start data path. Per
[LLD §`PriceHistoryService` lines 622-643](../../lld/grocery.md),
[LLD §Flow 5 (aggregation) lines 926-939](../../lld/grocery.md),
[LLD §`grocery_price_history` lines 279-321](../../lld/grocery.md),
[LLD §Price-history REST lines 729-738](../../lld/grocery.md),
[LLD §Configuration lines 990-1001](../../lld/grocery.md). Ships:

- **`ReferencePriceSource` SPI** + a `ReferencePriceSnapshotSource` impl seeded from a **bundled
  Open Food Facts "Open Prices" SNAPSHOT** (deterministic, no live API) + a **product→ingredient-category
  aggregation/mapping layer**.
- **`PriceAggregator` (V1-SIMPLE)** — confidence-weighted mean + sample count + a simple
  confidence/stale signal. **NO time-decay, NO Bayesian prior, NO inflation indexing in v1** (all
  deferred to the noted "v2" section below).
- **`PriceObservationWriter`** — one source-weighted row per observation, unit-normalised.
- **`PriceHistoryService` impl** — `getAggregate`, `getAggregatesByKeys`,
  `getCrossStoreAggregatesByKey`, `getObservations`, `getObservationsByMappingKey`,
  `recordManualPrice`, `refreshOnDemand`. (`runScheduledBackgroundRefresh` is a stub here; 01g owns it.)
- **`PriceHistoryController`** + the six endpoints (LLD lines 731-736).
- **`PriceObservedEvent`** published per observation after commit.

**Unblocks (E2E):** **GROC-03** ("cost projection with a confidence and stale-data summary" —
`@pending` `grocery.feature` lines 69-76, needs the cost-projection estimate+confidence) and
**GROC-30** ("learned price for an ingredient" — `@pending` lines 95-103, needs the per-ingredient
aggregate estimate+confidence+range). 01c is also the **cost-projection data source for 01b's
step 6** and the **price source the `FakeGroceryProvider` derives quotes from** (01e).

---

## THE V1 SCOPE LINE (read this first)

Product owner: *"for a v1 just having SOME price optimisation at all is good."* So Tier 4 ships
**V1-SIMPLE**:

| In v1 (this ticket) | Deferred to the "v2 — full aggregation" section (NOT v1-blocking) |
|---|---|
| `ReferencePriceSource` SPI + bundled Open Prices snapshot seed | full time-decay `decay = 0.5^(ageDays/halfLife)` (LLD line 932) |
| product→category aggregation/mapping layer (real work) | Bayesian-shaped confidence with `priorStrength` (LLD line 934) |
| **basic** per-mapping-key aggregate: `sum(price × weight) / sum(weight)` (weight = source weight only) | `InflationIndexer.synthesise` (LLD line 937) + ONS food CPI as the aging input |
| sample count, min/max range, `lastSeenAt`, `isStale` (`lastSeenAt < now − staleThresholdDays`) | half-life / monthly-factor / prior tuning |
| source confidence weights (`paid=1.0`, `quote=0.85`, `manual=0.7`, `manual_estimated=0.4`) | `inflation_indexed` source rows |
| a confidence signal good enough for GROC-03 + GROC-30 | the inflation-fallback path when zero/stale observations |

The **config surface is already shipped in 01a** (`GroceryConfig` carries `halfLifeDays`,
`priorStrength`, `monthlyFactor`) so v2 wiring doesn't churn config — **v1 simply doesn't read those
fields**; it reads only `confidenceWeights` + `staleThresholdDays`.

**v1 confidence (simple form):** `confidence = min(1.0, sum(sourceWeight) / (sum(sourceWeight) +
priorConst))` with a fixed `priorConst` (e.g. 2.0) — enough that "1 observation → wide/low
confidence, many → tight/high" holds for GROC-03/GROC-30 without the full Bayesian decay machinery.
**Worth user review** — this is a simplified confidence; the v2 section is where the proper
decay-weighted Bayesian form lands.

**v1 cold-start / staleness:** when no household observations exist for a key, fall back to the
`ReferencePriceSource` (a low-but-nonzero confidence reference estimate) so GROC-03/GROC-30 show a
number with an honest "low confidence" rather than "unknown." When observations exist but all are
older than `staleThresholdDays` → `isStale = true` and confidence is dampened. **No
`inflation_indexed` synthesis in v1** — that's the v2 replacement for the reference fallback.

---

## Decision baked in — ReferencePriceSource = Open Food Facts "Open Prices" snapshot

The owner approved **Open Food Facts "Open Prices"** as the seed dataset (real, open, has an API +
downloadable dumps). Key facts baked in:

1. **Per-barcoded-product granularity → a mapping layer is REAL WORK.** Open Prices rows are
   `(barcode/product, price, currency, location, date)` — they are NOT keyed by our
   `ingredient_mapping_key`. 01c ships a **`product→ingredient-category aggregation/mapping`
   layer** (`ReferenceProductMapper`) that rolls per-product reference prices up to a per-category /
   per-mapping-key reference estimate (e.g. average normalised unit price across products mapped to
   `"chicken breast"`). **Worth user review** — the product→category mapping is the load-bearing
   judgement call: the mapping table itself (which barcodes/categories roll up to which mapping key)
   is seed data that needs curation; v1 ships a small starter mapping for the common ingredients the
   E2E fixtures exercise and flags the full mapping as ongoing reference-data work.
2. **Bundle a SNAPSHOT, not the live API.** The seed is a **repeatable Flyway migration**
   (`R__grocery_seed_reference_prices.sql`) or a bundled resource the source loads at startup —
   deterministic, so tests/e2e never hit a live API. **Decision:** ship as a repeatable seed
   migration into a `grocery_reference_prices` table (new, owned by this ticket) so the data is
   queryable + cacheable like the pack-size heuristics. **Worth user review** — alternative is a
   classpath JSON resource loaded into memory; rejected for parity with the pack-size-heuristic seed
   pattern and so the reference data is inspectable in the DB.
3. **ODbL LICENCE CHECK (one-time, before bundling) — WORTH USER REVIEW (HIGH PRIORITY).** Open
   Prices / Open Food Facts data is **ODbL** (Open Database Licence) — attribution + share-alike
   obligations. Bundling a snapshot into the repo is a redistribution. **A one-time licence check is
   required before the snapshot is committed:** confirm attribution is carried (a NOTICE/attribution
   string in the seed file header + app "about" surface) and that share-alike doesn't conflict with
   the project's licence. **This is a blocking pre-build check the owner must sign off** — flag it
   prominently. 01c specifies the seed table + mapper; the actual snapshot bytes are NOT committed
   until the licence sign-off.
4. **ONS:** the classic ONS per-item average food price series is **discontinued** — do NOT use it.
   The **ONS food CPI index** is relevant ONLY as the (deferred-v2) inflation-aging input to
   `InflationIndexer` — noted in the v2 section, not built in v1.

---

## v2 — full aggregation (DEFERRED, NOT v1-blocking)

This section documents what a v2 follow-up ticket adds, so the v1 cut is legible:

- **Time-decay:** per-row `decay = 0.5^(ageDays/halfLifeDays)`; effective weight = `sourceWeight ×
  decay` (LLD lines 932-933). Reads `GroceryConfig.aggregator.halfLifeDays` (default 90).
- **Bayesian confidence:** `confidence = sum(effectiveWeight) / (sum(effectiveWeight) +
  priorStrength)` (LLD line 934). Reads `priorStrength` (default 2.0).
- **`InflationIndexer.synthesise`:** when zero/stale observations, find the most recent paid/quote
  row, apply `factorMonthly` compounded over elapsed months, write one `inflation_indexed` row
  (weight 0.15), re-aggregate (LLD line 937). The **ONS food CPI index** becomes the `factorMonthly`
  input instead of the flat 0.5%/month default.
- **`PriceFreshnessGuardrails`** cost-cap-aware refresh decisions (LLD lines 941-953) — partially
  needed by 01g's scheduled refresh; the on-demand path's simple version ships here.
- **12-month compaction** of `manual_estimated` / `inflation_indexed` rows into monthly aggregates
  (LLD line 321).

The v1 aggregator's interface (`PriceAggregator.aggregate(householdId, key, store) →
Optional<PriceAggregateDto>`) is **identical** in v1 and v2 — v2 only changes the internal maths, so
01b / 01e callers don't change.

---

## Behavioural spec

### `ReferencePriceSource` SPI

```java
public interface ReferencePriceSource {
  /** A cold-start reference estimate for a mapping key (per normalised unit), or empty if unmapped. */
  Optional<ReferencePrice> referencePrice(String ingredientMappingKey);
  Map<String, ReferencePrice> referencePrices(Collection<String> keys);   // batch
}
public record ReferencePrice(String ingredientMappingKey, int unitPence, String unit,
    BigDecimal referenceConfidence, Instant sourceAsOf, String attribution) {}
```

`ReferenceSnapshotSource` (`@Component`) reads `grocery_reference_prices` (seeded from the Open
Prices snapshot via `ReferenceProductMapper`). `referenceConfidence` is a low fixed value (e.g.
0.2) — a reference estimate is never as good as a real observation. `attribution` carries the ODbL
attribution string. **Mirrors discovery's SPI-with-impl-in-pocket pattern** — the SPI is public
(re-exported); the snapshot impl is package-private in `internal`.

### `PriceAggregator.aggregate` (V1-SIMPLE)

`Optional<PriceAggregateDto> aggregate(UUID householdId, String key, String store)` (store=null →
cross-store). Pure, deterministic:

1. Fetch observations via `findRecentByKey(householdId, key, since)` where `since = now() −
   staleThresholdDays` (v1: a flat window, NOT `6 × halfLife`). `store != null` → filter by store.
2. **If observations exist:** point estimate = `sum(unitPence × sourceWeight) / sum(sourceWeight)`;
   `sampleCount`, `min`/`max` + their `observedAt`, `lastSeenAt = max(observedAt)`; `isStale =
   lastSeenAt < now − staleThresholdDays`; `confidence = min(1.0, sum(sourceWeight) /
   (sum(sourceWeight) + priorConst))`, dampened if stale.
3. **If NO observations:** fall back to `referencePriceSource.referencePrice(key)` → a
   `PriceAggregateDto` with the reference estimate, `referenceConfidence`, `sampleCount = 0`,
   `isStale = true`. **Empty only if the key is also unmapped in the reference source** (true cold
   start, novel ingredient) — then `Optional.empty()` (the planner/list sees "unknown" — GROC-03
   cold-start variation).

`getAggregatesByKeys` batches step 1 via `findRecentByKeys` + one `referencePrices` batch call (the
ONE-query target 01b's step 6 relies on).

### `PriceObservationWriter` (LLD line 51, 319)

Writes one `PriceObservation` per observation. `confidence_weight` set at write time from
`GroceryConfig.confidenceWeights` by `source` (never updated — append-only). `paid_unit_pence`
**normalised** to the ingredient's canonical unit (per 100g / per litre / per item) using the
nutrition `nutrition_ingredient_mapping` preferred unit (LLD line 319 — verify the read path; if the
preferred-unit lookup isn't cross-module accessible, v1 normalises to a documented default unit and
flags it). Rejects writes for unknown mapping keys (after normalisation). Each write publishes
`PriceObservedEvent` after commit (LLD line 831, 883).

### `recordManualPrice` / `refreshOnDemand`

- `recordManualPrice(userId, RecordManualPriceRequest)` → `MANUAL` row (weight 0.7), store defaults
  to the request's store. `PriceObservedEvent` published. (Tier 2's mark-bought path in 01d also
  writes observations but through `PriceObservationWriter` directly — this REST entry is the manual
  one-off.)
- `refreshOnDemand(userId, RefreshPricesRequest)`:
  - `useProviderQuote = false` → return latest aggregates, no provider call (LLD line 945).
  - `useProviderQuote = true` + provider configured → assemble a 1-pack-per-key draft, call
    `provider.quote` (the `FakeGroceryProvider` in tests), write one `QUOTE` row per line. On
    `AiUnavailableException` → return `RefreshPricesResultDto(observationsWritten=0, …,
    aiUnavailableFallbackUsed=true, fallbackMessage="AI features paused — enter prices manually via
    mark-bought.")` (LLD line 943), HTTP 503 with the AI-unavailable ProblemDetail type. **The full
    cost-cap-aware `PriceFreshnessGuardrails` is 01g's concern** — 01c's on-demand path lets
    `AiUnavailableException` surface naturally (the user invoked it).

### `PriceHistoryController` (LLD lines 731-736)

Six endpoints under `/api/v1/grocery/price-history`: aggregates (by key+store), cross-store
aggregates, observations (paged), observations-by-key (paged), manual-record (201), refresh (200/503).
`@Tag(name = "Grocery — Price History")`.

## Database

```
src/main/resources/db/migration/V20260601120600__grocery_create_reference_prices.sql   new (this ticket — NOT in 01a's list)
src/main/resources/db/migration/R__grocery_seed_reference_prices.sql                    new (repeatable; snapshot bytes pending ODbL sign-off)
```

```sql
CREATE TABLE grocery_reference_prices (
    id                       uuid PRIMARY KEY,
    ingredient_mapping_key   varchar(128) NOT NULL,    -- normalised; the rolled-up category/key
    reference_unit_pence     integer NOT NULL,         -- per normalised unit
    unit                     varchar(16) NOT NULL,
    reference_confidence     numeric(4,3) NOT NULL,    -- low fixed value (e.g. 0.200)
    source_as_of             date NOT NULL,            -- snapshot date
    attribution              varchar(255) NOT NULL,    -- ODbL attribution string
    sample_products          integer,                  -- how many Open Prices products rolled up
    created_at               timestamptz NOT NULL
);
CREATE UNIQUE INDEX idx_grocery_ref_prices_key ON grocery_reference_prices (ingredient_mapping_key);
```

**Worth user review** — `V20260601120600` extends 01a's `..120000..120500` sequence; confirm no
other module claimed `120600`. The `R__grocery_seed_reference_prices.sql` ships with the schema +
the attribution header + a placeholder `-- TODO(odbl-signoff): snapshot rows pending licence
sign-off`; the actual rows are added only after the owner's ODbL sign-off.

## Edge-case checklist

- [ ] Single observation → wide/low confidence; many → tight/high (GROC-30 single-vs-many variation)
- [ ] All observations stale (> `staleThresholdDays`) → `isStale = true`, confidence dampened, recency reflects oldest data
- [ ] No observations, key mapped in reference source → reference estimate returned, `sampleCount = 0`, low `referenceConfidence`
- [ ] No observations, key NOT in reference source (novel ingredient) → `Optional.empty()` ("unknown")
- [ ] `getAggregatesByKeys` is ONE observation query + ONE reference batch (the ≤5-SQL target for 01b step 6)
- [ ] Per-store vs cross-store aggregate (store=null) both correct
- [ ] `PriceObservation` append-only — a "correction" writes a NEW row, never UPDATEs (GROC-29 / GROC-32 override behaviour)
- [ ] `confidence_weight` set per source at write time; `paid=1.0 > quote=0.85 > manual=0.7 > manual_estimated=0.4`
- [ ] `paid_unit_pence` normalised to canonical unit; unknown mapping key rejected
- [ ] `PriceObservedEvent` published once per observation after commit
- [ ] `recordManualPrice` → 201 + `MANUAL` row; invalid price (£/p mix-up) → 400 via `@ValidObservedPrice`
- [ ] `refreshOnDemand(useProviderQuote=false)` → aggregates, no provider call
- [ ] `refreshOnDemand(useProviderQuote=true)` + `AiUnavailableException` → 503 + `aiUnavailableFallbackUsed=true`, observationsWritten=0
- [ ] V1 aggregator does NOT read `halfLifeDays` / `priorStrength` / `monthlyFactor` (no decay/Bayesian/inflation) — verify by absence
- [ ] `ReferenceProductMapper` rolls per-product Open Prices rows to per-mapping-key reference estimates deterministically
- [ ] ODbL attribution string present on every reference row + a NOTICE in the seed header
- [ ] `grocery_reference_prices` migration applies; `ddl-auto=validate` passes
- [ ] Controller: 200/201/400/503; OpenAPI shapes match

## Files this ticket touches

```
NEW   src/main/resources/db/migration/V20260601120600__grocery_create_reference_prices.sql
NEW   src/main/resources/db/migration/R__grocery_seed_reference_prices.sql           (snapshot rows pending ODbL sign-off)
NEW   src/main/java/com/example/mealprep/grocery/domain/service/ReferencePriceSource.java   (public SPI)
NEW   src/main/java/com/example/mealprep/grocery/domain/service/internal/ReferenceSnapshotSource.java
NEW   src/main/java/com/example/mealprep/grocery/domain/service/internal/ReferenceProductMapper.java
NEW   src/main/java/com/example/mealprep/grocery/domain/entity/ReferencePriceRow.java + repository
NEW   src/main/java/com/example/mealprep/grocery/domain/service/internal/PriceAggregator.java       (V1-SIMPLE)
NEW   src/main/java/com/example/mealprep/grocery/domain/service/internal/PriceObservationWriter.java
NEW   src/main/java/com/example/mealprep/grocery/api/controller/PriceHistoryController.java
NEW   src/main/java/com/example/mealprep/grocery/api/mapper/PriceObservationMapper.java
NEW   src/main/java/com/example/mealprep/grocery/event/PriceObservedEvent.java
MOD   src/main/java/com/example/mealprep/grocery/domain/service/internal/GroceryServiceImpl.java     (fill PriceHistoryService bodies except runScheduledBackgroundRefresh → 01g stub)
MOD   src/main/resources/openapi/paths/grocery.yaml + schemas/grocery.yaml + openapi.yaml
NEW   src/test/java/com/example/mealprep/grocery/PriceAggregatorTest.java                            (V1-SIMPLE: confidence rises with count; reference fallback; stale)
NEW   src/test/java/com/example/mealprep/grocery/PriceObservationWriterTest.java
NEW   src/test/java/com/example/mealprep/grocery/ReferenceProductMapperTest.java
NEW   src/test/java/com/example/mealprep/grocery/PriceHistoryControllerIT.java
NEW   src/test/java/com/example/mealprep/grocery/PriceAggregationIT.java
MOD   src/test/java/com/example/mealprep/grocery/testdata/GroceryTestData.java
```

**Does NOT touch:** `InflationIndexer`, decay maths, the scheduled refresh (01g), other-module
production code. The `nutrition_ingredient_mapping` preferred-unit read is via nutrition's public
query surface only.

## Dependencies

- **Hard:** grocery-01a (entities, `grocery_price_history`, config, repos); `tickets/core/03`
  (normalise keys before write/lookup).
- **Soft:** `GroceryProvider` (01e) for the `useProviderQuote=true` path — 01c can ship with the
  provider call behind the `ObjectProvider<GroceryProvider>` optional-lookup, returning the
  `useProviderQuote=false` behaviour until 01e lands.
- **Read:** nutrition `nutrition_ingredient_mapping` preferred-unit (for unit normalisation).
- **Owner sign-off (BLOCKING):** ODbL licence check before the Open Prices snapshot bytes are committed.

## Acceptance / DoD

- [ ] `verify` + `spotless` clean; CI green; all edge cases ticked
- [ ] V1-SIMPLE confirmed: NO decay, NO Bayesian prior, NO inflation indexing (the v2 section is documentation only)
- [ ] GROC-03 + GROC-30 un-pendable (estimate + confidence + range surface)
- [ ] Reference fallback gives an honest low-confidence number, never a fake high-confidence one
- [ ] ODbL attribution carried; snapshot rows committed only after owner sign-off
- [ ] `PriceAggregationIT` + `PriceAggregatorTest` green; `GroceryBoundaryTest` passes

Squash-merge with: `feat(grocery): 01c — Tier 4 price history (v1-simple) + ReferencePriceSource (Open Prices snapshot) + cost-projection data`

## What's NOT in scope (deferred to v2 / other tickets)

- Time-decay, Bayesian confidence, `InflationIndexer`, ONS-CPI aging input → **v2 follow-up** (documented above).
- `runScheduledBackgroundRefresh` + full `PriceFreshnessGuardrails` cost-cap matrix → **grocery-01g**.
- 12-month observation compaction → v2 / a future migration.
- The full Open-Prices product→category mapping table (beyond the E2E-fixture starter set) → ongoing reference-data curation.
- Re-pointing the planner's `CostSubScore` onto Tier 4 → **`tickets/planner/02a`** (explicitly NOT grocery v1).
