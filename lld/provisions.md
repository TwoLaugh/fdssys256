# Provisions Module — LLD

*Implementation specification for the four-concern Provision Model: inventory, equipment, budget, and supplier data — the physical-world state the planner optimises against. Translates [provision-model.md](../design/provision-model.md) into a buildable Spring Boot module.*

## Scope

This document specifies the `provisions` module — package layout, JPA entities, Flyway migrations, repositories, service interfaces, DTOs, mappers, REST controllers, validation, events, business-logic flows, transaction boundaries, and the test plan. Conventions defer to [lld/style-guide.md](style-guide.md); this LLD restates a rule only when the module-specific application matters.

The HLD's four concerns map to mostly-independent persistence shapes:

| Concern | Storage | Mutability | Update path |
|---|---|---|---|
| Inventory | Relational (`provision_inventory`) — quantity- or status-tracked, discriminated by `tracking_mode` | High churn | `ProvisionUpdateService` (cook, grocery, manual, waste) |
| Equipment | Relational (`provision_equipment`) — one row per tool | Stable, user-only | `ProvisionUpdateService.upsertEquipment` |
| Budget | Relational (`provision_budget`) — one row per user | Stable, user-only | `ProvisionUpdateService.updateBudget` |
| Supplier products | Relational (`provision_supplier_products`); substitution history JSONB | Cached from orders, user corrections | `ProvisionUpdateService.upsertSupplierProduct` |
| Waste log | Append-only (`provision_waste_log`) | Immutable per HLD | `ProvisionUpdateService.logWaste` |

The Provisions data drives the planner's "use existing stock" scoring incentive (per [meal-planner.md §Provisions utilisation](../design/meal-planner.md#provisions-utilisation)). `ProvisionForPlannerService` bundles inventory, equipment, budget, and supplier prices in one round trip — analogous to `SoftPreferenceBundleDto` in [lld/preference.md](preference.md).

This module is **mechanical CRUD plus event sources**. There is no AI here — feedback arrives pre-classified, writes are deterministic.

---

## Package Layout

```
com.example.mealprep.provisions/
├── ProvisionsModule.java                   facade re-exporting public service interfaces
├── api/
│   ├── controller/                         InventoryController, EquipmentController,
│   │                                        BudgetController, WasteController, SupplierProductsController
│   ├── dto/                                request/response records
│   └── mapper/                             one MapStruct mapper per entity-DTO pair
├── domain/
│   ├── entity/                             JPA entities
│   ├── repository/                         Spring Data interfaces — package-private
│   └── service/
│       ├── ProvisionQueryService.java      public interface
│       ├── ProvisionUpdateService.java     public interface
│       ├── ProvisionForPlannerService.java public interface (bundle)
│       ├── ProvisionServiceImpl.java       single impl of all three
│       └── internal/
│           ├── InventoryDeductionEngine    FIFO-by-expiry deduction, floor-at-zero rule
│           ├── ExpiryInferenceService      rule-registry façade (rule data deferred)
│           ├── ExpiryRule                  registry interface
│           ├── StapleStateTransitioner     stocked → low → out enforcement
│           ├── BatchCookSplitter           fridge/freezer portion split from cook event
│           ├── GroceryImportProcessor      idempotent grocery order payload application
│           ├── WasteValidator              waste-vs-inventory rule
│           └── ProvisionEventBatcher       coalesces ProvisionChangedEvent within a tx
├── event/                                  ProvisionChangedEvent (sealed) + siblings
├── exception/                              module-root + per-failure subclasses
├── validation/                             @ValidQuantity, @ValidStorageLocation
└── config/                                 ProvisionsJsonConfig (registers JsonType)
```

Multi-controller split per the pilot precedent. `internal/` is package-private. `ExpiryRule` is an interface; v1 rule data is deliberately deferred (see Out of Scope).

---

## Database

