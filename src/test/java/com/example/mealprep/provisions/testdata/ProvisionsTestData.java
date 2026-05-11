package com.example.mealprep.provisions.testdata;

import com.example.mealprep.provisions.api.dto.BudgetDto;
import com.example.mealprep.provisions.api.dto.BundleStaleness;
import com.example.mealprep.provisions.api.dto.CreateInventoryItemRequest;
import com.example.mealprep.provisions.api.dto.EquipmentDto;
import com.example.mealprep.provisions.api.dto.FreezerExtensionDto;
import com.example.mealprep.provisions.api.dto.InventoryItemDto;
import com.example.mealprep.provisions.api.dto.LogWasteRequest;
import com.example.mealprep.provisions.api.dto.PriceSensitivity;
import com.example.mealprep.provisions.api.dto.ProvisionForPlannerBundleDto;
import com.example.mealprep.provisions.api.dto.RecordSubstitutionRequest;
import com.example.mealprep.provisions.api.dto.SubstitutionRecordDto;
import com.example.mealprep.provisions.api.dto.SupplierProductDto;
import com.example.mealprep.provisions.api.dto.UpdateBudgetRequest;
import com.example.mealprep.provisions.api.dto.UpdateInventoryItemRequest;
import com.example.mealprep.provisions.api.dto.UpsertEquipmentRequest;
import com.example.mealprep.provisions.api.dto.UpsertSupplierProductRequest;
import com.example.mealprep.provisions.api.dto.WasteReason;
import com.example.mealprep.provisions.domain.entity.Budget;
import com.example.mealprep.provisions.domain.entity.DefrostMethod;
import com.example.mealprep.provisions.domain.entity.Equipment;
import com.example.mealprep.provisions.domain.entity.InventoryItem;
import com.example.mealprep.provisions.domain.entity.ItemLifecycleStatus;
import com.example.mealprep.provisions.domain.entity.ItemSource;
import com.example.mealprep.provisions.domain.entity.StapleStatus;
import com.example.mealprep.provisions.domain.entity.StorageLocation;
import com.example.mealprep.provisions.domain.entity.SubstitutionRecord;
import com.example.mealprep.provisions.domain.entity.SupplierProduct;
import com.example.mealprep.provisions.domain.entity.TrackingMode;
import com.example.mealprep.provisions.domain.entity.WasteEntry;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Test Data Builder for the provisions module's inventory aggregate. Defaults match the validator +
 * tracking-mode constraints so callers tweak only the field under test.
 */
public final class ProvisionsTestData {

  private ProvisionsTestData() {}

  // ---------------- Builders ----------------

  public static InventoryItem.InventoryItemBuilder quantityTrackedItem(UUID userId) {
    return InventoryItem.builder()
        .id(UUID.randomUUID())
        .userId(userId)
        .name("Cheddar")
        .category("dairy")
        .storageLocation(StorageLocation.FRIDGE)
        .trackingMode(TrackingMode.QUANTITY)
        .quantity(new BigDecimal("250.000"))
        .unit("g")
        .costPaid(new BigDecimal("3.49"))
        .isStaple(false)
        .source(ItemSource.MANUAL_ADD)
        .itemStatus(ItemLifecycleStatus.ACTIVE);
  }

  public static InventoryItem.InventoryItemBuilder statusTrackedItem(UUID userId) {
    return InventoryItem.builder()
        .id(UUID.randomUUID())
        .userId(userId)
        .name("Salt")
        .category("seasoning")
        .storageLocation(StorageLocation.SPICE_RACK)
        .trackingMode(TrackingMode.STATUS)
        .status(StapleStatus.STOCKED)
        .isStaple(true)
        .source(ItemSource.MANUAL_ADD)
        .itemStatus(ItemLifecycleStatus.ACTIVE);
  }

  public static InventoryItem.InventoryItemBuilder freezerItem(UUID userId) {
    return InventoryItem.builder()
        .id(UUID.randomUUID())
        .userId(userId)
        .name("Frozen Peas")
        .category("vegetable")
        .storageLocation(StorageLocation.FREEZER)
        .trackingMode(TrackingMode.QUANTITY)
        .quantity(new BigDecimal("500.000"))
        .unit("g")
        .isStaple(false)
        .source(ItemSource.TESCO_ORDER)
        .itemStatus(ItemLifecycleStatus.ACTIVE)
        .frozenAt(LocalDate.parse("2026-04-01"))
        .maxFreezeWeeks(12)
        .defrostMethod(DefrostMethod.OVERNIGHT_FRIDGE)
        .defrostLeadTimeHours(8);
  }

