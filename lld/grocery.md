# Grocery Module — LLD

*Implementation specification for the four-tier Grocery Model: Shopping List (deterministic), Manual Fulfilment (mark-bought), Grocery Order via provider (Tesco v1, browser-automated), and Price History (confidence-weighted learning loop). Translates [grocery.md](../design/grocery.md) into a buildable Spring Boot module.*

## Scope

This document specifies the `grocery` module — package layout, JPA entities, Flyway migrations, repositories, service interfaces, DTOs, mappers, REST controllers, validation, events, business-logic flows, transaction boundaries, and the test plan. Conventions defer to [lld/style-guide.md](style-guide.md); this LLD restates a rule only when the module-specific application matters.

The HLD's four tiers map to four cooperating concerns within one module:

| Tier | Storage | Mutability | Driver |
|---|---|---|---|
| 1 — Shopping List | Relational (`shopping_lists` + `shopping_list_lines`) — derived state | Regenerated, never edited | `ShoppingListService.recalculate` |
| 2 — Manual Fulfilment | Writes to `grocery_price_history`; calls `ProvisionUpdateService.applyGroceryOrder` | Append-only price rows; idempotent inventory updates | `ManualFulfilmentService.markBought` |
| 3 — Grocery Order | Relational (`grocery_orders`, `grocery_substitution_proposals`, `grocery_provider_state`) — explicit state machine | Top-down lifecycle | `GroceryOrderService` + `GroceryProvider` |
| 4 — Price History | Append-only (`grocery_price_history`); confidence-weighted aggregation | Sources: `paid` / `quote` / `manual` / `manual_estimated` / `inflation_indexed` | `PriceHistoryService` |

The module is read-only against most data models — Plan (via planner), Recipes (ingredient lists at calculation time), Provisions (inventory + supplier products + budget), Preference (lifestyle config — pantry-tracking flag and grocery quality preferences). It owns its tables and emits events; provisions inventory updates flow through `ProvisionUpdateService.applyGroceryOrder`, never through direct DB writes.