Migrations live under `src/main/resources/db/migration/` with the project-wide timestamp scheme from [technical-architecture.md §Migrations](../design/technical-architecture.md#migrations):

```
V20260502120000__provision_create_inventory.sql
V20260502120100__provision_create_equipment.sql
V20260502120200__provision_create_budget.sql
V20260502120300__provision_create_supplier_products.sql
V20260502120400__provision_create_waste_log.sql
V20260502120500__provision_create_inventory_audit.sql
R__provision_seed_equipment_catalogue.sql
```

One concern per migration, append-only.

### V20260502120000 — Inventory

Single table for both quantity-tracked and status-tracked items, discriminated by `tracking_mode`. Justified by the HLD's "everything is optional" principle — the dominant access pattern is "what's in the house" regardless of tracking mode.

```sql
CREATE TABLE provision_inventory (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL,                                       -- household-shared; primary household member for v1
    name varchar(128) NOT NULL, category varchar(64) NOT NULL,
    storage_location varchar(16) NOT NULL,                       -- fridge | freezer | cupboard | spice_rack
    tracking_mode varchar(16) NOT NULL,                          -- quantity | status
    -- Quantity-tracked (nullable when status-tracked)
    quantity numeric(10,3), unit varchar(16), cost_paid numeric(8,2),
    -- Status-tracked (nullable when quantity-tracked)
    status varchar(16),                                          -- stocked | low | out
    is_staple boolean NOT NULL DEFAULT false,
    -- Common
    expiry_date date,                                            -- nullable: HLD allows expiry tracking off
    ingredient_mapping_key varchar(128),                         -- nutrition cache key, lowercased
    notes varchar(255),
    -- Provenance and lifecycle
    source varchar(16) NOT NULL,                                 -- tesco_order | other_shop | manual_add | batch_cook | gift
    source_ref varchar(128),
    item_status varchar(16) NOT NULL DEFAULT 'active',           -- active | exhausted | spoiled | wasted
    -- Freezer-only (nullable otherwise)
    frozen_at date, max_freeze_weeks integer,
    defrost_method varchar(32), defrost_lead_time_hours integer,
    source_recipe_id uuid,                                       -- soft FK; cross-module ID only
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL,
    CONSTRAINT chk_tracking_quantity CHECK (tracking_mode <> 'quantity' OR (quantity IS NOT NULL AND unit IS NOT NULL)),
    CONSTRAINT chk_tracking_status   CHECK (tracking_mode <> 'status'   OR status IS NOT NULL),
    CONSTRAINT chk_quantity_nonneg   CHECK (quantity IS NULL OR quantity >= 0)
);
-- Hot read: planner / query service "what's in the house".
CREATE INDEX idx_prov_inventory_user_status ON provision_inventory (user_id, item_status);
-- Expiry-driven scheduling and ItemNearingExpiryEvent sweep.
CREATE INDEX idx_prov_inventory_user_expiry ON provision_inventory (user_id, expiry_date)
    WHERE item_status = 'active' AND expiry_date IS NOT NULL;
-- Recipe → inventory match during cook-event deduction; planner's stock-utilisation scoring.
CREATE INDEX idx_prov_inventory_user_mapping_key ON provision_inventory (user_id, ingredient_mapping_key)
    WHERE item_status = 'active' AND ingredient_mapping_key IS NOT NULL;
-- Staple replenishment list — small selective index.
CREATE INDEX idx_prov_inventory_user_staples ON provision_inventory (user_id, status) WHERE is_staple = true;
-- Idempotency for grocery imports.
CREATE INDEX idx_prov_inventory_source_ref ON provision_inventory (user_id, source, source_ref) WHERE source_ref IS NOT NULL;
```

`item_status` is a soft-delete-with-reason. Justified per the style guide: the waste log references inventory IDs, and the user-facing "stale data" prompt needs to distinguish "still there but ignored" from "removed". The hot-path indexes (`idx_prov_inventory_user_expiry`, `idx_prov_inventory_user_mapping_key`) are already partial on `item_status = 'active'` so dead rows do not bloat planning queries. The `idx_prov_inventory_user_status` index covers status-filtered queries (e.g. waste reports) and intentionally includes all statuses.

**Retention.** Soft-deleted rows are bounded by a scheduled hard-delete sweep (`@Scheduled`, daily at 04:00 UTC, configurable via `mealprep.provision.retention.cron`): rows with `item_status IN ('exhausted', 'spoiled', 'wasted')` and `updated_at < now() - INTERVAL '12 months'` are eligible for hard delete, AFTER pruning any `provision_waste_log` rows that reference them. With ~500-1000 soft-deleted rows per user per year, this caps storage at ~1-2 years of dead rows in the worst case (~2-3 KB per user per year — negligible) while keeping FKs valid. The waste-log retention policy is the upstream constraint; deferred to a follow-up if 12-month waste-log retention is too short.

### Pantry tracking disable

The `pantry_tracking_enabled` flag in the user's lifestyle config (per [preference.md §Lifestyle config](preference.md)) project-wide gates provisions reads and event behaviour. When `false`:

| Concern | Behaviour |
|---|---|
| Inventory CRUD endpoints | Continue to function (user may re-enable later); reads return existing rows but are not consumed downstream. |
| `ProvisionForPlannerBundleDto.inventory` | Returns empty list. |
| Auto-deduct on `MealCookedEvent` | No-op. |
| Auto-add on `GroceryOrderConfirmedEvent` | No-op (the order itself still happens; provisions just doesn't track stock). |
| Expiry sweep job (`ItemNearingExpiryEvent` publisher) | Skipped. |
| Staple replenishment list | Empty. |

The flag is read once per service-method invocation via `PreferenceQueryService` and held for the duration of the call. `LifestyleConfigChangedEvent` invalidates any in-memory caches that hold the flag.

### V20260502120100 — Equipment

```sql
CREATE TABLE provision_equipment (
    id uuid PRIMARY KEY, user_id uuid NOT NULL,
    name varchar(64) NOT NULL,                                   -- oven | air_fryer | slow_cooker | ...
    available boolean NOT NULL, details varchar(255),
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL,
    UNIQUE (user_id, name)
);
-- Small set per user (~12 rows); the unique index covers all reads.
```

Free-text `name` (not enum) so users can add unusual items without a migration. The repeatable seed migration provides the canonical list.

### V20260502120200 — Budget

```sql
CREATE TABLE provision_budget (
    id uuid PRIMARY KEY, user_id uuid NOT NULL UNIQUE,
    weekly_target numeric(8,2) NOT NULL,
    currency varchar(3) NOT NULL DEFAULT 'GBP',
    tolerance_over numeric(8,2) NOT NULL DEFAULT 0,
    price_sensitivity varchar(16) NOT NULL DEFAULT 'moderate',   -- low | moderate | high
    enabled boolean NOT NULL DEFAULT true,                       -- HLD: budget is optional
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL,
    CONSTRAINT chk_weekly_target_pos CHECK (weekly_target > 0),
    CONSTRAINT chk_tolerance_nonneg  CHECK (tolerance_over >= 0)
);
CREATE UNIQUE INDEX idx_prov_budget_user ON provision_budget (user_id);
```

Spend tracking (rolling 4-week average, current-week actual) is **derived** on demand, not stored — avoids a denormalised counter that drifts. **Worth user review** — could materialise if dashboards become slow.

### V20260502120300 — Supplier products

```sql
CREATE TABLE provision_supplier_products (
    id uuid PRIMARY KEY,
    product_id varchar(128) NOT NULL,                            -- supplier-native SKU
    supplier varchar(32) NOT NULL,
    name varchar(255) NOT NULL,
    price numeric(8,2), price_per_unit numeric(8,4), unit varchar(16),
    pack_size_g integer, pack_size_unit varchar(16),
    category varchar(64), clubcard_price numeric(8,2),
    last_checked date NOT NULL,
    substitution_history jsonb NOT NULL DEFAULT '[]'::jsonb,     -- list of {date, substituted_with, accepted}
    ingredient_mapping_key varchar(128),
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL,
    UNIQUE (supplier, product_id)
);
-- Lookup by ingredient mapping key during shopping-list cost estimation.
CREATE INDEX idx_prov_supplier_products_mapping_key ON provision_supplier_products (ingredient_mapping_key);
-- Staleness scan: > 2w flag, > 4w exclude (relaxed during 8-week ramp-up per HLD).
CREATE INDEX idx_prov_supplier_products_last_checked ON provision_supplier_products (last_checked);
```

`substitution_history` is the only JSONB column — append-only, read whole, qualifies under the style guide's JSONB rule.

### V20260502120400 — Waste log

```sql
CREATE TABLE provision_waste_log (
    id uuid PRIMARY KEY, user_id uuid NOT NULL,
    inventory_item_id uuid,                                      -- nullable: HLD allows free-form when tracking off
    item_name varchar(128) NOT NULL,                             -- denormalised at point of waste (immutable per HLD)
    quantity numeric(10,3), unit varchar(16),
    reason varchar(32) NOT NULL,                                 -- expired | leftover_not_eaten | didnt_like | spoiled_early | made_too_much
    cost_estimate numeric(8,2),
    occurred_on date NOT NULL, notes varchar(255),
    created_at timestamptz NOT NULL                              -- no updated_at, no @Version: append-only
);
CREATE INDEX idx_prov_waste_log_user_date   ON provision_waste_log (user_id, occurred_on DESC);
CREATE INDEX idx_prov_waste_log_user_reason ON provision_waste_log (user_id, reason);
```

### V20260502120500 — Inventory audit

```sql
CREATE TABLE provision_inventory_audit (
    id uuid PRIMARY KEY,
    inventory_item_id uuid NOT NULL,                             -- soft FK
    user_id uuid NOT NULL,
    actor varchar(32) NOT NULL,                                  -- user | cook_event | grocery_import | nutrition_logger | system
    actor_user_id uuid,
    field_changed varchar(64) NOT NULL,
    previous_value_json jsonb NOT NULL, new_value_json jsonb NOT NULL,
    occurred_at timestamptz NOT NULL
);
CREATE INDEX idx_prov_inventory_audit_item_time ON provision_inventory_audit (inventory_item_id, occurred_at DESC);
```

Per-item audit; the HLD doesn't mandate this. **Adding here, worth user review** — needed to defend automated cook-event and grocery-import mutations to the user.

### R__provision_seed_equipment_catalogue.sql

Repeatable migration seeding canonical equipment names per [provision-model.md §Equipment](../design/provision-model.md#equipment): oven, hob, microwave, air_fryer, slow_cooker, blender, food_processor, grill, bbq, rice_cooker, stand_mixer, pressure_cooker, kettle, toaster, dishwasher.

---

## Entities

All entities follow the style guide: UUID `@Id` set application-side, `@Version` on every mutable aggregate root, `@CreatedDate` / `@LastModifiedDate` audit columns, Lombok `@Getter @Setter @Builder @NoArgsConstructor(access = PROTECTED) @AllArgsConstructor`. JSONB via `@Type(JsonType.class)` from `hypersistence-utils`.

| Entity | Notes |
|---|---|
| `InventoryItem` | Aggregate root. Single table for both tracking modes; `trackingMode` discriminator. `itemStatus` lifecycle enum. Freezer fields nullable. `@Version Long version`. `@PrePersist` validates the tracking-mode invariant alongside the DB check constraint. |
| `Equipment` | Aggregate root. Unique `(userId, name)`. `@Version Long version`. |
| `Budget` | Aggregate root. One per user. `@Version Long version`. `enabled` flag captures the HLD's "budget is optional" rule. |
| `SupplierProduct` | Aggregate root. `substitutionHistory` mapped to `List<SubstitutionRecord>` via JSONB. Unique `(supplier, productId)`. `@Version Long version`. |
| `WasteEntry` | Append-only. No `@Version`, no `@LastModifiedDate`. `inventoryItemId` nullable. `reason` enum. |
| `InventoryAuditLog` | Append-only. `actor` enum. JSONB before/after as `JsonNode`. |

Enums: `StorageLocation` (`FRIDGE`, `FREEZER`, `CUPBOARD`, `SPICE_RACK`); `TrackingMode` (`QUANTITY`, `STATUS`); `StapleStatus` (`STOCKED`, `LOW`, `OUT`); `ItemSource` (`TESCO_ORDER`, `OTHER_SHOP`, `MANUAL_ADD`, `BATCH_COOK`, `GIFT`); `ItemLifecycleStatus` (`ACTIVE`, `EXHAUSTED`, `SPOILED`, `WASTED`); `WasteReason` (`EXPIRED`, `LEFTOVER_NOT_EATEN`, `DIDNT_LIKE`, `SPOILED_EARLY`, `MADE_TOO_MUCH`); `DefrostMethod` (`OVERNIGHT_FRIDGE`, `ROOM_TEMP`, `MICROWAVE`, `QUICK_DEFROST`); `PriceSensitivity` (`LOW`, `MODERATE`, `HIGH`); `AuditActor` (`USER`, `COOK_EVENT`, `GROCERY_IMPORT`, `NUTRITION_LOGGER`, `SYSTEM`).

`SubstitutionRecord` JSONB inner shape: `record SubstitutionRecord(LocalDate date, String substitutedWithProductId, boolean accepted, String notes) {}`.

---

## DTOs

All DTOs are Java records. One response DTO per entity, mirroring entity fields with idiomatic types — `InventoryItemDto`, `EquipmentDto`, `BudgetDto`, `SupplierProductDto`, `WasteEntryDto`. Distinctive shapes:

```java
public record InventoryItemDto(
    UUID id, UUID userId, String name, String category,
    StorageLocation storageLocation, TrackingMode trackingMode,
    BigDecimal quantity, String unit, BigDecimal costPaid,
    StapleStatus status, boolean isStaple,
    LocalDate expiryDate, String ingredientMappingKey, String notes,
    ItemSource source, String sourceRef, ItemLifecycleStatus itemStatus,
    FreezerExtensionDto freezerExtension, long version
) {}

public record FreezerExtensionDto(
    LocalDate frozenAt, Integer maxFreezeWeeks,
    DefrostMethod defrostMethod, Integer defrostLeadTimeHours, UUID sourceRecipeId
) {}

public record BudgetDto(
    UUID id, UUID userId, BigDecimal weeklyTarget, String currency,
    BigDecimal toleranceOver, PriceSensitivity priceSensitivity, boolean enabled,
    BudgetSpendTrackingDto spendTracking, long version
) {}

public record BudgetSpendTrackingDto(
    BigDecimal currentWeekTarget, BigDecimal currentWeekActual, BigDecimal currentWeekRemaining,
    List<BudgetOrderRefDto> currentWeekOrders, BigDecimal rollingFourWeekAverage
) {}

public record WasteSummaryDto(
    LocalDate periodStart, LocalDate periodEnd, BigDecimal totalCost,
    Map<WasteReason, Integer> countByReason, List<TopWastedItemDto> topItems
) {}

// Planner-facing bundle — one round trip per planning run.
public record ProvisionForPlannerBundleDto(
    UUID userId,
    List<InventoryItemDto> activeInventory,
    List<InventoryItemDto> staplesAtLowOrOut,
    List<EquipmentDto> equipment,
    BudgetDto budget,
    Map<String, SupplierProductDto> supplierPricesByMappingKey,
    BundleStaleness staleness
) {}

public record BundleStaleness(
    int supplierCacheCoverageBps,                                // 0..10000 — share of mapping keys with fresh prices
    boolean inRampUpWindow,                                      // first 8 weeks per HLD
    Instant generatedAt
) {}
```

Request records carry standard Jakarta validation: `CreateInventoryItemRequest` (`@NotBlank` name and category, `@NotNull` storage location, source, tracking mode; `@ValidQuantity` quantity; `@Valid` freezer extension); `UpdateInventoryItemRequest` and `AdjustInventoryQuantityRequest` carry an `expectedVersion long` for optimistic locking; `UpsertEquipmentRequest` (`@NotBlank` name, `Long expectedVersion` nullable for create vs update); `UpdateBudgetRequest` (`@DecimalMin(0.0, exclusive)` weekly target, `@Size(3,3)` currency, `expectedVersion`); `LogWasteRequest` (`@NotNull @PastOrPresent` occurredOn, `@NotNull` reason, optional `inventoryItemId`); `UpsertSupplierProductRequest`. Search criteria: `InventorySearchCriteria(StorageLocation storageLocation, Boolean isStaple, Boolean expiringSoon, Integer expiringWithinDays, String categoryEquals, String ingredientMappingKey)`.

---

## Mappers

MapStruct, `@Mapper(componentModel = "spring")`, one per entity-DTO pair: `InventoryItemMapper`, `EquipmentMapper`, `BudgetMapper`, `SupplierProductMapper`, `WasteEntryMapper`. Custom mapping on `InventoryItemMapper` collapses freezer-extension fields into `FreezerExtensionDto` when `storageLocation = FREEZER`. Spend-tracking on `BudgetMapper` is service-populated before mapping (derived on demand).

---

## Repositories

Package-private. Cross-module access goes through service interfaces only. Every batch-relevant method has a `findAllByIdIn` / `findByUserIdIn` sibling per the style guide.

```java
interface InventoryItemRepository extends JpaRepository<InventoryItem, UUID> {
    List<InventoryItem> findAllByUserIdAndItemStatus(UUID userId, ItemLifecycleStatus status);
    Page<InventoryItem> findAllByUserIdAndItemStatus(UUID userId, ItemLifecycleStatus status, Pageable p);
    List<InventoryItem> findAllByIdIn(List<UUID> ids);
    boolean existsByUserIdAndSourceAndSourceRef(UUID userId, ItemSource source, String sourceRef);
    List<InventoryItem> findAllByUserIdAndIsStapleTrueAndStatusIn(UUID userId, Collection<StapleStatus> statuses);

    @Query("""
        select i from InventoryItem i
        where i.userId = :userId and i.itemStatus = 'ACTIVE'
          and i.expiryDate is not null and i.expiryDate <= :cutoff
        """)
    List<InventoryItem> findExpiringByUserId(@Param("userId") UUID userId, @Param("cutoff") LocalDate cutoff);

    // Cross-user sweep for the daily ItemNearingExpiryEvent scheduled job.
    @Query("""
        select i from InventoryItem i
        where i.itemStatus = 'ACTIVE' and i.expiryDate is not null and i.expiryDate <= :cutoff
        """)
    List<InventoryItem> findAllExpiringBefore(@Param("cutoff") LocalDate cutoff);

    // FIFO-by-expiry deduction. A single mapping key may have multiple active rows.
    @Query("""
        select i from InventoryItem i
        where i.userId = :userId and i.itemStatus = 'ACTIVE' and i.ingredientMappingKey = :key
        order by coalesce(i.expiryDate, date '9999-12-31') asc
        """)
    List<InventoryItem> findActiveByMappingKey(@Param("userId") UUID userId, @Param("key") String key);
}
```

The other repositories follow standard Spring Data shape:
- `EquipmentRepository` — `findAllByUserId`, `findAllByUserIdAndAvailableTrue`, `findAllByUserIdIn`, `findByUserIdAndName`.
- `BudgetRepository` — `findByUserId`, `findByUserIdIn`.
- `SupplierProductRepository` — `findBySupplierAndProductId`, `findAllByIngredientMappingKeyIn`, `findAllByLastCheckedBefore` (paginated).
- `WasteEntryRepository` — `findAllByUserIdOrderByOccurredOnDesc` paginated, plus a `@Query` aggregating count + cost-sum grouped by `reason` for the summary endpoint.
- `InventoryAuditLogRepository` — `findByInventoryItemIdOrderByOccurredAtDesc` paginated.

---

## Service Interfaces

A single `ProvisionServiceImpl` implements all three. `ProvisionForPlannerService` is split out so the planner injects only the bundle method — narrower API, narrower coupling, opportunity to layer caching there independently.

### `ProvisionQueryService`

```java
public interface ProvisionQueryService {
    // Inventory
    Optional<InventoryItemDto> getInventoryItem(UUID itemId);
    List<InventoryItemDto> getInventoryItemsByIds(List<UUID> itemIds);
    Page<InventoryItemDto> searchInventory(UUID userId, InventorySearchCriteria criteria, Pageable pageable);
    List<InventoryItemDto> getActiveInventory(UUID userId);
    List<InventoryItemDto> getActiveInventoryByUserIds(List<UUID> userIds);
    List<InventoryItemDto> getExpiringSoon(UUID userId, int withinDays);

    // Staples — surfaced explicitly because the shopping-list calculation needs them.
    List<InventoryItemDto> getAvailableStaples(UUID userId);
    List<InventoryItemDto> getStaplesNeedingReplenishment(UUID userId);

    // Equipment / budget / supplier / waste / audit reads
    List<EquipmentDto> getEquipment(UUID userId);
    List<EquipmentDto> getAvailableEquipment(UUID userId);
    List<EquipmentDto> getEquipmentByUserIds(List<UUID> userIds);
    Optional<BudgetDto> getBudget(UUID userId);
    List<BudgetDto> getBudgetsByUserIds(List<UUID> userIds);
    Optional<SupplierProductDto> getSupplierProductByMappingKey(String key);
    Map<String, SupplierProductDto> getSupplierProductsByMappingKeys(Collection<String> keys);
    Page<SupplierProductDto> getStaleSupplierProducts(LocalDate cutoff, Pageable p);
    Page<WasteEntryDto> getWasteEntries(UUID userId, Pageable p);
    WasteSummaryDto getWasteSummary(UUID userId, LocalDate from, LocalDate to);
    Page<InventoryAuditEntryDto> getInventoryAuditLog(UUID inventoryItemId, Pageable p);
}
```

### `ProvisionUpdateService`

```java
public interface ProvisionUpdateService {
    // Inventory CRUD
    InventoryItemDto createInventoryItem(UUID userId, CreateInventoryItemRequest request, AuditActor actor);
    InventoryItemDto updateInventoryItem(UUID itemId, UpdateInventoryItemRequest request, UUID actorUserId);
    InventoryItemDto adjustQuantity(UUID itemId, AdjustInventoryQuantityRequest request, UUID actorUserId);
    void markExhausted(UUID itemId, UUID actorUserId);
    void markSpoiled(UUID itemId, UUID actorUserId);                            // "the chicken's gone off"

    // Cook & consumption (called by Planner / Nutrition Logger via events; in-process)
    InventoryDeductionResultDto applyCookEvent(UUID userId, CookEventCommand command);
    InventoryDeductionResultDto applyMealConsumption(UUID userId, MealConsumptionCommand command);
    InventoryItemDto applyStandaloneConsumption(UUID userId, StandaloneConsumptionCommand command);

    // Grocery (called by Grocery module after order confirmation)
    GroceryImportResultDto applyGroceryOrder(UUID userId, GroceryOrderImportCommand command);

    // Equipment, budget, supplier products
    EquipmentDto upsertEquipment(UUID userId, UpsertEquipmentRequest request);
    void deleteEquipment(UUID userId, String name);
    BudgetDto initialiseBudget(UUID userId, UpdateBudgetRequest request);
    BudgetDto updateBudget(UUID userId, UpdateBudgetRequest request);
    SupplierProductDto upsertSupplierProduct(UpsertSupplierProductRequest request);
    SupplierProductDto recordSubstitution(UUID supplierProductId, SubstitutionRecordDto record, boolean userAccepted);

    // Waste — immutable per HLD; corrections create a new entry
    WasteEntryDto logWaste(UUID userId, LogWasteRequest request);

    // Feedback dispatch (called by Feedback System with pre-classified intent)
    void applyFeedback(UUID userId, ProvisionsFeedbackCommand command);
}
```

Command records (in-process, kept separate from REST request records):

```java
public record CookEventCommand(
    UUID recipeId, UUID planId, UUID mealSlotId,
    int servingsCooked, boolean isBatchCook, BigDecimal proportionOfRecipe,
    List<RecipeIngredientUsage> ingredientsUsed,                    // recipe ingredient list at cook time
    BatchCookSplitDirective batchSplit,                             // optional fridge/freezer split
    UUID traceId
) {}
public record RecipeIngredientUsage(String ingredientMappingKey, BigDecimal quantity, String unit) {}
public record BatchCookSplitDirective(int fridgePortions, int freezerPortions, Integer fridgeMaxDays, Integer freezerMaxWeeks) {}
public record GroceryOrderImportCommand(
    String supplier, String orderRef, LocalDate deliveredOn,
    List<GroceryOrderLine> lines, List<GroceryOrderSubstitution> substitutions, UUID traceId
) {}
public record GroceryOrderLine(String productId, String name, String ingredientMappingKey,
    BigDecimal quantity, String unit, BigDecimal pricePaid, String category, Integer packSizeG) {}
public record GroceryOrderSubstitution(String orderedProductId, String substitutedProductId, String reason) {}
```

Other commands are simple wrappers: `MealConsumptionCommand(inventoryItemId, portions, traceId)`; `StandaloneConsumptionCommand(ingredientMappingKey, quantity, unit, userConfirmedDeduction, traceId)`; `ProvisionsFeedbackCommand(type, freeTextNote, structured)` where `type` is `COST_CONCERN | AVAILABILITY | EQUIPMENT | SHELF_LIFE | WASTE_OBSERVATION`. Result records: `InventoryDeductionResultDto(updatedItems, exhaustedItems, underflows)`, `UnderflowFlagDto(ingredientMappingKey, requested, available)`, `GroceryImportResultDto(addedItems, mergedItems, updatedSupplierProducts, warnings)`.

### `ProvisionForPlannerService`

```java
public interface ProvisionForPlannerService {
    ProvisionForPlannerBundleDto getBundle(UUID userId);
    List<ProvisionForPlannerBundleDto> getBundlesByUserIds(List<UUID> userIds);
}
```

Bundle contents: active inventory, staples needing replenishment, available equipment, budget, and a price map keyed by mapping keys appearing in inventory and shopping-list inputs. The planner consumes this once per planning run for the `provisions` sub-score per [meal-planner.md §Scoring function](../design/meal-planner.md#scoring-function). **Worth user review** — the HLD does not specify this aggregation; added in line with the pilot precedent.

---

## REST Controllers

Multi-controller split per the pilot precedent. All endpoints under `/api/v1/provisions/...`. `userId` resolved server-side from auth context per [technical-architecture.md §Frontend-Backend Contract](../design/technical-architecture.md#frontend-backend-contract). OpenAPI: `@Tag(name = "Provisions — <resource>")` per controller, `@Operation` per handler.

| Controller | Resource |
|---|---|
| `InventoryController` | `/api/v1/provisions/inventory` |
| `EquipmentController` | `/api/v1/provisions/equipment` |
| `BudgetController` | `/api/v1/provisions/budget` |
| `WasteController` | `/api/v1/provisions/waste` |
| `SupplierProductsController` | `/api/v1/provisions/supplier-products` |

| Method | Path | Request | Response | Status |
|---|---|---|---|---|
| GET    | `/inventory?storageLocation=&isStaple=&expiringSoon=&page=&size=` | — | `Page<InventoryItemDto>` | 200 |
| GET    | `/inventory/{itemId}` | — | `InventoryItemDto` | 200 / 404 |
| POST   | `/inventory` | `CreateInventoryItemRequest` | `InventoryItemDto` | 201 / 400 |
| PUT    | `/inventory/{itemId}` | `UpdateInventoryItemRequest` | `InventoryItemDto` | 200 / 400 / 404 / 409 |
| PATCH  | `/inventory/{itemId}/quantity` | `AdjustInventoryQuantityRequest` | `InventoryItemDto` | 200 / 400 / 404 / 409 |
| POST   | `/inventory/{itemId}/mark-spoiled` | — | `InventoryItemDto` | 200 / 404 |
| DELETE | `/inventory/{itemId}` | — | — | 204 / 404 |
| GET    | `/inventory/{itemId}/audit-log` | — | `Page<InventoryAuditEntryDto>` | 200 |
| GET    | `/staples?status=` | — | `List<InventoryItemDto>` | 200 |
| GET    | `/equipment` | — | `List<EquipmentDto>` | 200 |
| PUT    | `/equipment/{name}` | `UpsertEquipmentRequest` | `EquipmentDto` | 200 |
| DELETE | `/equipment/{name}` | — | — | 204 |
| GET    | `/budget` | — | `BudgetDto` | 200 / 404 |
| PUT    | `/budget` | `UpdateBudgetRequest` | `BudgetDto` | 200 / 400 / 409 |
| GET    | `/supplier-products?mappingKey=&supplier=` | — | `Page<SupplierProductDto>` | 200 |
| POST   | `/waste` | `LogWasteRequest` | `WasteEntryDto` | 201 / 400 / 422 |
| GET    | `/waste` | — | `Page<WasteEntryDto>` | 200 |
| GET    | `/waste/summary?from=&to=` | — | `WasteSummaryDto` | 200 |
| GET    | `/planner-bundle` | — | `ProvisionForPlannerBundleDto` | 200 |

Cook-event, meal-consumption, standalone-consumption, and grocery-import flows are **not exposed** via REST — they're invoked in-process via direct service injection (matching the pilot's pattern of in-process methods kept off the HTTP boundary).

### Error responses

RFC 9457 ProblemDetail. Module-specific exceptions handled in the project-wide `GlobalExceptionHandler`:

| Exception | Status | `type` URI |
|---|---|---|
| `InventoryItemNotFoundException` | 404 | `.../inventory-item-not-found` |
| `EquipmentNotFoundException` | 404 | `.../equipment-not-found` |
| `BudgetNotFoundException` | 404 | `.../budget-not-found` |
| `SupplierProductNotFoundException` | 404 | `.../supplier-product-not-found` |
| `InvalidInventoryQuantityException` | 400 | `.../invalid-inventory-quantity` |
| `InventoryUnderflowException` | 422 | `.../inventory-underflow` |
| `WasteExceedsInventoryException` | 422 | `.../waste-exceeds-inventory` |
| `DuplicateGroceryImportException` | 409 | `.../duplicate-grocery-import` |
| `OptimisticLockException` (JPA) | 409 | `.../optimistic-lock` |
| `MethodArgumentNotValidException` | 400 | `errors[]` extension |

`InventoryUnderflowException` is a 422 only when the caller declares strict mode on a cook-event command. The default flow floors at zero and returns `UnderflowFlagDto`s — see Flow 1.

Module root: `ProvisionsException extends MealPrepException`.

---

## Validation

Standard Jakarta annotations on request records.

Custom validators in `validation/`:
- **`@ValidQuantity`** on `BigDecimal` fields — non-negative when present, scale ≤ 3, magnitude ≤ 1,000,000 (catches unit confusion). Null is valid (status-tracked items).
- **`@ValidStorageLocation`** class-level on `CreateInventoryItemRequest` — `SPICE_RACK` requires `STATUS` tracking, the others require `QUANTITY`. `freezerExtension` non-null iff `storageLocation = FREEZER`.
- **`@ValidWasteQuantity`** class-level on `LogWasteRequest` — shape only; the HLD's "waste ≤ inventory" rule needs a DB lookup and is enforced service-side.

**Cross-resource validation is service-layer.** Waste-quantity vs inventory rule throws `WasteExceedsInventoryException` (422) when tracking is active. Cook-event deduction floors underflows and surfaces them as `UnderflowFlagDto` rather than throwing. Grocery import idempotency on `(userId, source, sourceRef)` throws `DuplicateGroceryImportException` (409).

Validation failures bubble up as `MethodArgumentNotValidException` mapped to 400 ProblemDetails by the global advice.

---

## Events

### Published

`ProvisionChangedEvent` is a sealed interface — one record per change kind — so the planner's listener can `switch` exhaustively. All variants carry `userId`, `List<UUID> affectedItemIds`, `UUID traceId`, `Instant occurredAt`. Variants:

```java
public sealed interface ProvisionChangedEvent permits
    ItemAddedFromGroceryEvent,        // payload: supplier, orderRef
    ItemSpoiledEvent,                  // payload: reason
    ItemRanOutEvent,                   // payload: ingredientMappingKey, wasStaple
    SubstitutionAcceptedEvent,         // payload: orderedProductId, substitutedProductId
    ItemQuantityAdjustedEvent,         // payload: ItemAdjustmentSource (COOK_EVENT | MEAL_CONSUMPTION | MANUAL | STANDALONE_LOG | WASTE)
    GenericProvisionChangedEvent { ... }
```

Single-event-per-operation contract preserved — even when grocery import adds 15 items, exactly one `ItemAddedFromGroceryEvent` carries all 15 IDs. The technical-architecture catalogue lists `ProvisionChangedEvent` with a `changeType` enum; the sealed-interface upgrade is **worth user review**.

Sibling events (split from `ProvisionChangedEvent` so the planner can use different invalidation strategies — equipment invalidates recipe-feasibility caches, budget invalidates cost-estimate caches; not in the technical-architecture catalogue, **worth user review**):

```java
public record EquipmentChangedEvent(UUID userId, String equipmentName, boolean nowAvailable,
                                    UUID traceId, Instant occurredAt) {}
public record BudgetChangedEvent(UUID userId, BigDecimal previousWeeklyTarget, BigDecimal newWeeklyTarget,
                                 PriceSensitivity newPriceSensitivity, UUID traceId, Instant occurredAt) {}
public record ItemNearingExpiryEvent(UUID userId, UUID itemId, String itemName, LocalDate expiryDate,
                                     int daysUntilExpiry, StorageLocation storageLocation,
                                     UUID traceId, Instant occurredAt) {}
```

`ItemNearingExpiryEvent` is generated by a `@Scheduled` daily sweep, not at write time — expiry-approaching is a function of *the calendar*. Configurable threshold under `mealprep.provisions.expiry-alert.*`, defaulting to 2 days fridge / 14 days freezer per HLD.

Published via `ApplicationEventPublisher` after the relevant write transaction. Listeners use `@TransactionalEventListener(phase = AFTER_COMMIT)` per the style guide.

### Consumed

```java
@TransactionalEventListener(phase = AFTER_COMMIT) public void onMealCooked(MealCookedEvent event) { ... }
@TransactionalEventListener(phase = AFTER_COMMIT) public void onMealConsumed(MealConsumedEvent event) { ... }
@TransactionalEventListener(phase = AFTER_COMMIT) public void onGroceryOrderConfirmed(GroceryOrderConfirmedEvent event) { ... }
```

These events are defined in technical-architecture's catalogue; this module is a listener. Each listener is a thin adapter — constructs a command record from the event payload (fetching detail via the publishing module's query service) and delegates to `ProvisionUpdateService`. `GroceryOrderConfirmedEvent` is the contract this module *expects from the grocery module* — see Out of Scope for the gap.

---

## Business Logic Flows

### Flow 1: Cook-event deduction

`MealCookedEvent` arrives via the listener. The listener fetches the recipe ingredient list from `RecipeQueryService` (events carry IDs, listeners fetch state) and calls `applyCookEvent`. `@Transactional`.

1. **Apply proportion.** Multiply each requested quantity by `proportionOfRecipe` (default 1.0; 0.5 for "I halved it").
2. **Per ingredient:** `InventoryDeductionEngine.deduct(userId, key, requested, unit)` loads `findActiveByMappingKey` (FIFO by expiry — uses oldest first), walks rows decrementing. Unit conversion via a `UnitConverter` helper; unsupported conversions log WARN and emit underflow flags rather than throw. When a row hits zero, set `itemStatus = EXHAUSTED`.
3. **Floor at zero** when requested exceeds total available; emit `UnderflowFlagDto`. Per HLD guardrail.
4. **Batch cook.** If `isBatchCook`, `BatchCookSplitter` creates one fridge row (`storageLocation = FRIDGE`, `quantity = fridgePortions`, `unit = "portions"`, `expiry_date = today + maxFridgeDays`) and one freezer row with full freezer extension. `source = BATCH_COOK`, `source_recipe_id = recipeId`.
5. **Audit.** One row per affected item, `actor = COOK_EVENT`.
6. **Event coalescing.** A single `ItemQuantityAdjustedEvent` after commit with all touched IDs, via `ProvisionEventBatcher`. An additional `ItemRanOutEvent` if any deducted item was a staple that transitioned to `OUT`.
7. Returns `InventoryDeductionResultDto`. UI renders the "Removed from pantry: ..." confirmation.

**Concurrency.** Two cook events on the same row both load `version=N`, second commit gets `OptimisticLockException` → 409 → caller retries (Resilience4j `@Retry`, max 3 attempts) with the fresh state.

**Idempotency (locked 2026-05-07).** A duplicate `MealCookedEvent` for the same `mealSlotId` is treated as the same cook, not a second one. Mechanism:

- New table `provision_cook_event_dedupe` with primary key `(meal_slot_id, dedupe_key)` and a `created_at` column. `dedupe_key` defaults to a hash of `(mealSlotId, recipeId, servingsCooked, isBatchCook)` — caller may override.
- Listener checks for an existing row before applying; if present, no-op and log INFO.
- Otherwise insert + apply the deduction in the same transaction.
- Daily sweep deletes rows older than 24h — bounded storage; the dedupe window is short because legitimate "second cook of the same recipe" is a separate `mealSlotId`.

This costs one indexed lookup per cook event and prevents replay-driven double-deduction.

### Flow 2: Grocery order import (idempotent, partial-success-tolerant)

`GroceryOrderConfirmedEvent` arrives. Listener fetches the order from the grocery module and calls `applyGroceryOrder`. `@Transactional`.

1. **Idempotency.** `existsByUserIdAndSourceAndSourceRef` — if true, `DuplicateGroceryImportException` (409). Replays must be no-ops.
2. **Per `GroceryOrderLine`:** upsert `SupplierProduct` (refresh `price`, `pricePerUnit`, `lastChecked`, `clubcardPrice`); infer storage location from `category`; infer expiry via `ExpiryInferenceService` (rule data deferred); then create or merge inventory row using **expiry-aware merge**:

   - Merge into an existing row only when `(userId, ingredientMappingKey, storageLocation, expiryDate)` all match the existing row (or both `expiryDate` are null). Same delivery in two slots merges; deliveries weeks apart with different expiries do not.
   - Otherwise create a new row.

   This preserves FIFO-by-expiry semantics for the deduction flow (Flow 1) without paying a per-delivery row-explosion cost when expiries genuinely match. **Locked decision (2026-05-07)**.
3. **Substitutions.** Append a `SubstitutionRecord` to the substituted product's history JSONB. The inventory row is created against the *substituted* product (what arrived).
4. **Audit + event coalescing.** One audit row per inventory create/merge, `actor = GROCERY_IMPORT`. One `ItemAddedFromGroceryEvent` after commit.
5. **Partial failure.** The grocery module owns "of 5 ordered, 3 arrived"; by the time the command reaches us, those 3 are what the user has. The import is atomic on this side. **The grocery LLD must specify how partial deliveries map to the command** — see Out of Scope.

### Flow 3: Standalone consumption (snack from inventory)

The Nutrition Logger's standalone food logging calls `applyStandaloneConsumption` when the logged item matches an active inventory row by `ingredientMappingKey` (per [provision-model.md §Inventory updates](../design/provision-model.md#inventory-updates)).

1. Find active inventory rows by `(userId, ingredientMappingKey)`. None → return without error (unrelated logged item).
2. If `userConfirmedDeduction == true`, deduct `quantity` from the oldest-expiry row, floor at zero. Audit `actor = NUTRITION_LOGGER`.
3. Publish `ItemQuantityAdjustedEvent` with `source = STANDALONE_LOG`. The planner's listener filters: standalone-log events do not trigger re-optimisation (small-amount snack consumption is below the materiality threshold per [meal-planner.md §Re-optimisation triggers](../design/meal-planner.md#re-optimisation-triggers)).

### Flow 4: Manual edits, corrections, mark-spoiled

`PUT /inventory/{itemId}` with `expectedVersion` is `@Transactional`. Loads, diff-applies, audit `actor = USER`, publishes `ItemQuantityAdjustedEvent` with `source = MANUAL`. Stale version → 409.

`POST /inventory/{itemId}/mark-spoiled` sets `itemStatus = SPOILED`, publishes `ItemSpoiledEvent`. The "the chicken's gone off" feedback path (per [feedback-system.md](../design/feedback-system.md)) routes here via `applyFeedback` → `markSpoiled`. The planner reacts by offering re-optimisation of any meal slot scheduled to use the spoiled ingredient.

### Flow 5: Waste logging

`POST /waste`, `@Transactional`:
1. **Validate** quantity vs remaining inventory when tracking active. Otherwise unconstrained per HLD.
2. **Persist** the waste entry (immutable: no `@Version`, no update path).
3. **Deduct** the wasted quantity from the linked inventory row, audit `actor = USER`, floor at zero, mark `WASTED` if exhausted.
4. **Publish** `ItemQuantityAdjustedEvent` with `source = WASTE`.

### Flow 6: Staple low/out → next shopping list

`StapleStateTransitioner` enforces legal transitions: `STOCKED → LOW → OUT → STOCKED` (replenishment via grocery import sets `STOCKED`). When a staple transitions to `LOW` or `OUT`, an `ItemRanOutEvent` is published (using `wasStaple = true`). The shopping-list calculation in the Planner module (per [provision-model.md §Shopping List Calculation](../design/provision-model.md#shopping-list-calculation)) reads `getStaplesNeedingReplenishment(userId)` — the algorithm itself is not implemented here.

### Flow 7: Supplier price cache update and staleness

`SupplierProductsController` exposes `GET` only; writes happen via grocery import or the dedicated `upsertSupplierProduct` (used when the grocery module refreshes prices without an order). The HLD's [Supplier cache guardrails](../design/provision-model.md#guardrails) (> 2 weeks flagged, > 4 weeks excluded, with the 8-week ramp-up exception) are enforced **in `getBundle`**: `BundleStaleness.inRampUpWindow` is true for the first 8 weeks (computed from the user's earliest grocery import date), and the price map only excludes prices older than 4 weeks once ramp-up is over. **Worth user review** — the HLD does not specify where the ramp-up window is anchored.

### Flow 8: Expiry sweep

`@Scheduled(cron = "0 0 6 * * *")` (configurable). Calls `findAllExpiringBefore(today + maxThreshold)`, partitions by storage location, applies per-location threshold (default 2 days fridge, 14 days freezer), publishes one `ItemNearingExpiryEvent` per matching item. Notification fans out from there.

---

## Concurrency and Transactions

| Concern | Decision |
|---|---|
| `@Transactional` placement | All service-impl methods. Repositories never. |
| Read methods | `@Transactional(readOnly = true)`. |
| Write methods | Default REQUIRED. `applyCookEvent`, `applyGroceryOrder`, `applyMealConsumption`, `applyStandaloneConsumption` are top-level (invoked from event listeners, themselves AFTER_COMMIT). |
| Optimistic locking | `@Version` on `InventoryItem`, `Equipment`, `Budget`, `SupplierProduct`. Append-only logs (`WasteEntry`, `InventoryAuditLog`) — no `@Version`. |
| Pessimistic locking | None for v1. Concurrent same-item cook events resolve via `@Version` and listener-side retry. |
| Concurrent cook events on same item | Both load `version=N`, second commit raises `OptimisticLockException` → caught by Resilience4j `@Retry` (max 3) → re-fetch and re-apply. |
| Cook-event idempotency | `provision_cook_event_dedupe (meal_slot_id, dedupe_key)` PK gates the listener; duplicate `MealCookedEvent` for the same `(mealSlotId, dedupe_key)` is a no-op. Daily sweep removes rows >24h old. |
| Grocery import partial success | Atomic on this side. Idempotent on `(userId, source, sourceRef)`. **Open**: how the grocery module signals an "amend" (late-delivered line). Not in HLD — flagged in Out of Scope. |
| Standalone consumption races | Standalone log + cook event for the same ingredient may collide; standalone retries up to 3 times then drops silently (cook event is the larger and more important update). |
| Audit row writes | Same transaction as the inventory write — failed audit rolls back the inventory write. |
| `ProvisionEventBatcher` | Per-tx state via `TransactionSynchronizationManager`; flushes one event per kind at AFTER_COMMIT with collected `affectedItemIds`. |

---

## Test Plan

Unit tests use `@ExtendWith(MockitoExtension.class)`. Integration tests are `*IT.java` with Testcontainers Postgres. Names follow `methodName_scenario_expected`.

### Unit

| Class | Verifies |
|---|---|
| `ProvisionServiceImplTest` | Query and update happy paths plus error mappings, with mocked repositories and mocked internal helpers. |
| `InventoryDeductionEngineTest` | FIFO-by-expiry deduction, multi-row deduction within one mapping key, exact-zero exhaustion, underflow flag generation, unit-conversion success and unsupported-conversion warn. |
| `BatchCookSplitterTest` | Default split, explicit override, zero-fridge case, all-to-freezer case. |
| `ExpiryInferenceServiceTest` | Rule registry dispatch with stub rules, null when no rule matches, freezer derives expiry from `frozen_at + max_freeze_weeks`. |
| `StapleStateTransitionerTest` | Legal transitions, illegal transitions blocked. |
| `GroceryImportProcessorTest` | Idempotency, merge for non-perishables, new row for perishables, substitution-history append, supplier-product upsert. |
| `WasteValidatorTest` | Quantity ≤ inventory when tracking active, no rule when tracking off. |
| `ProvisionEventBatcherTest` | Multiple deductions in one tx → one `ItemQuantityAdjustedEvent`; mixed-kind operations → one event per kind. |
| Mapper + validator tests | MapStruct round-trips preserve fields incl. JSONB; `@ValidQuantity` / `@ValidStorageLocation` accept/reject canonical examples. |

### Integration

| Class | Verifies |
|---|---|
| `InventoryControllerIT` | Full HTTP cycle: GET (200/404), POST (201), PUT (200/400/404/409 stale), PATCH quantity, audit log pagination, ProblemDetail shape. |
| `EquipmentControllerIT`, `BudgetControllerIT`, `WasteControllerIT`, `SupplierProductsControllerIT` | Per-resource happy paths, validation rejection, version conflict, immutability of waste entries. |
| `ProvisionServiceIT` | Service-layer end-to-end against real DB. Cook-event deducts FIFO-by-expiry, batch-cook adds fridge + freezer rows, grocery import is idempotent. Verifies `ProvisionEventBatcher` produces exactly one event per operation. |
| `ProvisionForPlannerServiceIT` | `getBundle` returns active inventory + staples-needing-replenishment + equipment + budget + price map in one round trip. Bundle staleness flags partial coverage and the 8-week ramp-up window correctly. Verifies ≤ 5 SQL statements via Hibernate statistics — the planner calls this once per planning run, must be cheap. |
| `ConcurrencyIT` | Two concurrent `applyCookEvent` calls on the same item: one succeeds, the other retries via Resilience4j and succeeds. Final quantity matches both deductions applied exactly once. |
| `GroceryImportIT` | Idempotent re-application of the same `(source, sourceRef)` returns 409. Substitution-history JSONB grows correctly. New mapping keys cache supplier products. |
| `ExpirySweepIT` | `@Scheduled` sweep finds items within threshold, publishes `ItemNearingExpiryEvent` per item, respects per-location threshold. |
| `WasteLoggingIT` | Logging waste deducts inventory, sets `itemStatus = WASTED` when fully consumed, exceeds-inventory rule enforced when tracking on, unconstrained when off. |
| `EventPublicationIT` | Cook event triggers exactly one `ItemQuantityAdjustedEvent` after commit regardless of rows touched. Grocery import triggers one `ItemAddedFromGroceryEvent`. A failing test-scoped listener does not roll back the underlying state. |
| `FlywayMigrationIT` | Boots Postgres, runs all provisions migrations, validates schema matches JPA mapping (`ddl-auto=validate`). |

---

## Out of Scope

Deferred deliberately:

- **The grocery module.** No grocery LLD exists yet (a known gap). This module specifies what the grocery module must call (`applyGroceryOrder`, `upsertSupplierProduct`, `recordSubstitution`) and the payload shape (`GroceryOrderImportCommand`). Grocery owns: the `GroceryProvider` interface, Tesco browser automation, partial-failure handling at the basket level, the substitution review UI, and the publication of `GroceryOrderConfirmedEvent`. Contracts enforced from this side: the event carries `userId`, `orderRef`, `supplier` (sufficient to fetch the order); the grocery module exposes a `getOrder(orderRef)` returning a payload mappable onto `GroceryOrderImportCommand`; idempotency keyed on `(userId, source, sourceRef)`; re-publication is a no-op. **Open question for the grocery LLD**: how late-delivery amends are signalled.
- **Shopping list calculation.** Owned by the Planner module per HLD. The formula `plan ingredients − inventory + staples at low/out` lives there; this module exposes the read methods (`getActiveInventory`, `getStaplesNeedingReplenishment`, `getSupplierProductsByMappingKeys`).
- **Specific expiry-date heuristic table.** The `ExpiryRule` interface and `ExpiryInferenceService` registry are defined here; v1 rule data (fresh chicken = +3 days, fresh vegetables = +5 days, dairy = +7 days, ...) is a separate concern, loadable from a YAML resource or a seed migration.
- **AI-driven anything.** This module is mechanical CRUD; feedback arrives pre-classified.
- **Frontend / UI / API consumer concerns.** Inventory editor UI, staple-toggle interaction, cook-event confirmation prompt — Figma phase, then frontend LLD.
- **Cross-module orchestration.** Re-optimisation triggered by `ProvisionChangedEvent` is the planner's concern; notification listeners are the notification LLD's concern.
- **Authentication.** Owned by the auth module.
- **Household merging.** v1 treats `user_id` as the primary household member. Multi-user write reconciliation is open per [provision-model.md §Open Questions](../design/provision-model.md#open-questions).
- **Spend-tracking materialisation.** Computed on demand for v1; materialise into `provision_spend_tracking` only if dashboards become slow.
- **Cross-recipe pack-size optimisation.** v1 picks smallest sufficient pack; future planner objective.
- **Waste-data → planner learning.** Provisions exposes `WasteSummaryDto`; the planner consumes.
- **Onboarding wizard flow.** Underlying init endpoints exist (`initialiseBudget`, `upsertEquipment`, bulk staple creation via `createInventoryItem`); the wizard composes them.
- **Multi-location provisioning.** Per HLD's [Boundaries section](../design/provision-model.md#boundaries-with-other-models): a household concern, separate Provision Model instance per location.