  // ---------------- Request factories ----------------

  public static CreateInventoryItemRequest createQuantityTrackedRequest() {
    return new CreateInventoryItemRequest(
        "Cheddar",
        "dairy",
        StorageLocation.FRIDGE,
        TrackingMode.QUANTITY,
        new BigDecimal("250.000"),
        "g",
        new BigDecimal("3.49"),
        null,
        false,
        LocalDate.parse("2026-06-01"),
        "cheese:cheddar",
        null,
        ItemSource.MANUAL_ADD,
        null,
        null);
  }

  public static CreateInventoryItemRequest createStatusTrackedRequest() {
    return new CreateInventoryItemRequest(
        "Salt",
        "seasoning",
        StorageLocation.SPICE_RACK,
        TrackingMode.STATUS,
        null,
        null,
        null,
        StapleStatus.STOCKED,
        true,
        null,
        null,
        null,
        ItemSource.MANUAL_ADD,
        null,
        null);
  }

  public static CreateInventoryItemRequest createFreezerRequest() {
    return new CreateInventoryItemRequest(
        "Frozen Peas",
        "vegetable",
        StorageLocation.FREEZER,
        TrackingMode.QUANTITY,
        new BigDecimal("500.000"),
        "g",
        null,
        null,
        false,
        null,
        null,
        null,
        ItemSource.TESCO_ORDER,
        null,
        new FreezerExtensionDto(
            LocalDate.parse("2026-04-01"), 12, DefrostMethod.OVERNIGHT_FRIDGE, 8, null));
  }

  public static Equipment.EquipmentBuilder equipment(UUID userId, String name) {
    return Equipment.builder()
        .id(UUID.randomUUID())
        .userId(userId)
        .name(name)
        .available(true)
        .details(null);
  }

  public static UpsertEquipmentRequest upsertEquipmentRequestForCreate() {
    return new UpsertEquipmentRequest(true, "Stainless steel, 4-burner", null);
  }

  public static UpsertEquipmentRequest upsertEquipmentRequest(
      boolean available, String details, Long expectedVersion) {
    return new UpsertEquipmentRequest(available, details, expectedVersion);
  }

  public static UpdateInventoryItemRequest updateQuantityTrackedRequest(long expectedVersion) {
    return new UpdateInventoryItemRequest(
        "Cheddar",
        "dairy",
        StorageLocation.FRIDGE,
        TrackingMode.QUANTITY,
        new BigDecimal("250.000"),
        "g",
        new BigDecimal("3.49"),
        null,
        false,
        LocalDate.parse("2026-06-01"),
        "cheese:cheddar",
        null,
        ItemSource.MANUAL_ADD,
        null,
        ItemLifecycleStatus.ACTIVE,
        null,
        expectedVersion);
  }

  // ---------------- Budget builders & request factories ----------------

  public static Budget.BudgetBuilder budget(UUID userId) {
    return Budget.builder()
        .id(UUID.randomUUID())
        .userId(userId)
        .weeklyTarget(new BigDecimal("75.00"))
        .currency("GBP")
        .toleranceOver(new BigDecimal("5.00"))
        .priceSensitivity(PriceSensitivity.moderate)
        .enabled(true);
  }

  public static UpdateBudgetRequest updateBudgetRequestForCreate() {
    return new UpdateBudgetRequest(
        new BigDecimal("75.00"),
        "GBP",
        new BigDecimal("5.00"),
        PriceSensitivity.moderate,
        true,
        0L);
  }

  public static UpdateBudgetRequest updateBudgetRequest(
      BigDecimal weeklyTarget,
      String currency,
      BigDecimal toleranceOver,
      PriceSensitivity priceSensitivity,
      Boolean enabled,
      Long expectedVersion) {
    return new UpdateBudgetRequest(
        weeklyTarget, currency, toleranceOver, priceSensitivity, enabled, expectedVersion);
  }

  // ---------------- Supplier-product builders & request factories ----------------

  public static SupplierProduct.SupplierProductBuilder supplierProduct(
      String supplier, String productId) {
    return SupplierProduct.builder()
        .id(UUID.randomUUID())
        .supplier(supplier)
        .productId(productId)
        .name("Onion, brown, medium, loose")
        .price(new BigDecimal("0.30"))
        .pricePerUnit(new BigDecimal("1.5000"))
        .unit("kg")
        .packSizeG(200)
        .packSizeUnit("pcs")
        .category("vegetables")
        .lastChecked(LocalDate.parse("2026-05-01"))
        .ingredientMappingKey("onion")
        .substitutionHistory(List.of());
  }