This module is **mostly mechanical** — Tier 1's calculation is deterministic; Tier 2 is a write pipeline; Tier 4 is aggregation maths. AI is used only for Tier 3's browser-navigation step (out of scope for this LLD per the HLD's deferral note); the AI cost-cap behaviour is the only AI-touching concern surfaced here.

---

## Package Layout

```
com.example.mealprep.grocery/
├── GroceryModule.java                        facade re-exporting public service interfaces
├── api/
│   ├── controller/                           ShoppingListController, ManualFulfilmentController,
│   │                                          GroceryOrderController, PriceHistoryController
│   ├── dto/                                  request/response records
│   └── mapper/                               one MapStruct mapper per entity-DTO pair
├── domain/
│   ├── entity/                               JPA entities (see Entities)
│   ├── repository/                           Spring Data interfaces — package-private
│   └── service/
│       ├── ShoppingListService.java          public interface (Tier 1)
│       ├── ManualFulfilmentService.java      public interface (Tier 2)
│       ├── GroceryOrderService.java          public interface (Tier 3)
│       ├── PriceHistoryService.java          public interface (Tier 4)
│       ├── GroceryServiceImpl.java           single impl of all four
│       └── internal/
│           ├── ShoppingListCalculator         the six-step deterministic pipeline
│           ├── PackSizeHeuristic              ingredient → pack-size-set lookup (data deferred)
│           ├── PackSizeOptimiser              pick smallest pack combination meeting demand
│           ├── BasketDraftAssembler           builds BasketDraft from a shopping list for the provider
│           ├── OrderStateMachine              validates lifecycle transitions
│           ├── SubstitutionPersister          maps provider proposals to grocery_substitution_proposals
│           ├── PriceObservationWriter         writes one row per observation, source-weighted
│           ├── PriceAggregator                point estimate / confidence / range / recency
│           ├── InflationIndexer               synthesises rows when no recent data exists
│           ├── PriceFreshnessGuardrails       on-demand vs scheduled refresh, cost-cap checks
│           └── providers/
│               ├── GroceryProvider             public interface
│               ├── BasketDraft / QuoteResult / PlaceOrderResult / OrderStatus / SubstitutionProposal records
│               ├── TescoGroceryProvider        v1 impl — separate file
│               ├── ProviderUnavailableException
│               └── ProviderPartialFailureException
├── event/                                    sealed GroceryOrderLifecycleEvent + siblings; ShoppingList* events; SubstitutionProposed/Accepted/Rejected; PriceObservedEvent; GroceryProviderUnavailableEvent
├── exception/                                module-root + per-failure subclasses
├── validation/                               @ValidQuantityUnit, @ValidObservedPrice, @ValidOrderStatusTransition
└── config/                                   GroceryConfig — confidence formula + freshness defaults; GroceryJsonConfig — JsonType registration
```

`GroceryModule.java` re-exports the four public service interfaces — callers (planner, feedback, notification) inject one line. `internal/providers/` holds the `GroceryProvider` interface and the Tesco implementation; the interface is module-public (re-exported via the facade) but TescoGroceryProvider stays package-private. Per the pilot precedent, `internal/` plumbing is package-private.

---

## Database

Migrations live under `src/main/resources/db/migration/` with the project-wide timestamp scheme from [technical-architecture.md §Migrations](../design/technical-architecture.md#migrations):

```
V20260601120000__grocery_create_shopping_lists.sql
V20260601120100__grocery_create_grocery_orders.sql
V20260601120200__grocery_create_grocery_provider_state.sql
V20260601120300__grocery_create_grocery_substitution_proposals.sql
V20260601120400__grocery_create_grocery_price_history.sql
V20260601120500__grocery_create_pack_size_heuristics.sql
R__grocery_seed_pack_size_heuristics.sql
```

One concern per migration, append-only.

### V20260601120000 — Shopping lists

A shopping list is **derived state**: a snapshot rendered from a plan + provisions at a moment in time, kept for history (the user wants to retroactively mark "what was last week's list?"). The list is regenerated when the underlying plan or provisions change; rendered snapshots are not edited as the source of truth.

```sql
CREATE TABLE shopping_lists (
    id                       uuid PRIMARY KEY,
    user_id                  uuid NOT NULL,
    household_id             uuid,                                -- nullable: single-user mode; populated when household scope known
    plan_id                  uuid NOT NULL,                       -- soft FK to planner_plans
    plan_revision            integer NOT NULL,                    -- monotonic per plan; matches the planner's revision id
    generated_at             timestamptz NOT NULL,
    superseded_at            timestamptz,                         -- non-null when a newer revision is generated
    estimated_total_pence    integer,                             -- nullable: no price data yet
    estimated_total_currency varchar(3) NOT NULL DEFAULT 'GBP',
    cost_confidence          numeric(4,3),                        -- 0..1; null when no price history
    stale_ingredient_count   integer NOT NULL DEFAULT 0,
    pantry_tracking_enabled  boolean NOT NULL,                    -- snapshot of the lifestyle flag at calc time
    notes                    varchar(255),
    version                  bigint NOT NULL DEFAULT 0,
    created_at               timestamptz NOT NULL,
    updated_at               timestamptz NOT NULL,
    UNIQUE (plan_id, plan_revision)
);
-- "show me the current list for this plan"
CREATE INDEX idx_shop_lists_user_plan_active
    ON shopping_lists (user_id, plan_id) WHERE superseded_at IS NULL;
-- history view: list of past shopping lists by user
CREATE INDEX idx_shop_lists_user_generated
    ON shopping_lists (user_id, generated_at DESC);

CREATE TABLE shopping_list_lines (
    id                       uuid PRIMARY KEY,
    shopping_list_id         uuid NOT NULL REFERENCES shopping_lists(id) ON DELETE CASCADE,
    ingredient_mapping_key   varchar(128) NOT NULL,               -- lowercased per technical-architecture §Cross-module references
    display_name             varchar(128) NOT NULL,
    requested_quantity       numeric(10,3) NOT NULL,
    requested_unit           varchar(16) NOT NULL,
    suggested_pack_size_g    integer,                             -- output of the pack-size optimiser
    suggested_pack_count     integer,
    suggested_pack_unit      varchar(16),
    line_type                varchar(16) NOT NULL,                -- planned_demand | staple_replenishment
    quality_notes            varchar(255),                        -- e.g. "organic where available"
    estimated_unit_pence     integer,                             -- from price-history aggregate; null if no data
    estimated_line_pence     integer,
    estimated_confidence     numeric(4,3),
    is_stale_estimate        boolean NOT NULL DEFAULT false,      -- price > 3 months old
    fulfilment_status        varchar(16) NOT NULL DEFAULT 'unfilled', -- unfilled | partial | bought | substituted | dropped
    bought_quantity          numeric(10,3),
    bought_unit              varchar(16),
    bought_price_pence       integer,
    bought_at                timestamptz,
    bought_via               varchar(16),                         -- manual | order | bulk_total
    grocery_order_id         uuid,                                -- soft FK; populated when fulfilled via order
    created_at               timestamptz NOT NULL,
    updated_at               timestamptz NOT NULL
);
-- the dominant read: "render the current list with all lines"
CREATE INDEX idx_shop_lines_list ON shopping_list_lines (shopping_list_id);
-- bulk mark-bought / per-mapping-key fulfilment lookups
CREATE INDEX idx_shop_lines_list_mapping_key
    ON shopping_list_lines (shopping_list_id, ingredient_mapping_key);
```

`fulfilment_status` is a per-line fulfilment marker, not a soft-delete; the line stays for history. `bought_*` columns are the ground truth of what actually happened, populated by Tier 2 mark-bought or Tier 3 order reconciliation. `plan_revision` matches the planner's revision counter — when the planner re-optimises remaining days, the new revision generates a new list and supersedes the old one (`superseded_at` set).

### V20260601120100 — Grocery orders

```sql
CREATE TABLE grocery_orders (
    id                          uuid PRIMARY KEY,
    user_id                     uuid NOT NULL,
    household_id                uuid,
    shopping_list_id            uuid NOT NULL REFERENCES shopping_lists(id),
    provider_key                varchar(32) NOT NULL,             -- "tesco", "sainsburys", ...
    provider_order_id           varchar(128),                     -- nullable until placed
    status                      varchar(32) NOT NULL,             -- draft | quoted | placed | placed_partial | awaiting_user_confirmation | confirmed | delivered | reconciled | cancelled | archived | provider_unavailable
    status_reason               varchar(255),                     -- free-text on terminal/error states
    quoted_total_pence          integer,
    confirmed_total_pence       integer,
    paid_total_pence            integer,
    currency                    varchar(3) NOT NULL DEFAULT 'GBP',
    delivery_slot_start         timestamptz,
    delivery_slot_end           timestamptz,
    confirm_link                text,                             -- the user-confirmation URL the provider exposed
    placed_at                   timestamptz,
    confirmed_at                timestamptz,
    delivered_at                timestamptz,
    reconciled_at               timestamptz,
    cancelled_at                timestamptz,
    cancel_reason               varchar(64),
    last_status_check_at        timestamptz,
    automation_failure_log      jsonb NOT NULL DEFAULT '[]'::jsonb, -- array of {step, message, occurredAt}
    trace_id                    uuid NOT NULL,
    version                     bigint NOT NULL DEFAULT 0,
    created_at                  timestamptz NOT NULL,
    updated_at                  timestamptz NOT NULL
);
-- look up the order from a provider callback / status webhook (when they exist)
CREATE INDEX idx_grocery_orders_provider_order
    ON grocery_orders (provider_key, provider_order_id) WHERE provider_order_id IS NOT NULL;
-- list view "my orders" excluding archived
CREATE INDEX idx_grocery_orders_user_status_created
    ON grocery_orders (user_id, status, created_at DESC);
-- single-flight per user-week (advisory lock keyed off the active shopping list)
CREATE INDEX idx_grocery_orders_shopping_list ON grocery_orders (shopping_list_id);

CREATE TABLE grocery_order_lines (
    id                          uuid PRIMARY KEY,
    grocery_order_id            uuid NOT NULL REFERENCES grocery_orders(id) ON DELETE CASCADE,
    shopping_list_line_id       uuid REFERENCES shopping_list_lines(id) ON DELETE SET NULL,
    provider_product_id         varchar(128),                     -- supplier SKU
    ingredient_mapping_key      varchar(128) NOT NULL,
    display_name                varchar(255) NOT NULL,
    quantity_requested          numeric(10,3) NOT NULL,
    quantity_unit               varchar(16) NOT NULL,
    pack_size_g                 integer,
    pack_count_requested        integer,
    pack_count_delivered        integer,
    quoted_unit_pence           integer,
    confirmed_unit_pence        integer,
    paid_unit_pence             integer,
    line_status                 varchar(16) NOT NULL,             -- queued | added | added_partial | unavailable | substituted | delivered | rejected
    note                        varchar(255),
    created_at                  timestamptz NOT NULL,
    updated_at                  timestamptz NOT NULL
);
CREATE INDEX idx_grocery_order_lines_order ON grocery_order_lines (grocery_order_id);
```

`automation_failure_log` is JSONB because failures are append-only diagnostic records, read whole, never filtered on inner fields — a textbook fit for the style guide's JSONB rule. `placed_partial` is a distinct status (not just `placed` with a flag) so the planner and notification listeners can react differently — a partially placed order is **the** signal that the user must complete the basket manually.

### V20260601120200 — Grocery provider state

Per-user, per-provider session state. Cookies and the navigation cursor live here so a long-running session survives backend restarts without forcing a re-login. **No card / payment data ever enters this table** — the user authenticates against the provider once; we hold session cookies, not credentials.

```sql
CREATE TABLE grocery_provider_state (
    id                          uuid PRIMARY KEY,
    user_id                     uuid NOT NULL,
    provider_key                varchar(32) NOT NULL,
    enabled                     boolean NOT NULL DEFAULT true,
    session_state               jsonb,                            -- cookies + provider-side navigation cursor; encrypted at rest at app level
    session_expires_at          timestamptz,
    last_login_at               timestamptz,
    last_failure_at             timestamptz,
    last_failure_reason         varchar(255),
    consecutive_failures        integer NOT NULL DEFAULT 0,
    scheduled_refresh_enabled   boolean NOT NULL DEFAULT false,
    refresh_top_n_ingredients   integer NOT NULL DEFAULT 50,
    version                     bigint NOT NULL DEFAULT 0,
    created_at                  timestamptz NOT NULL,
    updated_at                  timestamptz NOT NULL,
    UNIQUE (user_id, provider_key)
);
-- single read path: "load my state for this provider"
CREATE INDEX idx_grocery_provider_state_user_provider
    ON grocery_provider_state (user_id, provider_key);
```

`session_state` is JSONB because cookies and cursors are read whole and have no inner fields the system filters on. Encryption at rest is **worth user review** — implementation phase decides whether to use Postgres `pgcrypto` or app-side AES via `core.crypto` (which doesn't exist yet in the LLD set). For v1, the LLD specifies the field and flags the encryption mechanism as deferred.

### V20260601120300 — Grocery substitution proposals

```sql
CREATE TABLE grocery_substitution_proposals (
    id                          uuid PRIMARY KEY,
    grocery_order_id            uuid NOT NULL REFERENCES grocery_orders(id) ON DELETE CASCADE,
    grocery_order_line_id       uuid REFERENCES grocery_order_lines(id) ON DELETE SET NULL,
    original_product_id         varchar(128) NOT NULL,
    original_display_name       varchar(255) NOT NULL,
    original_ingredient_mapping_key varchar(128),
    substitute_product_id       varchar(128) NOT NULL,
    substitute_display_name     varchar(255) NOT NULL,
    substitute_ingredient_mapping_key varchar(128),
    substitute_quantity         numeric(10,3),
    substitute_unit             varchar(16),
    substitute_unit_pence       integer,
    reason                      varchar(255),                     -- provider-supplied: "out of stock", "size up", ...
    proposal_status             varchar(32) NOT NULL,             -- pending_user_review | accepted | rejected | unparsed
    raw_payload                 jsonb,                            -- the provider's raw substitution record, for unparsed cases
    resolved_at                 timestamptz,
    resolved_by_user_id         uuid,
    created_at                  timestamptz NOT NULL,
    updated_at                  timestamptz NOT NULL
);
-- "what's outstanding for this order" — gates reconciliation
CREATE INDEX idx_grocery_subs_order_status
    ON grocery_substitution_proposals (grocery_order_id, proposal_status);
```

`unparsed` covers the HLD's "DOM differs from expected" case — we capture the raw payload and surface it for manual user resolution. `raw_payload` is JSONB because it's an opaque diagnostic blob.

### V20260601120400 — Grocery price history

Append-only, household-scoped (per HLD: "Same shopper, same prices — the household shares price history"). `household_id` nullable for single-user mode where `user_id` doubles as the household scope until household is configured.

```sql
CREATE TABLE grocery_price_history (
    id                          uuid PRIMARY KEY,
    user_id                     uuid NOT NULL,                    -- the observer; for audit
    household_id                uuid,                             -- aggregation scope
    ingredient_mapping_key      varchar(128) NOT NULL,
    store                       varchar(64) NOT NULL,             -- "tesco_online", "sainsburys_online", "tesco_metro_high_street", "manual"
    provider_product_id         varchar(128),                     -- supplier SKU when known
    pack_size_g                 integer,
    pack_count                  integer,
    quantity                    numeric(10,3),
    quantity_unit               varchar(16),
    paid_unit_pence             integer,                          -- normalised to a unit (per 100g, per litre, per item) — see notes
    paid_total_pence            integer,
    currency                    varchar(3) NOT NULL DEFAULT 'GBP',
    source                      varchar(24) NOT NULL,             -- paid | quote | manual | manual_estimated | inflation_indexed
    confidence_weight           numeric(4,3) NOT NULL,            -- 0..1, source-weighted at write time per GroceryConfig
    grocery_order_id            uuid,
    shopping_list_line_id       uuid,
    observed_at                 timestamptz NOT NULL,
    note                        varchar(255),
    created_at                  timestamptz NOT NULL
);
-- aggregation hot path: per-household, per-mapping-key, recency-ordered
CREATE INDEX idx_grocery_price_hh_key_observed
    ON grocery_price_history (household_id, ingredient_mapping_key, observed_at DESC);
-- per-store breakdown for cross-store comparison
CREATE INDEX idx_grocery_price_hh_key_store_observed
    ON grocery_price_history (household_id, ingredient_mapping_key, store, observed_at DESC);
-- "recent activity for this user" (audit / debug)
CREATE INDEX idx_grocery_price_user_observed
    ON grocery_price_history (user_id, observed_at DESC);
-- "old rows compactable to aggregates" — append-only retention sweep
CREATE INDEX idx_grocery_price_observed ON grocery_price_history (observed_at);
```

`paid_unit_pence` is normalised to "per pack-size-g" or "per litre" or "per item" depending on the ingredient's nutrition-cache canonical unit; the normalisation rule lives in `PriceObservationWriter` and uses the existing `nutrition_ingredient_mapping` table's preferred unit. The HLD lists rounding/precision as deferred — the LLD freezes the storage as integer pence with the normalisation rule and defers the per-ingredient unit choice to implementation.

Retention: indefinite for `paid` and `quote` rows; `manual_estimated` and `inflation_indexed` rows older than 12 months may be compacted into per-(household, key, month) aggregate snapshots in a future migration. **Worth user review** — v1 keeps everything; the back-of-envelope says ~10-20MB per household per 5 years which is negligible.

### V20260601120500 — Pack-size heuristics

A reference-data table mapping ingredient categories to typical pack sizes. The schema is fixed here; the data is reference data filled in later via the repeatable seed migration. Provider-specific pack sizes are an enrichment when Tier 3 is connected (sourced from `provision_supplier_products`); this table is the provider-agnostic v1 fallback.

```sql
CREATE TABLE grocery_pack_size_heuristics (
    id                          uuid PRIMARY KEY,
    ingredient_mapping_key      varchar(128),                     -- nullable: matches by category if null
    category                    varchar(64),                      -- nullable: matches by mapping key if specified
    pack_size_g                 integer,                          -- one of pack_size_g or pack_count is required
    pack_count                  integer,
    pack_unit                   varchar(16) NOT NULL,             -- "g" | "ml" | "items"
    rank                        integer NOT NULL,                 -- 1 = smallest typical pack, 2 = next, ...
    notes                       varchar(255),
    CONSTRAINT chk_packsize_or_count CHECK (pack_size_g IS NOT NULL OR pack_count IS NOT NULL),
    CONSTRAINT chk_match_target      CHECK (ingredient_mapping_key IS NOT NULL OR category IS NOT NULL)
);
-- lookup: "what packs exist for chicken thighs?" or "for the dairy category?"
CREATE INDEX idx_grocery_pack_heur_key
    ON grocery_pack_size_heuristics (ingredient_mapping_key, rank);
CREATE INDEX idx_grocery_pack_heur_category
    ON grocery_pack_size_heuristics (category, rank);
```

### R__grocery_seed_pack_size_heuristics.sql

Repeatable seed migration. The actual ingredient → pack-size data is **reference data filled in later** — the LLD specifies the schema, not the contents. The seed file's body is one INSERT per row for the v1 starter set (flour: 500g/1kg/1.5kg; eggs: 6/12; milk: 1pt/2pt/4pt; ...). Repeatable so additions don't pollute the version sequence.

---

## Entities

All entities follow the style guide: UUID `@Id` set application-side, `@Version` on every mutable aggregate root, `@CreatedDate` / `@LastModifiedDate` audit columns, Lombok `@Getter @Setter @Builder @NoArgsConstructor(access = PROTECTED) @AllArgsConstructor`. JSONB via `@Type(JsonType.class)` from `hypersistence-utils`.

| Entity | Notes |
|---|---|
| `ShoppingList` | Aggregate root. Owns `@OneToMany List<ShoppingListLine>` (cascade ALL, orphanRemoval). `@Version Long version`. `supersededAt` non-null when newer revision exists. |
| `ShoppingListLine` | Child. `@ManyToOne(fetch = LAZY)` to parent. No `@Version` — parent's version covers the aggregate. `boughtVia`, `groceryOrderId`, `boughtPricePence` populated by Tier 2 / Tier 3. |
| `GroceryOrder` | Aggregate root. Owns `@OneToMany List<GroceryOrderLine>`. `automationFailureLog` mapped to `List<AutomationFailureRecord>` via JSONB. `status` is a `GroceryOrderStatus` enum. `@Version Long version`. |
| `GroceryOrderLine` | Child. `@ManyToOne(fetch = LAZY)` to parent. `lineStatus` enum. |
| `GroceryProviderState` | Aggregate root. `sessionState` mapped to `ProviderSessionState` record via JSONB. `@Version Long version`. App-level encryption hook via `@Convert(converter = EncryptedJsonConverter.class)` — converter implementation deferred. |
| `GrocerySubstitutionProposal` | Aggregate root. `proposalStatus` enum. `rawPayload` mapped to `JsonNode`. `@Version Long version` — stale-resolve race protection. |
| `PriceObservation` | Append-only. No `@Version`, no `@LastModifiedDate`. `source` enum. `confidenceWeight` set at write time and never updated. |
| `PackSizeHeuristic` | Reference data. No `@Version` — refreshed via the repeatable migration. |

Module-local enums:
- `ShoppingListLineType` — `PLANNED_DEMAND`, `STAPLE_REPLENISHMENT`
- `LineFulfilmentStatus` — `UNFILLED`, `PARTIAL`, `BOUGHT`, `SUBSTITUTED`, `DROPPED`
- `BoughtVia` — `MANUAL`, `ORDER`, `BULK_TOTAL`
- `GroceryOrderStatus` — `DRAFT`, `QUOTED`, `PLACED`, `PLACED_PARTIAL`, `AWAITING_USER_CONFIRMATION`, `CONFIRMED`, `DELIVERED`, `RECONCILED`, `CANCELLED`, `ARCHIVED`, `PROVIDER_UNAVAILABLE`
- `OrderLineStatus` — `QUEUED`, `ADDED`, `ADDED_PARTIAL`, `UNAVAILABLE`, `SUBSTITUTED`, `DELIVERED`, `REJECTED`
- `SubstitutionProposalStatus` — `PENDING_USER_REVIEW`, `ACCEPTED`, `REJECTED`, `UNPARSED`
- `PriceSource` — `PAID`, `QUOTE`, `MANUAL`, `MANUAL_ESTIMATED`, `INFLATION_INDEXED`
- `ProviderKey` — open string at the entity level (so plug-in providers don't need migrations); the in-app registry validates against known keys.

`AutomationFailureRecord(String step, String message, Instant occurredAt)` — JSONB inner record. `ProviderSessionState(Map<String, String> cookies, String navigationCursor, Map<String, Object> providerExtras)` — JSONB inner record; `providerExtras` lets each provider carry its own typed extras without polluting the shared shape.

---

## DTOs

All DTOs are Java records. One response DTO per entity, mirroring entity fields with idiomatic types. Distinctive shapes:

```java
public record ShoppingListDto(
    UUID id, UUID userId, UUID householdId, UUID planId, int planRevision,
    Instant generatedAt, Instant supersededAt,
    Integer estimatedTotalPence, String estimatedTotalCurrency,
    BigDecimal costConfidence, int staleIngredientCount,
    boolean pantryTrackingEnabled, String notes,
    List<ShoppingListLineDto> lines, long version
) {}

public record ShoppingListLineDto(
    UUID id, String ingredientMappingKey, String displayName,
    BigDecimal requestedQuantity, String requestedUnit,
    Integer suggestedPackSizeG, Integer suggestedPackCount, String suggestedPackUnit,
    ShoppingListLineType lineType, String qualityNotes,
    Integer estimatedUnitPence, Integer estimatedLinePence,
    BigDecimal estimatedConfidence, boolean isStaleEstimate,
    LineFulfilmentStatus fulfilmentStatus,
    BigDecimal boughtQuantity, String boughtUnit, Integer boughtPricePence,
    Instant boughtAt, BoughtVia boughtVia, UUID groceryOrderId
) {}

public enum ExportFormat { PRINTABLE_HTML, PLAIN_TEXT, MARKDOWN, CSV }
public record ShoppingListExportDto(UUID shoppingListId, ExportFormat format, String content) {}

public record GroceryOrderDto(
    UUID id, UUID userId, UUID householdId, UUID shoppingListId,
    String providerKey, String providerOrderId,
    GroceryOrderStatus status, String statusReason,
    Integer quotedTotalPence, Integer confirmedTotalPence, Integer paidTotalPence, String currency,
    Instant deliverySlotStart, Instant deliverySlotEnd, String confirmLink,
    Instant placedAt, Instant confirmedAt, Instant deliveredAt, Instant reconciledAt, Instant cancelledAt,
    String cancelReason, Instant lastStatusCheckAt,
    List<GroceryOrderLineDto> lines,
    List<GrocerySubstitutionProposalDto> outstandingProposals,
    long version
) {}

public record GroceryOrderLineDto(
    UUID id, UUID shoppingListLineId, String providerProductId, String ingredientMappingKey,
    String displayName, BigDecimal quantityRequested, String quantityUnit,
    Integer packSizeG, Integer packCountRequested, Integer packCountDelivered,
    Integer quotedUnitPence, Integer confirmedUnitPence, Integer paidUnitPence,
    OrderLineStatus lineStatus, String note
) {}

public record GrocerySubstitutionProposalDto(
    UUID id, UUID groceryOrderId, UUID groceryOrderLineId,
    String originalProductId, String originalDisplayName, String originalIngredientMappingKey,
    String substituteProductId, String substituteDisplayName, String substituteIngredientMappingKey,
    BigDecimal substituteQuantity, String substituteUnit, Integer substituteUnitPence,
    String reason, SubstitutionProposalStatus proposalStatus,
    Instant resolvedAt, UUID resolvedByUserId
) {}

public record GroceryProviderStateDto(
    UUID id, UUID userId, String providerKey, boolean enabled,
    Instant sessionExpiresAt, Instant lastLoginAt, Instant lastFailureAt, String lastFailureReason,
    int consecutiveFailures, boolean scheduledRefreshEnabled, int refreshTopNIngredients
) {}

public record PriceObservationDto(
    UUID id, UUID userId, UUID householdId, String ingredientMappingKey, String store,
    String providerProductId, Integer packSizeG, Integer packCount,
    BigDecimal quantity, String quantityUnit,
    Integer paidUnitPence, Integer paidTotalPence, String currency,
    PriceSource source, BigDecimal confidenceWeight,
    UUID groceryOrderId, UUID shoppingListLineId, Instant observedAt, String note
) {}

public record PriceAggregateDto(
    String ingredientMappingKey, String store,                       // store=null → cross-store aggregate
    Integer pointEstimatePence, BigDecimal confidence,
    Integer minPence, Integer maxPence, Instant minObservedAt, Instant maxObservedAt,
    Instant lastSeenAt, int sampleCount, boolean isStale
) {}

public record MarkBoughtResultDto(UUID shoppingListLineId, LineFulfilmentStatus newStatus,
    Integer priceObservationId, UUID inventoryItemId) {}

public record RefreshPricesResultDto(int observationsWritten, int ingredientsRefreshed,
    boolean aiUnavailableFallbackUsed, String fallbackMessage) {}
```

Request records carry standard Jakarta validation:

- `RecalculateShoppingListRequest(@NotNull UUID planId, Integer planRevision)` — `null` revision means latest.
- `MarkBoughtRequest(@NotNull UUID shoppingListLineId, @NotNull @ValidQuantityUnit BigDecimal boughtQuantity, @NotNull String boughtUnit, @ValidObservedPrice Integer boughtPricePence, String store, Instant boughtAt)` — price/store/timestamp optional but encouraged.
- `BulkMarkBoughtRequest(@NotNull UUID shoppingListId, @NotEmpty List<UUID> shoppingListLineIds, @ValidObservedPrice Integer totalSpendPence, String store, Instant boughtAt)` — when `totalSpendPence` present, distributes proportionally.
- `CreateOrderRequest(@NotNull UUID shoppingListId, @NotBlank String providerKey)`; `QuoteRequest(@NotNull UUID groceryOrderId)`; `PlaceOrderRequest`; `CancelOrderRequest(@NotNull UUID groceryOrderId, @Size(max = 64) String reason)`.
- `ResolveSubstitutionRequest(@NotNull UUID proposalId, @NotNull SubstitutionProposalStatus decision)` — `ACCEPTED | REJECTED` only.
- `ProviderConnectionRequest(@NotBlank String providerKey, boolean enabled, boolean scheduledRefreshEnabled, @Min(0) @Max(200) Integer refreshTopNIngredients)`.
- `RefreshPricesRequest(@NotNull UUID userId, @Size(max = 200) List<@NotBlank String> ingredientMappingKeys, boolean useProviderQuote)`.
- `RecordManualPriceRequest(@NotBlank String ingredientMappingKey, @NotBlank String store, @ValidObservedPrice Integer paidTotalPence, BigDecimal quantity, String quantityUnit, Instant observedAt)`.

---

## Mappers

MapStruct, `@Mapper(componentModel = "spring")`, one per entity-DTO pair: `ShoppingListMapper` (with `uses = { ShoppingListLineMapper.class }`), `GroceryOrderMapper` (with `uses = { GroceryOrderLineMapper.class, GrocerySubstitutionProposalMapper.class }`), `GroceryProviderStateMapper`, `PriceObservationMapper`. Custom mapping on `ShoppingListMapper` resolves `outstandingProposals` from a service-supplied list (proposals are queried separately, not loaded with the order aggregate).

---

## Repositories

Package-private. Cross-module access goes through service interfaces only. Every batch-relevant method has a `findAllByIdIn` / `findByUserIdIn` sibling per the style guide.

```java
interface ShoppingListRepository extends JpaRepository<ShoppingList, UUID> {
    Optional<ShoppingList> findByPlanIdAndPlanRevision(UUID planId, int revision);
    Optional<ShoppingList> findFirstByPlanIdAndSupersededAtIsNullOrderByPlanRevisionDesc(UUID planId);
    Page<ShoppingList> findAllByUserIdOrderByGeneratedAtDesc(UUID userId, Pageable p);
    @EntityGraph(attributePaths = {"lines"}) Optional<ShoppingList> findWithLinesById(UUID id);
}

interface GroceryOrderRepository extends JpaRepository<GroceryOrder, UUID> {
    @EntityGraph(attributePaths = {"lines"}) Optional<GroceryOrder> findWithLinesById(UUID id);
    Page<GroceryOrder> findAllByUserIdAndStatusNotInOrderByCreatedAtDesc(
        UUID userId, Collection<GroceryOrderStatus> excludedStatuses, Pageable p);
    Optional<GroceryOrder> findByProviderKeyAndProviderOrderId(String providerKey, String providerOrderId);

    @Query("""
        select o from GroceryOrder o where o.shoppingListId = :listId
          and o.status not in ('CANCELLED', 'RECONCILED', 'ARCHIVED')""")
    List<GroceryOrder> findActiveByShoppingListId(@Param("listId") UUID listId);
}

interface PriceObservationRepository extends JpaRepository<PriceObservation, UUID> {
    @Query("""
        select p from PriceObservation p
        where p.householdId = :householdId and p.ingredientMappingKey = :key
          and p.observedAt >= :since
        order by p.observedAt desc""")
    List<PriceObservation> findRecentByKey(@Param("householdId") UUID householdId,
        @Param("key") String key, @Param("since") Instant since);

    @Query("""
        select p from PriceObservation p
        where p.householdId = :householdId and p.ingredientMappingKey in :keys
          and p.observedAt >= :since""")
    List<PriceObservation> findRecentByKeys(@Param("householdId") UUID householdId,
        @Param("keys") Collection<String> keys, @Param("since") Instant since);

    @Query("""
        select max(p.observedAt) from PriceObservation p
        where p.householdId = :householdId and p.ingredientMappingKey = :key""")
    Optional<Instant> findLatestObservedAt(@Param("householdId") UUID householdId, @Param("key") String key);

    Page<PriceObservation> findAllByUserIdOrderByObservedAtDesc(UUID userId, Pageable p);
}
```

The other repositories follow standard Spring Data shape:
- `ShoppingListLineRepository` — `findAllByShoppingListId`, `findAllByIdIn`.
- `GroceryOrderLineRepository` — `findAllByGroceryOrderId`.
- `GroceryProviderStateRepository` — `findByUserIdAndProviderKey`, `findAllByScheduledRefreshEnabledTrue`.
- `GrocerySubstitutionProposalRepository` — `findAllByGroceryOrderId`, `findAllByGroceryOrderIdAndProposalStatus`, `countByGroceryOrderIdAndProposalStatus` (gates reconciliation).
- `PackSizeHeuristicRepository` — `findAllByIngredientMappingKeyOrderByRankAsc`, `findAllByCategoryOrderByRankAsc`.

`@EntityGraph` on the hot-read paths keeps order + lines and shopping list + lines to a single JOIN — no N+1.

---

## Service Interfaces

Per the multi-controller / multi-service split (matching the four-tier HLD structure), the module exposes four public service interfaces. A single `GroceryServiceImpl` implements all four — the impl is one class, the interfaces are the contracts. The four-way split makes injection sites narrower (the planner needs only `ShoppingListService` and `PriceHistoryService`; the feedback module needs only `ManualFulfilmentService`; notifications need only the order interface).

### `ShoppingListService` (Tier 1)

```java
public interface ShoppingListService {
    Optional<ShoppingListDto> getCurrentByPlanId(UUID planId);
    Optional<ShoppingListDto> getById(UUID shoppingListId);
    List<ShoppingListDto> getByIds(List<UUID> ids);
    Page<ShoppingListDto> getHistory(UUID userId, Pageable pageable);

    // Recalculate from a plan + provisions snapshot. Idempotent on (planId, planRevision):
    // re-running with the same revision returns the existing row; new revision creates a new
    // shopping list and supersedes the previous one.
    ShoppingListDto recalculate(UUID userId, RecalculateShoppingListRequest request);

    // Render in the requested format. Pure transformation — no side effects.
    ShoppingListExportDto export(UUID shoppingListId, ExportFormat format);
}
```

The HLD lists in-app view, print/PDF, copy-to-clipboard, email/share, and provider drive-in as the surfaces. The LLD models them as `ExportFormat` values; the email/share endpoint is the same render call returning text the frontend hands to a mailto: link or the OS share sheet (frontend concern). PDF is a renderer concern — the service emits print-ready HTML and the frontend can print-to-PDF. **Worth user review** — generating PDF server-side is a future enhancement.

### `ManualFulfilmentService` (Tier 2)

```java
public interface ManualFulfilmentService {
    MarkBoughtResultDto markBought(UUID userId, MarkBoughtRequest request);
    List<MarkBoughtResultDto> bulkMarkBought(UUID userId, BulkMarkBoughtRequest request);

    // Used when the user changes their mind about an entry made via Tier 2.
    // Reverses the inventory add and the price observation.
    void undoMarkBought(UUID shoppingListLineId, UUID actorUserId);
}
```

`undoMarkBought` is HLD-silent but the LLD adds it because the HLD's "user under-marks bought" failure mode and the natural mistake-recovery flow demand a reverse path. **Worth user review.**

### `GroceryOrderService` (Tier 3)

```java
public interface GroceryOrderService {
    Optional<GroceryOrderDto> getById(UUID orderId);
    List<GroceryOrderDto> getByIds(List<UUID> ids);
    Page<GroceryOrderDto> getMyOrders(UUID userId, Pageable pageable);

    // State machine entry points. Each method is one allowed transition (or set of related
    // transitions); attempts that don't match the current state throw IllegalOrderTransitionException.
    GroceryOrderDto createDraft(UUID userId, CreateOrderRequest request);
    GroceryOrderDto quote(UUID userId, QuoteRequest request);
    GroceryOrderDto placeOrder(UUID userId, PlaceOrderRequest request);
    GroceryOrderDto markUserConfirmed(UUID userId, UUID orderId);     // user confirmed in provider UI
    GroceryOrderDto refreshStatus(UUID userId, UUID orderId);          // pulls the provider's checkStatus
    GroceryOrderDto markDelivered(UUID userId, UUID orderId);           // typically via scheduled status check
    GroceryOrderDto cancel(UUID userId, CancelOrderRequest request);

    // Substitutions
    GrocerySubstitutionProposalDto resolveSubstitution(UUID userId, ResolveSubstitutionRequest request);
    List<GrocerySubstitutionProposalDto> getOutstandingProposals(UUID orderId);

    // Provider connection management
    GroceryProviderStateDto getProviderState(UUID userId, String providerKey);
    GroceryProviderStateDto upsertProviderConnection(UUID userId, ProviderConnectionRequest request);
}
```

`markDelivered` is exposed for both manual user signal ("it arrived") and scheduled status polling — the same transition method covers both call sites. The provider's `checkStatus` is wrapped by `refreshStatus`; that method handles the lifecycle implications (e.g. status changing from `confirmed` to `delivered` triggers the substitution-review prompt).

### `PriceHistoryService` (Tier 4)

```java
public interface PriceHistoryService {

    // Aggregation reads — used by the planner's cost sub-score and Tier 1's cost projection.
    Optional<PriceAggregateDto> getAggregate(UUID householdId, String ingredientMappingKey, String store);
    Map<String, PriceAggregateDto> getAggregatesByKeys(UUID householdId, Collection<String> keys);
    List<PriceAggregateDto> getCrossStoreAggregatesByKey(UUID householdId, String key);

    // Raw observation reads — audit / debug.
    Page<PriceObservationDto> getObservations(UUID userId, Pageable pageable);
    Page<PriceObservationDto> getObservationsByMappingKey(
        UUID householdId, String key, Pageable pageable);

    // Capture entry points.
    PriceObservationDto recordManualPrice(UUID userId, RecordManualPriceRequest request);
    RefreshPricesResultDto refreshOnDemand(UUID userId, RefreshPricesRequest request);

    // Scheduled background refresh — invoked by the @Scheduled job, not exposed via REST.
    void runScheduledBackgroundRefresh(UUID userId);
}
```

The aggregation methods consume `PriceAggregator` internally and never return raw observations — the planner reads `(estimate, confidence)` per the HLD's contract.

### `GroceryProvider` interface

Lives in `com.example.mealprep.grocery.domain.service.internal.providers`. The interface is module-public (re-exported via the facade for cross-module testability and future multi-provider plug-in); `TescoGroceryProvider` is package-private.

```java
public interface GroceryProvider {
    String providerKey();
    QuoteResult quote(BasketDraft draft);
    PlaceOrderResult placeOrder(BasketDraft draft);
    OrderStatus checkStatus(String providerOrderId);
    void cancel(String providerOrderId);
}
```

Supporting records (same package):
- `BasketDraft(UUID groceryOrderId, UUID userId, List<BasketDraftLine> lines, BasketDraftPreferences preferences)`.
- `BasketDraftLine(UUID groceryOrderLineId, String ingredientMappingKey, String displayName, BigDecimal quantity, String unit, Integer packSizeG, Integer packCountRequested, String preferredProviderProductId)` — preferred SKU sourced from prior orders.
- `BasketDraftPreferences(boolean preferOwnBrand, boolean preferOrganic, boolean preferFreeRange, String deliverySlotPreference)` — derived from lifestyle config's `groceryQualityPreferences`.
- `QuoteResult(String providerOrderId, Map<UUID, QuoteLineResult> lineResults, Integer quotedTotalPence, String currency, Instant quotedAt)`; `QuoteLineResult(OrderLineStatus status, String resolvedProviderProductId, Integer quotedUnitPence, Integer packCountResolved, String note)`.
- `PlaceOrderResult(String providerOrderId, String confirmLink, Map<UUID, OrderLineStatus> lineStatuses, boolean partial, List<AutomationFailureRecord> failureLog, Instant placedAt)`.
- `OrderStatus(GroceryOrderStatus normalisedStatus, String providerNativeStatus, Instant deliverySlotStart, Instant deliverySlotEnd, Integer confirmedTotalPence, Integer paidTotalPence, List<SubstitutionProposal> substitutions, Instant statusObservedAt)`.
- `SubstitutionProposal(String originalProductId, String originalDisplayName, String substituteProductId, String substituteDisplayName, BigDecimal substituteQuantity, String substituteUnit, Integer substituteUnitPence, String reason, JsonNode rawPayload)`.

**Three structural rules per the HLD:**

1. `placeOrder` drives a basket up to checkout and stops. It never confirms — confirmation happens in the provider's UI by the user. The `confirmLink` returned is the URL the user clicks.
2. Persistent state (cookies, session cursor) is round-tripped through `grocery_provider_state` on every call — the implementation reads state in, calls the provider, writes updated state out. Provider memory is transient.
3. `ProviderUnavailableException` (provider down, login expired, infrastructure failure) and `ProviderPartialFailureException` (`placeOrder` succeeded for a subset of lines) are checked exceptions that the calling service catches and surfaces — never retries blindly.

`TescoGroceryProvider` lives in `internal/providers/TescoGroceryProvider.java` (separate file). Its v1 implementation uses **browser automation via Claude computer use / Chrome connector** with a long-lived per-user session; cookies persisted in `grocery_provider_state.session_state`; item search → "add to basket" actions driven by the AI navigator; substitution detection at checkout; checkout link surfaced for user confirmation. **DOM selectors, login flow, retry policy, and the AI navigator prompts are deferred to implementation-phase technical guidance** per the HLD; this LLD specifies only the contract and the state-machine integration. AI navigator prompts live in the AI service prompt registry, not here.

---

## REST Controllers

Multi-controller split per the pilot precedent. All endpoints under `/api/v1/grocery/...`. `userId` resolved server-side from auth context per [technical-architecture.md §Frontend-Backend Contract](../design/technical-architecture.md#frontend-backend-contract). OpenAPI: `@Tag(name = "Grocery — <tier>")` per controller, `@Operation` per handler.

| Controller | Resource | Tier |
|---|---|---|
| `ShoppingListController` | `/api/v1/grocery/shopping-lists` | 1 |
| `ManualFulfilmentController` | `/api/v1/grocery/shopping-lists/{listId}/lines` | 2 |
| `GroceryOrderController` | `/api/v1/grocery/orders` | 3 |
| `PriceHistoryController` | `/api/v1/grocery/price-history` | 4 |

### Shopping list

| Method | Path | Request | Response | Status |
|---|---|---|---|---|
| GET    | `/api/v1/grocery/shopping-lists/{shoppingListId}` | — | `ShoppingListDto` | 200 / 404 |
| GET    | `/api/v1/grocery/shopping-lists/current?planId={planId}` | — | `ShoppingListDto` | 200 / 404 |
| GET    | `/api/v1/grocery/shopping-lists/history?page=&size=` | — | `Page<ShoppingListDto>` | 200 |
| POST   | `/api/v1/grocery/shopping-lists/recalculate` | `RecalculateShoppingListRequest` | `ShoppingListDto` | 200 / 400 / 404 |
| GET    | `/api/v1/grocery/shopping-lists/{shoppingListId}/export?format=PRINTABLE_HTML` | — | `ShoppingListExportDto` | 200 / 404 |

### Manual fulfilment

| Method | Path | Request | Response | Status |
|---|---|---|---|---|
| POST   | `/api/v1/grocery/shopping-lists/{listId}/lines/{lineId}/mark-bought` | `MarkBoughtRequest` | `MarkBoughtResultDto` | 200 / 400 / 404 / 409 |
| POST   | `/api/v1/grocery/shopping-lists/{listId}/bulk-mark-bought` | `BulkMarkBoughtRequest` | `List<MarkBoughtResultDto>` | 200 / 400 / 404 |
| POST   | `/api/v1/grocery/shopping-lists/{listId}/lines/{lineId}/undo-mark-bought` | — | — | 204 / 404 / 409 |

### Grocery order

| Method | Path | Request | Response | Status |
|---|---|---|---|---|
| GET    | `/api/v1/grocery/orders` | — | `Page<GroceryOrderDto>` | 200 |
| GET    | `/api/v1/grocery/orders/{orderId}` | — | `GroceryOrderDto` | 200 / 404 |
| POST   | `/api/v1/grocery/orders` | `CreateOrderRequest` | `GroceryOrderDto` | 201 / 400 / 404 / 409 |
| POST   | `/api/v1/grocery/orders/{orderId}/quote` | — | `GroceryOrderDto` | 200 / 404 / 409 / 503 |
| POST   | `/api/v1/grocery/orders/{orderId}/place` | — | `GroceryOrderDto` | 200 / 404 / 409 / 422 / 503 |
| POST   | `/api/v1/grocery/orders/{orderId}/mark-user-confirmed` | — | `GroceryOrderDto` | 200 / 404 / 409 |
| POST   | `/api/v1/grocery/orders/{orderId}/refresh-status` | — | `GroceryOrderDto` | 200 / 404 / 503 |
| POST   | `/api/v1/grocery/orders/{orderId}/mark-delivered` | — | `GroceryOrderDto` | 200 / 404 / 409 |
| POST   | `/api/v1/grocery/orders/{orderId}/cancel` | `CancelOrderRequest` | `GroceryOrderDto` | 200 / 404 / 409 |
| GET    | `/api/v1/grocery/orders/{orderId}/substitutions` | — | `List<GrocerySubstitutionProposalDto>` | 200 / 404 |
| POST   | `/api/v1/grocery/orders/{orderId}/substitutions/{proposalId}/resolve` | `ResolveSubstitutionRequest` | `GrocerySubstitutionProposalDto` | 200 / 404 / 409 |
| GET    | `/api/v1/grocery/orders/providers/{providerKey}` | — | `GroceryProviderStateDto` | 200 / 404 |
| PUT    | `/api/v1/grocery/orders/providers/{providerKey}` | `ProviderConnectionRequest` | `GroceryProviderStateDto` | 200 / 400 |

`503` on `quote` / `place` / `refresh-status` means provider unavailable (`ProviderUnavailableException` or `AiUnavailableException`); the response body's `detail` carries the user-facing message and the order moves to `provider_unavailable`. The HLD's "AI navigator cost-cap exceeded" path is mapped to 503 with a distinct ProblemDetail `type` so the UI can render the manual-basket fallback affordance.

### Price history

| Method | Path | Request | Response | Status |
|---|---|---|---|---|
| GET    | `/api/v1/grocery/price-history/aggregates?ingredientKey={key}&store={store}` | — | `PriceAggregateDto` | 200 / 404 |
| GET    | `/api/v1/grocery/price-history/aggregates/cross-store?ingredientKey={key}` | — | `List<PriceAggregateDto>` | 200 |
| GET    | `/api/v1/grocery/price-history/observations?page=&size=` | — | `Page<PriceObservationDto>` | 200 |
| GET    | `/api/v1/grocery/price-history/observations/by-key?ingredientKey={key}&page=&size=` | — | `Page<PriceObservationDto>` | 200 |
| POST   | `/api/v1/grocery/price-history/observations/manual` | `RecordManualPriceRequest` | `PriceObservationDto` | 201 / 400 |
| POST   | `/api/v1/grocery/price-history/refresh` | `RefreshPricesRequest` | `RefreshPricesResultDto` | 200 / 503 |

`refreshOnDemand`'s 503 response indicates the AI cost cap was hit; the caller's degradation contract is "render the printable shopping list and let the user enter prices manually" per the HLD.

The scheduled background refresh has no REST surface — it runs from a `@Scheduled` method.

### Error responses

RFC 9457 ProblemDetail. Module-specific exceptions handled in the project-wide `GlobalExceptionHandler`:

| Exception | Status | `type` URI |
|---|---|---|
| `ShoppingListNotFoundException` | 404 | `.../shopping-list-not-found` |
| `ShoppingListLineNotFoundException` | 404 | `.../shopping-list-line-not-found` |
| `GroceryOrderNotFoundException` | 404 | `.../grocery-order-not-found` |
| `GrocerySubstitutionProposalNotFoundException` | 404 | `.../grocery-substitution-proposal-not-found` |
| `IllegalOrderTransitionException` | 409 | `.../illegal-order-transition` |
| `OrderHasOutstandingProposalsException` | 422 | `.../order-has-outstanding-proposals` |
| `ProviderUnavailableException` | 503 | `.../provider-unavailable` |
| `ProviderPartialFailureException` | 200 | (returned in body — not an error) |
| `ProviderNotConfiguredException` | 422 | `.../provider-not-configured` |
| `AiUnavailableException` | 503 | `.../ai-unavailable` |
| `OrderConcurrencyConflictException` | 409 | `.../order-concurrency-conflict` |
| `OptimisticLockException` (JPA) | 409 | `.../optimistic-lock` |
| `MethodArgumentNotValidException` | 400 | `errors[]` extension |

`OrderHasOutstandingProposalsException` is thrown when a transition to `reconciled` is attempted while substitution proposals are still `pending_user_review` — per the HLD: "The user must resolve all proposals before the order moves to `reconciled`."

`ProviderPartialFailureException` deliberately maps to a 200 response with the partially-filled order in the body — the HLD's "fail forward" contract requires that a partial place is a successful but flagged outcome, not an error.

Module root: `GroceryException extends MealPrepException`.

---

## Validation

Standard Jakarta annotations on request records.

Custom validators in `validation/`:

- **`@ValidQuantityUnit`** — non-negative quantity, scale ≤ 3, magnitude ≤ 1,000,000; unit ∈ canonical unit set (`g`, `kg`, `ml`, `l`, `items`, `pt`, `tsp`, `tbsp`, `cup`).
- **`@ValidObservedPrice`** — non-negative integer pence, ≤ 1,000,000 (catches a £/p mix-up).
- **`@ValidOrderStatusTransition`** — class-level on internal command records (not REST DTOs) that state machine transitions feed; asserts `from → to` is a legal edge per `OrderStateMachine`.

The order state machine itself is enforced service-side (cross-resource validation) — `OrderStateMachine.assertCanTransition(current, target)` throws `IllegalOrderTransitionException` (409). The legal-edges table:

```
draft           → quoted, cancelled
quoted          → placed, placed_partial, draft (re-edit), cancelled
placed          → awaiting_user_confirmation, cancelled, provider_unavailable
placed_partial  → awaiting_user_confirmation, cancelled    (user completes manually)
awaiting_user_confirmation → confirmed, cancelled
confirmed       → delivered, cancelled
delivered       → reconciled, cancelled
reconciled      → archived
provider_unavailable → draft, cancelled                     (re-attempt or abandon)
cancelled       → (terminal)
archived        → (terminal)
```

`reconciled → archived` happens via a `@Scheduled` daily sweep at 12 months past `reconciled_at`. Manual archiving is not exposed.

---

## Events

### Published

The grocery module publishes events across all four tiers. The order-lifecycle family is sealed; stand-alone events stay as plain records per the style guide's "single-kind plain, multi-kind sealed" rule. All events carry `UUID traceId, Instant occurredAt` (omitted from records below for brevity).

```java
// Tier 3 — sealed lifecycle hierarchy
public sealed interface GroceryOrderLifecycleEvent permits
    GroceryOrderQuotedEvent, GroceryOrderPlacedEvent, GroceryOrderConfirmedEvent,
    GroceryOrderDeliveredEvent, GroceryOrderReconciledEvent, GroceryOrderCancelledEvent {
    UUID userId();
    UUID groceryOrderId();
    UUID traceId();
    Instant occurredAt();
}
```

Lifecycle records (each implements `GroceryOrderLifecycleEvent`):
- `GroceryOrderQuotedEvent(UUID userId, UUID groceryOrderId, Integer quotedTotalPence, int observationsWritten, ...)`.
- `GroceryOrderPlacedEvent(UUID userId, UUID groceryOrderId, String confirmLink, boolean partial, ...)`.
- `GroceryOrderConfirmedEvent(UUID userId, UUID groceryOrderId, Integer confirmedTotalPence, Instant deliverySlotStart, Instant deliverySlotEnd, ...)`.
- `GroceryOrderDeliveredEvent(UUID userId, UUID groceryOrderId, int outstandingProposalsCount, ...)`.
- `GroceryOrderReconciledEvent(UUID userId, UUID groceryOrderId, Integer paidTotalPence, ...)`.
- `GroceryOrderCancelledEvent(UUID userId, UUID groceryOrderId, String reason, ...)`.

Stand-alone records (plain — single-kind):
- Tier 1: `ShoppingListGeneratedEvent(UUID userId, UUID householdId, UUID shoppingListId, UUID planId, int planRevision, int lineCount, Integer estimatedTotalPence, ...)`.
- Tier 2: `ShoppingListItemMarkedBoughtEvent(UUID userId, UUID shoppingListId, UUID shoppingListLineId, String ingredientMappingKey, BigDecimal boughtQuantity, String boughtUnit, Integer boughtPricePence, BoughtVia via, ...)` and `ShoppingListBulkMarkedBoughtEvent(UUID userId, UUID shoppingListId, List<UUID> shoppingListLineIds, Integer totalSpendPence, ...)`.
- Tier 3 substitution decisions (kept separate from the lifecycle hierarchy because their listeners react per proposal, not per lifecycle stage): `SubstitutionProposedEvent(UUID userId, UUID groceryOrderId, UUID proposalId, ...)`, `SubstitutionAcceptedEvent(... String substituteIngredientMappingKey, ...)`, `SubstitutionRejectedEvent(... String originalIngredientMappingKey, ...)`.
- Tier 3 availability: `GroceryProviderUnavailableEvent(UUID userId, String providerKey, String reason, ...)`.
- Tier 4: `PriceObservedEvent(UUID userId, UUID householdId, UUID priceObservationId, String ingredientMappingKey, String store, Integer paidUnitPence, PriceSource source, ...)`.

Published via `ApplicationEventPublisher` after the relevant write transaction. Listeners across the system use `@TransactionalEventListener(phase = AFTER_COMMIT)` per the style guide.

The technical-architecture event catalogue lists `GroceryOrderConfirmedEvent` only; the LLD adds the rest of the lifecycle family because the HLD explicitly enumerates them. **The catalogue's `GroceryOrderConfirmedEvent` is the contract Provisions consumes** — the HLD's flow ("provisions adds items to inventory after order confirmation") routes through this event. Provisions also listens to `SubstitutionAcceptedEvent` (the technical-architecture catalogue lists this as a sub-kind of `ProvisionChangedEvent` published by Provisions, but the **proposal/accept/reject decision** is owned here; Provisions in turn publishes `ItemAddedFromGroceryEvent` / `SubstitutionAcceptedEvent` from its own table updates). **Worth user review** — the directionality of `SubstitutionAcceptedEvent` is ambiguous in the design docs; this LLD says grocery publishes the user-decision event and provisions republishes its own state-change event.

The HLD says reconciliation also "updates price history with paid prices" — the LLD makes this explicit: `markDelivered` enqueues paid-price observations during reconciliation; each emits a `PriceObservedEvent` after commit. The reconciled-event fires once after all paid-price rows are written.

### Consumed

```java
@TransactionalEventListener(phase = AFTER_COMMIT)
public void onPlanGenerated(PlanGeneratedEvent event) { ... }     // triggers shopping-list recalculation

@TransactionalEventListener(phase = AFTER_COMMIT)
public void onProvisionChanged(ProvisionChangedEvent event) { ... } // sealed; recalculates list on inventory drift

@TransactionalEventListener(phase = AFTER_COMMIT)
public void onCostBudgetExceeded(CostBudgetExceededEvent event) { ... }  // disables scheduled refresh, surfaces AiUnavailable
```

`PlanGeneratedEvent` is published by the planner (per [meal-planner.md](../design/meal-planner.md)); the listener calls `ShoppingListService.recalculate` for the new plan + revision. `ProvisionChangedEvent` is sealed (per provisions LLD); the listener pattern-matches on sub-kinds: `ItemAddedFromGroceryEvent` and `SubstitutionAcceptedEvent` need no list change (Tier 3 already wrote them); `ItemSpoiledEvent` and `ItemRanOutEvent` may invalidate fulfilment status on the active list and prompt regeneration. **The listener applies a 5-second debounce per `(userId, planId)`** because a single grocery delivery batches multiple item updates — debouncing avoids 15 recalculations for one delivery. `CostBudgetExceededEvent` is published by the AI service (per [lld/ai.md](ai.md)); the grocery listener pauses scheduled refresh by setting `scheduled_refresh_enabled = false` for affected user state until the cap resets.

---

## Business Logic Flows

### Flow 1 — Tier 1: Shopping list calculation (the deterministic six-step pipeline)

`ShoppingListService.recalculate` is `@Transactional`. The HLD's six steps map one-for-one to `ShoppingListCalculator.calculate`:

1. **Aggregate planned demand.** Walk every scheduled recipe in the plan (via `PlannerQueryService.getPlanForGrocery(planId, planRevision)` — a bundled DTO defined in the planner LLD), multiply ingredient quantity by cooked-servings per slot, sum by `ingredient_mapping_key` using the nutrition module's `normaliseKey()` utility (per [technical-architecture.md §Cross-module references](../design/technical-architecture.md#cross-module-references)). Output: `Map<String, IngredientDemand>`.
2. **Subtract current inventory.** Read `pantry_tracking_enabled` from `PreferenceQueryService.getLifestyleConfig(userId).document().pantryTracking().enabled()`. When `false`, skip — inventory treated as empty per the HLD's lifestyle-config gate. When `true`, call `ProvisionQueryService.getActiveInventory(userId)`, group by mapping key, subtract from demand. Underflow (negative remaining) becomes zero — never negative.
3. **Apply pack-size heuristics.** For each non-zero remaining demand, `PackSizeHeuristic` lookup by mapping key (then category fallback). `PackSizeOptimiser.choose(remaining, availablePacks)` picks the smallest combination meeting demand: greedy from largest pack down for non-perishables, from smallest pack up for perishables (avoid waste). Output: `(pack_size_g, pack_count)` per line.
4. **Add staples.** `ProvisionQueryService.getStaplesNeedingReplenishment(userId)` returns staples at `LOW` or `OUT`. Each becomes a `STAPLE_REPLENISHMENT` line with default pack from heuristics.
5. **Apply quality preferences.** Read lifestyle config's `groceryQualityPreferences` (free-range eggs, organic where available, own-brand for staples, ...). Each line gets `quality_notes` populated as a hint. Provider-side product matching (Tier 3) reads these notes when selecting SKUs; without a provider, they're informational on the rendered list.
6. **Cost projection.** For each line, call `PriceHistoryService.getAggregate(householdId, key, store=null)`. Compute `estimated_unit_pence × pack_count = estimated_line_pence`; total = sum; `cost_confidence` = mean of line confidences weighted by line cost. Lines without aggregates contribute `null` and increment `stale_ingredient_count`. With no aggregates at all, the totals are null; the list still renders.

**Idempotency.** Same `(planId, planRevision)` returns the existing list. New `planRevision` creates a new list and supersedes the old one (`superseded_at = now()` on the prior). Concurrent recalculation calls for the same `(planId, planRevision)` are serialised via a database unique constraint (`UNIQUE (plan_id, plan_revision)`); the second insert fails and the catcher re-fetches the existing row.

**Performance.** The bundled `getPlanForGrocery` (defined in the planner LLD) and `getActiveInventory` calls each take one round trip; price aggregates are batched via `getAggregatesByKeys` (one query). `ShoppingListCalculator.calculate` should be ≤ 5 SQL statements end-to-end — verified in `ShoppingListServiceIT` via Hibernate statistics.

After commit, publishes `ShoppingListGeneratedEvent`.

### Flow 2 — Tier 2: Mark bought (single-line)

`ManualFulfilmentService.markBought` is `@Transactional`. Steps:

1. Load the line (404 if missing). Reject if `fulfilment_status = BOUGHT` (already bought; idempotency).
2. Update the line: `fulfilment_status = BOUGHT`, `bought_*` fields populated, `bought_via = MANUAL`.
3. If `boughtPricePence` supplied, write a `PriceObservation` with `source = MANUAL`, `confidence_weight = config.confidenceWeights.manual` (default 0.7); store from request defaults to `"manual"` when not given.
4. Update inventory via `ProvisionUpdateService.applyGroceryOrder` with a single-line command (the `applyGroceryOrder` API per the provisions LLD is the canonical path for inventory adds; idempotency on `(userId, source, sourceRef)` where `sourceRef` is the shopping-list-line-id prevents double adds on retry).
5. Publish `ShoppingListItemMarkedBoughtEvent`. If a price was captured, publish `PriceObservedEvent` (one per observation per the technical-architecture rule).

**Concurrency.** Two concurrent mark-bought calls on the same line both load `version=N`; second commit gets `OptimisticLockException` → 409. The frontend retries with the fresh state.

### Flow 3 — Tier 2: Bulk mark-bought with total spend distribution

`ManualFulfilmentService.bulkMarkBought` is `@Transactional`. When `totalSpendPence` is supplied:

1. Load all selected lines and their `estimated_unit_pence` aggregates.
2. Compute the distribution weight per line: `weight_i = estimated_line_pence_i / sum(estimated_line_pence)`. Lines with no estimate get a uniform fallback share of the residual.
3. Allocate `bought_price_pence_i = round(totalSpendPence × weight_i)`; reconcile rounding by dunning the last line (largest total) for the residual.
4. Write one `PriceObservation` per line with `source = MANUAL_ESTIMATED`, `confidence_weight = config.confidenceWeights.manualEstimated` (default 0.4). The lower confidence weight is the HLD's discount on distributed prices.
5. Single inventory call to `ProvisionUpdateService.applyGroceryOrder` with all lines (one event from provisions, one from grocery).

When `totalSpendPence` is null, each line uses its `estimated_unit_pence` if known, else writes no price observation — same behaviour as a per-line mark-bought without a price.

After commit, publishes one `ShoppingListBulkMarkedBoughtEvent` summarising the batch. Per-line `ShoppingListItemMarkedBoughtEvent`s are **not** published in bulk mode — the listener contract is "one event per user-initiated operation" per technical-architecture §Event debouncing.

### Flow 4 — Tier 3: Order lifecycle, draft → reconciled

The full happy path crosses six service methods, each `@Transactional`. Orchestration is sequential — each method commits before the next is invoked.

**Single-flight.** `quote`, `place`, and `refreshStatus` acquire `core.LockService.tryAcquire(scope, ttl)` keyed on `hash(userId, shoppingListId)` per [style-guide.md §Single-flight per scope](style-guide.md#single-flight-per-scope). TTL 5 minutes. Failed acquire → `OrderConcurrencyConflictException` (409). Guards against double-tapping "place order" mid-flight.

1. **`createDraft`** — `DRAFT`. Validates shopping list and provider configuration; clones lines from the list (deep copy — the order is the snapshot, the list may be regenerated).
2. **`quote`** — `DRAFT → QUOTED`. Acquires lock. Loads `GroceryProviderState` (404 if missing; `ProviderNotConfiguredException` if disabled). Constructs `BasketDraft` via `BasketDraftAssembler`. Calls `provider.quote(draft)`. On `ProviderUnavailableException` → `PROVIDER_UNAVAILABLE`, publishes `GroceryProviderUnavailableEvent`, 503. On `AiUnavailableException` → reverts to `DRAFT` with reason "AI cost cap reached", 503 — user falls back to manual fulfilment. On success: writes `provider_order_id`, per-line quoted prices, one `PriceObservation` per quoted line (`source = QUOTE`, weight 0.85). Publishes `GroceryOrderQuotedEvent` and per-line `PriceObservedEvent`.
3. **`placeOrder`** — `QUOTED → PLACED | PLACED_PARTIAL`. Acquires lock. Calls `provider.placeOrder(draft)`. Persists `confirm_link`, per-line `OrderLineStatus`, `automation_failure_log` records. Auto-advances to `AWAITING_USER_CONFIRMATION`. **The user always confirms in the provider's own UI** — automation never auto-confirms.
4. **`markUserConfirmed`** — `AWAITING_USER_CONFIRMATION → CONFIRMED`. Optionally fetches confirmed total via `provider.checkStatus`. Publishes `GroceryOrderConfirmedEvent` — **the event Provisions consumes** to add items to inventory per [technical-architecture.md §Event catalogue](../design/technical-architecture.md#event-catalogue).
5. **`refreshStatus` / `markDelivered`** — A `@Scheduled` job hourly polls active confirmed orders; advances `CONFIRMED → DELIVERED` when the provider reports delivery. Persists substitution proposals as `pending_user_review` rows; publishes one `SubstitutionProposedEvent` per. Publishes `GroceryOrderDeliveredEvent`.
6. **Substitution resolution.** Per-proposal `resolveSubstitution` updates status to `ACCEPTED | REJECTED`. Accept → `SubstitutionAcceptedEvent` (Provisions adds the substitute). Reject → `SubstitutionRejectedEvent` (Provisions skips; planner notified). **Auto-accept is never the default** per the HLD.
7. **Reconciliation.** Internal `tryReconcile(orderId)` runs when no proposals remain `pending_user_review` (called from `resolveSubstitution` commit and from `markDelivered`): writes paid-price observations (`source = PAID`, weight 1.0), updates `paid_total_pence`, sets `status = RECONCILED`. Publishes `GroceryOrderReconciledEvent` + per-line `PriceObservedEvent`. Reconciliation blocks while proposals are pending — `OrderHasOutstandingProposalsException` (422) if forced.

**Failure forward, never silent.**

| Failure | Module behaviour |
|---|---|
| Login expired (`ProviderUnavailableException` with reason `login_required`) | Order stays `DRAFT`; `consecutive_failures++`; the user re-authenticates via the provider connection flow and re-runs |
| 3 of 5 items added (`ProviderPartialFailureException`) | Status `PLACED_PARTIAL`; added items persisted; `confirm_link` returned for manual completion |
| Substitution unparseable | Stored with `proposal_status = UNPARSED`, `raw_payload` populated; user resolves manually via the `resolveSubstitution` endpoint (which accepts an `UNPARSED → ACCEPTED|REJECTED` transition) |
| Delivery slot fails | Order pauses at `PLACED`; user picks slot manually in the provider UI |
| Provider down (`ProviderUnavailableException`) | Status `PROVIDER_UNAVAILABLE`; scheduled retry runs `quote` once an hour for 24 hours, then the order auto-cancels with `cancel_reason = "provider_unavailable_24h"` |
| AI navigator cost cap (`AiUnavailableException`) | Status reverts to `DRAFT`; `automation_failure_log` records the AI cost cap hit; the printable shopping list is the fallback per the HLD's graceful-degrade contract |

### Flow 5 — Tier 4: Price aggregation

`PriceAggregator.aggregate(householdId, ingredientMappingKey, store)` is the worker. Pure logic, deterministic, called from `getAggregate`/`getAggregatesByKeys`. Steps per the HLD:

1. **Fetch recent observations.** `findRecentByKey(householdId, key, since)` where `since = now() - 6 × halfLife` — enough samples to span the decay window. With `halfLife = 90 days` (default), `since = now() - 540 days`.
2. **Source-weight observations.** Each row's `confidence_weight` was set at write time per `GroceryConfig.confidenceWeights` (`paid = 1.0`, `quote = 0.85`, `manual = 0.7`, `manual_estimated = 0.4`, `inflation_indexed = 0.15`). These are **defaults** flagged for tuning.
3. **Time-decay weight.** Per-row `decay = 0.5 ^ (ageDays / halfLife)`. Effective weight = `confidence_weight × decay`.
4. **Point estimate.** `sum(price × effectiveWeight) / sum(effectiveWeight)`.
5. **Confidence.** Bayesian-shaped: `confidence = sum(effectiveWeight) / (sum(effectiveWeight) + priorStrength)` where `priorStrength = config.aggregator.priorStrength` (default 2.0). Rises with sample count and freshness; falls with age. Range [0, 1).
6. **Range and recency.** `min`/`max` over the in-window set with their `observed_at`. `lastSeenAt = max(observed_at)`. `isStale = lastSeenAt < now() - 90 days`.

**Inflation indexing fallback.** When step 1 returns zero rows (or all rows are stale beyond a threshold), `InflationIndexer.synthesise` finds the most recent paid/quote observation for the key (across the household, ignoring `since`), applies `factorMonthly = config.inflation.monthlyFactor` (default 0.005, ~0.5%/month, intended to track UK food CPI per HLD) compounded over the elapsed months, writes one new row with `source = INFLATION_INDEXED` and `confidence_weight = config.confidenceWeights.inflationIndexed`. The aggregator re-runs and returns. When *no* prior observation exists (cold start, novel ingredient), returns `Optional.empty()` — the planner sees no price.

**Constants are configurable.** All decay, prior, and inflation values live in `GroceryConfig` (see Configuration below) with the values above as defaults; the LLD specifies the structure, tuning is implementation-phase per the HLD's deferral.

### Flow 6 — Tier 4: On-demand and scheduled refresh

**On-demand.** `PriceHistoryService.refreshOnDemand` accepts a key list and a `useProviderQuote` flag. When `useProviderQuote = true` and the user has a provider configured: assemble a draft basket from the requested keys (one item per key, quantity = 1 pack at default heuristic size), call `provider.quote(draft)`. Each line writes one `quote`-source observation. If `AiUnavailableException` is thrown, the call returns `RefreshPricesResultDto(observationsWritten = 0, ..., aiUnavailableFallbackUsed = true, fallbackMessage = "AI features paused — enter prices manually via mark-bought.")`. The HLD's "manual-refresh quotes count against the AI cost cap normally" — the AI service tracks this; the grocery module just calls and handles the response.

When `useProviderQuote = false`: returns the latest aggregates without touching the provider. Useful for "show me the freshest aggregates without spending tokens."

**Scheduled.** `@Scheduled(cron = "${mealprep.grocery.scheduled-refresh.cron:0 0 4 * * SUN}")` runs weekly. For each user with `scheduled_refresh_enabled = true`:

1. Pre-flight: check `AiCostBudgetService.hasDailyCapacity(userId)` (per [lld/ai.md](ai.md)). If false (daily cap approached), skip and log INFO — the HLD's "scheduled background refresh is the **first** thing the system skips when the daily cap is approached." If the monthly cap fires, all users skip until the cap resets.
2. Compute the user's top-N most-used ingredients (default `refresh_top_n_ingredients = 50`) via `RecipeQueryService.getTopUsedIngredientKeys(userId, lookbackWeeks = 12, n)`.
3. Call `refreshOnDemand(userId, keys, useProviderQuote = true)`.

`PriceFreshnessGuardrails.preflight(userId, kind)` centralises the cost-cap-aware decision: returns `ALLOW` / `SKIP` / `BLOCK` depending on cap state and refresh kind; the scheduled job respects `SKIP`, the on-demand path lets `AiUnavailableException` surface naturally because the user invoked it deliberately.

### Flow 7 — Tier 1 + Tier 4: Stale-data visibility on plan generation

When the planner publishes `PlanGeneratedEvent`, the listener (`onPlanGenerated`) calls `recalculate`; the resulting `ShoppingListDto` carries `cost_confidence` and `stale_ingredient_count`. The planner's plan rollup (per [meal-planner.md §Cost sub-score and price confidence](../design/meal-planner.md#cost-sub-score-and-price-confidence)) reads these fields via `ShoppingListService.getCurrentByPlanId` to render the freshness summary the HLD specifies. The "Refresh prices?" affordance routes to `refreshOnDemand`.

### Flow 8 — Provider connection management

`upsertProviderConnection` creates or updates `grocery_provider_state`. Used to:
- Enable/disable the provider for the user (`enabled` flag).
- Toggle scheduled background refresh (`scheduled_refresh_enabled`) and the curated set size.
- Reset the failure counter (when the user has fixed the underlying issue, e.g. logged back in).

Login itself (the actual cookie-establishing flow) happens in the provider implementation and is **out of scope for this LLD** per the HLD — Tesco's login interactive flow lives in implementation-phase technical guidance. The LLD specifies only that login happens out-of-band and produces a `session_state` blob the provider impl persists via this entity.

---

## Concurrency and Transactions

| Concern | Decision |
|---|---|
| `@Transactional` placement | All service-impl methods. Repositories never. |
| Read methods | `@Transactional(readOnly = true)`. |
| Write methods | Default REQUIRED. `markBought` / `bulkMarkBought` are top-level. Order-lifecycle methods are top-level. `recalculate` invoked from a `@TransactionalEventListener(AFTER_COMMIT)` — runs in its own transaction. |
| Optimistic locking | `@Version` on `ShoppingList` (parent of lines), `GroceryOrder` (parent of lines), `GroceryProviderState`, `GrocerySubstitutionProposal`. Append-only (`PriceObservation`) and reference data (`PackSizeHeuristic`) — no `@Version`. |
| Single-flight | `core.LockService.tryAcquire(scope, ttl)` keyed on `hash(userId, shoppingListId)` for `quote` / `place` / `refreshStatus`. Lock TTL 5 minutes. Failed acquire → `OrderConcurrencyConflictException` (409). |
| Pessimistic locking | None beyond the advisory lock above. |
| AI calls in transactions | **Outside transactions globally.** `provider.quote` and `provider.placeOrder` may call AI internally (the navigator); the wrapping service method opens its transaction *after* the provider call returns, applies state updates, commits. The advisory lock holds across both the provider call and the transaction so a concurrent caller can't observe inconsistent state. |
| Scheduled refresh fan-out | `@Async` per-user execution to avoid blocking the scheduler thread; each user's refresh is its own bounded transaction. |
| List-line concurrent mark-bought | `@Version` on `ShoppingList` (the aggregate root). Two concurrent line edits both bump the parent — second commit gets 409, frontend retries. |
| Substitution-resolve race | `@Version` on the proposal; concurrent resolve attempts → 409. |
| `recalculate` race on same revision | DB unique constraint `(plan_id, plan_revision)`; second insert fails, catcher re-fetches existing row. |

---

## Configuration

`GroceryConfig` is a `@ConfigurationProperties("mealprep.grocery") @Validated` record bundle. Six nested records cover the tunable surfaces:

| Record | Fields (defaults) |
|---|---|
| `AggregatorConfig` | `halfLifeDays=90`, `priorStrength=2.0`, `staleThresholdDays=90` |
| `ConfidenceWeightsConfig` | `paid=1.0`, `quote=0.85`, `manual=0.7`, `manualEstimated=0.4`, `inflationIndexed=0.15` |
| `InflationConfig` | `monthlyFactor=0.005` (~0.5%/month per HLD) |
| `FreshnessConfig` | `rampUpWeeks=8`, `defaultRefreshTopN=50` |
| `SchedulerConfig` | `refreshCron="0 0 4 * * SUN"`, `orderStatusCron="0 0 * * * *"`, `archiveCron="0 0 5 * * *"` |
| `OrderConfig` | `singleFlightLockTtlSeconds=300`, `providerUnavailableRetryHours=24` |

Values validated with `@Min` / `@DecimalMin` / `@DecimalMax` / `@NotBlank` per the style guide. Defaults specified above are the LLD's starting values; per the HLD's deferral, "tuning is implementation-phase" — `monthlyFactor`, `halfLifeDays`, and the confidence weights are the most likely tuning targets, exposed as config so changes don't require a redeploy.

---

## Test Plan

Unit tests use `@ExtendWith(MockitoExtension.class)`. Integration tests are `*IT.java` with Testcontainers Postgres. Names follow `methodName_scenario_expected`.

### Unit

| Class | Verifies |
|---|---|
| `GroceryServiceImplTest` | All four service interfaces' happy paths and error mappings, with mocked repositories, mocked `PlannerQueryService` / `ProvisionQueryService` / `PreferenceQueryService` / `ProvisionUpdateService`, mocked `GroceryProvider`. |
| `ShoppingListCalculatorTest` | The six steps in isolation: aggregate by mapping key, subtract inventory (gated by `pantryTrackingEnabled`), pack-size optimisation with category fallback, staple appending, quality-notes propagation, cost projection with mixed-aggregate-availability lines. |
| `PackSizeOptimiserTest` | Smallest-combination logic: 750g flour with 500g/1kg packs picks 1×1kg; 1.5kg flour picks 1×1.5kg over 3×500g; perishables prefer smaller-up; ingredient-key match wins over category match. |
| `OrderStateMachineTest` | Every legal transition succeeds; sample of illegal transitions throws `IllegalOrderTransitionException`; state-graph completeness assertion (every status has at least one outgoing edge except terminals). |
| `BasketDraftAssemblerTest` | Lines mapped 1:1; preferences derived from lifestyle config; preferred-product lookup uses last-paid SKU per `(householdId, ingredientMappingKey)`. |
| `SubstitutionPersisterTest` | Provider proposals → `pending_user_review` rows; opaque payloads → `unparsed` with `raw_payload`. |
| `PriceObservationWriterTest` | Source-weight assignment correct per source; unit normalisation per nutrition canonical unit; rejects writes for unknown mapping keys. |
| `PriceAggregatorTest` | Confidence rises with sample count, falls with age; decay produces continuous values not step functions; min/max/lastSeenAt correct on canonical fixtures; `isStale` flag triggers at the threshold. |
| `InflationIndexerTest` | Compounded factor over months is correct; null prior → empty result; long ago prior + low factor produces sensible synthesised price; written row carries `source = INFLATION_INDEXED` and the configured low confidence. |
| `PriceFreshnessGuardrailsTest` | Daily cap triggers `SKIP` for scheduled, `ALLOW` for on-demand; monthly cap triggers `BLOCK` everywhere. |
| `ManualFulfilmentServiceTest` | Single-line happy path (price + no-price branches); bulk distribution with rounding residual; bulk without total uses per-line aggregates; idempotent re-mark on already-bought lines (no-op or 409 per chosen rule). |
| Mapper + validator tests | MapStruct round-trips preserve fields incl. JSONB; `@ValidQuantityUnit` / `@ValidObservedPrice` / `@ValidOrderStatusTransition` accept/reject canonical examples. |

### Integration

| Class | Verifies |
|---|---|
| `ShoppingListControllerIT` | GET (200/404), recalculate (200/400/404), export (200), pagination on history. ProblemDetail shape on errors. |
| `ManualFulfilmentControllerIT` | mark-bought (200/400/404/409), bulk-mark-bought (200/400), undo (204/404/409). Verifies `ShoppingListItemMarkedBoughtEvent` is published exactly once after commit. |
| `GroceryOrderControllerIT` | Full lifecycle: createDraft → quote → place → mark-user-confirmed → refresh-status → mark-delivered → resolve-substitution → reconciled. Each transition emits its event after commit; outstanding-proposals blocks reconciliation; cancel from each state behaves per the state machine. |
| `PriceHistoryControllerIT` | Aggregate read returns correct shape; manual price record happy + 400; refresh on-demand returns 503 when AI service mocked to throw `AiUnavailableException`. |
| `ShoppingListCalculatorIT` | End-to-end with real DB: aggregate from a planner-bundle DTO, subtract real inventory, write list + lines, idempotent on `(planId, planRevision)`, supersede on new revision. Hibernate-statistics check: ≤ 5 SQL statements per recalculate call. |
| `OrderConcurrencyIT` | Two concurrent `quote` calls on the same shopping list: one acquires the advisory lock, the other gets `OrderConcurrencyConflictException` (409). After the first releases, the second can re-attempt. |
| `OrderReconciliationIT` | Order with substitutions: reconcile blocked while pending proposals exist; resolve all → `tryReconcile` runs and emits `GroceryOrderReconciledEvent`; paid-price observations written with `source = PAID`. |
| `SchedulerIT` | Order-status hourly job advances confirmed → delivered when provider mock reports delivery; archive sweep moves 12-month-old reconciled orders to `archived`; scheduled refresh respects `scheduled_refresh_enabled` and the AI daily cap. |
| `PriceAggregationIT` | Real DB: write 50 observations across sources, query aggregate, confidence within expected band, decay reflected in point estimate; inflation fallback synthesises a row when only stale observations exist. |
| `EventPublicationIT` | Each tier's events published exactly once after commit; sealed `GroceryOrderLifecycleEvent` listeners receive subtype-specific records; `ShoppingListBulkMarkedBoughtEvent` is single regardless of line count. |
| `ProvisionsIntegrationIT` | mark-bought + order reconciliation both call `ProvisionUpdateService.applyGroceryOrder` with the right command shape (uses a real provisions impl in the test slice or a Spy that records invocations); idempotent re-call is a no-op. |
| `FlywayMigrationIT` | Boots Postgres, runs all grocery migrations, validates schema matches JPA mapping (`ddl-auto=validate`). |

### Test doubles

- `FakeGroceryProvider` (test-scoped bean) implements `GroceryProvider` with deterministic outputs driven by per-test fixture data — quote/place/checkStatus/cancel return canned results; an injectable failure mode toggles `ProviderUnavailableException` / `ProviderPartialFailureException` / `AiUnavailableException` for negative-path tests. The real `TescoGroceryProvider` is exercised only in the implementation-phase test plan, not here.

---

## Failure Modes (consolidated)

| Failure | Tier | Module behaviour |
|---|---|---|
| No provider configured | 3 | `ProviderNotConfiguredException` 422 — UI surfaces "configure Tesco in Settings" |
| Login expired | 3 | Order remains `DRAFT`; `GroceryProviderUnavailableEvent`; user re-authenticates manually |
| Partial place (3/5 added) | 3 | Status `PLACED_PARTIAL`; persisted lines + `confirm_link`; user completes manually |
| Substitution unparseable | 3 | `proposal_status = UNPARSED`; user resolves via the resolve endpoint |
| Delivery slot fails | 3 | Pauses at `PLACED`; user picks slot manually |
| Provider down | 3, 4 | `provider_unavailable` status; auto-cancel after 24 retry hours; manual entry path remains |
| AI navigator cost-cap exceeded | 3, 4 | Status reverts to `DRAFT`; printable list is the fallback; scheduled refresh skipped |
| Plan total exceeds budget | 1 | `cost_confidence` and `estimated_total_pence` carried in DTO; planner surfaces the over-budget warning per its HLD |
| User over-marks bought (more than list) | 2 | `bought_quantity > requested_quantity` is allowed; ad-hoc inventory entries created via provisions; warning surfaced via the response (`note` on the result) |
| User under-marks bought | 2 | Unmarked lines remain `unfilled`; planner offers re-optimisation if material |
| Inflation indexing produces wrong price | 4 | Low `confidence_weight` on indexed observations; user override at next mark-bought writes a high-confidence row that dominates the aggregate |
| Concurrent recalculate / order-place | 1, 3 | DB unique on `(plan_id, plan_revision)`; advisory lock on `(userId, shoppingListId)` |
| Listener debounce miss (15 inventory updates) | 1 | 5-second `(userId, planId)` debounce in `onProvisionChanged` collapses bursts; one recalculate per delivery |

The **graceful-degrade contract** is preserved: at every failure point in Tier 3, the user can fall back to the printable shopping list and complete manually — automation is convenience, never a hard dependency, per the HLD.

---

## Out of Scope

Deferred deliberately:

- **Tesco-specific browser automation** — DOM selectors, login flow, retry policy, anti-bot measures. Live in implementation-phase technical guidance. The LLD specifies only the `GroceryProvider` contract and the lifecycle integration.
- **AI navigator prompts** — System message and tool-use schema for the basket-build navigator. Live in the AI service prompt registry per [technical-architecture.md §AI Service](../design/technical-architecture.md#ai-service-architecture).
- **Pack-size heuristic table contents** — Schema specified; actual ingredient → pack-size data is reference data filled in later via the repeatable seed migration.
- **Confidence-formula constants** — Structure and defaults are specified in `GroceryConfig`; tuning is implementation-phase per the HLD.
- **Frontend / UI concerns** — Shopping list view, mark-bought UX, basket review screen, substitution-review UI flow (Figma phase). PDF rendering server-side; v1 emits print-ready HTML for browser print-to-PDF. Email/share is a frontend concern over the `export` text output.
- **Cross-module orchestration of `PlanGeneratedEvent`** — The planner's contract; this module listens but does not specify when the planner publishes.
- **Multi-supplier basket splitting, recurring/subscription orders, loyalty schemes, click-and-collect, receipt scanning, barcode lookup, real-time deal scraping** — All explicit out-of-scope items per the HLD.
- **Provider-side payment management** — Payment lives with the provider; this module never sees card details. `grocery_provider_state.session_state` carries cookies only.
- **Encrypted-JSONB converter** — `EncryptedJsonConverter` for `session_state` is referenced; key management and algorithm choice are implementation-phase decisions. **Worth user review**.
- **Cross-recipe pack-size optimisation** — v1 picks smallest sufficient pack per line; cross-recipe consolidation is a future planner objective.
- **Multi-location provisioning** — One location per household for v1, per the HLD's household scoping.
- **Authentication** — Owned by the auth module.
- **Household merging** — Price history is household-scoped; the merge logic for a member's first contribution joining an existing household's history is the household LLD's concern.
- **Discovery-style ingredient enrichment** — Linking shopping-list lines to the recipe context that demanded them is a UX enrichment for v2; the data exists in the planner bundle but is not surfaced in the shopping-list DTO in v1. **Worth user review**.