  public static UpsertSupplierProductRequest upsertSupplierProductRequest() {
    return new UpsertSupplierProductRequest(
        "tesco:567-onion-medium-loose",
        "tesco",
        "Onion, brown, medium, loose",
        new BigDecimal("0.30"),
        new BigDecimal("1.5000"),
        "kg",
        200,
        "pcs",
        "vegetables",
        null,
        LocalDate.parse("2026-05-01"),
        "onion");
  }

  public static SubstitutionRecordDto substitutionRecordDto(String substituteSku) {
    return new SubstitutionRecordDto(
        LocalDate.parse("2026-05-09"), substituteSku, true, "no red onion stock");
  }

  public static SubstitutionRecord substitutionRecord(String substituteSku) {
    return new SubstitutionRecord(
        LocalDate.parse("2026-05-09"), substituteSku, true, "no red onion stock");
  }

  public static RecordSubstitutionRequest recordSubstitutionRequest(
      String substituteSku, long expectedVersion) {
    return new RecordSubstitutionRequest(
        substitutionRecordDto(substituteSku), true, expectedVersion);
  }

  // ---------------- Waste-log builders & request factories ----------------

  public static WasteEntry.WasteEntryBuilder wasteEntry(UUID userId) {
    return WasteEntry.builder()
        .id(UUID.randomUUID())
        .userId(userId)
        .inventoryItemId(null)
        .itemName("Cheddar")
        .quantity(new BigDecimal("100.000"))
        .unit("g")
        .reason(WasteReason.EXPIRED)
        .costEstimate(new BigDecimal("2.50"))
        .occurredOn(LocalDate.parse("2026-05-08"))
        .notes(null);
  }

  /** Free-form waste log (no linked inventory item; quantity may be null). */
  public static LogWasteRequest logWasteRequestFreeForm() {
    return new LogWasteRequest(
        null,
        "Bunch of celery",
        null,
        null,
        WasteReason.EXPIRED,
        new BigDecimal("1.20"),
        LocalDate.parse("2026-05-08"),
        null);
  }

  /** Linked waste log for a quantity-tracked inventory item. */
  public static LogWasteRequest logWasteRequestLinkedQuantity(UUID inventoryItemId) {
    return new LogWasteRequest(
        inventoryItemId,
        "Cheddar",
        new BigDecimal("100.000"),
        "g",
        WasteReason.EXPIRED,
        new BigDecimal("2.50"),
        LocalDate.parse("2026-05-08"),
        "moulded on the back shelf");
  }

  /** Linked waste log for a status-tracked inventory item (no quantity, no unit). */
  public static LogWasteRequest logWasteRequestLinkedStatus(UUID inventoryItemId) {
    return new LogWasteRequest(
        inventoryItemId,
        "Salt",
        null,
        null,
        WasteReason.SPOILED_EARLY,
        null,
        LocalDate.parse("2026-05-08"),
        null);
  }

  // ---------------- Planner-bundle builders ----------------

  /**
   * Default {@link BundleStaleness} stub — non-zero coverage, in-ramp-up false, deterministic time.
   */
  public static BundleStaleness bundleStaleness() {
    return new BundleStaleness(10000, false, Instant.parse("2026-05-09T10:00:00Z"));
  }

  /** Empty-state planner-bundle stub — all collections empty, null budget, zero coverage. */
  public static ProvisionForPlannerBundleDto emptyPlannerBundle(UUID userId) {
    return new ProvisionForPlannerBundleDto(
        userId,
        List.of(),
        List.of(),
        List.of(),
        null,
        Map.of(),
        new BundleStaleness(0, false, Instant.parse("2026-05-09T10:00:00Z")));
  }

  /** Populated planner-bundle stub — caller supplies the inner sections. */
  public static ProvisionForPlannerBundleDto plannerBundle(
      UUID userId,
      List<InventoryItemDto> activeInventory,
      List<InventoryItemDto> staplesAtLowOrOut,
      List<EquipmentDto> equipment,
      BudgetDto budget,
      Map<String, SupplierProductDto> supplierPrices) {
    return new ProvisionForPlannerBundleDto(
        userId,
        activeInventory,
        staplesAtLowOrOut,
        equipment,
        budget,
        supplierPrices,
        bundleStaleness());
  }
}
